package app.heyrobot.assistant.conversation

import android.Manifest
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvPause
import app.heyrobot.core.ConvStop
import app.heyrobot.core.ConversationLine
import app.heyrobot.core.ConversationLock
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.ConversationRules
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.ConversationSpeakers
import app.heyrobot.core.IoCode
import app.heyrobot.core.LangLabel
import app.heyrobot.core.MicEvent
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.Segment
import app.heyrobot.core.SessionRecovery
import app.heyrobot.core.SpeakerClusterer
import app.heyrobot.core.SpeakerLabels
import app.heyrobot.core.ThermalStep
import app.heyrobot.core.Transcript
import app.heyrobot.core.TranscriptEdits
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.TranscriptWriter
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Transcripts
import app.heyrobot.voice.AudioFrames
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.SegmentHeard
import app.heyrobot.voice.VoiceEngines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime

// VOICE PROCESS. One conversation session at a time (docs/conversation-mode.md 1, phase 5.2): the transcript it writes
// (TranscriptWriter), the engine that cuts and decodes the speech (ConversationEngine), where its audio comes from (a
// sink on the shared Listener, or a capture of its own when the wake word isn't listening: never a second microphone
// when one is open), and what it does when things change: pauses (you, a call, another app taking the microphone, the
// panel opening, heat), the heartbeat for crash recovery, the minute's numbers for the voice log, low storage. Every
// change of state happens on one thread ("robot-conv-ctl"); lines arrive on the engine's worker; audio on the
// listener's thread. The wake phrases sleep while it runs (unless "Hey robot works during a conversation"); "stop"
// while Robot talks still works; they wake again when it ends. Speaker labels (phase 5.3): with the speaker model and
// "Tell speakers apart" on, each line gets S1, S2… (or "Me" from your enrolled voiceprint, decrypted here and used only
// to seed the session's clusterer); what you change on the transcript screen (names, merges, "this is me", a line
// moved or split) is appended through the session's writer, and the voices are dropped when the session ends.

object ConversationSession {
    enum class Status { IDLE, STARTING, RUNNING, PAUSED, STOPPING }

    /** What the transcript screen, the notification and the tile show. [lines]: the newest [ConversationRules.LIVE_LINES]. */
    data class Ui(
        val status: Status = Status.IDLE,
        val id: String? = null,
        val mode: ConvMode = ConvMode.TRANSCRIBE,
        /** Wall clock of the transcript's start (the elapsed time counts from here). */
        val createdAt: Long = 0,
        /** Why it is paused (the first reason that still holds), or null. */
        val pausedFor: ConvPause? = null,
        val lines: List<TranscriptLine> = emptyList(),
        val total: Int = 0,
        val partial: String = "",
        val notice: String? = null,
        /** Started on the lock screen: its screen may show over the keyguard, with only what it heard since. */
        val lockedStart: Boolean = false,
        /** The last session that ended here (for "Saved: open it in Robot"). */
        val savedId: String? = null,
        /** Speakers are told apart in this session (the speaker model is there and "Tell speakers apart" is on). */
        val speakersOn: Boolean = false,
        /** The transcript's speakers so far, in the order they first spoke; the names you gave; who is you. */
        val speakers: List<String> = emptyList(),
        val names: Map<String, String> = emptyMap(),
        val me: String? = null,
        /** Two speakers who sound alike ("S3" to "S1"), offered as a merge; null: none. */
        val proposal: Pair<String, String>? = null,
        /** The last session ended with this many such proposals left, offered on the transcript in Robot. */
        val similarLeft: Int = 0,
    ) {
        val active: Boolean get() = status == Status.RUNNING || status == Status.PAUSED || status == Status.STARTING

        /** One voice and no names: the screen shows "Speaker". */
        val oneVoice: Boolean get() = speakers.size <= 1 && names.isEmpty() && me == null && SpeakerClusterer.ME !in speakers

        /** A speaker as shown: the name you gave, "You", or the label. */
        fun nameOf(speaker: String): String = Transcript(names = names, me = me).nameOf(speaker)
    }

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state

    /** A session runs or is paused. */
    val running: Boolean get() = _state.value.active

    private lateinit var app: Context
    private val ctl: Handler by lazy { Handler(HandlerThread("robot-conv-ctl").apply { start() }.looper) }
    private var recovered = false

