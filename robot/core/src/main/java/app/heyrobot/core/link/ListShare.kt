// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.ListOp
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ItemOp
import app.heyrobot.core.lists.ItemText
import app.heyrobot.core.lists.ListChange
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListItem
import app.heyrobot.core.lists.ListsCodec
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsShared
import app.heyrobot.core.lists.ListsText
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.link.requests.ListItem as WireItem

// Pure. 1.5, shared lists (lists-plan.md §9; robot-to-robot-plan.md "Decided after writing"): what another Robot's `ls.offer`
// parts and `ls.op` batches do to your lists file, and what your changes owe it. It runs where the lists are (:voice, after
// unlock); :link never opens them and only keeps requests sealed. Every number and word here is another person's claim:
//
//   - The order rule is LinkOrder.wins, per item: the higher counter, and on a tie the higher request id. An item keeps the
//     request id that carried its counter ("req"), which Lists didn't store before this.
//   - A counter may jump at most LinkOrder.MAX_JUMP, so a peer can't pin an item where your later changes never win.
//   - An op only reaches a list shared with that pairing, and only an item of that list or a new one: an id your other list
//     holds is refused, so no peer reaches a private list by naming it.
//   - Words pass the rules your own adds pass (one clean line, the caps, no secrets), or the change is refused and counted.
//     A wire text of 120 characters may be longer in UTF-16 than Lists' cap: refused, never a crash.
//   - Over 500 items, 400 live ones the peer added or 1.5 MiB of the 2 MiB file, the change is refused and counted; nothing else moves
//     but a mark on the list ("peerFull"), and the pass's notice. The other Robot has already marked the change sent, and no ack or
//     request can tell it otherwise (:link acks before the lists can be opened): the phones differ until that change is made again.

/** What a batch did: [file] (the same one when [changed] is false) and counts, never words. */
class ListShareApplied(val file: ListsFile, val changed: Boolean, val applied: Int, val stale: Int, val refused: Int, val taken: Int = 0, val full: Int = 0) {
    override fun toString(): String = "ListShareApplied($changed, $applied, $stale, $refused)" // l10n: not text (counts for logs and tests)
}

class ListShareSend(val pairing: String, val list: ListsId, val ops: List<ListOp>)

/** One pass of [ListShare.plan]. */
class ListSharePlan(val ended: List<Pair<ListsId, String>>, val leave: List<ListShareRec>, val lost: List<ListShareRec>, val send: List<ListShareSend>, val noRoom: List<ListShareRec> = emptyList())

object ListShare {
    /** What a removal unknown here keeps as its words: a tombstone is never shown, and its counter is what matters. */
    private const val GONE_TEXT = "-" // l10n: not text (a tombstone's placeholder, never shown)

    /**
     * Items a joined peer added with its changes and that are still on one list (its offer, which you said yes to, isn't limited;
     * neither are your items it only changed, or what it removed, since your adds drop tombstones first): the rest of the 500 stay yours.
     */
    const val PEER_ITEMS = 400

    /** How big a peer's changes may grow the lists file: the rest of the 2 MiB stays yours. */
    const val PEER_FILE = 1_572_864

    private fun refuseAll(file: ListsFile, n: Int) = ListShareApplied(file, false, 0, 0, n)

    /** Your yes made [o] a list here, or one more part of it. Refused whole for a list id that is someone else's or yours. */
    fun join(file: ListsFile, pairing: String, requestId: String, o: LsOffer, now: Long): ListShareApplied {
        val id = ListsId.of(o.list) ?: return refuseAll(file, o.items.size)
        val have = file.list(id)
        val start: ListsFile = when {
            have != null -> {
                if (ListsShared.origin(have) != pairing) return refuseAll(file, o.items.size)
                if (ListsShared.with(have) == listOf(pairing)) file else file.with(have.copy(extra = have.extra + (ListsShared.SHARED to listOf(pairing))))
            }
            file.lists.size >= ListsLimits.MAX_LISTS -> return refuseAll(file, o.items.size)
            else -> {
                val name = name(o.name) ?: return refuseAll(file, o.items.size)
                file.copy(lists = file.lists + ListsList(id, name = name, made = now, from = ListFrom.PEER,
                    extra = mapOf(ListsShared.SHARED to listOf(pairing), ListsShared.ORIGIN to pairing)))
            }
        }
        val r = changes(start, id, pairing, requestId, o.items.map { ListOp(it.item, it.rev, it) }, now)
        return if (!ListsCodec.fits(r.file)) refuseAll(file, o.items.size) else ListShareApplied(r.file, true, r.applied, r.stale, r.refused, full = r.full)
    }

