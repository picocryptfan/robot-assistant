// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tests only: AES-256-GCM with a software key, as the Keystore's cipher behaves (a random 12-byte IV per seal, IV then
 * ciphertext and tag, the additional data authenticated), so a changed byte or a wrong file name really fails. [problem]
 * makes it act locked or gone.
 */
internal class SoftCipher(override val alias: String, private val key: SecretKey = newKey()) : MemoryCipher {
    var problem: KeyProblem? = null

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        problem?.let { throw MemoryKeyException(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        check(c.iv.size == 12)
        return c.iv + ct
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        problem?.let { throw MemoryKeyException(it) }
        if (sealed.size <= 12) throw MemoryKeyException(KeyProblem.BAD_DATA)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            c.updateAAD(aad)
            c.doFinal(sealed, 12, sealed.size - 12)
        } catch (e: AEADBadTagException) {
            throw MemoryKeyException(KeyProblem.BAD_DATA, e)
        }
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/** Tests only: ids 1, 2, 3… as 32 hex characters. */
internal class CountingIds(private var next: Int = 0x1000) : () -> ListsId {
    override fun invoke(): ListsId = ListsId.of("%032x".format(next++))!!
}
