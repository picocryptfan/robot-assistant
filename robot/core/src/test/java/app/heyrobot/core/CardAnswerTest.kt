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

    private fun reply(text: String, card: CardAnswer.Waiting = CardAnswer.Waiting(), voiceCards: Boolean = true, voiceYesPersonal: Boolean = false,
        spoken: Boolean = true) = CardAnswer.reply(text, card, voiceCards, voiceYesPersonal, spoken)

    /** A plain card: yes confirms, no declines, a card with one choice takes it; anything else isn't an answer. */
    @Test fun aPlainCardTakesYesOrNo() {
        assertEquals(CardAnswer.Reply(CardAnswer.Does.CONFIRM), reply("yes"))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.CONFIRM), reply("oui, envoie", spoken = false))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.DECLINE), reply("no"))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.CHOOSE_ONLY), reply("do it", CardAnswer.Waiting(choices = 1)))
        assertEquals(null, reply("send it to Sam"))
        assertEquals(null, reply(""))
        assertEquals(CardAnswer.PROMPT, CardAnswer.prompt(CardAnswer.Waiting()))
    }

    /**
     * Names learned from Cheogram, not being sure who (several people, a near match, a number the model wrote), and the
     * nearest place's first-use card need a tap: a yes says why, a no or cancel still declines.
     */
    @Test fun aCardThatNeedsATapTakesOnlyNo() {
        for ((why, words) in listOf(AskWhy.LEARNED to CardAnswer.TAP_LEARNED, AskWhy.WHO to CardAnswer.TAP_UNSURE, AskWhy.ONLINE to CardAnswer.TAP_ONLINE)) {
            for (choices in listOf(0, 1, 3)) {
                val card = CardAnswer.Waiting(choices = choices, tapFor = why)
                assertEquals("$why $choices", CardAnswer.Reply(CardAnswer.Does.TAP, words), reply("yes", card))
                assertEquals("$why $choices", CardAnswer.Reply(CardAnswer.Does.TAP, words), reply("yes", card, spoken = false))
                assertEquals("$why $choices", CardAnswer.Reply(CardAnswer.Does.TAP, words), reply("yes", card, voiceYesPersonal = true))
                for (no in listOf("no", "cancel", "never mind", "annule")) assertEquals(no, CardAnswer.Reply(CardAnswer.Does.DECLINE), reply(no, card))
            }
            assertEquals("$words Or say no.", CardAnswer.prompt(CardAnswer.Waiting(tapFor = why)))
        }
        // Several choices without a reason of their own (which app's cache): tap the one meant.
        assertEquals(CardAnswer.Reply(CardAnswer.Does.TAP, CardAnswer.TAP_ONE), reply("yes", CardAnswer.Waiting(choices = 2)))
        assertEquals("Tap the one you mean on the card, or say no.", CardAnswer.prompt(CardAnswer.Waiting(choices = 2)))
        // The reason a card needs a tap, from why it shows (a learned name first).
        assertEquals(null, CardAnswer.tapFor(emptySet()))
        assertEquals(null, CardAnswer.tapFor(setOf(AskWhy.PICK, AskWhy.DELETE)))
        assertEquals(AskWhy.WHO, CardAnswer.tapFor(setOf(AskWhy.WHO, AskWhy.PICK)))
        assertEquals(AskWhy.LEARNED, CardAnswer.tapFor(setOf(AskWhy.WHO, AskWhy.LEARNED)))
        assertEquals(AskWhy.ONLINE, CardAnswer.tapFor(setOf(AskWhy.ONLINE)))
    }

    /** What a tap would send to Claude: a yes needs "Voice yes can send personal content". */
    @Test fun personalContentNeedsItsOwnSwitch() {
        val card = CardAnswer.Waiting(personal = true, confirm = "Share with Claude")
        assertEquals(CardAnswer.Reply(CardAnswer.Does.TAP, "Tap Share with Claude to share it with Claude."), reply("yes", card))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.CONFIRM), reply("yes", card, voiceYesPersonal = true))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.DECLINE), reply("no", card))
    }

    /** "Answer cards by voice" off: nothing spoken answers a card (no included); a typed answer still does. */
    @Test fun voiceOffLeavesTypedAnswers() {
        assertEquals(null, reply("yes", voiceCards = false))
        assertEquals(null, reply("no", voiceCards = false))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.CONFIRM), reply("yes", voiceCards = false, spoken = false))
        assertEquals(CardAnswer.Reply(CardAnswer.Does.DECLINE), reply("no", voiceCards = false, spoken = false))
    }
}
