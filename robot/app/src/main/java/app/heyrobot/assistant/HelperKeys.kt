package app.heyrobot.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.HelperKeyFiles
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// VOICE PROCESS. Each helper's API key (docs/ask-claude.md "Key and settings", every helper since 0.6.0): pasted once
// into HelperConnectionActivity, which runs in the :voice process like the panel, encrypted with an Android Keystore key
// of its own (AES-GCM, not exportable), and decrypted only here, just before the helper's client is made. It never
// appears in a log, a crash text, the history, an intent or an IPC message: the main app only reads whether one is
// saved and its masked form (sys/HelperFiles.kt). The window is FLAG_SECURE (no screenshots, recordings or task
// thumbnail), kept out of autofill, and clears the clipboard if it still holds the key just saved. Removing a helper
// deletes its Keystore key, so a copy of the file could never be read again.

object HelperKeys {
    private const val GCM_TAG_BITS = 128

    private fun secretKey(id: String): SecretKey {
        val alias = HelperKeyFiles.alias(id)
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

    /** Encrypts and saves [key] for helper [id] ([masked]: what Settings shows); false (nothing saved) if it can't. Background thread. */
    fun save(ctx: Context, id: String, key: String, masked: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey(id))
        val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        val out = ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
        val f = HelperKeyFiles.secret(ctx, id)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        writeAtomic(HelperKeyFiles.meta(ctx, id), JSONObject().put("masked", masked).put("saved", System.currentTimeMillis()).toString())
        RLog.i("helper_key", "op" to RLog.Code("saved"))
        true
    } catch (t: Throwable) {
        RLog.w("helper_key_save", t)
        false
    }

    /** Helper [id]'s key, or null (none saved, or the Keystore key is gone: then [forget] removes it and you paste it again). */
    fun read(ctx: Context, id: String): String? {
        val f = HelperKeyFiles.secret(ctx, id)
        if (!f.isFile) return null
        return try {
            val b = ByteBuffer.wrap(f.readBytes())
            val iv = ByteArray(b.get().toInt()).also { b.get(it) }
            val ct = ByteArray(b.remaining()).also { b.get(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(id), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            RLog.w("helper_key_read", t)
            null
        }
    }

    /** A key that can't be decrypted any more reads as no key: its files and the Keystore entry go, and Settings says why. */
    fun forget(ctx: Context, id: String) {
        HelperKeyFiles.delete(ctx, id)
        try { writeAtomic(HelperKeyFiles.meta(ctx, id), JSONObject().put("unreadable", true).toString()) } catch (t: Throwable) { RLog.w("helper_key_forget", t) }
    }

    /** Changes when the key file does: a cached client is rebuilt (or dropped) on the next turn. */
    fun stamp(ctx: Context, id: String): Long = HelperKeyFiles.secret(ctx, id).let { if (it.isFile) it.lastModified() xor it.length() else 0L }
}
