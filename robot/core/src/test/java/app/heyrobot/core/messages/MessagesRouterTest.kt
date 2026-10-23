// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Design §B7: every phrase in English and Québec French, and what stays someone else's (the screen, the model). */
class MessagesRouterTest {
    private fun r(s: String, reading: Boolean = false) = MessagesRouter.route(s, reading)

    @Test fun readingInBothLanguages() {
        for (s in listOf("Read my messages", "read me my new messages", "Hey Robot, read my messages please.", "Any new messages?", "do I have any messages",
            "Did I get any texts?", "who messaged me", "check my messages", "can you read my unread messages")) {
            assertEquals(s, MessageCommand.Read(Lang.EN), r(s))
        }
        for (s in listOf("Lis mes messages", "lis-moi mes messages", "Est-ce que j'ai des messages?", "J'ai-tu des nouveaux messages?", "As-tu des messages pour moi",
            "Y a-tu des messages?", "qui m'a écrit", "Vérifie mes messages", "lis mes textos")) {
            assertEquals(s, MessageCommand.Read(Lang.FR), r(s))
        }
    }

    @Test fun whoSaidWhatByName() {
        assertEquals(MessageCommand.FromWho("Mom", Lang.EN), r("What did Mom say?"))
        assertEquals(MessageCommand.FromWho("mom", Lang.EN), r("what did my mom say"))
        assertEquals(MessageCommand.FromWho("Pierre", Lang.EN), r("What did Pierre write?"))
        assertEquals(MessageCommand.FromWho("Pierre", Lang.EN), r("Read me Pierre's messages"))
        assertEquals(MessageCommand.FromWho("Marie-Ève", Lang.EN), r("Any messages from Marie-Ève?"))
        assertEquals(MessageCommand.FromWho("Julie", Lang.EN), r("Did Julie text me?"))
        assertEquals(MessageCommand.FromWho("maman", Lang.FR), r("Qu'est-ce que maman a dit?"))
        assertEquals(MessageCommand.FromWho("Pierre", Lang.FR), r("Qu’est-ce que Pierre m’a écrit ?"))
        assertEquals(MessageCommand.FromWho("maman", Lang.FR), r("Lis les messages de maman"))
        assertEquals(MessageCommand.FromWho("Hélène", Lang.FR), r("Est-ce qu'Hélène m'a écrit?"))
        // Not a person: Robot's own words are the model's to answer.
        assertNull(r("What did you say?"))
        assertNull(r("Qu'est-ce que tu as dit?"))
    }

    @Test fun summariesAndTranslations() {
        assertEquals(MessageCommand.Summarize(Lang.EN), r("Summarize my messages"))
        assertEquals(MessageCommand.Summarize(Lang.EN), r("summarise my unread messages"))
        assertEquals(MessageCommand.Summarize(Lang.FR), r("Résume mes messages non lus"))
        assertEquals(MessageCommand.Summarize(Lang.FR), r("Fais-moi un résumé de mes messages"))
        assertNull(r("summarize them"))
        assertEquals(MessageCommand.Summarize(Lang.EN), r("summarize them", reading = true))
        assertEquals(MessageCommand.Translate("Pierre", Lang.EN), r("Translate Pierre's message"))
        assertEquals(MessageCommand.Translate("Pierre", Lang.FR), r("Traduis le message de Pierre"))
        assertEquals(MessageCommand.Translate(null, Lang.EN), r("translate it", reading = true))
        assertEquals(MessageCommand.Translate(null, Lang.FR), r("traduis-le", reading = true))
        assertNull(r("translate it"))
    }

