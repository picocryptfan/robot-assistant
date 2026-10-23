// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.Approval
import app.heyrobot.core.BriefLock
import app.heyrobot.core.Escalation
import app.heyrobot.core.LookSettings
import app.heyrobot.core.NearestAnswer
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.Nearby
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarLockUse
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.SearchModelOffer
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRoute
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.termux.ApiCommands
import app.heyrobot.core.termux.CommandLevel
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.SpokenYes
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebConsent
import app.heyrobot.core.web.WebConsentAnswer
import app.heyrobot.core.web.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §7.5: each guarded setting keeps this phone's safer value when the file's is looser and the backup isn't
 * marked yours, and is listed ("Kept safer"); marked yours, it comes back as saved; a stricter one is always taken.
 */
class RestoreSafetyTest {
    /** One guarded field: [strict] and [loose] files of type T, [get] the field, [field] its name. */
    private class Case<T>(val field: SaferField, val strict: T, val loose: T, val get: (T) -> Any?, val guard: (T, T, Boolean) -> Guarded<T>)

    private fun <T> check(c: Case<T>) {
        val name = c.field.name
        // Not yours, looser: this phone's value, listed.
        val kept = c.guard(c.strict, c.loose, false)
        assertEquals(name, c.get(c.strict), c.get(kept.value))
        assertTrue(name, c.field in kept.kept)
        // Yours: as saved.
        val mine = c.guard(c.strict, c.loose, true)
        assertEquals(name, c.get(c.loose), c.get(mine.value))
        assertTrue(name, mine.kept.isEmpty())
        // Stricter than this phone: taken, not yours or yours.
        val stricter = c.guard(c.loose, c.strict, false)
        assertEquals(name, c.get(c.strict), c.get(stricter.value))
        assertTrue(name, c.field !in stricter.kept)
    }

    private val s = RobotSettings()
    private fun st(f: (RobotSettings) -> RobotSettings) = f(s)
    private val settings: (RobotSettings, RobotSettings, Boolean) -> Guarded<RobotSettings> = { p, f, m -> RestoreSafety.settings(p, f, m) }

    @Test fun settingsJson() {
        val cases = listOf(
            Case(SaferField.APPROVALS, s, st { it.copy(approvals = RobotSettings.DEFAULT_APPROVALS + (ToolName.SEND_TEXT to Approval.AUTO)) }, { it.approval(ToolName.SEND_TEXT) }, settings),
            Case(SaferField.LOCKED_TOOLS, s, st { it.copy(lockedTools = it.lockedTools + ToolName.SEND_TEXT) }, { it.lockedTools }, settings),
            Case(SaferField.SAFETY_CARDS, s, st { it.copy(safetyCards = SafetyCards.UNSURE_ONLY) }, { it.safetyCards }, settings),
            Case(SaferField.CLAUDE_ACTION_CARDS, s, st { it.copy(helperActionCards = false) }, { it.helperActionCards }, settings),
            Case(SaferField.WHO_CARDS, s, st { it.copy(whoCards = false) }, { it.whoCards }, settings),
            Case(SaferField.LEARNED_CARDS, s, st { it.copy(learnedCards = false) }, { it.learnedCards }, settings),
            Case(SaferField.DELETE_CARDS, s, st { it.copy(deleteCards = false) }, { it.deleteCards }, settings),
            Case(SaferField.PERSONAL_TO_HELPERS, s, st { it.copy(personalToHelpers = true) }, { it.personalToHelpers }, settings),
            Case(SaferField.VOICE_YES_PERSONAL, s, st { it.copy(voiceYesPersonal = true) }, { it.voiceYesPersonal }, settings),
            Case(SaferField.VOICE_CARDS, st { it.copy(voiceCards = false) }, s, { it.voiceCards }, settings),
            Case(SaferField.LEARN_FROM_CHEOGRAM, s, st { it.copy(learnFromCheogram = true) }, { it.learnFromCheogram }, settings),
            Case(SaferField.HELP, st { it.copy(help = Escalation.ASK) }, st { it.copy(help = Escalation.AUTO) }, { it.help }, settings),
            Case(SaferField.HELP, st { it.copy(help = Escalation.OFF) }, st { it.copy(help = null) }, { it.help }, settings),
            Case(SaferField.HELPER_BUDGET, s, st { it.copy(helperBudgetCents = 10_000) }, { it.helperBudgetCents }, settings),
            Case(SaferField.NEAREST_ONLINE, s, st { it.copy(nearestOnline = NearestOnline.ALWAYS) }, { it.nearestOnline }, settings),
            Case(SaferField.NEAREST_SERVER, s, st { it.copy(nearestServer = "https://nominatim.example") }, { it.nearestServer }, settings),
            Case(SaferField.SMS_GATEWAY, s, st { it.copy(smsGateway = "evil.example") }, { it.smsGateway }, settings),
            Case(SaferField.CONV_START_LOCKED, st { it.copy(convStartLocked = false) }, s, { it.convStartLocked }, settings),
        )
        cases.forEach { check(it) }
        // Several at once are all listed, and only those.
        val loose = s.copy(personalToHelpers = true, whoCards = false, nearestOnline = NearestOnline.ALWAYS)
        val g = RestoreSafety.settings(s, loose, false)
        assertEquals(setOf(SaferField.PERSONAL_TO_HELPERS, SaferField.WHO_CARDS, SaferField.NEAREST_ONLINE), g.kept.toSet())
        // What isn't guarded comes back as saved, yours or not.
        val other = s.copy(speechRate = 1.5f, saveHistory = false, wakeWord = false, brainModel = "e4b", convKeepAudio = true)
        val back = RestoreSafety.settings(s, other, false).value
        assertEquals(listOf(1.5f, false, false, "e4b", true), listOf(back.speechRate, back.saveHistory, back.wakeWord, back.brainModel, back.convKeepAudio))
    }

