// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

// Pure. Ask about your screenshots (docs/plans/screenshots-plan.md, question 1's default): what a screenshot's entry may
// keep. A screenshot that looks like it holds a password, a code or a card ([skips]: a secret's label anywhere, or a
// card's number) is skipped whole (fix-screenshots-d, -e): its entry keeps only that it's there and its date, never its
// text, so Robot can say it has one from that day, never what's in it. In any other, a secret's value [hide] still finds
// is replaced by its kind's token before the entry is sealed, so no index, prompt or answer ever holds it. A booking's
// confirmation number, a tracking number or a phone number stays as it is: those are what you ask about. It fails
// closed (review-screenshots H1): a value hidden for nothing costs little, a kept secret costs a lot.

/** A kind of secret an entry keeps only as its token. The tokens are for the model, not shown as they are. */
enum class SecretKind(val token: String) {
    CODE("[sign-in code]"), // l10n: not text (a token the model reads)
    PASSWORD("[password]"), // l10n: not text (a token the model reads)
    RECOVERY("[recovery code]"), // l10n: not text (a token the model reads)
    CARD("[card number]"), // l10n: not text (a token the model reads)
}

object ShotSecrets {
    /** [text] with every secret's value replaced by its kind's token; [kinds], the kinds it held. */
    data class Hidden(val text: String, val kinds: Set<SecretKind>)

    // A word's edges, Unicode-aware (é, É), the same on the JVM and Android's ICU.
    private const val L = """(?<![\p{L}\p{Nd}])""" // l10n: not text (a pattern)
    private const val R = """(?![\p{L}\p{Nd}])""" // l10n: not text (a pattern)

