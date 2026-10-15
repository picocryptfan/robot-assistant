// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import app.heyrobot.core.Playback
import app.heyrobot.core.RLog

// VOICE PROCESS. How Robot's voice is played (Settings › Voice, 0.2.3). The echo canceller that cleans the call-mode
// capture needs to know what the speaker plays (its "reference"). On Pixel the audio DSP mixes every stream it sends
// to the speaker and uses that mix, so the assistant usage Robot has always used should be enough; whether it is on
// this phone is what the barge-in test shows. "Call audio" is the other way, the one calling apps use: the phone in
// communication mode, Robot's voice as voice-communication audio, the loudspeaker as the call device (it would be the
// earpiece otherwise). Android resets the mode itself if the :voice process dies.

internal object CallAudio {
    private val owners = HashSet<Any>()

    fun attributes(p: Playback): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (p == Playback.CALL) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun focus(p: Playback): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes(p)).build()

    /**
     * May Robot take the communication mode now? Only from the normal mode (or when it is already Robot's): during
     * another app's call, taking it would put that call on the loudspeaker and silence its microphone.
     */
    fun mayTake(mode: Int, alreadyOurs: Boolean): Boolean = alreadyOurs || mode == AudioManager.MODE_NORMAL

    /**
     * [owner] starts speaking with call audio: communication mode and the loudspeaker, from the first owner on. False
     * when the phone is in another mode (a call): the caller then plays as assistant audio.
     */
    @Synchronized
    fun begin(ctx: Context, owner: Any): Boolean {
        if (owner in owners) return true
        val am = ctx.getSystemService(AudioManager::class.java)
        if (!mayTake(try { am.mode } catch (_: Throwable) { -1 }, owners.isNotEmpty())) return false
        owners += owner
        if (owners.size > 1) return true
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // A headset or car stays the call device; the earpiece gives way to the loudspeaker.
            val cur = am.communicationDevice
            if (cur == null || cur.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            }
        } catch (t: Throwable) {
            RLog.w("call_audio", t)
        }
        return true
    }

    /**
     * 0.8: [owner] (a headset or car session) takes the communication mode with [device] as the call device: SCO or LE
     * Audio for its microphone and Robot's voice (setCommunicationDevice needs only MODIFY_AUDIO_SETTINGS). False during
     * another app's call (that call is never touched) or when Android refused the device. Released with [end], the last
     * owner out putting the phone back in its normal mode; Android does it too if :voice dies.
     */
    @Synchronized
    fun route(ctx: Context, owner: Any, device: AudioDeviceInfo): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (owner !in owners && !mayTake(try { am.mode } catch (_: Throwable) { -1 }, owners.isNotEmpty())) return false
        owners += owner
        return try {
            if (am.mode != AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.setCommunicationDevice(device)
        } catch (t: Throwable) {
            RLog.w("call_audio_route", t)
            false
        }
    }

    /** Robot holds the communication mode now (its own call audio or a session's route), so a mode change isn't another call. */
    val held: Boolean @Synchronized get() = owners.isNotEmpty()

    /** [owner] stopped speaking; the last one out puts the phone back in its normal mode. */
    @Synchronized
    fun end(ctx: Context, owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            RLog.w("call_audio", t)
        }
    }
}
