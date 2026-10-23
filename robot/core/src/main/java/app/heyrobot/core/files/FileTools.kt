// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.ScreenText
import app.heyrobot.core.memory.SecretDetector
import app.heyrobot.core.web.Invisible
import java.time.LocalDate
import java.time.ZoneId

// Pure. 1.4, Robot Files and the model (files-1.4-plan.md §5, D7, D8; job F9): what find_files gives the model, which file
// read_file means, and which files are private.
//
//   find_files  Up to 3 matches, each a number (1 to 3), its name, kind, drawer, date and a few fields (merchant, total,
//               currency, due, expiry), every value one line of at most 40 characters (a name 60), invisible characters and
//               chat-template markers out: data, and a content record (every later action in the conversation follows
//               Safety cards). Never a file's text. The count and the totals by currency are code's (FileViews.totals over
//               every match, not only the three), with the totals to check left out and counted. A private file (below)
//               gives no name and no field: only its number, kind, drawer and date.
//   read_file   The file a number from this conversation's last find_files names, or words naming exactly one file. The
//               answer is FILE_ANSWER's (FilePrompt.answer), a tool-free one-shot over the file's text as the index keeps it
//               (FileLimits.MAX_TEXT): the model's turn ends at the call, as at web_search.
//   private     Medical files, IDs, and any file whose name, fields or text SecretDetector flags (card numbers, social
//               insurance numbers, passwords), and a DANGEROUS file (never quoted at all). Never to a helper, even on a card
//               (FilesForHelper); read locally, their answer is never kept in History nor read aloud.

/**
 * read_file's handover (the model's turn ends there, Executed.file): the file [id] to answer [question] from. Its text is
 * read again from the index when the answer is made, behind the lock gate. [private]: FilesForHelper refuses it.
 */
data class FileAsk(val id: FileId, val question: String, val kind: FileKind, val private: Boolean) {
    override fun toString(): String = "FileAsk($kind, private=$private)" // l10n: not text (never a question or a name in a log)
}

/** The one gate before any file goes to a helper (§5.2, §5.5): no switch, no trust and no tap opens it. */
object FilesForHelper {
    /** Kinds that never leave the phone for a helper: "Robot keeps medical files and IDs on the phone." */
    val NEVER: Set<FileKind> = setOf(FileKind.MEDICAL, FileKind.ID, FileKind.DANGEROUS)

    /** [e] may go to a helper on a card: not a medical file, an ID or a dangerous one, and nothing secret in its words. */
    fun allowed(e: FileEntry): Boolean = e.kind !in NEVER && !secret(e)

    /** [e]'s name, a field or its text holds what memory's secret rule keeps (a card number, a SIN, a password). */
    fun secret(e: FileEntry): Boolean =
        SecretDetector.isSecret(e.name) || e.fields.values.any { SecretDetector.isSecret(it.value) } || SecretDetector.isSecret(e.text)

    /** Private: never to a helper, never said aloud or kept in History from read_file. */
    fun private(e: FileEntry): Boolean = !allowed(e)

    /**
     * A file's helper card (§5.2): what it would send, or why nothing of it ever goes. This is the one place a file's words
     * are made into a helper's block: its text framed by FileQuote (information only, never instructions), at most [maxChars]
     * characters. A tap on the card is the only way it goes (Personal.FILES, HelperTrust); a medical file, an ID, a dangerous
     * file or one with a secret is refused whatever you say.
     */
    fun card(e: FileEntry, maxChars: Int = FilePrompt.ANSWER_TEXT_CHARS): FileForHelper {
        when {
            e.kind == FileKind.DANGEROUS -> return FileForHelper.Refused(app.heyrobot.core.i18n.FilesStrings.dangerous())
            e.kind in NEVER -> return FileForHelper.Refused(app.heyrobot.core.i18n.FilesStrings.helperKeeps())
            secret(e) -> return FileForHelper.Refused(app.heyrobot.core.i18n.FilesStrings.helperKeepsSecret())
        }
        val frame = FileQuote.frame(e.kind, e.from.source) ?: return FileForHelper.Refused(app.heyrobot.core.i18n.FilesStrings.dangerous())
        val text = FileQuote.clean(e.text, maxChars) ?: return FileForHelper.Refused(app.heyrobot.core.i18n.FilesStrings.helperNoText())
        val name = FileTools.value(e.name, FileTools.MAX_NAME) ?: app.heyrobot.core.i18n.FilesStrings.noName().en()
        return FileForHelper.Card(FileQuote.block(frame, text.text), app.heyrobot.core.i18n.FilesStrings.sendsFileText("name" to name))
    }
}

