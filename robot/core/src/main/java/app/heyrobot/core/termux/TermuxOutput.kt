package app.heyrobot.core.termux

import app.heyrobot.core.LogCode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

// Pure. A Termux result made fit to show and to give Gemma:
// ANSI escapes and control characters removed (newline and tab kept), invalid UTF-8 replaced, secrets masked, at most
// 100 KB kept (the end, as Termux keeps it), 16 KB for the card (its first and last 8 KB) and 40 lines / 1,500 characters
// for Gemma (the first 15 and last 25 lines), and the exit code in plain English and French. Termux's own error message
// is never shown or logged (it can hold the command): it is only matched to known causes ([TermuxFailure]).

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

    /** The card's text: whole up to 16 KB, else the first and last 8 KB with what is left out said in between. */
    fun forCard(text: String, fr: Boolean = false): String {
        if (text.length <= CARD_HEAD + CARD_TAIL) return text
        val gone = text.length - CARD_HEAD - CARD_TAIL
        val note = if (fr) "\n… ${size(gone, fr)} non affichés …\n" else "\n… ${size(gone, fr)} not shown …\n"
        return text.substring(0, CARD_HEAD) + note + text.substring(text.length - CARD_TAIL)
    }

    /**
     * What Gemma reads: at most [maxLines] lines (the first 3/8 and the last 5/8, 15 and 25 by default, "… 312 lines not
     * shown …" between) and [maxChars] characters (the first 3/8 and the last 5/8 again).
     */
    fun forModel(text: String, maxLines: Int = TermuxSettings.DEFAULT_LINES, maxChars: Int = TermuxSettings.DEFAULT_CHARS, fr: Boolean = false): String {
        var lines = text.split('\n')
        if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
        var out = if (lines.size <= maxLines) lines.joinToString("\n") else {
            val head = maxLines * 3 / 8
            val tail = maxLines - head
            val gone = lines.size - head - tail
            (lines.take(head) + (if (fr) "… $gone lignes non affichées …" else "… $gone lines not shown …") + lines.takeLast(tail)).joinToString("\n")
        }
        if (out.length > maxChars) {
            val head = maxChars * 3 / 8
            val tail = maxChars - head
            val gone = out.length - head - tail
            out = out.substring(0, head) + (if (fr) "\n… $gone caractères non affichés …\n" else "\n… $gone characters not shown …\n") + out.substring(out.length - tail)
        }
        return out
    }

    /**
     * [result]'s stdout and stderr (labelled when both have text), cleaned, masked ([mask]: "Hide secrets in output",
     * [own]: Robot's own keys by value), capped. [truncated] also counts Termux's own cut (its original lengths).
     */
    fun prepare(result: TermuxResult, mask: Boolean = true, own: Collection<String> = emptyList(), maxLines: Int = TermuxSettings.DEFAULT_LINES,
        maxChars: Int = TermuxSettings.DEFAULT_CHARS, fr: Boolean = false): TermuxOutputText {
        val out = clean(result.stdout.orEmpty())
        val err = clean(result.stderr.orEmpty())
        val both = when {
            out.isNotBlank() && err.isNotBlank() -> out.trimEnd('\n') + "\n" + (if (fr) "[erreurs]" else "[errors]") + "\n" + err
            err.isNotBlank() -> err
            else -> out
        }
        // Masked before the cap, so the cap can't cut a key in two and leave a part Robot no longer recognizes.
        val found = if (mask) SecretMask.find(both, own).size else 0
        val (masked, cut) = capEnd(if (mask && found > 0) SecretMask.mask(both, own, fr) else both)
        val termuxCut = cutByTermux(result.stdoutOriginalLength, result.stdout) || cutByTermux(result.stderrOriginalLength, result.stderr)
        return TermuxOutputText(forCard(masked, fr), forModel(masked, maxLines, maxChars, fr), masked.length, masked.count { it == '\n' } + if (masked.isEmpty()) 0 else 1,
            cut || termuxCut, found)
    }

    private fun cutByTermux(original: String?, kept: String?): Boolean {
        val n = original?.trim()?.toIntOrNull() ?: return false
        return n > (kept?.length ?: 0)
    }

    /** "Exit 0 · 0.8 s" / "Code 0 · 0,8 s", the card's line after a run. */
    fun exitLine(exit: Int, ms: Long, fr: Boolean = false): String = (if (fr) "Code $exit · " else "Exit $exit · ") + seconds(ms, fr)

    /**
     * The result in a sentence: "Done in 0.8 s.", "Took longer than 60 s and was stopped.", "Stopped.", "Command not
     * found (exit 127).", "Finished with exit code 2 in 1.2 s."; null [exit]: Termux didn't answer in time.
     */
    fun summary(exit: Int?, limitSeconds: Int, ms: Long, fr: Boolean = false): String = when {
        exit == null -> if (fr) "Termux n'a pas répondu." else "Termux didn't answer."
        exit == 0 -> if (fr) "Terminé en ${seconds(ms, fr)}." else "Done in ${seconds(ms, fr)}."
        exit == 124 -> if (fr) "A pris plus de $limitSeconds s et a été arrêtée." else "Took longer than $limitSeconds s and was stopped."
        exit == 137 || exit == 143 -> if (fr) "Arrêtée." else "Stopped."
        exit == 126 -> if (fr) "Impossible de la lancer : pas exécutable ou refusée (code 126)." else "Couldn't run it: not executable or refused (exit 126)."
        exit == 127 -> if (fr) "Commande introuvable (code 127)." else "Command not found (exit 127)."
        exit in 129..192 -> if (fr) "Arrêtée par le signal ${exit - 128} (code $exit)." else "Ended by signal ${exit - 128} (exit $exit)."
        else -> if (fr) "Terminée avec le code $exit en ${seconds(ms, fr)}." else "Finished with exit code $exit in ${seconds(ms, fr)}."
    }

    /** 0.8 s, 12 s, 2 min 5 s (French: 0,8 s). */
    fun seconds(ms: Long, fr: Boolean = false): String {
        val s = ms.coerceAtLeast(0) / 1000.0
        return when {
            s < 10 -> String.format(if (fr) Locale.FRANCE else Locale.ROOT, "%.1f s", s)
            s < 120 -> "${s.toLong()} s"
            else -> "${s.toLong() / 60} min ${s.toLong() % 60} s"
        }
    }

    private fun size(chars: Int, fr: Boolean): String =
        if (chars < 1024) "$chars " + (if (fr) "caractères" else "characters") else String.format(if (fr) Locale.FRANCE else Locale.ROOT, "%.1f KB", chars / 1024.0)
}