    /** 13 or more digits in groups of 3 or more (split by any space or a hyphen): a card, Luhn or not (failing closed),
     *  unless it isn't a card's and its line names what it is ([KEEP_NUMBER]). */
    private val CARD = Regex("""(?<!\p{N})\p{N}{3,}(?:[\p{Zs}\-]\p{N}{3,})*(?!\p{N})""") // l10n: not text (a pattern)
    private val CVV = Regex("""(?iu)$L(cvv2?|cvc2?|card security code|code de sécurité de la carte)$R\W{0,6}(\d{3,4})\b""") // l10n: not text (a pattern)
    private val KEEP_NUMBER = Regex("""(?iu)$L(tracking|track|suivi|parcel|colis|shipment|order|commande|e-?ticket|ticket|billet|booking|reservation|réservation|confirmation|flight|vol)$R""") // l10n: not text (a pattern)
    /** fix-screenshots-e item 4 (M5, M6): a long number right after what names it, on its line or the line above, stays
     *  ("Tracking number⏎7023…", "Order # 112-…", an e-ticket's), even passing Luhn; a card after it doesn't. */
    private val KEEP_BEFORE = Regex("""(?iu)$L(?:tracking|track|suivi|parcel|colis|shipment|order|commande|e-?ticket|ticket|billet|booking|reservation|réservation|confirmation|flight|vol)$R(?:\p{Zs}*(?:numbers?|no|nos|num|nr|numéro|numero|code|id|ref)\.?$R)?[\p{Zs}\n:#°º.\-]*${'$'}""") // l10n: not text (a pattern)
    private val NARROW = Regex("""[\p{Zs}\-.]""") // l10n: not text (a pattern)
    private val RECOVERY_WORD = Regex("""(?iu)(recovery|backup|reserve) (codes?|key)|(recovery|seed) (phrase|words)|secret key|phrases? de récupération|clés? secrètes?|codes? (de récupération|de secours|de sauvegarde)|clés? de (récupération|secours)""") // l10n: not text (a pattern)
    private val RECOVERY_VALUE = Regex("""(?<![\w-])(?:[A-Za-z0-9]{4,6}(?:[- ][A-Za-z0-9]{4,6})+|\d{6,10}|[A-Za-z0-9]{8,12})(?![\w-])""") // l10n: not text (a pattern)
    /** A password-like label: everything after it on its line is hidden (after its "is", "est" or colon, if any). */
    private val PASSWORD = Regex("""(?iu)$L(password|passwd|passcode|passphrase|mot de passe|phrase de passe|wi-?fi key|network key|wpa2? key|clé wpa2?|clé wi-?fi|clé (du )?réseau|clé de sécurité réseau)$R""") // l10n: not text (a pattern)
    /** Short labels, a password only when a colon or an equals sign follows. */
    private val PASSWORD_SHORT = Regex("""(?iu)$L(?<!boarding )(?<!embarquement )(pass|pwd|pw|mdp)(?=\s*[:=：])""") // l10n: not text (a pattern)
    private val SEPARATOR = Regex("""^.{0,40}?(?:[:=：]|(?<!\p{L})(?:is|est)(?!\p{L}))\s*[:=：]?""") // l10n: not text (a pattern)
    /** A line with one of these words hides every code-shaped value on it and on the line after. */
    private val STRONG_WORD = Regex("""(?iu)$L(otp|pin|nip|2fa|mfa|one[- ]time|single[- ]use|verify|verification|vérifier|vérification|authenticat\p{L}*|authentificat\p{L}*|passcode|password|mot de passe|mdp|usage unique)$R""") // l10n: not text (a pattern)
    /** Words that name a code only sometimes ("Sign in to manage your booking"): as with "code", [NOT_SECRET] keeps the line. */
    private val CODE_WORD = Regex("""(?iu)$L(codes?|sign[- ]?in|log[- ]?in|connexion|se connecter|security|sécurité|access|accès)$R""") // l10n: not text (a pattern)
    /** A plain "code" whose line names a booking, a boarding pass, a parcel or a promotion: that code stays. */
    private val NOT_SECRET = Regex("""(?iu)$L(booking|reservation|réservation|boarding|embarquement|tracking|suivi|order|commande|promo|coupon|rabais|discount|postal|zip|gift|cadeau|parcel|colis|error|erreur|area|régional|trip|voyage|flight|vol|hotel|hôtel|e-?ticket|ticket|billet)$R""") // l10n: not text (a pattern)
    /** fix-train-14: a date ("Nov 9, 2026", "9 nov. 2026", "2026-11-09", "11/09/2026") is never a code, even on a line
     *  with "login" ("Last login: Nov 9, 2026" kept its year). A code alone stays hidden ("Login code: 2026"). */
    private const val MONTH = """(?:jan(?:uary)?|janv(?:ier)?|feb(?:ruary)?|f[ée]vr?(?:ier)?|mar(?:ch|s)?|apr(?:il)?|avr(?:il)?|may|mai|june?|juin|july?|juil(?:let)?|aug(?:ust)?|ao[uû]t?|sept?(?:ember|embre)?|oct(?:ober|obre)?|nov(?:ember|embre)?|dec(?:ember)?|d[ée]c(?:embre)?)\.?""" // l10n: not text (a pattern)
    private val DATE = Regex("""(?iu)(?<![\p{L}\p{Nd}])(?:$MONTH\s+\d{1,2}(?:st|nd|rd|th)?,?\s+(?:19|20)\d{2}|\d{1,2}(?:er)?\s+$MONTH,?\s+(?:19|20)\d{2}|(?:19|20)\d{2}-\d{1,2}-\d{1,2}|\d{1,2}/\d{1,2}/(?:19|20)\d{2})(?![\p{L}\p{Nd}])""") // l10n: not text (a pattern)
    private fun inDate(line: String, r: IntRange): Boolean = DATE.findAll(line).any { r.first >= it.range.first && r.last <= it.range.last }
    private val VALUE_WORD = Regex("""(?<![\p{L}\p{Nd}\[-])[\p{L}\p{Nd}]+(?:-[\p{L}\p{Nd}]+)*(?![\p{L}\p{Nd}\]-])""") // l10n: not text (a pattern)
    private val DIGITS3 = Regex("""\p{Nd}{3,}""") // l10n: not text (a pattern)
    /** Digits typed apart ("7 3 9 1 0 4", "58 29 13"): one value. */
    private val SPACED = Regex("""(?<![\p{L}\p{Nd}])\p{Nd}{1,4}(?:\p{Zs}\p{Nd}{1,4}){2,}(?![\p{L}\p{Nd}])""") // l10n: not text (a pattern)
    /** A phone number, which stays ("Call 902-555-0142 about the security deposit"). */
    private val PHONE = Regex("""(?:\+?1-)?\(?\d{3}\)?-?\d{3}-\d{4}|\d{3}-\d{4}""") // l10n: not text (a pattern)

