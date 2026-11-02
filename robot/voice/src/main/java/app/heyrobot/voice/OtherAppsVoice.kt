// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.os.SystemClock
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.tts.VoiceForAppResult
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

// VOICE PROCESS. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3): Robot's voices for other apps, as app
// tts/RobotTtsService asks for them. One request's pieces (core TtsText.forApp's) are made one at a time on Robot's own
// engine (SherpaTextToSpeech.submitForApp), the next one while the one before plays, and handed to Android as 16-bit mono
// in chunks it takes (AppAudioOut: the request's SynthesisCallback). Android plays them with the app's own audio settings:
// Robot asks for no audio focus and has no track of its own here. Nothing is kept: the samples live until they're handed
// over, and the text is never logged (ProcessIsolationTest voicesForAppsKeepNothing).

/** What one piece of another app's text came to. */
sealed interface AppPiece {
    /** Its samples at [rate] Hz (none: nothing sayable in it). */
    class Audio(val samples: FloatArray, val rate: Int) : AppPiece

    /** Its voice isn't downloaded (or couldn't load). */
    data object Missing : AppPiece

    /** The voice failed on it. */
    data object Failed : AppPiece

    /** The app stopped the request. */
    data object Stopped : AppPiece
}

/** Why a request couldn't be spoken, as RobotTtsService tells Android (its TextToSpeech.ERROR_ codes). */
enum class AppError { NOT_INSTALLED, SYNTHESIS }

/** Where another app's audio goes: the request's SynthesisCallback in RobotTtsService, a fake in the tests. */
interface AppAudioOut {
    /** The most bytes one [write] may hand over. */
    val maxBytes: Int

    /** Starts the audio: [rate] Hz, 16-bit, mono. False when Android refused (the request is over). */
    fun start(rate: Int): Boolean

    /** The first [length] bytes of [pcm]; false when the app stopped listening. */
    fun write(pcm: ByteArray, length: Int): Boolean

    fun done()

    fun error(e: AppError)
}

/** Robot's voices as RobotTtsService uses them: on the engine Robot's own voice uses (VoiceEngines.otherApps). */
class OtherAppsVoice internal constructor(private val engine: SherpaTextToSpeech) {
    /** [c]'s model is downloaded, checked and not broken. */
    fun usable(c: VoiceChoice): Boolean = engine.usable(c)

    /**
     * Speaks [pieces] in [voice] at [speed] into [out], blocking until the last sample is handed over or [stopped]. How it
     * ended, for the voice log.
     */
    fun speak(voice: VoiceChoice, speed: Float, pieces: List<String>, out: AppAudioOut, stopped: () -> Boolean): VoiceForAppResult {
        engine.appRequests.incrementAndGet()
        try {
            return AppSpeech.run(pieces, { p -> engine.submitForApp(voice, speed, p, stopped) }, out, engine::appPlayed, SystemClock::elapsedRealtime, stopped)
        } finally {
            engine.appRequests.decrementAndGet()
        }
    }
}

/** Pure. One request, from its pieces to the audio handed over (OtherAppsVoice; AppSpeechTest drives it with fakes). */
object AppSpeech {
    /** The rate when no piece brought one (nothing sayable at all): Kokoro's and Kitten's. */
    const val RATE = SherpaTextToSpeech.SAMPLE_RATE

