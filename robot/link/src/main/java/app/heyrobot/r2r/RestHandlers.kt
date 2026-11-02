// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.Person
import app.heyrobot.core.link.ContactCard
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkRest
import app.heyrobot.core.link.LinkVCard
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.RequestType

// 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, D8, D17; job L10): reminders for you, contact cards and questions, on
// the link thread. ETA is LinkDesk's EtaHandler (shown, nothing done). No handler here reads the model or a helper: what
// goes back is your words or what you picked (D11), and what comes in lands only where its row of §4.1 says.

/** Your brief reminders, as reminders from someone need them (the app's RoutineStore, under its lock). */
interface DeskReminders {
    /** [text] from [pairing]'s person for the day of [whenAt] (Unix seconds); an undo token, or null when it couldn't. */
    fun add(pairing: String, text: String, whenAt: Long): String?
    /** The reminder [token] named, taken out again; true when it was. */
    fun remove(token: String): Boolean
}

/** People, as a contact card needs it (the app's PeopleStore, under its lock). */
interface DeskPeople {
    /** Everyone; null when People can't be read. */
    fun list(): List<Person>?
    /** [p] added (a new person, unconfirmed); true when written. */
    fun add(p: Person): Boolean
    fun newId(): String
}

/** What you shared with someone (L8 files, L11 folders), as a question answered on its own needs it (D17). */
fun interface DeskShares {
    /** The answer to [question] found in what you shared with [pairing]'s person, as your words; null when nothing is. */
    fun answer(pairing: String, question: String): String?

    companion object {
        /** Nothing is shared before L8 and L11: every question waits for you. */
        val NONE = DeskShares { _, _ -> null }
    }
}

/**
 * `rem.offer` (§4.1): Ask me first by default (D8); Yes, a spoken yes in the panel unlocked too, puts it in your brief
 * reminders, never your calendar. On its own at most 3 a day from one person (LinkPolicy.reminderAlone); past that, a card.
 */
class RemindersHandler(private val st: LinkStateStore, private val reminders: DeskReminders) : DeskHandler {
    override val types = setOf(RequestType.REM_OFFER)

    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? {
        if (!LinkRest.reminderAllowed(LinkRest.remindersToday(st.desk.values, pairing, out.now()))) return null
        return add(pairing, r)
    }

    override fun yes(r: LinkRequest) = if (r.body is RemOffer) DeskYes.ADD_REMINDER else null

    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? = add(pairing, r)

    private fun add(pairing: String, r: LinkRequest): DeskDone? {
        val b = r.body as? RemOffer ?: return null
        val token = reminders.add(pairing, b.text, b.whenAt) ?: return null
        return DeskDone(undo = token)
    }

    override fun undo(pairing: String, token: String, out: DeskOut): Boolean = reminders.remove(token)
}

/**
 * `card.offer` (§4.1): always your tap (Ask me first; never a spoken yes, never on its own, never a helper). Yes adds a new
 * person to People, unconfirmed, with any third person's Robot link dropped; someone you have is never changed.
 */
class CardsHandler(private val people: DeskPeople) : DeskHandler {
    override val types = setOf(RequestType.CARD_OFFER)

    override fun yes(r: LinkRequest) = if (r.body is CardOffer) DeskYes.ADD_CONTACT else null

    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? {
        val b = r.body as? CardOffer ?: return null
        // Read in :linkparse alone (ProcessIsolationTest linkParsesOnlyInIsolation): without its card, nothing is added.
        val card: ContactCard = b.card ?: return null
        val all = people.list() ?: return null
        val p = LinkRest.person(card, all, people.newId(), out.now()) ?: return DeskDone(nothing = true)
        return if (people.add(p)) DeskDone() else null
    }
}

/**
 * `q.ask` and `q.reply` (§4.1, D17): a question is answered on its own only from what you shared with that person; otherwise
 * it waits for you, answered with your typed words or a note's ([answerWith]). An answer that comes back counts only when
 * it answers a question you sent that Robot.
 *
 * L8's seam: [answerWithFile] sends a file offer's id as the answer (`q.reply` with `file`); L8 calls it after its file offer
 * is queued.
 */
class QuestionsHandler(private val st: LinkStateStore, private val shares: DeskShares = DeskShares.NONE) : DeskHandler {
    override val types = setOf(RequestType.Q_ASK, RequestType.Q_REPLY)

    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? {
        val q = r.body as? QAsk ?: return null
        val found = shares.answer(pairing, q.question)
        if (!app.heyrobot.core.link.LinkPolicy.fromSharedAlone(found != null)) return null
        return answerWith(pairing, r, found!!, out)
    }

    override fun answerWith(pairing: String, r: LinkRequest, text: String, out: DeskOut): DeskDone? {
        if (r.body !is QAsk) return null
        val t = LinkRest.answer(text) ?: return null
        out.send(pairing, QReply(r.id, t)) ?: return null
        return DeskDone()
    }

    /** L8: a file offer [offerId] (already queued to [pairing]) as the answer to question [r]. */
    override fun answerWithFile(pairing: String, r: LinkRequest, offerId: String, out: DeskOut): DeskDone? {
        if (r.body !is QAsk || !app.heyrobot.core.link.LinkIds.isId(offerId)) return null
        out.send(pairing, QReply(r.id, file = offerId)) ?: return null
        return DeskDone()
    }

    private var keep = false

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState {
        val b = r.body as? QReply
        keep = b != null && st.desk[b.re]?.let { it.way == DeskWay.OUT && it.pairing == pairing && it.type == RequestType.Q_ASK.code } == true &&
            st.desk.values.none { it.way == DeskWay.REPLY && it.pairing == pairing && it.type == RequestType.Q_REPLY.code && it.id == r.id }
        return AckState.RECEIVED
    }

    override fun kept(pairing: String, r: LinkRequest): Boolean = keep.also { keep = false }
}
