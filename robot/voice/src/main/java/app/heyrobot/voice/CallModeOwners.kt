// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.media.AudioManager

// VOICE PROCESS (1.4.2). The one owner of the phone's communication mode for Robot (CallAudio's begin, a headset or car
// session's route, car mode's own lines, the hands-free check): each user is an owner, the first in sets
// MODE_IN_COMMUNICATION, the last out clears the communication device and sets MODE_NORMAL again. A refused or failed
// take holds nothing, so Robot never leaves the phone in call mode, where no other app's sound plays (the 8 Nov car
// report). Android keeps one mode request per app and setMode(MODE_NORMAL) withdraws only Robot's own, so the last
// owner out always sets it: a call that came in meanwhile keeps its mode, and Robot's request can't come back after it.
internal class CallModeOwners<D> {
    /** What it asks of Android's AudioManager (a fake in CallModeOwnersTest). */
    interface Port<D> {
        /** The phone's audio mode; -1 when it can't be read. */
        fun mode(): Int
        fun setMode(m: Int)
        /** setCommunicationDevice: false when Android refused it. */
        fun setDevice(d: D): Boolean
        /** call audio's own device: the loudspeaker, unless a headset or car is the call device already. */
        fun speakerUnlessHeadset()
        fun clearDevice()
        fun warn(where: String, t: Throwable? = null)
    }

    private val owners = HashSet<Any>()

    /**
     * Robot may take the mode: the phone is in its normal mode, or in the communication mode Robot holds. A call that
     * came in while Robot held it (another mode now) is never switched to communication mode, even for an owner already in.
     */
    private fun free(m: Int): Boolean = CallAudio.mayTake(m, owners.isNotEmpty() && (m == AudioManager.MODE_IN_COMMUNICATION || m == -1))

    val held: Boolean @Synchronized get() = owners.isNotEmpty()

    /** [owner] speaks with call audio: true when the mode is Robot's for it; false during another call or on a failure. */
    @Synchronized
    fun begin(p: Port<D>, owner: Any): Boolean {
        if (owner in owners) return true
        if (!free(p.mode())) return false
        owners += owner
        if (owners.size > 1) return true
        return try {
            p.setMode(AudioManager.MODE_IN_COMMUNICATION)
            p.speakerUnlessHeadset()
            true
        } catch (t: Throwable) {
            p.warn("call_audio", t)
            end(p, owner)
            false
        }
    }

    /** [owner] takes the mode with [device] as the call device; false during another call or when refused: a new owner holds nothing; one already in keeps its hold. */
    @Synchronized
    fun route(p: Port<D>, owner: Any, device: D): Boolean {
        if (!free(p.mode())) return false
        val wasIn = owner in owners
        owners += owner
        val ok = try {
            if (p.mode() != AudioManager.MODE_IN_COMMUNICATION) p.setMode(AudioManager.MODE_IN_COMMUNICATION)
            p.setDevice(device)
        } catch (t: Throwable) {
            p.warn("call_audio_route", t)
            false
        }
        if (!ok) {
            p.warn("call_audio_route_refused")
            // An owner already in keeps its earlier hold; a new one holds nothing.
            if (!wasIn) end(p, owner)
        }
        return ok
    }

    /** [owner] lets go (twice or never taken: nothing); the last one out puts the phone back in its normal mode. */
    @Synchronized
    fun end(p: Port<D>, owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        try { p.clearDevice() } catch (t: Throwable) { p.warn("call_audio", t) }
        // Always: this withdraws Robot's own request only, never a call's (one that came in meanwhile keeps its mode).
        try {
            p.setMode(AudioManager.MODE_NORMAL)
        } catch (t: Throwable) {
            p.warn("call_audio", t)
        }
    }
}
