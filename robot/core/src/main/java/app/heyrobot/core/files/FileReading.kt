// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.BrainError
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.StrictJson
import app.heyrobot.core.web.Invisible
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

// Pure. 1.4, Robot Files (files-1.4-plan.md §4.1 steps 6 and 7, §4.2): what Robot keeps of the model's FILE_READ reply.
// The reply is data from a model that just read a file anyone could have written, so it is checked strictly and nothing
// in it is trusted:
//
// - It must be one JSON object, strict JSON (core StrictJson: RFC 8259, no key twice, nothing after it), alone or inside
//   one Markdown fence (```json … ```: every one of F0a's 161 replies had one). Prose around it, two objects, an array or
//   a string is no reading at all.
// - Only FilePrompt's keys are read; any other key is dropped (counted, never read), whatever it says ("action",
//   "delete", "tools").
// - Each value must be of its type (a string or null; a number too where an amount or a date may be one, as F0a saw for
//   `total` and `taxes`). A value of another type (an object, a list, true) drops that field, not the others: every value
//   is re-read before use anyway, so one odd field costs only itself.
// - Then each value is re-read: `kind` through a fixed table of words (FileKind, never DANGEROUS), `title` and `merchant`
//   cut to 40 letters, digits, spaces and &'.- (§4.2), `currency` to a code from a fixed list (a bare "$" is no
//   currency: FileFields decides it, F0a's lesson), `text` (a picture's) to 1,500 characters of clean text. Amounts,
//   dates, the period and the slip come out as [Unread]: one clean line of at most 64 characters, which nothing can use
//   without a parser (FileFields, F6, re-reads every amount and date against the file's text: §4.1 step 7).
//
// A reply that isn't a reading gives null, never an exception. What reaches the conversation: nothing (§4.2).

/**
 * A value from the model that Robot must re-read before using it (an amount, a date). Its text is reachable only through a
 * parser ([readBy]), and it never prints (a log line can't carry it).
 */
@JvmInline
value class Unread(private val raw: String) {
    /** [parser]'s reading of the model's text (Robot's own: FileFields), or null when it reads nothing. */
    fun <T : Any> readBy(parser: (String) -> T?): T? = parser(raw)

    override fun toString(): String = "Unread"
}

/**
 * One FILE_READ reading, every value checked as above; null fields were absent, null, of the wrong type or empty once
 * cleaned. [dropped]: keys outside FilePrompt's that were dropped, and fields of the wrong type (a count, for the log).
 */
