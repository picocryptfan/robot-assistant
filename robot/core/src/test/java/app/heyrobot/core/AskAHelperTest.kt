// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperNames
import app.heyrobot.core.helpers.NameCheck
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.web.BrowserRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.0.0: the feature is "Ask a helper". "Ask the helper …", "ask a helper …", "ask my helper …", « demande à l'assistant … »
 * and « demande à un assistant … » do what "ask Claude …" does, for the helper Robot checks with ([HelperName.ANY_ID]);
 * "ask Claude …" and « demande à Claude … » still work, unannounced.
 */
class AskAHelperTest {
    private val any = HelperName.ANY_ID
    private fun route(s: String, names: List<HelperName>? = null) = if (names == null) HelperRouter.route(s) else HelperRouter.route(s, names)
    private fun ask(q: String, helper: String = any, extra: Attachment = Attachment.NONE, deep: Boolean = false) = HelperCommand.Ask(q, deep, extra, helper)

    @Test fun theNewPhrases() {
        assertEquals(ask("why the sky is blue"), route("ask the helper why the sky is blue"))
        assertEquals(ask("why the sky is blue"), route("Ask a helper why the sky is blue"))
        assertEquals(ask("why the sky is blue"), route("ask my helper why the sky is blue"))
        assertEquals(ask("why the sky is blue"), route("Hey robot, ask the helper why the sky is blue"))
        assertEquals(ask("why the sky is blue"), route("can you please ask a helper why the sky is blue"))
        assertEquals(ask("about black holes"), route("ask the helper about black holes"))
        // "to" before a request goes, as with a name.
        assertEquals(ask("write a toast for my sister's wedding"), route("ask the helper to write a toast for my sister's wedding"))
        assertEquals(ask("write a toast", deep = false), route("ask a helper to write a toast"))
        // Depth and the screen, as with a name.
        assertEquals(ask("about this screen", extra = Attachment.SCREEN), route("ask the helper about this screen"))
        assertEquals(ask("think hard about this plan", deep = true), route("ask the helper to think hard about this plan"))
        // Nothing after it: the helper joins, as "ask Claude" does, but as after a mishearing (no helper was named).
        assertEquals(HelperCommand.Join(misheard = true, helper = any), route("ask the helper"))
        assertEquals(HelperCommand.Join(misheard = true, helper = any), route("ask a helper."))
        assertEquals(HelperCommand.Join(misheard = true, helper = any), route("ask my helper"))
    }

    @Test fun theNewFrenchPhrases() {
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à l'assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("Demande à l’assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à un assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("dis robot, demande à l'assistant pourquoi le ciel est bleu"))
        // The "à" not heard.
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande l'assistant pourquoi le ciel est bleu"))
        assertEquals(ask("m'écrire un poème"), route("demande à l'assistant de m'écrire un poème"))
        assertEquals(HelperCommand.Join(misheard = true, helper = any), route("demande à un assistant"))
        assertEquals(HelperCommand.Join(misheard = true, helper = any), route("demande à l'assistant"))
    }

