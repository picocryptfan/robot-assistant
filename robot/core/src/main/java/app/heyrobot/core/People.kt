package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. Who "Mom" is: the contacts' names (the phone's and Robot's People, PeopleStore.kt) ranked against the name the
// model gave, the numbers and addresses a text or an email can go to (mobile first for texts), phone numbers in E.164
// and the address of a number at the SMS gateway. The app process reads ContactsContract and calls PhoneNumberUtils;
// every choice is made here, so it is tested.

enum class PhoneKind { MOBILE, HOME, WORK, OTHER }

/** [label]: the type as the phone names it ("Mobile", or a custom label). */
data class ContactPhone(val number: String, val kind: PhoneKind, val label: String)

/**
 * Where a contact comes from, in its id: "s:<ContactsContract id>" for the phone's contacts, "r:<id>" for Robot's People,
 * so the two can never be taken for each other.
 */
object ContactIds {
    fun system(id: Long): String = "s:$id"
    fun people(id: String): String = "r:$id"
    /** The phone's contact id, or null for anything else. */
    fun systemId(ref: String?): Long? = ref?.takeIf { it.startsWith("s:") }?.substring(2)?.toLongOrNull()
    /** The People id, or null for anything else. */
    fun peopleId(ref: String?): String? = ref?.takeIf { it.startsWith("r:") }?.substring(2)?.takeIf { it.isNotEmpty() }
}

/**
 * A contact's name and nicknames, enough to rank it. [aliases]: names you told Robot to remember for this person
 * (People): one said exactly beats every other match. [learned]: the name came from a Cheogram notification and you
 * haven't used or edited it yet. [lookupKey]: the phone's lookup key, which a People entry that only adds names or a
 * choice to a phone contact refers to.
 */
data class ContactName(
    val id: String,
    val name: String,
    val nicknames: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val learned: Boolean = false,
    val lookupKey: String? = null,
)

/** An XMPP address, and the Cheogram account it was seen in ([account] null: Cheogram picks the account). */
data class XmppAddress(val jid: String, val account: String? = null)

/**
 * One contact's ways to reach it. [xmpp]: ContactsContract Im rows with the Jabber protocol, or People's XMPP addresses
 * with their account. [preferredText], [preferredEmail]: the address you chose for this person on a card ("Always use
 * this"), offered alone when it is still among the others.
 */
data class ContactCard(
    val id: String,
    val name: String,
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<String> = emptyList(),
    val xmpp: List<XmppAddress> = emptyList(),
    val preferredText: String? = null,
    val preferredEmail: String? = null,
    val learned: Boolean = false,
)

/** How a contact's name matched, best first. ALIAS: a name you told Robot to remember for that person, said exactly. */
enum class NameMatch { ALIAS, EXACT, PREFIX, WORD, FUZZY }

/** [learned]: the person's name was seen in a Cheogram notification (ContactName.learned). */
data class ContactMatch(val id: String, val name: String, val kind: NameMatch, val learned: Boolean = false)

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

    /**
     * Every contact that matches by name, nickname or alias, best kind first, then shorter names, then by name. An alias
     * said exactly is [NameMatch.ALIAS]; otherwise an alias counts like a nickname.
     */
    fun rank(query: String, contacts: List<ContactName>): List<ContactMatch> = contacts
        .mapNotNull { c ->
            val k = if (c.aliases.any { kind(query, it) == NameMatch.EXACT }) NameMatch.ALIAS
            else (listOf(c.name) + c.nicknames + c.aliases).mapNotNull { kind(query, it) }.minOrNull()
            k?.let { ContactMatch(c.id, c.name, it, c.learned) }
        }
        .sortedWith(compareBy<ContactMatch> { it.kind.ordinal }.thenBy { it.name.length }.thenBy { it.name.lowercase(Locale.ROOT) })

    /**
     * The contacts a request means: only the best kind of match there is (a remembered alias wins over every name, one
     * exact name over every prefix, and a fuzzy match counts only when nothing better exists), at most [max]. Several
     * left: the card asks which. A name learned from a Cheogram notification was chosen by whoever sent the message, so
     * it never stands beside a confirmed person who matches as well: it is left out then.
     */
    fun best(query: String, contacts: List<ContactName>, max: Int = 5): List<ContactMatch> {
        val all = rank(query, contacts)
        val top = all.firstOrNull()?.kind ?: return emptyList()
        val same = all.filter { it.kind == top }
        return same.filter { !it.learned }.ifEmpty { same }.take(max)
    }

    /**
     * Robot may go ahead without showing who (when texts or emails are set to "Just do it") only when exactly one
     * contact's name, nickname or remembered alias is exactly what was asked and it has exactly one place to send to (a
     * remembered choice counts as one). A prefix, word or fuzzy match, several people, several numbers, a name learned
     * from a notification, or a number or address the model wrote itself (never checked against the contacts) always
     * show the card.
     */
    fun sure(matches: List<ContactMatch>, destinations: Int): Boolean =
        matches.size == 1 && matches[0].kind <= NameMatch.EXACT && !matches[0].learned && destinations == 1
}

/**
 * One place a text or an email can go, as the card lists it ([label]: "Mobile", "Work", "XMPP"). [address]: as stored
 * with the contact. [account]: the Cheogram account an XMPP address was seen in. [learned]: the contact's name was seen
 * in a Cheogram notification (the card always shows, and says so).
 */
