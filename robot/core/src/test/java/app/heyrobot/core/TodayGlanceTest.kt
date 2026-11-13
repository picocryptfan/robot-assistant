// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

// H9: Today outside the app. The widget's rules (as WidgetRules' for the lists' widget), the lock screen summary's
// counts and times (never a title or anything you wrote), when it changes next, and the alarm switch.
class TodayGlanceTest {
    private val zone = ZoneId.of("America/Halifax")
    private fun at(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = at(h, m, day).toInstant().toEpochMilli()
    private fun ev(title: String, h: Int, m: Int = 0, mins: Int = 60, day: Int = 3) = CalendarEvent(ms(h, m, day), ms(h, m, day) + mins * 60_000L, false, title, "Secret place")
    private fun allDay(title: String, day: Int = 3): CalendarEvent {
        val d = LocalDate.of(2026, 11, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEvent(d, d + 86_400_000L, true, title, null)
    }
    private val day = listOf(ev("Secret standup", 9, 30, 30), ev("Secret dentist", 14, 30), ev("Secret car", 17), ev("Secret tomorrow", 9, 30, 30, day = 4))
    private fun pic(now: ZonedDateTime, events: List<CalendarEvent>? = day, reminders: List<String> = emptyList(), todo: Int? = null,
        timers: List<Long> = emptyList(), privateLocked: Boolean = false) = Today.picture(inp(now, events, reminders, todo, timers, privateLocked))
    private fun inp(now: ZonedDateTime, events: List<CalendarEvent>? = day, reminders: List<String> = emptyList(), todo: Int? = null,
        timers: List<Long> = emptyList(), privateLocked: Boolean = false) = DayInput(now, events, reminders, todo, timers, privateLocked)
    private fun plain(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2019', '\'')
    private fun summary(p: DayPicture, lang: Lang = Lang.EN) = plain(BriefComposer.shown(TodayGlance.summary(p, lang), lang))

    private val open = GlanceNow(today = true, lockSummary = true, interactive = true, locked = false, phoneLock = false, someoneElse = false)

    @Test fun theSummaryIsCountsAndTimes() {
        assertEquals("3 events today, next at 9:30. 1 reminder today. 2 things on your to-do list.",
            summary(pic(at(8), reminders = listOf("Secret pills"), todo = 2)))
        assertEquals("3 events today, next at 2:30.", summary(pic(at(9, 45))))
        assertEquals("1 event today, next at 5.", summary(pic(at(16))))
    }

    @Test fun underWayAndAllDayWithNothingNextAreACount() {
        assertEquals("2 events today.", summary(pic(at(17, 15), listOf(ev("Secret car", 17), allDay("Secret holiday")))))
    }

    @Test fun anEmptyDaySaysSoWithTomorrowsTimeOnly() {
        assertEquals("Nothing else today. Tomorrow starts at 9:30.", summary(pic(at(19))))
        assertEquals("Nothing else today. Tomorrow: an all-day event.", summary(pic(at(19), listOf(allDay("Secret trip", 4)))))
        assertEquals("Nothing else today.", summary(pic(at(19), emptyList())))
    }

    @Test fun lockedPrivateEventsAndNoCalendarNeverSayTheDayIsDone() {
        assertEquals("Private events wait for your unlock.", summary(pic(at(19), emptyList(), privateLocked = true)))
        assertEquals("Robot can't read your calendar.", summary(pic(at(19), null)))
    }

    @Test fun timersAreTimes() {
        assertEquals("3 events today, next at 9:30. Your timer ends at 8:10.", summary(pic(at(8), timers = listOf(ms(8, 10)))))
    }

    @Test fun french() {
        assertEquals("3 événements aujourd'hui, le prochain à 14 h 30. 1 rappel aujourd'hui.",
            summary(pic(at(9, 45), reminders = listOf("Secret")), Lang.FR))
        assertEquals("Rien d'autre aujourd'hui. Demain commence à 9 h 30.", summary(pic(at(19)), Lang.FR))
    }

    @Test fun noTitleNoPlaceNoWordsOfYoursAtAnyHour() {
        val events = day + allDay("Secret holiday") + ev("Secret late", 23, 30, 90)
        for (h in 0..23) for (lang in Lang.entries) {
            val s = summary(pic(at(h, 15), events, reminders = listOf("Secret reminder", "Secret two"), todo = 4, timers = listOf(ms(h, 40))), lang)
            assertFalse("$h $lang: $s", s.contains("Secret", ignoreCase = true))
        }
    }

    @Test fun theWidgetFollowsTheListsWidgetsLockRule() {
        var read = 0
        val p = { read++; inp(at(8)) }
        assertEquals(GlanceView.Line(GlanceLine.OFF), TodayGlance.widget(open.copy(today = false), Lang.EN, p))
        assertEquals(GlanceView.Line(GlanceLine.ROBOT_LOCKED), TodayGlance.widget(open.copy(phoneLock = true), Lang.EN, p))
        assertEquals(GlanceView.Line(GlanceLine.ROBOT_LOCKED), TodayGlance.widget(open.copy(phoneLock = true, someoneElse = true), Lang.EN, p))
        val off = open.copy(lockSummary = false)
        assertEquals(GlanceView.Line(GlanceLine.UNLOCK), TodayGlance.widget(off.copy(locked = true), Lang.EN, p))
        assertEquals(GlanceView.Line(GlanceLine.UNLOCK), TodayGlance.widget(off.copy(interactive = false), Lang.EN, p))
        assertEquals(GlanceView.Line(GlanceLine.UNLOCK), TodayGlance.widget(off.copy(keyguardHost = true), Lang.EN, p))
        assertEquals("Lines read nothing of the day", 0, read)
        for (n in listOf(open.copy(locked = true), open.copy(interactive = false), open.copy(keyguardHost = true))) {
            val v = TodayGlance.widget(n, Lang.EN, p)
            assertTrue("$n: $v", v is GlanceView.Summary)
            assertFalse(BriefComposer.shown((v as GlanceView.Summary).text, Lang.EN).contains("Secret"))
        }
        val v = TodayGlance.widget(open, Lang.EN, p) as GlanceView.Day
        assertEquals("Next: Secret standup at 9:30, then 2 more.", plain(BriefComposer.shown(v.text, Lang.EN)))
    }

    @Test fun theNoticeIsOffUntilTurnedOnAndGoneUnderAPhoneLock() {
        var read = 0
        val p = { read++; inp(at(8)) }
        assertNull(TodayGlance.notice(open.copy(lockSummary = false), Lang.EN, p))
        assertNull(TodayGlance.notice(open.copy(today = false), Lang.EN, p))
        assertNull(TodayGlance.notice(open.copy(phoneLock = true), Lang.EN, p))
        assertNull(TodayGlance.notice(open.copy(someoneElse = true), Lang.EN, p))
        assertEquals(0, read)
        assertFalse(HomeSettings().lockSummary)
        for (n in listOf(open, open.copy(locked = true, interactive = false)))
            assertEquals("3 events today, next at 9:30.", plain(BriefComposer.shown(TodayGlance.notice(n, Lang.EN, p)!!, Lang.EN)))
    }

    @Test fun theNextChangeIsTheSoonestStartEndTimerOrMidnight() {
        assertEquals(ms(9, 30), TodayGlance.nextChange(pic(at(8))))
        assertEquals(ms(10), TodayGlance.nextChange(pic(at(9, 45))))
        assertEquals(ms(8, 10), TodayGlance.nextChange(pic(at(8), timers = listOf(ms(8, 10)))))
        assertEquals(ms(0, 0, 4), TodayGlance.nextChange(pic(at(19))))
        assertEquals("never sooner than a minute", ms(9, 30) + 30_000L, TodayGlance.nextChange(pic(at(9, 29).plusSeconds(30))))
    }

    @Test fun theLockSummarySettingIsKeptAndRestored() {
        val on = HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY, lockSummary = true)
        assertTrue(HomeSettings.fromJson(on.toJson()).lockSummary)
        assertFalse(HomeSettings.fromJson("""{"v":1,"today":true}""").lockSummary)
        assertTrue(HomeSettings().restored(on).lockSummary)
        assertFalse(on.restored(HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY)).lockSummary)
    }

