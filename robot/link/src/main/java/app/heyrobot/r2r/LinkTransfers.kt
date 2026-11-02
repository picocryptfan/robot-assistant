// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.link.DeskNotice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkEvent
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkLog
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.LinkPurpose
import app.heyrobot.core.link.SlidingSum
import app.heyrobot.core.link.TransferView
import app.heyrobot.core.link.XferArrival
import app.heyrobot.core.link.XferEnd
import app.heyrobot.core.link.XferIn
import app.heyrobot.core.link.XferPaths
import app.heyrobot.core.link.XferSeal
import app.heyrobot.core.link.XferState
import app.heyrobot.core.link.XferStep
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.FileAccept
import app.heyrobot.core.link.requests.FileAsk
import app.heyrobot.core.link.requests.FileDecline
import app.heyrobot.core.link.requests.FileDone
import app.heyrobot.core.link.requests.FileKey
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.withFileLock
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §3.6, §3.7, §4.1; job L8): files between Robots, both ways.
//
// Coming in, in order, each step refusing what didn't earn it:
//   1. `file.offer` is a card, or accepted on its own under LinkPolicy.fileAlone (at most 50 MiB, never a dangerous type, 2 GiB
//      a day). A record is made only by that acceptance, and it says which pairing and offer it is for, under an id of this
//      Robot's own: the peer's id and name never reach a path.
//   2. `file.key` counts only for an offer you accepted, from the Robot that made it.
//   3. Chunks count only with that key, in order, each its exact length, each tag checked (core XferIn). They stay sealed in
//      files/link/transfers/. A peer that lies about the size is failed at once. Bad tags cut the connection and keep the
//      prefix; on a connection that proved itself, three of them fail the transfer.
//   4. All in: read through, size and SHA-256 checked (core XferArrival), sealed as format 2. Only then moved into
//      files/files/incoming/link/ (under files.lock), the one thing :link ever writes in Files' tree, where main's intake finds
//      it at an unlock. A wrong hash leaves nothing, and the sender is told.
// Going out: main seals its copy (made by :files) under the transfer's key in files/link/outgoing/, so :link can send it without
// ever holding its plain bytes, even with the phone locked. The key goes only after an acceptance; the chunks go only when
// asked for, only on a connection that proved itself, never by push, and only from a chunk that exists.
// Records live in the state store (sealed, robot_link_v1), on the link thread; chunks come on their connection's own thread,
// so a slow disk slows the sender and never fills memory.

/** One transfer. [id] is this Robot's own (a path's name); the peer's id is [offer]'s `file`. */
class TransferRecord(
    val id: String, val pairing: String, val out: Boolean, var state: XferState, val offer: FileOffer, val mode: Int, val at: Long,
    val expires: Long, var done: Long, val alone: Boolean, key: ByteArray?,
) {
    var key: ByteArray? = key?.copyOf()
    fun view() = TransferView(id, pairing, out, state, offer, mode, at, done, alone)
    override fun toString(): String = "TransferRecord($state)" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'X'.code.toByte(), 'F'.code.toByte(), 1)

        fun encode(t: TransferRecord): ByteArray = Bin.write { w ->
            w.write(MAGIC); w.blob(TransferView.encodeAll(listOf(t.view())), 8 * 1024); w.bytesOpt(t.key, 32); w.writeLong(t.expires)
        }

        fun decode(b: ByteArray): TransferRecord = Bin.read(b) { r ->
            r.magic(MAGIC)
            val v = TransferView.decodeAll(r.blob(8 * 1024)).singleOrNull() ?: Bin.bad()
            val key = r.bytesOpt(32)
            TransferRecord(v.id, v.pairing, v.out, v.state, v.offer, v.mode, v.at, r.readLong(), v.done, v.alone, key)
        }
    }
}

