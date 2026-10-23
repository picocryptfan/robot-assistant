// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.graphics.Bitmap
import android.os.SystemClock
import app.heyrobot.core.Attachment
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.ScreenData
import app.heyrobot.core.ScreenText
import app.heyrobot.core.ShowSource
import app.heyrobot.core.Tips
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Langs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// VOICE PROCESS. "Ask about this screen" (core ScreenAccess): what Android sent of the screen to this panel (its text and
// screenshot, the app it came from) and the chip that offers it, over PanelController's state. It holds them only while
// the panel is open. What goes to the model or a helper, and when, stays in PanelController (send), PanelHelperTurn and
// PanelWebSearch (context), which read the text, the screenshot and the app here. Its callers and its 2-second timer run on the main
// thread, in the panel's scope, as before.

/**
 * The screen shared with one panel, over PanelController's [state]; [scope]: the panel's own. [shown]: the panel is open;
 * [shownAt]: when it opened (elapsedRealtime); [langs]: Robot's screens' languages, for a tap's notice; [sharing]:
 * "Use text from screen" and "Use screenshot" (null: Android wouldn't say).
 */
internal class PanelScreenShare(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PanelState>,
    private val shown: () -> Boolean,
    private val shownAt: () -> Long,
    private val langs: () -> Langs,
    private val sharing: () -> Pair<Boolean?, Boolean?>,
) {
    /** The screen's text, from every window the corner swipe described. */
    var text: String? = null
        private set
    /** The text of every window the corner swipe described, in order (split screen gives several). */
    private val pieces = ArrayList<String>()
    var shot: Bitmap? = null
        private set
    /** The app on screen when the corner swipe came from a single app (its screenshot's label), by package. */
    var pkg: String? = null
        private set
    /** Robot's own app opened this panel, or it is back from the camera: Android's screen is not taken. */
    private var ignore = false
    /**
     * How this panel was opened, its show flags, and what Android sent of the screen so far (null: nothing yet, false:
     * it called with nothing). Cleared when the panel hides, not when it shows: on a reused session Android sends its
     * "nothing" before the show. Logged once per panel (SCREEN_SHARED).
     */
    var from = ShowSource.APP
        private set
    private var showFlags = 0
    private var textCame: Boolean? = null
    private var shotCame: Boolean? = null
    /** "Use text from screen" and "Use screenshot", read at each show (null: Android wouldn't say). */
    private var settings: Pair<Boolean?, Boolean?> = null to null
    private var wait: Job? = null
    private var logged = true
    /** The notice a tap on the chip put up: taken down if the screen comes after all. */
    private var notice: Notice? = null

    /**
     * The panel shows. [ignore]: Robot's own app opened it, or it is back from the camera; [returning]: back from the
     * camera, where the screen it had stays. What Android said before the show still counts.
     */
    fun show(ignore: Boolean, returning: Boolean) {
        this.ignore = ignore
        if (!returning) {
            text = null
            shot = null
            pieces.clear()
            pkg = null
        }
    }

    /** The panel hides: everything of the screen goes, after the log line if it wasn't written yet. */
    fun hide() {
        wait?.cancel()
        log()
        textCame = null
        shotCame = null
        text = null
        shot = null
        pieces.clear()
        pkg = null
    }

    /**
     * AssistStructure text (corner swipe only), one call per visible activity ([index] of [count]): the screen's text is
     * all of them together. False when it isn't taken (the panel is closed, or Robot opened it). The caller refreshes the
     * chip.
     */
    fun onText(pieces: List<String>, pkg: String?, index: Int, count: Int): Boolean {
        if (ignore || !shown()) return false
        if (index == 0) {
            this.pieces.clear()
            this.pkg = pkg.takeIf { count == 1 }
        }
        this.pieces += pieces
        text = ScreenText.build(this.pieces)
        textCame = text != null
        return true
    }

    /**
     * Android called without the screen's text: "Use text from screen" is off, or the screen is private. Noted even
     * before the panel shows (a reused session gets it ahead of the show), so the chip can say why at once.
     */
    fun onNoText() {
        if (textCame != true) textCame = false
        if (shown()) refresh()
    }

    /** Null: Android called without a picture. As with [onNoText], a "no" counts before the show; a picture doesn't. */
    fun onShot(bmp: Bitmap?) {
        if (bmp == null) {
            if (shotCame != true) shotCame = false
        } else {
            if (ignore || !shown()) return
            shot = bmp
            shotCame = true
        }
        if (shown()) refresh()
    }

    /** A new panel: nothing of the screen yet; the chip explains if it hasn't come in [ScreenAccess.WAIT_MS]. */
    fun start(source: ShowSource, flags: Int) {
        from = source
        showFlags = flags
        settings = sharing()
        logged = ignore
        wait?.cancel()
        wait = scope.launch {
            delay(ScreenAccess.WAIT_MS)
            refresh()
            log()
        }
        refresh()
    }

    fun refresh() {
        val chip = ScreenAccess.chip(from, state.value.locked, textCame, shotCame, SystemClock.elapsedRealtime() - shownAt(), settings.first)
        state.update { st ->
            // The screen came after a tap said it hadn't ("still sending", "didn't share"): that notice no longer holds.
            val stale = chip == ScreenChip.READY && st.notice != null && st.notice === notice
            st.copy(screenChip = chip, notice = if (stale) null else st.notice, tips = if (chip == ScreenChip.READY) Tips.withScreen(st.tips) else st.tips)
        }
    }

    /** "Ask about this screen": the screen goes with the next question, or the panel says why it can't. */
    fun attach() {
        when (state.value.screenChip) {
            ScreenChip.READY -> state.update { it.copy(draft = Draft(Attachment.SCREEN, shot?.let { b -> Images.scaled(b, Images.THUMB_EDGE) })) }
            ScreenChip.WAITING -> hint(ScreenAccess.WAITING_HINT)
            ScreenChip.NOT_SHARED -> hint(ScreenAccess.notSharedHint(settings.first, settings.second))
            ScreenChip.SWIPE_ONLY -> hint(ScreenAccess.swipeHint(settings.first))
            ScreenChip.HIDDEN -> {}
        }
    }

    private fun hint(h: ScreenAccess.Hint) {
        val n = Notice(h.phrase.render(langs()), if (h.openSettings) NoticeAction.OPEN_ASSIST_SETTINGS else NoticeAction.NONE)
        notice = n
        state.update { it.copy(notice = n) }
    }

    /**
     * Once per panel: its show flags and whether the screen's text and a screenshot came (codes only, never content).
     * Written 2 s after the panel opened, or when it closes if that is sooner, so it says what a tap on the chip
     * would have found by then; a screen that comes later changes the chip but isn't logged again.
     */
    private fun log() {
        if (logged) return
        logged = true
        VoiceLog.add(VoiceEvent.SCREEN_SHARED, showFlags, ScreenData.of(textCame).id, ScreenData.of(shotCame).id)
    }
}
