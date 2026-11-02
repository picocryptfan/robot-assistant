// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileGroup
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.LinkText
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.seal.PublicWrap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeParseException

// robot-to-robot-plan.md §3.6 step 5 (job L8): a file from another Robot in Robot Files, what the other Robot said about it, and
// what may go the other way.
//
// What the other Robot says about a file is a claim, never a value: its total, its due date and its merchant go in fields of
// their own ("peer_total", by PEER) that no list, sum, reminder or export reads, and Robot's own reading fills the usual fields
// ("total", by READ) as for any other file. A mismatch is shown; nothing else follows from it.

/** Where a file from another Robot came from: its entry's "peer", "mode" and "why" (§3.6), and the sender's fields. */
class PeerOrigin(val pairing: String, val mode: Int, val why: String, val fields: Map<String, FileField> = emptyMap()) {
    override fun toString(): String = "PeerOrigin" // l10n: not text (toString for logs and tests)
}

/** One thing the other Robot said about a file. [reads]: what Robot read or you set, or null when there is nothing yet. */
data class PeerSays(val key: String, val said: String, val reads: String?, val differs: Boolean = false)

object FilePeer {
    /** A field holding what the other Robot said: this and the usual field's name. */
    const val PREFIX = "peer_" // l10n: not text (a field's code)
    private const val PEER = "peer" // l10n: not text (an index key)
    private const val MODE = "mode" // l10n: not text (an index key)
    private const val WHY = "why" // l10n: not text (an index key)

    /** In the order a file's page shows them. */
    private val KEYS = listOf("merchant", "title", "date", "total", "currency", "due") // l10n: not text (field codes)

    fun isPeerField(key: String) = key.startsWith(PREFIX)

    /** An entry's keys for [o], beside its known ones. */
    internal fun extra(o: PeerOrigin): Map<String, Any?> = mapOf(PEER to o.pairing, MODE to o.mode.toString(), WHY to o.why)

    /** The pairing a file came through, or null for one that didn't come from another Robot. */
    fun peerOf(e: FileEntry): String? = (e.extra[PEER] as? String)?.takeIf { e.from.source == FileSource.ROBOT }

