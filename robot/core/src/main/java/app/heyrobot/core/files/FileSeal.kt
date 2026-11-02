// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.LogCode
import app.heyrobot.core.seal.Envelope
import app.heyrobot.core.seal.EnvelopeException
import app.heyrobot.core.seal.EnvelopeHeader
import app.heyrobot.core.seal.SealKind
import app.heyrobot.core.seal.EnvelopeProblem
import app.heyrobot.core.seal.KindPurpose
import app.heyrobot.core.seal.PublicWrap
import app.heyrobot.core.seal.SealException
import app.heyrobot.core.seal.SealProblem
import app.heyrobot.core.seal.SealReader
import app.heyrobot.core.seal.SealStream
import app.heyrobot.core.seal.SealWriter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

// Pure. How Robot Files seals a stream (files-1.4-plan.md §2.3, decision D1): envelope encryption.
//
//   1. robot_files_v1 (FileKeys: the Keystore, needs the phone unlocked) never touches a file's bytes;
//   2. a fresh random 256-bit key for every sealed stream (each blob, each thumbnail, every write of the index), so no
//      key ever seals two streams and STREAM's counter nonces can never repeat under one key;
//   3. that key wrapped by robot_files_v1 with "robot-files-v1|<purpose>|<id>" as the additional data, so a wrapped key
//      can't be moved to another file or purpose;
//   4. the bytes in STREAM chunks (core/seal/SealStream.kt, the backup's own code), the 96-byte header below as every
//      chunk's additional data.
//
//   offset  bytes  field          format 1 (big-endian)
//   0       8      magic          ASCII "ROBOTFIL"
//   8       2      format         1 (a newer one: NEWER, never read or written over)
//   10      1      purpose        1 file, 2 thumbnail, 3 index (anything else: DAMAGED)
//   11      1      wrap type      1 = robot_files_v1 in the Keystore. 2 is format 2's (below), never format 1's: NEWER, as
//                                 in 1.4. Anything else: DAMAGED
//   12      16     id             the file's id (the index: a fresh one each write); must be the id its path names
//   28      2      wrapped size   60
//   30      60     wrapped key    the Keystore's IV (12), the stream key's ciphertext (32), the tag (16)
//   90      4      chunk size     65,536 (anything else: DAMAGED)
//   94      2      reserved       0 (anything else: DAMAGED)
//
// Every field is checked strictly before the key is unwrapped, and the whole header is then authenticated twice over:
// purpose and id by the wrapped key's additional data, every byte by every chunk's. The algorithms are named by format
// (AES-256-GCM STREAM, 64 KiB chunks) and wrap type (robot_files_v1's AES-256-GCM); the plaintext's size isn't here and
// can't be (the header is written before the first chunk, and is that chunk's additional data): it is the index's.
// Nothing here decodes a file: these are bytes in and bytes out.
//
// Format 2 (1.5, Robot to Robot: robot-to-robot-plan.md §3.6, job L2): a file that arrived from another Robot while the
// phone was locked, when robot_files_v1 can't be used. :link seals it with a fresh key wrapped by robot_inbox_v1's public
// key (wrap type 2, RSA-OAEP, which needs no unlock), purpose 4 (ARRIVED), in a 420-byte header (core/seal/Envelope.kt:
// magic, format 2, purpose 4, wrap type 2, the id, wrapped size 384, the RSA block, chunk size 65,536, reserved), the whole
// header every chunk's additional data. At the first unlock Files' intake opens it ([arrivedReader]: one Keystore RSA
// decryption) and seals it again as format 1 under robot_files_v1, so what Robot Files keeps is format 1 only. Format 1's
// reader above is 1.4's, unchanged: it refuses format 2 (and wrap type 2) as NEWER, so a 1.4 Robot never misreads one, and
// a format 2 stream can never be read as a stored file. Format 2 is read only through [arrivedReader], never stored in
// blobs/.

/** What a sealed stream holds, and its cap. Codes are stable: never renumber one. */
enum class SealPurpose(val code: Int, val maxBytes: Long) {
    FILE(1, FileLimits.MAX_FILE),
    THUMBNAIL(2, FileLimits.MAX_THUMB),
    INDEX(3, FileLimits.MAX_INDEX);

    val maxChunks: Long get() = SealStream.chunks(maxBytes)

