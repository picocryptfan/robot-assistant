// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.content.Context
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.BriefText
import app.heyrobot.core.Person
import app.heyrobot.core.PeopleStore
import app.heyrobot.core.hex
import app.heyrobot.core.link.LinkRest
import app.heyrobot.r2r.DeskPeople
import app.heyrobot.r2r.DeskReminders
import app.heyrobot.r2r.LinkStateStore
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.RoutineFiles
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 1.5, Robot to Robot (job L10): where a reminder or a contact card from someone lands, on :link's thread. Both stores take
// the lock the main app's process takes too (RoutineStore, PeopleStore), so a write here never loses one made there.

/** Reminders from someone: one line of your brief reminders, "From Sam: bring the charger" (never your calendar). */
class LinkDeskReminders(private val app: Context, private val st: LinkStateStore) : DeskReminders {
    override fun add(pairing: String, text: String, whenAt: Long): String? {
        val day = Instant.ofEpochSecond(whenAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val line = BriefText.clean(LinkRest.reminderLine(name(pairing), text, AppLanguage.langs(app).ui), BriefReminders.MAX_TEXT)
        if (line.isEmpty()) return null
        val after = RoutineFiles.store(app).editReminders { BriefReminders.addFromOthers(it, line, day) } ?: return null
        return if (after.any { it.readAt == null && it.text == line && it.day == day }) token(line, day) else null
    }

    override fun remove(token: String): Boolean {
        var gone = false
        RoutineFiles.store(app).editReminders { list ->
            val i = list.indexOfFirst { it.readAt == null && token(it.text, it.day) == token }
            if (i < 0) list else list.filterIndexed { j, _ -> j != i }.also { gone = true }
        } ?: return false
        return gone
    }

    /** Your People name for them when you said who they are, else the name their Robot asked to be shown. */
    private fun name(pairing: String): String {
        val p = st.pairings[pairing]
        val person = p?.person?.let { id -> PeopleFiles.store(app).get(id) }
        return person?.name ?: p?.name.orEmpty()
    }

    private fun token(text: String, day: LocalDate): String =
        "rem-" + hex(MessageDigest.getInstance("SHA-256").digest("$day\n$text".toByteArray())).take(32) // l10n: not text (a token)
}

/** People for a contact card: a new person added unconfirmed; no one you have is changed (LinkRest.person decides). */
class LinkDeskPeople(private val app: Context) : DeskPeople {
    override fun list(): List<Person>? = PeopleFiles.store(app).let { if (it.damaged()) null else it.list() }
    override fun add(p: Person): Boolean = PeopleFiles.store(app).update { all -> if (all.any { it.id == p.id }) all else all + p } != null
    override fun newId(): String = PeopleStore.newId()
}
