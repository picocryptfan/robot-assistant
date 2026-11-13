// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DevicePresence
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime

// Reminders tied to a moment (docs/plans/moment-reminders-plan.md): "remind me to take the groceries in when I get out of
// the car", « quand je branche mon téléphone, rappelle-moi de sortir le recyclage ». One-off: it goes off once, on the
// triggers routines already listen for (RoutineHub), then it is gone. Kept in routines/moments.json, its own file.

/** The moment a reminder waits for: a routine trigger's event, once. */
enum class Moment(val id: String, val kind: TriggerKind) {
    CAR_OFF("car_off", TriggerKind.DEVICE), CAR_ON("car_on", TriggerKind.DEVICE),
    HEADSET_OFF("headset_off", TriggerKind.DEVICE), HEADSET_ON("headset_on", TriggerKind.DEVICE),
    CHARGING("charging", TriggerKind.CHARGING), UNPLUGGED("unplugged", TriggerKind.CHARGING),
    UNLOCK("unlock", TriggerKind.UNLOCK);

    /** The remembered device it needs, or null. */
    val device: DeviceKind? get() = when (this) {
        CAR_OFF, CAR_ON -> DeviceKind.CAR
        HEADSET_OFF, HEADSET_ON -> DeviceKind.HEADSET
        else -> null
    }

    companion object {
        fun of(id: String?): Moment? = entries.firstOrNull { it.id == id }

        /** The moment of a routine [trigger] read from your words, or null (a time or an alarm stays a routine's). */
        fun of(trigger: RoutineTrigger): Moment? = when (trigger) {
            is RoutineTrigger.Device -> when (trigger.type) {
                DeviceKind.CAR -> if (trigger.connect) CAR_ON else CAR_OFF
                DeviceKind.HEADSET -> if (trigger.connect) HEADSET_ON else HEADSET_OFF
            }
            is RoutineTrigger.Charging -> if (trigger.start) CHARGING else UNPLUGGED
            is RoutineTrigger.Unlock -> UNLOCK
            else -> null
        }
    }
}

/** One reminder for a [moment]: [text] is your words; [between]: only then ("when I plug in tonight": 18:00 to 4:00). */
/** [heldUntil]: the car's wait, kept in the file so the phone's sleep or a restart doesn't lose it (fix-moment-reminders). */
data class MomentReminder(val id: String, val text: String, val moment: Moment, val created: Long, val between: TimeWindow? = null,
    val heldUntil: Long? = null)

object MomentReminders {
    const val VERSION = 1
    const val MAX = 20
    const val MAX_TEXT = BriefReminders.MAX_TEXT
    /** Out of the car for about 2 minutes, so a red light or a stall doesn't count (DevicePresence waits its 30 s first). */
    const val CAR_WAIT_MS = 120_000L

    /** [r] added to [list]: the same words for the same moment once, the newest [MAX] kept. */
    fun add(list: List<MomentReminder>, r: MomentReminder): List<MomentReminder> {
        val t = BriefText.clean(r.text, MAX_TEXT)
        if (t.isEmpty()) return list
        val same = list.filterNot { it.moment == r.moment && it.text.equals(t, ignoreCase = true) }
        return (same + r.copy(text = t)).takeLast(MAX)
    }

    fun remove(list: List<MomentReminder>, id: String): List<MomentReminder> = list.filterNot { it.id == id }

    /** The trigger kinds [list] waits for (RoutineHub listens for them as for routines'). */
    fun kinds(list: List<MomentReminder>): Set<TriggerKind> = list.map { it.moment.kind }.toSet()

    /** The moment [event] is, or null. */
    fun momentOf(event: TriggerEvent): Moment? = when (event) {
        is TriggerEvent.Device -> when (event.type) {
            DeviceKind.CAR -> if (event.connected) Moment.CAR_ON else Moment.CAR_OFF
            DeviceKind.HEADSET -> if (event.connected) Moment.HEADSET_ON else Moment.HEADSET_OFF
        }
        is TriggerEvent.Power -> if (event.charging) Moment.CHARGING else Moment.UNPLUGGED
        TriggerEvent.Unlocked -> Moment.UNLOCK
        else -> null
    }

    /** How long after its event a moment waits before it goes off: the car's 2 minutes, counted from the link dropping. */
    fun holdMs(m: Moment): Long = if (m == Moment.CAR_OFF) CAR_WAIT_MS - DevicePresence.CAR_GRACE_MS else 0L

    /** What one event does: [now] goes off at once; [held] waits, by id, until its time (ms). */
    data class Step(val now: List<MomentReminder>, val held: Map<String, Long>)

    /**
     * [event] at [now] with [held] waiting: the reminders it is the moment of go off (the car's wait first); the car
     * back within its wait keeps them, so a red light never sets one off.
     */
    fun onEvent(list: List<MomentReminder>, event: TriggerEvent, now: ZonedDateTime, held: Map<String, Long> = emptyMap(),
        already: Boolean = false): Step {
        val m = momentOf(event) ?: return Step(emptyList(), held)
        val ms = now.toInstant().toEpochMilli()
        var keep = held.filterKeys { id -> list.any { it.id == id } }
        if (m == Moment.CAR_ON) keep = keep.filterKeys { id -> list.firstOrNull { it.id == id }?.moment != Moment.CAR_OFF }
        // [already]: a device that was connected when Robot started listening wasn't put on now, so it waits for the next time.
        val due = if (already && (event as? TriggerEvent.Device)?.connected == true) emptyList()
            else list.filter { it.moment == m && it.created <= ms && it.between?.contains(now.toLocalTime()) != false }
        val wait = holdMs(m)
        if (wait == 0L) return Step(due, keep)
        return Step(emptyList(), keep + due.filter { it.id !in keep }.associate { it.id to ms + wait })
    }