    @Test fun helpersHereKeepTheirSwitchesAndNewOnesArriveOff() {
        val claude = HelperConfig.claude()
        val jarvis = HelperConfig.of(Presets.OTHER, "jarvis", "af_heart", "Jarvis", "https://jarvis.example")
        val phone = s.copy(helpers = listOf(claude.copy(enabled = false)))
        val file = s.copy(helpers = listOf(claude.copy(enabled = true, trust = true, locked = true, budgetCents = 500), jarvis.copy(trust = true, locked = true)))
        val g = RestoreSafety.settings(phone, file, false)
        val c = g.value.helper("claude")!!
        // Its own daily cap is only ever stricter than none (the budget for all helpers still applies): taken.
        assertEquals(listOf(false, false, false, 500), listOf(c.enabled, c.trust, c.locked, c.budgetCents))
        // New to this phone, from someone else's backup: it arrives under a new id (a key kept here under "jarvis" stays
        // with no helper), so it is found by its name.
        val j = g.value.helpers.single { it.name == "Jarvis" }
        assertTrue(j.id != "jarvis")
        assertEquals(listOf(false, false, false), listOf(j.on, j.trust, j.locked))
        assertEquals("https://jarvis.example", j.baseUrl)
        assertTrue(SaferField.HELPERS in g.kept)
        // A helper's own budget: a lower one is stricter, taken.
        val strict = RestoreSafety.settings(s.copy(helpers = listOf(claude)), s.copy(helpers = listOf(claude.copy(budgetCents = 50))), false)
        assertEquals(50, strict.value.helper("claude")!!.budgetCents)
        // Yours: as saved, with its id.
        val mine = RestoreSafety.settings(phone, file, true).value
        assertEquals(listOf(true, true, true), listOf(mine.helper("claude")!!.on, mine.helper("claude")!!.trust, mine.helper("jarvis")!!.on))
        // A helper this phone has that points elsewhere keeps its address.
        val moved = RestoreSafety.settings(s.copy(helpers = listOf(jarvis)), s.copy(helpers = listOf(jarvis.copy(baseUrl = "https://other.example"))), false)
        assertEquals("https://jarvis.example", moved.value.helper("jarvis")!!.baseUrl)
    }

