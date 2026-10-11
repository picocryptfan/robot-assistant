package app.heyrobot.voice

import android.content.Context
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.ConversationRules
import app.heyrobot.core.FrameAssembler
import app.heyrobot.core.Heard
import app.heyrobot.core.LangGuess
import app.heyrobot.core.LangId
import app.heyrobot.core.LineLabeller
import app.heyrobot.core.LineLang
import app.heyrobot.core.MinuteStats
import app.heyrobot.core.RLog
import app.heyrobot.core.Rehear
import app.heyrobot.core.SampleRing
import app.heyrobot.core.Segment
import app.heyrobot.core.SegmentRules
import app.heyrobot.core.Segmenter
import app.heyrobot.core.VadFlag
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// VOICE PROCESS ONLY, :voice module only (sherpa-onnx). Conversation mode's speech to text (docs/conversation-mode.md
// 2.1-2.3, phase 5.2): microphone frames (16 kHz, from the shared Listener) → the gate while Robot talks → Silero's
// per-frame speech probability → the Segmenter's conversation rules (0.25 s of silence, 15 s at most, 0.2 s of padding
// from the session's own audio ring) → a queue → one worker that decodes each segment with the same Parakeet as
// questions (in turn with them), hears it again in two halves when too few words came back for the speech (event 119),
// and labels its language. Audio lives in the ring (20 s) and in the queued segments only, in memory, and is dropped
// once decoded; nothing is written anywhere.

/** Conversation mode's decoder: Parakeet v3, shared with questions (VoiceEngines.segmentDecoder). */
interface SegmentDecoder {
    val available: Boolean
    /** Keep the model loaded while a session runs. */
    fun hold(on: Boolean)
    /** Decodes [samples] (waits its turn); the words and the decode time in ms, or null when the model can't be used. */
    fun decode(samples: FloatArray): Pair<Heard, Long>?
}

/**
 * One decoded segment: [seg] in samples from the start of the run that began [offsetS] seconds into the session; what
 * was [heard] (after a re-hear, when [reheard]); its language; how long the decodes took.
 */
data class SegmentHeard(val seg: Segment, val offsetS: Double, val heard: Heard, val guess: LangGuess, val lang: LineLang, val reheard: Boolean,
    val decodeMs: Long)

