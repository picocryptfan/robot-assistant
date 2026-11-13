// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Escalation
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperChoice
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.QuestionKind
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.memory.MemoryChoice
import app.heyrobot.core.memory.MemoryReplies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Always ask the helper for these" and "ask the helper instead", the panel's side; the words are core HelperChoiceTest's. */
class PanelHelperChoiceTest {
    private val server = HelperConfig("h2", "home", "My server", "m")
    private var settings = RobotSettings(helpers = listOf(server))
    private var locked = false
    private var target: HelperConfig? = server
    private var saved = MemoryPanel.ChoiceSaved.SAVED
    private val kept = mutableListOf<Pair<MemoryChoice, String>>()
    private val dropped = mutableListOf<String>()

    private val choice = PanelHelperChoice(object : PanelHelperChoice.Port {
        override val settings get() = this@PanelHelperChoiceTest.settings
        override val turnLangs = Langs.EN
        override fun locked() = locked
        override fun askTarget() = target
        override suspend fun keepInMemory(c: MemoryChoice, text: String, spoken: Boolean): MemoryPanel.ChoiceSaved {
            kept += c to text
            return saved
        }
        override suspend fun dropFromMemory(kind: String): MemoryPanel.ChoiceSaved {
            dropped += kind
            return saved
        }
    })

    private val q = "explain why the sky is blue step by step"
    private fun asked(helper: String? = null, attached: Boolean = false, askedLocked: Boolean = false) =
        choice.asked(HelperChoice.Asked(q, "r1", helper, attached, false, askedLocked))
    private val toHelper = HelperChoice.Instead(false)
    private fun said(r: PanelHelperChoice.Resend) = (r as PanelHelperChoice.Resend.Say).text.render(Langs.EN)
    private fun en(p: Phrase) = p.render(Langs.EN)

    @Test fun theSameWordsGo_notRobotsReply() {
        asked()
        val r = choice.resend(toHelper, acted = false, tainted = false) as PanelHelperChoice.Resend.To
        assertEquals(q, r.question)
        assertSame(server, r.h)
    }

    @Test fun lockedSinceItWasAsked_isCheckedAtTheResend() {
        asked()
        locked = true
        assertEquals(en(HelperStrings.insteadUnlock()), said(choice.resend(toHelper, false, false)))
        locked = false
        assertEquals(q, (choice.resend(toHelper, false, false) as PanelHelperChoice.Resend.To).question)
    }

    @Test fun aConversationHoldingContent_orAnythingPersonal_takesATapOrAYes() {
        asked()
        val r = choice.resend(toHelper, acted = false, tainted = true) as PanelHelperChoice.Resend.Ask
        assertEquals(q, r.question)
    }

    @Test fun myHelper_takesATapOrAYes() {
        asked()
        assertTrue(choice.resend(HelperChoice.Instead(false, sure = false), false, false) is PanelHelperChoice.Resend.Ask)
    }

    @Test fun aHelperOff_goesToRunWhichRefusesIt_noOfferIsMade() {
        asked()
        target = null
        val r = choice.resend(toHelper, false, true) as PanelHelperChoice.Resend.To
        assertNull(r.h)
    }

    @Test fun whatCantBeSentAgain_saysWhy() {
        assertEquals(en(HelperStrings.insteadNothing()), said(choice.resend(toHelper, false, false)))
        asked(attached = true)
        assertEquals(en(HelperStrings.insteadAttached()), said(choice.resend(toHelper, false, false)))
        asked()
        assertEquals(en(HelperStrings.insteadActed()), said(choice.resend(toHelper, acted = true, tainted = false)))
        asked(helper = server.id)
        assertEquals(en(HelperStrings.insteadSameHelper("name" to server.name)), said(choice.resend(toHelper, false, false)))
        asked()
        assertEquals(en(HelperStrings.insteadSameRobot()), said(choice.resend(HelperChoice.Instead(true), false, false)))
    }

    @Test fun askRobotInstead_afterAHelpersReply_evenTainted_orLocked() {
        asked(helper = server.id)
        locked = true
        assertEquals(q, (choice.resend(HelperChoice.Instead(true), false, tainted = true) as PanelHelperChoice.Resend.Robot).question)
    }

    @Test fun commands_takeTheLastQuestionsKind_andSay() = runBlocking {
        assertEquals(en(HelperStrings.choiceNoKind()), en(choice.run(HelperChoice.Command(false), spoken = true)))
        asked()
        val line = en(choice.run(HelperChoice.Command(false), spoken = true))
        assertTrue(line, "your helper" in line && "from now on" in line)
        assertEquals(MemoryChoice("THINKING", false, null), kept.single().first)
        assertEquals("Ask your helper for questions that take reasoning.", kept.single().second)
        en(choice.run(HelperChoice.Command(true, kind = QuestionKind.CODE), spoken = true)).let { assertEquals(en(HelperStrings.choiceDropped("kind" to QuestionKind.CODE.text)), it) }
        assertEquals(listOf("CODE"), dropped)
    }

    // L16, finding 3: choiceTurn's decision, end to end but for the panel's own bubbles.
    private val always = PanelHelperChoice.Route.Command(HelperChoice.Command(false, kind = QuestionKind.CODE))