    companion object {
        fun of(code: Int): SealPurpose? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Why a file, a thumbnail or the index can't be read or written now, one code each (voice log FILE_KEY's a, F6). Codes are
 * stable: never renumber one.
 */
enum class FileProblem(override val id: Int, override val title: String) : LogCode {
    /** A header, a wrapped key or a chunk that doesn't check (changed, cut, moved, or sealed by another key). */
    DAMAGED(1, "damaged"),
    /** A newer Robot's format or wrap type: never read, never written over. */
    NEWER(2, "newer"),
    /** robot_files_v1 can't be used now (the phone is locked, or the Keystore is busy): not now, never gone. */
    LOCKED(3, "locked"),
    /** robot_files_v1 is gone: every file is kept, and Settings › Files offers a restore or Delete them all. */
    KEY_GONE(4, "key gone"),
    /** Over its cap (a file over 200 MiB, a thumbnail over 64 KiB, an index over 32 MiB). */
    TOO_BIG(5, "too big"),
    /** No such file, or it is being deleted. */
    MISSING(6, "missing"),
    /** Not enough free space for this and Robot's margin. Nothing was written. */
    LOW_SPACE(7, "low space"),
    /** 5,000 files. Robot never drops one to make room. */
    FULL(8, "full"),
    /** The index couldn't be read (damaged, or a newer Robot's), so nothing may be added, changed or written over it. */
    INDEX(9, "index"),
    /** The storage failed some other way. Nothing half-written is left. */
    IO(10, "io"),
}

/**
 * Something of Robot Files that can't be read or written now. No message and no stack trace of its own: nothing of a file
 * or a key is ever kept in one; [problem] says what happened, and for [FileProblem.LOW_SPACE] [needed] how many more
 * bytes adding it needs ("about 1.4 GB more needed", §2.6).
 */
class FileException(val problem: FileProblem, val needed: Long = 0) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "FileException(${problem.name})"  // l10n: not text (toString for logs and tests)
}

/** The 96-byte header. [wrapped] is copied in and out, so a header can't be changed through it. */
class FileSealHeader(val purpose: SealPurpose, val id: FileId, wrapped: ByteArray) {
    private val wrappedBytes = wrapped.copyOf()

    init {
        require(wrapped.size == FileSeal.WRAPPED)
    }

    val wrapped: ByteArray get() = wrappedBytes.copyOf()

    /** The 96 bytes, as written before the first chunk and given to every chunk as its additional data. */
    fun encode(): ByteArray = ByteBuffer.allocate(FileSeal.HEADER_SIZE).apply {
        put(FileSeal.MAGIC)
        putShort(FileSeal.FORMAT.toShort())
        put(purpose.code.toByte())
        put(FileSeal.WRAP_KEYSTORE.toByte())
        put(id.bytes())
        putShort(FileSeal.WRAPPED.toShort())
        put(wrappedBytes)
        putInt(SealStream.CHUNK)
        putShort(0)
    }.array()

    override fun toString(): String = "FileSealHeader($purpose)"  // l10n: not text (toString for logs and tests)
}

object FileSeal {
    val MAGIC: ByteArray = "ROBOTFIL".toByteArray(Charsets.US_ASCII)  // l10n: not text (the file's magic bytes)
    /** The format this Robot writes and the newest it reads. */
    const val FORMAT = 1
    const val HEADER_SIZE = 96
    /** robot_files_v1 in the Keystore. */
    const val WRAP_KEYSTORE = 1
    /** Format 2's wrap type (robot_inbox_v1's public key): format 1's reader refuses it as NEWER, as 1.4's did. */
    const val WRAP_PUBLIC = 2
    /** The Keystore's GCM IV. */
    const val IV = 12
    /** A wrapped stream key: IV, ciphertext, tag. */
    const val WRAPPED = IV + SealStream.KEY_BYTES + SealStream.TAG
    /** Where the wrapped key's size field starts: deleting overwrites it and the key ([destroyKey]). */
    const val WRAPPED_AT = 28

    /** The bytes a sealed stream of [plain] bytes takes: header, plaintext, a tag per chunk. */
    fun sealedSize(plain: Long): Long = HEADER_SIZE + plain + SealStream.chunks(plain) * SealStream.TAG