    // Owned by the control thread.
    private var writer: TranscriptWriter? = null
    private var engine: ConversationEngine? = null
    /** The session's speakers (memory only; the engine's worker labels lines with them, this thread applies your changes). */
    @Volatile private var speakers: ConversationSpeakers? = null
    /** Speakers you merged during the session (from, to): a line labelled just before lands with the merged label. */
    @Volatile private var merged: Map<String, String> = emptyMap()
    private var source: AudioFrames? = null
    /** Read by the panel's question (another thread) when it needs audio and the shared listener isn't running. */
    @Volatile private var own: Listener? = null
    private val pauses = LinkedHashSet<ConvPause>()
    private var pausedAt = 0L
    private var pausedE = 0L
    private var firstPause: ConvPause? = null
    private var entry = ConvEntry.APP
    private var wake: PowerManager.WakeLock? = null
    private var thermal: PowerManager.OnThermalStatusChangedListener? = null
    private var warm = false
    private var screenOn = true
    /** Transcript screens in front (live words are decoded only for someone looking). */
    @Volatile private var watching = 0
    private var lastBeat = 0L
    private var lastMinute = 0L
    private var lastDisk = 0L
    private var longNoticed = false
    private var lastSaveFail: IoCode? = null
    /** The transcript was deleted under the session: nothing more is written. */
    @Volatile private var discarded = false
    private val writerLock = Any()
    /** Starts asked for and not yet run on the control thread (guarded by [startLock], with the state's STARTING). */
    private val startLock = Any()
    private var pendingStarts = 0

    /** Audio from the listener's thread (paused: dropped). */
    private val sink: (FloatArray) -> Unit = { x ->
        if (_state.value.status == Status.RUNNING) engine?.feed(x, VoiceHub.robotSpeaking)
    }

