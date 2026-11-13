// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.heyrobot.core.OwnTimer
import app.heyrobot.core.OwnTimerPhrases
import app.heyrobot.core.OwnTimers
import app.heyrobot.core.RLog
import app.heyrobot.core.TimerCommand
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TimerStrings
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.sys.OwnTimerAlarms
import app.heyrobot.sys.OwnTimerRing
import app.heyrobot.sys.OwnTimerStore
import app.heyrobot.sys.RobotTimers
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// named-timers: Robot's own timers in the panel, before the routines' phrases and the model (PanelRoutines.route). Setting
// is taken only while the switch is on; asking, cancelling and stopping while any own timer is kept, so one set before the
// switch went off can still be stopped. Answers come from the list on disk, never the model.
object OwnTimerPanel {
    fun route(ctx: Context, q: String): TimerCommand? = try {
        val now = System.currentTimeMillis()
        val l = OwnTimerStore.load(ctx, now)
        val on = OwnTimerStore.on(ctx)
        if (!on && l.isEmpty()) null
        else OwnTimerPhrases.route(q, l.map { it.name }, OwnTimers.ringing(l, now).map { it.name })?.takeIf { on || it !is TimerCommand.Set }
    } catch (t: Throwable) {
        RLog.w("own_timer_route", t)
        null
    }

    /** The model's set_timer while the switch is on and Robot can ring: done or not, and what to say; null: the clock app's. */
    fun forTool(ctx: Context, seconds: Long): Pair<Boolean, Phrase>? =
        if (!OwnTimerStore.on(ctx) || !OwnTimerStore.canRing(ctx)) null else set(ctx, "", seconds)

    private fun clock(ms: Long) = Arg.Clock(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().truncatedTo(ChronoUnit.MINUTES))
    private fun name(t: OwnTimer) = "name" to Arg.Plain(t.name)

    /** Whole seconds left; from 10 minutes up, whole minutes (rounded up), as core TimerAsk says it. */
    private fun left(t: OwnTimer, now: Long): Long = ((t.ends - now + 999) / 1000).coerceAtLeast(0).let { if (it >= 600) (it + 59) / 60 * 60 else it }

    private fun line(t: OwnTimer, now: Long, ringing: Boolean): Phrase = when {
        ringing -> if (t.name.isEmpty()) TimerStrings.ringingPlain() else TimerStrings.ringingNamed(name(t))
        t.name.isEmpty() -> TimerStrings.leftPlain("left" to Arg.Seconds(left(t, now)), "time" to clock(t.ends))
        else -> TimerStrings.leftNamed(name(t), "left" to Arg.Seconds(left(t, now)), "time" to clock(t.ends))
    }

    private fun set(ctx: Context, name: String, seconds: Long): Pair<Boolean, Phrase> {
        val now = System.currentTimeMillis()
        var added: OwnTimers.Added? = null
        OwnTimerStore.update(ctx, now) { l -> OwnTimers.add(l, name, seconds, now).also { added = it }.let { (it as? OwnTimers.Added.Ok)?.timers ?: l } }
        return when (val a = added) {
            is OwnTimers.Added.Ok -> {
                OwnTimerAlarms.arm(ctx, now)
                val t = a.timer
                true to (if (t.name.isEmpty()) TimerStrings.setPlain("duration" to Arg.Seconds(seconds), "time" to clock(t.ends))
                    else TimerStrings.setNamed(name(t), "duration" to Arg.Seconds(seconds), "time" to clock(t.ends)))
            }
            OwnTimers.Added.Full -> false to TimerStrings.full()
            OwnTimers.Added.TooLong -> false to TimerStrings.tooLong()
            null -> false to ToolStrings.somethingWrong()
        }
    }

    /** What Robot says to [c], having done it; [launch] opens the clock app (a timer Robot can't ring goes there). */
    fun answer(ctx: Context, c: TimerCommand, launch: (Intent) -> Boolean): Phrase {
        val now = System.currentTimeMillis()
        val l = OwnTimerStore.load(ctx, now)
        val ringing = OwnTimers.ringing(l, now)
        val shown = ringing + OwnTimers.running(l, now)
        fun lines(ts: List<OwnTimer>) = Phrase.Join(ts.take(4).map { line(it, now, it in ringing) })
        return when (c) {
            is TimerCommand.Set -> {
                if (!OwnTimerStore.canRing(ctx)) {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, c.seconds.toInt().coerceAtMost(86_400))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { if (c.name.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, c.name) }
                    val ok = launch(i).also { if (it) RobotTimers.set(ctx, c.seconds) }
                    Phrase.Join(listOf(TimerStrings.cantRing(), if (ok) ToolStrings.timerSet("duration" to Arg.Seconds(c.seconds)) else ToolStrings.noClockTimers()))
                } else set(ctx, c.name, c.seconds).second
            }
            is TimerCommand.Left -> {
                val pick = if (c.name.isEmpty()) shown else OwnTimers.find(shown, c.name)
                when {
                    pick.isNotEmpty() -> lines(pick)
                    c.name.isEmpty() -> TimerStrings.none()
                    else -> TimerStrings.noneCalled("name" to Arg.Plain(c.name))
                }
            }
            is TimerCommand.Cancel -> {
                val pick = when (c.name) {
                    TimerCommand.ALL -> shown
                    "" -> if (shown.size > 1) return Phrase.Join(listOf(TimerStrings.which("count" to Arg.Count(shown.size.toLong())), lines(shown))) else shown
                    else -> OwnTimers.find(shown, c.name)
                }
                if (pick.isEmpty()) return if (c.name.isEmpty() || c.name == TimerCommand.ALL) TimerStrings.none() else TimerStrings.noneCalled("name" to Arg.Plain(c.name))
                OwnTimerRing.cancel(ctx, pick.map { it.id }.toSet())
                when {
                    pick.size > 1 -> TimerStrings.cancelledAll()
                    pick[0].name.isEmpty() -> TimerStrings.cancelledPlain()
                    else -> TimerStrings.cancelledNamed(name(pick[0]))
                }
            }
            is TimerCommand.Stop -> {
                val pick = (if (c.name.isEmpty()) ringing else OwnTimers.find(ringing, c.name)).ifEmpty { ringing }
                if (pick.isEmpty()) return TimerStrings.none()
                OwnTimerRing.stop(ctx, pick.map { it.id }.toSet())
                TimerStrings.stopped()
            }
        }
    }
}
