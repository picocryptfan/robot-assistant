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
            approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.MAPS_SEARCH to Approval.OFF) + (ToolName.SEND_TEXT to Approval.AUTO),
            textApp = TextApp.SMS, smsGateway = "sms.example.org", phoneRegion = "FR", emailApp = EmailApp.ASK, eventCalendar = 7, eventMinutes = 30,
        )
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        // Not chosen yet stays not chosen.
        assertEquals(RobotSettings(), RobotSettings.fromJson(RobotSettings().toJson().toString()))
    }

    @Test fun phaseTwoDefaults() {
        val d = RobotSettings()
        assertEquals("cheogram.com", d.smsGateway)
        assertEquals("CA", d.phoneRegion)
        assertEquals(60, d.eventMinutes)
        assertEquals(CalendarPick.AUTO, d.eventCalendar)
        // Cheogram (Proton Mail) when installed, else the SMS app (the chooser); a choice made sticks.
        assertEquals(TextApp.CHEOGRAM, d.textAppFor(cheogramInstalled = true))
        assertEquals(TextApp.SMS, d.textAppFor(cheogramInstalled = false))
        assertEquals(TextApp.OFF, d.copy(textApp = TextApp.OFF).textAppFor(true))
        assertEquals(EmailApp.PROTON, d.emailAppFor(protonInstalled = true))
        assertEquals(EmailApp.ASK, d.emailAppFor(protonInstalled = false))
        assertEquals(EmailApp.ASK, d.copy(emailApp = EmailApp.ASK).emailAppFor(true))
        for (t in listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT)) assertEquals(Approval.ASK, d.approval(t))
        for (t in listOf(ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) assertEquals(Approval.AUTO, d.approval(t))
    }

    @Test fun badPhaseTwoValuesFallBack() {
        val s = RobotSettings.fromJson("""{"textApp":"PIGEON","smsGateway":"not a domain","phoneRegion":"Canada","emailApp":"","eventMinutes":100000}""")
        assertEquals(null, s.textApp)
        assertEquals("cheogram.com", s.smsGateway)
        assertEquals("CA", s.phoneRegion)
        assertEquals(null, s.emailApp)
        assertEquals(ToolCalls.MAX_EVENT_MINUTES, s.eventMinutes)
        assertEquals("ca", RobotSettings.fromJson("""{"phoneRegion":"ca"}""").phoneRegion.lowercase())
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

    @Test fun voiceReliabilityDefaultsAndRoundTrip() {
        val d = RobotSettings()
        assertEquals(listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true)), d.wakePhrases)
        assertEquals(BargeIn.ECHO_CANCEL, d.bargeIn)
        assertEquals(Playback.ASSISTANT, d.playback)
        assertEquals(StopKey.VOLUME_DOWN, d.stopKey)
        val s = d.copy(
            wakePhrases = listOf(WakePhrase(1, "hey robot"), WakePhrase(3, "robot", WakeSensitivity.LOW, enabled = false), WakePhrase(4, "hey y'all", WakeSensitivity.HIGH)),
            bargeIn = BargeIn.ALWAYS, playback = Playback.CALL, stopKey = StopKey.EITHER,
        )
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        // Settings from 0.2.2 (no such keys) get the defaults; an emptied list stays empty.
        assertEquals(d, RobotSettings.fromJson("{}"))
        assertEquals(emptyList<WakePhrase>(), RobotSettings.fromJson(d.copy(wakePhrases = emptyList()).toJson().toString()).wakePhrases)
        // A damaged or invalid entry is dropped, never fatal.
        val j = """{"wakePhrases":[{"id":1,"text":"hey robot"},{"id":2,"text":"stop"},"x",{"id":5,"text":"jarvis","sensitivity":"NOPE"}],"bargeIn":"NOPE"}"""
        val back = RobotSettings.fromJson(j)
        assertEquals(listOf(WakePhrase(1, "hey robot"), WakePhrase(5, "jarvis", WakeSensitivity.MEDIUM)), back.wakePhrases)
        assertEquals(BargeIn.ECHO_CANCEL, back.bargeIn)
    }
}
