// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken cards and spoken choices: what is asked by voice, and what an answer means. */
class SpokenChoiceTest {
    private val callWords = listOf("call", "call her", "call him", "call them", "call it", "dial", "dial it", "appelle", "appelle la", "appelle le", "compose")
    private val twoMoms = SpokenCard("Call Mom", listOf(SpokenOption("Mom Martin, mobile", setOf("cell")), SpokenOption("Mom Martin, work")),
        CardAnswer.Waiting(2, AskWhy.WHO), spokenWho = true, confirmWords = callWords, personal = true, carFunction = true)
    private val threeMoms = twoMoms.copy(options = twoMoms.options + SpokenOption("Mom Martin, home"), waiting = CardAnswer.Waiting(3, AskWhy.WHO))
    private val confirmCall = SpokenCard("Call Mom, mobile, ending 0101", confirmWords = callWords, personal = true, carFunction = true)
    private val timer = SpokenCard("Set a timer for 10 minutes", tool = ToolName.SET_TIMER)

    private fun a(text: String, card: SpokenCard, said: String? = null) = SpokenChoice.answer(text, card, said)

    @Test fun yesAndNoReuseTheCardWords() {
        for (s in listOf("yes", "Yes.", "yeah", "send it", "Send it!", "go ahead", "do it", "oui", "Oui !", "envoie", "envoie-le", "vas-y", "d'accord", "c'est bon",
                "hey robot, yes", "okay, do it", "yes please")) {
            assertEquals(s, SpokenAnswer(AnswerCode.YES, confidence = 1.0), a(s, timer))
        }
        for (s in listOf("no", "No.", "nope", "cancel", "never mind", "non", "non merci", "annule", "annule ça", "laisse faire", "laisse tomber", "forget it", "stop",
                "hey robot, cancel", "none of them", "neither", "aucun", "aucune des deux", "ni l'un ni l'autre")) {
            assertEquals(s, AnswerCode.NO, a(s, timer).kind)
            assertEquals(s, AnswerCode.NO, a(s, twoMoms).kind)
        }
    }

    @Test fun aCardsOwnVerbConfirms() {
        for (s in listOf("call", "Call her.", "call him please", "yes, call her", "appelle", "appelle-la", "oui, appelle-le", "dial it", "compose")) {
            assertEquals(s, AnswerCode.YES, a(s, confirmCall).kind)
            assertTrue(s, a(s, confirmCall).sure)
        }
        // Only that card's verbs: "call" doesn't confirm a timer.
        assertEquals(AnswerCode.UNCLEAR, a("call", timer).kind)
        // "Call Bob" isn't "call": everything must be used up.
        for (s in listOf("call Bob", "call her later", "call him at work tomorrow", "appelle Marie")) assertEquals(s, AnswerCode.UNCLEAR, a(s, confirmCall).kind)
        // Its verb said with a no is a no.
        for (s in listOf("don't call", "no, don't call her", "do not call", "n'appelle pas", "never mind, don't call", "don't call the second one")) {
            assertEquals(s, AnswerCode.NO, a(s, confirmCall).kind)
            assertEquals(s, AnswerCode.NO, a(s, twoMoms).kind)
        }
    }

    @Test fun choicesByNumber() {
        val cases = mapOf(
            "the first" to 0, "the first one" to 0, "first" to 0, "number one" to 0, "one" to 0, "1" to 0, "option one" to 0, "yes, the first one" to 0,
            "the second one" to 1, "second" to 1, "number two" to 1, "two" to 1, "2" to 1, "the last one" to 1, "the second please" to 1,
            "la première" to 0, "le premier" to 0, "première" to 0, "numéro un" to 0, "la deuxième" to 1, "le deuxième" to 1, "numéro deux" to 1, "deux" to 1,
            "la seconde" to 1, "la dernière" to 1, "oui, la deuxième" to 1, "hey robot, the second one" to 1, "call the second one" to 1, "appelle le deuxième" to 1,
        )
        for ((s, i) in cases) assertEquals(s, SpokenAnswer(AnswerCode.PICK, i, 0.95), a(s, twoMoms))
        assertEquals(SpokenAnswer(AnswerCode.PICK, 2, 0.95), a("the third one", threeMoms))
        assertEquals(SpokenAnswer(AnswerCode.PICK, 2, 0.95), a("la troisième", threeMoms))
        assertEquals(SpokenAnswer(AnswerCode.PICK, 2, 0.95), a("the last one", threeMoms))
        // Out of range, two numbers, or a number that contradicts the name: not an answer.
        for (s in listOf("the third one", "number three", "the first or the second", "the second, no the first", "the first one at work")) {
            assertEquals(s, AnswerCode.UNCLEAR, a(s, twoMoms).kind)
        }
    }