    @Test fun repliesKeepYourWordsAsSaid() {
        assertEquals(MessageCommand.Reply(null, "I'll be late", Lang.EN), r("Reply I'll be late"))
        assertEquals(MessageCommand.Reply(null, "that I'm late", Lang.EN), r("reply that I'm late"))
        assertEquals("I'm late.", ReplyText.of("that I'm late"))
        assertEquals("are you coming?", (r("reply are you coming?") as MessageCommand.Reply).words)
        assertEquals(MessageCommand.Reply("Mom", "I'll be late, start without me.", Lang.EN), r("Reply to Mom: I'll be late, start without me."))
        assertEquals(MessageCommand.Reply("Mom", "that I'm on my way", Lang.EN), r("reply to Mom that I'm on my way"))
        // No separator: the name is split off by the names Robot knows.
        val split = r("Reply to Mom I'll be late, see you") as MessageCommand.Reply
        assertEquals(MessageCommand.Reply("Mom I'll be late, see you", null, Lang.EN, split = true), split)
        assertEquals("Mom" to "I'll be late, see you", MessagesRouter.split(split.to!!, listOf("Mom", "Pierre")))
        assertEquals("Mom Martin" to "j'arrive", MessagesRouter.split("Mom Martin j'arrive", listOf("Mom", "Mom Martin")))
        assertEquals("Bob" to "hi there", MessagesRouter.split("Bob hi there", listOf("Mom")))
        // French.
        assertEquals(MessageCommand.Reply(null, "que je vais être en retard", Lang.FR), r("Réponds que je vais être en retard"))
        assertEquals("Je vais être en retard.", ReplyText.of("que je vais être en retard"))
        assertEquals(MessageCommand.Reply("maman", "j'arrive", Lang.FR), r("Réponds à maman : j'arrive"))
        assertEquals(MessageCommand.Reply("maman", "que je suis en route", Lang.FR), r("réponds à maman que je suis en route"))
        assertEquals(MessageCommand.Reply(null, "qu'il fait beau", Lang.FR), r("Réponds-lui qu'il fait beau"))
        // In another language: the words go translated.
        assertEquals(MessageCommand.Reply(null, "I'll be there at six", Lang.EN, inLang = Lang.FR), r("Reply in French: I'll be there at six"))
        assertEquals(MessageCommand.Reply(null, "j'arrive à six heures", Lang.FR, inLang = Lang.EN), r("Réponds en anglais : j'arrive à six heures"))
        assertEquals(MessageCommand.Reply("Mom", "I'm home", Lang.EN, inLang = Lang.FR), r("reply to Mom in French: I'm home"))
    }

    @Test fun answerHerForMeIsADraft() {
        for (s in listOf("Answer her for me", "reply", "reply to her", "Reply for me", "respond to him")) assertEquals(s, MessageCommand.Reply(null, null, Lang.EN), r(s))
        for (s in listOf("Réponds-lui", "réponds pour moi")) assertEquals(s, MessageCommand.Reply(null, null, Lang.FR), r(s))
        // "Answer" is too common otherwise.
        assertNull(r("answer the question"))
        assertNull(r("answer this: what is 2 plus 2"))
    }

    @Test fun markClearNextAndStop() {
        assertEquals(MessageCommand.Mark(MarkKind.READ, Lang.EN), r("Mark it as read"))
        assertEquals(MessageCommand.Mark(MarkKind.READ, Lang.FR), r("Marque comme lu"))
        assertEquals(MessageCommand.Mark(MarkKind.CLEAR, Lang.EN), r("Clear it"))
        assertEquals(MessageCommand.Mark(MarkKind.CLEAR, Lang.FR), r("Efface la notification"))
        // Only while Robot reads.
        assertNull(r("next"))
        assertNull(r("stop"))
        assertEquals(MessageCommand.Next(Lang.EN), r("Next", reading = true))
        assertEquals(MessageCommand.Next(Lang.FR), r("Suivant", reading = true))
        assertEquals(MessageCommand.Stop(Lang.EN), r("stop", reading = true))
        assertEquals(MessageCommand.Stop(Lang.FR), r("Arrête", reading = true))
    }

    /**
     * With "Read my messages" off (the default), ordinary questions that only look like a messages command go to the model
     * as before 0.7, instead of "Reading messages is off"; "clear it" with nothing read here is ordinary talk too.
     */
    @Test fun ordinaryQuestionsStayTheModelsWhileMessagesAreOff() {
        fun panel(s: String, on: Boolean, readHere: Boolean = false, reading: Boolean = false) =
            r(s, reading)?.takeIf { MessagesRouter.applies(it, on, readHere || reading, knows = { n -> n.equals("Mom", ignoreCase = true) }) }
        for (s in listOf("Reply in one word: is a tomato a fruit?", "Respond in French", "reply with yes or no: is it raining?",
            "Réponds en une phrase : c'est quoi la capitale du Pérou?", "Translate Pierre's message", "What did Mom say?", "Clear it", "remove that",
            "Mark it as read", "efface-la", "Reply to Mom: I'll be late")) {
            assertNull(s, panel(s, on = false))
        }
        // Said about messages, with them off: Robot says so.
        assertEquals(MessageCommand.Read(Lang.EN), panel("Read my messages", on = false))
        assertEquals(MessageCommand.Summarize(Lang.FR), panel("Résume mes messages", on = false))
        // On: the commands, as before; "clear it" and "remove that" once a conversation was read here.
        assertEquals(MessageCommand.Reply("Mom", "I'll be late", Lang.EN), panel("Reply to Mom: I'll be late", on = true))
        assertNull(panel("Clear it", on = true))
        assertNull(panel("remove that", on = true))
        assertEquals(MessageCommand.Mark(MarkKind.CLEAR, Lang.EN), panel("Clear it", on = true, readHere = true))
        assertEquals(MessageCommand.Mark(MarkKind.CLEAR, Lang.FR), panel("efface-la", on = true, reading = true))
        assertEquals(MessageCommand.Mark(MarkKind.READ, Lang.EN), panel("Mark it as read", on = true))
        assertEquals(MessageCommand.Next(Lang.EN), panel("next", on = true, reading = true))
    }

