package app.heyrobot.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFallbackTest {
    private class Said : TextToSpeech {
        val lines = mutableListOf<String>()
        override fun speak(sentence: String) { lines += "robot: $sentence" }
        override fun speakOther(sentence: String, speaker: Int?, lang: String?) { lines += "other: $sentence" }
        override fun stop() {}
        override val isSpeaking = false
        override fun configure(rate: Float, voice: String) {}
        override fun setListener(listener: TextToSpeech.Listener?) {}
        override fun release() {}
    }

    @Test fun claudesSentencesKeepTheirOtherVoiceWhenTheModelFails() {
        val s = Said()
        replay(listOf("Checking with Claude." to false, "The sky is blue." to true, "Because air scatters light." to true), s)
        assertEquals(listOf("robot: Checking with Claude.", "other: The sky is blue.", "other: Because air scatters light."), s.lines)
    }
}
