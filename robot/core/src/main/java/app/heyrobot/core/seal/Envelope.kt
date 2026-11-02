// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.seal

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

// Pure. Robot's sealed envelope (files-1.4-plan.md §2.3, decision D1), for the kinds 1.5's Robot to Robot adds
// (robot-to-robot-plan.md §2.4 and §3.6, job L2): a fresh random 256-bit key for every sealed stream, that key wrapped
// by a key Robot keeps, and the bytes in STREAM chunks (SealStream) with the whole header as every chunk's additional
// data. Robot Files' own format 1 stays in core/files/FileSeal.kt, read and written exactly as in 1.4; this file holds the
// same layout for other magics, and format 2, the public-key wrap.
//
//   offset  bytes  field          (big-endian)
//   0       8      magic          the kind's: "ROBOTLNK" (Robot to Robot's state and waiting requests), "ROBOTFIL"
//                                 (format 2 only: a file that arrived while the phone was locked, FileSeal.arrived*)
//   8       2      format         1 or 2 (a newer one: NEWER, never read or written over)
//   10      1      purpose        the kind's codes; each purpose belongs to one format (anything else: DAMAGED)
//   11      1      wrap type      format 1: 1, a Keystore AES-256-GCM key ([SecretWrap]: robot_link_v1);
//                                 format 2: 2, robot_inbox_v1's RSA-3072 public key ([PublicWrap])
//   12      16     id             what the stream is (a record's id, a waiting request's id); the caller checks it
//   28      2      wrapped size   60 (format 1) or 384 (format 2)
//   30      w      wrapped key    format 1: the Keystore's IV (12), the key's ciphertext (32), the tag (16), with
//                                 "<key name>|<purpose>|<id>" as GCM's additional data (robot-link-v1|5|<32 hex>);
//                                 format 2: RSA-OAEP (SHA-256, MGF1 with SHA-1) of the key (32) followed by
//                                 "robot-inbox-v1|<magic>|<purpose>|<id>", checked whole after the unwrap
//   30+w   4      chunk size     65,536 (anything else: DAMAGED)
//   34+w   2      reserved       0 (anything else: DAMAGED)
//   header: 96 bytes in format 1, 420 in format 2
//
// Format 2 wraps with a public key, which needs no unlock: what :link must keep while the phone is locked is sealed this
// way and can be opened only once you unlock (robot_inbox_v1 needs the phone unlocked). Anyone holding the public key
// can make a format 2 stream; only Robot's processes have it, and only Robot's private storage holds the streams. The key's
// additional data travels inside the RSA block (Android's Keystore takes no OAEP label), and the whole header is every
// chunk's additional data, so a wrapped key moved to another file or purpose fails, as in format 1. Every field is
// checked before a key is unwrapped. Nothing here decodes what a stream holds: bytes in, bytes out.

/** Why a key couldn't wrap or unwrap now. The app's vault maps the Keystore's exceptions to these. */
enum class KeyProblem {
    /** The phone is locked (the key needs it unlocked), or the Keystore is busy: "not now", never "gone". */
    LOCKED,
    /** Invalidated by Android, or no key under the alias (and none may be made). */
    GONE,
    /** The wrapped key doesn't open: changed, moved to another stream, or wrapped by another key. */
    BAD_DATA,
}

class KeyException(val problem: KeyProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * Wrap type 1: an AES-256-GCM key in the Keystore that wraps each stream's 32-byte key (60 bytes out: its own 12-byte IV,
 * the ciphertext, the 16-byte tag), [aad] as GCM's additional data. [create]: make the key first when there is none (only
 * when nothing is sealed under it yet); without it a missing key is [KeyProblem.GONE]. Throws [KeyException].
 */
interface SecretWrap {
    fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray
    fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Wrap type 2: robot_inbox_v1, an RSA-3072 key in the Keystore. [wrap] encrypts with its public key (RSA-OAEP, SHA-256,
 * MGF1 with SHA-1; 384 bytes out) and needs no unlock; [unwrap] needs the phone unlocked. Throws [KeyException].
 */
interface PublicWrap {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** One purpose of a kind: its code, the format it is sealed in, and its cap. */
class KindPurpose(val code: Int, val format: Int, val maxBytes: Long) {
    init {
        require(code in 1..255 && format in 1..Envelope.FORMAT_MAX && maxBytes > 0)
    }

    val maxChunks: Long get() = SealStream.chunks(maxBytes)
}

/**
 * A kind of sealed stream: its 8-byte [magic], the name its format 1 key goes by in the additional data ([keyName],
 * "robot-link-v1"), and its purposes.
 */
class SealKind(magic: String, val keyName: String?, val purposes: List<KindPurpose>) {
    private val magicBytes = magic.toByteArray(Charsets.US_ASCII)
    val magic: ByteArray get() = magicBytes.copyOf()
    val magicText: String = magic

