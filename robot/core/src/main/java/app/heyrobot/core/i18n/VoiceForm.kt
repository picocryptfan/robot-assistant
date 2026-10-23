// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import java.util.Locale

// Pure. The voice's form of a sentence: what the screen needs and the voice mustn't read. No-break
// spaces become plain ones, « » and “ ” go (the voice would read "guillemet" or pause oddly), a path's "›" becomes a
// pause ("Settings, Memory"), sizes and percentages are said in words (« 1,5 gigaoctet », "30 percent"), and clock times
// are written as the voice must say them ([clock]). Markdown and links are SpeechText.sayable's, after this.
//
// Clock times, measured on the phonemes Robot's Kokoro voice gets (tools/voice_phonemes.sh): espeak's French reads the
// "h" of « 7 h 02 » as the letter (« sept ache zéro deux ») and sherpa's Kokoro front end turns "12:30" into "12,30"
// (« douze virgule trente »); its English reads "7:02" as "seven zero two". « 7 heures 2 » and "7 oh 2" read right.

object VoiceForm {
    private val QUOTES = Regex("""[«“”»]""")
    private val SPACES = Regex("""[ \u00A0\u202F]+""")
    private val PATH = Regex("""\s*›\s*""")
    private val NUMBER = """(\d[\d\u00A0\u202F,]*(?:[.,]\d+)?)"""
    private val SIZE = Regex("""$NUMBER[ \u00A0\u202F]?(GB|MB|KB|Go|Mo|ko|Ko)(?![\p{L}\p{N}])""")
    private val PERCENT = Regex("""$NUMBER[ \u00A0\u202F]?%""")

    /** [s] (in [lang]) as Robot's voice should receive it: no U+00A0, U+202F, « », “ ” or "›" left. */
    fun clean(s: String, lang: Lang): String {
        var t = SIZE.replace(s) { m -> m.groupValues[1].let { n -> n + " " + unitWord(m.groupValues[2], n, lang) } }
        t = PERCENT.replace(t) { m -> m.groupValues[1] + if (lang == Lang.FR) " pour cent" else " percent" }
        t = PATH.replace(t, ", ")
        t = QUOTES.replace(t, " ")
        t = SPACES.replace(t, " ")
        // A quote that ended the sentence leaves "word ." behind.
        t = t.replace(Regex(""" ([.,;:!?…])"""), "$1").trim()
        // English sentences reach the voice as the screen writes them (Phrase.said), so English times are the voice's
        // to fix (SherpaTextToSpeech); a French sentence's are fixed here, for every voice.
        return if (lang == Lang.FR) clock(t, lang) else t
    }

    // An hour and minutes of a clock or a duration, as text: « 7 h 02 », « 12 h », « 7h30 », « 12:30 »; "12:30 p.m.".
    private const val SP = "[ \u00A0\u202F]"
    private const val BEFORE = """(?<![\p{L}\p{N},.:/])"""
    private val FR_H = Regex("""$BEFORE(\d{1,2})$SP?h(?:$SP?(\d{2}))?(?![\p{L}\p{N}])""")
    private val COLON = Regex("""$BEFORE(\d{1,2}):(\d{2})(?!\p{N}|[:.,]\p{N})""")
    private val FR_TIME = """(?:\d{1,2}$SP?h(?:$SP?\d{2})?|\d{1,2}:\d{2})"""
    private val FR_RANGE = Regex("""($BEFORE$FR_TIME)$SP*[-–—]$SP*(?=$FR_TIME(?![\p{L}\p{N}]))""")
    private val EN_TIME = Regex("""$BEFORE(\d{1,2}):(\d{2})(?!\p{N}|[:.,]\p{N})(?:$SP?([aApP])\.?[mM]\b\.?)?""")

