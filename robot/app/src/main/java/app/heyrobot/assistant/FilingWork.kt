// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.files.TravelMode
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.heyrobot.assistant.backup.RestoreSession
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.RLog
import app.heyrobot.core.SummaryBudget
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileFiledCode
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileRemind
import app.heyrobot.core.files.FileReminders
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileParseCode
import app.heyrobot.core.files.FileReadCode
import app.heyrobot.core.files.FilingOutcome
import app.heyrobot.core.files.FilingPolicy
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.FilingPorts
import app.heyrobot.core.files.FilingRule
import app.heyrobot.core.files.FilingRun
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.ModelInfo
import app.heyrobot.core.files.NameWords
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.sys.RobotFiles
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// VOICE PROCESS. Robot Files' filing in the background (files-1.4-plan.md §4.1, job F6): the glue between core's FilingRun
// (every decision) and this process's vault, :files and the model. It runs on its own thread, never the main one, one file
// at a time, after each intake (Cmd.FILES), at the process's start and after each unlock; never while the phone is locked
// or during a phone lock (FilingRun checks before each file and each step, and the vault refuses anyway). A conversation in
// the panel goes first: FILE_READ waits behind it in :brain (Priority.SUMMARY), with summaries' generous time. The model is
// loaded only when a file needs it, held for the batch, and unloaded after Settings › Model's minutes as today.
//
// Each file is tried at most once per trigger, so a file that waits (a model that doesn't see, not now) never spins; the
// next trigger tries it again. Nothing of a file is logged: codes and counts only (the voice log's 270 to 279).

object FilingWork {
    private const val MODEL_HOLD = "files"
    /** "File this": how long the panel waits for its file to be filed before saying it's in Inbox and going on. */
    const val NOW_MS = 150_000L
    /** After "not now" from the model (busy, cancelled), the next try. */
    private const val LATER_MS = 120_000L

    @Volatile private var app: Context? = null
    private val worker: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-filing").apply { isDaemon = true } } } // l10n: not text (a thread's name)
    @Volatile private var running = false
    @Volatile private var again = false
    @Volatile private var later: java.util.concurrent.ScheduledFuture<*>? = null
    private val ports by lazy { Ports() }
    private val run by lazy { FilingRun(ports) }
    /** What a batch filed and what waits for "File them", for its notifications. */
    private val done = ArrayList<FilingOutcome>()
    /** "File this" waiting its turn: taken before the next file of a batch, or at once when none runs. */
    private val now = java.util.concurrent.ConcurrentLinkedQueue<java.util.concurrent.FutureTask<FilingOutcome>>()
    @Volatile private var inBatch = false

