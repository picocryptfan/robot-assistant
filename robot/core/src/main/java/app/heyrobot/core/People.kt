package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. Who "Mom" is: the contacts' names ranked against the name the model gave, the numbers and addresses a text or
// an email can go to (mobile first for texts), phone numbers in E.164 and the address of a number at the SMS gateway.
// The app process reads ContactsContract and calls PhoneNumberUtils; every choice is made here, so it is tested.

enum class PhoneKind { MOBILE, HOME, WORK, OTHER }

/** [label]: the type as the phone names it ("Mobile", or a custom label). */
data class ContactPhone(val number: String, val kind: PhoneKind, val label: String)

/** A contact's name and nicknames, enough to rank it. */
data class ContactName(val id: Long, val name: String, val nicknames: List<String> = emptyList())

/** One contact's ways to reach it. [xmpp]: ContactsContract Im rows with the Jabber protocol. */
data class ContactCard(
    val id: Long,
    val name: String,
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<String> = emptyList(),
    val xmpp: List<String> = emptyList(),
)

/** How a contact's name matched, best first. */
enum class NameMatch { EXACT, PREFIX, WORD, FUZZY }

data class ContactMatch(val id: Long, val name: String, val kind: NameMatch)

object ContactMatcher {
    /** Words that say whose, not who ("text my mom"). */
    private val FILLER = setOf("my", "the", "to")

    /** Lower case, accents off, split on anything that isn't a letter or digit. */
    fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * How [query] matches [name], or null. Exact (case and accents aside), then the name starting with it ("Alex" →
     * "Alexandra Roy"), then a word of the name being or starting with each word asked ("Roy" → "Alexandra Roy"), then
     * one or two letters off ("Smyth" → "Smith") for names of 4 letters or more: under that ("Mom"/"Tom", "Dan"/"Don")
     * it would be a guess.
     */
    fun kind(query: String, name: String): NameMatch? {
        val q = words(query).filter { it !in FILLER }
        val n = words(name)
        if (q.isEmpty() || n.isEmpty()) return null
        val qs = q.joinToString(" ")
        val ns = n.joinToString(" ")
        val qc = qs.replace(" ", "")
        return when {
            qs == ns || qc == ns.replace(" ", "") -> NameMatch.EXACT
            qc.length >= 2 && ns.startsWith(qs) -> NameMatch.PREFIX
            q.all { w -> n.any { it == w || (w.length >= 2 && it.startsWith(w)) } } -> NameMatch.WORD
            fuzzy(qs, ns) || (q.size == 1 && n.any { fuzzy(qs, it) }) -> NameMatch.FUZZY
            else -> null
        }
    }

    private fun fuzzy(q: String, n: String): Boolean {
        val allowed = when {
            q.length < 4 -> 0
            q.length <= 5 -> 1
            else -> 2
        }
        return allowed > 0 && kotlin.math.abs(q.length - n.length) <= allowed && AppMatcher.editDistance(q, n) <= allowed
    }

    /** Every contact that matches by name or nickname, best kind first, then shorter names, then by name. */
    fun rank(query: String, contacts: List<ContactName>): List<ContactMatch> = contacts
        .mapNotNull { c -> (listOf(c.name) + c.nicknames).mapNotNull { kind(query, it) }.minOrNull()?.let { ContactMatch(c.id, c.name, it) } }
        .sortedWith(compareBy<ContactMatch> { it.kind.ordinal }.thenBy { it.name.length }.thenBy { it.name.lowercase(Locale.ROOT) })

    /**
     * The contacts a request means: only the best kind of match there is (one exact name wins over every prefix, and
     * a fuzzy match counts only when nothing better exists), at most [max]. Several left: the card asks which.
     */
    fun best(query: String, contacts: List<ContactName>, max: Int = 5): List<ContactMatch> {
        val all = rank(query, contacts)
        val top = all.firstOrNull()?.kind ?: return emptyList()
        return all.filter { it.kind == top }.take(max)
    }

    /**
     * Robot may go ahead without showing who (when texts or emails are set to "Just do it") only when exactly one
     * contact's name or nickname is exactly what was asked and it has exactly one place to send to. A prefix, word or
     * fuzzy match, several people, several numbers, or a number or address the model wrote itself (never checked
     * against the contacts) always show the card.
     */
    fun sure(matches: List<ContactMatch>, destinations: Int): Boolean =
        matches.size == 1 && matches[0].kind == NameMatch.EXACT && destinations == 1
}

