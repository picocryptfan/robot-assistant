// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.tts

import app.heyrobot.core.SpeechText
import java.text.Normalizer

// Pure. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3, D16, D17): what text may reach Robot's speech library,
// sherpa-onnx with espeak-ng linked into it, native code that any installed app can now hand text to, again and again,
// through Robot's voices for other apps.
//
// Why each rule (read in the pinned sources, robot-1.0.1-third-party-sources: the csukuangfj/espeak-ng fork at ed530aa1,
// piper-phonemize at f3ff95af, sherpa-onnx v1.13.8):
// - Control characters. espeak acts on U+0001 wherever it is, as an embedded command (readclause.c ReadClause and
//   translate.c TranslateClause), and after "B" it copies the following characters into a 60-entry global array with no
//   bound (our audit's F9); "[" then U+0002 switches it into phoneme input (translate.c). C0, DEL and C1 become spaces.
// - Format characters (\p{Cf}: zero-width spaces and joiners, direction marks, the byte order mark, the soft hyphen) go
//   without a trace, as the brief's text does (BriefText.CONTROL): they'd split a word or reorder it unseen.
// - Private-use characters: espeak codes markup internally as U+E000 plus a character (readclause.c). Noncharacters and
//   unassigned code points: nothing to say. All become spaces.
// - Anything above U+FFFF becomes a space, and a lone surrogate goes. sherpa hands the text over with JNI's
//   GetStringUTFChars (jni/offline-tts.cc), which is modified UTF-8: such a character arrives as two 3-byte surrogate
//   halves, which espeak's automatic decoding (espeakCHARS_AUTO, piper's phonemize.cpp) doesn't read as one character
//   (emoji are already dropped by SpeechText.sayable).
// The control, format and surrogate rules are the release's SpeechText.forEngine (our audit's fix FA5) to the letter; when
// the branches meet, [native] calls it and keeps the rest.
// - Words over [MAX_WORD] characters are cut there: espeak keeps a word in fixed buffers, and a long run without a space
//   is what the F9 loop needed. Robot's own sentences never have one (a link is already said as "a link").
// - Unicode NFC first, so an accented letter written as a letter and a combining mark reads as one letter.
// - At most [MAX_INPUT] characters, Android's own limit for one request (TextToSpeech.getMaxSpeechInputLength()), which
//   a caller talking to the engine's binder directly could ignore.
// - Last, SpeechText.sayable: no Chinese, Japanese or Korean script, and nothing without a letter or a digit, so sherpa
//   never writes a piece to Android's log ("Ignore OOV", "Failed to convert … to token IDs" are logged whatever its debug
//   flag; the rest of its text logs are behind debug, which stays off).
// "[[" needs no rule: espeak reads phonemes there only with espeakPHONEMES, which only espeak_Synth sets (speech.c), and
// nothing in sherpa-onnx's or piper-phonemize's library code calls espeak_Synth; they call espeak_TextToPhonemes alone.
//
// [native] is the one way text reaches the library: SherpaTextToSpeech.generate, the only call into sherpa's synthesis,
// starts with it, for Robot's own replies, the voice speed test and other apps' text alike (ProcessIsolationTest
// voicesForAppsKeepNothing). [forApp] prepares another app's text before that: tags dropped, letters outside the Latin
// alphabet dropped (Robot's voices speak English and French only, and other scripts are what sherpa would log or espeak
// misread), and the text split into pieces of a sentence or so, each at most [MAX_PIECE] characters.

object TtsText {
    /** Android's limit for one request's text (TextToSpeech.getMaxSpeechInputLength(), 4,000 since API 18). */
    const val MAX_INPUT = 4_000

    /** The longest word that reaches espeak; a longer one is cut here. */
    const val MAX_WORD = 40

    /** The longest piece of another app's text spoken at once (a long sentence is split at a comma or a space). */
    const val MAX_PIECE = 400

    /** The longest run between "<" and ">" taken for a tag (SSML or HTML); a longer one is text. */
    private const val MAX_TAG = 500