    /**
     * A helper you have keeps its company, address and model maker unless the backup is yours: your key for it is never
     * sent where someone else's backup chose. One new to this phone arrives off, under an id no key here is kept under.
     */
    @Test fun aHelperYouHaveNeverMovesToAnotherService() {
        val claude = HelperConfig.claude().copy(budgetCents = 300)
        val phone = s.copy(helpers = listOf(claude))
        // Settings' Claude, turned into another company's helper by the file: this phone's, whole.
        val deepseek = claude.copy(preset = Presets.DEEPSEEK.id, name = "Claude", model = "deepseek-flash", enabled = true, trust = true, budgetCents = null)
        val g = RestoreSafety.settings(phone, s.copy(helpers = listOf(deepseek)), false)
        assertEquals(claude, g.value.helper("claude"))
        assertTrue(SaferField.HELPERS in g.kept)
        assertEquals(deepseek, RestoreSafety.settings(phone, s.copy(helpers = listOf(deepseek)), true).value.helper("claude"))

        // OpenRouter: the model's maker is part of where your questions go.
        val router = HelperConfig.of(Presets.OPENROUTER, "h1", "af_heart").copy(model = "anthropic/claude-sonnet-4.5", trust = true)
        fun routed(model: String) = RestoreSafety.settings(s.copy(helpers = listOf(router)), s.copy(helpers = listOf(router.copy(model = model, name = "Router"))), false)
        for (other in listOf("deepseek/deepseek-chat", "openrouter/auto", "DeepSeek/deepseek-chat")) {
            val r = routed(other)
            assertEquals(other, router, r.value.helper("h1"))
            assertTrue(other, SaferField.HELPERS in r.kept)
        }
        // The same maker: another of its models is taken, with the file's other fields.
        val same = routed("Anthropic/claude-opus-4.1").value.helper("h1")!!
        assertEquals(listOf("Anthropic/claude-opus-4.1", "Router", true), listOf(same.model, same.name, same.trust))
        // A catalog's ids that carry a maker count too (Groq serves both, yet the maker changes): this phone's, whole.
        val groq = HelperConfig.of(Presets.GROQ, "h2", "af_heart")
        val qwen = RestoreSafety.settings(s.copy(helpers = listOf(groq)), s.copy(helpers = listOf(groq.copy(model = "qwen/qwen3.8-27b", name = "Qwen"))), false)
        assertEquals(groq, qwen.value.helper("h2"))
        // Your server or another company: the address is part of it, and the helper stays whole.
        val jarvis = HelperConfig.of(Presets.OTHER, "jarvis", "af_heart", "Jarvis", "https://jarvis.example")
        val elsewhere = RestoreSafety.settings(s.copy(helpers = listOf(jarvis)), s.copy(helpers = listOf(jarvis.copy(baseUrl = "https://other.example", name = "Other"))), false)
        assertEquals(jarvis, elsewhere.value.helper("jarvis"))
        // Your server's own models, named without a maker: taken.
        val home = HelperConfig.of(Presets.HOME, "home1", "af_heart", "Home", "http://192.168.1.5:11434").copy(model = "llama3")
        assertEquals("qwen3", RestoreSafety.settings(s.copy(helpers = listOf(home)), s.copy(helpers = listOf(home.copy(model = "qwen3"))), false).value.helper("home1")!!.model)

        // Providers that keep nothing: off is the looser side.
        val zdrOff = RestoreSafety.settings(s.copy(helpers = listOf(router)), s.copy(helpers = listOf(router.copy(zdr = false))), false)
        assertTrue(zdrOff.value.helper("h1")!!.zdr)
        assertTrue(SaferField.HELPERS in zdrOff.kept)
        assertTrue(RestoreSafety.settings(s.copy(helpers = listOf(router.copy(zdr = false))), s.copy(helpers = listOf(router)), false).value.helper("h1")!!.zdr)
        assertFalse(RestoreSafety.settings(s.copy(helpers = listOf(router)), s.copy(helpers = listOf(router.copy(zdr = false))), true).value.helper("h1")!!.zdr)

        // New to this phone: off, untrusted, not on the lock screen, under an id that is neither this phone's, the file's nor
        // Claude's (keys are kept by id, and Claude's key files are its own); "When Robot needs help"'s helper follows it.
        val gone = s.copy(helpers = listOf(router))
        val theirs = s.copy(helpers = listOf(deepseek, router.copy(id = "h9", name = "Nine", enabled = true, trust = true, locked = true)), helpDefault = "claude")
        var n = 0
        val r = RestoreSafety.settings(gone, theirs, false) { n++ % it }
        assertEquals(listOf("Claude", "Nine"), r.value.helpers.map { it.name })
        for (h in r.value.helpers) {
            assertTrue(h.id, h.id !in setOf("claude", "h9", "h1"))
            assertEquals(h.id, listOf(false, false, false), listOf(h.on, h.trust, h.locked))
        }
        assertEquals(r.value.helpers.single { it.preset == Presets.DEEPSEEK.id }.id, r.value.helpDefault)
        assertTrue(SaferField.HELPERS in r.kept)
        // Yours: as saved, ids too.
        val yours = RestoreSafety.settings(gone, theirs, true).value
        assertEquals(listOf("claude", "h9"), yours.helpers.map { it.id })
        assertEquals("claude", yours.helpDefault)
    }

