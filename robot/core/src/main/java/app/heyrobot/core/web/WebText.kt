package app.heyrobot.core.web

import java.text.Normalizer
import java.util.Locale

// Pure. Text helpers web search shares (design A7, A10.3): characters that show nothing but can carry words a reader
// never sees, HTML character references, and the words QueryCheck matches and Passages ranks (English and French).

/** Characters that render as nothing, and control characters. Web text and queries lose them before anything reads them. */
object Invisible {
    /**
     * Zero-width and bidi controls (U+200B–U+200F, U+202A–U+202E, U+2060–U+206F, U+FEFF), the soft hyphen and the other
     * blank fillers (U+034F, U+061C, U+115F, U+1160, U+17B4, U+17B5, U+180B–U+180F, U+3164, U+FFA0, U+FFF9–U+FFFB), the
     * Unicode tag block (U+E0000–U+E007F, "ASCII smuggling") and the variation selectors (U+FE00–U+FE0F,
     * U+E0100–U+E01EF), which can hide bytes the same way.
     */
    fun isInvisible(cp: Int): Boolean =
        cp in 0x200B..0x200F || cp in 0x202A..0x202E || cp in 0x2060..0x206F || cp == 0xFEFF || cp == 0x00AD || cp == 0x034F ||
            cp == 0x061C || cp == 0x115F || cp == 0x1160 || cp == 0x17B4 || cp == 0x17B5 || cp in 0x180B..0x180F || cp == 0x3164 ||
            cp == 0xFFA0 || cp in 0xFFF9..0xFFFB || cp in 0xFE00..0xFE0F || isTag(cp) || cp in 0xE0100..0xE01EF

    /** The Unicode tag block: invisible copies of ASCII, used to smuggle instructions past people. */
    fun isTag(cp: Int): Boolean = cp in 0xE0000..0xE007F

    /** C0 and C1 controls other than tab, line feed and carriage return. */
    fun isControl(cp: Int): Boolean = (cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D) || cp in 0x7F..0x9F

    /** [s] has a tag character (a strong sign of a hidden instruction). */
    fun hasTags(s: String): Boolean = codePoints(s).any(::isTag)

    /** [s] has a control character (other than tab and line breaks). */
    fun hasControl(s: String): Boolean = codePoints(s).any(::isControl)

    /** [s] without invisible and control characters; U+2028 and U+2029 become line feeds, a lone surrogate U+FFFD. */
    fun strip(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp = if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) s.codePointAt(i) else c.code
            i += Character.charCount(cp)
            when {
                cp == 0x2028 || cp == 0x2029 -> out.append('\n')
                isInvisible(cp) || isControl(cp) -> {}
                cp in 0xD800..0xDFFF -> out.append('�')
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    private fun codePoints(s: String): Sequence<Int> = sequence {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            yield(cp)
            i += Character.charCount(cp)
        }
    }
}

