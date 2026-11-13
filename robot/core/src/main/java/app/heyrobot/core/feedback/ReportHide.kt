// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

// Pure (1.4, report-a-problem-plan.md "What's new" 2). "Hide names and numbers", on by default: in what you said, my
// answer and the tool line (a [ReportHide.Turn]), People's names and their parts, the numbers and addresses given, and
// anything shaped like a number, an email, a postal code or a street address become [name], [number] and [address]: any
// run of 3 digits or more (a year, a date, a code, digits stuck to letters), a day next to a month, numbers said in words,
// and street addresses in any case.
// The card (RP2) shows the hidden turn before you send; the words you type on the card stay as you typed them.

object ReportHide {
    const val NAME = "[name]" // l10n: not text (the report's placeholder, the same in every language)
    const val NUMBER = "[number]" // l10n: not text (the report's placeholder, the same in every language)
    const val ADDRESS = "[address]" // l10n: not text (the report's placeholder, the same in every language)
    /** Digits in a row that are hidden whoever's they are. */
    const val RUN = 3
    /** Digits in all, separators between, that are hidden whoever's they are (a phone number written out). */
    const val SPREAD = 7
    /** A shorter name or name part would hide ordinary words. */
    const val MIN_NAME = 2
    /** A part of a longer name shorter than this (an initial, J.) isn't hidden on its own; Jo and Li are. */
    const val MIN_PART = 2

    // Names match folded: case, accents and the apostrophe's kind don't count (René is Rene, O'Brien is O’Brien). A name
    // of two words or more hides in any case. One word alone (a part of a name, or a one-word name) hides only where it's
    // written with a capital, and when it's also an everyday word (a contact called Will, May, Mom or Home) or a particle
    // that is a word too (Van, Bin, De) not as a sentence's first word either, so "Will it rain?", "will" and "home" stay. A particle that is a
    // longer name's first word (Al of Al Smith) hides alone as that one-word name would; one later in a name (the van of Jan
    // van der Berg, the de of Marie de la Tour) never hides alone.
    private val PARTICLES = setOf("de", "la", "le", "les", "du", "des", "van", "von", "der", "den", "ter", "da", "di", "do", // l10n: not text (matched words, folded)
        "dos", "das", "del", "della", "bin", "ibn", "al", "el", "st", "ste", "saint", "sainte") // l10n: not text (matched words, folded)
    /** The particles that are English or French words too; Al, Del, Ibn or Von alone is a name wherever it stands. */
    private val WORD_PARTICLES = setOf("de", "la", "le", "les", "du", "des", "do", "dos", "van", "bin", "den", "st", "saint", "sainte") // l10n: not text (matched words, folded)
    private val COMMON = setOf("will", "may", "june", "april", "august", "mom", "mum", "mommy", "dad", "daddy", "mother", // l10n: not text (matched words, folded)
        "father", "home", "work", "office", "school", "grace", "hope", "rose", "mark", "bill", "pat", "guy", "maman", "papa", // l10n: not text (matched words, folded)
        "mere", "pere", "maison", "bureau", "travail", "ecole") // l10n: not text (matched words, French, folded)
    /** What may stand between a sentence's end and its first word. */
    private const val OPENERS = " \u00A0\t\"“«‘'(" // l10n: not text (characters)
    private const val ENDS = ".!?…:\n" // l10n: not text (characters)

