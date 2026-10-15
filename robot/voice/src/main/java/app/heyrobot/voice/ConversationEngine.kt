// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.os.Process
import android.os.SystemClock
import app.heyrobot.core.ConversationRules
import app.heyrobot.core.ConversationSpeakers
import app.heyrobot.core.Cut
import app.heyrobot.core.SegmentCutter
import app.heyrobot.core.FrameAssembler
import app.heyrobot.core.Heard
import app.heyrobot.core.LangGuess
import app.heyrobot.core.LangId
import app.heyrobot.core.LineLabeller
import app.heyrobot.core.LineLang
import app.heyrobot.core.MinuteStats
import app.heyrobot.core.RLog
import app.heyrobot.core.Rehear
import app.heyrobot.core.Segment
import app.heyrobot.core.SegmentRules
import app.heyrobot.core.Segmenter
import app.heyrobot.core.SpeakerAudio
import app.heyrobot.core.SpeakerTurn
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
// tells who said it (phase 5.3: the speech's voice embedding, compared with the session's speakers, when the speaker
// model is there and "Tell speakers apart" is on) and labels its language. Audio lives in the ring (20 s) and in the
// queued segments only, in memory, and is dropped once decoded; nothing is written anywhere. The queue holds at most [ConversationEngine.MAX_WAITING] segments (about
// 60 MB of audio at worst): should decoding fall that far behind, a new segment is let go and the session marks the gap.

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
 * was [heard] (after a re-hear, when [reheard]); its language; how long the decodes took; who said it ([speaker], null
 * for an empty line) and its [voice] embedding (null when none was made), which goes only to the session's speakers.
 */
class SegmentHeard(val seg: Segment, val offsetS: Double, val heard: Heard, val guess: LangGuess, val lang: LineLang, val reheard: Boolean,
    val decodeMs: Long, val speaker: SpeakerTurn? = null, val voice: FloatArray? = null)

