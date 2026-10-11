package app.heyrobot.core.messages

import java.text.Normalizer
import java.util.Locale

// Pure. The messages Robot holds, in RAM only (design §B4): the active notifications are the source of truth. A
// conversation enters when its notification is posted, is replaced when the app posts it again (what Robot already read
// to you stays marked), and leaves when the notification goes (read in the app, dismissed, cleared), at once by default
// or after "Keep messages for" 1 or 24 hours (still RAM only). Process death or a reboot empties it; the listener
// rebuilds it from getActiveNotifications() when it connects. Nothing here is ever written to disk or a log.

class MessageBuffer(keep: KeepMessages = KeepMessages.UNTIL_GONE, private val maxThreads: Int = MAX_THREADS) {
    private class Entry(val thread: MessageThread, val goneAt: Long?)

    private val entries = LinkedHashMap<String, Entry>()

    /** "Keep messages for": changing it applies at the next [sweep] (Until gone: what has gone, goes). */
    @get:Synchronized @set:Synchronized
    var keep: KeepMessages = keep

    @get:Synchronized
    val size: Int get() = entries.size

    /** A conversation's notification was posted (again): its messages replace the old ones; read marks carry over. */
    @Synchronized
    fun posted(thread: MessageThread) {
        val old = entries.remove(thread.key)
        val read = old?.thread?.messages?.filter { it.readByRobot }?.mapTo(HashSet()) { it.id }.orEmpty()
        val merged = if (read.isEmpty()) thread else thread.copy(messages = thread.messages.map { if (it.id in read) it.copy(readByRobot = true) else it })
        entries[thread.key] = Entry(merged, null)
        while (entries.size > maxThreads) entries.remove(entries.keys.first())
    }

    /** The notification [notificationKey] went away: its conversation goes now, or is kept until [keep] runs out. */
    @Synchronized
    fun removed(notificationKey: String, now: Long) {
        for (k in entries.filterValues { it.thread.notificationKey == notificationKey }.keys) {
            val e = entries.getValue(k)
            if (keep == KeepMessages.UNTIL_GONE) entries.remove(k)
            else if (e.goneAt == null) entries[k] = Entry(e.thread, now)
        }
    }

    /** The listener (re)connected and read the shade: [active] is what is there now. */
    @Synchronized
    fun connected(active: List<MessageThread>, now: Long) {
        val live = active.mapTo(HashSet()) { it.notificationKey }
        for (k in entries.filterValues { it.thread.notificationKey !in live && it.goneAt == null }.map { it.value.thread.notificationKey }) removed(k, now)
        for (t in active) posted(t)
    }

    /** Drops what has been gone longer than [keep] allows. */
    @Synchronized
    fun sweep(now: Long) {
        entries.entries.removeAll { (_, e) -> e.goneAt != null && now - e.goneAt >= keep.afterGoneMs }
    }

    /** "Read my messages" turned off: everything goes at once. */
    @Synchronized
    fun clear() = entries.clear()

    /** Every conversation, newest message first. */
    @Synchronized
    fun threads(now: Long): List<MessageThread> {
        sweep(now)
        return entries.values.map { it.thread }.sortedByDescending { t -> t.messages.maxOfOrNull { it.time } ?: t.postedAt }
    }

    /** The conversations with something not read to you yet, newest first. */
    fun unread(now: Long): List<MessageThread> = threads(now).filter { it.unread.isNotEmpty() || (it.hidden != null && it.incoming.any { m -> !m.readByRobot }) }

    @Synchronized
    fun thread(key: String): MessageThread? = entries[key]?.thread

    /** Robot read these to you (RAM only: nothing changes in the app unless "After Robot reads a message" says so). */
    @Synchronized
    fun markReadByRobot(ids: Collection<String>) {
        if (ids.isEmpty()) return
        for ((k, e) in entries.entries.toList()) {
            if (e.thread.messages.none { it.id in ids }) continue
            entries[k] = Entry(e.thread.copy(messages = e.thread.messages.map { if (it.id in ids) it.copy(readByRobot = true) else it }), e.goneAt)
        }
    }

    /**
     * The conversations "Mom" names by the notification's own words: the title or a sender's name, a whole word at a
     * time, accents and case aside. People aliases (your "Mom" is "Martine Tremblay") are matched by the caller through
     * [withSenderKeys].
     */
    fun named(name: String, now: Long): List<MessageThread> {
        val q = fold(name)
        if (q.isEmpty()) return emptyList()
        return threads(now).filter { t ->
            (listOfNotNull(t.title) + t.incoming.mapNotNull { it.senderName }).any { n -> val f = fold(n); f == q || " $f ".contains(" $q ") }
        }
    }

    /** The conversations with a message from one of [keys] (a JID, a phone number, Signal's id), case aside. */
    fun withSenderKeys(keys: Set<String>, now: Long): List<MessageThread> {
        val k = keys.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        return threads(now).filter { t -> t.incoming.any { it.senderKey?.lowercase(Locale.ROOT) in k } }
    }

    override fun toString(): String = "MessageBuffer(conversations=$size, keep=$keep)"

    companion object {
        const val MAX_THREADS = 40

        private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
