package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeRouterTest {
    private fun route(s: String) = ClaudeRouter.route(s)
    private fun ask(q: String, deep: Boolean = false, extra: Attachment = Attachment.NONE) = ClaudeCommand.Ask(q, deep, extra)

    @Test fun oneShotPhrasesFromTheTable() {
        assertEquals(ask("about the mysteries of the universe"), route("ask Claude about the mysteries of the universe"))
        assertEquals(ask("why the sky is blue"), route("Hey robot, ask Claude why the sky is blue"))
        assertEquals(ask("what's the tallest mountain on Mars?"), route("Claude, what's the tallest mountain on Mars?"))
        assertEquals(ask("how do vaccines work"), route("hey Claude how do vaccines work"))
        assertEquals(ask("What do you think about pineapple on pizza?"), route("what does Claude think about pineapple on pizza"))
        assertEquals(ask("What do you think about remote work?"), route("What does Claude think of remote work?"))
        // "to" before an imperative goes ("ask Claude to set a timer" is a request to Claude, whose card follows).
        assertEquals(ask("set a timer for 10 minutes"), route("ask Claude to set a timer for 10 minutes"))
        // The real name may lead straight into a question without a comma.
        assertEquals(ask("what is a black hole"), route("Claude what is a black hole"))
    }

    @Test fun fillerInFrontIsAllowed() {
        for (s in listOf("please ask Claude why the sky is blue", "can you ask Claude why the sky is blue", "okay ask Claude why the sky is blue",
            "hey robot please ask Claude why the sky is blue", "Hey Robot, can you please ask Claude why the sky is blue", "ok robot ask claude why the sky is blue")) {
            assertEquals(s, ask("why the sky is blue"), route(s))
        }
    }

    @Test fun claudeModePhrases() {
        for (s in listOf("go get Claude", "bring in Claude", "get Claude", "let me talk to Claude", "switch to Claude", "Hey robot, go get Claude",
            "bring Claude in", "go get Claude please", "can you get Claude", "Let me talk to Claude.", "put Claude on")) {
            assertEquals(s, ClaudeCommand.Join(), route(s))
        }
        // "Ask Claude" with nothing after it brings Claude in.
        assertEquals(ClaudeCommand.Join(), route("ask Claude"))
        // A question said in the same breath is the first one.
        assertEquals(ClaudeCommand.Join("what's a good name for a cat"), route("go get Claude and ask him what's a good name for a cat"))
        assertEquals(ClaudeCommand.Join("what do you make of this?"), route("get Claude, what do you make of this?"))
    }

    @Test fun leavePhrases() {
        for (s in listOf("thanks Claude", "thank you Claude", "bye Claude", "back to Robot", "that's all Claude", "stop Claude", "Thanks, Claude!",
            "okay thanks Claude", "goodbye Claude", "go back to Robot")) {
            assertEquals(s, ClaudeCommand.Leave, route(s))
        }
    }

    @Test fun french() {
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à Claude pourquoi le ciel est bleu"))
        assertEquals(ask("mettre une minuterie"), route("Demande à Claude de mettre une minuterie"))
        // "à" not heard.
        assertEquals(ask("si il va pleuvoir"), route("demande Claude si il va pleuvoir"))
        assertEquals(ClaudeCommand.Join(), route("va chercher Claude"))
        assertEquals(ClaudeCommand.Join(), route("Hé robot, va chercher Claude s'il te plaît"))
        assertEquals(ClaudeCommand.Leave, route("merci Claude"))
        assertEquals(ClaudeCommand.Leave, route("au revoir Claude"))
        assertEquals(ClaudeCommand.Leave, route("retour à Robot"))
        assertEquals(ask("Que penses-tu de la pizza à l'ananas ?"), route("qu'est-ce que Claude pense de la pizza à l'ananas"))
    }

    @Test fun mishearingsCountInCommandPosition() {
        for (a in ClaudeRouter.ALIASES) {
            assertEquals(a, ask("about black holes"), route("ask $a about black holes"))
            assertEquals(a, ask("what's the capital of Peru?"), route("$a, what's the capital of Peru?"))
            assertEquals(a, ask("tell me a joke"), route("hey $a tell me a joke"))
            assertEquals(a, ClaudeCommand.Join(), route("go get $a"))
            assertEquals(a, ClaudeCommand.Leave, route("thanks $a"))
            assertEquals(a, ClaudeCommand.Join(), route("demande à $a"))
        }
    }

    @Test fun mishearingsOutOfCommandPositionStayLocal() {
        assertNull(route("what's the cloud cover tomorrow"))
        assertNull(route("cloud cover tomorrow?"))
        assertNull(route("Cloud storage is full on my phone"))
        assertNull(route("get cloud storage prices"))
        assertNull(route("break the clod of dirt"))
        assertNull(route("the cat clawed the sofa"))
        assertNull(route("clawed hands are a symptom of what"))
        assertNull(route("Claud is my uncle's name"))
        assertNull(route("klode means what in Danish"))
        assertNull(route("Clyde is coming over at six"))
        assertNull(route("how is a cloud formed"))
        assertNull(route("set a timer for the clod"))
        // Not the real name either: a statement, not a vocative.
        assertNull(route("Claude Monet painted water lilies"))
        assertNull(route("claude is a nice name"))
    }

    @Test fun smallTalkAboutClaudeStaysLocal() {
        assertNull(route("what's the weather like on Claude's birthday"))
        assertNull(route("who is Claude"))
        assertNull(route("tell me about Claude Shannon"))
        assertNull(route("set a timer for 5 minutes"))
        assertNull(route("text Claude I'm running late"))
        assertNull(route("I asked Claude yesterday"))
        assertNull(route("did you ask Claude"))
        assertNull(route(""))
        assertNull(route("hey robot"))
        // "thanks" alone, or to someone else, is ordinary talk.
        assertNull(route("thanks"))
        assertNull(route("thanks Robot"))
    }

    @Test fun depthAndSharingAreRead() {
        assertEquals(ask("to think hard about the trolley problem".removePrefix("to "), deep = true), route("ask Claude to think hard about the trolley problem"))
        assertTrue((route("ask Claude, take your time: why do we dream?") as ClaudeCommand.Ask).deep)
        assertFalse((route("ask Claude why do we dream") as ClaudeCommand.Ask).deep)
        assertEquals(Attachment.SCREEN, (route("ask Claude about what's on my screen") as ClaudeCommand.Ask).extra)
        assertEquals(Attachment.SCREEN, (route("Claude, can you explain this screen") as ClaudeCommand.Ask).extra)
        assertEquals(Attachment.PHOTO, (route("ask Claude about this photo") as ClaudeCommand.Ask).extra)
        assertEquals(Attachment.NONE, (route("ask Claude about photosynthesis") as ClaudeCommand.Ask).extra)
        assertEquals(Attachment.SCREEN, (route("demande à Claude ce qu'il y a sur mon écran") as ClaudeCommand.Ask).extra)
    }

    @Test fun readClaudesAnswer() {
        assertEquals(ClaudeCommand.ReadAnswer, route("read Claude's answer"))
        assertEquals(ClaudeCommand.ReadAnswer, route("Hey robot, read Claude's answer"))
        assertEquals(ClaudeCommand.ReadAnswer, route("read cloud's reply"))
        assertNull(route("read me the news"))
    }

    @Test fun shortYesAcceptsAnOffer() {
        for (s in listOf("yes", "Yes please", "sure, go ahead", "yeah", "ok", "oui", "yes, check with Claude", "d'accord")) assertTrue(s, ClaudeRouter.isYes(s))
        for (s in listOf("no", "no thanks", "yes but what about tomorrow's weather in Montreal", "what time is it", "", "sure is hot today in here")) {
            assertFalse(s, ClaudeRouter.isYes(s))
        }
    }
}
