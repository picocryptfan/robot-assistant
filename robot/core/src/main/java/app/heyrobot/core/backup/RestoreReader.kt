// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.NoteCodec
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.PeopleFile
import app.heyrobot.core.People
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.StrictJson
import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryImport
import app.heyrobot.core.memory.MemoryParse
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// Pure. A restore reads the whole file before it writes anything of yours. [RestoreReader.verify] makes one
// pass over Robot's local copy of the file: every chunk's tag, the last-chunk rule, every record's kind, meta and name,
// every cap, StrictJson on every JSON entry and then the store's own codec on the same text, in memory, and the end
// record's counts. It keeps what the plan (RestorePlan) needs: the manifest, the counts and ids per category, People,
// the memory items and the small settings, routines and headset files; notes, History and transcripts are counted and
// checked, not kept, and keys only by name. Only a [VerifiedBackup] lets [RestoreReader.read] hand entries out, in a
// second pass over the same copy (every chunk is checked again as it is read).
//
// A refused entry refuses its whole category (a name outside its rules, a file over its cap, JSON or a codec that refuses
// it); a category this Robot doesn't know, or holding a file whose schema is newer than this Robot reads, is skipped by
// name. Problems with the file itself throw [BackupException].
//
// 1.4, Robot Files (FilesBackup): each file's bytes are streamed through SHA-256 and never kept; index.json is read by its
// codec (a newer one is left for a newer Robot; one it reads only in part is refused); then every file the index names
// must have its bytes in the backup, of the size and SHA-256 it says (the reader's own count, never the index's word),
// or the whole category is refused. Bytes the index doesn't name (a file the old phone couldn't read whole) are skipped.

/** Why a category of the file won't be restored. */
enum class SkipWhy {
    /** A category this Robot doesn't know (one added by a newer Robot). */
    UNKNOWN,
    /** A file in it has a newer schema than this Robot reads: "History needs Robot 1.3 or later." */
    NEWER,
    /** An entry broke its category's rules (its name, StrictJson, its store's codec): nothing of it is restored. */
    REFUSED,
    /** Over one of its caps. */
    TOO_BIG,
}

/** A category of the file skipped by name ([category]: null when this Robot doesn't know it). */
data class CategorySkip(val name: String, val category: BackupCategory?, val why: SkipWhy)

/** A transcript in the file: its files and, when it has one, the recording's size. */
data class TranscriptFound(val id: String, val files: Set<String>, val recordingBytes: Long)

/**
 * A file read whole and found sound. [found]: each category that can be restored, with what was read of it. The rest
 * is what the plan needs, nothing more. [toString] holds counts only.
 */
class VerifiedBackup internal constructor(
    val header: BackupHeader,
    val manifest: BackupManifest,
    val found: Map<BackupCategory, CategoryCount>,
    val skipped: List<CategorySkip>,
    val entries: Long,
    val bytes: Long,
    /** The settings files by name (settings.json, look.json, …), as text. */
    val settings: Map<String, String>,
    val people: PeopleFile?,
    val memory: MemoryFile?,
    /** keys/memory-codes.json: Memory's items that hold a code. */
    val memoryCodes: MemoryFile?,
    /** Notes and History by id, with when each was last changed. */
    val notes: Map<String, Long>,
    val history: Map<String, Long>,
    val transcripts: Map<String, TranscriptFound>,
    /** routines.json, routine_settings.json, reminders.json as text. */
    val routines: Map<String, String>,
    /** devices.json, headset.json, car.json, places.json as text. */
    val handsfree: Map<String, String>,
    /** The keys' names ("helper/claude", "search/brave"), never the keys. */
    val keys: List<String>,
    /** 1.1: Phone lock's locks.json as read (its locks, Settings › Phone lock's rows); never a code or a state. */
    val phoneLock: app.heyrobot.core.lock.LockFile? = null,
    /** 1.1: the voiceprint's speaker model (a VoiceBundle id), or null when the file has none. */
    val voiceprintModel: String? = null,
    /** 1.1: skills.json as read, every skill whole (a file that drops one is refused whole). */
    val skills: List<app.heyrobot.core.skills.Skill>? = null,
    /** 1.1, Skills phase B: skill_settings.json, when the file had it. */
    val skillSettings: app.heyrobot.core.skills.SkillSettings? = null,
    /**
     * 1.4: Robot Files' catalogue as the backup has it (every entry's bytes checked: size and SHA-256), or null. Names, fields
     * and text: content, kept in RAM only for the restore, never in a log ([toString] counts).
     */
    val files: app.heyrobot.core.files.FileIndex? = null,
    /** 1.4: Settings › Files (files_settings.json) when the file had it, with the keys this Robot doesn't know. */
    val filesSettings: app.heyrobot.core.files.FilesSettingsFile.Ok? = null,
) {
    fun has(c: BackupCategory): Boolean = c in found

    override fun toString(): String = "VerifiedBackup(entries=$entries, bytes=$bytes, found=${found.keys}, skipped=${skipped.map { it.name to it.why }})"  // l10n: not text (toString for logs and tests)
}

