// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.thirdparty.bouncycastle.crypto.generators.Argon2BytesGenerator
import app.heyrobot.thirdparty.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

// Pure. The passphrase → key step of a backup: Argon2id v1.3 (RFC 9106) from Robot's copy of Bouncy Castle
// 1.81's Argon2 (app.heyrobot.thirdparty.bouncycastle: the generator, BLAKE2b and their helpers, not the library; see its
// NOTICE), and the only file that uses it (ProcessIsolationTest). Nothing is registered as a JCA provider, so every
// cipher stays the platform's (AES-GCM in BackupStream).
//
//   password = UTF-8(NFC(passphrase))       the same passphrase typed on two keyboards gives the same bytes; not trimmed
//   key      = Argon2id(password, salt, t, m, p, length 32, version 0x13)
//
// The password bytes are zeroed as soon as Argon2id has them, and the key lives in a [BackupKey] its user closes (zeroed)
// when the job ends. A stranger's file could ask for 4 GiB, so a header's settings are read only within [BackupKdf]'s
// bounds. Speed: Bouncy Castle's generator fills the lanes one after another, in Java (no threads).

object BackupKdf {
    const val KEY_BYTES = 32
    /** Bounds on a header's settings: t 1–10, m 19 MiB to 256 MiB, p 1–8. */
    val PASSES = 1..10
    val MEMORY_KIB = 19_456..262_144
    val LANES = 1..8

    fun withinBounds(p: KdfParams): Boolean =
        p.kdf == BackupFormat.KDF_ARGON2ID && p.passes in PASSES && p.memoryKiB in MEMORY_KIB && p.lanes in LANES

    /**
     * The passphrase's bytes: Unicode NFC, then UTF-8. Nothing is trimmed (a space is part of it). A lone surrogate can't
     * be encoded and is refused, rather than turned into "?" (which another passphrase could share).
     */
    fun password(passphrase: CharSequence): ByteArray {
        val nfc = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
        val enc = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val buf: ByteBuffer = enc.encode(CharBuffer.wrap(nfc))
        val out = ByteArray(buf.remaining())
        buf.get(out)
        if (buf.hasArray()) buf.array().fill(0)
        return out
    }

    /** The backup's key for [passphrase] under [header]'s settings and salt. Runs Argon2id: about a second or more. */
    fun derive(passphrase: CharSequence, header: BackupHeader): BackupKey {
        require(withinBounds(header.kdf))
        val pw = password(passphrase)
        return BackupKey(deriveFrom(pw, header.salt, header.kdf))
    }

    /** As [derive], from the password bytes, which are zeroed before this returns, whatever happens. */
    fun deriveFrom(password: ByteArray, salt: ByteArray, p: KdfParams): ByteArray = try {
        require(withinBounds(p))
        argon2id(password, salt, p.passes, p.memoryKiB, p.lanes, KEY_BYTES)
    } finally {
        password.fill(0)
    }

    /**
     * Argon2id v1.3 with any settings (RFC 9106's test vector uses m = 32 KiB, a secret and associated data); [derive]
     * goes through the bounds first. [password] is left as it is.
     */
    fun argon2id(password: ByteArray, salt: ByteArray, passes: Int, memoryKiB: Int, lanes: Int, length: Int, secret: ByteArray? = null,
                 additional: ByteArray? = null): ByteArray {
        val b = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(passes)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(lanes)
            .withSalt(salt)
        secret?.let { b.withSecret(it) }
        additional?.let { b.withAdditional(it) }
        val params = b.build()
        val out = ByteArray(length)
        try {
            Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        } finally {
            // Its own copies of the salt, the secret and the associated data.
            params.clear()
        }
        return out
    }
}

/**
 * A derived key: [bytes] (a copy) for each cipher set-up, zeroed by [close] when the backup or restore ends. The
 * cipher's own SecretKeySpec copy waits for the garbage collector (a known limit). [toString] shows nothing of it.
 */
class BackupKey(private val key: ByteArray) : AutoCloseable {
    init {
        require(key.size == BackupKdf.KEY_BYTES)
    }

    @Volatile var closed = false
        private set

    val bytes: ByteArray get() {
        check(!closed)
        return key.copyOf()
    }

    /** Whether every byte of this key is zero (after [close]; for tests). */
    internal fun zeroed(): Boolean = key.all { it == 0.toByte() }

    override fun close() {
        key.fill(0)
        closed = true
    }

    override fun toString(): String = "BackupKey(…)"  // l10n: not text (toString for logs and tests)
}