class ConversationEngine(
    private val ctx: Context,
    private val decoder: SegmentDecoder,
    /** Your language, for a line nothing else tells the language of. */
    private val owner: String,
    private val out: Output,
    private val rules: SegmentRules = SegmentRules(),
    /** The session's speakers (phase 5.3); off: every line is S1, as before. */
    private val speakers: ConversationSpeakers = ConversationSpeakers(enabled = false),
    /** The speaker model, used only when [speakers] is on. */
    private val embedder: SpeakerEmbedding? = null,
) {
    interface Output {
        /** A segment was decoded (worker thread). Empty text is passed too, so the caller can count it. */
        fun segment(r: SegmentHeard)
        /** The open segment's words so far, for the screen (worker thread). */
        fun partial(text: String) {}
        /**
         * Decoding is [MAX_WAITING] segments behind (or stuck): [seg], from the run that began [offsetS] seconds into the
         * session, was let go unheard so memory stays bounded (the listener's thread; must not block).
         */
        fun dropped(seg: Segment, offsetS: Double) {}
    }

    /** [force]: the language of the push-to-talk side held while it was said (the interpreter), or null. */
    private class Job(val seg: Segment?, val offsetS: Double, val audio: FloatArray, val run: Int, val force: String? = null)

    /** The numbers for voice log events 104 and 105, taken once a minute by the session. */
    val stats = MinuteStats()
    /** Live words of the open segment (screen on, the setting on, the phone not hot): set by the session. */
    @Volatile var partials = false
    /**
     * Push-to-talk (the interpreter, docs/conversation-mode.md 5.2): while set, what is said takes this language and
     * skips language ID; a segment during which it was held at any moment keeps it.
     */
    @Volatile var forceLang: String? = null
    /** Under vadLock: the language held during the open segment. */
    private var heldLang: String? = null

    private val queue = LinkedBlockingDeque<Job>()
    private val waiting = AtomicInteger()
    @Volatile private var decoding = false
    @Volatile private var closed = false
    private var thread: Thread? = null
    private val vadLock = Any()
    private var vad: SpeechFlag? = null
    private val frames = FrameAssembler(rules.frameSamples)
    // The current run (from a start or a resume until a pause or stop), all guarded by vadLock.
    private var cutter = SegmentCutter(rules)
    private var offsetS = 0.0
    private var run = 0
    private var running = false
    private var lastPartialAt = 0L
    private val labeller: LineLabeller by lazy { LineLabeller(langId(ctx), owner) }

    /** Segments waiting to be decoded (the one being decoded included). */
    val queued: Int get() = waiting.get()

    private val voices: SpeakerEmbedding? = embedder.takeIf { speakers.enabled }
    @Volatile private var embeds = 0
    @Volatile private var embedMs = 0L

    /** Lines whose voice was embedded, and the mean time it took (ms), for the log at the end. */
    val embedStats: Pair<Int, Int> get() = embeds to (if (embeds == 0) 0 else (embedMs / embeds).toInt())

    /** Makes the voice detector and starts the worker. False when the detector can't be made. */
    fun start(): Boolean {
        val v = makeVad(ctx) ?: return false
        synchronized(vadLock) { vad = SpeechFlag(v) }
        decoder.hold(true)
        voices?.hold(true)
        thread = Thread({ work() }, "robot-conv").also { it.start() }
        return true
    }

    /** A run starts (the session starts, or goes on after a pause), [offsetS] seconds into the session. */
    fun beginRun(offsetS: Double) = synchronized(vadLock) {
        cutter = SegmentCutter(rules)
        frames.reset()
        vad?.reset()
        this.offsetS = offsetS
        heldLang = null
        run++
        running = true
        lastPartialAt = SystemClock.elapsedRealtime()
    }

    /** The run ends (pause, stop): the open segment is decoded with what was heard. */
    fun endRun() = synchronized(vadLock) {
        if (!running) return@synchronized
        running = false
        cutter.flush()?.let { enqueue(it) }
    }

    /** Microphone audio (the listener's thread). [robotSpeaking]: Robot's voice is playing, which counts as silence. */
    fun feed(x: FloatArray, robotSpeaking: Boolean) = synchronized(vadLock) {
        if (!running || closed) return@synchronized
        val v = vad ?: return@synchronized
        forceLang?.let { heldLang = it }
        frames.push(x) { f -> cutter.frame(f, v.frame(f), robotSpeaking)?.let { enqueue(it) } }
        maybePartial()
    }

    /** Under vadLock: a closed segment and its audio (sliced as it closed) on the queue, unless the queue is full. */
    private fun enqueue(c: Cut) {
        val force = heldLang ?: forceLang
        heldLang = forceLang
        if (waiting.get() >= MAX_WAITING) return out.dropped(c.seg, offsetS)
        waiting.incrementAndGet()
        queue.offer(Job(c.seg, offsetS, c.audio, run, force))
    }

    /** Under vadLock: the open segment's words every 1.5 s, only when nothing else waits. */
    private fun maybePartial() {
        if (!partials) return
        val now = SystemClock.elapsedRealtime()
        if (waiting.get() > 0 || decoding || now - lastPartialAt < ConversationRules.PARTIAL_MS) return
        val audio = cutter.openAudio() ?: return
        lastPartialAt = now
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
        // The model couldn't be used: -1 says so (the session stops after a few in a row).
        val (whole, ms) = decoder.decode(job.audio) ?: return out.segment(SegmentHeard(seg, job.offsetS, Heard(""), LangGuess(null, 0.0, 0, null),
            LineLang(owner, true), false, -1))
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
        // Who said it (docs/conversation-mode.md 3.2): the voice of a second or more of speech, compared with the
        // session's speakers; a shorter line inherits. Nothing for an empty line (it is not saved).
        var turn: SpeakerTurn? = null
        var voice: FloatArray? = null
        if (heard.text.isNotBlank()) {
            voice = voices?.let { e ->
                SpeakerAudio.speech(seg, job.audio)?.let { speech ->
                    val t0 = SystemClock.elapsedRealtime()
                    e.embed(speech).also {
                        embedMs += SystemClock.elapsedRealtime() - t0
                        embeds++
                    }
                }
            }
            val (a, b) = SpeakerAudio.clock(job.offsetS, seg, rules.sampleRate)
            turn = speakers.assign(voice, seg.speechMs, a, b)
        }
        val (guess, lang) = synchronized(labeller) { labeller.label(heard.text, turn?.let { speakers.usualLang(it.label) }, forced = job.force) }
        out.segment(SegmentHeard(seg, job.offsetS, heard, guess, lang, reheard, spent, turn, voice))
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
            vad?.release()
            vad = null
        }
        decoder.hold(false)
        voices?.hold(false)
    }

    /**
     * Silero's speech flag for one frame: its per-frame probability ([Vad.compute]) through [VadFlag]'s hysteresis, so
     * the Segmenter alone applies the 0.25 s rule. Should compute fail (it isn't in every sherpa-onnx build), the
     * detector's own flag instead, with a short minimum silence.
     */
    class SpeechFlag(private val v: Vad) {
        private val flag = VadFlag()
        private var direct = true

        fun frame(f: FloatArray): Boolean {
            if (direct) try {
                return flag.update(v.compute(f))
            } catch (t: Throwable) {
                RLog.w("conv_vad_compute", t)
                direct = false
                try { v.reset() } catch (_: Throwable) {}
            }
            return try {
                v.acceptWaveform(f)
                val sp = v.isSpeechDetected()
                while (!v.empty()) v.pop()
                sp
            } catch (t: Throwable) { false }
        }

        fun reset() {
            flag.reset()
            try { v.reset() } catch (t: Throwable) { RLog.w("conv_vad_reset", t) }
        }

        fun release() = try { v.release() } catch (_: Throwable) {}
    }

    companion object {
        /**
         * Segments waiting to be decoded, at most: 15.2 s of audio each at worst (about 60 MB in all), where a decode
         * takes well under a second. Reached only if decoding stalls; memory stays bounded meanwhile.
         */
        const val MAX_WAITING = 64

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
                // The minimums matter only if compute isn't there (SpeechFlag): short, so the Segmenter's own rules still decide.
                sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = 0.5f, minSilenceDuration = 0.1f,
                    minSpeechDuration = 0.1f, windowSize = Listener.FRAME, maxSpeechDuration = 60f),
                sampleRate = Listener.SAMPLE_RATE, numThreads = 1, debug = false,
            ))
        } catch (t: Throwable) {
            RLog.w("conv_vad", t)
            null
        }

        /**
         * "Your voice" (phase 5.3): only the frames of [samples] the voice detector hears as speech, joined, for the
         * enrolment's embedding (the taps and pauses around a sentence say nothing about a voice). Null when the
         * detector can't be made; empty when nobody spoke.
         */
        fun speechOnly(ctx: Context, samples: FloatArray): FloatArray? {
            val v = SpeechFlag(makeVad(ctx) ?: return null)
            try {
                val f = Listener.FRAME
                val out = FloatArray(samples.size)
                var n = 0
                var off = 0
                while (off + f <= samples.size) {
                    val frame = samples.copyOfRange(off, off + f)
                    if (v.frame(frame)) {
                        System.arraycopy(frame, 0, out, n, f)
                        n += f
                    }
                    off += f
                }
                return out.copyOf(n)
            } finally {
                v.release()
            }
        }

        /**
         * The Transcription check (Settings › Voice): a whole recording or clip decoded exactly as a conversation would be
         * (the same detector, segments and decoder), on the calling thread. The texts joined, the decode time, and the
         * language of what was heard. Null when the model or the detector can't be used.
         */
        fun transcribe(ctx: Context, decoder: SegmentDecoder, samples: FloatArray, owner: String): Triple<String, Long, LangGuess>? {
            val v = SpeechFlag(makeVad(ctx) ?: return null)
            decoder.hold(true)
            try {
                val rules = SegmentRules()
                val seg = Segmenter(rules)
                val segs = ArrayList<Segment>()
                var off = 0
                while (off + rules.frameSamples <= samples.size) {
                    val f = samples.copyOfRange(off, off + rules.frameSamples)
                    var ss = 0.0
                    for (s in f) ss += s * s
                    seg.feed(v.frame(f), Listener.db(ss / f.size).toFloat(), false)?.let { segs += it }
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
                v.release()
            }
        }
    }
}