object RestoreReader {
    /** The header of a file whose first bytes are [start] (throws [BackupException] for a file that can't be opened). */
    fun header(start: ByteArray): BackupHeader = BackupHeader.parse(start)

    /**
     * The key for [passphrase], checked against the file's first chunk at once: the wrong one throws
     * [OpenProblem.WRONG_PASSPHRASE] ("Chunk 0 decides"). [open] gives the file from its start.
     */
    fun unlock(open: () -> InputStream, header: BackupHeader, passphrase: CharSequence): BackupKey {
        val key = BackupKdf.derive(passphrase, header)
        try {
            stream(open, header, key).use { s ->
                if (s.read() < 0) throw BackupException(OpenProblem.DAMAGED)
            }
        } catch (x: Exception) {
            key.close()
            throw x
        }
        return key
    }

    /** Reads the whole file and returns what it holds, or throws [BackupException]. Writes nothing. */
    fun verify(open: () -> InputStream, header: BackupHeader, key: BackupKey, progress: (Long) -> Unit = {}): VerifiedBackup =
        verify(open, header, key, progress, BackupCategory::of)

    /**
     * [verify], with [known] saying which categories this Robot has: BackupDowngradeTest reads a 1.4 file as a Robot without
     * Files (1.1 to 1.3), whose reader is this one less that category.
     */
    internal fun verify(open: () -> InputStream, header: BackupHeader, key: BackupKey, progress: (Long) -> Unit,
                        known: (String) -> BackupCategory?): VerifiedBackup {
        val v = Verify(known)
        stream(open, header, key).use { s ->
            val r = BackupContainerReader(s)
            while (true) {
                when (val rec = r.next() ?: break) {
                    is BackupRecord.Manifest -> v.manifest = rec.manifest
                    is BackupRecord.Entry -> v.entry(rec)
                    is BackupRecord.End -> { v.entries = rec.entries; v.bytes = rec.bytes }
                }
                progress(s.released)
            }
        }
        return v.result(header)
    }

    /**
     * The second pass: each entry of [wanted] categories that [verified] found, in the file's order, given to [visit]
     * with its data (read it or not; the rest is skipped). Every chunk is checked again; anything wrong throws.
     */
    fun read(open: () -> InputStream, verified: VerifiedBackup, key: BackupKey, wanted: Set<BackupCategory>,
             visit: (BackupCategory, String, InputStream) -> Unit) {
        stream(open, verified.header, key).use { s ->
            val r = BackupContainerReader(s)
            while (true) {
                val rec = r.next() ?: break
                if (rec !is BackupRecord.Entry) continue
                val c = rec.known ?: continue
                if (c in wanted && c in verified.found && c.validName(rec.name)) visit(c, rec.name, rec.data)
            }
        }
    }

    /** The plaintext stream of the file [open] gives, after checking that its header is [header]'s. */
    private fun stream(open: () -> InputStream, header: BackupHeader, key: BackupKey): BackupReader {
        val input = open()
        try {
            val start = ByteArray(BackupFormat.HEADER_SIZE)
            var n = 0
            while (n < start.size) {
                val k = input.read(start, n, start.size - n)
                if (k < 0) throw BackupException(OpenProblem.NOT_A_BACKUP)
                n += k
            }
            if (!start.contentEquals(header.encode())) throw BackupException(OpenProblem.CHANGED)
            return BackupReader(input, header, key)
        } catch (x: Exception) {
            input.close()
            throw x
        }
    }

