// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.read

import android.app.KeyguardManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.RLog
import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.SpeechText
import app.heyrobot.core.TtsStop
import app.heyrobot.core.headset.MediaKey
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import app.heyrobot.core.read.MemoryReadPlaces
import app.heyrobot.core.read.ReadAction
import app.heyrobot.core.read.ReadCommand
import app.heyrobot.core.read.ReadCursor
import app.heyrobot.core.read.ReadKind
import app.heyrobot.core.read.ReadFocus
import app.heyrobot.core.read.ReadOutput
import app.heyrobot.core.read.ReadPlaces
import app.heyrobot.core.read.ReadSession
import app.heyrobot.core.read.ReadSource
import app.heyrobot.core.read.ReadState
import app.heyrobot.core.read.ReadText
import app.heyrobot.core.readOrNull
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.Paths
import app.heyrobot.voice.TurnFocus
import java.io.File

// VOICE PROCESS. Read it to me (docs/plans/read-it-to-me-plan.md §4 to §6): runs core ReadSession next to Robot's voice
// (VoiceHub.tts). Each piece goes through OutputGuard first (core ReadOutput.mayPlay: personal, never onto the phone's
// speaker once it started elsewhere); ReadingService holds the reading's foreground notification and its MediaSession
// only while a reading is on, and hears the headset's button, "becoming noisy", the lock, calls and audio focus. Main
// thread only. Nothing here logs words: counts and codes.

object ReadingVoice {
    /** Places kept while Robot runs; the sealed store replaces it when feature/seal-people merges. */
    val places: ReadPlaces = MemoryReadPlaces()

    private var session: ReadSession? = null
    private var cursor: ReadCursor? = null
    /** The piece Robot's voice is saying now, -1 for none. */
    private var speaking = -1
    private var startedOnPhone = true
    /** The reading's own Silence stops the voice: not a stop from you. */
    private var selfStop = false
    /** What waits for the foreground service (ReadingService.ready). */
    private var waiting: List<ReadAction> = emptyList()
    private var gen = 0

    val state: ReadState get() = session?.state ?: ReadState.NONE

    private val app: Context get() = VoiceHub.app

    /**
     * Reads [text] of [source] from its kept place. Returns the line for the panel or the share door to show and say first
     * (a refusal, or "Reading it to you."); the pieces follow once Robot's voice is quiet.
     */
    fun start(source: ReadSource, text: ReadText): Msg {
        stopNow()
        val c = ReadCursor(source, text, places)
        val s = ReadSession(c, inCall = HandsFreeHub::callActive, mayGoOn = { source.kind != ReadKind.FILE || filesOpen() })
        val a = s.start(locked())
        if (s.state != ReadState.PLAYING) return lineOf(a) ?: HandsFreeStrings.readNothing
        // The first piece is checked before "Reading it to you.": a hold now gets its own line, not silence.
        val onPhone = route().onPhone
        val first = a.filterIsInstance<ReadAction.Speak>().firstOrNull()
        if (first != null && (HandsFreeHub.callActive() || !ReadOutput.mayPlay(first.piece, route(), phone(), onPhone, guard()))) {
            RLog.i("read_held", "at" to first.index)
            return HandsFreeStrings.readNotHere
        }
        cursor = c
        session = s
        startedOnPhone = onPhone
        RLog.i("read_start", "kind" to RLog.Code(source.kind.prefix), "pieces" to text.pieces.size, "at" to c.current)
        waiting = a.filter { it !is ReadAction.Say }
        if (!ReadingService.start(app)) { stopNow(); return HandsFreeStrings.readNothing }
        return lineOf(a) ?: HandsFreeStrings.readStarting
    }

    /** The service is in the foreground: the reading plays. */
    internal fun ready() {
        val a = waiting
        waiting = emptyList()
        run(a)
    }

    /** "pause", "keep reading", "go back a bit", "stop reading": the line the panel says, the rest done here. */
    fun command(c: ReadCommand): Msg {
        val s = session ?: return HandsFreeStrings.readNothingOn
        val a = s.command(c, locked())
        run(a.filter { it !is ReadAction.Say })
        return s.reply(c, a)
    }

