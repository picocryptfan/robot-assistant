// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.CorrectRouter
import app.heyrobot.core.Executed
import app.heyrobot.core.Note
import app.heyrobot.core.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** correct-last: what PanelCorrect records, and when "no, 6:45" is claimed (only with a last action held). */
class PanelCorrectTest {
    @After fun clear() = PanelCorrect.forget()

    // review High A: handle() with a clock that records what Robot sends, in order (AlarmManager's next alarm scripted).
    private val zone = java.time.ZoneId.of("America/Halifax")
    private fun at(day: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val en = app.heyrobot.core.i18n.Langs(Lang.EN, Lang.EN, Lang.EN)

    /** [after]: the next alarm once the clock turned one off ([turnsOff] false: it stays); [clock] false: no clock took it. */
    private class Clock(var next: Long?, val after: Long? = null, val clock: Boolean = true, val turnsOff: Boolean = true, val sets: Boolean = true,
        /** fix-batch-15 M1: false, your clock didn't keep the moved alarm (no tap on its page). */ val saves: Boolean = true) : PanelCorrect.Hands {
        val sent = mutableListOf<String>()
        override fun nextAlarm() = next
        var stop = false
        var stopOnSend = false
        override fun sendDismiss(hour: Int, minute: Int): Boolean { sent += "off $hour:$minute"; if (stopOnSend) stop = true; if (clock && turnsOff) next = after; return clock }
        override fun stopped() = stop
        override fun run(action: Action): Executed {
            sent += "set ${(action as Action.SetAlarm).hour}:${action.minute}"
            return Executed(if (sets) ActionStatus.DONE else ActionStatus.FAILED, "").also { PanelCorrect.ran(action, it) }
        }
        // fix-alarm-moved: a move's new alarm goes through setMoved (the clock's page), never a plain run.
        override fun setMoved(hour: Int, minute: Int): Executed = run(Action.SetAlarm(hour, minute)).also {
            sent[sent.lastIndex] = "show $hour:$minute"
            if (saves && it.status.succeeded) next = java.time.ZonedDateTime.of(2026, 11, 9, hour, minute, 0, 0, java.time.ZoneId.of("America/Halifax")).toInstant().toEpochMilli()
        }
        override fun now() = java.time.ZonedDateTime.of(2026, 11, 9, 5, 0, 0, 0, java.time.ZoneId.of("America/Halifax")).toInstant().toEpochMilli()
        override fun pause(ms: Long) {}
    }

    private fun said(clock: Clock, q: String): String {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        return PanelCorrect.handle(PanelCorrect.route(q)!!, clock, { false }, zone) { error("no context for an alarm") }.line.render(en)
    }

    private fun t(h: Int, m: Int) = app.heyrobot.core.EventText.clock(h, m)

    @Test fun aDailyAlarmDueSoonerMeansNothingIsSent() {
        val clock = Clock(next = at(9, 6, 0)) // your daily 6:00 rings before Robot's 6:15
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.moveLeftOn("from" to t(6, 15), "to" to t(6, 45)).render(en), said(clock, "no, 6:45"))
        assertEquals(emptyList<String>(), clock.sent)
        assertEquals(CorrectRouter.Done.Alarm::class, PanelCorrect.held()!!::class) // still Robot's 6:15, still on
    }

