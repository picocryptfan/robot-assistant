// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import java.nio.charset.StandardCharsets.UTF_8

// Pure. How lists are sealed (lists-plan.md §4.2, decision D1): memory's scheme with Lists' own frame and label. The
// encryption is not here and nothing new is composed: the vault (LS2, sys/ListsVault.kt) implements core memory's
// [MemoryCipher] with its Android Keystore AES-256-GCM keys (the Keystore's random 12-byte IV, a 16-byte tag) and says
// which aliases are Lists'; this file frames the cipher's output and decides what a file means, so it runs in JVM tests.
//
//   lists.enc:  "RLST", version 1, the key's alias (a length byte and UTF-8), then IV, ciphertext and tag
//   locked.enc: "RLSJ", the same after it (the journal, §4.6)
//
// The additional data is "robot-lists-v1|<file name>", so neither file opens under the other's name, and memory's file
// ("RMEM", "robot-memory-v1|…") opens as neither. As in memory's frame, the version byte and the alias are outside the
// additional data: a changed version reads as TooNew and a changed alias asks for another key; neither is ever written
// over, and the content can't be changed without the key.

/** What opening one of Lists' sealed files gave. Settings › Lists and the voice side say what each means. */
sealed class ListsLoad<out T> {
    data class Loaded<T>(val value: T) : ListsLoad<T>()
    /** No file yet. */
    data object Empty : ListsLoad<Nothing>()
    /** The key can't be used until you unlock: "Unlock your phone to hear your lists." Nothing is written. */
    data object Locked : ListsLoad<Nothing>()
    /** The key is gone (screen lock removed, or reset), or there is no key under the file's alias: kept, never deleted by Robot. */
    data object KeyGone : ListsLoad<Nothing>()
    /** Damaged, or not this file: "Robot couldn't read your lists", never written over. */
    data object Unreadable : ListsLoad<Nothing>()
    /** Written by a newer Robot (the frame's version or the JSON's "v"): never written over. */
    data class TooNew(val version: Int) : ListsLoad<Nothing>()

    /** Only after these may anything be written: a file Robot couldn't read is never written over. */
    val writable: Boolean get() = this is Loaded || this == Empty
}

object ListsEnvelope {
    const val FILE = "lists.enc"
    const val JOURNAL = "locked.enc"
    const val VERSION = 1
    private val MAGIC = "RLST".toByteArray(UTF_8)  // l10n: not text (the file's magic bytes)
    private val JOURNAL_MAGIC = "RLSJ".toByteArray(UTF_8)  // l10n: not text (the journal's magic bytes)
    private const val MAX_ALIAS = 64

    fun aad(fileName: String): ByteArray = "robot-lists-v1|$fileName".toByteArray(UTF_8) // l10n: not text (the cipher's additional data)

    /** lists.enc's bytes for [file]. Throws IllegalArgumentException when the file is over its cap (callers check [ListsCodec.fits]). */
    fun seal(file: ListsFile, cipher: MemoryCipher): ByteArray = frame(MAGIC, ListsCodec.encode(file), cipher, FILE)

    /** locked.enc's bytes for [journal]. Throws IllegalArgumentException over its cap (callers check [ListsJournal.fits]). */
    fun sealJournal(journal: ListsJournalFile, cipher: MemoryCipher): ByteArray = frame(JOURNAL_MAGIC, ListsJournal.encode(journal), cipher, JOURNAL)

    private fun frame(magic: ByteArray, plain: ByteArray, cipher: MemoryCipher, fileName: String): ByteArray {
        val alias = cipher.alias.toByteArray(UTF_8)
        require(alias.isNotEmpty() && alias.size <= MAX_ALIAS) { "key alias" } // l10n: not text (a programming error)
        return magic + byteArrayOf(VERSION.toByte(), alias.size.toByte()) + alias + cipher.seal(plain, aad(fileName))
    }

    /** The key alias in lists.enc's header, or null when the bytes aren't lists.enc. */
    fun alias(bytes: ByteArray?): String? = alias(bytes, MAGIC)

    /** The key alias in locked.enc's header, or null when the bytes aren't the journal. */
    fun journalAlias(bytes: ByteArray?): String? = alias(bytes, JOURNAL_MAGIC)

