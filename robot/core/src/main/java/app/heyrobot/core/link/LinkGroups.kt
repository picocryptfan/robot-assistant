// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.GrpPerson
import app.heyrobot.core.link.requests.GrpWho
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.Span
import java.time.Instant
import java.time.ZoneId

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1; job L11): a group plan. Up to 8 people, each paired with the organizer and
// with no one else in the group, so the organizer's Robot is the hub: it asks each for busy times, finds common free times by code,
// sends each their own `ev.propose` with the group's id, and says who's in. An answer only ever counts for the Robot that gave it.
//
// What the other side keeps of who's in ([GroupSeen]) is the organizer's word and nothing more: no one else in the group can be
// reached or checked. It's shown as that, never read to the model, and never touches People, pairings or your calendar.
//
//   group  "RGP" 1 | id 16 | state u8 | at u64 | done u64 | from u64 | to u64 | time u64 | cal u64 | rev u64 | minutes u16 | title | place
//          | note | count u8, each: pairing 16 | ask 16 (zeros) | plan 16 (zeros) | answer u8 | name | busy (u16 length)
//   seen   "RGS" 1 | grp 16 | pairing 16 | rev u64 | req 16 | at u64 | count u8, each: name | who u8
//   text   u16 length + UTF-8

enum class GroupState(val code: Int) {
    /** Busy times are coming in. */
    ASKING(1),
    /** Proposed to everyone, at [LinkGroup.time]. */
    PROPOSED(2),
    CANCELLED(3);

    companion object { fun of(code: Int): GroupState? = entries.firstOrNull { it.code == code } }
}

enum class GroupAnswer(val code: Int) {
    ASKED(1), KNOWN(2),
    /** Their Robot said no, or never answered in time: the common times leave them out. */
    WONT_SAY(3);

    companion object { fun of(code: Int): GroupAnswer? = entries.firstOrNull { it.code == code } }
}

class GroupMember(val pairing: String, val name: String, var ask: String? = null, var answer: GroupAnswer = GroupAnswer.ASKED, var busy: ByteArray? = null, var plan: String? = null) {
    override fun toString(): String = "GroupMember($answer)" // l10n: not text (toString for logs and tests)
}

class LinkGroup(
    val id: String, var state: GroupState, val title: String, val place: String?, val note: String?, val minutes: Long, val from: Long, val to: Long,
    val at: Long, var done: Long, val members: List<GroupMember>, var time: Long = 0, var cal: Long = 0, var rev: Long = 0,
) {
    override fun toString(): String = "LinkGroup($state, ${members.size})" // l10n: not text (toString for logs and tests)

    /** Without anyone's busy times or the requests that asked: for the screens and for a backup. */
    fun light() = LinkGroup(id, state, title, place, note, minutes, from, to, at, done, members.map { GroupMember(it.pairing, it.name, null, it.answer, null, it.plan) }, time, 0, rev)

    /** From main, for a new group: only your words and your people, none of what :link keeps for itself. */
    fun fresh() = LinkGroup(id, GroupState.ASKING, title, place, note, minutes, from, to, System.currentTimeMillis() / 1000, 0, members.map { GroupMember(it.pairing, it.name) })

    /** A proposal this group may make, and may take from main: its people, its window and its words all within the row's limits. */
    fun valid(): Boolean = LinkIds.isId(id) && members.size in 1..GroupLimits.OTHERS && members.map { it.pairing }.toSet().size == members.size &&
        members.all { LinkIds.isId(it.pairing) && LinkText.ok(it.name, RequestCaps.NAME) } && LinkText.ok(title, RequestCaps.TITLE) &&
        (place == null || LinkText.ok(place, RequestCaps.PLACE)) && (note == null || LinkText.ok(note, RequestCaps.NOTE, true)) &&
        minutes in RequestCaps.EVENT_MIN_MINUTES..RequestCaps.EVENT_MAX_MINUTES && FbAsk.windowOk(from, to)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'G'.code.toByte(), 'P'.code.toByte(), 1)
        private val ZERO = ByteArray(LinkIds.BYTES)
        const val MAX_BYTES = 16 * 1024

        private fun id(s: String) = DeskEntry.idBytes(s)
        private fun opt(s: String?) = s?.let(::id) ?: ZERO
        private fun optId(r: BinReader) = r.bytes(LinkIds.BYTES).let { if (LinkBytes.allZero(it)) null else LinkBytes.hex(it) }

        fun encode(g: LinkGroup): ByteArray {
            val w = BinWriter(512).bytes(MAGIC).bytes(id(g.id)).u8(g.state.code).u64(g.at).u64(g.done).u64(g.from).u64(g.to).u64(g.time).u64(g.cal).u64(g.rev).u16(g.minutes.toInt())
            GroupText.write(w, g.title); GroupText.write(w, g.place.orEmpty()); GroupText.write(w, g.note.orEmpty())
            w.u8(g.members.size)
            g.members.forEach { m ->
                w.bytes(id(m.pairing)).bytes(opt(m.ask)).bytes(opt(m.plan)).u8(m.answer.code)
                GroupText.write(w, m.name)
                val b = m.busy ?: ByteArray(0)
                w.u16(b.size).bytes(b)
            }
            return w.toByteArray()
        }

        fun decode(b: ByteArray): LinkGroup? = try {
            if (b.size > MAX_BYTES) null else BinReader(b).let { r -> read(r).also { r.end() } }.takeIf { it.valid() }
        } catch (_: RuntimeException) {
            null
        }

        private fun read(r: BinReader): LinkGroup {
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val gid = DeskEntry.id(r)
            val state = GroupState.of(r.u8()) ?: throw LinkBad
            val at = r.u64(); val done = r.u64(); val from = r.u64(); val to = r.u64(); val time = r.u64(); val cal = r.u64(); val rev = r.u64()
            val minutes = r.u16().toLong()
            val title = GroupText.read(r, RequestCaps.TITLE)
            val place = GroupText.read(r, RequestCaps.PLACE).ifEmpty { null }
            val note = GroupText.read(r, RequestCaps.NOTE).ifEmpty { null }
            val n = r.u8()
            if (n > GroupLimits.OTHERS) throw LinkBad
            val members = List(n) {
                val pairing = DeskEntry.id(r); val ask = optId(r); val plan = optId(r)
                val answer = GroupAnswer.of(r.u8()) ?: throw LinkBad
                val name = GroupText.read(r, RequestCaps.NAME)
                val busy = r.bytes(r.u16().also { if (it > GroupTimes.MASK_BYTES) throw LinkBad }).takeIf { it.isNotEmpty() }
                GroupMember(pairing, name, ask, answer, busy, plan)
            }
            return LinkGroup(gid, state, title, place, note, minutes, from, to, at, done, members, time, cal, rev)
        }
    }
}

