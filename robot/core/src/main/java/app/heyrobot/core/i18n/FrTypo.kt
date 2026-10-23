// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Pure. French typography as the OQLF writes it, applied by code so nobody types invisible characters:
// the catalog's French is written with ordinary spaces (« Répondre, suivant ou arrêter ? ») and render() fixes it. The
// resources (values-fr-rCA) can't run code: tools/fr_typo.py --fix writes the same characters there as entities, and
// FrenchTypographyTest checks both. Speech never receives these characters ([VoiceForm]).

object FrTypo {
    /** U+00A0: before ":", inside « », before %, $ and units (sizes, °C, h, min, s). */
    const val NBSP = '\u00A0'

    /**
     * U+202F, the narrow no-break space before ";", "!" and "?": the OQLF's "fine" space, which "demeure convenable" where
     * software has it (open question 5, decided by default). The OQLF's own default is no space at all: should the phone
     * check (P7) find no glyph for it, this one constant becomes "" and the tests follow.
     */
    const val FINE = "\u202F"

    private const val SPACES = " \u00A0\u202F"

    /** What is never touched: code in backticks (`adb shell dumpsys meminfo {p}:mt`), addresses and a bare scheme ("claude://"), a time or a ratio ("7:02"), Termux:API. */
    private val PROTECTED = Regex("""`[^`\n]*`|[A-Za-z][A-Za-z0-9+.\-]*://[^\s\u00A0\u202F]*|Termux:API|\d:\d|\{[A-Za-z][A-Za-z0-9_]*\}""")

    private val COLON = Regex("""([^$SPACES\n])[$SPACES]*:""")
    private val HIGH = Regex("""([^$SPACES\n;!?])[$SPACES]*([;!?]+)""")
    private val OPEN = Regex("""«[$SPACES]*""")
    private val CLOSE = Regex("""[$SPACES]*»""")
    private val PERCENT = Regex("""([0-9\uE001])[$SPACES]*([%$])""")
    /** A unit after a number: sizes, temperatures, and time (« 7 h », « 10 min », « 3 s », « 15 ms »; never the « s' » of « s'arrête »). */
    private val UNIT = Regex("""([0-9\uE001])[$SPACES]+(Go|Mo|ko|km|°C|°F|h|min|ms|s)(?![\p{L}\p{N}'’])""")
    /** The minutes of « 7 h 30 » stay on the hour's line. */
    private val HOUR_MIN = Regex("""([0-9\uE001]\u00A0h)[$SPACES]+(?=[0-9]|\uE000)""")
    private val HOLE = Regex("\uE000(\\d+)\uE001")

    /** [s] (French) with §6.4's spaces, apostrophes and ellipsis. Running it twice changes nothing more. */
    fun fix(s: String): String {
        if (s.isEmpty()) return s
        // Placeholders and protected spans keep their characters (each stands in as one private-use mark meanwhile); the
        // text around them is fixed with them in place, so "{n} %" and "{what} ?" get their spaces too.
        val holes = mutableListOf<String>()
        var t = PROTECTED.replace(s) { m -> "\uE000${holes.size}\uE001".also { holes += m.value } }
        t = t.replace("...", "…").replace('\'', '’')
        t = COLON.replace(t) { it.groupValues[1] + NBSP + ":" }
        t = HIGH.replace(t) { it.groupValues[1] + FINE + it.groupValues[2] }
        t = OPEN.replace(t, "«$NBSP")
        t = CLOSE.replace(t, "$NBSP»")
        t = PERCENT.replace(t) { it.groupValues[1] + NBSP + it.groupValues[2] }
        t = UNIT.replace(t) { it.groupValues[1] + NBSP + it.groupValues[2] }
        t = HOUR_MIN.replace(t) { it.groupValues[1] + NBSP }
        return HOLE.replace(t) { holes[it.groupValues[1].toInt()] }
    }
}