class ConversationEngine(
    private val ctx: Context,
    private val decoder: SegmentDecoder,
    /** Your language, for a line nothing else tells the language of. */
    private val owner: String,
    private val out: Output,
    private val rules: SegmentRules = SegmentRules(),
) {
    interface Output {
        /** A segment was decoded (worker thread). Empty text is passed too, so the caller can count it. */
        fun segment(r: SegmentHeard)
        /** The open segment's words so far, for the screen (worker thread). */
        fun partial(text: String) {}
    }

    private class Job(val seg: Segment?, val offsetS: Double, val audio: FloatArray, val run: Int)

    /** The numbers for voice log events 104 and 105, taken once a minute by the session. */
    val stats = MinuteStats()
    /** Live words of the open segment (screen on, the setting on, the phone not hot): set by the session. */
    @Volatile var partials = false

    private val queue = LinkedBlockingDeque<Job>()
    private val waiting = AtomicInteger()
    @Volatile private var decoding = false
    @Volatile private var closed = false
    private var thread: Thread? = null
    private val vadLock = Any()
    private var vad: Vad? = null
    private val flag = VadFlag()
    private val frames = FrameAssembler(rules.frameSamples)
    // The current run (from a start or a resume until a pause or stop), all guarded by vadLock.
    private var segmenter = Segmenter(rules)
    private var ring = SampleRing(rules.sampleRate * ConversationRules.RING_MS / 1000)
    private var offsetS = 0.0
    private var run = 0
    private var running = false
    private var lastPartialAt = 0L
    private val labeller: LineLabeller by lazy { LineLabeller(langId(ctx), owner) }

    /** Segments waiting to be decoded (the one being decoded included). */
    val queued: Int get() = waiting.get()

    /** Makes the voice detector and starts the worker. False when the detector can't be made. */
    fun start(): Boolean {
        val v = makeVad(ctx) ?: return false
        synchronized(vadLock) { vad = v }
        decoder.hold(true)
        thread = Thread({ work() }, "robot-conv").also { it.start() }
        return true
    }

    /** A run starts (the session starts, or goes on after a pause), [offsetS] seconds into the session. */
    fun beginRun(offsetS: Double) = synchronized(vadLock) {
        segmenter = Segmenter(rules)
        ring = SampleRing(rules.sampleRate * ConversationRules.RING_MS / 1000)
        frames.reset()
        flag.reset()
        try { vad?.reset() } catch (t: Throwable) { RLog.w("conv_vad_reset", t) }
        this.offsetS = offsetS
        run++
        running = true
        lastPartialAt = SystemClock.elapsedRealtime()
    }

    /** The run ends (pause, stop): the open segment is decoded with what was heard. */
    fun endRun() = synchronized(vadLock) {
        if (!running) return@synchronized
        running = false
        segmenter.flush()?.let { enqueue(it) }
    }

    /** Microphone audio (the listener's thread). [robotSpeaking]: Robot's voice is playing, which counts as silence. */
    fun feed(x: FloatArray, robotSpeaking: Boolean) = synchronized(vadLock) {
        if (!running || closed) return@synchronized
        val v = vad ?: return@synchronized
        frames.push(x) { f ->
            ring.push(f)
            val p = try { v.compute(f) } catch (t: Throwable) { RLog.w("conv_vad", t); 0f }
            var ss = 0.0
            for (s in f) ss += s * s
            val level = Listener.db(ss / f.size).toFloat()
            segmenter.feed(flag.update(p), level, robotSpeaking)?.let { enqueue(it) }
        }
        maybePartial()
    }

    /** Under vadLock: the segment's audio, padding and all, sliced now (the ring moves on). */
    private fun enqueue(seg: Segment) {
        val audio = ring.slice(seg.start, seg.end)
        if (audio.isEmpty()) return
        waiting.incrementAndGet()
        queue.offer(Job(seg, offsetS, audio, run))
    }

    /** Under vadLock: the open segment's words every 1.5 s, only when nothing else waits. */
    private fun maybePartial() {
        if (!partials) return
        val open = segmenter.openStart ?: return
        val now = SystemClock.elapsedRealtime()
        if (waiting.get() > 0 || decoding || now - lastPartialAt < ConversationRules.PARTIAL_MS) return
        lastPartialAt = now
        val audio = ring.slice((open - rules.samples(rules.padMs)).coerceAtLeast(0), ring.end)
        if (audio.size < rules.sampleRate / 2) return
        queue.offer(Job(null, offsetS, audio, run))
    }

    private fun work() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
        while (true) {
            val job = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            if (job == null) {
                if (closed) break
                continue
            }
            decoding = true
            try {
                if (job.seg == null) partialOf(job) else segmentOf(job, job.seg)
            } catch (t: Throwable) {
                RLog.w("conv_decode", t)
            } finally {
                decoding = false
                if (job.seg != null) waiting.decrementAndGet()
            }
        }
    }

    private fun partialOf(job: Job) {
        // Stale once a segment is waiting, or the run it belongs to has ended.
        if (waiting.get() > 0 || job.run != run) return
        val (h, _) = decoder.decode(job.audio) ?: return
        if (waiting.get() == 0 && job.run == run && h.text.isNotBlank()) out.partial(h.text)
    }

    private fun segmentOf(job: Job, seg: Segment) {
        val (whole, ms) = decoder.decode(job.audio) ?: return out.segment(SegmentHeard(seg, job.offsetS, Heard(""), LangGuess(null, 0.0, 0, null),
            LineLang(owner, true), false, 0))
        var heard = whole
        var spent = ms
        var reheard = false
        // The dropped-words check (docs/conversation-mode.md 2.2): too few words for the speech heard.
        if (Rehear.needed(seg.speechMs, whole.words)) {
            val at = Rehear.splitAt(seg)
            if (at != null) {
                val cut = (at - seg.start).toInt().coerceIn(0, job.audio.size)
                val a = decoder.decode(job.audio.copyOfRange(0, cut))
                val b = decoder.decode(job.audio.copyOfRange(cut, job.audio.size))
                if (a != null && b != null) {
                    spent += a.second + b.second
                    val joined = Heard.join(a.first, b.first, cut.toDouble() / rules.sampleRate)
                    VoiceLog.add(VoiceEvent.CONV_REHEARD, seg.speechMs, whole.words, joined.words)
                    if (Rehear.keepHalves(whole.words, joined.words)) {
                        heard = joined
                        reheard = true
                    }
                }
            }
        }
        stats.segment(spent, waiting.get())
        val (guess, lang) = synchronized(labeller) { labeller.label(heard.text) }
        out.segment(SegmentHeard(seg, job.offsetS, heard, guess, lang, reheard, spent))
    }

    /** You fixed a line's language: the next short lines lean on it. */
    fun corrected(lang: String) {
        synchronized(labeller) { labeller.corrected(lang) }
    }

    /**
     * Stops: the open segment and every segment still waiting are decoded first (at most [drainMs]), so each line heard
     * is saved. Then the worker ends and the detector is freed.
     */
    fun close(drainMs: Long = ConversationRules.DRAIN_MS) {
        endRun()
        val until = SystemClock.elapsedRealtime() + drainMs
        while (waiting.get() > 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
        closed = true
        queue.clear()
        thread?.join(2_000)
        thread = null
        synchronized(vadLock) {
            try { vad?.release() } catch (_: Throwable) {}
            vad = null
        }
        decoder.hold(false)
    }

    companion object {
        @Volatile private var lid: LangId? = null

        /** The language table (core's asset), read once per process. */
        fun langId(ctx: Context): LangId = lid ?: synchronized(this) {
            lid ?: LangId.loadOrFallback { ctx.assets.open(LangId.ASSET) }.also { lid = it }
        }

        /**
         * Silero for conversation mode: only its per-frame probability is used ([Vad.compute]); the Segmenter applies the
         * conversation rules. Null if it can't be made.
         */
        fun makeVad(ctx: Context): Vad? = try {
            Vad(ctx.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f, windowSize = Listener.FRAME, maxSpeechDuration = 30f),
                sampleRate = Listener.SAMPLE_RATE, numThreads = 1, debug = false,
            ))
        } catch (t: Throwable) {
            RLog.w("conv_vad", t)
            null
        }

        /**
         * The Transcription check (Settings › Voice): a whole recording or clip decoded exactly as a conversation would be
         * (the same detector, segments and decoder), on the calling thread. The texts joined, the decode time, and the
         * language of what was heard. Null when the model or the detector can't be used.
         */
        fun transcribe(ctx: Context, decoder: SegmentDecoder, samples: FloatArray, owner: String): Triple<String, Long, LangGuess>? {
            val v = makeVad(ctx) ?: return null
            decoder.hold(true)
            try {
                val rules = SegmentRules()
                val seg = Segmenter(rules)
                val flag = VadFlag()
                val segs = ArrayList<Segment>()
                var off = 0
                while (off + rules.frameSamples <= samples.size) {
                    val f = samples.copyOfRange(off, off + rules.frameSamples)
                    var ss = 0.0
                    for (s in f) ss += s * s
                    seg.feed(flag.update(v.compute(f)), Listener.db(ss / f.size).toFloat(), false)?.let { segs += it }
                    off += rules.frameSamples
                }
                seg.flush()?.let { segs += it }
                var ms = 0L
                val texts = ArrayList<String>()
                for (s in segs) {
                    val audio = samples.copyOfRange(s.start.toInt().coerceIn(0, samples.size), s.end.toInt().coerceIn(0, samples.size))
                    val (h, t) = decoder.decode(audio) ?: return null
                    ms += t
                    var text = h.text
                    if (Rehear.needed(s.speechMs, h.words)) Rehear.splitAt(s)?.let { at ->
                        val cut = (at - s.start).toInt().coerceIn(0, audio.size)
                        val a = decoder.decode(audio.copyOfRange(0, cut))
                        val b = decoder.decode(audio.copyOfRange(cut, audio.size))
                        if (a != null && b != null) {
                            ms += a.second + b.second
                            val j = Heard.join(a.first, b.first, 0.0)
                            if (Rehear.keepHalves(h.words, j.words)) text = j.text
                        }
                    }
                    if (text.isNotBlank()) texts += text.trim()
                }
                val all = texts.joinToString(" ")
                return Triple(all, ms, langId(ctx).detect(all))
            } finally {
                decoder.hold(false)
                try { v.release() } catch (_: Throwable) {}
            }
        }
    }
}