    /**
     * When Settings last chose "Ask before searching" and "Find the nearest place online" is this phone's: the card's lasting
     * answers stay on this phone and count while newer than it, so a backup's time, older or far ahead, would bring back an
     * answer you changed since or hide a newer one. Yours or not.
     */
    @Test fun whenSettingsLastChoseIsThisPhones() {
        // Here: "Allow always" on the card at 100, then Ask first in Settings at 200; "Don't ask again" on a No at 300.
        val web = WebSettings(on = true, service = SearchService.BRAVE, ask = WebAsk.FIRST_TIME, askAt = 200)
        val always = WebConsentAnswer(WebAsk.NEVER, off = false, at = 100)
        val no = WebConsentAnswer(WebAsk.FIRST_TIME, off = true, at = 300)
        assertEquals(WebAsk.FIRST_TIME, WebConsent.ask(web, always))
        assertFalse(WebConsent.on(web, no))
        for (mine in listOf(false, true)) for (at in listOf(0L, 150L, 250L, Long.MAX_VALUE)) {
            val back = RestoreSafety.web(web, web.copy(askAt = at), mine).value
            assertEquals("$mine $at", 200L, back.askAt)
            assertEquals("$mine $at", WebAsk.FIRST_TIME, WebConsent.ask(back, always))
            assertFalse("$mine $at", WebConsent.on(back, no))
        }
        // Here: "Allow always" on the card at 100, then Never in Settings at 200; and Always in Settings with Never on the
        // card at 300.
        val never = RobotSettings(nearestOnline = NearestOnline.NEVER, nearestOnlineAt = 200)
        val allowed = RobotSettings(nearestOnline = NearestOnline.ALWAYS, nearestOnlineAt = 200)
        for (mine in listOf(false, true)) for (at in listOf(0L, 150L, 250L, Long.MAX_VALUE)) {
            val back = RestoreSafety.settings(never, never.copy(nearestOnlineAt = at), mine).value
            assertEquals("$mine $at", 200L, back.nearestOnlineAt)
            assertEquals("$mine $at", NearestOnline.NEVER, Nearby.mode(back, NearestAnswer(NearestOnline.ALWAYS, 100)))
            val later = RestoreSafety.settings(allowed, allowed.copy(nearestOnlineAt = at), mine).value
            assertEquals("$mine $at", NearestOnline.NEVER, Nearby.mode(later, NearestAnswer(NearestOnline.NEVER, 300)))
        }
    }

    @Test fun memoryJson() {
        val m = MemorySettings()
        val g: (MemorySettings, MemorySettings, Boolean) -> Guarded<MemorySettings> = RestoreSafety::memory
        listOf(
            Case(SaferField.MEMORY_LOCK_SCREEN, m, m.copy(onLockScreen = true), { it.onLockScreen }, g),
            Case(SaferField.MEMORY_SPEAK_SECRETS, m, m.copy(speakSecrets = true), { it.speakSecrets }, g),
            Case(SaferField.MEMORY_HELPERS, m, m.copy(helpers = HelperMemory.TRUSTED_HELPERS), { it.helpers }, g),
            Case(SaferField.MEMORY_FROM_CONTENT, m, m.copy(fromContentNoCard = true), { it.fromContentNoCard }, g),
        ).forEach { check(it) }
        // The search model's state is this phone's, yours or not.
        assertEquals(SearchModelOffer.NOT_OFFERED, RestoreSafety.memory(m, m.copy(searchModel = SearchModelOffer.DOWNLOADED), true).value.searchModel)
    }

