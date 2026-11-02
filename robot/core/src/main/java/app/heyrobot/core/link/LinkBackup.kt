// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.PeopleStore
import app.heyrobot.core.link.requests.LinkCaps
import org.json.JSONArray
import org.json.JSONObject

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.7, D14; job L12): what a backup carries of :link's sealed state
// ("links.json", the LINKS category), written by :link and read back by :link. Your identity, prekeys, sessions, your onion's
// key, either side's Tor client authorization, connection tokens, replay windows and the outbox are never here: this file has
// no field for them, and a pairing with a key it doesn't know is refused whole.
//
//   {"v":1,"pairings":[{"id","mode","created","name","caps","identity","push":{"endpoint","p256dh","auth"},"person","preset",
//    "levels","movedOut"}],"blocked":[{"identity","label","at"}],"desk":[{"id","pairing","type","state","at","done"}],
//    "plans":[hex],"transfers":[hex]}
//
// A pairing carries only what a moved hello needs and who it is: their public identity key, their push address (the
// RFC 8291 values the other Robot gave out to be written to), and movedOut, the one-use-per-identity token they gave for a
// move. The token can pause a pairing until its person answers, and nothing more (§2.7). Their onion is left out: reaching it
// needs a client authorization key, so a pairing with no push address must pair again (LinkNode.sendMoved answers false).
// Local addresses are left out too: they go stale and come again in net.hint.
//
// History: a desk line is what became of a request, without its contents or its Undo; a plan is without its calendar event
// (another phone's id), and one waiting for your answer isn't here; a transfer is a finished one, without its key. No path:
// nothing under incoming/ or transfers/ is ever named. :link applies RestoreSafety.linkLevels on the way in.
//
// Top-level keys this version doesn't know are ignored (a later job's history); anything wrong inside refuses the file.

class LinkPairingBackup(
    val id: String,
    val mode: LinkMode,
    val created: Long,
    val name: String,
    caps: ByteArray,
    identity: ByteArray,
    val push: PushRoute?,
    val person: String?,
    val preset: LinkPreset?,
    val levels: Map<LinkCapability, LinkLevel>,
    movedOut: ByteArray?,
) {
    private val c = caps.copyOf()
    private val k = identity.copyOf()
    private val t = movedOut?.copyOf()
    val caps: ByteArray get() = c.copyOf()
    val identity: ByteArray get() = k.copyOf()
    val movedOut: ByteArray? get() = t?.copyOf()

    init {
        require(LinkIds.isId(id) && created >= 0 && c.size == LinkCaps.BITMAP && k.size == LinkBackupCodec.IDENTITY)
        require(name.isEmpty() || LinkText.ok(name, PairHello.MAX_NAME))
        require(person == null || PeopleStore.validId(person))
        require(t == null || t.size == LinkIds.BYTES)
    }

    override fun toString(): String = "LinkPairingBackup(mode=$mode)" // l10n: not text (toString for logs and tests; nothing of the pairing)
}

class LinkBlockBackup(identity: ByteArray, val label: String, val at: Long) {
    private val k = identity.copyOf()
    val identity: ByteArray get() = k.copyOf()

    init {
        require(k.size == LinkBackupCodec.IDENTITY && at >= 0 && (label.isEmpty() || LinkText.ok(label, LinkIpcProtocol.MAX_LABEL)))
    }

    override fun toString(): String = "LinkBlockBackup" // l10n: not text (toString for logs and tests)
}

/** links.json as read: names and history, kept in RAM only for a restore ([toString] counts). */
class LinkBackupFile(
    val pairings: List<LinkPairingBackup> = emptyList(),
    val blocked: List<LinkBlockBackup> = emptyList(),
    val desk: List<DeskEntry> = emptyList(),
    val plans: List<LinkPlan> = emptyList(),
    val transfers: List<TransferView> = emptyList(),
    /** L11: that a folder is shared and with whom, none of what's in it. */
    val folders: List<FolderRec> = emptyList(),
    /** L11: group plans you proposed, without busy times. */
    val groups: List<LinkGroup> = emptyList(),
) {
    override fun toString(): String = "LinkBackupFile(${pairings.size} pairings, ${desk.size + plans.size + transfers.size} lines)" // l10n: not text (toString for logs and tests)
}

