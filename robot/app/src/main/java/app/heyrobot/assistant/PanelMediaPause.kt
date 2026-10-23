// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.headset.SessionPhase
import app.heyrobot.voice.TurnFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// VOICE PROCESS. 1.1: other apps' music and video pause while you ask (voice TurnFocus: from the question's first listen),
// and the panel says when its turn is over, whichever way it ends: the answer spoken, the reply only on screen, Stop, the
// panel closed, nothing heard, an error, or a headset session's follow-up window closing. One rule rather than a call at
// each ending: the turn goes on while the panel is open and its phase isn't idle, or a headset or car session is still
// between its question, its answer and its follow-up; otherwise it is over (TurnFocus waits for Robot's voice to be quiet
// and a moment more, so a listen that follows at once keeps the music paused). A typed question takes no focus here.

internal class PanelMediaPause(private val state: StateFlow<PanelState>, private val port: Port) {
    interface Port {
        val shown: Boolean
        /** A headset or car session is between its question and the end of its follow-up window. */
        val sessionHolds: Boolean
        fun keep()
        fun end(now: Boolean)
    }

    /** Follows the panel's phase from now on (PanelController.init). */
    fun start(scope: CoroutineScope) = scope.launch { state.map { it.phase }.distinctUntilChanged().collect { check() } }

    /** The panel's phase changed, or a headset session ended. */
    fun check() = if (inTurn(port.shown, state.value.phase, port.sessionHolds)) port.keep() else port.end(now = false)

    /** The panel closed: the music comes back now (once Robot's voice is quiet). */
    fun hidden() = port.end(now = true)

    companion object {
        fun inTurn(shown: Boolean, phase: Phase, sessionHolds: Boolean): Boolean = shown && (phase != Phase.IDLE || sessionHolds)

        /** A session's phases that are part of one question: the route, the question, the answer and the follow-up. */
        private val SESSION_TURN = setOf(SessionPhase.ROUTING, SessionPhase.LISTENING, SessionPhase.BUSY, SessionPhase.FOLLOW_UP)

        fun sessionHolds(phase: SessionPhase?): Boolean = phase in SESSION_TURN

        /** The panel's port: TurnFocus in this process. */
        fun port(shown: () -> Boolean): Port = object : Port {
            override val shown: Boolean get() = shown()
            override val sessionHolds: Boolean get() = sessionHolds(HandsFreeHub.session?.phase)
            override fun keep() = TurnFocus.keep()
            override fun end(now: Boolean) = TurnFocus.end(now)
        }
    }
}