    @Test fun messagesJson() {
        val f = MessagesFile()
        fun ms(x: (MessageSettings) -> MessageSettings) = f.copy(settings = x(f.settings))
        val g: (MessagesFile, MessagesFile, Boolean) -> Guarded<MessagesFile> = RestoreSafety::messages
        listOf(
            Case(SaferField.MESSAGES_REPLY_ROUTE, ms { it.copy(replyRoute = ReplyRoute.OPEN_CHAT) }, f, { it.settings.replyRoute }, g),
            Case(SaferField.MESSAGES_REPLY_CARD, f, ms { it.copy(replyCard = false) }, { it.settings.replyCard }, g),
            Case(SaferField.MESSAGES_VOICE_YES, f, ms { it.copy(voiceYesSends = true) }, { it.settings.voiceYesSends }, g),
            Case(SaferField.MESSAGES_LOCKED, f, ms { it.copy(locked = MessagesLocked.READ_AND_REPLY) }, { it.settings.locked }, g),
            Case(SaferField.MESSAGES_LOCKED, ms { it.copy(locked = MessagesLocked.WHO_MESSAGED) }, ms { it.copy(locked = MessagesLocked.READ_AND_REPLY) }, { it.settings.locked }, g),
            Case(SaferField.MESSAGES_HELPERS, f, ms { it.copy(helpersNoCard = true) }, { it.settings.helpersNoCard }, g),
            Case(SaferField.MESSAGES_ANNOUNCE, f, ms { it.copy(announce = true) }, { it.settings.announce }, g),
            Case(SaferField.MESSAGES_NO_CARD, f.copy(noCard = setOf("a")), f.copy(noCard = setOf("a", "b")), { it.noCard }, g),
        ).forEach { check(it) }
    }

    @Test fun termuxJson() {
        val t = TermuxSettings()
        val g: (TermuxSettings, TermuxSettings, Boolean) -> Guarded<TermuxSettings> = RestoreSafety::termux
        listOf(
            Case(SaferField.TERMUX_SCRIPTS, t.copy(scripts = mapOf("a.sh" to ScriptLevel.ASK)), t.copy(scripts = mapOf("a.sh" to ScriptLevel.AUTO)), { it.script("a.sh") }, g),
            Case(SaferField.TERMUX_SCRIPTS, t, t.copy(scripts = mapOf("new.sh" to ScriptLevel.AUTO)), { it.script("new.sh") }, g),
            Case(SaferField.TERMUX_COMMANDS, t.copy(commands = CommandLevel.OFF), t, { it.commands }, g),
            Case(SaferField.TERMUX_READ_ONLY, t, t.copy(readOnlyWithoutCard = true), { it.readOnlyWithoutCard }, g),
            Case(SaferField.TERMUX_SPOKEN_YES, t.copy(spokenYes = SpokenYes.NEVER), t, { it.spokenYes }, g),
            Case(SaferField.TERMUX_HIDE_SECRETS, t, t.copy(hideSecrets = false), { it.hideSecrets }, g),
            Case(SaferField.TERMUX_PROFILE, t, t.copy(loadProfile = true), { it.loadProfile }, g),
            Case(SaferField.TERMUX_API, t.copy(api = ApiCommands.NOT_ALLOWED), t, { it.api }, g),
            Case(SaferField.TERMUX_HELPERS, t, t.copy(helpers = true), { it.helpers }, g),
            Case(SaferField.TERMUX_LOCK_SCREEN, t, t.copy(lockScreenScripts = setOf("a.sh")), { it.lockScreenScripts }, g),
        ).forEach { check(it) }
    }

