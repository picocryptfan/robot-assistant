package app.heyrobot.core.helpers

import app.heyrobot.core.ClaudeCommand
import app.heyrobot.core.ClaudeRouter
import app.heyrobot.core.FastPath
import app.heyrobot.core.HelperName
import app.heyrobot.core.Phonetic
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRouterTest {
    private val gpt = HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")
    private val jarvis = HelperConfig.of(Presets.HOME, "hjar", "af_bella", "Jarvis", "http://10.0.0.5:11434/v1")
    private val deep = HelperConfig.of(Presets.DEEPSEEK, "hdee", "bf_emma")
    // Claude as set up before 1.0.0 (a new install has no helper), and three others.
    private val s = RobotSettings(helpers = listOf(HelperConfig.claude())).withHelper(gpt).withHelper(jarvis).withHelper(deep)
    private val names = HelperName.all(s)
    private fun route(t: String) = ClaudeRouter.route(t, names)

    @Test fun eachHelperByItsName() {
        assertEquals(ClaudeCommand.Ask("why the sky is blue", helper = "hgpt"), route("ask ChatGPT why the sky is blue"))
        // Multi-word forms, the longest that fits.
        assertEquals(ClaudeCommand.Ask("why the sky is blue", helper = "hgpt"), route("hey robot, ask chat GPT why the sky is blue"))
        assertEquals(ClaudeCommand.Ask("about black holes", helper = "hdee"), route("ask deep seek about black holes"))
        assertEquals(ClaudeCommand.Ask("about black holes", helper = "hdee"), route("ask DeepSeek about black holes"))
        assertEquals(ClaudeCommand.Join(helper = "hjar"), route("go get Jarvis"))
        assertEquals(ClaudeCommand.Join("Let's talk about my day.", helper = "hjar"), route("talk to Jarvis about my day"))
        assertEquals(ClaudeCommand.Ask("what's the weather in Montreal?", helper = "hjar"), route("Jarvis, what's the weather in Montreal?"))
        assertEquals(ClaudeCommand.Ask("pourquoi le ciel est bleu", helper = "hjar"), route("demande à Jarvis pourquoi le ciel est bleu"))
        // Claude is still Claude, mishearings included.
        assertEquals(ClaudeCommand.Join(), route("go get Claude"))
        assertEquals(ClaudeCommand.Join(misheard = true), route("get cloud"))
        assertEquals(ClaudeCommand.Ask("about stars"), route("ask clyde about stars"))
        // Leaving works with any helper's name (and "back to Robot").
        for (t in listOf("thanks Jarvis", "bye chat gpt", "thank you DeepSeek", "back to Robot", "merci Jarvis")) assertEquals(t, ClaudeCommand.Leave, route(t))
        assertEquals(FastPath.Way.CLAUDE, FastPath.of(route("ask Jarvis to set a timer"), claudeMode = false, attachment = Attachment.NONE))
    }

    @Test fun aWordThatSoundsLikeANameCountsOnlyAfterACommandVerb() {
        // "Jervis" for "Jarvis": after a command verb, marked misheard (the conversation so far needs a tap).
        assertEquals(ClaudeCommand.Join(misheard = true, helper = "hjar"), route("go get Jervis"))
        assertEquals(ClaudeCommand.Ask("about the weather", helper = "hjar"), route("ask Jervis about the weather"))
        assertEquals(ClaudeCommand.Leave, route("thanks Jervis"))
        // Never on its own, never addressed, never with words about a person.
        assertNull(route("Jervis, what's the weather?"))
        assertNull(route("Jervis is coming at six"))
        assertNull(route("go get Jervis from the airport"))
        assertNull(route("ask Jervis to pick up milk"))
        // A different first sound is a different name: Travis is someone else.
        assertNull(route("ask Travis about dinner"))
        assertNull(route("go get Travis"))
        // The name itself, about someone: local.
        assertNull(route("Jarvis can come over tomorrow"))
        assertNull(route("what's Jarvis's address"))
        // Claude's name has its own curated list: no sound-alike matching for it.
        assertNull(route("ask cloth about this"))
    }

    @Test fun notThisHelper() {
        assertTrue(ClaudeRouter.declines("not Jarvis, you tell me", names))
        assertTrue(ClaudeRouter.declines("don't ask ChatGPT", names))
        assertTrue(ClaudeRouter.declines("without deep seek please", names))
        assertTrue(ClaudeRouter.declines("leave Jarvis out of it", names))
        assertTrue(ClaudeRouter.declines("ne demande pas à Jarvis", names))
        assertFalse(ClaudeRouter.declines("Jarvis is not here", names))
        assertFalse(ClaudeRouter.declines("it's not Jarvis, it's Sam", names))
        assertFalse(ClaudeRouter.declines("don't ask Jervis to pick up milk", names))
        // Claude's as before.
        assertTrue(ClaudeRouter.declines("not Claude", names))
    }

    @Test fun aYesMayNameTheHelper() {
        assertTrue(ClaudeRouter.isYes("yes, check with Jarvis", names))
        assertTrue(ClaudeRouter.isYes("yes ask chat gpt", names))
        assertFalse(ClaudeRouter.isYes("yes, check with Bob", names))
    }

    @Test fun phoneticKeys() {
        assertEquals(Phonetic.key("jarvis"), Phonetic.key("jervis"))
        assertTrue(Phonetic.same("groq", "grok"))
        assertTrue(Phonetic.close("jervis", "jarvis"))
        assertFalse(Phonetic.close("travis", "jarvis"))
        assertFalse(Phonetic.close("jar", "jarvis"))
        // Short names never match by sound.
        assertFalse(Phonetic.close("mac", "max"))
        assertFalse(Phonetic.eligible("max"))
        assertTrue(Phonetic.eligible("gemini") && Phonetic.eligible("jarvis") && Phonetic.eligible("nova"))
    }

    @Test fun nameRules() {
        val wake = setOf("hey", "robot", "computer")
        fun check(n: String, id: String = "new") = HelperNames.check(n, id, s, wake)
        assertEquals(NameCheck.Ok, check("Jarvis", "hjar"))
        assertEquals(NameCheck.Ok, check("Atlas"))
        assertTrue(check("") is NameCheck.Problem)
        assertTrue(check("my big home server") is NameCheck.Problem)
        assertTrue(check("Jar-vis") is NameCheck.Problem)
        assertTrue(check("Robot") is NameCheck.Problem)
        assertTrue(check("Computer") is NameCheck.Problem)
        assertTrue(check("Thanks") is NameCheck.Problem)
        assertTrue(check("please") is NameCheck.Problem)
        // Another helper's name, or a mishearing of Claude.
        assertTrue(check("Jarvis") is NameCheck.Problem)
        assertTrue(check("Cloud") is NameCheck.Problem)
        assertTrue(check("chat gpt") is NameCheck.Problem)
        // Sounds the same: Groq and Grok can't both be called that.
        val withGrok = s.withHelper(HelperConfig.of(Presets.XAI, "hgrk", "am_fenrir"))
        assertTrue(HelperNames.check("Groq", "new", withGrok, wake) is NameCheck.Problem)
        // A common word: allowed, with a warning.
        assertTrue(check("Max") is NameCheck.Warning)
    }

    @Test fun namesFromSettings() {
        val c = HelperName.of(HelperConfig.claude())
        assertEquals(HelperName.CLAUDE, c)
        // Renamed: the preset's spellings and mishearings no longer apply, the sound-alike rule does.
        val renamed = HelperName.of(HelperConfig.claude().copy(name = "Claudia"))
        assertEquals(listOf(listOf("claudia")), renamed.forms)
        assertTrue(renamed.misheard.isEmpty() && renamed.phonetic && !renamed.app)
        assertEquals(listOf(listOf("chatgpt"), listOf("chat", "gpt"), listOf("chat", "g", "p", "t"), listOf("gpt"), listOf("g", "p", "t")), HelperName.of(gpt).forms)
        // Your aliases are exact forms.
        assertTrue(listOf("the", "box") in HelperName.of(jarvis.copy(aliases = listOf("the box"))).forms)
    }
}