    init {
        require(magicBytes.size == 8 && purposes.isNotEmpty() && purposes.map { it.code }.toSet().size == purposes.size)
        require(keyName != null || purposes.none { it.format == Envelope.FORMAT_SECRET })
    }

    fun purpose(code: Int): KindPurpose? = purposes.firstOrNull { it.code == code }

    internal fun isMagic(b: ByteArray): Boolean = MessageDigest.isEqual(b, magicBytes)
}

/** Why a sealed stream can't be read or written now: one code each. */
enum class EnvelopeProblem {
    /** A header, a wrapped key or a chunk that doesn't check (changed, cut, moved, or sealed by another key). */
    DAMAGED,
    /** A newer Robot's format or wrap type: never read, never written over. */
    NEWER,
    /** The key can't be used now (the phone is locked, or the Keystore is busy): not now, never gone. */
    LOCKED,
    /** The key is gone (invalidated, or missing while something is sealed under it). */
    GONE,
    /** Over the purpose's cap. */
    TOO_BIG,
}

/** No message and no stack trace of its own: nothing of a stream or a key is ever kept in one. */
class EnvelopeException(val problem: EnvelopeProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "EnvelopeException(${problem.name})" // l10n: not text (toString for logs and tests)
}

/** A parsed header. [wrapped] is copied in and out, so a header can't be changed through it. */
class EnvelopeHeader(val kind: SealKind, val purpose: KindPurpose, id: ByteArray, wrapped: ByteArray) {
    private val idBytes = id.copyOf()
    private val wrappedBytes = wrapped.copyOf()

    init {
        require(kind.purpose(purpose.code) === purpose && idBytes.size == Envelope.ID && wrappedBytes.size == Envelope.wrappedSize(purpose.format))
    }

    val format: Int get() = purpose.format
    val id: ByteArray get() = idBytes.copyOf()
    val wrapped: ByteArray get() = wrappedBytes.copyOf()

    /** The header's bytes, as written before the first chunk and given to every chunk as its additional data. */
    fun encode(): ByteArray = ByteBuffer.allocate(Envelope.headerSize(format)).apply {
        put(kind.magic)
        putShort(format.toShort())
        put(purpose.code.toByte())
        put(Envelope.wrapType(format).toByte())
        put(idBytes)
        putShort(wrappedBytes.size.toShort())
        put(wrappedBytes)
        putInt(SealStream.CHUNK)
        putShort(0)
    }.array()

    override fun toString(): String = "EnvelopeHeader(${kind.magicText}, ${purpose.code})" // l10n: not text (toString for logs and tests)
}

object Envelope {
    const val FORMAT_SECRET = 1
    const val FORMAT_PUBLIC = 2
    /** The newest format this Robot reads. */
    const val FORMAT_MAX = 2
    const val WRAP_SECRET = 1
    const val WRAP_PUBLIC = 2
    const val ID = 16
    const val KEY = SealStream.KEY_BYTES
    /** Format 1's wrapped key: IV, ciphertext, tag. */
    const val WRAPPED_SECRET = 12 + KEY + SealStream.TAG
    /** Format 2's: one RSA-3072 block. */
    const val WRAPPED_PUBLIC = 384
    /** The most RSA-OAEP with SHA-256 encrypts in one 3072-bit block: 384 - 2 × 32 - 2. */
    const val PUBLIC_PLAIN_MAX = WRAPPED_PUBLIC - 2 * 32 - 2
    /** Magic and format: what is read before the header's size is known. */
    private const val LEAD = 10
    private const val FIXED = 36

    fun wrappedSize(format: Int): Int = if (format == FORMAT_PUBLIC) WRAPPED_PUBLIC else WRAPPED_SECRET
    fun wrapType(format: Int): Int = if (format == FORMAT_PUBLIC) WRAP_PUBLIC else WRAP_SECRET
    fun headerSize(format: Int): Int = FIXED + wrappedSize(format)

    /** The bytes a sealed stream of [plain] bytes takes in [format]: header, plaintext, a tag per chunk. */
    fun sealedSize(format: Int, plain: Long): Long = headerSize(format) + plain + SealStream.chunks(plain) * SealStream.TAG

