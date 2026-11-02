// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.RequestCaps

/** Where a list stands with one other Robot. Codes are stable: never renumber one. */
enum class ListShareState(val code: Int) {
    /** Its `ls.offer` parts are coming in; no card yet (one offer is one card, whatever its parts). */
    GATHERING(1),
    /** Every part is here and the card waits for your tap. */
    CARD(2),
    /** Your yes, or their yes to your offer: changes go both ways. */
    JOINED(3),
    /** You offered it; no answer yet. */
    OFFERED(4);

    companion object { fun of(code: Int): ListShareState? = entries.firstOrNull { it.code == code } }
}

/**
 * What :link keeps about one list shared with one Robot, in robot_link_v1 (readable locked, so offers can be gathered then):
 * ids and counts only. A list's name and words stay sealed in the inbox, then in the lists file. [ids]: while GATHERING or
 * CARD, the parts held sealed; while OFFERED, your parts sent; while JOINED, your batches not acknowledged yet. [nameHash]
 * pins the name a first part claimed, so the name on the card is the name you get. [lost]: a batch never arrived, so
 * everything on the list is owed again.
 */
data class ListShareRec(
    val list: String,
    val pairing: String,
    val state: ListShareState,
    val at: Long,
    val parts: Int = 0,
    val seen: Long = 0,
    val items: Int = 0,
    val nameHash: ByteArray = ByteArray(HASH),
    val ids: List<String> = emptyList(),
    val lost: Boolean = false,
) {
    init {
        require(LinkIds.isId(list) && LinkIds.isId(pairing) && at >= 0 && parts in 0..RequestCaps.LIST_PARTS && items in 0..RequestCaps.LIST_ITEMS)
        require(nameHash.size == HASH && ids.size <= MAX_IDS && ids.all(LinkIds::isId) && seen >= 0)
    }

    /** Parts seen so far. */
    val partsSeen: Int get() = java.lang.Long.bitCount(seen)

    override fun equals(other: Any?) = other is ListShareRec && other.encode().contentEquals(encode())
    override fun hashCode() = list.hashCode() * 31 + pairing.hashCode()
    override fun toString(): String = "ListShareRec($state, $partsSeen/$parts)" // l10n: not text (toString for logs and tests)

    fun encode(): ByteArray {
        val w = BinWriter(128).bytes(MAGIC).bytes(DeskEntry.idBytes(list)).bytes(DeskEntry.idBytes(pairing)).u8(state.code).u64(at)
            .u8(parts).u16(seen.toInt()).u16(items).bytes(nameHash).u8(if (lost) 1 else 0).u8(ids.size)
        ids.forEach { w.bytes(DeskEntry.idBytes(it)) }
        return w.toByteArray()
    }

    companion object {
        const val HASH = 16
        /** Enough for an offer's 13 parts, and how many batches may wait unacknowledged before no more are queued. */
        const val MAX_IDS = RequestCaps.LIST_PARTS.toInt()
        const val MAX_BYTES = 4 + 16 + 16 + 1 + 8 + 1 + 2 + 2 + HASH + 1 + 1 + MAX_IDS * 16
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'S'.code.toByte(), 1)

        fun hash(name: String): ByteArray = LinkBytes.sha256("robot-link-list-name-v1".toByteArray(Charsets.US_ASCII), name.toByteArray(Charsets.UTF_8)).copyOf(HASH) // l10n: not text (a hash's label)

        /** The record's key: not the list's id or the pairing in the clear, as LinkPlan's. */
        fun key(pairing: String, list: String): String {
            require(LinkIds.isId(pairing) && LinkIds.isId(list))
            val h = LinkBytes.sha256("robot-link-list-v1".toByteArray(Charsets.US_ASCII), DeskEntry.idBytes(pairing), DeskEntry.idBytes(list)).copyOf(LinkIds.BYTES) // l10n: not text (a hash's label)
            if (LinkBytes.allZero(h)) h[0] = 1
            return LinkBytes.hex(h)
        }

        fun decode(b: ByteArray): ListShareRec? = try {
            if (b.size > MAX_BYTES) null else BinReader(b).let { r -> read(r).also { r.end() } }
        } catch (_: RuntimeException) {
            null
        }

        private fun read(r: BinReader): ListShareRec {
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val list = DeskEntry.id(r); val pairing = DeskEntry.id(r)
            val state = ListShareState.of(r.u8()) ?: throw LinkBad
            val at = r.u64(); val parts = r.u8(); val seen = r.u16().toLong(); val items = r.u16()
            val hash = r.bytes(HASH)
            val lost = DeskEntry.flag(r)
            val n = r.u8()
            if (n > MAX_IDS) throw LinkBad
            return ListShareRec(list, pairing, state, at, parts, seen, items, hash, List(n) { DeskEntry.id(r) }, lost)
        }
    }
}

/** One record and how many list changes may go to its Robot this hour, for :voice. */
class ListShareView(val rec: ListShareRec, val room: Int) {
    override fun toString(): String = "ListShareView($rec, $room)" // l10n: not text (toString for logs and tests)

    companion object {
        private const val MAX = 200

        fun encodeAll(views: List<ListShareView>): ByteArray {
            val w = BinWriter(256).u16(views.size.coerceAtMost(MAX))
            views.take(MAX).forEach { v -> val b = v.rec.encode(); w.u16(b.size).bytes(b).u16(v.room.coerceIn(0, 0xFFFF)) }
            return w.toByteArray()
        }

        fun decodeAll(b: ByteArray): List<ListShareView>? = try {
            val r = BinReader(b)
            val n = r.u16()
            if (n > MAX) throw LinkBad
            List(n) { ListShareView(ListShareRec.decode(r.bytes(r.u16())) ?: throw LinkBad, r.u16()) }.also { r.end() }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Requests of a list held sealed, as :voice takes them: each with its inbox id and the pairing that sent it. */
class ListShareItem(val id: String, val pairing: String, val request: DeskRequest) {
    override fun toString(): String = "ListShareItem" // l10n: not text (toString for logs and tests)

    companion object {
        /** A batch at most (the Binder's transaction is 1 MB). */
        const val MAX_BYTES = 256 * 1024

        fun encodeAll(items: List<ListShareItem>): ByteArray {
            val w = BinWriter(1024)
            var used = 2
            val fit = items.takeWhile { i -> used += 32 + 8 + i.request.frame.size + i.request.plain.size; used <= MAX_BYTES }
            w.u16(fit.size)
            fit.forEach { i ->
                w.bytes(DeskEntry.idBytes(i.id)).bytes(DeskEntry.idBytes(i.pairing))
                val f = i.request.frame; val p = i.request.plain
                w.u32(f.size.toLong()).bytes(f).u32(p.size.toLong()).bytes(p)
            }
            return w.toByteArray()
        }

        fun decodeAll(b: ByteArray): List<ListShareItem>? = try {
            val r = BinReader(b)
            val n = r.u16()
            List(n) {
                val id = DeskEntry.id(r); val pairing = DeskEntry.id(r)
                val f = r.bytes(size(r)); val p = r.bytes(size(r))
                ListShareItem(id, pairing, DeskRequest(f, p))
            }.also { r.end() }
        } catch (_: RuntimeException) {
            null
        }

        private fun size(r: BinReader): Int = r.u32().also { if (it > MAX_BYTES) throw LinkBad }.toInt()
    }
}
