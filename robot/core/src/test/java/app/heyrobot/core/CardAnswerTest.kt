package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Settings › Safety & privacy › "Answer cards by voice": a plain yes or no to the waiting card, and nothing else. */
class CardAnswerTest {
    @Test fun yesInEnglishAndFrench() {
        for (s in listOf("yes", "Yes.", "yeah", "yep, do it", "Do it", "go ahead", "Go ahead please", "send it", "Send it!", "okay, send it", "sure",
                "yes please", "hey robot, yes", "Robot, do it", "oui", "Oui !", "envoie", "envoie-le", "vas-y", "d'accord", "oui, envoie", "fais-le",
                "c'est bon", "go for it", "Yes, thank you")) {
            assertEquals(s, CardAnswer.Kind.YES, CardAnswer.of(s))
        }
    }

    @Test fun noReusesTheCancelWords() {
        for (s in listOf("no", "No.", "nope", "cancel", "Cancel that", "never mind", "nevermind", "no thanks", "non", "non merci", "annule", "annule ça",
                "forget it", "stop", "hey robot, cancel", "wait, cancel")) {
            assertEquals(s, CardAnswer.Kind.NO, CardAnswer.of(s))
        }
    }

    @Test fun anythingMoreIsNeither() {
        for (s in listOf("", "  ", "yes but at 5", "send it to Sam", "do it tomorrow", "no problem see you soon", "what is this", "set a timer",
                "yes yes yes yes yes yes yes yes yes", "go to the store", "okay so what does it say")) {
            assertNull(s, CardAnswer.of(s))
        }
        // A wake phrase of your own in front still counts.
        assertEquals(CardAnswer.Kind.YES, CardAnswer.of("computer, yes", listOf("computer")))
    }
}
