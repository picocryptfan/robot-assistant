package app.heyrobot.core.messages

import app.heyrobot.core.Person
import java.util.Locale

// Pure. Who wrote a message, in your words (design §B4.6): the name Robot reads aloud is your People alias when the
// sender's address is one of that person's (Cheogram's JID, or the number of a JMP address against People's phones),
// else the person's name in People when only the name matches, else the name the notification shows. Only people you
// confirmed count: a name learned from a notification is whoever sent it's choice, read as given. "Reply to Mom" and
// "what did Mom say?" find conversations the same way, so the recipient is always a notification's conversation.

object MessagePeople {
    /** [p] can write from [key] (a JID, a phone number, or a JMP address "+15145550101@cheogram.com"), case aside. */
    fun matchesKey(p: Person, key: String?): Boolean {
        val k = key?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
        if (p.xmpp.any { it.jid.trim().lowercase(Locale.ROOT) == k }) return true
        val d = phone(k) ?: return false
        return p.phones.any { ph -> phone(ph.number)?.let { it.takeLast(10) == d.takeLast(10) } == true }
    }

    /** The confirmed person whose address is [key], or whose name is [name] (when no address matched anyone). */
    fun personFor(key: String?, name: String?, people: List<Person>): Person? {
        val confirmed = people.filter { it.confirmed }
        confirmed.firstOrNull { matchesKey(it, key) }?.let { return it }
        val n = name?.let(MessagesRouter::nameKey)?.takeIf { it.isNotEmpty() } ?: return null
        return confirmed.firstOrNull { MessagesRouter.nameKey(it.name) == n }
    }

    /** What you call [p]: the first alias ("mom" read as "Mom"), else their name. */
    fun callName(p: Person): String = p.aliases.firstOrNull { it.isNotBlank() }?.trim()?.replaceFirstChar { it.titlecase(Locale.ROOT) } ?: p.name

    /** [t] as Robot reads it: a 1:1 chat titled by what you call its sender, each group message's sender by theirs. */
    fun named(t: MessageThread, people: List<Person>): MessageThread {
        if (people.isEmpty()) return t
        val msgs = t.messages.map { m ->
            if (m.fromMe) m else personFor(m.senderKey, m.senderName, people)?.let { m.copy(senderName = callName(it)) } ?: m
        }
        val title = if (t.isGroup) t.title else {
            val sender = t.incoming.lastOrNull()
            sender?.let { personFor(it.senderKey, it.senderName, people) }?.let(::callName) ?: t.title
        }
        return t.copy(title = title, messages = msgs)
    }

    /** The person behind a 1:1 conversation ("Send my replies to Mom without a card"), or null (a group, or no one in People). */
    fun personOf(t: MessageThread, people: List<Person>): Person? {
        if (t.isGroup) return null
        val m = t.incoming.lastOrNull() ?: return null
        return personFor(m.senderKey, m.senderName, people)
    }

    /**
     * The conversations "[name]" means: those with a message from a confirmed person you call that (People's name, an
     * alias or a nickname), and those whose title or a sender's name the notification shows is that. Newest first, as
     * [threads] are.
     */
    fun find(name: String, threads: List<MessageThread>, people: List<Person>): List<MessageThread> {
        val q = MessagesRouter.nameKey(name)
        if (q.isEmpty()) return emptyList()
        val who = people.filter { p -> p.confirmed && (listOf(p.name) + p.aliases + p.nicknames).any { MessagesRouter.nameKey(it) == q } }
        val byPerson = threads.filter { t -> t.incoming.any { m -> who.any { p -> matchesKey(p, m.senderKey) || personFor(m.senderKey, m.senderName, people) == p } } }
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

    /** The digits of a phone number, or of a JMP address's local part; null when it isn't one. */
    private fun phone(s: String): String? {
        val local = s.trim().substringBefore('@')
        if (!Regex("^\\+?[0-9 ()./-]{7,}$").matches(local)) return null
        return local.filter(Char::isDigit).takeIf { it.length >= 7 }
    }
}