data class FileReading(
    val kind: FileKind? = null,
    val title: String? = null,
    val merchant: String? = null,
    val date: Unread? = null,
    val total: Unread? = null,
    val currency: String? = null,
    val taxes: Unread? = null,
    val due: Unread? = null,
    val period: Unread? = null,
    val expiry: Unread? = null,
    val slip: Unread? = null,
    val text: String? = null,
    val dropped: Int = 0,
) {
    /** How many fields hold a value. */
    val fields: Int get() = listOf(kind, title, merchant, date, total, currency, taxes, due, period, expiry, slip, text).count { it != null }

    // Never the model's words in a log line.
    override fun toString(): String = "FileReading(fields=$fields, dropped=$dropped)" // l10n: not text (counts for a log)

    companion object {
        /** The longest reply read at all (a 700-chunk reply is far less). */
        const val MAX_REPLY_CHARS = 16_000
        /** `title` and `merchant` (§4.2). */
        const val MAX_NAME = 40
        /** An amount, a date, the period or the slip, before Robot's parsers read it. */
        const val MAX_VALUE = 64
        /** A picture's `text` (§4.2). */
        const val MAX_TEXT = 1_500
        /** A number's size past which it is no amount or date (and its text would be nonsense). */
        private val MAX_NUMBER = BigDecimal("1e12")

        private val FENCE = Regex("^```[A-Za-z]{0,10}[ \\t]*\\r?\\n(.*?)\\r?\\n[ \\t]*```$", RegexOption.DOT_MATCHES_ALL)
        private val ONE_LINE_FENCE = Regex("^```[A-Za-z]{0,10}[ \\t]*(\\{.*\\})[ \\t]*```$", RegexOption.DOT_MATCHES_ALL)

        /**
         * The reading in [reply] to a FILE_READ of a picture ([picture]: `text` is read) or of a text layer (it isn't: Robot
         * has the file's own text). Null when [reply] isn't one strict JSON object or none of its fields holds a value.
         */
        fun parse(reply: String, picture: Boolean): FileReading? {
            if (reply.length > MAX_REPLY_CHARS) return null
            val body = unfence(reply.trim())
            val map = StrictJson.parse(body)?.value as? Map<*, *> ?: return null
            val keys = if (picture) FilePrompt.PICTURE_KEYS else FilePrompt.TEXT_KEYS
            var dropped = map.keys.count { it !in keys }
            fun bad(): Nothing? { dropped++; return null }
            fun str(k: String): String? = when (val v = map[k]) {
                null -> null
                is String -> v
                else -> bad()
            }
            fun unread(k: String): Unread? = when (val v = map[k]) {
                null -> null
                is String -> oneLine(v, MAX_VALUE)?.let(::Unread)
                is Long, is Double -> number(v)?.let(::Unread) ?: bad()
                else -> bad()
            }
            val r = FileReading(
                kind = str("kind")?.let(::kindOf),
                title = str("title")?.let(::name),
                merchant = str("merchant")?.let(::name),
                date = unread("date"),
                total = unread("total"),
                currency = str("currency")?.let(::currencyOf),
                taxes = unread("taxes"),
                due = unread("due"),
                period = unread("period"),
                expiry = unread("expiry"),
                slip = unread("slip"),
                text = if (picture) str("text")?.let(::text) else null,
                dropped = 0,
            )
            return r.copy(dropped = dropped).takeIf { it.fields > 0 }
        }

        /** [s] without one Markdown fence around the whole of it (```json, then the object, then ```), else as it is. */
        private fun unfence(s: String): String {
            if (!s.startsWith("```")) return s
            FENCE.matchEntire(s)?.let { return it.groupValues[1].trim() }
            ONE_LINE_FENCE.matchEntire(s)?.let { return it.groupValues[1].trim() }
            return s
        }

        /** A number's plain text ("57.4", "66"), or null when it isn't finite or is past [MAX_NUMBER]. */
        private fun number(v: Any): String? {
            val d = when (v) {
                is Long -> BigDecimal.valueOf(v)
                is Double -> if (v.isFinite()) BigDecimal(v.toString()) else return null
                else -> return null
            }
            if (d.abs() >= MAX_NUMBER) return null
            return d.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
        }

        /** Invisible and control characters out, white space made single spaces, trimmed, at most [max] characters. */
        private fun oneLine(s: String, max: Int): String? =
            Invisible.strip(s).replace(Regex("\\s+"), " ").trim().take(max).trim().takeIf { it.isNotEmpty() }

        /** `title` or `merchant`: letters, digits, spaces and &'.- only, at most [MAX_NAME] (§4.2). */
        private fun name(s: String): String? {
            val kept = Invisible.strip(s).map { c -> if (c.isLetterOrDigit() || c in "&'.-") c else ' ' }.joinToString("")
            return kept.replace(Regex(" +"), " ").trim().take(MAX_NAME).trim().takeIf { it.isNotEmpty() }
        }

        /** A picture's `text`: clean lines (FileQuote.clean's rules), at most [MAX_TEXT] characters. */
        private fun text(s: String): String? = FileQuote.clean(s, MAX_TEXT)?.text

        private fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("[\\s_]+"), " ").trim()

        /**
         * The kinds a reading may give, by the model's word (folded: lower case, no accents, "_" as a space). A word not
         * here is no kind; DANGEROUS, UNKNOWN, VIDEO and TRANSCRIPT never come from a reading (sniffing and Robot decide those).
         */
        private val KINDS: Map<String, FileKind> = mapOf(
            "receipt" to FileKind.RECEIPT, "recu" to FileKind.RECEIPT, "sales receipt" to FileKind.RECEIPT,
            "bill" to FileKind.BILL, "invoice" to FileKind.BILL, "facture" to FileKind.BILL, "statement" to FileKind.BILL,
            "document" to FileKind.DOCUMENT, "contract" to FileKind.DOCUMENT, "lease" to FileKind.DOCUMENT, "letter" to FileKind.DOCUMENT,
            "id" to FileKind.ID, "identity document" to FileKind.ID, "passport" to FileKind.ID, "licence" to FileKind.ID,
            "license" to FileKind.ID, "driver's licence" to FileKind.ID, "driver's license" to FileKind.ID, "health card" to FileKind.ID,
            "tax slip" to FileKind.TAX_SLIP, "slip" to FileKind.TAX_SLIP, "feuillet" to FileKind.TAX_SLIP,
            "ticket" to FileKind.TICKET, "booking" to FileKind.TICKET, "reservation" to FileKind.TICKET, "boarding pass" to FileKind.TICKET,
            "medical" to FileKind.MEDICAL, "prescription" to FileKind.MEDICAL,
            "photo" to FileKind.PHOTO, "picture" to FileKind.PHOTO,
            "note" to FileKind.NOTE,
            "other" to FileKind.OTHER,
        )

        private fun kindOf(s: String): FileKind? = KINDS[fold(Invisible.strip(s))]

        /** Currency codes a reading may give (Canada's first; the rest a short fixed list). */
        private val CODES = setOf("CAD", "USD", "EUR", "GBP", "CHF", "JPY", "AUD", "NZD", "MXN", "CNY", "HKD", "INR", "SEK", "NOK", "DKK")
        private val SYMBOLS = mapOf("€" to "EUR", "£" to "GBP", "CA$" to "CAD", "C$" to "CAD", "CAN$" to "CAD", "US$" to "USD")

        /** A code from [CODES] or [SYMBOLS]; a bare "$" (Canada's or the US's?) or anything else is none. */
        private fun currencyOf(s: String): String? {
            val t = Invisible.strip(s).replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
            return t.takeIf { it in CODES } ?: SYMBOLS[t]
        }
    }
}

