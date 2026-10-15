// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

// Pure (java.io). Where transcripts live on the phone (docs/conversation-mode.md 6.1): filesDir/transcripts/<id>/, in
// the app's credential-encrypted storage, excluded from backup and device transfer like everything else of Robot's.
// One writer per transcript at a time: the :voice process while a session records it, the main app (rename, delete)
// otherwise. Each line heard is appended to lines.jsonl and fsynced; meta.json and snapshot.json are replaced
// atomically. Nothing here ever creates a transcript's folder except [TranscriptStore.create]: a session that
// outlives a delete (the main app deleted its transcript, or all of them) gets MISSING back and stops, and can't write
// the deleted transcript back. A delete first renames the folder out of the way (one atomic step), then removes it.

/** A session closed by [TranscriptStore.recover]: what to say about it. */
data class Recovered(val id: String, val action: SessionRecovery.Action, val lines: Int, val minutes: Int, val savedUpTo: Long)

class TranscriptStore(val root: File) {
    /** Transcripts deleted while this process runs: never written again, whatever arrives late. */
    private val forgotten = java.util.Collections.synchronizedSet(HashSet<String>())

    fun dir(id: String): File? = if (TranscriptIds.valid(id)) File(root, id) else null

    fun exists(id: String): Boolean = !forgotten.contains(id) && dir(id)?.isDirectory == true

    /** A new transcript: its folder, meta.json and an empty log. False if it exists or can't be made. */
    @Synchronized
    fun create(meta: TranscriptMeta): Boolean {
        val d = dir(meta.id) ?: return false
        if (d.exists() || meta.id in forgotten) return false
        return try {
            root.mkdirs()
            if (!d.mkdir()) return false
            if (writeIn(d, TranscriptFiles.META, meta.toJson()) != null) return false
            File(d, TranscriptFiles.LOG).createNewFile()
            TranscriptEvents.publish(TranscriptChange.Created(meta.id))
            true
        } catch (_: Exception) { false }
    }

