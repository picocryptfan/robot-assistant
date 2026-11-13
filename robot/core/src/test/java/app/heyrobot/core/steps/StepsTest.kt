// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.read.StepRouter
import app.heyrobot.core.read.StepSay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step by step (docs/plans/step-by-step-plan.md): the page's steps, the words, the timer offer, a question, the ends. */
class StepsTest {
    private val recipe = """
        Butter cookies

        - 1 cup butter, soft
        - 2 cups flour
        - 1/2 cup sugar

        1. Heat the oven to 350°F.
        2. Cream the butter and sugar
           until pale.
        3. Bake 12 to 15 minutes.
    """.trimIndent()

    @Test fun numberedStepsAreThePagesOwnText() {
        assertEquals(listOf("Heat the oven to 350°F.", "Cream the butter and sugar until pale.", "Bake 12 to 15 minutes."), StepSplit.split(recipe))
        assertEquals(listOf("Préchauffe le four.", "Mélange tout."), StepSplit.split("Étape 1 : Préchauffe le four.\nÉtape 2 : Mélange tout."))
        assertEquals(listOf("Open the box", "Lift the lid"), StepSplit.split("Step 1 Open the box\nStep 2 Lift the lid"))
    }

    @Test fun bulletsThenPlainLines() {
        assertEquals(listOf("Unplug it", "Wait ten seconds"), StepSplit.split("Reset\n- Unplug it\n- Wait ten seconds"))
        assertEquals(listOf("Fill the kettle.", "Boil it.", "Pour."), StepSplit.split("Fill the kettle.\n\nBoil it.\nPour."))
    }

    @Test fun noStepsOnAOneLinePage() {
        assertEquals(emptyList<String>(), StepSplit.split("Just one paragraph with no steps at all."))
        assertEquals(emptyList<String>(), StepSplit.split("   \n\n"))
    }

    @Test fun eachWordInBothLanguages() {
        val en = mapOf("Next" to StepWord.NEXT, "back" to StepWord.BACK, "again" to StepWord.AGAIN, "stop" to StepWord.STOP,
            "Hey robot, next step please" to StepWord.NEXT, "yes" to StepWord.YES, "no thanks" to StepWord.NO)
        val fr = mapOf("Suivant" to StepWord.NEXT, "retour" to StepWord.BACK, "encore" to StepWord.AGAIN, "arrête" to StepWord.STOP,
            "Étape suivante" to StepWord.NEXT, "répète" to StepWord.AGAIN, "oui" to StepWord.YES, "non merci" to StepWord.NO)
        for ((said, w) in en + fr) assertEquals(said, w, StepWords.of(said))
        for (said in listOf("next time I'll use less", "go back to the store", "stop the music and play jazz", "suivant le modèle"))
            assertNull(said, StepWords.of(said))
    }

    @Test fun timesInSteps() {
        assertEquals(25 * 60L, StepTime.seconds("Bake 25 minutes."))
        assertEquals(12 * 60L, StepTime.seconds("Bake 12 to 15 minutes."))
        assertEquals(90 * 60L, StepTime.seconds("Laisse mijoter 1 heure 30."))
        assertEquals(30L, StepTime.seconds("Stir for 30 seconds"))
        assertNull(StepTime.seconds("Heat the oven to 350°F."))
        assertNull(StepTime.seconds("Add 2 cups flour"))
    }

    @Test fun onlyQuestionsGoToThePageWhileStepsAreOpen() {
        for (q in listOf("set a timer for 25 minutes", "add butter to the list", "call the butter shop", "Mets du beurre sur la liste",
                "what time is it", "play some music while I bake"))
            assertNull(q, StepRouter.during(q, recipe, null))
        assertEquals(StepSay(StepWord.NEXT, null), StepRouter.during("hey robot, next", recipe, null))
        // A question goes to the page when a line of it answers (fix-step-by-step-b); else where it would without the steps.
        for (q in listOf("How much butter?", "combien de flour", "How long do I bake it?", "What temperature again?"))
            assertEquals(q, StepSay(null, q), StepRouter.during(q, recipe, null))
    }

