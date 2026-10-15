// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeRouterTest {
    private fun route(s: String) = HelperRouter.route(s)
    private fun ask(q: String, deep: Boolean = false, extra: Attachment = Attachment.NONE) = HelperCommand.Ask(q, deep, extra)

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
            assertEquals(s, HelperCommand.Join(), route(s))
        }
        // "Ask Claude" with nothing after it brings Claude in.
        assertEquals(HelperCommand.Join(), route("ask Claude"))
        // A question said in the same breath is the first one.
        assertEquals(HelperCommand.Join("what's a good name for a cat"), route("go get Claude and ask him what's a good name for a cat"))
        assertEquals(HelperCommand.Join("what do you make of this?"), route("get Claude, what do you make of this?"))
    }

    @Test fun leavePhrases() {
        for (s in listOf("thanks Claude", "thank you Claude", "bye Claude", "back to Robot", "that's all Claude", "stop Claude", "Thanks, Claude!",
            "okay thanks Claude", "goodbye Claude", "go back to Robot")) {
            assertEquals(s, HelperCommand.Leave, route(s))
        }
    }

    @Test fun french() {
        assertEquals(ask("pourquoi le ciel est bleu"), route("demande à Claude pourquoi le ciel est bleu"))
        assertEquals(ask("mettre une minuterie"), route("Demande à Claude de mettre une minuterie"))
        // "à" not heard.
        assertEquals(ask("si il va pleuvoir"), route("demande Claude si il va pleuvoir"))
        assertEquals(HelperCommand.Join(), route("va chercher Claude"))
        assertEquals(HelperCommand.Join(), route("Hé robot, va chercher Claude s'il te plaît"))
        assertEquals(HelperCommand.Leave, route("merci Claude"))
        assertEquals(HelperCommand.Leave, route("au revoir Claude"))
        assertEquals(HelperCommand.Leave, route("retour à Robot"))
        assertEquals(ask("Que penses-tu de la pizza à l'ananas ?"), route("qu'est-ce que Claude pense de la pizza à l'ananas"))
    }

    @Test fun mishearingsCountOnlyAfterACommandVerb() {
        for (a in HelperRouter.ALIASES) {
            assertEquals(a, ask("about black holes"), route("ask $a about black holes"))
            // A Claude mode from a mishearing is marked: the conversation so far goes only after a tap.
            val join = HelperCommand.Join(misheard = a != "claude")
            assertEquals(a, join, route("go get $a"))
            assertEquals(a, join, route("get $a"))
            assertEquals(a, HelperCommand.Leave, route("thanks $a"))
            assertEquals(a, join, route("demande à $a"))
            // Addressed without a command verb: only the name itself.
            val vocative = if (a == "claude") ask("what's the capital of Peru?") else null
            assertEquals(a, vocative, route("$a, what's the capital of Peru?"))
            assertEquals(a, if (a == "claude") ask("tell me a joke") else null, route("hey $a tell me a joke"))
            // A weaker verb needs the name too.
            assertEquals(a, if (a == "claude") HelperCommand.Join() else null, route("switch to $a"))
        }
    }

    @Test fun wordsAboutSomeoneCalledClaudeStayLocal() {
        // Statements about a person, with the name first.
        assertNull(route("Claude can come over tomorrow"))
        assertNull(route("Claude could use a haircut"))
        assertNull(route("Claude please stop calling me"))
        assertNull(route("Claude is coming at six"))
        assertNull(route("hey Claude I'm home"))
        // A mishearing is never addressed directly.
        assertNull(route("Okay, cloud cover tomorrow?"))
        assertNull(route("hey cloud storage is full"))
        assertNull(route("Clyde, pass the salt"))
        assertNull(route("hey cloud"))
        // "ask … if he …" is a message for a person.
        assertNull(route("ask Clyde if he's coming to dinner"))
        assertNull(route("ask Claude if she wants pizza"))
        assertEquals(ask("if it will rain tomorrow"), route("ask Claude if it will rain tomorrow"))
        // The name with a real question or request still goes.
        assertEquals(ask("can you come up with a name for my cat?"), route("Claude can you come up with a name for my cat?"))
        assertEquals(ask("is it going to rain?"), route("Claude, is it going to rain?"))
        assertEquals(ask("please explain black holes"), route("Claude please explain black holes"))
        assertEquals(ask("explain black holes"), route("Claude explain black holes"))
        assertEquals(ask("write me a haiku"), route("Claude write me a haiku"))
        assertEquals(ask("pourquoi le ciel est bleu"), route("Claude, pourquoi le ciel est bleu"))
        assertEquals(HelperCommand.Join(), route("Claude"))
        assertEquals(HelperCommand.Join(), route("hey Claude"))
    }

    @Test fun askClaudePhrasesComeBeforeTheTextingShortcut() {
        val q = "ask Claude to text mom I'm running late"
        assertEquals(ask("text mom I'm running late"), route(q))
        assertEquals(FastPath.Way.HELPER, FastPath.of(route(q), inHelperMode = false, attachment = Attachment.NONE))
        // Without the Claude phrase the texting shortcut takes it, before the model.
        val t = "text mom I'm running late"
        assertNull(route(t))
        assertEquals(FastPath.Way.TEXT, FastPath.of(route(t), inHelperMode = false, attachment = Attachment.NONE))
        assertEquals("I'm running late", TextCommand.parse(t, listOf("Mom"))?.message)
        // In Claude mode a request to text goes to Claude (whose call shows a card); a question about a photo goes to the model.
        assertEquals(FastPath.Way.HELPER, FastPath.of(route(t), inHelperMode = true, attachment = Attachment.NONE))
        assertEquals(FastPath.Way.MODEL, FastPath.of(route("what is this"), inHelperMode = false, attachment = Attachment.PHOTO))
    }

    @Test fun sentencesAboutAPersonCalledClaudeOrClydeStayLocal() {
        for (s in listOf(
            // A verb after the name, question mark or not: about someone, unless "you" follows or a comma sets the name off.
            "Claude has a fever, what should I do?", "Claude is coming over tonight?", "Claude did his homework?", "Claude should call his grandma?",
            // A mishearing after "get": Claude mode alone, never with words about a person.
            "go get Clyde and the kids from school", "get Clyde, he's at the door", "go get Clyde and bring him here", "can you get Clyde in here please",
            // "ask <mishearing> to / whether / if …" is a message for a person.
            "ask Clyde to pick up milk", "ask Clyde whether dinner is ready",
            // More in the same spirit.
            "Claude's birthday is Friday?", "go get Clyde from the airport", "Claude will be late tonight?", "Claude does karate on Tuesdays?",
            "get cloud backups working again", "go get clod and the shovel", "ask cloud to back up my photos", "demande à Clyde de venir",
            "Claude a de la fièvre, qu'est-ce que je fais ?", "Claude est malade ?", "ask Clyde if dinner is ready", "get Clyde now, he's waiting",
        )) assertNull(s, route(s))
    }

    @Test fun realCommandsStillGoToClaude() {
        assertEquals(ask("about black holes"), route("ask cloud about black holes"))
        assertEquals(ask("explain black holes"), route("Claude, explain black holes"))
        assertEquals(HelperCommand.Join(), route("go get Claude"))
        assertEquals(HelperCommand.Join(misheard = true), route("hey robot, get cloud"))
        assertEquals(HelperCommand.Join(misheard = true), route("go get Clyde please"))
        assertEquals(HelperCommand.Join("Let's talk about cooking."), route("talk to Claude about cooking"))
        assertEquals(Attachment.PHOTO, (route("ask Claude about this photo") as HelperCommand.Ask).extra)
        assertEquals(ask("text mom I'm running late"), route("ask Claude to text mom I'm running late"))
        // A question with a mishearing needs an explicit "and ask"; with the name itself, "you" or a comma.
        assertEquals(HelperCommand.Join("what a black hole is", misheard = true), route("go get cloud and ask what a black hole is"))
        assertEquals(ask("is it going to rain tomorrow"), route("Claude, is it going to rain tomorrow"))
        assertEquals(ask("can you help me with my essay?"), route("Claude can you help me with my essay?"))
        assertEquals(HelperCommand.Join("what's a good name for a cat"), route("go get Claude and ask him what's a good name for a cat"))
    }

    @Test fun talkToClaudeAbout() {
        assertEquals(HelperCommand.Join("Let's talk about my day."), route("talk to Claude about my day"))
        assertEquals(HelperCommand.Join("Let's talk about black holes."), route("let me talk to Claude about black holes."))
        assertEquals(HelperCommand.Join(), route("talk to Claude"))
        // A mishearing after "talk to" is not enough.
        assertNull(route("talk to cloud about my day"))
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
        assertNull(route("I saw a cloud"))
        assertNull(route("clawed at the door all night"))
        assertNull(route("Claude's birthday is on Friday"))
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
        assertTrue((route("ask Claude, take your time: why do we dream?") as HelperCommand.Ask).deep)
        assertFalse((route("ask Claude why do we dream") as HelperCommand.Ask).deep)
        assertEquals(Attachment.SCREEN, (route("ask Claude about what's on my screen") as HelperCommand.Ask).extra)
        assertEquals(Attachment.SCREEN, (route("Claude, can you explain this screen") as HelperCommand.Ask).extra)
        assertEquals(Attachment.PHOTO, (route("ask Claude about this photo") as HelperCommand.Ask).extra)
        assertEquals(Attachment.NONE, (route("ask Claude about photosynthesis") as HelperCommand.Ask).extra)
        assertEquals(Attachment.SCREEN, (route("demande à Claude ce qu'il y a sur mon écran") as HelperCommand.Ask).extra)
    }

    @Test fun readClaudesAnswer() {
        assertEquals(HelperCommand.ReadAnswer, route("read Claude's answer"))
        assertEquals(HelperCommand.ReadAnswer, route("Hey robot, read Claude's answer"))
        assertEquals(HelperCommand.ReadAnswer, route("read cloud's reply"))
        assertNull(route("read me the news"))
    }

    @Test fun shortYesAcceptsAnOffer() {
        for (s in listOf("yes", "Yes please", "sure, go ahead", "yeah", "ok", "oui", "yes, check with Claude", "d'accord")) assertTrue(s, HelperRouter.isYes(s))
        for (s in listOf("no", "no thanks", "yes but what about tomorrow's weather in Montreal", "what time is it", "", "sure is hot today in here")) {
            assertFalse(s, HelperRouter.isYes(s))
        }
    }
}