    /** [o] from [pairing], carried by request [requestId]. Nothing changes unless the list is shared with that pairing now. */
    fun apply(file: ListsFile, pairing: String, requestId: String, o: LsOp, now: Long): ListShareApplied {
        val l = ListsId.of(o.list)?.let(file::list) ?: return refuseAll(file, o.ops.size)
        if (pairing !in ListsShared.with(l)) return refuseAll(file, o.ops.size)
        val r = changes(file, l.id, pairing, requestId, o.ops, now, limited = true)
        if (!r.changed || !roomless(file, r.file)) return r
        val full = r.applied + r.full
        val noted = if (full > 0) noteFull(file, l.id) else file
        return ListShareApplied(noted, noted !== file, 0, 0, o.ops.size, full = full)
    }

    private fun changes(start: ListsFile, list: ListsId, pairing: String, requestId: String, ops: List<ListOp>, now: Long, limited: Boolean = false): ListShareApplied {
        var file = start
        var applied = 0; var stale = 0; var refused = 0; var bumped = false; var taken = 0; var full = 0
        for (op in ops) {
            val l = checkNotNull(file.list(list))
            val id = ListsId.of(op.item)
            val elsewhere = id?.let(file::holding)?.let { it.id != list } ?: false
            if (id == null || elsewhere) { refused++; continue }
            val cur = l.item(id)
            val verdict = order(cur, op.rev, requestId)
            if (cur != null && cur.rev == op.rev && owed(cur)) {
                // A tie against a change of yours not sent yet: its request id doesn't exist, so the ids can't settle it the same
                // way on both phones. Yours goes out one higher, which wins on both, and they agree.
                stale++; bumped = true
                file = file.with(l.copy(items = l.items.map { if (it.id == id) cur.copy(rev = cur.rev + 1) else it }))
                continue
            }
            if (verdict == Verdict.STALE) { stale++; continue }
            // What it added and removed may come back only within the cap, or it could add, remove, add more and bring all of it back.
            val grows = limited && op.state != null && (cur == null || (!cur.live && ListsShared.PEER_NEW in cur.extra))
            if (verdict == Verdict.JUMP) { refused++; continue }
            if ((cur == null && l.items.size >= ListsLimits.MAX_ITEMS) || (grows && l.items.count { it.live && ListsShared.PEER_NEW in it.extra } >= PEER_ITEMS)) {
                refused++
                if (op.state != null) full++ // A removal of what isn't here loses nothing: only words that didn't land count.
                continue
            }
            val next = item(cur, id, op, pairing, requestId, now)?.let { if (limited && cur == null) it.copy(extra = it.extra + (ListsShared.PEER_NEW to true)) else it }
            if (next == null) { refused++; continue }
            file = file.with(l.copy(items = if (cur == null) l.items + next else l.items.map { if (it.id == id) next else it }))
            applied++
            if (cur != null && notJustTheirs(cur) && (if (op.state == null) cur.live else next.text != cur.text && ListsShared.WAS in next.extra)) taken++
        }
        val noted = if (full > 0) noteFull(file, list) else file
        return ListShareApplied(noted, applied > 0 || bumped || noted !== file, applied, stale, refused, taken, full)
    }

    /** The mark for the list's page; [file] itself when it's there already, or when even the mark doesn't fit (the notification still says so). */
    private fun noteFull(file: ListsFile, list: ListsId): ListsFile {
        val l = file.list(list) ?: return file
        if (ListsShared.PEER_FULL in l.extra) return file
        val noted = file.with(l.copy(extra = l.extra + (ListsShared.PEER_FULL to true)))
        return if (ListsCodec.fits(noted)) noted else file
    }