    /** Appends one event to [id]'s log and fsyncs it; null when saved, else why not. Never creates the folder. */
    @Synchronized
    fun append(id: String, e: TranscriptEvent): IoCode? {
        if (id in forgotten) return IoCode.MISSING
        val d = dir(id) ?: return IoCode.OTHER
        if (!d.isDirectory) return IoCode.MISSING
        val log = File(d, TranscriptFiles.LOG)
        return try {
            val record = TranscriptCodec.encode(e)
            require('\n' !in record)
            // A crash that cut the last line off: a line break first, so the torn line stays alone (and is skipped).
            val torn = log.isFile && log.length() > 0 && RandomAccessFile(log, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
            FileOutputStream(log, true).use { out ->
                val before = out.channel.size()
                try {
                    out.write(((if (torn) "\n" else "") + record + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                } catch (x: Exception) {
                    // Not saved (a full disk, an I/O error): what got in is taken back out, so the log holds no half
                    // record and no line the session believes lost, and the session's count of records stays the file's.
                    try { out.channel.truncate(before); out.fd.sync() } catch (_: Exception) {}
                    throw x
                }
            }
            null
        } catch (x: Exception) { code(x, d) }
    }

    fun meta(id: String): TranscriptMeta? = dir(id)?.let { readOrNull(File(it, TranscriptFiles.META)) }?.let(TranscriptMeta::fromJson)?.takeIf { it.id == id }

    /** Replaces meta.json; never creates the folder. */
    @Synchronized
    fun writeMeta(meta: TranscriptMeta): IoCode? {
        if (meta.id in forgotten) return IoCode.MISSING
        val d = dir(meta.id) ?: return IoCode.OTHER
        return writeIn(d, TranscriptFiles.META, meta.toJson()).also { if (it == null) TranscriptEvents.publish(TranscriptChange.Updated(meta.id)) }
    }

    /** The transcript as it stands: the snapshot, then the log after it. Null when it doesn't exist. */
    fun load(id: String): Transcript? {
        val d = dir(id)?.takeIf { it.isDirectory } ?: return null
        return TranscriptCodec.load(readOrNull(File(d, TranscriptFiles.SNAPSHOT)), readOrNull(File(d, TranscriptFiles.LOG)))
    }

    @Synchronized
    fun writeSnapshot(id: String, t: Transcript): IoCode? {
        if (id in forgotten) return IoCode.MISSING
        val d = dir(id) ?: return IoCode.OTHER
        return writeIn(d, TranscriptFiles.SNAPSHOT, TranscriptCodec.snapshot(t))
    }

    /** Every transcript whose meta.json reads, newest first. */
    fun list(): List<TranscriptMeta> = root.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) && f.name !in forgotten }.orEmpty()
        .mapNotNull { meta(it.name) }
        .sortedByDescending { it.createdAt }

    /** You renamed it (an empty title: back to the date). */
    @Synchronized
    fun rename(id: String, title: String): Boolean {
        val m = meta(id) ?: return false
        return writeMeta(m.copy(title = TranscriptTitles.clean(title))) == null
    }

    /**
     * [MAIN] Your change to a transcript nobody is recording (phase 5.3: a name, a merge, "this is me", a line moved or
     * split): [make] gets the transcript as it stands and the next line index never used, and gives the event (null:
     * nothing to do). It is appended, and meta.json's speakers and open proposals follow. The event saved, or null.
     */
    @Synchronized
    fun edit(id: String, make: (Transcript, Int) -> TranscriptEvent?): TranscriptEvent? {
        if (id in forgotten) return null
        val d = dir(id)?.takeIf { it.isDirectory } ?: return null
        val t = load(id) ?: return null
        val e = make(t, TranscriptWriter.maxIndex(readOrNull(File(d, TranscriptFiles.LOG)) ?: "") + 1) ?: return null
        if (append(id, e) != null) return null
        meta(id)?.let { m ->
            val after = TranscriptFold(t).apply(e).result()
            val next = (if (e is TranscriptEvent.Merge) m.without(e.from, e.to) else m).withSpeakers(after)
            if (next != m) writeMeta(next)
        }
        TranscriptEvents.publish(TranscriptChange.Updated(id))
        return e
    }

    /** [MAIN] "Not the same person": the proposal to merge [from] and [to] goes from meta.json. */
    @Synchronized
    fun decline(id: String, from: String, to: String): Boolean {
        val m = meta(id) ?: return false
        return writeMeta(m.without(from, to)) == null
    }

    // ------------------------------------------------------------------ summaries (phase 5.6)

    /** [MAIN] You marked it important (the stronger AI is offered first for its summary), or not. */
    @Synchronized
    fun setImportant(id: String, on: Boolean): Boolean {
        val m = meta(id) ?: return false
        return m.important == on || writeMeta(m.copy(important = on)) == null
    }

    /** [id]'s summaries (summary-<n>.json: Robot's own, or a helper's), oldest first, with their numbers. */
    fun summaries(id: String): List<Pair<Int, SummaryRecord>> {
        if (id in forgotten) return emptyList()
        val d = dir(id)?.takeIf { it.isDirectory } ?: return emptyList()
        return d.listFiles().orEmpty().mapNotNull { f -> SUMMARY.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to f } }
            .sortedBy { it.first }.mapNotNull { (n, f) -> SummaryRecord.fromJson(readOrNull(f))?.let { n to it } }
    }

