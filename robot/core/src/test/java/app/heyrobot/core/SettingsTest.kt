package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(true, d.directAnswers)
    }

    /** Settings › "Direct answers": on by default, also for a file saved before it existed; off stays off. */
    @Test fun directAnswersRoundTrip() {
        assertEquals(true, RobotSettings.fromJson("""{"schema":5,"speechRate":1.5}""").directAnswers)
        assertEquals(true, RobotSettings.fromJson("""{"schema":3}""").directAnswers)
        val off = RobotSettings(directAnswers = false)
        assertEquals(false, off.toJson().getBoolean("directAnswers"))
        assertEquals(off, RobotSettings.fromJson(off.toJson().toString()))
        assertEquals(true, RobotSettings.fromJson(RobotSettings().toJson().toString()).directAnswers)
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
        // Settings from 0.2.2 (no such keys) get the defaults (and, as an update, the one-time line about Safety & privacy);
        // an emptied list stays empty.
        assertEquals(d.copy(safetyNotice = true), RobotSettings.fromJson("{}"))
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

    @Test fun conversationDefaultsMatchThePlan() {
        val d = RobotSettings()
        assertEquals(LangPair.FR_EN, d.convPairChoice)
        assertEquals(false, d.convTranslate)
        assertEquals(null, d.convSpeak)
        assertEquals(true, d.convPartials)
        assertEquals(6, d.convMaxSpeakers)
        assertEquals(false, d.convWakeDuring)
        assertEquals(true, d.convStartLocked)
        assertEquals(false, d.convKeepAudio)
        assertEquals(null, d.convSummaryLang)
        assertEquals(true, d.convAutoOfferHelper)
        assertEquals(false, d.meVoiceprint)
        // Each face's defaults: the Interpreter translates, speaks and tells two people apart.
        assertEquals(true, ConversationSettings.translate(d, ConvMode.INTERPRET))
        assertEquals(false, ConversationSettings.translate(d, ConvMode.TRANSCRIBE))
        assertEquals(true, ConversationSettings.speak(d, ConvMode.INTERPRET))
        assertEquals(false, ConversationSettings.speak(d, ConvMode.TRANSCRIBE))
        assertEquals(false, ConversationSettings.speak(d.copy(convSpeak = false), ConvMode.INTERPRET))
        assertEquals(2, ConversationSettings.maxSpeakers(d, ConvMode.INTERPRET))
        assertEquals(6, ConversationSettings.maxSpeakers(d, ConvMode.TRANSCRIBE))
        assertEquals("fr", ConversationSettings.summaryLang(d, java.util.Locale.CANADA_FRENCH))
        assertEquals("en", ConversationSettings.summaryLang(d.copy(convSummaryLang = "en"), java.util.Locale.CANADA_FRENCH))
        assertEquals(false, ConversationSettings.partials(d, screenOn = false, warm = false))
        assertEquals(false, ConversationSettings.partials(d, screenOn = true, warm = true))
    }

    @Test fun conversationSettingsRoundTripAndMigrate() {
        val s = RobotSettings(convTranslate = true, convSpeak = false, convPartials = false, convMaxSpeakers = 3, convWakeDuring = true,
            convStartLocked = false, convKeepAudio = true, convSummaryLang = "fr", convAutoOfferHelper = false, meVoiceprint = true)
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        assertEquals(8, RobotSettings.SCHEMA)
        assertEquals(8, s.toJson().getInt("schema"))
        // A 0.4.0-era file (schema 5) gets the defaults, except that live words in a conversation follow live words for questions.
        val old = RobotSettings.fromJson("""{"schema":5,"livePartials":false,"speechRate":1.2}""")
        assertEquals(false, old.convPartials)
        assertEquals(RobotSettings().copy(livePartials = false, speechRate = 1.2f, convPartials = false, safetyNotice = true), old)
        assertEquals(true, RobotSettings.fromJson("""{"schema":5}""").convPartials)
        // A 0.4.0 file (schema 6) too; from schema 8 on, the two are separate choices.
        assertEquals(false, RobotSettings.fromJson("""{"schema":6,"livePartials":false}""").convPartials)
        assertEquals(true, RobotSettings.fromJson("""{"schema":8,"livePartials":false}""").convPartials)
        // Out of range or unknown values fall back.
        val bad = RobotSettings.fromJson("""{"schema":6,"convPair":"fr-xx","convMaxSpeakers":40,"convSummaryLang":"de"}""")
        assertEquals(LangPair.FR_EN.id, bad.convPair)
        assertEquals(6, bad.convMaxSpeakers)
        assertEquals(null, bad.convSummaryLang)
        assertEquals(2, RobotSettings.fromJson("""{"convMaxSpeakers":1}""").convMaxSpeakers)
    }

    /** 5.2 adds "Show the reminder again" without a new schema (7 belongs to another branch): absent reads as never. */
    @Test fun theConsentReminderSettingStaysInSchemaEight() {
        assertEquals(0L, RobotSettings().convReminderAt)
        val s = RobotSettings(convReminderAt = 1_760_000_000_000L)
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        assertEquals(8, s.toJson().getInt("schema"))
        // Not written while unset, so a file from 5.1 and one from today read the same.
        assertTrue(!RobotSettings().toJson().has("convReminderAt"))
        assertEquals(0L, RobotSettings.fromJson("""{"schema":8,"convWakeDuring":true}""").convReminderAt)
        assertEquals(0L, RobotSettings.fromJson("""{"schema":5}""").convReminderAt)
        assertEquals(0L, RobotSettings.fromJson("""{"schema":8,"convReminderAt":-5}""").convReminderAt)
        assertEquals(RobotSettings().copy(convWakeDuring = true), RobotSettings.fromJson("""{"schema":8,"convWakeDuring":true}"""))
    }

    /**
     * 5.3 adds "Tell speakers apart" without a new schema: absent reads as "on once the speaker model is downloaded";
     * the voiceprint setting stays a flag.
     */
    @Test fun speakerLabelsStayInSchemaEight() {
        assertEquals(null, RobotSettings().convSpeakers)
        for (v in listOf(true, false)) {
            val s = RobotSettings(convSpeakers = v)
            assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
            assertEquals(8, s.toJson().getInt("schema"))
        }
        // Not written while unset, so a file from 5.2 and one from today read the same.
        assertTrue(!RobotSettings().toJson().has("convSpeakers"))
        assertEquals(null, RobotSettings.fromJson("""{"schema":8,"convMaxSpeakers":4}""").convSpeakers)
        assertEquals(null, RobotSettings.fromJson("""{"schema":5}""").convSpeakers)
        assertEquals(RobotSettings().copy(convMaxSpeakers = 4), RobotSettings.fromJson("""{"schema":8,"convMaxSpeakers":4}"""))
        assertEquals(false, RobotSettings.fromJson("""{"schema":8,"convSpeakers":false}""").convSpeakers)
        // On once downloaded unless you turned it off; the download is offered unless you did.
        val d = RobotSettings()
        assertEquals(true, ConversationSettings.speakers(d, modelReady = true))
        assertEquals(false, ConversationSettings.speakers(d, modelReady = false))
        assertEquals(false, ConversationSettings.speakers(d.copy(convSpeakers = false), modelReady = true))
        assertEquals(true, ConversationSettings.offerSpeakerModel(d, modelReady = false))
        assertEquals(true, ConversationSettings.offerSpeakerModel(d.copy(convSpeakers = true), modelReady = false))
        assertEquals(false, ConversationSettings.offerSpeakerModel(d.copy(convSpeakers = false), modelReady = false))
        assertEquals(false, ConversationSettings.offerSpeakerModel(d, modelReady = true))
        // The voiceprint is never in settings: only whether there is one.
        val withMe = RobotSettings(meVoiceprint = true).toJson()
        assertEquals(true, withMe.getBoolean("meVoiceprint"))
        assertTrue(withMe.keys().asSequence().none { it.contains("voiceprint", ignoreCase = true) && it != "meVoiceprint" })
        // The Interpreter tells two people apart; the transcript up to the setting.
        assertEquals(2, ConversationSettings.maxSpeakers(d.copy(convMaxSpeakers = 6), ConvMode.INTERPRET))
        assertEquals(4, ConversationSettings.maxSpeakers(d.copy(convMaxSpeakers = 4), ConvMode.TRANSCRIBE))
    }

    @Test fun theConsentReminderShowsUntilTickedAfterItWasAskedFor() {
        val d = RobotSettings()
        assertTrue(ConsentRecord.needed(null, d))
        assertTrue(!ConsentRecord.needed(ConsentRecord(5_000), d))
        // "Show the reminder again" after the tick: needed; a newer tick: not.
        assertTrue(ConsentRecord.needed(ConsentRecord(5_000), d.copy(convReminderAt = 6_000)))
        assertTrue(!ConsentRecord.needed(ConsentRecord(7_000), d.copy(convReminderAt = 6_000)))
        assertEquals(ConsentRecord(7_000), ConsentRecord.fromJson(ConsentRecord(7_000).toJson()))
        assertEquals(null, ConsentRecord.fromJson("""{"at":0}"""))
        assertEquals(null, ConsentRecord.fromJson("nope"))
        assertTrue(ConsentRecord.TEXT.startsWith("Before you transcribe or translate a conversation, tell the people you're talking with."))
        // The lock screen: start only when allowed; a locked screen shows nothing older.
        assertTrue(ConversationLock.mayStart(locked = true, d))
        assertTrue(!ConversationLock.mayStart(locked = true, d.copy(convStartLocked = false)))
        assertTrue(ConversationLock.mayStart(locked = false, d.copy(convStartLocked = false)))
        assertTrue(!ConversationLock.showsEarlier(locked = true))
    }

    /**
     * Settings › Safety & privacy (schema 6): the switches default to today's behaviour, except "Safety cards", whose new
     * default ("When you didn't ask for it") the person asked for, older files included.
     */
    @Test fun safetySettingsDefaultsMigrationAndRoundTrip() {
        val d = RobotSettings()
        assertEquals(SafetyCards.UNASKED, d.safetyCards)
        assertEquals(true, d.claudeActionCards)
        assertEquals(true, d.whoCards)
        assertEquals(true, d.learnedCards)
        assertEquals(true, d.deleteCards)
        assertEquals(setOf(ToolName.SET_TIMER, ToolName.SET_ALARM), d.lockedTools)
        assertEquals(false, d.personalToHelpers)
        assertEquals(false, d.voiceYesPersonal)
        assertEquals(true, d.voiceCards)
        assertEquals(false, RobotSettings.fromJson(d.copy(voiceCards = false).toJson().toString()).voiceCards)
        assertEquals(8, RobotSettings.SCHEMA)
        // A 0.3 or early 0.4 file: every rule as it was, and the new Safety cards default.
        for (old in listOf("""{"schema":5,"speechRate":1.5}""", """{"schema":3}""", "{}")) {
            val o = RobotSettings.fromJson(old)
            assertEquals(old, SafetyCards.UNASKED, o.safetyCards)
            assertEquals(old, listOf(true, true, true, true), listOf(o.claudeActionCards, o.whoCards, o.learnedCards, o.deleteCards))
            assertEquals(old, LockScreen.DEFAULT, o.lockedTools)
            assertEquals(old, false, o.personalToHelpers || o.voiceYesPersonal)
        }
        val s = d.copy(safetyCards = SafetyCards.AFTER_READING, claudeActionCards = false, whoCards = false, learnedCards = false, deleteCards = false,
            lockedTools = setOf(ToolName.FLASHLIGHT, ToolName.SET_TIMER), personalToHelpers = true, voiceYesPersonal = true)
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        assertEquals(RobotSettings.SCHEMA, s.toJson().getInt("schema"))
        // Emptied stays empty (only questions on the lock screen); unknown or internal tools are dropped; a bad mode is the default.
        assertEquals(emptySet<ToolName>(), RobotSettings.fromJson(d.copy(lockedTools = emptySet()).toJson().toString()).lockedTools)
        // The one-time line about Safety & privacy: for an update (a file from before schema 6), never a new install.
        assertEquals(false, d.safetyNotice)
        for (old in listOf("""{"schema":5,"speechRate":1.5}""", """{"schema":3}""", """{"schema":1}""")) assertEquals(old, true, RobotSettings.fromJson(old).safetyNotice)
        assertEquals(false, RobotSettings.fromJson(null).safetyNotice)
        assertEquals(false, RobotSettings.fromJson("""{"schema":6}""").safetyNotice)
        val updated = RobotSettings.fromJson("""{"schema":5}""")
        assertEquals(true, RobotSettings.fromJson(updated.toJson().toString()).safetyNotice)
        assertEquals(false, RobotSettings.fromJson(updated.copy(safetyNotice = false).toJson().toString()).safetyNotice)
        assertEquals("New: Settings › Safety & privacy. Cards now appear when you didn't ask for an action.", SafetyNotice.TEXT)
        // "Find the nearest place online": Ask the first time for new installs and older files; an early 0.4.0 switch that
        // was on (or absent) asks the first time too, one turned off is Never; the three choices round-trip.
        assertEquals(NearestOnline.ASK, d.nearestOnline)
        for (old in listOf("""{"schema":5}""", """{"schema":6,"nearestOnline":true}""", "{}")) assertEquals(old, NearestOnline.ASK, RobotSettings.fromJson(old).nearestOnline)
        assertEquals(NearestOnline.NEVER, RobotSettings.fromJson("""{"schema":6,"nearestOnline":false}""").nearestOnline)
        for (m in NearestOnline.entries) {
            val withIt = d.copy(nearestOnline = m, nearestOnlineAt = 1_700_000_000_000)
            assertEquals(withIt, RobotSettings.fromJson(withIt.toJson().toString()))
        }
        assertEquals(NearestOnline.ASK, RobotSettings.fromJson("""{"schema":6,"nearestPlace":"SOMETIMES","nearestOnline":false}""").nearestOnline)
        val bad = RobotSettings.fromJson("""{"schema":6,"lockedTools":["flashlight","ask_claude","teleport"],"safetyCards":"NEVER"}""")
        assertEquals(setOf(ToolName.FLASHLIGHT), bad.lockedTools)
        assertEquals(SafetyCards.UNASKED, bad.safetyCards)
    }

    /**
     * Settings › Model (schema 7, 0.4.1): Fast by default; a file from 0.4.0 or earlier (one model, the TPU's E2B) reads
     * as Fast; a choice saved with 0.4.1 is kept; an unknown one is Fast.
     */
    @Test fun modelChoiceDefaultsMigrationAndRoundTrip() {
        assertEquals(BrainModel.FAST, RobotSettings().brainModelChoice)
        assertEquals("e2b_tpu", RobotSettings().toJson().getString("brainModel"))
        for (old in listOf("""{"schema":6,"speechRate":1.5}""", """{"schema":5}""", """{"schema":3}""", "{}", """{"schema":6,"brainModel":"e4b_cpu"}""")) {
            assertEquals(old, BrainModel.FAST, RobotSettings.fromJson(old).brainModelChoice)
        }
        // An old file keeps everything else, and the next save writes schema 8 (0.5.0) with Fast.
        val migrated = RobotSettings.fromJson("""{"schema":6,"speechRate":1.5,"keepModelMinutes":15}""")
        assertEquals(1.5f, migrated.speechRate)
        assertEquals(15, migrated.keepModelMinutes)
        assertEquals(8, migrated.toJson().getInt("schema"))
        assertEquals("e2b_tpu", migrated.toJson().getString("brainModel"))
        for (m in BrainModel.entries) {
            val s = RobotSettings(brainModel = m.id)
            assertEquals(m, RobotSettings.fromJson(s.toJson().toString()).brainModelChoice)
            assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        }
        assertEquals(BrainModel.FAST, RobotSettings.fromJson("""{"schema":7,"brainModel":"horizon-edge-4b"}""").brainModelChoice)
        assertEquals(BrainModel.FAST, RobotSettings.fromJson("""{"schema":7}""").brainModelChoice)
        assertEquals(BrainModel.UNCENSORED, RobotSettings.fromJson("""{"schema":7,"brainModel":"e4b_cpu_uncensored"}""").brainModelChoice)
        // An unknown id is saved back as Fast's.
        assertEquals("e2b_tpu", RobotSettings(brainModel = "nope").toJson().getString("brainModel"))
    }

    /**
     * The merge of 0.4.3 into 0.5.0: schema 8 holds both. A 0.4.3 file (schema 7) keeps its model and gets conversation
     * mode's defaults (live words following live words for questions, as any file before 8); a 0.5.0 file from before the
     * merge (schema 8, no model) reads as Fast; a 0.4.0 file (schema 6) gets both migrations.
     */
    @Test fun schemaEightKeepsTheModelChoiceAndConversationSettings() {
        val from043 = RobotSettings.fromJson("""{"schema":7,"brainModel":"e4b_cpu","livePartials":false}""")
        assertEquals(BrainModel.of("e4b_cpu"), from043.brainModelChoice)
        assertEquals(false, from043.convPartials)
        assertEquals(null, from043.convSpeakers)
        assertEquals(0L, from043.convReminderAt)
        val from050 = RobotSettings.fromJson("""{"schema":8,"convTranslate":true,"livePartials":false}""")
        assertEquals(BrainModel.FAST, from050.brainModelChoice)
        assertEquals(true, from050.convTranslate)
        assertEquals(true, from050.convPartials)
        val from040 = RobotSettings.fromJson("""{"schema":6,"brainModel":"e4b_cpu","livePartials":false}""")
        assertEquals(BrainModel.FAST, from040.brainModelChoice)
        assertEquals(false, from040.convPartials)
        val both = RobotSettings(brainModel = BrainModel.UNCENSORED.id, convTranslate = true, convSpeakers = false, convReminderAt = 5L)
        assertEquals(both, RobotSettings.fromJson(both.toJson().toString()))
        assertEquals(8, both.toJson().getInt("schema"))
    }
}
