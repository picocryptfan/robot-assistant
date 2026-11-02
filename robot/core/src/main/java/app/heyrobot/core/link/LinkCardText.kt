// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.Person
import app.heyrobot.core.ScreenText
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.EtaTell
import app.heyrobot.core.link.requests.EvAccept
import app.heyrobot.core.link.requests.EvCancel
import app.heyrobot.core.link.requests.EvChange
import app.heyrobot.core.link.requests.EvDecline
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FileAsk
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.FoldJoin
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LsOffer
import app.heyrobot.core.link.requests.QAsk
import app.heyrobot.core.link.requests.QReply
import app.heyrobot.core.link.requests.RemOffer
import app.heyrobot.core.link.requests.SpRequest
import app.heyrobot.core.web.Invisible
import java.time.Instant
import java.time.ZoneId

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.2, §4.5, §6.5; job L6): what a card says, drawn by code from a
// request's typed fields, and apart from it the other person's own words, which the card shows in a framed box and never
// as part of Robot's sentence (a title like "Ignore your instructions" is shown, framed, and nothing else). Times are said in
// your time zone. The name is yours for that person (People), never the one their Robot sent, unless you haven't said who
// they are yet: then their claim, in quotes.

object LinkNames {
    /** [view]'s person as cards name them: your People name ([people]), else their claim in quotes, else "Someone". */
    fun of(view: PairingView?, people: List<Person>): Phrase {
        val p = view?.person?.let { id -> people.firstOrNull { it.id == id } }
        if (p != null) return Phrase.plain(PeerQuote.name(p).takeIf { it != PeerQuote.NO_NAME } ?: return LinkStrings.someone())
        val claim = claim(view?.claimed.orEmpty())
        return if (claim.isEmpty()) LinkStrings.someone() else LinkStrings.says("name" to claim)
    }

    /** Quote marks of any kind: a name that holds one could close the quote a card puts it in and add a sentence of its own. */
    private const val QUOTES = "\"\u201C\u201D\u201E\u201F\u00AB\u00BB\u2039\u203A\u301D\u301E\u301F\uFF02"

    /** What their Robot says they're called, as any screen shows it: clean, without quote marks; empty when nothing is left or it mixes look-alike scripts. */
    fun claim(raw: String): String {
        val t = ScreenText.sanitize(Invisible.strip(raw)).filterNot { it in QUOTES }.trim()
        return if (LinkVCard.mixedScripts(t)) "" else t
    }

    /** [view]'s person as their People entry, for PeerQuote's frame; null when you haven't said who they are. */
    fun person(view: PairingView?, people: List<Person>): Person? = view?.person?.let { id -> people.firstOrNull { it.id == id } }
}

object LinkCardText {
    /** The plan's times shown at most. */
    private const val TIMES = 3

    /** What [r] asks, by code: one sentence from its typed fields, with [name] for whose Robot sent it. */
    fun summary(r: LinkRequest, name: Phrase, zone: ZoneId): Phrase = when (val b = r.body) {
        is FbAsk -> {
            val from = Instant.ofEpochSecond(b.from).atZone(zone)
            val to = Instant.ofEpochSecond(b.to).atZone(zone)
            LinkStrings.askFreeBusy("name" to name, "from_day" to from.toLocalDate(), "from_time" to from.toLocalTime(),
                "to_day" to to.toLocalDate(), "to_time" to to.toLocalTime())
        }
        is EvPropose -> LinkStrings.askEvent("name" to name, "times" to times(b.event, zone))
        is EvChange -> LinkStrings.askEventChange("name" to name, "times" to times(b.event, zone))
        is SpRequest -> LinkStrings.askSplit("name" to name)
        is FileOffer -> LinkStrings.askFile("name" to name)
        is FileAsk -> LinkStrings.askFileRequest("name" to name)
        is CardOffer -> LinkStrings.askContact("name" to name)
        is EtaTell -> LinkStrings.askEta(b.minutes, "name" to name)
        is RemOffer -> Instant.ofEpochSecond(b.whenAt).atZone(zone).let { t -> LinkStrings.askReminder("name" to name, "day" to t.toLocalDate(), "time" to t.toLocalTime()) }
        is QAsk -> LinkStrings.askQuestion("name" to name)
        is FoldJoin -> LinkStrings.askFolder("name" to name)
        is LsOffer -> LinkStrings.askList("name" to name)
        else -> LinkStrings.askOther("name" to name)
    }

    private fun times(e: EventFields, zone: ZoneId): Phrase {
        val each = e.times.take(TIMES).map { t -> Instant.ofEpochSecond(t).atZone(zone).let { z -> LinkStrings.time("day" to z.toLocalDate(), "time" to z.toLocalTime()) } }
        return Phrase.ListOf(each, app.heyrobot.core.i18n.ListKind.OR)
    }

