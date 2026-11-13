// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import app.heyrobot.core.writeAtomicBytes
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Main and voice processes. Signal later's questions (docs/plans/signal-later-plan.md): one file, files/later/later.enc,
// sealed as Lists and memory are (AES-256-GCM, an Android Keystore key of its own, robot_later_v1, made with
// setUnlockedDeviceRequired(true), so it can't be read while the phone is locked). Every change under a lock both
// processes take (later.lock), written atomically. Locked or the Keystore busy reads as "not now" (null), never as empty;
// a file its key can't open any more (the key gone, the data damaged) holds only questions, so it is dropped and nothing
// in it is sent. Nothing here logs a question.
class LaterVault(private val dir: File, private val keys: MemoryKeys) {
    val file: File get() = File(dir, FILE)

    /** The questions; null when they can't be read now (locked, the Keystore busy). */
    fun load(): List<LaterItem>? = locked { readNow() }

    /** [change] applied and saved; null when nothing could be read or written now (nothing changed then). */
    fun update(change: (List<LaterItem>) -> List<LaterItem>): List<LaterItem>? = locked {
        val cur = readNow() ?: return@locked null
        val next = change(cur)
        if (next == cur) return@locked cur
        if (next.isEmpty()) return@locked next.also { file.delete() }
        val sealed = try {
            keys.cipher(ALIAS, create = true)?.seal(Later.toJson(next).toByteArray(Charsets.UTF_8), AAD)
        } catch (_: Exception) { null } ?: return@locked null
        writeAtomicBytes(file, sealed)
        next
    }

    private fun readNow(): List<LaterItem>? {
        if (!file.isFile) return emptyList()
        return try {
            val c = keys.cipher(ALIAS, create = false) ?: return dropped()
            Later.fromJson(String(c.open(file.readBytes(), AAD), Charsets.UTF_8))
        } catch (e: MemoryKeyException) {
            if (e.problem == KeyProblem.LOCKED) null else dropped()
        } catch (_: java.io.IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun dropped(): List<LaterItem> = emptyList<LaterItem>().also { file.delete() }

    private fun <T> locked(block: () -> T): T = synchronized(LaterVault::class.java) {
        dir.mkdirs()
        FileChannel.open(File(dir, LOCK).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch -> ch.lock().use { block() } }
    }

    companion object {
        const val ALIAS = "robot_later_v1" // l10n: not text (a key alias)
        const val FILE = "later.enc" // l10n: not text (a file name)
        private const val LOCK = "later.lock" // l10n: not text (a file name)
        private val AAD = "robot-later-v1".toByteArray(Charsets.UTF_8) // l10n: not text (additional data)

        fun of(ctx: Context): LaterVault = LaterVault(File(ctx.filesDir, "later"), KeystoreMemoryKeys(setOf(ALIAS))) // l10n: not text (a folder name)
    }
}