/** HTML character references ("&amp;", "&#233;", "&#xE9;") as their characters. */
object Entities {
    private val NAMED: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®",
        "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–", "minus" to "−", "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚",
        "ldquo" to "“", "rdquo" to "”", "bdquo" to "„", "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "middot" to "·",
        "bull" to "•", "deg" to "°", "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
        "sup2" to "²", "sup3" to "³", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "sect" to "§", "para" to "¶",
        "dagger" to "†", "Dagger" to "‡", "prime" to "′", "Prime" to "″", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "hearts" to "♥", "micro" to "µ", "ordf" to "ª", "ordm" to "º", "iexcl" to "¡", "iquest" to "¿", "shy" to "­",
        "zwj" to "‍", "zwnj" to "‌", "lrm" to "‎", "rlm" to "‏", "thinsp" to " ", "ensp" to " ",
        "emsp" to " ", "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã", "auml" to "ä", "aring" to "å",
        "aelig" to "æ", "ccedil" to "ç", "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë", "igrave" to "ì",
        "iacute" to "í", "icirc" to "î", "iuml" to "ï", "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó", "ocirc" to "ô",
        "otilde" to "õ", "ouml" to "ö", "oslash" to "ø", "oelig" to "œ", "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û",
        "uuml" to "ü", "yacute" to "ý", "yuml" to "ÿ", "szlig" to "ß", "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â",
        "Auml" to "Ä", "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë", "Icirc" to "Î",
        "Iuml" to "Ï", "Ocirc" to "Ô", "Ouml" to "Ö", "OElig" to "Œ", "Ugrave" to "Ù", "Ucirc" to "Û", "Uuml" to "Ü", "Ntilde" to "Ñ",
    )

    /** The few names browsers also take without the semicolon. */
    private val LEGACY = setOf("amp", "lt", "gt", "quot", "nbsp", "copy", "reg")

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { out.append(c); i++; continue }
            val end = (i + 1 until minOf(s.length, i + 34)).firstOrNull { !s[it].isLetterOrDigit() && s[it] != '#' } ?: minOf(s.length, i + 34)
            val body = s.substring(i + 1, end)
            val semi = end < s.length && s[end] == ';'
            val decoded: String? = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).takeIf { it.isNotEmpty() && it.length <= 8 && it.all { d -> d in '0'..'9' || d.lowercaseChar() in 'a'..'f' } }
                    ?.let { codePoint(it.toLong(16)) }
                body.startsWith("#") -> body.substring(1).takeIf { it.isNotEmpty() && it.length <= 9 && it.all { d -> d in '0'..'9' } }?.let { codePoint(it.toLong()) }
                semi -> NAMED[body]
                else -> null
            }
            if (decoded != null) {
                out.append(decoded)
                i = end + if (semi) 1 else 0
                continue
            }
            // "&amp" without its semicolon (old pages): the longest legacy name at the start.
            val legacy = LEGACY.filter { body.startsWith(it) }.maxByOrNull { it.length }
            if (legacy != null) {
                out.append(NAMED.getValue(legacy))
                i += 1 + legacy.length
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** A numeric reference's character: none for NUL, surrogates or past U+10FFFF (U+FFFD, as browsers do). */
    private fun codePoint(v: Long): String =
        if (v <= 0 || v > 0x10FFFF || v in 0xD800..0xDFFF) "�" else String(Character.toChars(v.toInt()))
}

/**
 * Words as QueryCheck and Passages compare them: accents and case folded, split on anything that isn't a letter or a
 * digit (so French elisions fall away: "l'hôtel" is "l" and "hotel"), English and French stop words known, and a light
 * stemmer for plurals and a few endings.
 */
object WebWords {
    private val MARKS = Regex("\\p{Mn}+")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Accents and case folded ("Montréal" → "montreal"). */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    /** Every word of [s], folded. */
    fun words(s: String): List<String> = WORD.findAll(fold(s)).map { it.value }.toList()

    /** Every word of [s] with where it is in [s] (as written). */
    fun spans(s: String): List<Pair<String, String>> = WORD.findAll(s).map { it.value to fold(it.value) }.toList()

    /** The words of [s] that carry meaning, stemmed. */
    fun terms(s: String): List<String> = words(s).filter { it !in STOP }.map(::stem)

    /**
     * Plurals, a few English endings and a final "e": "stations" → "station", "chevaux" → "cheval", "bateaux" →
     * "bateau", "descaling" and "descale" → "descal", "machines" → "machin". The same on both sides of every comparison.
     */
    fun stem(w: String): String {
        if (w.length <= 3 || w.any { it.isDigit() }) return w
        val s = when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("eaux") -> w.dropLast(1)
            w.endsWith("aux") && w.length > 4 -> w.dropLast(3) + "al"
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> w
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3)
            w.endsWith("ed") && w.length > 4 -> w.dropLast(2)
            w.endsWith("s") || w.endsWith("x") -> w.dropLast(1)
            else -> w
        }
        return if (s.length >= 4 && s.endsWith("e")) s.dropLast(1) else s
    }

    /** English and French words that say nothing about the subject (folded, without accents). */
    val STOP: Set<String> = (
        "a an and are as at be been but by can could did do does for from had has have he her his how i if in into is it its me my " +
            "no not of on or our she so than that the their them then there these they this those to too us was we were what when where " +
            "which who whom why will with would you your yours about after again all also am any because before being both each few " +
            "more most other over own same should some such only very just now here out up down off once until while during through " +
            "le la les l un une des du de d et ou mais donc or ni car que qu qui quoi dont ou au aux ce cet cette ces se sa son ses " +
            "mon ma mes ton ta tes notre nos votre vos leur leurs je tu il elle on nous vous ils elles me te lui y en ne pas plus " +
            "est sont etait ete etre avoir a ai as avons avez ont dans par pour sur sous avec sans chez vers entre comme si tres " +
            "aussi bien c j m n s t quel quelle quels quelles quand comment pourquoi combien"
        ).split(' ').filter { it.isNotEmpty() }.toSet()
}
