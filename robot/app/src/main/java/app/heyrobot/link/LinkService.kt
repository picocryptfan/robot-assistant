// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.app.KeyguardManager
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.core.link.BlockedView
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskNotices
import app.heyrobot.core.link.DeskRequest
import app.heyrobot.core.link.ListShareItem
import app.heyrobot.core.link.ListShareView
import app.heyrobot.core.link.LinkBytes
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.GroupsView
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.MakeCodeResult
import app.heyrobot.core.link.PairCodeForm
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.UseCodeResult
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.LsOp
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.r2r.ListsShareHandler
import app.heyrobot.r2r.LinkNode
import app.heyrobot.r2r.PushEvent
import app.heyrobot.r2r.within
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// THE :link PROCESS (android:process=":link", not exported, Robot's signature permission, no intent filter). 1.5, Robot to
// Robot (robot-to-robot-plan.md §5.1, §5.3; job L3): how Robot's other processes reach :link. Bound by name (core
// LinkIpcProtocol.SERVICE_CLASS) from main (L5's screens) and :voice (L6 and L7): neither refers to this class or the link
// module. Each call is checked twice: the binding needs Robot's signature permission, and the call's uid must be Robot's own
// (an isolated process, :linkparse's included, runs under another uid and is refused). Each frame is read strictly: the
// interface token, exactly the call's fields within their caps, nothing left over; anything else is refused with nothing
// but REPLY_REFUSED. Replies are numbers, strings and bytes; never an exception (Parcel.writeException is never used).
//
// While a client is bound :link is awake (LinkRuntime.hold): it listens for paired Robots on a shared network and runs Tor
// when a pairing needs it; when the last client unbinds, both stop.
//
// 1.5 L5-fix: the lock rules (§4.4, core LinkPolicy.canPair) here too, whatever the caller: while the phone is locked,
// nothing that pairs (MAKE_CODE, USE_CODE), verifies, loosens a pairing (a level or preset that lets more in, LOWER, accepting
// a moved hello) or unblocks is answered; raising, unpairing, blocking, anything that tightens, and the reads still are.
//
// 1.5 L5: main's pairing screens and People pages. Making and using a code wakes :link (it must listen and run Tor while
// you pair); reading the pairings, a safety number or the block list, and changing a pairing (who it is, its levels, its
// mode, verifying, blocking, a moved hello's answer) open the node without waking it, so a People page never starts Tor.
//
// 1.5 L6: the desk's calls, from :voice (the panel's cards, the notifications) and main (Requests). The view, your answers
// and Undo need the phone unlocked; the notices (kinds, pairings and counts) are read while locked too.

