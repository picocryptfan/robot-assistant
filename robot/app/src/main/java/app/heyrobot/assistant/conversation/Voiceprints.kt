// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.heyrobot.core.RLog
import app.heyrobot.core.Voiceprint
import app.heyrobot.core.VoiceprintCodec
import app.heyrobot.core.writeAtomicBytes
import app.heyrobot.sys.VoiceprintFiles
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// VOICE PROCESS. Your "me" voiceprint, encrypted (docs/conversation-mode.md 3.3, 8; phase 5.3): made in "Your voice"
// (VoiceprintActivity), sealed with an Android Keystore key (AES-256-GCM, not exportable, its own alias) into one file
// in Robot's private storage (core VoiceprintCodec is the format), and decrypted only when a session starts, to seed its
// "Me" speaker. Never in settings (a flag says there is one), a log, an intent, an IPC message or a crash text. A file
// that won't decrypt any more (the key is gone) is deleted: Robot never guesses a voice.

object Voiceprints {
    private const val GCM_TAG_BITS = 128

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(VoiceprintCodec.KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(VoiceprintCodec.KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    /** The Keystore key as the codec's sealer: a fresh IV from the Keystore each time, the file's header authenticated. */
    private class Sealer(private val key: SecretKey) : VoiceprintCodec.Sealer {
        /** Why the last open failed (null: it didn't, or the file wasn't even this format). */
        var error: Throwable? = null

        override fun seal(plain: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD(aad)
            val ct = c.doFinal(plain)
            return c.iv to ct
        }

        override fun open(iv: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            c.updateAAD(aad)
            c.doFinal(sealed)
        } catch (t: Throwable) {
            error = t
            throw t
        }
    }

    /** Encrypts and saves [v] (replacing any earlier one); false (nothing saved) if it can't. Background thread. */
    fun save(ctx: Context, v: Voiceprint): Boolean = try {
        val k = key(create = true) ?: throw IllegalStateException("no key")
        writeAtomicBytes(VoiceprintFiles.file(ctx), VoiceprintCodec.seal(v, Sealer(k)))
        RLog.i("voiceprint", "op" to RLog.Code("saved"), "clips" to v.clips)
        true
    } catch (t: Throwable) {
        RLog.w("voiceprint_save", t)
        false
    }

    /**
     * Your voiceprint for the speaker model [model] (a VoiceBundle id), or null: none, made with another model, or
     * unreadable (then it is deleted, and "Your voice" offers to make it again). Background or session thread.
     */
    fun read(ctx: Context, model: String): Voiceprint? {
        val f = VoiceprintFiles.file(ctx)
        if (!f.isFile) return null
        return try {
            val k = key(create = false)
            val sealer = k?.let { Sealer(it) }
            val v = sealer?.let { VoiceprintCodec.open(f.readBytes(), it) }
            // Gone for good: no key, a key Android invalidated, bytes that fail their tag, or not this format. A Keystore
            // that is merely busy keeps the file for the next session.
            val lost = sealer == null || sealer.error.let { it == null || it is javax.crypto.AEADBadTagException ||
                it is android.security.keystore.KeyPermanentlyInvalidatedException }
            if (v == null) {
                RLog.i("voiceprint", "op" to RLog.Code(if (lost) "unreadable" else "not_now"))
                if (lost) VoiceprintFiles.delete(ctx)
                null
            } else if (v.model != model) {
                RLog.i("voiceprint", "op" to RLog.Code("other_model"))
                v.vector.fill(0f)
                null
            } else v
        } catch (t: Throwable) {
            // The Keystore itself failed (busy, locked): not a reason to delete the file; this session goes without.
            RLog.w("voiceprint_read", t)
            null
        }
    }
}
