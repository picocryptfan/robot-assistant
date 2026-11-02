package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.link.DeskRequest
import app.heyrobot.core.link.ListShare
import app.heyrobot.core.link.ListShareView
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad

/**
 * 1.5 shared lists, the side that holds the lists: :link has no lists key, so it gathers offers and changes in its sealed inbox and
 * this takes them in, unlocked only, then sends what the lists owe. Everything from the other Robot went through the checks in
 * core ListShare; this only carries it.
 */
object ListShareSync {
    private const val MAX_ROUNDS = 32

    /** A pass's news for the notifications, by pairing: shares [ended], yes answers with [noRoom], peers who [taken] something of yours, and peers whose changes didn't fit ([full]). */
    class Pass(val ended: List<String> = emptyList(), val noRoom: List<String> = emptyList(), val taken: List<String> = emptyList(), val full: List<String> = emptyList())

    /** One pass, from LinkNotices (never while locked). */
    fun run(ctx: Context, client: LinkClient, vault: ListsVault = ListsFiles.vault(ctx), lang: String): Pass {
        val before = client.listShares() ?: return Pass()
        val taken = LinkedHashSet<String>()
        val full = LinkedHashSet<String>()
        if (before.isEmpty()) return reconcile(client, vault, before, lang, taken, full)
        return if (pull(client, vault, taken, full)) reconcile(client, vault, before, lang, taken, full) else Pass(taken = taken.toList(), full = full.toList())
    }

    /** Everything held, in arrival order; false when something stayed for later. */
    private fun pull(client: LinkClient, vault: ListsVault, taken: MutableSet<String>, full: MutableSet<String>): Boolean {
        repeat(MAX_ROUNDS) {
            val items = client.listPending() ?: return false
            if (items.isEmpty()) return true
            val done = ArrayList<String>(items.size)
            for (i in items) {
                val body = i.request.read()?.body
                val list = ListsId.of((body as? LsOffer)?.list ?: (body as? LsOp)?.list)
                if (list == null) { done += i.id; continue } // Checked at the desk: nothing else is ever held.
                var took = 0
                var over = 0
                val w = vault.update(ItemHow.LINK) { f, who ->
                    val r = if (body is LsOffer) ListShare.join(f, i.pairing, i.id, body, who.at) else ListShare.apply(f, i.pairing, i.id, body as LsOp, who.at)
                    took = r.taken
                    over = r.full
                    ListsEdit.Done(r.file, list, changed = r.changed)
                }
                if (w.done == null) break
                if (took > 0) taken += i.pairing
                if (over > 0) full += i.pairing
                done += i.id
            }
            if (done.isNotEmpty()) client.listApplied(done)
            if (done.size < items.size) return false
        }
        return false
    }

    private fun load(vault: ListsVault): ListsFile? = when (val l = vault.load(firstUse = false)) {
        is ListsLoad.Loaded -> l.value
        ListsLoad.Empty -> ListsFile()
        else -> null
    }

    private fun reconcile(client: LinkClient, vault: ListsVault, before: List<ListShareView>, lang: String, taken: Set<String>, full: Set<String>): Pass {
        val none = Pass(taken = taken.toList(), full = full.toList())
        val file = load(vault) ?: return none
        if (before.isEmpty() && file.lists.none { app.heyrobot.core.lists.ListsShared.with(it).isNotEmpty() }) return none
        val after = client.listShares() ?: return none
        val plan = ListShare.plan(file, before, after, System.currentTimeMillis() / 1000)
        for ((list, _) in plan.ended) edit(vault, list) { ListShare.left(it, list) }
        for (r in plan.leave) client.listLeave(r.pairing, r.list)
        for (r in plan.lost) {
            val list = ListsId.of(r.list) ?: continue
            if (edit(vault, list) { ListShare.forgetSent(it, list) }) client.listLostSeen(r.pairing, r.list)
        }
        for (s in plan.send) {
            val id = client.listSend(s.pairing, DeskRequest.of(LinkRequest.of(LsOp(s.list.hex, s.ops), System.currentTimeMillis() / 1000, lang))) ?: continue
            edit(vault, s.list) { ListShare.sent(it, s.list, s.ops, id) }
        }
        return Pass(plan.ended.map { it.second }, plan.noRoom.map { it.pairing }, taken.toList(), full.toList())
    }

    private fun edit(vault: ListsVault, list: ListsId, change: (ListsFile) -> ListsFile): Boolean =
        vault.update(ItemHow.LINK) { f, _ -> ListsEdit.Done(change(f), list) }.done != null

    /**
     * You offered [l] to [pairing], from the list's page. Each part goes out first, then the list is marked, so a list that says
     * it's shared always has its record. False when a part wouldn't go; whatever went is taken back.
     */
    fun offer(ctx: Context, l: ListsList, name: String, pairing: String, lang: String): Boolean {
        if (ListShare.parts(l, name).isEmpty()) return false
        val client = LinkClient(ctx)
        try {
            for (part in ListShare.parts(l, name)) {
                if (client.listSend(pairing, DeskRequest.of(LinkRequest.of(part, System.currentTimeMillis() / 1000, lang))) == null) {
                    client.listLeave(pairing, l.id.hex)
                    return false
                }
            }
            RLog.i("list_share_offer", "parts" to ListShare.parts(l, name).size)
        } finally {
            client.close()
        }
        return edit(ListsFiles.vault(ctx), l.id) { ListShare.shared(it, l.id, pairing) }
    }

    /** You stopped sharing [list]: your copy stays, and the other Robot is told. */
    fun stop(ctx: Context, list: ListsId, pairing: String): Boolean {
        val done = edit(ListsFiles.vault(ctx), list) { ListShare.left(it, list) }
        val client = LinkClient(ctx)
        try { client.listLeave(pairing, list.hex) } finally { client.close() }
        return done
    }
}