    /** A code-shaped value: three digits in a row, or 4 to 12 characters mixing letters and two digits or more. */
    private fun codeShaped(v: String): Boolean =
        DIGITS3.containsMatchIn(v) || (v.length in 4..12 && v.any(Char::isLetter) && v.count(Char::isDigit) >= 2)

    // fix-screenshots-d: a second round of misses in the line hiding below, so what's indexed is narrowed first. A
    // screenshot with a secret's label anywhere, or a card's number however grouped, is never read as text ([skips]).
    /** Labels, on [fold]ed text (OCR's slips read back, lower case, no accents, one plain space). The bare word "code" isn't one. */
    private val SKIP_LABEL = Regex( // l10n: not text (a pattern)
        """(?<![a-z0-9])(?:passwords?|passwd|personal identification numbers?|numeros? d'identification personnels?|passcodes?|passphrases?|cvv2?|cvc2?|otp|2fa|mfa|""" + // l10n: not text (a pattern)
        """(?:verification|security|sign[- ]?in|log[- ]?in|access|authentication) codes?|recovery (?:codes?|keys?|phrases?|words)|""" + // l10n: not text (a pattern)
        """seed (?:phrases?|words)|secret keys?|backup codes?|authenticator|wi ?fi key|network key|wpa2? key|""" + // l10n: not text (a pattern)
        """(?:pw|pwd|mdp|sign in|identifiant) ?[:=]|(?<!last |previous |dernier |derniere )log ?in ?[:=]|""" + // l10n: not text (a pattern)
        """mots? de passe|phrases? de passe|codes? de (?:verification|securite|secours|recuperation|connexion|authentification)|""" + // l10n: not text (a pattern)
        """codes? d'(?:acces|authentification)|phrases? de recuperation|cles? secretes?|cles? wi ?fi|""" + // l10n: not text (a pattern)
        """cles? (?:du )?reseau|authentificateur)(?![a-z0-9])""") // l10n: not text (a pattern)
    // fix-screenshots-e item 2 (M1): a label ending in "key" or starting with "clé", whatever its value looks like.
    /** review-last-fixes-14 Low 1: a key or clé is a secret's only beside one of these words ("Issue key:", "Clé USB :" read). */
    private val SECRET_WORD = Regex("""(?<![a-z0-9])(?:security|network|wireless|pre ?shared|private|api|product|secret|licen[cs]e|access|encryption|wi ?fi|wpa2?|wep|router|ssid|securite|reseau|privee|secrete|produit|chiffrement|acces)(?![a-z0-9])""") // l10n: not text (a pattern)
    /** An answer's label counts only within two lines of a security question (an FAQ's "Answer:" reads). */
    private val SECURITY_Q = Regex("""(?:security|secret) questions?|questions? (?:de securite|secretes?)""") // l10n: not text (a pattern)
    private val KEY_LABEL = Regex("""(?m)(?<![a-z0-9])keys? ?[:=]|(?:^|[^a-z0-9 ]) ?cles? [^:=\n]{0,40}[:=]""") // l10n: not text (a pattern)
    /** The same label alone on its line (at most four words), its value the next line's one word. */
    private val KEY_ABOVE = Regex("""(?:[\p{L}']+ ){0,3}keys?|cles?(?: [\p{L}']+){0,3}""") // l10n: not text (a pattern)
    /** A security question's answer: a label only within two lines of a line with "question". */
    /** "Login" alone on its line, a label when the next line is one word (an address), not "Log In⏎Sign Up". */
    private val LOG_IN = Regex("""log ?in""") // l10n: not text (a pattern)
    private val ANSWER = Regex("""(?<![a-z0-9])(?:answers?|reponses?) ?[:=]""") // l10n: not text (a pattern)
    // fix-screenshots-e item 3 (M4): everyday words that are a label only in a label's form: alone before a colon ("PIN:",
    // "Login:", not "Last login:" or "Season pass:"), pin or nip ending a line, beside code or password, or PIN/NIP in
    // capitals. "Log in" or "Pass" ending a line is a site's header or a game's button (review-last-fixes-14 M3).
    private const val SHORT = """(?:pins?|nip|pass|login|log in|one ?time|a usage unique|usage unique)""" // l10n: not text (a pattern)
    private val SHORT_LABEL = Regex("""(?m)(?:^|[^a-z0-9 ]) ?$SHORT ?[:=]|""" + // l10n: not text (a pattern)
        """(?<![a-z0-9])(?:codes?|passwords?|passcodes?|mots? de passe) (?:a |de |d'|du )?$SHORT(?![a-z0-9])|""" + // l10n: not text (a pattern)
        """(?<![a-z0-9])$SHORT (?:codes?|passwords?|passcodes?|words?|mots? de passe)(?![a-z0-9])""") // l10n: not text (a pattern)
    private val CAPS = Regex("""(?<![\p{L}\p{Nd}])(?:PINS?|NIP)(?![\p{L}\p{Nd}])""") // l10n: not text (a pattern)
    // fix-screenshots-g: pin or nip ending a line ("Pin" in a menu, "Pins" in a map's tabs, "Card / PIN") is a label only
    // when the next lines hold a value, three digits or more; a receipt's "VERIFIED BY PIN" ending its line names none.
    // fix-screenshots-h (review-screenshots-g M1): marks after it ("PIN*", "PIN -", "« NIP »") end it too.
    private val PIN_END = Regex("""(?:.*[^a-z0-9 ])? ?(?:pins?|nip)[^a-z0-9]*""") // l10n: not text (a pattern)
    private val PIN_NO_VALUE = Regex("""(?imu)^[^\p{L}\p{Nd}\n]*(?:PINS?|NIP)[^\p{L}\p{Nd}\n]*$|(?<![\p{L}\p{Nd}])(?:verified\p{Zs}+by\p{Zs}+pin|pin\p{Zs}+verified|v[ée]rifi[ée]e?\p{Zs}+par\p{Zs}+nip|nip\p{Zs}+v[ée]rifi[ée]e?)[^\p{L}\p{Nd}\n]*$""") // l10n: not text (a pattern)
    private val DIGIT = Regex("""\p{Nd}""") // l10n: not text (a pattern)
    /** fix-screenshots-h (review-screenshots-g L1): a receipt's "VERIFIED BY PIN" ([fold]ed) is a label beside a lone number. */
    private val VERIFIED = Regex("""(?<![a-z0-9])(?:verified by pin|pin verified|verifiee? par nip|nip verifiee?)(?![a-z0-9])""") // l10n: not text (a pattern)
    /** A line of digits alone, spaces and hyphens between them ("4821", "48 21"): a value with no word to say what it is. */
    private val BARE = Regex("""\p{Nd}[\p{Nd}\p{Zs}\-]*""") // l10n: not text (a pattern)
    /** review-screenshots-h Low 2: a clock time ("10:42", "14:32:05") or a date ("2026-11-11"), never a pin's value. */
    private val CLOCK_DATE = Regex("""(?<![\p{Nd}:\-])(?:\p{Nd}{1,2}:\p{Nd}{2}(?::\p{Nd}{2})?|\p{Nd}{4}-\p{Nd}{2}-\p{Nd}{2})(?![\p{Nd}:\-])""") // l10n: not text (a pattern)
    /** How many non-empty lines a lone label looks at, down or up (review-screenshots-g H1: a value two lines down). */
    private const val REACH = 3
    private val MARKS = Regex("""\p{Mn}+""") // l10n: not text (a pattern)
    private val SPACES = Regex("""[\p{Zs}\s]+""") // l10n: not text (a pattern)
    /** A run of digit groups split by one space of any kind, a hyphen or a dot, or by any one to three characters that
     *  are neither letters nor digits ("4111  1111", "4111 - 1111", "4111/1111", a line break) before three digits or more;
     *  [cardInText] keeps a wider gap only between groups of 4 to 6 digits. */
    private val DIGIT_RUN = Regex("""(?<!\p{Nd})\p{Nd}+(?:[\p{Zs}\-.]\p{Nd}+|[^\p{L}\p{Nd}]{1,3}\p{Nd}{3,})*(?!\p{Nd})""") // l10n: not text (a pattern)
    private val GROUP = Regex("""\p{Nd}+""") // l10n: not text (a pattern)