class LinkService : Service() {
    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LinkIpcProtocol.DESCRIPTOR)
                return true
            }
            if (code !in LinkIpcProtocol.STATUS..LinkIpcProtocol.LAST) return false
            if (getCallingUid() != Process.myUid()) {
                reply?.writeInt(LinkIpcProtocol.REPLY_REFUSED)
                return true
            }
            try {
                data.enforceInterface(LinkIpcProtocol.DESCRIPTOR)
                answer(code, IpcFrame(data), reply)
            } catch (_: Exception) {
                reply?.apply { setDataSize(0); setDataPosition(0); writeInt(LinkIpcProtocol.REPLY_REFUSED) }
            }
            return true
        }
    }

    private var held = false

    // fix-link-unbind-141 (review-fix-tor-lows-141-d F1): onUnbind runs on :link's main thread and never takes this
    // service's lock or LinkRuntime's (a call's node() can hold both while a turn-off's close() runs, up to 15 s): the
    // release goes to [releaser]. Each unbind is counted; a hold taken before the latest unbind ([heldAt] below it) is that
    // unbind's to release, so a bind landing before the release runs takes a hold of its own and lets the old one go.
    private val unbinds = AtomicLong()
    private var heldAt = 0L

    // fix-link-unbind-141-b (review-fix-link-unbind-141 L1): once destroyed, no call takes a hold (a stale one, sent
    // before its client unbound, is refused), and a hold such a call took after the last unbind is dropped here.
    @Volatile private var gone = false

    override fun onBind(intent: Intent?): IBinder = stub

    override fun onUnbind(intent: Intent?): Boolean {
        val at = unbinds.incrementAndGet()
        releaser.execute { synchronized(this) { if (held && heldAt < at) { held = false; LinkRuntime.release() } } }
        return false
    }

    override fun onDestroy() {
        // No lock here either (main thread): [gone] first, so a node() after the queued release finds it and holds nothing.
        gone = true
        releaser.execute { synchronized(this) { if (held) { held = false; LinkRuntime.release() } } }
        super.onDestroy()
    }

    private fun node(): LinkNode? = synchronized(this) {
        if (gone) return null
        val now = unbinds.get()
        if (held && heldAt < now) {
            // Unbound since this hold, and bound again before its release ran: hold for the new client first, so :link
            // stays awake, then let the old hold go here (the queued release finds it newer and leaves it).
            val again = LinkRuntime.hold(this)
            LinkRuntime.release()
            held = again != null
            if (!held) return null
            heldAt = now
        }
        if (!held) LinkRuntime.hold(this)?.also { held = true; heldAt = now } ?: return null
        LinkRuntime.node(this)
    }

    private fun answer(code: Int, f: IpcFrame, reply: Parcel?) {
        val out = reply ?: return
        // L5-fix: Off means off. While Robot to Robot is off, only ALLOWED_WHILE_OFF is answered; everything else is
        // refused before its frame is read, and no node is opened for it.
        if (code !in ALLOWED_WHILE_OFF && !LinkRuntime.on(this)) return refused(out)
        // L5-fix: the phone locked: only what makes things stricter, or reads them.
        if (code in LOCKED_REFUSED && !LinkPolicy.canPair(locked())) return refused(out)
        // L16: a phone lock stops what goes out to another Robot on your word, as the desk does.
        if (code in PHONE_LOCK_REFUSED && phoneLocked()) return refused(out)
        when (code) {
            LinkIpcProtocol.STATUS -> {
                f.end()
                // L12: a restore while :link was already running is picked up here (Settings asks for this when it opens).
                val s = LinkRuntime.node(this)?.also { LinkRuntime.restored(applicationContext, it) }?.status()?.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeInt(LinkRuntime.vaultCode(this))
                out.writeInt(if (LinkRuntime.on(this)) 1 else 0)
                out.writeInt(if (AndroidLinkEnv(this).networkAllowed()) 1 else 0)
                for (v in listOf(s?.pairings ?: 0, s?.sent ?: 0, s?.outbox ?: 0, s?.codes ?: 0, s?.connections ?: 0)) out.writeInt(v)
                for (b in listOf(s?.listening, s?.torRunning, s?.awake)) out.writeInt(if (b == true) 1 else 0)
            }
            LinkIpcProtocol.SET_UP -> {
                f.end()
                val ok = LinkRuntime.setUp(this)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (ok) 1 else 0)
            }
            LinkIpcProtocol.START_OVER -> {
                f.end()
                synchronized(this) { if (held) { held = false; LinkRuntime.release() } }
                val gone = LinkRuntime.startOver(this)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (gone) 1 else 0)
            }
            LinkIpcProtocol.SEND -> {
                val pairing = f.id(); val type = f.int(); val id = f.id(); val at = f.long(); val ttl = f.long()
                val bytes = f.bytes(LinkIpcProtocol.MAX_REQUEST)
                f.end()
                val n = node() ?: return refused(out)
                val problem = n.queue(pairing, type, id, at, ttl, bytes).within(WAIT_S)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(problem?.code ?: 0)
            }
            LinkIpcProtocol.MAKE_CODE -> {
                val mode = LinkMode.of(f.int()) ?: throw IllegalArgumentException()
                val inPerson = f.flag()
                f.end()
                val n = node() ?: return refused(out)
                val made = try { n.makeCode(mode, inPerson).get(CODE_WAIT_S, TimeUnit.SECONDS) } catch (_: Exception) { app.heyrobot.r2r.MadeCode(MakeCodeResult.FAILED) }
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(made.result.code)
                out.writeByteArray(made.id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(0)); out.writeString(made.code?.text() ?: "")
            }
            LinkIpcProtocol.USE_CODE -> {
                val text = f.string(LinkIpcProtocol.MAX_CODE_TEXT)
                val form = when (f.int()) { 0 -> PairCodeForm.SCANNED; 1 -> PairCodeForm.PASTED; else -> throw IllegalArgumentException() }
                val mode = LinkMode.of(f.int()) ?: throw IllegalArgumentException()
                f.end()
                val n = node() ?: return refused(out)
                val used = try { n.useCode(text, form, mode).get(CODE_WAIT_S, TimeUnit.SECONDS) } catch (_: Exception) { app.heyrobot.r2r.UsedCode(UseCodeResult.FAILED) }
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(used.result.code)
                out.writeByteArray(used.pairing?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(0))
            }
            LinkIpcProtocol.CANCEL_CODES -> {
                f.end()
                node()?.cancelCodes()?.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(1)
            }
            LinkIpcProtocol.UNPAIR -> {
                val pairing = f.id()
                f.end()
                val n = node() ?: return refused(out)
                val ok = n.unpair(pairing).get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (ok) 1 else 0)
            }
            LinkIpcProtocol.CHOOSE_PUSH -> {
                val pkg = f.string(app.heyrobot.core.link.PushApps.MAX_PACKAGE)
                f.end()
                val n = node() ?: return refused(out)
                val connector = app.heyrobot.r2r.ConnectorPush(this)
                val ok = (pkg.isEmpty() || connector.apps().any { it.pkg == pkg }) &&
                    try { n.choosePush(pkg.isNotEmpty()) { connector.use(pkg) }.get(CODE_WAIT_S, TimeUnit.SECONDS) } catch (_: Exception) { false }
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (ok) 1 else 0)
            }
            LinkIpcProtocol.SEAL_REPORT -> {
                // RP5: a report sealed for the share sheet; no node, no vault, no network (LinkReports).
                val key = f.bytes(app.heyrobot.core.feedback.ReportKey.BYTES); val ref = f.string(LinkIpcProtocol.REF_CHARS); val pipe = f.fd(); f.end()
                val n = LinkReports.seal(this, key, ref, pipe) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(n)
            }
            LinkIpcProtocol.FEED_TOR -> {
                f.end()
                val n = node() ?: return refused(out)
                val up = when (n.feedTor()) {
                    app.heyrobot.r2r.FeedTorGate.State.UP -> LinkIpcProtocol.FEED_TOR_UP
                    app.heyrobot.r2r.FeedTorGate.State.STARTING -> LinkIpcProtocol.FEED_TOR_STARTING
                    app.heyrobot.r2r.FeedTorGate.State.DOWN -> LinkIpcProtocol.FEED_TOR_DOWN
                }
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(up)
            }
            LinkIpcProtocol.FEED_STREAM -> {
                val host = f.string(LinkIpcProtocol.MAX_FEED_HOST); val port = f.int(); f.end()
                // Today H7b: :link dials and hands the stream over; the TLS, the request and the bytes are the fetching process's.
                val n = node() ?: return refused(out)
                val d = n.feedStream(host, port)
                val fd = (d as? app.heyrobot.r2r.AndroidUnixSockets.Described)?.descriptor
                try {
                    out.writeInt(LinkIpcProtocol.REPLY_OK)
                    if (fd == null) out.writeInt(0)
                    else ParcelFileDescriptor.dup(fd).use { p -> out.writeInt(1); p.writeToParcel(out, 0) }
                } finally {
                    d?.close()
                }
            }
            LinkIpcProtocol.PUSH_APPS -> {
                f.end()
                val n = node() ?: return refused(out)
                val connector = app.heyrobot.r2r.ConnectorPush(this)
                val apps = connector.apps()
                val waiting = n.pushWaiting().get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeString(connector.chosen().orEmpty()); out.writeInt(waiting); out.writeInt(apps.size)
                for (a in apps) { out.writeString(a.pkg); out.writeString(a.label) }
            }
            LinkIpcProtocol.WAKE -> {
                f.end()
                node()
                out.writeInt(LinkIpcProtocol.REPLY_OK)
            }
            LinkIpcProtocol.CODE_STATE -> {
                val id = f.id()
                f.end()
                val n = open() ?: return refused(out)
                val (state, pairing) = n.codeState(id).get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(state.code)
                out.writeByteArray(pairing?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(0))
            }
            LinkIpcProtocol.PAIRINGS -> {
                f.end()
                val n = open() ?: return refused(out)
                val views = n.views().get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(PairingView.encodeAll(views))
            }
            LinkIpcProtocol.SAFETY -> {
                val pairing = f.id()
                f.end()
                val n = open() ?: return refused(out)
                val digits = n.safety(pairing).get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeString(digits ?: "")
            }
            LinkIpcProtocol.VERIFY -> done(out) { val pairing = f.id(); f.end(); open()?.verify(pairing) }
            LinkIpcProtocol.SET_PERSON -> done(out) {
                val pairing = f.id()
                val person = f.string(LinkIpcProtocol.MAX_PERSON)
                f.end()
                open()?.setPerson(pairing, person.ifEmpty { null })
            }
            LinkIpcProtocol.SET_PRESET -> done(out) {
                val pairing = f.id()
                val preset = LinkLevels.preset(f.int()) ?: throw IllegalArgumentException()
                f.end()
                val n = open() ?: return@done null
                if (locked() && loosens(n, pairing, LinkLevels.of(preset))) return@done null
                n.setPreset(pairing, preset)
            }
            LinkIpcProtocol.SET_LEVEL -> done(out) {
                val pairing = f.id()
                val cap = LinkLevels.capability(f.int()) ?: throw IllegalArgumentException()
                val level = LinkLevels.level(f.int()) ?: throw IllegalArgumentException()
                f.end()
                val n = open() ?: return@done null
                if (locked() && loosens(n, pairing, mapOf(cap to level))) return@done null
                n.setLevel(pairing, cap, level)
            }
            LinkIpcProtocol.RAISE -> done(out) { val pairing = f.id(); f.end(); open()?.raise(pairing) }
            LinkIpcProtocol.LOWER -> done(out) { val pairing = f.id(); f.end(); open()?.lower(pairing) }
            LinkIpcProtocol.KEEP_MODE -> done(out) { val pairing = f.id(); f.end(); open()?.keepMode(pairing) }
            LinkIpcProtocol.BLOCK -> done(out) {
                val pairing = f.id()
                val label = f.string(LinkIpcProtocol.MAX_LABEL)
                f.end()
                open()?.block(pairing, label)
            }
            LinkIpcProtocol.BLOCKED -> {
                f.end()
                val n = open() ?: return refused(out)
                val list = n.blockedList().get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(BlockedView.encodeAll(list))
            }
            LinkIpcProtocol.UNBLOCK -> done(out) { val id = f.id(); f.end(); open()?.unblock(id) }
            LinkIpcProtocol.ANSWER_MOVED -> done(out) {
                val pairing = f.id()
                val accept = f.flag()
                f.end()
                // Keeping the old phone tightens; taking the new one's key needs the phone unlocked.
                if (accept && locked()) return@done null
                open()?.answerMoved(pairing, accept)
            }
            LinkIpcProtocol.CANCEL_CODE -> done(out) { val id = f.id(); f.end(); open()?.cancelCode(id) }
            // L6, the desk (core LinkDesk). Its view opens the waiting inbox and reads it through :linkparse, so :link is
            // held awake for it (as for a client using it); the notices only count.
            LinkIpcProtocol.DESK -> {
                f.end()
                val n = node() ?: return refused(out)
                val bytes = n.onLinkThread { n.desk?.viewBytes() }.get(DESK_WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(bytes)
            }
            LinkIpcProtocol.DESK_ANSWER -> done(out) {
                val id = f.id()
                val choice = DeskChoice.of(f.int()) ?: throw IllegalArgumentException()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.answer(id, choice) == true }
            }
            LinkIpcProtocol.DESK_REPLY -> done(out) {
                val id = f.id()
                val text = f.string(LinkIpcProtocol.MAX_ANSWER)
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.answerWith(id, text) == true }
            }
            // L12: what a backup holds of Robot to Robot: no key is in it (core LinkBackupCodec).
            LinkIpcProtocol.BACKUP_EXPORT -> {
                f.end()
                val n = node() ?: return refused(out)
                val file = n.backupExport().get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(app.heyrobot.core.link.LinkBackupCodec.encode(file).toByteArray(Charsets.UTF_8))
            }
            // L12: "Tell my Robots I moved": a tap, one pairing at a time. It can wait on a push address and on Tor.
            LinkIpcProtocol.SEND_MOVED -> {
                val pairing = f.id()
                f.end()
                val n = node() ?: return refused(out)
                val ok = try { n.sendMoved(pairing).get(CODE_WAIT_S, TimeUnit.SECONDS) } catch (_: Exception) { false }
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (ok) 1 else 0)
            }
            LinkIpcProtocol.DESK_FILE_ANSWER -> done(out) {
                val id = f.id()
                val file = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.answerWithFile(id, file) == true }
            }
            LinkIpcProtocol.FILE_SEND -> done(out) {
                val v = TransferView.decodeAll(f.bytes(LinkIpcProtocol.MAX_TRANSFER)).singleOrNull()
                val key = f.bytes(XferSeal.KEY_BYTES)
                f.end()
                val n = open() ?: return@done null
                if (v == null || !v.out) return@done null
                n.onLinkThread { n.fileTransfers?.offer(v.id, v.pairing, v.offer, key, LinkRequests.DEFAULT_TTL) == true }
            }
            LinkIpcProtocol.TRANSFERS -> {
                f.end()
                val n = open() ?: return refused(out)
                val views = n.onLinkThread { n.fileTransfers?.views() }.get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(TransferView.encodeAll(views))
            }
            LinkIpcProtocol.TRANSFER_FILED -> done(out) {
                val id = f.id()
                val ok = f.flag()
                f.end()
                val n = open() ?: return@done null
                n.onLinkThread { n.fileTransfers?.filed(id, ok) == true }
            }
            LinkIpcProtocol.DESK_UNDO -> done(out) {
                val id = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.undo(id) == true }
            }
            LinkIpcProtocol.DESK_NOTICES -> {
                f.end()
                val n = open() ?: return refused(out)
                val notices = n.onLinkThread { n.desk?.notices() }.get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(DeskNotices.encode(notices))
            }
            LinkIpcProtocol.DESK_NOT_NOW -> done(out) {
                val pairing = f.id()
                val cap = LinkLevels.capability(f.int()) ?: throw IllegalArgumentException()
                f.end()
                val n = open() ?: return@done null
                n.onLinkThread { n.desk?.notNow(pairing, cap) == true }
            }
            // L7: your plans (core LinkPlan). Proposing and cancelling send to another Robot: only unlocked (LOCKED_REFUSED).
            // 1.5: shared lists. :link holds no lists: :voice and main ask, unlocked (LOCKED_REFUSED).
            LinkIpcProtocol.LIST_SEND -> {
                val pairing = f.id()
                val frame = f.bytes(ListShareItem.MAX_BYTES); val plain = f.bytes(ListShareItem.MAX_BYTES)
                f.end()
                val body = DeskRequest(frame, plain).read()?.body ?: throw IllegalArgumentException()
                val node = node() ?: return refused(out)
                val id = node.onLinkThread {
                    when (body) {
                        is LsOffer -> node.desk?.lists?.offerPart(pairing, body, node.deskOut)
                        is LsOp -> node.desk?.lists?.sendOps(pairing, body, node.deskOut)
                        else -> null
                    }
                }.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(0))
            }
            // L11: shared folders and group plans. Each is yours, from an unlocked phone (LOCKED_REFUSED, and the desk's mine).
            LinkIpcProtocol.FOLDER_INVITE -> {
                val pairing = f.id(); val name = f.string(LinkIpcProtocol.MAX_TITLE)
                f.end()
                val n = node() ?: return refused(out)
                val id = n.onLinkThread { n.desk?.let { d -> d.mine { o -> d.folders?.invite(pairing, name, o) } } }.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(LinkIpcProtocol.ID_BYTES))
            }
            LinkIpcProtocol.FOLDER_ADD -> {
                val pairing = f.id(); val fold = f.id(); val file = f.id(); val name = f.string(LinkIpcProtocol.MAX_TITLE); val size = f.long()
                f.end()
                val n = node() ?: return refused(out)
                val id = n.onLinkThread { n.desk?.let { d -> d.mine { o -> d.folders?.add(pairing, fold, file, name, size, o) } } }.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(LinkIpcProtocol.ID_BYTES))
            }
            LinkIpcProtocol.FOLDER_REMOVE -> done(out) {
                val pairing = f.id(); val fold = f.id(); val item = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.let { d -> d.mine { o -> d.folders?.remove(pairing, fold, item, o) } } == true }
            }
            LinkIpcProtocol.FOLDER_LEAVE -> done(out) {
                val pairing = f.id(); val fold = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.let { d -> d.mine { o -> d.folders?.leave(pairing, fold, o) } } == true }
            }
            LinkIpcProtocol.FOLDERS -> {
                f.end()
                val n = node() ?: return refused(out)
                val b = n.onLinkThread { n.desk?.folders?.views()?.let(FolderRec::encodeAll) }.get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(b)
            }
            LinkIpcProtocol.GROUP_START -> {
                val g = LinkGroup.decode(f.bytes(LinkGroup.MAX_BYTES))?.fresh()
                f.end()
                val n = node() ?: return refused(out)
                val id = g?.let { n.onLinkThread { n.desk?.let { d -> d.mine { o -> d.groups?.start(g, o) } } }.get(WAIT_S, TimeUnit.SECONDS) }
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(LinkIpcProtocol.ID_BYTES))
            }
            LinkIpcProtocol.GROUP_PROPOSE -> done(out) {
                val id = f.id(); val time = f.long()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.proposeGroup(id, time) == true }
            }
            LinkIpcProtocol.GROUP_CANCEL -> done(out) {
                val id = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.cancelGroup(id) == true }
            }
            LinkIpcProtocol.GROUPS -> {
                f.end()
                val n = node() ?: return refused(out)
                val b = n.onLinkThread { n.desk?.groups?.view(System.currentTimeMillis() / 1000)?.let(GroupsView::encode) }.get(DESK_WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK)
                out.writeByteArray(b)
            }
            LinkIpcProtocol.LIST_LEAVE -> done(out) {
                val pairing = f.id(); val list = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.lists?.leave(pairing, list, n.deskOut) == true }
            }
            LinkIpcProtocol.LIST_LOST_SEEN -> done(out) {
                val pairing = f.id(); val list = f.id()
                f.end()
                val n = node() ?: return@done null
                n.onLinkThread { n.desk?.lists?.lostSeen(pairing, list); true }
            }
            LinkIpcProtocol.LIST_PENDING -> {
                f.end()
                val node = open() ?: return refused(out)
                val b = node.onLinkThread { node.desk?.lists?.pending() }.get(DESK_WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(b)
            }
            LinkIpcProtocol.LIST_APPLIED -> done(out) {
                val n = f.int()
                if (n !in 1..ListsShareHandler.PENDING) throw IllegalArgumentException()
                val ids = List(n) { f.id() }
                f.end()
                val node = node() ?: return@done null
                node.onLinkThread { node.desk?.lists?.applied(ids); true }
            }
            LinkIpcProtocol.LIST_SHARES -> {
                f.end()
                val node = node() ?: return refused(out)
                val v = node.onLinkThread { node.desk?.lists?.views(node.deskOut.now()) }.get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(ListShareView.encodeAll(v))
            }
            LinkIpcProtocol.PLAN_SEND -> {
                val pairing = f.id()
                val title = f.string(LinkIpcProtocol.MAX_TITLE); val place = f.string(LinkIpcProtocol.MAX_PLACE); val note = f.string(LinkIpcProtocol.MAX_NOTE)
                val n = f.int()
                if (n !in 1..RequestCaps.TIMES) throw IllegalArgumentException()
                val times = List(n) { f.long() }
                val minutes = f.long()
                f.end()
                if (minutes !in RequestCaps.EVENT_MIN_MINUTES..RequestCaps.EVENT_MAX_MINUTES || times.zipWithNext().any { (a, b) -> a >= b }) throw IllegalArgumentException()
                val event = EventFields(title, times, minutes, place.ifEmpty { null }, note.ifEmpty { null })
                val node = node() ?: return refused(out)
                val id = node.onLinkThread { node.desk?.propose(pairing, event) }.get(WAIT_S, TimeUnit.SECONDS)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(id?.let { LinkBytes.unhex(it, LinkIpcProtocol.ID_BYTES) } ?: ByteArray(0))
            }
            LinkIpcProtocol.PLANS -> {
                f.end()
                val node = open() ?: return refused(out)
                val plans = node.onLinkThread { node.desk?.plans() }.get(WAIT_S, TimeUnit.SECONDS) ?: return refused(out)
                out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeByteArray(LinkPlan.encodeAll(plans))
            }
            LinkIpcProtocol.PLAN_CANCEL -> done(out) {
                val pairing = f.id(); val id = f.id()
                f.end()
                val node = node() ?: return@done null
                node.onLinkThread { node.desk?.cancelPlan(pairing, id) == true }
            }
            LinkIpcProtocol.DESK_SEEN -> done(out) {
                val id = f.id()
                f.end()
                val node = open() ?: return@done null
                node.onLinkThread { node.desk?.seen(id) == true }
            }
            else -> refused(out)
        }
    }

    /** L16: a phone lock is on now (unknown counts as on: refusing to send is the safe side). */
    private fun phoneLocked(): Boolean = try { PhoneLockNow.on(this) } catch (_: Exception) { true }

    /** L5-fix: the phone is locked now (KeyguardManager; unknown counts as locked). */
    private fun locked(): Boolean = try { getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true } catch (_: Exception) { true }

    /** L5-fix: whether [to] lets [pairing] do more than its levels do now (a level above the current one for any capability). */
    private fun loosens(n: LinkNode, pairing: String, to: Map<LinkCapability, LinkLevel>): Boolean {
        val now = n.views().get(WAIT_S, TimeUnit.SECONDS).firstOrNull { it.id == pairing }?.levels ?: return false
        return to.any { (cap, level) -> level.ordinal > (now[cap] ?: LinkLevel.OFF).ordinal }
    }

    /** L5: the node opened (and started) without waking it: no listening, no Tor for this call. Null when off or not set up. */
    private fun open(): LinkNode? = LinkRuntime.node(this)

    /**
     * L5: a call that changes one thing and answers 1 or 0. [call] reads the frame (throwing on a bad one: refused) and
     * returns the node's answer, or null when the node isn't open (refused).
     */
    private fun done(out: Parcel, call: () -> java.util.concurrent.CompletableFuture<Boolean>?) {
        val answer = call() ?: return refused(out)
        val ok = answer.within(WAIT_S)
        out.writeInt(LinkIpcProtocol.REPLY_OK); out.writeInt(if (ok) 1 else 0)
    }

    private fun refused(out: Parcel) {
        out.writeInt(LinkIpcProtocol.REPLY_REFUSED)
    }

    internal companion object {
        /** One daemon thread for every unbind's release, in order; never :link's main thread. */
        private val releaser: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-unbind").apply { isDaemon = true } }

        /** Tests only: true once every release queued so far has run, within [seconds]. */
        @androidx.annotation.VisibleForTesting
        internal fun released(seconds: Long): Boolean = try { releaser.submit {}.get(seconds, TimeUnit.SECONDS); true } catch (_: Exception) { false }

        /** Tests only: [block] runs on the release thread first, so a release queued after it waits for it. */
        @androidx.annotation.VisibleForTesting
        internal fun onReleaser(block: () -> Unit) { releaser.execute(block) }

        /** L5-fix: refused whole while the phone is locked (the lock rules, §4.4). */
        /**
         * L5-fix: answered while Robot to Robot is off: its state, turning it on (SET_UP) and Start over; and RP5's
         * SEAL_REPORT, since sealing a report needs nothing of Robot to Robot: LinkReports opens no node (no listener, no
         * socket, no Tor), reads no vault, and writes only cache/reports/ (LinkServiceIpcTest checks all three).
         */
        val ALLOWED_WHILE_OFF = setOf(LinkIpcProtocol.STATUS, LinkIpcProtocol.SET_UP, LinkIpcProtocol.START_OVER, LinkIpcProtocol.SEAL_REPORT)
        val PHONE_LOCK_REFUSED = setOf(LinkIpcProtocol.SEND, LinkIpcProtocol.FILE_SEND, LinkIpcProtocol.LIST_SEND, LinkIpcProtocol.LIST_LEAVE)
        val LOCKED_REFUSED = setOf(LinkIpcProtocol.MAKE_CODE, LinkIpcProtocol.USE_CODE, LinkIpcProtocol.VERIFY, LinkIpcProtocol.LOWER, LinkIpcProtocol.UNBLOCK,
            // L6: cards, your answers to them and Undo show or change what another Robot asked: only unlocked (§4.4).
            LinkIpcProtocol.DESK, LinkIpcProtocol.DESK_ANSWER, LinkIpcProtocol.DESK_UNDO,
            // L10: your typed answer to a question goes to another Robot.
            LinkIpcProtocol.DESK_REPLY,
            // L8: a file for another Robot, and the list of them with their names.
            LinkIpcProtocol.FILE_SEND, LinkIpcProtocol.TRANSFERS, LinkIpcProtocol.TRANSFER_FILED, LinkIpcProtocol.DESK_FILE_ANSWER,
            // L12: a backup is made unlocked, and telling your Robots you moved is a tap on an unlocked phone.
            LinkIpcProtocol.BACKUP_EXPORT, LinkIpcProtocol.SEND_MOVED,
            // L7: sending to another Robot (§4.4: "Unlock your phone to ask Sam's Robot."), and your plans (their words).
            LinkIpcProtocol.SEND, LinkIpcProtocol.PLAN_SEND, LinkIpcProtocol.PLANS, LinkIpcProtocol.PLAN_CANCEL,
            // L11: shared folders and group plans are never read or changed while locked.
            LinkIpcProtocol.FOLDER_INVITE, LinkIpcProtocol.FOLDER_ADD, LinkIpcProtocol.FOLDER_REMOVE, LinkIpcProtocol.FOLDER_LEAVE, LinkIpcProtocol.FOLDERS,
            LinkIpcProtocol.GROUP_START, LinkIpcProtocol.GROUP_PROPOSE, LinkIpcProtocol.GROUP_CANCEL, LinkIpcProtocol.GROUPS,
            // 1.5: shared lists are never read or changed while locked.
            LinkIpcProtocol.LIST_SEND, LinkIpcProtocol.LIST_LEAVE, LinkIpcProtocol.LIST_PENDING, LinkIpcProtocol.LIST_APPLIED, LinkIpcProtocol.LIST_SHARES, LinkIpcProtocol.LIST_LOST_SEEN)
        const val WAIT_S = 30L
        /** A code may need Tor started (its one-time onion), or your distributor's answer. */
        const val CODE_WAIT_S = 180L
        /** L6: the desk's view may read many waiting items through :linkparse (each at most LinkParseIpc.CALL_MS). */
        const val DESK_WAIT_S = 120L
    }
}

