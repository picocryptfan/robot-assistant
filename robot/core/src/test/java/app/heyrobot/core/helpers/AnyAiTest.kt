package app.heyrobot.core.helpers

import app.heyrobot.core.AppHandOff
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeCommand
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeRouter
import app.heyrobot.core.HelperName
import app.heyrobot.core.RobotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 1.0.0: the helpers work the same with any AI. Each company with an Android app can take a question in its app without a
// key; each helper answers to the name people say for it, or to the one you gave it.
class AnyAiTest {
    private fun of(p: Preset, id: String) = HelperConfig.of(p, id, "am_puck")

    @Test fun theAppsArePinnedByPackage() {
        // Checked against each app's Play Store listing (13 Oct 2026). Changing one changes where your question goes.
        assertEquals(mapOf(
            "anthropic" to "com.anthropic.claude",
            "openai" to "com.openai.chatgpt",
            "gemini" to "com.google.android.apps.bard",
            "mistral" to "ai.mistral.chat",
            "deepseek" to "com.deepseek.chat",
            "xai" to "ai.x.grok",
        ), Presets.ALL.filter { it.app != null }.associate { it.id to it.app })
        // A company without an app, your server and another company: their API only.
        for (p in listOf(Presets.GROQ, Presets.OPENROUTER, Presets.HOME, Presets.OTHER)) assertNull(p.id, p.app)
    }

    @Test fun withoutAKeyAQuestionOpensTheApp() {
        for (p in Presets.ALL.filter { it.app != null }) {
            val h = of(p, "h1")
            assertEquals(p.id, ClaudeRoute.APP, HelperSettings.route(h, keySaved = false))
            assertEquals(p.id, ClaudeRoute.API, HelperSettings.route(h, keySaved = true))
            // You can keep the app with a key, or choose the API without one (then it needs the key).
            assertEquals(ClaudeRoute.APP, HelperSettings.route(h.copy(route = ClaudeRoute.APP), keySaved = true))
            assertFalse(HelperSettings.apiReady(h.copy(route = ClaudeRoute.API), keySaved = false))
            // Saved and read back with its route.
            assertEquals(ClaudeRoute.APP, HelperConfig.fromJson(h.copy(route = ClaudeRoute.APP).toJson())!!.route)
        }
        // Without an app, always the API.
        assertEquals(ClaudeRoute.API, HelperSettings.route(of(Presets.GROQ, "h1").copy(route = ClaudeRoute.APP), keySaved = false))
        // "Ask the helper" goes to one whose app is installed, by its own package.
        val s = RobotSettings(helpers = listOf(of(Presets.OPENAI, "hgpt"), of(Presets.XAI, "hgrk")))
        assertEquals("hgrk", HelperSettings.askTarget(s, { false }) { it == "ai.x.grok" }?.id)
        assertEquals("hgpt", HelperSettings.askTarget(s, { false }) { it == "com.openai.chatgpt" }?.id)
        assertNull(HelperSettings.askTarget(s, { false }) { it == ClaudeAppLink.PACKAGE })
        // What one still needs names its own app.
        assertEquals("Grok can't answer yet. Add its API key in Robot's settings, under Helpers, or install the Grok app.",
            HelperWords(HelperWho.of(of(Presets.XAI, "hgrk"))).notReady)
    }