    /** What an offer says about its file, as an entry's fields: each checked again, each by PEER, none a field anything acts on. */
    fun fields(o: OfferFields?): Map<String, FileField> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, FileField>()
        fun put(k: String, v: String?) {
            val x = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= FileIndexCodec.MAX_FIELD_VALUE && FileIndexCodec.plain(it) } ?: return
            out[PREFIX + k] = FileField(x, FieldBy.PEER)
        }
        put("merchant", o.merchant)
        put("title", o.title)
        put("date", o.date?.takeIf(::isDay))
        val total = o.total?.takeIf { o.currency != null && it <= FileFields.MAX_CENTS }
        put("total", total?.let(FileFields::plain))
        put("currency", o.currency.takeIf { total != null })
        put("due", o.due?.takeIf(::isDay))
        return out
    }

    /**
     * What the other Robot said about [e], beside what Robot read or you set. Its currency isn't a line of its own; a total in
     * another currency differs.
     */
    fun says(e: FileEntry): List<PeerSays> = KEYS.filter { it != "currency" }.mapNotNull { k ->
        val said = e.fields[PREFIX + k]?.takeIf { it.by == FieldBy.PEER }?.value ?: return@mapNotNull null
        val mine = e.fields[k]?.takeIf { it.by != FieldBy.PEER }?.value
        val same = mine != null && when (k) {
            "total" -> FileFields.amountOf(mine) == FileFields.amountOf(said) && (e.fields["currency"]?.value ?: e.fields[PREFIX + "currency"]?.value) == e.fields[PREFIX + "currency"]?.value
            else -> mine == said
        }
        PeerSays(k, said, mine, mine != null && !same)
    }

    /**
     * Whether an arrival may be filed. Its bytes were sealed with the hash the offer named, and this is that check again, on
     * what Files itself read. A file that could install or run something goes in only when its offer said so and you said yes
     * on a card; bytes that say otherwise than the offer (a "photo" that is an installer) never do.
     */
    fun mayFile(v: TransferView, sha256: String, size: Long, type: FileType): Boolean {
        val whole = sha256 == v.offer.sha256 && size == v.offer.size
        val dangerous = type.group == FileGroup.DANGEROUS
        return whole && !v.out && (!dangerous || (v.offer.kind == OfferKind.DANGEROUS && !v.alone))
    }

    /** What an offer calls [e]'s kind. */
    fun kindOf(e: FileEntry, type: FileType?): OfferKind = when {
        type?.group == FileGroup.DANGEROUS || e.kind == FileKind.DANGEROUS -> OfferKind.DANGEROUS
        else -> when (e.kind) {
            FileKind.RECEIPT -> OfferKind.RECEIPT
            FileKind.BILL -> OfferKind.BILL
            FileKind.DOCUMENT -> OfferKind.DOCUMENT
            FileKind.ID -> OfferKind.ID
            FileKind.TAX_SLIP -> OfferKind.TAX_SLIP
            FileKind.TICKET -> OfferKind.TICKET
            FileKind.MEDICAL -> OfferKind.MEDICAL
            FileKind.PHOTO -> OfferKind.PHOTO
            FileKind.VIDEO -> OfferKind.VIDEO
            FileKind.NOTE -> OfferKind.NOTE
            FileKind.TRANSCRIPT -> OfferKind.TRANSCRIPT
            FileKind.OTHER, FileKind.UNKNOWN, FileKind.DANGEROUS -> OfferKind.OTHER
        }
    }

    /** What may go with [e]: only what Robot read or you set (never what another Robot said), each as an offer takes it. */
    fun offerFields(e: FileEntry): OfferFields? {
        fun v(k: String) = e.fields[k]?.takeIf { it.by != FieldBy.PEER }?.value
        fun name(k: String) = v(k)?.takeIf { LinkText.ok(it, RequestCaps.NAME) }
        val currency = v("currency")?.takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } }
        val total = v("total")?.let(FileFields::amountOf)?.takeIf { currency != null && it in 0..RequestCaps.MAX_MONEY }
        val merchant = name("merchant"); val title = name("title")
        // F87: a photo's or a video's date is when it was taken, which its stripped copy no longer says.
        val date = v("date")?.takeIf(::isDay)?.takeIf { e.kind != FileKind.PHOTO && e.kind != FileKind.VIDEO }; val due = v("due")?.takeIf(::isDay)
        if (merchant == null && title == null && date == null && total == null && due == null) return null
        return OfferFields(merchant, title, date, total, currency.takeIf { total != null }, due)
    }

    /** A date or a clock's count in a file name, as cameras and messaging apps write them ("PXL_20261001_1234.jpg", "IMG-20261001-WA0001", "1791234567890.jpg"). */
    private val WHEN_IN_NAME = Regex("""(?<!\d)(?:(?:19|20)\d{2}[-_.]?(?:0[1-9]|1[0-2])[-_.]?(?:0[1-9]|[12]\d|3[01])|1[5-9]\d{8}(?:\d{3})?(?!\d))""")

    /**
     * F87: the name a picture or a video goes out under: a camera's own name says when it was taken, which the stripped copy no
     * longer does, so one holding a date goes as "photo" or "video". What the file is decides, as [FileKind] stays UNKNOWN while
     * filing waits and a filed picture with no name to build keeps its source name under another kind ("PXL_20261001.jpg", RECEIPT).
     * A name you gave it stays, and so does any date in it that's the file's own (a receipt's "2026-10-01 Costco"): one Robot read
     * or you set, which the offer carries anyway ([offerFields]).
     */
    fun nameToSend(e: FileEntry, type: FileType, name: String): String {
        val video = e.kind == FileKind.VIDEO || type.group == FileGroup.VIDEO
        val photo = e.kind == FileKind.PHOTO || type.group == FileGroup.IMAGE || type.group == FileGroup.IMAGE_OTHER
        if (e.named || (!video && !photo)) return name
        val stamps = WHEN_IN_NAME.findAll(name).map { it.value }.toList()
        if (stamps.isEmpty() || stamps.all { it in ownDates(e) }) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 8 } ?: name.length
        return (if (video) "video" else "photo") + name.substring(dot) // l10n: not text (a file name)
    }

    /** The dates that are [e]'s own, as [offerFields] would send them: none for a photo or a video, whose date is when it was taken. */
    private fun ownDates(e: FileEntry): Set<String> = offerFields(e)?.let { setOfNotNull(it.date, it.due) }.orEmpty()

    /** [name] as an offer may carry it: cut to the offer's 80 characters, keeping its extension; null when nothing's left. */
    fun offerName(name: String): String? {
        val max = RequestCaps.FILE_NAME
        val dot = name.lastIndexOf('.').takeIf { it > 0 && name.length - it <= 8 } ?: name.length
        val ext = name.substring(dot)
        val cut = if (name.length <= max) name else name.take(max - ext.length).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it } + ext
        return cut.takeIf(FileOffer::nameOk)
    }

    private fun isDay(s: String) = try { LocalDate.parse(s).toString() == s } catch (_: DateTimeParseException) { false }
}

/**
 * Files' intake for what :link left in incoming/link/ (§3.6, "at the first unlock"): each arrival opened with one Keystore RSA
 * decrypt, read through Files' own copy (which sniffs it and seals it as format 1), checked again, and only then in the index.
 * Run after an unlock, with Robot Files open; what can't be done now stays where it is for the next pass.
 */