    /** You've read that a peer's changes didn't fit on [list]. */
    fun fullSeen(file: ListsFile, list: ListsId): ListsFile {
        val l = file.list(list) ?: return file
        return file.with(l.copy(extra = l.extra - ListsShared.PEER_FULL))
    }

    /** A change that grows the file past [PEER_FILE] waits: the last of the 2 MiB stay yours. One that shrinks it or keeps its size may go. */
    private fun roomless(before: ListsFile, after: ListsFile): Boolean {
        val size = ListsCodec.size(after)
        return size > ListsLimits.MAX_FILE || (size > PEER_FILE && size > ListsCodec.size(before))
    }

    private enum class Verdict { WINS, STALE, JUMP }

    private fun order(cur: ListItem?, rev: Long, requestId: String): Verdict {
        if (cur == null) return if (rev > LinkOrder.MAX_JUMP) Verdict.JUMP else Verdict.WINS
        val req = cur.extra[ListsShared.REQ] as? String ?: ""
        if (!LinkOrder.wins(rev, requestId, cur.rev, req)) return Verdict.STALE
        return if (rev - cur.rev > LinkOrder.MAX_JUMP) Verdict.JUMP else Verdict.WINS
    }

    /** [cur] with [op]'s state, or a new item; null when its words break a rule your own adds keep. */
    private fun item(cur: ListItem?, id: ListsId, op: ListOp, pairing: String, requestId: String, now: Long): ListItem? {
        val s = op.state
        val text: String?; val qty: String?
        if (s == null) {
            text = cur?.text ?: GONE_TEXT; qty = cur?.qty
        } else {
            text = words(s.text, ListsLimits.MAX_TEXT) ?: return null
            qty = s.qty?.let { words(it, ListsLimits.MAX_QTY) ?: return null }
        }
        val what = when {
            s == null -> ItemOp.REMOVE
            cur == null -> ItemOp.ADD
            cur.gone != null -> ItemOp.RESTORE
            cur.done != s.done -> if (s.done) ItemOp.TICK else ItemOp.UNTICK
            cur.pos != s.pos && cur.text == text && cur.qty == qty -> ItemOp.MOVE
            else -> ItemOp.EDIT
        }
        val hist = (cur?.hist.orEmpty() + ListChange(what, now, ItemBy.PEER, ItemHow.LINK, mapOf(ListsShared.WHO to pairing))).takeLast(ListsLimits.MAX_HIST)
        var marks = cur?.extra.orEmpty() + mapOf(ListsShared.PEER to pairing, ListsShared.REQ to requestId, ListsShared.SENT to op.rev)
        if (cur != null && notJustTheirs(cur)) marks = marks + (ListsShared.NOT_JUST_PEER to true)
        // The words their change replaced stay, so you can put them back, until they go back to those words or you change the item yourself.
        val extra = when {
            cur == null -> marks
            // A removal keeps the words their last change replaced, for Put back. Older words aren't yours any more: you've changed it since.
            s == null -> if (cur.hist.lastOrNull()?.by == ItemBy.PEER) marks else marks - ListsShared.WAS
            else -> yourWords(cur, text)?.let { marks + (ListsShared.WAS to it) } ?: (marks - ListsShared.WAS)
        }
        return ListItem(id, text, qty, s?.done ?: cur?.done ?: false, s?.pos ?: cur?.pos ?: 0L, cur?.at ?: now, now, ItemBy.PEER, ItemHow.LINK, op.rev,
            if (s == null) cur?.gone ?: now else null, hist, extra)
    }

    /**
     * The words [Put back][putBack] would bring back once a peer has set [cur] to [text]; null when that would change nothing.
     * Words kept from before your own last change aren't yours any more, and an item you removed yourself isn't taken from you.
     */
    private fun yourWords(cur: ListItem, text: String): String? {
        val theirsLast = cur.hist.lastOrNull()?.by == ItemBy.PEER
        val kept = (cur.extra[ListsShared.WAS] as? String)?.takeIf { theirsLast }
        val replaced = cur.text.takeIf { cur.live || (theirsLast && it != GONE_TEXT) }
        return (kept ?: replaced)?.takeIf { it != text }
    }

