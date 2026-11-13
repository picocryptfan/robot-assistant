// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.seal.KeyProblem
import app.heyrobot.core.shots.ShotKeys
import app.heyrobot.core.shots.ShotStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** robot_shots_v1, Ask about your screenshots' key: its kind, sealing while locked, and gone at switch-off. */
class ShotsVaultTest {
    private val root: File = Files.createTempDirectory("shots").toFile()
    private val dir: File = File(root, "shots").apply { mkdirs() }
    @After fun removeTemp() { root.deleteRecursively() }

    private fun src(path: String): String =
        listOf(File("src/main/java/app/heyrobot/$path"), File("app/src/main/java/app/heyrobot/$path")).first { it.isFile }.readText()
    private val vault = src("sys/ShotsVault.kt")
    private fun body(marker: String): String {
        assertTrue(marker, vault.contains(marker))
        return vault.substringAfter(marker).substringBefore("\n    override fun ").substringBefore("\n    private ")
    }

    @Test fun theKeyIsRobotLinksInboxKind() {
        assertTrue(vault.contains("const val ALIAS = \"robot_shots_v1\""))
        assertTrue(vault.contains("private const val BITS = 3072"))
        val spec = vault.substringAfter("fun spec(): KeyGenParameterSpec").substringBefore(".build()")
        // Decrypt only: the private key never encrypts or signs.
        assertTrue(spec, spec.contains("KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT)"))
        assertFalse(spec, Regex("""PURPOSE_(ENCRYPT|SIGN|VERIFY|WRAP_KEY|AGREE_KEY)""").containsMatchIn(spec))
        for (s in listOf("RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4)", ".setKeySize(BITS)", ".setDigests(KeyProperties.DIGEST_SHA256)",
            ".setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)", ".setUnlockedDeviceRequired(true)")) {
            assertTrue(s, spec.contains(s))
        }
        // Made in the Keystore, never exported or backed up in the clear.
        assertTrue(vault.contains("KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, \"AndroidKeyStore\")"))
        assertTrue(body("override fun create()").contains("initialize(spec())"))
        assertEquals(1, Regex("""\bspec\(\)""").findAll(vault).count() - 1)
    }

    @Test fun sealingNeedsOnlyThePublicKeySoItWorksLocked() {
        val wrap = body("override fun wrap(")
        for (s in listOf("getCertificate(ALIAS)?.publicKey", "KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA)", "Cipher.ENCRYPT_MODE, soft")) {
            assertTrue(s, wrap.contains(s))
        }
        assertFalse(wrap, Regex("""getKey\(|PrivateKey""").containsMatchIn(wrap))
        val unwrap = body("override fun unwrap(")
        assertTrue(unwrap, unwrap.contains("getKey(ALIAS, null) as? PrivateKey") && unwrap.contains("Cipher.DECRYPT_MODE, key"))
        assertTrue(vault.contains("\"RSA/ECB/OAEPWithSHA-256AndMGF1Padding\""))
    }

    @Test fun switchingOffDeletesTheFolderAndTheKey() {
        var deleted = 0
        val keys = object : ShotKeys {
            override fun wrap(plain: ByteArray) = plain
            override fun unwrap(wrapped: ByteArray) = wrapped
            override fun create() {}
            override fun delete() { deleted++ }
        }
        File(dir, "index.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(ShotStore(dir, keys).switchOff())
        assertFalse(dir.exists())
        assertEquals(1, deleted)
        assertTrue(body("override fun delete()").contains("deleteEntry(ALIAS)"))
        // The page's Off cancels the pass, then deletes the folder and the key.
        assertTrue(src("ui/settings/ShotsSettings.kt").contains("ShotsJob.cancel(ctx); ShotsVault.store(ctx).switchOff()"))
    }

    /** review-screenshots M3: only a lock error is "unlock first"; a batch that doesn't open is BAD_DATA, read past. */
    @Test fun onlyALockErrorSaysUnlockFirst() {
        assertEquals(KeyProblem.BAD_DATA, KeystoreShotKeys.problemOf(javax.crypto.BadPaddingException()))
        assertEquals(KeyProblem.BAD_DATA, KeystoreShotKeys.problemOf(javax.crypto.AEADBadTagException()))
        assertEquals(KeyProblem.BAD_DATA, KeystoreShotKeys.problemOf(javax.crypto.IllegalBlockSizeException()))
        assertEquals(KeyProblem.LOCKED, KeystoreShotKeys.problemOf(java.security.KeyStoreException("busy")))
    }
}
