package app.heyrobot.core

import app.heyrobot.core.output.HoldWhy
import app.heyrobot.core.output.Keep
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the app runs routines with (0.8): the built-in brief, a run's plan, the spoken parts, missed runs, outputs, the editor. */
class RoutineRuntimeTest {
    private val zone = ZoneId.of("America/Toronto")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private fun tool(name: String, vararg args: Pair<String, Any?>) = RoutineStep.Tool((ToolCalls.parse(RawToolCall(name, args.toMap())) as ParsedCall.Ok).action)

    @Test fun theBriefIsARoutineOfItsOwn() {
        val off = BriefRoutine.of(RoutineSettings())
        assertEquals(BriefRoutine.ID, off.id)
        assertFalse(off.enabled)
        assertEquals(listOf(RoutineStep.Brief), off.steps)
        assertEquals(RoutineTrigger.AlarmDismissed(TimeWindow.ALARM), off.trigger)
        assertEquals(emptySet<OnlineUse>(), off.online)
        assertTrue(RoutineCheck.valid(off))
        // Turned on through its card: Open-Meteo listed, the alarm's hours as set.
        val window = TimeWindow(LocalTime.of(5, 0), LocalTime.of(10, 0))
        val on = BriefRoutine.of(RoutineSettings(briefEnabled = true, briefOnline = true, alarmWindow = window, briefOutput = RoutineOutput.HEADSET_ONLY))
        assertTrue(on.enabled)
        assertEquals(setOf(OnlineUse.OPEN_METEO), on.online)
        assertEquals(RoutineTrigger.AlarmDismissed(window), on.trigger)
        assertEquals(RoutineOutput.HEADSET_ONLY, on.output)
        // Its "When" choices keep the time already set.
        val seven = RoutineTrigger.At(LocalTime.of(6, 45), RoutineWords.ALL)
        assertEquals(BriefRoutine.When.TIME, BriefRoutine.When.of(seven))
        assertEquals(seven, BriefRoutine.trigger(BriefRoutine.When.TIME, seven))
        assertEquals(RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), BriefRoutine.trigger(BriefRoutine.When.TIME, RoutineTrigger.Asked))
        assertEquals(RoutineTrigger.Unlock(), BriefRoutine.trigger(BriefRoutine.When.UNLOCK, seven))
        assertEquals(RoutineTrigger.Asked, BriefRoutine.trigger(BriefRoutine.When.ASKED, seven))
        assertEquals(BriefRoutine.When.ALARM, BriefRoutine.When.of(RoutineTrigger.AlarmDismissed()))
    }

    @Test fun theStateFileRoundTripsAndADamagedOneStartsFresh() {
        val st = RoutineState(checked = 5L, runs = mapOf("r-1" to RunRecord(9L, TriggerKind.ALARM, 2, 1, 1)))
        assertEquals(st, RoutineStateFile.decode(RoutineStateFile.encode(st)))
        assertEquals(RoutineState(), RoutineStateFile.decode("{nope"))
        assertEquals(RoutineState(), RoutineStateFile.decode(null))
        // The ledger and AlarmWatch read as RoutineStateCodec always did.
        val ledger = RunLedger().record("r-1", "a:5", at(2026, 10, 12, 6, 50))
        assertEquals(ledger, RoutineStateFile.decode(RoutineStateFile.encode(RoutineState(ledger))).ledger)
        assertEquals(RoutineState(ledger), RoutineState(ledger, runs = mapOf("gone" to RunRecord(1, TriggerKind.TIME, 1))).keep(setOf("r-1")))
    }

    @Test fun aRunsPlanFollowsTheGate() {
        val r = Routine("r-1", "Leaving work", RoutineTrigger.At(LocalTime.of(17, 0), RoutineWords.WEEKDAYS),
            listOf(RoutineStep.Brief, tool("weather"), tool("open_app", "name" to "Signal"), tool("set_timer", "minutes" to 5)), created = 1,
            online = setOf(OnlineUse.OPEN_METEO))
        val s = RobotSettings()
        val unlocked = RoutinePlan.plan(r, RunOrigin.UNATTENDED, false, s, RoutineSettings())
        assertEquals(listOf(StepGo.RUN, StepGo.RUN, StepGo.READY, StepGo.RUN), unlocked.map { it.go })
        val locked = RoutinePlan.plan(r, RunOrigin.UNATTENDED, true, s, RoutineSettings())
        assertEquals(listOf(StepGo.RUN_PUBLIC, StepGo.RUN_PUBLIC, StepGo.READY, StepGo.RUN), locked.map { it.go })
        val offline = RoutinePlan.plan(r, RunOrigin.UNATTENDED, false, s, RoutineSettings(online = false))
        assertEquals(StepGo.SKIP_OFFLINE, offline[1].go)
        assertEquals("Weather skipped: routines may not go online.", RoutinePlan.skipLine(StepGo.SKIP_OFFLINE, r.steps[1], BriefLang.EN))
        assertEquals("Météo sautée : la carte de cette routine ne l'indiquait pas.", RoutinePlan.skipLine(StepGo.SKIP_NO_CONSENT, r.steps[1], BriefLang.FR))
        assertNull(RoutinePlan.skipLine(StepGo.READY, r.steps[2], BriefLang.EN))
        assertTrue(RoutinePlan.runs(StepGo.RUN_PUBLIC) && !RoutinePlan.runs(StepGo.WAIT_UNLOCK))
        assertEquals(listOf("1 thing is ready for you on the phone.", "Unlock to hear the rest."), RoutinePlan.closing(1, 2, BriefLang.EN))
        assertEquals(listOf("2 choses t'attendent sur le téléphone."), RoutinePlan.closing(2, 0, BriefLang.FR))
        assertEquals(emptyList<String>(), RoutinePlan.closing(0, 0, BriefLang.EN))
    }

    @Test fun nextSkipRepeatAndStopMoveThroughTheParts() {
        fun part(n: Int) = SpokenPart(listOf("line $n"), false, StepCode.BRIEF)
        var p = PlayState(listOf(part(0), part(1), part(2)))
        assertEquals(part(0), p.current)
        p = p.started()
        // "repeat" during a part says it again from its start.
        assertEquals(0, p.control(BriefControl.REPEAT).at)
        p = p.played()
        // Between parts, "repeat" is the one just said.
        assertEquals(0, p.control(BriefControl.REPEAT).at)
        p = p.started().control(BriefControl.NEXT)
        assertEquals(2, p.at)
        assertFalse(p.inPart)
        p = p.control(BriefControl.SKIP)
        assertTrue(p.done)
        assertEquals(3, p.control(BriefControl.NEXT).at)
        // More parts can come while it speaks (the weather after the greeting).
        p = PlayState(listOf(part(0))).started().played().plus(listOf(part(1)))
        assertEquals(part(1), p.current)
        val stopped = p.control(BriefControl.STOP)
        assertTrue(stopped.done)
        assertNull(stopped.current)
        // "repeat" after a stop plays the last part again.
        assertEquals(part(0), stopped.control(BriefControl.REPEAT).current)
    }

    @Test fun theBriefsLinesBecomeOnePartPerItem() {
        val now = at(2026, 10, 12, 7, 2)
        val input = BriefInput(now, BriefLang.EN, reminders = listOf("bring the passport"), events = emptyList())
        val locked = BriefComposer.compose(input, locked = true)
        val parts = BriefParts.of(locked.spoken)
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, null), parts.map { it.item })
        assertEquals(2, parts[0].lines.size)
        assertEquals(listOf("Unlock to hear your day."), parts.last().lines)
        assertFalse(parts.any { it.personal })
        val held = BriefParts.of(locked.held)
        assertEquals(listOf(BriefItem.CALENDAR, BriefItem.REMINDERS, BriefItem.CLOSING), held.map { it.item })
        assertTrue(held[0].personal && held[1].personal && !held[2].personal)
        assertEquals("Reminder: bring the passport.", held[1].text)
    }

    @Test fun aRebootOrDozeMissedRunIsRunOfferedOrDropped() {
        val weekday = Routine("r-1", "Morning", RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), listOf(RoutineStep.Brief), created = 1)
        val off = weekday.copy(id = "r-2", enabled = false)
        val since = at(2026, 10, 11, 23, 0)
        // Monday 2026-10-12: 5 minutes late runs, 2 hours late is a Ready card, 4 hours late is nothing.
        assertEquals(listOf(MissedRun.RUN), RoutineCatchUp.late(listOf(weekday, off), since, at(2026, 10, 12, 7, 5), RunLedger()).map { it.what })
        val offer = RoutineCatchUp.late(listOf(weekday), since, at(2026, 10, 12, 9, 0), RunLedger()).single()
        assertEquals(MissedRun.OFFER, offer.what)
        assertEquals(LocalDateTime.of(2026, 10, 12, 7, 0), offer.slot)
        assertEquals(MissedRun.DROP, RoutineCatchUp.late(listOf(weekday), since, at(2026, 10, 12, 11, 0), RunLedger()).single().what)
        // It already ran (the ledger has that occurrence): nothing.
        val ran = RunLedger().record("r-1", RoutineTriggers.timeKey(LocalDateTime.of(2026, 10, 12, 7, 0)), at(2026, 10, 12, 7, 0))
        assertEquals(emptyList<LateRun>(), RoutineCatchUp.late(listOf(weekday), since, at(2026, 10, 12, 7, 5), ran))
        // A week off: only the latest occurrence counts, and it is too old.
        val week = RoutineCatchUp.late(listOf(weekday), at(2026, 10, 2, 0, 0), at(2026, 10, 12, 6, 0), RunLedger())
        assertEquals(listOf(LocalDateTime.of(2026, 10, 9, 7, 0)), week.map { it.slot })
        assertEquals(MissedRun.DROP, week.single().what)
        // The exact alarm that came late, as the receiver sees it.
        val due = at(2026, 10, 12, 7, 0).toInstant().toEpochMilli()
        assertEquals(MissedRun.RUN, RoutineCatchUp.fired(due, at(2026, 10, 12, 7, 9)))
        assertEquals(MissedRun.OFFER, RoutineCatchUp.fired(due, at(2026, 10, 12, 7, 30)))
    }

    @Test fun onlyTheReceiversSomeRoutineNeedsAreRegistered() {
        fun r(t: RoutineTrigger, on: Boolean = true) = Routine("r-${t.kind}", "X", t, listOf(RoutineStep.Brief), on, created = 1)
        assertEquals(emptySet<TriggerKind>(), TriggerNeeds.of(listOf(r(RoutineTrigger.Asked), r(RoutineTrigger.Unlock(), on = false))))
        assertEquals(setOf(TriggerKind.ALARM, TriggerKind.CHARGING, TriggerKind.TIME),
            TriggerNeeds.of(listOf(r(RoutineTrigger.AlarmDismissed()), r(RoutineTrigger.Charging()), r(RoutineTrigger.At(LocalTime.NOON, RoutineWords.ALL)))))
        assertEquals(setOf(TriggerKind.UNLOCK), TriggerNeeds.of(emptyList(), waitingForUnlock = true))
        assertEquals(setOf(TriggerKind.DEVICE), TriggerNeeds.of(listOf(r(RoutineTrigger.Device(DeviceKind.CAR)))))
        val alarm = listOf(r(RoutineTrigger.AlarmDismissed()))
        assertTrue(TriggerNeeds.prepareOnAlert(alarm, RoutineSettings()))
        assertFalse(TriggerNeeds.prepareOnAlert(alarm, RoutineSettings(briefPrepare = false)))
        assertFalse(TriggerNeeds.prepareOnAlert(listOf(r(RoutineTrigger.AlarmDismissed()).copy(output = RoutineOutput.SILENT)), RoutineSettings()))
    }

    @Test fun outputGuardDecidesEachPart() {
        val public = SpokenPart(listOf("It's 7:02."), false, StepCode.BRIEF)
        val personal = SpokenPart(listOf("You have 3 events today."), true, StepCode.BRIEF)
        val unlocked = PhoneState(locked = false)
        val locked = PhoneState(locked = true)
        fun d(p: SpokenPart, o: RoutineOutput = RoutineOutput.HEADSET_OR_SPEAKER, phone: PhoneState = unlocked, headset: SeenDevice? = null,
            trust: Trust = Trust.NOT_PRIVATE, everything: Boolean = false) = RoutineOutputs.decide(p, o, phone, headset, trust, everything)
        assertTrue(d(public) is OutputDecision.Speak)
        assertTrue(d(personal) is OutputDecision.Speak)
        // Locked: the personal part waits for the unlock, "Unlock to hear it." said instead; at "Everything" it plays.
        val held = d(personal, phone = locked)
        assertEquals(OutputDecision.Hold(HoldWhy.LOCKED, Keep.UNLOCK, sayUnlock = true), held)
        assertTrue(RoutineOutputs.waitsForUnlock(held))
        assertTrue(d(personal, phone = locked, everything = true) is OutputDecision.Speak)
        assertTrue(d(public, phone = locked) is OutputDecision.Speak)
        // Nothing talks over a call.
        assertEquals(HoldWhy.CALL, RoutineOutputs.why(d(public, phone = PhoneState(locked = false, callActive = true))))
        // "Don't speak".
        assertNull(d(public, RoutineOutput.SILENT))
        // "Headset only": nothing on the speaker; personal only on a private headset.
        assertEquals(HoldWhy.DEVICE_GONE, RoutineOutputs.why(d(public, RoutineOutput.HEADSET_ONLY)))
        val wired = SeenDevice(RouteType.WIRED_HEADSET, "Headset")
        assertTrue(d(personal, RoutineOutput.HEADSET_ONLY, headset = wired, trust = Trust.PRIVATE) is OutputDecision.Speak)
        assertEquals(HoldWhy.NOT_PRIVATE, RoutineOutputs.why(d(personal, RoutineOutput.HEADSET_ONLY, headset = wired, trust = Trust.NOT_PRIVATE)))
        assertTrue(d(personal, RoutineOutput.HEADSET_ONLY, headset = wired, trust = Trust.ASK) is OutputDecision.Ask)
    }

    @Test fun theEditorsStepsBuildFromOneArgument() {
        assertEquals(RoutineStep.Brief, StepChoice.BRIEF.build())
        assertEquals(tool("set_timer", "minutes" to 10), StepChoice.TIMER.build("10"))
        assertNull(StepChoice.TIMER.build("0"))
        assertNull(StepChoice.TIMER.build("ten"))
        assertEquals("A number of minutes, 1 to 1440.", StepChoice.TIMER.problem("ten"))
        assertEquals(tool("set_alarm", "hour" to 6, "minute" to 30), StepChoice.ALARM.build("06:30"))
        assertEquals(tool("set_alarm", "hour" to 6, "minute" to 30), StepChoice.ALARM.build("6 h 30"))
        assertNull(StepChoice.ALARM.build("25:00"))
        assertEquals("A time such as 06:30.", StepChoice.ALARM.problem("25:00"))
        assertEquals("Type what Robot says.", StepChoice.SAY.problem(" "))
        assertEquals(RoutineStep.Say("Time to go."), StepChoice.SAY.build(" Time to go. "))
        assertNull(StepChoice.SAY.build("x".repeat(RoutineCheck.MAX_SAY + 1)))
        assertEquals(tool("weather"), StepChoice.WEATHER.build())
        assertEquals(tool("read_calendar", "day" to "today"), StepChoice.CALENDAR.build())
        assertEquals("runs by itself · goes online: Open-Meteo (the weather)", StepChoice.WEATHER.badge())
        assertEquals("waits for you", StepChoice.OPEN_APP.badge())
        assertEquals("runs by itself", StepChoice.TIMER.badge())
        // Every choice makes a step a routine may hold, and none is one Robot never does by itself.
        for (c in StepChoice.entries) {
            val s = c.build(if (c == StepChoice.TIMER) "5" else if (c == StepChoice.ALARM) "07:15" else "Coffee")
            assertNotNull(c.name, s)
            assertTrue(c.name, RoutineGate.classOf(s!!) != StepClass.NEVER)
            assertTrue(c.name, RoutineCheck.valid(Routine("r-1", "X", RoutineTrigger.Asked, listOf(s), created = 1)))
        }
    }

    @Test fun settingsAndNotificationsSayItPlainly() {
        val now = at(2026, 10, 12, 9, 0)
        assertEquals("Not run yet", RoutineTexts.lastRun(null, now))
        val rec = RunRecord(at(2026, 10, 12, 6, 47).toInstant().toEpochMilli(), TriggerKind.ALARM, 1, 1, 0)
        assertEquals("Last run: today 06:47 · an alarm turned off · 1 step, 1 ready", RoutineTexts.lastRun(rec, now))
        assertEquals("Last run: yesterday 06:47 · an alarm turned off · 1 step, 1 ready", RoutineTexts.lastRun(rec, now.plusDays(1)))
        assertEquals("Last run: Monday October 12 06:47 · an alarm turned off · 1 step, 1 ready", RoutineTexts.lastRun(rec, now.plusDays(3)))
        val r = Routine("r-1", "Leaving work", RoutineTrigger.At(LocalTime.of(17, 0), RoutineWords.WEEKDAYS), listOf(RoutineStep.Brief, tool("weather")), created = 1)
        assertEquals("Every weekday at 17:00 · 2 steps", RoutineTexts.row(r))
        assertEquals("Only when you ask · 2 steps · off", RoutineTexts.row(r.copy(trigger = RoutineTrigger.Asked, enabled = false)))
        assertEquals("Robot is running “Leaving work”", RoutineTexts.speaking(r))
        assertEquals("Robot is reading your morning brief", RoutineTexts.speaking(BriefRoutine.of(RoutineSettings())))
        assertEquals("Morning brief ready", RoutineTexts.readyToPlay(BriefRoutine.of(RoutineSettings())))
        assertEquals("Your “Leaving work” routine is ready", RoutineTexts.notAssistant(r))
    }

    @Test fun aWholeRoutineThatDidntRunWaitsOnItsOwnReadyCard() {
        val day = LocalDate.of(2026, 10, 12)
        val missed = ReadyCard("r-1", "Morning", RoutineStep.Brief, 5L, day, missed = LocalTime.of(7, 0))
        assertEquals("Your “Morning” routine didn't run at 07:00. Tap to run it now.", missed.text())
        val step = ReadyCard("r-1", "Morning", RoutineStep.Brief, 5L, day)
        assertEquals("Your “Morning” routine is ready to: Read your morning brief. Tap to do it.", step.text())
        assertTrue(missed.key != step.key)
        // Both kinds keep their own place, and read back.
        val both = ReadyCards.add(ReadyCards.add(emptyList(), missed, day), step, day)
        assertEquals(2, both.size)
        assertEquals(both, ReadyCards.decode(ReadyCards.encode(both)))
    }

    @Test fun commandCodesCoverEveryRoutineCommand() {
        val draft = RoutineDraft(Routine("r-1", "X", RoutineTrigger.Asked, listOf(RoutineStep.Brief), created = 1), BriefLang.EN)
        val all = listOf(RoutineCommand.BriefNow, RoutineCommand.Create(draft), RoutineCommand.Run("x"), RoutineCommand.ListAll, RoutineCommand.Delete("x"),
            RoutineCommand.Remind("x", false))
        assertEquals(RoutineCommandCode.entries.toList(), all.map(RoutineCommandCode::of))
    }
}