    // ------------------------------------------------------------------ what Android tells the service

    fun key(k: MediaKey) = session?.let { run(it.key(k, SystemClock.uptimeMillis(), locked())) }
    /** ACTION_AUDIO_BECOMING_NOISY: the headset is going; sound would move to the speaker, so stop now and keep the place. */
    fun noisy() = session?.let { run(it.held()) }
    /** The screen went off or the keyguard came up: the piece playing ends, then the reading pauses. */
    fun lockedNow() = session?.let { if (locked() != false) run(it.locked()) }
    /** The phone's audio mode changed: a call rings or starts (HandsFreeHub's rule: Robot's own headset route is none). */
    fun modeChanged() = session?.let { if (HandsFreeHub.callActive()) run(it.call()) }
    /** An output went away: the next piece is checked anyway; the one playing stops now if it may no longer play there. */
    fun routeChanged() {
        val s = session ?: return
        val p = cursor?.piece() ?: return
        if (s.state == ReadState.PLAYING && !ReadOutput.mayPlay(p, route(), phone(), startedOnPhone, guard())) run(s.held())
    }

    private fun focus(change: Int) {
        val s = session ?: return
        when (ReadOutput.focus(change)) {
            ReadFocus.LOST -> run(s.focusLost(short = false))
            ReadFocus.LOST_SHORT -> run(s.focusLost(short = true))
            ReadFocus.GAINED -> run(s.focusGained(locked()))
            ReadFocus.OTHER -> Unit
        }
    }

    // ------------------------------------------------------------------ from VoiceHub

    /** Robot's voice went quiet: once it really has, the piece was said to its end. */
    fun onSpeakingChanged(on: Boolean) {
        if (on || speaking < 0) return
        val i = speaking
        val g = gen
        VoiceHub.main.postDelayed({
            if (g == gen && speaking == i && !VoiceHub.tts.isSpeaking) piecePlayed(i)
        }, 350)
    }

    /** Something stopped Robot's voice ("hey robot", "stop", a tap): the reading pauses and keeps its place. */
    fun onVoiceStopped(why: TtsStop) {
        if (selfStop) return
        val s = session ?: return
        if (s.state == ReadState.PLAYING) {
            RLog.i("read_voice_stopped", "why" to why.id)
            run(s.command(ReadCommand.Pause, locked()))
        }
    }

    private fun piecePlayed(i: Int) {
        speaking = -1
        VoiceHub.main.removeCallbacks(watchdog)
        session?.let { run(it.pieceEnded(i, whole = true, lockedNow = locked())) }
    }

    private val pauseEnds = Runnable { session?.let { RLog.i("read_pause_ended"); run(it.pausedTooLong()) } }

    private val watchdog = Runnable { if (speaking >= 0) { RLog.i("read_piece_watchdog"); piecePlayed(speaking) } }

    // ------------------------------------------------------------------ the session's actions

    private fun run(actions: List<ReadAction>) {
        for (a in actions) when (a) {
            is ReadAction.Speak -> speak(a)
            ReadAction.Silence -> silence()
            is ReadAction.Say -> say(a.line)
            ReadAction.End -> end()
        }
        VoiceHub.main.removeCallbacks(pauseEnds)
        val s = session ?: return
        if (s.state == ReadState.PAUSED) VoiceHub.main.postDelayed(pauseEnds, ReadSession.PAUSE_ENDS_MS)
        TurnFocus.reading(app, s.holdsFocus, ::focus)
        ReadingService.instance?.update(s.state)
    }

    private fun speak(a: ReadAction.Speak) {
        val s = session ?: return
        // The panel's own reply is still being said: this piece comes after it.
        if (VoiceHub.tts.isSpeaking && speaking < 0) {
            val g = gen
            VoiceHub.main.postDelayed({ if (g == gen && session === s && s.state == ReadState.PLAYING && speaking < 0) speak(a) }, 400)
            return
        }
        // A call came before its mode change did: the reading pauses for the call, never plays in it.
        if (HandsFreeHub.callActive()) { run(s.call()); return }
        if (!ReadOutput.mayPlay(a.piece, route(), phone(), startedOnPhone, guard())) {
            RLog.i("read_held", "at" to a.index)
            run(s.held())
            return
        }
        speaking = a.index
        gen++
        VoiceHub.tts.speak(a.piece.text)
        VoiceHub.main.removeCallbacks(watchdog)
        VoiceHub.main.postDelayed(watchdog, PIECE_MS + 120L * a.piece.text.length)
    }