    /** One to three of these between groups of digits: "06/12/34/56/78", "902 – 555 – 0123", "902  555  0123". */
    private const val SEP = """(?:[\p{Zs}\p{Pd}./_]|,(?=\p{Zs})){1,3}""" // l10n: not text (a pattern)
    /** Digits with separators between groups, an optional "+" and bracketed area code, as a whole token: "+1 (902) 555-0123"; never "E1042" or "1080p". */
    private const val NUMBERISH = """(?<![\p{L}\p{N}])\+?\(?\p{Nd}+(?:(?:$SEP|\)(?:$SEP)?|(?:$SEP)?\()\p{Nd}+)*+([\p{L}\p{N}]*)""" // l10n: not text (a pattern)
    /** Group 1 is what's glued to the digits ("1080p"). No date or year is kept: one said or answered can point at someone (a birthday, an appointment); the dates Robot's own code writes (the report's time, log stamps) never come here. */
    private val NUMBERS = Regex(NUMBERISH) // l10n: not text (a pattern)
    /** Digits in a row anywhere, any script's, stuck to letters too (an account "AC12345678", "E1042"). */
    private val STUCK = Regex("""\p{Nd}{$RUN,}""") // l10n: not text (a pattern)
    private val SLASH_DAY = Regex("""\p{Nd}{1,2}/\p{Nd}{1,2}""") // l10n: not text (a pattern)
    private const val MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|" + // l10n: not text (matched words)
        "janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre" // l10n: not text (matched words, French)
    private const val ORDINALS = "(?:(?:twenty|thirty)[\\s\\-])?(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|fourteenth|" + // l10n: not text (matched words)
        "fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth)|premier|première|premiere" // l10n: not text (matched words)
    /** A given number's last digits, at least this many, hide too ("55-01-99" for +1 902-555-0199). */
    private const val TAIL = 5
    /** Number words in a row that are hidden: a number said in groups ("five fifty-five, oh one two three", "cinq cinquante-cinq"). */
    const val SAID = 3
    /** Number words, English and French, folded: digits, tens, hundreds and "double" ("nine oh two, double five..."). */
    private val DIGIT_WORDS = listOf("zero", "zéro", "oh", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", // l10n: not text (matched words, folded)
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", // l10n: not text (matched words, folded)
        "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "double", "triple", // l10n: not text (matched words, folded)
        "un", "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", // l10n: not text (matched words, French, folded)
        "seize", "vingt", "vingts", "trente", "quarante", "cinquante", "soixante", "cent", "cents", "mille").joinToString("|") // l10n: not text (matched words, French, folded)
    /** In any case: speech to text writes "Nine oh two", an all-caps transcript "NINE OH TWO". */
    private val SPOKEN = Regex("""(?iu)(?<![\p{L}\p{N}])(?:$DIGIT_WORDS|\d)(?:[\s,\-]+(?:$DIGIT_WORDS|\d)){${SAID - 1},}(?![\p{L}\p{N}])""") // l10n: not text (a pattern)
    /** Tens and teens, English and French: two in a row are a year or a code said in pairs ("nineteen eighty", "twenty nineteen"). */
    private const val TENS = "ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|" + // l10n: not text (matched words)
        "dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante" // l10n: not text (matched words, French)
    private val TWO_TENS = Regex("""(?iu)(?<![\p{L}\p{N}])(?:$TENS)[\s\-]+(?:$TENS)(?![\p{L}\p{N}])""") // l10n: not text (a pattern)
    /** What may follow an everyday street name in what you said for it to be an address: a mark ("12 high street?"), a line's end, or a linking word ("12 high street and…", never "12 high street shops"). */
    private val AFTER = Regex("""(?m)\p{Zs}*(?:[,.;:?!)»”"]|\[address]|$)|\p{Zs}+$STOP""") // l10n: not text (a pattern)
    /** A day next to a month, in digits or words: "March 3rd", "3 March", "the third of May", "le 1er mai" (the day becomes [number]). */
    private val MONTH_DAY = Regex("""(?i)(?<![\p{L}\p{N}])(?:(?:$MONTHS)\.?$SP(?:the$SP)?(\d{1,2}(?:st|nd|rd|th|er|e)?|$ORDINALS|(?:$DIGIT_WORDS)(?:[\s\-](?:$DIGIT_WORDS))?)|(\d{1,2}(?:st|nd|rd|th|er|e)?|$ORDINALS|(?:$DIGIT_WORDS)(?:[\s\-](?:$DIGIT_WORDS))?)$SP(?:of$SP)?(?:$MONTHS))(?![\p{L}\p{N}])""") // l10n: not text (a pattern)

