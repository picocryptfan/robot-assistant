// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import app.heyrobot.core.EventPlan
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.sys.Calendars
import android.content.Context
import android.content.Intent
import android.util.LruCache
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileDeleteCode
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileMoveCode
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FileRuleCode
import app.heyrobot.core.files.FilingRule
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.NameWords
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParsedPixels
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.FileParseLink
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.FilesOut
import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.sys.OutCopy
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.RobotFiles
import app.heyrobot.sys.TravelReveal
import app.heyrobot.core.files.TravelMode
import app.heyrobot.sys.ExportResult
import app.heyrobot.sys.ExportSource
import app.heyrobot.sys.ExportStop
import app.heyrobot.sys.FilesExport
import app.heyrobot.sys.TreeExportPlace
import app.heyrobot.core.files.FileOutCode
import app.heyrobot.core.files.FilePack
import app.heyrobot.core.files.PackKind
import app.heyrobot.core.files.PackWords

// MAIN PROCESS. What the Files screens (FilesScreen.kt; files-1.4-plan.md §6, F7) ask of the phone, behind one interface
// so the tests run the screens on a real vault with a software key and a :files stand-in. Every call blocks: never on the
// main thread (the screens call them from Dispatchers.IO).
//
//   - The index through this process's vault (sys/FilesVault.kt): its lock gate, files.lock, a fresh key per write. Read
//     only once the Files tab shows (RobotFiles.use: the main app reads nothing of Robot Files before), never at start.
//   - Pages and thumbnails as raw pixels from :files (FileParseLink.pixels, D19), made Bitmaps only in sys/FilePixels.kt:
//     no decoder runs here. One link while the screen is in front, ended when it goes, when the phone locks and at
//     ON_STOP; at most 64 MiB of a file is ever sent to show its pages.
//   - A copy to hand to another app through sys/FilesOut.kt (Share, Open with another app).
//   - F8: your filing rules, in memory through sys/FilingMemory.kt: read for filing a file from here, and saved when a move
//     or your tags teach one (files-1.4-plan.md §4.5), worded in the app's language.

/** A file's page to show: [pixels] and how many pages the file has. */
class ShownPage(val pixels: ParsedPixels, val pages: Int)

/** What the Files screens ask of the phone. Every call may block: never on the main thread. */
interface FilesPort {
    /** The phone is locked, or a phone lock is on: nothing is read, shown or changed. */
    fun locked(): Boolean
    fun load(): IndexLoad
    fun update(change: (FileIndex) -> FileIndex): IndexLoad
    /** Deletes [ids] after their Undo ran out (each file's key destroyed first, core FileStore.delete). False when nothing was. */
    fun delete(ids: Collection<FileId>): Boolean
    fun settings(): FilesSettings
    /** The words a name needs in the menus' language ("due"). */
    fun words(): NameWords
    /** People's ids and names (People in Settings), for linking a file to a person. */
    fun people(): List<Pair<String, String>>
    /** [e]'s thumbnail as pixels (320 px), or null when it has none or it couldn't be shown. */
    fun thumb(e: FileEntry): ParsedPixels?
    /** [e]'s page [page] (from 0) as pixels, or null when it can't be shown. */
    fun page(e: FileEntry, page: Int): ShownPage?
    /** The type [e]'s bytes are (its provenance's sniff, else its first bytes'). */
    fun type(e: FileEntry): FileType
    /** A copy of [e] to hand out: [copy] ORIGINAL or STRIPPED, named [name], typed [mime]. */
    fun copy(e: FileEntry, copy: OutgoingCopy, name: String, mime: String, dangerous: Boolean): OutCopy
    /** The chooser that hands [c] to the app you pick ([open]: Open with another app, else Share). */
    fun handOut(c: OutCopy.Ready, open: Boolean, title: String): Intent
    /** Every copy handed out taken back (grants revoked, copies deleted); [id]'s only when given. */
    fun takeBack(id: FileId? = null)
    /** The screen went to the background ([stopped]) or came back ([resumed]): a copy out is taken back on the way back. */
    fun stopped()
    fun resumed()
    /** :files' link for this screen ends (it goes, the phone locks, the app goes to the background). */
    fun endReader()
    /** Robot Files changed here: :voice reads it again (and files what came in). */
    fun changed()
    /** The voice log: FILE_MOVE and FILE_DELETE, codes and counts only. */
    fun logMove(code: FileMoveCode, n: Int)
    fun logDelete(code: FileDeleteCode, n: Int)

    /** F8: your filing rules; none while Memory is off or closed. */
    fun rules(): List<FilingRule>
    /** F8: your drawer [old] was renamed [new]: your rules that name it follow. */
    fun renameRules(old: String, new: String)
    /** F8: a rule your move or your tags taught, kept in memory (Memory off or full: not kept, and the result says so). */
    fun saveRule(rule: MemoryFiling): FilingMemory.Saved
    /** Its Undo: the rule goes (and an older one it replaced comes back); the move or the tag stays. */
    fun undoRule(saved: FilingMemory.Saved.Kept): Boolean
    /** The voice log's FILE_RULE: a code, never the rule's words. */
    fun logRule(code: FileRuleCode)

