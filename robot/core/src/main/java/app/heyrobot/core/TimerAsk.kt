// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// Pure. fix-everyday-e (everyday-1.2.2-a, finding 4): "how much time is left on my timer" went to the model, which can't
// see a clock app's timers, and said so. Android gives an app no way to read the timers it asked a clock app to set, so
// Robot keeps its own record of the ones it set (when, for how long) and answers from it, without the model. A timer set
// in the clock app itself is never known, and Robot says so rather than guess.

/**
 * A timer Robot asked your clock app to set at [at] (ms since the epoch), for [seconds]. [cancelAsked]: you said "cancel
 * the timer" while it ran, and Robot opened your clock's timers; whether you cancelled it there, Robot can't see.
 */
data class RobotTimer(val at: Long, val seconds: Long, val cancelAsked: Boolean = false) {
    val ends: Long get() = at + seconds * 1000
}

object TimerAsk {
    /** A timer that ran out more than this long ago is forgotten. */
    const val KEEP_MS = 12 * 3_600_000L
    const val MAX = 20

    fun encode(timers: List<RobotTimer>): String = JSONArray().also { a ->
        timers.forEach { a.put(JSONObject().put("at", it.at).put("s", it.seconds).put("c", it.cancelAsked)) }
    }.toString()

    fun decode(json: String?): List<RobotTimer> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            RobotTimer(o.getLong("at"), o.getLong("s"), o.optBoolean("c", false)).takeIf { it.seconds > 0 }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** [timers] still worth knowing at [now]: running, or run out within [KEEP_MS] (none set in the future: a clock change). */
    fun kept(timers: List<RobotTimer>, now: Long): List<RobotTimer> =
        timers.filter { now - it.ends < KEEP_MS && it.at <= now + 60_000 }.takeLast(MAX)

    /** [timers] with one more, of [seconds], set at [now]. */
    fun added(timers: List<RobotTimer>, now: Long, seconds: Long): List<RobotTimer> = kept(timers, now) + RobotTimer(now, seconds)

    /** "Cancel the timer" at [now]: the running ones are marked, since Robot can't see whether you cancelled them. */
    fun cancelAsked(timers: List<RobotTimer>, now: Long): List<RobotTimer> =
        kept(timers, now).map { if (it.ends > now) it.copy(cancelAsked = true) else it }

    /** The ones still running at [now], the first to end first. */
    fun running(timers: List<RobotTimer>, now: Long): List<RobotTimer> = kept(timers, now).filter { it.ends > now }.sortedBy { it.ends }

    /** A clock rings a timer that ran out until you stop it: one of [timers] that ran out this long ago may still ring. */
    const val RING_MS = 30 * 60_000L

    /** One of [timers] ran out at most [RING_MS] before [now], so it may be ringing. */
    fun mayRing(timers: List<RobotTimer>, now: Long): Boolean = kept(timers, now).any { it.ends <= now && now - it.ends < RING_MS }

    /** How long [t] has left at [now], in whole seconds; from 10 minutes up, in whole minutes (rounded up). */
    fun left(t: RobotTimer, now: Long): Long {
        val s = (t.ends - now + 999) / 1000
        return if (s >= 600) (s + 59) / 60 * 60 else s
    }

    private fun clock(ms: Long, zone: ZoneId): LocalTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)

    /** What [t] has left, as "Your timer for 5 minutes has 3 minutes left. It ends at 3:09." */
    fun leftLine(t: RobotTimer, now: Long, zone: ZoneId): Phrase =
        (if (t.cancelAsked) ToolStrings.timerLeftUnless else ToolStrings.timerLeft)(
            "duration" to Arg.Seconds(t.seconds), "left" to Arg.Seconds(left(t, now)), "time" to Arg.Clock(clock(t.ends, zone)))

    /** "How much time is left on my timer", answered from the timers Robot set ([timers]) at [now]. */
    fun answer(timers: List<RobotTimer>, now: Long, zone: ZoneId): Phrase {
        val on = running(timers, now)
        if (on.isNotEmpty()) return Phrase.Join(on.take(3).map { leftLine(it, now, zone) })
        val last = kept(timers, now).maxByOrNull { it.ends } ?: return ToolStrings.timerNoneSet()
        return ToolStrings.timerRanOut("duration" to Arg.Seconds(last.seconds), "time" to Arg.Clock(clock(last.ends, zone)))
    }
}
