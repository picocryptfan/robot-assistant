// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import android.os.Looper
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** fix-seal-lows-d 2: Today's quick add across a rotation mid-save: one add per tap, words cleared only once saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayQuickAddTest {
    @get:Rule val rule = createComposeRule()
    private val sent = mutableListOf<String>()
    private var done: ((Boolean) -> Unit)? = null

    @Before fun fresh() { TodayQuickAdd.sent.value = null }

    private fun show(): StateRestorationTester {
        val tester = StateRestorationTester(rule)
        tester.setContent {
            var text by rememberSaveable { mutableStateOf("") }
            QuickAddRow(text, { text = it }, newNote = false) { w, d -> sent += w; done = d }
        }
        rule.onNode(hasSetTextAction()).performTextInput("milk")
        rule.onNodeWithText("Add").performClick()
        tester.emulateSavedInstanceStateRestore()
        return tester
    }

    /**
     * The save ends on an IO thread in real time and posts done to the main looper, which Robolectric holds paused, and
     * the compose rule's waitUntil runs on virtual time: so wait in real time and idle the main looper by hand.
     */
    private fun landed(count: Int) {
        val end = System.nanoTime() + 5_000_000_000L
        while (TodayQuickAdd.landed.intValue < count && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(count, TodayQuickAdd.landed.intValue)
    }

    private fun shown() = rule.onAllNodesWithText("milk").fetchSemanticsNodes().size

    @Test fun aRotationDuringTheSaveAddsOnceAndClearsTheWords() {
        show()
        rule.onNodeWithText("Add").performClick()
        assertEquals(listOf("milk"), sent)
        rule.runOnIdle { done!!(true) }
        rule.waitForIdle()
        assertEquals(0, shown())
        rule.onNodeWithText("Add").performClick()
        assertEquals(listOf("milk"), sent)
    }

    @Test fun aRotationDuringAHeldSaveKeepsTheWords() {
        show()
        rule.runOnIdle { done!!(false) }
        rule.waitForIdle()
        assertEquals(1, shown())
        rule.onNodeWithText("Add").performClick()
        assertEquals(listOf("milk", "milk"), sent)
    }

    /** fix-seal-lows-e 1: the real add, its save still running when the page is rebuilt: done still runs, so Add works again. */
    @Test fun theSaveOutlivesThePageThatStartedIt() {
        val gate = CountDownLatch(1)
        val before = TodayQuickAdd.landed.intValue
        val saved = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tester = StateRestorationTester(rule)
        tester.setContent {
            var text by rememberSaveable { mutableStateOf("") }
            QuickAddRow(text, { text = it }, newNote = false, TodayQuickAdd.adder { w -> gate.await(); saved += w; true })
        }
        rule.onNode(hasSetTextAction()).performTextInput("milk")
        rule.onNodeWithText("Add").performClick()
        tester.emulateSavedInstanceStateRestore()
        gate.countDown()
        landed(before + 1)
        rule.waitForIdle()
        assertEquals(listOf("milk"), saved.toList())
        assertEquals(0, shown())
        rule.onNode(hasSetTextAction()).performTextInput("eggs")
        rule.onNodeWithText("Add").performClick()
        landed(before + 2)
        assertEquals(listOf("milk", "eggs"), saved.toList())
    }
}
