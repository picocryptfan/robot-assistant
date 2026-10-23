// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

// Pure. Pseudo-locales for core's words. A debug build has en-XA and ar-XB (pseudoLocalesEnabled): aapt2
// marks every resource string, and a hard-coded string shows as plain text, so a missed string stands out. Core's words
// get the same treatment here when the app language is one of them (LocalLangs carries it): accents, about a third
// longer, in brackets; or right to left. Only the entry's own words are marked, never the arguments (your words, names).

enum class Pseudo(val tag: String) {
    /** en-XA: "[Šéţţîñĝš one two]". */
    ACCENTS("en-XA"),

    /** ar-XB: the sentence set right to left (aapt2's exact method is UNVERIFIED; the test only needs the direction). */
    BIDI("ar-XB");

    /** [template] marked; its {placeholders} are left as they are, for render() to fill. */
    fun mark(template: String): String = when (this) {
        ACCENTS -> {
            val out = StringBuilder("[")
            var inHole = false
            for (c in template) {
                if (c == '{') inHole = true
                out.append(if (inHole) c else ACCENTED[c] ?: c)
                if (c == '}') inHole = false
            }
            val letters = template.count { it.isLetter() }
            val pad = PADDING.joinToString(" ").take((letters * 0.3).toInt().coerceAtLeast(1)).trimEnd()
            out.append(' ').append(pad).append(']').toString()
        }
        BIDI -> "\u200F\u202B$template\u202C\u200F"
    }

    companion object {
        private val PADDING = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        private val ACCENTED: Map<Char, Char> = ("abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .zip("åƀçđéƒĝĥîĵķļɱñöþǫŕšţûṽŵẋýž" + "ÅßÇÐÉƑĜĤÎĴĶĻṀÑÖÞǪŔŠŢÛṼŴẊÝŽ").toMap()

        /** The pseudo-locale a language tag names ("en-XA", "ar-XB"), or null. */
        fun of(tag: String?): Pseudo? = entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
    }
}
