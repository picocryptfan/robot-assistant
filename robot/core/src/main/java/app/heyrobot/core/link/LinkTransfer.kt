// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileSeal
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.OfferFields
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.seal.SealException
import app.heyrobot.core.seal.SealReader
import app.heyrobot.core.seal.SealStream
import app.heyrobot.core.seal.SealWriter
import app.heyrobot.core.seal.StreamNonce
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.6, §3.7; job L8): a file's way between two Robots.
//
//   ROBOTXFR  8 magic, 2 format (1), 16 the offer's id: 26 bytes, every chunk's additional data.
//   a stream  on one live connection: a start frame (the header and the first chunk's number, 4 bytes), then its chunks in
//             order, each one transfer-chunk envelope holding exactly one sealed chunk (core SealStream: STREAM, 64 KiB).
//
// The sender's copy is sealed once, whole, in the main process: :link never holds its plain bytes when sending. The receiver
// knows from the offer how long every chunk must be and which is the last, so a peer that lies about the size is caught at
// the first chunk that doesn't fit, and keeps only an authentic prefix (chunks past their tag check, in order), which is what
// a cut transfer resumes from. Those chunks stay sealed under the transfer's key and in :link's own folder. Only when all
// of them are in is the plain stream read through: its size and SHA-256 must be the offer's, and only then is it sealed as
// format 2 (FileSeal, robot_inbox_v1) and moved into incoming/link/ under files.lock. Nothing else is ever written there,
// and no name a peer sent ever reaches a path: every path uses an id this Robot chose.

object XferSeal {
    val MAGIC: ByteArray = "ROBOTXFR".toByteArray(Charsets.US_ASCII) // l10n: not text (the file's magic bytes)
    const val FORMAT = 1
    const val ID_BYTES = 16
    const val KEY_BYTES = 32
    const val BUFFER = 64 * 1024
    const val HEADER = 26
    /** A start frame: the header and the number of the first chunk that follows it. */
    const val START = HEADER + 4

    /** The header of offer [file] (32 hex). */
    fun header(file: String): ByteArray {
        val id = LinkBytes.unhex(file, ID_BYTES) ?: throw IllegalArgumentException()
        return ByteBuffer.allocate(HEADER).put(MAGIC).putShort(FORMAT.toShort()).put(id).array()
    }

    fun chunks(plain: Long): Long = SealStream.chunks(plain)

    /** The bytes a sealed copy of [plain] bytes takes on disk: header and chunks. */
    fun sealedSize(plain: Long): Long = HEADER + plain + chunks(plain) * SealStream.TAG

    /** Exactly how long chunk [index] of a file of [plain] bytes is, sealed; -1 when it has no such chunk. */
    fun chunkLength(plain: Long, index: Long): Int {
        val n = chunks(plain)
        if (index < 0 || index >= n) return -1
        if (index < n - 1) return SealStream.SEALED
        return (plain - (n - 1) * SealStream.CHUNK).toInt() + SealStream.TAG
    }

    /** Where chunk [index] starts in the sender's copy. */
    fun offset(index: Long): Long = HEADER + index * SealStream.SEALED

    /** A sealed copy on [out]: the header, then what the caller writes; closing seals the last chunk. */
    fun writer(out: OutputStream, file: String, key: ByteArray): OutputStream {
        val h = header(file)
        out.write(h)
        return SealWriter(out, h, key, maxChunks = chunks(RequestCaps.FILE_MAX_BYTES))
    }

    fun start(file: String, from: Long): ByteArray {
        require(from in 0..Int.MAX_VALUE)
        return ByteBuffer.allocate(START).put(header(file)).putInt(from.toInt()).array()
    }

    /** A start frame's offer id and first chunk, or null for anything else. */
    fun readStart(b: ByteArray): Pair<String, Long>? {
        if (b.size != START) return null
        val x = ByteBuffer.wrap(b)
        val m = ByteArray(MAGIC.size).also { x.get(it) }
        if (!m.contentEquals(MAGIC) || x.short.toInt() != FORMAT) return null
        val id = ByteArray(ID_BYTES).also { x.get(it) }
        val from = x.int.toLong() and 0xFFFFFFFFL
        if (LinkBytes.allZero(id)) return null
        return LinkBytes.hex(id) to from
    }
}

/** What one chunk did. */
enum class XferStep {
    /** Kept: more to come. */
    MORE,
    /** Kept, and it was the last. */
    WHOLE,
    /** It can't be this file's next chunk (its length is wrong for the offered size, or the file is whole already): the peer lied. */
    LIE,
    /** Its tag doesn't check: not kept. The prefix stands; the connection goes. */
    BAD_TAG,
    /** It couldn't be written. */
    IO,
}

/**
 * The chunks of one transfer arriving in [part], an authentic prefix at all times. [good]: how many it holds, counted from its
 * length (a part cut in the middle of a chunk is cut back). The key is held for as long as this is open.
 */
