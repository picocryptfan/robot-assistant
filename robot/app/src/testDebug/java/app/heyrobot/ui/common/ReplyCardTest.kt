// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7, the reply card (design §B5): who and which app, your words in quotes, the tick box that sends them in her language
 * with what Robot made of them, the notes, and Send, Edit, Open chat, Cancel; the 3 seconds' Stop; a pick when several
 * conversations fit; what's left when it went or failed; History's has no buttons. In the panel's tonal colours and type,
 * and at twice the size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ReplyCardTest {
    @get:Rule val rule = createComposeRule()
    private val ops = ArrayList<ReplyOp>()

    private val waiting = ReplyCardView(
        headline = "Reply to **Mom** · Cheogram", state = ReplyCardState.WAITING, line = "Tap Send to send it.",
        text = "I'll be late, start without me.", translation = "Je vais être en retard, commencez sans moi.", translateLabel = "Send in French",
        alwaysLabel = "Always for Mom",
    )
    private val group = ReplyCardView("Reply to the group **Famille** · Signal", ReplyCardState.WAITING, "Tap Send to send it.", text = "J'apporte le vin",
        notes = listOf("Everyone in the group will see it. Tap Send to send it.", "Signal marks the chat read and may send a read receipt."))
    private val window = ReplyCardView("Reply to **Pierre** · Signal", ReplyCardState.STOP_WINDOW, "Sending in 3 seconds. Say stop or tap Stop.", text = "On my way")
    private val gone = ReplyCardView("Reply to **Mom** · Cheogram", ReplyCardState.GONE, "Mom's notification is gone. Open the chat with your reply?", text = "OK")
    private val sent = ReplyCardView("Reply to **Mom** · Cheogram", ReplyCardState.SENT, "Sent in Cheogram.", text = "OK")
    private val pick = ReplyCardView("Reply to whom?", ReplyCardState.PICK, "Several conversations fit. Tap the one you mean.",
        choices = listOf("Marc · Cheogram", "Marc · Signal"))

    private fun card(r: ReplyCardView, history: Boolean = false) =
        rule.show(classic = true) { Column(Modifier.padding(16.dp)) { ReplyCard(r, if (history) null else { op: ReplyOp -> ops += op }) } }

    @Test fun aWaitingCardSaysWhatGoesWhereAndItsButtonsAnswer() {
        card(waiting)
        rule.onNodeWithText("Reply to Mom · Cheogram").assertExists()
        rule.onNodeWithText("«I'll be late, start without me.»").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        val tick = rule.onNodeWithText("Send in French: «Je vais être en retard, commencez sans moi.»")
        tick.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOff().assertHeightIsAtLeast(48.dp)
        tick.performClick()
        rule.onNodeWithText("Open chat").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf(ReplyOp.Translate(true), ReplyOp.OpenChat, ReplyOp.Cancel, ReplyOp.Send), ops)
        // "Always for Mom" only once "Send in French" is ticked.
        rule.onNodeWithText("Always for Mom").assertDoesNotExist()
    }

    @Test fun editChangesTheWordsBeforeSend() {
        card(waiting.copy(translate = true, always = true))
        rule.onNodeWithText("Always for Mom").assertIsOn().performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Your reply").performTextReplacement("I'll be there at six")
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf(ReplyOp.Always(false), ReplyOp.Edit("I'll be there at six")), ops)
    }

    @Test fun theOtherStates() {
        rule.show(classic = true) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in listOf(group, window, gone, sent, pick)) ReplyCard(r, { op -> ops += op })
            }
        }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Everyone in the group will see it. Tap Send to send it.").assertExists()
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithContentDescription("Sent").assertExists()
        rule.onNodeWithText("Marc · Signal").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(listOf(ReplyOp.Stop, ReplyOp.Pick(1)), ops)
    }

    @Test fun historysCardHasNoButtons() {
        card(waiting, history = true)
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("Edit").assertDoesNotExist()
        // The tick box shows what was chosen, and does nothing.
        rule.onNodeWithText("Send in French: «Je vais être en retard, commencez sans moi.»").performClick()
        assertTrue(ops.isEmpty())
    }

    /** In a conversation: the card under the reply, its taps handed back with the reply's id; History's has none. */
    @Test fun underAReply() {
        val got = ArrayList<String>()
        val turn = TurnUi("r1", user = false, text = "Reply to Mom · Cheogram.", reply = waiting)
        rule.show { TurnView(turn, TurnActions(reply = { id, op -> got += "$id $op" })) }
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf("r1 Send"), got)
    }

    @Test fun historysTurnShowsTheCardWithoutButtons() {
        rule.show { TurnView(TurnUi("r1", user = false, text = "Reply to Mom · Cheogram.", reply = sent), TurnActions.None) }
        rule.onNodeWithText("Sent in Cheogram.").assertExists()
        rule.onNodeWithText("Send").assertDoesNotExist()
    }

    @Test fun light() {
        rule.show(classic = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReplyCard(waiting.copy(notes = listOf("Robot hasn't checked yet how this app sends a reply.")), { ops += it })
                ReplyCard(window, { ops += it })
                ReplyCard(sent, { ops += it })
            }
        }
        rule.shot("reply_card_light")
    }

    @Test fun classic() {
        rule.show(classic = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReplyCard(waiting, { ops += it })
                ReplyCard(gone, { ops += it })
            }
        }
        rule.shot("reply_card_classic")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        rule.show(classic = true) { Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) { ReplyCard(waiting.copy(translate = true), { ops += it }) } }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("reply_card_font200")
    }
}
