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

    /**
     * The everyday set's everyday_settings.json as a backup carries it: your music and maps apps' pins dropped (its package and
     * signer are this phone's decision; on a new phone you pick it again the first time you play by name). A file this
     * Robot can't read whole keeps its other keys as they are; one that isn't a JSON object isn't written (null), since
     * nothing can take a pin out of it.
     */
    fun everyday(text: String?): String? = when (val f = app.heyrobot.core.everyday.EverydaySettingsCodec.decode(text)) {
        is app.heyrobot.core.everyday.EverydaySettingsFile.Ok -> app.heyrobot.core.everyday.EverydaySettingsCodec.encode(f.read.copy(musicApp = null, podcastApp = null, mapsApp = null), f.extra)
        else -> try { JSONObject(text ?: "{}").apply { remove("musicApp"); remove("podcastApp"); remove("mapsApp") }.toString() } catch (_: Exception) { null }
    }

    /** Memory split for a backup: [memory] what Memory carries, [codes] the items SecretDetector flags (Keys and private codes). */
    class MemorySplit(val memory: MemoryFile, val codes: MemoryFile) {
        override fun toString(): String = "MemorySplit(${memory.items.size} items, ${codes.items.size} codes)" // l10n: not text (toString for logs and tests)
    }

    fun memory(f: MemoryFile): MemorySplit {
        val (codes, rest) = f.items.partition { SecretDetector.isSecret(it.text) }
        return MemorySplit(f.copy(items = rest), MemoryFile(items = codes))
    }

    /**
     * 1.4, Lists: lists.json as a backup carries it: the lists as the vault opened them (the journal's adds merged in first),
     * tombstones and someone else's lists included, in plain JSON inside the backup's own encryption. Never the sealed file.
     */
    fun lists(f: app.heyrobot.core.lists.ListsFile): ByteArray = app.heyrobot.core.lists.ListsCodec.encode(f)

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
    /** 1.4, Lists: Lists was chosen and the lists couldn't be opened (locked, their key gone, damaged, a newer Robot's). */
    LISTS_UNREADABLE(10, "lists couldn't be opened"),
    /** F20: read back whole, but a restore would leave a category out: the file deleted, the category named. */
    CHECK_REFUSED(11, "a restore would refuse a part, deleted"),
    /** F20: a category over what a restore takes, refused before anything was written. */
    OVER_CAP(12, "a part over what a restore takes"),
    /** H3: private events were chosen and couldn't be opened (locked, their key gone, damaged, a newer Robot's). */
    CALENDAR_UNREADABLE(13, "private events couldn't be opened"),
    /** seal-history-fix-b: History was chosen and a conversation can't be opened now (the key says not now, or a read failed). */
    HISTORY_UNREADABLE(14, "history couldn't be opened"),
    /** seal-history-fix-b: History was chosen and holds a newer Robot's file. */
    HISTORY_TOO_NEW(15, "history holds a newer Robot's file"),
    /** seal-notes: Notes was chosen and a note can't be opened now, or was saved by a newer Robot. */
    NOTES_UNREADABLE(16, "notes couldn't be opened"),
    /** seal-notes-b: Notes was chosen and holds a newer Robot's note. */
    NOTES_TOO_NEW(17, "notes hold a newer Robot's file"),
    /** seal-people: People was chosen and can't be opened now (the phone locked, the key busy, a read failed). */
    PEOPLE_UNREADABLE(18, "people couldn't be opened"),
    /** seal-people: People was chosen and is a newer Robot's file, or one that can't be read (Start over in People). */
    PEOPLE_TOO_NEW(19, "people is a newer Robot's file"),
    /** spoken-log: the Logbook was chosen and can't be opened now (the key says not now, a newer Robot's log, a read failed). */
    LOGBOOK_UNREADABLE(20, "the logbook couldn't be opened"),
    /** fix-seal-transcripts 6: Transcripts was chosen and one sealed under the unlock key can't be opened (the phone locked). */
    TRANSCRIPTS_LOCKED(21, "transcripts need the phone unlocked"),
    /** fix-seal-transcripts-b, Medium 1: Transcripts was chosen and the Keystore failed while the phone is unlocked. */
    TRANSCRIPTS_UNREADABLE(22, "transcripts couldn't be opened"),
}