    private val tick = object : Runnable {
        override fun run() {
            try { onTick() } catch (t: Throwable) { RLog.w("conv_tick", t) }
            if (running) ctl.postDelayed(this, 1_000)
        }
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    // ------------------------------------------------------------------ crash recovery

    /**
     * Once per process, before any session can start (same thread): a session the process died under is closed and
     * Robot offers Resume (never resumes by itself). After your own Stop in Active apps, closed without asking.
     */
    fun recoverOnce(ctx: Context) {
        init(ctx)
        ctl.post {
            if (recovered) return@post
            recovered = true
            try {
                // A session of this process holds the heartbeat only once it has its writer (STARTING doesn't count).
                val r = Transcripts.store(app).recover(serviceRunning = writer != null, stoppedByUser = stoppedByUser()) ?: return@post
                RLog.i("conv_recovered", "lines" to r.lines, "resume" to (r.action == SessionRecovery.Action.OFFER_RESUME))
                VoiceLog.add(VoiceEvent.CONV_RECOVERED, r.lines, r.minutes, 0)
                if (r.action == SessionRecovery.Action.OFFER_RESUME) ConversationNotices.offerResume(app, r.id, r.savedUpTo)
            } catch (t: Throwable) {
                RLog.w("conv_recover", t)
            }
        }
    }

    /** The voice process's last end was your own "Stop" in Active apps (REASON_USER_REQUESTED). */
    private fun stoppedByUser(): Boolean = try {
        app.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(app.packageName, 0, 16)
            .firstOrNull { it.processName.endsWith(":voice") }?.reason == ApplicationExitInfo.REASON_USER_REQUESTED
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ start and stop

    /**
     * From the service (it is in the foreground already): a new session, or [resumeId] after it stopped unexpectedly.
     * [onFail] gets the words to show when it can't start (the service then stops).
     */
    fun start(ctx: Context, mode: ConvMode, entry: ConvEntry, resumeId: String?, onFail: (String) -> Unit) {
        init(ctx)
        // Recovery first (same thread, so it runs before): a new session's heartbeat must not hide an old one's.
        recoverOnce(ctx)
        // Counted before it is posted, so a stop still saving the last session (Stop, then Start at once) leaves the
        // service in the foreground for this one instead of taking it down under it.
        synchronized(startLock) {
            if (_state.value.active) return
            _state.update { it.copy(status = Status.STARTING, notice = null) }
            pendingStarts++
        }
        ctl.post {
            synchronized(startLock) { pendingStarts-- }
            if (writer != null) return@post
            val why = try { startNow(mode, entry, resumeId) } catch (t: Throwable) {
                RLog.w("conv_start", t)
                // Half started (the transcript and the engine made, then something threw): undone like a stop, so
                // nothing goes on recording, holding the wake lock or keeping the wake word asleep.
                if (writer != null) return@post stopNow(ConvStop.ERROR)
                "Transcribing couldn't start."
            }
            if (why != null) {
                val last = synchronized(startLock) {
                    _state.update { Ui(notice = why, savedId = it.savedId, status = if (pendingStarts > 0) Status.STARTING else Status.IDLE) }
                    pendingStarts == 0
                }
                if (last) onFail(why)
            }
        }
    }

    /** Control thread. Null when started, else why not. */
    private fun startNow(mode: ConvMode, entry: ConvEntry, resumeId: String?): String? {
        VoiceHub.init(app)
        val s = VoiceHub.settings
        if (!Assistant.granted(app, Manifest.permission.RECORD_AUDIO)) return "Robot needs the microphone permission (Robot app › Get started)."
        val decoder = VoiceEngines.segmentDecoder()
        if (decoder == null || !decoder.available) return "Transcribing needs the speech recognition model: download it in the Robot app (Model › Voice models)."
        val store = Transcripts.store(app)
        if (ConversationRules.diskFull(store.freeBytes())) return "Storage is nearly full (under 200 MB free): Robot can't save a transcript."
        val locked = Assistant.locked(app)
        if (!ConversationLock.mayStart(locked, s)) return "Unlock your phone to start transcribing (Settings › Conversation › Start from the lock screen)."
        val now = System.currentTimeMillis()
        val w = if (resumeId != null) TranscriptWriter.resume(store, resumeId, now)
            ?: return "That transcript was deleted or can't be opened."
        else TranscriptWriter.start(store, TranscriptIds.new(LocalDateTime.now()), now, mode, s.convPairChoice)
            ?: return "Robot couldn't create the transcript on this phone."
        // Speaker labels (phase 5.3): the speaker model when it is there and "Tell speakers apart" is on, seeded with your
        // voiceprint if you made one. A resumed transcript's people get new labels (S1′): their voices are gone.
        val embedder = VoiceEngines.speakerEmbedder()
        val on = ConversationSettings.speakers(s, embedder?.available == true)
        val so = w.current()
        val mine = if (on) Voiceprints.read(app, VoiceBundle.SPEAKERS.id) else null
        val sp = ConversationSpeakers(on, ConversationSettings.maxSpeakers(s, mode),
            if (resumeId != null) SpeakerLabels.resumeMark(so.speakers + so.merges.keys) else "", mine?.vector)
        mine?.vector?.fill(0f)
        val e = ConversationEngine(app, decoder, LangLabel.owner(), output, speakers = sp, embedder = embedder)
        if (!e.start()) {
            // A new transcript with nothing in it goes again (not left as "Stopped unexpectedly"); a resumed one is closed.
            if (resumeId == null) store.delete(w.id) else w.close(System.currentTimeMillis(), SessionStatus.INTERRUPTED)
            return "The voice detector couldn't start."
        }
        discarded = false
        writer = w
        engine = e
        speakers = sp
        merged = emptyMap()
        this.entry = entry
        pauses.clear()
        firstPause = null
        lastSaveFail = null
        longNoticed = false
        lastBeat = SystemClock.elapsedRealtime()
        lastMinute = lastBeat
        lastDisk = lastBeat
        // Lines from before a crash show only when the phone is unlocked (a screen over the lock screen shows nothing older).
        val earlier = if (resumeId != null && ConversationLock.showsEarlier(locked)) w.current().lines.takeLast(ConversationRules.LIVE_LINES) else emptyList()
        _state.value = Ui(Status.RUNNING, w.id, mode, w.meta.createdAt, lines = earlier, total = w.lines, lockedStart = locked,
            speakersOn = on, speakers = so.speakers, names = so.names, me = so.me)
        // The wake phrases sleep for the session unless "Hey robot works during a conversation".
        VoiceHub.conversationWake(!s.convWakeDuring)
        wake = (app.getSystemService(PowerManager::class.java)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robot:conversation").apply {
            setReferenceCounted(false)
            acquire()
        }
        watchThermal()
        screenOn = app.getSystemService(PowerManager::class.java).isInteractive
        e.beginRun(w.clock(now))
        updatePartials()
        if (!attachAudio()) pauseNow(ConvPause.MIC_TAKEN)
        // Opened from the panel: it is on screen until it hides, and nothing of it is transcribed.
        if (PanelController.shown) pauseNow(ConvPause.PANEL)
        VoiceLog.add(VoiceEvent.CONV_START, mode, entry, if (locked) 1 else 0)
        if (resumeId != null) VoiceLog.add(VoiceEvent.CONV_RECOVERED, w.lines, ((now - w.meta.createdAt) / 60_000).toInt(), 1)
        RLog.i("conv_start", "mode" to mode, "entry" to entry, "locked" to locked, "resumed" to (resumeId != null), "speakers" to on,
            "me" to sp.seeded)
        ConversationNotices.cancelResume(app, w.id)
        ctl.removeCallbacks(tick)
        ctl.postDelayed(tick, 1_000)
        ConversationNotices.changed(app)
        return null
    }

    /** Stops the session (from any thread); every segment heard is decoded and saved first. */
    fun stop(why: ConvStop) {
        if (!::app.isInitialized) return
        _state.update { if (it.active) it.copy(status = Status.STOPPING) else it }
        ctl.post { stopNow(why) }
    }

    private fun stopNow(why: ConvStop) {
        val w = writer ?: return finishService()
        val e = engine
        detachAudio()
        // The open segment and everything queued are decoded and saved before the file is closed.
        try { e?.close() } catch (t: Throwable) { RLog.w("conv_close", t) }
        val now = System.currentTimeMillis()
        // Speakers who sound alike are offered on the transcript afterwards; then every voice is forgotten.
        val sp = speakers
        val similar = sp?.proposals().orEmpty()
        synchronized(writerLock) {
            if (!discarded) {
                if (pauses.isNotEmpty()) gap(w, now)
                w.close(now, similar = similar)
            }
        }
        if (sp != null && sp.enabled) {
            // Numbers only: never a label, a name or a voice.
            VoiceLog.add(VoiceEvent.CONV_SPEAKERS, sp.count, similar.size, if (sp.seeded) 1 else 0)
            e?.embedStats?.let { (n, ms) -> RLog.i("conv_speakers", "speakers" to sp.count, "proposed" to similar.size, "embedded" to n, "embedMs" to ms) }
        }
        sp?.clear()
        speakers = null
        merged = emptyMap()
        val minutes = ((now - w.meta.createdAt) / 60_000).toInt()
        VoiceLog.add(VoiceEvent.CONV_STOP, why, minutes, w.lines / 10)
        RLog.i("conv_stop", "why" to why, "minutes" to minutes)
        e?.stats?.take()?.let { (m, l) -> VoiceLog.add(VoiceEvent.CONV_MINUTE, m.first, m.second, m.third); VoiceLog.add(VoiceEvent.CONV_LANG, l.first, l.second, l.third) }
        writer = null
        engine = null
        pauses.clear()
        unwatchThermal()
        try { wake?.release() } catch (_: Throwable) {}
        wake = null
        // The wake word comes back.
        VoiceHub.conversationWake(false)
        ctl.removeCallbacks(tick)
        val note = when (why) {
            ConvStop.DISK -> "Stopped: storage is nearly full. The transcript is saved."
            ConvStop.ERROR -> "Stopped after an error. What was heard is saved."
            else -> null
        }
        synchronized(startLock) {
            _state.update {
                val ended = Ui(savedId = if (discarded) null else w.id, lines = if (discarded) emptyList() else it.lines, total = if (discarded) 0 else it.total,
                    notice = if (discarded) "This transcript was deleted." else note, speakers = if (discarded) emptyList() else it.speakers,
                    names = if (discarded) emptyMap() else it.names, me = if (discarded) null else it.me, similarLeft = if (discarded) 0 else similar.size)
                // A start asked for while this one was saving runs next on this thread: it stays STARTING.
                if (pendingStarts > 0) ended.copy(status = Status.STARTING) else ended
            }
        }
        finishService()
    }

    /**
     * The session ended: the notification and the tile follow, and the service leaves the foreground, unless a start
     * asked for meanwhile is waiting on this thread (its session must not run without the service and its notification).
     */
    private fun finishService() {
        ConversationNotices.changed(app)
        if (synchronized(startLock) { pendingStarts > 0 || _state.value.active }) return
        ConversationService.instance?.done()
    }

    /**
     * The app deleted [id] (null: every transcript): a session recording it stops and writes nothing more, and a
     * Resume offered for it goes away (Cmd.TRANSCRIPTS_DELETED).
     */
    fun transcriptsDeleted(ctx: Context, id: String?) {
        init(ctx)
        val store = Transcripts.store(app)
        id?.let(store::forget)
        ConversationNotices.cancelResume(app, id)
        ctl.post {
            val w = writer ?: run {
                // Not running: "Saved: open it" must not point at a deleted transcript.
                _state.update { if (id == null || it.savedId == id) it.copy(savedId = null, lines = emptyList(), total = 0) else it }
                return@post
            }
            if (id != null && id != w.id) return@post
            // "Delete all" took the transcripts there were; one started just after it (its folder is there) goes on.
            if (id == null && store.dir(w.id)?.isDirectory == true) return@post
            store.forget(w.id)
            discarded = true
            stopNow(ConvStop.USER)
        }
    }

    // ------------------------------------------------------------------ pause and resume

    /** You paused it (the screen, the notification, by voice). It stays paused until you resume. */
    fun userPause() = ctl.post { pauseNow(ConvPause.USER) }

    /** You resumed it: every pause you can end ends (not the microphone being taken or the phone being too hot). */
    fun userResume() = ctl.post {
        pauses.remove(ConvPause.USER)
        pauses.remove(ConvPause.PANEL)
        resumeIfFree()
    }

    /** The panel opened (true) or closed: nothing said to Robot is transcribed. */
    fun panel(shown: Boolean) {
        if (!::app.isInitialized) return
        ctl.post {
            if (writer == null) return@post
            if (shown) pauseNow(ConvPause.PANEL) else {
                pauses.remove(ConvPause.PANEL)
                resumeIfFree()
            }
        }
    }

    private fun pauseNow(why: ConvPause) {
        val e = engine ?: return
        if (why in pauses) return
        val wasRunning = pauses.isEmpty()
        pauses += why
        if (!wasRunning) return publishPause()
        e.endRun()
        pausedAt = System.currentTimeMillis()
        pausedE = SystemClock.elapsedRealtime()
        firstPause = why
        VoiceLog.add(VoiceEvent.CONV_PAUSE, why)
        publishPause()
    }

    private fun resumeIfFree() {
        val w = writer ?: return
        val e = engine ?: return
        if (pauses.isNotEmpty()) return publishPause()
        if (_state.value.status != Status.PAUSED) return
        val now = System.currentTimeMillis()
        synchronized(writerLock) { gap(w, now) }
        VoiceLog.add(VoiceEvent.CONV_RESUME, firstPause ?: ConvPause.USER, ((SystemClock.elapsedRealtime() - pausedE) / 1000).toInt())
        firstPause = null
        e.beginRun(w.clock(now))
        _state.update { it.copy(status = Status.RUNNING, pausedFor = null) }
        ConversationNotices.changed(app)
    }

    /** The gap marker for the pause that ends at [now] ("[paused 14:03–14:07]"), when it lasted a second or more. */
    private fun gap(w: TranscriptWriter, now: Long) {
        val why = firstPause ?: return
        if (now - pausedAt < 1_000 || discarded) return
        w.event(TranscriptEvent.Gap(w.clock(pausedAt), w.clock(now), ConversationRules.gap(why)))
    }

    private fun publishPause() {
        val first = pauses.firstOrNull()
        _state.update { if (it.status == Status.STOPPING) it else it.copy(status = if (first == null) Status.RUNNING else Status.PAUSED, pausedFor = first, partial = "") }
        ConversationNotices.changed(app)
    }

    // ------------------------------------------------------------------ audio

    /** The shared listener's frames when it runs, else a capture of the session's own (wake word off). */
    private fun attachAudio(): Boolean {
        val hub = VoiceHub.listener?.takeIf { it.running }
        if (hub != null) {
            hub.addSink(sink)
            source = hub
            return true
        }
        val l = Listener(app, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
        if (!l.start()) {
            l.stop()
            return false
        }
        l.addSink(sink)
        own = l
        source = l
        return true
    }

    private fun detachAudio() {
        source?.removeSink(sink)
        source = null
        own?.stop()
        own = null
    }

    /** The question after "hey robot" takes its audio from the session's own capture when there is no shared one. */
    fun frames(): AudioFrames? = own?.takeIf { it.running }

    /** VoiceHub started or stopped its listener: move to it, or to a capture of our own (on the next tick). */
    fun audioChanged() {
        if (!::app.isInitialized) return
        ctl.post { checkAudio() }
    }

    private fun checkAudio() {
        if (writer == null) return
        val hub = VoiceHub.listener?.takeIf { it.running }
        val src = source
        when {
            // The shared listener came (back): use it, and close our own.
            hub != null && src !== hub -> {
                detachAudio()
                hub.addSink(sink)
                source = hub
            }
            src == null || !src.capturing -> {
                detachAudio()
                if (!attachAudio()) {
                    if (ConvPause.MIC_TAKEN !in pauses) VoiceLog.add(VoiceEvent.CONV_MIC, MicEvent.SILENCED)
                    pauseNow(ConvPause.MIC_TAKEN)
                    return
                }
            }
        }
        // A call, or another app recording: Android silences the capture. The session pauses with a gap marker and goes
        // on by itself when the microphone comes back.
        val silenced = source?.silenced == true
        val held = ConvPause.CALL in pauses || ConvPause.MIC_TAKEN in pauses
        if (silenced && !held) {
            VoiceLog.add(VoiceEvent.CONV_MIC, MicEvent.SILENCED)
            pauseNow(ConversationRules.silencedBy(inCall()))
        } else if (!silenced && held && source != null) {
            VoiceLog.add(VoiceEvent.CONV_MIC, MicEvent.BACK)
            pauses.remove(ConvPause.CALL)
            pauses.remove(ConvPause.MIC_TAKEN)
            resumeIfFree()
        }
    }

    private fun inCall(): Boolean = try {
        when (app.getSystemService(AudioManager::class.java).mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ lines

    private val output = object : ConversationEngine.Output {
        /** Segments in a row the speech model couldn't decode (it failed to load, or went away). */
        private var unusable = 0

        override fun segment(r: SegmentHeard) {
            if (r.decodeMs < 0) {
                if (++unusable >= 3 && running) {
                    _state.update { it.copy(notice = "Speech recognition stopped working. What was heard before is saved.") }
                    stop(ConvStop.ERROR)
                }
                return
            }
            unusable = 0
            if (r.heard.text.isBlank() || discarded) {
                _state.update { it.copy(partial = "") }
                return
            }
            val now = System.currentTimeMillis()
            val saved = synchronized(writerLock) {
                val w = writer ?: return
                if (discarded) return
                // Labelled just before you merged that speaker: it lands as the merged one (the file would read it so too).
                val label = resolve(r.speaker?.label ?: ConversationRules.SPEAKER)
                val l = w.line(now) { i -> ConversationLine.make(i, r.offsetS, r.seg, r.heard, r.guess, r.lang, r.reheard, speaker = label) }
                if (l == null) w.failed?.let { saveFailed(it) }
                l
            } ?: return
            engine?.stats?.line(r.lang)
            speakers?.heard(saved.i, saved.speaker, r.lang, r.voice)
            val line = TranscriptLine(saved.i, saved.t0, saved.t1, saved.speaker, saved.lang, saved.text, saved.lid, saved.prior, saved.words, saved.reheard)
            _state.update {
                it.copy(lines = (it.lines + line).takeLast(ConversationRules.LIVE_LINES), total = it.total + 1, partial = "",
                    speakers = if (line.speaker in it.speakers) it.speakers else it.speakers + line.speaker)
            }
        }

        override fun partial(text: String) {
            _state.update { if (it.status == Status.RUNNING) it.copy(partial = text) else it }
        }

        /** Decoding fell too far behind and a segment was let go: the transcript says so where it was ("[not transcribed …]"). */
        override fun dropped(seg: Segment, offsetS: Double) {
            RLog.i("conv_dropped", "waiting" to ConversationEngine.MAX_WAITING)
            ctl.post {
                val w = writer ?: return@post
                val t0 = offsetS + seg.speechStart.toDouble() / Pcm.RATE
                val t1 = offsetS + seg.speechEnd.toDouble() / Pcm.RATE
                synchronized(writerLock) { if (!discarded) w.event(TranscriptEvent.Gap(t0, t1, TranscriptEvent.GAP_BEHIND)) }
            }
        }
    }

    /** A line couldn't be saved (worker thread): deleted under us stops quietly, a full disk stops, anything else is logged. */
    private fun saveFailed(code: IoCode) {
        if (code != lastSaveFail) VoiceLog.add(VoiceEvent.CONV_SAVE_FAIL, code)
        lastSaveFail = code
        when (code) {
            IoCode.MISSING -> {
                discarded = true
                stop(ConvStop.USER)
            }
            IoCode.NO_SPACE -> stop(ConvStop.DISK)
            else -> {}
        }
    }

    /** You tapped "FR?" and chose the language: an event in the log; the next short lines lean on it. */
    fun setLang(i: Int, lang: String) = ctl.post {
        val w = writer ?: return@post
        val ok = synchronized(writerLock) { !discarded && w.event(TranscriptEvent.Lang(i, lang)) == null }
        if (!ok) return@post
        engine?.corrected(lang)
        _state.value.lines.firstOrNull { it.i == i }?.let { l -> speakers?.corrected(l.speaker, lang) }
        _state.update { st -> st.copy(lines = st.lines.map { if (it.i == i) it.copy(lang = lang, prior = false, translations = emptyMap()) else it }) }
    }

    // ------------------------------------------------------------------ speakers (phase 5.3)

    private fun resolve(label: String): String {
        var s = label
        repeat(16) { s = merged[s] ?: return s }
        return s
    }

    /** You named [speaker] (empty: back to the label). Kept in this transcript only. */
    fun rename(speaker: String, name: String) = edit { t, _ -> TranscriptEdits.rename(t, speaker, name) }

    /** "[from] is [to]": their lines are one person's, and so are later lines that sound like either. */
    fun merge(from: String, to: String) = edit { t, _ -> TranscriptEdits.merge(t, from, to) }

    /** "This is me". */
    fun thisIsMe(speaker: String) = edit { t, _ -> TranscriptEdits.me(t, speaker) }

    /** Line [i] was [speaker]'s (one of the transcript's, or a new label for someone else). */
    fun move(i: Int, speaker: String) = edit { t, _ -> TranscriptEdits.move(t, i, speaker) }

    /** Line [i] splits at character [at]; the rest is [speaker]'s (null: the same). */
    fun split(i: Int, at: Int, speaker: String?) = edit { t, n -> TranscriptEdits.split(t, i, at, n, speaker) }

    /** "Not the same person": the proposal goes, and isn't made again in this session. */
    fun decline(from: String, to: String) = ctl.post {
        speakers?.decline(from, to)
        _state.update { it.copy(proposal = nextProposal()) }
    }

    private fun nextProposal(): Pair<String, String>? = speakers?.proposals()?.firstOrNull()

    /**
     * Control thread: appends your change through the session's writer (checked against the transcript as it stands),
     * then the session's speakers and the screen follow. Nothing when the session ended or the change isn't allowed.
     */
    private fun edit(make: (Transcript, Int) -> TranscriptEvent?) = ctl.post {
        val w = writer ?: return@post
        val sp = speakers
        val (e, t) = synchronized(writerLock) {
            if (discarded) return@post
            val e = w.edit(make) ?: run {
                w.failed?.let { saveFailed(it) }
                return@post
            }
            // In step with the file before the next line is written: a line labelled with the old speaker lands merged.
            if (e is TranscriptEvent.Merge) {
                merged = merged + (e.from to e.to)
                sp?.merge(e.from, e.to)
            }
            e to w.current()
        }
        when (e) {
            is TranscriptEvent.Speaker -> sp?.moved(e.i, e.speaker)
            else -> {}
        }
        _state.update { st ->
            val shown = st.lines.map { it.i }.toSet() + ((e as? TranscriptEvent.Split)?.n?.let { setOf(it) } ?: emptySet())
            st.copy(
                // The lines on screen as the file now reads them (a newer line not folded here stays as it was).
                lines = (st.lines.map { t.line(it.i) ?: it } + listOfNotNull((e as? TranscriptEvent.Split)?.let { s -> t.line(s.n) }))
                    .filter { it.i in shown }.distinctBy { it.i }.sortedWith(compareBy<TranscriptLine> { it.t0 }.thenBy { it.i }),
                speakers = t.speakers, names = t.names, me = t.me, proposal = nextProposal(),
            )
        }
    }

    /** Your voiceprint was deleted (Settings): a running session stops comparing with it at once. */
    fun voiceprintDeleted() {
        if (!::app.isInitialized) return
        ctl.post { speakers?.forgetMe() }
    }

    // ------------------------------------------------------------------ the tick (control thread, every second)

    private fun onTick() {
        val w = writer ?: return
        checkAudio()
        val nowE = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        if (nowE - lastBeat >= ConversationRules.BEAT_MS) {
            lastBeat = nowE
            if (!discarded && !w.beat(now) && !Transcripts.store(app).exists(w.id)) {
                discarded = true
                return stopNow(ConvStop.USER)
            }
        }
        if (nowE - lastMinute >= 60_000) {
            lastMinute = nowE
            engine?.stats?.take()?.let { (m, l) ->
                VoiceLog.add(VoiceEvent.CONV_MINUTE, m.first, m.second, m.third)
                VoiceLog.add(VoiceEvent.CONV_LANG, l.first, l.second, l.third)
            }
            // The notification's line count (it never shows words).
            ConversationNotices.changed(app)
            // Two speakers who have come to sound alike are offered as a merge (never merged by themselves).
            if (speakers?.enabled == true) {
                val p = nextProposal()
                _state.update { if (it.proposal == p) it else it.copy(proposal = p) }
            }
        }
        if (nowE - lastDisk >= 60_000) {
            lastDisk = nowE
            val free = Transcripts.store(app).freeBytes()
            if (ConversationRules.diskFull(free)) return stopNow(ConvStop.DISK)
            if (!longNoticed && now - w.meta.createdAt >= ConversationRules.LONG_NOTICE_MS) {
                longNoticed = true
                val mb = if (free >= 0) ", ${free / (1024 * 1024)} MB free" else ""
                _state.update { it.copy(notice = "Still transcribing — ${(now - w.meta.createdAt) / 3_600_000} h$mb.") }
                ConversationNotices.changed(app)
            }
        }
        updatePartials()
    }

    // ------------------------------------------------------------------ screen and heat

    fun screen(on: Boolean) {
        if (!::app.isInitialized) return
        ctl.post {
            screenOn = on
            updatePartials()
        }
    }

    /** A transcript screen came to the front (true) or left it. */
    fun watching(on: Boolean) {
        watching = (watching + if (on) 1 else -1).coerceAtLeast(0)
        if (::app.isInitialized) ctl.post { updatePartials() }
    }

    private fun updatePartials() {
        val e = engine ?: return
        e.partials = watching > 0 && ConversationSettings.partials(VoiceHub.settings, screenOn, warm)
        if (!e.partials) _state.update { if (it.partial.isEmpty()) it else it.copy(partial = "") }
    }

    private fun watchThermal() {
        val pm = app.getSystemService(PowerManager::class.java)
        val l = PowerManager.OnThermalStatusChangedListener { status -> ctl.post { onThermal(status) } }
        try {
            pm.addThermalStatusListener(app.mainExecutor, l)
            thermal = l
        } catch (t: Throwable) { RLog.w("conv_thermal", t) }
    }

    private fun unwatchThermal() {
        thermal?.let { try { app.getSystemService(PowerManager::class.java).removeThermalStatusListener(it) } catch (_: Throwable) {} }
        thermal = null
        warm = false
    }

    /** Heat (docs/conversation-mode.md 1.4): live words off at MODERATE, a pause at CRITICAL, back when it cools. */
    private fun onThermal(status: Int) {
        if (writer == null) return
        val nowWarm = status >= PowerManager.THERMAL_STATUS_MODERATE
        val hot = status >= PowerManager.THERMAL_STATUS_CRITICAL
        val step = when {
            hot && ConvPause.THERMAL !in pauses -> ThermalStep.PAUSED
            nowWarm && !warm -> ThermalStep.PARTIALS_OFF
            !nowWarm && warm || (!hot && ConvPause.THERMAL in pauses) -> ThermalStep.NORMAL
            else -> null
        }
        warm = nowWarm
        if (hot) {
            if (ConvPause.THERMAL !in pauses) _state.update { it.copy(notice = "Paused: the phone is too hot. It goes on when it cools down.") }
            pauseNow(ConvPause.THERMAL)
        } else if (ConvPause.THERMAL in pauses) {
            pauses.remove(ConvPause.THERMAL)
            _state.update { it.copy(notice = null) }
            resumeIfFree()
        }
        updatePartials()
        step?.let { VoiceLog.add(VoiceEvent.CONV_THERMAL, status, it.id) }
    }

    /** "Open" in the notification or the tile while one runs: the transcript screen. */
    fun openScreen(ctx: Context) {
        try {
            ctx.startActivity(Intent(Transcripts.ACTION_OPEN).setClassName(ctx.packageName, Transcripts.ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { RLog.w("conv_open", t) }
    }
}
