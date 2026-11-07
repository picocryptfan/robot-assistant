// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.output.AskedBy
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.Dnd
import app.heyrobot.core.output.QuietAnswer
import app.heyrobot.core.output.Ringer
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

// MAIN THREAD. Quiet when the phone is silent (core QuietAnswer): as a turn starts, a typed question on a phone that is
// on silent or in Do Not Disturb turns Speak replies off for the panel and says why once; the next spoken question turns
// it back on, and so does a typed one once the phone isn't silenced or headphones are where the voice goes (each turn
// checks again). Speak replies on "Always", or your own tap on it, keeps the voice. The ringer, Do Not Disturb and the
// route need no permission.
internal class PanelQuiet(private val state: MutableStateFlow<PanelState>, private val notify: (Phrase) -> Unit) {
    private var quieted = false
    private var tapped = false
    private var told = false
    private var tell = false
    private var typed = false
    private var receiver: BroadcastReceiver? = null

    /** What a typed turn looks at: the ringer, Do Not Disturb, where the voice would go and car mode's car. */
    internal data class Look(val ringer: Ringer, val dnd: Dnd, val routed: RouteType? = null, val trust: Trust = Trust.NOT_PRIVATE,
        val car: Boolean = false)

    /** A new panel: nothing quieted, nothing tapped. */
    fun reset() { quieted = false; tapped = false; told = false; tell = false; typed = false }

    /** You turned Speak replies on or off yourself. */
    fun tapped(on: Boolean) { tapped = on; quieted = false }

    fun turn(ctx: Context, fromVoice: Boolean) =
        turn(fromVoice, VoiceHub.settings.speakReplies == SpeakReplies.ALWAYS) { look(ctx) }

    /** [always]: Speak replies is set to "Always". [look] is read only for a typed turn that may change something. */
    internal fun turn(fromVoice: Boolean, always: Boolean, look: () -> Look) {
        typed = !fromVoice
        if (fromVoice) { unquiet(); return }
        // Off by your own choice (a tap, "Never"): nothing to check.
        if (!state.value.speak && !quieted) return
        val l = look()
        if (!QuietAnswer.quiet(AskedBy.TYPED, l.ringer, l.dnd, l.routed, l.trust, l.car, tapped, always)) {
            // The ringer is back, Do Not Disturb ended or headphones came: this panel speaks again (review, 2).
            unquiet()
            return
        }
        if (quieted) return
        quieted = true
        state.update { it.copy(speak = false) }
        if (!told) { told = true; tell = true }
    }

    /**
     * Speak replies back on. A notice still waiting (a quieted turn that showed no answer, such as a screen question the
     * screen couldn't go with) is dropped, so it never comes with a spoken answer, and comes with the next quiet one.
     */
    private fun unquiet() {
        if (!quieted) return
        quieted = false
        if (tell) { tell = false; told = false }
        state.update { it.copy(speak = true) }
    }

    /**
     * The ringer or Do Not Disturb changed while the panel shows (review-quiet-when-silent, 6): after a typed question,
     * with no tap and not "Always", a phone that is now silenced quiets the panel, the notice comes once, and the caller
     * stops the answer still speaking (true).
     */
    internal fun changed(always: Boolean, look: () -> Look): Boolean {
        if (!typed || quieted || !state.value.speak) return false
        val l = look()
        if (!QuietAnswer.quiet(AskedBy.TYPED, l.ringer, l.dnd, l.routed, l.trust, l.car, tapped, always)) return false
        quieted = true
        state.update { it.copy(speak = false) }
        if (!told) { told = true; tell = false; notify(PanelStrings.quietSilenced()) }
        return true
    }

    /** While the panel shows: the ringer and Do Not Disturb, so a change mid-answer is heard at once. No permission. */
    fun listen(ctx: Context, stop: () -> Unit) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                // The ringer's broadcast is sticky: the one Android replays as this registers is no change.
                if (isInitialStickyBroadcast) return
                if (changed(VoiceHub.settings.speakReplies == SpeakReplies.ALWAYS) { look(c) }) stop()
            }
        }
        val f = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION).apply { addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) }
        try { ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED); receiver = r } catch (_: Throwable) {}
    }

    /** The panel hid: stop listening. */
    fun unlisten(ctx: Context) {
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
    }

    /** Once the turn shows: the notice, the first time this panel quieted an answer. */
    fun tell() { if (tell) { tell = false; notify(PanelStrings.quietSilenced()) } }

    private fun look(ctx: Context): Look {
        val ringer = ringer(ctx)
        val dnd = dnd(ctx)
        // Not silenced: where the voice goes doesn't matter, so no route or file is read.
        if (!QuietAnswer.silenced(ringer, dnd)) return Look(ringer, dnd)
        val seen = routed(ctx)
        val trust = seen?.takeIf { it.type in QuietAnswer.EARS }?.let { s -> memory(ctx).trust(s) } ?: Trust.NOT_PRIVATE
        return Look(ringer, dnd, seen?.type, trust, CarModeController.carSeen() != null)
    }

    internal companion object {
        /** The ringer and Do Not Disturb, also for routines (RoutineVoice.quiet). No permission. */
        fun ringer(ctx: Context): Ringer =
            try { Ringer.of(ctx.getSystemService(AudioManager::class.java).ringerMode) } catch (_: Throwable) { Ringer.NORMAL }

        fun dnd(ctx: Context): Dnd =
            try { Dnd.of(ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter) } catch (_: Throwable) { Dnd.OFF }
    }

    /**
     * Where Robot's voice would play now, as RoutineVoice.route: a SeenDevice with its name and class, so DeviceMemory
     * tells a remembered speaker from headphones (review, 3). Null when Android won't say, read as the phone.
     */
    private fun routed(ctx: Context): SeenDevice? = try {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        ctx.getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(attrs).firstOrNull()?.let { AudioDevices.seen(ctx, it) }
    } catch (_: Throwable) { null }

    private fun memory(ctx: Context): DeviceMemory =
        (DeviceMemory.decode(readOrNull(File(Paths.dir(ctx), "devices.json"))) as? DeviceFile.Ok)?.memory ?: DeviceMemory()
}
