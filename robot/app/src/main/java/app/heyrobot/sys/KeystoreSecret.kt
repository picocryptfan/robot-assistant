package app.heyrobot.sys

import android.content.ClipboardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.SecretRead
import app.heyrobot.core.SecretReads
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Main and voice processes. A secret Robot keeps for you: each helper's API key (Claude's since 0.3.0, every helper's since
// 0.6.0; sys/HelperFiles.kt HelperKeyFiles, decrypted in the :voice process only) and the updater's GitHub access token
// (the main process only). Each is encrypted with its own Android Keystore key (AES-GCM, 256 bits, not exportable) into
// <dir>/<name>.bin (files/robot/ unless it has a folder of its own, as a helper other than Claude does), with a masked form
// beside it (<name>.json) that Settings may show. Each secret is decrypted in one place only, just before its one use
// (ProcessIsolationTest names it), and never logged, put in an intent, the history or an IPC message.
// A read that fails is removed only when it can never work again (core SecretReads.classify); a failure that may pass (the
// Keystore busy or locked, an I/O error) keeps the file and the Keystore key, and the caller tries again later.

/**
 * One secret: its Keystore [alias], its file [name] (".bin" and ".json") in [dir] (files/robot/ by default), and the name
 * its log lines use ([log]).
 */
class KeystoreSecret(
    val alias: String,
    private val name: String,
    private val log: String,
    private val dir: (Context) -> File = { Paths.dir(it) },
) {
    /** IV and ciphertext. */
    fun secret(ctx: Context) = File(dir(ctx), "$name.bin")
    /** Not secret: the masked form and when it was saved, for Settings. */
    fun meta(ctx: Context) = File(dir(ctx), "$name.json")

    fun saved(ctx: Context): Boolean = secret(ctx).isFile

    /** The masked form ("Saved" if its note is gone), or null when none is saved. */
    fun masked(ctx: Context): String? = if (!saved(ctx)) null else
        readOrNull(meta(ctx))?.let { try { JSONObject(it).optString("masked").takeIf { m -> m.isNotEmpty() } } catch (_: Exception) { null } } ?: "Saved"

    /** A saved secret could never be decrypted again and was removed (Settings says so until a new one is saved). */
    fun unreadable(ctx: Context): Boolean = !saved(ctx) &&
        (readOrNull(meta(ctx))?.let { try { JSONObject(it).optBoolean("unreadable") } catch (_: Exception) { false } } ?: false)

    /** For saving: the Keystore key, made the first time. */
    private fun secretKey(): SecretKey = keys.get(alias) ?: keys.create(alias)

    /** Encrypts and saves [value], with [masked] for Settings; false (nothing saved) if it can't. Background thread. */
    fun save(ctx: Context, value: String, masked: String): Boolean = try {
        val out = seal(secretKey(), value)
        val f = secret(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        writeAtomic(meta(ctx), JSONObject().put("masked", masked).put("saved", System.currentTimeMillis()).toString())
        RLog.i(log, "op" to RLog.Code("saved"))
        true
    } catch (t: Throwable) {
        RLog.w("${log}_save", t)
        false
    }

    /**
     * The secret ([SecretRead.Present]), or none saved ([SecretRead.Absent]). One that can never be decrypted again
     * ([SecretRead.Invalidated]: its Keystore key gone or replaced, the file damaged) is removed here ([forget]), and you
     * enter it again. One that couldn't be read just now ([SecretRead.Unavailable]) is kept, file and Keystore key, for the
     * next read. Never throws. Background thread.
     */
    fun read(ctx: Context): SecretRead {
        val f = secret(ctx)
        val r = SecretReads.read(
            stored = { stored(f) },
            // Never made here: a missing key means the one that sealed the file is gone.
            key = { keys.get(alias) },
            decrypt = { k, stored -> open(k, stored) },
        )
        when (r) {
            is SecretRead.Unavailable -> RLog.w("${log}_read", null, "error" to RLog.Code(r.reason), "kept" to true)
            is SecretRead.Invalidated -> {
                RLog.w("${log}_read", null, "error" to RLog.Code(r.reason), "kept" to false)
                forget(ctx)
            }
            is SecretRead.Present, SecretRead.Absent -> {}
        }
        return r
    }

    /** [f]'s bytes, or null when none is saved (also when it was deleted just now). */
    private fun stored(f: File): ByteArray? = try {
        if (f.isFile) f.readBytes() else null
    } catch (e: FileNotFoundException) {
        if (f.exists()) throw e else null
    }

    /** One that can never be decrypted again reads as none: its files and Keystore entry go, and a note lets Settings say why. */
    private fun forget(ctx: Context) {
        delete(ctx)
        try { writeAtomic(meta(ctx), JSONObject().put("unreadable", true).toString()) } catch (t: Throwable) { RLog.w("${log}_forget", t) }
    }

    /** Delete: the files and the Keystore key go. Background thread. */
    fun delete(ctx: Context) {
        secret(ctx).delete()
        meta(ctx).delete()
        try {
            keys.delete(alias)
        } catch (t: Throwable) {
            RLog.w("${log}_delete", t)
        }
    }

    /** Changes when the secret's file does. */
    fun stamp(ctx: Context): Long = secret(ctx).let { if (it.isFile) it.lastModified() xor it.length() else 0L }

    /**
     * [secret] was just pasted into a window that has the focus (so it may read the clipboard): if the clipboard still holds
     * exactly it, it is cleared.
     */
    fun clearClipboard(ctx: Context, secret: String) {
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim() == secret) cm.clearPrimaryClip()
        } catch (t: Throwable) {
            RLog.w("${log}_clip", t)
        }
    }

    companion object {
        private const val GCM_TAG_BITS = 128

        /** The Android Keystore; tests stand in for it (it exists only on a phone). */
        @Volatile internal var keys: SecretKeys = AndroidKeystoreKeys

        /**
         * The stored form, as Ask Claude's key has had it since 0.3.0 and every helper's key since 0.6.0 (KeystoreSecretTest
         * reads both): the IV's length (one byte), the IV, then the AES-GCM ciphertext with its 128-bit tag.
         */
        internal fun seal(key: SecretKey, value: String): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = c.iv
            return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
        }

        /** [seal]'s output back to the secret; throws if it was changed or [key] isn't the one that sealed it. */
        internal fun open(key: SecretKey, stored: ByteArray): String {
            val b = ByteBuffer.wrap(stored)
            val iv = ByteArray(b.get().toInt()).also { b.get(it) }
            val ct = ByteArray(b.remaining()).also { b.get(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return String(c.doFinal(ct), Charsets.UTF_8)
        }
    }
}

/** The Android Keystore as [KeystoreSecret] uses it: one AES key per alias. */
internal interface SecretKeys {
    /** The key under [alias], or null when there is none. Throws what the Keystore throws. */
    fun get(alias: String): SecretKey?

    /** A new key under [alias]: AES-GCM, 256 bits, not exportable. */
    fun create(alias: String): SecretKey

    fun delete(alias: String)
}

internal object AndroidKeystoreKeys : SecretKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun get(alias: String): SecretKey? = store().getKey(alias, null) as? SecretKey

    override fun create(alias: String): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    override fun delete(alias: String) = store().deleteEntry(alias)
}