    @Test fun theHandOffIsBuiltPerApp() {
        // The Claude app keeps its links first; every other app gets a share, else just opens.
        assertEquals(listOf(ClaudeAppLink.Way.APP_LINK, ClaudeAppLink.Way.WEB_LINK, ClaudeAppLink.Way.SEND, ClaudeAppLink.Way.LAUNCH),
            AppHandOff.ways(ClaudeAppLink.PACKAGE))
        for (pkg in listOf("com.openai.chatgpt", "ai.x.grok", "com.google.android.apps.bard", "ai.mistral.chat", "com.deepseek.chat")) {
            assertEquals(pkg, listOf(ClaudeAppLink.Way.SEND, ClaudeAppLink.Way.LAUNCH), AppHandOff.ways(pkg))
            assertEquals(ClaudeAppLink.Way.SEND, AppHandOff.choose(pkg, installed = true) { true })
            assertEquals(ClaudeAppLink.Way.LAUNCH, AppHandOff.choose(pkg, installed = true) { it == ClaudeAppLink.Way.LAUNCH })
            assertNull(AppHandOff.choose(pkg, installed = true) { false })
            assertNull(AppHandOff.choose(pkg, installed = false) { true })
            // A link the Claude app takes is never tried on another app.
            assertEquals(ClaudeAppLink.Way.SEND, AppHandOff.choose(pkg, installed = true) { it != ClaudeAppLink.Way.APP_LINK })
        }
        // The Claude app as before: its own link first.
        assertEquals(ClaudeAppLink.Way.APP_LINK, ClaudeAppLink.choose(true) { true })
        assertEquals(ClaudeAppLink.Way.SEND, ClaudeAppLink.choose(true) { it == ClaudeAppLink.Way.SEND || it == ClaudeAppLink.Way.LAUNCH })
        assertNull(ClaudeAppLink.choose(false) { true })
        // The card: what goes, to which app, and that you send it there. Then what happened.
        assertEquals("Robot opens the ChatGPT app with this text and copies it. Nothing is sent until you press Send in ChatGPT.",
            AppHandOff.note("ChatGPT", "why is the sky blue"))
        assertEquals("Robot opens the Grok app. Nothing goes to Grok from Robot.", AppHandOff.note("Grok", ""))
        assertEquals("Opened Gemini with your question: press Send there if it isn't sent. It's on the clipboard too.",
            AppHandOff.done("Gemini", ClaudeAppLink.Way.SEND, "why"))
        assertEquals("Opened DeepSeek. Paste your question in DeepSeek: it's on the clipboard.", AppHandOff.done("DeepSeek", ClaudeAppLink.Way.LAUNCH, "why"))
        assertEquals("Opened Mistral.", AppHandOff.done("Mistral", ClaudeAppLink.Way.LAUNCH, ""))
        assertEquals("Install ChatGPT.", AppHandOff.install("ChatGPT"))
        assertEquals("No helper opens an app. Set one up in Robot's settings, under Helpers.", AppHandOff.install(null))
    }

    @Test fun theAppOpenedIsAlwaysOneOfYourHelpers() {
        val gpt = of(Presets.OPENAI, "hgpt")
        val grok = of(Presets.XAI, "hgrk")
        val groq = of(Presets.GROQ, "hgrq")
        val s = RobotSettings(helpers = listOf(groq, gpt, grok))
        // Named: that helper, only when it is on and has an app.
        assertEquals("hgrk", HelperSettings.appHelper(s, "hgrk") { false }?.id)
        assertNull(HelperSettings.appHelper(s, "hgrq") { false })
        assertNull(HelperSettings.appHelper(s, "nobody") { false })
        assertNull(HelperSettings.appHelper(s.withHelper(grok.copy(enabled = false)), "hgrk") { false })
        // Unnamed: the one Robot checks with when it opens its app ("Ask:"), else the first that opens one.
        assertEquals("hgrk", HelperSettings.appHelper(s.copy(helpDefault = "hgrk"), null) { false }?.id)
        assertEquals("hgpt", HelperSettings.appHelper(s, null) { false }?.id)
        // ChatGPT with a key goes through its API: Grok's app is the one opened.
        assertEquals("hgrk", HelperSettings.appHelper(s, null) { it.id == "hgpt" }?.id)
        // None set up: nothing opens (a new install).
        assertNull(HelperSettings.appHelper(RobotSettings(), null) { false })
    }

    @Test fun routeWordsNameTheApp() {
        assertEquals("Claude app", HelperSettings.routeTitle(Presets.ANTHROPIC, ClaudeRoute.APP))
        assertEquals("Claude API", HelperSettings.routeTitle(Presets.ANTHROPIC, ClaudeRoute.API))
        assertEquals(ClaudeRoute.APP.detail, HelperSettings.routeDetail(Presets.ANTHROPIC, ClaudeRoute.APP))
        assertEquals("ChatGPT app", HelperSettings.routeTitle(Presets.OPENAI, ClaudeRoute.APP))
        assertEquals("OpenAI API", HelperSettings.routeTitle(Presets.OPENAI, ClaudeRoute.API))
        assertEquals("Opens the Grok app with your question; you press Send there, with your own account. Robot doesn't hear the answer.",
            HelperSettings.routeDetail(Presets.XAI, ClaudeRoute.APP))
    }

    // ------------------------------------------------------------------ call names