    /**
     * [pieces] in order: each made by [synth] (the next one asked for once the one before is ready, so at most two wait on
     * Robot's engine), noted by [played] where it will sound ([now] in ms: it starts when handed over or when the one
     * before ends), then handed to [out]. A piece that fails is skipped; a voice that is missing before any audio is an
     * error to the app, after some audio the end of it.
     */
    fun run(pieces: List<String>, synth: (String) -> Future<AppPiece>, out: AppAudioOut, played: (String, Long, Long) -> Unit, now: () -> Long,
        stopped: () -> Boolean): VoiceForAppResult {
        var started = false
        var spoke = false
        var failed = false
        var playEnd = Long.MIN_VALUE
        var next: Future<AppPiece>? = if (pieces.isEmpty() || stopped()) null else synth(pieces[0])
        var i = 0
        try {
            while (next != null) {
                val piece = when (val r = wait(next)) {
                    is AppPiece.Audio -> r
                    AppPiece.Stopped -> return VoiceForAppResult.STOPPED
                    AppPiece.Missing -> {
                        if (started) { out.done(); return VoiceForAppResult.FAILED }
                        out.error(AppError.NOT_INSTALLED)
                        return VoiceForAppResult.NOT_DOWNLOADED
                    }
                    AppPiece.Failed -> { failed = true; null }
                }
                val text = pieces[i++]
                next = if (i < pieces.size && !stopped()) synth(pieces[i]) else null
                if (piece == null) continue
                if (!started) {
                    if (!out.start(piece.rate)) return VoiceForAppResult.FAILED
                    started = true
                }
                if (piece.samples.isEmpty()) continue
                val t = now()
                val start = maxOf(t, playEnd)
                val end = start + piece.samples.size * 1_000L / piece.rate
                playEnd = end
                played(text, start, end)
                if (!feed(piece.samples, out, stopped)) return VoiceForAppResult.STOPPED
                spoke = true
            }
            if (stopped()) return VoiceForAppResult.STOPPED
            if (!started) {
                if (failed) {
                    out.error(AppError.SYNTHESIS)
                    return VoiceForAppResult.FAILED
                }
                // Nothing sayable: an empty utterance, done (a screen reader reading an emoji gets no error).
                if (!out.start(RATE)) return VoiceForAppResult.FAILED
            }
            out.done()
            return when {
                spoke -> VoiceForAppResult.SPOKEN
                failed -> VoiceForAppResult.FAILED
                else -> VoiceForAppResult.NOTHING_TO_SAY
            }
        } finally {
            next?.cancel(false)
        }
    }

    private fun wait(f: Future<AppPiece>): AppPiece = try {
        f.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        AppPiece.Stopped
    } catch (_: ExecutionException) {
        AppPiece.Failed
    } catch (_: java.util.concurrent.CancellationException) {
        AppPiece.Stopped
    }

    /** [samples] as 16-bit little-endian PCM, in chunks of at most [AppAudioOut.maxBytes]; false when stopped. */
    private fun feed(samples: FloatArray, out: AppAudioOut, stopped: () -> Boolean): Boolean {
        val chunk = (out.maxBytes.coerceIn(2, 1 shl 20)) and 1.inv()
        val buf = ByteArray(chunk)
        var i = 0
        while (i < samples.size) {
            if (stopped()) return false
            var n = 0
            while (n < chunk && i < samples.size) {
                val v = (samples[i++].coerceIn(-1f, 1f) * 32_767f).toInt()
                buf[n++] = (v and 0xFF).toByte()
                buf[n++] = ((v shr 8) and 0xFF).toByte()
            }
            if (!out.write(buf, n)) return false
        }
        return true
    }
}

/** A growing array of samples, at most [MAX] (2 minutes at 24 kHz: a 400-character piece at half speed fits). */
internal class AppSamples {
    private var a = FloatArray(24_000)
    var size = 0
        private set

    /** Adds [x]; false once full (the rest of the piece is dropped and sherpa told to stop). */
    fun add(x: FloatArray): Boolean {
        val n = minOf(x.size, MAX - size)
        if (size + n > a.size) a = a.copyOf(maxOf(size + n, minOf(MAX, a.size * 2)))
        System.arraycopy(x, 0, a, size, n)
        size += n
        return size < MAX
    }

    fun toArray(): FloatArray = a.copyOf(size)

    companion object {
        const val MAX = 120 * SherpaTextToSpeech.SAMPLE_RATE
    }
}