    /**
     * The plaintext's size in a sealed stream of [sealed] bytes, from its size alone ([sealedSize]'s inverse; no key, nothing
     * read): what Settings › Files counts while the index can't be read. 0 for a file too short to hold one chunk.
     */
    fun plainSize(sealed: Long): Long {
        val body = sealed - HEADER_SIZE
        if (body < SealStream.TAG) return 0
        val chunks = (body + SealStream.CHUNK + SealStream.TAG - 1) / (SealStream.CHUNK + SealStream.TAG)
        return (body - chunks * SealStream.TAG).coerceAtLeast(0)
    }

    /** The wrapped key's additional data. */
    fun keyAad(purpose: SealPurpose, id: FileId): ByteArray =
        "robot-files-v1|${purpose.code}|${id.hex}".toByteArray(Charsets.UTF_8)  // l10n: not text (the cipher's additional data)

    /**
     * The header in [bytes] (at least [HEADER_SIZE] of them), checked field by field: anything that isn't format 1 with
     * known values throws [FileException] (a newer format or wrap type [FileProblem.NEWER], the rest
     * [FileProblem.DAMAGED]). The key isn't unwrapped here.
     */
    fun parse(bytes: ByteArray): FileSealHeader {
        if (bytes.size < HEADER_SIZE) throw FileException(FileProblem.DAMAGED)
        val b = ByteBuffer.wrap(bytes, 0, HEADER_SIZE)
        val magic = ByteArray(MAGIC.size).also { b.get(it) }
        if (!magic.contentEquals(MAGIC)) throw FileException(FileProblem.DAMAGED)
        val format = b.short.toInt() and 0xFFFF
        if (format > FORMAT) throw FileException(FileProblem.NEWER)
        if (format < 1) throw FileException(FileProblem.DAMAGED)
        val purpose = SealPurpose.of(b.get().toInt() and 0xFF) ?: throw FileException(FileProblem.DAMAGED)
        when (b.get().toInt() and 0xFF) {
            WRAP_KEYSTORE -> {}
            WRAP_PUBLIC -> throw FileException(FileProblem.NEWER)
            else -> throw FileException(FileProblem.DAMAGED)
        }
        val id = FileId.of(ByteArray(FileId.BYTES).also { b.get(it) })
        if ((b.short.toInt() and 0xFFFF) != WRAPPED) throw FileException(FileProblem.DAMAGED)
        val wrapped = ByteArray(WRAPPED).also { b.get(it) }
        if (b.int != SealStream.CHUNK) throw FileException(FileProblem.DAMAGED)
        if (b.short.toInt() != 0) throw FileException(FileProblem.DAMAGED)
        return FileSealHeader(purpose, id, wrapped)
    }

    /** The header at the start of [input]: exactly [HEADER_SIZE] bytes read, parsed as [parse] does. */
    fun readHeader(input: InputStream): Pair<FileSealHeader, ByteArray> {
        val bytes = ByteArray(HEADER_SIZE)
        var n = 0
        while (n < bytes.size) {
            val k = input.read(bytes, n, bytes.size - n)
            if (k < 0) throw FileException(FileProblem.DAMAGED)
            n += k
        }
        return parse(bytes) to bytes
    }

