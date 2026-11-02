// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.NetHint
import app.heyrobot.core.link.requests.OfferKind
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.requests.Span
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1 to §4.4, §4.7, D8, D14, D17, D18, Q3; job L1): who may ask
// what of your Robot, and what happens to each request. The executive-assistant model of the user's decision of 12 Oct:
// a level per person and capability (Answer on its own, Ask me first, Off), presets at pairing, limits that stay even
// under Answer on its own, and the lock rules. Nothing here sends, shows or stores anything: `:link` and `:voice` (L3, L6)
// act on what it decides.
//
// The rules this file pins (LinkPolicyTest):
//   - Nothing starts at Answer on its own; loosening a level is always a tap, never a spoken yes; tightening is at once.
//   - Money and contact cards are never answered on their own (payment types are reserved until after the full test, but
//     their capability is here so the rule is pinned before any code can loosen it).
//   - Free/busy replies carry busy blocks only, on 30-minute steps, never outside the window asked.
//   - Events on their own: inside your working hours (9:00 to 21:00 by default), only when free, at most 3 a day per person.
//   - Locked (Q3's default, "a"): what you set to Answer on its own still answers (free/busy, events in limits, reminders,
//     ETA, splits); files are received and sealed, then filed at unlock; everything else waits sealed until you unlock.
//     With Q3's other answer, everything waits. Sending, pairing, cards and levels: never while locked. During a phone
//     lock, nothing is sent by you and no card opens; answers on their own continue.

/** What another Robot may ask for, each with its own level per person (§4.1). */
enum class LinkCapability(
    /** Whether Answer on its own may ever be set. */
    val autoAllowed: Boolean,
    /** Under Q3's default, whether Answer on its own still answers while the phone is locked. */
    val autoWhileLocked: Boolean,
    /** Whether a spoken yes may answer its card (panel open, phone unlocked, §4.2). */
    val spokenYes: Boolean,
) {
    FREE_BUSY(true, true, true),
    EVENTS(true, true, true),
    SPLITS(true, true, false),
    /** Reserved: payment requests come after the full test (the user, 21 Oct). Always a tap, never on its own. */
    PAYMENTS(false, false, false),
    FILES(true, false, false),
    FILE_REQUESTS(true, false, false),
    FOLDERS(true, false, false),
    CONTACT_CARDS(false, false, false),
    ETA(true, true, true),
    REMINDERS(true, true, true),
    QUESTIONS(true, false, false),
    LISTS(true, false, false),
}

/** A person's level for one capability, from strictest to loosest. */
enum class LinkLevel { OFF, ASK, AUTO }

/** D8: what a new pairing starts with. */
enum class LinkPreset { FAMILY_OR_FRIEND, SOMEONE_ELSE }

/** What becomes of one incoming request. */
enum class Handling {
    /** Answered or done on its own: no card; listed in "Robot handled 3 things", with Undo where it can be undone. */
    ANSWER_ALONE,
    /** A card: in the panel if it's open, else a notification that opens it. */
    CARD,
    /** Kept sealed until you unlock (robot_inbox_v1), then a card or "Robot: 2 requests waiting". */
    WAIT,
    /** A file accepted on its own while locked: received and sealed (format 2), filed at unlock (§3.6). */
    RECEIVE_AND_SEAL,
    /** Off for this person: answered `declined`. */
    DECLINE,
    /** Pairing, control, answers to your own requests and updates to things you already have: handled by code. */
    CODE,
}

/** How a type is decided. */
enum class Flow {
    /** Pairing and control: code, whatever the levels. */
    CONTROL,
    /** An answer to a request you sent (matched by the outbox): code. */
    ANSWER,
    /** A new request: the person's level for its capability decides. */
    ASK,
    /** Always your tap first, whatever the level: joining a folder or a list, lowering the mode. */
    ALWAYS_CARD,
    /** A change to a shared folder or list you joined: applied on its own unless the level is Off. */
    SYNC,
    /** An end to something you already have (a cancelled event, a settled split, someone leaving): applied by code. */
    UPDATE,
}

/** The phone's state when a request arrives. */
data class LinkContext(
    val locked: Boolean,
    /** Q3: answer on their own while locked (default yes). */
    val answerWhileLocked: Boolean = true,
    /** Phone lock (PhoneLockNow.on): no card opens. */
    val phoneLock: Boolean = false,
    /** The request is inside the capability's limits that stay ([LinkPolicy]'s checks); outside them, a card instead. */
    val withinLimits: Boolean = true,
)