    /**
     * The other person's own words in [r] (titles, places, notes, questions, memos, file names, contact names), each one line
     * or a few, cleaned for the screen (ScreenText: no controls or invisible characters): shown in the framed box, never in
     * Robot's sentence, never to the model except inside PeerQuote's frame.
     */
    fun theirWords(r: LinkRequest): List<String> {
        val raw: List<String?> = when (val b = r.body) {
            is EvPropose -> listOf(b.event.title, b.event.place, b.event.note)
            is EvChange -> listOf(b.event.title, b.event.place, b.event.note)
            is SpRequest -> listOf(b.note) + b.items.map { it.text }
            is FileOffer -> listOf(b.name)
            is FileAsk -> listOf(b.what)
            is CardOffer -> b.card?.let { c -> listOf(c.name) + c.phones + c.emails + c.xmpp }.orEmpty() // F61: all that Add to People would store
            is EtaTell -> listOf(LinkAsk.place(b.place))
            is QReply -> listOf(b.text)
            is RemOffer -> listOf(b.text)
            is QAsk -> listOf(b.question)
            is EvDecline -> listOf(b.note)
            is EvCancel -> listOf(b.note)
            is FoldJoin -> listOf(b.name)
            is LsOffer -> listOf(b.name)
            else -> emptyList()
        }
        return raw.mapNotNull { s -> s?.let { ScreenText.sanitize(Invisible.strip(it)).trim() }?.takeIf { it.isNotEmpty() } }
    }

    /** Robot's proposal for [cap] from [name], or null when it has none to make (loosening is your tap). */
    fun proposal(cap: LinkCapability, name: Phrase, hours: EventAlone.Hours): Phrase? = when (cap) {
        LinkCapability.EVENTS -> LinkStrings.proposeEvents("name" to name, "from" to hours.start, "to" to hours.end)
        LinkCapability.FREE_BUSY -> LinkStrings.proposeFreeBusy("name" to name)
        LinkCapability.ETA -> LinkStrings.proposeEta("name" to name)
        LinkCapability.REMINDERS -> LinkStrings.proposeReminders("name" to name)
        LinkCapability.FILES -> LinkStrings.proposeFiles("name" to name)
        LinkCapability.SPLITS -> LinkStrings.proposeSplits("name" to name)
        LinkCapability.FILE_REQUESTS, LinkCapability.QUESTIONS -> LinkStrings.proposeShared("name" to name)
        else -> null
    }

    /** What became of an entry of yours, as Requests says it. */
    fun state(s: DeskState, name: Phrase): Phrase = when (s) {
        DeskState.HANDLED -> LinkStrings.handledOne()
        DeskState.ANSWERED -> LinkStrings.answered()
        DeskState.ALREADY_HAVE -> LinkStrings.alreadyHave()
        DeskState.DECLINED -> LinkStrings.declined()
        DeskState.EXPIRED -> LinkStrings.expired()
        DeskState.UNDONE -> LinkStrings.undone()
        DeskState.SENT -> LinkStrings.sent()
        DeskState.DELIVERED -> LinkStrings.delivered()
        DeskState.WAITING_FOR_THEM -> LinkStrings.waitingFor("name" to name)
        DeskState.ANSWERED_BY_THEM -> LinkStrings.answeredByThem()
        DeskState.DECLINED_BY_THEM -> LinkStrings.declinedByThem()
        DeskState.COULDNT_REACH -> LinkStrings.couldntReach()
        DeskState.NOT_NOW -> LinkStrings.notNow()
        DeskState.REPLIED -> LinkStrings.replied()
    }

    /** A card's yes button, or null for none. */
    /** L8: where a file sent or received stands, for Sent and received. */
    fun transfer(v: TransferView): Phrase = when (v.state) {
        XferState.OFFERED -> LinkStrings.xferOffered()
        XferState.SENDING -> LinkStrings.xferSending()
        XferState.SENT -> LinkStrings.xferSent()
        XferState.ACCEPTED, XferState.RECEIVING -> LinkStrings.xferComing()
        XferState.ARRIVED -> LinkStrings.xferWaiting()
        XferState.FILED -> LinkStrings.xferFiled()
        XferState.DECLINED, XferState.EXPIRED, XferState.FAILED -> if (v.out) LinkStrings.xferNotSent() else LinkStrings.xferNotReceived()
    }

    fun yes(y: DeskYes?): Phrase? = when (y) {
        DeskYes.GOT_IT -> LinkStrings.gotIt()
        DeskYes.ACCEPT -> LinkStrings.accept()
        DeskYes.SEND_BUSY -> LinkStrings.sendBusy()
        DeskYes.ADD_EVENT -> LinkStrings.addToCalendar()
        DeskYes.MOVE_EVENT -> LinkStrings.moveIt()
        DeskYes.ADD_REMINDER -> LinkStrings.addReminder()
        DeskYes.ADD_CONTACT -> LinkStrings.addContact()
        DeskYes.SEND_FILE -> LinkStrings.sendFile()
        null -> null
    }

