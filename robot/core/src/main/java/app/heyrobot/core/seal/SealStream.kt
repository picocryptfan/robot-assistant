// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.seal

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Pure (JCA). age's STREAM construction with the platform's AES-GCM (Cipher "AES/GCM/NoPadding", Conscrypt on the phone),
// nothing custom. Written for the backup (1.1, core/backup/BackupStream.kt, which now wraps these two classes) and shared
// with Robot Files (1.4, core/files/FileSeal.kt): one key per stream, which the caller makes and never reuses.
//
//   stream  = chunk_0 ‖ chunk_1 ‖ … ‖ chunk_n                 (what comes before it, a header, is the caller's)
//   chunk_i = AES-256-GCM(key, nonce_i, aad, plaintext_i) ‖ tag(16)
//   nonce_i = i as 11 bytes big-endian ‖ (0x01 if i = n else 0x00)
//   |plaintext_i| = 65,536 for i < n; 1 … 65,536 for the last. Empty only when the whole stream is (one last chunk 0 of
//   nothing but its tag), and only where the caller allows it: a backup is never empty, a file can be.
//
// A new Cipher.init per chunk. Each chunk's bytes are used only after its tag is checked, and the "last" flag in the
// nonce makes a cut stream detectable at any point: a stream that ends without a chunk that opens as the last is
// incomplete. The additional data (the caller's header) goes with every chunk, so none of it can be changed either.
// Memory stays flat whatever the size: one chunk in, one chunk out (the reader reads one chunk ahead).

object SealStream {
    /** Plaintext bytes in every chunk but the last (which holds 1 to [CHUNK], or 0 in an empty stream). */
    const val CHUNK = 65_536
    /** AES-GCM's tag, after each chunk's ciphertext. */
    const val TAG = 16
    /** A chunk as it is on disk: ciphertext and tag. */
    const val SEALED = CHUNK + TAG
    /** AES-256. */
    const val KEY_BYTES = 32
    /** The counter's limit (256 TiB of plaintext); callers set lower caps of their own. */
    const val MAX_CHUNKS = 1L shl 32

    /** How many chunks [plain] bytes make, and so the stream's size: [plain] + this × [TAG]. An empty stream is one chunk. */
    fun chunks(plain: Long): Long = if (plain <= 0) 1 else (plain + CHUNK - 1) / CHUNK
}

internal object StreamNonce {
    fun of(index: Long, last: Boolean): ByteArray {
        require(index in 0 until SealStream.MAX_CHUNKS)
        val n = ByteArray(12)
        var v = index
        for (k in 10 downTo 0) {
            n[k] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        n[11] = if (last) 1 else 0
        return n
    }
}

/** Why a stream can't be read (or written) further. Each caller says it in its own words (the backup: OpenProblem). */
enum class SealProblem {
    /** Chunk 0 doesn't open: the wrong key, or changed additional data (they can't be told apart). */
    FIRST_CHUNK,
    /** A later chunk doesn't open, chunks are out of order, or bytes follow the end. */
    CHANGED,
    /** The stream ends too early: cut at a chunk's end or inside one. */
    INCOMPLETE,
    /** More chunks or bytes than the caller allows. */
    TOO_BIG,
    /** A chunk that opens but holds nothing where the stream may not be empty. */
    EMPTY,
}

/** A stream that can't be read further; no message and no stack trace (nothing of the bytes or the key in one). */
class SealException(val problem: SealProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "SealException(${problem.name})"  // l10n: not text (toString for logs and tests)
}

/**
 * Encrypts everything written to it into chunks on [out] (nothing else: a header before them is the caller's to write).
 * [close] seals the last chunk and closes [out]. A stream closed with nothing written is refused unless [allowEmpty].
 * [key] (32 bytes) and [aad] are copied; the caller zeroes its own key. More than [maxChunks] chunks throws
 * [SealException] ([SealProblem.TOO_BIG]).
 */
class SealWriter(
    private val out: OutputStream,
    aad: ByteArray,
    key: ByteArray,
    private val allowEmpty: Boolean = false,
    private val maxChunks: Long = SealStream.MAX_CHUNKS,
) : OutputStream() {
    private val aad = aad.copyOf()
    private val spec = run {
        require(key.size == SealStream.KEY_BYTES)
        SecretKeySpec(key, "AES")
    }
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buf = ByteArray(SealStream.CHUNK)
    private val sealed = ByteArray(SealStream.SEALED)
    private var n = 0
    private var index = 0L
    private var closed = false
    /** Plaintext bytes written so far. */
    var written = 0L
        private set

    override fun write(b: Int) {
        check(!closed)
        if (n == SealStream.CHUNK) seal(last = false)
        buf[n++] = b.toByte()
        written++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check(!closed)
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        var o = off
        var left = len
        while (left > 0) {
            if (n == SealStream.CHUNK) seal(last = false)
            val k = minOf(left, SealStream.CHUNK - n)
            System.arraycopy(b, o, buf, n, k)
            n += k
            o += k
            left -= k
            written += k
        }
    }

    /** Nothing to flush: a chunk is written only when it is full (or the last). */
    override fun flush() {}

    override fun close() {
        if (closed) return
        try {
            // A full chunk is sealed only when more follows, so n is 0 here only when nothing was written at all.
            check(n > 0 || (allowEmpty && index == 0L)) { "empty" }
            seal(last = true)
            out.flush()
        } finally {
            closed = true
            buf.fill(0)
            out.close()
        }
    }

    private fun seal(last: Boolean) {
        if (index >= maxChunks) throw SealException(SealProblem.TOO_BIG)
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(SealStream.TAG * 8, StreamNonce.of(index, last)))
        cipher.updateAAD(aad)
        val len = cipher.doFinal(buf, 0, n, sealed, 0)
        out.write(sealed, 0, len)
        index++
        n = 0
    }
}

