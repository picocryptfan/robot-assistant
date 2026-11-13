// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.CorrectRouter.Ask
import app.heyrobot.core.CorrectRouter.Done
import app.heyrobot.core.CorrectRouter.Plan
import app.heyrobot.core.CorrectRouter.Seen
import app.heyrobot.core.i18n.CorrectStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectLastTest {
    private val zone = ZoneId.of("America/Halifax")
    private val t0 = 1_000_000L
    private val soon = t0 + 30_000L
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)
    private val begin = ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val row = CalendarEvent(begin, begin + 3_600_000L, false, "Lunch", null, id = 42)
    private val note = Note("n1", "", "milk", t0, t0)
    private val undo = Ask.Undo(Lang.EN)

    private fun plan(ask: Ask, last: Done?, now: Long = soon, locked: Boolean = false, seen: Seen = Seen.SAME) =
        CorrectRouter.plan(ask, last, now, locked, seen, zone)

    private fun said(p: Plan, l: Langs = en) = (p as Plan.Say).line.render(l)

    @Test fun parsesUndoInBothLanguages() {
        listOf("Undo that", "undo that please", "Scratch that.", "take that back").forEach { assertEquals(it, Ask.Undo(Lang.EN), CorrectRouter.parse(it)) }
        listOf("Annule ça", "annule ça s'il te plaît", "Défais ça").forEach { assertEquals(it, Ask.Undo(Lang.FR), CorrectRouter.parse(it)) }
    }

    @Test fun parsesCorrections() {
        assertEquals(Ask.Change(6, 45, null, Lang.EN), CorrectRouter.parse("No, 6:45"))
        assertEquals(Ask.Change(6, 45, true, Lang.EN), CorrectRouter.parse("no, make it 6:45 p.m."))
        assertEquals(Ask.Change(7, 0, false, Lang.EN), CorrectRouter.parse("Actually, 7 am"))
        assertEquals(Ask.Change(12, 0, null, Lang.EN), CorrectRouter.parse("no, noon"))
        assertEquals(Ask.Change(6, 45, null, Lang.FR), CorrectRouter.parse("Non, 6 h 45"))
        assertEquals(Ask.Change(18, 0, null, Lang.FR), CorrectRouter.parse("non, plutôt 18 h"))
        assertEquals(Ask.Change(6, 45, null, Lang.FR), CorrectRouter.parse("non 6h45"))
        assertEquals(Ask.Change(7, 0, true, Lang.FR), CorrectRouter.parse("non, 7 h du soir"))
    }

    @Test fun leavesOtherWordsAlone() {
        listOf("6:45", "no thanks", "no", "set an alarm for 6:45", "no, call Sam", "non merci", "no, 25:00", "undo my alarm for tomorrow")
            .forEach { assertNull(it, CorrectRouter.parse(it)) }
    }

    @Test fun undoesEachKind() {
        assertEquals(Plan.DismissAlarm(6, 15), plan(undo, Done.Alarm(6, 15, t0)))
        assertEquals(Plan.DeleteEvent(42), plan(undo, Done.Event(row, t0)))
        assertEquals(Plan.UndoCard("a1"), plan(undo, Done.Note(note, "a1", t0)))
        assertEquals(Plan.DeleteNote("n1"), plan(undo, Done.Note(note, null, t0)))
        assertEquals(Plan.UndoCard("m1"), plan(undo, Done.Card("m1", t0)))
    }

    @Test fun correctsAlarmsAndEvents() {
        val alarm = Done.Alarm(6, 15, t0)
        assertEquals(Plan.MoveAlarm(alarm, 6, 45), plan(CorrectRouter.parse("no, 6:45")!!, alarm))
        // The same half of the day as the alarm Robot set, unless a.m. or p.m. was said.
        assertEquals(Plan.MoveAlarm(Done.Alarm(18, 15, t0), 18, 45), plan(Ask.Change(6, 45, null, Lang.EN), Done.Alarm(18, 15, t0)))
        assertEquals(Plan.MoveAlarm(Done.Alarm(18, 15, t0), 6, 45), plan(Ask.Change(6, 45, false, Lang.EN), Done.Alarm(18, 15, t0)))
        val moved = plan(Ask.Change(1, 0, true, Lang.EN), Done.Event(row, t0)) as Plan.MoveEvent
        assertEquals(ZonedDateTime.of(2026, 11, 9, 13, 0, 0, 0, zone).toInstant().toEpochMilli(), moved.begin)
        assertEquals(3_600_000L, moved.end - moved.begin)
        assertEquals(CorrectStrings.cantChange().render(en), said(plan(Ask.Change(7, 0, null, Lang.EN), Done.Event(row.copy(allDay = true), t0))))
        assertEquals(CorrectStrings.cantChange().render(en), said(plan(Ask.Change(7, 0, null, Lang.EN), Done.Card("m1", t0))))
    }

    @Test fun editedSinceIsLeftAlone() {
        assertEquals(Seen.CHANGED, CorrectRouter.seen(Done.Event(row, t0), row.copy(begin = begin + 60_000)))
        assertEquals(Seen.GONE, CorrectRouter.seen(Done.Event(row, t0), null))
        assertEquals(Seen.SAME, CorrectRouter.seen(Done.Event(row, t0), row))
        assertEquals(Seen.CHANGED, CorrectRouter.seen(Done.Note(note, null, t0), note.copy(text = "oat milk")))
        assertEquals(CorrectStrings.changedSince().render(en), said(plan(undo, Done.Event(row, t0), seen = Seen.CHANGED)))
        assertEquals(CorrectStrings.goneSince().render(en), said(plan(undo, Done.Note(note, "a1", t0), seen = Seen.GONE)))
        assertEquals(CorrectStrings.changedSince().render(en), said(plan(Ask.Change(1, 0, true, Lang.EN), Done.Event(row, t0), seen = Seen.CHANGED)))
    }

    @Test fun expiredAndNothing() {
        assertEquals(CorrectStrings.tooOld().render(en), said(plan(undo, Done.Alarm(6, 15, t0), now = t0 + CorrectRouter.WINDOW_MS + 1)))
        assertTrue(plan(undo, Done.Alarm(6, 15, t0), now = t0 + CorrectRouter.WINDOW_MS) is Plan.DismissAlarm)
        assertEquals(CorrectStrings.nothing().render(en), said(plan(undo, null)))
    }

    @Test fun cantUndoKindsSayOneLine() {
        assertEquals(CorrectStrings.cantTimer().render(en), said(plan(undo, Done.Timer(600, t0))))
        assertEquals(CorrectStrings.cantSent().render(en), said(plan(undo, Done.Sent(t0))))
        assertEquals(CorrectStrings.cantElsewhere().render(en), said(plan(undo, Done.Elsewhere(t0))))
        assertEquals(CorrectStrings.cantTimer().render(en), said(plan(Ask.Change(7, 0, null, Lang.EN), Done.Timer(600, t0))))
    }

    @Test fun lockedTouchesNothingPrivate() {
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Alarm(6, 15, t0), locked = true))) // review C2
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Event(row, t0), locked = true)))
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Note(note, "a1", t0), locked = true)))
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Card("m1", t0), locked = true)))
        assertEquals(CorrectStrings.unlockChange().render(en), said(plan(Ask.Change(1, 0, true, Lang.EN), Done.Event(row, t0), locked = true)))
        // Locked and changed since: the unlock line, nothing about the event.
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Event(row, t0), locked = true, seen = Seen.CHANGED)))
    }

    @Test fun frenchLines() {
        assertTrue(said(plan(Ask.Undo(Lang.FR), null), fr).endsWith("y a rien à annuler pour Robot."))
        assertTrue(CorrectStrings.alarmChanged("time" to EventText.clock(6, 45)).render(fr).contains("est changé pour 6"))
        assertEquals("Déverrouille ton téléphone pour annuler ça.", said(plan(Ask.Undo(Lang.FR), Done.Event(row, t0), locked = true), fr))
    }

    // fix-correct-last, review High 1: Robot says an alarm went only when the next alarm shows it.
    @Test fun anAlarmIsCheckedOnlyWhenItIsTheNextOne() {
        val at = ZonedDateTime.of(2026, 11, 9, 18, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(CorrectRouter.isNext(18, 15, at, zone))
        assertTrue(!CorrectRouter.isNext(6, 15, at, zone))
        assertTrue(!CorrectRouter.isNext(18, 15, null, zone))
    }

    // review 9: "no, 12:30 a.m." on tomorrow's 23:00 event goes to the night after, not 22.5 h back; "no, 10 p.m." stays that day.
    @Test fun aMorningTimeLongBeforeTheEventIsTheNextDay() {
        val start = ZonedDateTime.of(2026, 11, 9, 23, 0, 0, 0, zone)
        val late = CalendarEvent(start.toInstant().toEpochMilli(), start.plusHours(1).toInstant().toEpochMilli(), false, "Late", null, id = 7)
        val night = plan(Ask.Change(12, 30, false, Lang.EN), Done.Event(late, t0)) as Plan.MoveEvent
        assertEquals(start.plusMinutes(90).toInstant().toEpochMilli(), night.begin)
        assertEquals(night.begin + 3_600_000L, night.end)
        val ten = plan(Ask.Change(10, 0, true, Lang.EN), Done.Event(late, t0)) as Plan.MoveEvent
        assertEquals(start.minusHours(1).toInstant().toEpochMilli(), ten.begin)
    }

    @Test fun robotNeverClaimsMoreThanItCanTell() {
        val t = EventText.clock(18, 15)
        assertEquals(app.heyrobot.core.i18n.ToolStrings.alarmOff("time" to t).render(en), CorrectRouter.offSaid(CorrectRouter.Off.GONE, 18, 15).render(en))
        // fix-alarm-moved: still on, the line says where to finish it.
        assertEquals(CorrectStrings.offStillOn("time" to t).render(en), CorrectRouter.offSaid(CorrectRouter.Off.STILL_ON, 18, 15).render(en))
        assertTrue(CorrectRouter.offSaid(CorrectRouter.Off.STILL_ON, 18, 15).render(en).contains("Turn it off in your clock app's alarms"))
        assertEquals(CorrectStrings.offNotSent("time" to t).render(en), CorrectRouter.offSaid(CorrectRouter.Off.NOT_SENT, 18, 15).render(en))
        assertEquals(app.heyrobot.core.i18n.ToolStrings.noClockAlarms().render(en), CorrectRouter.offSaid(null, 18, 15).render(en))
        val from = Done.Alarm(18, 15, t0)
        val to = EventText.clock(18, 45)
        // review Low D: still on is said so; not sent says Robot left it on; neither sets the new one.
        assertEquals(CorrectStrings.moveStillOn("from" to t, "to" to to).render(en), CorrectRouter.moveSaid(CorrectRouter.Off.STILL_ON, from, 18, 45).render(en))
        assertEquals(CorrectStrings.moveLeftOn("from" to t, "to" to to).render(en), CorrectRouter.moveSaid(CorrectRouter.Off.NOT_SENT, from, 18, 45).render(en))
        assertTrue(CorrectRouter.moveSaid(CorrectRouter.Off.NOT_SENT, from, 18, 45).render(en).contains("left it on"))
        assertTrue(CorrectRouter.moveSaid(CorrectRouter.Off.STILL_ON, from, 18, 45).render(en).contains("still on"))
        // review Low E: off, then the new one failed, names both.
        assertEquals(CorrectStrings.moveOffOnly("from" to t, "to" to to).render(en), CorrectRouter.offNotSet(from, 18, 45).render(en))
    }

    @Test fun anActionRobotCantUndoIsSaidSo() {
        assertEquals(CorrectStrings.cantKept().render(en), said(plan(undo, Done.Kept(t0))))
        assertEquals(CorrectStrings.cantAlarmShared().render(en), said(plan(undo, Done.Kept(t0, alarm = true))))
    }

    // review Medium 7: times in words, both languages; a day or a length is not a time.
    @Test fun timesInWordsAreReadToo() {
        assertEquals(Ask.Change(6, 45, null, Lang.EN), CorrectRouter.parse("no, six forty-five"))
        assertEquals(Ask.Change(6, 45, null, Lang.EN), CorrectRouter.parse("no, quarter to seven"))
        assertEquals(Ask.Change(7, 0, null, Lang.FR), CorrectRouter.parse("non, sept heures"))
        assertNull(CorrectRouter.parse("no, tomorrow"))
        assertNull(CorrectRouter.parse("no, in ten minutes"))
    }

    // review Medium C2: locked, a move or an undo of Robot's alarm sends nothing and says to unlock; unlocked, as before.
    @Test fun aLockedAlarmMoveOrUndoFailsClosed() {
        val alarm = Done.Alarm(6, 15, t0)
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, alarm, locked = true)))
        assertEquals(CorrectStrings.unlockChange().render(en), said(plan(Ask.Change(6, 45, null, Lang.EN), alarm, locked = true)))
        assertEquals("Déverrouille ton téléphone pour changer ça.", said(plan(Ask.Change(6, 45, null, Lang.FR), alarm, locked = true), fr))
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Kept(t0, alarm = true, hour = 6, minute = 15), locked = true)))
        assertEquals(Plan.DismissAlarm(6, 15), plan(undo, alarm))
        assertEquals(Plan.MoveAlarm(alarm, 6, 45), plan(Ask.Change(6, 45, null, Lang.EN), alarm))
    }

    private fun moved(start: ZonedDateTime, c: Ask.Change): ZonedDateTime {
        val e = CalendarEvent(start.toInstant().toEpochMilli(), start.plusHours(1).toInstant().toEpochMilli(), false, "E", null, id = 9)
        return java.time.Instant.ofEpochMilli((plan(c, Done.Event(e, t0)) as Plan.MoveEvent).begin).atZone(zone)
    }

    // review Low I: the morning rule on the clock's hours, on both change days, and its mirror.
    @Test fun theMorningRuleOnChangeDaysAndItsMirror() {
        val half = Ask.Change(12, 30, false, Lang.EN)
        val spring = ZonedDateTime.of(2026, 3, 8, 13, 0, 0, 0, zone) // 2:00 went to 3:00 that morning
        assertEquals(ZonedDateTime.of(2026, 3, 9, 0, 30, 0, 0, zone), moved(spring, half))
        val fall = ZonedDateTime.of(2026, 11, 1, 13, 0, 0, 0, zone) // 2:00 went back to 1:00
        assertEquals(ZonedDateTime.of(2026, 11, 2, 0, 30, 0, 0, zone), moved(fall, half))
        assertEquals(ZonedDateTime.of(2026, 3, 8, 1, 30, 0, 0, zone), moved(spring.withHour(12).withMinute(30), Ask.Change(1, 30, false, Lang.EN)))
        val early = ZonedDateTime.of(2026, 11, 10, 0, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 9, 23, 30, 0, 0, zone), moved(early, Ask.Change(11, 30, true, Lang.EN)))
        assertEquals(ZonedDateTime.of(2026, 11, 10, 2, 0, 0, 0, zone), moved(early, Ask.Change(2, 0, false, Lang.EN)))
        // A tie (exactly 12 h) between daytime hours keeps the day both ways (review-e Medium: the morning side's limit too).
        val eleven = ZonedDateTime.of(2026, 11, 10, 11, 0, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 10, 23, 0, 0, 0, zone), moved(eleven, Ask.Change(11, 0, true, Lang.EN)))
        assertEquals(ZonedDateTime.of(2026, 11, 10, 11, 0, 0, 0, zone), moved(eleven.withHour(23), Ask.Change(11, 0, false, Lang.EN)))
    }

    // review-d Medium: the mirror only for a start before 06:00; a pick before now goes a day later.
    @Test fun aDaytimeEventKeepsItsDayAndNeverMovesIntoThePast() {
        fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)
        fun move(start: ZonedDateTime, now: ZonedDateTime, c: Ask.Change): ZonedDateTime {
            val e = CalendarEvent(start.toInstant().toEpochMilli(), start.plusHours(1).toInstant().toEpochMilli(), false, "E", null, id = 9)
            val n = now.toInstant().toEpochMilli()
            return java.time.Instant.ofEpochMilli((plan(c, Done.Event(e, n - 1_000L), now = n) as Plan.MoveEvent).begin).atZone(zone)
        }
        val evening = Ask.Change(7, 30, true, Lang.EN)
        assertEquals(at(10, 19, 30), move(at(10, 7, 0), at(9, 20, 0), evening)) // 7:00 a.m. tomorrow
        assertEquals(at(10, 19, 30), move(at(10, 7, 0), at(10, 6, 0), evening)) // 7:00 a.m. today
        assertEquals(at(11, 7, 30), move(at(10, 9, 0), at(10, 8, 0), Ask.Change(7, 30, false, Lang.EN))) // 7:30 today is past
        val late = Ask.Change(11, 30, true, Lang.EN)
        assertEquals(at(9, 23, 30), move(at(10, 0, 30), at(9, 22, 0), late)) // a 00:30 start keeps its mirror
        assertEquals(at(10, 23, 30), move(at(10, 0, 30), at(9, 23, 45), late)) // unless that is past
        // review-e Medium: the AM/PM fix both ways keeps the day; a pick before 06:00 keeps its mirror.
        assertEquals(at(10, 7, 0), move(at(10, 19, 0), at(10, 6, 0), Ask.Change(7, 0, false, Lang.EN))) // "no, 7 a.m." on 7 p.m.
        assertEquals(at(10, 19, 0), move(at(10, 7, 0), at(10, 6, 0), Ask.Change(7, 0, true, Lang.EN))) // "no, 7 p.m." on 7 a.m.
        assertEquals(at(11, 0, 30), move(at(10, 23, 30), at(10, 6, 0), Ask.Change(12, 30, false, Lang.EN))) // 12:30 a.m. on 23:30
        assertEquals(at(11, 7, 0), move(at(10, 19, 0), at(10, 12, 0), Ask.Change(7, 0, false, Lang.EN))) // 7 a.m. today is past
    }

    // review Low J: the turn-off Robot didn't send gives no reason it can't know; a move after a shared alarm says what wasn't set.
    @Test fun theTwoWordingLines() {
        assertEquals("Robot couldn't check the ${EventText.clock(6, 15).render(en)} alarm, so it left your alarms as they are. Check the alarms in your clock app.",
            CorrectRouter.offSaid(CorrectRouter.Off.NOT_SENT, 6, 15).render(en))
        val shared = Done.Kept(t0, alarm = true, hour = 18, minute = 15)
        assertEquals(CorrectStrings.moveShared("to" to EventText.clock(18, 45)).render(en), said(plan(Ask.Change(6, 45, null, Lang.EN), shared)))
        assertEquals(CorrectStrings.moveShared("to" to EventText.clock(18, 45)).render(fr), said(plan(Ask.Change(6, 45, null, Lang.FR), shared), fr))
        assertEquals(CorrectStrings.cantAlarmShared().render(en), said(plan(undo, shared)))
    }

    @Test fun theLowsOneLineFixes() {
        assertEquals(0, CorrectRouter.hourOf(Ask.Change(12, 45, null, Lang.EN), 0)) // review 8
        assertEquals(CorrectStrings.unlockUndo().render(en), said(plan(undo, Done.Sent(t0), locked = true))) // review 12
        assertEquals(Seen.CHANGED, CorrectRouter.seen(Done.Event(row, t0), row.copy(location = "Elsewhere"))) // review 10
    }
}
