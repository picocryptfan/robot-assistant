// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.Attachment
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.ScreenData
import app.heyrobot.core.ShowSource
import app.heyrobot.core.Tip
import app.heyrobot.core.Tips
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.i18n.Langs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * "Ask about this screen" (PanelScreenShare, from PanelController in 1.1): what Android sent of the screen is taken only
 * while the panel is open and wasn't opened by Robot itself; a "nothing" that came before the show still counts, and only
 * hiding forgets it; the chip waits 2 s for the corner swipe's screen, and a tap without it says why; the screen coming
 * after all takes down that tap's notice and no other; and the voice log gets one line per panel, codes only. The timer
 * runs in the panel's scope on the main looper, so the looper's clock drives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelScreenShareTest {
    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c"))
    private var shown = false
    private var shownAt = 0L
    /** "Use text from screen" and "Use screenshot". */
    private var sharing: Pair<Boolean?, Boolean?> = true to true
    private val screen = PanelScreenShare(scope, state, { shown }, { shownAt }, { Langs.EN }) { sharing }
    private val bmp: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    @Before fun log() = VoiceLog.resetForTests()
    @After fun end() = scope.cancel()

    /** The panel opens as PanelController's onShow does: shown, its time, then the show and, later, the start. */
    private fun open(from: ShowSource, ignore: Boolean = false, returning: Boolean = false, flags: Int = 7) {
        shown = true
        screen.show(ignore, returning)
        shownAt = SystemClock.elapsedRealtime()
        screen.start(from, flags)
    }

    /** onHide's order: hidden first, then the screen's content goes, then the chip is hidden. */
    private fun close() {
        shown = false
        screen.hide()
        state.update { it.copy(screenChip = ScreenChip.HIDDEN) }
    }

    private fun logged(): List<VoiceLogEntry> = VoiceLog.entries().filter { it.event == VoiceEvent.SCREEN_SHARED }

    private fun chip() = state.value.screenChip

    @Test fun theCornerSwipesScreenMakesTheChipReadyAndATapAttachesIt() {
        open(ShowSource.SWIPE)
        assertEquals(ScreenChip.WAITING, chip())
        assertTrue(screen.onText(listOf("Invoice 42"), "com.example.bank", 0, 1))
        screen.refresh()
        assertEquals("Invoice 42", screen.text)
        assertEquals("com.example.bank", screen.pkg)
        assertEquals(ScreenChip.READY, chip())
        screen.onShot(bmp)
        assertSame(bmp, screen.shot)
        screen.attach()
        val draft = state.value.draft!!
        assertEquals(Attachment.SCREEN, draft.kind)
        assertNotNull(draft.thumb)
        assertEquals(ShowSource.SWIPE, screen.from)
    }

    @Test fun splitScreenGivesAllTheWindowsTextAndNoSingleApp() {
        open(ShowSource.SWIPE)
        screen.onText(listOf("Inbox"), "com.example.mail", 0, 2)
        screen.onText(listOf("Calendar"), "com.example.cal", 1, 2)
        assertEquals("Inbox\nCalendar", screen.text)
        assertNull(screen.pkg)
        // A new corner swipe starts over from its first window.
        screen.onText(listOf("Notes"), "com.example.notes", 0, 1)
        assertEquals("Notes", screen.text)
        assertEquals("com.example.notes", screen.pkg)
    }

    @Test fun nothingIsTakenWhileThePanelIsClosedOrRobotOpenedIt() {
        assertFalse(screen.onText(listOf("Secret"), "com.example", 0, 1))
        screen.onShot(bmp)
        assertNull(screen.text)
        assertNull(screen.shot)
        // Robot's own app opened the panel: Android's screen is not taken, the chip stays hidden and nothing is logged.
        open(ShowSource.APP, ignore = true)
        assertFalse(screen.onText(listOf("Secret"), "com.example", 0, 1))
        screen.onShot(bmp)
        assertNull(screen.text)
        assertNull(screen.shot)
        assertEquals(ScreenChip.HIDDEN, chip())
        looper.idleFor(Duration.ofSeconds(3))
        close()
        assertEquals(emptyList<VoiceLogEntry>(), logged())
    }

    @Test fun aNothingBeforeTheShowStillCountsAndOnlyHidingForgetsIt() {
        // A reused session: Android says "nothing" for both before the show, so the chip says why at once.
        screen.onNoText()
        screen.onShot(null)
        open(ShowSource.SWIPE)
        assertEquals(ScreenChip.NOT_SHARED, chip())
        close()
        assertEquals(listOf(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 7, ScreenData.EMPTY.id, ScreenData.EMPTY.id)), logged().map { it.copy(time = 0) })
        // Hiding forgot it: the next corner swipe waits for its screen.
        open(ShowSource.SWIPE)
        assertEquals(ScreenChip.WAITING, chip())
    }

    @Test fun showingDropsTheLastScreenUnlessBackFromTheCamera() {
        open(ShowSource.SWIPE)
        screen.onText(listOf("Invoice 42"), "com.example.bank", 0, 1)
        screen.onShot(bmp)
        // Back from the camera: the screen it had stays.
        screen.show(ignore = true, returning = true)
        assertEquals("Invoice 42", screen.text)
        assertSame(bmp, screen.shot)
        assertEquals("com.example.bank", screen.pkg)
        // Any other show: it goes.
        screen.show(ignore = false, returning = false)
        assertNull(screen.text)
        assertNull(screen.shot)
        assertNull(screen.pkg)
    }

    @Test fun hidingDropsTheScreen() {
        open(ShowSource.SWIPE)
        screen.onText(listOf("Invoice 42"), "com.example.bank", 0, 1)
        screen.onShot(bmp)
        close()
        assertNull(screen.text)
        assertNull(screen.shot)
        assertNull(screen.pkg)
    }

    @Test fun theChipWaitsTwoSecondsThenSaysItWasntSharedAndLogsOnce() {
        open(ShowSource.SWIPE, flags = 3)
        looper.idleFor(Duration.ofMillis(ScreenAccess.WAIT_MS - 1))
        assertEquals(ScreenChip.WAITING, chip())
        assertEquals(emptyList<VoiceLogEntry>(), logged())
        looper.idleFor(Duration.ofMillis(1))
        assertEquals(ScreenChip.NOT_SHARED, chip())
        assertEquals(listOf(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 3, ScreenData.NOT_SENT.id, ScreenData.NOT_SENT.id)), logged().map { it.copy(time = 0) })
        // A screen that comes later changes the chip but isn't logged again, nor when the panel closes.
        screen.onShot(bmp)
        assertEquals(ScreenChip.READY, chip())
        close()
        assertEquals(1, logged().size)
    }

    @Test fun closingBeforeTwoSecondsLogsThenAndStopsTheTimer() {
        open(ShowSource.SWIPE)
        screen.onText(listOf("Invoice 42"), null, 0, 1)
        close()
        assertEquals(listOf(VoiceLogEntry(0, VoiceEvent.SCREEN_SHARED, 7, ScreenData.SHARED.id, ScreenData.NOT_SENT.id)), logged().map { it.copy(time = 0) })
        looper.idleFor(Duration.ofSeconds(3))
        assertEquals(ScreenChip.HIDDEN, chip())
        assertEquals(1, logged().size)
    }

    @Test fun aTapWithoutTheScreenSaysWhyAndTheScreenComingTakesDownOnlyThatNotice() {
        open(ShowSource.SWIPE)
        screen.attach()
        val waiting = state.value.notice!!
        assertEquals(NoticeAction.NONE, waiting.action)
        assertNull(state.value.draft)
        screen.onShot(bmp)
        assertEquals(ScreenChip.READY, chip())
        assertNull(state.value.notice)
        // A notice put up after the tap's (the model fell back, say) is not the tap's: it stays.
        close()
        open(ShowSource.SWIPE)
        screen.attach()
        val other = Notice("Robot is using Fast for now.", NoticeAction.OPEN_APP)
        state.update { it.copy(notice = other) }
        screen.onShot(bmp)
        assertSame(other, state.value.notice)
    }

    @Test fun aTapNamesTheSettingThatIsOffWithOpenSettings() {
        sharing = false to true
        open(ShowSource.SWIPE)
        assertEquals(ScreenChip.NOT_SHARED, chip())
        screen.attach()
        assertEquals(NoticeAction.OPEN_ASSIST_SETTINGS, state.value.notice!!.action)
        // The wake word: a tap says to swipe up from a corner, and to turn the setting on.
        close()
        open(ShowSource.WAKE)
        assertEquals(ScreenChip.SWIPE_ONLY, chip())
        assertEquals(ShowSource.WAKE, screen.from)
        screen.attach()
        assertEquals(ScreenAccess.swipeHint(false).phrase.render(Langs.EN), state.value.notice!!.text)
    }

    @Test fun theLockScreenHidesTheChipAndReadyAddsTheScreenTip() {
        state.update { it.copy(locked = true) }
        open(ShowSource.SWIPE)
        assertEquals(ScreenChip.HIDDEN, chip())
        state.update { it.copy(locked = false, tips = listOf(Tip("a"), Tip("b"), Tip("c"))) }
        screen.onShot(bmp)
        assertEquals(ScreenChip.READY, chip())
        assertEquals(listOf("a", "b", Tips.SCREEN.text), state.value.tips.map { it.text })
    }
}
