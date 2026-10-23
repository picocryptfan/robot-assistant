// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.LogCode
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TermuxStrings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Pure. A Termux result made fit to show and to give Gemma:
// ANSI escapes and control characters removed (newline and tab kept), invalid UTF-8 replaced, secrets masked, at most
// 100 KB kept (the end, as Termux keeps it), 16 KB for the card (its first and last 8 KB) and 40 lines / 1,500 characters
// for Gemma (the first 15 and last 25 lines), and the exit code in plain English and French (core i18n TermuxStrings; what
// Gemma reads is English). Termux's own error message is never shown or logged (it can hold the command): it is only
// matched to known causes ([TermuxFailure]).

/** The result bundle Termux sends back, as read by :app's receiver. */
data class TermuxResult(
    val stdout: String?,
    val stderr: String?,
    /** Null when Termux couldn't run anything (then [err] says why). */
    val exitCode: Int?,
    val err: Int = TermuxIntents.ERR_NONE,
    val errmsg: String? = null,
    /** Termux sends these as text: the lengths before it cut the output. */
    val stdoutOriginalLength: String? = null,
    val stderrOriginalLength: String? = null,
)

/** A result made fit for the card and for Gemma. */
data class TermuxOutputText(
    /** stdout and stderr, cleaned and masked: at most 16 KB, its first and last 8 KB. */
    val card: String,
    /** What Gemma gets: at most [TermuxSettings.outputLines] lines and [TermuxSettings.outputChars] characters. */
    val model: String,
    /** Characters kept (after the 100 KB cap), for "Show all (4.1 KB)" and the voice log's bucket. */
    val length: Int,
    val lines: Int,
    /** Termux or Robot cut the output. */
    val truncated: Boolean,
    /** How many secrets were hidden. */
    val masked: Int,
)

object TermuxOutput {
    /** What Robot keeps of one result at most (stdout and stderr together), like Termux's own cap. */
    const val MAX_CHARS = 100 * 1024
    const val CARD_HEAD = 8 * 1024
    const val CARD_TAIL = 8 * 1024