    private fun alias(bytes: ByteArray?, magic: ByteArray): String? {
        if (bytes == null || bytes.size < 7 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return null
        val n = bytes[5].toInt() and 0xFF
        if (n == 0 || n > MAX_ALIAS || bytes.size < 6 + n) return null
        return String(bytes, 6, n, UTF_8)
    }

    /** lists.enc's [bytes] opened with the cipher [cipherFor] gives for the header's alias (null: no such key). No bytes: no file. */
    fun open(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?): ListsLoad<ListsFile> =
        when (val p = plain(bytes, MAGIC, FILE, cipherFor)) {
            is ListsLoad.Loaded -> when (val parse = ListsCodec.decode(p.value)) {
                is ListsParse.Ok -> ListsLoad.Loaded(parse.file)
                is ListsParse.TooNew -> ListsLoad.TooNew(parse.version)
                ListsParse.Damaged -> ListsLoad.Unreadable
            }
            else -> p.notLoaded()
        }

    /** locked.enc's [bytes], as [open]. */
    fun openJournal(bytes: ByteArray?, cipherFor: (String) -> MemoryCipher?): ListsLoad<ListsJournalFile> =
        when (val p = plain(bytes, JOURNAL_MAGIC, JOURNAL, cipherFor)) {
            is ListsLoad.Loaded -> when (val parse = ListsJournal.decode(p.value)) {
                is JournalParse.Ok -> ListsLoad.Loaded(parse.journal)
                is JournalParse.TooNew -> ListsLoad.TooNew(parse.version)
                JournalParse.Damaged -> ListsLoad.Unreadable
            }
            else -> p.notLoaded()
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> ListsLoad<*>.notLoaded(): ListsLoad<T> = this as ListsLoad<T>

    /** The frame's plain bytes, or why there are none. */
    private fun plain(bytes: ByteArray?, magic: ByteArray, fileName: String, cipherFor: (String) -> MemoryCipher?): ListsLoad<ByteArray> {
        if (bytes == null || bytes.isEmpty()) return ListsLoad.Empty
        if (bytes.size < 6 || !bytes.copyOfRange(0, 4).contentEquals(magic)) return ListsLoad.Unreadable
        val version = bytes[4].toInt() and 0xFF
        if (version > VERSION) return ListsLoad.TooNew(version)
        if (version < 1) return ListsLoad.Unreadable
        val alias = alias(bytes, magic) ?: return ListsLoad.Unreadable
        val found = try {
            cipherFor(alias)
        } catch (e: MemoryKeyException) {
            return state(e.problem)
        }
        val cipher = found ?: return ListsLoad.KeyGone
        val body = bytes.copyOfRange(6 + alias.toByteArray(UTF_8).size, bytes.size)
        return try {
            ListsLoad.Loaded(cipher.open(body, aad(fileName)))
        } catch (e: MemoryKeyException) {
            state(e.problem)
        } catch (_: Exception) {
            ListsLoad.Unreadable
        }
    }

    private fun state(p: KeyProblem): ListsLoad<Nothing> = when (p) {
        KeyProblem.LOCKED -> ListsLoad.Locked
        KeyProblem.GONE -> ListsLoad.KeyGone
        KeyProblem.BAD_DATA -> ListsLoad.Unreadable
    }

    /**
     * One change to lists.enc: open [bytes], apply [change], seal with [cipher]. Returns the load and the bytes to write,
     * or null bytes when nothing may or need be written: the file couldn't be read (locked, key gone, damaged, newer; the
     * load says which), [change] gave null (refused), or the result is over its cap. With no file yet, [fresh] (the
     * first-use lists, ListsEdits.firstUse) is the file changed, and it is written even when [change] gives null, so the
     * default lists keep the ids they were first shown with.
     */
    fun update(bytes: ByteArray?, cipher: MemoryCipher, cipherFor: (String) -> MemoryCipher?, fresh: () -> ListsFile,
        change: (ListsFile) -> ListsFile?): Pair<ListsLoad<ListsFile>, ByteArray?> {
        val load = open(bytes, cipherFor)
        val current = when (load) {
            is ListsLoad.Loaded -> load.value
            ListsLoad.Empty -> fresh()
            else -> return load to null
        }
        val next = change(current) ?: if (load == ListsLoad.Empty) current else return load to null
        if (!ListsCodec.fits(next)) return load to null
        return ListsLoad.Loaded(next) to seal(next, cipher)
    }
}