    /** Saves [r] as [id]'s next summary (atomically); its number, or null. Never creates the folder. */
    @Synchronized
    fun addSummary(id: String, r: SummaryRecord): Int? {
        if (id in forgotten) return null
        val d = dir(id)?.takeIf { it.isDirectory } ?: return null
        val n = (d.listFiles().orEmpty().mapNotNull { SUMMARY.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        if (writeIn(d, TranscriptFiles.summary(n), r.toJson()) != null) return null
        TranscriptEvents.publish(TranscriptChange.Updated(id))
        return n
    }

    @Synchronized
    fun deleteSummary(id: String, n: Int): Boolean {
        val d = dir(id)?.takeIf { it.isDirectory } ?: return false
        return File(d, TranscriptFiles.summary(n)).delete().also { if (it) TranscriptEvents.publish(TranscriptChange.Updated(id)) }
    }

    /** The notes of Robot's summary under way for [id] (kept so a stopped one goes on), or null. */
    fun draft(id: String): SummaryDraft? = dir(id)?.takeIf { it.isDirectory && id !in forgotten }?.let { SummaryDraft.fromJson(readOrNull(File(it, SummaryDraft.FILE))) }

    @Synchronized
    fun saveDraft(id: String, d: SummaryDraft): IoCode? {
        if (id in forgotten) return IoCode.MISSING
        val f = dir(id) ?: return IoCode.OTHER
        return writeIn(f, SummaryDraft.FILE, d.toJson())
    }

    @Synchronized
    fun clearDraft(id: String) {
        dir(id)?.let { File(it, SummaryDraft.FILE).delete() }
    }

    /** Bytes on storage (the folder's files). */
    fun bytes(id: String): Long = dir(id)?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    /**
     * Deletes [id]: the folder is renamed out of the way in one step (a late write can't find it), then removed. Also
     * forgets it for this process. True if it is gone. Deleting on flash storage is not a secure erase.
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val d = dir(id) ?: return false
        forgotten += id
        val gone = if (!d.exists()) true else {
            val trash = File(root, "$TRASH$id-${System.nanoTime()}")
            val moved = d.renameTo(trash)
            removeAll(if (moved) trash else d)
            !d.exists()
        }
        if (heartbeat()?.id == id) clearBeat(id)
        TranscriptEvents.publish(TranscriptChange.Deleted(id))
        return gone
    }

    /** Deletes every transcript; returns how many there were. */
    @Synchronized
    fun deleteAll(): Int {
        val ids = root.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) }.orEmpty().map { it.name }
        ids.forEach { delete(it) }
        purgeTrash()
        clearBeat()
        return ids.size
    }

    /** Folders a delete couldn't finish (a write raced it): removed now. */
    fun purgeTrash() {
        root.listFiles { f -> f.name.startsWith(TRASH) }.orEmpty().forEach { removeAll(it) }
    }

    /**
     * The main app deleted [id] while this process runs: nothing of it is written again. (After "Delete all" there is
     * nothing to list: the folders still there are ones made after it, which must go on being written.)
     */
    fun forget(id: String) {
        forgotten += id
    }

    fun forgotten(id: String): Boolean = id in forgotten

    private fun removeAll(f: File) {
        // A file written into it meanwhile makes the folder's removal fail once: try again.
        repeat(3) { if (!f.exists() || f.deleteRecursively()) return }
    }

    // ------------------------------------------------------------------ the running session's heartbeat

    private val beatFile: File get() = File(root, SessionHeartbeat.FILE)

    fun heartbeat(): SessionHeartbeat? = SessionHeartbeat.fromJson(readOrNull(beatFile))

    /** Written by the :voice process only, every 30 s while a session runs. */
    @Synchronized
    fun beat(hb: SessionHeartbeat): Boolean {
        if (hb.id in forgotten || dir(hb.id)?.isDirectory != true) return false
        return writeIn(root, SessionHeartbeat.FILE, hb.toJson()) == null
    }

    /** Removes the heartbeat ([id]: only if it names that transcript). */
    @Synchronized
    fun clearBeat(id: String? = null) {
        if (id == null || heartbeat()?.id == id) beatFile.delete()
    }