    /**
     * L7: their Robot's answer [r] to something of yours, by code from its typed fields ([name]: your People name for them),
     * in your time zone; null for a type that isn't an answer. A decline's or a cancel's note is their words: [theirWords].
     */
    fun reply(r: LinkRequest, name: Phrase, zone: ZoneId): Phrase? = when (val b = r.body) {
        is FbReply -> {
            val from = Instant.ofEpochSecond(b.from).atZone(zone)
            val to = Instant.ofEpochSecond(b.to).atZone(zone)
            val span = arrayOf<Pair<String, Any>>("from_day" to from.toLocalDate(), "from_time" to from.toLocalTime(), "to_day" to to.toLocalDate(), "to_time" to to.toLocalTime())
            if (b.busy.isEmpty()) LinkStrings.fbFree("name" to name, *span)
            else {
                val blocks = "blocks" to Phrase.ListOf(b.busy.take(BLOCKS).map { block(it, zone) })
                if (b.busy.size > BLOCKS) LinkStrings.fbBusyMore(b.busy.size - BLOCKS, "name" to name, blocks, *span) else LinkStrings.fbBusy("name" to name, blocks, *span)
            }
        }
        is EvAccept -> Instant.ofEpochSecond(b.time).atZone(zone).let { t -> LinkStrings.evAccepted("name" to name, "day" to t.toLocalDate(), "time" to t.toLocalTime()) }
        is EvDecline -> LinkStrings.evDeclined("name" to name)
        is EvCancel -> LinkStrings.evCancelled("name" to name)
        is QReply -> if (b.file != null) LinkStrings.qFileAnswer("name" to name) else LinkStrings.qAnswered("name" to name)
        else -> null
    }

    /** L10: what Robot did on its own with [r] from [name], said in the panel (an ETA, a reminder into your brief); null otherwise. */
    fun handled(r: LinkRequest, name: Phrase, zone: ZoneId): Phrase? = when (val b = r.body) {
        is EtaTell -> LinkStrings.askEta(b.minutes, "name" to name)
        is RemOffer -> LinkStrings.handledReminder("name" to name, "day" to Instant.ofEpochSecond(b.whenAt).atZone(zone).toLocalDate())
        else -> null
    }

    /** L10: ask_robot's card for an ETA: exactly what goes (minutes and a place word, never a location). */
    fun toEta(e: EtaTell, name: Phrase): Phrase =
        if (e.place == null) LinkStrings.toEta(e.minutes, "name" to name) else LinkStrings.toEtaPlace(e.minutes, "name" to name, "place" to e.place)

    /** L10: ask_robot's card for a reminder: its words and when. */
    fun toReminder(r: RemOffer, name: Phrase, zone: ZoneId): Phrase = Instant.ofEpochSecond(r.whenAt).atZone(zone).let { t ->
        LinkStrings.toReminder("name" to name, "day" to t.toLocalDate(), "time" to t.toLocalTime(), "text" to r.text)
    }

    /** L10: ask_robot's card for a question. */
    fun toQuestion(q: QAsk, name: Phrase): Phrase = LinkStrings.toQuestion("name" to name, "q" to q.question)

    /** L10: ask_robot's card for a contact card: whose, and every number and address that goes. */
    fun toContact(c: ContactCard, name: Phrase): Phrase =
        LinkStrings.toContact("name" to name, "who" to c.name, "fields" to (c.phones + c.emails + c.xmpp).joinToString(", "))

    /** Busy blocks said at most: a longer list says how many more, and never that the rest is free. */
    private const val BLOCKS = 8

    private fun block(s: Span, zone: ZoneId): Phrase {
        val a = Instant.ofEpochSecond(s.start).atZone(zone); val b = Instant.ofEpochSecond(s.end).atZone(zone)
        if (b.toLocalDate() != a.toLocalDate()) return LinkStrings.blockDays("day" to a.toLocalDate(), "from" to a.toLocalTime(), "to_day" to b.toLocalDate(), "to" to b.toLocalTime())
        return LinkStrings.block("day" to a.toLocalDate(), "from" to a.toLocalTime(), "to" to b.toLocalTime())
    }

    /** L7: ask_robot's card for free/busy: exactly what goes, and that only busy times come back. */
    fun toFreeBusy(ask: FbAsk, name: Phrase, zone: ZoneId): Phrase {
        val from = Instant.ofEpochSecond(ask.from).atZone(zone)
        val to = Instant.ofEpochSecond(ask.to).atZone(zone)
        return LinkStrings.toFreeBusy("name" to name, "from_day" to from.toLocalDate(), "from_time" to from.toLocalTime(), "to_day" to to.toLocalDate(),
            "to_time" to to.toLocalTime())
    }

    /** L7: ask_robot's card for a plan: its title (your words) and its times. */
    fun toPlan(e: EventFields, name: Phrase, zone: ZoneId): Phrase = LinkStrings.toPlan("name" to name, "title" to e.title, "times" to times(e, zone))

    /** L7: ask_robot's card for cancelling plan [p]. */
    fun toCancel(p: LinkPlan, name: Phrase, zone: ZoneId): Phrase =
        Instant.ofEpochSecond(if (p.time > 0) p.time else p.event.times.first()).atZone(zone).let { t ->
            LinkStrings.toCancel("name" to name, "title" to p.event.title, "day" to t.toLocalDate(), "time" to t.toLocalTime())
        }
}
