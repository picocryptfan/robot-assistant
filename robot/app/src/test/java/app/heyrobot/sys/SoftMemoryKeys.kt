// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Memory's keys for JVM tests: software AES-256-GCM keys by alias, standing in for the Keystore's. [locked]: the
 * unlock-only key fails as the Keystore's does on a locked phone; [busy]: every lookup throws, as a Keystore that can't
 * answer now.
 */
class SoftMemoryKeys : MemoryKeys {
    val keys = HashMap<String, SecretKey>()
    var locked = false
    var busy = false
    var created = 0

    override fun cipher(alias: String, create: Boolean): MemoryCipher? {
        if (busy) throw IllegalStateException("keystore busy")
        val k = keys[alias] ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it; created++ } else return null
        return object : MemoryCipher {
            override val alias = alias
            private fun check() { if (locked && alias == MemoryKeyAliases.UNLOCKED) throw MemoryKeyException(KeyProblem.LOCKED) }
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
                check()
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
                c.updateAAD(aad)
                return iv + c.doFinal(plain)
            }
            override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
                check()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12))
                c.updateAAD(aad)
                return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: AEADBadTagException) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
            }
        }
    }

    override fun delete(alias: String) { keys.remove(alias) }
}