data class Reach(
    val contactId: String,
    val name: String,
    val address: String,
    val label: String?,
    val xmpp: Boolean = false,
    val account: String? = null,
    val learned: Boolean = false,
)

object Recipients {
    /** At most this many choices on one card. */
    const val MAX_CHOICES = 6

    /**
     * Where a text can go for these contacts, in order: a contact's XMPP addresses first (Cheogram texts them directly;
     * [xmppOk]: the texting app is Cheogram), then its numbers, mobile first, each number once. All of them: the caller
     * shows [MAX_CHOICES] and says how many more there are. A contact with a remembered choice still among them
     * (ContactCard.preferredText) offers that one only.
     */
    fun forText(cards: List<ContactCard>, xmppOk: Boolean): List<Reach> = cards.flatMap { c ->
        val jids = if (xmppOk) c.xmpp.filter { Addresses.isJid(it.jid) }.distinctBy { it.jid.lowercase(Locale.ROOT) } else emptyList()
        val all = jids.map { Reach(c.id, c.name, it.jid, "XMPP", xmpp = true, account = it.account, learned = c.learned) } +
            mobileFirst(c.phones).map { Reach(c.id, c.name, it.number, it.label, learned = c.learned) }
        Preferred.only(all, c.preferredText)
    }

    fun forEmail(cards: List<ContactCard>): List<Reach> = cards.flatMap { c ->
        val all = c.emails.filter(Addresses::isAddress).distinctBy { it.lowercase(Locale.ROOT) }.map { Reach(c.id, c.name, it, null, learned = c.learned) }
        Preferred.only(all, c.preferredEmail)
    }

    /** At most this many people are looked at for one name; more are counted ("and 3 more"). */
    const val MAX_PEOPLE = 20

    /**
     * One place a text or an email can go, ready: the person, the kind ("Mobile"), what the card shows, the action.
     * [learned]: the person's name was seen in a Cheogram notification (always a card).
     */
    data class Option(val name: String, val kind: String?, val shown: String, val action: Action, val learned: Boolean = false)

    /**
     * The card for a text or an email to the people [matches] named, given every place they can be reached
     * ([options], in order; never empty). One place: that one, and the card shows it unless the name was surely
     * meant (ContactMatcher.sure) by a person you confirmed. Several: the card lists them (at most [MAX_CHOICES],
     * [hiddenPeople] and the rest counted as "more"); [named] renames the waiting action after the one person they all
     * belong to.
     */
    fun prepare(asked: Action, matches: List<ContactMatch>, options: List<Option>, hiddenPeople: Int, named: (String) -> Action): Prepared {
        if (options.size == 1) {
            return Prepared.Ready(options[0].action, mustAsk = hiddenPeople > 0 || options[0].learned || !ContactMatcher.sure(matches, 1))
        }
        val oneName = options.map { it.name }.distinct().singleOrNull()
        val choices = options.take(MAX_CHOICES).map { o ->
            Choice(listOfNotNull(if (oneName == null) o.name else null, o.kind, o.shown).joinToString(" · "), o.action)
        }
        return Prepared.Ready(oneName?.let(named) ?: asked, choices = choices, more = options.size - choices.size + hiddenPeople)
    }

    /**
     * send_text found no one in the contacts to text ([reason] says why, as the card and the model are told). With
     * Cheogram as the texting app (installed) the card offers "Choose in Cheogram" instead: Cheogram keeps its own roster
     * and only reads the phone's contacts, so the person may be there. It always asks. The SMS app has no such list: not
     * found.
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
            c.xmpp.firstOrNull()?.let { put("xmpp", it.jid) }
            if (c.learned) put("note", "name seen in a Cheogram notification, not confirmed")
        }
    }
}

/**
 * A remembered choice ("Always use this for mom"): which of a contact's places it names. An XMPP address names itself
 * (case aside); a number names the same number (its last 10 digits), and a number's address at the SMS gateway
 * ("+15145550101@cheogram.com") names that number too.
 */
object Preferred {
    private fun key(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return null
        if (t.contains('@')) {
            val local = t.substringBeforeLast('@')
            return if (Regex("""^\+\d{7,15}$""").matches(local)) "n:" + PhoneNumbers.digits(local).takeLast(10) else "x:" + t.lowercase(Locale.ROOT)
        }
        return PhoneNumbers.digits(PhoneNumbers.normalize(t)).takeIf { it.length >= 3 }?.let { "n:" + it.takeLast(10) }
    }

    fun same(address: String, pref: String): Boolean = key(address)?.let { it == key(pref) } == true

    /** Only the place [pref] names, if it is among [all]; otherwise all of them (the remembered one is gone). */
    fun only(all: List<Reach>, pref: String?): List<Reach> {
        if (pref.isNullOrBlank()) return all
        return all.firstOrNull { same(it.address, pref) }?.let { listOf(it) } ?: all
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

    /** A two-letter region (ISO 3166), upper case, or null. */
    fun region(s: String?): String? = s?.trim()?.uppercase(Locale.ROOT)?.takeIf { Regex("^[A-Z]{2}$").matches(it) }

    /**
     * The phone's own region, for numbers written without a country code when none is set: the SIM's country, else
     * the mobile network's, else the language setting's; null if none says.
     */
    fun deviceRegion(sim: String?, network: String?, locale: String?): String? = region(sim) ?: region(network) ?: region(locale)

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
        // No region known: only numbers written with their country code are read.
        val r = region(region) ?: ""
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