/**
 * What a FILE_READ came to, for filing (F6's FilingWork): a [Read]ing; [Unreadable], an answer that isn't a reading (a
 * bad or cut reply, a picture :brain refused as malformed, a text too long for this model); [NoVision], the model loaded
 * can't see pictures (BrainError.NO_VISION: your own GGUF file, perhaps a tiny model), so the file waits for one that can
 * ("Reading pictures needs Fast or Smarter", §4.2); [NotNow], nothing came to a verdict (no model, busy, its process
 * stopped, timed out, cancelled): try again later, never "unreadable".
 */
sealed class FileReadOutcome {
    data class Read(val reading: FileReading) : FileReadOutcome()
    data object Unreadable : FileReadOutcome()
    data object NoVision : FileReadOutcome()
    data object NotNow : FileReadOutcome()

    companion object {
        /** [result] of a FILE_READ of a picture ([picture]) or of a text layer. */
        fun of(result: OneShotResult, picture: Boolean): FileReadOutcome = when (result) {
            is OneShotResult.Done -> when (result.reason) {
                DoneReason.END -> FileReading.parse(result.text, picture)?.let(::Read) ?: Unreadable
                DoneReason.CANCELLED -> NotNow
                // Cut at its cap (half a JSON object), garbage, a tool call in a conversation with none: no reading.
                DoneReason.LENGTH, DoneReason.GARBAGE, DoneReason.TOOL_CALLS, DoneReason.CONTEXT_FULL -> Unreadable
            }
            is OneShotResult.Failed -> when (result.code) {
                BrainError.NO_VISION -> NoVision
                BrainError.BAD_REQUEST, BrainError.TOO_LONG -> Unreadable
                BrainError.NOT_LOADED, BrainError.BUSY, BrainError.ENGINE, BrainError.STOPPED, null -> NotNow
            }
            OneShotResult.NotStarted, OneShotResult.Cancelled, is OneShotResult.TimedOut -> NotNow
        }
    }
}
