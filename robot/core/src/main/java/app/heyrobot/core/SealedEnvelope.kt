// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import kotlin.text.Charsets.UTF_8

/** What opening one sealed file gave: History's and Notes' (seal-notes: one framing, the store's own magic and kind). */
sealed class SealedOpen<out T> {
    /** [base]: in a journal, how many of the sealed copy's parts come before these (0: the whole thing). */
    data class Ok<T>(val value: T, val base: Int = 0) : SealedOpen<T>()
    /** The key needs the phone unlocked, or the Keystore is busy: not now, never a reason to move or drop the file. */
    data object Locked : SealedOpen<Nothing>()
    /** Android invalidated the key (the screen lock removed, say), or there is no key under the alias: it never opens again. */
    data object KeyGone : SealedOpen<Nothing>()
    /** Damaged, cut, sealed by another key, or not this store's file. */
    data object Unreadable : SealedOpen<Nothing>()
    /** Written by a newer Robot: left as it is. */
    data class TooNew(val version: Int) : SealedOpen<Nothing>()
}

/**
 * One item sealed on its own (seal-history, shared with Notes by seal-notes): the 4-byte [magic], a version byte, the key
 * alias's length and the alias, a journal's base (4 bytes, big-endian; 0 otherwise), then the cipher's output (its IV, the
 * AES-256-GCM text and tag). The additional data is "robot-<kind>-v1|<file name>|" and the header, so a file renamed to
 * another id, or a journal renamed to a sealed file, never opens, and a changed alias or version byte fails the tag.
 */
class SealedEnvelope(magic: String, private val kind: String, val version: Int) {
    private val magic = magic.toByteArray(UTF_8).also { require(it.size == 4) { "magic" } } // l10n: not text (a programming error)

    fun aad(fileName: String, header: ByteArray): ByteArray = "robot-$kind-v1|$fileName|".toByteArray(UTF_8) + header // l10n: not text (the cipher's additional data)

    fun seal(plain: ByteArray, cipher: MemoryCipher, fileName: String, base: Int = 0): ByteArray {
        val alias = cipher.alias.toByteArray(UTF_8)
        require(alias.isNotEmpty() && alias.size <= MAX_ALIAS) { "key alias" } // l10n: not text (a programming error)
        require(base >= 0) { "base" } // l10n: not text (a programming error)
        val header = magic + byteArrayOf(version.toByte(), alias.size.toByte()) + alias + java.nio.ByteBuffer.allocate(4).putInt(base).array()
        return header + cipher.seal(plain, aad(fileName, header))
    }

    /** The key alias in [bytes]' header, or null when it isn't this store's file. */
    fun alias(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size < 7 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return null
        val n = bytes[5].toInt() and 0xFF
        if (n == 0 || n > MAX_ALIAS || bytes.size < 6 + n) return null
        return String(bytes, 6, n, UTF_8)
    }

    /**
     * [bytes] (a file named [fileName]) opened with the cipher [cipherFor] gives for the header's alias (null: no such key; a
     * [MemoryKeyException] says which problem) and read by [parse] (null: [SealedOpen.Unreadable]).
     */
    fun <T> open(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?, fileName: String, parse: (ByteArray) -> T?): SealedOpen<T> {
        if (bytes == null || bytes.size < 6 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return SealedOpen.Unreadable
        val v = bytes[4].toInt() and 0xFF
        // A newer Robot's only when it doesn't open as this one's with the byte put back: a damaged byte is damage, kept aside.
        if (v > version) return if (open(bytes.copyOf().also { it[4] = version.toByte() }, cipherFor, fileName, parse) is SealedOpen.Ok) SealedOpen.Unreadable
            else SealedOpen.TooNew(v)
        if (v < version) return SealedOpen.Unreadable
        val alias = alias(bytes) ?: return SealedOpen.Unreadable
        val headerSize = 6 + alias.toByteArray(UTF_8).size + 4
        if (bytes.size <= headerSize) return SealedOpen.Unreadable
        val base = java.nio.ByteBuffer.wrap(bytes, headerSize - 4, 4).int
        if (base < 0) return SealedOpen.Unreadable
        val body = bytes.copyOfRange(headerSize, bytes.size)
        val plain = try {
            val cipher = cipherFor(alias) ?: return SealedOpen.KeyGone
            cipher.open(body, aad(fileName, bytes.copyOfRange(0, headerSize)))
        } catch (e: MemoryKeyException) {
            return when (e.problem) {
                KeyProblem.LOCKED -> SealedOpen.Locked
                KeyProblem.GONE -> SealedOpen.KeyGone
                KeyProblem.BAD_DATA -> SealedOpen.Unreadable
            }
        } catch (_: Exception) {
            return SealedOpen.Unreadable
        }
        return parse(plain)?.let { SealedOpen.Ok(it, base) } ?: SealedOpen.Unreadable
    }

    /** The id a file's [name] holds and whether it is a journal entry (<id>.locked.enc), or null for any other name. */
    fun idOf(name: String): Pair<String, Boolean>? = when {
        name.endsWith(LOCKED) -> name.removeSuffix(LOCKED) to true
        name.endsWith(SEALED) -> name.removeSuffix(SEALED) to false
        else -> null
    }?.takeIf { (id, _) -> ID.matches(id) }

    companion object {
        /** An item sealed under the store's unlock-only key. */
        const val SEALED = ".enc"
        /** The same, written while the phone was locked, under the store's lock-screen key: merged at the next unlock. */
        const val LOCKED = ".locked.enc"
        /** The plain file of Robot 1.2 and earlier, sealed at the first unlock and then removed. */
        const val PLAIN = ".json"
        private const val MAX_ALIAS = 64
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