    @Test fun choiceTurn_yourOwnWords_saveTheRoute() = runBlocking {
        val r = choice.turn(always, acted = false, untrusted = false, personal = false, spoken = true) as PanelHelperChoice.Resend.Say
        assertEquals(MemoryChoice("CODE", false, null), kept.single().first)
        assertTrue(en(r.text), "from now on" in en(r.text))
    }

    @Test fun choiceTurn_aConversationHoldingContent_savesNothing_aTapDoes() = runBlocking {
        val r = choice.turn(always, acted = false, untrusted = true, personal = false, spoken = true) as PanelHelperChoice.Resend.Keep
        assertTrue(kept.isEmpty())
        assertTrue(r.k.tapOnly)
        assertEquals(en(HelperStrings.choiceKeepTap()), en(r.text))
        assertEquals("Always ask your helper for code questions?", en(r.k.offer))
        // The tap saves it as the words would have.
        choice.save(r.k, spoken = false)
        assertEquals(MemoryChoice("CODE", false, null), kept.single().first)
    }

    @Test fun choiceTurn_aRouteToRobot_too_andNoKindStillSaysSo() = runBlocking {
        assertTrue(choice.turn(PanelHelperChoice.Route.Command(HelperChoice.Command(false, robot = true, kind = QuestionKind.CODE)), false, true, false, true) is PanelHelperChoice.Resend.Keep)
        assertTrue(kept.isEmpty())
        val r = choice.turn(PanelHelperChoice.Route.Command(HelperChoice.Command(false)), false, true, false, true) as PanelHelperChoice.Resend.Say
        assertEquals(en(HelperStrings.choiceNoKind()), en(r.text))
    }

    @Test fun choiceTurn_aConversationHoldingContent_dropsNothing() = runBlocking {
        val drop = PanelHelperChoice.Route.Command(HelperChoice.Command(true, kind = QuestionKind.CODE))
        assertEquals(en(HelperStrings.choiceDropHeld()), en((choice.turn(drop, false, true, false, true) as PanelHelperChoice.Resend.Say).text))
        assertTrue(dropped.isEmpty())
        choice.turn(drop, false, false, false, true)
        assertEquals(listOf("CODE"), dropped)
    }

    @Test fun choiceTurn_insteadChecksTheConversationAndTheCard() = runBlocking {
        val instead = PanelHelperChoice.Route.Instead(toHelper)
        asked()
        assertEquals(q, (choice.turn(instead, false, untrusted = false, personal = false, spoken = false) as PanelHelperChoice.Resend.To).question)
        assertEquals(q, (choice.turn(instead, false, untrusted = true, personal = false, spoken = false) as PanelHelperChoice.Resend.Ask).question)
        assertEquals(q, (choice.turn(instead, false, untrusted = false, personal = true, spoken = false) as PanelHelperChoice.Resend.Ask).question)
        assertEquals(en(HelperStrings.insteadActed()), en((choice.turn(instead, acted = true, untrusted = false, personal = false, spoken = false) as PanelHelperChoice.Resend.Say).text))
        asked(helper = server.id)
        assertEquals(q, (choice.turn(PanelHelperChoice.Route.Instead(HelperChoice.Instead(true)), false, true, true, false) as PanelHelperChoice.Resend.Robot).question)
    }

    @Test fun commands_saySoWhenMemoryRefuses_orHelpIsOff() = runBlocking {
        asked()
        saved = MemoryPanel.ChoiceSaved.OFF
        assertEquals(en(PanelStrings.memoryOff()), en(choice.run(HelperChoice.Command(false), true)))
        saved = MemoryPanel.ChoiceSaved.UNLOCK
        assertEquals(en(MemoryReplies.unlockText(saving = true)), en(choice.run(HelperChoice.Command(false), true)))
        saved = MemoryPanel.ChoiceSaved.FULL
        assertEquals(en(HelperStrings.choiceFull()), en(choice.run(HelperChoice.Command(false), true)))
        saved = MemoryPanel.ChoiceSaved.SAVED
        settings = settings.copy(help = Escalation.OFF)
        assertTrue(en(choice.run(HelperChoice.Command(false), true)).contains("off in Settings"))
        val none = en(choice.run(HelperChoice.Command(false, robot = true), true))
        assertTrue(none, "off in Settings" !in none)
    }

    /** Review L1: "that's wrong, ask Robot instead" gets its Report it line also when the re-send goes ahead. */
    @Test fun reportItAlsoWhenTheResendGoesAhead() {
        choice.asked(HelperChoice.Asked("what's the capital of Peru", "r1", "h2", false, false, false))
        val wrong = PanelHelperChoice.Route.Instead(HelperChoice.Instead(true), wrong = true)
        // Review-report-shot L1: the Notice choiceTurn sets, before and whatever the re-send comes to (it takes no Resend).
        assertEquals(Notice(PanelStrings.reportWrong().render(Langs.EN), NoticeAction.REPORT, "r1"), choice.reportNotice(wrong, Langs.EN))
        assertNull(choice.reportNotice(PanelHelperChoice.Route.Instead(HelperChoice.Instead(true)), Langs.EN))
        choice.forget()
        assertNull(choice.reportNotice(wrong, Langs.EN))
    }
}
