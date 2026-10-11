package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID

// Pure (java.io, org.json). Robot's People (docs/people.md): the people you text and email who aren't in the phone's
// address book, or whom you call something else ("mom", "maman"). One JSON file in app-private storage
// (files/people/people.json), written atomically. Three writers share it (the main app's Settings › People, the
// assistant's cards and Cheogram pick, and the notification listener), so every change is a read, change and write
// under a file lock both app processes take. People are personal content: never in a log, never sent to Claude unless
// you include them, excluded from Android backup like the rest of Robot's files.

/** Where a person came from (shown in Settings › People). */
enum class PersonSource(val title: String) {
    MANUAL("Added by you"),
    VCARD("From a contacts file"),
    CHEOGRAM_PICK("Picked in Cheogram"),
    PHONE_PICKER("From the phone's contacts"),
    /** Seen in a Cheogram message notification ("Learn names from Cheogram notifications"). */
    NOTIFICATION("Seen in Cheogram"),
    SHARE("Shared to Robot"),
    /** "Always use this for …" on a card, for one of the phone's contacts ([Person.systemLookupKey]). */
    REMEMBERED("Remembered from a card"),
}

/**
 * One person. [aliases]: what you call them ("mom", "maman"), each said exactly beating any other match.
 * [preferredText], [preferredEmail]: the place chosen on a card ("Always use this"). [confirmed]: false only for
 * someone learned from a notification you haven't used or edited yet: whoever sent the message chose that name, so a
 * text or an email to them always shows the card first. [systemLookupKey]: set when this entry only adds names or a
 * choice to one of the phone's contacts (its ContactsContract lookup key); its places are kept too, for when that
 * contact can't be read.
 */
data class Person(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val phones: List<ContactPhone> = emptyList(),
    val emails: List<String> = emptyList(),
    val xmpp: List<XmppAddress> = emptyList(),
    val preferredText: String? = null,
    val preferredEmail: String? = null,
    val source: PersonSource = PersonSource.MANUAL,
    val confirmed: Boolean = true,
    val systemLookupKey: String? = null,
    val updatedAt: Long = 0,
) {
    /** Something to text or email. */
    val reachable: Boolean get() = phones.isNotEmpty() || emails.isNotEmpty() || xmpp.isNotEmpty()
}

object PeopleCodec {
    const val VERSION = 1

    fun toJson(people: List<Person>): String = JSONObject().put("v", VERSION).put("people", JSONArray().apply {
        for (p in people) put(JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            if (p.aliases.isNotEmpty()) put("aliases", JSONArray(p.aliases))
            if (p.phones.isNotEmpty()) put("phones", JSONArray().apply {
                p.phones.forEach { put(JSONObject().put("number", it.number).put("kind", it.kind.name).put("label", it.label)) }
            })
            if (p.emails.isNotEmpty()) put("emails", JSONArray(p.emails))
            if (p.xmpp.isNotEmpty()) put("xmpp", JSONArray().apply {
                p.xmpp.forEach { x -> put(JSONObject().put("jid", x.jid).apply { x.account?.let { put("account", it) } }) }
            })
            p.preferredText?.let { put("preferredText", it) }
            p.preferredEmail?.let { put("preferredEmail", it) }
            put("source", p.source.name)
            put("confirmed", p.confirmed)
            p.systemLookupKey?.let { put("systemLookupKey", it) }
            put("updatedAt", p.updatedAt)
        })
    }).toString()

    /** Everyone in [text]; a damaged entry is dropped, a damaged file reads as nobody (never a crash). */
    fun fromJson(text: String?): List<Person> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = try { JSONObject(text).optJSONArray("people") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::person) }.distinctBy { it.id }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    private fun person(o: JSONObject): Person? {
        val id = o.optString("id").takeIf { PeopleStore.validId(it) } ?: return null
        val phones = o.optJSONArray("phones")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val x = a.optJSONObject(i) ?: return@mapNotNull null
                val n = x.optString("number").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = enumOr(x.optString("kind"), PhoneKind.OTHER)
                ContactPhone(n, kind, x.optString("label").ifBlank { People.label(kind) })
            }
        }.orEmpty()
        val xmpp = o.optJSONArray("xmpp")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val x = a.optJSONObject(i) ?: return@mapNotNull null
                val jid = x.optString("jid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                XmppAddress(jid, x.optString("account").takeIf { it.isNotBlank() })
            }
        }.orEmpty()
        return People.clean(Person(
            id = id,
            name = o.optString("name"),
            aliases = strings(o.optJSONArray("aliases")),
            phones = phones,
            emails = strings(o.optJSONArray("emails")),
            xmpp = xmpp,
            preferredText = o.optString("preferredText").takeIf { it.isNotBlank() },
            preferredEmail = o.optString("preferredEmail").takeIf { it.isNotBlank() },
            source = enumOr(o.optString("source"), PersonSource.MANUAL),
            confirmed = o.optBoolean("confirmed", true),
            systemLookupKey = o.optString("systemLookupKey").takeIf { it.isNotBlank() },
            updatedAt = o.optLong("updatedAt"),
        ))
    }
}

