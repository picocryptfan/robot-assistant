// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
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
        // 1.0.0: and Claude, as every version before 0.6.0 had it (a new install has no helper).
        assertEquals(d.copy(safetyNotice = true, helpers = listOf(HelperConfig.claude())), RobotSettings.fromJson("{}"))
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
        assertEquals(13, RobotSettings.SCHEMA)
        assertEquals(13, s.toJson().getInt("schema"))
        // A 0.4.0-era file (schema 5) gets the defaults, except that live words in a conversation follow live words for questions.
        val old = RobotSettings.fromJson("""{"schema":5,"livePartials":false,"speechRate":1.2}""")
        assertEquals(false, old.convPartials)
        assertEquals(RobotSettings().copy(livePartials = false, speechRate = 1.2f, convPartials = false, safetyNotice = true, helpers = listOf(HelperConfig.claude())), old)
        assertEquals(true, RobotSettings.fromJson("""{"schema":5}""").convPartials)
        // A 0.4.0 file (schema 6) too, 0.4.4's (7), the GGUF branch's (10) and 0.4.5's (12); from schema 13 on, the two are
        // separate choices.
        for (n in listOf(6, 7, 10, 12)) assertEquals("$n", false, RobotSettings.fromJson("""{"schema":$n,"livePartials":false}""").convPartials)
        assertEquals(true, RobotSettings.fromJson("""{"schema":13,"livePartials":false}""").convPartials)
        // Out of range or unknown values fall back.
        val bad = RobotSettings.fromJson("""{"schema":6,"convPair":"fr-xx","convMaxSpeakers":40,"convSummaryLang":"de"}""")
        assertEquals(LangPair.FR_EN.id, bad.convPair)
        assertEquals(6, bad.convMaxSpeakers)
        assertEquals(null, bad.convSummaryLang)
        assertEquals(2, RobotSettings.fromJson("""{"convMaxSpeakers":1}""").convMaxSpeakers)
    }

    /** 5.2 adds "Show the reminder again" without a schema of its own (conversation mode's, 13): absent reads as never. */
    @Test fun theConsentReminderSettingNeedsNoSchemaOfItsOwn() {
        assertEquals(0L, RobotSettings().convReminderAt)
        val s = RobotSettings(convReminderAt = 1_760_000_000_000L)
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        assertEquals(13, s.toJson().getInt("schema"))
        // Not written while unset, so a file from 5.1 and one from today read the same.
        assertTrue(!RobotSettings().toJson().has("convReminderAt"))
        assertEquals(0L, RobotSettings.fromJson("""{"schema":13,"convWakeDuring":true}""").convReminderAt)
        assertEquals(0L, RobotSettings.fromJson("""{"schema":5}""").convReminderAt)
        assertEquals(0L, RobotSettings.fromJson("""{"schema":13,"convReminderAt":-5}""").convReminderAt)
        assertEquals(RobotSettings().copy(convWakeDuring = true), RobotSettings.fromJson("""{"schema":13,"convWakeDuring":true}"""))
    }

    /**
     * 5.3 adds "Tell speakers apart" without a schema of its own: absent reads as "on once the speaker model is
     * downloaded"; the voiceprint setting stays a flag.
     */
    @Test fun speakerLabelsNeedNoSchemaOfTheirOwn() {
        assertEquals(null, RobotSettings().convSpeakers)
        for (v in listOf(true, false)) {
            val s = RobotSettings(convSpeakers = v)
            assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
            assertEquals(13, s.toJson().getInt("schema"))
        }
        // Not written while unset, so a file from 5.2 and one from today read the same.
        assertTrue(!RobotSettings().toJson().has("convSpeakers"))
        assertEquals(null, RobotSettings.fromJson("""{"schema":13,"convMaxSpeakers":4}""").convSpeakers)
        assertEquals(null, RobotSettings.fromJson("""{"schema":5}""").convSpeakers)
        assertEquals(RobotSettings().copy(convMaxSpeakers = 4), RobotSettings.fromJson("""{"schema":13,"convMaxSpeakers":4}"""))
        assertEquals(false, RobotSettings.fromJson("""{"schema":13,"convSpeakers":false}""").convSpeakers)
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
        assertEquals(true, d.helperActionCards)
        assertEquals(true, d.whoCards)
        assertEquals(true, d.learnedCards)
        assertEquals(true, d.deleteCards)
        assertEquals(setOf(ToolName.SET_TIMER, ToolName.SET_ALARM), d.lockedTools)
        assertEquals(false, d.personalToHelpers)
        assertEquals(false, d.voiceYesPersonal)
        assertEquals(true, d.voiceCards)
        assertEquals(false, RobotSettings.fromJson(d.copy(voiceCards = false).toJson().toString()).voiceCards)
        assertEquals(13, RobotSettings.SCHEMA)
        // A 0.3 or early 0.4 file: every rule as it was, and the new Safety cards default.
        for (old in listOf("""{"schema":5,"speechRate":1.5}""", """{"schema":3}""", "{}")) {
            val o = RobotSettings.fromJson(old)
            assertEquals(old, SafetyCards.UNASKED, o.safetyCards)
            assertEquals(old, listOf(true, true, true, true), listOf(o.helperActionCards, o.whoCards, o.learnedCards, o.deleteCards))
            assertEquals(old, LockScreen.DEFAULT, o.lockedTools)
            assertEquals(old, false, o.personalToHelpers || o.voiceYesPersonal)
        }
        val s = d.copy(safetyCards = SafetyCards.AFTER_READING, helperActionCards = false, whoCards = false, learnedCards = false, deleteCards = false,
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
        // An old file keeps everything else, and the next save writes today's schema with Fast.
        val migrated = RobotSettings.fromJson("""{"schema":6,"speechRate":1.5,"keepModelMinutes":15}""")
        assertEquals(1.5f, migrated.speechRate)
        assertEquals(15, migrated.keepModelMinutes)
        assertEquals(RobotSettings.SCHEMA, migrated.toJson().getInt("schema"))
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
     * Schema 12 (0.4.5, the compliance work): the nearest place's search server. Older files read as OpenStreetMap's
     * server, the only one they knew; a server you chose round-trips in its checked form; anything unusable in a file reads
     * as OpenStreetMap's rather than failing.
     */
    @Test fun searchServerSchema12MigrationAndRoundTrip() {
        assertEquals("", RobotSettings().nearestServer)
        assertEquals(NearbyServer.DEFAULT, RobotSettings().nearbyServer)
        // The default isn't written, so a later default (should OpenStreetMap ask) follows for everyone who didn't choose.
        assertEquals(false, RobotSettings().toJson().has("nearestServer"))
        for (old in listOf("""{"schema":7,"brainModel":"e4b_cpu","nearestPlace":"ALWAYS"}""", """{"schema":6}""", """{"schema":3}""", "{}")) {
            val o = RobotSettings.fromJson(old)
            assertEquals(old, NearbyServer.DEFAULT, o.nearbyServer)
            assertEquals(old, 13, o.toJson().getInt("schema"))
        }
        // A schema 7 file keeps its other choices through the migration.
        val seven = RobotSettings.fromJson("""{"schema":7,"brainModel":"e4b_cpu","nearestPlace":"ALWAYS","nearestPlaceAt":5}""")
        assertEquals(BrainModel.SMARTER, seven.brainModelChoice)
        assertEquals(NearestOnline.ALWAYS, seven.nearestOnline)
        val own = RobotSettings(nearestServer = "https://nominatim.example.org/osm")
        assertEquals("https://nominatim.example.org/osm", own.toJson().getString("nearestServer"))
        assertEquals(own, RobotSettings.fromJson(own.toJson().toString()))
        assertEquals("nominatim.example.org", own.nearbyServer.name)
        // Saved in its checked form; OpenStreetMap's typed out is the default again; anything unusable reads as the default.
        assertEquals("https://nominatim.example.org:8443", RobotSettings.fromJson("""{"schema":12,"nearestServer":"Nominatim.Example.org:8443/"}""").nearestServer)
        assertEquals("", RobotSettings.fromJson("""{"schema":12,"nearestServer":"https://nominatim.openstreetmap.org/"}""").nearestServer)
        for (bad in listOf("http://nominatim.example.org", "ftp://x.org", "https://user@x.org", "https://x.org/search?q=a", "not a host", "localhost", "12345")) {
            val o = RobotSettings.fromJson("""{"schema":12,"nearestServer":"$bad"}""")
            assertEquals(bad, "", o.nearestServer)
            assertEquals(bad, NearbyServer.DEFAULT, o.nearbyServer)
        }
    }

    /** Schema 12: the model terms you confirmed (ModelTerms). Older files: none confirmed; damaged entries are dropped. */
    @Test fun confirmedTermsSchema12MigrationAndRoundTrip() {
        assertEquals(emptyMap<String, Long>(), RobotSettings().confirmedTerms)
        assertEquals(false, RobotSettings().toJson().has("confirmedTerms"))
        for (old in listOf("""{"schema":7,"brainModel":"e4b_cpu_uncensored"}""", """{"schema":6}""", "{}")) {
            val o = RobotSettings.fromJson(old)
            assertEquals(old, emptyMap<String, Long>(), o.confirmedTerms)
            // A model chosen (and downloaded) before keeps being the choice: only a new download asks.
            if (old.contains("uncensored")) assertEquals(BrainModel.UNCENSORED, o.brainModelChoice)
        }
        val s = RobotSettings(confirmedTerms = mapOf(ModelTerms.UNCENSORED.id to 1_760_000_000_000))
        assertEquals(1_760_000_000_000, s.toJson().getJSONObject("confirmedTerms").getLong(ModelTerms.UNCENSORED.id))
        assertEquals(s, RobotSettings.fromJson(s.toJson().toString()))
        val damaged = RobotSettings.fromJson("""{"schema":12,"confirmedTerms":{"a":1,"b":0,"c":"x","d":-5,"${"x".repeat(201)}":3}}""")
        assertEquals(mapOf("a" to 1L), damaged.confirmedTerms)
        assertEquals(emptyMap<String, Long>(), RobotSettings.fromJson("""{"schema":12,"confirmedTerms":[1,2]}""").confirmedTerms)
    }

    /**
     * Settings › Model › "Let the model think first" (schema 10, 0.4.5): off by default and for every older file (0.4.1's
     * schema 7, and the unreleased 8 and 9, which never had it, even if one said so); a file of schema 10 or later keeps
     * what it says, and the rest of an older file is kept.
     */
    @Test fun thinkingSwitchDefaultsMigrationAndRoundTrip() {
        assertEquals(false, RobotSettings().modelThinks)
        assertEquals(false, RobotSettings().toJson().getBoolean("modelThinks"))
        for (old in listOf("""{"schema":7,"brainModel":"own"}""", """{"schema":8}""", """{"schema":9,"modelThinks":true}""", """{"schema":6}""", "{}")) {
            assertEquals(old, false, RobotSettings.fromJson(old).modelThinks)
        }
        val v041 = RobotSettings.fromJson("""{"schema":7,"brainModel":"own","speechRate":1.25,"directAnswers":false}""")
        assertEquals(BrainModel.OWN, v041.brainModelChoice)
        assertEquals(1.25f, v041.speechRate)
        assertEquals(false, v041.directAnswers)
        assertEquals(false, v041.modelThinks)
        assertEquals(RobotSettings.SCHEMA, v041.toJson().getInt("schema"))
        assertEquals(true, RobotSettings.fromJson("""{"schema":10,"modelThinks":true}""").modelThinks)
        assertEquals(false, RobotSettings.fromJson("""{"schema":10,"modelThinks":false}""").modelThinks)
        assertEquals(false, RobotSettings.fromJson("""{"schema":10,"modelThinks":"maybe"}""").modelThinks)
        assertEquals(true, RobotSettings.fromJson("""{"schema":12,"modelThinks":true}""").modelThinks)
        assertEquals(true, RobotSettings.fromJson("""{"schema":13,"modelThinks":true}""").modelThinks)
        val on = v041.copy(modelThinks = true)
        assertEquals(on, RobotSettings.fromJson(on.toJson().toString()))
        assertEquals(true, RobotSettings.fromJson(on.toJson().toString()).modelThinks)
    }

    // ------------------------------------------------------------------ released files, migrated to 13 (0.6.0)

    /** Every choice 0.4.4 and 0.4.5 could save, each away from its default (the 0.4.0 file has all but the model). */
    private val chosen044 = RobotSettings(
        wakeWord = false, wakeOnlyWhileCharging = true, speakReplies = SpeakReplies.ALWAYS, speechRate = 1.25f,
        voice = VoiceChoice.KITTEN.id, livePartials = false, directAnswers = false, keepModelMinutes = 15, saveHistory = false,
        approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.SEND_TEXT to Approval.AUTO) + (ToolName.MAPS_SEARCH to Approval.OFF) +
            (ToolName.FREE_MEMORY to Approval.OFF),
        textApp = TextApp.CHEOGRAM, smsGateway = "sms.example.org", phoneRegion = "FR", emailApp = EmailApp.PROTON,
        eventCalendar = 7, eventMinutes = 30, rememberPicks = false, learnFromCheogram = true,
        wakePhrases = listOf(WakePhrase(1, "hey robot"), WakePhrase(3, "robot", WakeSensitivity.LOW, enabled = false)),
        bargeIn = BargeIn.ALWAYS, interrupt = InterruptSensitivity.HIGH, playback = Playback.CALL, stopKey = StopKey.EITHER,
        // Ask Claude's fields (0.3 to 0.4.5) become the Claude helper and every helper's budget, cost line and escalation.
        helpers = listOf(HelperConfig.claude().copy(enabled = false, route = AskRoute.API, model = AnthropicModel.SONNET.id,
            effort = HelperEffortSetting.HIGH, search = false, locked = true, voice = "bf_emma")),
        helperShowCost = false, help = Escalation.AUTO, helperBudgetCents = 200, weatherHome = WeatherPlace("Lyon", 45.76, 4.84),
        safetyCards = SafetyCards.AFTER_READING, helperActionCards = false, whoCards = false, learnedCards = false, deleteCards = false,
        lockedTools = setOf(ToolName.SET_TIMER, ToolName.FLASHLIGHT), personalToHelpers = true, voiceYesPersonal = true,
        keepMedia = true, voiceCards = false, nearestOnline = NearestOnline.ALWAYS, nearestOnlineAt = 1_760_000_000_000,
        safetyNotice = true, brainModel = BrainModel.OWN.id,
        // Conversation mode (schema 13) is new to these files: its defaults, but live words in a conversation follow
        // live words for questions, which these files turned off.
        convPartials = false,
    )

    /** Every field 0.4.0's RobotSettings had (schema 6), as its toJson wrote them (the approvals of every tool, Ask Claude's too). */
    private val v040 = """{"schema":6,"wakeWord":false,"wakeOnlyWhileCharging":true,"speakReplies":"ALWAYS","speechRate":1.25,""" +
        """"voice":"kitten_expr_voice_5_f","livePartials":false,"directAnswers":false,"keepModelMinutes":15,"saveHistory":false,""" +
        """"approvals":{"set_timer":"AUTO","set_alarm":"AUTO","open_app":"AUTO","maps_search":"OFF","maps_navigate":"AUTO",""" +
        """"flashlight":"AUTO","send_text":"AUTO","draft_email":"ASK","lookup_contact":"AUTO","add_note":"AUTO","find_notes":"AUTO",""" +
        """"add_event":"ASK","read_calendar":"AUTO","weather":"AUTO","phone_status":"AUTO","free_memory":"OFF","free_storage":"ASK",""" +
        """"ask_claude":"ASK"},"textApp":"CHEOGRAM","smsGateway":"sms.example.org","phoneRegion":"FR","emailApp":"PROTON",""" +
        """"eventCalendar":7,"eventMinutes":30,"rememberPicks":false,"learnFromCheogram":true,""" +
        """"wakePhrases":[{"id":1,"text":"hey robot","sensitivity":"MEDIUM","enabled":true},""" +
        """{"id":3,"text":"robot","sensitivity":"LOW","enabled":false}],"bargeIn":"ALWAYS","interrupt":"HIGH","playback":"CALL",""" +
        """"stopKey":"EITHER","claudeEnabled":false,"claudeRoute":"API","claudeModel":"claude-sonnet-5-5","claudeEffort":"HIGH",""" +
        """"claudeSearch":false,"claudeLocked":true,"claudeVoice":"bf_emma","claudeShowCost":false,"claudeHelp":"AUTO",""" +
        """"claudeBudgetCents":200,"weatherHome":{"name":"Lyon","lat":45.76,"lon":4.84},"safetyCards":"AFTER_READING",""" +
        """"claudeActionCards":false,"whoCards":false,"learnedCards":false,"deleteCards":false,"lockedTools":["set_timer","flashlight"],""" +
        """"personalToHelpers":true,"voiceYesPersonal":true,"keepMedia":true,"voiceCards":false,"nearestPlace":"ALWAYS",""" +
        """"nearestPlaceAt":1760000000000,"safetyNotice":true}"""

    /** Every field 0.4.1 to 0.4.4 wrote (schema 7): 0.4.0's and the model you chose. */
    private val v044 = v040.replace(""""schema":6""", """"schema":7""").removeSuffix("}") + ""","brainModel":"own"}"""

    /** Every field 0.4.5 writes (schema 12): 0.4.4's, the thinking switch, the search server and the terms you confirmed. */
    private val v045 = v044.replace(""""schema":7""", """"schema":12""").removeSuffix("}") +
        ""","modelThinks":true,"nearestServer":"https://nominatim.example.org","confirmedTerms":{"${ModelTerms.UNCENSORED.id}":1760000000001}}"""

    /** The conversation mode keys schema 13 adds (the ones written while unset: the rest only once chosen). */
    private val conversationKeys = setOf("convPair", "convTranslate", "convPartials", "convMaxSpeakers", "convWakeDuring", "convStartLocked",
        "convKeepAudio", "convAutoOfferHelper", "meVoiceprint")

    /** The helpers' keys schema 13 adds for these files ("helpDefault" only once you choose who Robot asks). */
    private val helperKeys = setOf("helpers", "help", "helperBudgetCents", "helperShowCost")

    /** Ask Claude's fields as 0.3 to 0.4.5 wrote them: still written, from the Claude helper, so a downgrade keeps Claude. */
    private val claudeKeys = listOf("claudeEnabled", "claudeRoute", "claudeModel", "claudeEffort", "claudeSearch", "claudeLocked", "claudeVoice",
        "claudeShowCost", "claudeHelp", "claudeBudgetCents")

    /** Ask Claude as [file] set it is the Claude helper (the only one), and the next save writes the same claude* fields back. */
    private fun assertClaudeKept(file: String, read: RobotSettings) {
        val o = org.json.JSONObject(file)
        val claude = read.claude!!
        assertEquals(listOf(HelperConfig.CLAUDE_ID), read.helpers.map { it.id })
        assertEquals(false, claude.enabled)
        assertEquals(AskRoute.API, claude.route)
        assertEquals(AnthropicModel.SONNET, read.claudeModelChoice)
        assertEquals(HelperEffortSetting.HIGH, claude.effort)
        assertEquals(false, claude.search)
        assertEquals(true, claude.locked)
        assertEquals("bf_emma", claude.voice)
        assertEquals(Escalation.AUTO, read.help)
        assertEquals(null, read.helpDefault)
        assertEquals(200, read.helperBudgetCents)
        assertEquals(false, read.helperShowCost)
        val saved = read.toJson()
        for (k in claudeKeys) assertEquals(k, o.get(k).toString(), saved.get(k).toString())
    }

    /**
     * Updating 0.4.4 to 0.6.0: a settings file as 0.4.4 wrote it (schema 7), every choice away from its default, reads with
     * every choice kept, the thinking switch off (schema 10), no search server or terms of its own (schema 12) and conversation
     * mode's defaults (schema 13), live words in a conversation off like live words for questions; the next save writes 13.
     */
    @Test fun aFileFrom044MigratesToSchema13() {
        val read = RobotSettings.fromJson(v044)
        assertEquals(chosen044, read)
        assertClaudeKept(v044, read)
        assertEquals(false, read.modelThinks)
        assertEquals("", read.nearestServer)
        assertEquals(NearbyServer.DEFAULT, read.nearbyServer)
        assertEquals(emptyMap<String, Long>(), read.confirmedTerms)
        assertEquals(RobotSettings().copy(livePartials = false, convPartials = false).let { d ->
            listOf(d.convPair, d.convTranslate, d.convSpeak, d.convPartials, d.convMaxSpeakers, d.convSpeakers, d.convWakeDuring, d.convStartLocked,
                d.convKeepAudio, d.convSummaryLang, d.convAutoOfferHelper, d.meVoiceprint, d.convReminderAt)
        }, listOf(read.convPair, read.convTranslate, read.convSpeak, read.convPartials, read.convMaxSpeakers, read.convSpeakers, read.convWakeDuring,
            read.convStartLocked, read.convKeepAudio, read.convSummaryLang, read.convAutoOfferHelper, read.meVoiceprint, read.convReminderAt))
        val saved = read.toJson()
        assertEquals(13, saved.getInt("schema"))
        assertEquals(false, saved.getBoolean("modelThinks"))
        assertEquals(false, saved.getBoolean("convPartials"))
        assertEquals(false, saved.has("nearestServer") || saved.has("confirmedTerms"))
        assertEquals(read, RobotSettings.fromJson(saved.toString()))
        // The 0.4.4 file has every key today's file can have but those added since (0.4.7's "Use the GPU" with no schema of
        // its own among them), and none it doesn't.
        val keys044 = org.json.JSONObject(v044).keys().asSequence().toSet()
        val everything = chosen044.copy(modelThinks = true, nearestServer = "https://nominatim.example.org",
            confirmedTerms = mapOf(ModelTerms.UNCENSORED.id to 1L), convSpeak = true, convSpeakers = true, convSummaryLang = "fr",
            convReminderAt = 5).toJson().keys().asSequence().toSet()
        assertEquals(setOf("modelThinks", "nearestServer", "confirmedTerms", "convSpeak", "convSpeakers", "convSummaryLang", "convReminderAt",
            "modelGpu") + conversationKeys + helperKeys, everything - keys044)
        assertEquals(emptySet<String>(), keys044 - everything)

        // The GGUF branch's file (schema 10, never released): the same choices and the switch on, kept on through 13.
        val v10 = v044.replace(""""schema":7""", """"schema":10""").removeSuffix("}") + ""","modelThinks":true}"""
        val ggufRead = RobotSettings.fromJson(v10)
        assertEquals(chosen044.copy(modelThinks = true), ggufRead)
        assertClaudeKept(v10, ggufRead)
        val ggufSaved = ggufRead.toJson()
        assertEquals(13, ggufSaved.getInt("schema"))
        assertEquals(true, ggufSaved.getBoolean("modelThinks"))
        assertEquals(ggufRead, RobotSettings.fromJson(ggufSaved.toString()))
    }

    /**
     * Updating 0.4.5 to 0.6.0: a file as 0.4.5 wrote it (schema 12), every field away from its default, keeps every choice,
     * its thinking switch, search server and confirmed terms included, and gets conversation mode's defaults (schema 13), live
     * words in a conversation following live words for questions as for any file from before conversation mode.
     */
    @Test fun aFileFrom045MigratesToSchema13() {
        val read = RobotSettings.fromJson(v045)
        val expected = chosen044.copy(modelThinks = true, nearestServer = "https://nominatim.example.org",
            confirmedTerms = mapOf(ModelTerms.UNCENSORED.id to 1_760_000_000_001))
        assertEquals(expected, read)
        assertClaudeKept(v045, read)
        assertEquals(false, read.convPartials)
        assertEquals("nominatim.example.org", read.nearbyServer.name)
        val saved = read.toJson()
        assertEquals(13, saved.getInt("schema"))
        assertEquals(read, RobotSettings.fromJson(saved.toString()))
        // Everything 0.4.5 wrote is still written, and only conversation mode's, the helpers' and 0.4.7's GPU switch are new.
        val keys045 = org.json.JSONObject(v045).keys().asSequence().toSet()
        val keysNow = saved.keys().asSequence().toSet()
        assertEquals(emptySet<String>(), keys045 - keysNow)
        assertEquals(conversationKeys + helperKeys + "modelGpu", keysNow - keys045)
        // Live words for questions on (0.4.5's default): live words in a conversation on too, as for a new install.
        val withWords = RobotSettings.fromJson(v045.replace(""""livePartials":false""", """"livePartials":true"""))
        assertEquals(true, withWords.convPartials)
        assertEquals(RobotSettings().convPartials, withWords.convPartials)
        // From 13 on, the two are separate choices.
        assertEquals(true, RobotSettings.fromJson(saved.toString().replace(""""convPartials":false""", """"convPartials":true""")).convPartials)
    }

    /**
     * Updating 0.4.0 to 0.6.0: a 0.4.0 file (schema 6) gets both later migrations: Fast as the model (schema 7), the thinking
     * switch off, no server or terms, and conversation mode's defaults with live words following live words for questions.
     */
    @Test fun aFileFrom040MigratesToSchema13() {
        val read = RobotSettings.fromJson(v040)
        assertEquals(chosen044.copy(brainModel = BrainModel.FAST.id), read)
        assertClaudeKept(v040, read)
        assertEquals(BrainModel.FAST, read.brainModelChoice)
        assertEquals(false, read.modelThinks)
        assertEquals(false, read.convPartials)
        // An update from before Safety & privacy still shows its one-time line; this 0.4.0 file had it already.
        assertEquals(true, read.safetyNotice)
        val saved = read.toJson()
        assertEquals(13, saved.getInt("schema"))
        assertEquals("e2b_tpu", saved.getString("brainModel"))
        assertEquals(read, RobotSettings.fromJson(saved.toString()))
        // A 0.4.0 file whose model id came from elsewhere still reads as Fast (only schema 7 or later chose one).
        assertEquals(BrainModel.FAST, RobotSettings.fromJson(v040.removeSuffix("}") + ""","brainModel":"e4b_cpu"}""").brainModelChoice)
    }

    /**
     * Settings › Model › "Use the GPU" (0.4.7, merged into 0.6.0): on by default, and for every file that never had it
     * (absent means on: 0.4.7 added it to schema 12 without a bump, and 0.6.0 keeps schema 13 for it), off kept from a 0.4.7
     * file (schema 12) and a 0.6.0 one (13). Fast's id from a Pixel 10 is left as saved; ModelCatalogue reads it as this
     * phone's Fast.
     */
    @Test fun gpuSwitchDefaultsOnWithoutANewSchema() {
        assertEquals(true, RobotSettings().modelGpu)
        assertEquals(true, RobotSettings().toJson().getBoolean("modelGpu"))
        assertEquals(13, RobotSettings.SCHEMA)
        // Files of 0.4.0 (6), 0.4.1 to 0.4.4 (7), 0.4.5 (12) and 0.6.0 before the merge (13): none had the key, all read on.
        val v060 = RobotSettings(convTranslate = true).toJson().apply { remove("modelGpu") }.toString()
        for (old in listOf(v040, v044, v045, v060, """{"schema":12}""", """{"schema":7,"brainModel":"e2b_tpu"}""", "{}",
            """{"schema":12,"modelGpu":"maybe"}""", """{"schema":13,"modelGpu":null}""")) {
            assertEquals(old, true, RobotSettings.fromJson(old).modelGpu)
        }
        // Off, as 0.4.7 saved it (schema 12, 0.4.5's keys and the switch): kept through the migration to 13, every other choice too.
        val v047 = v045.removeSuffix("}") + ""","modelGpu":false}"""
        val from047 = RobotSettings.fromJson(v047)
        assertEquals(false, from047.modelGpu)
        assertEquals(chosen044.copy(modelThinks = true, nearestServer = "https://nominatim.example.org",
            confirmedTerms = mapOf(ModelTerms.UNCENSORED.id to 1_760_000_000_001), modelGpu = false), from047)
        val saved047 = from047.toJson()
        assertEquals(13, saved047.getInt("schema"))
        assertEquals(false, saved047.getBoolean("modelGpu"))
        assertEquals(from047, RobotSettings.fromJson(saved047.toString()))
        assertEquals(conversationKeys + helperKeys, saved047.keys().asSequence().toSet() - org.json.JSONObject(v047).keys().asSequence().toSet())
        // Off in a schema 13 file, and in older schemas too should one carry it: kept.
        for (schema in listOf(6, 7, 12, 13)) {
            assertEquals(schema.toString(), false, RobotSettings.fromJson("""{"schema":$schema,"modelGpu":false}""").modelGpu)
        }
        val off = RobotSettings(modelGpu = false)
        assertEquals(false, RobotSettings.fromJson(off.toJson().toString()).modelGpu)
        assertEquals(13, off.toJson().getInt("schema"))
        // Fast chosen on a Pixel 10 (the TPU build's id) stays in the file; the general build's id reads as itself.
        val fromPixel10 = RobotSettings.fromJson("""{"schema":12,"brainModel":"e2b_tpu"}""")
        assertEquals(BrainModel.FAST, fromPixel10.brainModelChoice)
        assertEquals("e2b_tpu", fromPixel10.toJson().getString("brainModel"))
        assertEquals(BrainModel.GENERAL, RobotSettings.fromJson("""{"schema":12,"brainModel":"e2b"}""").brainModelChoice)
        assertEquals(BrainModel.GENERAL, RobotSettings.fromJson("""{"schema":13,"brainModel":"e2b"}""").brainModelChoice)
    }

    /**
     * Conversation mode with the model choice: a file of the unreleased conversation branch (schema 8) keeps its
     * conversation choices (it wrote every key it had), and reads as Fast when it had no model; both kinds of choices
     * round-trip together in schema 13.
     */
    @Test fun schemaThirteenKeepsTheModelChoiceAndConversationSettings() {
        val from050 = RobotSettings.fromJson("""{"schema":8,"convTranslate":true,"convPartials":true,"livePartials":false}""")
        assertEquals(BrainModel.FAST, from050.brainModelChoice)
        assertEquals(true, from050.convTranslate)
        assertEquals(true, from050.convPartials)
        val both = RobotSettings(brainModel = BrainModel.UNCENSORED.id, convTranslate = true, convSpeakers = false, convReminderAt = 5L)
        assertEquals(both, RobotSettings.fromJson(both.toJson().toString()))
        assertEquals(13, both.toJson().getInt("schema"))
    }
}
