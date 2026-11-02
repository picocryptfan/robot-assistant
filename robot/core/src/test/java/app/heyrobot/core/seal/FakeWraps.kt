// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.seal

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * robot_link_v1 in software, for the JVM tests: AES-256-GCM with a random 12-byte IV and the additional data, as the
 * Keystore does it. [master] null: no key. [refuse]: every call throws that problem. [boom]: every call throws a runtime
 * error. [created] counts the keys made; [keys] the stream keys it wrapped (hex), so a test can see none repeats.
 */
class FakeSecretWrap(seed: Int = 1, present: Boolean = true) : SecretWrap {
    var master: ByteArray? = if (present) ByteArray(32) { (it * 5 + seed).toByte() } else null
    var refuse: KeyProblem? = null
    var boom = false
    var created = 0
        private set
    var wraps = 0
        private set
    val keys = ArrayList<String>()
    private val random = SecureRandom()

    private fun check() {
        refuse?.let { throw KeyException(it) }
        if (boom) throw IllegalStateException("keystore")
    }

    override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray {
        check()
        require(key.size == 32)
        val m = master ?: if (create) ByteArray(32).also(random::nextBytes).also { master = it; created++ } else throw KeyException(KeyProblem.GONE)
        wraps++
        keys += key.joinToString("") { "%02x".format(it) }
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        check()
        val m = master ?: throw KeyException(KeyProblem.GONE)
        if (wrapped.size <= 12) throw KeyException(KeyProblem.BAD_DATA)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
            c.updateAAD(aad)
            c.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (x: AEADBadTagException) {
            throw KeyException(KeyProblem.BAD_DATA, x)
        }
    }
}

/**
 * robot_inbox_v1 in software: RSA-3072, OAEP with SHA-256 and MGF1 with SHA-1 named in full on both sides, as the vault
 * does. [locked]: unwrapping throws LOCKED (the phone locked), wrapping still works, as with the Keystore's public key.
 * [gone]: both throw GONE. [unwraps] counts the decryptions.
 */
class FakeInboxWrap(private val pair: KeyPair = PAIR) : PublicWrap {
    var locked = false
    var gone = false
    var unwraps = 0
        private set

    override fun wrap(plain: ByteArray): ByteArray {
        if (gone) throw KeyException(KeyProblem.GONE)
        val c = Cipher.getInstance(OAEP)
        c.init(Cipher.ENCRYPT_MODE, pair.public, PARAMS)
        return c.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (gone) throw KeyException(KeyProblem.GONE)
        if (locked) throw KeyException(KeyProblem.LOCKED)
        unwraps++
        return try {
            val c = Cipher.getInstance(OAEP)
            c.init(Cipher.DECRYPT_MODE, pair.private, PARAMS)
            c.doFinal(wrapped)
        } catch (x: BadPaddingException) {
            throw KeyException(KeyProblem.BAD_DATA, x)
        }
    }

    companion object {
        const val OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val PARAMS = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        /** One key pair for every test (RSA-3072 takes a moment to make), and a second, another phone's. */
        val PAIR: KeyPair by lazy { rsa() }
        val OTHER: KeyPair by lazy { rsa() }

        private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
    }
}