    @Test fun theAlarmReadIsTheBriefAfterTheAlarmOffUntilTurnedOn() {
        val rs = RoutineSettings()
        assertFalse(TodayAlarm.on(rs))
        assertFalse(BriefRoutine.of(rs).enabled)
        val on = TodayAlarm.set(rs, true)
        assertTrue(TodayAlarm.on(on))
        val r = BriefRoutine.of(on)
        assertTrue(r.enabled)
        assertEquals(RoutineTrigger.AlarmDismissed(rs.alarmWindow), r.trigger)
        assertFalse(TodayAlarm.on(TodayAlarm.set(on, false)))
        // today-fix-c 2: off never turns the brief off; with no trigger before it, the brief runs only when asked.
        assertTrue(BriefRoutine.of(TodayAlarm.set(on, false)).enabled)
        assertEquals(RoutineTrigger.Asked, TodayAlarm.set(on, false).briefTrigger)
        // A brief you set for a time isn't this switch's: turning it off leaves it; turning it on moves it to the alarm.
        val timed = rs.copy(briefEnabled = true, briefTrigger = RoutineTrigger.At(LocalTime.of(7, 0), java.time.DayOfWeek.entries.toSet()))
        assertFalse(TodayAlarm.on(timed))
        assertEquals(timed, TodayAlarm.set(timed, false))
        assertTrue(TodayAlarm.on(TodayAlarm.set(timed, true)))
    }