    /** The wrapped key's additional data: GCM's in format 1, the tail of the RSA block in format 2. */
    fun keyAad(kind: SealKind, purpose: KindPurpose, id: ByteArray): ByteArray {
        val hex = id.joinToString("") { "%02x".format(it.toInt() and 0xFF) } // l10n: not text (hex)
        val text = if (purpose.format == FORMAT_SECRET) "${kind.keyName}|${purpose.code}|$hex" // l10n: not text (the cipher's additional data)
        else "robot-inbox-v1|${kind.magicText}|${purpose.code}|$hex" // l10n: not text (the cipher's additional data)
        return text.toByteArray(Charsets.UTF_8)
    }

    /**
     * The header in [bytes] for [kind], checked field by field before any key is used: a newer format or wrap type is
     * [EnvelopeProblem.NEWER]; anything else that isn't exactly this kind's is [EnvelopeProblem.DAMAGED].
     */
    fun parse(kind: SealKind, bytes: ByteArray): EnvelopeHeader {
        if (bytes.size < LEAD) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val b = ByteBuffer.wrap(bytes)
        if (!kind.isMagic(ByteArray(8).also { b.get(it) })) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val format = b.short.toInt() and 0xFFFF
        if (format > FORMAT_MAX) throw EnvelopeException(EnvelopeProblem.NEWER)
        if (format < 1 || kind.purposes.none { it.format == format }) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        if (bytes.size < headerSize(format)) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val purpose = kind.purpose(b.get().toInt() and 0xFF)?.takeIf { it.format == format } ?: throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val wrap = b.get().toInt() and 0xFF
        if (wrap > WRAP_PUBLIC) throw EnvelopeException(EnvelopeProblem.NEWER)
        if (wrap != wrapType(format)) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val id = ByteArray(ID).also { b.get(it) }
        if ((b.short.toInt() and 0xFFFF) != wrappedSize(format)) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val wrapped = ByteArray(wrappedSize(format)).also { b.get(it) }
        if (b.int != SealStream.CHUNK) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        if (b.short.toInt() != 0) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        return EnvelopeHeader(kind, purpose, id, wrapped)
    }

    /** The header at the start of [input]: its magic and format first, then exactly the rest of its size. */
    fun readHeader(kind: SealKind, input: InputStream): Pair<EnvelopeHeader, ByteArray> {
        val lead = readFully(input, LEAD)
        val format = ((lead[8].toInt() and 0xFF) shl 8) or (lead[9].toInt() and 0xFF)
        // Parse the lead alone first, so a wrong magic or a newer format says so before more is read.
        if (!kind.isMagic(lead.copyOf(8))) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        if (format > FORMAT_MAX) throw EnvelopeException(EnvelopeProblem.NEWER)
        if (format < 1 || kind.purposes.none { it.format == format }) throw EnvelopeException(EnvelopeProblem.DAMAGED)
        val bytes = lead + readFully(input, headerSize(format) - LEAD)
        return parse(kind, bytes) to bytes
    }

