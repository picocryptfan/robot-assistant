package app.heyrobot.core.helpers

import app.heyrobot.core.ClaudeEffortSetting
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeModel
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationPolicy
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
        assertEquals(HelperConfig(id = "claude", preset = "anthropic", name = "Claude", model = "claude-haiku-5-5", enabled = true, route = ClaudeRoute.API,
            effort = ClaudeEffortSetting.HIGH, search = false, voice = "bf_emma", locked = true), c)
        assertEquals(Escalation.ASK, s.help)
        assertEquals(250, s.helperBudgetCents)
        assertFalse(s.helperShowCost)
        assertNull(s.helpDefault)
        // The same behaviour: route, escalation and the local tool as 0.3.1 computed them.
        assertEquals(ClaudeRoute.API, ClaudeSettings.route(s, keySaved = false))
        assertEquals(Escalation.ASK, ClaudeSettings.escalation(s, keySaved = true))
        assertTrue(ClaudeSettings.localTool(s, keySaved = true))
    }

    @Test fun anEmptyOrDefaultFileKeepsTodaysDefaults() {
        for (text in listOf("{}", """{"schema":8}""", null)) {
            val s = RobotSettings.fromJson(text)
            assertEquals(listOf(HelperConfig.claude()), s.helpers)
            assertNull(s.claude!!.enabled)
            assertNull(s.claude!!.route)
            assertEquals(ClaudeModel.OPUS.id, s.claude!!.model)
            assertTrue(s.claude!!.search && !s.claude!!.locked && !s.claude!!.trust && s.claude!!.actions)
            assertEquals(100, s.helperBudgetCents)
            assertTrue(s.helperShowCost)
            // Without a key: the Claude app, Ask me first; with one: the API, Automatic.
            assertEquals(ClaudeRoute.APP, ClaudeSettings.route(s, false))
            assertEquals(Escalation.ASK, ClaudeSettings.escalation(s, false))
            assertEquals(ClaudeRoute.API, ClaudeSettings.route(s, true))
            assertEquals(Escalation.AUTO, ClaudeSettings.escalation(s, true))
        }
    }

    @Test fun schemaNineWritesTheListAndTheLegacyMirror() {
        val s = RobotSettings.fromJson(v031).withHelper(gemini())
        val j = s.toJson()
        assertEquals(9, j.getInt("schema"))
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
        // An older reader sees only the mirror: strip the new fields and read it back as 0.5 would have, Claude unchanged.
        val legacy = JSONObject(j.toString()).apply { remove("helpers"); remove("help"); remove("helperBudgetCents"); remove("helperShowCost"); put("schema", 8) }
        assertEquals(s.claude, RobotSettings.fromJson(legacy.toString()).claude)
        // Claude removed: an older Robot reads Claude as off.
        val noClaude = s.copy(helpers = listOf(gemini()))
        assertEquals(false, noClaude.toJson().getBoolean("claudeEnabled"))
        assertEquals(listOf(gemini()), RobotSettings.fromJson(noClaude.toJson().toString()).helpers)
        assertNull(RobotSettings.fromJson(noClaude.toJson().toString()).claude)
    }

    private fun gemini() = HelperConfig.of(Presets.GEMINI, "h1abcde", "am_puck")
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
        assertEquals(ClaudeModel.OPUS.id, RobotSettings.fromJson("""{"schema":9,"helpers":[{"id":"claude","preset":"anthropic","name":"Claude","model":"claude-9"}]}""").claude!!.model)
    }

    @Test fun theHelperRobotChecksWith() {
        val s = RobotSettings().withHelper(gemini()).withHelper(home())
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
        val onlyCloud = RobotSettings().withHelper(gemini())
        assertEquals("claude", HelperSettings.target(onlyCloud, keys())?.id)
        assertNull(HelperSettings.target(RobotSettings(helpers = listOf(gemini())), keys()))
    }

    @Test fun escalationForAnyHelper() {
        val s = RobotSettings().withHelper(home()).withHelper(gemini())
        val h = home()
        fun d(target: HelperConfig?, key: Boolean = false, personal: Boolean = false, locked: Boolean = false, budget: Boolean = false,
            price: Boolean = true, search: Boolean = false, s2: RobotSettings = s) =
            EscalationPolicy.decide(s2, target, key, personal, locked, budget, priceKnown = price, needsSearch = search)
        // A home server needs no key: Automatic by default.
        assertEquals(EscalationDecision.AUTO, d(h))
        // A company without a key and without an app: nothing to offer.
        assertEquals(EscalationDecision.NONE, d(gemini()))
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

    @Test fun trustAndThePersonalSwitchFitTogether() {
        val s = RobotSettings()
        val untrusted = home()
        val trusted = home(trust = true)
        // Off by default, for every helper, home servers included.
        assertFalse(HelperConfig.of(Presets.HOME, "x", "am_puck", baseUrl = "http://10.0.0.1/v1").trust)
        assertFalse(HelperTrust.conversation(s, untrusted))
        assertTrue(HelperTrust.conversation(s, trusted))
        assertTrue(HelperTrust.conversation(s.copy(personalToHelpers = true), untrusted))
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
        assertEquals("Your Claude key was refused. Check it in Robot's settings.", ClaudeFailure.KEY_REFUSED.text)
        assertEquals("Your Anthropic account is out of API credits. Add credits at console.anthropic.com.", ClaudeFailure.NO_CREDITS.text)
        assertEquals("Add your Claude API key in Robot's settings first, or choose the Claude app there.", ClaudeFailure.NO_KEY.text)
        assertEquals("Robot lost track of the conversation with Claude, so Claude mode has ended. Ask again to start fresh.", ClaudeFailure.RESET.text)
        assertEquals(HelperWho.CLAUDE, HelperWho.of(HelperConfig.claude()))
        val gpt = HelperWho.of(HelperConfig.of(Presets.OPENAI, "h1", "am_puck"))
        assertEquals("Your ChatGPT key was refused. Check it in Robot's settings.", ClaudeFailure.KEY_REFUSED.say(gpt))
        assertEquals("Your OpenAI account is out of API credits. Add credits at platform.openai.com.", ClaudeFailure.NO_CREDITS.say(gpt))
        assertEquals("Add your ChatGPT API key in Robot's settings first.", ClaudeFailure.NO_KEY.say(gpt))
        val jarvis = HelperWho.of(home())
        assertEquals("Your server Jarvis isn't answering. Is it on, and is Robot on the same network?", ClaudeFailure.UNREACHABLE.say(jarvis))
        assertEquals("Jarvis doesn't have that model any more. Pick another one in Robot's settings, under Helpers.", ClaudeFailure.MODEL_UNAVAILABLE.say(jarvis))
        assertEquals("Jarvis is loading its model…", HelperWords(jarvis).loading)
        assertEquals("Want me to check with Jarvis?", HelperWords(jarvis).offer)
    }

    @Test fun failuresReadTheCodeBeforeTheStatus() {
        // OpenAI says "out of credit" with a 429: never "busy".
        assertEquals(ClaudeFailure.NO_CREDITS, ClaudeFailure.of(429, "insufficient_quota", "insufficient_quota"))
        assertEquals(ClaudeFailure.NO_CREDITS, ClaudeFailure.of(429, null, "credit_balance_exhausted"))
        assertEquals(ClaudeFailure.NO_CREDITS, ClaudeFailure.of(429, null, "monthly_spend_limit_exceeded"))
        assertEquals(ClaudeFailure.RATE_LIMITED, ClaudeFailure.of(429, "rate_limit_error", "slow_down"))
        // DeepSeek and OpenRouter: 402.
        assertEquals(ClaudeFailure.NO_CREDITS, ClaudeFailure.of(402, null))
        // OpenAI's overload code and Groq's 498: one retry.
        assertEquals(ClaudeFailure.OVERLOADED, ClaudeFailure.of(503, null, "server_is_overloaded"))
        assertEquals(ClaudeFailure.OVERLOADED, ClaudeFailure.of(498, null))
        assertTrue(ClaudeFailure.OVERLOADED.retry)
        // Anthropic's as before.
        assertEquals(ClaudeFailure.SERVER, ClaudeFailure.of(503, null))
        assertEquals(ClaudeFailure.BAD_REQUEST, ClaudeFailure.of(422, null))
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
        assertEquals("none", EffortStyle.MISTRAL.wire(app.heyrobot.core.ClaudeEffort.LOW))
        assertEquals("high", EffortStyle.MISTRAL.wire(app.heyrobot.core.ClaudeEffort.MEDIUM))
        assertEquals("max", EffortStyle.DEEPSEEK.wire(app.heyrobot.core.ClaudeEffort.HIGH))
        assertNull(EffortStyle.NONE.wire(app.heyrobot.core.ClaudeEffort.HIGH))
    }

    @Test fun newIdsAndVoices() {
        var n = 0
        val seq = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1)
        assertEquals("hbbbbbb", HelperConfig.newId(setOf("haaaaaa")) { seq[n++] })
        // A new helper gets a voice nobody uses: not Claude's, not Robot's own speaker.
        val s = RobotSettings()
        assertEquals("am_fenrir", HelperSettings.freeVoice(s, robotSpeaker = 3))
        assertEquals("am_fenrir", HelperSettings.freeVoice(s.withHelper(home().copy(voice = "af_heart")), robotSpeaker = 3))
        assertEquals("am_puck", HelperSettings.freeVoice(s.withHelper(home().copy(voice = "am_fenrir")), robotSpeaker = 3))
    }
}