    /**
     * Clock times in [s] as [lang]'s voice must be given them, the rest as it is.
     *
     * French: « 7 h 02 », « 7h02 » and « 7:02 » become « 7 heures 2 »; « 21 h » « vingt et une heures », « 1 h »
     * « une heure » (espeak's « 1 » is « un »), « 0 h 15 » « minuit 15 » (how people say it; « zéro heure » reads like a
     * form), « 12 h » « 12 heures » (a duration too: « 12 h de route »; Fmt.clock says « midi » where it knows it's
     * noon). A minute ending in one is feminine as well (« 13 heures vingt et une »). A range's dash becomes « à ».
     *
     * English: "7:02" becomes "7 oh 2", "7:00" "7 o'clock" ("7 p.m." with its "p.m."), "19:00" "19 hundred", "0:15"
     * "12:15 a.m.", "0:00" "midnight"; "12:30" stays ("twelve thirty").
     *
     * A number joined to letters, a decimal (« 1,5 h ») and "1:30:00" stay. The voice's form only: the screen keeps
     * its own. Idempotent.
     */
    fun clock(s: String, lang: Lang): String = when (lang) {
        Lang.FR -> {
            val t = FR_RANGE.replace(s) { m -> m.groupValues[1] + " à " }
            val h = FR_H.replace(t) { m -> frClock(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { null }?.toInt(), duration = true) ?: m.value }
            COLON.replace(h) { m -> frClock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), duration = false) ?: m.value }
        }
        // "At 0:15." gains an "a.m." before its full stop.
        Lang.EN -> EN_TIME.replace(s) { m -> enClock(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) ?: m.value }.replace(" a.m..", " a.m.")
    }

    /**
     * « 7 heures 2 », « une heure », « minuit 15 »: [h] hours and [m] minutes (null: none written) as the French voice
     * says them; null when they aren't a time or a duration. [duration]: written with « h », which counts hours beyond 23.
     */
    internal fun frClock(h: Int, m: Int?, duration: Boolean): String? {
        if (m != null && m > 59 || !duration && h > 23) return null
        val hours = when {
            h == 0 -> "minuit"
            h == 1 -> "une heure"
            else -> feminine(h) + " heures"
        }
        return if (m == null || m == 0) hours else hours + " " + feminine(m)
    }

    /** [n] as French counts hours and minutes: « une », « vingt et une », … for the numbers ending in one, else digits. */
    private fun feminine(n: Int): String = when (n) {
        1 -> "une"
        21 -> "vingt et une"
        31 -> "trente et une"
        41 -> "quarante et une"
        51 -> "cinquante et une"
        61 -> "soixante et une"
        81 -> "quatre-vingt-une"
        else -> n.toString()
    }

    /** "7 oh 2", "7 o'clock", "7 p.m."; null when [h]:[m] isn't a time. [ampm]: "a" or "p" when the text had one. */
    private fun enClock(h: Int, m: Int, ampm: String): String? {
        if (h > 23 || m > 59 || ampm.isNotEmpty() && (h == 0 || h > 12)) return null
        val suffix = if (ampm.isEmpty()) "" else if (ampm.lowercase() == "a") " a.m." else " p.m."
        return when {
            h == 0 && m == 0 -> "midnight"
            h == 0 -> enMinutes(12, m) + " a.m."
            m == 0 && suffix.isNotEmpty() -> "$h$suffix"
            m == 0 && h > 12 -> "$h hundred"
            m == 0 -> "$h o'clock"
            else -> enMinutes(h, m) + suffix
        }
    }

    /** "7 oh 2", "12:30". */
    private fun enMinutes(h: Int, m: Int): String = if (m < 10) "$h oh $m" else "$h:" + String.format(Locale.US, "%02d", m)

    private fun unitWord(unit: String, number: String, lang: Lang): String {
        val i = listOf("KB|ko|Ko", "MB|Mo", "GB|Go").indexOfFirst { unit in it.split('|') }
        val decimal = if (lang == Lang.FR) number.contains(',') else number.contains('.')
        val value = number.replace(Regex("[\u00A0\u202F]"), "").let { if (lang == Lang.FR) it.replace(',', '.') else it.replace(",", "") }
            .toDoubleOrNull() ?: 2.0
        val one = Plural.category(lang, value, if (decimal) 1 else 0) == PluralCategory.ONE
        val word = if (lang == Lang.FR) listOf("kilooctet", "mégaoctet", "gigaoctet")[i] else listOf("kilobyte", "megabyte", "gigabyte")[i]
        return word + if (one) "" else "s"
    }
}