    @Test fun choicesByName() {
        val cases = mapOf(
            "the work one" to 1, "work" to 1, "at work" to 1, "the one at work" to 1, "her work number" to 1, "celui du travail" to 1,
            "le travail" to 1, "au bureau" to 1, "mobile" to 0, "the mobile" to 0, "her cell" to 0, "the cell" to 0, "le cell" to 0, "sur son cell" to 0,
            "son cellulaire" to 0, "Mom Martin mobile" to 0, "Mom Martin, work" to 1, "the second one, work" to 1, "the work one, the second" to 1,
        )
        for ((s, i) in cases) {
            val r = a(s, twoMoms)
            assertEquals(s, AnswerCode.PICK, r.kind)
            assertEquals(s, i, r.index)
            assertTrue(s, r.sure)
        }
        assertEquals(2, a("à la maison", threeMoms).index)
        assertEquals(2, a("home", threeMoms).index)
        // A name both choices share, or a word neither has: not an answer.
        for (s in listOf("Mom", "Martin", "Mom Martin", "the other one", "Bob", "mobile or work", "the car one", "maybe")) assertEquals(s, AnswerCode.UNCLEAR, a(s, twoMoms).kind)
        // A yes to a card with choices doesn't say which.
        val yes = a("yes", twoMoms)
        assertEquals(AnswerCode.UNCLEAR, yes.kind)
        assertFalse(yes.sure)
    }

    @Test fun robotsOwnPromptHeardBackIsNeverAnAnswer() {
        val prompt = SpokenCards.prompt(twoMoms, Lang.EN)
        assertEquals("Call Mom: Mom Martin, mobile, or Mom Martin, work?", prompt)
        for (s in listOf("Mom Martin mobile or Mom Martin work", "mobile or Mom Martin work", "call mom mom martin mobile", "Call Mom: Mom Martin, mobile, or Mom Martin, work?")) {
            assertEquals(s, AnswerCode.ECHO, a(s, twoMoms, prompt).kind)
        }
        // A short answer that happens to be in the prompt is still an answer.
        assertEquals(0, a("Mom Martin, mobile", twoMoms, prompt).index)
        assertEquals(1, a("work", twoMoms, prompt).index)
        val yesNo = SpokenCards.prompt(confirmCall, Lang.EN) + " " + SpokenCards.hint(0, Lang.EN)
        assertEquals(AnswerCode.ECHO, a("say yes or no", confirmCall, yesNo).kind)
        assertEquals(AnswerCode.ECHO, a("yes or no", confirmCall, yesNo).kind)
        assertEquals(AnswerCode.ECHO, a("call mom mobile ending 0101", confirmCall, yesNo).kind)
        assertEquals(AnswerCode.YES, a("yes", confirmCall, yesNo).kind)
        assertEquals(AnswerCode.NO, a("no", confirmCall, yesNo).kind)
        val fr = SpokenCards.prompt(twoMoms.copy(action = "Appeler Maman"), Lang.FR)
        assertEquals("Appeler Maman : Mom Martin, mobile, ou Mom Martin, work?", fr)
        assertEquals(AnswerCode.ECHO, a("mobile ou Mom Martin work", twoMoms, fr).kind)
    }

    @Test fun misfiresAndRepeats() {
        for (s in listOf("what's the weather", "set a timer for 5 minutes", "yes but at 5", "send it to Sam", "call me later", "I don't know", "hmm let me think",
                "turn on car mode", "play some music", "the weather in Montreal", "yes yes yes yes yes yes yes yes yes yes yes")) {
            assertEquals(s, AnswerCode.UNCLEAR, a(s, twoMoms).kind)
            assertEquals(s, AnswerCode.UNCLEAR, a(s, timer).kind)
        }
        for (s in listOf("repeat", "repeat that", "say that again", "what?", "sorry?", "pardon?", "come again", "répète", "répète ça", "quoi?", "comment?", "hey robot, repeat please")) {
            assertEquals(s, AnswerCode.REPEAT, a(s, twoMoms).kind)
        }
        for (s in listOf("", "   ", "hey robot")) assertEquals(s, AnswerCode.SILENCE, a(s, twoMoms).kind)
    }