    /**
     * Someone other than a Robot on the link has touched [i]: a peer changing only its own additions isn't worth a word to you.
     * The mark lasts: [ListsLimits.MAX_HIST] of its changes would push you out of the history.
     */
    private fun notJustTheirs(i: ListItem) = ListsShared.NOT_JUST_PEER in i.extra || i.hist.any { it.by != ItemBy.PEER }

    /** What a joined peer last removed or rewrote on [l], for its page: each can be put back ([putBack]) until you or they change it. */
    fun takenByPeer(l: ListsList): List<ListItem> = l.items.filter { i ->
        val last = i.hist.lastOrNull()
        last != null && last.by == ItemBy.PEER && notJustTheirs(i) &&
            // The last change may be a tick or a restore: on a live item, WAS is what says your words are hidden.
            ((last.op == ItemOp.REMOVE && !i.live && i.text != GONE_TEXT) || (i.live && ListsShared.WAS in i.extra))
    }

    /**
     * One of [takenByPeer] as it was, as your change: it reaches the other Robot like any other. An item they rewrote and then removed
     * comes back with your words, in one step: when those can't be used, nothing changes and the row stays.
     */
    fun putBack(file: ListsFile, list: ListsId, item: ListsId, who: ListsWho): ListsEdit {
        val i = file.list(list)?.item(item) ?: return ListsEdit.No(ListsRefusal.NO_ITEM)
        val was = i.extra[ListsShared.WAS] as? String
        if (!i.live) {
            val restored = ListsEdits.restore(file, list, listOf(item), who)
            if (was == null || restored !is ListsEdit.Done) return restored
            return when (val e = ListsEdits.edit(restored.file, list, item, was, who)) {
                is ListsEdit.Done -> e.copy(changed = true)
                is ListsEdit.No -> e
            }
        }
        if (was == null) return ListsEdit.No(ListsRefusal.NO_ITEM)
        return ListsEdits.edit(file, list, item, was, who)
    }

    /** Another person's words as Lists' rules take them: cleaned, one line, within [max], not a secret; else null. */
    private fun words(s: String, max: Int): String? = ItemText.clean(s).takeIf { ListsText.line(it, max) && !SecretDetector.isSecret(it) }

    /** A list's name from a peer: cleaned and cut to Lists' cap, without splitting a character. */
    private fun name(s: String): String? {
        val c = ItemText.clean(s)
        val cut = if (c.length <= ListsLimits.MAX_NAME) c else c.substring(0, c.offsetByCodePoints(0, c.codePointCount(0, ListsLimits.MAX_NAME))).trim()
        return cut.takeIf { ListsText.line(it, ListsLimits.MAX_NAME) && !SecretDetector.isSecret(it) }
    }

    // What your side sends ---------------------------------------------------------------------------------------------------

    /**
     * [l] as `ls.offer` parts: its live items, 40 at a time, under [name] (a role list's name in your language). Empty when the name
     * is nothing the link can carry.
     */
    fun parts(l: ListsList, name: String): List<LsOffer> {
        val clean = LinkText.clean(name, RequestCaps.NAME) ?: return emptyList()
        val chunks = l.live.mapNotNull(::wire).chunked(RequestCaps.LIST_PART_ITEMS).ifEmpty { listOf(emptyList()) }
        return chunks.mapIndexed { i, c -> LsOffer(l.id.hex, clean, i + 1L, chunks.size.toLong(), c) }
    }

    /**
     * [i] as the link's text rule takes it: Lists accepts words it refuses (decomposed accents, a flag's tag characters, a leading
     * U+FEFF), and one refused batch would hold back every later change. Null when no words are left: that item stays here only.
     */
    private fun wire(i: ListItem): WireItem? {
        val text = LinkText.clean(i.text, RequestCaps.LIST_ITEM) ?: return null
        return WireItem(i.id.hex, i.rev.coerceAtLeast(1), text, i.done, i.pos.coerceIn(-RequestCaps.LIST_POS, RequestCaps.LIST_POS), i.qty?.let { LinkText.clean(it, RequestCaps.LIST_QTY) })
    }

    /** Whether this phone owes the other Robot a change to [i]. */
    fun owed(i: ListItem): Boolean = (i.extra[ListsShared.SENT] as? Long) != i.rev && i.rev >= 1

