package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0 audit ipc-4: a flood of fake Clock broadcasts can't push the real events out of the voice log. */
class LogThrottleTest {
    @Test fun oneEventAMinuteWithItsCount() {
        val t = LogThrottle(60_000)
        assertEquals(1, t.admit(0))
        // 1,000 fake ALARM_ALERTs within the minute: none logged...
        for (i in 1..1_000) assertNull(t.admit(i * 10L))
        // ...then one, with the count.
        assertEquals(1_001, t.admit(60_000))
        assertEquals(1, t.admit(200_000))
    }

    @Test fun aFloodLeavesTheRingsRealEvents() {
        val ring = VoiceLogRing()
        ring.add(1, VoiceEvent.SESSION_SHOWN, ShowSource.HEADSET.id)
        val t = LogThrottle()
        for (i in 0 until 5_000) t.admit(i.toLong())?.let { ring.add(i.toLong(), VoiceEvent.ROUTINE_SKIPPED, SkipWhy.NOT_CLOCKS_ALARM.id, TriggerKind.ALARM.id, it) }
        assertTrue(ring.snapshot().any { it.event == VoiceEvent.SESSION_SHOWN })
        assertEquals(1, ring.snapshot().count { it.event == VoiceEvent.ROUTINE_SKIPPED })
    }

    @Test fun aDoneWithNothingRingingIsDroppedBeforeAnyWork() {
        assertFalse(AlarmWatch.awaitsDone(AlarmWatchState(), 1_000))
        assertFalse(AlarmWatch.awaitsDone(AlarmWatchState(AlarmPhase.SNOOZED, 0, 0, 500), 1_000))
        assertTrue(AlarmWatch.awaitsDone(AlarmWatchState(AlarmPhase.RINGING, 500, 500), 1_000))
        // Ringing far past the auto-silence time: DONE was lost, nothing rings now.
        assertFalse(AlarmWatch.awaitsDone(AlarmWatchState(AlarmPhase.RINGING, 0, 0), 3 * 60 * 60_000L))
    }
}