/** Why Termux couldn't run a command (err ≠ -1, or no answer), in words; Termux's errmsg itself never surfaces. */
enum class TermuxFailure(override val id: Int, override val title: String, val en: String, val fr: String) : LogCode {
    EXTERNAL_APPS_OFF(1, "allow-external-apps off", "Termux doesn't allow other apps yet: add allow-external-apps=true in Settings › Termux's set-up.",
        "Termux n'autorise pas encore les autres applications : ajoutez allow-external-apps=true (Réglages › Termux)."),
    NOT_SET_UP(2, "not set up", "Termux isn't set up yet: open it once.", "Termux n'est pas encore installé : ouvrez-le une fois."),
    NO_PERMISSION(3, "no permission", "Robot may not run commands in Termux yet: allow it in Settings › Termux.",
        "Robot ne peut pas encore lancer de commandes dans Termux : autorisez-le dans Réglages › Termux."),
    NO_ANSWER(4, "no answer", "Termux didn't answer: check that its battery use isn't restricted.",
        "Termux n'a pas répondu : vérifiez que sa batterie n'est pas restreinte."),
    NOT_TRUSTED(5, "signer changed", "Termux was reinstalled or replaced. Check where it came from, then set it up again in Settings › Termux.",
        "Termux a été réinstallé ou remplacé. Vérifiez sa provenance, puis configurez-le à nouveau dans Réglages › Termux."),
    INTERNAL(6, "internal error", "Termux couldn't run it.", "Termux n'a pas pu la lancer."),
    /** 0.9: Android found no Termux to start (not installed, or a build without RunCommandService). */
    NOT_INSTALLED(7, "not installed", "Termux isn't installed, or can't take commands from other apps: see Settings › Termux.",
        "Termux n'est pas installé, ou n'accepte pas les commandes d'autres applications : voyez Réglages › Termux."),
    /** 0.9: Settings › Termux's set-up isn't finished (rule 1: nothing runs until every line is green). */
    NOT_READY(8, "set-up not finished", "Finish setting up Termux in Settings › Termux first.", "Terminez d'abord la configuration de Termux dans Réglages › Termux.");

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
