// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseStatus
import java.time.LocalDate

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.1; job F6): filing one file, step by step, over ports the app fills (the
// vault, :files, the model, the settings, your rules). The app's FilingWork (:voice) is only glue: every decision is here,
// and the JVM tests run it whole.
//
//   1. Locked (the phone, or a phone lock): nothing is read or written.
//   2. Only a file in Inbox that Robot hasn't settled (NEW, or WAIT for a model that sees) is taken, and only by its id.
//   3. The type, from the first bytes (the provenance's sniff, or the blob's own first 4 KiB when there is none): a
//      dangerous file is marked DANGEROUS and held (never parsed, never opened); a video, an archive-free image Robot
//      doesn't read, an audio file are filed by type; an unknown type waits for you.
//   4. A try is written to the index before anything is sent (so a file that crashes :files or the model, and the
//      process with it, is still counted), then :files reads it (text, the first page's picture, a thumbnail, a date).
//   5. Rules first (FileRules, FileFields over the file's own text). When they settle the kind and its fields, no model.
//   6. Model second: a text layer as text, else the first page's picture (when "Read text in images" is on and the model
//      sees). The reading is checked: its kind only when the rules can live with it, every value re-read by FileFields.
//   7. Filer decides; the result is written in one change of the index that first checks the file is still in Inbox and
//      still Robot's to file (you may have moved, renamed, filed or deleted it while it was being read).
//
// The retry cap: a try that came to no verdict for a reason that may be the file's ( :files crashed, hung or answered
// garbage; the model's engine failed, its process stopped, or it ran past its time) counts, and at [FilingPolicy.MAX_TRIES]
// the file is held in Inbox as unreadable. A reason that is never the file's (the model busy, not loaded, cancelled, a
// lock, no vision, a context too small for a picture) doesn't count: the try is taken back and the file waits.

/** The model Robot would read with: whether it sees pictures (null: not known until it answers) and its context. */
data class ModelInfo(val vision: Boolean?, val contextTokens: Int)

/** What filing needs of the app. Every call may block: never on the main thread. */
interface FilingPorts {
    /** The phone is locked, or a phone lock is on. */
    fun locked(): Boolean
    fun load(): IndexLoad
    fun update(change: (FileIndex) -> FileIndex): IndexLoad
    /** Up to [n] first bytes of [id] (for the sniff). Throws [FileException]. */
    fun head(id: FileId, n: Int): ByteArray
    /** [id]'s bytes, at most [max]. Throws [FileException]. */
    fun bytes(id: FileId, max: Long): ByteArray
    /** :files' READ of [bytes] with [flags]. */
    fun parse(bytes: ByteArray, flags: Int): ParseResult
    /** Seals [jpeg] as [id]'s thumbnail; false when it couldn't. */
    fun putThumb(id: FileId, jpeg: ByteArray): Boolean
    /** The model to read with, or null when there is none to ask. */
    fun model(): ModelInfo?
    /** A FILE_READ one-shot, with [jpeg] for a picture. Blocks until it ends. */
    fun read(req: BrainMsg.OneShot, jpeg: ByteArray?): OneShotResult
    fun settings(): FilesSettings
    fun rules(): List<FilingRule>
    fun today(): LocalDate
    /** The currency a bare "$" means here (CAD on a Canadian phone), or null. */
    fun dollar(): String?
    fun words(): NameWords
    fun now(): Long
}

/** How a try ended, for the retry cap. */
enum class TryEnd {
    /** It came to a verdict (a reading, or a sure "can't be read"). */
    VERDICT,
    /** No verdict, and the file may be why: counted. */
    COUNTS,
    /** No verdict, never the file's fault: not counted; the file waits. */
    WAIT,
    /** Not the file's fault and nothing else should go now (:files unavailable, or not isolated): the batch stops. */
    STOP,
}

object FilingPolicy {
    /** Tries that came to nothing before a file is held as unreadable. EST. */
    const val MAX_TRIES = 3
    /** Below this context, a picture's reading (about 700 tokens out, the picture's in) is cut (F4: 2,048 cut the JSON). */
    const val MIN_PICTURE_CONTEXT = 3_000
    /** Files one batch takes at most; the next trigger takes the rest. */
    const val BATCH = 20
    /** What :files is asked for filing: the text, the pages only when there is no text layer, a thumbnail, the date. */
    const val READ_FLAGS = ParseRequest.TEXT or ParseRequest.PAGES or ParseRequest.PAGES_IF_NO_TEXT or ParseRequest.THUMB or ParseRequest.DATE

    fun ofParse(r: ParseResult): TryEnd = when (r) {
        is ParseResult.Ok, is ParseResult.Refused -> TryEnd.VERDICT
        is ParseResult.Failed -> when (r.why) {
            ParseFailure.CRASHED, ParseFailure.TIMED_OUT, ParseFailure.MALFORMED -> TryEnd.COUNTS
            ParseFailure.UNAVAILABLE, ParseFailure.NOT_ISOLATED -> TryEnd.STOP
        }
    }

    /** [r] of a FILE_READ ([picture]); [small]: the model's context is below [MIN_PICTURE_CONTEXT]. */
    fun ofModel(r: OneShotResult, picture: Boolean, small: Boolean): Pair<TryEnd, FilingWhy?> = when (r) {
        is OneShotResult.Done -> when (r.reason) {
            DoneReason.END, DoneReason.GARBAGE, DoneReason.TOOL_CALLS -> TryEnd.VERDICT to null
            DoneReason.LENGTH, DoneReason.CONTEXT_FULL -> if (picture && small) TryEnd.WAIT to FilingWhy.SMALL_CONTEXT else TryEnd.VERDICT to null
            DoneReason.CANCELLED -> TryEnd.WAIT to null
        }
        is OneShotResult.Failed -> when (r.code) {
            BrainError.NO_VISION -> TryEnd.WAIT to FilingWhy.NO_VISION
            BrainError.BAD_REQUEST, BrainError.TOO_LONG -> TryEnd.VERDICT to null
            BrainError.ENGINE, BrainError.STOPPED -> TryEnd.COUNTS to null
            BrainError.NOT_LOADED, null -> TryEnd.WAIT to FilingWhy.NO_MODEL
            BrainError.BUSY -> TryEnd.WAIT to null
        }
        is OneShotResult.TimedOut -> TryEnd.COUNTS to null
        OneShotResult.NotStarted, OneShotResult.Cancelled -> TryEnd.WAIT to null
    }
}

/** What filing one file came to. */
sealed class FilingOutcome {
    data class Filed(val entry: FileEntry, val placement: Placement) : FilingOutcome()
    data class Asked(val entry: FileEntry, val why: FilingWhy) : FilingOutcome()
    data class Held(val entry: FileEntry, val why: FilingWhy) : FilingOutcome()
    /** It waits ([why]: what for, or null for "not now"); [counted]: this try counted towards the cap. */
    data class Waiting(val why: FilingWhy?, val counted: Boolean = false) : FilingOutcome()
    /** Locked: nothing happened. */
    data object Locked : FilingOutcome()
    /** Not Robot's to file (gone, filed, moved, asked, held, or changed by you meanwhile). */
    data object Skipped : FilingOutcome()
    /** :files couldn't be used: nothing more this batch. */
    data object Stop : FilingOutcome()

    /** Never a name in a log line. */
    override fun toString(): String = when (this) {
        is Filed -> "Filed(${placement.kind})" // l10n: not text (codes for logs and tests)
        is Asked -> "Asked($why)" // l10n: not text (codes for logs and tests)
        is Held -> "Held($why)" // l10n: not text (codes for logs and tests)
        is Waiting -> "Waiting($why, $counted)" // l10n: not text (codes for logs and tests)
        Locked -> "Locked" // l10n: not text (codes for logs and tests)
        Skipped -> "Skipped" // l10n: not text (codes for logs and tests)
        Stop -> "Stop" // l10n: not text (codes for logs and tests)
    }
}

/** What one file's run did, for the voice log: counts and codes only. */
data class FilingTrace(var parsed: ParseStatus? = null, var parseFailed: ParseFailure? = null, var read: TryEnd? = null, var picture: Boolean = false,
    var traps: Int = 0, var rejected: Int = 0, var model: Boolean = false, var ms: Long = 0)

class FilingRun(private val ports: FilingPorts) {
    /** What the last [file] did (for the voice log). */
    var trace = FilingTrace()
        private set

    /**
     * Files whose last try a lock cut short after it was written (a locked vault can't take it back): the next try here
     * doesn't count it. Only this process knows; a restart in between leaves it counted, which is on the cap's safe side.
     */
    private val lockedTries = HashSet<FileId>()

    /** The files a batch takes next: in Inbox, Robot's to file, oldest first, at most [max]. One pass over the index. */
    fun queue(index: FileIndex, max: Int = FilingPolicy.BATCH): List<FileId> {
        val out = ArrayList<FileEntry>()
        for (e in index.entries) if (filable(e)) out += e
        out.sortBy { it.from.at }
        return out.take(max).map { it.id }
    }

    /** Files [id]. [yourWords]: you asked ("file this"), so Ask me first doesn't wait. */
    fun file(id: FileId, yourWords: Boolean = false): FilingOutcome {
        trace = FilingTrace()
        if (ports.locked()) return FilingOutcome.Locked
        val load = ports.load()
        if (load == IndexLoad.Locked) return FilingOutcome.Locked
        val entry = load.current?.entry(id) ?: return FilingOutcome.Skipped
        if (!filable(entry)) return FilingOutcome.Skipped

        val type = typeOf(entry) ?: return if (ports.locked()) FilingOutcome.Locked else FilingOutcome.Skipped
        val byType = FileRules.kindOf(type, entry.from.source, null)
        if (byType.kind == FileKind.DANGEROUS) return hold(id, FilingWhy.DANGEROUS, FileKind.DANGEROUS)
        if (!byType.read) {
            // Kept by type, never read: a video, an image Robot doesn't read, an audio file, a transcript; or an unknown type.
            return decide(id, entry, byType.kind, FileFieldValues(), null, yourWords, null, byType.leaning)
        }
        if (entry.from.size > ParseLimits.MAX_INPUT) return hold(id, FilingWhy.TOO_BIG, null)
        val tries = ((entry.filing?.tries ?: 0) - if (synchronized(lockedTries) { lockedTries.remove(id) }) 1 else 0).coerceAtLeast(0)
        if (tries >= FilingPolicy.MAX_TRIES) return hold(id, FilingWhy.UNREADABLE, null)
        // The try is counted before anything is sent: a crash from here on is this file's.
        if (!setTries(id, tries + 1)) return if (ports.locked()) FilingOutcome.Locked else FilingOutcome.Skipped

        val bytes = try {
            ports.bytes(id, ParseLimits.MAX_INPUT.toLong())
        } catch (x: FileException) {
            takeBack(id, tries)
            return when (x.problem) {
                FileProblem.LOCKED -> FilingOutcome.Locked
                FileProblem.TOO_BIG -> hold(id, FilingWhy.TOO_BIG, null)
                FileProblem.DAMAGED -> hold(id, FilingWhy.UNREADABLE, null)
                else -> FilingOutcome.Skipped
            }
        }
        val parsed = ports.parse(bytes, FilingPolicy.READ_FLAGS)
        when (FilingPolicy.ofParse(parsed)) {
            TryEnd.COUNTS -> {
                trace.parseFailed = (parsed as ParseResult.Failed).why
                return counted(id, tries + 1)
            }
            TryEnd.STOP, TryEnd.WAIT -> {
                trace.parseFailed = (parsed as? ParseResult.Failed)?.why
                takeBack(id, tries)
                return FilingOutcome.Stop
            }
            TryEnd.VERDICT -> {}
        }
        if (parsed is ParseResult.Refused) {
            trace.parsed = parsed.status
            return when (parsed.status) {
                ParseStatus.DANGEROUS -> hold(id, FilingWhy.DANGEROUS, FileKind.DANGEROUS)
                ParseStatus.TOO_LARGE, ParseStatus.OUTPUT_TOO_LARGE -> hold(id, FilingWhy.TOO_BIG, null)
                else -> hold(id, FilingWhy.UNREADABLE, null)
            }
        }
        val out = (parsed as ParseResult.Ok).out
        trace.parsed = ParseStatus.OK
        if (ports.locked()) {
            takeBack(id, tries)
            return FilingOutcome.Locked
        }
        val thumb = out.thumb?.let { ports.putThumb(id, it) } == true
        val own = out.text?.takeIf { out.has(ParseReply.HAS_TEXT) && it.isNotBlank() }
        val picture = own == null && out.pageJpegs.isNotEmpty()
        trace.picture = picture

        // Rules first.
        val settings = ports.settings()
        val today = ports.today()
        val dollar = ports.dollar()
        var vote = FileRules.kindOf(type, entry.from.source, own)
        val ruled = own?.let { FileFields.check(it, ownText = true, reading = null, dollar = dollar, today = today) } ?: FileFieldValues()
        if (vote.settled && vote.kind != null && Filer.level(vote.kind!!, settings) == FileLevel.OFF)
            return decide(id, entry, vote.kind, ruled, own, yourWords, null, emptyList(), thumb, tries + 1)
        val needModel = !(vote.settled && complete(vote.kind!!, ruled))

        // Model second.
        var reading: FileReading? = null
        if (needModel) {
            val m = ports.model()
            val allLevelsOff = FileKind.entries.none { it != FileKind.DANGEROUS && it != FileKind.UNKNOWN && Filer.level(it, settings) != FileLevel.OFF }
            when {
                allLevelsOff -> return decide(id, entry, vote.kind, ruled, own, yourWords, null, vote.leaning, thumb, tries + 1)
                // "Read text in images" off: a picture is filed by its type and date only (§4.2): a photo; a scanned PDF (its
                // pages are pictures too) is no photo, so Robot asks what it is.
                picture && !settings.readImageText -> return decide(id, entry, vote.kind ?: FileKind.PHOTO.takeIf { type.group == FileGroup.IMAGE }, ruled, own,
                    yourWords, null, vote.leaning, thumb, tries + 1)
                m == null -> if (!vote.settled) return wait(id, tries, FilingWhy.NO_MODEL, thumb)
                picture && m.vision == false -> return wait(id, tries, FilingWhy.NO_VISION, thumb)
                picture && m.contextTokens in 1 until FilingPolicy.MIN_PICTURE_CONTEXT -> return wait(id, tries, FilingWhy.SMALL_CONTEXT, thumb)
                else -> {
                    val frameKind = vote.kind ?: FileKind.UNKNOWN
                    val req = if (picture) FilePrompt.readPicture(entry.from.source, frameKind)
                    else own?.let { FilePrompt.readText(it, entry.from.source, frameKind, m.contextTokens.takeIf { t -> t > 0 } ?: app.heyrobot.core.ContextWindow.MAX_TOKENS) }
                    if (req != null) {
                        trace.model = true
                        if (ports.locked()) {
                            takeBack(id, tries)
                            return FilingOutcome.Locked
                        }
                        val r = ports.read(req.request, if (picture) out.pageJpegs.first() else null)
                        val (end, why) = FilingPolicy.ofModel(r, picture, m.contextTokens in 1 until FilingPolicy.MIN_PICTURE_CONTEXT)
                        trace.read = end
                        when (end) {
                            TryEnd.COUNTS -> return counted(id, tries + 1)
                            TryEnd.WAIT, TryEnd.STOP -> return if (ports.locked()) { takeBack(id, tries); FilingOutcome.Locked } else wait(id, tries, why, thumb)
                            TryEnd.VERDICT -> reading = (FileReadOutcome.of(r, picture) as? FileReadOutcome.Read)?.reading
                        }
                    }
                }
            }
        }

        // The reading, checked.
        val text = own ?: reading?.text
        if (picture && reading?.text != null) vote = FileRules.kindOf(type, entry.from.source, reading.text)
        var choices = vote.leaning
        val kind: FileKind? = when {
            vote.settled -> vote.kind
            reading?.kind != null && FileRules.agrees(reading.kind, vote) -> reading.kind
            reading?.kind != null -> { choices = (listOf(reading.kind) + vote.leaning).distinct(); null }
            // A picture the model read with no words and no kind: a photo.
            picture && reading != null && reading.text == null && vote.scores.isEmpty() -> FileKind.PHOTO
            else -> null
        }
        val values = if (text != null) FileFields.check(text, ownText = own != null, reading = reading, dollar = dollar, today = today) else FileFieldValues()
        trace.traps = values.traps
        trace.rejected = values.rejected
        return decide(id, entry, kind, values, text, yourWords, reading, choices, thumb, tries + 1)
    }

    /** Whether the rules alone found what [kind]'s name and checks need, so no model is asked (§4.1 step 5). */
    private fun complete(kind: FileKind, v: FileFieldValues): Boolean = when (kind) {
        FileKind.RECEIPT -> v.total != null && !v.totalCheck && v.merchant != null && v.date != null
        FileKind.BILL -> v.total != null && !v.totalCheck && v.merchant != null && (v.due != null || v.date != null)
        FileKind.TAX_SLIP -> v.slip != null && v.year != null
        FileKind.DOCUMENT, FileKind.ID, FileKind.MEDICAL, FileKind.TICKET -> v.date != null && (v.merchant != null || v.title != null)
        else -> true
    }

    // F11: thumbnails a restore asks for again (a backup carries none).

    /** The files a restore listed for their thumbnail ([RETHUMB]), in order, at most [max]. */
    fun rethumbs(index: FileIndex, max: Int = FilingPolicy.BATCH): List<FileId> = rethumbIds(index).take(max)

    /**
     * [id]'s thumbnail made again: an image's or a PDF's bytes read in :files for the thumbnail alone, sealed, and the entry
     * marked. Tried once: it leaves [RETHUMB] whatever came of it, except "not now" (locked, :files unavailable), which
     * returns false and keeps it for the next batch.
     */
    fun rethumb(id: FileId): Boolean {
        if (ports.locked()) return false
        val e = ports.load().current?.entry(id)
        val wanted = e?.takeIf { !it.thumb && !it.damaged && it.kind != FileKind.DANGEROUS && it.from.size in 1..ParseLimits.MAX_INPUT }
        val type = wanted?.let { typeOf(it) }
        if (wanted != null && type == null && ports.locked()) return false
        var made = false
        if (type != null && (type.group == FileGroup.IMAGE || type.group == FileGroup.PDF)) {
            val bytes = try {
                ports.bytes(id, ParseLimits.MAX_INPUT.toLong())
            } catch (x: FileException) {
                if (x.problem == FileProblem.LOCKED) return false
                null
            }
            if (bytes != null) {
                val parsed = try { ports.parse(bytes, ParseRequest.THUMB) } finally { bytes.fill(0) }
                if (FilingPolicy.ofParse(parsed) == TryEnd.STOP) return false
                val jpeg = (parsed as? ParseResult.Ok)?.out?.thumb
                if (jpeg != null) {
                    if (ports.locked()) return false
                    made = ports.putThumb(id, jpeg)
                }
            }
        }
        val load = ports.update { idx ->
            val left = rethumbIds(idx) - id
            idx.copy(entries = if (made) idx.entries.map { if (it.id == id) it.copy(thumb = true) else it } else idx.entries,
                extra = if (left.isEmpty()) idx.extra - RETHUMB else idx.extra + (RETHUMB to left.map { it.hex }))
        }
        return load.current != null
    }

    /** In Inbox and Robot's to file: not yet tried, or waiting for something that may have changed. */
    fun filable(e: FileEntry): Boolean = e.state == FileState.INBOX && !e.damaged && e.kind != FileKind.DANGEROUS &&
        (e.filing == null || e.filing.step == FilingStep.NEW || e.filing.step == FilingStep.WAIT)

    /** The file's type: the provenance's sniff, else its own first bytes'. Null when they can't be read now. */
    private fun typeOf(e: FileEntry): FileType? {
        val known = e.from.type?.let { t -> FileType.entries.firstOrNull { it.mime == t } }
        if (known != null && known != FileType.UNKNOWN && e.from.source != FileSource.RECOVERED) return known
        return try {
            FileSniff.sniff(ports.head(e.id, FileSniff.PREFIX), e.from.size)
        } catch (_: FileException) {
            null
        }
    }

    private fun decide(id: FileId, entry: FileEntry, kind: FileKind?, values: FileFieldValues, text: String?, yourWords: Boolean, reading: FileReading?,
        choices: List<FileKind>, thumb: Boolean = false, tries: Int = entry.filing?.tries ?: 0): FilingOutcome {
        val d = Filer.decide(entry, kind, values, ports.settings(), ports.rules(), yourWords, ports.words(), text, choices)
        var after: FileEntry? = null
        val load = ports.update { idx -> apply(idx, id) { e ->
            val searchText = if (e.text.isEmpty()) text?.take(FileLimits.MAX_TEXT)?.takeIf { FileIndexCodec.wellFormed(it) }.orEmpty() else e.text
            val base = e.copy(thumb = e.thumb || thumb, text = searchText)
            val next = when (d) {
                is FilingDecision.File -> filed(base, d.placement, tries)
                is FilingDecision.Ask -> base.copy(kind = d.kind ?: e.kind, fields = merge(e.fields, d.fields),
                    filing = Filing(FilingStep.ASK, d.why, tries, d.choices))
                is FilingDecision.Held -> base.copy(kind = d.kind ?: e.kind, fields = merge(e.fields, d.fields), filing = Filing(FilingStep.HELD, d.why, tries))
            }
            (FileIndexCodec.fitText(next) ?: next.copy(text = "")).also { after = it }
        } }
        val e = after
        if (load.current == null) return FilingOutcome.Locked
        if (e == null) return FilingOutcome.Skipped
        return when (d) {
            is FilingDecision.File -> FilingOutcome.Filed(e, d.placement)
            is FilingDecision.Ask -> FilingOutcome.Asked(e, d.why)
            is FilingDecision.Held -> FilingOutcome.Held(e, d.why)
        }
    }

    private fun filed(e: FileEntry, p: Placement, tries: Int): FileEntry = e.copy(
        state = FileState.FILED, kind = p.kind, drawer = p.drawer, name = p.name, tags = p.tags, fields = merge(e.fields, p.fields),
        filing = Filing(FilingStep.FILED, null, tries, wasName = e.name, wasTags = e.tags),
        from = e.from.copy(filedBy = p.by, filedAt = ports.now()),
    )

    /** Fields you set stay as you set them; Robot's are replaced by its new reading. */
    private fun merge(old: Map<String, FileField>, new: Map<String, FileField>): Map<String, FileField> {
        val out = LinkedHashMap<String, FileField>()
        for ((k, f) in old) if (f.by != FieldBy.READ) out[k] = f
        for ((k, f) in new) if (k !in out) out[k] = f
        return out.entries.take(FileIndexCodec.MAX_FIELD_COUNT).associate { it.key to it.value }
    }

    /**
     * [idx] with [id]'s entry changed by [change], only while it is still Robot's to file: in Inbox, not damaged, and not
     * settled (you may have filed, moved, asked about or put it back meanwhile). Otherwise [idx] unchanged.
     */
    private fun apply(idx: FileIndex, id: FileId, change: (FileEntry) -> FileEntry): FileIndex {
        val i = idx.entries.indexOfFirst { it.id == id }
        if (i < 0) return idx
        val e = idx.entries[i]
        if (!filable(e)) return idx
        val list = idx.entries.toMutableList()
        list[i] = change(e)
        return idx.copy(entries = list)
    }

    private fun setTries(id: FileId, tries: Int): Boolean {
        var done = false
        val load = ports.update { idx -> apply(idx, id) { e ->
            done = true
            e.copy(filing = (e.filing ?: Filing(FilingStep.NEW)).copy(tries = tries.coerceIn(0, FileIndexCodec.MAX_TRIES)))
        } }
        return load.current != null && done
    }

    /** The try didn't count: written back, or remembered when the vault can't be written now (locked). */
    private fun takeBack(id: FileId, tries: Int) {
        if (!setTries(id, tries)) synchronized(lockedTries) { lockedTries += id }
    }

    private fun counted(id: FileId, tries: Int): FilingOutcome =
        if (tries >= FilingPolicy.MAX_TRIES) hold(id, FilingWhy.UNREADABLE, null) else FilingOutcome.Waiting(null, counted = true)

    private fun wait(id: FileId, tries: Int, why: FilingWhy?, thumb: Boolean): FilingOutcome {
        ports.update { idx -> apply(idx, id) { e ->
            e.copy(thumb = e.thumb || thumb, filing = (e.filing ?: Filing(FilingStep.NEW)).copy(step = if (why == null) e.filing?.step ?: FilingStep.NEW else FilingStep.WAIT,
                why = why ?: e.filing?.why, tries = tries))
        } }
        return FilingOutcome.Waiting(why)
    }

    private fun hold(id: FileId, why: FilingWhy, kind: FileKind?): FilingOutcome {
        var after: FileEntry? = null
        val load = ports.update { idx -> apply(idx, id) { e ->
            e.copy(kind = kind ?: e.kind, filing = Filing(FilingStep.HELD, why, e.filing?.tries ?: 0)).also { after = it }
        } }
        if (load.current == null) return FilingOutcome.Locked
        return after?.let { FilingOutcome.Held(it, why) } ?: FilingOutcome.Skipped
    }

    companion object {
        /**
         * F11: the index's top-level key listing restored files whose thumbnail is made again (core/backup FilesBackup),
         * additive in index v1 (a 1.4 Robot from before F11 keeps it as an unknown key).
         */
        const val RETHUMB = "rethumb"

        /** The ids [RETHUMB] lists; anything that isn't an id is ignored. */
        fun rethumbIds(index: FileIndex): List<FileId> = (index.extra[RETHUMB] as? List<*>).orEmpty().mapNotNull { FileId.of(it as? String) }.distinct()

        /**
         * Undo of Robot's filing (or a rule's): back in Inbox with the name and tags it had, its kind and fields kept, and
         * marked KEPT so Robot never files it on its own again. Null when [id] isn't a file Robot or a rule filed.
         */
        fun undo(idx: FileIndex, id: FileId): FileIndex? {
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = idx.entries[i]
            if (e.state != FileState.FILED || e.from.filedBy == FiledBy.YOU || e.filing?.step != FilingStep.FILED) return null
            val back = e.copy(state = FileState.INBOX, drawer = null, name = e.filing.wasName ?: e.name, tags = e.filing.wasTags ?: e.tags,
                filing = Filing(FilingStep.KEPT, null, e.filing.tries), from = e.from.copy(filedBy = null, filedAt = null))
            return idx.copy(entries = idx.entries.toMutableList().also { it[i] = back })
        }

        /**
         * You moved [id] to [drawer] (the panel's Move, F7's screens): filed there, by you, from Inbox or from another
         * drawer. Null when there is no such file, or [drawer] isn't a drawer's id or name.
         */
        fun move(idx: FileIndex, id: FileId, drawer: String, now: Long): FileIndex? {
            if (!FileIndexCodec.label(drawer)) return null
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = idx.entries[i]
            if (e.kind == FileKind.DANGEROUS || e.damaged) return null
            val moved = e.copy(state = FileState.FILED, drawer = drawer, filing = (e.filing ?: Filing(FilingStep.FILED)).copy(step = FilingStep.FILED, why = null),
                from = e.from.copy(filedBy = FiledBy.YOU, filedAt = now))
            return idx.copy(entries = idx.entries.toMutableList().also { it[i] = moved })
        }

        /**
         * "File them" on Ask me first's card: [id], waiting with Robot's reading, filed as Robot would have (Filer.place
         * over its kind and the fields it read). Null when it isn't waiting for that any more.
         */
        fun fileThem(idx: FileIndex, id: FileId, settings: FilesSettings, rules: List<FilingRule>, words: NameWords, now: Long): FileIndex? {
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = idx.entries[i]
            if (e.state != FileState.INBOX || e.filing?.step != FilingStep.ASK || e.filing.why != FilingWhy.FILE_THEM) return null
            if (Filer.level(e.kind, settings) == FileLevel.OFF || Drawers.of(e.kind) == null) return null
            val p = Filer.place(e, e.kind, Filer.valuesOf(e.fields), e.fields, rules, words, e.text)
            val filed = e.copy(state = FileState.FILED, kind = p.kind, drawer = p.drawer, name = p.name, tags = p.tags,
                filing = Filing(FilingStep.FILED, null, e.filing.tries, wasName = e.name, wasTags = e.tags), from = e.from.copy(filedBy = p.by, filedAt = now))
            return idx.copy(entries = idx.entries.toMutableList().also { it[i] = filed })
        }

        /**
         * Your answer to Robot's question about [id] (F7's chips, the panel's words later): it is a [kind]. Filed as Robot
         * would file one of those, with the fields it read (a total still needing checking stays asked); null when [id]
         * isn't waiting for an answer, or [kind] can't be filed.
         */
        fun answer(idx: FileIndex, id: FileId, kind: FileKind, settings: FilesSettings, rules: List<FilingRule>, words: NameWords, now: Long): FileIndex? {
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = idx.entries[i]
            if (e.state != FileState.INBOX || e.filing?.step != FilingStep.ASK || e.filing.why != FilingWhy.KIND) return null
            if (Drawers.of(kind) == null) return null
            val v = Filer.valuesOf(e.fields)
            val next = when (val d = Filer.decide(e, kind, v, settings, rules, yourWords = true, words = words, text = e.text)) {
                is FilingDecision.File -> e.copy(state = FileState.FILED, kind = kind, drawer = d.placement.drawer, name = d.placement.name, tags = d.placement.tags,
                    filing = Filing(FilingStep.FILED, null, e.filing.tries, wasName = e.name, wasTags = e.tags), from = e.from.copy(filedBy = FiledBy.YOU, filedAt = now))
                is FilingDecision.Ask -> e.copy(kind = kind, filing = Filing(FilingStep.ASK, d.why, e.filing.tries))
                is FilingDecision.Held -> e.copy(kind = kind, filing = Filing(FilingStep.HELD, d.why, e.filing.tries))
            }
            return idx.copy(entries = idx.entries.toMutableList().also { it[i] = next })
        }

        /** [id] put back as [was] (a move undone): its state, drawer and filing as they were. */
        fun restore(idx: FileIndex, id: FileId, was: FileEntry): FileIndex? {
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0) return null
            val e = idx.entries[i]
            val back = e.copy(state = was.state, drawer = was.drawer, filing = was.filing, from = e.from.copy(filedBy = was.from.filedBy, filedAt = was.from.filedAt))
            return idx.copy(entries = idx.entries.toMutableList().also { it[i] = back })
        }
    }
}