    /** Bytes as UTF-8, every invalid sequence replaced by U+FFFD. */
    fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(bytes)).toString()

    /**
     * Terminal escapes (CSI, OSC, DCS and the rest, 7- and 8-bit) and control characters removed, newline and tab kept;
     * CRLF read as a newline, and a lone carriage return (a progress bar redrawing its line) keeps only what came after
     * it; text-reordering and zero-width characters removed. One pass, linear.
     */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var lineStart = 0
        var k = 0
        val n = text.length
        while (k < n) {
            val c = text[k]
            when {
                c == '\u001b' -> k = skipEscape(text, k + 1)
                c == '\u009b' -> k = skipCsi(text, k + 1)
                c == '\u009d' || c == '\u0090' || c == '\u0098' || c == '\u009e' || c == '\u009f' -> k = skipString(text, k + 1)
                c == '\r' -> {
                    if (k + 1 < n && text[k + 1] == '\n') {
                        k++
                    } else {
                        // Back to the start of the line: what follows replaces it.
                        out.setLength(lineStart)
                        k++
                    }
                }
                c == '\n' -> { out.append('\n'); lineStart = out.length; k++ }
                c == '\t' -> { out.append(c); k++ }
                c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f' -> k++
                c in '​'..'‏' || c in '‪'..'‮' || c in '⁦'..'⁩' || c == '⁠' || c == '﻿' || c == '؜' -> k++
                else -> { out.append(c); k++ }
            }
        }
        return out.toString()
    }

    /** After ESC: a CSI (ESC [), a string (ESC ] P X ^ _ … ended by BEL or ESC \), or a short sequence. */
    private fun skipEscape(t: String, from: Int): Int {
        if (from >= t.length) return from
        return when (t[from]) {
            '[' -> skipCsi(t, from + 1)
            ']', 'P', 'X', '^', '_' -> skipString(t, from + 1)
            else -> {
                var k = from
                while (k < t.length && t[k] in ' '..'/') k++
                if (k < t.length) k + 1 else k
            }
        }
    }

    /** Parameter and intermediate bytes, then one final byte (@ to ~). */
    private fun skipCsi(t: String, from: Int): Int {
        var k = from
        while (k < t.length && t[k] in '0'..'?') k++
        while (k < t.length && t[k] in ' '..'/') k++
        return if (k < t.length && t[k] in '@'..'~') k + 1 else k
    }

    /** Up to BEL, ESC \ or the 8-bit ST; at most 4 KB, so a broken one can't swallow the rest. */
    private fun skipString(t: String, from: Int): Int {
        var k = from
        val stop = minOf(t.length, from + 4096)
        while (k < stop) {
            when {
                t[k] == '\u0007' || t[k] == '\u009c' -> return k + 1
                t[k] == '\u001b' && k + 1 < t.length && t[k + 1] == '\\' -> return k + 2
            }
            k++
        }
        return k
    }

    /** At most [max] characters, keeping the end (as Termux does); whether it cut anything. */
    fun capEnd(text: String, max: Int = MAX_CHARS): Pair<String, Boolean> =
        if (text.length <= max) text to false else text.substring(text.length - max) to true

    /** The card's text: whole up to 16 KB, else the first and last 8 KB with what is left out said in between ([l]: its languages). */
    fun forCard(text: String, l: Langs = Langs.EN): String {
        if (text.length <= CARD_HEAD + CARD_TAIL) return text
        val gone = text.length - CARD_HEAD - CARD_TAIL
        val note = TermuxStrings.notShown("size" to sizeText(gone)).render(l)
        return listOf(text.substring(0, CARD_HEAD), note, text.substring(text.length - CARD_TAIL)).joinToString("\n")
    }

    /** [forCard] in English ([fr]: in French). */
    fun forCard(text: String, fr: Boolean): String = forCard(text, langs(fr))

    /**
     * What Gemma reads: at most [maxLines] lines (the first 3/8 and the last 5/8, 15 and 25 by default, "… 312 lines not
     * shown …" between) and [maxChars] characters (the first 3/8 and the last 5/8 again). 1.1: in English whatever the
     * reply language (the model reads tool results in English); [fr] is kept for the callers that still pass it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forModel(text: String, maxLines: Int = TermuxSettings.DEFAULT_LINES, maxChars: Int = TermuxSettings.DEFAULT_CHARS, fr: Boolean = false): String {
        var lines = text.split('\n')
        if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
        var out = if (lines.size <= maxLines) lines.joinToString("\n") else {
            val head = maxLines * 3 / 8
            val tail = maxLines - head
            val gone = lines.size - head - tail
            (lines.take(head) + "… $gone lines not shown …" + lines.takeLast(tail)).joinToString("\n") // l10n: not text (what the model reads)
        }
        if (out.length > maxChars) {
            val head = maxChars * 3 / 8
            val tail = maxChars - head
            val gone = out.length - head - tail
            out = out.substring(0, head) + "\n… $gone characters not shown …\n" + out.substring(out.length - tail) // l10n: not text (what the model reads)
        }
        return out
    }

    /**
     * [result]'s stdout and stderr (labelled when both have text), cleaned, masked ([mask]: "Hide secrets in output",
     * [own]: Robot's own keys by value), capped. [truncated] also counts Termux's own cut (its original lengths). 1.1: the
     * card's labels ("[errors]", "[hidden: …]") in [l]'s language, the model's always in English.
     */
    fun prepare(result: TermuxResult, mask: Boolean = true, own: Collection<String> = emptyList(), maxLines: Int = TermuxSettings.DEFAULT_LINES,
        maxChars: Int = TermuxSettings.DEFAULT_CHARS, l: Langs = Langs.EN): TermuxOutputText {
        val out = clean(result.stdout.orEmpty())
        val err = clean(result.stderr.orEmpty())
        fun both(l: Langs) = when {
            out.isNotBlank() && err.isNotBlank() -> listOf(out.trimEnd('\n'), TermuxStrings.errorsLabel().render(l), err).joinToString("\n")
            err.isNotBlank() -> err
            else -> out
        }
        // Masked before the cap, so the cap can't cut a key in two and leave a part Robot no longer recognizes.
        val found = if (mask) SecretMask.find(both(Langs.EN), own).size else 0
        fun masked(l: Langs) = capEnd(both(l).let { if (mask && found > 0) SecretMask.mask(it, own, l) else it })
        val (model, cut) = masked(Langs.EN)
        val card = if (l.say == Lang.EN && l.pseudo == null) model else masked(l).first
        val termuxCut = cutByTermux(result.stdoutOriginalLength, result.stdout) || cutByTermux(result.stderrOriginalLength, result.stderr)
        return TermuxOutputText(forCard(card, l), forModel(model, maxLines, maxChars), model.length, model.count { it == '\n' } + if (model.isEmpty()) 0 else 1,
            cut || termuxCut, found)
    }

    /** [prepare] with the card's labels in English ([fr]: in French). */
    fun prepare(result: TermuxResult, mask: Boolean = true, own: Collection<String> = emptyList(), maxLines: Int = TermuxSettings.DEFAULT_LINES,
        maxChars: Int = TermuxSettings.DEFAULT_CHARS, fr: Boolean): TermuxOutputText = prepare(result, mask, own, maxLines, maxChars, langs(fr))

    private fun cutByTermux(original: String?, kept: String?): Boolean {
        val n = original?.trim()?.toIntOrNull() ?: return false
        return n > (kept?.length ?: 0)
    }

    /** "Exit 0 · 0.8 s" / « Code 0 · 0,8 s », the card's line after a run. */
    fun exitLineText(exit: Int, ms: Long): Phrase = TermuxStrings.exitLine("code" to exit.toString(), "time" to secondsText(ms))

    /** [exitLineText] in English ([fr]: in French). */
    fun exitLine(exit: Int, ms: Long, fr: Boolean = false): String = exitLineText(exit, ms).render(langs(fr))

    /**
     * The result in a sentence: "Done in 0.8 s.", "Took longer than 60 s and was stopped.", "Stopped.", "Command not
     * found (exit 127).", "Finished with exit code 2 in 1.2 s."; null [exit]: Termux didn't answer in time.
     */
    fun summaryText(exit: Int?, limitSeconds: Int, ms: Long): Phrase = when {
        exit == null -> TermuxStrings.noAnswer()
        exit == 0 -> TermuxStrings.doneIn("time" to secondsText(ms))
        exit == 124 -> TermuxStrings.tookLonger("n" to limitSeconds.toString())
        exit == 137 || exit == 143 -> TermuxStrings.stopped()
        exit == 126 -> TermuxStrings.notExecutable()
        exit == 127 -> TermuxStrings.notFound()
        exit in 129..192 -> TermuxStrings.endedBySignal("signal" to (exit - 128).toString(), "code" to exit.toString())
        else -> TermuxStrings.finished("code" to exit.toString(), "time" to secondsText(ms))
    }

    /** [summaryText] in English ([fr]: in French). */
    fun summary(exit: Int?, limitSeconds: Int, ms: Long, fr: Boolean = false): String = summaryText(exit, limitSeconds, ms).render(langs(fr))

    /** 0.8 s, 12 s, 2 min 5 s (French: 0,8 s). */
    fun secondsText(ms: Long): Phrase {
        val s = ms.coerceAtLeast(0) / 1000.0
        return when {
            s < 10 -> String.format(Locale.ROOT, "%.1f", s).let { TermuxStrings.secondsTenths("whole" to it.substringBefore('.'), "tenth" to it.substringAfter('.')) }
            s < 120 -> Phrase.plain("${s.toLong()} s")
            else -> Phrase.plain("${s.toLong() / 60} min ${s.toLong() % 60} s") // l10n: not text (a duration, the same in French)
        }
    }

    /** [secondsText] in English ([fr]: in French). */
    fun seconds(ms: Long, fr: Boolean = false): String = secondsText(ms).render(langs(fr))

    /** "512 characters", "3.5 KB" / « 3,5 Ko » (a KB of 1,024 characters). */
    fun sizeText(chars: Int): Phrase =
        if (chars < 1024) TermuxStrings.sizeChars("n" to chars.toString())
        else String.format(Locale.ROOT, "%.1f", chars / 1024.0).let { TermuxStrings.sizeKb("whole" to it.substringBefore('.'), "tenth" to it.substringAfter('.')) }

    private fun langs(fr: Boolean) = Langs.of(if (fr) Lang.FR else Lang.EN)
}

