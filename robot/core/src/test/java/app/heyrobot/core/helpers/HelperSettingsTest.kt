package app.heyrobot.core.helpers

import app.heyrobot.core.HelperEffortSetting
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.AskRoute
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.Hedge
import app.heyrobot.core.Personal
import app.heyrobot.core.RobotSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperSettingsTest {
    /** A 0.3.1 settings file with every Ask Claude field set (as 0.4/0.5 wrote them). */
    private val v031 = """{"schema":4,"claudeEnabled":true,"claudeRoute":"API","claudeModel":"claude-haiku-5-5","claudeEffort":"HIGH",
        "claudeSearch":false,"claudeLocked":true,"claudeVoice":"bf_emma","claudeShowCost":false,"claudeHelp":"ASK","claudeBudgetCents":250}"""

    @Test fun anOldFileBecomesOneClaudeHelperExactly() {
        val s = RobotSettings.fromJson(v031)
        assertEquals(1, s.helpers.size)
        val c = s.claude!!
        assertEquals(HelperConfig(id = "claude", preset = "anthropic", name = "Claude", model = "claude-haiku-5-5", enabled = true, route = AskRoute.API,
            effort = HelperEffortSetting.HIGH, search = false, voice = "bf_emma", locked = true), c)
        assertEquals(Escalation.ASK, s.help)
        assertEquals(250, s.helperBudgetCents)
        assertFalse(s.helperShowCost)
        assertNull(s.helpDefault)
        // The same behaviour: route, escalation and the local tool as 0.3.1 computed them.
        assertEquals(AskRoute.API, ClaudeSettings.route(s, keySaved = false))
        assertEquals(Escalation.ASK, ClaudeSettings.escalation(s, keySaved = true))
        assertTrue(ClaudeSettings.localTool(s, keySaved = true))
    }

    @Test fun aNewInstallHasNoHelper() {
        // 1.0.0: no file (a new install), a damaged one, or a schema 13 file without the list: no helper at all, so
        // "ask the helper" says none is set up, and Robot never brings one in by itself.
        for (text in listOf(null, "garbage", """{"schema":13}""")) {
            val s = RobotSettings.fromJson(text)
            assertEquals(emptyList<HelperConfig>(), s.helpers)
            assertNull(s.claude)
            assertNull(HelperSettings.target(s) { true })
            assertNull(HelperSettings.askTarget(s, { true }) { true })
            assertNull(HelperSettings.appHelper(s, null) { false })
            assertEquals(emptyList<HelperConfig>(), HelperSettings.summarizers(s) { true })
            assertEquals(EscalationDecision.NONE, EscalationPolicy.decide(s, keySaved = true, personal = false, locked = false, budgetReached = false))
        }
        assertEquals(emptyList<HelperConfig>(), RobotSettings().helpers)
        // Saved as it is: still none after a round trip.
        assertEquals(emptyList<HelperConfig>(), RobotSettings.fromJson(RobotSettings().toJson().toString()).helpers)
    }

    @Test fun savedHelpersStayExactlyAsTheyAre() {
        // Someone who had Claude (the default before 1.0.0) keeps it, with every setting, and so does a list without it.
        val claude = HelperConfig.claude().copy(route = AskRoute.APP, locked = true)
        val gpt = HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")
        for (list in listOf(listOf(claude), listOf(claude, gpt), listOf(gpt), emptyList())) {
            val s = RobotSettings(helpers = list, helpDefault = list.lastOrNull()?.id)
            assertEquals(list, RobotSettings.fromJson(s.toJson().toString()).helpers)
        }
    }

    @Test fun anEmptyOrDefaultFileKeepsTodaysDefaults() {
        // Files from before 0.6.0 (no "helpers"): Claude, as those versions had it on.
        for (text in listOf("{}", """{"schema":8}""", """{"schema":12}""")) {
            val s = RobotSettings.fromJson(text)
            assertEquals(listOf(HelperConfig.claude()), s.helpers)
            assertNull(s.claude!!.enabled)
            assertNull(s.claude!!.route)
            assertEquals(AnthropicModel.OPUS.id, s.claude!!.model)
            assertTrue(s.claude!!.search && !s.claude!!.locked && !s.claude!!.trust && s.claude!!.actions)
            assertEquals(100, s.helperBudgetCents)
            assertTrue(s.helperShowCost)
            // Without a key: the Claude app, Ask me first; with one: the API, Automatic.
            assertEquals(AskRoute.APP, ClaudeSettings.route(s, false))
            assertEquals(Escalation.ASK, ClaudeSettings.escalation(s, false))
            assertEquals(AskRoute.API, ClaudeSettings.route(s, true))
            assertEquals(Escalation.AUTO, ClaudeSettings.escalation(s, true))
        }
    }

    /** Schema 13 (0.6.0; the helpers' branch called it 9): the list, and the claude* mirror an older Robot reads. */
    @Test fun schemaThirteenWritesTheListAndTheLegacyMirror() {
        val s = RobotSettings.fromJson(v031).withHelper(gemini())
        val j = s.toJson()
        assertEquals(13, j.getInt("schema"))
        assertEquals(2, j.getJSONArray("helpers").length())
        // The mirror an older Robot reads: Claude as set, the budget, the cost line and the escalation.
        assertEquals(true, j.getBoolean("claudeEnabled"))
        assertEquals("API", j.getString("claudeRoute"))
        assertEquals("claude-haiku-5-5", j.getString("claudeModel"))
        assertEquals("HIGH", j.getString("claudeEffort"))
        assertEquals(false, j.getBoolean("claudeSearch"))
        assertEquals(true, j.getBoolean("claudeLocked"))
        assertEquals("bf_emma", j.getString("claudeVoice"))
        assertEquals(false, j.getBoolean("claudeShowCost"))
        assertEquals("ASK", j.getString("claudeHelp"))
        assertEquals(250, j.getInt("claudeBudgetCents"))
        // Round trip.
        assertEquals(s, RobotSettings.fromJson(j.toString()))
        // An older reader sees only the mirror: strip the new fields and read it back as 0.4.5 would have, Claude unchanged.
        val legacy = JSONObject(j.toString()).apply { remove("helpers"); remove("help"); remove("helperBudgetCents"); remove("helperShowCost"); put("schema", 12) }
        assertEquals(s.claude, RobotSettings.fromJson(legacy.toString()).claude)
        // Claude removed: an older Robot reads Claude as off.
        val noClaude = s.copy(helpers = listOf(gemini()))
        assertEquals(false, noClaude.toJson().getBoolean("claudeEnabled"))
        assertEquals(listOf(gemini()), RobotSettings.fromJson(noClaude.toJson().toString()).helpers)
        assertNull(RobotSettings.fromJson(noClaude.toJson().toString()).claude)
    }

    private fun gemini() = HelperConfig.of(Presets.GEMINI, "h1abcde", "am_puck")
    private fun groq() = HelperConfig.of(Presets.GROQ, "hgroq", "bf_emma")
    /** Claude as installs before 1.0.0 had it (on, no key: its app); a 1.0.0 install has no helper. */
    private val legacy = RobotSettings(helpers = listOf(HelperConfig.claude()))
    private fun home(id: String = "h2", trust: Boolean = false) =
        HelperConfig.of(Presets.HOME, id, "af_bella", "Jarvis", "http://192.168.1.20:11434/v1").copy(model = "qwen3.8:27b", trust = trust)

    @Test fun damagedEntriesAreDroppedIdsAreUnique() {
        val text = """{"schema":9,"helpers":[
            {"id":"claude","preset":"anthropic","name":"Claude","model":"claude-opus-5-5"},
            {"id":"claude","preset":"gemini","name":"Twin","model":"gemini-3.8-flash"},
            {"id":"BAD ID","preset":"gemini","name":"X","model":"m"},
            {"id":"h9","preset":"no-such-preset","name":"X","model":"m"},
            {"id":"h8","preset":"home","name":"No address","model":"m"},
            "not an object",
            {"id":"h7","preset":"home","name":"Jarvis!!","model":"qwen3.8:27b","baseUrl":"http://10.0.0.5:8080/v1","trust":true,"voice":"nope",
             "prices":{"input":-3,"output":1},"budgetCents":50}
        ]}"""
        val s = RobotSettings.fromJson(text)
        assertEquals(listOf("claude", "h7"), s.helpers.map { it.id })
        val h7 = s.helper("h7")!!
        // Cleaned: the name's punctuation goes, an unknown voice is the default, impossible prices are dropped.
        assertEquals("Jarvis", h7.name)
        assertEquals("am_michael", h7.voice)
        assertNull(h7.prices)
        assertEquals(50, h7.budgetCents)
        assertTrue(h7.trust)
        // An empty list stays empty: every helper was removed.
        assertEquals(emptyList<HelperConfig>(), RobotSettings.fromJson("""{"schema":9,"helpers":[]}""").helpers)
        // A Claude model Robot doesn't know is the default one, as before.
        assertEquals(AnthropicModel.OPUS.id, RobotSettings.fromJson("""{"schema":9,"helpers":[{"id":"claude","preset":"anthropic","name":"Claude","model":"claude-9"}]}""").claude!!.model)
    }

    @Test fun theHelperRobotChecksWith() {
        val s = legacy.withHelper(gemini()).withHelper(home())
        fun keys(vararg ids: String): (HelperConfig) -> Boolean = { it.id in ids }
        // Nothing chosen: the first Robot can ask itself; a home server needs no key.
        assertEquals("h2", HelperSettings.target(s, keys())?.id)
        assertEquals("claude", HelperSettings.target(s, keys("claude"))?.id)
        assertEquals("h1abcde", HelperSettings.target(s, keys("h1abcde"))?.id)
        // Chosen: that one, even without its key (it then offers, and Settings says why).
        assertEquals("h1abcde", HelperSettings.target(s.copy(helpDefault = "h1abcde"), keys("claude"))?.id)
        // A chosen helper that was removed or turned off: the first that can answer through its API.
        assertEquals("claude", HelperSettings.target(s.copy(helpDefault = "gone"), keys("claude"))?.id)
        assertEquals("claude", HelperSettings.target(s.copy(helpDefault = "h2").withHelper(home().copy(enabled = false)), keys("claude"))?.id)
        // No API anywhere: Claude's app (Ask me first). Nothing at all: none.
        val onlyCloud = legacy.withHelper(gemini())
        assertEquals("claude", HelperSettings.target(onlyCloud, keys())?.id)
        // 1.0.0: Gemini without a key opens its app too; a company without an app and without a key: none.
        assertEquals("h1abcde", HelperSettings.target(RobotSettings(helpers = listOf(gemini())), keys())?.id)
        assertNull(HelperSettings.target(RobotSettings(helpers = listOf(groq())), keys()))
    }

    @Test fun askTheHelperGoesOnlyToOneThatCanAnswer() {
        fun keys(vararg ids: String): (HelperConfig) -> Boolean = { it.id in ids }
        fun apps(vararg pkgs: String): (String) -> Boolean = { it in pkgs }
        val claudeApp = Presets.ANTHROPIC.app!!
        // Claude as installs before 1.0.0 had it: on, the app route, no key. Without the app nothing can take it: none, so Robot says so.
        val d = legacy
        assertNull(HelperSettings.askTarget(d, keys(), apps()))
        // Its key saved: Claude through its API. Its app installed: Claude through the app.
        assertEquals("claude", HelperSettings.askTarget(d, keys("claude"), apps())?.id)
        assertEquals("claude", HelperSettings.askTarget(d, keys(), apps(claudeApp))?.id)
        // Claude off: neither counts.
        val off = d.withHelper(d.claude!!.copy(enabled = false))
        assertNull(HelperSettings.askTarget(off, keys("claude"), apps(claudeApp)))
        // A helper with a key wins over an app to open; a home server needs no key.
        val s = d.withHelper(gemini()).withHelper(home())
        assertEquals("h2", HelperSettings.askTarget(s, keys(), apps(claudeApp))?.id)
        assertEquals("h1abcde", HelperSettings.askTarget(d.withHelper(gemini()), keys("h1abcde"), apps(claudeApp))?.id)
        // Your "Ask:" choice wins whenever it can answer…
        assertEquals("h1abcde", HelperSettings.askTarget(s.copy(helpDefault = "h1abcde"), keys("h1abcde"), apps())?.id)
        assertEquals("claude", HelperSettings.askTarget(s.copy(helpDefault = "claude"), keys(), apps(claudeApp))?.id)
        // …and not when it can't (no key, or its app missing): the next one that can.
        assertEquals("h2", HelperSettings.askTarget(s.copy(helpDefault = "h1abcde"), keys(), apps())?.id)
        assertEquals("h2", HelperSettings.askTarget(s.copy(helpDefault = "claude"), keys(), apps())?.id)
        // Claude set to the API without a key, its app installed: not ready either way.
        val apiOnly = d.withHelper(d.claude!!.copy(route = app.heyrobot.core.AskRoute.API))
        assertNull(HelperSettings.askTarget(apiOnly, keys(), apps(claudeApp)))
        // No helpers at all.
        assertNull(HelperSettings.askTarget(RobotSettings(helpers = emptyList()), keys(), apps(claudeApp)))
    }

    @Test fun whatAHelperStillNeedsIsSaid() {
        assertEquals("Claude can't answer yet. Add its API key in Robot's settings, under Helpers, or install the Claude app.",
            HelperWords(HelperWho.of(HelperConfig.claude())).notReady)
        assertEquals("Gemini can't answer yet. Add its API key in Robot's settings, under Helpers, or install the Gemini app.", HelperWords(HelperWho.of(gemini())).notReady)
        assertEquals("Groq can't answer yet. Add its API key in Robot's settings, under Helpers.", HelperWords(HelperWho.of(groq())).notReady)
    }

    @Test fun escalationForAnyHelper() {
        val s = legacy.withHelper(home()).withHelper(gemini()).withHelper(groq())
        val h = home()
        fun d(target: HelperConfig?, key: Boolean = false, personal: Boolean = false, locked: Boolean = false, budget: Boolean = false,
            price: Boolean = true, search: Boolean = false, s2: RobotSettings = s) =
            EscalationPolicy.decide(s2, target, key, personal, locked, budget, priceKnown = price, needsSearch = search)
        // A home server needs no key: Automatic by default.
        assertEquals(EscalationDecision.AUTO, d(h))
        // A company without a key and without an app: nothing to offer. With an app (1.0.0, Gemini's): its app is offered.
        assertEquals(EscalationDecision.NONE, d(groq()))
        assertEquals(EscalationDecision.OFFER, d(gemini()))
        assertEquals(EscalationDecision.AUTO, d(gemini(), key = true, price = gemini().price.known))
        // Unknown price: offered, never asked by itself.
        assertEquals(EscalationDecision.OFFER, d(h, price = false))
        val mistral = HelperConfig.of(Presets.MISTRAL, "h3", "am_puck")
        assertFalse(mistral.price.known)
        assertEquals(EscalationDecision.OFFER, EscalationPolicy.decide(s, mistral, true, false, false, false))
        // Current information: only a helper that can search goes by itself.
        assertEquals(EscalationDecision.OFFER, d(h, search = true))
        val openai = HelperConfig.of(Presets.OPENAI, "h4", "am_puck")
        assertTrue(openai.searching)
        assertEquals(EscalationDecision.AUTO, d(openai, key = true, search = true))
        // Personal content, the budget, the lock screen.
        assertEquals(EscalationDecision.OFFER, d(h, personal = true))
        assertEquals(EscalationDecision.OFFER, d(h, budget = true))
        assertEquals(EscalationDecision.NEEDS_UNLOCK, d(h, locked = true))
        assertEquals(EscalationDecision.AUTO, d(h.copy(locked = true), locked = true))
        // Off, or none.
        assertEquals(EscalationDecision.NONE, d(h, s2 = s.copy(help = Escalation.OFF)))
        assertEquals(EscalationDecision.NONE, d(h.copy(enabled = false)))
        assertEquals(EscalationDecision.NONE, d(null))
        assertEquals(EscalationDecision.OFFER, d(h, s2 = s.copy(help = Escalation.ASK)))
    }

    /**
     * 0.4.6's hedges with helpers: a reply that says its facts may be out of date, or that it can't go online, needs a
     * helper that searches, as current information does; Claude, which searches, is asked by itself as in 0.4.6, a server
     * that can't search is offered. A plain refusal or an empty reply goes to either.
     */
    @Test fun aHedgeAboutTodaysFactsGoesByItselfOnlyToAHelperThatSearches() {
        for (r in listOf(
            "I don't have access to real-time information, so I can't tell you the latest news about the Artemis program.",
            "I'm unable to browse the internet, but I can tell you about the weather in general.",
            "As a large language model, I don't have access to current events.",
            "My knowledge cutoff is in 2024, so I may not have the latest information.",
            "Je n'ai pas accès à Internet.",
            "J'ai pas accès aux nouvelles en temps réel.",
            "Je ne peux pas vérifier l'actualité.",
        )) {
            assertTrue(r, Hedge.detect(r))
            assertTrue(r, Hedge.needsSearch(r))
        }
        for (r in listOf("I'm sorry, but I can't provide information about political figures.", "I can't help with that.",
            "Je ne peux pas faire ça.", "Je sais pas.", "")) {
            assertTrue(r, Hedge.detect(r))
            assertFalse(r, Hedge.needsSearch(r))
        }
        val s = legacy.withHelper(home())
        val claude = s.claude!!
        assertTrue(claude.searching)
        val stale = Hedge.needsSearch("I don't have access to real-time information.")
        assertEquals(EscalationDecision.AUTO, EscalationPolicy.decide(s, claude, true, false, false, false, needsSearch = stale))
        assertEquals(EscalationDecision.OFFER, EscalationPolicy.decide(s, home(), false, false, false, false, needsSearch = stale))
        val refusal = Hedge.needsSearch("I can't help with that.")
        assertEquals(EscalationDecision.AUTO, EscalationPolicy.decide(s, home(), false, false, false, false, needsSearch = refusal))
    }

    @Test fun trustAndThePersonalSwitchFitTogether() {
        val s = RobotSettings()
        val untrusted = home()
        val trusted = home(trust = true)
        // Off by default, for every helper, home servers included.
        assertFalse(HelperConfig.of(Presets.HOME, "x", "am_puck", baseUrl = "http://10.0.0.1/v1").trust)
        assertFalse(HelperTrust.conversation(s, untrusted, setOf(Personal.CALENDAR)))
        assertTrue(HelperTrust.conversation(s, trusted, setOf(Personal.CALENDAR)))
        assertTrue(HelperTrust.conversation(s.copy(personalToHelpers = true), untrusted, setOf(Personal.CALENDAR)))
        // A conversation that had your screen or a photo: its last turns go by themselves only with the switch for all
        // helpers, never on trust alone (Robot's own words may repeat what was on the screen).
        assertFalse(HelperTrust.conversation(s, trusted, setOf(Personal.CALENDAR, Personal.SCREEN)))
        assertFalse(HelperTrust.conversation(s, trusted, setOf(Personal.PHOTO)))
        assertTrue(HelperTrust.conversation(s.copy(personalToHelpers = true), untrusted, setOf(Personal.SCREEN)))
        // A screen or photo with the question: only the switch for every helper lets it go without a tap, never trust.
        assertFalse(HelperTrust.attachments(s))
        assertTrue(HelperTrust.attachments(s.copy(personalToHelpers = true)))
        // Joining: a trusted helper skips Include, unless the name was misheard or a screen or photo is in the conversation.
        assertTrue(HelperTrust.joinWithoutCard(trusted, setOf(Personal.CALENDAR), misheard = false))
        assertFalse(HelperTrust.joinWithoutCard(trusted, setOf(Personal.CALENDAR), misheard = true))
        assertFalse(HelperTrust.joinWithoutCard(trusted, setOf(Personal.SCREEN), misheard = false))
        assertFalse(HelperTrust.joinWithoutCard(untrusted, setOf(Personal.CALENDAR), misheard = false))
        // A spoken yes.
        assertTrue(HelperTrust.voiceYes(s, untrusted, emptySet()))
        assertFalse(HelperTrust.voiceYes(s, untrusted, setOf(Personal.NOTES)))
        assertTrue(HelperTrust.voiceYes(s, trusted, setOf(Personal.NOTES)))
        assertFalse(HelperTrust.voiceYes(s, trusted, setOf(Personal.PHOTO)))
        assertTrue(HelperTrust.voiceYes(s.copy(voiceYesPersonal = true), untrusted, setOf(Personal.PHOTO)))
        assertTrue(HelperTrust.reads(s, trusted) && !HelperTrust.reads(s, untrusted))
    }

    @Test fun wordsTakeTheHelpersName() {
        // Claude's words are what they were.
        assertEquals("Claude declined that one.", ClaudeWords.DECLINED)
        assertEquals("Your Claude key was refused. Check it in Robot's settings.", HelperFailure.KEY_REFUSED.text)
        assertEquals("Your Anthropic account is out of API credits. Add credits at console.anthropic.com.", HelperFailure.NO_CREDITS.text)
        assertEquals("Add your Claude API key in Robot's settings first, or choose the Claude app there.", HelperFailure.NO_KEY.text)
        assertEquals("Robot lost track of the conversation with Claude, so Claude mode has ended. Ask again to start fresh.", HelperFailure.RESET.text)
        assertEquals(HelperWho.CLAUDE, HelperWho.of(HelperConfig.claude()))
        val gpt = HelperWho.of(HelperConfig.of(Presets.OPENAI, "h1", "am_puck"))
        assertEquals("Your ChatGPT key was refused. Check it in Robot's settings.", HelperFailure.KEY_REFUSED.say(gpt))
        assertEquals("Your OpenAI account is out of API credits. Add credits at platform.openai.com.", HelperFailure.NO_CREDITS.say(gpt))
        assertEquals("Add your ChatGPT API key in Robot's settings first, or choose the ChatGPT app there.", HelperFailure.NO_KEY.say(gpt))
        assertEquals("Add your Groq API key in Robot's settings first.", HelperFailure.NO_KEY.say(HelperWho.of(groq())))
        val jarvis = HelperWho.of(home())
        assertEquals("Jarvis isn't answering. Is the computer on, and is the phone on the same network?", HelperFailure.UNREACHABLE.say(jarvis))
        assertEquals("Jarvis doesn't have that model any more. Pick another one in Robot's settings, under Helpers.", HelperFailure.MODEL_UNAVAILABLE.say(jarvis))
        assertEquals("Jarvis is loading its model…", HelperWords(jarvis).loading)
        assertEquals("Want me to check with Jarvis?", HelperWords(jarvis).offer)
        // A saved key the Keystore couldn't read just now is kept, and Robot says so in the helper's name (the updater's
        // words for Claude's key); one that could never be read again was removed.
        assertEquals("Robot couldn't read your saved Claude key just now. It's still saved, so try again in a moment.", ClaudeWords.KEY_UNAVAILABLE)
        assertEquals("Robot couldn't read your saved ChatGPT key just now. It's still saved, so try again in a moment.", HelperWords(gpt).keyUnavailable)
        assertEquals("Robot couldn't read your saved Jarvis key just now. It's still saved, so try again in a moment.", HelperWords(jarvis).keyUnavailable)
        assertEquals("Robot couldn't read your saved ChatGPT key, so it was removed. Add it again in Robot's settings, under Helpers.", HelperWords(gpt).keyUnreadable)
    }

    @Test fun failuresReadTheCodeBeforeTheStatus() {
        // OpenAI says "out of credit" with a 429: never "busy".
        assertEquals(HelperFailure.NO_CREDITS, HelperFailure.of(429, "insufficient_quota", "insufficient_quota"))
        assertEquals(HelperFailure.NO_CREDITS, HelperFailure.of(429, null, "credit_balance_exhausted"))
        assertEquals(HelperFailure.NO_CREDITS, HelperFailure.of(429, null, "monthly_spend_limit_exceeded"))
        assertEquals(HelperFailure.RATE_LIMITED, HelperFailure.of(429, "rate_limit_error", "slow_down"))
        // DeepSeek and OpenRouter: 402.
        assertEquals(HelperFailure.NO_CREDITS, HelperFailure.of(402, null))
        // OpenAI's overload code and Groq's 498: one retry.
        assertEquals(HelperFailure.OVERLOADED, HelperFailure.of(503, null, "server_is_overloaded"))
        assertEquals(HelperFailure.OVERLOADED, HelperFailure.of(498, null))
        assertTrue(HelperFailure.OVERLOADED.retry)
        // Anthropic's as before.
        assertEquals(HelperFailure.SERVER, HelperFailure.of(503, null))
        assertEquals(HelperFailure.BAD_REQUEST, HelperFailure.of(422, null))
    }

    @Test fun presetsAreWhatTheDesignVerified() {
        assertEquals(listOf("anthropic", "openai", "gemini", "mistral", "deepseek", "groq", "openrouter", "xai", "home", "compatible"), Presets.ALL.map { it.id })
        // Every cloud base URL is https, and every shipped price belongs to a model a preset lists (or Claude's).
        for (p in Presets.ALL) p.baseUrl?.let { assertTrue(it, it.startsWith("https://") && !it.endsWith("/")) }
        val listed = Presets.ALL.flatMap { p -> p.models.map { it.id } }.toSet()
        for (id in PriceTable.SHIPPED.keys) assertTrue(id, id in listed)
        // Search only where the connection has it; Groq's price isn't published, so none.
        assertFalse(Presets.GROQ.canSearch || Presets.MISTRAL.canSearch || Presets.GEMINI.canSearch || Presets.DEEPSEEK.canSearch || Presets.HOME.canSearch)
        assertTrue(Presets.OPENAI.canSearch && Presets.OPENROUTER.canSearch && Presets.ANTHROPIC.canSearch && Presets.XAI.canSearch)
        // OpenRouter's search may use a third company (Exa): off until you turn it on.
        assertFalse(HelperConfig.of(Presets.OPENROUTER, "h1", "am_puck").search)
        assertTrue(HelperConfig.of(Presets.OPENAI, "h1", "am_puck").search)
        // Mistral thinks fully or not at all; DeepSeek's high is "max".
        assertEquals("none", EffortStyle.MISTRAL.wire(app.heyrobot.core.HelperEffort.LOW))
        assertEquals("high", EffortStyle.MISTRAL.wire(app.heyrobot.core.HelperEffort.MEDIUM))
        assertEquals("max", EffortStyle.DEEPSEEK.wire(app.heyrobot.core.HelperEffort.HIGH))
        assertNull(EffortStyle.NONE.wire(app.heyrobot.core.HelperEffort.HIGH))
    }

    @Test fun newIdsAndVoices() {
        var n = 0
        val seq = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1)
        assertEquals("hbbbbbb", HelperConfig.newId(setOf("haaaaaa")) { seq[n++] })
        // A new helper gets a voice nobody uses: not Claude's, not Robot's own speaker.
        val s = legacy
        assertEquals("am_fenrir", HelperSettings.freeVoice(s, robotSpeaker = 3))
        assertEquals("am_fenrir", HelperSettings.freeVoice(s.withHelper(home().copy(voice = "af_heart")), robotSpeaker = 3))
        assertEquals("am_puck", HelperSettings.freeVoice(s.withHelper(home().copy(voice = "am_fenrir")), robotSpeaker = 3))
    }
}