    @Test fun aQuestionAnywhereReachesThePageAndNothingElseDoes() {
        val page = recipe + "\n4. Set a timer, then cool."
        val shown = "Heat the oven to 350°F."
        for (q in listOf("so how much butter", "what's the oven temperature", "What temperature?", "How long?", "and how hot"))
            assertEquals(q, StepSay(null, q), StepRouter.during(q, page, shown))
        // Sums and the timer come first; what the page never names goes where it would without the steps.
        for (q in listOf("how many grams in a cup", "how long is left on my timer", "How much vanilla?", "combien de farine",
                "what's the weather", "how much is 12 times 4"))
            assertNull(q, StepRouter.during(q, page, shown))
        val fr = "Biscuits\n\n1. Préchauffe le four à 180 °C.\n2. Cuire 20 minutes."
        assertEquals(StepSay(null, "quelle est la température du four"), StepRouter.during("quelle est la température du four", fr, null))
        assertEquals("1. Préchauffe le four à 180 °C.", StepAsk.answer(fr, "quelle est la température du four"))
        assertEquals("Cuire 20 minutes.", StepAsk.answer(fr, "combien de temps", shown = "Cuire 20 minutes."))
        assertEquals("2. Cuire 20 minutes.", StepAsk.answer(fr, "Combien de temps ?", shown = "Préchauffe le four à 180 °C."))
        assertEquals("Preheat to 350°F", StepAsk.answer("Cookies\nPreheat to 350°F\nMix well", "What temperature?"))
        assertEquals("Bake 12 to 15 minutes.", StepAsk.answer(recipe, "How long?", shown = "Bake 12 to 15 minutes."))
        assertEquals("3. Bake 12 to 15 minutes.", StepAsk.answer(recipe, "How long?", shown = shown))
        assertNull(StepAsk.answer(recipe, "how much"))
    }

    @Test fun theOfferTakesOnlyYesAndClosesAfterThirtySeconds() {
        for (w in listOf("ok", "okay", "sure", "yeah", "yep", "set it", "d'accord", "ouais", "vas-y")) assertNull(w, StepWords.of(w))
        assertEquals(StepWord.YES, StepWords.of("Yes please"))
        assertEquals(StepWord.YES, StepWords.of("oui"))
        val s = StepSession(StepSplit.split(recipe), recipe, now = 0)
        s.start(0); s.word(StepWord.NEXT, 1); s.word(StepWord.NEXT, 2)
        assertEquals(listOf(StepAct.Show(2, 3, "Bake 12 to 15 minutes.", null)), s.tick(2 + StepSession.OFFER_MS))
        assertNull(s.offer)
        assertEquals(emptyList<StepAct>(), s.word(StepWord.YES, 3 + StepSession.OFFER_MS))
        s.word(StepWord.AGAIN, 40_000)
        assertEquals(listOf(StepAct.SetTimer(720L), StepAct.Show(2, 3, "Bake 12 to 15 minutes.", null)),
            s.word(StepWord.YES, 40_000 + StepSession.OFFER_MS - 1))
    }

    @Test fun aCallOrCarModeEndsTheStepsSilently() {
        for (why in listOf(StepEnd.CALL, StepEnd.CAR)) {
            val s = StepSession(StepSplit.split(recipe), recipe, now = 0)
            s.start(0)
            assertEquals(listOf(StepAct.End(why)), s.cut(why))
            assertEquals(why, s.ended)
        }
    }

    @Test fun aYesOrANoTakesTheTimerButtonAway() {
        val bake = StepAct.Show(2, 3, "Bake 12 to 15 minutes.", null)
        for ((w, first) in listOf(StepWord.NO to emptyList(), StepWord.YES to listOf(StepAct.SetTimer(720L)))) {
            val s = StepSession(StepSplit.split(recipe), recipe, now = 0)
            s.start(0); s.word(StepWord.NEXT, 1); s.word(StepWord.NEXT, 2)
            assertEquals(w.name, first + bake, s.word(w, 3))
            assertEquals(w.name, emptyList<StepAct>(), s.word(w, 4))
        }
    }

    @Test fun timerOfferedAndSetOnlyOnAYes() {
        val s = StepSession(StepSplit.split(recipe), recipe, now = 0)
        s.start(0)
        s.word(StepWord.NEXT, 1)
        val third = s.word(StepWord.NEXT, 2)
        assertTrue(third.contains(StepAct.Say(HandsFreeStrings.stepsTimerOffer("duration" to app.heyrobot.core.i18n.Arg.Seconds(720)))))
        assertEquals(StepAct.Show(2, 3, "Bake 12 to 15 minutes.", 720L), third.first())
        // No yes (another word, or a no): no timer, and the offer is gone.
        assertTrue(s.word(StepWord.AGAIN, 3).none { it is StepAct.SetTimer })
        assertTrue(s.word(StepWord.NO, 4).none { it is StepAct.SetTimer })
        assertTrue(s.word(StepWord.YES, 5).none { it is StepAct.SetTimer })
        s.word(StepWord.AGAIN, 6)
        assertEquals(listOf(StepAct.SetTimer(720L), StepAct.Show(2, 3, "Bake 12 to 15 minutes.", null)), s.word(StepWord.YES, 7))
        // A step with no time offers none: a yes there sets nothing.
        s.word(StepWord.BACK, 8)
        assertNull(s.offer)
        assertEquals(emptyList<StepAct>(), s.word(StepWord.YES, 9))
    }