    /**
     * F10: the calendar app's own editor for an all-day event [title] on [date] (you save it there; Robot writes nothing to the
     * calendar), or null when no calendar app takes it.
     */
    fun calendar(title: String, date: java.time.LocalDate): Intent? = null

    /**
     * F12: [kind]'s pack for [year], worked out again from the index behind the gate, copied into the folder [tree] you just
     * picked (sys/FilesExport): each file as Share sends it, then the table. Never without your tap and your pick.
     */
    fun export(kind: PackKind, year: Int, tree: android.net.Uri): ExportResult = ExportResult.Stopped(ExportStop.FAILED, 0)

    /** F12: your drawer [old] was renamed [new]: hidden under its new name too, when travel mode hides it. */
    fun renameHidden(old: String, new: String) {}

    /** F12: the voice log's FILE_FIND (a pack's page shown): a code and a count. */
    fun logFind(code: FileFindCode, n: Int) {}
}

/** The phone's: this process's vault, :files through one link, People, sys/FilesOut. */
class PhoneFilesPort(ctx: Context) : FilesPort {
    private val app = ctx.applicationContext
    private val vault = RobotFiles.vault(app)
    private val reader = FilesReader(app)
    /** Thumbnails shown lately (each at most 320 by 320), by file id: dropped when the screen locks or goes. */
    private val thumbs = object : LruCache<FileId, ParsedPixels>(48) {}

    init {
        RobotFiles.use(app)
    }

    override fun locked(): Boolean = PhoneLockNow.on(app) || RobotFiles.locked(app)
    // F12: the screens see the index as travel mode shows it (core TravelMode.shown; TravelReveal after your PIN), the same
    // view object while nothing changed (the screen asks every second); a change runs on the whole index (the vault's).
    override fun load(): IndexLoad = shown(vault.load())
    override fun update(change: (FileIndex) -> FileIndex): IndexLoad = shown(vault.update(change))

    private var viewOf: Triple<IndexLoad, Any, IndexLoad>? = null

    @Synchronized private fun shown(l: IndexLoad): IndexLoad {
        val idx = l.current ?: return l
        val s = settings()
        val key = Triple(s.travel, s.hidden to s.hiddenUnknown, TravelReveal.on())
        viewOf?.let { (src, k, v) -> if (src === l && k == key) return v }
        val v = TravelMode.shown(idx, s, key.third).let { if (it === idx) l else IndexLoad.Loaded(it) }
        viewOf = Triple(l, key, v)
        return v
    }

    override fun delete(ids: Collection<FileId>): Boolean {
        for (id in ids) FilesOut.revoke(app, id)
        ids.forEach(thumbs::remove)
        return try { (vault.delete(ids)?.removed ?: 0) > 0 } catch (t: Exception) { RLog.w("files_delete", t); false }
    }

    override fun settings(): FilesSettings = FilesSettingsStore.current(app)
    override fun words(): NameWords = NameWords(FilesStrings.nameDue().render(AppLanguage.langs(app)))
    override fun people(): List<Pair<String, String>> = try { PeopleFiles.store(app).list().map { it.id to it.name } } catch (_: Exception) { emptyList() }

    override fun thumb(e: FileEntry): ParsedPixels? {
        if (!e.thumb || locked()) return null
        thumbs.get(e.id)?.let { return it }
        val jpeg = try { vault.thumb(e.id) } catch (_: FileException) { null } ?: return null
        val px = (reader.pixels(jpeg, thumb = true, page = 0) as? ParseResult.Ok)?.out?.pixels
        jpeg.fill(0)
        return px?.also { thumbs.put(e.id, it) }
    }

    override fun page(e: FileEntry, page: Int): ShownPage? {
        if (locked() || e.from.size !in 1..ParseLimits.MAX_INPUT) return null
        val bytes = try { vault.open(e.id).use { it.readAll(ParseLimits.MAX_INPUT.toLong()) } } catch (_: FileException) { return null }
        val r = reader.pixels(bytes, thumb = false, page = page)
        bytes.fill(0)
        val out = (r as? ParseResult.Ok)?.out ?: return null
        return out.pixels?.let { ShownPage(it, out.pages.coerceAtLeast(1)) }
    }

    override fun type(e: FileEntry): FileType = FileOut.typeOf(e)?.takeIf { it != FileType.UNKNOWN } ?: try {
        vault.open(e.id).use { FileSniff.sniff(it.readNBytes(FileSniff.PREFIX), e.from.size) }
    } catch (_: Exception) { FileType.UNKNOWN }

    override fun copy(e: FileEntry, copy: OutgoingCopy, name: String, mime: String, dangerous: Boolean): OutCopy =
        FilesOut.make(app, vault, e.id, e.from.size, copy, name, mime, dangerous, ::locked, strip = { bytes -> reader.strip(bytes) })

