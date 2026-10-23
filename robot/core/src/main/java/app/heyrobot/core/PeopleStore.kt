// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

// Pure (java.io, org.json). Robot's People (docs/people.md): the people you text and email who aren't in the phone's
// address book, or whom you call something else ("mom", "maman"). One JSON file in app-private storage
// (files/people/people.json), written atomically. Three writers share it (the main app's Settings › People, the
// assistant's cards and Cheogram pick, and the notification listener), so every change is a read, change and write
// under a file lock both app processes take. People are personal content: never in a log, never sent to a helper unless
// you include them, excluded from Android backup like the rest of Robot's files.

/** Where a person came from (shown in Settings › People). */
enum class PersonSource(private val msg: app.heyrobot.core.i18n.Msg) {
    MANUAL(InfoStrings.sourceManual),
    VCARD(InfoStrings.sourceVcard),
    CHEOGRAM_PICK(InfoStrings.sourceCheogramPick),
    PHONE_PICKER(InfoStrings.sourcePhonePicker),
    /** Seen in a Cheogram message notification ("Learn names from Cheogram notifications"). */
    NOTIFICATION(InfoStrings.sourceNotification),
    SHARE(InfoStrings.sourceShare),
    /** "Always use this for …" on a card, for one of the phone's contacts ([Person.systemLookupKey]). */
    REMEMBERED(InfoStrings.sourceRemembered);

    /** Where they came from, in words without a language yet (1.1: Settings › People shows it with `str()`). */
    val titleText: Phrase get() = msg()

    val title: String get() = titleText.en()
}

/**
 * One person. [aliases]: what you told Robot you call them ("mom", "maman": typed in Also called, "Remember as" after a
 * pick, "Always use this for"), each said exactly beating any other match of someone who can be reached that way.
 * [nicknames]: other names they go by (from a contacts file, or a merge), matched like the phone's nicknames.
 * [preferredText], [preferredEmail]: the place chosen on a card ("Always use this"). [confirmed]: false only for
 * someone learned from a notification you haven't kept, edited or remembered yet: whoever sent the message chose that
 * name, so it never displaces anyone confirmed and a text or an email to them always shows the card first. [systemLookupKey]: set when this entry only adds names or a
 * choice to one of the phone's contacts (its ContactsContract lookup key); its places are kept too, for when that
 * contact can't be read.
 */