    /**
     * 0.7 after the review: with messages on, "reply …" / "respond …" / « réponds … » is about your messages only where it
     * can apply: a conversation was read in this conversation, or it names someone a conversation or People knows.
     * Otherwise it is an ordinary question for the model, in English and Québec French.
     */
    @Test fun aReplyGoesToMessagesOnlyWhereItCanApply() {
        val known = setOf("mom", "maman", "pierre", "mom martin")
        fun panel(s: String, readHere: Boolean = false) =
            r(s)?.takeIf { MessagesRouter.applies(it, on = true, readHere = readHere, knows = { n -> MessagesRouter.nameKey(n) in known }) }
        // Nothing read here, no one named: the model's.
        for (s in listOf("Reply in one word: is a tomato a fruit?", "Respond in French: how are you?", "reply with yes or no: is it raining?",
            "Reply I'll be late", "respond that I'm busy", "Answer her for me", "reply to her", "Reply to this email politely", "Respond to the critics in one line",
            "Réponds en une phrase : c'est quoi la capitale du Pérou?", "Réponds que je suis occupé", "Réponds-lui qu'il fait beau", "réponds pour moi",
            "Réponds à la question suivante : combien font 2 et 2?", "Reply to Einstein: thanks")) {
            assertNull(s, panel(s))
        }
        // Someone named whom a conversation or People knows: the messages'.
        assertEquals(MessageCommand.Reply("Mom", "I'll be late", Lang.EN), panel("Reply to Mom: I'll be late"))
        assertEquals(MessageCommand.Reply("mom", "that I'm on my way", Lang.EN), panel("reply to my mom that I'm on my way"))
        assertEquals(MessageCommand.Reply("Mom Martin I'll be late", null, Lang.EN, split = true), panel("Reply to Mom Martin I'll be late"))
        assertEquals(MessageCommand.Reply("Mom", null, Lang.EN, split = true), panel("Reply to Mom"))
        assertEquals(MessageCommand.Reply("maman", "j'arrive", Lang.FR), panel("Réponds à maman : j'arrive"))
        assertEquals(MessageCommand.Reply("Pierre", "que je suis en route", Lang.FR), panel("réponds à Pierre que je suis en route"))
        assertEquals(MessageCommand.Reply("Mom", "I'm home", Lang.EN, inLang = Lang.FR), panel("reply to Mom in French: I'm home"))
        // A conversation read here: "reply …" without a name is about it, in both languages.
        assertEquals(MessageCommand.Reply(null, "I'll be late", Lang.EN), panel("Reply I'll be late", readHere = true))
        assertEquals(MessageCommand.Reply(null, null, Lang.EN), panel("Answer her for me", readHere = true))
        assertEquals(MessageCommand.Reply(null, "I'll be there at six", Lang.EN, inLang = Lang.FR), panel("Reply in French: I'll be there at six", readHere = true))
        assertEquals(MessageCommand.Reply(null, "que je vais être en retard", Lang.FR), panel("Réponds que je vais être en retard", readHere = true))
        assertEquals(MessageCommand.Reply(null, null, Lang.FR), panel("Réponds-lui", readHere = true))
        // The rest of the commands are as before.
        assertEquals(MessageCommand.Read(Lang.EN), panel("Read my messages"))
        assertEquals(MessageCommand.Read(Lang.FR), panel("Lis mes messages"))
    }

    @Test fun theScreenAndEverythingElseStayOutside() {
        assertNull(r("What does this message say?"))
        assertNull(r("Qu'est-ce que dit ce message?"))
        assertNull(r("translate this message"))
        assertNull(r("Text Mom I'm on my way"))
        assertNull(r("What's the weather?"))
        assertNull(r("Remember that my sister's birthday is May 3"))
        assertNull(r("Read the news"))
        assertNull(r(""))
        assertNull(r("hey robot"))
    }
}
