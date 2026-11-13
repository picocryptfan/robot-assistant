package app.heyrobot.people

import app.heyrobot.core.ContactMatcher
import app.heyrobot.core.People
import app.heyrobot.core.Person

/**
 * fix-seal-14 M2: who [HoldTap] lets through, only what it matches on (each person's name and aliases), from the last unlocked
 * read of People, kept in this process's memory only: never on disk or in a log. While the phone is locked People reads back
 * as nobody (it's sealed), so the last unlocked read stands until the next one, or a change; with none since the process
 * started, nobody is known and messages are held, as before. fix-bug-card-14-b L1: locked 24 hours since the last unlocked
 * read, the list is dropped too, and messages are held until the next one.
 */
class HoldPeople(private val maxAgeMs: Long = 60_000L) {
    private class Seen(val at: Long, val names: List<Pair<String, List<String>>>)

    @Volatile private var seen: Seen? = null

    /** People as just read unlocked, or as just changed (Delete all: empty): kept until the next. */
    fun remember(people: List<Person>, now: Long) { seen = Seen(now, people.map { it.name to it.aliases }) }

    /** [title] is someone in People. [read] (null: People can't be opened now, the phone locked) at most once a [maxAgeMs]. */
    fun knows(title: String, now: Long, read: () -> List<Person>?): Boolean {
        val s = seen?.takeIf { now - it.at in 0 until maxAgeMs }
            ?: (try { read() } catch (_: Throwable) { null }).let { l ->
                if (l != null) remember(l, now) else if (seen?.let { now - it.at !in 0 until LOCKED_MAX_MS } == true) seen = null
                seen
            }
        return s?.names.orEmpty().any { (name, aliases) -> People.sameName(name, title) || aliases.any { ContactMatcher.sameAlias(it, title) } }
    }

    companion object {
        /** L1: how long the last unlocked read stands while People can't be read (locked). */
        const val LOCKED_MAX_MS = 24 * 3_600_000L
    }
}