    /**
     * A new sealed stream on [out]: a fresh key from [random], wrapped by [keys] (made first when [create]), the header,
     * then what the caller writes. The caller closes it to seal the last chunk; more than the purpose's cap throws
     * [FileProblem.TOO_BIG] before any byte past it is sealed. Key problems throw [FileException].
     */
    fun writer(out: OutputStream, purpose: SealPurpose, id: FileId, keys: FileKeys, create: Boolean, random: SecureRandom): FileSealWriter {
        val key = ByteArray(SealStream.KEY_BYTES).also(random::nextBytes)
        try {
            val wrapped = keyed { keys.wrap(key, keyAad(purpose, id), create) }
            if (wrapped.size != WRAPPED) throw FileException(FileProblem.DAMAGED)
            val header = FileSealHeader(purpose, id, wrapped)
            val bytes = header.encode()
            val stream = SealWriter(out, bytes, key, allowEmpty = true, maxChunks = purpose.maxChunks)
            out.write(bytes)
            return FileSealWriter(stream, header, purpose.maxBytes)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens the sealed stream on [input]: its header read and checked, its purpose [purpose] and (when given) its id [id],
     * the key unwrapped by [keys]. Reading it releases only chunks whose tags check: what has been read is always an
     * authentic prefix, and the file is whole only when a read returns -1 ([readAll] gives all or nothing). Problems throw
     * [FileException]. Closing it closes [input].
     */
    fun reader(input: InputStream, purpose: SealPurpose, id: FileId?, keys: FileKeys): FileSealReader {
        try {
            val (header, bytes) = readHeader(input)
            if (header.purpose != purpose || (id != null && header.id != id)) throw FileException(FileProblem.DAMAGED)
            val key = keyed { keys.unwrap(header.wrapped, keyAad(purpose, header.id)) }
            try {
                if (key.size != SealStream.KEY_BYTES) throw FileException(FileProblem.DAMAGED)
                return FileSealReader(SealReader(input, bytes, key, allowEmpty = true, maxChunks = purpose.maxChunks, maxBytes = purpose.maxBytes), header)
            } finally {
                key.fill(0)
            }
        } catch (x: Exception) {
            try { input.close() } catch (_: IOException) {}
            throw x
        }
    }

    /** The whole plaintext of [input]'s sealed stream, or nothing: over [max] bytes is TOO_BIG, and a failure part way zeroes what was read. */
    fun readAll(input: InputStream, purpose: SealPurpose, id: FileId?, keys: FileKeys, max: Long = purpose.maxBytes): ByteArray =
        reader(input, purpose, id, keys).use { it.readAll(max) }

    // Format 2: a file that arrived while the phone was locked (1.5, above). -----------------------------------------------

    /** Format 2: the only one wrapped by robot_inbox_v1. */
    const val FORMAT_ARRIVED = Envelope.FORMAT_PUBLIC
    /** Format 2's one purpose: a file that arrived from another Robot. */
    const val ARRIVED = 4
    const val ARRIVED_HEADER_SIZE = 420

    /** Format 2 as core/seal's envelope reads it: Robot Files' magic, purpose [ARRIVED] alone, at most a file's cap. */
    val ARRIVED_KIND = SealKind("ROBOTFIL", keyName = null, purposes = listOf(KindPurpose(ARRIVED, FORMAT_ARRIVED, FileLimits.MAX_FILE))) // l10n: not text (the file's magic bytes)
    private val arrived = ARRIVED_KIND.purposes.single()

    /** The bytes a format 2 stream of [plain] bytes takes. */
    fun arrivedSize(plain: Long): Long = Envelope.sealedSize(FORMAT_ARRIVED, plain)

    /**
     * A format 2 header in [bytes], checked field by field: format 1 (or anything not an arrival) is DAMAGED here, a newer
     * format or wrap type NEWER. Throws [FileException].
     */
    fun parseArrived(bytes: ByteArray): EnvelopeHeader = envelope { Envelope.parse(ARRIVED_KIND, bytes) }

    /**
     * A new format 2 stream on [out] for file [id]: a fresh key from [random] wrapped by robot_inbox_v1's public key
     * ([inbox], no unlock needed), the header, then what the caller writes; close it to seal the last chunk. Over 200 MiB
     * throws TOO_BIG before any byte past it is sealed. Problems throw [FileException].
     */
    fun arrivedWriter(out: OutputStream, id: FileId, inbox: PublicWrap, random: SecureRandom): OutputStream {
        val w = envelope { Envelope.writer(out, ARRIVED_KIND, arrived, id.bytes(), public = inbox, random = random) }
        return object : OutputStream() {
            override fun write(b: Int) = envelope { w.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = envelope { w.write(b, off, len) }
            override fun flush() {}
            override fun close() = envelope { w.close() }
        }
    }

    /**
     * Opens the format 2 stream on [input] (its id must be [id] when given), robot_inbox_v1 unwrapping its key: the phone
     * must be unlocked (LOCKED otherwise, never gone). Reading it releases only chunks whose tags check; it is whole only
     * when a read returns -1. Problems throw [FileException]. Closing it closes [input]. Files' intake hands it to the
     * store as any other input (FilesVault.stage), which seals it again as format 1.
     */
    fun arrivedReader(input: InputStream, id: FileId?, inbox: PublicWrap): InputStream {
        val r = envelope { Envelope.reader(input, ARRIVED_KIND, arrived, id?.bytes(), public = inbox) }
        return object : InputStream() {
            override fun read(): Int = envelope { r.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = envelope { r.read(b, off, len) }
            override fun available(): Int = r.available()
            override fun close() = r.close()
        }
    }

    /** An envelope call with its problems as [FileException]s, in Robot Files' own codes. */
    private inline fun <T> envelope(block: () -> T): T = try {
        block()
    } catch (x: EnvelopeException) {
        throw FileException(
            when (x.problem) {
                EnvelopeProblem.DAMAGED -> FileProblem.DAMAGED
                EnvelopeProblem.NEWER -> FileProblem.NEWER
                EnvelopeProblem.LOCKED -> FileProblem.LOCKED
                EnvelopeProblem.GONE -> FileProblem.KEY_GONE
                EnvelopeProblem.TOO_BIG -> FileProblem.TOO_BIG
            },
        )
    }

    /** A FileKeys call with its problems as [FileException]s: anything but its own exception is "not now". */
    internal inline fun <T> keyed(block: () -> T): T = try {
        block()
    } catch (x: FileKeyException) {
        throw FileException(
            when (x.problem) {
                FileKeyProblem.LOCKED -> FileProblem.LOCKED
                FileKeyProblem.GONE -> FileProblem.KEY_GONE
                FileKeyProblem.BAD_DATA -> FileProblem.DAMAGED
            },
        )
    } catch (x: FileException) {
        throw x
    } catch (x: Exception) {
        // The Keystore busy, a provider's runtime error: not now, and never a reason to drop anything.
        throw FileException(FileProblem.LOCKED)
    }
}

/** The sealed stream being written: plaintext in, at most [max] bytes. Close it to seal the last chunk. */
class FileSealWriter internal constructor(private val stream: SealWriter, val header: FileSealHeader, private val max: Long) : OutputStream() {
    val written: Long get() = stream.written

    override fun write(b: Int) {
        if (stream.written + 1 > max) throw FileException(FileProblem.TOO_BIG)
        mapped { stream.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0 && stream.written + len > max) throw FileException(FileProblem.TOO_BIG)
        mapped { stream.write(b, off, len) }
    }

    override fun flush() {}

    override fun close() = mapped { stream.close() }
}

/**
 * A sealed stream being read: only chunks whose tags check are released. A [FileException] part way means what was
 * read is an authentic beginning of the file and the rest isn't there: callers drop it.
 */
class FileSealReader internal constructor(private val stream: SealReader, val header: FileSealHeader) : InputStream() {
    /** Plaintext bytes released so far. */
    val released: Long get() = stream.released

    override fun read(): Int = mapped { stream.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = mapped { stream.read(b, off, len) }

    override fun available(): Int = stream.available()

    override fun close() = stream.close()

    /** Reads to the end, checking every chunk, and keeps nothing; the plaintext's size. */
    fun drain(): Long = mapped { stream.drain() }

    /** Everything, or a [FileException] and nothing: more than [max] bytes is TOO_BIG, and on any failure what was read is zeroed. */
    fun readAll(max: Long): ByteArray {
        require(max in 0..MAX_IN_RAM)
        var buf = ByteArray(minOf(max, SealStream.CHUNK.toLong()).toInt())
        var n = 0
        try {
            while (true) {
                if (n == buf.size) {
                    if (n.toLong() >= max) {
                        // Full at the cap: one more byte means it's over.
                        if (read() >= 0) throw FileException(FileProblem.TOO_BIG)
                        break
                    }
                    val bigger = ByteArray(minOf(max, 2L * buf.size.coerceAtLeast(1)).toInt())
                    System.arraycopy(buf, 0, bigger, 0, n)
                    buf.fill(0)
                    buf = bigger
                }
                val k = read(buf, n, buf.size - n)
                if (k < 0) break
                n += k
            }
            return buf.copyOf(n)
        } finally {
            buf.fill(0)
        }
    }

    private companion object {
        /** The most [readAll] holds in memory: a whole file at its cap. */
        const val MAX_IN_RAM = FileLimits.MAX_FILE
    }
}

private inline fun <T> mapped(block: () -> T): T = try {
    block()
} catch (x: SealException) {
    throw FileException(if (x.problem == SealProblem.TOO_BIG) FileProblem.TOO_BIG else FileProblem.DAMAGED)
}