/** A file's helper card: refused (and why, in words), or the block a tap would send and the card's line for it. */
sealed class FileForHelper {
    data class Refused(val why: app.heyrobot.core.i18n.Phrase) : FileForHelper()

    /** [block]: the file's text after FileQuote's frame, in its quotes; [sends]: the card's line ("the text of the file …"). */
    data class Card(val block: String, val sends: app.heyrobot.core.i18n.Phrase) : FileForHelper() {
        override fun toString(): String = "Card(${block.length})" // l10n: not text (never a file's words in a log)
    }
}

/** Which file read_file means. */
sealed class FilePick {
    data class One(val entry: FileEntry) : FilePick()
    /** A number this conversation's find_files didn't give (none yet, or out of range). */
    data object NoNumber : FilePick()
    /** No file, or several, match the words. */
    data class Unclear(val matches: Int) : FilePick()
}

object FileTools {
    /** find_files' matches given to the model, at most (§5.1). */
    const val SHOWN = 3
    /** A field's value given to the model, at most (§5.4). */
    const val MAX_VALUE = 40
    /** A name given to the model, at most (a sender's file name can be long). */
    const val MAX_NAME = 60
    /** The fields find_files gives, in this order. */
    val FIELDS = listOf("merchant", "total", "currency", "due", "expiry")

    /** The kind's word the model reads (fixed, English). */
    fun kindWord(k: FileKind): String = when (k) {
        FileKind.RECEIPT -> "receipt" // l10n: not text (the model's tool result)
        FileKind.BILL -> "bill" // l10n: not text (the model's tool result)
        FileKind.DOCUMENT -> "document" // l10n: not text (the model's tool result)
        FileKind.ID -> "ID" // l10n: not text (the model's tool result)
        FileKind.TAX_SLIP -> "tax slip" // l10n: not text (the model's tool result)
        FileKind.TICKET -> "ticket or booking" // l10n: not text (the model's tool result)
        FileKind.MEDICAL -> "medical file" // l10n: not text (the model's tool result)
        FileKind.PHOTO -> "photo" // l10n: not text (the model's tool result)
        FileKind.VIDEO -> "video" // l10n: not text (the model's tool result)
        FileKind.NOTE -> "note" // l10n: not text (the model's tool result)
        FileKind.TRANSCRIPT -> "transcript" // l10n: not text (the model's tool result)
        FileKind.DANGEROUS -> "app installer or script, never opened" // l10n: not text (the model's tool result)
        FileKind.OTHER, FileKind.UNKNOWN -> "file" // l10n: not text (the model's tool result)
    }

    /** The drawer's name the model reads: a starter drawer's in English, yours as you named it, or Inbox. */
    fun drawerWord(e: FileEntry): String = e.drawer?.let { d ->
        if (d in Drawers.STARTER) app.heyrobot.core.i18n.FilesStrings.drawer(d).en() else value(d, MAX_VALUE)
    } ?: "Inbox" // l10n: not text (the model's tool result)

