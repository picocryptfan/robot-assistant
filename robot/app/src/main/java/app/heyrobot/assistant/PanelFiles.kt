// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.sys.FilesSettingsStore
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.files.TravelMode
import android.content.Context
import android.graphics.Bitmap
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileMoveCode
import app.heyrobot.core.files.FilingOutcome
import app.heyrobot.core.files.FilingRun
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileThis
import app.heyrobot.core.files.FileFind
import app.heyrobot.core.files.FileFindCode
import app.heyrobot.core.files.FileDeleteCode
import app.heyrobot.core.files.FileRuleCode
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FilesCommand
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.FilingRules
import app.heyrobot.core.files.IntakeNames
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.FileDeletes
import app.heyrobot.core.files.PendingDeletes
import app.heyrobot.sys.IntakeStep
import java.io.ByteArrayOutputStream
import java.time.ZoneId

// VOICE PROCESS. "File this" in the panel (files-1.4-plan.md §3.3, F5): your own words ("file this", « classe ça », core
// FileThis), typed or said, put what the panel holds into Robot Files' Inbox, with no card (your words asked). What it holds:
// the photo you attached, else the screen you attached or the screenshot Android sent this panel, else the last photo
// taken in this panel. A photo is filed as its own bytes, at full resolution (the model got a 1,024 px copy), held in RAM
// only until the panel closes; the screenshot is Robot's own Bitmap, encoded as a PNG here (encoding, never decoding).
// Nothing of it is read or decoded in :voice: F6's filing reads it in :files.
//
// Refused during a phone lock and while the phone is locked (FilesIntake.gate), before anything is taken. A duplicate is
// said with its place ("You already have this file: Inbox › …"), and only "file it anyway" adds it; the panel closing, or
// another "file this", discards it.
//
// F6: once in, it is filed at once (FilingWork.fileNow: your words, so Ask me first doesn't wait), and the reply says where:
// "Filed in Receipts." with a card naming the file, Move and Undo (§3); or "It's in your Inbox." and Robot's question or
// reason. The reply's text names only the drawer, since History keeps it in plain; the file's name is on the card (History
// keeps no card without a tool) and in what Robot says (never a medical file's or an ID's name aloud). Move asks for a
// drawer and takes your next words; Undo puts the file back in Inbox, or a move back where it was.
//
// F8: the rest of Robot Files' words (core FilesRouter), each your own, so no card asks first, except deleting:
//   - a rule ("Esso receipts go under Car"): saved in memory at once (sys/FilingMemory), confirmed once, with Undo; a drawer
//     it names that doesn't exist is made, since your words asked. Memory off or full: not kept, and the reply says so.
//     The reply's text says a rule was kept; its words are on the card and in what Robot says (History keeps the text);
//   - a move ("move the Costco receipt to Taxes"): only when exactly one file matches and the drawer exists; else the
//     reply says how many match and a card opens Files to choose. Never a guess;
//   - find and "what's in my Inbox": counts and a drawer in the text, the best match's name on a card that opens Files;
//   - delete ("delete the Costco receipt"): a card naming the file with Delete and Keep, answered only by a tap (D9,
//     AskWhy.DELETE: a spoken yes never deletes; PanelController.confirm hands the tap here). One file only.

internal object PanelFiles {
    /** What "file this" would file: a screenshot, a photo's own bytes, or neither ([tooBig]: the photo was over the cap). */
    class What(val shot: Bitmap?, val photo: ByteArray?, val tooBig: Boolean)

    /**
     * Robot's reply: [text] shown (and kept in History), [spoken], and the card under it ([card]: the file, Move and Undo);
     * F10: [more] cards after it (a reminder's, one per date coming up).
     */
    class Reply(val text: Phrase, val spoken: Phrase = text, val card: UiAction? = null, val more: List<UiAction> = emptyList()) {
        val cards: List<UiAction> get() = listOfNotNull(card) + more
    }

    /** Undo keys and Move's hand-off start so. */
    private const val UNDO = "files:"
    private const val MOVE = "files-move:"
    /** Show in Files' hand-off; a delete card's id. */
    private const val OPEN = "files-open"
    private const val DELETE = "files-del-"
    /** F10: a reminder card's id (its Add to my brief and Not now come to [remindTapped]) and its Add to calendar hand-off. */
    private const val REMIND = "files-rem-"
    private const val CALENDAR = "files-cal:"
    /** How long Move waits for your words. */
    private const val MOVE_MS = 120_000L

    /** What each card's Undo puts back: [was] null, Robot's filing undone; else the file as it was before your move. */
    private class Undo(val id: FileId, val was: FileEntry?)
    private val undos = HashMap<String, Undo>()
    /** A rule's Undo: what was saved, and the drawer its words made (null: it existed). */
    private val ruleUndos = HashMap<String, Pair<FilingMemory.Saved.Kept, String?>>()
    /** Delete cards waiting for a tap: the file each names. */
    private val deletes = HashMap<String, FileId>()
    private val moves = HashMap<String, FileId>()
    /** F10: reminder cards waiting: the file and which of its dates each names. */
    private val reminds = HashMap<String, Pair<FileId, app.heyrobot.core.files.DateKind>>()
    @Volatile private var moving: Pair<FileId, Long>? = null
    private val random = java.security.SecureRandom()

    private fun token(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random token)

    /** The last photo taken or picked in this panel, as its own bytes; null when there is none or it was too big. */
    @Volatile private var original: ByteArray? = null
    @Volatile private var originalTooBig = false
    /** A duplicate waiting for "file it anyway", until the panel closes. */
    @Volatile private var waiting: IntakeStep.Duplicate? = null

