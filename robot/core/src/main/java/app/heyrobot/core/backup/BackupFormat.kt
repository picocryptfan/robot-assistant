// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.LogCode
import java.io.IOException
import java.nio.ByteBuffer

// Pure. Back up and restore (1.1): one file, "Robot backup <date>.robotbackup",
// encrypted with a passphrase you choose. This file holds what anyone holding the file can read without it: the 48-byte
// header (big-endian), which is also the additional authenticated data of every chunk, so none of it can be changed
// without every chunk failing.
//
//   offset  bytes  field        format 1
//   0       8      magic        ASCII "ROBOTBAK"
//   8       2      format       1
//   10      2      flags        0 (anything else is refused)
//   12      1      KDF          1 = Argon2id v1.3 (2, PBKDF2-HMAC-SHA256, is reserved: refused by this reader)
//   13      1      lanes p      4
//   14      2      passes t     3
//   16      4      memory m     65,536 KiB
//   20      16     salt         SecureRandom, new for every backup
//   36      4      chunk size   65,536 (anything else is refused)
//   40      4      app version  the version code of the Robot that made it
//   44      4      reserved     0 (anything else is refused)
//
// What it shows: that the file is a Robot backup, the version code that made it, and (from the file's size) how big it
// is. The date, the version name and what is inside are in the encrypted manifest (BackupContainer).

object BackupFormat {
    /** The file's first 8 bytes. */
    val MAGIC: ByteArray = "ROBOTBAK".toByteArray(Charsets.US_ASCII)
    /** The format this Robot writes and the newest it reads. */
    const val FORMAT = 1
    const val HEADER_SIZE = 48
    /** Plaintext bytes in every chunk but the last (which holds 1 to [CHUNK]): the shared STREAM's (core/seal). */
    const val CHUNK = app.heyrobot.core.seal.SealStream.CHUNK
    /** AES-GCM's tag, after each chunk's ciphertext. */
    const val TAG = app.heyrobot.core.seal.SealStream.TAG
    /** A chunk as it is on disk: ciphertext and tag. */
    const val SEALED = CHUNK + TAG
    const val SALT = 16
    const val KDF_ARGON2ID = 1
    /** Reserved for PBKDF2-HMAC-SHA256; no release writes it yet, so this one refuses it. */
    const val KDF_PBKDF2 = 2
    /** A file of more chunks than this (256 TiB) is refused; real backups are megabytes to gigabytes. */
    const val MAX_CHUNKS = app.heyrobot.core.seal.SealStream.MAX_CHUNKS
    /** The suggested file name's extension. */
    const val EXTENSION = ".robotbackup"
    /** What the file picker is given (Android has no type for it). */
    const val MIME = "application/octet-stream"
}

/**
 * The key derivation's settings as the header carries them. [memoryKiB] is Argon2id's m in KiB, [passes] its t, [lanes]
 * its p. Writing always uses [CURRENT]; reading accepts anything within [BackupKdf]'s bounds.
 */
data class KdfParams(val kdf: Int, val lanes: Int, val passes: Int, val memoryKiB: Int) {
    companion object {
        /** RFC 9106's second recommended option: Argon2id, 64 MiB, 3 passes, 4 lanes. */
        val CURRENT = KdfParams(BackupFormat.KDF_ARGON2ID, lanes = 4, passes = 3, memoryKiB = 65_536)
    }
}