    private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday" // l10n: not text (matched words)
    private const val JOURS = "lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche" // l10n: not text (matched words, French)
    /** French days as words (cardinals, "le trois"), longest first; "premier" only where it ends a phrase, never "le premier ministre". */
    private const val FR_DAY = "(?:vingt|trente)(?:[\\s\\-]et[\\s\\-]un|-(?:deux|trois|quatre|cinq|six|sept|huit|neuf))?|dix-(?:sept|huit|neuf)|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|" + // l10n: not text (matched words, French)
        "(?:premier|première|premiere)(?=\\p{Zs}*(?:[,.;:!?)»\\r\\n]|$)|\\p{Zs}+(?:à|au|avant|après|apres|et|ou|de|du|matin|soir)(?![\\p{L}]))" // l10n: not text (a pattern)
    /** A day's number with its ordinal ending, English or French: 3rd, 1er, 1re, 1ère, 3e, 3ème, 3ième, 2de, 2nde. */
    private const val NTH = "\\d{1,2}(?:st|nd|rd|th|er|e|re|ère|ere|ème|eme|ième|ieme|de|nde)" // l10n: not text (a pattern)
    /** A day's number after a French word: digits that aren't a d/m ("le 02/03") or an hour ("lundi 3 h"), or [FR_DAY]. */
    private const val FR_NUM = "\\d{1,2}(?![/.:\\-]\\p{N})(?!\\p{Zs}*h(?!\\p{L}))|$FR_DAY" // l10n: not text (a pattern)
    /** review-develop-14-b L3: "du 3 au 5", "du 1er au trois" (both days become [number]); groups 1 and 3 are the words. */
    private val DAY_SPAN = Regex("""(?iu)(?<![\p{L}\p{N}])(du$SP)(\d{1,2}(?:er|re|e)?|$FR_DAY)($SP(?:au|jusqu['’]au)$SP)($FR_NUM)(?![\p{L}\p{N}])""") // l10n: not text (a pattern)

    /**
     * A day with no month: "the 3rd", "21st", "le 1er", "le 3", and an ordinal word after on, by, until… the ("on the third").
     * review-develop-14-b L3: also a weekday's ("Tuesday the third"), French ordinal endings (1re, 3ème, 2de), and after le,
     * jusqu'au or a French weekday, digits or a French day word ("le premier", "le trois", "jusqu'au 3", "lundi 3").
     * An ordinal word anywhere else ("the first thing", "for the third time") isn't a day. Groups: 1 a numbered day; 2 and 3
     * the words before an ordinal word and the word; 4 and 5 "le" and its number (not a d/m, "le 02/03", which hides whole).
     */
    private val DAY_ALONE = Regex("""(?iu)(?<![\p{L}\p{N}])(?:($NTH)|((?:on|by|until|till|from|since|before|after|$WEEKDAYS)$SP(?:the$SP))($ORDINALS)|((?:le|jusqu['’]au|$JOURS)$SP(?:le$SP)?)($FR_NUM))(?![\p{L}\p{N}])""") // l10n: not text (a pattern)

    /** "May" next to a number said as a word ("no one may enter", "may one ask") isn't a date; with digits or an ordinal it is. */
    private fun mayWithAWord(m: MatchResult): Boolean {
        val day = (m.groups[1] ?: m.groups[2])!!.value
        return m.value.split(Regex("""[\s.]+""")).any { it.equals("may", ignoreCase = true) } && // l10n: not text (a pattern)
            day.split(Regex("""[\s\-]+""")).all { fold(it.lowercase()) in DIGIT_WORDS } // l10n: not text (a pattern)
    }