    // fix-screenshots-e item 1 (review-screenshots-c M3): what OCR reads for a letter (P@ssw0rd, C0de, P1N) is read back
    // as that letter inside a word that has one; a 1 both as an l and as an i.
    private val OCR_WORD = Regex("""[\p{L}\p{Nd}@${'$'}]+""") // l10n: not text (a pattern)
    private val ONES = listOf('L', 'I')
    private val HYPHEN = Regex("""(?<=\p{L})[-\u2010\u2011_](?=\p{L})""") // l10n: not text (a pattern)
    private val LINE_SPACES = Regex("""[\p{Zs}\t\r\u000B\f]+""") // l10n: not text (a pattern)

    /** [s] with OCR's digit-for-letter slips read as letters, [one] for a 1; the same length, so a match's place holds. */
    private fun ocr(s: String, one: Char): String = OCR_WORD.replace(s) { m ->
        val v = m.value
        if (v.none(Char::isLetter)) v else String(CharArray(v.length) { i ->
            when (val c = v[i]) { '0' -> 'O'; '1' -> one; '3' -> 'E'; '4', '@' -> 'A'; '5', '\$' -> 'S'; '7' -> 'T'; else -> c }
        })
    }

    /** [ocr], then lower case, no accents, a hyphen between letters as a space, one plain space; lines kept. */
    private fun fold(s: String, one: Char): String = java.text.Normalizer.normalize(ocr(s, one), java.text.Normalizer.Form.NFD).replace(MARKS, "")
        .lowercase(java.util.Locale.ROOT).replace('\u2019', '\'').replace(HYPHEN, " ").replace(LINE_SPACES, " ")