sealed class LinkBackupRead {
    /** L16: a file a later Robot wrote is kept for the update that can read it (only a downgrade gets here); only a damaged one goes. */
    val dropsFile: Boolean get() = this is Damaged

    class Ok(val file: LinkBackupFile) : LinkBackupRead()
    class TooNew(val v: Int) : LinkBackupRead()
    object Damaged : LinkBackupRead()
}

object LinkBackupCodec {
    const val FILE = "links.json" // l10n: not text (a file name)
    const val VERSION = 1
    /** Under Binder's limit: :link hands this over in one reply; [fit] drops the oldest history to stay under. */
    const val MAX_BYTES = 512 * 1024
    /** A libsignal public key, as PairHello carries it. */
    const val IDENTITY = 33
    /**
     * Waiting for :link, under the app's files folder: written by the main app at a restore's finish, read and deleted by
     * :link once it opens (Robot to Robot on, its keys made).
     */
    const val PENDING = "link_restored.json" // l10n: not text (a file name)
    /** L16: it's plain until :link reads it, so a restore you never used doesn't stay on the phone for good. */
    const val PENDING_KEEP_MS = 30L * 24 * 3600 * 1000
    fun pendingTooOld(modifiedMs: Long, nowMs: Long): Boolean = nowMs - modifiedMs > PENDING_KEEP_MS

    /**
     * R8: a restore's links.json over pairings an earlier restore left waiting ([waiting], the file as it is): both stay, the
     * newer restore's copy of anything in both. A waiting file that can't be read has nothing to keep.
     */
    fun pendingOver(waiting: String?, next: String): String {
        val old = (decode(waiting) as? LinkBackupRead.Ok)?.file ?: return next
        val new = (decode(next) as? LinkBackupRead.Ok)?.file ?: return next
        return encode(fit(LinkBackupFile(
            (new.pairings + old.pairings).distinctBy { it.id },
            (new.blocked + old.blocked).distinctBy { it.identity.contentHashCode() },
            (new.desk + old.desk).distinctBy { it.id },
            (new.plans + old.plans).distinctBy { it.id },
            (new.transfers + old.transfers).distinctBy { it.id },
            (new.folders + old.folders).distinctBy { it.fold to it.pairing },
            (new.groups + old.groups).distinctBy { it.id },
        )))
    }

    private val PAIRING_KEYS = setOf("id", "mode", "created", "name", "caps", "identity", "push", "person", "preset", "levels", "movedOut") // l10n: not text (JSON keys)
    private val BLOCK_KEYS = setOf("identity", "label", "at") // l10n: not text (JSON keys)
    private val DESK_KEYS = setOf("id", "pairing", "type", "state", "at", "done") // l10n: not text (JSON keys)
    private val PUSH_KEYS = setOf("endpoint", "p256dh", "auth") // l10n: not text (JSON keys)

    fun encode(f: LinkBackupFile): String {
        val pairings = JSONArray()
        for (p in f.pairings) {
            val o = JSONObject().put("id", p.id).put("mode", p.mode.code).put("created", p.created).put("name", p.name) // l10n: not text (JSON keys)
                .put("caps", LinkBytes.hex(p.caps)).put("identity", LinkBytes.hex(p.identity)).put("levels", LinkBytes.hex(LinkLevels.encode(p.levels))) // l10n: not text (JSON keys)
            p.push?.let { r -> o.put("push", JSONObject().put("endpoint", r.endpoint).put("p256dh", LinkBytes.hex(r.p256dh)).put("auth", LinkBytes.hex(r.auth))) } // l10n: not text (JSON keys)
            p.person?.let { o.put("person", it) } // l10n: not text (a JSON key)
            p.preset?.let { o.put("preset", LinkLevels.presetCode(it)) } // l10n: not text (a JSON key)
            p.movedOut?.let { o.put("movedOut", LinkBytes.hex(it)) } // l10n: not text (a JSON key)
            pairings.put(o)
        }
        val blocked = JSONArray()
        for (b in f.blocked) blocked.put(JSONObject().put("identity", LinkBytes.hex(b.identity)).put("label", b.label).put("at", b.at)) // l10n: not text (JSON keys)
        val desk = JSONArray()
        for (e in f.desk) desk.put(JSONObject().put("id", e.id).put("pairing", e.pairing).put("type", e.type).put("state", e.state.code).put("at", e.at).put("done", e.done)) // l10n: not text (JSON keys)
        val plans = JSONArray()
        for (p in f.plans) plans.put(LinkBytes.hex(LinkPlan.encode(p)))
        val transfers = JSONArray()
        for (t in f.transfers) transfers.put(LinkBytes.hex(TransferView.encodeAll(listOf(t))))
        val folders = JSONArray()
        for (x in f.folders) folders.put(LinkBytes.hex(FolderRec.encode(x)))
        val groups = JSONArray()
        for (g in f.groups) groups.put(LinkBytes.hex(LinkGroup.encode(g)))
        return JSONObject().put("v", VERSION).put("pairings", pairings).put("blocked", blocked).put("desk", desk).put("plans", plans).put("transfers", transfers).put("folders", folders).put("groups", groups).toString() // l10n: not text (JSON keys)
    }