    /** At most [max] (and at most one batch's 40) of the changes [l] owes, the oldest first. */
    fun owedOps(l: ListsList, max: Int): List<ListOp> =
        l.items.filter(::owed).sortedBy { it.upd }.mapNotNull { i -> if (i.gone != null) ListOp(i.id.hex, i.rev) else wire(i)?.let { ListOp(i.id.hex, i.rev, it) } }.take(max.coerceIn(0, RequestCaps.LIST_OPS))

    /** [ops] went out in request [requestId]: those items, unless changed meanwhile, are what the other Robot has. */
    fun sent(file: ListsFile, list: ListsId, ops: List<ListOp>, requestId: String): ListsFile {
        val l = file.list(list) ?: return file
        val revs = ops.associate { it.item to it.rev }
        return file.with(l.copy(items = l.items.map { i ->
            if (revs[i.id.hex] == i.rev) i.copy(extra = i.extra + mapOf(ListsShared.SENT to i.rev, ListsShared.REQ to requestId)) else i
        }))
    }

    /**
     * You offered [list] to [pairing]: its live items travel in the offer, so they aren't owed. A removed item isn't in the offer: one
     * that was sent before stays as it is, so a removal made since the last share still goes once they say yes.
     */
    fun shared(file: ListsFile, list: ListsId, pairing: String): ListsFile {
        val l = file.list(list) ?: return file
        return file.with(l.copy(extra = l.extra + (ListsShared.SHARED to listOf(pairing)),
            items = l.items.map { if (it.gone != null && ListsShared.SENT in it.extra) it else it.copy(extra = it.extra + (ListsShared.SENT to it.rev)) }))
    }

    /** The other Robot lost track of what it has (its record said so): everything's owed again. */
    fun forgetSent(file: ListsFile, list: ListsId): ListsFile {
        val l = file.list(list) ?: return file
        return file.with(l.copy(items = l.items.map { it.copy(extra = it.extra - ListsShared.SENT - ListsShared.REQ) }))
    }

    /**
     * What :voice does after the pull, with the desk's records taken [before] the pull and [after] reading [file]: a list with no
     * record has ended (a record not yet made can't be missed, the offer's parts go before the list is marked); a record whose list
     * isn't shared with it goes (the pull held everything accepted before [before]; your offer of the last [OFFER_GRACE_S] may not be
     * marked yet); the rest sends.
     */
    fun plan(file: ListsFile, before: List<ListShareView>, after: List<ListShareView>, nowS: Long): ListSharePlan {
        val ended = file.lists.flatMap { l ->
            ListsShared.with(l).filter { p -> after.none { it.rec.pairing == p && it.rec.list == l.id.hex } }.map { l.id to it }
        }
        val leave = before.map { it.rec }.filter { r ->
            val open = r.state == ListShareState.JOINED || (r.state == ListShareState.OFFERED && nowS - r.at > OFFER_GRACE_S)
            open && ListsId.of(r.list)?.let(file::list)?.let { r.pairing in ListsShared.with(it) } != true
        }
        val send = after.filter { it.rec.state == ListShareState.JOINED && !it.rec.lost }.mapNotNull { v ->
            val l = ListsId.of(v.rec.list)?.let(file::list)?.takeIf { v.rec.pairing in ListsShared.with(it) } ?: return@mapNotNull null
            owedOps(l, v.room).takeIf { it.isNotEmpty() }?.let { ListShareSend(v.rec.pairing, l.id, it) }
        }
        // Your yes to a list that never landed, with no room for it: the share ends, and you're told why.
        val noRoom = if (file.lists.size < ListsLimits.MAX_LISTS) emptyList() else leave.filter { r -> r.state == ListShareState.JOINED && ListsId.of(r.list)?.let(file::list) == null }
        return ListSharePlan(ended, leave, after.map { it.rec }.filter { it.lost }, send, noRoom)
    }

    const val OFFER_GRACE_S = 120L

    /** No longer shared: the list stays, as your copy, and nothing more goes either way. */
    fun left(file: ListsFile, list: ListsId): ListsFile {
        val l = file.list(list) ?: return file
        return file.with(l.copy(extra = l.extra - ListsShared.SHARED))
    }
}
