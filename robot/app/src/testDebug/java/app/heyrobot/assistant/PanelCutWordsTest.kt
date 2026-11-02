// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.PanelStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Speech cut at the 30 s cap is never acted on. Where a text box stays (core CarTurnEnd.boxStays) the words wait in it; elsewhere nothing's kept or promised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelCutWordsTest {
    @After fun clean() = PanelController.fillTaken()

    @Test fun whereTheBoxStaysTheWordsWaitInIt() {
        PanelController.holdCutWords("the words I said", box = true)
        val st = PanelController.state.value
        assertEquals("the words I said", st.fillInput)
        assertTrue(st.notice!!.text, st.notice!!.text.contains("text box"))
    }

    @Test fun whereNoBoxStaysNoneIsPromisedAndNothingIsKept() {
        PanelController.holdCutWords("the words I said", box = false)
        val st = PanelController.state.value
        assertNull(st.fillInput)
        assertFalse(st.notice!!.text, st.notice!!.text.contains("box"))
        assertTrue(st.notice!!.text, st.notice!!.text.contains("Say it again"))
    }

    @Test fun neitherLanguageSaysThereIsABoxWhereThereIsNone() {
        assertFalse(PanelStrings.cutAtCapNoBox().en().contains("box"))
        assertFalse(PanelStrings.cutAtCapNoBox().render(app.heyrobot.core.i18n.Langs(Lang.FR, Lang.FR, Lang.FR)).contains("zone"))
    }

}
