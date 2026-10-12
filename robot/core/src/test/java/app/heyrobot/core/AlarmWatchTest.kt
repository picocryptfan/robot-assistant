package app.heyrobot.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmWatchTest {
    private val zone = ZoneId.of("America/Toronto")
    /** Monday 12 October 2026, 06:45: the alarm rings. */
    private val t0 = ZonedDateTime.of(2026, 10, 12, 6, 45, 0, 0, zone).toInstant().toEpochMilli()
    private val s = 1_000L
    private val min = 60_000L

    /** Runs [signals] from idle; the endings in order (null where none). */
    private fun run(vararg signals: AlarmSignal, from: AlarmWatchState = AlarmWatchState()): Pair<AlarmWatchState, List<AlarmStep>> {
        var state = from
        val steps = signals.map { sig -> AlarmWatch.on(state, sig).also { state = it.state } }
        return state to steps
    }

    private fun alert(at: Long) = AlarmSignal.Alert(at)
    private fun done(at: Long, next: NextAlarm? = null) = AlarmSignal.Done(at, next)
    private fun clockNext(at: Long) = NextAlarm(at, fromClock = true)

    @Test fun dismissed() {
        val (state, steps) = run(alert(t0), done(t0 + 40 * s))
        assertTrue(steps[0].prepare)
        assertNull(steps[0].end)
        assertEquals(AlarmEnd.DISMISSED, steps[1].end)
        assertEquals(t0, steps[1].alarmAt)
        assertEquals(AlarmPhase.IDLE, state.phase)
        // Tomorrow's alarm being the next one changes nothing.
        assertEquals(AlarmEnd.DISMISSED, run(alert(t0), done(t0 + 40 * s, clockNext(t0 + 24 * 60 * min))).second[1].end)
    }

    @Test fun snoozedThenDismissedKeepsTheFirstAlarmTime() {
        val snooze = t0 + 30 * s
        val (state, steps) = run(alert(t0), done(snooze, clockNext(snooze + 10 * min)), alert(snooze + 10 * min), done(snooze + 10 * min + 20 * s))
        assertEquals(AlarmEnd.SNOOZED, steps[1].end)
        assertTrue(steps[2].prepare)
        assertNull(steps[2].end)
        assertEquals(AlarmEnd.DISMISSED, steps[3].end)
        assertEquals(t0, steps[3].alarmAt)
        assertEquals(AlarmWatchState(), state)
        // Snoozed four times, then dismissed: one dismissal.
        var st = AlarmWatchState()
        var at = t0
        val ends = mutableListOf<AlarmEnd?>()
        repeat(4) {
            ends += AlarmWatch.on(st, alert(at)).also { st = it.state }.end
            ends += AlarmWatch.on(st, done(at + 20 * s, clockNext(at + 20 * s + 10 * min))).also { st = it.state }.end
            at += 20 * s + 10 * min
        }
        ends += AlarmWatch.on(st, alert(at)).also { st = it.state }.end
        val last = AlarmWatch.on(st, done(at + 5 * s))
        assertEquals(List(4) { listOf(null, AlarmEnd.SNOOZED) }.flatten() + null, ends)
        assertEquals(AlarmEnd.DISMISSED, last.end)
        assertEquals(t0, last.alarmAt)
    }

    /** Clock silences an alarm after 10 minutes (its default): rang to the end, it was missed, whatever the next alarm is. */
    @Test fun autoSilenceIsMissed() {
        assertEquals(AlarmEnd.MISSED, run(alert(t0), done(t0 + 10 * min)).second[1].end)
        assertEquals(AlarmEnd.MISSED, run(alert(t0), done(t0 + 10 * min - 30 * s, clockNext(t0 + 20 * min))).second[1].end)
        assertEquals(AlarmEnd.DISMISSED, run(alert(t0), done(t0 + 10 * min - 31 * s)).second[1].end)
        // Robot's setting "Clock silences after" when Clock's was changed.
        assertEquals(AlarmEnd.DISMISSED, AlarmWatch.on(AlarmWatch.on(AlarmWatchState(), alert(t0), 15).state, done(t0 + 12 * min), 15).end)
        assertEquals(AlarmEnd.MISSED, AlarmWatch.classify(t0, t0 + 5 * min, null, silenceMinutes = 5))
    }

    /** Another app can send ALARM_DONE: without an ALERT first it starts nothing. */
    @Test fun doneWithoutAlertIsIgnored() {
        val (state, steps) = run(done(t0))
        assertEquals(AlarmEnd.UNKNOWN, steps[0].end)
        assertNull(steps[0].alarmAt)
        assertEquals(AlarmWatchState(), state)
        // While snoozed, too: the snooze stands.
        val (st2, steps2) = run(alert(t0), done(t0 + 20 * s, clockNext(t0 + 20 * s + 10 * min)), done(t0 + 2 * min))
        assertEquals(AlarmEnd.UNKNOWN, steps2[2].end)
        assertEquals(AlarmPhase.SNOOZED, st2.phase)
    }

    /** A new alarm replaces a ringing one: Clock marks the first missed. */
    @Test fun twoAlertsMeanTheFirstWasMissed() {
        val (state, steps) = run(alert(t0), alert(t0 + 2 * min), done(t0 + 3 * min))
        assertEquals(AlarmEnd.MISSED, steps[1].end)
        assertEquals(t0, steps[1].alarmAt)
        assertTrue(steps[1].prepare)
        assertEquals(AlarmEnd.DISMISSED, steps[2].end)
        assertEquals(t0 + 2 * min, steps[2].alarmAt)
        assertEquals(AlarmPhase.IDLE, state.phase)
    }

    /**
     * Documented misclassification (design §2.12 risk 5): a real alarm within the hour after a dismissed one reads as a
     * snooze, so the brief waits for that second alarm. An alarm another app set doesn't count.
     */
    @Test fun aRealAlarmWithinTheHourReadsAsASnooze() {
        assertEquals(AlarmEnd.SNOOZED, run(alert(t0), done(t0 + 30 * s, clockNext(t0 + 15 * min))).second[1].end)
        assertEquals(AlarmEnd.DISMISSED, run(alert(t0), done(t0 + 30 * s, NextAlarm(t0 + 15 * min, fromClock = false))).second[1].end)
        assertEquals(AlarmEnd.DISMISSED, run(alert(t0), done(t0 + 30 * s, clockNext(t0 + 30 * s + 61 * min))).second[1].end)
        // A "next alarm" already in the past isn't the snoozed one.
        assertEquals(AlarmEnd.DISMISSED, run(alert(t0), done(t0 + 30 * s, clockNext(t0))).second[1].end)
    }

    /** :voice restarts between ALERT and DONE: the state comes back from state.json. */
    @Test fun aRestartBetweenAlertAndDoneKeepsTheState() {
        val ringing = AlarmWatch.on(AlarmWatchState(), alert(t0)).state
        val restored = AlarmWatch.decode(JSONObject(AlarmWatch.encode(ringing).toString()))
        assertEquals(ringing, restored)
        val step = AlarmWatch.on(restored, done(t0 + 50 * s))
        assertEquals(AlarmEnd.DISMISSED, step.end)
        assertEquals(t0, step.alarmAt)
        // Unreadable: idle.
        assertEquals(AlarmWatchState(), AlarmWatch.decode(JSONObject().put("phase", "dancing")))
        assertEquals(AlarmWatchState(), AlarmWatch.decode(null))
    }

    @Test fun staleStatesExpire() {
        // Snoozed, then dismissed from the notification (no broadcast): over an hour later a new alarm is a new one.
        val snoozed = run(alert(t0), done(t0 + 20 * s, clockNext(t0 + 20 * s + 10 * min))).first
        val later = AlarmWatch.on(snoozed, alert(t0 + 3 * 60 * min))
        assertNull(later.end)
        assertEquals(t0 + 3 * 60 * min, later.state.firstAlert)
        // Ringing with no DONE for far too long (a lost broadcast): a DONE much later is ignored.
        val ringing = AlarmWatch.on(AlarmWatchState(), alert(t0)).state
        assertEquals(AlarmEnd.UNKNOWN, AlarmWatch.on(ringing, done(t0 + 3 * 60 * min)).end)
        // The clock set back before the alert.
        assertEquals(AlarmWatchState(), AlarmWatch.expire(ringing, t0 - min))
    }

    @Test fun theTriggersHours() {
        assertTrue(AlarmWatch.inWindow(t0, zone, TimeWindow.ALARM))
        assertFalse(AlarmWatch.inWindow(t0 + 7 * 60 * min, zone, TimeWindow.ALARM))
        assertFalse(AlarmWatch.inWindow(t0 - 3 * 60 * min, zone, TimeWindow.ALARM))
    }

    /**
     * 0.8 review: Clock's broadcasts come with no permission. An ALERT counts only when Clock's own next alarm, read before
     * it, was due within 2 minutes; anything else is ignored before AlarmWatch sees it, so a forged ALERT then DONE never
     * starts a routine, never fetches the weather and never uses up the once-a-day run the real alarm needs.
     */
    @Test fun onlyClocksOwnAlarmCounts() {
        // The night before: Clock's 06:45 alarm read as the next one.
        val night = t0 - 9 * 60 * min
        var seen = AlarmCheck.remember(emptyList(), clockNext(t0), night)
        assertTrue(AlarmCheck.verified(seen, t0 + 3 * s))
        assertTrue(AlarmCheck.verified(seen, t0 - min))
        assertFalse(AlarmCheck.verified(seen, t0 + 3 * min))
        // A forged ALERT then DONE at 05:00, inside the alarm hours: ignored, nothing ends, nothing to run.
        val forged = listOf(alert(t0 - 105 * min), done(t0 - 104 * min))
        var st = AlarmWatchState()
        val ends = forged.filter { AlarmCheck.admits(seen, it) }.map { sig -> AlarmWatch.on(st, sig).also { st = it.state } }
        assertEquals(listOf(AlarmEnd.UNKNOWN), ends.map { it.end })
        assertTrue(ends.none { it.prepare })
        assertEquals(AlarmWatchState(), st)
        // The real alarm afterwards still counts (its DONE dismisses it: the routine's run is still there to use).
        val real = listOf(alert(t0 + 2 * s), done(t0 + 40 * s))
        val realEnds = real.filter { AlarmCheck.admits(seen, it) }.map { sig -> AlarmWatch.on(st, sig).also { st = it.state } }
        assertEquals(listOf(null, AlarmEnd.DISMISSED), realEnds.map { it.end })
        // An alarm another app set (not Clock's), or one read only after the ALERT came, doesn't vouch for it.
        assertFalse(AlarmCheck.verified(AlarmCheck.remember(emptyList(), NextAlarm(t0, fromClock = false), night), t0))
        assertFalse(AlarmCheck.verified(AlarmCheck.remember(emptyList(), clockNext(t0), t0 + 5 * s), t0 + 2 * s))
        // A snooze: Clock's snoozed instance, read after the DONE, vouches for the next ALERT; the old ones are let go.
        seen = AlarmCheck.remember(seen, clockNext(t0 + 10 * min), t0 + 42 * s)
        assertTrue(AlarmCheck.verified(seen, t0 + 10 * min + s))
        assertTrue(AlarmCheck.remember(seen, null, t0 + 2 * 24 * 60 * min).isEmpty())
        // The same alarm read twice is kept once.
        assertEquals(seen, AlarmCheck.remember(seen, clockNext(t0), t0 + 50 * s))
    }
}
