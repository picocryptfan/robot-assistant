// SPDX-License-Identifier: GPL-3.0-only
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
 * How the keys were stored before KeystoreSecret kept them all, without the Keystore and the files: Ask Claude's key at
 * Robot 0.4.5 (bc9dba8, ClaudeKeys.save and .read, the format since 0.3.0) and every helper's key at 0.6.0 before the
 * updater's merge (9182495, HelperKeys.save and .read). KeystoreSecretReadTest writes files with them too.
 */
internal object OldKeyCode {
    /** ClaudeKeys.save at Robot 0.4.5 (bc9dba8). */
    fun sealAt045(key: SecretKey, value: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    /** ClaudeKeys.read at Robot 0.4.5 (bc9dba8). */
    fun openAt045(key: SecretKey, stored: ByteArray): String {
        val b = ByteBuffer.wrap(stored)
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        val ct = ByteArray(b.remaining()).also { b.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    /** HelperKeys.save at 0.6.0 (9182495), any helper (Claude's file and alias, or another helper's). */
    fun sealAt060(key: SecretKey, value: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
    }

    /** HelperKeys.read at 0.6.0 (9182495). */
    fun openAt060(key: SecretKey, stored: ByteArray): String {
        val b = ByteBuffer.wrap(stored)
        val iv = ByteArray(b.get().toInt()).also { b.get(it) }
        val ct = ByteArray(b.remaining()).also { b.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}

/**
 * Every secret Robot keeps goes through KeystoreSecret: each helper's API key (Claude's, in Ask Claude's format since 0.3.0,
 * and every other helper's since 0.6.0) and the updater's access token. A key saved by Robot 0.4.x (Ask Claude) or by 0.6.0
 * (any helper) must still decrypt after the update, and one saved now must read as both read it. The Android Keystore isn't
 * on the JVM, so an ordinary AES key stands in for it; the format around it (IV length, IV, AES-GCM with a 128-bit tag) is
 * what is tested, with those versions' own code for both directions. The Keystore aliases and the file and log names are
 * theirs too (KeystoreSecretReadTest checks the files on disk).
 */
class KeystoreSecretTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val claudeKey = "sk-ant-api03-" + "Ab1_-".repeat(18) + "xyzAA"
    private val groqKey = "gsk_" + "Ab1".repeat(16) + "AB12"
    private val token = "ghp_" + "a".repeat(36)

    private fun open(k: SecretKey, stored: ByteArray) = SecretReads.read({ stored }, { k }, { key, s -> KeystoreSecret.open(key, s) })

    @Test fun aKeySavedBy045StillDecrypts() {
        val k = aesKey()
        assertEquals(claudeKey, KeystoreSecret.open(k, OldKeyCode.sealAt045(k, claudeKey)))
        assertEquals(token, KeystoreSecret.open(k, OldKeyCode.sealAt045(k, token)))
        // Read through core's SecretReads (which removes only a file that can never decrypt): 0.4.5's file is well formed.
        assertTrue(SecretReads.wellFormed(OldKeyCode.sealAt045(k, claudeKey)) && SecretReads.wellFormed(KeystoreSecret.seal(k, claudeKey)))
        assertEquals(SecretRead.Present(claudeKey), open(k, OldKeyCode.sealAt045(k, claudeKey)))
    }

    @Test fun aKeySavedNowReadsAs045ReadIt() {
        val k = aesKey()
        val stored = KeystoreSecret.seal(k, claudeKey)
        assertEquals(claudeKey, OldKeyCode.openAt045(k, stored))
        // The same layout: one byte of IV length (12 for GCM), the IV, the ciphertext with its 16-byte tag.
        assertEquals(12, stored[0].toInt())
        assertEquals(1 + 12 + claudeKey.length + 16, stored.size)
        // A fresh IV each time.
        assertTrue(!KeystoreSecret.seal(k, claudeKey).contentEquals(stored))
    }

    /** 0.6.0's helpers: Claude's key (still 0.4.x's file and alias) and any other helper's, as HelperKeys stored them. */
    @Test fun aHelpersKeySavedBy060StillDecryptsAndOneSavedNowReadsAs060ReadIt() {
        for (key in listOf(claudeKey, groqKey, "sk-proj-" + "x".repeat(40), "local-server-key")) {
            val k = aesKey()
            val old = OldKeyCode.sealAt060(k, key)
            assertTrue(SecretReads.wellFormed(old))
            assertEquals(key, KeystoreSecret.open(k, old))
            assertEquals(SecretRead.Present(key), open(k, old))
            val now = KeystoreSecret.seal(k, key)
            assertEquals(key, OldKeyCode.openAt060(k, now))
            assertEquals(1 + 12 + key.length + 16, now.size)
        }
    }

    @Test fun anotherKeyOrAChangedFileDoesntDecrypt() {
        val k = aesKey()
        val stored = KeystoreSecret.seal(k, claudeKey)
        try { KeystoreSecret.open(aesKey(), stored); throw AssertionError("another key") } catch (_: AEADBadTagException) {}
        val changed = stored.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { KeystoreSecret.open(k, changed); throw AssertionError("a changed file") } catch (_: AEADBadTagException) {}
        // Each helper has its own Keystore key: one helper's file never opens with another's.
        assertTrue(open(aesKey(), OldKeyCode.sealAt060(k, groqKey)) is SecretRead.Invalidated)
    }

    @Test fun theAliasesFilesAndLogNamesAre04xAnd060s() {
        // Claude: 0.4.x's alias and files (0.6.0 kept them for the Claude helper); every other helper: its own alias and folder.
        assertEquals("robot_claude_api_key", HelperKeyFiles.CLAUDE_ALIAS)
        assertEquals(HelperKeyFiles.CLAUDE_ALIAS, HelperKeyFiles.alias("claude"))
        assertEquals(HelperKeyFiles.CLAUDE_ALIAS, HelperKeyFiles.store("claude").alias)
        assertEquals("robot_helper_groq", HelperKeyFiles.alias("groq"))
        assertEquals("robot_helper_groq", HelperKeyFiles.store("groq").alias)
        assertEquals("robot_helper_h2", HelperKeyFiles.store("h2").alias)
        // 0.6.0's log names, for every helper: helper_key, helper_key_save, helper_key_read, helper_key_forget, helper_key_delete,
        // helper_key_clip.
        assertEquals("helper_key", HelperKeyFiles.LOG)
        val files = File(root, "app/src/main/java/app/heyrobot/sys/HelperFiles.kt").readText()
        // 0.4.5 and 0.6.0: File(Paths.dir(ctx), "claude_key.bin") and "claude_key.json"; helpers/<id>/key.bin and key.json.
        assertTrue(files.contains("KeystoreSecret(CLAUDE_ALIAS, \"claude_key\", LOG)"))
        assertTrue(files.contains("KeystoreSecret(alias(id), \"key\", LOG) { dir(it, id) }"))
        assertTrue(files.contains("return File(File(Paths.dir(ctx), \"helpers\"), id)"))
        val src = File(root, "app/src/main/java/app/heyrobot/sys/KeystoreSecret.kt").readText()
        assertTrue(src.contains("private val dir: (Context) -> File = { Paths.dir(it) },"))
        assertTrue(src.contains("fun secret(ctx: Context) = File(dir(ctx), \"\$name.bin\")"))
        assertTrue(src.contains("fun meta(ctx: Context) = File(dir(ctx), \"\$name.json\")"))
        for (n in listOf("RLog.i(log,", "\"\${log}_save\"", "\"\${log}_read\"", "\"\${log}_forget\"", "\"\${log}_delete\"", "\"\${log}_clip\"")) {
            assertTrue(n, src.contains(n))
        }
        // The updater's token: its own alias and file, which no helper's can be (a helper's alias starts robot_helper_ and
        // its files are in helpers/<id>/).
        val store = File(root, "app/src/main/java/app/heyrobot/update/UpdateStore.kt").readText()
        assertTrue(store.contains("const val ALIAS = \"robot_github_token\"") && store.contains("KeystoreSecret(ALIAS, \"github_token\", \"update_token\")"))
        assertTrue(listOf("claude", "groq", "github_token", "token").none { HelperKeyFiles.alias(it) == "robot_github_token" })
    }
}