    /** A card's number: 13 to 19 digits that pass Luhn and start as the card networks' do (1 to 6). */
    fun cardLike(digits: String): Boolean = digits.isNotEmpty() && digits[0] in '1'..'6' && luhn(digits)

    /** A card in a run of digit groups: the whole run, or its first groups (a card with its CVV after it, say). */
    private fun cardIn(run: String): Boolean = cardIn(GROUP.findAll(run).map { it.value }.toList())

    private fun cardIn(groups: List<String>): Boolean {
        var d = ""
        for ((i, g) in groups.withIndex()) { d += g; if (cardLike(d) && !years(groups.subList(0, i + 1))) return true; if (d.length > 19) break }
        return false
    }

    /** fix-screenshots-g: groups that are all years, 1900 to 2099 ("2019⏎2020⏎2021⏎2022"), a list and no card: no card
     *  network's number starts with 19 or 20 (Mastercard's 2-series starts at 2221). */
    private fun years(groups: List<String>): Boolean = groups.all { it.length == 4 && (it.startsWith("19") || it.startsWith("20")) }

    /** A card's own grouping, 4-4-4-4 passing Luhn: a card even right after a label (review-last-fixes-14 Low 2). */
    private fun cardGrouped(groups: List<String>): Boolean =
        groups.size >= 4 && groups.take(4).all { it.length == 4 } && cardLike(groups.take(4).joinToString(""))

    /** A card anywhere in [text]'s digit runs: from any of a run's groups, since a run now spans lines (a total, then a card). */
    private fun cardInText(text: String): Boolean = DIGIT_RUN.findAll(text).any { run ->
        // review-last-fixes-14 M1: a gap wider than one character joins only groups of 4 to 6 digits, as a split card's
        // are, so a list of phone numbers, dates or prices on lines of their own is never read as one card.
        val all = GROUP.findAll(run.value).toList()
        val parts = ArrayList<List<MatchResult>>()
        var start = 0
        for (i in 1..all.size) {
            if (i == all.size || !(NARROW.matches(run.value.substring(all[i - 1].range.last + 1, all[i].range.first)) ||
                    (all[i - 1].value.length in 4..6 && all[i].value.length in 4..6))) { parts += all.subList(start, i); start = i }
        }
        parts.any { found -> cardInGroups(text, run.range.first, run.value, found) }
    }

    private fun cardInGroups(text: String, at: Int, run: String, found: List<MatchResult>): Boolean {
        val groups = found.map { it.value }
        // The number a label names ("Order # 112-4829137-5520311"): its groups up to the first wider gap aren't a card's.
        var from = 0
        if (keptNumber(text, at + found[0].range.first) && !cardGrouped(groups)) {
            from = 1
            while (from < found.size && NARROW.matches(run.substring(found[from - 1].range.last + 1, found[from].range.first))) from++
        }
        return (from until groups.size).any { s -> cardIn(groups.subList(s, groups.size)) }
    }