/** Where a transfer's bytes are. Every name is an id of this Robot's own. */
class TransferDirs(private val root: File, val filesDir: File) {
    val parts = File(root, "transfers") // l10n: not text (a folder's name)
    val outgoing = XferPaths.outgoing(root)
    val arrived = XferPaths.arrived(filesDir)
    val lock = File(filesDir, FileStore.LOCK)
    fun part(id: String) = File(parts, "$id.xfr") // l10n: not text (a file's name)
    fun sealing(id: String) = File(parts, "$id.arr") // l10n: not text (a file's name)
    fun copy(id: String) = XferPaths.outgoing(root, id)
    fun arrivedFile(id: String) = XferPaths.arrived(filesDir, id)

    /** Start over: nothing of any transfer stays. */
    fun wipe() {
        parts.deleteRecursively()
        outgoing.deleteRecursively()
        try { withFileLock(lock) { arrived.deleteRecursively() } } catch (_: Exception) {}
    }
}

/** What transfers need of the node. */
interface XferLine {
    /** On the link thread: an open connection to [pairing] that proved itself, if any. */
    fun proven(pairing: String): LiveConn?
    /** Off the link thread: opens a connection to [pairing] by a carrier its mode allows (never push); throws when none can. */
    fun dial(pairing: String)
    fun post(block: () -> Unit)
    fun io(block: () -> Unit)
    fun now(): Long
}

