// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import app.heyrobot.core.lock.LockCodeRules
import app.heyrobot.core.lock.LockMac
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The Keystore in software for the lock code: HMAC-SHA256 keys by alias. [failGet]: what asking for a key throws (a
 * Keystore error, an invalidated key); [failGetAfterMake]: the same, but only once a key was made (a Keystore that stops
 * answering in the middle of a change); [failMake] and [failDelete] likewise.
 */
class SoftLockKeys : LockCodeKeys {
    val keys = HashMap<String, SecretKey>()
    var failGet: (() -> Throwable)? = null
    var failGetAfterMake: (() -> Throwable)? = null
    var failMake: (() -> Throwable)? = null
    var failDelete: (() -> Throwable)? = null
    val made = ArrayList<String>()
    val deleted = ArrayList<String>()

    override fun key(alias: String): SecretKey? {
        failGet?.let { throw it() }
        if (made.isNotEmpty()) failGetAfterMake?.let { throw it() }
        return keys[alias]
    }

    override fun make(alias: String): SecretKey {
        failMake?.let { throw it() }
        made += alias
        return SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "HmacSHA256").also { keys[alias] = it }
    }

    override fun delete(alias: String) {
        failDelete?.let { throw it() }
        deleted += alias
        keys.remove(alias)
    }
}

/**
 * The lock code's vault on a real folder, with the Keystore in software ([SoftLockKeys]) and the JVM's HMAC-SHA256: only a
 * key gone for good counts as lost; every other Keystore, MAC or file failure is "not now" and changes nothing; a new code
 * goes under the other alias, and the old code keeps working until the new one is written and read back.
 */
class LockCodeVaultTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val keys = SoftLockKeys()
    /** Throws what it holds instead of the MAC (Mac.init or doFinal failing). */
    private var failMac: (() -> Throwable)? = null
    private val file by lazy { File(tmp.root, LockCodeVault.FILE) }
    private val vault by lazy {
        LockCodeVault(file, keys) { key, input -> failMac?.let { throw it() } ?: Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(input) }
    }

    private val v1 = LockCodeRules.ALIAS
    private val v1b = LockCodeVault.ALIAS_B

    private fun alias(): String = JSONObject(file.readText()).getString("alias")

    @Test fun aCodeIsCheckedAndEachNewOneGoesUnderTheOtherAlias() {
        assertEquals(LockCodeVault.Check.NoCode, vault.check("123456"))
        assertTrue(vault.set("123456"))
        assertEquals(v1, alias())
        assertEquals(setOf(v1), keys.keys.keys)
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        assertEquals(LockCodeVault.Check.Wrong, vault.check("123457"))
        assertEquals(LockCodeVault.Check.Wrong, vault.check("12345"))
        // A change: the new key under v1b, the old one deleted only after.
        assertTrue(vault.set("2468"))
        assertEquals(v1b, alias())
        assertEquals(setOf(v1b), keys.keys.keys)
        assertEquals(LockCodeVault.Check.Right, vault.check("2468"))
        assertEquals(LockCodeVault.Check.Wrong, vault.check("123456"))
        // And back to v1.
        assertTrue(vault.set("97531"))
        assertEquals(v1, alias())
        assertEquals(setOf(v1), keys.keys.keys)
        assertEquals(LockCodeVault.Check.Right, vault.check("97531"))
        assertEquals(listOf(v1, v1b, v1), keys.made)
        // Not a code: refused before anything is made.
        assertFalse(vault.set("12a4"))
        assertFalse(vault.set("123"))
        assertEquals(3, keys.made.size)
    }

    /** A code.json the vault wrote before the second alias (Base64 NO_WRAP, robot_lock_code_v1) still checks. */
    @Test fun aCodeSetBeforeTheSecondAliasStillChecks() {
        val key = keys.make(v1)
        val salt = ByteArray(LockCodeRules.SALT_BYTES) { it.toByte() }
        val f = LockCodeRules.make("424242", salt, LockMac { Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(it) })
        file.writeText(JSONObject().put("v", 1).put("alg", LockCodeRules.ALG).put("alias", f.alias)
            .put("salt", Base64.getEncoder().encodeToString(f.salt)).put("mac", Base64.getEncoder().encodeToString(f.mac)).put("digits", 6).toString())
        assertEquals(LockCodeVault.Check.Right, vault.check("424242"))
        assertEquals(LockCodeVault.Check.Wrong, vault.check("424243"))
        // Its first change goes to v1b.
        assertTrue(vault.set("1111"))
        assertEquals(v1b, alias())
        assertEquals(setOf(v1b), keys.keys.keys)
    }

    @Test fun onlyAKeyGoneForGoodIsLost() {
        assertTrue(vault.set("123456"))
        // A set clears the alias it didn't use (a key an earlier try may have left there), never the one in use.
        assertEquals(listOf(v1b), keys.deleted)
        keys.deleted.clear()
        fun at(get: Throwable): LockCodeVault.Check {
            keys.failGet = { get }
            return vault.check("123456").also { keys.failGet = null }
        }
        // Android says the key can never be used again, also as a cause, and getKey's way of saying it (no cause).
        assertEquals(LockCodeVault.Check.KeyLost, at(KeyPermanentlyInvalidatedException()))
        assertEquals(LockCodeVault.Check.KeyLost, at(InvalidKeyException("wrapped").initCause(KeyPermanentlyInvalidatedException())))
        assertEquals(LockCodeVault.Check.KeyLost, at(UnrecoverableKeyException("User changed or deleted their auth credentials")))
        // Everything else may pass: keystore2 or StrongBox busy or restarting, anything unexpected.
        assertEquals(LockCodeVault.Check.NotNow, at(UnrecoverableKeyException("Failed to obtain information about key").initCause(KeyStoreException("busy"))))
        assertEquals(LockCodeVault.Check.NotNow, at(KeyStoreException("system error")))
        assertEquals(LockCodeVault.Check.NotNow, at(ProviderException("keystore2")))
        assertEquals(LockCodeVault.Check.NotNow, at(IllegalStateException("not initialized")))
        assertEquals(LockCodeVault.Check.NotNow, at(OutOfMemoryError()))
        // The same at the MAC (Mac.init or doFinal).
        fun mac(t: Throwable): LockCodeVault.Check {
            failMac = { t }
            return vault.check("123456").also { failMac = null }
        }
        assertEquals(LockCodeVault.Check.KeyLost, mac(KeyPermanentlyInvalidatedException()))
        assertEquals(LockCodeVault.Check.KeyLost, mac(ProviderException("init").initCause(KeyPermanentlyInvalidatedException())))
        assertEquals(LockCodeVault.Check.NotNow, mac(ProviderException("Keystore operation failed")))
        assertEquals(LockCodeVault.Check.NotNow, mac(InvalidKeyException("Keystore operation failed")))
        // Not one of those deleted anything: the code still checks.
        assertTrue(file.isFile)
        assertEquals(emptyList<String>(), keys.deleted)
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        // No key under code.json's alias, getKey not throwing: lost.
        keys.keys.clear()
        assertEquals(LockCodeVault.Check.KeyLost, vault.check("123456"))
        assertTrue(file.isFile)
    }

    @Test fun aFileThatCantBeReadIsNotNowNeverLost() {
        keys.make(v1)
        fun with(text: String): LockCodeVault.Check {
            file.writeText(text)
            return vault.check("123456")
        }
        val salt = Base64.getEncoder().encodeToString(ByteArray(16))
        val mac = Base64.getEncoder().encodeToString(ByteArray(32))
        fun json(alias: String = v1, v: Int = 1, saltB64: String = salt, macB64: String = mac, digits: Int = 6) =
            JSONObject().put("v", v).put("alg", LockCodeRules.ALG).put("alias", alias).put("salt", saltB64).put("mac", macB64).put("digits", digits).toString()
        assertEquals(LockCodeVault.Check.Wrong, with(json()))
        assertEquals(LockCodeVault.Check.NotNow, with("{\"v\":1,"))
        assertEquals(LockCodeVault.Check.NotNow, with(""))
        assertEquals(LockCodeVault.Check.NotNow, with(json(v = 2)))
        assertEquals(LockCodeVault.Check.NotNow, with(json(alias = "robot_lock_code_v2")))
        assertEquals(LockCodeVault.Check.NotNow, with(json(macB64 = Base64.getEncoder().encodeToString(ByteArray(31)))))
        assertEquals(LockCodeVault.Check.NotNow, with(json(saltB64 = "not base64!")))
        assertEquals(LockCodeVault.Check.NotNow, with(json(digits = 3)))
        // There but unreadable (here a folder in its place): not now, and no new code over it.
        file.delete()
        assertTrue(File(file, "x").mkdirs())
        assertTrue(vault.isSet())
        assertEquals(LockCodeVault.Check.NotNow, vault.check("123456"))
        assertFalse(vault.set("654321"))
        assertEquals(listOf(v1), keys.made)
        assertEquals(emptyList<String>(), keys.deleted)
    }

    @Test fun aChangeThatFailsBeforeTheWriteKeepsTheOldCode() {
        assertTrue(vault.set("123456"))
        // The new key can't be made.
        keys.failMake = { ProviderException("Failed to generate key") }
        assertFalse(vault.set("2468"))
        keys.failMake = null
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        assertEquals(v1, alias())
        assertTrue(v1 in keys.keys)
        keys.deleted.clear()
        // The MAC under the new key fails: the new key goes, the old one stays.
        failMac = { ProviderException("Keystore operation failed") }
        assertFalse(vault.set("2468"))
        failMac = null
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        assertEquals(setOf(v1), keys.keys.keys)
        assertEquals(listOf(v1b), keys.deleted)
        // The write fails (a folder where its temporary file goes).
        val part = File(tmp.root, ".${LockCodeVault.FILE}.tmp")
        assertTrue(File(part, "x").mkdirs())
        assertFalse(vault.set("2468"))
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        assertEquals(LockCodeVault.Check.Wrong, vault.check("2468"))
        assertEquals(setOf(v1), keys.keys.keys)
        File(part, "x").delete()
        part.delete()
        // And then it works.
        assertTrue(vault.set("2468"))
        assertEquals(LockCodeVault.Check.Right, vault.check("2468"))
        assertEquals(setOf(v1b), keys.keys.keys)
    }

    @Test fun aChangeThatDoesntReadBackPutsTheOldFileBack() {
        assertTrue(vault.set("123456"))
        val before = file.readText()
        keys.made.clear()
        // The Keystore stops answering right after making the new key: code.json was written, but can't be checked.
        keys.failGetAfterMake = { KeyStoreException("system error") }
        assertFalse(vault.set("2468"))
        keys.failGetAfterMake = null
        assertEquals(before, file.readText())
        assertEquals(LockCodeVault.Check.Right, vault.check("123456"))
        assertEquals(setOf(v1), keys.keys.keys)
        // A first code that doesn't read back leaves no code at all.
        assertTrue(vault.remove())
        keys.made.clear()
        keys.failGetAfterMake = { KeyStoreException("system error") }
        assertFalse(vault.set("2468"))
        keys.failGetAfterMake = null
        assertFalse(vault.isSet())
        assertEquals(emptySet<String>(), keys.keys.keys)
    }

    @Test fun removingTakesTheFileThenTheKeys() {
        assertTrue(vault.set("123456"))
        assertTrue(vault.remove())
        assertFalse(vault.isSet())
        assertEquals(LockCodeVault.Check.NoCode, vault.check("123456"))
        assertEquals(emptySet<String>(), keys.keys.keys)
        assertEquals(setOf(v1, v1b), keys.deleted.toSet())
        // The keys can't be deleted: the code is still gone.
        assertTrue(vault.set("2468"))
        keys.failDelete = { KeyStoreException("system error") }
        assertTrue(vault.remove())
        assertFalse(vault.isSet())
        keys.failDelete = null
        // The file can't be deleted (here a folder that isn't empty): the keys stay, so the code isn't taken for lost.
        keys.deleted.clear()
        keys.make(v1)
        assertTrue(File(file, "x").mkdirs())
        assertFalse(vault.remove())
        assertTrue(v1 in keys.keys)
        assertEquals(emptyList<String>(), keys.deleted)
    }
}