    /**
     * When the :voice process starts (docs/conversation-mode.md 1.6): a heartbeat with no service holding its session
     * means the process died under it. The transcript is closed, as interrupted (Robot offers Resume) or, after your
     * own "Stop" in Active apps, as ended. A heartbeat for a transcript that no longer exists is just removed. Null
     * when there was nothing to recover.
     */
    @Synchronized
    fun recover(serviceRunning: Boolean, stoppedByUser: Boolean): Recovered? {
        purgeTrash()
        val beat = heartbeat()
        val action = SessionRecovery.decide(beat, serviceRunning, stoppedByUser)
        if (beat == null || action == SessionRecovery.Action.NONE) return null
        val meta = meta(beat.id)
        if (meta == null || !exists(beat.id)) {
            clearBeat()
            return null
        }
        val t = load(beat.id) ?: Transcript()
        val upTo = SessionRecovery.savedUpTo(beat).coerceAtLeast(meta.createdAt)
        val status = if (action == SessionRecovery.Action.CLOSE) SessionStatus.ENDED else SessionStatus.INTERRUPTED
        writeMeta(meta.copy(status = status, endedAt = upTo).withSpeakers(t))
        writeSnapshot(beat.id, t)
        clearBeat()
        return Recovered(beat.id, action, t.lines.size, ((upTo - meta.createdAt) / 60_000).toInt(), upTo)
    }

    /** Free space where transcripts are written, or -1 if unknown. */
    fun freeBytes(): Long = try { (if (root.isDirectory) root else root.parentFile ?: root).usableSpace } catch (_: Exception) { -1 }

    private fun writeIn(d: File, name: String, text: String): IoCode? {
        if (!d.isDirectory) return IoCode.MISSING
        val tmp = File(d, ".$name.tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(File(d, name))) {
                tmp.delete()
                if (!d.isDirectory) IoCode.MISSING else IoCode.WRITE
            } else null
        } catch (x: Exception) {
            tmp.delete()
            code(x, d)
        }
    }

    // MISSING only when the folder is gone (deleted): the session then stops as if you deleted it and never closes the
    // file. A file that can't be opened in a folder that is there (no space to create it, too many open files) is not that.
    private fun code(x: Exception, d: File): IoCode = when {
        !d.isDirectory -> IoCode.MISSING
        (x.message ?: "").contains("ENOSPC") || (x.message ?: "").contains("No space", ignoreCase = true) -> IoCode.NO_SPACE
        x is IOException -> IoCode.WRITE
        else -> IoCode.OTHER
    }

    companion object {
        private val SUMMARY = Regex("""summary-(\d{1,6})\.json""")

        /** A deleted transcript's folder on its way out. */
        const val TRASH = ".trash-"
    }
}

/**
 * The live session's side of one transcript (:voice process, one per session): gives each line its index (never
 * reused, also after a resume), appends every event, keeps its own fold for snapshot.json (every 200 events and at the
 * end) and the heartbeat's numbers. Not thread-safe by itself: the session calls it from one thread at a time.
 */
class TranscriptWriter private constructor(val store: TranscriptStore, meta: TranscriptMeta, start: Transcript, private var next: Int) {
    var meta: TranscriptMeta = meta
        private set
    val id: String get() = meta.id
    private val fold = TranscriptFold(start)
    private var sinceSnapshot = 0
    /** Lines in the transcript (resumed ones included). */
    var lines: Int = start.lines.size
        private set
    /** Wall clock of the last line saved (0: none yet). */
    var lastLineAt: Long = 0
        private set
    /** The last save failed with this (null: fine). MISSING means the transcript was deleted: the session stops. */
    var failed: IoCode? = null
        private set

    /** The transcript so far (this writer's fold). */
    fun current(): Transcript = fold.result()

    /**
     * Saves the line made with the next index; returns it, or null when it couldn't be saved ([failed] says why). The
     * index is used up either way: should a failed write have reached the file after all, the next line can't share it.
     */
    fun line(now: Long, make: (Int) -> TranscriptEvent.Line): TranscriptEvent.Line? {
        val l = make(next)
        next = l.i + 1
        if (event(l) != null) return null
        lines++
        lastLineAt = now
        return l
    }

    /** Appends [e]; null when saved. */
    fun event(e: TranscriptEvent): IoCode? {
        val r = store.append(id, e)
        failed = r
        if (r != null) return r
        fold.apply(e)
        if (e is TranscriptEvent.Split) next = maxOf(next, e.n + 1)
        if (++sinceSnapshot >= TranscriptFiles.SNAPSHOT_EVERY) snapshot()
        return null
    }

