// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.7: the panel's feedback phrases, English and Québec French, before the model; a question stays a question. */
class FeedbackRouterTest {
    private fun bug(text: String? = null) = FeedbackCommand(FeedbackKind.BUG, text)
    private fun idea(text: String? = null) = FeedbackCommand(FeedbackKind.IDEA, text)
    private fun other(text: String? = null) = FeedbackCommand(FeedbackKind.OTHER, text)

    @Test fun englishPhrasesOpenSendFeedbackWithTheirKind() {
        for (s in listOf("report a bug", "Report a bug.", "hey robot, report a bug", "Please report a bug", "I want to report a bug",
            "I'd like to report a bug!", "file a bug report", "send a bug report", "bug report", "report a problem", "can you report a bug?",
            "Report a bug please", "report a bug, thanks")) assertEquals(s, bug(), FeedbackRouter.route(s))
        for (s in listOf("I have an idea", "I've got an idea", "here's an idea", "feature request", "I have a feature request",
            "request a feature", "make a feature request", "ok robot I have an idea.")) assertEquals(s, idea(), FeedbackRouter.route(s))
        for (s in listOf("send feedback", "Send some feedback", "give feedback", "leave feedback", "I want to send feedback"))
            assertEquals(s, other(), FeedbackRouter.route(s))
    }

    /** RP3: "report a problem", "that's a bug", « signaler un problème », « c'est un bogue » open the card; "that's not right" stays a correction. */
    @Test fun moreWaysToReportAProblem() {
        for (s in listOf("report a problem", "That's a bug", "that's a bug!", "this is a bug", "that is a bug", "hey robot, that's a bug",
            "signaler un problème", "Signale un problème", "c'est un bogue", "C’est un bug")) assertEquals(s, bug(), FeedbackRouter.route(s))
        assertEquals(bug("The timer didn't ring"), FeedbackRouter.route("that's a bug: the timer didn't ring"))
        for (s in listOf("that's not right", "is that a bug?", "that's a bug in Excel?", "c'est pas correct", "that's wrong"))
            assertNull(s, FeedbackRouter.route(s))
    }

    /** Review M1: "that's a bug" opens the card only alone, with tail words, or with ":" text; with words after it, it goes to the model. */
    @Test fun thatsABugWithWordsAfterItIsForTheModel() {
        for (s in listOf("that's a bug robot", "that's a bug, please", "this is a bug, thanks", "c'est un bogue s'il te plaît", "C'est un bogue, Robot"))
            assertEquals(s, bug(), FeedbackRouter.route(s))
        assertEquals(bug("Le minuteur n'a pas sonné"), FeedbackRouter.route("c'est un bogue : le minuteur n'a pas sonné"))
        for (s in listOf("that's a bug in my code, how do I fix it", "it's a bug on my wall what is it", "this is a bug in my code",
            "that is a bug I found in Excel", "that was a bug", "it's a bug", "c'est un bogue dans mon code comment le régler", "c'était un bogue"))
            assertNull(s, FeedbackRouter.route(s))
    }

    @Test fun quebecFrenchPhrases() {
        for (s in listOf("signaler un bogue", "Signaler un bug", "je veux signaler un bogue", "Robot, signale un bogue s'il te plaît",
            "signaler un problème", "rapport de bogue", "faire un rapport de bug")) assertEquals(s, bug(), FeedbackRouter.route(s))
        for (s in listOf("j'ai une idée", "J’ai une idée", "dis robot, j'ai une idée", "j'aurais une suggestion", "demande de fonctionnalité"))
            assertEquals(s, idea(), FeedbackRouter.route(s))
        for (s in listOf("envoyer un commentaire", "Envoie un commentaire", "laisser un commentaire", "je voudrais donner mon avis"))
            assertEquals(s, other(), FeedbackRouter.route(s))
    }

    @Test fun whatYouSaidAfterThePhraseStartsYourText() {
        assertEquals(idea("Read my calendar in the brief"), FeedbackRouter.route("I have an idea: read my calendar in the brief"))
        assertEquals(idea("Read my calendar in the brief"), FeedbackRouter.route("I have an idea that read my calendar in the brief"))
        assertEquals(bug("The timer didn't ring this morning."), FeedbackRouter.route("report a bug: the timer didn't ring this morning."))
        assertEquals(bug("Le minuteur n'a pas sonné"), FeedbackRouter.route("Signaler un bogue : le minuteur n'a pas sonné"))
        assertEquals(idea("Lire mon calendrier dans le résumé"), FeedbackRouter.route("J'ai une idée, lire mon calendrier dans le résumé"))
        assertEquals(other("The new voice is great"), FeedbackRouter.route("send feedback - the new voice is great"))
        assertEquals(other("La nouvelle voix est super"), FeedbackRouter.route("envoyer un commentaire: la nouvelle voix est super"))
        // At most what a prefill keeps.
        assertEquals(FeedbackPrefill.MAX_TEXT, FeedbackRouter.route("report a bug " + "x".repeat(5_000))!!.text!!.length)
    }

    @Test fun questionsAndOtherTalkStayForTheModel() {
        for (s in listOf("how do I report a bug in Excel?", "can you report a bug in Excel?", "What is a feature request?",
            "Is it a bug report?", "I found a bug in my code", "what's an idea for dinner", "reporting a bug", "the bug report was long",
            "comment signaler un bogue dans Excel?", "j'ai une question", "feedback", "send a text to mom", "", "   ", "hey robot"))
            assertNull(s, FeedbackRouter.route(s))
    }
}