    @Test fun askClaudeStillWorksAsAHiddenAlias() {
        assertEquals(HelperCommand.Ask("why the sky is blue"), route("ask Claude why the sky is blue"))
        assertEquals(HelperCommand.Ask("why the sky is blue"), route("ask cloud why the sky is blue"))
        assertEquals(HelperCommand.Ask("what's a black hole?"), route("Claude, what's a black hole?"))
        assertEquals(HelperCommand.Ask("pourquoi le ciel est bleu"), route("demande à Claude pourquoi le ciel est bleu"))
        assertEquals(HelperCommand.Join(), route("go get Claude"))
        // With Claude and another helper in settings, Claude's name still means Claude, and the new phrases still mean the one Robot asks.
        val s = RobotSettings(helpers = listOf(HelperConfig.claude())).withHelper(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck"))
        val names = HelperName.all(s)
        assertEquals(HelperCommand.Ask("why the sky is blue"), route("ask Claude why the sky is blue", names))
        assertEquals(ask("why the sky is blue", helper = "hgpt"), route("ask ChatGPT why the sky is blue", names))
        assertEquals(ask("why the sky is blue"), route("ask the helper why the sky is blue", names))
    }

    @Test fun nearMissesStayWithRobot() {
        for (t in listOf(
            // "help" is not "helper".
            "help", "help me", "ask for help with my homework", "ask for help", "can you help me ask the bank", "ask the help desk about my order",
            "ask the helpers", "helper", "I need a helper", "ask helper why the sky is blue",
            // Not after "ask": the helper is never addressed by these words, joined or thanked.
            "the helper is coming at six", "the helper, what's the weather?", "a helper, what's the weather?", "go get the helper", "talk to the helper",
            "my helper can come over tomorrow", "what does the helper think about this", "helper, thanks", "assistant",
            // Someone at home who helps: a message for a person.
            "ask my helper to pick up the kids", "ask my helper if she can stay late", "ask my helper whether dinner is ready",
            "ask the helper if he's coming tomorrow", "ask a helper if she knows the way",
            // French near-misses.
            "l'assistant est là", "demande de l'aide", "demande à l'assistante pourquoi", "va chercher l'assistant", "assistant, quelle heure est-il ?",
            // Robot's own words: the wake phrase, a card's yes and no, stop.
            "hey robot", "robot", "yes", "no", "stop", "oui", "non", "arrête",
        )) assertNull(t, route(t))
    }

    @Test fun joiningWithoutANameAlwaysAsksBeforeTheConversationGoes() {
        // A helper you trust skips the Include card only when you called it by its name: "ask the helper" names none.
        val trusted = HelperConfig.of(Presets.HOME, "h2", "af_bella", "Jarvis", "http://192.168.1.20:11434/v1").copy(trust = true)
        val s = RobotSettings().withHelper(trusted)
        val names = HelperName.all(s)
        val personal = setOf(Personal.CALENDAR)
        val byName = route("ask Jarvis", names) as HelperCommand.Join
        assertTrue(app.heyrobot.core.helpers.HelperTrust.joinWithoutCard(trusted, personal, byName.misheard))
        for (t in listOf("ask the helper", "ask a helper", "ask my helper", "demande à l'assistant", "demande à un assistant")) {
            val j = route(t, names) as HelperCommand.Join
            assertFalse(t, app.heyrobot.core.helpers.HelperTrust.joinWithoutCard(trusted, personal, j.misheard))
            // Even a conversation with nothing personal in it asks first.
            val plain = listOf(ChatTurn("u1", TurnRole.USER, "what's a good name for a cat?", 0L), ChatTurn("a1", TurnRole.ASSISTANT, "Miso.", 0L))
            assertEquals(t, "Include the conversation so far?", HelperContext.includeQuestion(plain, j.misheard))
        }
    }

    @Test fun thankingTheHelperLeaves() {
        val s = RobotSettings().withHelper(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck"))
        for (names in listOf(null, HelperName.all(s))) for (t in listOf("thanks helper", "Thanks, helper.", "thank you helper", "thank you, the helper",
            "thanks the helper", "thanks my helper", "bye helper", "stop the helper", "that's all, helper", "hey robot, thanks helper",
            "merci l'assistant", "Merci, l’assistant !", "merci assistant", "au revoir l'assistant", "c'est tout l'assistant")) {
            assertEquals(t, HelperCommand.Leave, route(t, names))
            // Never a question for the helper, in its conversation or out of it.
            assertFalse(t, FastPath.of(route(t, names), inHelperMode = true, attachment = Attachment.NONE) == FastPath.Way.HELPER)
            // No router before the helpers' takes it (the panel runs these first, in a helper's conversation too).
            assertNull(t, MessagesRouter.route(t))
            assertNull(t, ConversationRouter.route(t, running = false))
            assertNull(t, FeedbackRouter.route(t))
            assertNull(t, FreeUp.asked(t))
            assertNull(t, MemoryRouting.route(t, MemorySettings(), locked = false))
        }
        // The names still leave, and "thanks" alone stays ordinary talk.
        assertEquals(HelperCommand.Leave, route("thanks Claude"))
        assertEquals(HelperCommand.Leave, route("thanks ChatGPT", HelperName.all(s)))
        for (t in listOf("thanks", "thank you", "merci", "thanks for the help", "thank you for helping", "thanks, that helped")) assertNull(t, route(t))
    }

    @Test fun theOtherRoutersLeaveTheNewPhrasesAlone() {
        val now = ZonedDateTime.of(2026, 10, 13, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
        for (t in listOf("ask the helper why the sky is blue", "ask a helper what to cook tonight", "ask my helper what to read next",
            "ask the helper to write a toast", "demande à l'assistant pourquoi le ciel est bleu", "demande à un assistant une recette")) {
            assertNull(t, MessagesRouter.route(t))
            assertNull(t, MessagesRouter.route(t, reading = true))
            assertNull(t, ConversationRouter.route(t, running = false))
            assertNull(t, FeedbackRouter.route(t))
            assertNull(t, HandsFreeRouter.route(t, carMode = true))
            assertNull(t, BrowserRouter.route(t))
            assertNull(t, FreeUp.asked(t))
            assertNull(t, MemoryRouting.route(t, MemorySettings(), locked = false))
            assertNull(t, RoutineRouter.route(t, now))
            // A Claude command wins over texting, and these are one.
            assertEquals(t, FastPath.Way.HELPER, FastPath.of(route(t), inHelperMode = false, attachment = Attachment.NONE))
        }
    }

    @Test fun turningTheHelperDown() {
        for (t in listOf("don't ask the helper", "do not ask a helper, you tell me", "not the helper", "without a helper please", "leave my helper out of it",
            "ne demande pas à l'assistant", "ne demande pas à un assistant")) assertTrue(t, HelperRouter.declines(t))
        for (t in listOf("the helper is not here", "it's not my helper, it's Sam", "he's not a helper", "ask the helper why the sky is blue",
            "no help needed", "not helpful")) assertFalse(t, HelperRouter.declines(t))
        // The old words still turn Claude down.
        assertTrue(HelperRouter.declines("don't ask Claude"))
    }

    @Test fun aYesMayNameTheHelper() {
        assertTrue(HelperRouter.isYes("yes, ask the helper"))
        assertTrue(HelperRouter.isYes("oui, l'assistant"))
        assertTrue(HelperRouter.isYes("yes, ask Claude"))
        assertFalse(HelperRouter.isYes("yes, the helper is wrong"))
        assertFalse(HelperRouter.isYes("no, not the helper"))
    }

    @Test fun noHelperMayBeCalledHelperOrAssistant() {
        val s = RobotSettings()
        for (n in listOf("Helper", "Assistant", "My Helper")) assertTrue(n, HelperNames.check(n, "new", s, setOf("hey", "robot")) is NameCheck.Problem)
    }

    @Test fun everyPresetsNameCanBeSaved() {
        val wake = setOf("hey", "robot")
        for (s in listOf(RobotSettings(helpers = emptyList()), RobotSettings())) for (p in Presets.ALL) {
            val id = if (p == Presets.ANTHROPIC) HelperConfig.CLAUDE_ID else "new"
            assertFalse(p.id, HelperNames.check(p.defaultName, id, s, wake) is NameCheck.Problem)
        }
        // Another company: the name in its address when there is one, "My AI" when not.
        val empty = RobotSettings(helpers = emptyList())
        assertEquals("My AI", Presets.OTHER.defaultName)
        assertEquals("Example", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://api.example.com/v1"))
        assertEquals("Cerebras", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://inference.cerebras.ai/v1"))
        assertEquals("Example", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://llm.example.co.uk/v1"))
        assertEquals("My llm", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://api.my-llm.dev/v1"))
        assertEquals("My AI", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://203.0.113.9/v1"))
        assertEquals("My AI", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://ai.io/v1"))
        assertEquals("My AI", HelperNames.forNew(Presets.OTHER, "new", empty, wake, null))
        // A name in the address that is a command word, or taken: the default, then numbered.
        assertEquals("My AI", HelperNames.forNew(Presets.OTHER, "new", empty, wake, "https://api.helper.com/v1"))
        val taken = empty.withHelper(HelperConfig.of(Presets.OTHER, "h1", "am_puck", "Example", "https://api.example.com/v1"))
        assertEquals("Example 2", HelperNames.forNew(Presets.OTHER, "new", taken, wake, "https://api.example.com/v1"))
        // The other presets keep their names, numbered when one is already there.
        assertEquals("Gemini", HelperNames.forNew(Presets.GEMINI, "new", empty, wake))
        assertEquals("Gemini 2", HelperNames.forNew(Presets.GEMINI, "new", empty.withHelper(HelperConfig.of(Presets.GEMINI, "h1", "am_puck")), wake))
    }

    @Test fun aHelperSavedAsHelperKeepsWorking() {
        // Before 1.0.0 another company's helper was called "Helper" by default.
        val text = """{"schema":9,"helpers":[{"id":"claude","preset":"anthropic","name":"Claude","model":"claude-opus-5-5"},
            {"id":"h1abcde","preset":"compatible","name":"Helper","model":"m-1","baseUrl":"https://api.example.com/v1"}]}"""
        val s = RobotSettings.fromJson(text)
        val h = s.helper("h1abcde")!!
        assertEquals("Helper", h.name)
        val wake = setOf("hey", "robot")
        // Its own name stays allowed (renaming it to the same name, say); another helper can't take it, nor can it become "Assistant".
        assertFalse(HelperNames.check("Helper", h.id, s, wake) is NameCheck.Problem)
        assertTrue(HelperNames.check("Helper", "new", s, wake) is NameCheck.Problem)
        assertTrue(HelperNames.check("Assistant", h.id, s, wake) is NameCheck.Problem)
        // Asked by its name it still answers; "ask the helper" is still the one Robot checks with.
        val names = HelperName.all(s)
        assertEquals(ask("why the sky is blue", helper = "h1abcde"), route("ask helper why the sky is blue", names))
        assertEquals(ask("why the sky is blue"), route("ask the helper why the sky is blue", names))
        assertEquals(HelperCommand.Leave, route("thanks helper", names))
        // A default name, not one you chose: the diagnostics don't hide the word "helper".
        assertFalse("Helper" in app.heyrobot.core.feedback.FeedbackPrivacy.secrets(s, null, null))
    }

    @Test fun routinesRefuseTheNewPhrases() {
        val now = ZonedDateTime.of(2026, 10, 11, 20, 0, 0, 0, ZoneId.of("America/Toronto"))
        for (t in listOf("when I plug in at night, ask the helper for the news", "every day at 7, ask a helper for a joke",
            "every day at 7, ask my helper for the news", "tous les soirs à 23 h, demande à l'assistant les nouvelles",
            "tous les soirs à 23 h, demande à un assistant les nouvelles")) {
            val c = RoutineRouter.route(t, now, emptyList(), id = "draft-1")
            assertTrue("“$t” → $c", c is RoutineCommand.Create)
            val d = (c as RoutineCommand.Create).draft
            assertEquals(t, emptyList<RoutineStep>(), d.routine.steps)
            assertEquals(t, 1, d.refused.size)
            assertFalse(t, d.canSave)
        }
    }
}