    private class Verify(private val known: (String) -> BackupCategory?) {
        var manifest: BackupManifest? = null
        var entries = 0L
        var bytes = 0L
        val counts = HashMap<BackupCategory, CategoryCount>()
        val refused = LinkedHashMap<BackupCategory, SkipWhy>()
        val unknown = LinkedHashSet<String>()
        val settings = LinkedHashMap<String, String>()
        var people: PeopleFile? = null
        var memory: MemoryFile? = null
        var memoryCodes: MemoryFile? = null
        val notes = HashMap<String, Long>()
        val history = HashMap<String, Long>()
        val transcriptFiles = HashMap<String, MutableSet<String>>()
        val recordings = HashMap<String, Long>()
        val routines = LinkedHashMap<String, String>()
        var phoneLock: app.heyrobot.core.lock.LockFile? = null
        val handsfree = LinkedHashMap<String, String>()
        val keys = ArrayList<String>()
        var voiceprintModel: String? = null
        var skills: List<app.heyrobot.core.skills.Skill>? = null
        var skillSettings: app.heyrobot.core.skills.SkillSettings? = null
        var filesIndex: app.heyrobot.core.files.FileIndex? = null
        var filesSettings: app.heyrobot.core.files.FilesSettingsFile.Ok? = null
        /** Each file's bytes in the backup: the size and SHA-256 counted here. */
        val fileBlobs = HashMap<app.heyrobot.core.files.FileId, FilesBackup.Streamed>()