    @Test fun afterAnAnswer() {
        val en = Lang.EN
        assertEquals(SpokenStep.Confirm, SpokenChoice.next(a("yes", timer), 0, 0, 0, en))
        assertEquals(SpokenStep.Decline, SpokenChoice.next(a("no", twoMoms), 0, 0, 2, en))
        assertEquals(SpokenStep.Choose(1), SpokenChoice.next(a("la deuxième", twoMoms), 0, 0, 2, en))
        assertEquals(SpokenStep.Reread, SpokenChoice.next(a("repeat", twoMoms), 1, 0, 2, en))
        // Silence twice, or an echo and then nothing: held for later, in a headset and in the car alike.
        assertEquals(SpokenStep.AskAgain("Say the first or the second, or no."), SpokenChoice.next(a("", twoMoms), 0, 0, 2, en))
        assertEquals(SpokenStep.Hold("I'll keep that for when you can look at the phone."), SpokenChoice.next(a("", twoMoms), 1, 0, 2, en))
        assertEquals(SpokenStep.Hold("I'll keep that for when you can look at the phone."), SpokenChoice.next(a("blah", timer), 1, 0, 0, en))
        assertEquals(SpokenStep.Hold("Je garde ça pour quand tu pourras regarder le téléphone."), SpokenChoice.next(a("blah", timer), 1, 0, 0, Lang.FR))
        assertEquals(SpokenStep.AskAgain("Dis oui ou non."), SpokenChoice.next(a("mmm", timer), 0, 0, 0, Lang.FR))
        assertEquals(SpokenStep.AskAgain("Say the first, the second or the third, or no."), SpokenChoice.next(a("yes", threeMoms), 0, 0, 3, en))
        // "Repeat" is re-read at most twice, then counts like any other non-answer.
        assertEquals(SpokenStep.Hold("I'll keep that for when you can look at the phone."), SpokenChoice.next(a("repeat", twoMoms), 1, SpokenChoice.MAX_REREADS, 2, en))
    }

    @Test fun whatMayBeAnsweredByVoice() {
        val en = Lang.EN
        val headset = CardContext(SessionKind.HEADSET)
        val car = CardContext(SessionKind.CAR)
        val look = "I'll keep that for when you can look at the phone."
        // On the phone: the panel's card, as today.
        assertEquals(CardVoice.Panel, SpokenCards.plan(timer, CardContext(SessionKind.PHONE), en))
        // A plain card is asked.
        assertEquals(CardVoice.Ask("Set a timer for 10 minutes?", 0, false), SpokenCards.plan(timer, headset, en))
        assertEquals(CardVoice.Ask("Call Mom, mobile, ending 0101?", 0, true), SpokenCards.plan(confirmCall, car, en))
        // Who-to choices for a call: spoken; for anything else: held, the same way in a headset and in the car.
        assertEquals(CardVoice.Ask("Call Mom: Mom Martin, mobile, or Mom Martin, work?", 2, true), SpokenCards.plan(twoMoms, car, en))
        val text = twoMoms.copy(spokenWho = false, action = "Text Mom")
        assertEquals(CardVoice.Defer(look, CardVoiceCode.WHO_UNSURE), SpokenCards.plan(text.copy(tool = null), car, en))
        assertEquals(CardVoice.Defer(look, CardVoiceCode.WHO_UNSURE), SpokenCards.plan(text.copy(tool = null), headset, en))
        // Cards that must be seen stay tap-only: a learned name, the first time online, deleting, personal content to a helper.
        for (c in listOf(timer.copy(waiting = CardAnswer.Waiting(tapFor = AskWhy.LEARNED)), timer.copy(waiting = CardAnswer.Waiting(tapFor = AskWhy.ONLINE)),
                timer.copy(deletes = true), timer.copy(waiting = CardAnswer.Waiting(personal = true)))) {
            assertEquals(CardVoiceCode.MUST_SEE, (SpokenCards.plan(c, headset, en) as CardVoice.Defer).why)
            assertEquals(SpokenCards.plan(c, headset, en), SpokenCards.plan(c, car, en))
        }
        // ... the last one may be answered with "Voice yes can send personal content" on.
        assertTrue(SpokenCards.plan(timer.copy(waiting = CardAnswer.Waiting(personal = true)), headset.copy(voiceYesPersonal = true), en) is CardVoice.Ask)
        // Even a learned name for a call stays tap-only.
        assertEquals(CardVoiceCode.MUST_SEE, (SpokenCards.plan(twoMoms.copy(waiting = CardAnswer.Waiting(2, AskWhy.LEARNED)), car, en) as CardVoice.Defer).why)
        // More than three choices: held.
        val four = threeMoms.copy(options = threeMoms.options + SpokenOption("Mom Martin, other"), waiting = CardAnswer.Waiting(4, AskWhy.WHO))
        assertEquals(CardVoiceCode.TOO_MANY, (SpokenCards.plan(four, car, en) as CardVoice.Defer).why)
        // "Answer cards by voice" off: held.
        assertEquals(CardVoiceCode.VOICE_OFF, (SpokenCards.plan(timer, headset.copy(voiceCards = false), en) as CardVoice.Defer).why)
        assertEquals(CardVoiceCode.VOICE_OFF, (SpokenCards.plan(timer, car.copy(voiceCards = false), en) as CardVoice.Defer).why)
        // Locked: a question that names people waits for the unlock, except car functions in a trusted car session.
        assertEquals(CardVoice.Defer("Unlock to answer that.", CardVoiceCode.LOCKED), SpokenCards.plan(confirmCall, headset.copy(locked = true), en))
        assertEquals(CardVoiceCode.LOCKED, (SpokenCards.plan(confirmCall, car.copy(locked = true), en) as CardVoice.Defer).why)
        assertTrue(SpokenCards.plan(confirmCall, car.copy(locked = true, carTrusted = true), en) is CardVoice.Ask)
        assertTrue(SpokenCards.plan(confirmCall.copy(carFunction = false), car.copy(locked = true, carTrusted = true), en) is CardVoice.Defer)
        assertTrue(SpokenCards.plan(timer, headset.copy(locked = true), en) is CardVoice.Ask)
    }