    private fun keptNumber(text: String, at: Int): Boolean = KEEP_BEFORE.containsMatchIn(text.substring(maxOf(0, at - 60), at))

    /** Whether a screenshot whose text is [text] is kept only as there, never as its text: a secret's label anywhere, or a card. */
    fun skips(text: String): Boolean = ONES.any { labelled(fold(text, it), ocr(text, it), text) } || cardInText(text)

    /** A secret's label in [lined] ([fold]ed text, its lines kept) or [raw] ([ocr] only, its case kept). */
    private fun labelled(lined: String, raw: String, text: String): Boolean {
        if (SKIP_LABEL.containsMatchIn(lined.replace(SPACES, " ")) || keyLabel(lined) ||
            SHORT_LABEL.containsMatchIn(lined) || CAPS.containsMatchIn(raw.replace(PIN_NO_VALUE, ""))) return true
        val lines = lined.split('\n')
        val textLines = text.split('\n').map { it.replace(CLOCK_DATE, " ") }
        val bare = lines.map { l -> l.trim { !it.isLetterOrDigit() } }
        val filled = lines.indices.filter { bare[it].isNotEmpty() }
        fun after(i: Int) = filled.filter { it > i }.take(REACH)
        fun before(i: Int) = filled.filter { it < i }.takeLast(REACH)
        fun loneText(s: String) = BARE.matches(s) && DIGIT.findAll(s).count() >= 3
        fun lone(j: Int) = loneText(textLines.getOrElse(j) { "" }.trim())
        return lines.indices.any { i ->
            (ANSWER.containsMatchIn(lines[i]) && (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).any { SECURITY_Q.containsMatchIn(lines[it]) }) ||
                // fix-screenshots-h (H1, L2): digits counted over the next three lines, so a PIN two lines down or split counts.
                // fix-screenshots-i (review-screenshots-h H1): or a lone number three lines up or anywhere down.
                (PIN_END.matches(lines[i]) && (after(i).sumOf { DIGIT.findAll(textLines.getOrElse(it) { "" }).count() } >= 3 ||
                    (before(i) + filled.filter { it > i }).any(::lone))) ||
                // fix-screenshots-i (review-screenshots-h M1): a lone number on its own line too ("4821 VERIFIED BY PIN").
                (VERIFIED.containsMatchIn(lines[i]) && ((after(i) + before(i)).any(::lone) ||
                    loneText(VERIFIED.replace(lines[i], " ").trim { !it.isLetterOrDigit() }))) ||
                (((KEY_ABOVE.matches(bare[i]) && SECRET_WORD.containsMatchIn(bare[i])) || LOG_IN.matches(bare[i])) && (i + 1 until lines.size).firstOrNull { bare[it].isNotEmpty() }?.let { ' ' !in bare[it] } == true)
        }
    }

    /** [KEY_LABEL] with a secret's word on its line or the line above ("Wi-Fi BELL-5G-22⏎Key:"). */
    private fun keyLabel(lined: String): Boolean = KEY_LABEL.findAll(lined).any { m ->
        val start = lined.lastIndexOf('\n', m.range.first)
        val above = if (start > 0) lined.lastIndexOf('\n', start - 1) + 1 else 0
        SECRET_WORD.containsMatchIn(lined.substring(above, m.range.last + 1))
    }