/**
 * files/people/people.json. Reads need no lock (the file is replaced atomically); every change goes through [update],
 * which holds a lock on files/people/.lock (between the app's processes) and a lock in this process (between threads).
 */
class PeopleStore(private val dir: File) {
    private val file get() = File(dir, "people.json")

    fun list(): List<Person> = PeopleCodec.fromJson(readOrNull(file))

    fun get(id: String): Person? = list().firstOrNull { it.id == id }

    /**
     * Reads, changes and writes everyone under the lock. [change] gets the list as it is now; returning the same list
     * (the same object) writes nothing. Null if it couldn't be written.
     */
    fun update(change: (List<Person>) -> List<Person>): List<Person>? = try {
        locked {
            val now = list()
            val next = change(now)
            if (next !== now) writeAtomic(file, PeopleCodec.toJson(next.map(People::clean).take(People.MAX_PEOPLE)))
            next
        }
    } catch (_: Exception) {
        null
    }

    /** Adds [p], or replaces the person with its id. */
    fun save(p: Person): Boolean = update { PeopleEdit.upsert(it, p) } != null

    fun delete(id: String): Boolean = update { list -> list.filter { it.id != id } } != null

    fun deleteAll(): Boolean = update { emptyList() } != null

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        dir.mkdirs()
        FileChannel.open(File(dir, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }

    companion object {
        private val LOCK = Any()
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
        fun validId(id: String?): Boolean = id != null && ID.matches(id)
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Limits and tidying shared by everything that writes a person. */
object People {
    /** Everyone at most (an imported file is cut there). */
    const val MAX_PEOPLE = 5_000
    const val MAX_NAME = 100
    const val MAX_EACH = 20

    fun label(kind: PhoneKind): String = when (kind) {
        PhoneKind.MOBILE -> "Mobile"
        PhoneKind.HOME -> "Home"
        PhoneKind.WORK -> "Work"
        PhoneKind.OTHER -> "Other"
    }

    private fun line(s: String, max: Int): String = ToolCalls.safeTake(s.replace(Regex("\\s+"), " ").trim(), max)

    /** Names on one line and not too long, each place once, at most [MAX_EACH] of each. */
    fun clean(p: Person): Person {
        val name = line(p.name, MAX_NAME)
        return p.copy(
            name = name,
            aliases = p.aliases.map { line(it, MAX_NAME) }.filter { it.isNotEmpty() && !sameName(it, name) }
                .distinctBy { ContactMatcher.words(it) }.take(MAX_EACH),
            phones = p.phones.map { it.copy(number = line(it.number, 40), label = line(it.label, 40)) }.filter { it.number.isNotEmpty() }
                .distinctBy { PhoneNumbers.digits(it.number).takeLast(10).ifEmpty { it.number } }.take(MAX_EACH),
            emails = p.emails.map { line(it, 254) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_EACH),
            xmpp = p.xmpp.map { XmppAddress(line(it.jid, 254), it.account?.let { a -> line(a, 254) }?.takeIf { a -> a.isNotEmpty() }) }
                .filter { it.jid.isNotEmpty() }.distinctBy { it.jid.lowercase(Locale.ROOT) }.take(MAX_EACH),
        )
    }

    /** The same name, case, accents and punctuation aside ("Maman" and "maman"). */
    fun sameName(a: String, b: String): Boolean = ContactMatcher.words(a).let { it.isNotEmpty() && it == ContactMatcher.words(b) }

    /** One line for a list: the first number, address or XMPP address. */
    fun summary(p: Person): String = listOfNotNull(p.phones.firstOrNull()?.number, p.emails.firstOrNull(), p.xmpp.firstOrNull()?.jid)
        .firstOrNull().orEmpty()

    /** Settings › People's search: name, aliases and places, case and accents aside. */
    fun matches(p: Person, query: String): Boolean {
        val q = ContactMatcher.words(query)
        if (q.isEmpty()) return true
        val hay = ContactMatcher.words((listOf(p.name) + p.aliases + p.emails + p.xmpp.map { it.jid } + p.phones.map { it.number }).joinToString(" "))
        val digits = PhoneNumbers.digits(query)
        return q.all { w -> hay.any { it.startsWith(w) } } || (digits.length >= 3 && p.phones.any { PhoneNumbers.digits(it.number).contains(digits) })
    }

    /** Sorted for Settings › People: by name, case and accents aside. */
    fun sorted(people: List<Person>): List<Person> = people.sortedBy { ContactMatcher.words(it.name).joinToString(" ") }
}

/** Pure changes to the list of people (PeopleStore.update applies them under its lock). */
object PeopleEdit {
    fun upsert(list: List<Person>, p: Person): List<Person> =
        if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p

    /** Using or editing a learned person confirms them. Unchanged (the same list) if there was nothing to confirm. */
    fun confirm(list: List<Person>, id: String?, now: Long): List<Person> {
        val p = list.firstOrNull { it.id == id } ?: return list
        if (p.confirmed) return list
        return upsert(list, p.copy(confirmed = true, updatedAt = now))
    }

    /** [p] with [alias] (as said: "mom") unless it is already their name or an alias. */
    private fun withAlias(p: Person, alias: String?): Person {
        val a = alias?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { ContactMatcher.words(it).isNotEmpty() } ?: return p
        if (People.sameName(a, p.name) || p.aliases.any { People.sameName(it, a) }) return p
        return p.copy(aliases = p.aliases + a)
    }

    /** [alias] taken from everyone but [keep]: a name you gave one person means only them from now on. */
    private fun takeAlias(list: List<Person>, alias: String?, keep: String): List<Person> {
        if (alias.isNullOrBlank()) return list
        return list.map { p -> if (p.id != keep && p.aliases.any { People.sameName(it, alias) }) p.copy(aliases = p.aliases.filter { !People.sameName(it, alias) }) else p }
    }

    private fun sameJid(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Someone picked in Cheogram's list, remembered: the person with that XMPP address gets [spoken] (what you said:
     * "mom") as an alias and the account it came from, and is confirmed; or a new person, named as Cheogram names them.
     * Returns the list and the person.
     */
    fun rememberPick(list: List<Person>, spoken: String?, pick: CheogramPick, now: Long, newId: () -> String = PeopleStore::newId): Pair<List<Person>, Person> {
        val old = list.firstOrNull { p -> p.xmpp.any { sameJid(it.jid, pick.jid) } }
        val p = if (old == null) {
            Person(newId(), pick.name, xmpp = listOf(XmppAddress(pick.jid, pick.account)), source = PersonSource.CHEOGRAM_PICK, confirmed = true, updatedAt = now)
        } else {
            // A learned name was the sender's choice: Cheogram's name (your roster's, or your contacts') replaces it.
            old.copy(
                name = if (old.confirmed) old.name else pick.name,
                xmpp = old.xmpp.map { if (sameJid(it.jid, pick.jid) && it.account == null) it.copy(account = pick.account) else it },
                confirmed = true, updatedAt = now,
            )
        }
        val named = People.clean(withAlias(p, spoken))
        return upsert(takeAlias(list, spoken, named.id), named) to named
    }

    /** What an import did: people added, people who got something new, cards left out (nothing to reach them by). */
    data class Merged(val people: List<Person>, val added: Int, val updated: Int, val skipped: Int)

    /** Keys two entries share when they are the same person: a number (last 10 digits), an email or an XMPP address. */
    private fun keys(p: Person): Set<String> = buildSet {
        p.phones.forEach { PhoneNumbers.digits(it.number).takeIf { d -> d.length >= 7 }?.let { d -> add("n:" + d.takeLast(10)) } }
        p.emails.forEach { add("e:" + it.trim().lowercase(Locale.ROOT)) }
        p.xmpp.forEach { x ->
            add("x:" + x.jid.trim().lowercase(Locale.ROOT))
            // A number's address at the SMS gateway is that number.
            val local = x.jid.substringBeforeLast('@')
            if (Regex("""^\+\d{7,15}$""").matches(local)) add("n:" + PhoneNumbers.digits(local).takeLast(10))
        }
    }

    /**
     * Imported people ([incoming]: a contacts file, the phone's contact picker) merged in: one who shares a number, an
     * email or an XMPP address with someone already there adds what's new to them (their name stays, unless they were
     * only learned from a notification); anyone else is added. Entries with nothing to reach them by are skipped.
     */
    fun merge(list: List<Person>, incoming: List<Person>, now: Long): Merged {
        var out = list
        var added = 0
        var updated = 0
        var skipped = 0
        for (raw in incoming) {
            val inc = People.clean(raw)
            if (!inc.reachable || inc.name.isEmpty()) { skipped++; continue }
            val k = keys(inc)
            val old = out.firstOrNull { p -> keys(p).any { it in k } }
            if (old == null) {
                if (out.size >= People.MAX_PEOPLE) { skipped++; continue }
                out = out + inc.copy(updatedAt = now)
                added++
                continue
            }
            val merged = People.clean(old.copy(
                name = if (old.confirmed) old.name else inc.name,
                aliases = old.aliases + inc.aliases + (if (old.confirmed && !People.sameName(old.name, inc.name)) listOf(inc.name) else emptyList()),
                phones = old.phones + inc.phones,
                emails = old.emails + inc.emails,
                xmpp = old.xmpp + inc.xmpp.filter { x -> old.xmpp.none { sameJid(it.jid, x.jid) } },
                confirmed = true,
            ))
            if (merged != old) {
                out = upsert(out, merged.copy(updatedAt = now))
                updated++
            }
        }
        return Merged(out, added, updated, skipped)
    }
}

/**
 * The phone's contacts and Robot's People as one address book, for ranking a name and finding where a text or email
 * goes. A People entry that only adds to a phone contact ([Person.systemLookupKey]) joins that contact (its aliases and
 * its choices) when the contact is there; otherwise it stands on its own, like everyone else in People.
 */
class AddressBook(system: List<ContactName>, val people: List<Person>) {
    private val byKey: Map<String, Person> = people.filter { it.systemLookupKey != null }.associateBy { it.systemLookupKey!! }
    /** People entries joined to a phone contact here, by that contact's id. */
    private val linked: Map<String, Person> = system.mapNotNull { c -> c.lookupKey?.let(byKey::get)?.let { c.id to it } }.toMap()
    private val joined: Set<String> = linked.values.map { it.id }.toSet()
    private val own: Map<String, Person> = people.filter { it.id !in joined }.associateBy { ContactIds.people(it.id) }

    /** Every name to rank: the phone's contacts (with the aliases a People entry adds) and People of their own. */
    val names: List<ContactName> = system.map { c -> linked[c.id]?.let { p -> c.copy(aliases = c.aliases + p.aliases) } ?: c } +
        own.map { (ref, p) -> ContactName(ref, p.name, aliases = p.aliases, learned = !p.confirmed) }

    fun best(query: String, max: Int = 5): List<ContactMatch> = ContactMatcher.best(query, names, max)

    /** The People entry behind [ref]: a person of their own ("r:…"), or the one joined to a phone contact ("s:…"). */
    fun person(ref: String): Person? = own[ref] ?: linked[ref]

    /**
     * The cards of [matches], in their order: a phone contact's as [system] reads them (one call for all of them), with
     * the choices its People entry remembers; a person's from People.
     */
    fun cards(matches: List<ContactMatch>, system: (List<ContactMatch>) -> List<ContactCard>): List<ContactCard> {
        val sys = matches.filter { ContactIds.systemId(it.id) != null }
        val read = if (sys.isEmpty()) emptyMap() else system(sys).associateBy { it.id }
        return matches.mapNotNull { m ->
            own[m.id]?.let(::card) ?: read[m.id]?.let { c ->
                linked[m.id]?.let { p -> c.copy(preferredText = p.preferredText, preferredEmail = p.preferredEmail) } ?: c
            }
        }
    }

    companion object {
        fun card(p: Person): ContactCard = ContactCard(ContactIds.people(p.id), p.name, p.phones, p.emails, p.xmpp, p.preferredText, p.preferredEmail,
            learned = !p.confirmed)
    }
}
