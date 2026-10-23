// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * robot_files_v1 in software, for the JVM tests: AES-256-GCM with a random 12-byte IV, the additional data
 * authenticated, as the Keystore does it (F2). [master] null: no key. [refuse]: every call throws that problem (the phone
 * locked, the key invalidated). [boom]: every call throws a runtime error (a Keystore provider failing). [keys] records
 * each stream key it was given to wrap (hex), so a test can see that none repeats.
 */
class FakeFileKeys(seed: Int = 1, present: Boolean = true) : FileKeys {
    var master: ByteArray? = if (present) ByteArray(32) { (it * 3 + seed).toByte() } else null
    var refuse: FileKeyProblem? = null
    var boom = false
    val keys = ArrayList<String>()
    var created = 0
        private set
    var unwraps = 0
        private set
    private val random = SecureRandom()

    private fun check() {
        refuse?.let { throw FileKeyException(it) }
        if (boom) throw IllegalStateException("keystore")
    }

    override fun wrap(key: ByteArray, aad: ByteArray, create: Boolean): ByteArray {
        check()
        require(key.size == 32)
        val m = master ?: if (create) ByteArray(32).also(random::nextBytes).also { master = it; created++ } else throw FileKeyException(FileKeyProblem.GONE)
        keys += key.joinToString("") { "%02x".format(it) }
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(key)
    }

    override fun unwrap(wrapped: ByteArray, aad: ByteArray): ByteArray {
        check()
        unwraps++
        val m = master ?: throw FileKeyException(FileKeyProblem.GONE)
        if (wrapped.size <= 12) throw FileKeyException(FileKeyProblem.BAD_DATA)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(m, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
            c.updateAAD(aad)
            c.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (x: AEADBadTagException) {
            throw FileKeyException(FileKeyProblem.BAD_DATA, x)
        }
    }

    override fun delete() {
        master = null
    }
}