class LinkTransfers(
    private val st: LinkStateStore,
    private val dirs: TransferDirs,
    private val inbox: PublicWrap,
    private val log: LinkLog,
    private val random: SecureRandom = SecureRandom(),
    private val space: () -> Long = { dirs.filesDir.usableSpace },
) : DeskHandler {
    override val types = setOf(RequestType.FILE_OFFER, RequestType.FILE_ACCEPT, RequestType.FILE_KEY, RequestType.FILE_DECLINE, RequestType.FILE_DONE, RequestType.FILE_ASK)

    private class Active(val rec: TransferRecord, val input: XferIn) {
        @Volatile var strikes = 0
        @Volatile var dead = false
        @Volatile var lastChunk = 0L
    }

    private var line: XferLine? = null
    private var deskOut: DeskOut? = null
    private var tell: (DeskNotice) -> Unit = {}
    /** Coming in, with their keys, by pairing and the peer's id. */
    private val active = ConcurrentHashMap<String, Active>()
    private val streaming = HashSet<String>()
    private val resumes = HashMap<String, FileResumes>()
    private val opening = HashSet<String>()
    private val asked = HashMap<String, Long>()

    /** On the link thread, once, before anything else: what a cut run left is picked up, what no record claims goes. */
    fun attach(l: XferLine, o: DeskOut, notice: (DeskNotice) -> Unit) {
        line = l; deskOut = o; tell = notice
        val ids = st.transfers.keys
        for (d in listOf(dirs.parts, dirs.outgoing)) d.listFiles().orEmpty().forEach { f -> if (f.name.substringBefore('.') !in ids || f.name.endsWith(".arr")) f.delete() }
        for (t in st.transfers.values.toList()) when (t.state) {
            XferState.RECEIVING -> open(t)?.let { a -> if (a.input.whole) finish(a) }
            else -> {}
        }
    }

    private fun slot(pairing: String, file: String) = "$pairing/$file"
    private fun nowS(): Long = line?.now() ?: (System.currentTimeMillis() / 1000)
    private fun find(pairing: String, file: String, out: Boolean) = st.transfers.values.firstOrNull { it.pairing == pairing && it.out == out && it.offer.file == file }
    private fun save(t: TransferRecord) { try { st.put(t) } catch (_: app.heyrobot.core.link.LinkStateException) { log.add(LinkEvent.DROPPED, 0, LinkProblem.NOT_HERE.code) } }

    private fun open(t: TransferRecord): Active? {
        val key = t.key ?: return null
        return try {
            Active(t, XferIn(dirs.part(t.id), t.offer.size, t.offer.file, key)).also { active[slot(t.pairing, t.offer.file)] = it }
        } catch (_: java.io.IOException) { null }
    }

    private fun end(t: TransferRecord, state: XferState) {
        t.state = state
        t.done = nowS()
        t.key = null
        active.remove(slot(t.pairing, t.offer.file))?.dead = true
        dirs.part(t.id).delete(); dirs.sealing(t.id).delete(); dirs.copy(t.id).delete()
        save(t)
    }

    // The desk ---------------------------------------------------------------------------------------------------------------

    private fun bytesToday(pairing: String): SlidingSum {
        val sum = SlidingSum(LinkLimits.DAY, LinkLimits.FILE_BYTES_PER_DAY)
        for (t in st.transfers.values.sortedBy { it.at }) if (!t.out && t.pairing == pairing && t.state != XferState.DECLINED) sum.add(t.at, t.offer.size)
        return sum
    }

    /** Room for it: its chunks and then its format 2 copy, side by side, with some to spare. */
    private fun roomFor(size: Long): Boolean = try { space() > 2 * size + SPARE } catch (_: RuntimeException) { false }

    private fun openIn(pairing: String) = st.transfers.values.count { !it.out && it.pairing == pairing && !it.state.final }

    private fun accept(pairing: String, offer: FileOffer, alone: Boolean, out: DeskOut): Boolean {
        val p = st.pairings[pairing] ?: return false
        if (find(pairing, offer.file, false) != null) return false
        if (offer.size > RequestCaps.FILE_MAX_BYTES || !FileOffer.nameOk(offer.name)) return false
        val now = nowS()
        if (!bytesToday(pairing).room(now, offer.size) || !roomFor(offer.size) || openIn(pairing) >= MAX_OPEN) return false
        val t = TransferRecord(LinkIds.newId(), pairing, false, XferState.ACCEPTED, offer, p.mode.code, now, now + KEEP_S, 0, alone, null)
        save(t)
        if (out.send(pairing, FileAccept(offer.file)) != null) return true
        // Not sent: nothing of it stays, so your next tap can try again (a kept record would read as already accepted).
        try { st.deleteTransfer(t.id) } catch (_: app.heyrobot.core.link.LinkStateException) {}
        return false
    }

    /**
     * L11: a file for a shared folder you joined (SharedFolders has checked the folder's own rules). On its own, so the day's limit,
     * the room and everything after the accept are the same as any file's: nothing readable until its hash passes, sealed, marked as
     * no one's tap. False when it can't come in now.
     */
    fun acceptForFolder(pairing: String, offer: FileOffer, out: DeskOut): Boolean = accept(pairing, offer, true, out)

    /** L11: what's still on its way for a line that left a folder stops. It stays counted in the day's bytes. */
    fun cancelIn(pairing: String, file: String, out: DeskOut) {
        val t = find(pairing, file, false)?.takeIf { !it.state.final && it.state != XferState.ARRIVED } ?: return
        end(t, XferState.FAILED)
        out.send(pairing, FileDecline(file))
    }

    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? = when (val b = r.body) {
        is FileOffer -> if (LinkPolicy.fileAlone(b.size, b.kind, bytesToday(pairing), nowS()) && accept(pairing, b, true, out)) DeskDone() else null
        is FileAsk -> null // D17: only from what you shared with that person, and shared folders aren't here yet: always your tap.
        else -> null
    }

    override fun yes(r: LinkRequest): DeskYes? = when (r.body) {
        is FileOffer -> DeskYes.ACCEPT
        is FileAsk -> DeskYes.SEND_FILE
        else -> null
    }

    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? = when (val b = r.body) {
        // No room or over a limit: null, so your tap says it didn't work and the card stays for you to decline or try again.
        is FileOffer -> if (accept(pairing, b, false, out)) DeskDone() else null
        is FileAsk -> DeskDone()
        else -> null
    }

    override fun declined(pairing: String, r: LinkRequest, out: DeskOut) {
        (r.body as? FileOffer)?.let { out.send(pairing, FileDecline(it.file)) }
    }

    override fun stale(pairing: String, r: LinkRequest): Boolean = (r.body as? FileOffer)?.let { find(pairing, it.file, false) != null } == true

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState {
        when (val b = r.body) {
            is FileAccept -> onAccept(pairing, r.id, b, out)
            is FileKey -> onKey(pairing, b, out)
            is FileDecline -> find(pairing, b.file, true)?.takeIf { !it.state.final && it.state != XferState.SENDING }?.let { end(it, XferState.DECLINED) }
            is FileDone -> find(pairing, b.file, true)?.takeIf { it.state == XferState.SENDING }?.let { t ->
                if (b.ok && b.sha256 == t.offer.sha256) end(t, XferState.SENT) else { end(t, XferState.FAILED); tell(DeskNotice(DeskNoticeKind.FILE_FAILED, pairing, 1)) }
            }
            else -> {}
        }
        return AckState.RECEIVED
    }

    private fun onKey(pairing: String, b: FileKey, out: DeskOut) {
        // An offer you never accepted gets nothing: no record, so no key is kept and no chunk can ever count.
        val t = find(pairing, b.file, false) ?: return
        if (t.state != XferState.ACCEPTED) return
        t.key = b.key
        t.state = XferState.RECEIVING
        save(t)
        val a = open(t) ?: return
        if (a.input.whole) finish(a) else out.send(pairing, FileAccept(t.offer.file, a.input.good))
    }

    private fun onAccept(pairing: String, re: String, b: FileAccept, out: DeskOut) {
        val t = find(pairing, b.file, true) ?: return
        val from = b.from
        when {
            t.state.final || t.state == XferState.ARRIVED -> return
            from == null -> {
                // L16: one accept per offer: the key goes out once, on a queue that keeps trying.
                if (t.state != XferState.OFFERED) return
                val key = t.key ?: return
                if (out.send(pairing, FileKey(t.offer.file, key)) == null) return
                if (t.state == XferState.OFFERED) { t.state = XferState.SENDING; save(t) }
            }
            t.state != XferState.SENDING -> return
            // A resume past the end: nothing is read, nothing is sent.
            from >= XferSeal.chunks(t.offer.size) -> log.add(LinkEvent.DROPPED, 0, LinkProblem.BAD_KIND.code)
            // L16: a resume that gets further is what a bad connection does; one that keeps asking again from the same place isn't.
            !resumes.getOrPut(t.id) { FileResumes(XferSeal.chunks(t.offer.size)) }.allow(from) -> log.add(LinkEvent.DROPPED, 0, LinkProblem.BAD_KIND.code)
            else -> stream(t, from, re, out)
        }
    }

    // Sending ----------------------------------------------------------------------------------------------------------------

    private fun stream(t: TransferRecord, from: Long, re: String, out: DeskOut) {
        val l = line ?: return
        if (t.id in streaming) return
        val c = l.proven(t.pairing)
        if (c == null) {
            // None that proved itself. Open one; an ack on it proves it to the other side, whose next "send from" proves it here.
            if (!opening.add(t.pairing)) return
            l.io {
                try { l.dial(t.pairing) } catch (_: Exception) {}
                l.post { opening.remove(t.pairing); out.ack(t.pairing, re, AckState.RECEIVED) }
            }
            return
        }
        streaming += t.id
        val limit = resumes[t.id]
        l.io {
            try { sendChunks(t, from, c, limit) } catch (_: Exception) {}
            l.post { streaming.remove(t.id) }
        }
    }

    private fun sendChunks(t: TransferRecord, from: Long, c: LiveConn, limit: FileResumes?) {
        val size = t.offer.size
        val copy = dirs.copy(t.id)
        if (copy.length() != XferSeal.sealedSize(size)) return
        c.chunk(XferSeal.start(t.offer.file, from))
        RandomAccessFile(copy, "r").use { f ->
            f.seek(XferSeal.offset(from))
            var i = from
            val total = XferSeal.chunks(size)
            while (i < total) {
                val b = ByteArray(XferSeal.chunkLength(size, i))
                f.readFully(b)
                c.chunk(b)
                limit?.sent(i)
                i++
            }
        }
    }

    /**
     * On the link thread: main's copy, in files/link/outgoing/[id].xfr, offered to [pairing] under transfer key [key] (which
     * goes only after an acceptance). False when the copy isn't exactly what the offer says, or the offer couldn't be queued;
     * then nothing of it stays.
     */
    fun offer(id: String, pairing: String, offer: FileOffer, key: ByteArray, ttl: Long): Boolean {
        val o = deskOut ?: return false
        val p = st.pairings[pairing]
        val copy = dirs.copy(id)
        val fits = p != null && LinkIds.isId(id) && key.size == 32 && copy.length() == XferSeal.sealedSize(offer.size) && st.transfers[id] == null &&
            find(pairing, offer.file, true) == null
        val sent = fits && o.send(pairing, offer, ttl) != null
        if (!sent) { copy.delete(); return false }
        val now = nowS()
        save(TransferRecord(id, pairing, true, XferState.OFFERED, offer, p!!.mode.code, now, now + ttl, 0, false, key))
        return true
    }

    // Coming in --------------------------------------------------------------------------------------------------------------

    /**
     * A transfer-chunk envelope's body on connection [c] of [pairing], on that connection's own thread. False: the connection
     * goes. A chunk's tag is its proof: only the Robot this transfer's key went to can make one; a connection that hasn't
     * proved itself can't spoil a transfer with bad ones, only lose itself.
     */
    fun chunk(c: LiveConn, pairing: String, bytes: ByteArray): Boolean {
        val l = line ?: return false
        val cur = c.xfer as? Active
        if (cur == null) {
            val (file, from) = XferSeal.readStart(bytes) ?: return false
            val a = active[slot(pairing, file)] ?: return false
            if (synchronized(a) { a.dead || !a.input.canStart(from) }) return false
            c.xfer = a
            return true
        }
        val step = synchronized(cur) { if (cur.dead) XferStep.IO else cur.input.take(bytes) }
        when (step) {
            XferStep.MORE -> { cur.lastChunk = l.now(); return true }
            XferStep.WHOLE -> { c.xfer = null; l.post { finish(cur) }; return true }
            XferStep.LIE -> {
                c.xfer = null
                // F117: its length is checked before its tag, so an unproven connection may be anyone: it loses itself, never the transfer.
                if (c.proven) l.post { failed(cur) }
                return false
            }
            XferStep.BAD_TAG -> {
                c.xfer = null
                if (c.proven && ++cur.strikes >= MAX_STRIKES) l.post { failed(cur) }
                return false
            }
            XferStep.IO -> { c.xfer = null; return false }
        }
    }

    private fun failed(a: Active) {
        val t = a.rec
        if (t.state.final) return
        a.dead = true
        log.add(LinkEvent.DROPPED, 0, LinkProblem.BAD_KIND.code)
        end(t, XferState.FAILED)
        deskOut?.send(t.pairing, FileDone(t.offer.file, false))
        tell(DeskNotice(DeskNoticeKind.FILE_FAILED, t.pairing, 1))
    }

    /** All chunks are in: read through off the link thread; only a size and hash that match leave a file for Files. */
    private fun finish(a: Active) {
        val l = line ?: return
        val t = a.rec
        val key = t.key ?: return
        val id = FileId.of(t.id) ?: return
        l.io {
            var end = XferArrival.seal(dirs.part(t.id), dirs.sealing(t.id), t.offer.file, key, t.offer.size, t.offer.sha256, id, inbox, random)
            if (end == XferEnd.OK) {
                val moved = try {
                    withFileLock(dirs.lock) { dirs.arrived.mkdirs(); dirs.sealing(t.id).renameTo(dirs.arrivedFile(t.id)) }
                } catch (_: Exception) { false }
                if (!moved) end = XferEnd.IO
            }
            l.post { settled(a, end) }
        }
    }

    private fun settled(a: Active, end: XferEnd) {
        val t = a.rec
        if (t.state != XferState.RECEIVING) { dirs.arrivedFile(t.id).delete(); return }
        if (end != XferEnd.OK) return failed(a)
        active.remove(slot(t.pairing, t.offer.file))
        a.dead = true
        t.state = XferState.ARRIVED
        t.key = null
        t.done = nowS()
        dirs.part(t.id).delete()
        save(t)
        deskOut?.send(t.pairing, FileDone(t.offer.file, true, t.offer.sha256))
        tell(DeskNotice(DeskNoticeKind.FILES, t.pairing, 1))
    }

    /** A connection to [pairing] proved itself: what stalled asks again now. */
    fun linkUp(pairing: String) = resume({ it == pairing }, 0)

    /** On the link thread, at each sweep: anything past its time goes, finished ones after 30 days, stalled ones ask again. */
    fun sweep() {
        val now = nowS()
        for (t in st.transfers.values.toList()) when {
            t.state.final -> {}
            t.state == XferState.ARRIVED -> {}
            now > t.expires -> {
                end(t, if (t.state == XferState.OFFERED) XferState.EXPIRED else XferState.FAILED)
            }
        }
        // §2.5's "keeps the sent and received history": the newest stay, whatever's old; a day's stay for the day's limit.
        for (t in st.transfers.values.filter { it.state.final && now > it.done + LinkLimits.DAY }.sortedByDescending { it.done }.drop(HISTORY)) st.deleteTransfer(t.id)
        resume({ true }, RESUME_S)
    }

    private fun resume(which: (String) -> Boolean, after: Long) {
        val out = deskOut ?: return
        val now = nowS()
        for (a in active.values) {
            val t = a.rec
            if (t.state != XferState.RECEIVING || !which(t.pairing) || a.dead || a.input.whole) continue
            if (now - (asked[t.id] ?: 0) < after || now - a.lastChunk < after) continue
            asked[t.id] = now
            out.send(t.pairing, FileAccept(t.offer.file, a.input.good))
        }
    }

    /** The pairing is gone (unpaired, blocked, ended): nothing of its transfers stays, arrivals not yet filed included. */
    fun forget(pairing: String) {
        // Fail closed: what hadn't finished ends with its keys and every copy, a file not yet filed too. The history stays (unpairing keeps it).
        for (t in st.transfers.values.toList()) if (t.pairing == pairing && !t.state.final) {
            dropArrived(t)
            end(t, XferState.FAILED)
        }
    }

    private fun dropArrived(t: TransferRecord) {
        try { withFileLock(dirs.lock) { dirs.arrivedFile(t.id).delete() } } catch (_: Exception) {}
    }

    /** Every transfer, for main (never a key or a path). */
    fun views(): List<TransferView> = st.transfers.values.sortedBy { it.at }.map { it.view() }

    /** Files took [id] ([ok]) or refused it: either way its sealed copy goes. */
    fun filed(id: String, ok: Boolean): Boolean {
        val t = st.transfers[id]?.takeIf { !it.out && it.state == XferState.ARRIVED } ?: return false
        dropArrived(t)
        t.state = if (ok) XferState.FILED else XferState.FAILED
        t.done = nowS()
        save(t)
        return true
    }

    companion object {
        /** A transfer accepted has this long to come (the longest a request may wait by default). */
        const val KEEP_S = 7 * LinkLimits.DAY
        const val MAX_OPEN = 16
        const val MAX_STRIKES = 3
        /** Sent and received kept, past the last day's. */
        const val HISTORY = 200
        const val RESUME_S = 300L
        private const val SPARE = 16L * 1024 * 1024
    }
}
