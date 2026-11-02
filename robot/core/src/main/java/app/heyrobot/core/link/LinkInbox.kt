// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.seal.Envelope
import app.heyrobot.core.seal.EnvelopeException
import app.heyrobot.core.seal.PublicWrap
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom

// Pure java.io. 1.5, Robot to Robot (robot-to-robot-plan.md §3.6 and §4.4, Q3's default, job L2): the waiting inbox.
// A request that arrives while the phone is locked and must wait for you (a card, anything not answered on its own) is
// sealed here by :link with robot_inbox_v1's public key, which works while locked; opening it needs the phone unlocked
// (robot_inbox_v1 requires it), so a locked phone holds what waits only as ciphertext it can't read. After the unlock :link
// opens each one (one Keystore RSA decryption), hands it on, and deletes it.
//
//   files/link/inbox/<id>.rl     core/seal/Envelope.kt's format 2, magic "ROBOTLNK", purpose 6 (WAITING), wrap type 2;
//                                the id is the item's own (random), and the plaintext:
//       "RLW" 1 | pairing id 16 | received at (Unix seconds) u64 | request: u32 length, then its bytes (at most 32 KiB,
//       LinkRequests.LIVE: the session's plaintext as it arrived, decoded later in :linkparse, never here)
//   files/link/inbox/.<id>.rl.tmp   a write under way
//
// At most [LinkInbox.MAX] items (EST); past that a request is answered `busy` by its caller and nothing is written. The
// pairing id travels inside the sealed bytes, so nothing on storage says who sent what while the phone is locked.

/** One request that waited for you, as opened after the unlock. */
class Waiting(val id: String, val pairing: String, val receivedAt: Long, request: ByteArray) {
    private val bytes = request.copyOf()
    val request: ByteArray get() = bytes.copyOf()

    override fun toString(): String = "Waiting" // l10n: not text (toString for logs and tests)
}

/**
 * The waiting inbox in [dir] (files/link/inbox), sealed by [keys] (robot_inbox_v1). Every call may throw
 * [LinkStateException]. One instance per process (:link); its calls take turns.
 */
class LinkInbox(val dir: File, private val keys: PublicWrap, private val random: SecureRandom = SecureRandom()) {
    private val lock = Any()