    private val TAG = Regex("""</?[A-Za-z!?][^<>]{0,$MAX_TAG}>""")
    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+(?=["'“‘«(\[]?[\s\u00A0\u202F]?[\p{Lu}\p{Nd}])""")
    private val LINE = Regex("""[\n\r\u2028\u2029\u000B\u000C\u0085]+""")

    /**
     * Text made safe for the speech library: every rule in the file's notes, for any caller. Blank when nothing sayable is
     * left (the caller then speaks nothing). Linear in the text's length, and the same text again gives the same result.
     */
    fun native(text: String): String {
        val nfc = cap(Normalizer.normalize(chars(cap(text)), Normalizer.Form.NFC))
        // sayable joins a space and the punctuation after it to the word before ("word ,"): cut after it, then check again
        // that a letter or a digit is left.
        return SpeechText.sayable(cutWords(SpeechText.sayable(chars(nfc))))
    }

    /**
     * Another app's [text] as pieces to speak in order, each already [native] (blank pieces dropped): at most [MAX_INPUT]
     * characters read, tags dropped with their text kept (SSML's five named entities read back when it had tags), letters
     * of other alphabets dropped, then split at line breaks and sentence ends, and a piece longer than [MAX_PIECE] at its
     * last comma or space before that.
     */
    fun forApp(text: CharSequence): List<String> {
        var s = cap(text.toString())
        if (s.indexOf('<') >= 0) {
            val tagged = TAG.containsMatchIn(s)
            s = s.replace(TAG, " ")
            if (tagged) s = entities(s)
        }
        s = cutWords(latinOnly(chars(cap(Normalizer.normalize(chars(s), Normalizer.Form.NFC)))))
        return LINE.split(s).asSequence()
            .flatMap { it.split(SENTENCE_END).asSequence() }
            .flatMap { split(it.trim()).asSequence() }
            .map(::native)
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun cap(s: String): String = if (s.length > MAX_INPUT) s.substring(0, MAX_INPUT) else s

    /**
     * The character rules. Controls (C0, DEL, C1) and the line and paragraph separators become spaces, format characters
     * and lone surrogates go: exactly the release's SpeechText.forEngine (our audit's fix FA5, on release/1.1), which
     * this calls instead once the branches meet. Beyond it, for any app's text: a character above U+FFFF (a whole
     * surrogate pair), a private-use, unassigned or noncharacter code point becomes a space. Line breaks stay line breaks
     * here, for [forApp]'s split; [native]'s SpeechText.sayable makes them spaces.
     */
    private fun chars(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (Character.isHighSurrogate(c) && i < s.length && Character.isLowSurrogate(s[i])) {
                i++
                out.append(' ')
                continue
            }
            when (Character.getType(c).toByte()) {
                Character.FORMAT, Character.SURROGATE -> Unit
                Character.CONTROL, Character.PRIVATE_USE, Character.UNASSIGNED, Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR -> out.append(if (c == '\n' || c == '\r') '\n' else ' ')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Letters and marks of the Latin script, and the common characters (digits, punctuation, symbols, spaces), only. */
    private fun latinOnly(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            val keep = when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> true
                else -> false
            }
            out.append(if (keep) c else ' ')
        }
        return out.toString()
    }

    /** Every run of more than [MAX_WORD] characters without a space or a line break cut to its first [MAX_WORD]. */
    private fun cutWords(s: String): String {
        val out = StringBuilder(s.length)
        var run = 0
        for (c in s) {
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                run = 0
                out.append(c)
            } else if (++run <= MAX_WORD) out.append(c)
        }
        return out.toString()
    }

    /** One sentence (or line) as pieces of at most [MAX_PIECE] characters, split at the last ", " or space before that. */
    private fun split(sentence: String): List<String> {
        if (sentence.length <= MAX_PIECE) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > MAX_PIECE) {
            val window = rest.substring(0, MAX_PIECE + 1)
            val comma = window.lastIndexOf(", ")
            val space = window.lastIndexOfAny(charArrayOf(' ', '\u00A0', '\u202F', '\t'))
            val at = when {
                comma >= MAX_PIECE / 2 -> comma + 1
                space > 0 -> space
                else -> MAX_PIECE
            }
            out += rest.substring(0, at).trim()
            rest = rest.substring(at).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    /** SSML's (XML's) five named entities, &amp; last so "&amp;lt;" stays "&lt;". */
    private fun entities(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