    /**
     * 1.0 (the user, 13 Oct 2026: "I do not want any driving related restrictions"): what car mode used to hold for parking
     * (opening an app, a text, an email or event draft, freeing memory or storage) and a helper's app hand-off it refused
     * are answered in the car exactly as in a headset, unlocked and locked, with every other rule as it is.
     */
    @Test fun theCarAnswersCardsExactlyAsAHeadset() {
        val en = Lang.EN
        val formerlyHeld = listOf(ToolName.OPEN_APP, ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE,
            ToolName.ASK_HELPER)
        val headset = CardContext(SessionKind.HEADSET)
        val car = CardContext(SessionKind.CAR)
        for (t in formerlyHeld + listOf(ToolName.SET_TIMER, ToolName.MAPS_NAVIGATE, ToolName.MAPS_SEARCH, ToolName.FLASHLIGHT, ToolName.ADD_NOTE)) {
            val card = timer.copy(action = "Do it", tool = t)
            // Unlocked: asked by voice, as in a headset.
            assertEquals(t.id, CardVoice.Ask("Do it?", 0, false), SpokenCards.plan(card, car, en))
            // Every variant decides the same in the car and in a headset (the lock, personal words, tap-only cards, choices).
            for (c in listOf(card, card.copy(personal = true), card.copy(deletes = true), card.copy(waiting = CardAnswer.Waiting(personal = true)),
                    card.copy(waiting = CardAnswer.Waiting(tapFor = AskWhy.LEARNED)), card.copy(waiting = CardAnswer.Waiting(2, AskWhy.WHO), options = twoMoms.options))) {
                for (ctx in listOf(headset, headset.copy(locked = true), headset.copy(voiceCards = false), headset.copy(voiceYesPersonal = true))) {
                    assertEquals(t.id, SpokenCards.plan(c, ctx, en), SpokenCards.plan(c, ctx.copy(session = SessionKind.CAR), en))
                }
            }
            // Locked, personal: the unlock, in the car too (a trusted car session loosens only car functions).
            assertEquals(t.id, CardVoiceCode.LOCKED, (SpokenCards.plan(card.copy(personal = true), car.copy(locked = true, carTrusted = true), en) as CardVoice.Defer).why)
            // Locked, nothing personal: asked, as in a headset.
            assertTrue(t.id, SpokenCards.plan(card, car.copy(locked = true), en) is CardVoice.Ask)
        }
        // No card is ever refused or held because you are driving: nothing says "park" or "drive".
        for (t in formerlyHeld) for (l in Lang.entries) for (ctx in listOf(car, car.copy(locked = true), car.copy(voiceCards = false))) {
            val say = when (val v = SpokenCards.plan(timer.copy(tool = t, personal = true), ctx, l)) {
                is CardVoice.Defer -> v.say
                is CardVoice.Ask -> v.prompt
                else -> ""
            }
            for (w in listOf("park", "drive", "stationn", "conduis")) assertFalse("$t $l", say.contains(w))
        }
    }

    @Test fun promptsInBothLanguages() {
        assertEquals("Set a timer for 10 minutes?", SpokenCards.prompt(timer.copy(action = "Set a timer for 10 minutes."), Lang.EN))
        val simple = SpokenCard("Which app", listOf(SpokenOption("Signal"), SpokenOption("Molly"), SpokenOption("Cheogram")))
        assertEquals("Which app: Signal, Molly, or Cheogram?", SpokenCards.prompt(simple, Lang.EN))
        assertEquals("Quelle app : Signal, Molly, ou Cheogram?", SpokenCards.prompt(simple.copy(action = "Quelle app"), Lang.FR))
        assertEquals("Which app: Signal or Molly?", SpokenCards.prompt(simple.copy(options = simple.options.take(2)), Lang.EN))
        assertEquals("Dis le premier ou le deuxième, ou non.", SpokenCards.hint(2, Lang.FR))
        assertEquals(1, a("Molly", simple).index)
        assertEquals(2, a("Cheogram please", simple).index)
    }
}
