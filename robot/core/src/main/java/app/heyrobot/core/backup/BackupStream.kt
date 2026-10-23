// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.seal.SealException
import app.heyrobot.core.seal.SealProblem
import app.heyrobot.core.seal.SealReader
import app.heyrobot.core.seal.SealWriter
import java.io.InputStream
import java.io.OutputStream

// Pure (JCA). The encrypted stream after the header: age's STREAM construction with the platform's AES-GCM
// (Cipher "AES/GCM/NoPadding", Conscrypt on the phone), nothing custom. Since 1.4 the construction itself is
// core/seal/SealStream.kt, which Robot Files shares; these two classes give it the backup's header as the additional data
// and its key, and say its problems as OpenProblem. The bytes are 1.1's, byte for byte (BackupStreamPinTest).
//
//   file    = header(48) ‖ chunk_0 ‖ chunk_1 ‖ … ‖ chunk_n
//   chunk_i = AES-256-GCM(key, nonce_i, aad = header, plaintext_i) ‖ tag(16)
//   nonce_i = i as 11 bytes big-endian ‖ (0x01 if i = n else 0x00)
//   |plaintext_i| = 65,536 for i < n; 1 … 65,536 for the last (never empty: the manifest is always there)
//
// A new Cipher.init per chunk. Each chunk's bytes are used only after its tag is checked, and the "last" flag in the
// nonce makes a cut file detectable at any point: a file that ends without a chunk that opens as the last is
// incomplete. The header is every chunk's additional data, so its settings and salt can't be changed either. Memory
// stays flat whatever the file's size: one chunk in, one chunk out (the reader reads one chunk ahead).

/** The stream's problems as the backup says them. */
private fun SealException.backup(): BackupException = BackupException(
    when (problem) {
        SealProblem.FIRST_CHUNK -> OpenProblem.WRONG_PASSPHRASE
        SealProblem.CHANGED -> OpenProblem.CHANGED
        SealProblem.INCOMPLETE -> OpenProblem.INCOMPLETE
        SealProblem.TOO_BIG -> OpenProblem.TOO_BIG
        SealProblem.EMPTY -> OpenProblem.DAMAGED
    },
)

private inline fun <T> backupProblems(block: () -> T): T = try {
    block()
} catch (x: SealException) {
    throw x.backup()
}

/**
 * Writes [header] and then the encrypted chunks of everything written to it, to [out]. [close] seals the last chunk and
 * closes [out]; a stream closed with nothing written is refused (a backup always holds its manifest). [key] is copied.
 */
class BackupWriter(out: OutputStream, header: BackupHeader, key: BackupKey) : OutputStream() {
    private val stream: SealWriter

    init {
        val aad = header.encode()
        stream = key.bytes.let { k -> try { SealWriter(out, aad, k, allowEmpty = false, maxChunks = BackupFormat.MAX_CHUNKS) } finally { k.fill(0) } }
        out.write(aad)
    }

    /** Plaintext bytes written so far. */
    val written: Long get() = stream.written

    override fun write(b: Int) = backupProblems { stream.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = backupProblems { stream.write(b, off, len) }

    /** Nothing to flush: a chunk is written only when it is full (or the last). */
    override fun flush() {}

    override fun close() = backupProblems { stream.close() }
}

/**
 * Reads the plaintext of the chunks after [header] from [input] (positioned just after the header). Each chunk is
 * released only once its tag is checked, and only as the last one when nothing follows it. Problems throw
 * [BackupException]: chunk 0 not opening is [OpenProblem.WRONG_PASSPHRASE] (or a changed header: they can't be told
 * apart), a later one [OpenProblem.CHANGED] (changed bytes, chunks swapped, repeated or dropped, bytes after the end), and
 * a file that ends without a chunk that opens as the last [OpenProblem.INCOMPLETE]. A cut inside the last chunk and a
 * changed byte in it look the same from outside; both read as incomplete, and neither restores anything. [close]
 * closes [input].
 */
class BackupReader(input: InputStream, header: BackupHeader, key: BackupKey) : InputStream() {
    private val stream: SealReader = key.bytes.let { k ->
        try { SealReader(input, header.encode(), k, allowEmpty = false, maxChunks = BackupFormat.MAX_CHUNKS) } finally { k.fill(0) }
    }
    /** Chunks opened so far. */
    val chunks: Long get() = stream.chunks
    /** Plaintext bytes released so far. */
    val released: Long get() = stream.released

    override fun read(): Int = backupProblems { stream.read() }

    override fun read(b: ByteArray, off: Int, n: Int): Int = backupProblems { stream.read(b, off, n) }

    override fun available(): Int = stream.available()

    override fun close() = stream.close()

    /** Reads to the end, checking every chunk, and keeps nothing. Throws as [read] does. */
    fun drain(): Long = backupProblems { stream.drain() }
}
