// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.tts.VoiceForAppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/**
 * The everyday set, E6 (everyday-set-plan.md §6.3): one request of another app's, from its pieces to the audio Android
 * plays. 16-bit little-endian mono in chunks Android takes, started once at the voice's rate; at most one piece made ahead;
 * where each piece will sound noted for the wake word's echo guard; a missing voice an error before any audio; a stop
 * ends it at once.
 */
class AppSpeechTest {
    private class Out(override val maxBytes: Int = 64, private val acceptWrites: Int = Int.MAX_VALUE) : AppAudioOut {
        val events = mutableListOf<String>()
        val bytes = mutableListOf<Byte>()
        var writes = 0
        override fun start(rate: Int): Boolean { events += "start $rate"; return true }
        override fun write(pcm: ByteArray, length: Int): Boolean {
            assertTrue(length in 2..maxBytes && length % 2 == 0)
            if (writes++ >= acceptWrites) return false
            for (i in 0 until length) bytes += pcm[i]
            return true
        }
        override fun done() { events += "done" }
        override fun error(e: AppError) { events += "error $e" }
    }

    private fun audio(n: Int, rate: Int = 24_000) = AppPiece.Audio(FloatArray(n) { 0.25f }, rate)

    private class Run(val pieces: Map<String, AppPiece>) {
        val submitted = mutableListOf<String>()
        val played = mutableListOf<Triple<String, Long, Long>>()
        var clock = 1_000L
        fun synth(p: String): Future<AppPiece> { submitted += p; return CompletableFuture.completedFuture(pieces.getValue(p)) }
        fun go(order: List<String>, out: AppAudioOut, stopped: () -> Boolean = { false }) =
            AppSpeech.run(order, ::synth, out, { t, s, e -> played += Triple(t, s, e) }, { clock }, stopped)
    }

    @Test fun piecesPlayInOrderStartedOnce() {
        val r = Run(mapOf("One." to audio(2_400), "Two." to audio(4_800)))
        val out = Out()
        assertEquals(VoiceForAppResult.SPOKEN, r.go(listOf("One.", "Two."), out))
        assertEquals(listOf("start 24000", "done"), out.events)
        assertEquals((2_400 + 4_800) * 2, out.bytes.size)
        // 2,400 samples at 24 kHz are 100 ms: the second piece sounds after the first.
        assertEquals(listOf(Triple("One.", 1_000L, 1_100L), Triple("Two.", 1_100L, 1_300L)), r.played)
        assertEquals(listOf("One.", "Two."), r.submitted)
    }

    @Test fun samplesBecome16BitLittleEndian() {
        val r = Run(mapOf("x" to AppPiece.Audio(floatArrayOf(1f, -1f, 0.5f, 2f, 0f), 24_000)))
        val out = Out(maxBytes = 4)
        r.go(listOf("x"), out)
        fun le(v: Int) = listOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        assertEquals(le(32_767) + le(-32_767) + le(16_383) + le(32_767) + le(0), out.bytes)
        // In chunks of at most 4 bytes: 3 writes for 5 samples.
        assertEquals(3, out.writes)
    }

    @Test fun aMissingVoiceIsAnErrorBeforeAnyAudio() {
        val out = Out()
        assertEquals(VoiceForAppResult.NOT_DOWNLOADED, Run(mapOf("a" to AppPiece.Missing)).go(listOf("a"), out))
        assertEquals(listOf("error NOT_INSTALLED"), out.events)
        // After some audio, the end of the request.
        val out2 = Out()
        assertEquals(VoiceForAppResult.FAILED, Run(mapOf("a" to audio(10), "b" to AppPiece.Missing)).go(listOf("a", "b"), out2))
        assertEquals(listOf("start 24000", "done"), out2.events)
    }

    @Test fun aFailedPieceIsSkipped() {
        val out = Out()
        assertEquals(VoiceForAppResult.SPOKEN, Run(mapOf("a" to AppPiece.Failed, "b" to audio(10))).go(listOf("a", "b"), out))
        assertEquals(listOf("start 24000", "done"), out.events)
        val out2 = Out()
        assertEquals(VoiceForAppResult.FAILED, Run(mapOf("a" to AppPiece.Failed)).go(listOf("a"), out2))
        assertEquals(listOf("error SYNTHESIS"), out2.events)
    }

    @Test fun nothingSayableIsAnEmptyUtteranceNotAnError() {
        val out = Out()
        assertEquals(VoiceForAppResult.NOTHING_TO_SAY, Run(emptyMap()).go(emptyList(), out))
        assertEquals(listOf("start ${AppSpeech.RATE}", "done"), out.events)
        val out2 = Out()
        assertEquals(VoiceForAppResult.NOTHING_TO_SAY, Run(mapOf("a" to audio(0))).go(listOf("a"), out2))
        assertEquals(listOf("start 24000", "done"), out2.events)
    }

    @Test fun aStopEndsItAtOnce() {
        // The app stops listening mid-piece: nothing more is written, and the piece made ahead is let go.
        val r = Run(mapOf("a" to audio(1_000), "b" to audio(1_000), "c" to audio(1_000)))
        val out = Out(acceptWrites = 3)
        assertEquals(VoiceForAppResult.STOPPED, r.go(listOf("a", "b", "c"), out))
        assertEquals(listOf("start 24000"), out.events)
        assertEquals(listOf("a", "b"), r.submitted)
        // Stopped before it began: nothing asked of the voice, nothing started.
        val r2 = Run(mapOf("a" to audio(10)))
        val out2 = Out()
        assertEquals(VoiceForAppResult.STOPPED, r2.go(listOf("a"), out2) { true })
        assertEquals(emptyList<String>(), r2.submitted)
        assertEquals(emptyList<String>(), out2.events)
        // The voice says the app stopped it.
        assertEquals(VoiceForAppResult.STOPPED, Run(mapOf("a" to AppPiece.Stopped)).go(listOf("a"), Out()))
    }

    @Test fun atMostOnePieceIsMadeAhead() {
        val pieces = (1..5).map { "p$it" }
        val submittedAtWrite = mutableListOf<Int>()
        val r = Run(pieces.associateWith { audio(100) })
        val out = object : AppAudioOut {
            override val maxBytes = 1_000
            override fun start(rate: Int) = true
            override fun write(pcm: ByteArray, length: Int): Boolean { submittedAtWrite += r.submitted.size; return true }
            override fun done() {}
            override fun error(e: AppError) {}
        }
        r.go(pieces, out)
        // Writing piece k (1-based), pieces up to k + 1 have been asked for, never more.
        assertEquals(listOf(2, 3, 4, 5, 5), submittedAtWrite)
    }
}