    @Test fun anUndoOfAnAlarmThatIsNotNextSendsNothing() {
        val clock = Clock(next = at(10, 7, 0)) // review Medium B: another day's alarm is next
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.offNotSent("time" to t(6, 15)).render(en), said(clock, "undo that"))
        assertEquals(emptyList<String>(), clock.sent)
        assertEquals(emptyList<String>(), Clock(next = null).also { said(it, "undo that") }.sent)
    }

    // fix-alarm-moved (phone-train-14-b): the moved 6:45 was set without the clock's page and no turn-off found it after.
    @Test fun aMoveSetsTheNewAlarmThroughTheClocksPage() {
        val clock = Clock(next = at(9, 6, 15), after = null)
        said(clock, "no, 6:45")
        assertEquals(listOf("off 6:15", "show 6:45"), clock.sent)
        assertEquals(45, (PanelCorrect.held() as CorrectRouter.Done.Alarm).minute) // still Robot's, so "undo that" finds it
    }

    // fix-batch-15 M1 (review-batch-15): the clock didn't keep the new one: Robot says to check its page, never "changed".
    @Test fun aMoveTheClockDidNotKeepSaysToCheckItsPage() {
        val clock = Clock(next = at(9, 6, 15), after = null, saves = false)
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.moveNotSeen("from" to t(6, 15), "to" to t(6, 45)).render(en), said(clock, "no, 6:45"))
        val hidden = Clock(next = at(9, 6, 15), after = at(9, 6, 30), saves = false) // a 6:30 rings first: Robot can't tell
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.alarmChanged("time" to t(6, 45)).render(en), said(hidden, "no, 6:45"))
    }

    @Test fun aPlainMoveTurnsOffThenSets() {
        val clock = Clock(next = at(9, 6, 15), after = null)
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.alarmChanged("time" to t(6, 45)).render(en), said(clock, "no, 6:45"))
        assertEquals(listOf("off 6:15", "show 6:45"), clock.sent)
        assertEquals(CorrectRouter.Done.Alarm(6, 45, 0L).minute, (PanelCorrect.held() as CorrectRouter.Done.Alarm).minute)
        val undo = Clock(next = at(9, 6, 15), after = at(10, 6, 15)) // a daily 6:15: the next one moves a day on
        assertEquals(app.heyrobot.core.i18n.ToolStrings.alarmOff("time" to t(6, 15)).render(en), said(undo, "undo that"))
        assertEquals(listOf("off 6:15"), undo.sent)
    }

    // review Low F: "no, 6:45" after an alarm at a time you already had is claimed and said, never sent or passed to the model.
    @Test fun aSharedAlarmsChangeIsClaimedAndSaid() {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""), shared = true)
        val ask = PanelCorrect.route("no, 6:45")!!
        val clock = Clock(next = at(9, 6, 15))
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.moveShared("to" to t(6, 45)).render(en), PanelCorrect.handle(ask, clock, { false }, zone) { error("none") }.line.render(en))
        assertEquals(emptyList<String>(), clock.sent)
    }

    // review Medium C2: locked (or the lock check failing), a move or an undo of Robot's alarm sends nothing.
    @Test fun aLockedMoveOrUndoSendsNothing() {
        for (q in listOf("no, 6:45", "undo that")) for (locked in listOf<() -> Boolean>({ true }, { error("no keyguard") })) {
            PanelCorrect.forget()
            PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
            val clock = Clock(next = at(9, 6, 15))
            val line = PanelCorrect.handle(PanelCorrect.route(q)!!, clock, locked, zone) { error("none") }.line.render(en)
            assertEquals(q, if (q == "undo that") app.heyrobot.core.i18n.CorrectStrings.unlockUndo().render(en) else app.heyrobot.core.i18n.CorrectStrings.unlockChange().render(en), line)
            assertEquals(q, emptyList<String>(), clock.sent)
            assertEquals(CorrectRouter.Done.Alarm::class, PanelCorrect.held()!!::class)
        }
    }

    /** A clock whose next alarm moves on only [after] pauses of 250 ms ([after] null: never). */
    private class Slow(val after: Int?, val stopAt: Int? = null) : PanelCorrect.Hands {
        override fun stopped() = stopAt != null && pauses >= stopAt
        var pauses = 0
        override fun nextAlarm(): Long? = if (after != null && pauses >= after) null else java.time.ZonedDateTime.of(2026, 11, 9, 6, 15, 0, 0, java.time.ZoneId.of("America/Halifax")).toInstant().toEpochMilli()
        override fun sendDismiss(hour: Int, minute: Int) = true
        override fun run(action: Action): Executed = error("nothing is set")
        override fun pause(ms: Long) { pauses++ }
    }

    // review Low H: a clock slow from a cold start is waited for up to 5 s, then still on.
    // fix-batch-15-lows c: a stop during a move's read-back ends it at the next read, not 5 s later.
    @Test fun aStopEndsTheMovesReadBack() {
        var pauses = 0
        val hands = object : PanelCorrect.Hands {
            override fun nextAlarm(): Long? = null
            override fun sendDismiss(hour: Int, minute: Int) = true
            override fun run(action: Action): Executed = error("nothing is set")
            override fun pause(ms: Long) { pauses++ }
            override fun stopped() = pauses >= 2
        }
        assertEquals(CorrectRouter.Landed.MISSING, PanelCorrect.landed(hands, 6, 45, zone))
        assertEquals(2, pauses)
    }

    @Test fun aSlowClockIsWaitedForUpTo5s() {
        val slow = Slow(after = 12) // 3 s
        assertEquals(CorrectRouter.Off.GONE, PanelCorrect.turnOff(slow, 6, 15, zone))
        assertEquals(12, slow.pauses)
        val never = Slow(after = null)
        assertEquals(CorrectRouter.Off.STILL_ON, PanelCorrect.turnOff(never, 6, 15, zone))
        assertEquals(5_000L, never.pauses * PanelCorrect.OFF_STEP_MS)
    }

    // review-d Low: "stop" or the wake word in the wait: nothing more is sent, and one line says what rings now.
    @Test fun stopDuringTheWaitEndsTheMove() {
        val off = Clock(at(9, 6, 15), after = null).apply { stopOnSend = true }
        val line = said(off, "no, 6:45")
        assertEquals(listOf("off 6:15"), off.sent)
        assertEquals(CorrectRouter.offStoppedNotSet(CorrectRouter.Done.Alarm(6, 15, 0L), 6, 45).render(en), line)
        val on = Slow(after = null, stopAt = 3)
        assertEquals(CorrectRouter.Off.STILL_ON, PanelCorrect.turnOff(on, 6, 15, zone))
        assertEquals(PanelCorrect.OFF_STEPS, on.pauses) // review-e High: the stop doesn't cut the wait once sent
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        val still = PanelCorrect.handle(PanelCorrect.route("no, 6:45")!!, Slow(after = null, stopAt = 1), { false }, zone) { error("no context") }.line.render(en)
        assertEquals(CorrectRouter.moveSaid(CorrectRouter.Off.STILL_ON, CorrectRouter.Done.Alarm(6, 15, 0L), 6, 45).render(en), still)
        val early = Clock(at(9, 6, 15), after = null).apply { stop = true }
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.offStopped("time" to t(6, 15)).render(en), said(early, "undo that"))
        assertEquals(emptyList<String>(), early.sent)
    }

    // review-e High: a stop just after the send, then the clock turns the alarm off: Robot says it's off, never "still on".
    @Test fun aStopAfterTheSendWaitsForTheReadBack() {
        fun said(q: String): String {
            PanelCorrect.forget()
            PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
            return PanelCorrect.handle(PanelCorrect.route(q)!!, Slow(after = 4, stopAt = 1), { false }, zone) { error("no context") }.line.render(en)
        }
        assertEquals(app.heyrobot.core.i18n.ToolStrings.alarmOff("time" to t(6, 15)).render(en), said("undo that"))
        assertEquals(CorrectRouter.offStoppedNotSet(CorrectRouter.Done.Alarm(6, 15, 0L), 6, 45).render(en), said("no, 6:45"))
    }

    // review-f Low: a turn is stopped by more than the person (a lock starting, the panel closing), so no stop line says who.
    @Test fun aStopLineNamesNoOneAsTheStopper() {
        val fr = app.heyrobot.core.i18n.Langs(Lang.FR, Lang.FR, Lang.FR)
        val a = CorrectRouter.Done.Alarm(6, 15, 0L)
        for (l in listOf(en, fr)) for (m in listOf(CorrectRouter.offSaid(CorrectRouter.Off.STOPPED, 6, 15), CorrectRouter.moveSaid(CorrectRouter.Off.STOPPED, a, 6, 45), CorrectRouter.offStoppedNotSet(a, 6, 45))) {
            val line = m.render(l)
            for (who in listOf("You stopped", "you stopped", "Tu as arrêté", "tu l\u2019as arrêté")) org.junit.Assert.assertFalse(line, line.contains(who))
            org.junit.Assert.assertTrue(line, line.contains("was stopped") || line.contains("été arrêté"))
        }
    }

    // review-e Low: a stop says it stopped (EN and FR); a stopped turn's line is held for its stop handler to show, once.
    @Test fun aStopSaysItStopped() {
        val fr = app.heyrobot.core.i18n.Langs(Lang.FR, Lang.FR, Lang.FR)
        val a = CorrectRouter.Done.Alarm(6, 15, 0L)
        assertEquals("Robot was stopped, so it didn't turn off the ${t(6, 15).render(en)} alarm or set ${t(6, 45).render(en)}.", CorrectRouter.moveSaid(CorrectRouter.Off.STOPPED, a, 6, 45).render(en))
        assertEquals("Robot a été arrêté, alors il n'a pas désactivé l'alarme de ${t(6, 15).render(fr)} ni mis d'alarme à ${t(6, 45).render(fr)}.".replace('\'', '\u2019'), CorrectRouter.moveSaid(CorrectRouter.Off.STOPPED, a, 6, 45).render(fr))
        assertEquals("Robot a été arrêté, alors il n'a pas désactivé l'alarme de ${t(6, 15).render(fr)}.".replace('\'', '\u2019'), CorrectRouter.offSaid(CorrectRouter.Off.STOPPED, 6, 15).render(fr))
        assertEquals("Robot a désactivé l'alarme de ${t(6, 15).render(fr)}, mais il a été arrêté avant de mettre une alarme à ${t(6, 45).render(fr)}. Crée-la dans ton appli d'horloge.".replace('\'', '\u2019'), CorrectRouter.offStoppedNotSet(a, 6, 45).render(fr))
        val stop = Clock(at(9, 6, 15), after = null).apply { stopOnSend = true }
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        val r = PanelCorrect.hold("r1", PanelCorrect.handle(PanelCorrect.route("no, 6:45")!!, stop, { false }, zone) { error("no context") })
        assertEquals(CorrectRouter.offStoppedNotSet(a, 6, 45).render(en), PanelCorrect.taken("r1")!!.render(en))
        assertEquals(r.line.render(en), CorrectRouter.offStoppedNotSet(a, 6, 45).render(en))
        assertNull(PanelCorrect.taken("r1"))
    }

    // review Low: the app's "day before" line, and the other two.
    @Test fun aMoveSaysWhichDay() {
        fun d(day: Int, h: Int, m: Int) = at(day, h, m)
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.eventMovedDayBefore("time" to t(23, 30)).render(en), PanelCorrect.movedSaid(d(10, 0, 30), d(9, 23, 30), zone).render(en))
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.eventMovedNextDay("time" to t(0, 30)).render(en), PanelCorrect.movedSaid(d(9, 23, 0), d(10, 0, 30), zone).render(en))
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.eventMoved("time" to t(19, 30)).render(en), PanelCorrect.movedSaid(d(10, 7, 0), d(10, 19, 30), zone).render(en))
    }

    @Test fun aClockThatRefusesSetsNothingNew() {
        val still = Clock(next = at(9, 6, 15), turnsOff = false)
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.moveStillOn("from" to t(6, 15), "to" to t(6, 45)).render(en), said(still, "no, 6:45"))
        assertEquals(listOf("off 6:15"), still.sent)
        val none = Clock(next = at(9, 6, 15), clock = false)
        assertEquals(app.heyrobot.core.i18n.ToolStrings.noClockAlarms().render(en), said(none, "no, 6:45"))
        assertEquals(listOf("off 6:15"), none.sent)
        // review Low E: off, then the set fails: both are named.
        val fails = Clock(next = at(9, 6, 15), sets = false)
        assertEquals(app.heyrobot.core.i18n.CorrectStrings.moveOffOnly("from" to t(6, 15), "to" to t(6, 45)).render(en), said(fails, "no, 6:45"))
        assertEquals(listOf("off 6:15", "show 6:45"), fails.sent)
        assertNull(PanelCorrect.held())
    }

    @Test fun aChangeIsClaimedOnlyAfterAnAction() {
        PanelCorrect.forget()
        assertNull(PanelCorrect.route("no, 6:45"))
        assertEquals(CorrectRouter.Ask.Undo(Lang.EN), PanelCorrect.route("undo that"))
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        assertEquals(CorrectRouter.Ask.Change(6, 45, null, Lang.EN), PanelCorrect.route("no, 6:45"))
    }

    @Test fun aFailedActionIsNotRecorded() {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.FAILED, ""))
        assertNull(PanelCorrect.route("no, 6:45"))
    }

    // review High 1: the turn-off goes as the runners' proven delete (24-hour HOUR, no EXTRA_IS_PM), here and in PanelRoutines.
    @Test fun alarmTurnOffsSendNoIsPm() {
        val root = java.io.File("src/main/java/app/heyrobot/assistant")
        for (f in listOf("ToolExecutor.kt", "routines/PanelRoutines.kt"))
            org.junit.Assert.assertFalse(f, java.io.File(root, f).readText().contains("AlarmClock.EXTRA_IS_PM"))
    }

    // review High 2: every action replaces the record ("set an alarm for 6:15", the flashlight, "undo that": 6:15 stays on).
    @Test fun anyOtherActionReplacesTheRecord() {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        PanelCorrect.ran(Action.Flashlight(true), Executed(ActionStatus.DONE, ""))
        assertEquals(CorrectRouter.Done.Kept::class, PanelCorrect.held()!!::class)
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""))
        PanelCorrect.ran(Action.SetTimer(60), Executed(ActionStatus.FAILED, ""))
        assertNull(PanelCorrect.held())
    }

    // review High 2: a note or an event recorded while its tool ran stays the record.
    @Test fun aRecordMadeDuringTheToolStays() {
        PanelCorrect.forget()
        val mark = PanelCorrect.held()
        PanelCorrect.note(Note("n1", "", "milk", 1L, 1L))
        PanelCorrect.ran(Action.Flashlight(true), Executed(ActionStatus.DONE, ""), mark)
        assertEquals(CorrectRouter.Done.Note::class, PanelCorrect.held()!!::class)
    }

    // review-f Low: while a stopped move ends its read-back, the mic and the camera say to ask again; nothing is dropped unsaid.
    @Test fun aQuestionDuringTheReadBackIsNotDroppedSilently() {
        val panel = java.io.File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
        for ((from, to) in listOf("fun micPressed()" to "fun micReleased()", "fun openCapture(" to "if (h.startCapture(")) {
            val body = panel.substring(panel.indexOf(from), panel.indexOf(to, panel.indexOf(from)))
            org.junit.Assert.assertFalse(from, Regex("slot\\.running\\) return\\s*\\n").containsMatchIn(body))
            org.junit.Assert.assertTrue(from, body.contains("slot.running) return notify(PanelStrings.stillOnLastAskAgain())"))
        }
    }

    // review Medium 3: a routine's steps don't record; only the panel's executor does; review High 2: another router's turn forgets.
    @Test fun onlyThePanelsExecutorRecords() {
        val root = java.io.File("src/main/java/app/heyrobot/assistant")
        val routine = java.io.File(root, "routines/RoutineRunner.kt").readText()
        org.junit.Assert.assertFalse(routine.contains("records = true"))
        val panel = java.io.File(root, "PanelController.kt").readText()
        org.junit.Assert.assertTrue(panel.contains("turnLangs.say, records = true)"))
        org.junit.Assert.assertTrue(panel.contains("if (fixCmd == null) PanelCorrect.forget()"))
        org.junit.Assert.assertTrue(panel.contains("PanelCorrect.keep(heldFix)"))
    }

    // review Medium 6: Robot's 6:15 next to your own 6:15 is never undone by time (the clock can't tell them apart).
    @Test fun anAlarmAtATimeAlreadySetIsKept() {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""), shared = true)
        assertEquals(CorrectRouter.Done.Kept(1L, alarm = true).alarm, (PanelCorrect.held() as CorrectRouter.Done.Kept).alarm)
    }

    // review Medium 4: the card's own line is the reply, a gone card is said so, and a new conversation forgets.
    @Test fun aCardsUndoSaysWhatItDid() {
        val en = app.heyrobot.core.i18n.Langs(Lang.EN, Lang.EN, Lang.EN)
        assertEquals("Can't undo while locked.", PanelCorrect.cardSaid("Can't undo while locked.", en))
        assertEquals("Robot asked to undo it.", PanelCorrect.cardSaid(null, en)) // review Low G
        val panel = java.io.File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
        org.junit.Assert.assertTrue(panel.contains("else if (!undo(card)) localReply(replyId, CorrectStrings.goneSince()"))
        org.junit.Assert.assertTrue(panel.contains("helperChoice.forget()\n        PanelCorrect.forget()"))
    }

    // review Medium 5: "add eggs", "what time is it?", "undo that" leaves the eggs (LS10); the card's own Undo stays.
    @Test fun aListCardIsNotUndoneByUndoThat() {
        PanelCorrect.forget()
        PanelCorrect.card("a1", "lists:x")
        assertEquals(CorrectRouter.Done.Kept::class, PanelCorrect.held()!!::class)
    }

    // review 11: after a text, or an old alarm, "no, 10" goes to the model, not to a change.
    @Test fun aChangeIsClaimedOnlyForAFreshAlarmOrEvent() {
        PanelCorrect.forget()
        PanelCorrect.ran(Action.SendText("Sam", "hi"), Executed(ActionStatus.DONE, ""))
        assertNull(PanelCorrect.route("no, 10"))
        PanelCorrect.ran(Action.SetAlarm(6, 15), Executed(ActionStatus.DONE, ""), now = 1L)
        assertNull(PanelCorrect.route("no, 6:45"))
    }
}
