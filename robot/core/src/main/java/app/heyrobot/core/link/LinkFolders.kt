// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldOp
import app.heyrobot.core.link.requests.FoldOpKind
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.RequestCaps

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, D17; job L11): a shared folder as :link keeps it (LinkPurpose.FOLDER,
// sealed under robot_link_v1, keyed by [FolderRec.key]). One record per folder and pairing. It holds what's in the folder, never a
// file: files travel as L8's offers and are filed in Robot Files, locked and from that Robot. Every word and number from the other
// Robot is a claim:
//
//   - No record, or not joined: nothing counts.
//   - 500 files and 1 GiB a folder, counted on the sizes in offers (which L8's transfer holds them to, byte by byte and by hash).
//   - An item id is used once: it's added, then removed, and never comes back. They can remove only what they added.
//   - A removal only takes the line out of the folder. Nothing here can reach your file in Robot Files.
//
//   record  "RFD" 1 | fold 16 | pairing 16 | state u8 | mine u8 | at u64 | invite 16 (zeros: none) | name | count u16, each: item 16
//           | file 16 | mine u8 | gone u8 | rev u64 | size u64 | at u64 | name
//   text    u16 length + UTF-8
//   all     "RFA" 1 | count u16, each: u32 length + record

enum class FolderState(val code: Int) {
    /** You invited them: no answer yet. */
    INVITED(1),
    JOINED(2);

    companion object { fun of(code: Int): FolderState? = entries.firstOrNull { it.code == code } }
}

/** A line of a folder. [file]: the offer's id. [mine]: you added it. [size] and [name]: 0 and empty until its offer came. */
data class FolderItem(val item: String, val file: String, val mine: Boolean, val rev: Long, val size: Long, val name: String, val at: Long, val gone: Boolean = false) {
    override fun toString(): String = "FolderItem(mine=$mine, gone=$gone)" // l10n: not text (toString for logs and tests; never the name)
}

data class FolderRec(val fold: String, val pairing: String, val state: FolderState, val name: String, val mine: Boolean, val at: Long, val invite: String? = null, val items: List<FolderItem> = emptyList()) {
    val live: List<FolderItem> get() = items.filter { !it.gone }
    /** F71: what counts toward [LinkLimits.FOLDER_BYTES]: what they took out still does (it arrived and stays in Files); what you took out doesn't. */
    val bytes: Long get() = items.sumOf { it.size }
    /** F71: the lines that count toward [LinkLimits.FOLDER_FILES], on the same terms. */
    val used: Int get() = items.count { !it.gone || it.size > 0 }
    fun item(id: String) = items.firstOrNull { it.item == id }
    override fun toString(): String = "FolderRec($state, ${items.size})" // l10n: not text (toString for logs and tests; never the name)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'F'.code.toByte(), 'D'.code.toByte(), 1)
        private val ALL = byteArrayOf('R'.code.toByte(), 'F'.code.toByte(), 'A'.code.toByte(), 1)
        private val ZERO = ByteArray(LinkIds.BYTES)
        /** All of one folder across Binder at most: under its 1 MB transaction, however many folders there are. */
        const val MAX_LIST_BYTES = 768 * 1024

        fun key(pairing: String, fold: String): String = LinkBytes.hex(LinkBytes.sha256(id(pairing), id(fold)).copyOf(LinkIds.BYTES))
        private fun id(s: String) = DeskEntry.idBytes(s)

        private fun text(w: BinWriter, s: String) { val b = s.toByteArray(); w.u16(b.size).bytes(b) }
        private fun text(r: BinReader, max: Int): String {
            val n = r.u16()
            if (n > max * 4) throw LinkBad
            return LinkBytes.utf8(r.bytes(n))?.takeIf { it.codePointCount(0, it.length) <= max } ?: throw LinkBad
        }

        fun encode(f: FolderRec): ByteArray {
            val w = BinWriter(256).bytes(MAGIC).bytes(id(f.fold)).bytes(id(f.pairing)).u8(f.state.code).u8(if (f.mine) 1 else 0).u64(f.at).bytes(f.invite?.let(::id) ?: ZERO)
            text(w, f.name)
            w.u16(f.items.size)
            f.items.forEach { i ->
                w.bytes(id(i.item)).bytes(id(i.file)).u8(if (i.mine) 1 else 0).u8(if (i.gone) 1 else 0).u64(i.rev).u64(i.size).u64(i.at)
                text(w, i.name)
            }
            return w.toByteArray()
        }

        private fun read(r: BinReader): FolderRec {
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val fold = DeskEntry.id(r); val pairing = DeskEntry.id(r)
            val state = FolderState.of(r.u8()) ?: throw LinkBad
            val mine = DeskEntry.flag(r)
            val at = r.u64()
            val invite = r.bytes(LinkIds.BYTES).let { if (LinkBytes.allZero(it)) null else LinkBytes.hex(it) }
            val name = text(r, RequestCaps.NAME)
            val n = r.u16()
            if (n > FolderRules.MAX_ITEMS) throw LinkBad
            val items = List(n) {
                val item = DeskEntry.id(r); val file = DeskEntry.id(r)
                val m = DeskEntry.flag(r); val g = DeskEntry.flag(r)
                val rev = r.u64(); val size = r.u64(); val t = r.u64()
                FolderItem(item, file, m, rev, size, text(r, RequestCaps.FILE_NAME), t, g)
            }
            return FolderRec(fold, pairing, state, name, mine, at, invite, items)
        }

        fun decode(b: ByteArray): FolderRec? = try {
            BinReader(b).let { r -> read(r).also { r.end() } }
        } catch (_: RuntimeException) {
            null
        }

        /** As many as fit a Binder call, the oldest first; the rest wait until some go. */
        fun encodeAll(all: List<FolderRec>): ByteArray {
            var used = 6
            val parts = all.sortedBy { it.at }.map(::encode).takeWhile { used += 4 + it.size; used <= MAX_LIST_BYTES }
            val w = BinWriter(1024).bytes(ALL).u16(parts.size)
            parts.forEach { w.u32(it.size.toLong()).bytes(it) }
            return w.toByteArray()
        }

        fun decodeAll(b: ByteArray): List<FolderRec>? = try {
            if (b.size > MAX_LIST_BYTES) null else BinReader(b).let { r ->
                if (!ALL.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
                List(r.u16()) {
                    val n = r.u32()
                    if (n > MAX_LIST_BYTES) throw LinkBad
                    decode(r.bytes(n.toInt())) ?: throw LinkBad
                }.also { r.end() }
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

enum class FolderOpResult {
    ADDED, REMOVED,
    /** The same again: nothing changes. */
    SAME,
    /** A removal ahead of its add: it comes again. */
    WAIT,
    /** A lie, or over a cap: nothing changes. */
    REFUSED,
}

enum class FolderOfferFit {
    FITS,
    /** Its `fold.op` hasn't come yet: the offer comes again. */
    NOT_YET,
    /** The same offer again, for a line that already has its file: nothing to do, and the line stays. */
    REPEAT,
    REFUSED,
}

object FolderRules {
    /** The most folders kept on this phone, with anyone. */
    const val MAX_FOLDERS = 32
    /** What a record holds at most: the cap's live items and as many removed ones, whose words are gone. */
    const val MAX_ITEMS = 2 * LinkLimits.FOLDER_FILES

    /** A folder's name from anyone: one line within the cap, and nothing that looks like a path. */
    fun nameOk(name: String): Boolean = LinkText.ok(name, RequestCaps.NAME) && FileOffer.nameOk(name)

    /** Their [op]. The record stays the same unless the result is ADDED or REMOVED. */
    fun apply(f: FolderRec, op: FoldOp, now: Long): Pair<FolderRec, FolderOpResult> {
        val cur = f.item(op.item)
        return when (op.op) {
            FoldOpKind.ADD -> {
                val file = op.file ?: return f to FolderOpResult.REFUSED
                when {
                    cur != null -> f to (if (!cur.mine && cur.file == file && cur.rev == op.rev) FolderOpResult.SAME else FolderOpResult.REFUSED)
                    op.rev > LinkOrder.MAX_JUMP || f.items.any { it.file == file } || f.used >= LinkLimits.FOLDER_FILES -> f to FolderOpResult.REFUSED
                    else -> trimmed(f.copy(items = f.items + FolderItem(op.item, file, false, op.rev, 0, "", now))) to FolderOpResult.ADDED
                }
            }
            FoldOpKind.REMOVE -> when {
                cur == null -> f to FolderOpResult.WAIT
                cur.mine -> f to FolderOpResult.REFUSED // Only what they added. What you added stays yours to take out.
                cur.gone -> f to FolderOpResult.SAME
                op.rev <= cur.rev -> f to FolderOpResult.REFUSED
                else -> f.copy(items = f.items.map { if (it.item == op.item) gone(it, op.rev) else it }) to FolderOpResult.REMOVED
            }
        }
    }

    private fun gone(i: FolderItem, rev: Long) = i.copy(rev = rev, size = if (i.mine) 0 else i.size, name = "", gone = true)

    private fun trimmed(f: FolderRec): FolderRec {
        val over = f.items.size - MAX_ITEMS
        if (over <= 0) return f
        val drop = f.items.filter { it.gone && it.size == 0L }.sortedBy { it.at }.take(over).map { it.item }.toSet()
        return f.copy(items = f.items.filter { it.item !in drop })
    }

    /** Whether their offer [o] may come in for this folder. Never a type that can run, whatever it says; never past the caps. */
    fun offerFits(f: FolderRec, o: FileOffer): FolderOfferFit {
        if (f.state != FolderState.JOINED) return FolderOfferFit.REFUSED
        val i = f.items.firstOrNull { it.file == o.file } ?: return FolderOfferFit.NOT_YET
        return when {
            i.mine || i.gone -> FolderOfferFit.REFUSED
            i.size > 0 -> FolderOfferFit.REPEAT
            o.kind == OfferKind.DANGEROUS || o.size !in 1..RequestCaps.FILE_MAX_BYTES || !FileOffer.nameOk(o.name) -> FolderOfferFit.REFUSED
            f.bytes + o.size > LinkLimits.FOLDER_BYTES -> FolderOfferFit.REFUSED
            else -> FolderOfferFit.FITS
        }
    }

    /** What the accepted offer says it is. */
    fun offered(f: FolderRec, o: FileOffer): FolderRec = f.copy(items = f.items.map { if (it.file == o.file) it.copy(size = o.size, name = o.name) else it })

    /** The line is out, for an offer that didn't fit: it counts for nothing and won't be asked for again. */
    fun refused(f: FolderRec, file: String): FolderRec = f.copy(items = f.items.map { if (it.file == file && !it.mine && it.size == 0L) gone(it, it.rev) else it })

    /** A file of yours added, when it fits. */
    fun add(f: FolderRec, item: String, file: String, name: String, size: Long, now: Long): FolderRec? {
        if (f.state != FolderState.JOINED || f.used >= LinkLimits.FOLDER_FILES || f.bytes + size > LinkLimits.FOLDER_BYTES || size !in 1..RequestCaps.FILE_MAX_BYTES) return null
        return trimmed(f.copy(items = f.items + FolderItem(item, file, true, 1, size, name, now)))
    }

    /** Your removal of what you added: what goes to the other Robot says the next counter. */
    fun remove(f: FolderRec, item: String): Pair<FolderRec, Long>? {
        val i = f.item(item)?.takeIf { it.mine && !it.gone } ?: return null
        val rev = i.rev + 1
        return f.copy(items = f.items.map { if (it.item == item) gone(it, rev) else it }) to rev
    }

    /** For a backup: that it's shared and with whom, none of what's in it. */
    fun bare(f: FolderRec): FolderRec? = if (f.state == FolderState.JOINED) f.copy(items = emptyList(), invite = null) else null
}