/** One place a text or an email can go, as the card lists it ([label]: "Mobile", "Work", "XMPP"). */
data class Reach(val contactId: Long, val name: String, val address: String, val label: String?, val xmpp: Boolean = false)

object Recipients {
    /** At most this many choices on one card. */
    const val MAX_CHOICES = 6

    /**
     * Where a text can go for these contacts, in order: a contact's XMPP addresses first (Cheogram texts them directly;
     * [xmppOk]: the texting app is Cheogram), then its numbers, mobile first, each number once. All of them: the caller
     * shows [MAX_CHOICES] and says how many more there are.
     */
    fun forText(cards: List<ContactCard>, xmppOk: Boolean): List<Reach> = cards.flatMap { c ->
        val jids = if (xmppOk) c.xmpp.filter(Addresses::isJid).distinctBy { it.lowercase(Locale.ROOT) } else emptyList()
        jids.map { Reach(c.id, c.name, it, "XMPP", xmpp = true) } + mobileFirst(c.phones).map { Reach(c.id, c.name, it.number, it.label) }
    }

    fun forEmail(cards: List<ContactCard>): List<Reach> = cards.flatMap { c ->
        c.emails.filter(Addresses::isAddress).distinctBy { it.lowercase(Locale.ROOT) }.map { Reach(c.id, c.name, it, null) }
    }

    /** At most this many people are looked at for one name; more are counted ("and 3 more"). */
    const val MAX_PEOPLE = 20

    /** One place a text or an email can go, ready: the person, the kind ("Mobile"), what the card shows, the action. */
    data class Option(val name: String, val kind: String?, val shown: String, val action: Action)

    /**
     * The card for a text or an email to the people [matches] named, given every place they can be reached
     * ([options], in order; never empty). One place: that one, and the card shows it unless the name was surely
     * meant (ContactMatcher.sure). Several: the card lists them (at most [MAX_CHOICES], [hiddenPeople] and the rest
     * counted as "more"); [named] renames the waiting action after the one person they all belong to.
     */
    fun prepare(asked: Action, matches: List<ContactMatch>, options: List<Option>, hiddenPeople: Int, named: (String) -> Action): Prepared {
        if (options.size == 1) return Prepared.Ready(options[0].action, mustAsk = hiddenPeople > 0 || !ContactMatcher.sure(matches, 1))
        val oneName = options.map { it.name }.distinct().singleOrNull()
        val choices = options.take(MAX_CHOICES).map { o -> Choice(listOfNotNull(if (oneName == null) o.name else null, o.kind, o.shown).joinToString(" · "), o.action) }
        return Prepared.Ready(oneName?.let(named) ?: asked, choices = choices, more = options.size - choices.size + hiddenPeople)
    }

    /**
     * send_text found no one in the phone's contacts to text ([reason] says why, as the card and the model are told).
     * With Cheogram as the texting app (installed) the card offers "Choose in Cheogram" instead: Cheogram keeps its own
     * roster and only reads the phone's contacts, so the person may be there. It always asks. The SMS app has no such
     * list: not found.
     */
    fun notFound(asked: Action.SendText, reason: String, via: TextVia): Prepared =
        if (via == TextVia.CHEOGRAM) Prepared.Ready(asked.copy(target = TextTarget(null, "", reason, "Cheogram", TextVia.CHEOGRAM_PICK)), mustAsk = true)
        else Prepared.Refused(ActionStatus.NOT_FOUND, reason)

    /** Mobile numbers first (texts), then the rest in the contact's order; the same digits only once. */
    fun mobileFirst(phones: List<ContactPhone>): List<ContactPhone> =
        phones.distinctBy { PhoneNumbers.digits(it.number).takeLast(10) }.sortedBy { if (it.kind == PhoneKind.MOBILE) 0 else 1 }

    /**
     * lookup_contact's answer for the model: up to 3 contacts, each with its name, up to 3 numbers (with their type),
     * up to 2 email addresses and an XMPP address.
     */
    fun lookup(cards: List<ContactCard>, display: (String) -> String = { it }): List<Map<String, Any?>> = cards.take(3).map { c ->
        buildMap {
            put("name", c.name)
            val phones = mobileFirst(c.phones).take(3)
            if (phones.isNotEmpty()) put("numbers", phones.map { "${it.label.lowercase(Locale.ROOT)}: ${display(it.number)}" })
            val emails = c.emails.distinct().take(2)
            if (emails.isNotEmpty()) put("emails", emails)
            c.xmpp.firstOrNull()?.let { put("xmpp", it) }
        }
    }
}

