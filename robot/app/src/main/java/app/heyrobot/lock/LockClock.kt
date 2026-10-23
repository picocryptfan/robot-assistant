// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.AlarmManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import app.heyrobot.core.AlarmCheck
import app.heyrobot.core.AlarmSignal
import app.heyrobot.core.AlarmWatch
import app.heyrobot.core.NextAlarm
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.RecordEvent
import app.heyrobot.core.lock.RecordLine
import app.heyrobot.core.lock.UsageEvent
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.RoutineFiles

// :LOCK PROCESS ONLY. After the alarm: while an After the alarm lock, or a
// One app first lock that starts with the alarm, is on, :lock registers the same runtime receiver as the routines' hub
// for GrapheneOS Clock's ALARM_ALERT and ALARM_DONE, and feeds the same pure AlarmCheck and AlarmWatch with its own state
// (state.json's alarm, seen, missed). It never depends on :voice or on Robot being the assistant. Clock's broadcasts come
// with no permission, so any app can send them: an ALERT counts only when Clock's own next alarm, read before it, was due
// then (AlarmCheck), and a DONE only after a counted ALERT (AlarmWatch). A fake one can at worst start your own lock.

object LockClock {
    private const val ALARM_ALERT = "com.android.deskclock.ALARM_ALERT"
    private const val ALARM_DONE = "com.android.deskclock.ALARM_DONE"

    private var clock: BroadcastReceiver? = null
    private var nextChanged: BroadcastReceiver? = null
    @Volatile private var donePending = false

    /** [LockAccessibilityService] Listens to Clock only while a lock may start on its alarm ([handler]: the service's). */
    @Synchronized
    fun sync(ctx: Context, file: LockFile?, handler: Handler) {
        val want = LockSchedule.wantsClock(file)
        if (want == (clock != null)) return
        if (want) {
            try {
                see(ctx)
                nextChanged = receiver { see(ctx) }.also {
                    ctx.registerReceiver(it, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), null, handler, Context.RECEIVER_NOT_EXPORTED)
                }
                // Clock is another app: the one receiver :lock registers exported, as the routines' hub does.
                clock = receiver { i -> onClock(ctx, i, handler) }.also {
                    ctx.registerReceiver(it, IntentFilter().apply { addAction(ALARM_ALERT); addAction(ALARM_DONE) }, null, handler, Context.RECEIVER_EXPORTED)
                }
            } catch (t: Throwable) {
                RLog.w("lock_clock", t)
            }
        } else stop(ctx)
    }

    @Synchronized
    fun stop(ctx: Context) {
        for (r in listOfNotNull(clock, nextChanged)) try { ctx.unregisterReceiver(r) } catch (_: Throwable) {}
        clock = null
        nextChanged = null
    }

    private fun receiver(f: (Intent) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // The files are read and written off the main thread.
            Thread({ try { f(i) } catch (t: Throwable) { RLog.w("lock_clock_receiver", t) } }, "robot-lock").start()
        }
    }

    private fun silence(ctx: Context): Int = try { RoutineFiles.store(ctx).settings().clockSilenceMinutes } catch (_: Throwable) { AlarmWatch.DEFAULT_SILENCE_MINUTES }

    private fun nextAlarm(ctx: Context): NextAlarm? = try {
        ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.let { NextAlarm(it.triggerTime, it.showIntent?.creatorPackage == RoutineFacts.CLOCK) }
    } catch (_: Throwable) { null }

    /** Clock's next alarm, kept so its ALERT can be vouched for. */
    private fun see(ctx: Context) {
        val next = nextAlarm(ctx)
        LockStore.editState(ctx) { it.copy(seen = AlarmCheck.remember(it.seen, next, System.currentTimeMillis())) }
    }

    private fun onClock(ctx: Context, i: Intent, handler: Handler) {
        val now = System.currentTimeMillis()
        when (i.action) {
            ALARM_ALERT -> {
                val signal = AlarmSignal.Alert(now)
                val st = LockStore.readState(ctx)
                if (!AlarmCheck.admits(st.seen, signal)) return RLog.i("lock_alarm_unverified")
                step(ctx, signal)
            }
            // Clock publishes the snoozed instance as its next alarm first: read 2 s later, one read at a time.
            ALARM_DONE -> {
                if (donePending || !AlarmWatch.awaitsDone(LockStore.readState(ctx).alarm, now, silence(ctx))) return
                donePending = true
                handler.postDelayed({
                    donePending = false
                    Thread({ try { step(ctx, AlarmSignal.Done(now, nextAlarm(ctx))) } catch (t: Throwable) { RLog.w("lock_clock_done", t) } }, "robot-lock").start()
                }, AlarmWatch.READ_DELAY_MS)
            }
        }
    }

    private fun step(ctx: Context, signal: AlarmSignal) {
        val file = LockStore.usable(ctx)
        val c = LockEngine.clocks(ctx)
        val silence = silence(ctx)
        var started = false
        LockStore.editState(ctx) { st0 ->
            val seen = if (signal is AlarmSignal.Done) AlarmCheck.remember(st0.seen, signal.next, c.nowMs) else st0.seen
            val s = AlarmWatch.on(st0.alarm, signal, silence)
            var st = st0.copy(alarm = s.state, seen = seen)
            val end = s.end
            if (end != null && s.alarmAt != null) {
                val r = LockSchedule.onAlarmEnd(file, st, c, end, s.alarmAt!!)
                started = r.started.isNotEmpty()
                st = r.state.recording(*r.started.map { RecordLine(c.nowMs, if (LockAccess.enabled(ctx)) RecordEvent.STARTED else RecordEvent.COULD_NOT_START, it.lock) }.toTypedArray())
            }
            st
        }
        RLog.i("lock_alarm", "signal" to signal.javaClass.simpleName, "started" to started)
        LockEngine.refresh(ctx, startedNow = started)
    }
}

/**
 * :LOCK PROCESS ONLY. Android's usage events for One app first and Daily limit, through usage access
 * (PACKAGE_USAGE_STATS, already declared for phone status). Only an event's type, time, package and class are read, and
 * only the totals are kept (core ScreenTime). Null without usage access, or before the first unlock.
 */
object UsageReader {
    private val TYPES = setOf(1, 2, 15, 16, 17, 18, 23, 26, 27)

    fun access(ctx: Context): Boolean = PhoneInfo.usageAccess(ctx)

    fun events(ctx: Context, from: Long, to: Long): List<UsageEvent>? {
        if (!access(ctx) || to <= from) return null
        return try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
            val q = usm.queryEvents(from, to) ?: return null
            val e = UsageEvents.Event()
            val out = ArrayList<UsageEvent>()
            while (q.hasNextEvent()) {
                q.getNextEvent(e)
                if (e.eventType in TYPES) out += UsageEvent(e.timeStamp, e.eventType, e.packageName, e.className)
            }
            out
        } catch (t: Throwable) {
            RLog.w("lock_usage", t)
            null
        }
    }
}
