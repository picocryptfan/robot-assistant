// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.PeopleCodec
import app.heyrobot.core.NoteCodec
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
import app.heyrobot.core.connect.ConnFile
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.sys.ListsVault
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.RobotFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import app.heyrobot.sys.EverydaySettingsStore

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
    data class Summary(val count: Int, val bytes: Long, val extra: Int = 0, val extraBytes: Long = 0, val names: List<String> = emptyList(), val aside: Int = 0) {
        val empty: Boolean get() = count == 0
    }

    private fun dir(ctx: Context) = Paths.dir(ctx)

    /** skills-backup-b: the trusted keys' file and each MCP connection's tools file (only while connections.json reads whole), by entry name. */
    private fun skillTrustFiles(ctx: Context): List<Pair<String, File>> {
        val mcp = app.heyrobot.core.connect.McpStore(dir(ctx))
        val conns = (ConnFiles.store(ctx).file() as? ConnFile.Ok)?.connections.orEmpty().filter { it.kind == app.heyrobot.core.connect.ConnKind.MCP }
        return (listOf(BackupCategory.SKILL_KEYS_FILE to File(dir(ctx), BackupCategory.SKILL_KEYS_FILE)) +
            conns.map { BackupCategory.mcpName(it.id) to mcp.file(it.id) }).filter { it.second.isFile }
    }
    private fun transcriptsDir(ctx: Context) = File(ctx.filesDir, TranscriptFiles.DIR)

    private fun settingsFiles(ctx: Context): List<File> = BackupCategory.SETTINGS_FILES.keys.map { File(dir(ctx), it) }.filter { it.isFile }
    /** seal-notes: every note, opened through Notes' vault, or [NotesUnreadable] while one can't be opened (BackupJob says Notes is why). */
    fun notesWhole(ctx: Context): List<app.heyrobot.core.Note> = notesWhole(NotesFiles.store(ctx))

    /** fix-seal-lows-c 3: any other failure (the lock file, a key) is [NotesUnreadable] too, so the backup names Notes, not FAILED. */
    fun notesWhole(v: app.heyrobot.sys.NotesVault): List<app.heyrobot.core.Note> = try {
        v.whole()
    } catch (e: app.heyrobot.sys.NotesNotWhole) {
        throw NotesUnreadable(tooNew = e.tooNew > 0)
    } catch (e: Exception) {
        RLog.w("backup_notes", e)
        throw NotesUnreadable()
    }

    /**
     * FX3: History stays out of Back up now, whole, while travel mode hides drawers and any conversation holds Robot Files'
     * answer (core TravelMode.holdsFiles): a backup would carry what History shows as placeholders. Whole, as Robot Files
     * itself: a backup without some conversations would delete them on this phone at a Replace restore, and one without
     * History leaves History as it is. The next backup after travel mode is off carries it.
     */
    /** seal-history-fix-b 1: every conversation, or [HistoryUnreadable] while one can't be opened (BackupJob says History is why). */
    fun historyWhole(ctx: Context): List<app.heyrobot.core.StoredConversation> = try {
        app.heyrobot.sys.ConversationStore.whole(ctx)
    } catch (e: app.heyrobot.sys.HistoryNotWhole) {
        throw HistoryUnreadable(tooNew = e.tooNew > 0)
    }

    fun historyHidden(ctx: Context): Boolean =
        app.heyrobot.core.files.TravelMode.hidesAny(FilesSettingsStore.current(ctx)) && app.heyrobot.sys.ConversationStore.list(ctx).any { app.heyrobot.core.files.TravelMode.holdsFiles(it.turns) }
    private fun transcripts(ctx: Context): List<File> = transcriptsDir(ctx).listFiles { f -> f.isDirectory && TranscriptIds.valid(f.name) &&
        File(f, TranscriptFiles.META).isFile }.orEmpty().sortedBy { it.name }
    /** seal-people: People and its ignored addresses, opened through its vault, or [PeopleUnreadable] while it can't be (BackupJob says People is why). */
    fun peopleWhole(ctx: Context): app.heyrobot.core.PeopleFile = try {
        PeopleFiles.store(ctx).whole()
    } catch (e: app.heyrobot.core.PeopleNotWhole) {
        // seal-people-fix 1: damaged gets the same words as a newer Robot's (Start over in People), not "just now".
        throw PeopleUnreadable(tooNew = e.load is app.heyrobot.core.PeopleLoad.TooNew || e.load == app.heyrobot.core.PeopleLoad.Unreadable)
    }
    private fun lockFile(ctx: Context) = File(LockStore.dir(ctx), LockStore.LOCKS)
    private fun connectionsFile(ctx: Context) = File(dir(ctx), BackupCategory.CONNECTIONS_FILE)

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

    /**
     * 1.4, Lists as they are now, through this process's vault (its gate): the lists with the journal's adds merged in (the
     * first-use lists never made here), an empty file when there are none, or null when they can't be opened now (locked,
     * their key gone, damaged, a newer Robot's).
     */
    fun lists(vault: ListsVault): ListsFile? = try {
        when (val l = vault.load(firstUse = false)) {
            is ListsLoad.Loaded -> l.value
            ListsLoad.Empty -> ListsFile()
            else -> null
        }
    } catch (t: Throwable) {
        RLog.w("backup_lists", t)
        null
    }

    /** spoken-log: the Logbook as it is now through its vault (the journal merged), or null when it can't be opened now. */
    fun logbook(ctx: Context): List<app.heyrobot.core.logbook.LogEntry>? = try {
        (app.heyrobot.sys.LogbookFiles.store(ctx).read() as? app.heyrobot.sys.LogbookRead.Ok)?.entries
    } catch (t: Throwable) {
        RLog.w("backup_logbook", t)
        null
    }

    /** seal-people-fix 1: [Summary.extra] on People's line when it can't be read (damaged, a newer Robot's), not just not now. */
    const val PEOPLE_DAMAGED = 1

    /**
     * seal-people-fix 1: People's line. Never Empty when it can't be opened (count -1, [PEOPLE_DAMAGED] when damaged or a
     * newer Robot's), and not Empty while it holds ignored addresses: [Summary.extra] is how many people.
     */
    fun peopleSummary(load: app.heyrobot.core.PeopleLoad, aside: Int = 0): Summary = when (load) {
        // fix-seal-14-lows-b 1: [aside], People's copies kept aside, never in a backup: the line says how many.
        is app.heyrobot.core.PeopleLoad.Ok -> load.file.let { f ->
            if (f.people.isEmpty() && f.ignored.isEmpty()) Summary(0, 0, aside = aside)
            else Summary(maxOf(f.people.size, 1), PeopleCodec.toJson(f.people, f.ignored).length.toLong(), extra = f.people.size, aside = aside)
        }
        app.heyrobot.core.PeopleLoad.NotNow -> Summary(-1, 0)
        else -> Summary(-1, 0, extra = PEOPLE_DAMAGED)
    }

    /**
     * seal-people-fix 1: whether a row on Back up now's list can be ticked, and counts as chosen when ticked. A row that
     * can't be read is off, except People: it stays as you set it, and Back up now then says why it stopped
     * (PEOPLE_UNREADABLE, PEOPLE_TOO_NEW) rather than leave People out without a word.
     */
    fun choosable(c: BackupCategory, s: Summary?): Boolean = s != null && (if (s.count < 0) c == BackupCategory.PEOPLE || c == BackupCategory.NOTES else !s.empty)

    /** fix-seal-lows-c 1: [Summary.extra] on Notes' line when some are a newer Robot's, not just not now. */
    const val NOTES_NEWER = 1

    /**
     * fix-seal-lows-c 1: Notes' line, from what read() holds back too. Never Empty while a note can't be opened (locked, a
     * newer Robot's, a read that failed): count -1, and Notes stays as you set it, so Back up now stops with
     * NOTES_UNREADABLE or NOTES_TOO_NEW rather than leave your notes out without a word.
     */
    fun notesSummary(r: app.heyrobot.sys.NotesRead): Summary =
        if (r.notNow + r.tooNew > 0) Summary(-1, 0, extra = if (r.tooNew > 0) NOTES_NEWER else 0)
        else Summary(r.notes.size, r.notes.sumOf { NoteCodec.toJson(it).length.toLong() })

    /** What each category holds now (background thread). Memory's line is null-count when it can't be opened now. */
    fun survey(ctx: Context, s: RobotSettings, lists: () -> ListsVault = { ListsFiles.vault(ctx) },
               files: () -> FilesVault = { RobotFiles.vault(ctx) }): Map<BackupCategory, Summary> {
        val out = LinkedHashMap<BackupCategory, Summary>()
        val settings = settingsFiles(ctx)
        out[BackupCategory.SETTINGS] = Summary(settings.size, settings.sumOf { it.length() })
        out[BackupCategory.PEOPLE] = peopleSummary(try { PeopleFiles.store(ctx).load() } catch (t: Throwable) {
            RLog.w("backup_people", t); app.heyrobot.core.PeopleLoad.NotNow }, aside = try { PeopleFiles.keptAside(ctx) } catch (_: Throwable) { 0 })
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
        out[BackupCategory.NOTES] = notesSummary(NotesFiles.store(ctx).read())
        val conv = app.heyrobot.sys.ConversationStore.list(ctx)
        out[BackupCategory.HISTORY] = if (historyHidden(ctx)) Summary(-1, 0) else Summary(conv.size, conv.sumOf { ConversationCodec.toJson(it).length.toLong() })
        // fix-seal-transcripts-d, Low 1: a newer Robot's transcripts are in no backup: counted with the kept-aside ones.
        val newer = try { app.heyrobot.sys.Transcripts.store(ctx).newer() } catch (_: Throwable) { emptySet() }
        val tr = transcripts(ctx).filter { it.name !in newer }
        val audio = tr.map { File(it, TranscriptFiles.AUDIO) }.filter { it.isFile }
        out[BackupCategory.TRANSCRIPTS] = Summary(tr.size, tr.sumOf { d -> d.listFiles().orEmpty().filter { it.isFile && it.name != TranscriptFiles.AUDIO }.sumOf { it.length() } },
            extra = audio.size, extraBytes = audio.sumOf { it.length() },
            // fix-seal-transcripts-c, Medium 1: transcripts kept aside are never in a backup: the line says how many, as People's does.
            aside = (try { app.heyrobot.sys.Transcripts.store(ctx).asideCount() } catch (_: Throwable) { 0 }) + newer.size)
        val keys = BackupSecrets.keyNames(ctx, s)
        // C10: your connections' keys are shown in this row too ("Home Assistant, ntfy"), and go in with its tick.
        val connKeys = BackupSecrets.connectionsWithKeys(ctx).map { it.name }
        out[BackupCategory.KEYS] = Summary(keys.size + connKeys.size + (split?.codes?.items?.size ?: 0), 0, extra = split?.codes?.items?.size ?: 0,
            names = keys + connKeys)
        out[BackupCategory.VOICEPRINT] = Summary(if (BackupSecrets.hasVoiceprint(ctx)) 1 else 0, if (BackupSecrets.hasVoiceprint(ctx)) 1024 else 0)
        // F12: travel mode on, Robot Files stays out of Back up now (a backup would carry the hidden drawers' files).
        out[BackupCategory.FILES] = if (app.heyrobot.core.files.TravelMode.active(FilesSettingsStore.current(ctx))) Summary(-1, 0) else filesSummary(files())
        // 1.4, Lists: how many lists (null-count when they can't be opened now).
        out[BackupCategory.LISTS] = lists(lists())?.let { f -> Summary(f.lists.size, if (f.lists.isEmpty()) 0 else BackupExport.lists(f).size.toLong()) } ?: Summary(-1, 0)
        // H3: how many private events (null-count when they can't be opened now).
        out[BackupCategory.CALENDAR] = privateEvents(ctx)?.let { f -> Summary(f.events.size, if (f.events.isEmpty()) 0 else app.heyrobot.core.calendar.PrivateEventsCodec.encode(f).length.toLong()) } ?: Summary(-1, 0)
        // H5: the Today page's home_settings.json, once Today was offered, turned on or arranged.
        val home = app.heyrobot.sys.HomeSettingsStore.file(ctx)
        out[BackupCategory.HOME] = Summary(if (home.isFile) 1 else 0, if (home.isFile) home.length() else 0)
        // earbud-headsup-c: headsup.json once the heads-up was set (never headsup-said.json, the events already handled).
        val headsUp = app.heyrobot.sys.HeadsUpStore.file(ctx)
        out[BackupCategory.HEADSUP] = Summary(if (headsUp.isFile) 1 else 0, if (headsUp.isFile) headsUp.length() else 0)
        // moments-backup: moments.json, reminders for a moment (shown in Routines' row).
        val moments = File(dir(ctx), BackupCategory.MOMENTS_FILE)
        out[BackupCategory.MOMENTS] = if (moments.isFile) Summary(maxOf(app.heyrobot.core.MomentReminders.decode(readOrNull(moments)).size, 1), moments.length()) else Summary(0, 0)
        out[BackupCategory.LOGBOOK] = logbook(ctx)?.let { l -> Summary(l.size, if (l.isEmpty()) 0 else app.heyrobot.core.logbook.LogbookCodec.toJson(l).length.toLong()) } ?: Summary(-1, 0)
        // The everyday set: everyday_settings.json, once you changed one of its switches.
        val everyday = EverydaySettingsStore.file(ctx)
        out[BackupCategory.EVERYDAY] = Summary(if (everyday.isFile) 1 else 0, if (everyday.isFile) everyday.length() else 0)
        // auto-open-plan §4: open_apps.json, once you changed the switch (shown in Settings' row).
        val openApps = app.heyrobot.sys.OpenAppsStore.file(ctx)
        out[BackupCategory.OPEN_APPS] = Summary(if (openApps.isFile) 1 else 0, if (openApps.isFile) openApps.length() else 0)
        // L12: how many pairings would go (F132: off, :link holds them closed and they stay out; on but locked or out of reach: can't be read now).
        out[BackupCategory.LINKS] = if (!linksOn(ctx)) Summary(-1, 0, extra = LINKS_OFF) else linkBackup(ctx)?.let { b ->
            val n = (app.heyrobot.core.link.LinkBackupCodec.decode(b.toString(Charsets.UTF_8)) as? app.heyrobot.core.link.LinkBackupRead.Ok)?.file?.pairings?.size
            if (n == null) Summary(-1, 0) else Summary(n, if (n == 0) 0 else b.size.toLong())
        } ?: Summary(-1, 0)
        // C10: connections.json (null-count when it can't be read: damaged, or a newer Robot's, it stays out), and their keys.
        val conn = connectionsFile(ctx)
        out[BackupCategory.CONNECTIONS] = when (val f = ConnFiles.store(ctx).file()) {
            is ConnFile.Ok -> Summary(f.connections.size, if (conn.isFile) conn.length() else 0)
            else -> Summary(-1, 0)
        }
        out[BackupCategory.CONN_KEYS] = Summary(connKeys.size, 0, names = connKeys)
        // skills-backup-b: skill_keys.json and each MCP connection's tools file (shown in Skills' row).
        val trust = skillTrustFiles(ctx).map { it.second }
        out[BackupCategory.SKILL_TRUST] = Summary(trust.size, trust.sumOf { it.length() })
        val ticked = (SkillCodec.decode(readOrNull(File(dir(ctx), SkillStore.SKILLS))) as? SkillFile.Ok)?.skills.orEmpty().count { it.opens.isNotEmpty() }
        out[BackupCategory.SKILL_OPENS] = Summary(ticked, 0)
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
    class Gathered(val sources: List<Source>, val secrets: List<BackupSecrets.Secret>, val unreadableKeys: List<String>, val linksLeftOut: Boolean = false,
                   /** R4: asked once the entries are written: how many of Robot Files' files couldn't be read. */
                   val filesLeftOut: () -> Int = { 0 }, val codesLeftOut: Boolean = false,
                   /** Today fix: private events Robot can't open on this phone (key gone, damaged): left out, and Done says so. */
                   val privateLeftOut: Boolean = false,
                   /** fix-seal-transcripts-c, Medium 1: transcripts that never opened during the gather (kept aside, or a newer Robot's): left out, and Done says so. */
                   val transcriptsLeftOut: Int = 0)

    /** Memory couldn't be opened now while Memory was chosen ("Back up without Memory, or unlock and try again"). */
    class MemoryUnreadable : Exception()

    /** 1.4: Robot Files' list couldn't be opened now while Files was chosen ("Back up without Files, or unlock and try again"). */
    class FilesUnreadable : Exception()

    /** 1.4: the lists couldn't be opened now while Lists was chosen ("Back up without Lists, or unlock and try again"). */
    class ListsUnreadable : Exception()
    /** spoken-log: the Logbook couldn't be opened now while it was chosen. */
    class LogbookUnreadable : Exception()

    /** History couldn't be opened whole while History was chosen: [tooNew], a newer Robot's files (update, or Delete these); else not now. */
    class HistoryUnreadable(val tooNew: Boolean) : Exception()
    /** seal-notes: Notes was chosen and a note can't be opened now (or is a newer Robot's). */
    class NotesUnreadable(val tooNew: Boolean = false) : Exception()
    /** seal-people: People was chosen and can't be opened now, or holds a newer Robot's file ([tooNew]). */
    class PeopleUnreadable(val tooNew: Boolean = false) : Exception()

    /** H3: private events couldn't be opened now while they were chosen ("Back up without them, or unlock and try again"). */
    class PrivateUnreadable : Exception()
    /** fix-seal-transcripts 6: a transcript can't be opened now (sealed under the unlock key, the phone locked). */
    /** [fault]: the phone is unlocked and the Keystore still failed (fix-seal-transcripts-b, Medium 1). */
    class TranscriptsLocked(val fault: Boolean = false) : Exception()

    /** H3: private events as they are now (an empty file when there are none), or null when they can't be opened now. */
    fun privateEvents(ctx: Context): app.heyrobot.core.calendar.PrivateEventsFile? =
        (app.heyrobot.sys.PrivateEventsFiles.vault(ctx).load() as? app.heyrobot.sys.PrivateLoad.Loaded)?.file

    /**
     * 1.4, Robot Files (files-1.4-plan.md §2.8, core FilesBackup): each carried file's plain bytes, streamed through this
     * phone's seal as they are written (never the sealed form, never robot_files_v1), then index.json, made after them from
     * the files that went in whole, then files_settings.json. A file deleted since the list was read is left out; one that
     * fails part way goes in as zeros and isn't named, so the backup still completes and a restore skips it. R4: returns how to
     * ask how many went that way, once they're written; index.json notes the same count, for the restore.
     */
    private fun filesEntries(ctx: Context, vault: FilesVault, videos: Boolean, out: MutableList<Source>): () -> Int {
        val c = BackupCategory.FILES
        if (app.heyrobot.core.files.TravelMode.active(FilesSettingsStore.current(ctx))) throw FilesUnreadable()
        val index = when (val l = vault.load()) {
            is IndexLoad.Loaded -> l.index
            IndexLoad.Empty -> return { 0 }
            else -> throw FilesUnreadable()
        }
        if (index.entries.isEmpty()) return { 0 }
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
        val carried = index.entries.count { FilesBackup.carried(it, videos) }
        val left = { carried - synchronized(whole) { whole.size } }
        out += Source.bytes(c, FilesBackup.INDEX) { FileIndexCodec.encode(FilesBackup.exported(index, synchronized(whole) { LinkedHashMap(whole) }, left())) }
        smallFile(c, FilesSettingsStore.file(ctx), FilesBackup.SETTINGS, out)
        return left
    }

    /** F132: Robot to Robot's row in a survey, in [Summary.extra]: can't be read because it's off. */
    const val LINKS_OFF = 1

    private fun linksOn(ctx: Context): Boolean = app.heyrobot.sys.LinkSettingsStore.current(ctx).on

    /** L12: from :link, over Binder; null when it won't say (locked, or out of reach). Off the main thread, as all of this is. */
    private fun linkBackup(ctx: Context): ByteArray? {
        val client = app.heyrobot.sys.LinkClient(ctx)
        return try { client.backupExport() } finally { client.close() }
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
               lists: () -> ListsVault = { ListsFiles.vault(ctx) }, files: () -> FilesVault = { RobotFiles.vault(ctx) }): Gathered {
        val out = ArrayList<Source>()
        val secrets = ArrayList<BackupSecrets.Secret>()
        val unreadable = ArrayList<String>()
        var linksLeftOut = false
        var filesLeft: () -> Int = { 0 }
        var codesLeftOut = false
        var privateLeftOut = false
        var transcriptsLeft = 0
        var codes: MemoryFile? = null
        fun small(c: BackupCategory, f: File, name: String = f.name, change: ((String) -> String?)? = null) {
            if (!f.isFile) return
            out += Source.bytes(c, name, f.length()) {
                if (!f.isFile) null else if (change == null) f.readBytes() else change(f.readText(Charsets.UTF_8))?.toByteArray(Charsets.UTF_8)
            }
        }
        for (c in BackupCategory.entries.filter { it in chosen }) when (c) {
            BackupCategory.SETTINGS -> for (name in BackupCategory.SETTINGS_FILES.keys) small(c, File(dir(ctx), name), name, when (name) {
                // Never trust: the apps' signer pins and the trusted Termux stay on this phone.
                MessagesFile.FILE -> BackupExport::messages
                "termux.json" -> BackupExport::termux // l10n: not text (a file name)
                else -> null
            })
            BackupCategory.PEOPLE -> peopleWhole(ctx).takeIf { it.people.isNotEmpty() || it.ignored.isNotEmpty() }?.let { f ->
                val json = PeopleCodec.toJson(f.people, f.ignored).toByteArray(Charsets.UTF_8)
                out += Source.bytes(c, BackupCategory.PEOPLE_FILE, json.size.toLong()) { json }
            }
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
            // auto-open-i: skills.json without its ticks (1.3 reads it whole), the ticks in skill_opens.json (SKILL_OPENS).
            BackupCategory.SKILLS -> for (name in BackupCategory.SKILL_FILES.keys) small(c, File(dir(ctx), name), name,
                if (name == SkillStore.SKILLS) { t -> app.heyrobot.core.skills.SkillOpensFile.split(t).first } else null)
            BackupCategory.SKILL_OPENS -> readOrNull(File(dir(ctx), SkillStore.SKILLS))?.let { app.heyrobot.core.skills.SkillOpensFile.split(it).second }
                ?.toByteArray(Charsets.UTF_8)?.let { b -> out += Source.bytes(c, BackupCategory.SKILL_OPENS_FILE, b.size.toLong()) { b } }
            BackupCategory.PHONE_LOCK -> small(c, lockFile(ctx), BackupCategory.LOCK_FILE)
            // seal-notes: each note opened through Notes' vault; the backup holds it as JSON inside its own seal, as before.
            BackupCategory.NOTES -> for (n in notesWhole(ctx)) {
                val json = NoteCodec.toJson(n).toByteArray(Charsets.UTF_8)
                out += Source.bytes(c, n.id + ".json", json.size.toLong()) { json } // l10n: not text (a file name)
            }
            // FX3: never while travel mode keeps it out (historyHidden; the list already showed it off), so it is in no manifest.
            // seal-history: each conversation opened through History's vault; the backup holds it as JSON inside its own seal.
            // seal-history-fix: one that can't be opened now fails the backup with LOCKED, as Robot Files does.
            BackupCategory.HISTORY -> if (!historyHidden(ctx)) for (conv in historyWhole(ctx)) {
                val json = ConversationCodec.toJson((if (pictures) null else ConversationCodec.withoutMedia(conv)) ?: conv).toByteArray(Charsets.UTF_8)
                out += Source.bytes(c, conv.id + ".json", json.size.toLong()) { json } // l10n: not text (a file name)
            }
            BackupCategory.TRANSCRIPTS -> for (d in run { app.heyrobot.sys.Transcripts.store(ctx).settle(); transcripts(ctx) }) {
                val files = d.listFiles { f -> f.isFile && c.validName(d.name + "/" + f.name) }.orEmpty().sortedBy { if (it.name == TranscriptFiles.META) "" else it.name }
                // seal-transcripts: text opened through the transcripts' seal (the bundle holds it plain inside its own); one
                // that can't be opened now fails the backup rather than leave it out, with its own words when the phone is
                // unlocked (fix-seal-transcripts-b, Medium 1). One that never opens is kept aside and left out (Medium 2).
                val texts = try { app.heyrobot.sys.Transcripts.store(ctx).backupFolder(d, files.map { it.name }.filter { it != TranscriptFiles.AUDIO }) }
                    catch (e: app.heyrobot.core.memory.MemoryKeyException) { throw TranscriptsLocked(fault = e is app.heyrobot.core.TranscriptKeyFault) }
                if (texts == null) { transcriptsLeft++; continue }
                for (f in files) {
                    if (f.name == TranscriptFiles.AUDIO && !recordings) continue
                    val name = d.name + "/" + f.name
                    if (f.name == TranscriptFiles.AUDIO) out += Source.file(c, name, f) else texts[f.name]?.let { b -> out += Source.bytes(c, name, b.size.toLong()) { b } }
                }
            }
            BackupCategory.KEYS -> {
                val k = BackupSecrets.keys(ctx, s)
                secrets += k.secrets
                unreadable += k.unreadable
                // Memory's codes: those of the memory just read, or read now when Memory itself isn't chosen.
                val cs = codes ?: memory(ctx)?.let { BackupExport.memory(it).codes }
                if (cs == null) codesLeftOut = true
                if (cs != null && cs.items.isNotEmpty()) {
                    val json = MemoryCodec.toJson(cs).toByteArray(Charsets.UTF_8)
                    out += Source.bytes(c, BackupCategory.MEMORY_CODES, json.size.toLong()) { json }
                }
            }
            BackupCategory.VOICEPRINT -> BackupSecrets.voiceprint(ctx)?.let { secrets += it }
            BackupCategory.FILES -> filesLeft = filesEntries(ctx, files(), videos, out)
            // 1.4, Lists: lists.json (the journal merged, never the sealed file), then Settings › Lists. No lists file yet: none.
            BackupCategory.LISTS -> {
                val f = lists(lists()) ?: throw ListsUnreadable()
                if (f.lists.isNotEmpty()) {
                    val json = BackupExport.lists(f)
                    out += Source.bytes(c, BackupCategory.LISTS_FILE, json.size.toLong()) { json }
                }
                small(c, ListsSettingsStore.file(ctx), BackupCategory.LISTS_SETTINGS)
            }
            // H3: private_events.json, the plain events (never the sealed file); none yet: nothing.
            // Locked: not now (unlock and try again); can't be opened at all: left out, so the rest still goes in.
            BackupCategory.CALENDAR -> when (val l = app.heyrobot.sys.PrivateEventsFiles.vault(ctx).load()) {
                app.heyrobot.sys.PrivateLoad.Unreadable, is app.heyrobot.sys.PrivateLoad.TooNew -> privateLeftOut = true
                app.heyrobot.sys.PrivateLoad.Locked -> throw PrivateUnreadable()
                is app.heyrobot.sys.PrivateLoad.Loaded -> if (l.file.events.isNotEmpty()) {
                    val f = l.file
                    val json = app.heyrobot.core.calendar.PrivateEventsCodec.encode(f).toByteArray(Charsets.UTF_8)
                    out += Source.bytes(c, BackupCategory.CALENDAR_FILE, json.size.toLong()) { json }
                }
            }
            // spoken-log: logbook.json (the journal merged, never the sealed file), then Settings › Logbook.
            BackupCategory.LOGBOOK -> {
                val l = logbook(ctx) ?: throw LogbookUnreadable()
                if (l.isNotEmpty()) {
                    val json = app.heyrobot.core.logbook.LogbookCodec.toJson(l).toByteArray()
                    out += Source.bytes(c, BackupCategory.LOGBOOK_FILE, json.size.toLong()) { json }
                }
                small(c, app.heyrobot.sys.LogbookFiles.settingsFile(ctx), BackupCategory.LOGBOOK_SETTINGS)
            }
            // Your music app's pin stays on this phone (trust is never in a backup).
            BackupCategory.EVERYDAY -> small(c, EverydaySettingsStore.file(ctx), BackupCategory.EVERYDAY_FILE, BackupExport::everyday)
            // H5: the Today page's order, pinned note and whether it is your home page, as written.
            BackupCategory.HOME -> small(c, app.heyrobot.sys.HomeSettingsStore.file(ctx), BackupCategory.HOME_FILE)
            // earbud-headsup-c: the heads-up's settings as written.
            BackupCategory.HEADSUP -> small(c, app.heyrobot.sys.HeadsUpStore.file(ctx), BackupCategory.HEADSUP_FILE)
            // moments-backup: reminders for a moment as written.
            BackupCategory.MOMENTS -> small(c, File(dir(ctx), BackupCategory.MOMENTS_FILE), BackupCategory.MOMENTS_FILE)
            BackupCategory.OPEN_APPS -> small(c, app.heyrobot.sys.OpenAppsStore.file(ctx), BackupCategory.OPEN_APPS_FILE)
            // L12: what :link hands over (never a key: core LinkBackupCodec), then Settings › Robot to Robot. Off, none of the first.
            // U1: on, but :link wouldn't say (the export refused or timed out): the whole category stays out and Done says so.
            BackupCategory.LINKS -> {
                val on = linksOn(ctx)
                val pairings = if (on) linkBackup(ctx) else null
                if (on && pairings == null) linksLeftOut = true
                else {
                    pairings?.let { b -> out += Source.bytes(c, BackupCategory.LINKS_FILE, b.size.toLong()) { b } }
                    small(c, app.heyrobot.sys.LinkSettingsStore.file(ctx), BackupCategory.LINK_SETTINGS)
                }
            }
            // C10: connections.json as saved (no key is ever in it), only while it reads whole; a restore decides what comes back.
            BackupCategory.CONNECTIONS -> if (ConnFiles.store(ctx).file() is ConnFile.Ok) small(c, connectionsFile(ctx), BackupCategory.CONNECTIONS_FILE)
            // C10: each connection's key, decrypted here, in :voice, straight into the backup's encryption (BackupSecrets).
            BackupCategory.SKILL_TRUST -> for ((name, f) in skillTrustFiles(ctx)) small(c, f, name)
            BackupCategory.CONN_KEYS -> {
                val k = BackupSecrets.connectionKeys(ctx)
                secrets += k.secrets
                unreadable += k.unreadable
            }
        }
        return Gathered(out, secrets, unreadable, linksLeftOut, filesLeft, codesLeftOut, privateLeftOut, transcriptsLeft)
    }
}