        fun entry(e: BackupRecord.Entry) {
            val c = known(e.category)
            if (c == null) {
                if (e.length > BackupCaps.UNKNOWN_ENTRY) throw BackupException(OpenProblem.TOO_BIG)
                unknown += e.category
                return
            }
            val count = counts[c] ?: CategoryCount(0, 0)
            counts[c] = CategoryCount(count.entries + 1, count.bytes + e.length)
            if (c in refused) return
            val max = c.maxBytes(e.name) ?: return refuse(c, SkipWhy.REFUSED)
            if (e.length > max || count.entries + 1 > c.maxEntries) return refuse(c, SkipWhy.TOO_BIG)
            if (e.schema > (c.schema(e.name) ?: 0)) return refuse(c, SkipWhy.NEWER)
            when (c) {
                BackupCategory.TRANSCRIPTS -> return transcript(e)
                BackupCategory.KEYS -> if (e.name != BackupCategory.MEMORY_CODES) return key(e)
                BackupCategory.VOICEPRINT -> return voiceprint(e)
                BackupCategory.FILES -> if (e.name != FilesBackup.SETTINGS) return files(e)
                else -> {}
            }
            val bytes = readAll(e.data, e.length)
            // StrictJson first (UTF-8, RFC 8259, no repeated keys, 32 levels), then the store's codec on the same text.
            val json = StrictJson.parse(bytes)?.value as? Map<*, *> ?: return refuse(c, SkipWhy.REFUSED)
            // The file's own version too, whatever its record said.
            val known = c.schema(e.name) ?: 0
            val own = (json[if (e.name == "settings.json") "schema" else "v"] as? Long) ?: 0L
            if (known > 0 && own > known) return refuse(c, SkipWhy.NEWER)
            val text = String(bytes, Charsets.UTF_8)
            val why = when (c) {
                BackupCategory.SETTINGS -> { settings[e.name] = text; null }
                BackupCategory.PEOPLE -> people(json, text)
                BackupCategory.MEMORY -> memory(json, text) { memory = it }
                BackupCategory.KEYS -> memory(json, text) { memoryCodes = it }
                BackupCategory.HANDSFREE -> handsfree(e.name, text)
                BackupCategory.ROUTINES -> routines(e.name, text)
                // skills.json strictly (plan §4): newer is left for a newer Robot; damaged, or a skill this Robot drops, refuses it whole.
                // skill_settings.json (phase B) the same way: newer left alone, damaged refuses the category.
                BackupCategory.SKILLS -> if (e.name == app.heyrobot.core.skills.SkillStore.SETTINGS) {
                    when (val r = app.heyrobot.core.skills.SkillSettingsCodec.decode(text)) {
                        is app.heyrobot.core.skills.SkillSettingsFile.Ok -> { skillSettings = r.read; null }
                        is app.heyrobot.core.skills.SkillSettingsFile.TooNew -> SkipWhy.NEWER
                        app.heyrobot.core.skills.SkillSettingsFile.Damaged -> SkipWhy.REFUSED
                    }
                } else when (val r = app.heyrobot.core.skills.SkillCodec.decode(text)) {
                    is app.heyrobot.core.skills.SkillFile.Ok -> if (r.dropped > 0) SkipWhy.REFUSED else { skills = r.skills; null }
                    is app.heyrobot.core.skills.SkillFile.TooNew -> SkipWhy.NEWER
                    app.heyrobot.core.skills.SkillFile.Damaged -> SkipWhy.REFUSED
                }
                // locks.json strictly, as Phone lock reads it: one it can't read whole is refused whole.
                BackupCategory.PHONE_LOCK -> when (val r = app.heyrobot.core.lock.LockCodec.decode(text)) {
                    is app.heyrobot.core.lock.LockFileRead.Ok -> { phoneLock = r.file; null }
                    is app.heyrobot.core.lock.LockFileRead.TooNew -> SkipWhy.NEWER
                    app.heyrobot.core.lock.LockFileRead.Damaged -> SkipWhy.REFUSED
                }
                BackupCategory.NOTES -> {
                    val n = NoteCodec.fromJson(text)
                    if (n == null || n.id != BackupCategory.idOf(e.name)) SkipWhy.REFUSED else { notes[n.id] = n.updated; null }
                }
                BackupCategory.HISTORY -> {
                    val conv = ConversationCodec.fromJson(text)
                    if (conv == null || conv.id != BackupCategory.idOf(e.name)) SkipWhy.REFUSED else { history[conv.id] = conv.updated; null }
                }
                BackupCategory.TRANSCRIPTS, BackupCategory.VOICEPRINT -> null
                // files_settings.json: after StrictJson, its own codec; newer is left for a newer Robot.
                BackupCategory.FILES -> when (val r = app.heyrobot.core.files.FilesSettingsCodec.decode(text)) {
                    is app.heyrobot.core.files.FilesSettingsFile.Ok -> { filesSettings = r; null }
                    is app.heyrobot.core.files.FilesSettingsFile.TooNew -> SkipWhy.NEWER
                    is app.heyrobot.core.files.FilesSettingsFile.Damaged -> SkipWhy.REFUSED
                }
            }
            if (why != null) refuse(c, why)
        }

        /**
         * A file's bytes (streamed through SHA-256, never kept) or the catalogue (its codec: newer left for a newer Robot,
         * damaged or read only in part refused, over 5,000 files too big).
         */
        private fun files(e: BackupRecord.Entry) {
            val c = BackupCategory.FILES
            val id = FilesBackup.blobId(e.name)
            if (id != null) {
                fileBlobs[id] = hashed(e.data)
                return
            }
            val bytes = readAll(e.data, e.length)
            try {
                when (val p = app.heyrobot.core.files.FileIndexCodec.decode(bytes)) {
                    is app.heyrobot.core.files.IndexParse.Ok -> when {
                        p.dropped > 0 -> refuse(c, SkipWhy.REFUSED)
                        p.index.entries.size > app.heyrobot.core.files.FileLimits.MAX_FILES -> refuse(c, SkipWhy.TOO_BIG)
                        else -> filesIndex = p.index
                    }
                    is app.heyrobot.core.files.IndexParse.TooNew -> refuse(c, SkipWhy.NEWER)
                    app.heyrobot.core.files.IndexParse.Damaged -> refuse(c, SkipWhy.REFUSED)
                }
            } finally {
                bytes.fill(0)
            }
        }

