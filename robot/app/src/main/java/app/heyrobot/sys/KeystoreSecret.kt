package app.heyrobot.sys

import android.content.ClipboardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Main and voice processes. A secret Robot keeps for you (Ask Claude's API key, since 0.3.0; the updater's GitHub access
// token): encrypted with its own Android Keystore key (AES-GCM, 256 bits, not exportable) into files/robot/<name>.bin,
// with a masked form beside it (<name>.json) that Settings may show. Each secret is decrypted in one place only, just before
// its one use (ProcessIsolationTest names it), and never logged, put in an intent, the history or an IPC message.

/** One secret: its Keystore [alias], its file [name] (".bin" and ".json"), and the name its log lines use ([log]). */
class KeystoreSecret(val alias: String, private val name: String, private val log: String) {
    private val gcmTagBits = 128

    /** IV and ciphertext. */
    fun secret(ctx: Context) = File(Paths.dir(ctx), "$name.bin")
    /** Not secret: the masked form and when it was saved, for Settings. */
    fun meta(ctx: Context) = File(Paths.dir(ctx), "$name.json")

    fun saved(ctx: Context): Boolean = secret(ctx).isFile

    /** The masked form ("Saved" if its note is gone), or null when none is saved. */
    fun masked(ctx: Context): String? = if (!saved(ctx)) null else
        readOrNull(meta(ctx))?.let { try { JSONObject(it).optString("masked").takeIf { m -> m.isNotEmpty() } } catch (_: Exception) { null } } ?: "Saved"

    /** A saved secret couldn't be decrypted and was removed (Settings says so until a new one is saved). */
    fun unreadable(ctx: Context): Boolean = !saved(ctx) &&
        (readOrNull(meta(ctx))?.let { try { JSONObject(it).optBoolean("unreadable") } catch (_: Exception) { false } } ?: false)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    /** Encrypts and saves [value], with [masked] for Settings; false (nothing saved) if it can't. Background thread. */
    fun save(ctx: Context, value: String, masked: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        val out = ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
        val f = secret(ctx)
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

    /** The secret, or null (none saved, or the Keystore key is gone: then [forget] removes it and you enter it again). */
    fun read(ctx: Context): String? {
        val f = secret(ctx)
        if (!f.isFile) return null
        return try {
            val b = ByteBuffer.wrap(f.readBytes())
            val iv = ByteArray(b.get().toInt()).also { b.get(it) }
            val ct = ByteArray(b.remaining()).also { b.get(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(gcmTagBits, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            RLog.w("${log}_read", t)
            null
        }
    }

    /** One that can't be decrypted any more reads as none: its files and Keystore entry go, and a note lets Settings say why. */
    fun forget(ctx: Context) {
        delete(ctx)
        try { writeAtomic(meta(ctx), JSONObject().put("unreadable", true).toString()) } catch (t: Throwable) { RLog.w("${log}_forget", t) }
    }

    /** Delete: the files and the Keystore key go. Background thread. */
    fun delete(ctx: Context) {
        secret(ctx).delete()
        meta(ctx).delete()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        } catch (t: Throwable) {
            RLog.w("${log}_delete", t)
        }
    }

    /** Changes when the secret's file does. */
    fun stamp(ctx: Context): Long = secret(ctx).let { if (it.isFile) it.lastModified() xor it.length() else 0L }

    companion object {
        /**
         * A secret was just pasted into a window that has the focus (so it may read the clipboard): if the clipboard still
         * holds exactly it, it is cleared.
         */
        fun clearClipboard(ctx: Context, secret: String) {
            try {
                val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
                val clip = cm.primaryClip ?: return
                if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString()?.trim() == secret) cm.clearPrimaryClip()
            } catch (t: Throwable) {
                RLog.w("secret_clip", t)
            }
        }
    }
}