/**
 * One call's fields, read strictly from [p]: each only while the parcel still holds it (a read past the end gives 0 or
 * null, not an error), arrays and strings capped before anything is made for them, and [end] refuses anything left over.
 * Any breach throws, and the call is refused.
 */
internal class IpcFrame(private val p: Parcel) {
    fun int(): Int { need(4); return p.readInt() }
    fun long(): Long { need(8); return p.readLong() }
    fun flag(): Boolean = when (int()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException() }

    /** A byte array of 1 to [max] bytes. */
    fun bytes(max: Int): ByteArray {
        val at = p.dataPosition()
        val n = int()
        if (n < 1 || n > max) throw IllegalArgumentException()
        need(n.toLong())
        p.setDataPosition(at)
        val b = p.createByteArray()
        if (b == null || b.size != n) throw IllegalArgumentException()
        return b
    }

    /** A pairing or request id: exactly 16 bytes, as 32 hex digits, never all zero. */
    fun id(): String {
        val b = bytes(LinkIpcProtocol.ID_BYTES)
        if (b.size != LinkIpcProtocol.ID_BYTES || LinkBytes.allZero(b)) throw IllegalArgumentException()
        return LinkBytes.hex(b)
    }

    /**
     * A string of at most [maxChars] characters (never null). Parcel reads a string only as long as the parcel holds (a
     * transaction is at most 1 MB), so it is read whole and then checked.
     */
    fun string(maxChars: Int): String {
        need(4)
        val s = p.readString() ?: throw IllegalArgumentException()
        if (s.length > maxChars) throw IllegalArgumentException()
        return s
    }

