package app.heyrobot.sys

import app.heyrobot.core.SecretRead
import app.heyrobot.core.SecretReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ask Claude's key moved into KeystoreSecret (the class the updater's token shares): a key saved by Robot 0.4.5 must still
 * decrypt after the update, and one saved after it must read the same way. The Android Keystore isn't on the JVM, so an
 * ordinary AES key stands in for it; the format around it (IV length, IV, AES-GCM with a 128-bit tag) is what is tested,
 * with 0.4.5's own code for both directions. The Keystore alias and the file and log names are 0.4.5's too.
 */
class KeystoreSecretTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** ClaudeKeys.save at Robot 0.4.5 (bc9dba8), without the Keystore and the file. */
    private fun sealAt045(key: SecretKey, value: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    /** ClaudeKeys.read at Robot 0.4.5 (bc9dba8), without the Keystore and the file. */
    private fun openAt045(key: SecretKey, stored: ByteArray): String {
        val b = ByteBuffer.wrap(stored)
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        val ct = ByteArray(b.remaining()).also { b.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    private val claudeKey = "sk-ant-api03-" + "Ab1_-".repeat(18) + "xyzAA"

    @Test fun aKeySavedBy045StillDecrypts() {
        val k = aesKey()
        assertEquals(claudeKey, KeystoreSecret.open(k, sealAt045(k, claudeKey)))
        assertEquals("ghp_" + "a".repeat(36), KeystoreSecret.open(k, sealAt045(k, "ghp_" + "a".repeat(36))))
        // Read through core's SecretReads (which removes only a file that can never decrypt): 0.4.5's file is well formed.
        assertTrue(SecretReads.wellFormed(sealAt045(k, claudeKey)) && SecretReads.wellFormed(KeystoreSecret.seal(k, claudeKey)))
        assertEquals(SecretRead.Present(claudeKey), SecretReads.read({ sealAt045(k, claudeKey) }, { k }, { key, stored -> KeystoreSecret.open(key, stored) }))
    }

    @Test fun aKeySavedNowReadsAs045ReadIt() {
        val k = aesKey()
        val stored = KeystoreSecret.seal(k, claudeKey)
        assertEquals(claudeKey, openAt045(k, stored))
        // The same layout: one byte of IV length (12 for GCM), the IV, the ciphertext with its 16-byte tag.
        assertEquals(12, stored[0].toInt())
        assertEquals(1 + 12 + claudeKey.length + 16, stored.size)
        // A fresh IV each time.
        assertTrue(!KeystoreSecret.seal(k, claudeKey).contentEquals(stored))
    }

    @Test fun anotherKeyOrAChangedFileDoesntDecrypt() {
        val k = aesKey()
        val stored = KeystoreSecret.seal(k, claudeKey)
        try { KeystoreSecret.open(aesKey(), stored); throw AssertionError("another key") } catch (_: AEADBadTagException) {}
        val changed = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { KeystoreSecret.open(k, changed); throw AssertionError("a changed file") } catch (_: AEADBadTagException) {}
    }

    @Test fun theAliasFilesAndLogNamesAre045s() {
        assertEquals("robot_claude_api_key", ClaudeKeyFiles.ALIAS)
        assertEquals(ClaudeKeyFiles.ALIAS, ClaudeKeyFiles.store.alias)
        val src = File(root, "app/src/main/java/app/heyrobot/sys/KeystoreSecret.kt").readText()
        // 0.4.5: File(Paths.dir(ctx), "claude_key.bin") and "claude_key.json"; the store is KeystoreSecret(ALIAS, "claude_key", ...).
        assertTrue(src.contains("fun secret(ctx: Context) = File(Paths.dir(ctx), \"\$name.bin\")"))
        assertTrue(src.contains("fun meta(ctx: Context) = File(Paths.dir(ctx), \"\$name.json\")"))
        assertTrue(File(root, "app/src/main/java/app/heyrobot/sys/ClaudeFiles.kt").readText().contains("KeystoreSecret(ALIAS, \"claude_key\", \"claude_key\")"))
        // 0.4.5's log names: claude_key, claude_key_save, claude_key_read, claude_key_forget, claude_key_delete, claude_key_clip.
        for (n in listOf("RLog.i(log,", "\"\${log}_save\"", "\"\${log}_read\"", "\"\${log}_forget\"", "\"\${log}_delete\"", "\"\${log}_clip\"")) {
            assertTrue(n, src.contains(n))
        }
    }
}