    private fun silence() {
        speaking = -1
        gen++
        VoiceHub.main.removeCallbacks(watchdog)
        selfStop = true
        try { VoiceHub.stopVoice(TtsStop.OTHER) } finally { selfStop = false }
    }

    private fun say(line: Msg) {
        val t = SpeechText.clean(line().render(AppLanguage.langs(app)))
        if (t.isNotEmpty()) VoiceHub.tts.speak(t)
    }

    private fun end() {
        RLog.i("read_end", "at" to (cursor?.current ?: -1))
        session = null
        cursor = null
        speaking = -1
        gen++
        waiting = emptyList()
        VoiceHub.main.removeCallbacks(watchdog)
        VoiceHub.main.removeCallbacks(pauseEnds)
        TurnFocus.reading(app, false)
        ReadingService.instance?.done()
    }

    /** A new reading, or Robot closing: the one on ends now (its place kept, a shared page's dropped). */
    fun stopNow() {
        val s = session ?: return
        run(s.command(ReadCommand.Stop, locked()).filter { it !is ReadAction.Say })
        if (session != null) end()
    }

    /**
     * fix-stage-reading H1: :lock says a lock started (Cmd.PHONE_LOCK, however it started: voice, tap, schedule). One for
     * someone else, or one that can't be told (fails closed), ends the reading now: the voice is cut, nothing is said, and
     * its text goes with the session; your place in a note or file is kept.
     */
    fun lockStarted() {
        val s = session ?: return
        val other = try { PhoneLockNow.someoneElse(app) } catch (t: Throwable) { RLog.w("read_lock", t); true }
        if (!other) return
        RLog.i("read_lock_other", "at" to (cursor?.current ?: -1))
        run(s.lockForSomeoneElse())
        if (session != null) end()
    }

    private fun lineOf(a: List<ReadAction>): Msg? = a.firstNotNullOfOrNull { (it as? ReadAction.Say)?.line }

    // ------------------------------------------------------------------ the phone now

    /** Locked, or null when it can't be told (counted as locked: ReadGate fails closed; Assistant.keyguard fails open). */
    private fun locked(): Boolean? = try {
        app.getSystemService(KeyguardManager::class.java).isKeyguardLocked || PhoneLockNow.someoneElse(app)
    } catch (t: Throwable) { RLog.w("read_lock", t); null }

    /** Robot Files is still open for reading (FilesIntake's gate); false when it can't be told. */
    private fun filesOpen(): Boolean = try { FilesIntake.gateText(FilesIntake.gate(app)) == null } catch (t: Throwable) { RLog.w("read_files_gate", t); false }

    private fun phone(): PhoneState {
        return PhoneState(
            locked = locked() != false,
            screenOn = try { app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true },
            callActive = HandsFreeHub.callActive(),
        )
    }

    /** Where Robot's voice would play now, as routines see it (RoutineVoice.route). */
    private fun route(): RoutineRoute {
        val memory = (DeviceMemory.decode(readOrNull(File(Paths.dir(app), "devices.json"))) as? DeviceFile.Ok)?.memory ?: DeviceMemory()
        val routed = try {
            val am = app.getSystemService(AudioManager::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { AudioDevices.seen(app, it) }
        } catch (t: Throwable) {
            RLog.w("read_route", t)
            // Can't say where it goes: as somewhere others may hear, never as private.
            SeenDevice(RouteType.OTHER)
        }
        val car = CarModeController.carSeen()
        return RoutineRoute(routed, routed?.let(memory::trust) ?: Trust.NOT_PRIVATE, car, car != null && CarModeController.trusted,
            HandsFreeHub.car.settings.readAloud)
    }

    private fun guard() = HandsFreeHub.headset.settings.guard(HandsFreeHub.headset.safety, HandsFreeHub.car.settings.readAloud)

    private const val PIECE_MS = 8_000L
}