    /** An email, whoever's it is. */
    private val EMAIL = Regex("""[\p{L}\p{N}._%+\-]+@[\p{L}\p{N}\-]+(?:\.[\p{L}\p{N}\-]+)+""") // l10n: not text (a pattern)
    /** Postal codes: Canadian (B3H 4R2, any case), British (SW1A 1AA, before a comma or the line's end: never "PS5 1TB" or "M2 8GB card"), and a US ZIP after its state ("IL 62704-1234"). */
    private val POSTAL = Regex("""(?m)(?<![\p{L}\p{N}])(?:(?i:[A-Z]\d[A-Z][ \u00A0\-]?\d[A-Z]\d)|[A-Z]{1,2}\d[A-Z\d]?[ \u00A0]?\d[A-Z]{2}$THEN|(?<=[A-Z]{2}[ \u00A0])\d{5}(?:-\d{4})?)(?![\p{L}\p{N}])""") // l10n: not text (a pattern)
    private const val SP = "[ \\u00A0]+" // l10n: not text (a pattern)
    /** Words that aren't a street's name: a house number's street words never run across them ("3 timers on Maple Street", "a 5 minute drive"). */
    private const val STOP = "(?i:on|at|in|to|for|by|with|near|from|into|onto|past|the|a|an|and|or|of|my|your|is|was|it|more|other|minute|minutes|min|hour|hours|" + // l10n: not text (a pattern)
        "km|mile|miles|kilometres|kilometers|metres|meters|et|ou|au|aux|à|sur|pour|dans|en|est|avec|près|heure|heures|minuteur|minuteurs)(?![\\p{L}\\p{N}])" // l10n: not text (a pattern)
    /** A street's name word, in any case (12 maple street), never one of [STOP]. */
    private const val WORD = "(?!$STOP)\\p{L}" // l10n: not text (a pattern)
    /** 1 to 5 digits, then up to three words in any case and a street word (12 Maple St, 12 maple street), or a French street word and its name (45 rue saint-denis, 12, rue des Érables). */
    private const val SURE = "St|Street|Ave|Avenue|Rd|Road|Dr|Blvd|Boulevard|Ln|Lane|Ct|Cres|Crescent|Pl|Terrace|Hwy|Highway|Pkwy|Parkway" // l10n: not text (a pattern)
    /** Also everyday words ("3 Google Drive files", "Top 10 Best Place list"): a street only before a comma, a postal code or the line's end. */
    private const val UNSURE = "Drive|Place|Way|Court" // l10n: not text (a pattern)
    private const val THEN = """(?=\p{Zs}*(?:[,.;:?!)»”"]|\[address]|$))""" // l10n: not text (a pattern)
    private const val FR = """rue|chemin|ch\.|boulevard|boul\.|bd|avenue|av\.|rang|route|place|allée|impasse|montée|côte|promenade|quai|cours""" // l10n: not text (a pattern)
    /** In what you said, the French street words that aren't also everyday words. */
    private const val FR_SAID = "rue|chemin|boulevard|avenue|allée|impasse|montée|promenade|quai" // l10n: not text (a pattern)
    /** The street's name words are group 1 (English) or 2 (French). */
    private fun street(word: String, fr: String) = Regex("""(?m)(?<![\p{L}\p{N}])\d{1,5}[A-Za-z]?,?$SP(?:((?:$word[\p{L}'’.\-]*$SP){1,3})(?:(?i:$SURE)(?![\p{L}\p{N}])|(?i:$UNSURE)$THEN)""" + // l10n: not text (a pattern)
        """|(?i:$fr)$SP(?:(?i:de|des|du|la|le|les)$SP|(?i:[ld])['’])*($word[\p{L}'’\-]*(?:$SP$word[\p{L}'’\-]*){0,3}))""") // l10n: not text (a pattern)
    private val STREET = street(WORD, FR) // l10n: not text (a pattern)
    /** What you said has no capitals, and its French street words are only the ones that aren't everyday words; never a name of everyday words ("12 grand street"). */
    private val STREET_SAID = street(WORD, FR_SAID) // l10n: not text (a pattern)
    /** Said aloud: "jroy at videotron dot ca", "jean dot roy at gmail.com", « jroy arobase videotron point ca », "b three h four r two". */
    private val SPOKEN_EMAIL = Regex("""(?i)(?<![\p{L}\p{N}])[\p{L}\p{N}._\-]+(?:\s+(?:dot|point)\s+[\p{L}\p{N}._\-]+)*\s+(?:at|arobase)\s+[\p{L}\p{N}\-]+(?:(?:\s+(?:dot|point)\s+|\.)[\p{L}\p{N}\-]+)+(?![\p{L}\p{N}])""") // l10n: not text (a pattern)
    private val SPOKEN_POSTAL = Regex("""(?i)(?<![\p{L}\p{N}])[a-z]\s+(?:$DIGIT_WORDS|\d)\s+[a-z]\s+(?:$DIGIT_WORDS|\d)\s+[a-z]\s+(?:$DIGIT_WORDS|\d)(?![\p{L}\p{N}])""") // l10n: not text (a pattern)

