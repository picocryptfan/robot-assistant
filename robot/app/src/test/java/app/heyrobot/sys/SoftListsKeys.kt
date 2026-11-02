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
 * Lists' keys in software, by alias. [locked]: an unlock-only alias fails as the Keystore's does on a locked phone;
 * [busy]: every lookup throws, as a Keystore that can't answer now; [invalid]: the key is still there but every use
 * fails GONE, as one Android invalidated (KeystoreMemoryKeys maps KeyPermanentlyInvalidatedException so). [failSeal]: a
 * seal that fails "not now" for an alias and additional data (LS10: the journal's alone, "robot-lists-v1|locked.enc").
 */
class SoftListsKeys(private val unlockOnly: Set<String> = setOf(ListsKeyAliases.UNLOCKED)) : MemoryKeys {
    val keys = HashMap<String, SecretKey>()
    val asked = ArrayList<String>()
    val deleted = ArrayList<String>()
    val invalid = HashSet<String>()
    var locked = false
    var busy = false
    var created = 0
    var failSeal: (alias: String, aad: ByteArray) -> Boolean = { _, _ -> false }

    override fun cipher(alias: String, create: Boolean): MemoryCipher? {
        asked += alias
        if (busy) throw IllegalStateException("keystore busy")
        val k = keys[alias] ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it; created++ } else return null
        return object : MemoryCipher {
            override val alias = alias
            private fun check() {
                if (alias in invalid) throw MemoryKeyException(KeyProblem.GONE)
                if (locked && alias in unlockOnly) throw MemoryKeyException(KeyProblem.LOCKED)
            }
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
                check()
                if (failSeal(alias, aad)) throw MemoryKeyException(KeyProblem.LOCKED)
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

    override fun delete(alias: String) {
        keys.remove(alias)
        invalid.remove(alias)
        deleted += alias
    }
}