/**
 * Reads the plaintext of the chunks on [input] (positioned at chunk 0). Each chunk is released only once its tag is
 * checked, and only as the last one when nothing follows it; so what has been released is always an authentic prefix,
 * and the stream is whole only when a read returns -1. Problems throw [SealException], and a reader that failed stays
 * failed: chunk 0 not opening is [SealProblem.FIRST_CHUNK], a later one [SealProblem.CHANGED] (changed bytes, chunks
 * swapped, repeated or dropped, bytes after the end), a stream that ends without a chunk that opens as the last
 * [SealProblem.INCOMPLETE]. A cut inside the last chunk and a changed byte in it look the same from outside; both read as
 * incomplete. More than [maxChunks] chunks or [maxBytes] bytes is [SealProblem.TOO_BIG]; an empty chunk is
 * [SealProblem.EMPTY] unless [allowEmpty] and it is chunk 0 and the last. [key] and [aad] are copied; the caller zeroes its
 * own key. [close] closes [input].
 */
class SealReader(
    private val input: InputStream,
    aad: ByteArray,
    key: ByteArray,
    private val allowEmpty: Boolean = false,
    private val maxChunks: Long = SealStream.MAX_CHUNKS,
    private val maxBytes: Long = Long.MAX_VALUE,
) : InputStream() {
    private val aad = aad.copyOf()
    private val spec = run {
        require(key.size == SealStream.KEY_BYTES)
        SecretKeySpec(key, "AES")
    }
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var cur = ByteArray(SealStream.SEALED)
    private var curLen = 0
    private var ahead = ByteArray(SealStream.SEALED)
    private var aheadLen = -1
    private val plain = ByteArray(SealStream.CHUNK)
    private var pos = 0
    private var len = 0
    private var index = 0L
    private var opened = 0L
    private var ended = false
    private var failed: SealException? = null
    /** Chunks opened so far. */
    val chunks: Long get() = index
    /** Plaintext bytes released so far. */
    var released = 0L
        private set

    override fun read(): Int {
        if (pos >= len && !next()) return -1
        released++
        return plain[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, n: Int): Int {
        if (off < 0 || n < 0 || off + n > b.size) throw IndexOutOfBoundsException()
        if (n == 0) return 0
        if (pos >= len && !next()) return -1
        val k = minOf(n, len - pos)
        System.arraycopy(plain, pos, b, off, k)
        pos += k
        released += k
        return k
    }

    override fun available(): Int = len - pos

    override fun close() {
        plain.fill(0)
        len = 0
        pos = 0
        input.close()
    }

    /** Reads to the end, checking every chunk, and keeps nothing. Throws as [read] does. */
    fun drain(): Long {
        var total = 0L
        val skip = ByteArray(8192)
        while (true) {
            val k = read(skip)
            if (k < 0) return total
            total += k
        }
    }

    /** Opens the next chunk into [plain]; false at the end (after the last chunk). */
    private fun next(): Boolean {
        failed?.let { throw it }
        if (ended) return false
        try {
            if (index == 0L) {
                curLen = fill(cur)
            } else {
                val t = cur
                cur = ahead
                ahead = t
                curLen = aheadLen
            }
            if (index >= maxChunks) throw SealException(SealProblem.TOO_BIG)
            val last = if (curLen < SealStream.SEALED) true else fill(ahead).also { aheadLen = it } == 0
            len = open(last)
            pos = 0
            index++
            if (last) ended = true
            opened += len
            if (opened > maxBytes) throw SealException(SealProblem.TOO_BIG)
            if (len == 0) {
                // The one empty chunk a stream may hold: chunk 0 of an empty stream, where the caller allows one.
                if (allowEmpty && index == 1L && last) return false
                throw SealException(SealProblem.EMPTY)
            }
            return true
        } catch (x: SealException) {
            failed = x
            plain.fill(0)
            len = 0
            throw x
        }
    }

    private fun open(last: Boolean): Int {
        tryOpen(last)?.let { return it }
        val first = index == 0L
        val problem = if (!last) {
            // A full chunk with more after it. One that opens as the last means bytes were added after the end.
            when {
                tryOpen(true) != null -> SealProblem.CHANGED
                first -> SealProblem.FIRST_CHUNK
                else -> SealProblem.CHANGED
            }
        } else {
            when {
                curLen < SealStream.TAG + 1 -> SealProblem.INCOMPLETE
                // Cut right after a chunk that isn't the last.
                curLen == SealStream.SEALED && tryOpen(false) != null -> SealProblem.INCOMPLETE
                first -> SealProblem.FIRST_CHUNK
                curLen == SealStream.SEALED -> SealProblem.CHANGED
                // Shorter than a full chunk: cut inside one (or the last one changed, which can't be told apart).
                else -> SealProblem.INCOMPLETE
            }
        }
        plain.fill(0)
        throw SealException(problem)
    }

    /** The plaintext's length once [cur] opens as chunk [index] ([last] or not), else null (and nothing released). */
    private fun tryOpen(last: Boolean): Int? = try {
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(SealStream.TAG * 8, StreamNonce.of(index, last)))
        cipher.updateAAD(aad)
        cipher.doFinal(cur, 0, curLen, plain, 0)
    } catch (_: AEADBadTagException) {
        plain.fill(0)
        null
    } catch (_: javax.crypto.IllegalBlockSizeException) {
        plain.fill(0)
        null
    }

    /** Reads into [b] until it is full or the input ends; how many bytes. */
    private fun fill(b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val k = input.read(b, n, b.size - n)
            if (k < 0) break
            n += k
        }
        return n
    }
}