    /** today-fix-c 2 (review finding 2): a 7:00 brief, the switch tried and turned off, is the 7:00 brief again, kept on disk. */
    @Test fun theAlarmSwitchGivesTheBriefsTimeBack() {
        val seven = RoutineTrigger.At(LocalTime.of(7, 0), java.time.DayOfWeek.entries.toSet())
        val timed = RoutineSettings(briefEnabled = true, briefTrigger = seven)
        val on = RoutineSettingsCodec.decode(RoutineSettingsCodec.encode(TodayAlarm.set(timed, true)))
        assertTrue(TodayAlarm.on(on))
        assertEquals(seven, on.briefTriggerBefore)
        val off = TodayAlarm.set(on, false)
        assertTrue(off.briefEnabled)
        assertEquals(seven, off.briefTrigger)
        assertNull(off.briefTriggerBefore)
        // On twice keeps the time; a brief that was off starts no timed brief when the switch goes off.
        assertEquals(seven, TodayAlarm.set(TodayAlarm.set(on, true), false).briefTrigger)
        assertEquals(RoutineTrigger.Asked, TodayAlarm.set(TodayAlarm.set(timed.copy(briefEnabled = false), true), false).briefTrigger)
    }

    private fun priv(title: String, h: Int, m: Int = 0) = ev(title, h, m).copy(kind = EventKind.PRIVATE, key = "0".repeat(32))

    /** today-fix-c 1 (review finding 1): the widget never draws a private event's title or place, only "a private event" and its time. */
    @Test fun theWidgetNeverCarriesAPrivateTitle() {
        val i = { inp(at(8), listOf(priv("Secret clinic", 9, 30), ev("Standup", 14))) }
        val en = plain(BriefComposer.shown((TodayGlance.widget(open, Lang.EN, i) as GlanceView.Day).text, Lang.EN))
        assertFalse(en, en.contains("Secret"))
        assertTrue(en, en.contains("a private event") && en.contains("9:30"))
        val fr = BriefComposer.shown((TodayGlance.widget(open, Lang.FR, i) as GlanceView.Day).text, Lang.FR)
        assertFalse(fr, fr.contains("Secret"))
        assertTrue(fr, fr.contains("un événement privé"))
    }

    /** today-fix-c 5 (review finding 5): unlocked or not, the summary counts and times no private event; it says they wait. */
    @Test fun theSummaryIsAlwaysAsIfLocked() {
        val i = { inp(at(8), listOf(priv("Secret clinic", 9, 30), ev("Standup", 14))) }
        for (n in listOf(open, open.copy(locked = true, interactive = false))) {
            val s = plain(BriefComposer.shown(TodayGlance.notice(n, Lang.EN, i)!!, Lang.EN))
            assertEquals("1 event today, next at 2. Private events wait for your unlock.", s)
        }
        val w = TodayGlance.widget(open.copy(interactive = false), Lang.EN, i) as GlanceView.Summary
        assertEquals("1 event today, next at 2. Private events wait for your unlock.", plain(BriefComposer.shown(w.text, Lang.EN)))
    }

    /** today-fix-c 3 (review finding 3): on asks for notifications when Robot may not post them; off never asks. */
    @Test fun theLockSwitchAsksForNotifications() {
        assertEquals(LockSwitch.SET, TodayGlance.lockSwitch(want = true, canNotify = true, granted = true))
        assertEquals(LockSwitch.ASK, TodayGlance.lockSwitch(want = true, canNotify = false, granted = false))
        assertEquals(LockSwitch.REFUSED, TodayGlance.lockSwitch(want = true, canNotify = false, granted = true))
        assertEquals(LockSwitch.SET, TodayGlance.lockSwitch(want = false, canNotify = false, granted = false))
    }

    /** today-fix-c 10 (review finding 10): at the largest font the widget's day is a few whole lines, the last ending "…". */
    @Test fun theWidgetsLinesFitItsHeight() {
        assertEquals(1, TodayGlance.lines(0, 2f))
        assertEquals(2, TodayGlance.lines(0, 1f))
        assertTrue(TodayGlance.lines(250, 2f) in 3..5)
        assertEquals(1, TodayGlance.lines(40, Float.NaN))
    }

    /** today-fix-c 11 (review finding 11): French outside the app, as drawn, has U+00A0 before ":" (core's render, FrTypo). */
    @Test fun frenchOutsideTheAppHasItsSpaces() {
        val i = { inp(at(20), listOf(allDay("Secret holiday", day = 4))) }
        val s = BriefComposer.shown(TodayGlance.notice(open, Lang.FR, i)!!, Lang.FR)
        assertTrue(s, s.contains("Demain\u00A0: un événement toute la journée."))
        assertFalse(s, Regex(" [:;?!]").containsMatchIn(s))
    }
}
