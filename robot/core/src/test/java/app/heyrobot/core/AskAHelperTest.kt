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
    private fun route(s: String, names: List<HelperName>? = null) = if (names == null) ClaudeRouter.route(s) else ClaudeRouter.route(s, names)
    private fun ask(q: String, helper: String = any, extra: Attachment = Attachment.NONE, deep: Boolean = false) = ClaudeCommand.Ask(q, deep, extra, helper)

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
        // Nothing after it: the helper joins, as "ask Claude" does.
        assertEquals(ClaudeCommand.Join(helper = any), route("ask the helper"))
        assertEquals(ClaudeCommand.Join(helper = any), route("ask a helper."))
    }

    @Test fun theNewFrenchPhrases() {
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à l'assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("Demande à l’assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à un assistant pourquoi le ciel est bleu"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("dis robot, demande à l'assistant pourquoi le ciel est bleu"))
        // The "à" not heard.
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande l'assistant pourquoi le ciel est bleu"))
        assertEquals(ask("m'écrire un poème"), route("demande à l'assistant de m'écrire un poème"))
        assertEquals(ClaudeCommand.Join(helper = any), route("demande à un assistant"))
    }

    @Test fun askClaudeStillWorksAsAHiddenAlias() {
        assertEquals(ClaudeCommand.Ask("why the sky is blue"), route("ask Claude why the sky is blue"))
        assertEquals(ClaudeCommand.Ask("why the sky is blue"), route("ask cloud why the sky is blue"))
        assertEquals(ClaudeCommand.Ask("what's a black hole?"), route("Claude, what's a black hole?"))
        assertEquals(ClaudeCommand.Ask("pourquoi le ciel est bleu"), route("demande à Claude pourquoi le ciel est bleu"))
        assertEquals(ClaudeCommand.Join(), route("go get Claude"))
        // With every helper in settings, Claude's name still means Claude, and the new phrases still mean the one Robot asks.
        val s = RobotSettings().withHelper(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck"))
        val names = HelperName.all(s)
        assertEquals(ClaudeCommand.Ask("why the sky is blue"), route("ask Claude why the sky is blue", names))
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
            "thanks the helper", "stop the helper", "my helper can come over tomorrow", "what does the helper think about this",
            // Someone at home who helps: a message for a person.
            "ask my helper to pick up the kids", "ask my helper if she can stay late", "ask my helper whether dinner is ready",
            "ask the helper if he's coming tomorrow", "ask a helper if she knows the way",
            // French near-misses.
            "l'assistant est là", "demande de l'aide", "demande à l'assistante pourquoi", "va chercher l'assistant", "assistant, quelle heure est-il ?",
            // Robot's own words: the wake phrase, a card's yes and no, stop.
            "hey robot", "robot", "yes", "no", "stop", "oui", "non", "arrête",
        )) assertNull(t, route(t))
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
            assertEquals(t, FastPath.Way.CLAUDE, FastPath.of(route(t), claudeMode = false, attachment = Attachment.NONE))
        }
    }

    @Test fun turningTheHelperDown() {
        for (t in listOf("don't ask the helper", "do not ask a helper, you tell me", "not the helper", "without a helper please", "leave my helper out of it",
            "ne demande pas à l'assistant", "ne demande pas à un assistant")) assertTrue(t, ClaudeRouter.declines(t))
        for (t in listOf("the helper is not here", "it's not my helper, it's Sam", "he's not a helper", "ask the helper why the sky is blue",
            "no help needed", "not helpful")) assertFalse(t, ClaudeRouter.declines(t))
        // The old words still turn Claude down.
        assertTrue(ClaudeRouter.declines("don't ask Claude"))
    }

    @Test fun aYesMayNameTheHelper() {
        assertTrue(ClaudeRouter.isYes("yes, ask the helper"))
        assertTrue(ClaudeRouter.isYes("oui, l'assistant"))
        assertTrue(ClaudeRouter.isYes("yes, ask Claude"))
        assertFalse(ClaudeRouter.isYes("yes, the helper is wrong"))
        assertFalse(ClaudeRouter.isYes("no, not the helper"))
    }

    @Test fun noHelperMayBeCalledHelperOrAssistant() {
        val s = RobotSettings()
        for (n in listOf("Helper", "Assistant", "My Helper")) assertTrue(n, HelperNames.check(n, "new", s, setOf("hey", "robot")) is NameCheck.Problem)
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
