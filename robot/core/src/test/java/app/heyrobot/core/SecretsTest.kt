package app.heyrobot.core

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Reading a saved secret (Ask Claude's key, the updater's token): only a failure that would happen on every read removes
 * it; one that may pass keeps it. The app's KeystoreSecret reads through [SecretReads.read]; an ordinary AES key stands in
 * for the Android Keystore's here, in the same stored form (IV length, IV, AES-GCM with its 128-bit tag).
 */
class SecretsTest {
    private fun invalidated(t: Throwable) = SecretReads.classify(t) is SecretRead.Invalidated
    private fun unavailable(t: Throwable) = SecretReads.classify(t) is SecretRead.Unavailable

    @Test fun aFailureThatWillHappenAgainInvalidates() {
        // The ciphertext doesn't match the key: the file was changed, or the key replaced.
        assertTrue(invalidated(AEADBadTagException("tag mismatch")))
        // Android says the key can never be used again, also when it comes as the cause of another failure.
        assertTrue(invalidated(KeyPermanentlyInvalidatedException()))
        assertTrue(invalidated(InvalidKeyException("wrapped").initCause(KeyPermanentlyInvalidatedException())))
        assertTrue(invalidated(UnrecoverableKeyException("wrapped").initCause(KeyPermanentlyInvalidatedException())))
        // KeyStore.getKey gives an invalidated key as an UnrecoverableKeyException with its message only.
        assertTrue(invalidated(UnrecoverableKeyException("User changed or deleted their auth credentials")))
    }

    @Test fun aFailureThatMayPassKeepsTheSecret() {
        val transient = listOf(
            KeyStoreException("keystore busy"),
            ProviderException("keystore restarting"),
            // The phone locked, for a key that needs it unlocked: it reads again once you unlock.
            UserNotAuthenticatedException(),
            IllegalStateException("not initialized"),
            // The keystore failed to answer while it loaded the key.
            UnrecoverableKeyException("Failed to obtain information about key").initCause(KeyStoreException("system error")),
            IOException("EIO"),
            FileNotFoundException("EACCES"),
            // Android's cipher turns a keystore failure during decryption into these.
            IllegalBlockSizeException().initCause(KeyStoreException("system error")),
            InvalidKeyException("Keystore operation failed"),
            BadPaddingException(),
            // Anything unexpected: when in doubt, the secret is kept.
            RuntimeException("?"),
            NullPointerException(),
            OutOfMemoryError(),
            StackOverflowError(),
            object : Exception("anonymous") {},
        )
        for (t in transient) assertTrue(t.toString(), unavailable(t))
    }

    @Test fun theReasonIsTheClassNeverTheMessage() {
        assertEquals(SecretRead.Unavailable("KeyStoreException"), SecretReads.classify(KeyStoreException("/data/user/0/ai.robot_app/files/robot/claude_key.bin")))
        assertEquals(SecretRead.Invalidated("AEADBadTagException"), SecretReads.classify(AEADBadTagException("sk-ant-api03-abc")))
        // A decrypted secret never shows in a log line or a crash text either.
        val present = SecretRead.Present("sk-ant-api03-secret")
        assertFalse(present.toString().contains("sk-ant"))
        assertFalse("$present".contains("secret"))
    }

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** The app's KeystoreSecret.seal: IV length, IV, AES-GCM ciphertext with its tag. */
    private fun seal(key: SecretKey, value: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(1 + c.iv.size + ct.size).put(c.iv.size.toByte()).put(c.iv).put(ct).array()
    }

    /** The app's KeystoreSecret.open. */
    private fun open(key: SecretKey, stored: ByteArray): String {
        val b = ByteBuffer.wrap(stored)
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        val ct = ByteArray(b.remaining()).also { b.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    private val token = "ghp_" + "a1B2".repeat(9)

    @Test fun theStoredForm() {
        val k = aesKey()
        assertTrue(SecretReads.wellFormed(seal(k, token)))
        assertTrue(SecretReads.wellFormed(seal(k, "x")))
        val good = seal(k, token)
        for (bad in listOf(
            ByteArray(0),
            byteArrayOf(12),
            // Shorter than an IV and a tag.
            good.copyOf(1 + 12 + 15),
            // An IV length Robot never wrote (Android's Keystore makes and takes 12 bytes only), or none, or "negative".
            good.copyOf().also { it[0] = 16 },
            good.copyOf().also { it[0] = 0 },
            good.copyOf().also { it[0] = 0x8c.toByte() },
        )) assertFalse(bad.size.toString(), SecretReads.wellFormed(bad))
    }

    @Test fun aReadInOrder() {
        val k = aesKey()
        val stored = seal(k, token)
        var asked = 0
        fun key(k: SecretKey?): () -> SecretKey? = { asked++; k }
        // Read and decrypted.
        assertEquals(SecretRead.Present(token), SecretReads.read({ stored }, key(k), ::open))
        // None saved: the Keystore isn't asked.
        asked = 0
        assertEquals(SecretRead.Absent, SecretReads.read({ null }, key(k), ::open))
        assertEquals(0, asked)
        // The file couldn't be read just now.
        assertEquals(SecretRead.Unavailable("IOException"), SecretReads.read({ throw IOException("EIO") }, key(k), ::open))
        assertEquals(0, asked)
        // A damaged file never decrypts, whatever the Keystore says (it isn't asked).
        assertEquals(SecretRead.Invalidated("malformed"), SecretReads.read({ stored.copyOf(20) }, key(k), ::open))
        assertEquals(0, asked)
        // Its Keystore key is gone.
        assertEquals(SecretRead.Invalidated("no_key"), SecretReads.read({ stored }, key(null), ::open))
        // The Keystore couldn't answer just now.
        assertEquals(SecretRead.Unavailable("KeyStoreException"), SecretReads.read({ stored }, { throw KeyStoreException("busy") }, ::open))
        assertEquals(SecretRead.Unavailable("UserNotAuthenticatedException"), SecretReads.read({ stored }, { throw UserNotAuthenticatedException() }, ::open))
        assertEquals(SecretRead.Invalidated("UnrecoverableKeyException"), SecretReads.read({ stored }, { throw UnrecoverableKeyException("gone") }, ::open))
        // Another key (the old one replaced), or a changed byte.
        assertEquals(SecretRead.Invalidated("AEADBadTagException"), SecretReads.read({ stored }, key(aesKey()), ::open))
        val changed = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(SecretRead.Invalidated("AEADBadTagException"), SecretReads.read({ changed }, key(k), ::open))
        // The cipher failed for another reason.
        assertEquals(SecretRead.Unavailable("ProviderException"), SecretReads.read({ stored }, key(k), { _, _ -> throw ProviderException("keystore") }))
        assertEquals(SecretRead.Invalidated("KeyPermanentlyInvalidatedException"),
            SecretReads.read({ stored }, key(k), { _, _ -> throw KeyPermanentlyInvalidatedException() }))
    }
}