object LinkPolicy {
    fun flow(type: RequestType): Flow = when (type) {
        RequestType.CAPS, RequestType.NET_HINT, RequestType.MODE_RAISE, RequestType.UNPAIR -> Flow.CONTROL
        RequestType.ACK, RequestType.FB_REPLY, RequestType.EV_ACCEPT, RequestType.EV_DECLINE, RequestType.SP_REPLY,
        RequestType.FILE_ACCEPT, RequestType.FILE_KEY, RequestType.FILE_DECLINE, RequestType.FILE_DONE, RequestType.Q_REPLY -> Flow.ANSWER
        RequestType.MODE_LOWER, RequestType.FOLD_JOIN, RequestType.LS_OFFER -> Flow.ALWAYS_CARD
        RequestType.FOLD_OP, RequestType.LS_OP -> Flow.SYNC
        RequestType.EV_CANCEL, RequestType.SP_SETTLE, RequestType.FOLD_LEAVE, RequestType.LS_LEAVE, RequestType.GRP_STATUS -> Flow.UPDATE
        RequestType.FB_ASK, RequestType.EV_PROPOSE, RequestType.EV_CHANGE, RequestType.SP_REQUEST, RequestType.FILE_OFFER,
        RequestType.FILE_ASK, RequestType.CARD_OFFER, RequestType.ETA_TELL, RequestType.REM_OFFER, RequestType.Q_ASK -> Flow.ASK
    }

    /** What becomes of an incoming request of [type] from a person whose level for its capability is [level]. */
    fun decide(type: RequestType, level: LinkLevel, ctx: LinkContext): Handling {
        val cap = type.capability
        val asking = ctx.locked || ctx.phoneLock
        return when (flow(type)) {
            Flow.CONTROL, Flow.ANSWER, Flow.UPDATE -> Handling.CODE
            Flow.ALWAYS_CARD -> when {
                cap != null && level == LinkLevel.OFF -> Handling.DECLINE
                asking -> Handling.WAIT
                else -> Handling.CARD
            }
            Flow.SYNC -> when {
                level == LinkLevel.OFF -> Handling.DECLINE
                ctx.locked -> Handling.WAIT
                else -> Handling.ANSWER_ALONE
            }
            Flow.ASK -> {
                checkNotNull(cap)
                when {
                    level == LinkLevel.OFF -> Handling.DECLINE
                    level == LinkLevel.ASK || !cap.autoAllowed || !ctx.withinLimits -> if (asking) Handling.WAIT else Handling.CARD
                    !ctx.locked -> Handling.ANSWER_ALONE
                    !ctx.answerWhileLocked -> Handling.WAIT
                    cap.autoWhileLocked -> Handling.ANSWER_ALONE
                    cap == LinkCapability.FILES -> Handling.RECEIVE_AND_SEAL
                    else -> Handling.WAIT
                }
            }
        }
    }

    /** The levels [cap] may be set to: Answer on its own only where it is allowed. */
    fun levels(cap: LinkCapability): List<LinkLevel> = if (cap.autoAllowed) LinkLevel.entries else listOf(LinkLevel.OFF, LinkLevel.ASK)

    /**
     * Whether [cap] may go from [from] to [to]: to a level it allows, and when that loosens it (Off to Ask, Ask to on its
     * own), only by a tap ([tap]), e.g. "Robot proposes the switch"'s Turn on; never by a spoken yes. Tightening is at once.
     */
    fun change(cap: LinkCapability, from: LinkLevel, to: LinkLevel, tap: Boolean): Boolean =
        to in levels(cap) && (to.ordinal <= from.ordinal || tap)

    /** D8. Nothing starts at Answer on its own. */
    fun preset(p: LinkPreset): Map<LinkCapability, LinkLevel> = LinkCapability.entries.associateWith { cap ->
        when (p) {
            LinkPreset.FAMILY_OR_FRIEND -> LinkLevel.ASK
            LinkPreset.SOMEONE_ELSE -> if (cap == LinkCapability.EVENTS || cap == LinkCapability.FILES || cap == LinkCapability.PAYMENTS) LinkLevel.ASK else LinkLevel.OFF
        }
    }

    /** D14: levels restored from a backup drop to Ask me first (Off stays Off). */
    fun restored(level: LinkLevel): LinkLevel = if (level == LinkLevel.AUTO) LinkLevel.ASK else level

    /** §4.2: a spoken yes may answer [type]'s card only for free/busy, events, reminders and ETA, with the panel open and the phone unlocked. */
    fun spokenYes(type: RequestType, panelOpen: Boolean, locked: Boolean): Boolean =
        panelOpen && !locked && flow(type) == Flow.ASK && type.capability?.spokenYes == true