    /** Every way you call [name] gets to helper [id] in settings [s]. */
    private fun answersTo(s: RobotSettings, id: String, name: String) {
        val names = HelperName.all(s)
        fun route(t: String) = ClaudeRouter.route(t, names)
        assertEquals("ask $name", ClaudeCommand.Ask("why the sky is blue", helper = id), route("ask $name why the sky is blue"))
        assertEquals("go get $name", ClaudeCommand.Join(helper = id), route("go get $name"))
        assertEquals("$name, …", ClaudeCommand.Ask("what's a black hole?", helper = id), route("$name, what's a black hole?"))
        assertEquals("demande à $name", ClaudeCommand.Ask("pourquoi le ciel est bleu", helper = id), route("demande à $name pourquoi le ciel est bleu"))
        assertEquals("thanks $name", ClaudeCommand.Leave, route("thanks $name"))
    }

    @Test fun eachPresetAnswersToTheNamesPeopleSay() {
        val claude = HelperConfig.claude()
        val gpt = of(Presets.OPENAI, "hgpt")
        val grok = of(Presets.XAI, "hgrk")
        val gemini = of(Presets.GEMINI, "hgem")
        val mistral = of(Presets.MISTRAL, "hmis")
        val deep = of(Presets.DEEPSEEK, "hdee")
        val s = RobotSettings(helpers = listOf(claude, gpt, grok, gemini, mistral, deep))
        for (n in listOf("Claude", "claude")) answersTo(s, "claude", n)
        for (n in listOf("ChatGPT", "chat GPT", "GPT", "gpt")) answersTo(s, "hgpt", n)
        answersTo(s, "hgrk", "Grok")
        answersTo(s, "hgem", "Gemini")
        answersTo(s, "hmis", "Mistral")
        for (n in listOf("DeepSeek", "deep seek")) answersTo(s, "hdee", n)
        // Each alone too, the way a new install would have it.
        for (h in listOf(gpt, grok, gemini, mistral, deep)) answersTo(RobotSettings(helpers = listOf(h)), h.id, h.name)
        // Claude's mishearings still count after a command verb only.
        assertEquals(ClaudeCommand.Join(misheard = true), ClaudeRouter.route("get cloud", HelperName.all(s)))
        assertNull(ClaudeRouter.route("what's the cloud cover tomorrow", HelperName.all(s)))
        // The defaults are the names people say.
        assertEquals(listOf("Claude", "ChatGPT", "Grok", "Gemini", "Mistral", "DeepSeek"), s.helpers.map { it.name })
        // Every preset's name can be saved as it comes.
        for (p in Presets.ALL) assertFalse(p.id, HelperNames.check(p.defaultName, "new", RobotSettings(), setOf("hey", "robot")) is NameCheck.Problem)
    }

    @Test fun aRenamedHelperAnswersToItsNewName() {
        val wake = setOf("hey", "robot")
        val gpt = of(Presets.OPENAI, "hgpt")
        val base = RobotSettings(helpers = listOf(gpt))
        assertEquals(NameCheck.Ok, HelperNames.check("Jarvis", "hgpt", base, wake))
        val s = base.withHelper(gpt.copy(name = HelperNames.clean("Jarvis")))
        answersTo(s, "hgpt", "Jarvis")
        // The preset's spellings belong to its name: once renamed, "ChatGPT" and "GPT" are no longer it.
        val names = HelperName.all(s)
        assertNull(ClaudeRouter.route("ask ChatGPT why the sky is blue", names))
        assertNull(ClaudeRouter.route("ask GPT why the sky is blue", names))
        // An alias you add answers too.
        val aliased = s.withHelper(s.helper("hgpt")!!.copy(aliases = listOf("GPT")))
        answersTo(aliased, "hgpt", "GPT")
        // Claude renamed: its curated mishearings go with the old name; a word that only sounds like the new one counts
        // after a command verb, marked misheard (the conversation so far then needs a tap), as for any name.
        val claudia = RobotSettings(helpers = listOf(HelperConfig.claude().copy(name = "Claudia")))
        answersTo(claudia, "claude", "Claudia")
        assertEquals(ClaudeCommand.Join(misheard = true, helper = "claude"), ClaudeRouter.route("go get cloud", HelperName.all(claudia)))
        // Grok renamed "Max" (a common word: allowed, with a warning) answers to Max.
        val grok = of(Presets.XAI, "hgrk")
        assertTrue(HelperNames.check("Max", "hgrk", RobotSettings(helpers = listOf(grok)), wake) is NameCheck.Warning)
        answersTo(RobotSettings(helpers = listOf(grok.copy(name = "Max"))), "hgrk", "Max")
    }
}
