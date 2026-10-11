package app.heyrobot.core.memory

// Pure. What looks like a secret (design §A10): a PIN or NIP, a password or mot de passe, a door code, a card, SIN/NAS,
// passport or account number, a recovery phrase or an API key, and any long run of digits that isn't a phone number. An
// item that looks like one is still saved (no refusal), but marked secret: shown only on screen after unlock, never
// spoken unless you ask for it by name, never put in a prompt unless the question names it, never pinned, and never sent
// to a helper. The same test keeps such lines out of suggestions. A false positive costs little (an item that isn't
// read aloud), a miss costs more, so the words are generous; numbers that are clearly not secrets ("Room 1203", a date,
// a phone number, an area or postal code) are left alone.

/** What kind of secret a text holds, with what Robot says instead of it ("Remember: [a code, kept in Memory]"). */
enum class SecretKind(val en: String, val fr: String) {
    PASSWORD("a password", "un mot de passe"),
    PIN("a PIN", "un NIP"),
    CODE("a code", "un code"),
    CARD("a card number", "un numéro de carte"),
    ID_NUMBER("an ID number", "un numéro d'identité"),
    ACCOUNT("an account number", "un numéro de compte"),
    KEY("a key", "une clé"),
    LONG_NUMBER("a long number", "un long numéro"),
}

