// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 1.1, dated reminders: the alert a reminder's event carries, as the Reminders row Calendars.insert writes (core EventAlert). */
class EventAlertTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 10, 21, 17, 45, 0, 0, zone)

    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone)

    private fun plan(span: WhenSpan, alert: ZonedDateTime?) = EventPlan(span.start, span.end!!, span.allDay, 7L, "Personal", 2026, alert)

    @Test fun anAllDayReminderRingsAtNineThatDay() {
        val span = WhenSpan(at(11, 20), at(11, 21), true)
        val alert = EventAlert.at(span, now)
        assertEquals(at(11, 20, 9), alert)
        // Its row: 9:00 is 540 minutes after the day's start, so -540 minutes "before" it, as an alert (METHOD_ALERT, 1).
        assertEquals(EventAlert.Row(-540, 1), EventAlert.row(plan(span, alert)))
    }

    @Test fun aTimedReminderRingsAtItsTime() {
        val span = WhenSpan(at(10, 21, 18, 5), at(10, 21, 19, 5), false)
        val alert = EventAlert.at(span, now)
        assertEquals(at(10, 21, 18, 5), alert)
        assertEquals(EventAlert.Row(0, EventAlert.METHOD_ALERT), EventAlert.row(plan(span, alert)))
    }

    @Test fun noAlertNoRow() {
        assertNull(EventAlert.row(plan(WhenSpan(at(11, 20, 9), at(11, 20, 10), false), null)))
    }

    @Test fun anAlertAlreadyPastIsNeverPromised() {
        // "Remind me to … today", said at 17:45: today's 9:00 has gone.
        assertNull(EventAlert.at(WhenSpan(at(10, 21), at(10, 22), true), now))
        assertNull(EventAlert.at(WhenSpan(at(10, 21, 17, 45), null, false), now))
    }
}