    /**
     * Your words, when they are Robot Files' (core FilesRouter: "file this", a rule, a move, find, Inbox, delete) or answer
     * Move's question. "Move it to …" means the file this panel last filed or moved, only while there is one.
     */
    fun route(q: String): FilesCommand? {
        FilesRouter.route(q, recent = lastMoving != null, yours = yours, out = outOfSight)?.let { moving = null; return it }
        val m = moving ?: return null
        moving = null
        if (System.currentTimeMillis() > m.second) return null
        return FilesCommand.File(FileThis.Heard(FileThis.Ask.MOVE, if (FRENCH.containsMatchIn(app.heyrobot.core.files.FileFields.fold(q))) Lang.FR else Lang.EN, q))
    }

    /** Your drawers' names, as filing last saw the index (FilingWork): only to know a move's drawer exists. Names, no files. */
    @Volatile private var yours: List<String> = emptyList()

    /** Filing or the panel read the index: your drawers' names, for [route]. */
    fun seen(idx: FileIndex?) {
        yours = idx?.let(FileViews::yours).orEmpty()
        outOfSight = idx?.outOfSight.orEmpty()
    }

    /** F12: the drawers travel mode hides, as filing last saw them ([seen] gets the view): never a move's or a rule's drawer by voice. */
    @Volatile private var outOfSight: Set<String> = emptySet()

    /** Words that make Move's answer French (« dans », « tiroir », a starter drawer's French name). */
    private val FRENCH = Regex("(?<![\\p{L}])(dans|tiroir|recus|factures|impots|sante|voyages|medias|mets|range)(?![\\p{L}])") // l10n: not text (words Robot hears)

    /** Whether [key] is a files card's Undo. */
    fun isUndo(key: String?): Boolean = key?.startsWith(UNDO) == true

    /** Whether [handOff] is a files card's Move. */
    fun isMove(handOff: String?): Boolean = handOff?.startsWith(MOVE) == true

    /** Whether [handOff] opens the Files tab (find, Inbox, several matches; F12: a tax pack's or an expense report's page). */
    fun isOpen(handOff: String?): Boolean = handOff == OPEN || handOff?.let(app.heyrobot.sys.AppPages::filesPack) != null

    /** The Files tab, as a filing notification's tap opens it; F12: a pack's page for its [handOff] (AppPages.filesPack). */
    fun openIntent(ctx: Context, handOff: String? = null): android.content.Intent = android.content.Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
        .putExtra(app.heyrobot.sys.AppPages.EXTRA, handOff?.takeIf { app.heyrobot.sys.AppPages.filesPack(it) != null } ?: app.heyrobot.sys.AppPages.FILES)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A files card's button for its hand-off (Move, Show in Files, Add to calendar); null for any other. */
    fun label(handOff: String?): Phrase? = when {
        isMove(handOff) -> FilesStrings.move()
        isOpen(handOff) -> FilesStrings.showInFiles()
        isCalendar(handOff) -> FilesStrings.addToCalendar()
        else -> null
    }

    /** F10: whether [actionId] is a reminder card (its Add to my brief and Not now come to [remindTapped]). */
    fun isRemind(actionId: String?): Boolean = actionId?.startsWith(REMIND) == true

    /** F10: whether [handOff] is a reminder card's Add to calendar: add_event's own card, which only a tap answers (PanelController). */
    fun isCalendar(handOff: String?): Boolean = handOff?.startsWith(CALENDAR) == true

    /** Whether [actionId] is a delete card (its Delete and Keep come to [deleteTapped]). */
    fun isDelete(actionId: String?): Boolean = actionId?.startsWith(DELETE) == true

    /** A delete card's tap answered: its new [status] and [line], and FX2's [undo] key while its Undo runs. */
    data class Tapped(val status: ActionStatus, val line: String, val undo: String? = null)

    /**
     * Delete or Keep tapped on a delete card (a background thread; PanelController.confirm, from the card's buttons only: a
     * spoken yes never comes here, the card being tap-only). The card's new state and line. Refused while the phone is locked
     * or during a phone lock (the card waits for another tap). [delete]: the vault's, your tap having been checked.
     *
     * FX2 (files-1.4-review.md K1): Delete starts the 10 seconds of its Undo ([held], this process's holder, core
     * PendingDeletes): the file leaves the panel's answers at once and goes when its Undo runs out, or when the panel closes
     * or the screen goes off. [ended] hears whether it went (the card's Undo goes then).
     */
    fun deleteTapped(ctx: Context, actionId: String, yes: Boolean, langs: Langs, gate: () -> Phrase? = { FilesIntake.gateText(FilesIntake.gate(ctx)) },
        delete: (FileId) -> Boolean = { id -> (app.heyrobot.sys.RobotFiles.vault(ctx).delete(listOf(id))?.removed ?: 0) > 0 },
        held: PendingDeletes = FileDeletes.here, ended: (Boolean) -> Unit = {}): Tapped {
        val token = actionId.removePrefix(DELETE)
        if (!yes) {
            synchronized(this) { deletes.remove(token) }
            return Tapped(ActionStatus.DECLINED, FilesStrings.kept().render(langs))
        }
        gate()?.let { return Tapped(ActionStatus.PENDING, it.render(langs)) }
        // A card from before the panel closed, or tapped twice: nothing is deleted.
        val id = synchronized(this) { deletes.remove(token) } ?: return Tapped(ActionStatus.DECLINED, FilesStrings.kept().render(langs))
        val waiting = held.start(listOf(id), delete = { ids -> try { delete(ids.single()) } catch (t: Exception) { RLog.w("files_panel_delete", t); false } }) { ok ->
            VoiceLog.add(VoiceEvent.FILE_DELETE, (if (ok) FileDeleteCode.DELETED else FileDeleteCode.NOT_NOW).id, if (ok) 1 else 0)
            if (ok && lastMoving == id) lastMoving = null
            ended(ok)
        }
        return Tapped(ActionStatus.DONE, FilesStrings.deleted().render(langs), UNDO_DELETE + waiting)
    }

    /** FX2: a delete card's Undo key (a files card's Undo, [isUndo]): the holder's token after it. */
    private const val UNDO_DELETE = UNDO + "del-"