    /**
     * A new sealed stream on [out]: a fresh key from [random], wrapped by [secret] (format 1; made first when [create]) or
     * [public] (format 2), the header, then what the caller writes; close it to seal the last chunk. More than the
     * purpose's cap throws [EnvelopeProblem.TOO_BIG] before any byte past it is sealed. Problems throw [EnvelopeException].
     */
    fun writer(
        out: OutputStream, kind: SealKind, purpose: KindPurpose, id: ByteArray,
        secret: SecretWrap? = null, public: PublicWrap? = null, create: Boolean = false, random: SecureRandom,
    ): EnvelopeWriter {
        require(kind.purpose(purpose.code) === purpose && id.size == ID)
        require(if (purpose.format == FORMAT_SECRET) secret != null else public != null)
        val key = ByteArray(KEY).also(random::nextBytes)
        try {
            val aad = keyAad(kind, purpose, id)
            val wrapped = keyed {
                if (secret != null && purpose.format == FORMAT_SECRET) secret.wrap(key, aad, create)
                else (key + aad).let { plain -> try { public!!.wrap(plain) } finally { plain.fill(0) } }
            }
            if (wrapped.size != wrappedSize(purpose.format)) throw EnvelopeException(EnvelopeProblem.DAMAGED)
            val header = EnvelopeHeader(kind, purpose, id, wrapped)
            val bytes = header.encode()
            val stream = SealWriter(out, bytes, key, allowEmpty = true, maxChunks = purpose.maxChunks)
            out.write(bytes)
            return EnvelopeWriter(stream, header, purpose.maxBytes)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Opens the sealed stream on [input]: its header read and checked, its purpose [purpose] and (when given) its id [id],
     * the key unwrapped by [secret] or [public] as its format says. Reading releases only chunks whose tags check; the
     * stream is whole only when a read returns -1. Problems throw [EnvelopeException]. Closing it closes [input].
     */
    fun reader(
        input: InputStream, kind: SealKind, purpose: KindPurpose, id: ByteArray?,
        secret: SecretWrap? = null, public: PublicWrap? = null,
    ): EnvelopeReader {
        require(kind.purpose(purpose.code) === purpose && (id == null || id.size == ID))
        require(if (purpose.format == FORMAT_SECRET) secret != null else public != null)
        try {
            val (header, bytes) = readHeader(kind, input)
            if (header.purpose !== purpose || (id != null && !MessageDigest.isEqual(header.id, id))) throw EnvelopeException(EnvelopeProblem.DAMAGED)
            val aad = keyAad(kind, purpose, header.id)
            val key = keyed {
                if (secret != null && purpose.format == FORMAT_SECRET) secret.unwrap(header.wrapped, aad)
                else public!!.unwrap(header.wrapped).let { plain ->
                    try {
                        val tail = plain.copyOfRange(minOf(KEY, plain.size), plain.size)
                        if (plain.size != KEY + aad.size || !MessageDigest.isEqual(tail, aad)) throw EnvelopeException(EnvelopeProblem.DAMAGED)
                        plain.copyOf(KEY)
                    } finally {
                        plain.fill(0)
                    }
                }
            }
            try {
                if (key.size != KEY) throw EnvelopeException(EnvelopeProblem.DAMAGED)
                return EnvelopeReader(SealReader(input, bytes, key, allowEmpty = true, maxChunks = purpose.maxChunks, maxBytes = purpose.maxBytes), header)
            } finally {
                key.fill(0)
            }
        } catch (x: Exception) {
            try { input.close() } catch (_: IOException) {}
            throw x
        }
    }

    /** A key call with its problems as [EnvelopeException]s: anything but its own exception is "not now". */
    internal inline fun <T> keyed(block: () -> T): T = try {
        block()
    } catch (x: KeyException) {
        throw EnvelopeException(
            when (x.problem) {
                KeyProblem.LOCKED -> EnvelopeProblem.LOCKED
                KeyProblem.GONE -> EnvelopeProblem.GONE
                KeyProblem.BAD_DATA -> EnvelopeProblem.DAMAGED
            },
        )
    } catch (x: EnvelopeException) {
        throw x
    } catch (x: Exception) {
        // The Keystore busy, a provider's runtime error: not now, and never a reason to drop anything.
        throw EnvelopeException(EnvelopeProblem.LOCKED)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val bytes = ByteArray(n)
        var at = 0
        while (at < n) {
            val k = input.read(bytes, at, n - at)
            if (k < 0) throw EnvelopeException(EnvelopeProblem.DAMAGED)
            at += k
        }
        return bytes
    }
}

/** The sealed stream being written: plaintext in, at most [max] bytes. Close it to seal the last chunk. */
class EnvelopeWriter internal constructor(private val stream: SealWriter, val header: EnvelopeHeader, private val max: Long) : OutputStream() {
    val written: Long get() = stream.written

    override fun write(b: Int) {
        if (stream.written + 1 > max) throw EnvelopeException(EnvelopeProblem.TOO_BIG)
        envelopeMapped { stream.write(b) }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len > 0 && stream.written + len > max) throw EnvelopeException(EnvelopeProblem.TOO_BIG)
        envelopeMapped { stream.write(b, off, len) }
    }

    override fun flush() {}

    override fun close() = envelopeMapped { stream.close() }
}

/** A sealed stream being read: only chunks whose tags check are released. */
class EnvelopeReader internal constructor(private val stream: SealReader, val header: EnvelopeHeader) : InputStream() {
    val released: Long get() = stream.released

    override fun read(): Int = envelopeMapped { stream.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = envelopeMapped { stream.read(b, off, len) }

    override fun available(): Int = stream.available()

    override fun close() = stream.close()

    /** Everything, or an [EnvelopeException] and nothing: more than [max] bytes is TOO_BIG; on a failure what was read is zeroed. */
    fun readAll(max: Int): ByteArray {
        require(max >= 0)
        var buf = ByteArray(minOf(max, SealStream.CHUNK))
        var n = 0
        try {
            while (true) {
                if (n == buf.size) {
                    if (n >= max) {
                        if (read() >= 0) throw EnvelopeException(EnvelopeProblem.TOO_BIG)
                        break
                    }
                    val bigger = ByteArray(minOf(max.toLong(), 2L * buf.size.coerceAtLeast(1)).toInt())
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
}

private inline fun <T> envelopeMapped(block: () -> T): T = try {
    block()
} catch (x: SealException) {
    throw EnvelopeException(if (x.problem == SealProblem.TOO_BIG) EnvelopeProblem.TOO_BIG else EnvelopeProblem.DAMAGED)
}