    /** A value from a file (a name, a merchant: content) as one line of at most [max]: invisible characters and markers out. */
    fun value(s: String, max: Int = MAX_VALUE): String? {
        val one = ScreenText.sanitize(Invisible.strip(s)).replace(Regex("\\s+"), " ").replace("\"\"\"", "").trim() // l10n: not text (a pattern)
        if (one.isEmpty()) return null
        return if (one.length <= max) one else app.heyrobot.core.ToolCalls.safeTake(one, max - 1).trimEnd() + "…"
    }

    /** find_files' results: [shown] (the best [SHOWN]), numbered from 1, never a text; a private file without name or fields. */
    fun results(shown: List<FileEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Map<String, Any?>> = shown.take(SHOWN).mapIndexed { i, e ->
        buildMap {
            put("number", i + 1)
            val private = FilesForHelper.private(e)
            if (!private) value(e.name, MAX_NAME)?.let { put("name", it) }
            put("kind", kindWord(e.kind))
            put("drawer", drawerWord(e))
            put("date", FileViews.day(e, zone).toString())
            if (private) put("private", true)
            else for (f in FIELDS) e.fields[f]?.value?.let { v -> value(v)?.let { put(f, it) } }
            if (!private && e.fields["check"]?.value == "total") put("total_unchecked", true) // l10n: not text (a field's code)
        }
    }

    /**
     * What the model reads beside the results: how many match and the totals by currency, by code ("5 files match; the best 3
     * are in results. Totals: 312.40 CAD in 4 files. 1 total needs checking and is left out.").
     */
    fun summary(all: List<FileEntry>): String = buildString {
        val n = all.size
        append(if (n == 1) "1 file matches" else "$n files match") // l10n: not text (the model's tool result)
        if (n > SHOWN) append("; the best $SHOWN are in results") // l10n: not text (the model's tool result)
        append('.')
        val t = FileViews.totals(all)
        if (t.byCurrency.isNotEmpty()) {
            append(" Totals, added by Robot: ") // l10n: not text (the model's tool result)
            append(t.byCurrency.entries.joinToString("; ") { (cur, v) ->
                "${FileFields.plain(v.first)}${cur?.let { " $it" } ?: ""} in ${v.second} ${if (v.second == 1) "file" else "files"}" // l10n: not text (the model's tool result)
            })
            append('.')
        }
        if (t.check > 0) append(" ${t.check} ${if (t.check == 1) "total needs" else "totals need"} checking and ${if (t.check == 1) "is" else "are"} left out.") // l10n: not text (the model's tool result)
        if (all.take(SHOWN).any(FilesForHelper::private)) append(" Private files show no name.") // l10n: not text (the model's tool result)
    }

    /**
     * The file read_file's [arg] names: a number (1 to 3) from [numbered] (this conversation's last find_files), or words that
     * name exactly one file in [idx] (FileSearch). [hidden]: files waiting for a delete's Undo.
     */
    fun pick(arg: String, numbered: List<FileId>, idx: FileIndex, hidden: Set<FileId> = emptySet(), yours: Collection<String> = emptyList(),
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): FilePick {
        val a = arg.trim().removePrefix("#").removePrefix("number ").removePrefix("file ").trim() // l10n: not text (words the model writes)
        a.toIntOrNull()?.let { n ->
            val id = numbered.getOrNull(n - 1) ?: return FilePick.NoNumber
            val e = idx.entry(id)?.takeIf { it.id !in hidden && !it.damaged } ?: return FilePick.Unclear(0)
            return FilePick.One(e)
        }
        // Words: the one file they name, or the one of this conversation's results among those they name.
        val q = FileSearch.parse(a, yours = yours, today = today)
        if (q.empty) return FilePick.Unclear(0)
        val found = FileSearch.find(idx, q, hidden, zone, limit = SHOWN + 1)
        if (found.size == 1) return FilePick.One(found.single())
        found.filter { it.id in numbered }.singleOrNull()?.let { return FilePick.One(it) }
        return FilePick.Unclear(found.size)
    }
}