class PeerArrivals(
    private val arrived: (String) -> File,
    private val inbox: PublicWrap,
    private val copy: (InputStream, Long) -> Intake,
    private val duplicate: (String) -> Boolean,
    private val discard: (Staged) -> Unit,
    /** The commit: false only for "not now" (full, locked), when the staged file went with it and the arrival stays. */
    private val commit: (Intake.Ready, FileIntake) -> Boolean,
) {
    /** Takes what's ARRIVED in [views], telling :link each settled one through [tell] (its file goes then). How many were filed. */
    fun run(views: List<TransferView>, tell: (String, Boolean) -> Unit): Int {
        var filed = 0
        for (v in views) {
            if (v.out || v.state != XferState.ARRIVED) continue
            when (one(v)) {
                Outcome.FILED -> { filed++; tell(v.id, true) }
                Outcome.HAD -> tell(v.id, true)
                Outcome.BAD -> tell(v.id, false)
                Outcome.LATER -> {}
            }
        }
        return filed
    }

    private enum class Outcome { FILED, HAD, BAD, LATER }

    private fun one(v: TransferView): Outcome {
        val id = FileId.of(v.id) ?: return Outcome.BAD
        val file = arrived(v.id)
        if (!file.isFile) return Outcome.BAD
        val r = try {
            copy(FileSeal.arrivedReader(FileInputStream(file), id, inbox), v.offer.size)
        } catch (x: FileException) {
            return if (x.problem == FileProblem.DAMAGED || x.problem == FileProblem.KEY_GONE || x.problem == FileProblem.NEWER) Outcome.BAD else Outcome.LATER
        } catch (_: IOException) {
            return Outcome.LATER
        }
        return when (r) {
            is Intake.Failed -> when (r.problem) {
                IntakeProblem.UNREADABLE, IntakeProblem.TOO_BIG, IntakeProblem.EMPTY -> Outcome.BAD
                else -> Outcome.LATER
            }
            is Intake.Ready -> settle(v, r)
        }
    }

    private fun settle(v: TransferView, r: Intake.Ready): Outcome {
        if (!FilePeer.mayFile(v, r.staged.sha256, r.staged.size, r.type)) { discard(r.staged); return Outcome.BAD }
        if (duplicate(r.staged.sha256)) { discard(r.staged); return Outcome.HAD }
        val intake = FileIntake(FileIndexCodec.cleanName(v.offer.name), FileSource.ROBOT, type = r.type.mime,
            peer = PeerOrigin(v.pairing, v.mode, v.offer.why.wire, FilePeer.fields(v.offer.fields)))
        return if (commit(r, intake)) Outcome.FILED else Outcome.LATER
    }
}

/** How sending a file to another Robot ended. [ASK_FIRST]: Robot can't remove this type's location and camera data, and only you can say it goes as it is. */
enum class PeerSent { OFFERED, ASK_FIRST, LOCKED, TOO_BIG, NO_COPY, FAILED }

/**
 * The sender's side of §3.6 step 1: the copy, made where the phone is unlocked, from [plain] (what :files made, or the file as it
 * is when you said so), sealed whole under a new key before :link hears of it: :link never holds a file's plain bytes when sending.
 * [outgoing] names the sealed copy; [post] tells :link about the offer and hands it the key, and false means no offer went.
 */
class PeerOut(
    private val outgoing: (String) -> File,
    private val post: (TransferView, ByteArray) -> Boolean,
    private val locked: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
) {
    fun send(pairing: String, e: FileEntry, type: FileType, name: String, why: OfferWhy, re: String?, plain: () -> InputStream?): PeerSent {
        if (locked()) return PeerSent.LOCKED
        if (e.from.size > RequestCaps.FILE_MAX_BYTES) return PeerSent.TOO_BIG
        val offered = FilePeer.offerName(FilePeer.nameToSend(e, type, name)) ?: return PeerSent.FAILED
        val id = LinkIds.newId()
        val file = LinkIds.newId()
        val copy = outgoing(id)
        val part = File(copy.path + PART)
        val key = ByteArray(XferSeal.KEY_BYTES).also(random::nextBytes)
        var kept = false
        try {
            val sha = MessageDigest.getInstance("SHA-256") // l10n: not text (an algorithm's name)
            var size = 0L
            copy.parentFile?.mkdirs()
            val input = plain() ?: return PeerSent.NO_COPY
            input.use { i ->
                val buf = ByteArray(XferSeal.BUFFER)
                var n = i.read(buf)
                if (n <= 0) return PeerSent.NO_COPY
                XferSeal.writer(FileOutputStream(part), file, key).use { w ->
                    while (n >= 0) {
                        size += n
                        if (size > RequestCaps.FILE_MAX_BYTES) return PeerSent.TOO_BIG
                        sha.update(buf, 0, n)
                        w.write(buf, 0, n)
                        n = i.read(buf)
                    }
                }
            }
            // The phone may have locked while the copy was made: nothing goes then.
            if (locked()) return PeerSent.LOCKED
            if (!part.renameTo(copy)) return PeerSent.FAILED
            kept = true
            val offer = FileOffer(file, offered, FilePeer.kindOf(e, type), size, LinkBytes.hex(sha.digest()), why, re, FilePeer.offerFields(e))
            kept = post(TransferView(id, pairing, true, XferState.OFFERED, offer, 0, 0, 0, false), key)
            return if (kept) PeerSent.OFFERED else PeerSent.FAILED
        } catch (_: Exception) {
            kept = false
            return PeerSent.FAILED // A file gone, a full disk, a refused seal: nothing went, and no copy stays.
        } finally {
            key.fill(0)
            part.delete()
            if (!kept) copy.delete()
        }
    }

    private companion object {
        const val PART = ".part" // l10n: not text (a file's extension)
    }
}
