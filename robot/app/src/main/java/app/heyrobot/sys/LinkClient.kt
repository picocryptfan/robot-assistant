// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.CodeState
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNotices
import app.heyrobot.core.link.DeskRequest
import app.heyrobot.core.link.ListShareItem
import app.heyrobot.core.link.ListShareView
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.GroupsView
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.SafetyNumber
import app.heyrobot.core.link.UseCodeResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// MAIN PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §5.1, §6; job L5): how Settings › Robot to Robot, the pairing
// screen and People's pages reach :link. LinkService is bound by its name (core LinkIpcProtocol.SERVICE_CLASS): main never
// names the link module, :link's classes or libsignal (ProcessIsolationTest). Every call is a hand-written frame of numbers,
// strings and bytes; every reply is read strictly, and anything that isn't exactly the call's answer is no answer (null or
// false). Calls block: never on the main thread. A screen keeps one client while it shows and closes it when it goes, so
// :link sleeps again (no listening, no Tor) once nothing uses it.
//
// 1.5 L6: :voice uses it too (the panel's cards, the notifications, the brief), and main's Requests screen: the desk's calls.

/** STATUS's answer: the vault's state (LinkIpcProtocol.VAULT_…), Robot to Robot on, network allowed, and the node's counts. */
class LinkStatus(val vault: Int, val on: Boolean, val network: Boolean, val pairings: Int, val sent: Int, val outbox: Int, val codes: Int) {
    override fun toString(): String = "LinkStatus(vault=$vault, on=$on)" // l10n: not text (toString for logs and tests)
}

/** MAKE_CODE's answer: its result, and when a code was made its id and its text. */
class MadeCodeReply(val result: MakeCodeResult, val id: String?, val text: String?) {
    override fun toString(): String = "MadeCodeReply($result)" // l10n: not text (toString for logs and tests)
}

/** USE_CODE's answer: its result, and the pairing's id when the result has one. */
class UsedCodeReply(val result: UseCodeResult, val pairing: String?) {
    override fun toString(): String = "UsedCodeReply($result)" // l10n: not text (toString for logs and tests)
}