    /**
     * A card's spoken yes ([yes]: what its Yes does): [spokenYes] for its type, and never a contact card's Add to People,
     * which is a tap whatever its capability says (§1.1).
     */
    fun spokenYes(type: RequestType, yes: DeskYes?, panelOpen: Boolean, locked: Boolean): Boolean =
        yes != null && yes != DeskYes.ADD_CONTACT && yes != DeskYes.SEND_FILE && spokenYes(type, panelOpen, locked)

    /** §4.4: you send to another Robot only unlocked, and not during a phone lock. */
    fun canSend(locked: Boolean, phoneLock: Boolean): Boolean = !locked && !phoneLock

    /** §2.3 and §4.4: pairing, a new code, the requests screen and levels need the phone unlocked. */
    fun canPair(locked: Boolean): Boolean = !locked

    /**
     * The addresses on a shared network a `net.hint` gives that a pairing in [mode] takes: none while Anonymous, which never
     * learns or uses them. L5-fix (deliberately changed from refusing the whole hint): the rest of the hint, its token
     * included, is still taken, so a hint from a side still Private never leaves a token unused.
     */
    fun hintAddrs(hint: NetHint, mode: LinkMode): List<LocalAddress> = if (mode == LinkMode.PRIVATE) hint.addrs else emptyList()

    // Limits that stay ---------------------------------------------------------------------------------------------------

    /** Files on their own: at most 50 MiB, never a dangerous type, within the person's 2 GiB a day (§4.1, §3.7). */
    fun fileAlone(size: Long, kind: OfferKind, bytesToday: SlidingSum, now: Long): Boolean =
        size <= LinkLimits.FILE_AUTO_BYTES && kind != OfferKind.DANGEROUS && bytesToday.room(now, size)

    /** Splits on their own: only under the amount you set, in its currency ([max] null: you set none, so a card). */
    fun splitAlone(share: Long, currency: String, max: Long?, maxCurrency: String?): Boolean = max != null && currency == maxCurrency && share <= max

    /** Reminders on their own: at most 3 a day from one person (never your calendar). */
    fun reminderAlone(today: SlidingSum, now: Long): Boolean = today.room(now)

    /** D17: file requests and delegated questions on their own only from what you shared with that person. */
    fun fromSharedAlone(foundInShared: Boolean): Boolean = foundInShared
}

/** §4.1: free/busy's answer, by code: busy blocks only, never titles, places or who. */
object FreeBusy {
    /**
     * The blocks to send for the window [from]..[to] from the [busy] times of the calendars you chose: each clipped to the
     * window and widened to 30-minute steps from its start (so a 10-minute meeting shows as half an hour), merged where they
     * touch. Nothing outside the window, ever.
     */
    fun blocks(from: Long, to: Long, busy: List<Span>): List<Span> {
        require(to > from)
        val step = RequestCaps.FB_STEP_SECONDS
        val snapped = busy.filter { it.end > from && it.start < to && it.end > it.start }.map { b ->
            val s = from + Math.floorDiv(maxOf(b.start, from) - from, step) * step
            val e = minOf(to, from + Math.floorDiv(minOf(b.end, to) - from + step - 1, step) * step)
            Span(s, e)
        }.sortedBy { it.start }
        val out = ArrayList<Span>()
        for (b in snapped) {
            val last = out.lastOrNull()
            if (last != null && b.start <= last.end) out[out.size - 1] = Span(last.start, maxOf(last.end, b.end)) else out += b
        }
        return out
    }
}

/** D18: accepting an event on its own. */
object EventAlone {
    /** "Working hours for events on their own": 9:00 to 21:00 by default. */
    data class Hours(val start: LocalTime = LocalTime.of(9, 0), val end: LocalTime = LocalTime.of(21, 0))

    /**
     * The first of the proposal's [times] (Unix seconds, [minutes] long) that starts and ends inside [hours] on one day in
     * [zone] and overlaps none of your [busy] times, when fewer than [LinkLimits.EVENTS_AUTO_PER_DAY] events from this
     * person were accepted on their own in the last day ([acceptedLastDay]); null otherwise (a card, never a guess).
     */
    fun pick(times: List<Long>, minutes: Long, zone: ZoneId, hours: Hours, busy: List<Span>, acceptedLastDay: Int): Long? {
        if (acceptedLastDay >= LinkLimits.EVENTS_AUTO_PER_DAY) return null
        for (t in times) {
            val end = t + minutes * 60
            val s = Instant.ofEpochSecond(t).atZone(zone)
            val e = Instant.ofEpochSecond(end).atZone(zone)
            if (s.toLocalDate() != e.toLocalDate()) continue
            if (s.toLocalTime() < hours.start || e.toLocalTime() > hours.end) continue
            if (busy.any { it.start < end && it.end > t }) continue
            return t
        }
        return null
    }
}
