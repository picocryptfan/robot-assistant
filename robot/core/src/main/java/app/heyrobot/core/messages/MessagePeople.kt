// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.Person
import app.heyrobot.core.PhoneNumbers
import java.util.Locale

// Pure. Who wrote a message, in your words: the name Robot reads aloud is your People alias when the
// sender's address is one of that person's (Cheogram's JID, or the number of a JMP address against People's phones),
// else, for an app that gives no address (Signal), the person whose name in People the notification shows, else the
// name the notification shows. Only people you confirmed count: a name learned from a notification is whoever sent it's
// choice, read as given, and so is a name sent from an address nobody in People has. "Reply to Mom" and "what did Mom
// say?" find conversations the same way, so the recipient is always a notification's conversation.
//
// A JID's local part is a phone number only at the SMS gateway Robot texts through (Settings' smsGateway, cheogram.com
// unless you changed it): anyone can make "+15145550101@their.server", so on any other server the address is matched
// whole, as given in People. Every [gateway] below is that setting; the default is cheogram.com's.

object MessagePeople {
    /**
     * [p] can write from [key] (a JID, a phone number, or a JMP address "+15145550101@cheogram.com" at [gateway]), case
     * aside.
     */
    fun matchesKey(p: Person, key: String?, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): Boolean {
        val k = key?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        if (p.xmpp.any { it.jid.trim().lowercase(Locale.ROOT) == k }) return true
        val d = number(k, gateway) ?: return false
        return p.phones.any { ph -> number(ph.number, gateway)?.let { it.takeLast(10) == d.takeLast(10) } == true }
    }

    /**
     * The confirmed person whose address is [key], or whose name is [name] when the app gives no address Robot can check
     * (Signal's keys are its own). A sender writing from an address (a JID, a phone number) that is nobody's in People is
     * nobody in People, whatever name they chose for their app to show.
     */
    fun personFor(key: String?, name: String?, people: List<Person>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): Person? {
        val confirmed = people.filter { it.confirmed }
        confirmed.firstOrNull { matchesKey(it, key, gateway) }?.let { return it }
        if (address(key)) return null
        val n = name?.let(MessagesRouter::nameKey)?.takeIf { it.isNotEmpty() } ?: return null
        return confirmed.firstOrNull { MessagesRouter.nameKey(it.name) == n }
    }

    /**
     * "Send my replies to … without a card" ([ids]: People ids it is on for) holds for [t]: a one-to-one conversation whose
     * sender writes from an address of that confirmed person's (Cheogram's JID, a JMP number against their phones). A name
     * match alone never sends without a card: the sender chose the name their app shows.
     */
    fun withoutCard(t: MessageThread, people: List<Person>, ids: Set<String>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): Boolean {
        if (t.isGroup || ids.isEmpty()) return false
        val key = t.incoming.lastOrNull()?.senderKey ?: return false
        return people.any { it.confirmed && it.id in ids && matchesKey(it, key, gateway) }
    }

    /** [key] is an address Robot can check against People: a JID (on any server) or a phone number. */
    private fun address(key: String?): Boolean {
        val k = key?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return '@' in k || number(k, PhoneNumbers.DEFAULT_GATEWAY) != null
    }

    /** What you call [p]: the first alias ("mom" read as "Mom"), else their name. */
    fun callName(p: Person): String = p.aliases.firstOrNull { it.isNotBlank() }?.trim()?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: p.name

    /** [t] as Robot reads it: a 1:1 chat titled by what you call its sender, each group message's sender by theirs. */
    fun named(t: MessageThread, people: List<Person>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): MessageThread {
        if (people.isEmpty()) return t
        val msgs = t.messages.map { m ->
            if (m.fromMe) m else personFor(m.senderKey, m.senderName, people, gateway)?.let { m.copy(senderName = callName(it)) } ?: m
        }
        val title = if (t.isGroup) t.title else {
            val sender = t.incoming.lastOrNull()
            sender?.let { personFor(it.senderKey, it.senderName, people, gateway) }?.let(::callName) ?: t.title
        }
        return t.copy(title = title, messages = msgs)
    }

    /** The person behind a 1:1 conversation ("Send my replies to Mom without a card"), or null (a group, or no one in People). */
    fun personOf(t: MessageThread, people: List<Person>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): Person? {
        if (t.isGroup) return null
        val m = t.incoming.lastOrNull() ?: return null
        return personFor(m.senderKey, m.senderName, people, gateway)
    }

    /**
     * The conversations "[name]" means: those with a message from a confirmed person you call that (People's name, an
     * alias or a nickname), and those whose title or a sender's name the notification shows is that. Newest first, as
     * [threads] are.
     */
    fun find(name: String, threads: List<MessageThread>, people: List<Person>, gateway: String = PhoneNumbers.DEFAULT_GATEWAY): List<MessageThread> {
        val q = MessagesRouter.nameKey(name)
        if (q.isEmpty()) return emptyList()
        val who = people.filter { p -> p.confirmed && (listOf(p.name) + p.aliases + p.nicknames).any { MessagesRouter.nameKey(it) == q } }
        val byPerson = threads.filter { t -> t.incoming.any { m -> who.any { p -> matchesKey(p, m.senderKey, gateway) || personFor(m.senderKey, m.senderName, people, gateway) == p } } }
        val byName = ReplyPlanner.candidates(name, threads)
        return (byPerson + byName).distinctBy { it.key }.let { found -> threads.filter { t -> found.any { it.key == t.key } } }
    }

    /** Whether "[name]" is anyone Robot knows: a confirmed person, or a conversation (so "what did Einstein say?" stays a question). */
    fun known(name: String, threads: List<MessageThread>, people: List<Person>): Boolean {
        val q = MessagesRouter.nameKey(name)
        if (q.isEmpty()) return false
        return people.any { p -> p.confirmed && (listOf(p.name) + p.aliases + p.nicknames).any { MessagesRouter.nameKey(it) == q } } ||
            ReplyPlanner.candidates(name, threads).isNotEmpty()
    }

    /** Every name a conversation goes by (titles, senders, what you call them), for [MessagesRouter.split]. */
    fun names(threads: List<MessageThread>, people: List<Person>): List<String> {
        val out = LinkedHashSet<String>()
        for (t in threads) {
            t.title?.let(out::add)
            t.incoming.mapNotNullTo(out) { it.senderName }
        }
        for (p in people) if (p.confirmed) { out += p.name; out += p.aliases; out += p.nicknames }
        return out.filter { it.isNotBlank() }
    }

    /**
     * The digits of a phone number, or of a number's address at the SMS gateway [gateway] ("+15145550101@cheogram.com",
     * a JMP number); null otherwise. On any other server a JID is only itself, whatever its local part looks like: there
     * "+15145550101" is a name anyone can register.
     */
    fun number(s: String, gateway: String): String? {
        val t = s.trim()
        val local = if ('@' in t) {
            // The domain, without a resource ("/phone"); the first '@' ends the local part, which can't hold one (RFC 7622).
            val domain = t.substringAfter('@').substringBefore('/').lowercase(Locale.ROOT)
            if (domain != PhoneNumbers.gateway(gateway)) return null
            t.substringBefore('@')
        } else t
        if (!Regex("^\\+?[0-9 ()./-]{7,}$").matches(local)) return null
        return local.filter(Char::isDigit).takeIf { it.length >= 7 }
    }
}