object GroupLimits {
    /** The row's 8 people: you and these. */
    const val OTHERS = RequestCaps.GROUP_OTHERS
    const val MAX_GROUPS = 50
    const val KEEP_DAYS = 30L
}

internal object GroupText {
    fun write(w: BinWriter, s: String) { val b = s.toByteArray(); w.u16(b.size).bytes(b) }
    fun read(r: BinReader, max: Int): String {
        val n = r.u16()
        if (n > max * 4) throw LinkBad
        return LinkBytes.utf8(r.bytes(n))?.takeIf { it.codePointCount(0, it.length) <= max } ?: throw LinkBad
    }
}

/** The organizer's word on who's in, as the other side keeps it: for the screen only. */
class GroupSeen(val grp: String, val pairing: String, val rev: Long, val req: String, val at: Long, val who: List<GrpPerson>, val out: Int = 0, val waiting: Int = 0) {
    /** What the screen names: only who's in. Anyone else's name, whoever sent it, is a count. */
    val named: List<GrpPerson> get() = who.filter { it.who == GrpWho.IN }
    val outCount: Int get() = out + who.count { it.who == GrpWho.OUT }
    val waitingCount: Int get() = waiting + who.count { it.who == GrpWho.WAITING }

    override fun toString(): String = "GroupSeen(${who.size})" // l10n: not text (toString for logs and tests)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'G'.code.toByte(), 'S'.code.toByte(), 2)
        const val MAX_BYTES = 2 * 1024

        fun key(pairing: String, grp: String) = FolderRec.key(pairing, grp)

        fun encode(s: GroupSeen): ByteArray {
            val w = BinWriter(256).bytes(MAGIC).bytes(DeskEntry.idBytes(s.grp)).bytes(DeskEntry.idBytes(s.pairing)).u64(s.rev).bytes(DeskEntry.idBytes(s.req)).u64(s.at).u8(s.who.size)
            s.who.forEach { GroupText.write(w, it.name); w.u8(it.who.ordinal) }
            return w.u8(s.out).u8(s.waiting).toByteArray()
        }

