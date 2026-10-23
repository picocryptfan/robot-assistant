// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.NoteStore
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.skills.SkillCodec
import app.heyrobot.core.skills.SkillFile
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupExport
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.readOrNull
import app.heyrobot.lock.LockStore
import app.heyrobot.sys.MemoryFiles
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.core.backup.FilesBackup
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.files.FileSealReader
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.RobotFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

// :VOICE PROCESS (docs/plans/backup-1.1-design.md §6.1). Reads every store a backup carries, by category, into entries:
// only reads, never a write. 1.4: Robot Files through this process's vault (its lock gate), each file's bytes streamed. The main app's settings files are read as :voice already reads settings.json, messages.json
// and termux.json; Memory through its vault (both app processes may open it). Keys and the voiceprint come from
// BackupSecrets alone. Nothing here is logged but codes.

internal object BackupSources {
    /**
     * One entry to write: small files are read into memory as it is written ([bytes]), big ones streamed ([opener]). [size]:
     * about how big it is now (the manifest's count, and the 32 GiB check before anything is written).
     */
    class Source private constructor(val category: BackupCategory, val name: String, private val bytes: (() -> ByteArray?)?,
                                     private val opener: (() -> Pair<Long, InputStream>?)?, private val size: () -> Long) {
        /** The entry's bytes now, or null when it went away since the list was made (left out). */
        fun read(): ByteArray? = bytes?.invoke()
        val streamed: Boolean get() = opener != null
        /** A streamed entry: its length now and its data, or null when it went away. */
        fun open(): Pair<Long, InputStream>? = opener?.invoke()
        /** The size as the list shows it. */
        val estimate: Long get() = size()

        override fun toString(): String = "Source(${category.id})" // l10n: not text (toString for logs and tests)

        companion object {
            fun bytes(c: BackupCategory, name: String, size: Long = 0, read: () -> ByteArray?) = Source(c, name, read, null) { size }
            fun file(c: BackupCategory, name: String, f: File) =
                Source(c, name, null, { f.takeIf { it.isFile }?.let { it.length() to it.inputStream() } }) { f.length() }
            /** 1.4: a stream of [size] bytes that [open] gives (Robot Files' bytes, through this phone's seal). */
            fun stream(c: BackupCategory, name: String, size: Long, open: () -> Pair<Long, InputStream>?) = Source(c, name, null, open) { size }
        }
    }

    /** What a category holds now, for Back up now's list: [count] its items, [bytes] about its size, [extra] its sub-count. */
    data class Summary(val count: Int, val bytes: Long, val extra: Int = 0, val extraBytes: Long = 0, val names: List<String> = emptyList()) {
        val empty: Boolean get() = count == 0
    }

    private fun dir(ctx: Context) = Paths.dir(ctx)
    private fun transcriptsDir(ctx: Context) = File(ctx.filesDir, TranscriptFiles.DIR)

    private fun settingsFiles(ctx: Context): List<File> = BackupCategory.SETTINGS_FILES.keys.map { File(dir(ctx), it) }.filter { it.isFile }
    private fun files(d: File, valid: (String) -> Boolean): List<File> =
        d.listFiles { f -> f.isFile && BackupCategory.idOf(f.name)?.let(valid) == true }.orEmpty().sortedBy { it.name }
    private fun notes(ctx: Context) = files(NotesFiles.dir(ctx), NoteStore::validId)
    private fun conversations(ctx: Context) = files(Paths.conversations(ctx), BackupCategory::conversationId)

