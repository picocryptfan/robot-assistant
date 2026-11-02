// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.HelperCommand
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperChoice.Asked
import app.heyrobot.core.helpers.HelperChoice.Command
import app.heyrobot.core.helpers.HelperChoice.Gate
import app.heyrobot.core.helpers.HelperChoice.Instead
import app.heyrobot.core.helpers.HelperChoice.Picked
import app.heyrobot.core.helpers.HelperChoice.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperChoiceTest {
    private val names = HelperName.all(RobotSettings(helpers = listOf(HelperConfig.claude())))

    // ---- what the check catches, and what it misses

    @Test fun aRouteTakesManyOrAKind_oneQuestionIsAskedOnce() {
        for (t in listOf("Ask Claude about that", "ask Claude about this", "ask Claude about it", "ask Claude with this", "ask Claude for this", "ask Claude for that",
            "use Claude for this", "use Claude on that", "demande à Claude pour ça", "utilise Claude pour ça")) {
            assertNull(t, HelperChoice.command(t, names))
            assertEquals(t, Instead(robot = false, helper = "claude"), HelperChoice.instead(t, names))
        }
        for (t in listOf("use Claude for these", "ask Claude for those", "use Claude for questions like this", "ask Claude for this kind of question",
            "utilise Claude pour ces questions", "always ask Claude", "always ask Claude about that")) assertEquals(t, Command(false, helper = "claude"), HelperChoice.command(t, names))
        assertEquals(Command(false, helper = "claude", kind = QuestionKind.CODE), HelperChoice.command("use Claude for code", names))
        // Still no question: another's name, or a new question.
        for (t in listOf("ask Bob about that", "ask Claude what about that")) assertNull(t, HelperChoice.instead(t, names))
    }

    @Test fun anotherHelpersName_isntAnAnswerToThisCard() {
        val two = HelperName.all(RobotSettings(helpers = listOf(HelperConfig.claude(), HelperConfig("h2", "home", "Jarvis", "m"))))
        assertEquals(Picked.HELPER, HelperChoice.answer("ask Jarvis", two, "h2"))
        assertNull(HelperChoice.answer("ask Claude", two, "h2"))
        assertNull(HelperChoice.answer("Claude", two, "h2"))
        assertEquals(Picked.HELPER, HelperChoice.answer("the helper", two, "h2"))
        assertEquals(Picked.HERE, HelperChoice.answer("Robot", two, "h2"))
        // A yes under an offer takes only the offer's helper's name.
        val jarvis = two.filter { it.id == "h2" }
        assertTrue(HelperRouter.isYes("yes, ask Jarvis", jarvis))
        assertTrue(HelperRouter.isYes("yes, ask the helper", jarvis))
        assertFalse(HelperRouter.isYes("yes, ask Claude", jarvis))
    }

    @Test fun kindsCome_fromThePreRouter_plusManyParts() {
        assertEquals(QuestionKind.THINKING, HelperChoice.kindOf("compare the pros and cons of a heat pump and a furnace"))
        assertEquals(QuestionKind.WRITING, HelperChoice.kindOf("write me a cover letter for a bakery job"))
        assertEquals(QuestionKind.CODE, HelperChoice.kindOf("fix this python function"))
        assertEquals(QuestionKind.BIG, HelperChoice.kindOf(("tell me about the history of the place where I grew up and what it was like " +
            "for people who lived there in the old days when there was nothing around for miles and miles and then some more words about the place").trim()))
        assertEquals(QuestionKind.BIG, HelperChoice.kindOf("how does a heat pump work and why is it cold outside and when should I change the filter"))
        assertEquals(QuestionKind.BIG, HelperChoice.kindOf("Who built it? When did they build it? What did it cost them back then?"))
        assertEquals(QuestionKind.BIG, HelperChoice.kindOf("Comment marche une thermopompe et pourquoi fait-il froid et quand changer le filtre"))
    }

    @Test fun whatTheCheckMisses_isNamedHere() {
        // Hard, but worded like an easy one: the model answers, and the hedge check is what's left.
        assertNull(HelperChoice.kindOf("why did the Roman empire fall"))
        assertNull(HelperChoice.kindOf("what is the capital of France and Spain and Italy"))
        assertNull(HelperChoice.kindOf("what's the meaning of life"))
    }

    @Test fun anythingThatActsHasNoKind_whateverItsWording() {
        for (q in listOf(
            "call mum and compare the pros and cons", "text Sam a long explanation of why I am late", "set a timer for code review",
            "send an email about the stock price", "turn on the lights and explain why", "delete the code note",
            "write a message to Sam", "pay the math tutor", "lock the phone and compare options",
            "envoie un texto à Sam pour expliquer pourquoi", "supprime la note sur le code",
        )) assertNull(q, HelperChoice.kindOf(q))
    }

    @Test fun shortManyPartLookingWords_areNotManyParts() {
        assertFalse(HelperChoice.manyParts("a? b? c?"))
        assertFalse(HelperChoice.manyParts("what is it and why"))
    }

    // ---- the step

    @Test fun step_offerMode_asksWhenItHasAKind() {
        assertEquals(Step.CHOICE, HelperChoice.step(true, QuestionKind.CODE, null, EscalationDecision.OFFER, true))
        assertEquals(Step.CHOICE, HelperChoice.step(false, QuestionKind.BIG, null, EscalationDecision.OFFER, true))
        assertEquals(Step.OFFER_AFTER, HelperChoice.step(true, null, null, EscalationDecision.OFFER, true))
    }

    @Test fun step_nothingSeen_nothingDone() {
        assertEquals(Step.NOTHING, HelperChoice.step(false, null, null, EscalationDecision.OFFER, true))
        assertEquals(Step.NOTHING, HelperChoice.step(false, null, null, EscalationDecision.AUTO, true))
    }

    @Test fun step_noHelper_saysSoAndNeverOffers() {
        assertEquals(Step.SAY_NO_HELPER, HelperChoice.step(true, QuestionKind.CODE, null, EscalationDecision.NONE, false))
        assertEquals(Step.NOTHING, HelperChoice.step(true, null, null, EscalationDecision.NONE, false))
    }

    @Test fun step_refused_staysRefused_rememberedOrNot() {
        for (d in listOf(EscalationDecision.NONE, EscalationDecision.NEEDS_UNLOCK)) {
            assertEquals(Step.NOTHING, HelperChoice.step(true, QuestionKind.CODE, false, d, true))
            assertEquals(Step.NOTHING, HelperChoice.step(true, QuestionKind.CODE, null, d, true))
        }
    }

    @Test fun step_remembered() {
        assertEquals(Step.GO, HelperChoice.step(true, QuestionKind.CODE, false, EscalationDecision.AUTO, true))
        // OFFER with the helper remembered: an app helper, or a question Automatic may not send. Offered after, not asked again.
        assertEquals(Step.OFFER_AFTER, HelperChoice.step(true, QuestionKind.CODE, false, EscalationDecision.OFFER, true))
        assertEquals(Step.ROBOT, HelperChoice.step(true, QuestionKind.CODE, true, EscalationDecision.AUTO, true))
        assertEquals(Step.ROBOT, HelperChoice.step(true, QuestionKind.CODE, true, EscalationDecision.NONE, false))
    }

    @Test fun step_manyPartsUnderAutomatic_asksFirst_soAutoIsNoWider() {
        assertEquals(Step.CHOICE, HelperChoice.step(false, QuestionKind.BIG, null, EscalationDecision.AUTO, true))
        assertEquals(Step.GO, HelperChoice.step(false, QuestionKind.BIG, false, EscalationDecision.AUTO, true))
    }

    // ---- the spoken answer

    @Test fun spokenAnswers() {
        // rc4: the card's French buttons, read aloud, and what they stand for.
        for (t in listOf("Ici", "Essayer ici", "essayer", "Essaie ici", "oui, ici")) assertEquals(t, Picked.HERE, HelperChoice.answer(t, listOf(HelperName.CLAUDE)))
        for (t in listOf("l'assistant", "L’assistant", "demande à l'assistant", "à l'assistant", "Demander à l'assistant", "Demander à Claude", "demande à Claude")) assertEquals(t, Picked.HELPER, HelperChoice.answer(t, listOf(HelperName.CLAUDE)))
        for (t in listOf("here", "Robot", "try it", "try it here", "you", "ici", "essaie", "non", "try it please", "no thanks")) assertEquals(t, Picked.HERE, HelperChoice.answer(t, names))
        for (t in listOf("the helper", "helper", "your helper", "Claude", "ask Claude", "ask the helper", "l'assistant", "demande à Claude", "une IA externe", "yes the helper please")) assertEquals(t, Picked.HELPER, HelperChoice.answer(t, names))
        for (t in listOf("yes", "oui", "what time is it", "", "maybe")) assertNull(t, HelperChoice.answer(t, names))
    }

    // ---- remembering by voice

    @Test fun keepCommands() {
        assertEquals(Command(false), HelperChoice.command("always ask the helper for these", names))
        assertEquals(Command(false), HelperChoice.command("use the helper for these", names))
        assertEquals(Command(false, helper = HelperName.CLAUDE_ID), HelperChoice.command("always ask Claude", names))
        assertEquals(Command(false, helper = HelperName.CLAUDE_ID), HelperChoice.command("ask Claude for questions like this", names))
        assertEquals(Command(false, robot = true), HelperChoice.command("use Robot for these", names))
        assertEquals(Command(false, robot = true), HelperChoice.command("keep these on Robot", names))
        assertEquals(Command(false, kind = QuestionKind.CODE), HelperChoice.command("always ask the helper for code", names))
        assertEquals(Command(false, robot = true, kind = QuestionKind.MATH), HelperChoice.command("use Robot for math", names))
        assertEquals(Command(false), HelperChoice.command("demande toujours à l'assistant pour ce genre de questions", names))
        assertEquals(Command(false, robot = true), HelperChoice.command("utilise Robot pour ces questions", names))
    }

    @Test fun dropCommands() {
        assertEquals(Command(true), HelperChoice.command("stop asking the helper for these", names))
        assertEquals(Command(true), HelperChoice.command("stop using Robot for these", names))
        assertEquals(Command(true), HelperChoice.command("forget that choice", names))
        assertEquals(Command(true, kind = QuestionKind.CODE), HelperChoice.command("ask me again for code", names))
        assertEquals(Command(true), HelperChoice.command("arrête de demander à l'assistant pour ces questions", names))
        assertEquals(Command(true), HelperChoice.command("oublie ce choix", names))
    }

    @Test fun notCommands() {
        for (t in listOf("ask Claude for a recipe", "use the oven for these", "always ask Sam for these", "stop asking Sam for these", "use my helper for the dishes",
            "stop asking me", "what is code", "ask the helper", "ask Claude what time it is")) assertNull(t, HelperChoice.command(t, names))
    }

    // ---- "ask the helper instead"

    @Test fun insteadToAHelper() {
        for (t in listOf("ask the helper instead", "try the helper", "that's wrong, ask the helper", "no, ask the helper instead", "send it to the helper",
            "see what the helper says", "let the helper try", "what does the helper think", "the helper instead", "actually try asking the helper please",
            "demande plutôt à l'assistant", "demande à l'assistant à la place", "essaie l'assistant", "envoie ça à l'assistant", "passe ça à l'assistant",
            "vois avec l'assistant", "l'assistant plutôt", "c'est faux, demande à l'assistant"))
            assertEquals(t, Instead(false), HelperChoice.instead(t, names))
        for (t in listOf("ask Claude instead", "that's wrong, ask Claude", "try Claude", "Claude instead", "demande plutôt à Claude"))
            assertEquals(t, Instead(false, HelperName.CLAUDE_ID), HelperChoice.instead(t, names))
    }

    @Test fun insteadToRobot() {
        for (t in listOf("ask Robot instead", "try Robot", "let Robot try", "Robot instead", "try it here", "demande à Robot plutôt", "essaie Robot", "Robot plutôt", "essaie ici"))
            assertEquals(t, Instead(true), HelperChoice.instead(t, names))
    }

    @Test fun myHelper_mayBeAPerson_soItAsksFirst() {
        assertEquals(Instead(false, sure = false), HelperChoice.instead("ask my helper instead", names))
        assertNull(HelperChoice.instead("ask my helper to pick up the kids", names))
    }

    @Test fun notInstead() {
        for (t in listOf("don't ask the helper instead", "don't ask Claude", "ask Claude what time it is", "ask the helper", "ask Claude",
            "try the soup instead", "ask Sam instead", "ask Claude why the sky is blue instead of green", "n'envoie pas ça à l'assistant", "what is a helper"))
            assertNull(t, HelperChoice.instead(t, names))
    }

    @Test fun routerAlone_isUnchangedForTheOldForms() {
        // "Ask the helper" alone stays a join, a refusal stays a refusal.
        assertTrue(HelperRouter.route("ask the helper", names) is HelperCommand.Join)
        assertTrue(HelperRouter.declines("don't ask the helper", names))
    }

    // ---- the gate, checked at the re-send

    private fun asked(helper: String? = null, attached: Boolean = false, heardBack: Boolean = false, locked: Boolean = false) =
        Asked("why is the sky blue", "r1", helper, attached, heardBack, locked)
    private val toHelper = Instead(false)

    @Test fun gate_goes_whenClean() {
        assertEquals(Gate.GO, HelperChoice.gate(toHelper, asked(), false, false, false, "claude"))
        assertEquals(Gate.GO, HelperChoice.gate(Instead(true), asked(helper = "claude"), false, true, true, null))
    }

    @Test fun gate_tainted_orUnsure_asksFirst() {
        assertEquals(Gate.ASK_FIRST, HelperChoice.gate(toHelper, asked(), false, false, true, "claude"))
        assertEquals(Gate.ASK_FIRST, HelperChoice.gate(Instead(false, sure = false), asked(), false, false, false, "claude"))
    }

    @Test fun gate_lockedSinceItWasAsked_refuses() {
        assertEquals(Gate.UNLOCK, HelperChoice.gate(toHelper, asked(), false, true, false, "claude"))
        assertEquals(Gate.GO, HelperChoice.gate(toHelper, asked(locked = true), false, true, false, "claude"))
    }

    @Test fun gate_refusals() {
        assertEquals(Gate.NOTHING, HelperChoice.gate(toHelper, null, false, false, false, "claude"))
        assertEquals(Gate.ATTACHED, HelperChoice.gate(toHelper, asked(attached = true), false, false, false, "claude"))
        assertEquals(Gate.ATTACHED, HelperChoice.gate(toHelper, asked(heardBack = true), false, false, false, "claude"))
        assertEquals(Gate.ACTED, HelperChoice.gate(toHelper, asked(), true, false, false, "claude"))
        assertEquals(Gate.SAME, HelperChoice.gate(toHelper, asked(helper = "claude"), false, false, false, "claude"))
        assertEquals(Gate.SAME, HelperChoice.gate(Instead(true), asked(), false, false, false, null))
    }
}