/**
 * Why Termux couldn't run a command (err ≠ -1, or no answer), in words ([text]; [en] and [fr] its English and French);
 * Termux's errmsg itself never surfaces. [title] is the voice log's.
 */
enum class TermuxFailure(override val id: Int, override val title: String) : LogCode {
    EXTERNAL_APPS_OFF(1, "allow-external-apps off"),
    NOT_SET_UP(2, "not set up"),
    NO_PERMISSION(3, "no permission"),
    NO_ANSWER(4, "no answer"),
    NOT_TRUSTED(5, "signer changed"),
    INTERNAL(6, "internal error"),
    /** 0.9: Android found no Termux to start (not installed, or a build without RunCommandService). */
    NOT_INSTALLED(7, "not installed"),
    /** 0.9: Settings › Termux's set-up isn't finished (rule 1: nothing runs until every line is green). */
    NOT_READY(8, "set-up not finished");

    val text: Phrase get() = when (this) {
        EXTERNAL_APPS_OFF -> TermuxStrings.failExternalApps("line" to "${TermuxSetup.EXTERNAL_APPS}=true", "path" to TermuxReason.SETTINGS) // l10n: not text (a property line)
        NOT_SET_UP -> TermuxStrings.failNotSetUp()
        NO_PERMISSION -> TermuxStrings.failNoPermission("path" to TermuxReason.SETTINGS)
        NO_ANSWER -> TermuxStrings.failNoAnswer()
        NOT_TRUSTED -> TermuxStrings.failNotTrusted("path" to TermuxReason.SETTINGS)
        INTERNAL -> TermuxStrings.failInternal()
        NOT_INSTALLED -> TermuxStrings.failNotInstalled("path" to TermuxReason.SETTINGS)
        NOT_READY -> TermuxStrings.failNotReady("path" to TermuxReason.SETTINGS)
    }

    val en: String get() = text.en()
    val fr: String get() = text.render(Langs.of(Lang.FR))

    fun text(fr: Boolean): String = if (fr) this.fr else en

    companion object {
        /** From Termux's err and errmsg (matched, never shown); null when there was no internal error. */
        fun of(err: Int, errmsg: String?): TermuxFailure? {
            if (err == TermuxIntents.ERR_NONE) return null
            val m = errmsg.orEmpty().take(25 * 1024).lowercase(Locale.ROOT)
            return when {
                "allow-external-apps" in m -> EXTERNAL_APPS_OFF
                "no such file" in m || "does not exist" in m || "not found" in m || "not a regular file" in m || "not executable" in m -> NOT_SET_UP
                else -> INTERNAL
            }
        }
    }
}