        fun decode(b: ByteArray): GroupSeen? = try {
            if (b.size > MAX_BYTES) null else BinReader(b).let { r ->
                if (!MAGIC.take(3).all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
                val version = r.u8() // 1 kept no counts.
                if (version != 1 && version != 2) throw LinkBad
                val grp = DeskEntry.id(r); val pairing = DeskEntry.id(r); val rev = r.u64(); val req = DeskEntry.id(r); val at = r.u64()
                val n = r.u8()
                if (n > GroupLimits.OTHERS) throw LinkBad
                val who = List(n) { GrpPerson(GroupText.read(r, RequestCaps.NAME), GrpWho.entries.getOrNull(r.u8()) ?: throw LinkBad) }
                val out = if (version == 2) r.u8() else 0
                val waiting = if (version == 2) r.u8() else 0
                if (out > GroupLimits.OTHERS || waiting > GroupLimits.OTHERS) throw LinkBad
                GroupSeen(grp, pairing, rev, req, at, who, out, waiting).also { r.end() }
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** A plan you're in, as the screens see it: its own words from your plan with the organizer, and their word on who's in. */
class GroupSeenPlan(val seen: GroupSeen, val title: String, val time: Long)

/** A group as the screens see it: for yours, where each person stands and the common free times; for others', the organizer's word. */
class GroupsView(val mine: List<Pair<LinkGroup, GroupStanding>>, val seen: List<GroupSeenPlan>) {
    override fun toString(): String = "GroupsView(${mine.size}, ${seen.size})" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX_BYTES = 768 * 1024

        fun encode(v: GroupsView): ByteArray {
            val w = BinWriter(1024).u16(v.mine.size)
            v.mine.forEach { (g, s) ->
                val b = LinkGroup.encode(g)
                w.u16(b.size).bytes(b).u8(s.who.size)
                s.who.forEach { w.u8(it.ordinal) }
                w.u8(s.times.size)
                s.times.forEach { w.u64(it) }
                w.u8(if (s.calendar) 1 else 0)
            }
            w.u16(v.seen.size)
            v.seen.forEach { val b = GroupSeen.encode(it.seen); w.u16(b.size).bytes(b).u64(it.time); GroupText.write(w, it.title) }
            return w.toByteArray()
        }

        fun decode(b: ByteArray): GroupsView? = try {
            if (b.size > MAX_BYTES) null else BinReader(b).let { r ->
                val mine = List(r.u16()) {
                    val g = LinkGroup.decode(r.bytes(r.u16())) ?: throw LinkBad
                    val who = List(r.u8()) { GrpWho.entries.getOrNull(r.u8()) ?: throw LinkBad }
                    val times = List(r.u8()) { r.u64() }
                    g to GroupStanding(who, times, r.u8() != 0)
                }
                val seen = List(r.u16()) {
                    val s = GroupSeen.decode(r.bytes(r.u16())) ?: throw LinkBad
                    val t = r.u64()
                    GroupSeenPlan(s, GroupText.read(r, RequestCaps.TITLE), t)
                }
                GroupsView(mine, seen).also { r.end() }
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** [who]: each member's place, in order. [times]: common free times, while asking. */
class GroupStanding(val who: List<GrpWho>, val times: List<Long>, val calendar: Boolean = true)

object GroupRoster {
    /** Where a person stands: only ever from their own plan with you. */
    fun who(asking: Boolean, plan: LinkPlan?, active: Boolean): GrpWho = when {
        !active -> GrpWho.OUT
        plan == null -> if (asking) GrpWho.WAITING else GrpWho.OUT
        plan.state == PlanState.ACCEPTED -> GrpWho.IN
        plan.state == PlanState.PROPOSED -> GrpWho.WAITING
        else -> GrpWho.OUT
    }
}

object GroupTimes {
    /** 14 days of 30-minute steps. */
    const val MASK_BYTES = 84
    const val MAX_TIMES = 3
    /** The plan's default hours for events (§4.1), since no one has said otherwise for a time that suits a group. */
    const val FIRST_HOUR = 9
    const val LAST_HOUR = 21

    private const val STEP = RequestCaps.FB_STEP_SECONDS

    /** [busy] (blocks a reply carried, or your calendar's) as one bit per step from [from]. Anything outside the window is left out. */
    fun mask(from: Long, to: Long, busy: List<Span>): ByteArray {
        val steps = ((to - from) / STEP).toInt()
        val m = ByteArray((steps + 7) / 8)
        for (s in busy) {
            var i = ((s.start - from).coerceAtLeast(0) / STEP).toInt()
            val last = (s.end - from).coerceAtMost(to - from)
            while (i < steps && i * STEP < last) { m[i / 8] = (m[i / 8].toInt() or (1 shl (i % 8))).toByte(); i++ }
        }
        return m
    }

    private fun busyAt(m: ByteArray, i: Int) = i / 8 < m.size && (m[i / 8].toInt() shr (i % 8)) and 1 == 1

    /** Up to three times [minutes] long that no mask marks, each the first of its own day, inside the hours in [zone], from [now] on. */
    fun common(from: Long, to: Long, minutes: Long, masks: List<ByteArray>, zone: ZoneId, now: Long): List<Long> {
        val steps = ((to - from) / STEP).toInt()
        val need = ((minutes * 60 + STEP - 1) / STEP).toInt()
        val out = ArrayList<Long>()
        val days = HashSet<java.time.LocalDate>()
        var i = 0
        while (i + need <= steps && out.size < MAX_TIMES) {
            val t = from + i * STEP
            val start = Instant.ofEpochSecond(t).atZone(zone)
            val end = Instant.ofEpochSecond(t + minutes * 60).atZone(zone)
            val inside = start.hour >= FIRST_HOUR && start.toLocalDate() == end.toLocalDate() && (end.hour < LAST_HOUR || (end.hour == LAST_HOUR && end.minute == 0))
            if (t >= now && inside && start.toLocalDate() !in days && (i until i + need).none { s -> masks.any { busyAt(it, s) } }) {
                out += t
                days += start.toLocalDate()
            }
            i++
        }
        return out
    }
}
