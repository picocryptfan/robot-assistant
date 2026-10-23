// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.helpers.HelperSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// VOICE PROCESS. A helper's conversation ("go get Jarvis"): which helper it is with (in PanelController's state, for the
// chip), its session, where it joined Robot's conversation, and its 10 minutes without a turn. What is sent to the
// helper, and when, is PanelHelperTurn's, which reads the session and the join point here. Its callers
// and its idle timer run on the main thread, in the panel's scope, as before.

/** One helper's conversation at a time, over PanelController's [state]; [scope]: the panel's own; [running]: a turn is under way. */
internal class PanelHelperMode(private val scope: CoroutineScope, private val state: MutableStateFlow<PanelState>, private val running: () -> Boolean) {
    // Helpers (docs/ask-claude.md; any helper since 0.6.0). A helper's conversation (API route), and when it last had a turn.
    /** The conversation's session, made by its first question (PanelHelperTurn keeps it here); closed when it ends. */
    var session: HelperSession? = null
    private var last = 0L
    private var idle: Job? = null
    /** Where the helper's conversation started: what came before is what it joins. */
    var joinedAt = 0
        private set
    /** It came from a word that only sounded like the name: what came before goes only after a tap. */
    var misheard = false
        private set

    /** [h]'s conversation starts (another helper's ends: one at a time). */
    fun start(h: HelperConfig, joinedAt: Int, misheard: Boolean = false) {
        if (state.value.helperMode != h.id) {
            HelperLink.done(session)
            session = null
            this.joinedAt = joinedAt
            this.misheard = misheard
        }
        state.update { it.copy(helperMode = h.id, helperName = h.name, helperHome = h.home) }
        touch()
        RLog.i("helper_mode", "on" to true, "kind" to h.kind)
    }

    /** A turn in the helper's conversation: the 10 minutes start again. */
    fun touch() {
        last = System.currentTimeMillis()
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            if (!running()) end()
        }
    }

    fun expire() {
        if (state.value.helperMode != null && System.currentTimeMillis() - last > IDLE_MS) end()
    }

    /**
     * Settings changed in the main app ([s]): the conversation of a helper turned off or removed ends; renamed meanwhile, the
     * chip says the new name.
     */
    fun settingsChanged(s: RobotSettings) {
        val mode = state.value.helperMode ?: return
        if (s.helper(mode)?.on != true) return end()
        val name = s.helper(mode)?.name
        if (name != null && name != state.value.helperName) state.update { it.copy(helperName = name) }
    }

    /** "Back to Robot" on the chip, "thanks Jarvis", the panel closing, 10 minutes without a turn, or the lock screen. */
    fun end() {
        idle?.cancel()
        idle = null
        if (session == null && state.value.helperMode == null) return
        HelperLink.done(session)
        session = null
        state.update { it.copy(helperMode = null, helperName = null, helperHome = false) }
        RLog.i("helper_mode", "on" to false)
    }

    private companion object {
        /** A helper's conversation ends after this long without a turn. */
        const val IDLE_MS = 10 * 60_000L
    }
}