        /** Every file the catalogue names has its bytes here, of its size and SHA-256; bytes without a catalogue refuse it. */
        private fun filesWhole() {
            val c = BackupCategory.FILES
            if (c !in counts || c in refused) return
            val index = filesIndex
            if (index == null) {
                if (fileBlobs.isNotEmpty()) refuse(c, SkipWhy.REFUSED)
                return
            }
            if (index.entries.any { e -> fileBlobs[e.id]?.let { it.size == e.from.size && it.sha256 == e.from.sha256 } != true }) refuse(c, SkipWhy.REFUSED)
        }

        private fun people(json: Map<*, *>, text: String): SkipWhy? {
            if (((json["people"] as? List<*>)?.size ?: 0) > People.MAX_PEOPLE) return SkipWhy.TOO_BIG
            people = PeopleCodec.read(text) ?: return SkipWhy.REFUSED
            return null
        }

        /** Memory's JSON (MemoryCodec's, version 1): at most Import's 5,000 items, every item its codec reads. */
        private fun memory(json: Map<*, *>, text: String, keep: (MemoryFile) -> Unit): SkipWhy? {
            if (((json["items"] as? List<*>)?.size ?: 0) > MemoryImport.MAX_ITEMS) return SkipWhy.TOO_BIG
            return when (val m = MemoryCodec.read(text)) {
                is MemoryParse.Ok -> { keep(m.file); null }
                is MemoryParse.TooNew -> SkipWhy.NEWER
                MemoryParse.Damaged -> SkipWhy.REFUSED
            }
        }

        private fun handsfree(name: String, text: String): SkipWhy? {
            if (name == HandsFreeStore.DEVICES) when (DeviceMemory.decode(text)) {
                is DeviceFile.Ok -> {}
                is DeviceFile.TooNew -> return SkipWhy.NEWER
                DeviceFile.Damaged -> return SkipWhy.REFUSED
            }
            handsfree[name] = text
            return null
        }

        private fun routines(name: String, text: String): SkipWhy? {
            if (name == "routines.json") when (RoutineCodec.decode(text)) {
                is RoutineFile.Ok -> {}
                is RoutineFile.TooNew -> return SkipWhy.NEWER
                else -> return SkipWhy.REFUSED
            }
            routines[name] = text
            return null
        }

        private fun transcript(e: BackupRecord.Entry) {
            val c = BackupCategory.TRANSCRIPTS
            val id = BackupCategory.transcriptOf(e.name)
            val file = e.name.substringAfter('/')
            val files = transcriptFiles.getOrPut(id) { LinkedHashSet() }
            if (transcriptFiles.size > BackupCaps.MAX_TRANSCRIPTS || files.size >= BackupCaps.TRANSCRIPT_FILES) return refuse(c, SkipWhy.TOO_BIG)
            files += file
            when {
                file == TranscriptFiles.AUDIO -> { skipAll(e.data); recordings[id] = e.length }
                file == TranscriptFiles.LOG -> if (!utf8(e.data)) refuse(c, SkipWhy.REFUSED)
                else -> {
                    val bytes = readAll(e.data, e.length)
                    if (StrictJson.parse(bytes)?.value !is Map<*, *>) return refuse(c, SkipWhy.REFUSED)
                    if (file == TranscriptFiles.META && TranscriptMeta.fromJson(String(bytes, Charsets.UTF_8))?.id != id) refuse(c, SkipWhy.REFUSED)
                }
            }
        }

        private fun key(e: BackupRecord.Entry) {
            val bytes = readAll(e.data, e.length)
            try {
                val ok = bytes.isNotEmpty() && utf8(bytes.inputStream())
                if (!ok) refuse(BackupCategory.KEYS, SkipWhy.REFUSED) else keys += e.name
            } finally {
                bytes.fill(0)
            }
        }

        /** The voiceprint's plain bytes, read by its codec and zeroed: only its speaker model's id is kept. */
        private fun voiceprint(e: BackupRecord.Entry) {
            val bytes = readAll(e.data, e.length)
            try {
                val v = app.heyrobot.core.VoiceprintCodec.decode(bytes)
                if (v == null) refuse(BackupCategory.VOICEPRINT, SkipWhy.REFUSED) else { voiceprintModel = v.model; v.vector.fill(0f) }
            } finally {
                bytes.fill(0)
            }
        }

        private fun refuse(c: BackupCategory, why: SkipWhy) {
            refused.putIfAbsent(c, why)
        }