object Addresses {
    private val ADDRESS = Regex("""^[^\s@<>()",;:\[\]]+@[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?)+$""")

    /** user@domain.tld: an email address. */
    fun isAddress(s: String): Boolean = ADDRESS.matches(s.trim())

    /**
     * user@domain.tld for Cheogram: also none of the characters RFC 7622 forbids in the local part (" & ' / : < > @),
     * nor '?' or '#', which would cut the address short in Cheogram's xmpp: link ("a?b@x.org" opens "a").
     */
    fun isJid(s: String): Boolean = isAddress(s) && s.trim().substringBeforeLast('@').none { it in "\"&'/:<>?#%" }

    /** Only digits and the usual separators, with 3 to 15 digits: a number to text, not a name. */
    fun isPhone(s: String): Boolean {
        val t = PhoneNumbers.normalize(s)
        return t.isNotEmpty() && Regex("""^\+?[\d\s().\-]+$""").matches(t) && PhoneNumbers.digits(t).length in 3..15
    }
}

object PhoneNumbers {
    const val DEFAULT_REGION = "CA"
    const val DEFAULT_GATEWAY = "cheogram.com"
    /** The North American Numbering Plan's regions: their 10-digit numbers are +1 numbers. */
    private val NANP = setOf("US", "CA", "AG", "AI", "AS", "BB", "BM", "BS", "DM", "DO", "GD", "GU", "JM", "KN", "KY", "LC",
        "MP", "MS", "PR", "SX", "TC", "TT", "VC", "VG", "VI")

    fun digits(s: String): String = s.filter(Char::isDigit)

    /**
     * A number as dialled: bidi marks and odd spaces out, and no extension or second number ("x23", "ext. 23", ";23",
     * ",23", "/ 0102"): only the number itself is texted.
     */
    fun normalize(raw: String): String = raw.replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069]"), "")
        .replace(Regex("[\u00A0\u2007\u202F]"), " ")
        .replace(Regex("""(?i)\s*(?:ext\.?|x|#|;|,|/|p|w).*$"""), "").trim()

    fun isE164(s: String): Boolean = Regex("""^\+[1-9]\d{6,14}$""").matches(s)

    /** A two-letter region (ISO 3166), upper case; anything else is [DEFAULT_REGION]. */
    fun region(s: String?): String = s?.trim()?.uppercase(Locale.ROOT)?.takeIf { Regex("^[A-Z]{2}$").matches(it) } ?: DEFAULT_REGION

    /** A plain domain name ("cheogram.com"), lower case; anything else is [DEFAULT_GATEWAY]. */
    fun gateway(s: String?): String = s?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { Regex("""^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$""").matches(it) } ?: DEFAULT_GATEWAY

    /**
     * [raw] in E.164 ("+15145550101"). [platform] is PhoneNumberUtils.formatNumberToE164(number, region) in the app;
     * its answer is used only if it really is E.164. When it has none (it refuses numbers its tables call invalid), a
     * number written with its country code, or a 10-digit number in a North American region, is still read; anything
     * else is null (the card says the number couldn't be read rather than guess).
     */
    fun e164(raw: String, region: String, platform: (String, String) -> String?): String? {
        val r = region(region)
        val t = normalize(raw)
        if (!Addresses.isPhone(t)) return null
        val plain = if (t.startsWith("00")) "+" + t.substring(2) else t
        platform(plain, r)?.takeIf(::isE164)?.let { return it }
        val d = digits(plain)
        return when {
            // +1 is North America: exactly 10 digits after it ("+1 514 555 0101 22" is not a number).
            plain.startsWith("+1") -> "+$d".takeIf { d.length == 11 && d[1] in '2'..'9' }
            plain.startsWith("+") -> "+$d".takeIf(::isE164)
            r in NANP && d.length == 10 && d[0] in '2'..'9' -> "+1$d"
            r in NANP && d.length == 11 && d[0] == '1' && d[1] in '2'..'9' -> "+$d"
            else -> null
        }
    }

    /** A number's address at the SMS gateway, as Cheogram texts it: "+15145550101@cheogram.com" (JMP's gateway). */
    fun jid(e164: String, gateway: String): String? = if (isE164(e164)) "$e164@${gateway(gateway)}" else null
}
