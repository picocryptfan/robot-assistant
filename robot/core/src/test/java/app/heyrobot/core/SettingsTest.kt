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
        assertEquals(Approval.AUTO, d.approval(ToolName.OPEN_APP))
        assertEquals(Approval.ASK, d.approval(ToolName.SEND_TEXT))
    }

    @Test fun olderFilesOpenAppsAndMapsWithoutAsking() {
        val old = RobotSettings.fromJson("""{"schema":3,"approvals":{"open_app":"ASK","maps_search":"ASK","maps_navigate":"OFF","send_text":"ASK"}}""")
        assertEquals(Approval.AUTO, old.approval(ToolName.OPEN_APP))
        assertEquals(Approval.AUTO, old.approval(ToolName.MAPS_SEARCH))
        assertEquals(Approval.OFF, old.approval(ToolName.MAPS_NAVIGATE))
        assertEquals(Approval.ASK, old.approval(ToolName.SEND_TEXT))
        // From schema 4 on, "Ask first" is a choice and stays.
        val chosen = RobotSettings.fromJson("""{"schema":4,"approvals":{"open_app":"ASK"}}""")
        assertEquals(Approval.ASK, chosen.approval(ToolName.OPEN_APP))
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
        // No region of its own: the phone's (SIM, network, language), or none.
        assertEquals(null, d.phoneRegion)
        assertEquals("FR", d.regionFor("FR"))
        assertEquals("", d.regionFor(null))
        assertEquals("CA", d.copy(phoneRegion = "CA").regionFor("FR"))
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
        assertEquals(null, s.phoneRegion)
        assertEquals(null, s.emailApp)
        assertEquals(ToolCalls.MAX_EVENT_MINUTES, s.eventMinutes)
        // A region saved with this version is kept; "CA" from a file of an earlier version (which always wrote it) is
        // the old default, so it reads as unset, once: the next save writes the new layout.
        assertEquals("CA", RobotSettings.fromJson("""{"schema":2,"phoneRegion":"ca"}""").phoneRegion)
        assertEquals(null, RobotSettings.fromJson("""{"phoneRegion":"CA"}""").phoneRegion)
        assertEquals("FR", RobotSettings.fromJson("""{"phoneRegion":"FR"}""").phoneRegion)
        val migrated = RobotSettings.fromJson("""{"phoneRegion":"CA","speechRate":1.5}""")
        val json = migrated.toJson()
        assertEquals(RobotSettings.SCHEMA, json.getInt("schema"))
        assertEquals(false, json.has("phoneRegion"))
        assertEquals(migrated, RobotSettings.fromJson(json.toString()))
        // Chosen again after the migration: kept.
        assertEquals("CA", RobotSettings.fromJson(migrated.copy(phoneRegion = "CA").toJson().toString()).phoneRegion)
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
        assertEquals(Approval.AUTO, s.approval(ToolName.OPEN_APP))
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

    @Test fun peopleSettingsDefaultsAndRoundTrip() {
        // 0.4.0: Robot offers to remember who you pick until you turn that off; it learns from Cheogram's notifications
        // only once you turn that on.
        val d = RobotSettings()
        assertEquals(true, d.rememberPicks)
        assertEquals(false, d.learnFromCheogram)
        val s = d.copy(rememberPicks = false, learnFromCheogram = true)
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        assertEquals(true, RobotSettings.fromJson("""{"schema":4}""").rememberPicks)
        assertEquals(false, RobotSettings.fromJson("""{"schema":4}""").learnFromCheogram)
    }
}