    /**
     * FX3: History stays out of Back up now, whole, while travel mode hides drawers and any conversation holds Robot Files'
     * answer (core TravelMode.holdsFiles): a backup would carry what History shows as placeholders. Whole, as Robot Files
     * itself: a backup without some conversations would delete them on this phone at a Replace restore, and one without
     * History leaves History as it is. The next backup after travel mode is off carries it.
     */
    fun historyHidden(ctx: Context): Boolean =
        app.heyrobot.core.files.TravelMode.hidesAny(FilesSettingsStore.current(ctx)) && conversations(ctx).any { f ->
            readOrNull(f)?.let(ConversationCodec::fromJson)?.let { app.heyrobot.core.files.TravelMode.holdsFiles(it.turns) } ?: false
        }
    private fun transcripts(ctx: Context): List<File> = transcriptsDir(ctx).listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) &&
        File(f, TranscriptFiles.META).isFile }.orEmpty().sortedBy { it.name }
    private fun peopleFile(ctx: Context) = File(PeopleFiles.dir(ctx), BackupCategory.PEOPLE_FILE)
    private fun lockFile(ctx: Context) = File(LockStore.dir(ctx), LockStore.LOCKS)

    /** Memory as it is now: the file, or null when it can't be opened (locked, its key gone, damaged); empty when none. */
    fun memory(ctx: Context): MemoryFile? = try {
        when (val l = MemoryFiles.vault(ctx).load()) {
            is MemoryLoad.Loaded -> l.file
            MemoryLoad.Empty -> MemoryFile()
            else -> null
        }
    } catch (t: Throwable) {
        RLog.w("backup_memory", t)
        null
    }

    /** What each category holds now (background thread). Memory's line is null-count when it can't be opened now. */
    fun survey(ctx: Context, s: RobotSettings, files: () -> FilesVault = { RobotFiles.vault(ctx) }): Map<BackupCategory, Summary> {
        val out = LinkedHashMap<BackupCategory, Summary>()
        val settings = settingsFiles(ctx)
        out[BackupCategory.SETTINGS] = Summary(settings.size, settings.sumOf { it.length() })
        val people = peopleFile(ctx)
        out[BackupCategory.PEOPLE] = Summary(PeopleCodec.read(readOrNull(people))?.people?.size ?: 0, if (people.isFile) people.length() else 0)
        val m = memory(ctx)
        val split = m?.let(BackupExport::memory)
        out[BackupCategory.MEMORY] = if (split == null) Summary(-1, 0) else
            Summary(split.memory.items.size, MemoryCodec.toJson(split.memory).length.toLong(), extra = split.memory.items.count { it.secret })
        out[BackupCategory.HANDSFREE] = BackupCategory.HANDSFREE_FILES.keys.map { File(dir(ctx), it) }.filter { it.isFile }.let { fs ->
            val devices = (DeviceMemory.decode(readOrNull(File(dir(ctx), HandsFreeStore.DEVICES))) as? DeviceFile.Ok)?.memory?.devices?.size ?: 0
            Summary(if (fs.isEmpty()) 0 else maxOf(devices, 1), fs.sumOf { it.length() }, extra = devices)
        }
        out[BackupCategory.ROUTINES] = BackupCategory.ROUTINE_FILES.keys.map { File(dir(ctx), it) }.filter { it.isFile }.let { fs ->
            val n = (RoutineCodec.decode(readOrNull(File(dir(ctx), "routines.json"))) as? RoutineFile.Ok)?.routines?.size ?: 0 // l10n: not text (a file name)
            Summary(if (fs.isEmpty()) 0 else maxOf(n, 1), fs.sumOf { it.length() }, extra = n)
        }
        // 1.1: skills.json (never skills_state.json, the last runs).
        out[BackupCategory.SKILLS] = BackupCategory.SKILL_FILES.keys.map { File(dir(ctx), it) }.filter { it.isFile }.let { fs ->
            val n = (SkillCodec.decode(readOrNull(File(dir(ctx), SkillStore.SKILLS))) as? SkillFile.Ok)?.skills?.size ?: 0
            Summary(if (fs.isEmpty()) 0 else maxOf(n, 1), fs.sumOf { it.length() }, extra = n)
        }
        val lock = lockFile(ctx)
        out[BackupCategory.PHONE_LOCK] = Summary(if (lock.isFile) ((LockCodec.decode(readOrNull(lock) ?: "") as? LockFileRead.Ok)?.file?.locks?.size ?: 0).coerceAtLeast(1) else 0,
            if (lock.isFile) lock.length() else 0)
        val notes = notes(ctx)
        out[BackupCategory.NOTES] = Summary(notes.size, notes.sumOf { it.length() })
        val conv = conversations(ctx)
        out[BackupCategory.HISTORY] = if (historyHidden(ctx)) Summary(-1, 0) else Summary(conv.size, conv.sumOf { it.length() })
        val tr = transcripts(ctx)
        val audio = tr.map { File(it, TranscriptFiles.AUDIO) }.filter { it.isFile }
        out[BackupCategory.TRANSCRIPTS] = Summary(tr.size, tr.sumOf { d -> d.listFiles().orEmpty().filter { it.isFile && it.name != TranscriptFiles.AUDIO }.sumOf { it.length() } },
            extra = audio.size, extraBytes = audio.sumOf { it.length() })
        val keys = BackupSecrets.keyNames(ctx, s)
        out[BackupCategory.KEYS] = Summary(keys.size + (split?.codes?.items?.size ?: 0), 0, extra = split?.codes?.items?.size ?: 0, names = keys)
        out[BackupCategory.VOICEPRINT] = Summary(if (BackupSecrets.hasVoiceprint(ctx)) 1 else 0, if (BackupSecrets.hasVoiceprint(ctx)) 1024 else 0)
        // F12: travel mode on, Robot Files stays out of Back up now (a backup would carry the hidden drawers' files).
        out[BackupCategory.FILES] = if (app.heyrobot.core.files.TravelMode.active(FilesSettingsStore.current(ctx))) Summary(-1, 0) else filesSummary(files())
        return out
    }

    /**
     * 1.4: Robot Files for Back up now's list: the files that go in (none marked damaged) and their size, videos apart
     * ([Summary.extra], [Summary.extraBytes]); null-count when its list can't be opened now.
     */
    private fun filesSummary(vault: FilesVault): Summary {
        val index = when (val l = try { vault.load() } catch (t: Throwable) { RLog.w("backup_files", t); IndexLoad.Locked }) {
            is IndexLoad.Loaded -> l.index
            IndexLoad.Empty -> return Summary(0, 0)
            else -> return Summary(-1, 0)
        }
        val (videos, rest) = index.entries.filter { FilesBackup.carried(it, videos = true) }.partition(FilesBackup::video)
        return Summary(videos.size + rest.size, rest.sumOf { it.from.size }, extra = videos.size, extraBytes = videos.sumOf { it.from.size })
    }

    /**
     * 1.4: what goes in: [chosen] less a category the list can't open now ([survey]'s count below 0: Memory, Files), which it
     * shows off and disabled. Files whose key is gone or whose list can't be read stay that way, so a backup must go on without
     * them rather than fail asking you to leave out what you can't untick.
     */
    fun going(chosen: Set<BackupCategory>, survey: Map<BackupCategory, Summary>?): Set<BackupCategory> =
        chosen.filterTo(LinkedHashSet()) { c -> (survey?.get(c)?.count ?: 0) >= 0 }

    /** What [gather] found: the entries in restore order, and the helpers whose key couldn't be read just now (by name). */
    class Gathered(val sources: List<Source>, val secrets: List<BackupSecrets.Secret>, val unreadableKeys: List<String>)

    /** Memory couldn't be opened now while Memory was chosen ("Back up without Memory, or unlock and try again"). */
    class MemoryUnreadable : Exception()

    /** 1.4: Robot Files' list couldn't be opened now while Files was chosen ("Back up without Files, or unlock and try again"). */
    class FilesUnreadable : Exception()

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8, core FilesBackup): each carried file's plain bytes, streamed through this
     * phone's seal as they are written (never the sealed form, never robot_files_v1), then index.json, made after them from
     * the files that went in whole, then files_settings.json. A file deleted since the list was read is left out; one that
     * fails part way goes in as zeros and isn't named, so the backup still completes and a restore skips it.
     */
    private fun filesEntries(ctx: Context, vault: FilesVault, videos: Boolean, out: MutableList<Source>) {
        val c = BackupCategory.FILES
        if (app.heyrobot.core.files.TravelMode.active(FilesSettingsStore.current(ctx))) throw FilesUnreadable()
        val index = when (val l = vault.load()) {
            is IndexLoad.Loaded -> l.index
            IndexLoad.Empty -> return
            else -> throw FilesUnreadable()
        }
        if (index.entries.isEmpty()) return
        val whole = LinkedHashMap<FileId, FilesBackup.Streamed>()
        for (e in index.entries) if (FilesBackup.carried(e, videos)) {
            out += Source.stream(c, FilesBackup.blobName(e.id), e.from.size) {
                val r = try {
                    vault.open(e.id)
                } catch (x: FileException) {
                    // Locked: the backup stops (the window ends at screen-off anyway). Gone or damaged: left out.
                    if (x.problem == FileProblem.LOCKED) throw x
                    null
                }
                r?.let { e.from.size to Blob(it, e.from.size) { s -> synchronized(whole) { whole[e.id] = s } } }
            }
        }
        out += Source.bytes(c, FilesBackup.INDEX) { FileIndexCodec.encode(FilesBackup.exported(index, synchronized(whole) { LinkedHashMap(whole) })) }
        smallFile(c, FilesSettingsStore.file(ctx), FilesBackup.SETTINGS, out)
    }

    private fun smallFile(c: BackupCategory, f: File, name: String, out: MutableList<Source>) {
        if (f.isFile) out += Source.bytes(c, name, f.length()) { if (f.isFile) f.readBytes() else null }
    }

    /**
     * A file's bytes for the backup: exactly [size] of them, counted into SHA-256. When the sealed file fails part way (a
     * damaged chunk, a storage error) or holds another length, the rest is zeros and [whole] isn't told: index.json then
     * leaves the file out. Whole only when the sealed file ends right there too (reading on checks its last chunk).
     */
    private class Blob(private val reader: FileSealReader, private val size: Long, private val whole: (FilesBackup.Streamed) -> Unit) : InputStream() {
        private val md = MessageDigest.getInstance("SHA-256")
        private var left = size
        private var failed = false
        private var finished = false

        init {
            if (size == 0L) finish()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) {
                finish()
                return -1
            }
            if (len == 0) return 0
            val want = minOf(len.toLong(), left).toInt()
            var k = if (failed) -1 else try { reader.read(b, off, want) } catch (_: IOException) { -1 }
            if (k < 0) {
                failed = true
                java.util.Arrays.fill(b, off, off + want, 0)
                k = want
            } else {
                md.update(b, off, k)
            }
            left -= k
            if (left == 0L) finish()
            return k
        }

        private fun finish() {
            if (finished) return
            finished = true
            val end = !failed && try { reader.read() < 0 } catch (_: IOException) { false }
            if (end) whole(FilesBackup.Streamed(size, md.digest().joinToString("") { "%02x".format(it) }))
        }

        override fun close() = reader.close()
    }

    /**
     * The entries for [chosen] (with transcripts' [recordings] and History's [pictures] or not), in restore order. Keys and
     * the voiceprint are read here, from BackupSecrets, and must be closed by the caller once written. Background thread.
     */
    fun gather(ctx: Context, s: RobotSettings, chosen: Set<BackupCategory>, recordings: Boolean, pictures: Boolean, videos: Boolean = true,
               files: () -> FilesVault = { RobotFiles.vault(ctx) }): Gathered {
        val out = ArrayList<Source>()
        val secrets = ArrayList<BackupSecrets.Secret>()
        var unreadable: List<String> = emptyList()
        var codes: MemoryFile? = null
        fun small(c: BackupCategory, f: File, name: String = f.name, change: ((String) -> String)? = null) {
            if (!f.isFile) return
            out += Source.bytes(c, name, f.length()) {
                if (!f.isFile) null else if (change == null) f.readBytes() else change(f.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
            }
        }
        for (c in BackupCategory.entries.filter { it in chosen }) when (c) {
            BackupCategory.SETTINGS -> for (name in BackupCategory.SETTINGS_FILES.keys) small(c, File(dir(ctx), name), name, when (name) {
                // Never trust: the apps' signer pins and the trusted Termux stay on this phone.
                MessagesFile.FILE -> BackupExport::messages
                "termux.json" -> BackupExport::termux // l10n: not text (a file name)
                else -> null
            })
            BackupCategory.PEOPLE -> small(c, peopleFile(ctx))
            BackupCategory.MEMORY -> {
                val m = memory(ctx) ?: throw MemoryUnreadable()
                val split = BackupExport.memory(m)
                codes = split.codes
                if (split.memory.items.isNotEmpty() || m.suggestions.isNotEmpty()) {
                    val json = MemoryCodec.toJson(split.memory).toByteArray(Charsets.UTF_8)
                    out += Source.bytes(c, BackupCategory.MEMORY_FILE, json.size.toLong()) { json }
                }
            }
            BackupCategory.HANDSFREE -> for (name in BackupCategory.HANDSFREE_FILES.keys) small(c, File(dir(ctx), name))
            BackupCategory.ROUTINES -> for (name in BackupCategory.ROUTINE_FILES.keys) small(c, File(dir(ctx), name))
            BackupCategory.SKILLS -> for (name in BackupCategory.SKILL_FILES.keys) small(c, File(dir(ctx), name))
            BackupCategory.PHONE_LOCK -> small(c, lockFile(ctx), BackupCategory.LOCK_FILE)
            BackupCategory.NOTES -> for (f in notes(ctx)) small(c, f)
            // FX3: never while travel mode keeps it out (historyHidden; the list already showed it off), so it is in no manifest.
            BackupCategory.HISTORY -> if (!historyHidden(ctx)) for (f in conversations(ctx)) {
                if (pictures) small(c, f) else small(c, f) { text ->
                    ConversationCodec.fromJson(text)?.let { conv -> ConversationCodec.withoutMedia(conv)?.let(ConversationCodec::toJson) } ?: text
                }
            }
            BackupCategory.TRANSCRIPTS -> for (d in transcripts(ctx)) {
                val files = d.listFiles { f -> f.isFile && c.validName(d.name + "/" + f.name) }.orEmpty().sortedBy { if (it.name == TranscriptFiles.META) "" else it.name }
                for (f in files) {
                    if (f.name == TranscriptFiles.AUDIO && !recordings) continue
                    val name = d.name + "/" + f.name
                    if (f.name == TranscriptFiles.AUDIO || f.name == TranscriptFiles.LOG) out += Source.file(c, name, f) else small(c, f, name)
                }
            }
            BackupCategory.KEYS -> {
                val k = BackupSecrets.keys(ctx, s)
                secrets += k.secrets
                unreadable = k.unreadable
                // Memory's codes: those of the memory just read, or read now when Memory itself isn't chosen.
                val cs = codes ?: memory(ctx)?.let { BackupExport.memory(it).codes }
                if (cs != null && cs.items.isNotEmpty()) {
                    val json = MemoryCodec.toJson(cs).toByteArray(Charsets.UTF_8)
                    out += Source.bytes(c, BackupCategory.MEMORY_CODES, json.size.toLong()) { json }
                }
            }
            BackupCategory.VOICEPRINT -> BackupSecrets.voiceprint(ctx)?.let { secrets += it }
            BackupCategory.FILES -> filesEntries(ctx, files(), videos, out)
        }
        return Gathered(out, secrets, unreadable)
    }
}