data class Person(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val nicknames: List<String> = emptyList(),
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

/**
 * What files/people/people.json holds: everyone, and [ignored], the XMPP addresses (lower case) never to learn from a
 * notification again because you removed the person learned from them.
 */
data class PeopleFile(val people: List<Person> = emptyList(), val ignored: Set<String> = emptySet())

object PeopleCodec {
    const val VERSION = 1

    fun toJson(people: List<Person>, ignored: Set<String> = emptySet()): String = JSONObject().put("v", VERSION).apply {
        if (ignored.isNotEmpty()) put("ignored", JSONArray(ignored.sorted()))
    }.put("people", JSONArray().apply {
        for (p in people) put(JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            if (p.aliases.isNotEmpty()) put("aliases", JSONArray(p.aliases))
            if (p.nicknames.isNotEmpty()) put("nicknames", JSONArray(p.nicknames))
            if (p.phones.isNotEmpty()) put("phones", JSONArray().apply {
                p.phones.forEach { put(JSONObject().put("number", it.number).put("kind", it.kind.name).put("label", it.label)) }
            })
            if (p.emails.isNotEmpty()) put("emails", JSONArray(p.emails))
            if (p.xmpp.isNotEmpty()) put("xmpp", JSONArray().apply {
                p.xmpp.forEach { x -> put(JSONObject().put("jid", x.jid).apply { x.account?.let { put("account", it) }; if (x.picked) put("picked", true) }) }
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
    fun fromJson(text: String?): List<Person> = read(text)?.people.orEmpty()

    /**
     * The whole file, or null when [text] isn't a People file at all (damaged: cut short, or something else). No text is
     * an empty file. A damaged entry inside a good file is dropped.
     */
    fun read(text: String?): PeopleFile? {
        if (text.isNullOrBlank()) return PeopleFile()
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        val arr = o.optJSONArray("people") ?: return null
        val people = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::person) }.distinctBy { it.id }
        return PeopleFile(people, strings(o.optJSONArray("ignored")).map { it.trim().lowercase(Locale.ROOT) }.toSet())
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
                XmppAddress(jid, x.optString("account").takeIf { it.isNotBlank() }, x.optBoolean("picked", false))
            }
        }.orEmpty()
        return People.clean(Person(
            id = id,
            name = o.optString("name"),
            aliases = strings(o.optJSONArray("aliases")),
            nicknames = strings(o.optJSONArray("nicknames")),
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
 * files/people/people.json. Reads need no lock (the file is replaced atomically); every change goes through [edit],
 * which holds a lock on files/people/.lock (between the app's processes) and a lock in this process (between threads).
 * A file that is there but can't be read as a People file (damaged, or unreadable right now) is never written over:
 * changes are refused, and readers see nobody, until [reset] (Settings › People's "Start over") keeps it aside as
 * people.json.bad.
 */
class PeopleStore(private val dir: File) {
    private val file get() = File(dir, "people.json")

    /**
     * Everyone, as the file holds them now (nobody when it is [damaged]). Kept in this process until the file changes (a
     * write replaces it: a new file, time and size), so a text doesn't parse thousands of people again.
     */
    fun list(): List<Person> = cached()?.people.orEmpty()

    /** The addresses never to learn again. */
    fun ignored(): Set<String> = cached()?.ignored.orEmpty()

    /** The file is there but isn't a People file Robot can read: nothing is written to it until [reset]. */
    fun damaged(): Boolean = file.exists() && read() == null

    private fun cached(): PeopleFile? {
        val f = file
        val stamp = stamp(f) ?: return read()
        synchronized(CACHE) { CACHE[f.path]?.let { (s, l) -> if (s == stamp) return l } }
        val l = read() ?: return null
        if (stamp(f) == stamp) synchronized(CACHE) { CACHE[f.path] = stamp to l }
        return l
    }

    /** The file as it is now; an empty one when there is none; null when it can't be read or isn't a People file. */
    private fun read(): PeopleFile? {
        val f = file
        if (!f.exists()) return PeopleFile()
        val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { return null }
        return PeopleCodec.read(text)
    }

    /** The file's identity (its inode where the system has one), time and size; null when it can't be told. */
    private fun stamp(f: File): String? = try {
        val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        a.fileKey()?.let { "$it:${a.lastModifiedTime().toMillis()}:${a.size()}" }
    } catch (_: Exception) { null }

    fun get(id: String): Person? = list().firstOrNull { it.id == id }

    /**
     * Reads, changes and writes the file under the lock. [change] gets it as it is now; returning the same object writes
     * nothing. Null if it couldn't be read (damaged: nothing is written) or written.
     */
    fun edit(change: (PeopleFile) -> PeopleFile): PeopleFile? = try {
        locked {
            // Under the lock always from the file itself.
            val now = read() ?: return@locked null
            val next = change(now)
            if (next !== now) writeAtomic(file, PeopleCodec.toJson(next.people.map(People::clean).take(People.MAX_PEOPLE), next.ignored))
            next
        }
    } catch (_: Exception) {
        null
    }

    /** [edit] of everyone: returning the same list (the same object) writes nothing. Null if it couldn't be written. */
    fun update(change: (List<Person>) -> List<Person>): List<Person>? =
        edit { f -> change(f.people).let { if (it === f.people) f else f.copy(people = it) } }?.people

    /** A sender seen in a Cheogram notification ([PeopleEdit.learn]), unless you removed someone with that address. */
    fun learn(s: CheogramSender, now: Long): Boolean = edit { f ->
        if (s.jid.trim().lowercase(Locale.ROOT) in f.ignored) f
        else PeopleEdit.learn(f.people, s.name, s.jid, s.account, now).let { if (it === f.people) f else f.copy(people = it) }
    } != null

    /**
     * Removes [id]; with [ignore] (Remove on someone learned from a notification) their XMPP addresses are never learned
     * again, so the next message doesn't bring them back.
     */
    fun remove(id: String, ignore: Boolean): Boolean = edit { f ->
        val p = f.people.firstOrNull { it.id == id } ?: return@edit f
        f.copy(people = f.people.filter { it.id != id }, ignored = if (ignore) f.ignored + p.xmpp.map { it.jid.trim().lowercase(Locale.ROOT) } else f.ignored)
    } != null

    /** Starts over after a damaged file: it is kept aside as people.json.bad, and People is empty. */
    fun reset(): Boolean = try {
        locked {
            if (file.exists() && read() == null) {
                val bad = File(dir, "people.json.bad")
                bad.delete()
                file.renameTo(bad)
            } else true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Writes [after], worked out from [before] outside the lock (an import's merge), if the file still holds [before];
     * otherwise [again] of what it holds now. The lock is held only for that check and the write.
     */
    fun commit(before: List<Person>, after: List<Person>, again: (List<Person>) -> List<Person>): List<Person>? =
        update { now -> if (now == before) after else again(now) }

    /** Adds [p], or replaces the person with its id. */
    fun save(p: Person): Boolean = update { PeopleEdit.upsert(it, p) } != null

    fun delete(id: String): Boolean = update { list -> list.filter { it.id != id } } != null

    /** Everyone, and the addresses not to learn again. */
    fun deleteAll(): Boolean = edit { PeopleFile() } != null

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        dir.mkdirs()
        FileChannel.open(File(dir, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }

    companion object {
        private val LOCK = Any()
        private val CACHE = HashMap<String, Pair<String, PeopleFile>>()
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")
        fun validId(id: String?): Boolean = id != null && ID.matches(id)
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Limits and tidying shared by everything that writes a person. */
object People {
    /** Everyone at most (an imported file is cut there). */
    const val MAX_PEOPLE = 5_000
    /** People learned from notifications kept at most: the oldest go first, so a flood of senders can't fill the file. */
    const val MAX_LEARNED = 200
    const val MAX_NAME = 100
    const val MAX_EACH = 20

    fun label(kind: PhoneKind): String = labelText(kind).en()

    /** [label] in words without a language yet. */
    fun labelText(kind: PhoneKind): Phrase = when (kind) {
        PhoneKind.MOBILE -> InfoStrings.phoneMobile()
        PhoneKind.HOME -> InfoStrings.phoneHome()
        PhoneKind.WORK -> InfoStrings.phoneWork()
        PhoneKind.OTHER -> InfoStrings.phoneOther()
    }

    /** A number's [label] as words without a language yet: one of [label]'s own in the reader's language, anything else as it is. */
    fun labelText(label: String): Phrase = PhoneKind.entries.firstOrNull { label(it) == label }?.let(::labelText) ?: Phrase.plain(label)

    private fun line(s: String, max: Int): String = ToolCalls.safeTake(s.replace(Regex("\\s+"), " ").trim(), max)

    /**
     * What you call someone, as kept: one line, without a leading "my", "mon", "ma" or "mes" ("my mom" is "mom", "ma
     * mère" is "mère"), unless that is all there is ("ma").
     */
    fun aliasName(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        val first = t.substringBefore(' ')
        return if (t.contains(' ') && ContactMatcher.words(first).singleOrNull() in ContactMatcher.POSSESSIVE) t.substringAfter(' ').trim() else t
    }

    /** Names on one line and not too long, each place once, at most [MAX_EACH] of each. */
    fun clean(p: Person): Person {
        val name = line(p.name, MAX_NAME)
        val aliases = p.aliases.map { aliasName(line(it, MAX_NAME)) }.filter { it.isNotEmpty() && !sameName(it, name) }
            .distinctBy { ContactMatcher.aliasWords(it) }.take(MAX_EACH)
        return p.copy(
            name = name,
            aliases = aliases,
            nicknames = p.nicknames.map { line(it, MAX_NAME) }.filter { n -> n.isNotEmpty() && !sameName(n, name) && aliases.none { sameName(it, n) } }
                .distinctBy { ContactMatcher.words(it) }.take(MAX_EACH),
            phones = p.phones.map { it.copy(number = line(it.number, 40), label = line(it.label, 40)) }.filter { it.number.isNotEmpty() }
                .distinctBy { PhoneNumbers.digits(it.number).takeLast(10).ifEmpty { it.number } }.take(MAX_EACH),
            emails = p.emails.map { line(it, 254) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_EACH),
            xmpp = p.xmpp.map { x ->
                val account = x.account?.let { a -> line(a, 254).lowercase(Locale.ROOT) }?.takeIf { a -> a.isNotEmpty() }
                XmppAddress(line(x.jid, 254), account, x.picked && account != null)
            }.filter { it.jid.isNotEmpty() }.distinctBy { it.jid.lowercase(Locale.ROOT) }.take(MAX_EACH),
        )
    }

    /** The same name, case, accents and punctuation aside ("Maman" and "maman"). */
    fun sameName(a: String, b: String): Boolean = ContactMatcher.words(a).let { it.isNotEmpty() && it == ContactMatcher.words(b) }

    /** One line for a list: the first number, address or XMPP address. */
    fun summary(p: Person): String = listOfNotNull(p.phones.firstOrNull()?.number, p.emails.firstOrNull(), p.xmpp.firstOrNull()?.jid)
        .firstOrNull().orEmpty()

    /** Settings › People's search: names and places, case and accents aside. */
    fun matches(p: Person, query: String): Boolean {
        val q = ContactMatcher.words(query)
        if (q.isEmpty()) return true
        val hay = ContactMatcher.words((listOf(p.name) + p.aliases + p.nicknames + p.emails + p.xmpp.map { it.jid } + p.phones.map { it.number }).joinToString(" "))
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

    /** Keep (Settings › People) confirms a learned person. Unchanged (the same list) if there was nothing to confirm. */
    fun confirm(list: List<Person>, id: String?, now: Long): List<Person> {
        val p = list.firstOrNull { it.id == id } ?: return list
        if (p.confirmed) return list
        return upsert(list, p.copy(confirmed = true, updatedAt = now))
    }

    /**
     * Save in Settings › People: [edited] is the editor's person, typed over [base] (the person as the editor loaded
     * them, in the editor's own terms; null for someone new). Only what was changed there is applied to the person as the
     * file holds them now, field by field and row by row, so what changed meanwhile elsewhere (a choice the assistant
     * remembered, an account a notification refreshed, a number an import added) is kept. Someone deleted meanwhile
     * stays deleted (null). Saving confirms them. Returns the list and the person as saved.
     */
    fun applyEdit(list: List<Person>, base: Person?, edited: Person, now: Long): Pair<List<Person>, Person>? {
        val cur = list.firstOrNull { it.id == edited.id }
        if (cur == null) {
            if (base != null) return null
            val p = People.clean(edited.copy(confirmed = true, updatedAt = now))
            return list + p to p
        }
        val b = base ?: Person(cur.id, "")
        fun text(s: String) = s.trim().lowercase(Locale.ROOT)
        val phones = rows(b.phones, edited.phones, cur.phones) { PhoneNumbers.digits(it.number).ifEmpty { it.number } }
        val xmpp = rows(b.xmpp, edited.xmpp, cur.xmpp) { text(it.jid) }
        val emails = rows(b.emails, edited.emails, cur.emails, ::text)
        val merged = cur.copy(
            name = if (edited.name != b.name) edited.name else cur.name,
            aliases = rows(b.aliases, edited.aliases, cur.aliases) { ContactMatcher.aliasWords(it).joinToString(" ") },
            nicknames = rows(b.nicknames, edited.nicknames, cur.nicknames) { ContactMatcher.words(it).joinToString(" ") },
            phones = phones, emails = emails, xmpp = xmpp,
            // A remembered choice whose place is gone is forgotten.
            preferredText = cur.preferredText?.takeIf { pref -> phones.any { Preferred.same(it.number, pref) } || xmpp.any { Preferred.same(it.jid, pref) } },
            preferredEmail = cur.preferredEmail?.takeIf { pref -> emails.any { it.equals(pref, ignoreCase = true) } },
            confirmed = true, updatedAt = now,
        )
        val p = People.clean(merged)
        return upsert(list, p) to p
    }

    /**
     * One list field of [applyEdit]: the editor's rows ([edited]) in their order, where a row left as it was in [base] is
     * the stored one now ([current]; gone if it was removed meanwhile), then rows added meanwhile. Rows match by [key].
     */
    private fun <T> rows(base: List<T>, edited: List<T>, current: List<T>, key: (T) -> String): List<T> {
        if (edited == base) return current
        val baseKeys = base.map(key).toSet()
        val out = ArrayList<T>()
        for (e in edited) {
            if (e in base) current.firstOrNull { key(it) == key(e) }?.let { out += it } else out += e
        }
        for (c in current) if (key(c) !in baseKeys) out += c
        return out.distinctBy(key)
    }

    /** "Forget" on a remembered choice ([email]: the one for emails, else for texts). Unchanged if there is none. */
    fun forgetChoice(list: List<Person>, id: String, email: Boolean, now: Long): List<Person> {
        val p = list.firstOrNull { it.id == id } ?: return list
        if ((if (email) p.preferredEmail else p.preferredText) == null) return list
        return upsert(list, if (email) p.copy(preferredEmail = null, updatedAt = now) else p.copy(preferredText = null, updatedAt = now))
    }

    /** [p] with [alias] (as said: "my mom" is kept as "mom") unless it is already their name or an alias. */
    private fun withAlias(p: Person, alias: String?): Person {
        val a = alias?.let(People::aliasName)?.takeIf { ContactMatcher.words(it).isNotEmpty() } ?: return p
        if (People.sameName(a, p.name) || p.aliases.any { ContactMatcher.sameAlias(it, a) }) return p
        return p.copy(aliases = p.aliases + a)
    }

    /** [alias] taken from everyone but [keep]: a name you gave one person means only them from now on. */
    private fun takeAlias(list: List<Person>, alias: String?, keep: String): List<Person> {
        if (alias.isNullOrBlank()) return list
        return list.map { p ->
            if (p.id != keep && p.aliases.any { ContactMatcher.sameAlias(it, alias) }) p.copy(aliases = p.aliases.filter { !ContactMatcher.sameAlias(it, alias) }) else p
        }
    }

    private fun sameJid(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Someone picked in Cheogram's list, remembered: the person with that XMPP address gets [spoken] (what you said:
     * "mom") as an alias and the account it was picked in (the newest pick's, replacing an older one), and is confirmed;
     * or a new person, named as Cheogram names them. Returns the list and the person.
     */
    fun rememberPick(list: List<Person>, spoken: String?, pick: CheogramPick, now: Long, newId: () -> String = PeopleStore::newId): Pair<List<Person>, Person> {
        val old = list.firstOrNull { p -> p.xmpp.any { sameJid(it.jid, pick.jid) } }
        val address = XmppAddress(pick.jid, pick.account, picked = pick.account != null)
        val p = if (old == null) {
            Person(newId(), pick.name, xmpp = listOf(address), source = PersonSource.CHEOGRAM_PICK, confirmed = true, updatedAt = now)
        } else {
            // A learned name was the sender's choice: Cheogram's name (your roster's, or your contacts') replaces it.
            old.copy(
                name = if (old.confirmed) old.name else pick.name,
                xmpp = old.xmpp.map { if (sameJid(it.jid, pick.jid) && pick.account != null) it.copy(account = pick.account, picked = true) else it },
                confirmed = true, updatedAt = now,
            )
        }
        val named = People.clean(withAlias(p, spoken))
        return upsert(takeAlias(list, spoken, named.id), named) to named
    }

    /**
     * "Always use this for “mom”" on a card: the place [r] becomes the person's choice for texts ([email] false) or
     * emails, and [spoken] (the name asked) one of their aliases, taken from anyone else who had it. A person in People
     * is changed; for one of the phone's contacts ([lookupKey]) the entry that adds to it is made or changed, keeping
     * the place too (for when the contact can't be read).
     */
    fun rememberChoice(list: List<Person>, spoken: String, r: Reach, email: Boolean, lookupKey: String?, now: Long,
        newId: () -> String = PeopleStore::newId): List<Person> {
        val pid = ContactIds.peopleId(r.contactId)
        val old = if (pid != null) list.firstOrNull { it.id == pid } else lookupKey?.let { k -> list.firstOrNull { it.systemLookupKey == k } }
        if (pid != null && old == null) return list
        val base = old ?: Person(newId(), r.name, source = PersonSource.REMEMBERED, systemLookupKey = lookupKey, updatedAt = now)
        val kept = when {
            email -> if (base.emails.any { it.equals(r.address, ignoreCase = true) }) base else base.copy(emails = base.emails + r.address)
            r.xmpp -> if (base.xmpp.any { sameJid(it.jid, r.address) }) base else base.copy(xmpp = base.xmpp + XmppAddress(r.address, r.account, r.account != null))
            base.phones.any { Preferred.same(it.number, r.address) } -> base
            else -> base.copy(phones = base.phones + ContactPhone(r.address, PhoneKind.OTHER, r.label ?: People.label(PhoneKind.OTHER)))
        }
        val chosen = (if (email) kept.copy(preferredEmail = r.address) else kept.copy(preferredText = r.address)).copy(confirmed = true, updatedAt = now)
        val p = People.clean(withAlias(chosen, spoken))
        return upsert(takeAlias(list, spoken, p.id), p)
    }

    /**
     * Someone who messaged you in Cheogram (the notification listener): [name] as Cheogram showed it, their address and
     * the account it came in on. Someone already known by that address keeps their name if you confirmed them; a learned
     * one takes the newest name. The account is the newest one too (an older one, even picked, is replaced: the chat
     * moved). New people are learned, unconfirmed, and at most [People.MAX_LEARNED] are kept (the oldest go). Unchanged
     * (the same list) when there is nothing new.
     */
    fun learn(list: List<Person>, name: String, jid: String, account: String, now: Long, newId: () -> String = PeopleStore::newId): List<Person> {
        val n = name.replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty() || !Addresses.isJid(jid)) return list
        val acct = account.trim().lowercase(Locale.ROOT)
        val old = list.firstOrNull { p -> p.xmpp.any { sameJid(it.jid, jid) } }
        if (old != null) {
            val newAccount = old.xmpp.any { sameJid(it.jid, jid) && it.account != acct }
            val rename = !old.confirmed && old.name != People.clean(old.copy(name = n)).name
            if (!newAccount && !rename) return list
            // Seen in another account than the one picked: not known to be a contact there.
            val xmpp = old.xmpp.map { if (sameJid(it.jid, jid) && it.account != acct) it.copy(account = acct, picked = false) else it }
            return upsert(list, People.clean(old.copy(name = if (rename) n else old.name, xmpp = xmpp, updatedAt = now)))
        }
        val added = list + People.clean(Person(newId(), n, xmpp = listOf(XmppAddress(jid, acct)), source = PersonSource.NOTIFICATION, confirmed = false,
            updatedAt = now))
        val learned = added.filter { !it.confirmed }
        if (learned.size <= People.MAX_LEARNED) return added
        val drop = learned.sortedBy { it.updatedAt }.take(learned.size - People.MAX_LEARNED).map { it.id }.toSet()
        return added.filter { it.id !in drop }
    }

    /**
     * What an import did: people added, people who got something new, cards left out (nothing to reach them by), and
     * for each incoming entry the id of the person it is now part of (null: left out).
     */
    data class Merged(val people: List<Person>, val added: Int, val updated: Int, val skipped: Int, val landed: List<String?> = emptyList())

    /** A number's address at the SMS gateway ("+15145550101@cheogram.com"): its local part. */
    private val GATEWAY_LOCAL = Regex("""^\+\d{7,15}$""")

    /** Keys two entries share when they are the same person: a number (last 10 digits), an email or an XMPP address. */
    private fun keys(p: Person): Set<String> = buildSet {
        p.phones.forEach { PhoneNumbers.digits(it.number).takeIf { d -> d.length >= 7 }?.let { d -> add("n:" + d.takeLast(10)) } }
        p.emails.forEach { add("e:" + it.trim().lowercase(Locale.ROOT)) }
        p.xmpp.forEach { x ->
            add("x:" + x.jid.trim().lowercase(Locale.ROOT))
            // A number's address at the SMS gateway is that number.
            val local = x.jid.substringBeforeLast('@')
            if (GATEWAY_LOCAL.matches(local)) add("n:" + PhoneNumbers.digits(local).takeLast(10))
        }
    }

    /** The same name, or one's name is a nickname or an alias of the other (case and accents aside). */
    private fun sameNames(a: Person, b: Person): Boolean =
        (listOf(a.name) + a.aliases + a.nicknames).any { People.sameName(it, b.name) } || (b.aliases + b.nicknames).any { People.sameName(it, a.name) }

    /**
     * Imported people ([incoming]: a contacts file, the phone's contact picker) merged in. Someone already there is the
     * same person only when they share a number, an email or an XMPP address and either their names agree (a nickname
     * or an alias counts) or every number and address of the incoming entry is already theirs: a shared landline or a
     * family email alone never merges two people (Mom's home number doesn't make Dad's mobile hers). The same person
     * gets what's new (their name stays, unless they were only learned from a notification; the file's other name
     * becomes a nickname); anyone else is added. Entries with nothing to reach them by are skipped. Indexed by those
     * keys, so a file of thousands merges in one pass.
     */
    fun merge(list: List<Person>, incoming: List<Person>, now: Long): Merged {
        val out = ArrayList(list)
        val index = HashMap<String, MutableList<Int>>()
        fun indexAt(i: Int) {
            for (k in keys(out[i])) index.getOrPut(k) { ArrayList(1) }.let { if (i !in it) it += i }
        }
        out.indices.forEach(::indexAt)
        var added = 0
        var updated = 0
        var skipped = 0
        val landed = ArrayList<String?>(incoming.size)
        for (raw in incoming) {
            val inc = People.clean(raw)
            if (!inc.reachable || inc.name.isEmpty()) { skipped++; landed += null; continue }
            val k = keys(inc)
            val at = k.flatMap { index[it].orEmpty() }.distinct().firstOrNull { i -> sameNames(out[i], inc) || keys(out[i]).containsAll(k) }
            if (at == null) {
                if (out.size >= People.MAX_PEOPLE) { skipped++; landed += null; continue }
                out += inc.copy(updatedAt = now)
                indexAt(out.lastIndex)
                added++
                landed += inc.id
                continue
            }
            val old = out[at]
            landed += old.id
            val merged = People.clean(old.copy(
                name = if (old.confirmed) old.name else inc.name,
                aliases = old.aliases + inc.aliases,
                nicknames = old.nicknames + inc.nicknames + (if (old.confirmed && !People.sameName(old.name, inc.name)) listOf(inc.name) else emptyList()),
                phones = old.phones + inc.phones,
                emails = old.emails + inc.emails,
                xmpp = old.xmpp + inc.xmpp.filter { x -> old.xmpp.none { sameJid(it.jid, x.jid) } },
                confirmed = true,
            ))
            if (merged != old) {
                out[at] = merged.copy(updatedAt = now)
                indexAt(at)
                updated++
            }
        }
        return Merged(out, added, updated, skipped, landed)
    }
}

/** Who can be reached by a text ([text]; XMPP only with Cheogram, [xmppOk]) or an email ([email]), for AddressBook.best. */
object Reachable {
    fun text(xmppOk: Boolean): (Person) -> Boolean = { p -> p.phones.any { Addresses.isPhone(it.number) } || (xmppOk && p.xmpp.any { Addresses.isJid(it.jid) }) }
    val email: (Person) -> Boolean = { p -> p.emails.any(Addresses::isAddress) }
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

    /** Every name to rank: the phone's contacts (with the names a People entry adds) and People of their own. */
    val names: List<ContactName> = system.map { c -> linked[c.id]?.let { p -> c.copy(nicknames = c.nicknames + p.nicknames, aliases = c.aliases + p.aliases) } ?: c } +
        own.map { (ref, p) -> ContactName(ref, p.name, nicknames = p.nicknames, aliases = p.aliases, learned = !p.confirmed) }

    /**
     * The contacts [query] means. [reach]: whether a person can be reached by the tool asking (Reachable): an alias
     * outranks other names only for someone who can, so "bob" for a texting-only person never hides the phone's Bob with
     * an email from "email bob"; for anyone else it counts like a nickname.
     */
    fun best(query: String, max: Int = 5, reach: ((Person) -> Boolean)? = null): List<ContactMatch> =
        ContactMatcher.best(query, names, max) { c -> reach == null || person(c.id)?.let(reach) ?: true }

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