    @Test fun nextBackAgainAndTheEnds() {
        val s = StepSession(listOf("One", "Two"), "One\nTwo", now = 0)
        assertEquals(StepAct.Show(0, 2, "One", null), s.start(0).first())
        assertTrue(s.word(StepWord.BACK, 1).contains(StepAct.Say(HandsFreeStrings.stepsFirst())))
        assertEquals(1, (s.word(StepWord.NEXT, 2).first() as StepAct.Show).index)
        assertEquals(listOf(StepAct.Say(HandsFreeStrings.stepsLast())), s.word(StepWord.NEXT, 3))
        assertEquals(1, (s.word(StepWord.AGAIN, 4).first() as StepAct.Show).index)
        assertEquals(StepAct.End(StepEnd.STOP), s.word(StepWord.STOP, 5).last())
        assertEquals(emptyList<StepAct>(), s.word(StepWord.NEXT, 6))
        val left = StepSession(listOf("One", "Two"), "", now = 0)
        assertEquals(listOf(StepAct.End(StepEnd.LEFT)), left.left())
    }

    @Test fun idleEndsAfterThirtyMinutesWithNoWord() {
        val s = StepSession(listOf("One", "Two"), "", now = 0)
        s.start(0)
        assertEquals(emptyList<StepAct>(), s.tick(StepSession.IDLE_MS - 1))
        s.word(StepWord.NEXT, 10 * 60_000L)
        assertEquals(emptyList<StepAct>(), s.tick(StepSession.IDLE_MS + 1))
        val end = s.tick(10 * 60_000L + StepSession.IDLE_MS)
        assertEquals(listOf(StepAct.Say(HandsFreeStrings.stepsIdle()), StepAct.End(StepEnd.IDLE)), end)
        assertEquals(StepEnd.IDLE, s.ended)
        assertEquals(30 * 60_000L, StepSession.IDLE_MS)
    }

    @Test fun theWholeQuestionMustBeOnThePage() {
        val page = recipe + "\n4. Set a timer, then cool.\n\nIf you don't have buttermilk, use milk and lemon."
        val shown = "Heat the oven to 350°F."
        // Every word asked about is on the page (a temperature word takes the degrees, a time word a duration), or it
        // falls through (review-step-by-step-c Lows 2 and 3).
        for (q in listOf("How much battery do I have left", "how long until Christmas", "What's the temperature outside",
                "how many steps did I walk today", "how long is the drive to work", "what's the temperature in Halifax",
                "how much does a coffee cost", "how hot is it outside", "how much time is left on the parking meter",
                "how many people are coming", "il reste combien de batterie", "combien de temps avant Noël",
                "quelle température fait-il dehors", "combien de pas aujourd'hui", "quelle est la température de l'eau du lac"))
            assertNull(q, StepRouter.during(q, page, shown))
        for (q in listOf("What temperature?", "How long?", "how many minutes in the oven",
                "what's the oven temperature", "how much butter and sugar"))
            assertEquals(q, StepSay(null, q), StepRouter.during(q, page, shown))
        assertEquals("3. Bake 12 to 15 minutes.", StepAsk.answer(page, "how many minutes in the oven"))
        assertEquals("3. Bake 12 to 15 minutes.", StepAsk.answer(page, "how much time does it bake"))
        assertEquals("1. Heat the oven to 350°F.", StepAsk.answer(page, "ok so what temperature then"))
    }

    @Test fun aQuestionIsAnsweredFromThePageOnly() {
        val s = StepSession(StepSplit.split(recipe), recipe, now = 0)
        assertEquals(listOf(StepAct.Say(Phrase.Plain("1 cup butter, soft"))), s.ask("How much butter again?", 1))
        assertEquals(listOf(StepAct.Say(Phrase.Plain("2 cups flour"))), s.ask("Combien de flour?", 2))
        assertEquals(listOf(StepAct.Say(HandsFreeStrings.stepsPageSilent())), s.ask("How much salt?", 3))
        assertNull(StepAsk.answer(recipe, "how much"))
    }
}
