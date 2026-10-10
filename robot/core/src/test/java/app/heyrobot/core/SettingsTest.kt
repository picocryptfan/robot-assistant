package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun defaultsMatchTheSpec() {
        val d = RobotSettings()
        assertEquals(true, d.wakeWord)
        assertEquals(false, d.wakeOnlyWhileCharging)
        assertEquals(SpeakReplies.VOICE, d.speakReplies)
        assertEquals(Approval.AUTO, d.approval(ToolName.SET_TIMER))
        assertEquals(Approval.AUTO, d.approval(ToolName.SET_ALARM))
        assertEquals(Approval.ASK, d.approval(ToolName.OPEN_APP))
    }

    @Test fun jsonRoundTrips() {
        val s = RobotSettings(
            wakeWord = false, wakeOnlyWhileCharging = true, speakReplies = SpeakReplies.ALWAYS, speechRate = 1.5f, voice = "en-us-x-abc", livePartials = false,
            keepModelMinutes = RobotSettings.KEEP_ALWAYS, saveHistory = false,
            approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.MAPS_SEARCH to Approval.OFF),
        )
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
    }

    @Test fun voiceChoiceDefaultsToKokoroHeart() {
        assertEquals(VoiceChoice.HEART, RobotSettings().voiceChoice)
        assertEquals(true, RobotSettings().livePartials)
        assertEquals(VoiceChoice.HEART, VoiceChoice.of("en-us-x-abc"))
        assertEquals(VoiceChoice.KITTEN, RobotSettings(voice = VoiceChoice.KITTEN.id).voiceChoice)
        // docs/voice-plan.md: af_heart is speaker 3, ff_siwis speaker 30 with espeak "fr"; Kitten's natural-paced voice is 7.
        assertEquals(3, VoiceChoice.HEART.speaker)
        assertEquals(30 to "fr", VoiceChoice.SIWIS.speaker to VoiceChoice.SIWIS.lang)
        assertEquals(VoiceBundle.KITTEN, VoiceChoice.KITTEN.bundle)
        assertEquals(null, VoiceChoice.SYSTEM.bundle)
        assertEquals(VoiceChoice.entries.size, VoiceChoice.entries.map { it.id }.distinct().size)
    }

    @Test fun badOrMissingValuesFallBackToDefaults() {
        assertEquals(RobotSettings(), RobotSettings.fromJson(null))
        assertEquals(RobotSettings(), RobotSettings.fromJson("garbage"))
        val s = RobotSettings.fromJson("""{"speakReplies":"SHOUT","speechRate":9,"keepModelMinutes":-7,"approvals":{"open_app":"MAYBE"}}""")
        assertEquals(SpeakReplies.VOICE, s.speakReplies)
        assertEquals(2.0f, s.speechRate)
        assertEquals(0, s.keepModelMinutes)
        assertEquals(Approval.ASK, s.approval(ToolName.OPEN_APP))
    }
}