    fun decode(text: String?): LinkBackupRead = try {
        val o = JSONObject(text ?: throw Bad())
        val v = o.opt("v") as? Int ?: throw Bad() // l10n: not text (a JSON key)
        if (v > VERSION) LinkBackupRead.TooNew(v)
        else if (v < 1) LinkBackupRead.Damaged
        else LinkBackupRead.Ok(read(o))
    } catch (_: Exception) {
        LinkBackupRead.Damaged
    }

    /**
     * [f] as it fits [MAX_BYTES]: the oldest of the history dropped until it does. Pairings and blocks are never dropped; at
     * [LinkLimits.PAIRINGS] of them they fit by far.
     */
    fun fit(f: LinkBackupFile): LinkBackupFile {
        var now = f
        while (encode(now).toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            if (now.desk.isEmpty() && now.plans.isEmpty() && now.transfers.isEmpty()) return now
            now = LinkBackupFile(now.pairings, now.blocked,
                now.desk.sortedBy { it.at }.let { it.drop(1 + it.size / 4) },
                now.plans.sortedBy { it.at }.let { it.drop(1 + it.size / 4) },
                now.transfers.sortedBy { it.at }.let { it.drop(1 + it.size / 4) },
                now.folders, now.groups)
        }
        return now
    }

    /** A desk line as a backup carries it: no contents, no Undo, nothing left to show as new. */
    fun line(e: DeskEntry): DeskEntry = DeskEntry(e.id, e.pairing, e.way, e.type, e.state, e.at, e.done, undo = null, seen = true, plain = null)

    /** A plan as a backup carries it, or null while it waits for your answer: no calendar event (another phone's id). */
    fun plan(p: LinkPlan): LinkPlan? {
        if (p.state == PlanState.PROPOSED && !p.mine) return null
        return LinkPlan.decode(LinkPlan.encode(p))?.also { it.cal = 0 }
    }

    /** L11: a folder as a backup carries it, or null until it's joined. */
    fun folder(f: FolderRec): FolderRec? = FolderRules.bare(f)

    /** L11: a group as a backup carries it, or null until it's proposed or after it's cancelled: no busy times, no calendar event. */
    fun group(g: LinkGroup): LinkGroup? = g.takeIf { it.state == GroupState.PROPOSED }?.light()

    /** A transfer as a backup carries it, or null while it is under way (or its file waits in incoming/). */
    fun transfer(t: TransferView): TransferView? = t.takeIf { it.state.final }

