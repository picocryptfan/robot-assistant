// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.RLog
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.readForUpdate
import java.io.File

// MAIN AND VOICE PROCESSES. What History's and Notes' sealed stores share (seal-notes): a folder of files sealed one by
// one under the store's own Keystore keys (sys/HistoryVault, sys/NotesVault). The files kept aside, the key asked for, a
// key Android invalidated replaced, and a pass that fails logged by code only. [kind] names the store in the log alone.

class SealedDir(val dir: File, private val keys: MemoryKeys, val kind: String) {
    fun files(name: (String) -> Boolean): List<File> = dir.listFiles { f -> f.isFile && name(f.name) }.orEmpty().sortedBy { it.name }

    /** A write cut part way leaves only its temporary file; the file it would have replaced is still whole. */
    fun dropCutWrites() = files { it.startsWith(".") && it.endsWith(".tmp") }.forEach { it.delete() }

    /** [alias]'s key; any failure as a [MemoryKeyException] with its problem (ListsVault.problemOf: Android's causeless invalidated key is GONE). */
    fun key(alias: String, create: Boolean): MemoryCipher? = try {
        keys.cipher(alias, create)
    } catch (e: MemoryKeyException) {
        throw e
    } catch (t: Throwable) {
        throw MemoryKeyException(ListsVault.problemOf(t), t)
    }

    /** [f] renamed to name.bad, or the first free of .bad2 to .bad9 (an older copy is never replaced). */
    fun aside(f: File) {
        for (n in 1..9) {
            val to = File(dir, f.name + if (n == 1) ".bad" else ".bad$n")
            if (!to.exists()) { if (f.renameTo(to)) RLog.i("${kind}_kept_aside", "n" to 1); return } // l10n: not text (a log code)
        }
    }

    /** Every kept-aside file (*.bad, *.bad2..9). */
    fun asideFiles(): List<File> = files { BAD.containsMatchIn(it) }

    /** How many items are kept aside: items (the name before its first dot), not files. */
    fun asideCount(): Int = asideFiles().map { it.name.substringBefore('.') }.toSet().size

    /**
     * [seal] (a seal with [alias]'s key). A key Android invalidated can never open what it sealed: every file of this store
     * ([ours] by name) whose header names [alias] ([aliasOf]) is kept aside (never deleted; [onAside] told first), the key
     * deleted and [seal] tried once more, which makes it again. "Not now" throws, and nothing is written.
     */
    fun <T> rekeyingOnGone(alias: String, code: String, ours: (String) -> Boolean, aliasOf: (ByteArray?) -> String?, onAside: (File) -> Unit, seal: () -> T): T = try {
        seal()
    } catch (e: MemoryKeyException) {
        if (e.problem != KeyProblem.GONE) throw e
        for (f in files(ours)) if (aliasOf(readForUpdate(f)) == alias) { onAside(f); aside(f) }
        keys.delete(alias)
        RLog.i("${kind}_key_replaced", "key" to RLog.Code(code)) // l10n: not text (a log code)
        seal()
    }

    /** [block], or [fallback] when it fails: logged by the problem's code or the exception's class, never an item's words. */
    inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        dir.mkdirs()
        block()
    } catch (e: MemoryKeyException) {
        RLog.i("${kind}_key", "why" to RLog.Code(e.problem.name)) // l10n: not text (a log code)
        fallback
    } catch (t: Exception) {
        RLog.w("${kind}_vault", t) // l10n: not text (a log code)
        fallback
    }

    private companion object {
        val BAD = Regex("""\.bad\d?$""")
    }
}
