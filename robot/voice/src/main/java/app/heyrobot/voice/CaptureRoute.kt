package app.heyrobot.voice

import android.media.AudioDeviceInfo

// VOICE PROCESS (0.8). What a headset or car session asks of the microphone and of Robot's voice (design §3.6
// HeadsetSession): the capture with VOICE_COMMUNICATION while the session holds the call route (an LE Audio headset's
// microphone is listed only for it; SCO's for both), pinned to the headset's input; Robot's voice pinned to its output and
// watched while it plays (OutputGuard: a route change onto a device that isn't private stops it at once). Set by the
// session in the app's :voice code, read by Listener (between questions: a question's audio never has a gap) and by the
// voice's audio track.

object CaptureRoute {
    /** A session holds the call route: capture with VOICE_COMMUNICATION. */
    @Volatile var communication = false; private set
    /** The input to pin the capture to (AudioRecord.setPreferredDevice); null: Android's own choice. */
    @Volatile var preferred: AudioDeviceInfo? = null; private set
    /** Bumped at each change: a running capture reopens with it between questions. */
    @Volatile var generation = 0; private set

    @Synchronized
    fun set(communication: Boolean, preferred: AudioDeviceInfo?) {
        this.communication = communication
        this.preferred = preferred
        generation++
    }

    /** Back to the phone's own choices (the session ended). */
    fun clear() {
        if (communication || preferred != null) set(false, null)
    }
}

/**
 * Robot's voice pinned to [device] (AudioTrack.setPreferredDevice, design §1.3 rule 4: a second Bluetooth device
 * connecting doesn't take it), and [onRouted] told on the main thread whenever its track's route changes (rule 3).
 */
class OutputPin(val device: AudioDeviceInfo?, val onRouted: (AudioDeviceInfo?) -> Unit)