    fun snapshot(): IoCode? {
        sinceSnapshot = 0
        return store.writeSnapshot(id, fold.result())
    }

    /** The heartbeat (every 30 s). False when the transcript is gone. */
    fun beat(now: Long): Boolean = store.beat(SessionHeartbeat(id, meta.mode, meta.createdAt, now, lastLineAt, lines))

    /** Seconds from the session's start to [now] (wall clock): where a run that begins now starts on the transcript's clock. */
    fun clock(now: Long): Double = ((now - meta.createdAt) / 10).coerceAtLeast(0) / 100.0

    /**
     * Your change during the session (phase 5.3): [make] gets the transcript so far and the next line index, and gives
     * the event (null: nothing to do). Appended like a line; the event, or null when there was nothing to save or it
     * couldn't be saved ([failed] says why).
     */
    fun edit(make: (Transcript, Int) -> TranscriptEvent?): TranscriptEvent? {
        val e = make(current(), next) ?: return null
        return if (event(e) == null) e else null
    }

    /**
     * The session ends: meta.json says so (with the speakers, and [similar]: those who sounded alike, offered as merges),
     * the snapshot is written, the heartbeat goes.
     */
    fun close(now: Long, status: SessionStatus = SessionStatus.ENDED, similar: List<Pair<String, String>> = emptyList()): IoCode? {
        val m = meta.copy(status = status, endedAt = now, similar = (meta.similar + similar).distinct()).withSpeakers(fold.result())
        val r = store.writeMeta(m)
        if (r == null) meta = m
        snapshot()
        store.clearBeat(id)
        return r
    }

    companion object {
        /** A new transcript for a session started at [now]. Null if it couldn't be made. */
        fun start(store: TranscriptStore, id: String, now: Long, mode: ConvMode, pair: LangPair): TranscriptWriter? {
            val meta = TranscriptMeta(id, "", now, status = SessionStatus.RUNNING, mode = mode, pair = pair,
                speakers = listOf(SpeakerInfo(ConversationRules.SPEAKER)))
            if (!store.create(meta)) return null
            return TranscriptWriter(store, meta, Transcript(), 0).also { it.beat(now) }
        }

        /**
         * Goes on with [id] after it stopped unexpectedly (Resume): RUNNING again, a gap marker for the time lost, and
         * new lines numbered after every index the log ever used. Null if it no longer exists.
         */
        fun resume(store: TranscriptStore, id: String, now: Long): TranscriptWriter? {
            val meta = store.meta(id) ?: return null
            val d = store.dir(id)?.takeIf { it.isDirectory } ?: return null
            val log = readOrNull(File(d, TranscriptFiles.LOG)) ?: ""
            val t = store.load(id) ?: return null
            val running = meta.copy(status = SessionStatus.RUNNING, endedAt = 0)
            if (store.writeMeta(running) != null) return null
            val w = TranscriptWriter(store, running, t, maxIndex(log) + 1)
            // The next append ends a cut-off last line first, which then counts as one (skipped) record.
            if (t.torn > 0) w.fold.apply(null)
            val from = maxOf(t.lines.maxOfOrNull { it.t1 } ?: 0.0, t.gaps.maxOfOrNull { it.t1 } ?: 0.0,
                if (meta.endedAt > meta.createdAt) (meta.endedAt - meta.createdAt) / 1000.0 else 0.0)
            val to = w.clock(now)
            if (to > from) w.event(TranscriptEvent.Gap(from, to, TranscriptEvent.GAP_CRASH))
            w.beat(now)
            return w
        }

        /** The highest line index the log ever gave (lines and splits; -1 for none), so a resumed session never reuses one. */
        fun maxIndex(log: String): Int {
            var max = -1
            for (r in TranscriptCodec.records(log).first) when (val e = TranscriptCodec.decode(r)) {
                is TranscriptEvent.Line -> max = maxOf(max, e.i)
                is TranscriptEvent.Split -> max = maxOf(max, e.n)
                else -> {}
            }
            return max
        }
    }
}