    /** At [nowMs], the held reminders whose wait is over go off; the rest keep waiting. */
    fun ripe(list: List<MomentReminder>, held: Map<String, Long>, nowMs: Long): Step {
        val (done, wait) = held.entries.partition { it.value <= nowMs }
        val ids = done.map { it.key }.toSet()
        return Step(list.filter { it.id in ids }, wait.associate { it.key to it.value }.filterKeys { id -> list.any { it.id == id } })
    }

    /** The car's waits as the file keeps them (id to when). */
    fun held(list: List<MomentReminder>): Map<String, Long> = list.mapNotNull { r -> r.heldUntil?.let { r.id to it } }.toMap()

    /** [step] written into [list]: what went off leaves it, the others keep [step]'s waits (or none). */
    fun apply(list: List<MomentReminder>, step: Step): List<MomentReminder> {
        val gone = step.now.map { it.id }.toSet()
        return list.filterNot { it.id in gone }.map { it.copy(heldUntil = step.held[it.id]) }
    }

    /** Each reminder's own notice (the tag, with LockShade.MOMENT_ID): its id, so two that go off together never replace each other. */
    fun noticeTag(r: MomentReminder): String = "moment:" + r.id

    /** When [held]'s next wait ends, or null. */
    fun nextRipe(held: Map<String, Long>): Long? = held.values.minOrNull()

    /** Whether [m] can ever go off: a car or headphones moment needs one Robot remembers ([memory], devices.json). */
    fun canFire(m: Moment, memory: DeviceMemory?): Boolean {
        val d = m.device ?: return true
        return memory?.devices?.any { it.role.id == d.id } == true
    }

    /** "when you get out of the car", in the words of the card and Settings › Routines. */
    fun phrase(r: MomentReminder): Phrase = when (r.moment) {
        Moment.CAR_OFF -> RoutineStrings.momentCarOff()
        Moment.CAR_ON -> RoutineStrings.momentCarOn()
        Moment.HEADSET_OFF -> RoutineStrings.momentHeadsetOff()
        Moment.HEADSET_ON -> RoutineStrings.momentHeadsetOn()
        Moment.CHARGING -> if (r.between == TimeWindow.NIGHT) RoutineStrings.momentChargingNight() else RoutineStrings.momentCharging()
        Moment.UNPLUGGED -> RoutineStrings.momentUnplugged()
        Moment.UNLOCK -> RoutineStrings.momentUnlock()
    }

    /** The card's line when [m] can't go off yet, or null. */
    fun cantFire(m: Moment, memory: DeviceMemory?): Phrase? = if (canFire(m, memory)) null else when (m.device) {
        DeviceKind.CAR -> RoutineStrings.momentNoCar()
        else -> RoutineStrings.momentNoHeadset()
    }

    fun encode(list: List<MomentReminder>): String = JSONObject().put("v", VERSION).put("moments", JSONArray().apply {
        for (r in list) put(JSONObject().put("id", r.id).put("text", r.text).put("moment", r.moment.id).put("created", r.created).apply {
            r.between?.let { put("from", it.from.toString()).put("to", it.to.toString()) }
            r.heldUntil?.let { put("held", it) }
        })
    }).toString()

    /** A moments.json from a newer Robot: never written over ([RoutineStore.editMoments] refuses). */
    fun newer(json: String?): Boolean = try {
        ((JSONObject(json ?: return false).opt("v") as? Number)?.toInt() ?: 0) > VERSION
    } catch (_: Exception) { false }

    /** The reminders that can be read; a damaged or newer file gives none, an unknown moment is left out. */
    fun decode(json: String?): List<MomentReminder> = try {
        val o = JSONObject(json ?: return emptyList())
        if ((o.opt("v") as? Number)?.toInt() != VERSION) return emptyList()
        val a = o.getJSONArray("moments")
        (0 until a.length()).mapNotNull { i -> entry(a.optJSONObject(i)) }.takeLast(MAX)
    } catch (_: Exception) { emptyList() }

    /** moments-backup-c (L4): a backup's moments.json read whole, or null when any entry is damaged or there are more than [MAX]. */
    fun decodeAll(json: String?): List<MomentReminder>? = try {
        val o = JSONObject(json ?: return null)
        if ((o.opt("v") as? Number)?.toInt() != VERSION) return null
        val a = o.getJSONArray("moments")
        if (a.length() > MAX) return null
        (0 until a.length()).map { i -> entry(a.optJSONObject(i), strict = true) ?: return null }
    } catch (_: Exception) { null }

    /** One entry, or null when damaged; [strict]: a time window that doesn't read is damage too (else it's left off). */
    private fun entry(r: JSONObject?, strict: Boolean = false): MomentReminder? {
        r ?: return null
        val id = RoutineCodec.str(r, "id")?.takeIf { it.isNotBlank() } ?: return null
        val text = RoutineCodec.str(r, "text")?.let { BriefText.clean(it, MAX_TEXT) }?.takeIf { it.isNotEmpty() } ?: return null
        val m = Moment.of(RoutineCodec.str(r, "moment")) ?: return null
        val f = RoutineCodec.str(r, "from"); val t = RoutineCodec.str(r, "to")
        val between = try {
            if (f != null && t != null) TimeWindow(LocalTime.parse(f), LocalTime.parse(t)) else null
        } catch (_: Exception) { null }
        if (strict && (f != null || t != null) && between == null) return null
        return MomentReminder(id, text, m, RoutineCodec.long(r, "created") ?: 0L, between, RoutineCodec.long(r, "held"))
    }
}