/** The 48-byte header. [salt] is copied in and out, so a header can't be changed through it. */
class BackupHeader(
    val format: Int,
    val kdf: KdfParams,
    salt: ByteArray,
    val appVersion: Long,
    val flags: Int = 0,
    val chunkSize: Int = BackupFormat.CHUNK,
    val reserved: Long = 0,
) {
    private val saltBytes = salt.copyOf()

    init {
        require(salt.size == BackupFormat.SALT)
        require(format in 0..0xFFFF && flags in 0..0xFFFF && kdf.kdf in 0..0xFF && kdf.lanes in 0..0xFF && kdf.passes in 0..0xFFFF)
        require(appVersion in 0..0xFFFF_FFFFL && reserved in 0..0xFFFF_FFFFL)
    }

    val salt: ByteArray get() = saltBytes.copyOf()

    /** The 48 bytes, as written at the start of the file and given to every chunk as its additional data. */
    fun encode(): ByteArray = ByteBuffer.allocate(BackupFormat.HEADER_SIZE).apply {
        put(BackupFormat.MAGIC)
        putShort(format.toShort())
        putShort(flags.toShort())
        put(kdf.kdf.toByte())
        put(kdf.lanes.toByte())
        putShort(kdf.passes.toShort())
        putInt(kdf.memoryKiB)
        put(saltBytes)
        putInt(chunkSize)
        putInt(appVersion.toInt())
        putInt(reserved.toInt())
    }.array()

    override fun toString(): String = "BackupHeader(format=$format, kdf=$kdf, appVersion=$appVersion)"  // l10n: not text (toString for logs and tests)

    companion object {
        /** A header for a new backup: this release's format and KDF settings, [salt] new for this file. */
        fun new(salt: ByteArray, appVersion: Long): BackupHeader = BackupHeader(BackupFormat.FORMAT, KdfParams.CURRENT, salt, appVersion)

        /**
         * The header in [bytes] (the file's first bytes, at least [BackupFormat.HEADER_SIZE] of them), checked before any
         * passphrase is asked for: not a backup (or a damaged header), a newer format, or KDF settings out of bounds each
         * throw their [BackupException].
         */
        fun parse(bytes: ByteArray): BackupHeader {
            if (bytes.size < BackupFormat.HEADER_SIZE) throw BackupException(OpenProblem.NOT_A_BACKUP)
            val b = ByteBuffer.wrap(bytes, 0, BackupFormat.HEADER_SIZE)
            val magic = ByteArray(8).also { b.get(it) }
            if (!magic.contentEquals(BackupFormat.MAGIC)) throw BackupException(OpenProblem.NOT_A_BACKUP)
            val format = b.short.toInt() and 0xFFFF
            if (format > BackupFormat.FORMAT) throw BackupException(OpenProblem.NEWER_FORMAT)
            if (format < 1) throw BackupException(OpenProblem.NOT_A_BACKUP)
            val flags = b.short.toInt() and 0xFFFF
            val kdf = b.get().toInt() and 0xFF
            val lanes = b.get().toInt() and 0xFF
            val passes = b.short.toInt() and 0xFFFF
            val memory = b.int.toLong() and 0xFFFF_FFFFL
            val salt = ByteArray(BackupFormat.SALT).also { b.get(it) }
            val chunk = b.int
            val app = b.int.toLong() and 0xFFFF_FFFFL
            val reserved = b.int.toLong() and 0xFFFF_FFFFL
            if (flags != 0 || chunk != BackupFormat.CHUNK || reserved != 0L) throw BackupException(OpenProblem.NOT_A_BACKUP)
            if (kdf != BackupFormat.KDF_ARGON2ID) throw BackupException(OpenProblem.KDF_REFUSED)
            if (memory > Int.MAX_VALUE) throw BackupException(OpenProblem.KDF_REFUSED)
            val params = KdfParams(kdf, lanes, passes, memory.toInt())
            if (!BackupKdf.withinBounds(params)) throw BackupException(OpenProblem.KDF_REFUSED)
            return BackupHeader(format, params, salt, app)
        }
    }
}

/**
 * Why a backup can't be opened or restored, one code each (voice log 241's a; the app says each in words, §9.4). Codes
 * are stable: never renumber one.
 */
enum class OpenProblem(override val id: Int, override val title: String) : LogCode {
    /** Not a Robot backup, or its header is damaged. */
    NOT_A_BACKUP(1, "not a backup"),
    /** Made by a newer Robot in a format this one can't read: "Update Robot, then restore it." */
    NEWER_FORMAT(2, "newer format"),
    /** Its key derivation asks for more memory or time than Robot allows (or a KDF this Robot doesn't have). */
    KDF_REFUSED(3, "KDF refused"),
    /** The first chunk doesn't open: a wrong passphrase, or a changed header (Robot can't tell the two apart). */
    WRONG_PASSPHRASE(4, "wrong passphrase"),
    /** A later chunk doesn't open, chunks are out of order, or bytes follow the end. */
    CHANGED(5, "changed"),
    /** The file ends too early: cut at a chunk's end or inside one. */
    INCOMPLETE(6, "incomplete"),
    /** Over the whole file's caps (records, size, chunks). */
    TOO_BIG(7, "too big"),
    /** Every chunk opens but the records inside break the format's rules: a writer's bug. */
    DAMAGED(8, "damaged"),
}

/**
 * A backup that can't be opened or read further. No message and no stack trace of its own: nothing of the file or of
 * the passphrase is ever kept in one (StrictJson's pattern); [problem] says what happened.
 */
class BackupException(val problem: OpenProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "BackupException(${problem.name})"  // l10n: not text (toString for logs and tests)
}
