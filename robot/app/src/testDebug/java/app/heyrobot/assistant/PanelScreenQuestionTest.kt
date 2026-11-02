// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Attachment
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.i18n.Langs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "What's on my screen?" in your words: the screen goes as the draft, or the panel says why it can't, on every tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelScreenQuestionTest {
    private fun share(st: PanelState) = MutableStateFlow(st).let { f ->
        f to PanelScreenShare(CoroutineScope(Dispatchers.Unconfined), f, { true }, { 0L }, { Langs.EN }) { true to true }
    }

    @Test fun aReadyScreenBecomesTheDraft() {
        val (f, s) = share(PanelState("c1", screenChip = ScreenChip.READY))
        assertTrue(s.attachAsked())
        assertEquals(Attachment.SCREEN, f.value.draft?.kind)
        assertNull(f.value.notice)
    }

    @Test fun aPanelOpenedInsideRobotSaysHowToShareIt() {
        val (f, s) = share(PanelState("c1", screenChip = ScreenChip.HIDDEN))
        assertFalse(s.attachAsked())
        assertNull(f.value.draft)
        assertEquals(ScreenAccess.swipeHint(null).phrase.render(Langs.EN), f.value.notice?.text)
    }

    @Test fun aLockedPhoneSaysToUnlock() {
        val (f, s) = share(PanelState("c1", screenChip = ScreenChip.HIDDEN, locked = true))
        assertFalse(s.attachAsked())
        assertEquals(ScreenAccess.hiddenHint(true, null).phrase.render(Langs.EN), f.value.notice?.text)
    }

    @Test fun aScreenNotSharedSaysWhy() {
        val (f, s) = share(PanelState("c1", screenChip = ScreenChip.SWIPE_ONLY))
        assertFalse(s.attachAsked())
        assertNull(f.value.draft)
        assertTrue(f.value.notice != null)
    }
}
