// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
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

class TranscriptStore(val root: File, val seal: TranscriptSeal? = null) {
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
    fun append(id: String, e: TranscriptEvent): IoCode? = try { sealLock { appendNow(id, e) } } catch (_: Exception) { IoCode.WRITE }

    private fun appendNow(id: String, e: TranscriptEvent): IoCode? {
        if (id in forgotten) return IoCode.MISSING
        val d = dir(id) ?: return IoCode.OTHER
        if (!d.isDirectory) return IoCode.MISSING
        val log = File(d, TranscriptFiles.LOG)
        return try {
            // seal-transcripts: each record sealed on its own line, under the key the phone's state allows now.
            val record = TranscriptCodec.encode(e).let { r -> seal?.seal("$id/${TranscriptFiles.LOG}", r) ?: r }
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

    /**
     * 1.1 restore: a transcript folder a restore staged ([staged], a whole folder with its meta.json) moved into place in
     * one rename, on the same storage. False when [id] isn't a transcript id, is already here (Add leaves yours alone),
     * was deleted while this process runs, or the rename fails; a repeat of one that worked changes nothing.
     */
    @Synchronized
    fun restore(staged: File, id: String): Boolean {
        val d = dir(id) ?: return false
        if (d.exists() || id in forgotten || !File(staged, TranscriptFiles.META).isFile) return false
        if (!sealStaged(staged, id)) return false
        root.mkdirs()
        if (!staged.renameTo(d)) return false
        TranscriptEvents.publish(TranscriptChange.Created(id))
        return true
    }

    fun meta(id: String): TranscriptMeta? = dir(id)?.let { text(it, TranscriptFiles.META) }?.let(TranscriptMeta::fromJson)?.takeIf { it.id == id }

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
        // seal-transcripts: null too when it can't be opened now (its key needs the phone unlocked) or its key is lost.
        return try { TranscriptCodec.load(textOrThrow(d, TranscriptFiles.SNAPSHOT), logOrThrow(d)) } catch (_: MemoryKeyException) { null }
    }

    @Synchronized
    fun writeSnapshot(id: String, t: Transcript): IoCode? {
        if (id in forgotten) return IoCode.MISSING
        val d = dir(id) ?: return IoCode.OTHER
        return writeIn(d, TranscriptFiles.SNAPSHOT, TranscriptCodec.snapshot(t))
    }

    /** Every transcript whose meta.json reads, newest first. */
    fun list(): List<TranscriptMeta> = run { settle(); root.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) && f.name !in forgotten }.orEmpty()
        .mapNotNull { meta(it.name) }
        .sortedByDescending { it.createdAt } }

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
        val e = make(t, TranscriptWriter.maxIndex(logText(d) ?: return null) + 1) ?: return null
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
            .sortedBy { it.first }.mapNotNull { (n, f) -> SummaryRecord.fromJson(text(d, f.name))?.let { n to it } }
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
    fun draft(id: String): SummaryDraft? = dir(id)?.takeIf { it.isDirectory && id !in forgotten }?.let { SummaryDraft.fromJson(text(it, SummaryDraft.FILE)) }

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
        asideDirs().forEach { removeAll(it) }
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
        // fix-seal-transcripts-b: plain (ids, times and a count, no words), so recover and settle read it, locked or not.
        return writeIn(root, SessionHeartbeat.FILE, hb.toJson(), sealed = false) == null
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
        if (!exists(beat.id)) {
            clearBeat()
            return null
        }
        // seal-transcripts: one that can't be opened now (the unlock key's, the phone locked) waits, heartbeat and all, for
        // the next start; never an empty snapshot written over lines that are there.
        val d = dir(beat.id)!!
        val meta = meta(beat.id) ?: run { if (!notNow(d, TranscriptFiles.META)) clearBeat(); return null }
        val t = load(beat.id) ?: if (opensNow(d)) Transcript() else return null
        val upTo = SessionRecovery.savedUpTo(beat).coerceAtLeast(meta.createdAt)
        val status = if (action == SessionRecovery.Action.CLOSE) SessionStatus.ENDED else SessionStatus.INTERRUPTED
        writeMeta(meta.copy(status = status, endedAt = upTo).withSpeakers(t))
        writeSnapshot(beat.id, t)
        clearBeat()
        return Recovered(beat.id, action, t.lines.size, ((upTo - meta.createdAt) / 60_000).toInt(), upTo)
    }

    /** Free space where transcripts are written, or -1 if unknown. */
    fun freeBytes(): Long = try { (if (root.isDirectory) root else root.parentFile ?: root).usableSpace } catch (_: Exception) { -1 }

    private fun writeIn(d: File, name: String, text: String, sealed: Boolean = true): IoCode? = try { sealLock { writeInNow(d, name, text, sealed) } } catch (x: Exception) { code(x, d) }

    private fun writeInNow(d: File, name: String, plain: String, sealed: Boolean = true): IoCode? {
        if (!d.isDirectory) return IoCode.MISSING
        val tmp = File(d, ".$name.tmp")
        return try {
            val text = (if (sealed) seal else null)?.seal("${d.name}/$name", plain) ?: plain
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

    // ------------------------------------------------------------------ the seal (seal-transcripts)

    /** [d]'s file [name] as plain text; null when it is missing or can't be opened (now or ever). */
    private fun text(d: File, name: String): String? = try { textOrThrow(d, name) } catch (_: MemoryKeyException) { null }

    /** [d]'s log as plain lines ("" when there is none); null when a record can't be opened now or its key is lost. */
    fun logText(d: File): String? = try { logOrThrow(d) ?: "" } catch (_: MemoryKeyException) { null }

    private fun textOrThrow(d: File, name: String): String? {
        val raw = readOrNull(File(d, name)) ?: return null
        val s = seal ?: return raw
        return if (s.isSealed(raw)) s.open("${d.name}/$name", raw) else raw
    }

    /** A record that doesn't open as damaged (a line a crash cut) stays as it is, and the codec skips it as a plain torn one. */
    private fun logOrThrow(d: File): String? {
        val raw = readOrNull(File(d, TranscriptFiles.LOG)) ?: return null
        val s = seal ?: return raw
        if (!raw.contains(TranscriptSeal.MARK)) return raw
        return s.reading { raw.split('\n').joinToString("\n") { openLine(s, "${d.name}/${TranscriptFiles.LOG}", it) } }
    }

    private fun openLine(s: TranscriptSeal, name: String, line: String): String = if (!s.isSealed(line)) line else try {
        s.open(name, line)
    } catch (e: MemoryKeyException) {
        if (e.problem != KeyProblem.BAD_DATA) throw e
        line
    }

    /** [d]'s file [name] is there but its key can't be used now (the phone is locked). */
    private fun notNow(d: File, name: String): Boolean = try { textOrThrow(d, name); false } catch (e: MemoryKeyException) { e.problem == KeyProblem.LOCKED }

    private fun opensNow(d: File): Boolean = try { textOrThrow(d, TranscriptFiles.SNAPSHOT); logOrThrow(d); true } catch (_: MemoryKeyException) { false }

    private fun live(now: Long = System.currentTimeMillis()): String? = heartbeat()?.takeIf { now - it.beatAt < 3 * ConversationRules.BEAT_MS }?.id

    /**
     * [MAIN] A backup's plain copies of [d]'s text files [names] (the bundle has its own seal), all of them or none. Throws
     * a [MemoryKeyException] (LOCKED, or a [TranscriptKeyFault]) when one can't be opened now. One that never opens (its key
     * lost, or damaged) gives null (fix-seal-transcripts-b, Medium 2): the folder is kept aside as settle does, so it fails
     * no later backup and the Transcripts screen counts it; the live session's is only left out.
     */
    @Synchronized
    fun backupFolder(d: File, names: List<String>): Map<String, ByteArray>? = try {
        buildMap { for (n in names) backupBytes(d, n)?.let { put(n, it) } }
    } catch (e: MemoryKeyException) {
        // fix-seal-transcripts-c, Low 3: a newer Robot's record is left out of the backup, and left as it is.
        if (e is TranscriptTooNew) return null
        if (e.problem == KeyProblem.LOCKED) throw e
        if (d.name != live()) aside(d)
        null
    }

    /** fix-seal-transcripts-c, Lows 3 and 5: why a transcript doesn't open, for its page (d, Low 2: FAILED a Keystore fault only). */
    enum class Unopened { NEWER, FAILED, DAMAGED }

    /** Why [id] doesn't open now: null when it opens, is gone, or the phone is locked (its page then stays as it was). */
    fun unopened(id: String): Unopened? {
        val d = dir(id)?.takeIf { it.isDirectory } ?: return null
        return try { textOrThrow(d, TranscriptFiles.META); textOrThrow(d, TranscriptFiles.SNAPSHOT); logOrThrow(d); null }
        catch (_: TranscriptTooNew) { Unopened.NEWER }
        catch (_: TranscriptKeyFault) { Unopened.FAILED }
        catch (e: MemoryKeyException) { if (e.problem == KeyProblem.LOCKED) null else Unopened.DAMAGED }
    }

    /**
     * fix-seal-transcripts-d, Low 1: the transcripts a newer Robot wrote (meta.json, or the key it is under): left as they
     * are and in no list or backup, so the backup line counts them with the kept-aside ones and a Replace restore keeps them.
     */
    fun newer(): Set<String> = root.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) && f.name !in forgotten }.orEmpty()
        .filter { d -> try { textOrThrow(d, TranscriptFiles.META); false } catch (e: MemoryKeyException) { e is TranscriptTooNew } }.map { it.name }.toSet()

    /** [MAIN] A backup's copy of [d]'s text file [name]: plain (the bundle has its own seal). Throws when it can't be opened. */
    fun backupBytes(d: File, name: String): ByteArray? = (if (name == TranscriptFiles.LOG) logOrThrow(d) else textOrThrow(d, name))?.toByteArray(Charsets.UTF_8)

    /** Both processes write and re-seal under this lock, so a re-seal never puts back what the other process just wrote. */
    private fun <T> sealLock(block: () -> T): T = if (seal == null) block() else {
        root.mkdirs()
        RandomAccessFile(File(root, LOCK), "rw").use { raf -> raf.channel.lock().use { block() } }
    }

    /** Folders settled, by name, with their files' stamp then: a list doesn't read them again until one changes. */
    private val settled = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun stamp(d: File): Long = d.listFiles().orEmpty().sumOf { it.lastModified() * 31 + it.length() + it.name.hashCode() }

    /**
     * Unlocked only: every transcript but the live one sealed under the unlock key, the plain files of an older Robot and
     * the lock screen's records alike. Each file is replaced only after its sealed copy reads back the same. A folder with
     * a file that never opens (its key lost, or damaged) is kept aside as <id>.bad, never deleted; a lost key is made again.
     */
    @Synchronized
    fun settle(now: Long = System.currentTimeMillis()) {
        val s = seal ?: return
        if (s.locked()) return
        val live = live(now)
        for (d in root.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) && f.name !in forgotten && f.name != live }.orEmpty().sortedBy { it.name }) {
            if (settled[d.name] == stamp(d)) continue
            var again = true
            while (true) {
                try {
                    sealLock { sealDir(s, d, d.name) }
                    settled[d.name] = stamp(d)
                } catch (e: Lost) {
                    // fix-seal-transcripts, Medium 4: the key lost was the one sealing to (the folder's own files open):
                    // that key is made again and the folder done again, never set aside.
                    if (e.sealing && again) { again = false; e.alias?.let { s.replace(it) }; continue }
                    if (!e.sealing) { aside(d); e.alias?.let { s.replace(it) } }
                } catch (e: MemoryKeyException) {
                    // fix-seal-transcripts-b, Medium 1: a Keystore fault on an unlocked phone (the seal logged it) skips this
                    // folder only; it is settled at a later list. Locked: none can be, so settle stops.
                    // fix-seal-transcripts-c, Low 3: a newer Robot's record: that folder left as it is.
                    if (e !is TranscriptKeyFault && e !is TranscriptTooNew) { if (e.problem == KeyProblem.LOCKED) return; aside(d) }
                } catch (t: Exception) {
                    RLog.w("transcripts_seal", t) // l10n: not text (a log code)
                }
                break
            }
        }
    }

    /** [MAIN] A restore's staged folders (<id>/ in [staging]) sealed where they are, before they move in. False: not sealed. */
    // fix-seal-transcripts-c, Low 6: synchronized as every other user of sealLock, so a list's settle in this process
    // never meets it on the file lock (a second lock in one process throws rather than waits).
    @Synchronized
    fun sealStaged(staging: File): Boolean = staging.listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) }.orEmpty().all { sealStaged(it, it.name) }

    private fun sealStaged(d: File, id: String): Boolean {
        val s = seal ?: return true
        // fix-seal-transcripts, Low 6: under the key the phone's state allows now (a locked phone's restore can finish).
        val to = if (s.locked()) TranscriptKeyAliases.LOCK_SCREEN else TranscriptKeyAliases.UNLOCKED
        // fix-seal-transcripts-c, Low 6: under the cross-process lock, as settle and every write are, so the two processes
        // never make a data key through the same tmp file at once.
        return try { sealLock { sealDir(s, d, id, to) }; true } catch (t: Exception) { RLog.w("transcripts_seal_staged", t); false } // l10n: not text (a log code)
    }

    /** How a re-sealed copy is read back (tests make it fail). */
    internal var readBack: (File) -> String? = ::readOrNull

    /** A file that never opens: its folder goes aside; [alias], when its key is lost, is made again. [sealing]: the key sealed to is the lost one. */
    private class Lost(val alias: String?, val sealing: Boolean = false) : Exception()

    private fun sealDir(s: TranscriptSeal, d: File, id: String, unlock: String = TranscriptKeyAliases.UNLOCKED) = s.reading {
        for (f in d.listFiles { f -> f.isFile && textFile(f.name) }.orEmpty()) {
            val raw = readOrNull(f) ?: continue
            val name = "$id/${f.name}"
            fun opened(line: String): String = try { s.open(name, line) } catch (e: MemoryKeyException) {
                if (e.problem == KeyProblem.LOCKED) throw e
                if (e.problem == KeyProblem.GONE) throw Lost(s.aliasOf(line)) else throw Lost(null)
            }
            fun sealed(text: String): String = try { s.seal(name, text, unlock) } catch (e: MemoryKeyException) {
                if (e.problem == KeyProblem.GONE) throw Lost(unlock, sealing = true) else throw e
            }
            val plain: String
            val out: String
            if (f.name == TranscriptFiles.LOG) {
                val lines = raw.split('\n')
                if (lines.all { it.isEmpty() || s.aliasOf(it) == unlock }) continue
                val p = lines.map { l -> if (!s.isSealed(l)) l else try { opened(l) } catch (e: Lost) { if (e.alias != null) throw e else l } }
                plain = p.joinToString("\n")
                out = lines.indices.joinToString("\n") { i -> val l = lines[i]; if (l.isEmpty() || s.aliasOf(l) == unlock || (s.isSealed(l) && p[i] == l)) l else sealed(p[i]) }
            } else {
                // Already the unlock key's: meta.json opened once, so a key Android dropped is found and its folder kept aside.
                if (s.aliasOf(raw) == unlock) { if (f.name == TranscriptFiles.META) opened(raw); continue }
                plain = if (s.isSealed(raw)) opened(raw) else raw
                out = sealed(plain)
            }
            val tmp = File(d, ".${f.name}.seal.tmp")
            try {
                FileOutputStream(tmp).use { it.write(out.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                val back = readBack(tmp) ?: ""
                // fix-seal-transcripts-c, Low 2: a copy that doesn't read back is this write's fault, not the folder's: an
                // IOException, so settle logs it and tries again later, and never sets aside a folder whose files are intact.
                val reread = try {
                    if (f.name == TranscriptFiles.LOG) back.split('\n').joinToString("\n") { l -> if (!s.isSealed(l)) l else try { s.open(name, l) } catch (_: MemoryKeyException) { l } } else s.open(name, back)
                } catch (e: MemoryKeyException) { if (e.problem == KeyProblem.LOCKED) throw e; throw IOException("seal read back") } // l10n: not text (a log detail)
                if (reread != plain || !tmp.renameTo(f)) throw IOException("seal read back") // l10n: not text (a log detail)
            } finally { tmp.delete() }
        }
    }

    private fun textFile(name: String) = name == TranscriptFiles.META || name == TranscriptFiles.LOG || name == TranscriptFiles.SNAPSHOT ||
        name == SummaryDraft.FILE || SUMMARY.matches(name)

    private fun aside(d: File) {
        for (n in 1..9) {
            val to = File(root, d.name + if (n == 1) ".bad" else ".bad$n")
            if (!to.exists()) { if (d.renameTo(to)) RLog.i("transcripts_kept_aside", "n" to 1); return } // l10n: not text (a log code)
        }
    }

    private fun asideDirs(): List<File> = root.listFiles { f -> f.isDirectory && BAD.containsMatchIn(f.name) }.orEmpty().toList()

    /** How many transcripts are kept aside (their key is gone, or a file is damaged): never deleted but by Delete all. */
    fun asideCount(): Int = asideDirs().size

    // MISSING only when the folder is gone (deleted): the session then stops as if you deleted it and never closes the
    // file. A file that can't be opened in a folder that is there (no space to create it, too many open files) is not that.
    private fun code(x: Exception, d: File): IoCode = when {
        !d.isDirectory -> IoCode.MISSING
        (x.message ?: "").contains("ENOSPC") || (x.message ?: "").contains("No space", ignoreCase = true) -> IoCode.NO_SPACE // l10n: not text (the system's error message, matched)
        x is IOException -> IoCode.WRITE
        x is MemoryKeyException -> IoCode.KEY
        else -> IoCode.OTHER
    }

    companion object {
        private val BAD = Regex("""\.bad\d?$""")
        private const val LOCK = ".seal.lock" // l10n: not text (a file name)
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
            val log = store.logText(d) ?: return null
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