    /** Move tapped: the next words name the drawer. The card's line, asking. */
    fun startMove(handOff: String): Phrase? {
        val id = synchronized(this) { moves[handOff.removePrefix(MOVE)] } ?: return null
        moving = id to System.currentTimeMillis() + MOVE_MS
        lastMoving = id
        return FilesStrings.moveAsk("drawers" to Arg.Sub(FilesStrings.drawers()))
    }

    /** Undo on a files card (a background thread): the card's new line. */
    fun undo(ctx: Context, key: String, langs: Langs, change: ((FileIndex) -> FileIndex?) -> Boolean = { FilingWork.change(ctx, it) },
        unsave: (FilingMemory.Saved.Kept) -> Boolean = { FilingMemory.undo(ctx, it) }, held: PendingDeletes = FileDeletes.here): String {
        // FX2 (K1): a delete's Undo, while its 10 seconds run: nothing was deleted, the file is back.
        if (key.startsWith(UNDO_DELETE)) {
            if (!held.undo(key.removePrefix(UNDO_DELETE))) return FilesStrings.notUndone().render(langs)
            VoiceLog.add(VoiceEvent.FILE_DELETE, FileDeleteCode.UNDONE.id, 1)
            return FilesStrings.undeleted().render(langs)
        }
        synchronized(this) { ruleUndos.remove(key.removePrefix(UNDO)) }?.let { (saved, made) ->
            if (!unsave(saved)) return FilesStrings.notUndone().render(langs)
            VoiceLog.add(VoiceEvent.FILE_RULE, FileRuleCode.UNDONE.id, 0)
            // A drawer your rule made, still empty, goes with it.
            made?.let { d -> change { FileViews.removeDrawer(it, d) } }
            return FilesStrings.ruleDroppedSaid().render(langs)
        }
        val u = synchronized(this) { undos.remove(key.removePrefix(UNDO)) } ?: return FilesStrings.notUndone().render(langs)
        val ok = change { idx -> if (u.was == null) FilingRun.undo(idx, u.id) else FilingRun.restore(idx, u.id, u.was) }
        if (ok) VoiceLog.add(VoiceEvent.FILE_MOVE, (if (u.was == null) FileMoveCode.UNDO else FileMoveCode.PUT_BACK).id, 1)
        return (if (!ok) FilesStrings.notUndone() else if (u.was == null) FilesStrings.backInInbox() else FilesStrings.putBack()).render(langs)
    }

    /** A photo came from the camera or the picker: its own bytes ([tooBig] when over what Robot reads). */
    fun picked(bytes: ByteArray?, tooBig: Boolean) {
        original = bytes
        originalTooBig = tooBig
    }

    /** What the panel holds now: the attached photo; the attached screen or Android's screenshot; else the panel's last photo. */
    fun what(draft: Attachment, shot: Bitmap?): What = when (draft) {
        Attachment.PHOTO -> What(null, original, originalTooBig)
        Attachment.SCREEN -> What(shot, null, false)
        Attachment.NONE -> if (original != null || originalTooBig) What(null, original, originalTooBig) else What(shot, null, false)
    }

    /**
     * Robot's answer to [heard] with [what]; blocks (the copy, the seal, the filing): never on the main thread. [fileNow]
     * files an entry that just came in (FilingWork's, :files and the model); [change] changes the index (a move).
     */
    fun file(ctx: Context, heard: FileThis.Heard, what: What, langs: Langs, now: Long = System.currentTimeMillis(),
        gate: () -> Phrase? = { FilesIntake.gateText(FilesIntake.gate(ctx)) }, intakeOf: () -> FilesIntake = { FilesIntake.phone(ctx) },
        fileNow: (FileId) -> FilingOutcome? = { FilingWork.fileNow(ctx, it) },
        change: ((FileIndex) -> FileIndex?) -> Boolean = { FilingWork.change(ctx, it) },
        index: () -> FileIndex? = { app.heyrobot.sys.RobotFiles.shown(ctx) }): Reply =
        file(ctx, FilesCommand.File(heard), what, langs, now, gate, intakeOf, fileNow, change, index)

    /**
     * Robot's answer to [cmd] (FilesRouter's), blocking: never on the main thread. [spoken]: said, not typed (a rule's
     * memory item says which); [save]: a rule into memory (sys/FilingMemory). Everything waits behind the gate: a locked
     * phone or a phone lock hears only that.
     */
    fun file(ctx: Context, cmd: FilesCommand, what: What, langs: Langs, now: Long = System.currentTimeMillis(),
        gate: () -> Phrase? = { FilesIntake.gateText(FilesIntake.gate(ctx)) }, intakeOf: () -> FilesIntake = { FilesIntake.phone(ctx) },
        fileNow: (FileId) -> FilingOutcome? = { FilingWork.fileNow(ctx, it) },
        change: ((FileIndex) -> FileIndex?) -> Boolean = { FilingWork.change(ctx, it) },
        index: () -> FileIndex? = { app.heyrobot.sys.RobotFiles.shown(ctx) },
        spoken: Boolean = true,
        save: (app.heyrobot.core.memory.MemoryFiling, Lang) -> FilingMemory.Saved = { f, l ->
            FilingMemory.save(ctx, f, l, if (spoken) app.heyrobot.core.memory.MemorySource.SAID else app.heyrobot.core.memory.MemorySource.TYPED, now) },
        settings: () -> FilesSettings = { app.heyrobot.sys.FilesSettingsStore.current(ctx) }): Reply {
        gate()?.let { return Reply(it) }
        travelNow = settings()
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (cmd) {
            is FilesCommand.File -> { lastFiled = null; fileThis(cmd.heard, what, langs, now, intakeOf, fileNow, change, index).let { r -> withReminder(r, settings, langs, today) } }
            is FilesCommand.Spend -> spend(cmd, index, today)
            is FilesCommand.Upcoming -> upcoming(cmd, langs, index, settings(), today)
            is FilesCommand.Subscriptions -> subscriptions(index, today)
            is FilesCommand.Rule -> rule(cmd, langs, change, index, save)
            is FilesCommand.Move -> moveNamed(cmd, langs, change, index, now)
            is FilesCommand.Find -> find(cmd, langs, index)
            is FilesCommand.Inbox -> inbox(langs, index)
            is FilesCommand.Delete -> deleteCard(cmd, langs, index)
            is FilesCommand.Pack -> pack(cmd, langs, index, today)
        }
    }

