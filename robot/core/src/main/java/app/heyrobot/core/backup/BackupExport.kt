// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.LogCode
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.termux.TermuxSettings
import org.json.JSONObject

// Pure. What Back up now writes for the files it doesn't carry as they are: messages.json without
// the apps' signer pins, termux.json without the trusted Termux, Memory without the items that hold a code (those go in
// Keys and private codes, or nowhere). Trust is never in a backup (§3.3), so a restore can't bring it back even from an
// older reader.

object BackupExport {
    /** messages.json as a backup carries it: the pins and the first-use list dropped (they are this phone's decisions). */
    fun messages(text: String?): String = MessagesFile.fromJson(text).copy(pins = emptyMap(), firstUse = emptySet()).toJson()

    /** termux.json without the trusted Termux (TermuxSettings.untrust: off until you trust it again on the new phone). */
    fun termux(text: String?): String =
        TermuxSettings.fromJson(try { text?.let(::JSONObject) } catch (_: Exception) { null }).untrust().toJson().toString()

    /** Memory split for a backup: [memory] what Memory carries, [codes] the items SecretDetector flags (Keys and private codes). */
    class MemorySplit(val memory: MemoryFile, val codes: MemoryFile) {
        override fun toString(): String = "MemorySplit(${memory.items.size} items, ${codes.items.size} codes)" // l10n: not text (toString for logs and tests)
    }

    fun memory(f: MemoryFile): MemorySplit {
        val (codes, rest) = f.items.partition { SecretDetector.isSecret(it.text) }
        return MemorySplit(f.copy(items = rest), MemoryFile(items = codes))
    }

    /** The categories as one number for the voice log (bit [BackupCategory.ordinal]). */
    fun bits(categories: Collection<BackupCategory>): Int = categories.fold(0) { acc, c -> acc or (1 shl c.ordinal) }
}

/** How a backup ended (voice log event 240, a). */
enum class BackupCode(override val id: Int, override val title: String) : LogCode {
    DONE_CHECKED(1, "saved and checked"),
    CANCELLED(2, "cancelled"),
    WRITE_FAILED(3, "couldn't be written there"),
    CHECK_FAILED(4, "didn't read back, deleted"),
    LOCKED(5, "the phone was locked"),
    MEMORY_UNREADABLE(6, "memory couldn't be opened"),
    FAILED(7, "failed"),
    /** 1.4: Robot Files was chosen and its list couldn't be opened (locked, its key gone, damaged). */
    FILES_UNREADABLE(8, "files couldn't be opened"),
    /** 1.4: over BackupCaps.MAX_TOTAL, refused before anything was written. */
    TOO_BIG(9, "too big"),
}