    /** The waiting items' ids, oldest first. */
    fun ids(): List<String> = synchronized(lock) {
        dir.listFiles().orEmpty().filter { it.isFile && idOf(it.name) != null }
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name })).map { idOf(it.name)!! }
    }

    fun count(): Int = ids().size

    /**
     * Seals [request] from [pairing], received at [at] (Unix seconds): works while the phone is locked. Its new id, or
     * [LinkStateProblem.FULL] when [MAX] items wait, [LinkStateProblem.TOO_BIG] over [MAX_REQUEST] bytes.
     */
    fun put(pairing: String, at: Long, request: ByteArray): String = synchronized(lock) {
        val p = LinkBytes.unhex(pairing, LinkIds.BYTES)
        require(p != null && !LinkBytes.allZero(p) && at >= 0)
        if (request.size > MAX_REQUEST) throw LinkStateException(LinkStateProblem.TOO_BIG)
        if (count() >= MAX) throw LinkStateException(LinkStateProblem.FULL)
        dir.mkdirs()
        val id = LinkIds.newId()
        val f = File(dir, "$id${LinkRecords.EXT}") // l10n: not text (a file name)
        val tmp = File(dir, ".${f.name}.tmp") // l10n: not text (a file name)
        val plain = ByteBuffer.allocate(HEAD + request.size).apply {
            put(MAGIC)
            put(p)
            putLong(at)
            putInt(request.size)
            put(request)
        }.array()
        try {
            val out = SyncedOut(FileOutputStream(tmp))
            try {
                Envelope.writer(out, LinkSeal.KIND, LinkSeal.purpose(LinkPurpose.WAITING), LinkBytes.unhex(id, Envelope.ID)!!, public = keys, random = random)
                    .use { it.write(plain) }
            } catch (x: Exception) {
                try { out.close() } catch (_: IOException) {}
                throw x
            }
            if (!tmp.renameTo(f)) throw IOException()
            LinkRecords.syncDir(dir)
            return id
        } catch (x: Exception) {
            LinkRecords.destroy(tmp)
            throw when (x) {
                is EnvelopeException -> LinkStateException(LinkRecords.problemOf(x.problem))
                is LinkStateException -> x
                else -> LinkStateException(LinkStateProblem.IO)
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Item [id], opened: needs the phone unlocked ([LinkStateProblem.LOCKED] otherwise). Null when there is no such item. */
    fun open(id: String): Waiting? = synchronized(lock) {
        val b = LinkBytes.unhex(id, LinkIds.BYTES) ?: return null
        val f = File(dir, "$id${LinkRecords.EXT}") // l10n: not text (a file name)
        if (!f.isFile) return null
        val plain = try {
            Envelope.reader(BufferedInputStream(FileInputStream(f)), LinkSeal.KIND, LinkSeal.purpose(LinkPurpose.WAITING), b, public = keys)
                .use { it.readAll(LinkPurpose.WAITING.maxBytes.toInt()) }
        } catch (x: EnvelopeException) {
            // F5: a delete a crash cut short: as if it had finished.
            if (x.problem == app.heyrobot.core.seal.EnvelopeProblem.DAMAGED && LinkRecords.finishDelete(f)) { LinkRecords.syncDir(dir); return null }
            throw LinkStateException(LinkRecords.problemOf(x.problem))
        } catch (_: IOException) {
            throw LinkStateException(LinkStateProblem.IO)
        }
        try {
            val r = ByteBuffer.wrap(plain)
            if (plain.size < HEAD) throw LinkStateException(LinkStateProblem.DAMAGED)
            val magic = ByteArray(MAGIC.size).also { r.get(it) }
            if (!magic.contentEquals(MAGIC)) throw LinkStateException(LinkStateProblem.DAMAGED)
            val pairing = ByteArray(LinkIds.BYTES).also { r.get(it) }
            val at = r.long
            val n = r.int
            if (LinkBytes.allZero(pairing) || at < 0 || n < 0 || n > MAX_REQUEST || r.remaining() != n) throw LinkStateException(LinkStateProblem.DAMAGED)
            val request = ByteArray(n).also { r.get(it) }
            try {
                return Waiting(id, LinkBytes.hex(pairing), at, request)
            } finally {
                request.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Item [id] gone (handled, or Start over): its wrapped key overwritten first. */
    fun delete(id: String): Unit = synchronized(lock) {
        if (LinkBytes.unhex(id, LinkIds.BYTES) == null) return
        LinkRecords.destroy(File(dir, "$id${LinkRecords.EXT}")) // l10n: not text (a file name)
        LinkRecords.syncDir(dir)
    }

    /** Start over: every item, and anything else in the folder, destroyed. */
    fun deleteAll() = synchronized(lock) {
        dir.listFiles().orEmpty().filter { it.isFile }.forEach(LinkRecords::destroy)
        dir.delete()
    }

    /** Whether the folder holds anything at all: then robot_inbox_v1 is never made again. */
    fun anything(): Boolean = dir.listFiles().orEmpty().any { it.isFile }

    companion object {
        /** Items waiting at most (EST): the outbox's cap (§3.7), as many as could be sent to you. */
        const val MAX = 500
        /** A request's plaintext as a live connection carries it (§3.7). */
        const val MAX_REQUEST = app.heyrobot.core.link.requests.LinkRequests.LIVE
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'W'.code.toByte(), 1)
        private const val HEAD = 4 + LinkIds.BYTES + 8 + 4

        private fun idOf(name: String): String? =
            name.takeIf { it.endsWith(LinkRecords.EXT) }?.removeSuffix(LinkRecords.EXT)?.takeIf { LinkIds.isId(it) }
    }
}