    @Test fun webUpdatesAndFeedback() {
        val w = WebSettings()
        val gw: (WebSettings, WebSettings, Boolean) -> Guarded<WebSettings> = RestoreSafety::web
        listOf(
            Case(SaferField.WEB_ASK, w, w.copy(ask = WebAsk.NEVER), { it.ask }, gw),
            Case(SaferField.WEB_ASK, w.copy(ask = WebAsk.ALWAYS), w, { it.ask }, gw),
            Case(SaferField.WEB_HELPERS, w.copy(helpersMayUse = false), w, { it.helpersMayUse }, gw),
            Case(SaferField.WEB_LOCK_SCREEN, w, w.copy(lockScreen = true), { it.lockScreen }, gw),
            Case(SaferField.WEB_SEARXNG, w, w.copy(searxng = "https://searx.example"), { it.searxng }, gw),
            Case(SaferField.WEB_PUBLIC_SEARXNG, w, w.copy(publicSearxng = "https://public.example"), { it.publicSearxng }, gw),
            Case(SaferField.WEB_DAILY, w, w.copy(dailySearches = 5_000), { it.dailySearches }, gw),
        ).forEach { check(it) }
        val u = UpdateSettings()
        val gu: (UpdateSettings, UpdateSettings, Boolean) -> Guarded<UpdateSettings> = RestoreSafety::updates
        listOf(
            Case(SaferField.UPDATES_REPO, u, u.copy(repo = "someone/robot"), { it.repo }, gu),
            Case(SaferField.UPDATES_TEST_BUILDS, u, u.copy(testBuilds = true), { it.testBuilds }, gu),
            Case(SaferField.UPDATES_INSTALL, u, u.copy(installWithoutAsking = true), { it.installWithoutAsking }, gu),
            Case(SaferField.UPDATES_SKIPPED, u, u.copy(skipped = "1.1.1"), { it.skipped }, gu),
        ).forEach { check(it) }
        // Another version skipped than this phone's: this phone's, unless the backup is yours.
        val other = RestoreSafety.updates(u.copy(skipped = "1.1.1"), u.copy(skipped = "1.1.2"), false)
        assertEquals("1.1.1", other.value.skipped)
        assertTrue(SaferField.UPDATES_SKIPPED in other.kept)
        assertEquals("1.1.2", RestoreSafety.updates(u.copy(skipped = "1.1.1"), u.copy(skipped = "1.1.2"), true).value.skipped)
        // Automatic checks: off is the stricter side, so the file's off is taken.
        assertFalse(RestoreSafety.updates(u, u.copy(autoCheck = false), false).value.autoCheck)
        val gf: (FeedbackPrefs, FeedbackPrefs, Boolean) -> Guarded<FeedbackPrefs> = RestoreSafety::feedback
        check(Case(SaferField.FEEDBACK_SEND_TO, FeedbackPrefs(), FeedbackPrefs("+15145550199"), { it.sendTo }, gf))
        // Display: as saved.
        assertEquals(LookSettings(wallpaperColors = false), RestoreSafety.look(LookSettings(), LookSettings(wallpaperColors = false)).value)
    }