class LinkClient(private val ctx: Context) {
    private val connected = CountDownLatch(1)
    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false
    /** Closed for good: a late call on it answers nothing rather than binding again with nobody left to close it. */
    @Volatile private var closed = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!closed) remote = binder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    /** Binds LinkService (its process starts if it must); true once connected within [timeoutMs]. Blocks. */
    fun connect(timeoutMs: Long = CONNECT_MS): Boolean {
        if (closed) return false
        if (remote != null) return true
        synchronized(conn) {
            if (closed) return false
            if (!bound) {
                val i = Intent().setClassName(ctx.packageName, LinkIpcProtocol.SERVICE_CLASS)
                bound = try { ctx.bindService(i, conn, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
                if (!bound) return false
            }
        }
        return try { connected.await(timeoutMs, TimeUnit.MILLISECONDS) && remote != null } catch (_: InterruptedException) { false }
    }

    fun close() = synchronized(conn) {
        closed = true
        if (bound) try { ctx.unbindService(conn) } catch (_: Exception) {}
        bound = false
        remote = null
        connected.countDown()
    }

    fun status(): LinkStatus? = call(LinkIpcProtocol.STATUS, {}) { r ->
        val vault = r.readInt(); val on = r.readInt() == 1; val net = r.readInt() == 1
        val pairings = r.readInt(); val sent = r.readInt(); val outbox = r.readInt(); val codes = r.readInt()
        repeat(4) { r.readInt() }
        LinkStatus(vault, on, net, pairings, sent, outbox, codes)
    }

    /** Turning Robot to Robot on: the keys and your identity (link_settings.json must already say on). */
    fun setUp(): Boolean = flag(LinkIpcProtocol.SET_UP) {}

    fun startOver(): Boolean = flag(LinkIpcProtocol.START_OVER) {}

    fun makeCode(mode: LinkMode, inPerson: Boolean): MadeCodeReply? = call(LinkIpcProtocol.MAKE_CODE, { writeInt(mode.code); writeInt(if (inPerson) 1 else 0) }) { r ->
        val result = MakeCodeResult.of(r.readInt()) ?: return@call null
        val id = id(r)
        val text = r.readString() ?: return@call null
        if ((result == MakeCodeResult.OK) != (id != null && text.isNotEmpty())) return@call null
        MadeCodeReply(result, id, text.ifEmpty { null })
    }

    fun useCode(text: String, form: PairCodeForm, mode: LinkMode): UsedCodeReply? =
        call(LinkIpcProtocol.USE_CODE, { writeString(text.take(LinkIpcProtocol.MAX_CODE_TEXT)); writeInt(if (form == PairCodeForm.SCANNED) 0 else 1); writeInt(mode.code) }) { r ->
            val result = UseCodeResult.of(r.readInt()) ?: return@call null
            val pairing = id(r)
            if (result.hasPairing != (pairing != null)) return@call null
            UsedCodeReply(result, pairing)
        }

    fun cancelCodes(): Boolean = flag(LinkIpcProtocol.CANCEL_CODES) {}

    fun cancelCode(id: String): Boolean = flag(LinkIpcProtocol.CANCEL_CODE) { writeId(id) }

    fun codeState(id: String): Pair<CodeState, String?>? = call(LinkIpcProtocol.CODE_STATE, { writeId(id) }) { r ->
        val state = CodeState.of(r.readInt()) ?: return@call null
        val pairing = id(r)
        if ((state == CodeState.USED) != (pairing != null)) return@call null
        state to pairing
    }

    fun pairings(): List<PairingView>? = call(LinkIpcProtocol.PAIRINGS, {}) { r -> r.createByteArray()?.let(PairingView::decodeAll) }

    /** [pairing]'s safety number (60 digits), or null. */
    fun safety(pairing: String): String? = call(LinkIpcProtocol.SAFETY, { writeId(pairing) }) { r -> r.readString()?.takeIf(SafetyNumber::ok) }

    fun verify(pairing: String): Boolean = flag(LinkIpcProtocol.VERIFY) { writeId(pairing) }

    fun setPerson(pairing: String, person: String?): Boolean = flag(LinkIpcProtocol.SET_PERSON) { writeId(pairing); writeString(person.orEmpty()) }

    fun setPreset(pairing: String, preset: LinkPreset): Boolean = flag(LinkIpcProtocol.SET_PRESET) { writeId(pairing); writeInt(LinkLevels.presetCode(preset)) }

    fun setLevel(pairing: String, cap: LinkCapability, level: LinkLevel): Boolean =
        flag(LinkIpcProtocol.SET_LEVEL) { writeId(pairing); writeInt(LinkLevels.code(cap)); writeInt(LinkLevels.levelCode(level)) }

    fun raise(pairing: String): Boolean = flag(LinkIpcProtocol.RAISE) { writeId(pairing) }

    fun lower(pairing: String): Boolean = flag(LinkIpcProtocol.LOWER) { writeId(pairing) }

    fun keepMode(pairing: String): Boolean = flag(LinkIpcProtocol.KEEP_MODE) { writeId(pairing) }

    fun unpair(pairing: String): Boolean = flag(LinkIpcProtocol.UNPAIR) { writeId(pairing) }

    fun block(pairing: String, label: String): Boolean = flag(LinkIpcProtocol.BLOCK) { writeId(pairing); writeString(label.take(LinkIpcProtocol.MAX_LABEL)) }

    fun blocked(): List<BlockedView>? = call(LinkIpcProtocol.BLOCKED, {}) { r -> r.createByteArray()?.let(BlockedView::decodeAll) }

    fun unblock(id: String): Boolean = flag(LinkIpcProtocol.UNBLOCK) { writeId(id) }

    /** L12: core LinkBackupCodec's file (no key in it), or null when it can't be had (off, locked, :link gone). */
    fun backupExport(): ByteArray? = call(LinkIpcProtocol.BACKUP_EXPORT, {}) { r -> r.createByteArray()?.takeIf { it.size <= app.heyrobot.core.link.LinkBackupCodec.MAX_BYTES } }

    /** L12: "Tell my Robots I moved", for one pairing. Waits on a push address and on Tor. */
    fun sendMoved(pairing: String): Boolean = flag(LinkIpcProtocol.SEND_MOVED) { writeId(pairing) }

    fun answerMoved(pairing: String, accept: Boolean): Boolean = flag(LinkIpcProtocol.ANSWER_MOVED) { writeId(pairing); writeInt(if (accept) 1 else 0) }

    /** Your tap in the Push app row: [pkg], one of [pushApps]' (empty: none). */
    fun choosePush(pkg: String): Boolean = flag(LinkIpcProtocol.CHOOSE_PUSH) { writeString(pkg) }

    /** The push apps on this phone and the one chosen. Null while off or locked. */
    fun pushApps(): app.heyrobot.core.link.PushApps? = call(LinkIpcProtocol.PUSH_APPS, {}) { r ->
        val chosen = r.readString().orEmpty().ifEmpty { null }
        val waiting = r.readInt()
        val n = r.readInt().takeIf { it in 0..app.heyrobot.core.link.PushApps.MAX_APPS } ?: return@call null
        app.heyrobot.core.link.PushApps(chosen, waiting, List(n) { app.heyrobot.core.link.PushApp(r.readString().orEmpty(), r.readString().orEmpty()) })
    }

    // L6, the desk (Requests, the panel's cards, the notifications) ---------------------------------------------------------

    /** The desk: cards, what Robot handled, what you sent, Robot's proposals. Null while locked, or when it doesn't read. */
    fun desk(): DeskView? = call(LinkIpcProtocol.DESK, {}) { r -> r.createByteArray()?.let(DeskView::decode) }

    /** Your Yes or Decline on card [id]. */
    fun answer(id: String, choice: DeskChoice): Boolean = flag(LinkIpcProtocol.DESK_ANSWER) { writeId(id); writeInt(choice.code) }

    /** L10: your typed [text] as the answer to question card [id]. */
    fun reply(id: String, text: String): Boolean =
        text.length <= LinkIpcProtocol.MAX_ANSWER && flag(LinkIpcProtocol.DESK_REPLY) { writeId(id); writeString(text) }

    /** L8: the question on card [id] answered with the file offered as [file] (your tap, in Robot Files). */
    fun answerWithFile(id: String, file: String): Boolean = flag(LinkIpcProtocol.DESK_FILE_ANSWER) { writeId(id); writeId(file) }

    /** L8: the sealed copy in outgoing/ offered as [v], under transfer key [key]. */
    fun sendFile(v: TransferView, key: ByteArray): Boolean = flag(LinkIpcProtocol.FILE_SEND) { writeByteArray(TransferView.encodeAll(listOf(v))); writeByteArray(key) }

    /** L8: every file sent and received. Null while locked, or when it doesn't read. */
    fun transfers(): List<TransferView>? = call(LinkIpcProtocol.TRANSFERS, {}) { r -> r.createByteArray()?.let(TransferView::decodeAll) }

    /** L8: Files took received transfer [id], or refused it. */
    fun filed(id: String, ok: Boolean): Boolean = flag(LinkIpcProtocol.TRANSFER_FILED) { writeId(id); writeInt(if (ok) 1 else 0) }

    /** Undo of entry [id], something Robot did on its own. */
    fun undo(id: String): Boolean = flag(LinkIpcProtocol.DESK_UNDO) { writeId(id) }

    /** What changed since the last call (kinds, pairings and counts), and how many cards wait. */
    fun notices(): DeskNotices? = call(LinkIpcProtocol.DESK_NOTICES, {}) { r -> r.createByteArray()?.let(DeskNotices::decode) }

    /** "Not now" on Robot's proposal to answer [pairing]'s [cap] on its own. */
    fun notNow(pairing: String, cap: LinkCapability): Boolean = flag(LinkIpcProtocol.DESK_NOT_NOW) { writeId(pairing); writeInt(LinkLevels.code(cap)) }

    // L7, sending and plans ------------------------------------------------------------------------------------------------

    /**
     * Your request [r] to [pairing]'s Robot, as [bytes] (core LinkRequests.encode of [r]): queued, listed under Sent. Null
     * when refused (locked, off); else 0 queued or the node's QueueProblem code.
     */
    fun send(pairing: String, r: LinkRequest, bytes: ByteArray): Int? = call(LinkIpcProtocol.SEND, {
        writeId(pairing); writeInt(r.type.code); writeId(r.id); writeLong(r.at); writeLong(r.ttl); writeByteArray(bytes)
    }) { p -> p.readInt() }

    /** Your plan [e] proposed to [pairing]'s Robot: the plan's id, or null when it couldn't go. */
    fun propose(pairing: String, e: EventFields): String? = call(LinkIpcProtocol.PLAN_SEND, {
        writeId(pairing); writeString(e.title); writeString(e.place.orEmpty()); writeString(e.note.orEmpty())
        writeInt(e.times.size); e.times.forEach { writeLong(it) }; writeLong(e.minutes)
    }) { p -> id(p) }

    // 1.5: shared lists (LinkService refuses each while locked).

    /** A part or batch of a list for [pairing]'s Robot; its request's id, or null when it can't go now. */
    fun listSend(pairing: String, r: DeskRequest): String? = call(LinkIpcProtocol.LIST_SEND, {
        writeId(pairing); writeByteArray(r.frame); writeByteArray(r.plain)
    }) { p -> id(p) }

    // L11: shared folders and group plans (unlocked only).
    fun folderInvite(pairing: String, name: String): String? = call(LinkIpcProtocol.FOLDER_INVITE, { writeId(pairing); writeString(name) }) { p -> id(p) }
    fun folderAdd(pairing: String, fold: String, file: String, name: String, size: Long): String? =
        call(LinkIpcProtocol.FOLDER_ADD, { writeId(pairing); writeId(fold); writeId(file); writeString(name); writeLong(size) }) { p -> id(p) }
    fun folderRemove(pairing: String, fold: String, item: String): Boolean = flag(LinkIpcProtocol.FOLDER_REMOVE) { writeId(pairing); writeId(fold); writeId(item) }
    fun folderLeave(pairing: String, fold: String): Boolean = flag(LinkIpcProtocol.FOLDER_LEAVE) { writeId(pairing); writeId(fold) }
    fun folders(): List<FolderRec>? = call(LinkIpcProtocol.FOLDERS, {}) { r -> r.createByteArray()?.let(FolderRec::decodeAll) }
    fun groupStart(g: LinkGroup): String? = call(LinkIpcProtocol.GROUP_START, { writeByteArray(LinkGroup.encode(g)) }) { p -> id(p) }
    fun groupPropose(id: String, time: Long): Boolean = flag(LinkIpcProtocol.GROUP_PROPOSE) { writeId(id); writeLong(time) }
    fun groupCancel(id: String): Boolean = flag(LinkIpcProtocol.GROUP_CANCEL) { writeId(id) }
    fun groups(): GroupsView? = call(LinkIpcProtocol.GROUPS, {}) { r -> r.createByteArray()?.let(GroupsView::decode) }

    fun listLeave(pairing: String, list: String): Boolean = flag(LinkIpcProtocol.LIST_LEAVE) { writeId(pairing); writeId(list) }
    fun listLostSeen(pairing: String, list: String): Boolean = flag(LinkIpcProtocol.LIST_LOST_SEEN) { writeId(pairing); writeId(list) }
    fun listPending(): List<ListShareItem>? = call(LinkIpcProtocol.LIST_PENDING, {}) { r -> r.createByteArray()?.let(ListShareItem::decodeAll) }
    fun listApplied(ids: List<String>): Boolean = ids.isNotEmpty() && flag(LinkIpcProtocol.LIST_APPLIED) { writeInt(ids.size); ids.forEach { writeId(it) } }
    fun listShares(): List<ListShareView>? = call(LinkIpcProtocol.LIST_SHARES, {}) { r -> r.createByteArray()?.let(ListShareView::decodeAll) }

    /** Every plan kept (null while locked, or when it doesn't read). */
    fun plans(): List<LinkPlan>? = call(LinkIpcProtocol.PLANS, {}) { r -> r.createByteArray()?.let(LinkPlan::decodeAll) }

    /** Your cancel of plan [id] with [pairing]'s Robot. */
    fun cancelPlan(pairing: String, id: String): Boolean = flag(LinkIpcProtocol.PLAN_CANCEL) { writeId(pairing); writeId(id) }

    /** Reply line [id] was said: not announced again. */
    fun seen(id: String): Boolean = flag(LinkIpcProtocol.DESK_SEEN) { writeId(id) }

    // Frames -------------------------------------------------------------------------------------------------------------

    private fun Parcel.writeId(id: String) {
        writeByteArray(LinkBytes.unhex(id, LinkIpcProtocol.ID_BYTES) ?: throw IllegalArgumentException())
    }

    /** An id field of a reply: 16 bytes (never all zero), or 0 bytes for none. Anything else throws (no answer). */
    private fun id(r: Parcel): String? {
        val b = r.createByteArray() ?: throw IllegalStateException()
        if (b.isEmpty()) return null
        if (b.size != LinkIpcProtocol.ID_BYTES) throw IllegalStateException()
        return LinkBytes.hex(b).takeIf(LinkIds::isId) ?: throw IllegalStateException()
    }

    /** A call answered by 1 or 0. */
    private fun flag(code: Int, write: Parcel.() -> Unit): Boolean = call(code, write) { r -> when (r.readInt()) { 1 -> true; 0 -> false; else -> null } } == true

    /**
     * One call: the interface token and [write]'s fields, then the reply read by [read] only when it starts with REPLY_OK and
     * nothing is left over after it. Null for anything else (refused, a dead process, a frame that doesn't read).
     */
    private fun <T> call(code: Int, write: Parcel.() -> Unit, read: (Parcel) -> T?): T? {
        if (!connect()) return null
        val b = remote ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(LinkIpcProtocol.DESCRIPTOR)
            data.write()
            if (!b.transact(code, data, reply, 0)) return null
            reply.setDataPosition(0)
            if (reply.dataAvail() < 4 || reply.readInt() != LinkIpcProtocol.REPLY_OK) return null
            val v = read(reply)
            if (reply.dataAvail() != 0) null else v
        } catch (_: Exception) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        const val CONNECT_MS = 10_000L
    }
}