class XferIn(private val part: File, private val size: Long, file: String, key: ByteArray) {
    private val aad = XferSeal.header(file)
    private val spec = run { require(key.size == SealStream.KEY_BYTES); SecretKeySpec(key.copyOf(), "AES") }
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val total = XferSeal.chunks(size)
    private val plain = ByteArray(SealStream.CHUNK)

    var good: Long = 0
        private set

    init {
        part.parentFile?.mkdirs()
        var bytes = 0L
        val len = part.length()
        while (good < total) {
            val n = XferSeal.chunkLength(size, good).toLong()
            if (bytes + n > len) break
            bytes += n
            good++
        }
        if (part.exists() && len != bytes) RandomAccessFile(part, "rw").use { it.setLength(bytes) }
    }

    val whole: Boolean get() = good >= total

    /** A start frame's [from] fits: it must be exactly the next chunk this one needs, never past the end. */
    fun canStart(from: Long): Boolean = !whole && from == good

    fun take(sealed: ByteArray): XferStep {
        if (whole) return XferStep.LIE
        if (sealed.size != XferSeal.chunkLength(size, good)) return XferStep.LIE
        try {
            cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(SealStream.TAG * 8, StreamNonce.of(good, good == total - 1)))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, 0, sealed.size, plain, 0)
        } catch (_: java.security.GeneralSecurityException) {
            return XferStep.BAD_TAG
        } finally {
            plain.fill(0)
        }
        try {
            FileOutputStream(part, true).use { it.write(sealed) }
        } catch (_: IOException) {
            return XferStep.IO
        }
        good++
        return if (whole) XferStep.WHOLE else XferStep.MORE
    }
}

/** What reading a whole transfer through found. Only [OK] leaves anything behind. */
enum class XferEnd { OK, WRONG_SIZE, WRONG_HASH, DAMAGED, IO }

object XferArrival {
    /**
     * Reads the chunks in [part] through, under the transfer's [key], as format 2 for file [id] in [temp]. [temp] is deleted
     * unless the plain bytes are exactly [size] long with SHA-256 [sha256]. Never touches anything else; [part] stays.
     */
    fun seal(part: File, temp: File, file: String, key: ByteArray, size: Long, sha256: String, id: FileId, inbox: PublicWrap, random: SecureRandom): XferEnd {
        val end = try {
            read(part, temp, file, key, size, sha256, id, inbox, random)
        } catch (_: SealException) {
            XferEnd.DAMAGED
        } catch (_: app.heyrobot.core.files.FileException) {
            XferEnd.IO
        } catch (_: IOException) {
            XferEnd.IO
        }
        if (end != XferEnd.OK) temp.delete()
        return end
    }

    private fun read(part: File, temp: File, file: String, key: ByteArray, size: Long, sha256: String, id: FileId, inbox: PublicWrap, random: SecureRandom): XferEnd {
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(SealStream.CHUNK)
        var n = 0L
        temp.parentFile?.mkdirs()
        FileOutputStream(temp).use { raw ->
            val w = FileSeal.arrivedWriter(object : OutputStream() { // stays open for the sync below
                override fun write(b: Int) = raw.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)
            }, id, inbox, random)
            SealReader(FileInputStream(part), XferSeal.header(file), key, maxChunks = XferSeal.chunks(RequestCaps.FILE_MAX_BYTES), maxBytes = RequestCaps.FILE_MAX_BYTES).use { r ->
                while (true) {
                    val k = r.read(buf, 0, buf.size)
                    if (k < 0) break
                    n += k
                    if (n > size) return XferEnd.WRONG_SIZE
                    sha.update(buf, 0, k)
                    w.write(buf, 0, k)
                }
            }
            if (n != size) return XferEnd.WRONG_SIZE
            if (!LinkBytes.same(sha.digest(), LinkBytes.unhex(sha256, 32) ?: return XferEnd.WRONG_HASH)) return XferEnd.WRONG_HASH
            w.close()
            raw.fd.sync()
        }
        return XferEnd.OK
    }
}

/** Where a transfer stands. Final states never change. */
enum class XferState(val code: Int, val final: Boolean = false) {
    /** Yours, offered, not yet accepted. */
    OFFERED(1),
    /** Yours, accepted: its key went; its chunks go when asked for. */
    SENDING(2),
    /** Yours: their Robot has it, hash checked. */
    SENT(3, true),
    /** Yours or theirs: declined, or an offer never accepted that ran out. */
    DECLINED(4, true),
    EXPIRED(5, true),
    /** Theirs: you accepted, its key hasn't come. */
    ACCEPTED(6),
    /** Theirs: the key is here, chunks come. */
    RECEIVING(7),
    /** Theirs: whole, hash checked, sealed in incoming/link/, waiting for Files to take it at an unlock. */
    ARRIVED(8),
    /** Theirs: in Robot Files. */
    FILED(9, true),
    /** Either way: it failed (a wrong hash or size, a peer that lied), nothing of it is kept. */
    FAILED(10, true);