    /** A file descriptor (RP5's pipe). */
    fun fd(): ParcelFileDescriptor { need(4); return p.readFileDescriptor() ?: throw IllegalArgumentException() }

    fun end() { if (p.dataAvail() != 0) throw IllegalArgumentException() }

    private fun need(n: Long) { if (p.dataAvail() < n) throw IllegalArgumentException() }
}

/**
 * THE :link PROCESS. The push job (§2.2 "Being woken"): your distributor's event, handed over by LinkPushReceiver as an
 * expedited job, given to the node, which answers what it can; the job ends once the outbox is empty or after a minute.
 * Not exported; only the system binds it (BIND_JOB_SERVICE).
 */
class LinkJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread({
            var held = false
            try {
                // L5-fix: Robot to Robot off: the event is dropped unread (LinkRuntime.hold opens nothing then either).
                if (!LinkRuntime.on(app)) return@Thread
                val e = PushEvent.of(params.extras) ?: return@Thread
                val n = LinkRuntime.hold(app, job = true) ?: return@Thread
                held = true
                n.onPush(e).get(30, TimeUnit.SECONDS)
                n.pump().get(30, TimeUnit.SECONDS)
                // Its answers go out while the job runs (the job's network window).
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (System.nanoTime() < until && (n.status().get(10, TimeUnit.SECONDS).due > 0)) Thread.sleep(1_000)
            } catch (_: Throwable) {
            } finally {
                if (held) LinkRuntime.release()
                try { jobFinished(params, false) } catch (_: Throwable) {}
            }
        }, "robot-link-job").start() // l10n: not text (a thread's name)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