    fun hide(text: String, names: Collection<String> = emptyList(), numbers: Collection<String> = emptyList(),
        addresses: Collection<String> = emptyList(), anyCase: Boolean = false): String =
        hide(text, names, numbers, addresses, if (anyCase) Mode.SAID else Mode.ANSWER)

    /**
     * What you said (speech to text, whose capitals mean nothing), my answer, or the tool line, whose arguments copy what
     * you said ("call(name: Grace)", "name":"rose"): there a name's part hides in any case, everyday words too.
     */
    private enum class Mode { SAID, ANSWER, TOOL }

    private fun hide(text: String, names: Collection<String>, numbers: Collection<String>, addresses: Collection<String>, mode: Mode): String {
        // Cleaned first, as the file will be, so a mark between digits can't split a number.
        var out = replace(ReportFile.clean(text), terms(addressLines(addresses), """[\s,]+"""), ADDRESS) // l10n: not text (a pattern)
        out = POSTAL.replace(EMAIL.replace(out, ADDRESS), ADDRESS)
        if (mode == Mode.SAID) out = SPOKEN_POSTAL.replace(SPOKEN_EMAIL.replace(out, ADDRESS), ADDRESS)
        val src = out
        out = (if (mode == Mode.SAID) STREET_SAID else STREET).replace(src) { m ->
            val name = (m.groups[1] ?: m.groups[2])?.value.orEmpty()
            val everyday = mode == Mode.SAID && name.split(' ', '\u00A0', '-').any { fold(it.trim('.', '\'', '’')) in ReportWords.ORDINARY } // l10n: not text (characters)
            // An everyday name ("12 high street") is an address only where one ends: before a comma, a stop or the line's end.
            if (everyday && AFTER.find(src, m.range.last + 1)?.range?.first != m.range.last + 1) m.value else ADDRESS
        }
        val whole = mutableListOf<String>()
        val lone = mutableListOf<String>()
        val siblings = HashMap<String, MutableSet<String>>()
        for (n in names.map { it.trim() }) {
            val parts = n.split(Regex("""[\s\-]+""")).filter { it.isNotEmpty() } // l10n: not text (a pattern)
            if (parts.size > 1) {
                whole += n
                val kept = parts.filterIndexed { i, p -> p.count(Char::isLetterOrDigit) >= MIN_PART && (i == 0 || fold(p) !in PARTICLES) }
                lone += kept
                for (p in kept) siblings.getOrPut(fold(p)) { HashSet() } += kept.map { fold(it) } - fold(p)
            } else if (n.length >= MIN_NAME) lone += n
        }
        out = replace(out, terms(whole, """[\s\-]+"""), NAME) // l10n: not text (a pattern)
        out = replace(out, terms(lone), NAME) { t, s, e -> fitsAName(t, s, e, mode, siblings) }
        val given = numbers.map { digits(it) }.filter { it.isNotEmpty() }.toSet()
        out = replace(out, SPOKEN, NUMBER)
        out = TWO_TENS.replace(out, NUMBER)
        out = MONTH_DAY.replace(out) { m -> if (mayWithAWord(m)) m.value else m.value.replace(m.groups[1]?.value ?: m.groups[2]!!.value, NUMBER) }
        out = DAY_SPAN.replace(out) { m -> m.groups[1]!!.value + NUMBER + m.groups[3]!!.value + NUMBER }
        out = DAY_ALONE.replace(out) { m -> if (m.groups[1] != null) NUMBER else (m.groups[2] ?: m.groups[4])!!.value + NUMBER }
        out = NUMBERS.replace(out) { m ->
            // Glued to a word ("902-555-0123x45", "1080p"): the whole run if it's long or given; a run of 3 digits hides below.
            if (!m.groups[1]?.value.isNullOrEmpty()) return@replace digits(m.value).let { d -> if (d.length >= SPREAD || isGiven(d, given)) NUMBER else m.value }
            var s = m.value
            var lead = ""
            // A "(" with no ")" after it is the text's own bracket.
            if (s.startsWith("(") && ')' !in s) { lead = "("; s = s.substring(1) }
            if (hidden(s, given)) lead + NUMBER else m.value
        }
        return STUCK.replace(out, NUMBER)
    }