    /** Luhn's check, so a long number that isn't a card (a tracking number, say) stays. */
    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c.digitToIntOrNull() ?: return false
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    fun hide(text: String): Hidden {
        val kinds = LinkedHashSet<SecretKind>()
        var t = CARD.replace(text) { m ->
            // review-screenshots-b M2: a long number that isn't a card's stays when its line says what it is (a parcel's,
            // an order's, a ticket's, a booking's), as FilePrompt.SHOT_READ asks the model to write them.
            val line = text.substring(text.lastIndexOf('\n', m.range.first) + 1).substringBefore('\n')
            val keep = m.value.count(Char::isDigit) < 13 || (keptNumber(text, m.range.first) && !cardGrouped(GROUP.findAll(m.value).map { it.value }.toList())) || (!cardIn(m.value) && KEEP_NUMBER.containsMatchIn(line))
            if (keep) m.value else SecretKind.CARD.token.also { kinds += SecretKind.CARD }
        }
        t = CVV.replace(t) { m -> kinds += SecretKind.CARD; m.value.replace(m.groupValues[2], SecretKind.CARD.token) }
        t = passwords(t, kinds)
        // Recovery codes: every code-shaped word in the 300 characters after their heading.
        RECOVERY_WORD.findAll(t).toList().asReversed().forEach { m ->
            val end = minOf(t.length, m.range.last + 1 + 300)
            val tail = t.substring(m.range.last + 1, end)
            val hidden = RECOVERY_VALUE.replace(tail) { _ -> SecretKind.RECOVERY.token.also { kinds += SecretKind.RECOVERY } }
            t = t.substring(0, m.range.last + 1) + hidden + t.substring(end)
        }
        return Hidden(codes(t, kinds), kinds)
    }

    /** Everything after a password-like label on its line; a label alone on its line hides the next line. */
    private fun passwords(text: String, kinds: MutableSet<SecretKind>): String {
        val lines = text.split('\n').toMutableList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = ONES.firstNotNullOfOrNull { o -> ocr(line, o).let { PASSWORD.find(it) ?: PASSWORD_SHORT.find(it) } }
            if (m != null) {
                val head = line.substring(0, m.range.last + 1)
                val rest = line.substring(m.range.last + 1)
                val sep = SEPARATOR.find(rest)
                if ((sep?.let { rest.substring(it.range.last + 1) } ?: rest).none(Char::isLetterOrDigit)) {
                    // The label alone ("**Password:**", "Password (case-sensitive):"): its value is on the next line.
                    val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
                    if (next != null) { lines[next] = SecretKind.PASSWORD.token; kinds += SecretKind.PASSWORD; i = next }
                } else {
                    val keep = sep?.let { rest.substring(0, it.range.last + 1) } ?: ""
                    lines[i] = head + keep.trimEnd() + " " + SecretKind.PASSWORD.token
                    kinds += SecretKind.PASSWORD
                }
            }
            i++
        }
        return lines.joinToString("\n")
    }

    /** One-time codes: every code-shaped value on a line with a code word, and on the next line that isn't blank. */
    private fun codes(text: String, kinds: MutableSet<SecretKind>): String {
        val lines = text.split('\n').toMutableList()
        val hideAt = BooleanArray(lines.size)
        for ((i, line) in lines.withIndex()) {
            val seen = ONES.map { ocr(line, it) }
            val strong = seen.any { STRONG_WORD.containsMatchIn(it) }
            if (!strong && seen.none { CODE_WORD.containsMatchIn(it) && !NOT_SECRET.containsMatchIn(it) }) continue
            hideAt[i] = true
            // fix-screenshots-h (review-screenshots-g H1): the next three lines that aren't blank, as [labelled] reads them.
            (i + 1 until lines.size).asSequence().filter { lines[it].isNotBlank() }.take(REACH).forEach { hideAt[it] = true }
        }
        // A value split over lines of digits alone ("48⏎21", review-screenshots-g L2): hidden whole when they hold three or more.
        val run = ArrayList<Int>()
        fun flush() {
            if (run.sumOf { DIGIT.findAll(lines[it]).count() } >= 3) run.forEach { lines[it] = SecretKind.CODE.token; kinds += SecretKind.CODE }
            run.clear()
        }
        for (i in lines.indices) {
            if (lines[i].isBlank()) continue
            val l = lines[i].trim()
            if (hideAt[i] && BARE.matches(l) && !PHONE.matches(l)) run += i else flush()
        }
        flush()
        for (i in lines.indices) {
            if (!hideAt[i]) continue
            val o = lines[i]
            val l = SPACED.replace(o) { v -> if (PHONE.matches(v.value.replace(SPACES, "-")) || inDate(o, v.range)) v.value else SecretKind.CODE.token.also { kinds += SecretKind.CODE } }
            lines[i] = VALUE_WORD.replace(l) { v -> if (codeShaped(v.value) && !PHONE.matches(v.value) && !inDate(l, v.range)) SecretKind.CODE.token.also { kinds += SecretKind.CODE } else v.value }
        }
        return lines.joinToString("\n")
    }
}
