// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskRequest
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.ListShareItem
import app.heyrobot.core.link.ListShareRec
import app.heyrobot.core.link.ListShareState
import app.heyrobot.core.link.ListShareView
import app.heyrobot.core.link.SlidingSum
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LsLeave
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType

/**
 * 1.5, shared lists: the requests about them, on the link thread. :link never holds your lists (ProcessIsolationTest pins it):
 * it gathers an offer's parts, keeps what arrives sealed in [box] (robot_lists_v1's inbox, RSA like the cards', so it works
 * while locked and opens only unlocked) and hands it to :voice, which applies it to the lists file (core ListShare).
 *
 *  - One offer, one card: parts wait until all are here; only then does the desk make a card, whatever order they came in.
 *  - The peer lies: parts counted, items counted, the name pinned, at most [MAX_GATHERING] offers gathering per Robot and
 *    [MAX_RECS] records in all. Whatever breaks that is declined and what it held deleted.
 *  - No record, no way in: `ls.op` is only taken for a list JOINED with that pairing; everything else is declined.
 *  - The Lists journal's cap of 50 adds is never touched: nothing a peer sends is written to it. [box] holds 500 requests; full
 *    is `busy`, and their Robot tries again.
 */
class ListsShareHandler(
    private val st: LinkStateStore,
    private val box: DeskBox,
    private val decoder: RequestDecoder,
    private val env: DeskEnv,
) : DeskHandler {
    override val types = setOf(RequestType.LS_OFFER, RequestType.LS_OP, RequestType.LS_LEAVE)

    /** What you send, per Robot (in memory): the other side's limit of 60 changes an hour, kept on this side too. */
    private val sending = HashMap<String, SlidingSum>()

    private fun rec(pairing: String, list: String): ListShareRec? = st.listShares[ListShareRec.key(pairing, list)]

    private fun drop(r: ListShareRec) {
        if (r.state == ListShareState.GATHERING || r.state == ListShareState.CARD) r.ids.forEach(box::delete)
        st.deleteListShare(r)
    }

    // An offer coming in ------------------------------------------------------------------------------------------------------

    override fun hold(pairing: String, r: LinkRequest, out: DeskOut, card: () -> AckState): AckState? {
        val b = r.body as? LsOffer ?: return null
        val cur = rec(pairing, b.list)
        val parts = b.parts.toInt(); val part = b.part.toInt()
        val bit = 1L shl (part - 1)
        val hash = ListShareRec.hash(b.name)
        if (cur != null && cur.state != ListShareState.GATHERING) return AckState.DECLINED
        if (cur == null && (st.listShares.values.count { it.pairing == pairing && it.state == ListShareState.GATHERING } >= MAX_GATHERING || st.listShares.size >= MAX_RECS)) return AckState.DECLINED
        val was = cur ?: ListShareRec(b.list, pairing, ListShareState.GATHERING, out.now(), parts, nameHash = hash)
        if (was.parts != parts || !was.nameHash.contentEquals(hash)) { drop(was); return AckState.DECLINED }
        if (was.seen and bit != 0L) return AckState.RECEIVED
        val items = was.items + b.items.size
        if (items > RequestCaps.LIST_ITEMS) { drop(was); return AckState.DECLINED }
        val seen = was.seen or bit
        val next = was.copy(seen = seen, items = items)
        if (next.partsSeen == parts) {
            // The last to arrive is the card. If the card inbox is full it's busy: this part comes again and is still the one missing.
            val a = card()
            if (a != AckState.BUSY) st.put(next.copy(state = ListShareState.CARD))
            return a
        }
        val id = try {
            box.put(pairing, out.now(), app.heyrobot.core.link.requests.LinkRequests.wire(r))
        } catch (_: LinkStateException) {
            return AckState.BUSY
        }
        st.put(next.copy(ids = next.ids + id))
        return AckState.RECEIVED
    }

    override fun yes(r: LinkRequest) = if (r.body is LsOffer) DeskYes.ACCEPT else null

    override fun stale(pairing: String, r: LinkRequest): Boolean = (r.body as? LsOffer)?.let { rec(pairing, it.list)?.state != ListShareState.CARD } ?: false

    /** Your yes: the card's part joins the others for :voice, and now they may be opened and applied. */
    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? {
        val b = r.body as? LsOffer ?: return null
        val cur = rec(pairing, b.list)?.takeIf { it.state == ListShareState.CARD } ?: return null
        try {
            box.put(pairing, out.now(), app.heyrobot.core.link.requests.LinkRequests.wire(r))
        } catch (_: LinkStateException) {
            return null
        }
        st.put(cur.copy(state = ListShareState.JOINED, at = out.now(), ids = emptyList()))
        env.ping()
        return DeskDone()
    }

    override fun declined(pairing: String, r: LinkRequest, out: DeskOut) {
        (r.body as? LsOffer)?.let { b -> rec(pairing, b.list)?.takeIf { it.state == ListShareState.CARD }?.let(::drop) }
    }

    // Changes coming in -------------------------------------------------------------------------------------------------------

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState = when (val b = r.body) {
        is LsOp -> when (rec(pairing, b.list)?.state) {
            ListShareState.JOINED -> try {
                box.put(pairing, out.now(), app.heyrobot.core.link.requests.LinkRequests.wire(r))
                env.ping()
                AckState.RECEIVED
            } catch (_: LinkStateException) {
                AckState.BUSY
            }
            // Their yes hasn't reached you yet: it's on its way, ahead of this on any order but a lost one.
            ListShareState.OFFERED -> AckState.BUSY
            else -> AckState.DECLINED
        }
        is LsLeave -> {
            // Theirs alone to say, for their pairing: no other Robot's share moves. :voice tells you, unlocked. An offer still
            // gathering or on a card goes too, with the parts it holds: the desk then takes the card down as stale.
            rec(pairing, b.list)?.let { drop(it); env.ping() }
            AckState.RECEIVED
        }
        else -> AckState.RECEIVED
    }

    // What you send (from :voice, on the link thread) ---------------------------------------------------------------------------

    /** A part of your list to [pairing] (one per call: 13 of them don't fit a Binder call); its request's id, or null. */
    fun offerPart(pairing: String, p: LsOffer, out: DeskOut): String? {
        val cur = rec(pairing, p.list)
        if (cur == null && st.listShares.size >= MAX_RECS) return null
        if (cur != null && (cur.state != ListShareState.OFFERED || cur.ids.size >= ListShareRec.MAX_IDS)) return null
        val id = send(out, pairing, p) ?: return null
        st.put((cur ?: ListShareRec(p.list, pairing, ListShareState.OFFERED, out.now())).let { it.copy(ids = it.ids + id) })
        return id
    }

    /** One batch; its request's id, or null while it can't go (not joined, Shared lists set to Off, too many unanswered, over the hour's limit). */
    fun sendOps(pairing: String, b: LsOp, out: DeskOut): String? {
        if ((st.pairings[pairing]?.levels?.get(LinkCapability.LISTS) ?: LinkLevel.OFF) == LinkLevel.OFF) return null
        val r = rec(pairing, b.list)?.takeIf { it.state == ListShareState.JOINED && it.ids.size < ListShareRec.MAX_IDS } ?: return null
        val rate = sending.getOrPut(pairing) { rate() }
        val now = out.now()
        if (!rate.room(now, b.ops.size.toLong())) return null
        val id = send(out, pairing, b) ?: return null
        rate.add(now, b.ops.size.toLong())
        st.put(r.copy(ids = r.ids + id))
        return id
    }

    /** You stop sharing: their Robot is told, the record goes, and your copy stays yours. */
    fun leave(pairing: String, list: String, out: DeskOut): Boolean {
        val r = rec(pairing, list) ?: return false
        if (r.state == ListShareState.JOINED || r.state == ListShareState.OFFERED) send(out, pairing, LsLeave(list))
        drop(r)
        return true
    }

    /** :voice marked everything owed again: the lost flag has done its work. */
    fun lostSeen(pairing: String, list: String) {
        rec(pairing, list)?.takeIf { it.lost }?.let { st.put(it.copy(lost = false)) }
    }

    private fun send(out: DeskOut, pairing: String, b: LinkBody): String? = out.send(pairing, b, RequestTtl.LIST)

    // Acks to what you sent ----------------------------------------------------------------------------------------------------

    /** [state]: their ack to [requestId]; null when the outbox gave up on it. These sends aren't on the desk's lists. */
    override fun unlisted(pairing: String, requestId: String, state: AckState?) {
        val r = st.listShares.values.firstOrNull { it.pairing == pairing && requestId in it.ids && (it.state == ListShareState.OFFERED || it.state == ListShareState.JOINED) } ?: return
        if (r.state == ListShareState.OFFERED) {
            when (state) {
                AckState.HANDLED -> st.put(r.copy(state = ListShareState.JOINED, ids = emptyList()))
                null, AckState.DECLINED, AckState.EXPIRED -> st.deleteListShare(r)
                else -> return
            }
            env.ping()
            return
        }
        when (state) {
            AckState.BUSY, AckState.WAITING -> return
            AckState.DECLINED -> st.deleteListShare(r)
            null, AckState.EXPIRED -> st.put(r.copy(ids = r.ids - requestId, lost = true))
            else -> st.put(r.copy(ids = r.ids - requestId))
        }
        env.ping()
    }

    // For :voice (unlocked) ----------------------------------------------------------------------------------------------------

    /** The records, with how many changes may go to each Robot this hour. */
    fun views(now: Long): List<ListShareView> = st.listShares.values.toList().map { ListShareView(it, sending.getOrPut(it.pairing) { rate() }.let { s -> (s.max - s.total(now)).toInt() }) }

    /** Requests ready to apply, the oldest first, at most [PENDING] at a time. What's gathered or on a card stays closed. */
    fun pending(): ByteArray {
        val waiting = st.listShares.values.filter { it.state == ListShareState.GATHERING || it.state == ListShareState.CARD }.flatMap { it.ids }.toSet()
        val out = ArrayList<ListShareItem>()
        for (id in box.ids()) {
            if (id in waiting) continue
            if (out.size >= PENDING) break
            val w = box.open(id) ?: continue
            // No reader now: the sender has dropped its copy, so this stays for the next pass.
            val read = decoder.stored(w.request, app.heyrobot.core.link.requests.LinkRequests.LIVE) ?: break
            val req = (read as? RequestRead.Ok)?.request
            val list = when (val b = req?.body) { is LsOffer -> b.list; is LsOp -> b.list; else -> null }
            if (req == null || list == null || rec(w.pairing, list)?.state != ListShareState.JOINED) { box.delete(id); continue }
            out += ListShareItem(id, w.pairing, DeskRequest.of(req))
        }
        return ListShareItem.encodeAll(out)
    }

    fun applied(ids: List<String>) = ids.forEach(box::delete)

    // Old records --------------------------------------------------------------------------------------------------------------

    override fun prune(now: Long) {
        for (r in st.listShares.values.toList()) {
            val age = now - r.at
            val ended = st.pairings[r.pairing]?.state != PairingState.ACTIVE
            val old = when (r.state) {
                ListShareState.GATHERING -> age > GATHER_S
                ListShareState.CARD, ListShareState.OFFERED -> age > OFFER_S
                ListShareState.JOINED -> false
            }
            if (ended || old) drop(r)
        }
    }

    private fun rate() = SlidingSum(LinkLimits.HOUR, LinkLimits.LIST_OPS_PER_HOUR.toLong())

    companion object {
        const val MAX_GATHERING = 3
        const val MAX_RECS = 200
        const val PENDING = 16
        const val GATHER_S = 7L * 86_400
        const val OFFER_S = 30L * 86_400
    }
}

/** How long a list's changes keep trying: as long as the protocol allows; a phone can be off for days. */
private object RequestTtl {
    val LIST: Long get() = app.heyrobot.core.link.requests.LinkRequests.MAX_TTL
}