    /**
     * One turn as the report carries it: what you said, my answer and the tool line, each hidden the same way. What you
     * said comes from speech to text, whose capitals mean nothing, so there one word alone hides in any case, except an
     * everyday word ([ReportWords.ORDINARY]: Mon, Grand, Côté, Day, Max), which hides only where it stands as the name: next
     * to the contact's other part, or after call, text, appelle, écris… ("call max", "écris à max"; never "call my").
     */
    data class Turn(val said: String?, val answer: String?, val tool: String?)

    fun hide(turn: Turn, names: Collection<String> = emptyList(), numbers: Collection<String> = emptyList(),
        addresses: Collection<String> = emptyList()): Turn = Turn(
        turn.said?.let { hide(it, names, numbers, addresses, Mode.SAID) },
        turn.answer?.let { hide(it, names, numbers, addresses, Mode.ANSWER) },
        turn.tool?.let { hide(it, names, numbers, addresses, Mode.TOOL) },
    )

    private fun hidden(s: String, given: Set<String>): Boolean {
        val d = digits(s)
        // A given number first, then any run of 3 digits (a year, a door code 2041) or 7 in all, and a day and month with a
        // slash ("11/8"; a fraction too, which can't be told from one).
        if (isGiven(d, given) || SLASH_DAY.matches(s)) return true
        return STUCK.containsMatchIn(s) || d.length >= SPREAD
    }

    private fun isGiven(d: String, given: Set<String>): Boolean = given.any { g -> g == d || (d.length >= TAIL && g.endsWith(d)) }

    /** Any script's digits, as 0 to 9. */
    private fun digits(s: String): String = buildString { for (c in s) Character.digit(c, 10).let { if (it >= 0) append('0' + it) } }

    /**
     * A given address line by line and whole; a line of one word with no digit and no @ (a city, a country) isn't hidden
     * alone. An email or XMPP address (zeb@localhost, o'brien@x.org) hides whole, as the email pattern may not see it.
     */
    private fun addressLines(addresses: Collection<String>): List<String> = addresses.flatMap { a ->
        val lines = a.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines + if (lines.size > 1) listOf(lines.joinToString(" ")) else emptyList()
    }.filter { l -> l.length >= MIN_NAME && (l.any(Char::isDigit) || '@' in l || l.split(Regex("""[\s,]+""")).count { it.isNotEmpty() } > 1) } // l10n: not text (a pattern)

    /** [s] folded as ReportSort.fold does (and ‘ straightened), with where each folded character came from in [s]. */
    private class Folded(val text: String, val from: IntArray)