    @Test fun headsetCarAndRoutines() {
        val h = HeadsetFile()
        val gh: (HeadsetFile, HeadsetFile, Boolean) -> Guarded<HeadsetFile> = RestoreSafety::headset
        listOf(
            Case(SaferField.HEADSET_ANNOUNCE_LOCKED, h, h.copy(settings = HeadsetSettings(announceLocked = true)), { it.settings.announceLocked }, gh),
            Case(SaferField.HANDSFREE_PRIVATE, h, h.copy(safety = HandsFreeSafety(personalOnlyPrivate = false)), { it.safety.personalOnlyPrivate }, gh),
            Case(SaferField.HANDSFREE_REPLY, h.copy(safety = HandsFreeSafety(replyAfterSpokenYes = false)), h, { it.safety.replyAfterSpokenYes }, gh),
            Case(SaferField.HANDSFREE_CALL, h.copy(safety = HandsFreeSafety(callAfterSpokenYes = false)), h, { it.safety.callAfterSpokenYes }, gh),
        ).forEach { check(it) }
        val c = CarFile()
        fun cs(x: (CarSettings) -> CarSettings) = c.copy(settings = x(c.settings))
        val gc: (CarFile, CarFile, Boolean) -> Guarded<CarFile> = RestoreSafety::car
        listOf(
            Case(SaferField.CAR_LOCK_USE, c, cs { it.copy(lockUse = CarLockUse.ALWAYS) }, { it.settings.lockUse }, gc),
            Case(SaferField.CAR_CALLS, c, cs { it.copy(calls = CallSetting.CALL_AFTER_YES) }, { it.settings.calls }, gc),
            Case(SaferField.CAR_READ_ALOUD, c, cs { it.copy(readAloud = ReadAloud.ALWAYS) }, { it.settings.readAloud }, gc),
            Case(SaferField.CAR_TRUSTED_HOURS, c, cs { it.copy(trustedHours = 12) }, { it.settings.trustedHours }, gc),
        ).forEach { check(it) }
        val r = RoutineSettings()
        val gr: (RoutineSettings, RoutineSettings, Boolean) -> Guarded<RoutineSettings> = RestoreSafety::routineSettings
        listOf(
            Case(SaferField.BRIEF_LOCK, r, r.copy(briefLock = BriefLock.EVERYTHING), { it.briefLock }, gr),
            Case(SaferField.BRIEF_ON, r, r.copy(briefEnabled = true), { it.briefEnabled }, gr),
            Case(SaferField.ROUTINES_LOCKED, r.copy(runLocked = false), r, { it.runLocked }, gr),
            Case(SaferField.ROUTINES_ONLINE, r.copy(online = false), r, { it.online }, gr),
        ).forEach { check(it) }
        // 1.1, Skills phase B: the model picking skills is the looser side.
        val k = app.heyrobot.core.skills.SkillSettings()
        val gk: (app.heyrobot.core.skills.SkillSettings, app.heyrobot.core.skills.SkillSettings, Boolean) -> Guarded<app.heyrobot.core.skills.SkillSettings> =
            RestoreSafety::skillSettings
        listOf(
            Case(SaferField.SKILLS_MODEL_PICKS, k.copy(modelPicks = false), k, { it.modelPicks }, gk),
        ).forEach { check(it) }
    }

    /** 1.4, Settings › Files (files-1.4-plan.md §2.8): a looser filing level, reminders on their own, location stripping off. */
    @Test fun filesSettingsAreKeptSafer() {
        val f = app.heyrobot.core.files.FilesSettings()
        val gf: (app.heyrobot.core.files.FilesSettings, app.heyrobot.core.files.FilesSettings, Boolean) -> Guarded<app.heyrobot.core.files.FilesSettings> =
            RestoreSafety::filesSettings
        val off = app.heyrobot.core.files.FileLevel.OFF
        val ask = app.heyrobot.core.files.FileLevel.ASK
        val auto = app.heyrobot.core.files.FileLevel.AUTO
        listOf(
            Case(SaferField.FILES_RECEIPTS, f.copy(receipts = ask), f.copy(receipts = auto), { it.receipts }, gf),
            Case(SaferField.FILES_BILLS, f.copy(bills = off), f.copy(bills = ask), { it.bills }, gf),
            Case(SaferField.FILES_DOCUMENTS, f.copy(documents = off), f.copy(documents = auto), { it.documents }, gf),
            Case(SaferField.FILES_PHOTOS, f.copy(photos = ask), f.copy(photos = auto), { it.photos }, gf),
            Case(SaferField.FILES_OTHER, f.copy(other = off), f.copy(other = auto), { it.other }, gf),
            Case(SaferField.FILES_REMINDERS, f.copy(reminders = app.heyrobot.core.files.FileReminders.ASK),
                f.copy(reminders = app.heyrobot.core.files.FileReminders.BRIEF), { it.reminders }, gf),
            Case(SaferField.FILES_REMINDERS, f.copy(reminders = app.heyrobot.core.files.FileReminders.OFF),
                f.copy(reminders = app.heyrobot.core.files.FileReminders.ASK), { it.reminders }, gf),
            Case(SaferField.FILES_STRIP_LOCATION, f.copy(stripLocation = true), f.copy(stripLocation = false), { it.stripLocation }, gf),
            // FX3: Robot Files, reading text in images and the brief's lines, turned off here, stay off unless the backup is yours.
            Case(SaferField.FILES_ON, f.copy(on = false), f, { it.on }, gf),
            Case(SaferField.FILES_READ_IMAGE_TEXT, f.copy(readImageText = false), f, { it.readImageText }, gf),
            Case(SaferField.FILES_BRIEF, f.copy(brief = false), f, { it.brief }, gf),
        ).forEach { check(it) }
        // Off in the backup is the stricter side: it comes back as the file has it, nothing kept safer.
        val strict = f.copy(on = false, readImageText = false, cleanup = false, brief = false)
        assertEquals(Guarded(strict, emptyList()), RestoreSafety.filesSettings(f, strict, mine = false))
        // Clean-up suggestions (a list for your tap, never a delete) as the file has it, either way.
        assertEquals(true, RestoreSafety.filesSettings(f.copy(cleanup = false), f, mine = false).value.cleanup)
        assertEquals(emptyList<SaferField>(), RestoreSafety.filesSettings(f.copy(cleanup = false), f, mine = false).kept)
        // Keys this Robot doesn't know (a newer one's rows) come only from your own backup.
        val here = mapOf("hidden" to listOf("Medical"))
        val theirs = mapOf("hidden" to emptyList<String>(), "newer" to true)
        assertEquals(here, RestoreSafety.filesSettingsExtra(here, theirs, mine = false))
        assertEquals(theirs, RestoreSafety.filesSettingsExtra(here, theirs, mine = true))
    }