    override fun handOut(c: OutCopy.Ready, open: Boolean, title: String): Intent =
        (if (open) FilesOut.openIntent(app, c, title) else FilesOut.shareIntent(app, c, title)).also { FilesOut.handed(app, c, open) }

    override fun takeBack(id: FileId?) = if (id == null) FilesOut.revokeAll(app) else FilesOut.revoke(app, id)
    override fun stopped() = FilesOut.stopped()
    override fun resumed() = FilesOut.resumed(app)

    override fun endReader() {
        reader.end()
        thumbs.evictAll()
    }

    override fun changed() = Cmd.send(app, Cmd.FILES)
    override fun logMove(code: FileMoveCode, n: Int) = log(VoiceEvent.FILE_MOVE, code.id, n)
    override fun logDelete(code: FileDeleteCode, n: Int) = log(VoiceEvent.FILE_DELETE, code.id, n)
    override fun rules(): List<FilingRule> = FilingMemory.rules(app)
    override fun renameRules(old: String, new: String) { FilingMemory.renameDrawer(app, old, new) }
    override fun saveRule(rule: MemoryFiling): FilingMemory.Saved =
        FilingMemory.save(app, rule, AppLanguage.langs(app).ui, MemorySource.SETTINGS)
    override fun undoRule(saved: FilingMemory.Saved.Kept): Boolean = FilingMemory.undo(app, saved)
    override fun logRule(code: FileRuleCode) = log(VoiceEvent.FILE_RULE, code.id, 1)
    override fun calendar(title: String, date: java.time.LocalDate): Intent? {
        val zone = java.time.ZoneId.systemDefault()
        val plan = EventPlan(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone), allDay = true, calendarId = null, calendarName = null,
            currentYear = java.time.LocalDate.now().year)
        val i = Calendars.insertIntent(title, plan).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        log(VoiceEvent.FILE_FIND, FileFindCode.CALENDAR.id, 1)
        return i.takeIf { app.packageManager.resolveActivity(it, 0) != null }
    }

    override fun logFind(code: FileFindCode, n: Int) = log(VoiceEvent.FILE_FIND, code.id, n)

    // FX3: over the file as it is now, under its lock (FilesSettingsStore.rename): the new name takes the old one's place.
    override fun renameHidden(old: String, new: String) { FilesSettingsStore.rename(app, old, new) }

    override fun export(kind: PackKind, year: Int, tree: android.net.Uri): ExportResult {
        if (locked()) return ExportResult.Stopped(ExportStop.LOCKED, 0)
        val idx = load().current ?: return ExportResult.Stopped(ExportStop.LOCKED, 0)
        val pack = FilePack.of(idx, kind, year)
        val langs = AppLanguage.langs(app)
        val words = PackWords(FilesStrings.csvColumns().map { it.render(langs) }, { k -> app.getString(kindRes(k)) }, { d -> FilesStrings.drawer(d).render(langs) },
            FilesStrings.csvCheckTotal().render(langs), FilesStrings.csvNotCopied().render(langs))
        val src = ExportSource(open = { id -> vault.open(id) }, type = ::type, strip = { b -> reader.strip(b) }, locked = ::locked,
            stripLocation = settings().stripLocation)
        val r = try { FilesExport.run(pack, TreeExportPlace(app, tree), src, words, langs) } finally { reader.end() }
        when (r) {
            is ExportResult.Done -> log(VoiceEvent.FILE_OUT, FileOutCode.EXPORTED.id, r.copied, r.leftOut)
            is ExportResult.Stopped -> log(VoiceEvent.FILE_OUT, FileOutCode.EXPORTED.id, r.copied, pack.files.size - r.copied)
        }
        return r
    }

    private fun log(e: VoiceEvent, a: Int, b: Int, c: Int = 0) {
        try {
            VoiceLog.init(Paths.voiceLogMain(app))
            VoiceLog.add(e, a, b, c)
        } catch (_: Throwable) {}
    }
}

/** :files for the Files screens: one FileParseLink, made again when the last one ended (as scanning's). */
class FilesReader(private val ctx: Context) {
    private var link: FileParseLink? = null

    @Synchronized private fun link(): FileParseLink? {
        link?.takeIf { it.isAlive }?.let { return it }
        val l = FileParseLink(ctx.applicationContext)
        if (!l.start()) return null
        link = l
        return l
    }

    fun pixels(bytes: ByteArray, thumb: Boolean, page: Int): ParseResult = link()?.pixels(bytes, thumb, page) ?: ParseResult.Failed(ParseFailure.UNAVAILABLE)

    fun strip(bytes: ByteArray): ParseResult = link()?.strip(bytes) ?: ParseResult.Failed(ParseFailure.UNAVAILABLE)

    @Synchronized fun end() {
        link?.end("files_screen")  // l10n: not text (a log code)
        link = null
    }
}