    private fun folded(s: String): Folded {
        val out = StringBuilder(s.length)
        val from = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            val c = s.substring(i, i + n)
            val f = if (n == 1 && c[0] < '\u0080') c.lowercase() else ReportSort.fold(c).replace('‘', '\'')
            for (ch in f) { out.append(ch); from += i }
            i += n
        }
        return Folded(out.toString(), from.toIntArray())
    }

    private fun fold(s: String): String = folded(s).text

    /** A letter or digit that ends a word; Han, Hiragana, Katakana and Thai are written without spaces, so they don't. */
    private const val LETTER = """[\p{L}\p{N}&&[^\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsThai}]]""" // l10n: not text (a pattern)

    /** [list] as whole words in folded text, longest first; a term's words match across any run of [between]. */
    private fun terms(list: Collection<String>, between: String = """\s+"""): Regex? { // l10n: not text (a pattern)
        val split = Regex(between)
        val t = list.map { fold(it).trim() }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        if (t.isEmpty()) return null
        val alt = t.joinToString("|") { term -> term.split(split).filter { it.isNotEmpty() }.joinToString(between) { Regex.escape(it) } }
        return Regex("""(?<!$LETTER)(?:$alt)(?!$LETTER)""") // l10n: not text (a pattern)
    }

    /** Each match of [re] in [text] folded, mapped back to [text] (its accents with it), becomes [with] where [keep] says so. */
    private fun replace(text: String, re: Regex?, with: String, keep: (String, Int, Int) -> Boolean = { _, _, _ -> true }): String {
        if (re == null || text.isEmpty()) return text
        val f = folded(text)
        val out = StringBuilder()
        var last = 0
        for (m in re.findAll(f.text)) {
            if (m.range.isEmpty()) continue
            val s = f.from[m.range.first]
            val endAt = f.from[m.range.last]
            var e = endAt + Character.charCount(text.codePointAt(endAt))
            while (e < text.length && Character.getType(text[e]) == Character.NON_SPACING_MARK.toInt()) e++
            if (s < last || !keep(text, s, e)) continue
            out.append(text, last, s).append(with)
            last = e
        }
        return out.append(text, last, text.length).toString()
    }

    /**
     * One word alone fits a name where it's written with a capital and, an everyday word, isn't a sentence's first word
     * ("Mon alarme…" keeps its Mon). In what you said, see [hide] for a [Turn]. In the tool line any case fits, but an
     * everyday word only as a name argument's value.
     */
    private fun fitsAName(text: String, s: Int, e: Int, mode: Mode, siblings: Map<String, Set<String>>): Boolean {
        val w = fold(text.substring(s, e))
        val everyday = w in ReportWords.ORDINARY || w in ReportWords.NICKNAMES
        if (mode == Mode.TOOL) return !everyday || isNameValue(text, s)
        val common = w in COMMON || everyday || w in WORD_PARTICLES
        if (mode == Mode.SAID) return !common || standsAsAName(text, s, e, w, siblings[w].orEmpty())
        val first = (s until e).firstOrNull { text[it].isLetter() } ?: return false
        // A letter with no case (Arabic, Hebrew, Chinese, Hindi, Korean) counts as a capital.
        if (Character.isLowerCase(text.codePointAt(first))) return false
        if (!common) return true
        var i = s - 1
        while (i >= 0 && text[i] in OPENERS) i--
        return i >= 0 && text[i] !in ENDS
    }

    /** In the tool line, whether [at] is in a name argument's value: `name: Max`, `"to":"max"`, not `level: max`. */
    private fun isNameValue(t: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && t[i] !in VALUE_ENDS) i--
        if (i < 0 || (t[i] != ':' && t[i] != '=')) return false
        i--
        while (i >= 0 && (t[i].isWhitespace() || t[i] in QUOTES)) i--
        val end = i + 1
        while (i >= 0 && (t[i].isLetterOrDigit() || t[i] == '_')) i--
        val key = fold(t.substring(i + 1, end))
        return key in NAME_KEYS || key.endsWith("name") || key.endsWith("contact") // l10n: not text (argument names)
    }

    private const val VALUE_ENDS = ",;(){}[]:=\n" // l10n: not text (characters)
    private const val QUOTES = "\"'“”‘’" // l10n: not text (characters)
    private val NAME_KEYS = setOf("to", "who", "contact", "contacts", "recipient", "recipients", "person", "people", "nom", "destinataire") // l10n: not text (argument names)

    private fun standsAsAName(text: String, s: Int, e: Int, w: String, siblings: Set<String>): Boolean {
        val before = wordBefore(text, s)
        if (before?.first in siblings || wordAfter(text, e) in siblings) return true
        if (w in ReportWords.FUNCTION || before == null) return false
        return before.first in ReportWords.TO_NAME ||
            (before.first in ReportWords.LINKS && wordBefore(text, before.second)?.first in ReportWords.TO_NAME)
    }

    /** The word before [at], folded, and where it starts; spaces and a hyphen may stand between. */
    private fun wordBefore(t: String, at: Int): Pair<String, Int>? {
        var i = at - 1
        while (i >= 0 && (t[i].isWhitespace() || t[i] == '-')) i--
        val end = i + 1
        while (i >= 0 && t[i].isLetterOrDigit()) i--
        return if (end > i + 1) fold(t.substring(i + 1, end)) to i + 1 else null
    }

    private fun wordAfter(t: String, at: Int): String? {
        var i = at
        while (i < t.length && (t[i].isWhitespace() || t[i] == '-')) i++
        val start = i
        while (i < t.length && t[i].isLetterOrDigit()) i++
        return if (i > start) fold(t.substring(start, i)) else null
    }
}
