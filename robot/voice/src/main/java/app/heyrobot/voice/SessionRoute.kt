// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.media.AudioDeviceInfo

// VOICE PROCESS (0.8). A headset or car session's call route for the app's :voice code (design §3.6 HeadsetSession
// "Begins" and "Ends"), sharing call audio's rule and owners (CallAudio): never during another app's call, the last owner
// out puts the phone back in its normal mode.
object SessionRoute {
    /** Takes the communication mode with [device] as the call device; false during another call, or if Android refused. */
    fun take(ctx: Context, owner: Any, device: AudioDeviceInfo): Boolean = CallAudio.route(ctx, owner, device)

    /** Lets go of [owner]'s hold: music comes back once nothing else of Robot's holds the call route. */
    fun release(ctx: Context, owner: Any) = CallAudio.end(ctx, owner)

    /** Robot may take the communication mode now: the phone is in its normal mode, or the mode is already Robot's. */
    fun mayTake(mode: Int): Boolean = CallAudio.mayTake(mode, CallAudio.held)

    /** The communication mode is Robot's now (so a mode change to it is no other app's call). */
    val held: Boolean get() = CallAudio.held
}