    /**
     * F12, "make my 2026 tax pack": what would go, in counts, and a card that opens its page in Files. Nothing is exported
     * here: only your tap on that page's Export, then the folder you pick, copies anything out (§7.2).
     */
    private fun pack(c: FilesCommand.Pack, langs: Langs, index: () -> FileIndex?, today: java.time.LocalDate): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        val year = c.year ?: app.heyrobot.core.files.FilePack.defaultYear(today)
        val p = app.heyrobot.core.files.FilePack.of(idx, c.kind, year)
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.PACK.id, p.files.size)
        val y = year.toString()
        val tax = c.kind == app.heyrobot.core.files.PackKind.TAX
        if (p.empty) return Reply(if (tax) FilesStrings.packEmptyTax("year" to y) else FilesStrings.packEmptyExpenses("year" to y))
        val text = (if (tax) FilesStrings.packTax else FilesStrings.packExpenses)("year" to y, "what" to Arg.Sub(FilesStrings.kinds(p.files.map { it.kind })))
        val title = (if (tax) FilesStrings.packTitleTax else FilesStrings.packTitleExpenses)("year" to y).render(langs)
        val card = UiAction("files-${token()}", null, title, ActionStatus.DONE, text.render(langs), // l10n: not text (a card's id)
            handOff = app.heyrobot.sys.AppPages.packRoute(c.kind.name.lowercase(), year), files = true)
        return Reply(text, text, card)
    }

    private fun fileThis(heard: FileThis.Heard, what: What, langs: Langs, now: Long, intakeOf: () -> FilesIntake, fileNow: (FileId) -> FilingOutcome?,
        change: ((FileIndex) -> FileIndex?) -> Boolean, index: () -> FileIndex?): Reply {
        if (heard.ask == FileThis.Ask.MOVE) return move(heard.words.orEmpty(), langs, change, index, now)
        val intake = intakeOf()
        val said: (IntakeStep) -> Reply = { said(it, langs, fileNow) }
        if (heard.ask == FileThis.Ask.ANYWAY) {
            val d = waiting ?: return Reply(FilesStrings.nothingWaiting())
            waiting = null
            return said(intake.anyway(d))
        }
        // A new "file this" replaces a question still waiting.
        waiting?.let(intake::drop)
        waiting = null
        val stamp = IntakeNames.stamp(now, ZoneId.systemDefault())
        val photo = what.photo
        val shot = what.shot
        val step = when {
            photo != null -> intake.add(photo) { t -> FileIntake(FilesStrings.namePhoto("when" to stamp).render(langs) + ext(t), FileSource.PANEL, type = t.mime) }
            what.tooBig -> return Reply(FilesStrings.photoTooBig())
            shot != null -> {
                val png = png(shot) ?: return Reply(FilesStrings.problem(app.heyrobot.core.files.IntakeProblem.IO))
                intake.add(png) { t -> FileIntake(FilesStrings.nameScreenshot("when" to stamp).render(langs) + ext(t), FileSource.PANEL, type = t.mime) }
            }
            else -> return Reply(FilesStrings.nothingToFile())
        }
        return said(step)
    }

    private fun said(s: IntakeStep, langs: Langs, fileNow: (FileId) -> FilingOutcome?): Reply = when (s) {
        is IntakeStep.Added -> {
            if (s.entry.kind == FileKind.DANGEROUS) Reply(FilesStrings.inInbox("why" to Arg.Sub(FilesStrings.dangerous())))
            else filed(fileNow(s.entry.id), langs)
        }
        is IntakeStep.Duplicate -> {
            waiting = s
            // FX2 (files-1.4-review.md finding 12): History keeps the shown text, so it says where, never the file's name (the
            // sender's words, up to 200 characters, kept as Robot's own); the name is only said, as Robot says a file. In a
            // drawer travel mode hides: neither the drawer nor the name.
            if (TravelMode.hides(s.of, travelNow)) Reply(FilesStrings.duplicateHidden())
            else {
                val where = s.of.drawer?.let(FilesStrings::drawer) ?: FilesStrings.yourInbox()
                Reply(FilesStrings.duplicateShown("where" to Arg.Sub(where)), FilesStrings.duplicateSay("where" to Arg.Sub(where), "name" to Arg.Sub(app.heyrobot.core.files.FileMoneyText.said(s.of))))
            }
        }
        is IntakeStep.Failed -> Reply(FilesStrings.problem(s.problem, s.need))
    }

    /** F12: Settings › Files as travel mode stood when your words came in ([file]); FX1: TravelMode.hidden reads it. */
    @Volatile private var travelNow: FilesSettings = FilesSettings()

    /** What filing the file came to, in words: filed (with the card), or in Inbox and why. */
    private fun filed(o: FilingOutcome?, langs: Langs): Reply {
        fun inbox(why: Phrase) = Reply(FilesStrings.inInbox("why" to Arg.Sub(why)))
        return when (o) {
            // F12: filed in a drawer travel mode hides: said without the drawer, the name or a card (it stays out of sight).
            is FilingOutcome.Filed -> if (app.heyrobot.core.files.TravelMode.hidden(o.placement.drawer, travelNow)) { lastFiled = null; Reply(FilesStrings.filedQuiet()) } else {
                lastFiled = o.entry
                val drawer = FilesStrings.drawer(o.placement.drawer)
                val text = FilesStrings.filedIn("drawer" to Arg.Sub(drawer))
                // A medical file's or an ID's name is never said aloud (memory's rule for secrets): only the drawer.
                val quiet = o.placement.kind == FileKind.MEDICAL || o.placement.kind == FileKind.ID
                // FX2 (F13's note): the file as Robot says one ("a receipt from Costco on August 14, 2026"), never its name read raw.
                val name = if (quiet) null else app.heyrobot.core.files.FileMoneyText.more(o.entry)
                val spoken = if (name == null) text else FilesStrings.filedInSay("drawer" to Arg.Sub(drawer), "name" to Arg.Sub(name))
                Reply(text, spoken, card(o.entry, text.render(langs), langs, was = null))
            }
            is FilingOutcome.Asked -> inbox(FilesStrings.why(o.why, o.entry.filing?.choices.orEmpty()))
            is FilingOutcome.Held -> inbox(FilesStrings.why(o.why))
            is FilingOutcome.Waiting -> inbox(FilesStrings.why(o.why))
            FilingOutcome.Locked -> Reply(FilesStrings.locked())
            else -> inbox(FilesStrings.waitLater())
        }
    }

    /** The card under a reply: the file's name, [line], Move and Undo ([was]: what Undo puts back after a move). */
    private fun card(e: FileEntry, line: String, langs: Langs, was: FileEntry?): UiAction {
        val undo = token()
        val move = token()
        synchronized(this) {
            undos[undo] = Undo(e.id, was)
            moves[move] = e.id
        }
        // "Move it to …" means this one from now on.
        lastMoving = e.id
        return UiAction("files-$undo", null, e.name, ActionStatus.DONE, line, undo = UNDO + undo, handOff = MOVE + move, files = true) // l10n: not text (a card's id)
    }

    /** Your words after Move: the drawer they name (a starter drawer in either language, or one of yours), and the move. */
    private fun move(words: String, langs: Langs, change: ((FileIndex) -> FileIndex?) -> Boolean, index: () -> FileIndex?, now: Long): Reply {
        val id = lastMoving ?: return Reply(FilesStrings.nothingWaiting())
        val idx = index() ?: return Reply(FilesStrings.locked())
        val was = idx.entry(id) ?: return Reply(FilesStrings.notUndone())
        val drawer = drawerIn(words, idx) ?: return Reply(FilesStrings.moveNoDrawer("drawers" to Arg.Sub(FilesStrings.drawers())))
        if (!change { FilingRun.move(it, id, drawer, now) }) return Reply(FilesStrings.notUndone())
        VoiceLog.add(VoiceEvent.FILE_MOVE, FileMoveCode.MOVED.id, 1)
        val text = FilesStrings.moved("drawer" to Arg.Sub(FilesStrings.drawer(drawer)))
        return Reply(text, text, card(was.copy(drawer = drawer), text.render(langs), langs, was))
    }

    // F8's words.

    private fun ruleCode(s: FilingMemory.Saved): FileRuleCode = when (s) {
        is FilingMemory.Saved.Kept -> FileRuleCode.SAVED
        is FilingMemory.Saved.Already -> FileRuleCode.ALREADY
        FilingMemory.Saved.Off -> FileRuleCode.OFF
        FilingMemory.Saved.Full -> FileRuleCode.FULL
        FilingMemory.Saved.NotOpen -> FileRuleCode.NOT_OPEN
    }

    /** "Esso receipts go under Car": the rule kept (a drawer it names that doesn't exist made, your words asking), with Undo. */
    private fun rule(c: FilesCommand.Rule, langs: Langs, change: ((FileIndex) -> FileIndex?) -> Boolean, index: () -> FileIndex?,
        save: (app.heyrobot.core.memory.MemoryFiling, Lang) -> FilingMemory.Saved): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val have = FilesRouter.drawer(c.drawer, FileViews.yours(idx))
        val drawer = have ?: FilesRouter.newDrawer(c.drawer)?.takeIf { FileViews.drawerProblem(idx, it) == null }
            ?: return Reply(FilesStrings.moveNoDrawer("drawers" to Arg.Sub(FilesStrings.drawers())))
        val merchant = app.heyrobot.core.memory.MemoryFiling.cleanWords(c.merchant) ?: return Reply(FilesStrings.whichFile())
        val f = app.heyrobot.core.memory.MemoryFiling(kind = c.kind.name, merchant = merchant, drawer = drawer)
        val saved = save(f, c.lang)
        VoiceLog.add(VoiceEvent.FILE_RULE, ruleCode(saved).id, 0)
        val clause = FilingRules.clause(f, c.lang)
        return when (saved) {
            is FilingMemory.Saved.Kept -> {
                val made = if (have == null && change { FileViews.addDrawer(it, drawer) }) drawer else null
                val undo = token()
                synchronized(this) { ruleUndos[undo] = saved to made }
                val text = FilesStrings.ruleSavedText()
                val card = UiAction("files-$undo", null, FilingRules.text(f, c.lang), ActionStatus.DONE, // l10n: not text (a card's id)
                    FilesStrings.ruleIn("page" to Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.memory))).render(langs),
                    undo = UNDO + undo, files = true)
                Reply(text, FilesStrings.ruleSaved("rule" to clause), card)
            }
            is FilingMemory.Saved.Already -> Reply(FilesStrings.ruleKeptText(), FilesStrings.ruleKept("rule" to clause))
            FilingMemory.Saved.Off -> Reply(FilesStrings.ruleMemoryOff())
            FilingMemory.Saved.Full -> Reply(FilesStrings.ruleMemoryFull("page" to Arg.Ui(app.heyrobot.core.i18n.UiStrings.inSettings(app.heyrobot.core.i18n.UiStrings.memory))))
            FilingMemory.Saved.NotOpen -> Reply(FilesStrings.ruleNoMemory())
        }
    }

    // F10's words: money and dates, answered by code (core FileMoney); the reply's text names no file or merchant from a file.

    /** "How much did I spend on gas in August": code's sum per currency; totals to check left out and counted. */
    private fun spend(c: FilesCommand.Spend, index: () -> FileIndex?, today: java.time.LocalDate): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val r = app.heyrobot.core.files.FileSpending.sum(idx, c.query, FileDeletes.here.hidden())
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.SPEND.id, r.files)
        return Reply(app.heyrobot.core.files.FileMoneyText.spending(c.query, r, today))
    }

    /**
     * "What bills are due this week", "when does my passport expire": the dates from files' fields, soonest first; said by
     * name (never a private file's), with a card for each of the first three that offers a reminder: Add to my brief, Add to
     * calendar (add_event's card, a tap). None when Reminders from files is Off.
     */
    private fun upcoming(c: FilesCommand.Upcoming, langs: Langs, index: () -> FileIndex?, settings: FilesSettings, today: java.time.LocalDate): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val kind = c.kinds.first()
        var found = app.heyrobot.core.files.FileDates.upcoming(idx, today, c.kinds, app.heyrobot.core.files.FileMoneyText.until(c.span, today))
        if (c.terms.isNotBlank()) {
            val words = c.terms.split(' ').filter { it.isNotBlank() }.map(FilesRouter::fold)
            found = found.filter { d -> FilesRouter.fold(FileViews.searchText(d.entry)).let { t -> words.all { t.contains(it) } } }
        }
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.DUE.id, found.size)
        val said = app.heyrobot.core.files.FileMoneyText.upcoming(kind, c.span, found)
        val cards = if (settings.reminders == app.heyrobot.core.files.FileReminders.OFF) emptyList()
            else found.take(app.heyrobot.core.files.FileMoneyText.NAMED).map { remindCard(it, langs) }
        return Reply(said.text, said.spoken, cards.firstOrNull(), cards.drop(1))
    }

    /** "What subscriptions do I have": found by code in your bills, listed (never cancelled, paid or acted on). */
    private fun subscriptions(index: () -> FileIndex?, today: java.time.LocalDate): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val subs = app.heyrobot.core.files.FileSubscriptions.find(idx, today)
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.SUBSCRIPTIONS.id, subs.size)
        val said = app.heyrobot.core.files.FileMoneyText.subscriptions(subs)
        return Reply(said.text, said.spoken)
    }

    /** "File this" filed a file with a date coming up: Ask me first offers its reminder on a card under the filed one. */
    private fun withReminder(r: Reply, settings: () -> FilesSettings, langs: Langs, today: java.time.LocalDate): Reply {
        val e = lastFiled ?: return r
        lastFiled = null
        if (settings().reminders != app.heyrobot.core.files.FileReminders.ASK) return r
        val d = app.heyrobot.core.files.FileDates.of(e).firstOrNull { d ->
            !d.date.isBefore(today) && !d.date.isAfter(today.plusDays(app.heyrobot.core.files.FileDates.AHEAD.getValue(d.kind)))
        } ?: return r
        return Reply(r.text, r.spoken, r.card, r.more + remindCard(d, langs))
    }

    /** The file "file this" just filed (for [withReminder]). */
    @Volatile private var lastFiled: FileEntry? = null

    /** A date's card: the file, its date (and a bill's amount), Add to my brief (and Not now), Add to calendar. */
    private fun remindCard(d: app.heyrobot.core.files.FileDate, langs: Langs): UiAction {
        val t = token()
        synchronized(this) { reminds[t] = d.entry.id to d.kind }
        val name = d.entry.name.ifBlank { FilesStrings.noName().render(langs) }
        val line = Phrase.join(FilesStrings.sentence("s" to app.heyrobot.core.files.FileMoneyText.dateLine(d)), FilesStrings.remindAsk())
        return UiAction(REMIND + t, null, name, ActionStatus.PENDING, line.render(langs), confirm = FilesStrings.addToBrief().render(langs),
            cancel = FilesStrings.notNow().render(langs), handOff = CALENDAR + t, files = true)
    }

    /**
     * Add to my brief (true) or Not now on a reminder card (a background thread): the reminder kept in the file's own entry
     * (core FileRemind, sealed with the index), said in the brief from its day while the phone is unlocked. Never the
     * calendar. Refused while the phone is locked or during a phone lock (the card waits). The card's new state and line.
     */
    fun remindTapped(ctx: Context, actionId: String, yes: Boolean, langs: Langs, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now(),
        gate: () -> Phrase? = { FilesIntake.gateText(FilesIntake.gate(ctx)) }, change: ((FileIndex) -> FileIndex?) -> Boolean = { FilingWork.change(ctx, it) },
        index: () -> FileIndex? = { app.heyrobot.sys.RobotFiles.shown(ctx) }): Pair<ActionStatus, String> {
        if (!yes) return ActionStatus.DECLINED to FilesStrings.notNow().render(langs)
        gate()?.let { return ActionStatus.PENDING to it.render(langs) }
        val (id, kind) = synchronized(this) { reminds[actionId.removePrefix(REMIND)] } ?: return ActionStatus.DECLINED to FilesStrings.whichFile().render(langs)
        val e = index()?.entry(id) ?: return ActionStatus.FAILED to FilesStrings.whichFile().render(langs)
        val d = app.heyrobot.core.files.FileDates.of(e).firstOrNull { it.kind == kind } ?: return ActionStatus.FAILED to FilesStrings.whichFile().render(langs)
        if (app.heyrobot.core.files.FileRemind.of(e)[kind] != null) return ActionStatus.DONE to FilesStrings.alreadyInBrief().render(langs)
        val on = app.heyrobot.core.files.FileDates.remindOn(d, app.heyrobot.core.files.FileDates.nextBrief(now))
            ?: return ActionStatus.DECLINED to FilesStrings.datePassed().render(langs)
        val ok = try { change { app.heyrobot.core.files.FileRemind.set(it, d, on) } } catch (t: Exception) { RLog.w("files_remind", t); false }
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.REMIND.id, if (ok) 1 else 0)
        return if (ok) ActionStatus.DONE to FilesStrings.inBriefOn("day" to on).render(langs) else ActionStatus.FAILED to FilesStrings.notUndone().render(langs)
    }

    /**
     * Add to calendar on a reminder card: the file's date for add_event's card (PanelController runs add_event with a reason
     * that always shows its card and takes only a tap, AskWhy.FROM_CONTENT). Null when the card is stale or the date passed.
     */
    fun calendar(ctx: Context, handOff: String, today: java.time.LocalDate = java.time.LocalDate.now(),
        index: () -> FileIndex? = { app.heyrobot.sys.RobotFiles.shown(ctx) }): app.heyrobot.core.files.FileDate? {
        val (id, kind) = synchronized(this) { reminds[handOff.removePrefix(CALENDAR)] } ?: return null
        val e = index()?.entry(id) ?: return null
        return app.heyrobot.core.files.FileDates.of(e).firstOrNull { it.kind == kind && !it.date.isBefore(today) }
            ?.also { VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.CALENDAR.id, 1) }
    }

    /** add_event's arguments for a reminder card's Add to calendar ([calendar]'s date): its title in [langs] and the day, all day. */
    class EventAsk(val title: String, val day: String)

    /** [calendar] as add_event's arguments, for PanelController (which names nothing of Robot Files' own). */
    fun event(ctx: Context, handOff: String, langs: Langs): EventAsk? =
        calendar(ctx, handOff)?.let { d -> EventAsk(app.heyrobot.core.files.FileMoneyText.eventTitle(d).render(langs), d.date.toString()) }

    /** A card that opens Files, naming the best match ([e]) or the count. */
    private fun openCard(summary: String, line: String): UiAction =
        UiAction("files-${token()}", null, summary, ActionStatus.DONE, line, handOff = OPEN, files = true) // l10n: not text (a card's id)

    /** Where [e] is, in words: its drawer, or Inbox. */
    private fun whereOf(e: FileEntry): Phrase = e.drawer?.let { FilesStrings.drawer(it) } ?: FilesStrings.inbox()

    /** "Move the Costco receipt to Taxes" (or "move it…": the file this panel last filed or moved): one clear file, a drawer that exists. */
    private fun moveNamed(c: FilesCommand.Move, langs: Langs, change: ((FileIndex) -> FileIndex?) -> Boolean, index: () -> FileIndex?, now: Long): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val drawer = FilesRouter.drawer(c.drawer, FileViews.yours(idx), idx.outOfSight) ?: return Reply(FilesStrings.moveNoDrawer("drawers" to Arg.Sub(FilesStrings.drawers())))
        val q = c.query
        val e = if (q == null) lastMoving?.let(idx::entry) else {
            val found = FileFind.find(idx, q, FileDeletes.here.hidden())
            VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.MOVE.id, found.size)
            FileFind.one(found, q) ?: return several(found, langs)
        }
        if (e == null) return Reply(FilesStrings.whichFile())
        if (e.kind == FileKind.DANGEROUS || e.damaged) return Reply(FilesStrings.notUndone())
        if (!change { FilingRun.move(it, e.id, drawer, now) }) return Reply(FilesStrings.notUndone())
        VoiceLog.add(VoiceEvent.FILE_MOVE, FileMoveCode.MOVED.id, 1)
        val text = FilesStrings.moved("drawer" to Arg.Sub(FilesStrings.drawer(drawer)))
        return Reply(text, text, card(e.copy(drawer = drawer), text.render(langs), langs, e))
    }

    /** None or several match: how many, and a card to choose in Files. */
    private fun several(found: List<FileEntry>, langs: Langs): Reply =
        if (found.isEmpty()) Reply(FilesStrings.foundNone())
        else FilesStrings.several(found.size.toLong()).let { Reply(it, it, openCard(FilesStrings.showInFiles().render(langs), it.render(langs))) }

    /** "Find my Costco receipt from August": how many and where the best is; its name on a card that opens Files. */
    private fun find(c: FilesCommand.Find, langs: Langs, index: () -> FileIndex?): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val found = FileFind.find(idx, c.query, FileDeletes.here.hidden())
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.FIND.id, found.size)
        val best = found.firstOrNull() ?: return Reply(FilesStrings.foundNone())
        val n = found.size.toLong()
        val text = best.drawer?.let { FilesStrings.found(n, "drawer" to Arg.Sub(FilesStrings.drawer(it))) } ?: FilesStrings.foundInInbox(n)
        return Reply(text, text, openCard(best.name.ifBlank { FilesStrings.noName().render(langs) }, FilesStrings.where(best.drawer, best.name).render(langs)))
    }

    /** "What's in my Inbox": how many, and how many need you; a card that opens Files. */
    private fun inbox(langs: Langs, index: () -> FileIndex?): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val home = FileViews.home(idx)
        VoiceLog.add(VoiceEvent.FILE_FIND, FileFindCode.INBOX.id, home.inbox)
        if (home.inbox == 0) return Reply(FilesStrings.inboxEmpty())
        val text = if (home.needYou.isEmpty()) FilesStrings.inboxHolds(home.inbox.toLong())
            else Phrase.join(FilesStrings.inboxHolds(home.inbox.toLong()), FilesStrings.needYou(home.needYou.size.toLong()), sep = " ")
        return Reply(text, text, openCard(FilesStrings.inbox().render(langs), text.render(langs)))
    }

    /**
     * "Delete the Costco receipt": never deleted here. One clear file gets a card naming it, Delete and Keep, that only a tap
     * answers ([deleteTapped]); several or none, the count.
     */
    private fun deleteCard(c: FilesCommand.Delete, langs: Langs, index: () -> FileIndex?): Reply {
        val idx = index() ?: return Reply(FilesStrings.locked())
        seen(idx)
        val found = FileFind.find(idx, c.query, FileDeletes.here.hidden())
        val e = FileFind.one(found, c.query) ?: return several(found, langs)
        val t = token()
        synchronized(this) { deletes[t] = e.id }
        VoiceLog.add(VoiceEvent.FILE_DELETE, FileDeleteCode.CARD.id, 1)
        val name = e.name.ifBlank { FilesStrings.noName().render(langs) }
        val card = UiAction(DELETE + t, null, name, ActionStatus.PENDING, FilesStrings.deleteCard("name" to name).render(langs),
            confirm = FilesStrings.deleteButton().render(langs), cancel = FilesStrings.keep().render(langs), tapFor = app.heyrobot.core.AskWhy.DELETE, files = true)
        return Reply(FilesStrings.deleteAsk(), FilesStrings.deleteAsk(), card)
    }

    /** The file Move's question was about (kept past [route], which clears the question). */
    @Volatile private var lastMoving: FileId? = null

    /** The drawer [words] name: a starter drawer by its English or French name, else one of yours already in use; else null. */
    internal fun drawerIn(words: String, idx: FileIndex?): String? {
        val f = " " + app.heyrobot.core.files.FileFields.fold(words).replace(Regex("[^\\p{L}\\d]+"), " ").trim() + " " // l10n: not text (a parser's pattern)
        fun has(name: String) = f.contains(" " + app.heyrobot.core.files.FileFields.fold(name).replace(Regex("[^\\p{L}\\d]+"), " ").trim() + " ") // l10n: not text (a parser's pattern)
        // F12: a drawer travel mode hides (the view's outOfSight) isn't there.
        for (d in Drawers.STARTER) if (d !in idx?.outOfSight.orEmpty()) for (l in listOf(Langs.of(Lang.EN), Langs.of(Lang.FR))) if (has(FilesStrings.drawer(d).render(l))) return d
        val yours = idx?.entries?.mapNotNull { it.drawer }?.filter { it !in Drawers.STARTER }?.distinct().orEmpty()
        return yours.firstOrNull { has(it) }
    }

    /** The panel closed (or a new conversation began): the photo leaves RAM, and a duplicate waiting is discarded. */
    /**
     * FX1 (files-1.4-review.md finding 3): [c], opened from History to go on with it, as travel mode shows it now (core
     * TravelMode.continued): Robot Files' answers as placeholders, to the screen and the model, while it hides drawers.
     * [saved] puts the stored answers back whenever the conversation is saved again.
     */
    fun opened(ctx: Context, c: StoredConversation, langs: Langs): StoredConversation {
        val k = TravelMode.continued(c, TravelMode.hidesAny(FilesSettingsStore.current(ctx)), FilesStrings.travelAnswer().render(langs),
            FilesStrings.travelQuestion().render(langs))
        continued = k
        return k.shown
    }

    /** FX1: [turns] as the conversation is saved: a Files answer [opened] showed as a placeholder, back as it was stored. */
    fun saved(turns: List<ChatTurn>): List<ChatTurn> = continued.saved(turns)

    /**
     * FX3: what travel mode hides now, for the panel to tell when it starts hiding more (core TravelMode.hiding; "" stands
     * for every drawer, when the file can't say which). Empty while nothing is hidden.
     */
    fun hidingNow(ctx: Context): Set<String> = FilesSettingsStore.current(ctx).let { s ->
        if (!TravelMode.hidesAny(s)) emptySet() else if (s.hiddenUnknown) setOf("") else TravelMode.hiding(s)
    }

    /**
     * FX1: a conversation holding Robot Files' answers ([turns]) isn't continued while travel mode hides drawers. FX3: as
     * History finds them (core TravelMode.holdsFiles: a question the router takes counts too).
     */
    fun travelHides(ctx: Context, turns: List<ChatTurn>): Boolean = TravelMode.hidesAny(FilesSettingsStore.current(ctx)) && TravelMode.holdsFiles(turns)

    @Volatile private var continued = TravelMode.Continued.NONE

    fun forget(ctx: Context, drop: (IntakeStep.Duplicate) -> Unit = { FilesIntake.phone(ctx).drop(it) }) {
        // F9: the numbers find_files gave name nothing in the next conversation, and no answer waits to be kept as a placeholder.
        continued = TravelMode.Continued.NONE
        PanelFileTools.forget()
        PanelFileAnswer.forget()
        original = null
        originalTooBig = false
        moving = null
        lastMoving = null
        yours = emptyList()
        lastFiled = null
        synchronized(this) { undos.clear(); moves.clear(); ruleUndos.clear(); deletes.clear(); reminds.clear() }
        // FX2 (K1): a delete waiting for its Undo goes through now: the panel that could undo it is gone.
        FileDeletes.here.commitAll()
        val d = waiting ?: return
        waiting = null
        // Off the caller's (main) thread: destroying the staged file's key and bytes is a write.
        Thread({ try { drop(d) } catch (t: Exception) { RLog.w("files_panel_drop", t) } }, "robot-files-panel").start()  // l10n: not text (a thread's name)
    }

    /** The screenshot as a PNG (lossless: a receipt's small print stays readable), at its full size. */
    private fun png(b: Bitmap): ByteArray? = try {
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
        ByteArrayOutputStream().use { out ->
            soft.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray().takeIf { it.isNotEmpty() && it.size <= ParseLimits.MAX_INPUT }
        }
    } catch (t: Exception) {
        RLog.w("files_panel_png", t)
        null
    }

    /** A name's extension from the sniffed type. */
    private fun ext(t: FileType): String = EXT[t].orEmpty()

    private val EXT = mapOf(FileType.JPEG to ".jpg", FileType.PNG to ".png", FileType.HEIC to ".heic", FileType.HEIF to ".heif", FileType.WEBP to ".webp")  // l10n: not text (file name extensions)
}