        fun result(header: BackupHeader): VerifiedBackup {
            val m = manifest ?: throw BackupException(OpenProblem.DAMAGED)
            // A transcript without its meta.json can't be restored: its category is refused whole.
            if (transcriptFiles.values.any { TranscriptFiles.META !in it }) refuse(BackupCategory.TRANSCRIPTS, SkipWhy.REFUSED)
            filesWhole()
            val skipped = refused.map { (c, why) -> CategorySkip(c.id, c, why) } + unknown.map { CategorySkip(it, null, SkipWhy.UNKNOWN) }
            fun <T> ok(c: BackupCategory, v: T): T? = if (c in counts && c !in refused) v else null
            return VerifiedBackup(
                header = header,
                manifest = m,
                found = counts.filterKeys { it !in refused },
                skipped = skipped,
                entries = entries,
                bytes = bytes,
                settings = ok(BackupCategory.SETTINGS, settings).orEmpty(),
                people = ok(BackupCategory.PEOPLE, people),
                memory = ok(BackupCategory.MEMORY, memory),
                memoryCodes = ok(BackupCategory.KEYS, memoryCodes),
                notes = ok(BackupCategory.NOTES, notes).orEmpty(),
                history = ok(BackupCategory.HISTORY, history).orEmpty(),
                transcripts = ok(BackupCategory.TRANSCRIPTS, transcriptFiles.mapValues { (id, f) -> TranscriptFound(id, f, recordings[id] ?: 0) }).orEmpty(),
                routines = ok(BackupCategory.ROUTINES, routines).orEmpty(),
                handsfree = ok(BackupCategory.HANDSFREE, handsfree).orEmpty(),
                keys = ok(BackupCategory.KEYS, keys).orEmpty(),
                phoneLock = ok(BackupCategory.PHONE_LOCK, phoneLock),
                voiceprintModel = ok(BackupCategory.VOICEPRINT, voiceprintModel),
                skills = ok(BackupCategory.SKILLS, skills),
                skillSettings = ok(BackupCategory.SKILLS, skillSettings),
                files = ok(BackupCategory.FILES, filesIndex),
                filesSettings = ok(BackupCategory.FILES, filesSettings),
            )
        }
    }

    private fun readAll(input: InputStream, length: Long): ByteArray {
        val out = ByteArray(length.toInt())
        var n = 0
        while (n < out.size) {
            val k = input.read(out, n, out.size - n)
            if (k < 0) throw BackupException(OpenProblem.DAMAGED)
            n += k
        }
        return out
    }

    /** The rest of [input] counted and hashed (SHA-256) in pieces, never held whole: a file may be 200 MiB. */
    private fun hashed(input: InputStream): FilesBackup.Streamed {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BackupFormat.CHUNK)
        var n = 0L
        while (true) {
            val k = input.read(buf)
            if (k < 0) break
            md.update(buf, 0, k)
            n += k
        }
        buf.fill(0)
        return FilesBackup.Streamed(n, md.digest().joinToString("") { "%02x".format(it) })
    }

    private fun skipAll(input: InputStream) {
        val buf = ByteArray(BackupFormat.CHUNK)
        while (input.read(buf) >= 0) Unit
    }

    /** Whether the rest of [input] is valid UTF-8, read in pieces (a transcript's log may be 64 MiB). */
    private fun utf8(input: InputStream): Boolean {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val inBuf = ByteBuffer.allocate(BackupFormat.CHUNK + 8)
        val outBuf = CharBuffer.allocate(BackupFormat.CHUNK + 8)
        val raw = ByteArray(BackupFormat.CHUNK)
        try {
            while (true) {
                val k = input.read(raw)
                if (k < 0) break
                inBuf.put(raw, 0, k)
                inBuf.flip()
                val r = dec.decode(inBuf, outBuf, false)
                if (r.isError) return false
                inBuf.compact()
                outBuf.clear()
            }
            inBuf.flip()
            if (dec.decode(inBuf, outBuf, true).isError || dec.flush(outBuf).isError) return false
            return !inBuf.hasRemaining()
        } catch (_: CharacterCodingException) {
            return false
        }
    }
}