object SecretDetector {
    /** Digits that run together, in groups of three or more after the first ("4111 1111 1111 1111", "046 454 286"). */
    private val RUN = Regex("""(?<![\p{L}\p{N}])\+?\d+(?:[ \-.]\d{3,})*(?!\p{N})""")
    private val SSN = Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)""")
    /** A word that mixes letters and digits ("AB123456", "Tr0ub4dor", "A4512"). */
    private val MIXED = Regex("""(?<![\p{L}\p{N}])(?=[\p{L}\p{N}]*\p{N})(?=[\p{L}\p{N}]*\p{L})[\p{L}\p{N}]{4,}(?![\p{L}\p{N}])""")
    /** A long token of letters, digits and key punctuation with both letters and digits: an API key, a token. */
    private val KEY_SHAPE = Regex("""[A-Za-z0-9_\-+/=]{20,}""")
    private val RAMQ = Regex("""(?<![A-Za-z])[A-Z]{4} ?\d{4} ?\d{4}(?!\d)""")
    private val IBAN = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?(?![A-Za-z0-9])""")

    // All on folded text (lower case, no accents, plain apostrophes).
    private val PASSWORD = Regex("""\b(passwords?|passwd|passcodes?|pass ?phrases?|mots? de passe|mdp|wi-?fi (?:key|code))\b""")
    private val KEY_WORDS = Regex("""\b(api (?:key|token)|access token|secret key|private key|seed phrase|recovery (?:phrase|code|key)s?|backup codes?|2fa codes?|cle (?:api|privee|secrete)|phrase de recuperation|codes? de recuperation)\b""")
    private val PIN = Regex("""\b(pin|nip)\b""")
    private val PIN_STRONG = Regex("""\b(?:my|mon|ma|bank|card|debit|credit|sim|phone|banque|bancaire|carte|cellulaire)\s+(?:pin|nip)\b|\b(?:pin|nip)\s+(?:code|number|numero)\b""")
    private val CODE = Regex("""\bcodes?\b""")
    private val CODE_PLACES = Regex("""\b(?:door|gate|alarm|lock|garage|building|entry|entrance|access|safe|locker|lockbox|key ?box|alarm system) codes?\b|\bcodes? (?:de (?:la )?porte|d'acces|d acces|du garage|de l'alarme|de l alarme|du coffre|de la barriere|de l'immeuble|de l immeuble|du cadenas|de la boite a cles|d'entree|d entree|du systeme d'alarme)\b""")
    /** "area code 514", "postal code", "code postal", "dress code": not secrets. */
    private val CODE_HARMLESS_BEFORE = setOf("area", "zip", "postal", "country", "dress", "source", "bar", "promo", "discount", "coupon", "morse", "qr", "colour", "color", "zone")
    private val CODE_HARMLESS_AFTER = setOf("postal", "regional", "pays", "vestimentaire", "source", "barre", "promo", "couleur", "zone")
    private val CARD_WORDS = Regex("""\b(card number|credit card|debit card|numero de carte|carte de credit|carte de debit|cvv2?|cvc|security code|code de securite)\b""")
    private val ID_WORDS = Regex("""\b(social insurance|assurance sociale|social security|numero d'assurance sociale)\b""")
    private val ID_NEEDS_NUMBER = Regex("""\b(passports?|passeports?|health card|carte soleil|assurance maladie|ramq|driver'?s licen[cs]e|permis de conduire)\b""")
    private val ID_CAPS = Regex("""\b(SIN|NAS|SSN)\b""")
    private val ACCOUNT_WORDS = Regex("""\b(account numbers?|account no|numeros? de compte|no de compte|transit|folio|iban|routing numbers?|swift|bic)\b""")

    /** Every kind of secret [text] seems to hold; empty when none. */
    fun find(text: String): Set<SecretKind> {
        val out = LinkedHashSet<SecretKind>()
        val f = MemoryText.fold(text)
        val runs = RUN.findAll(text).map { it.value }.toList()
        val digitRuns = runs.map { r -> r.filter { it.isDigit() } }
        val hasDigits3 = digitRuns.any { it.length >= 3 }
        val mixed = MIXED.findAll(text).any()

        if (PASSWORD.containsMatchIn(f)) out += SecretKind.PASSWORD
        if (KEY_WORDS.containsMatchIn(f) || KEY_SHAPE.findAll(text).any { k -> k.value.count { it.isDigit() } >= 2 && k.value.count { it.isLetter() } >= 2 }) {
            out += SecretKind.KEY
        }
        if (PIN.containsMatchIn(f) && (digitRuns.any { it.length in 3..12 } || PIN_STRONG.containsMatchIn(f) || Regex("""\b(PIN|NIP)\b""").containsMatchIn(text))) {
            out += SecretKind.PIN
        }
        if (CODE_PLACES.containsMatchIn(f) || (secretCodeWord(f) && (hasDigits3 || mixed))) out += SecretKind.CODE
        if (CARD_WORDS.containsMatchIn(f) && hasDigits3) out += SecretKind.CARD
        if (ID_WORDS.containsMatchIn(f) || ID_CAPS.containsMatchIn(text) || SSN.containsMatchIn(text) || RAMQ.containsMatchIn(text) ||
            (ID_NEEDS_NUMBER.containsMatchIn(f) && (digitRuns.any { it.length >= 6 } || mixed))) {
            out += SecretKind.ID_NUMBER
        }
        if ((ACCOUNT_WORDS.containsMatchIn(f) && digitRuns.any { it.length >= 4 }) || IBAN.containsMatchIn(text)) out += SecretKind.ACCOUNT
        for ((raw, d) in runs.zip(digitRuns)) {
            when {
                d.length in 13..19 && luhn(d) -> out += SecretKind.CARD
                d.length == 9 && luhn(d) -> out += SecretKind.ID_NUMBER
                d.length >= 9 && !phoneShaped(raw) -> out += SecretKind.LONG_NUMBER
            }
        }
        return out
    }

    fun isSecret(text: String): Boolean = find(text).isNotEmpty()

    /** "code" somewhere in [folded] that isn't an area, postal, dress… code. */
    private fun secretCodeWord(folded: String): Boolean {
        val words = Regex("[\\p{L}\\p{N}']+").findAll(folded).map { it.value }.toList()
        return words.indices.any { i ->
            (words[i] == "code" || words[i] == "codes") &&
                words.getOrNull(i - 1) !in CODE_HARMLESS_BEFORE && words.getOrNull(i + 1) !in CODE_HARMLESS_AFTER
        }
    }

    /** The Luhn check card numbers and Canadian SINs pass. */
    fun luhn(digits: String): Boolean {
        if (digits.length < 2 || digits.any { !it.isDigit() }) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    /** A phone number, not a secret: "+…" with 8 to 15 digits, or a North American number (10 digits, or 11 starting with 1). */
    fun phoneShaped(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }
        if (raw.startsWith("+")) return d.length in 8..15
        fun nanp(n: String) = n.length == 10 && n[0] in '2'..'9' && n[3] in '2'..'9'
        return nanp(d) || (d.length == 11 && d[0] == '1' && nanp(d.substring(1)))
    }

    /** What Robot keeps or says instead of a secret ("[a code, kept in Memory]"), in English or French. */
    fun placeholder(kinds: Set<SecretKind>, fr: Boolean = false): String {
        val k = SecretKind.entries.firstOrNull { it in kinds } ?: SecretKind.CODE
        return if (fr) "[${k.fr}, gardé en mémoire]" else "[${k.en}, kept in Memory]"
    }
}