    /** At :voice's start (VoiceHub.init): the unlock hook, then a first look at Inbox. */
    fun start(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        try {
            ProtectedBroadcasts.register(ctx.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_USER_PRESENT) kick()
                }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT))
        } catch (t: Throwable) { RLog.w("filing_hooks", t) }
        kick()
    }

    /** Files what waits in Inbox, on the filing thread (a new file came in, the phone was unlocked, a model is ready). */
    fun kick() {
        if (app == null) return
        synchronized(this) {
            again = true
            if (running) return
            running = true
        }
        worker.execute(::loop)
    }

    private fun loop() {
        try {
            while (true) {
                synchronized(this) {
                    if (!again) { running = false; return }
                    again = false
                }
                // F11: a restore's Robot Files part that a lock left committed is finished first (never while one is written).
                app?.let { RestoreSession.afterUnlock(it) }
                batch()
                rethumbs()
            }
        } catch (t: Throwable) {
            RLog.w("filing_loop", t)
            synchronized(this) { running = false }
        }
    }

    /** One pass over Inbox: every file Robot's to file, oldest first, each once. */
    private fun batch() {
        val ctx = app ?: return
        if (ports.locked()) return
        val index = (RobotFiles.vault(ctx).load() as? IndexLoad.Loaded)?.index ?: return
        // F8: the panel's "move it to …" checks a drawer exists against these names; F12: as travel mode shows them.
        PanelFiles.seen(TravelMode.shown(index, FilesSettingsStore.current(ctx)))
        // A file waiting for a model that can read it is tried again only when the model loaded now could: otherwise every
        // unlock would decode it again and load a model for nothing.
        val queue = run.queue(index, Int.MAX_VALUE).filter { id -> index.entry(id)?.filing?.let { f -> f.step != FilingStep.WAIT || couldRead(f.why) } != false }
        if (queue.isEmpty()) return
        synchronized(done) { done.clear() }
        inBatch = true
        try {
            for (id in queue) {
                drainNow()
                val o = fileOne(id, yourWords = false)
                synchronized(done) { done += o }
                when {
                    o == FilingOutcome.Locked || o == FilingOutcome.Stop -> break
                    // The model said "not now" (busy, cancelled): the rest wait for the next try, in a while.
                    o is FilingOutcome.Waiting && o.why == null && !o.counted -> { retryLater(); break }
                }
            }
        } finally {
            inBatch = false
            drainNow()
            ports.endBatch()
            val outcomes = synchronized(done) { done.toList().also { done.clear() } }
            try { FilingNotices.batch(ctx, outcomes) } catch (t: Throwable) { RLog.w("filing_notice", t) }
        }
    }

    /**
     * F11: thumbnails a restore asks for again (a backup carries none), a batch at a time in :files, each tried once; never
     * while locked. More waiting: the next pass, once this one went through.
     */
    private fun rethumbs() {
        val ctx = app ?: return
        if (ports.locked()) return
        val index = (RobotFiles.vault(ctx).load() as? IndexLoad.Loaded)?.index ?: return
        val ids = run.rethumbs(index)
        if (ids.isEmpty()) return
        var all = true
        try {
            for (id in ids) if (!run.rethumb(id)) { all = false; break }
        } catch (t: Throwable) {
            RLog.w("filing_rethumb", t)
            all = false
        } finally {
            ports.endBatch()
        }
        if (all && ids.size == FilingPolicy.BATCH) kick()
    }

    /** Whether the model loaded now could read a file waiting for [why]. */
    private fun couldRead(why: FilingWhy?): Boolean {
        val st = BrainKeeper.state.value
        return when (why) {
            FilingWhy.NO_VISION -> BrainKeeper.ready && st.vision && !st.textOnly
            FilingWhy.SMALL_CONTEXT -> BrainKeeper.ready && st.contextTokens >= FilingPolicy.MIN_PICTURE_CONTEXT
            FilingWhy.NO_MODEL -> BrainKeeper.ready
            else -> true
        }
    }

    private fun retryLater() {
        if (later?.isDone == false) return
        later = worker.schedule({ kick() }, LATER_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * "File this" (the panel's own words, §3.3): [id] filed now, ahead of the queue, Ask me first not waiting. Blocks up to
     * [NOW_MS]; null when it didn't finish in time (it goes on, and the background notices it as any other).
     */
    fun fileNow(ctx: Context, id: FileId, waitMs: Long = NOW_MS): FilingOutcome? {
        if (app == null) start(ctx)
        val f = java.util.concurrent.FutureTask { fileOne(id, yourWords = true) }
        now += f
        // Taken by a batch running now before its next file, else on its own.
        worker.execute { drainNow(); if (!inBatch) ports.endBatch() }
        return try {
            f.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (t: Exception) {
            RLog.w("filing_now", t)
            null
        }
    }

    private fun drainNow() {
        while (true) (now.poll() ?: return).run()
    }

    /** One file, with its voice-log lines: codes and counts only. */
    private fun fileOne(id: FileId, yourWords: Boolean): FilingOutcome {
        val t0 = SystemClock.elapsedRealtime()
        val o = try { run.file(id, yourWords) } catch (t: Throwable) { RLog.w("filing_file", t); FilingOutcome.Skipped }
        val tr = run.trace
        if (tr.parsed != null || tr.parseFailed != null)
            VoiceLog.add(VoiceEvent.FILE_PARSE, FileParseCode.of(tr.parsed, tr.parseFailed).id, if (tr.picture) 1 else 0, tr.traps + tr.rejected)
        if (tr.model) tr.read?.let { VoiceLog.add(VoiceEvent.FILE_READ, FileReadCode.of(it).id, if (tr.picture) 1 else 0, ((SystemClock.elapsedRealtime() - t0) / 1000).toInt()) }
        val kind = when (o) {
            is FilingOutcome.Filed -> o.placement.kind.ordinal + 1
            is FilingOutcome.Asked -> o.entry.kind.ordinal + 1
            is FilingOutcome.Held -> o.entry.kind.ordinal + 1
            else -> 0
        }
        VoiceLog.add(VoiceEvent.FILE_FILED, FileFiledCode.of(o).id, kind, FileFiledCode.why(o))
        if (o is FilingOutcome.Filed) briefOnItsOwn(id)
        RLog.i("filing", "outcome" to FileFiledCode.of(o).id, "ms" to (SystemClock.elapsedRealtime() - t0))
        return o
    }

    /**
     * F10, Reminders from files at "Add to my brief on its own" (files-1.4-plan.md §7.2, D11): a file just filed gets a brief
     * reminder for each of its dates coming up, kept in its sealed entry (core FileRemind). Never a calendar event: that is
     * always add_event's card, and a tap.
     */
    private fun briefOnItsOwn(id: FileId) {
        val ctx = app ?: return
        try {
            if (ports.settings().reminders != FileReminders.BRIEF) return
            val set = change(ctx) { idx -> FileRemind.autoAdd(idx, listOf(id), java.time.ZonedDateTime.now()).takeIf { it !== idx } }
            if (set) VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.REMIND.id, 1)
        } catch (t: Throwable) {
            RLog.w("filing_remind", t)
        }
    }

    /** A notification's action (Cmd.FILES_UNDO, Cmd.FILES_FILE), on the filing thread. */
    fun act(ctx: Context, op: String, token: String?) {
        if (app == null) start(ctx)
        worker.execute { try { FilingNotices.act(ctx.applicationContext, op, token) } catch (t: Throwable) { RLog.w("filing_act", t) } }
    }

    /** Undo, "File them" and Move: one change of the index through this process's vault (its lock gate); false when it didn't happen. */
    fun change(ctx: Context, change: (FileIndex) -> FileIndex?): Boolean {
        if (RobotFiles.locked(ctx)) return false
        var did = false
        val load = RobotFiles.vault(ctx).update { idx -> change(idx)?.also { did = true } ?: idx }
        return did && load.current != null
    }

    /**
     * Your filing rules (files-1.4-plan.md §4.5, F8), read from memory through sys/FilingMemory for each file, outside the
     * index's lock: a rule saved a moment ago (your words, a move in Files) applies to the next file. None while Memory is off.
     */
    fun rules(ctx: Context): List<FilingRule> = try {
        FilingMemory.rules(ctx)
    } catch (t: Exception) {
        RLog.w("filing_rules", t)
        emptyList()
    }

    /** The vault, :files and the model, as FilingRun asks for them. */
    private class Ports : FilingPorts {
        private val ctx: Context get() = app!!
        private var link: FileParseLink? = null
        private var holding = false

        override fun locked(): Boolean = RobotFiles.locked(ctx) || PhoneLockNow.on(ctx)
        override fun load(): IndexLoad = RobotFiles.vault(ctx).load()
        override fun update(change: (FileIndex) -> FileIndex): IndexLoad = RobotFiles.vault(ctx).update(change)
        override fun head(id: FileId, n: Int): ByteArray = RobotFiles.vault(ctx).open(id).use { it.readNBytes(n) }
        override fun bytes(id: FileId, max: Long): ByteArray = RobotFiles.vault(ctx).open(id).use { it.readAll(max) }

        override fun parse(bytes: ByteArray, flags: Int): ParseResult {
            val l = link ?: FileParseLink(ctx).also { if (!it.start()) { it.end("filing_start"); return ParseResult.Failed(ParseFailure.UNAVAILABLE) }; link = it } // l10n: not text (a log code)
            val r = l.read(bytes, flags)
            // A link that failed is gone: the next file gets a fresh :files.
            if (r is ParseResult.Failed) { l.end("filing_failed"); link = null } // l10n: not text (a log code)
            return r
        }

        override fun putThumb(id: FileId, jpeg: ByteArray): Boolean = try {
            RobotFiles.vault(ctx).putThumb(id, jpeg)
            true
        } catch (t: Exception) {
            RLog.w("filing_thumb", t)
            false
        }

        override fun model(): ModelInfo? {
            if (!holding) { BrainKeeper.hold(MODEL_HOLD); holding = true }
            runBlocking { BrainKeeper.ensureLoaded(ctx) } ?: return null
            val st = BrainKeeper.state.value
            return ModelInfo(vision = st.vision && !st.textOnly, contextTokens = st.contextTokens)
        }

        override fun read(req: BrainMsg.OneShot, jpeg: ByteArray?): OneShotResult = runBlocking {
            val budget = SummaryBudget.of(BrainKeeper.state.value.backend)
            if (jpeg != null) BrainOneShots.runWithImage(req, jpeg, budget) else BrainOneShots.run(req, budget) {}
        }

        override fun settings(): FilesSettings = FilesSettingsStore.current(ctx)
        /** F8: your filing rules, from memory (none while Memory is off), with the drawers that exist now. */
        override fun rules(): List<FilingRule> = FilingWork.rules(ctx)
        override fun today(): LocalDate = LocalDate.now()
        /** A bare "$" is Canada's on a phone set to Canada, the US's on one set to the US; otherwise unknown. */
        override fun dollar(): String? = when (Locale.getDefault().country) {
            "CA" -> "CAD" // l10n: not text (a currency code)
            "US" -> "USD" // l10n: not text (a currency code)
            else -> null
        }
        override fun words(): NameWords = NameWords(FilesStrings.nameDue().render(AppLanguage.langs(ctx)))
        override fun now(): Long = System.currentTimeMillis()

        /** A batch ended: :files ends, and the model may unload after its minutes. */
        fun endBatch() {
            link?.end("filing_done") // l10n: not text (a log code)
            link = null
            if (holding) { BrainKeeper.releaseHold(MODEL_HOLD); holding = false }
        }
    }
}