    /**
     * F12, travel mode (§2.8, "fewer hidden drawers"): neither someone else's backup nor an older one of yours shows what
     * this phone hides. "This backup is my own" doesn't change that: it is a tick, not the phone's PIN.
     */
    @Test fun travelModeIsNeverLoosenedByARestore() {
        val f = app.heyrobot.core.files.FilesSettings()
        val phone = f.copy(travel = true, hidden = setOf("medical", "Lawyer"))
        val older = f.copy(stripLocation = false, hidden = setOf("Car"))
        for (mine in listOf(false, true)) {
            val g = RestoreSafety.filesSettings(phone, older, mine)
            assertTrue(g.value.travel)
            assertEquals(setOf("medical", "Lawyer", "Car"), g.value.hidden)
            assertEquals(if (mine) listOf(SaferField.FILES_HIDDEN, SaferField.FILES_TRAVEL) else listOf(SaferField.FILES_STRIP_LOCATION, SaferField.FILES_HIDDEN,
                SaferField.FILES_TRAVEL), g.kept)
        }
        // A backup that hides more, or turns travel mode on, comes back as it is; nothing is kept safer for it.
        val more = f.copy(travel = true, hidden = setOf("medical", "Lawyer", "Car"))
        assertEquals(RestoreSafety.filesSettings(phone, more, mine = false), Guarded(more, emptyList()))
        // Travel mode off here and in the backup: the backup's hidden drawers are added to this phone's.
        val g = RestoreSafety.filesSettings(f.copy(hidden = setOf("Lawyer")), f.copy(hidden = setOf("Car")), mine = false)
        assertEquals(setOf(SaferField.FILES_HIDDEN), g.kept.toSet())
        assertEquals(setOf("Lawyer", "Car"), g.value.hidden)
        assertTrue(!g.value.travel)
        assertEquals(setOf(SaferField.FILES_TRAVEL, SaferField.FILES_HIDDEN), RestoreSafety.filesSettings(phone, f, mine = true).kept.toSet())
    }

    /** Every guarded field of the [SaferField] list has a case above. */
    @Test fun everySaferFieldIsCovered() {
        val src = java.io.File("src/test/java/app/heyrobot/core/backup/RestoreSafetyTest.kt").readText()
        for (f in SaferField.entries.filter { it != SaferField.HELPERS }) assertTrue(f.name, src.contains("SaferField.${f.name},"))
        assertTrue(src.contains("SaferField.HELPERS in g.kept"))
    }
}
