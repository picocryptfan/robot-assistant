package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStatusTest {
    @Test fun engineStatusRoundTrips() {
        val s = VoiceEngineStatus(mapOf(VoiceBundle.STT to EngineState.READY, VoiceBundle.KOKORO to EngineState.BROKEN), 123)
        val back = VoiceEngineStatus.fromJson(s.toJson().toString())!!
        assertEquals(s, back)
        assertEquals(EngineState.MISSING, back.state(VoiceBundle.KITTEN))
        assertNull(VoiceEngineStatus.fromJson("nope"))
        assertNull(VoiceEngineStatus.fromJson(null))
        assertEquals(EngineState.MISSING, VoiceEngineStatus.fromJson("""{"engines":{"stt-parakeet-v3":"EXPLODED"}}""")!!.state(VoiceBundle.STT))
    }

    @Test fun onlyWorkingStatesUseTheRealEngine() {
        assertEquals(setOf(EngineState.IDLE, EngineState.LOADING, EngineState.READY), EngineState.entries.filter { it.usable }.toSet())
    }

    private val kokoro = TtsBench(VoiceChoice.HEART, 900, 600, 1400, 4500, 2000)

    @Test fun benchRoundTripsAndKeepsNumbersOnly() {
        val b = VoiceBench(BenchState.DONE, "", 42, SttBench(3000, 5330, 400, 300, 100),
            listOf(kokoro, TtsBench(VoiceChoice.KITTEN, 400, 200, 500, 5000, 400)), 1300)
        val json = b.toJson().toString()
        assertEquals(b, VoiceBench.fromJson(json))
        assertNull(VoiceBench.fromJson("{"))
        assertEquals(0.075, b.stt!!.rtf, 0.001)
        assertEquals(0.444, kokoro.rtf, 0.001)
        assertTrue(VoiceBench.fromJson("""{"state":"RUNNING","step":"x","started":1}""")!!.voices.isEmpty())
    }

    @Test fun decisionRuleFromTheVoicePlan() {
        assertFalse(VoiceBenchLogic.kokoroTooSlow(kokoro))
        assertTrue(VoiceBenchLogic.kokoroTooSlow(kokoro.copy(genMs = 3200)))
        assertTrue(VoiceBenchLogic.kokoroTooSlow(kokoro.copy(firstChunkMs = 1201)))
        assertTrue(VoiceBenchLogic.kokoroTooSlow(kokoro.copy(audioMs = 0)))
        val lines = VoiceBenchLogic.lines(VoiceBench(BenchState.DONE, "", 1, SttBench(3000, 5330, 400, 300, 94), listOf(kokoro), 1300))
        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Speech recognition: 5.3 s of speech written down in 0.40 s"))
        assertTrue(lines[0], lines[0].contains("94% of the words right"))
        assertTrue(lines[1], lines[1].startsWith("Heart: first words after 0.60 s"))
        assertEquals("Kokoro is fast enough on this phone.", lines[2])
    }

    @Test fun wordAccuracyIgnoresCaseAndPunctuation() {
        val ref = "Please remind me to water the plants when I get home this evening, and turn on the flashlight."
        assertEquals(100, WordAccuracy.percent(ref, "please remind me to water the plants when I get home this evening and turn on the flashlight"))
        assertEquals(94, WordAccuracy.percent(ref, "Please remind me to water the plant when I get home this evening, and turn on the flashlight."))
        assertEquals(0, WordAccuracy.percent(ref, ""))
        assertEquals(0, WordAccuracy.percent("a b", "c d e f g"))
        assertEquals(listOf("it's", "10", "min"), WordAccuracy.words("It's 10-min!"))
    }
}