    private class Bad : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun read(o: JSONObject): LinkBackupFile {
        val pairings = list(o, "pairings") { j -> pairing(j as JSONObject) } // l10n: not text (a JSON key)
        val blocked = list(o, "blocked") { j -> // l10n: not text (a JSON key)
            j as JSONObject
            only(j, BLOCK_KEYS)
            LinkBlockBackup(bytes(j, "identity", IDENTITY), j.get("label") as String, number(j, "at")) // l10n: not text (JSON keys)
        }
        val desk = list(o, "desk") { j -> // l10n: not text (a JSON key)
            j as JSONObject
            only(j, DESK_KEYS)
            val state = DeskState.of(j.get("state") as Int) ?: throw Bad() // l10n: not text (a JSON key)
            if (!LinkIds.isId(j.get("id") as String) || !LinkIds.isId(j.get("pairing") as String)) throw Bad() // l10n: not text (JSON keys)
            DeskEntry(j.get("id") as String, j.get("pairing") as String, state.way, j.get("type") as Int, state, number(j, "at"), number(j, "done"), seen = true) // l10n: not text (JSON keys)
        }
        val plans = list(o, "plans") { j -> plan(LinkPlan.decode(LinkBytes.unhex(j as String, (j.length) / 2) ?: throw Bad()) ?: throw Bad()) ?: throw Bad() } // l10n: not text (a JSON key)
        val transfers = list(o, "transfers") { j -> // l10n: not text (a JSON key)
            transfer(TransferView.decodeAll(LinkBytes.unhex(j as String, (j.length) / 2) ?: throw Bad()).singleOrNull() ?: throw Bad()) ?: throw Bad()
        }
        if (pairings.size > LinkLimits.PAIRINGS || pairings.distinctBy { it.id }.size != pairings.size) throw Bad()
        if (desk.distinctBy { it.id }.size != desk.size || transfers.distinctBy { it.id }.size != transfers.size) throw Bad()
        val folders = if (!o.has("folders")) emptyList() else list(o, "folders") { j -> // l10n: not text (a JSON key)
            val x = FolderRec.decode(LinkBytes.unhex(j as String, j.length / 2) ?: throw Bad()) ?: throw Bad()
            if (x.state != FolderState.JOINED || x.items.isNotEmpty() || !FolderRules.nameOk(x.name)) throw Bad()
            x
        }
        val groups = if (!o.has("groups")) emptyList() else list(o, "groups") { j -> // l10n: not text (a JSON key)
            val g = LinkGroup.decode(LinkBytes.unhex(j as String, j.length / 2) ?: throw Bad()) ?: throw Bad()
            if (g.state != GroupState.PROPOSED || g.cal != 0L || g.members.any { it.busy != null }) throw Bad()
            g
        }
        return LinkBackupFile(pairings, blocked, desk, plans, transfers, folders, groups)
    }

    private fun pairing(j: JSONObject): LinkPairingBackup {
        only(j, PAIRING_KEYS)
        val push = j.opt("push")?.let { r -> // l10n: not text (a JSON key)
            r as JSONObject
            only(r, PUSH_KEYS)
            val k = LinkBytes.unhex(r.get("p256dh") as String, (r.get("p256dh") as String).length / 2) ?: throw Bad() // l10n: not text (JSON keys)
            val a = LinkBytes.unhex(r.get("auth") as String, (r.get("auth") as String).length / 2) ?: throw Bad() // l10n: not text (JSON keys)
            PushRoute(r.get("endpoint") as String, k, a) // l10n: not text (a JSON key)
        }
        val levels = LinkLevels.decode(LinkBytes.unhex(j.get("levels") as String, (j.get("levels") as String).length / 2) ?: throw Bad()) ?: throw Bad() // l10n: not text (JSON keys)
        return LinkPairingBackup(j.get("id") as String, LinkMode.of(j.get("mode") as Int) ?: throw Bad(), number(j, "created"), j.get("name") as String, // l10n: not text (JSON keys)
            bytes(j, "caps", LinkCaps.BITMAP), bytes(j, "identity", IDENTITY), push, j.opt("person") as String?, // l10n: not text (JSON keys)
            j.opt("preset")?.let { LinkLevels.preset(it as Int) ?: throw Bad() }, levels, // l10n: not text (a JSON key)
            if (j.has("movedOut")) bytes(j, "movedOut", LinkIds.BYTES) else null) // l10n: not text (a JSON key)
    }

    private fun only(j: JSONObject, keys: Set<String>) {
        for (k in j.keys()) if (k !in keys) throw Bad()
    }

    private fun bytes(j: JSONObject, key: String, n: Int): ByteArray = LinkBytes.unhex(j.get(key) as String, n) ?: throw Bad()

    private fun number(j: JSONObject, key: String): Long = (j.get(key) as Number).toLong().also { if (it < 0) throw Bad() }

    private fun <T> list(o: JSONObject, key: String, one: (Any) -> T): List<T> {
        val a = o.get(key) as JSONArray
        return List(a.length()) { one(a.get(it)) }
    }
}
