// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import app.heyrobot.core.PeopleLoad
import app.heyrobot.core.Person
import app.heyrobot.sys.PeopleFiles

/**
 * 1.4 (report-hide-people): what Robot's People adds to the bug card's hidden names, numbers and addresses (ReportHide),
 * read through PeopleFiles, so sealed People reads the same way. Null when People can't be read at all: damaged, a read error,
 * too new, or sealed and the phone locked. fix-seal-14-c M1: whatever it can read is always hidden; [Terms.whole] is false
 * when some of it wasn't read (an entry that didn't parse, a copy kept aside that can't be opened now), and the hide note
 * then says some of People wasn't read (fix-seal-14-d 1). Never sent: only what it hides goes.
 */
internal object ReportPeople {
    class Terms(val names: List<String>, val numbers: List<String>, val addresses: List<String>, val whole: Boolean = true)

    fun read(ctx: Context): Terms? = try {
        // fix-bug-card-14 M1: read once, and only a whole read counts (sealed and locked is NotNow); the seals' .lock file
        // in People's folder never makes an empty People look unreadable.
        // fix-seal-14 L4: an entry that didn't parse, or a copy kept aside, means People wasn't read whole either; never
        // that the ones it read go unhidden (fix-seal-14-c M1).
        when (val l = PeopleFiles.store(ctx).load()) {
            // fix-seal-14-d 1: what kept-aside copies that open hold is hidden too; read in part only when some of it wasn't read.
            is PeopleLoad.Ok -> {
                val aside = try { PeopleFiles.asidePeople(ctx) } catch (_: Exception) { null }
                of(l.file.people + aside?.people.orEmpty(), l.file.skipped == 0 && aside?.whole == true)
            }
            else -> null
        }
    } catch (_: Exception) { null }

    /** Each person's name, other names and nicknames; their numbers; their email and XMPP addresses (each once: a kept-aside copy may repeat them). */
    fun of(people: List<Person>, whole: Boolean = true): Terms = Terms(
        names = people.flatMap { listOf(it.name) + it.aliases + it.nicknames }.distinct(),
        numbers = people.flatMap { p -> p.phones.map { it.number } }.distinct(),
        addresses = people.flatMap { p -> p.emails + p.xmpp.map { it.jid } }.distinct(),
        whole = whole,
    )
}
