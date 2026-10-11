package app.heyrobot.core.messages

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