    companion object {
        fun of(code: Int): XferState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One transfer as main may see it: no key, no path. [id]: this Robot's own id for it (for a file coming in, also the file's id
 * in incoming/link/). [offer]: as the sender wrote it, so everything in it is another Robot's words.
 */
class TransferView(
    val id: String, val pairing: String, val out: Boolean, val state: XferState, val offer: FileOffer,
    val mode: Int, val at: Long, val done: Long, val alone: Boolean,
) {
    override fun toString(): String = "TransferView($state)" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX = 2_000
        private const val MAX_TEXT = 1_024
        private fun DataOutputStream.text(s: String?) {
            val b = (s ?: "").toByteArray(Charsets.UTF_8)
            require(b.size <= MAX_TEXT)
            writeShort(b.size); write(b)
        }

        private fun DataInputStream.text(): String {
            val n = readUnsignedShort()
            if (n > MAX_TEXT) throw IOException()
            return String(ByteArray(n).also(::readFully), Charsets.UTF_8)
        }

        fun encodeAll(all: List<TransferView>): ByteArray {
            val b = ByteArrayOutputStream()
            DataOutputStream(b).run {
                writeInt(all.size.coerceAtMost(MAX))
                for (v in all.take(MAX)) {
                    text(v.id); text(v.pairing); writeBoolean(v.out); writeByte(v.state.code); writeByte(v.mode); writeLong(v.at); writeLong(v.done); writeBoolean(v.alone)
                    val o = v.offer
                    text(o.file); text(o.name); text(o.kind.wire); writeLong(o.size); text(o.sha256); text(o.why.wire); text(o.re)
                    writeBoolean(o.fields != null)
                    o.fields?.let { f -> text(f.merchant); text(f.title); text(f.date); writeBoolean(f.total != null); writeLong(f.total ?: 0); text(f.currency); text(f.due) }
                }
            }
            return b.toByteArray()
        }

        /** Read back, every offer through the same checks as on the wire; what doesn't read is left out. Never throws. */
        fun decodeAll(bytes: ByteArray): List<TransferView> = try {
            DataInputStream(ByteArrayInputStream(bytes)).run {
                val n = readInt()
                if (n !in 0..MAX) return emptyList()
                (0 until n).mapNotNull { _ ->
                    val id = text(); val pairing = text(); val out = readBoolean(); val state = XferState.of(readByte().toInt()); val mode = readByte().toInt()
                    val at = readLong(); val done = readLong(); val alone = readBoolean()
                    val file = text(); val name = text(); val kind = OfferKind.of(text()); val size = readLong(); val sha = text(); val why = OfferWhy.of(text()); val re = text()
                    val fields = if (readBoolean()) {
                        val a = text(); val t = text(); val d = text(); val has = readBoolean(); val total = readLong(); val cur = text(); val due = text()
                        try { OfferFields(a.ifEmpty { null }, t.ifEmpty { null }, d.ifEmpty { null }, total.takeIf { has }, cur.ifEmpty { null }, due.ifEmpty { null }) } catch (_: IllegalArgumentException) { null }
                    } else null
                    if (state == null || kind == null || why == null || !LinkIds.isId(id) || !LinkIds.isId(pairing) || !LinkIds.isId(file) || !FileOffer.nameOk(name) ||
                        size !in 1..RequestCaps.FILE_MAX_BYTES || LinkBytes.unhex(sha, 32) == null) null
                    else TransferView(id, pairing, out, state, FileOffer(file, name, kind, size, sha, why, re.ifEmpty { null }, fields), mode, at, done, alone)
                }
            }
        } catch (_: IOException) {
            emptyList()
        }
    }
}

/** L8: where a transfer's files are, for the processes on each side of them: the link folder's outgoing/, Files' incoming/link/. */
object XferPaths {
    /** Robot Files' folder, from the app's files folder. */
    fun filesDir(appFiles: File) = File(appFiles, "files") // l10n: not text (a folder's name)
    /** :link's folder (LinkVault's), from the app's files folder. */
    fun linkDir(appFiles: File) = File(appFiles, "link") // l10n: not text (a folder's name)
    fun outgoing(linkDir: File) = File(linkDir, "outgoing") // l10n: not text (a folder's name)
    fun outgoing(linkDir: File, id: String) = File(outgoing(linkDir), "$id.xfr") // l10n: not text (a file's name)
    fun arrived(filesDir: File) = File(File(filesDir, FileStore.INCOMING), FileStore.INCOMING_LINK)
    fun arrived(filesDir: File, id: String) = File(arrived(filesDir), "$id${FileStore.SEALED_EXT}")
}
