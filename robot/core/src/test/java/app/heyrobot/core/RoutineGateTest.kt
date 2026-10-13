package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineGateTest {
    private val defaults = RobotSettings()
    private val rs = RoutineSettings()

    private fun tool(call: String, vararg args: Pair<String, Any?>): RoutineStep =
        RoutineStep.Tool((ToolCalls.parse(RawToolCall(call, mapOf(*args))) as ParsedCall.Ok).action)

    /** A step of each kind, by name, for the table below. */
    private val steps: Map<String, RoutineStep> = linkedMapOf(
        "brief" to RoutineStep.Brief,
        "say" to RoutineStep.Say("Time for your pills"),
        "reminders" to RoutineStep.Reminders,
        "messages" to RoutineStep.Messages,
        "read_calendar" to tool("read_calendar", "day" to "today"),
        "find_notes" to tool("find_notes", "query" to "passport"),
        "phone_status" to tool("phone_status"),
        "lookup_contact" to tool("lookup_contact", "name" to "Marie"),
        "weather" to tool("weather"),
        "set_timer" to tool("set_timer", "minutes" to 5),
        "set_alarm" to tool("set_alarm", "hour" to 6, "minute" to 30),
        "flashlight" to tool("flashlight", "on" to true),
        "add_note" to tool("add_note", "text" to "Bought milk"),
        "open_app" to tool("open_app", "name" to "Organic Maps"),
        "maps_search" to tool("maps_search", "query" to "home"),
        "maps_navigate" to tool("maps_navigate", "destination" to "work"),
        "send_text" to tool("send_text", "to" to "Mom", "message" to "On my way"),
        "draft_email" to tool("draft_email", "to" to "boss", "body" to "Late"),
        "add_event" to tool("add_event", "title" to "Gym", "when" to "tomorrow 6pm"),
        "free_memory" to RoutineStep.Tool(Action.FreeMemory(null)),
        "free_storage" to RoutineStep.Tool(Action.FreeStorage(StorageWay.FILES)),
        "ask_claude" to RoutineStep.Tool(Action.AskHelper("news")),
    )

    private fun routine(step: RoutineStep, lock: RoutineLock = RoutineLock.FOLLOW, online: Set<OnlineUse> = setOf(OnlineUse.OPEN_METEO)) =
        Routine("r", "Test", RoutineTrigger.AlarmDismissed(), listOf(step), created = 0, lock = lock, online = online)

    private fun go(name: String, origin: RunOrigin, locked: Boolean, settings: RobotSettings = defaults, r: RoutineSettings = rs,
        lock: RoutineLock = RoutineLock.FOLLOW, online: Set<OnlineUse> = setOf(OnlineUse.OPEN_METEO)): StepGo {
        val s = steps.getValue(name)
        return RoutineGate.decide(s, origin, locked, routine(s, lock, online), settings, r)
    }

    /**
     * The policy with default settings (design §2.5 and §2.6), one row per step:
     * unattended unlocked, unattended locked, asked unlocked, asked locked.
     */
    private val table = """
        brief          RUN          RUN_PUBLIC   RUN      RUN_PUBLIC
        say            RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        reminders      RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        messages       RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        read_calendar  RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        find_notes     RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        phone_status   RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        lookup_contact RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        weather        RUN          RUN_PUBLIC   RUN      WAIT_UNLOCK
        set_timer      RUN          RUN          RUN      RUN
        set_alarm      RUN          RUN          RUN      RUN
        flashlight     RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        add_note       RUN          WAIT_UNLOCK  RUN      WAIT_UNLOCK
        open_app       READY        READY        RUN      WAIT_UNLOCK
        maps_search    READY        READY        RUN      WAIT_UNLOCK
        maps_navigate  READY        READY        RUN      WAIT_UNLOCK
        send_text      READY        READY        CONFIRM  WAIT_UNLOCK
        draft_email    READY        READY        CONFIRM  WAIT_UNLOCK
        add_event      READY        READY        CONFIRM  WAIT_UNLOCK
        free_memory    NEVER        NEVER        NEVER    NEVER
        free_storage   NEVER        NEVER        NEVER    NEVER
        ask_claude     NEVER        NEVER        NEVER    NEVER
    """.trimIndent()

    @Test fun thePolicyTable() {
        val rows = table.lines().map { it.trim().split(Regex("\\s+")) }
        assertEquals(steps.keys.toList(), rows.map { it[0] })
        for (row in rows) {
            val name = row[0]
            val got = listOf(go(name, RunOrigin.UNATTENDED, false), go(name, RunOrigin.UNATTENDED, true), go(name, RunOrigin.ASKED, false), go(name, RunOrigin.ASKED, true))
            assertEquals(name, row.drop(1), got.map { it.name })
            // A tapped Ready card is the same as asking.
            for (locked in listOf(false, true)) assertEquals(name, go(name, RunOrigin.ASKED, locked), go(name, RunOrigin.READY_TAP, locked))
        }
    }

    @Test fun stepClasses() {
        val expected = mapOf(
            StepClass.SPEAK to listOf("brief", "say"),
            StepClass.READ_LOCAL to listOf("reminders", "messages", "read_calendar", "find_notes", "phone_status", "lookup_contact"),
            StepClass.READ_ONLINE to listOf("weather"),
            StepClass.LOCAL_ACTION to listOf("set_timer", "set_alarm", "flashlight", "add_note"),
            StepClass.NEEDS_YOU to listOf("open_app", "maps_search", "maps_navigate", "send_text", "draft_email", "add_event"),
            StepClass.NEVER to listOf("free_memory", "free_storage", "ask_claude"),
        )
        for ((cls, names) in expected) for (n in names) assertEquals(n, cls, RoutineGate.classOf(steps.getValue(n)))
        assertEquals("waits for you", StepClass.NEEDS_YOU.badge)
    }

    /** class × origin × locked × lock set × online switch × consent × lock level × "run while locked" × the routine's lock. */
    @Test fun theFullMatrixKeepsItsPromises() {
        val lockSets = listOf(LockScreen.DEFAULT, emptySet(), setOf(ToolName.FLASHLIGHT, ToolName.ADD_NOTE), ToolName.entries.toSet())
        for (name in steps.keys) for (origin in RunOrigin.entries) for (locked in listOf(false, true)) for (lockSet in lockSets)
            for (online in listOf(true, false)) for (consent in listOf(true, false)) for (level in BriefLock.entries)
                for (runLocked in listOf(true, false)) for (lock in RoutineLock.entries) {
                    val s = steps.getValue(name)
                    val cls = RoutineGate.classOf(s)
                    val settings = defaults.copy(lockedTools = lockSet)
                    val r = rs.copy(online = online, briefLock = level, runLocked = runLocked)
                    val use = if (consent) setOf(OnlineUse.OPEN_METEO) else emptySet()
                    val g = go(name, origin, locked, settings, r, lock, use)
                    val what = "$name $origin locked=$locked set=$lockSet online=$online consent=$consent $level runLocked=$runLocked $lock → $g"
                    // Never offered: refused from anywhere.
                    if (cls == StepClass.NEVER) { assertEquals(what, StepGo.NEVER, g); continue }
                    assertNotEquals(what, StepGo.NEVER, g)
                    if (origin == RunOrigin.UNATTENDED) {
                        // Nobody is there to answer a card.
                        assertNotEquals(what, StepGo.CONFIRM, g)
                        // Opening apps, drafts, maps and new events always wait for you.
                        assertEquals(what, cls == StepClass.NEEDS_YOU, g == StepGo.READY)
                        // Locked, nothing personal is said and no action outside the lock-screen set runs.
                        if (locked && g == StepGo.RUN) {
                            assertTrue(what, runLocked && lock == RoutineLock.FOLLOW)
                            when (cls) {
                                StepClass.LOCAL_ACTION -> assertTrue(what, s.tool in lockSet)
                                StepClass.READ_LOCAL, StepClass.READ_ONLINE -> assertEquals(what, BriefLock.EVERYTHING, level)
                                StepClass.SPEAK -> assertEquals(what, BriefLock.EVERYTHING, level)
                                else -> Unit
                            }
                        }
                        if (locked && g == StepGo.RUN_PUBLIC) assertEquals(what, BriefLock.TIME_WEATHER, level)
                        // Online only with the switch and the consent on the save card.
                        if (cls == StepClass.READ_ONLINE && (g == StepGo.RUN || g == StepGo.RUN_PUBLIC)) assertTrue(what, online && consent)
                        if (cls == StepClass.READ_ONLINE && !(locked && (!runLocked || lock == RoutineLock.WAIT_FOR_UNLOCK))) {
                            if (!online) assertEquals(what, StepGo.SKIP_OFFLINE, g) else if (!consent) assertEquals(what, StepGo.SKIP_NO_CONSENT, g)
                        }
                        // "Routines can run while the phone is locked" off, or "Wait for unlock": held.
                        if (locked && (!runLocked || lock == RoutineLock.WAIT_FOR_UNLOCK) && cls != StepClass.NEEDS_YOU) assertEquals(what, StepGo.WAIT_UNLOCK, g)
                        if (!locked && cls != StepClass.NEEDS_YOU && cls != StepClass.READ_ONLINE) assertEquals(what, StepGo.RUN, g)
                    } else if (s is RoutineStep.Tool) {
                        // Asked or tapped: exactly ApprovalGate's answer for a typed command.
                        val expected = when (ApprovalGate.decide(s.action.tool, locked, settings, asked = true, content = false)) {
                            GateDecision.RUN -> StepGo.RUN
                            GateDecision.CONFIRM -> StepGo.CONFIRM
                            GateDecision.NEEDS_UNLOCK -> StepGo.WAIT_UNLOCK
                            GateDecision.DISABLED -> StepGo.DISABLED
                        }
                        assertEquals(what, expected, g)
                        assertFalse(what, g == StepGo.SKIP_OFFLINE || g == StepGo.SKIP_NO_CONSENT || g == StepGo.READY)
                    }
                }
    }

    @Test fun settingsActionsStillCount() {
        val off = defaults.copy(approvals = defaults.approvals + (ToolName.SET_TIMER to Approval.OFF))
        val ask = defaults.copy(approvals = defaults.approvals + (ToolName.SET_TIMER to Approval.ASK) + (ToolName.WEATHER to Approval.ASK))
        for (o in RunOrigin.entries) assertEquals(StepGo.DISABLED, go("set_timer", o, false, off))
        // "Ask first" can't be answered by nobody: a Ready card.
        assertEquals(StepGo.READY, go("set_timer", RunOrigin.UNATTENDED, false, ask))
        assertEquals(StepGo.READY, go("weather", RunOrigin.UNATTENDED, false, ask))
        assertEquals(StepGo.CONFIRM, go("set_timer", RunOrigin.ASKED, false, ask))
    }

    @Test fun theBriefsWeatherNeedsTheSwitchAndTheConsent() {
        val r = routine(RoutineStep.Brief)
        assertNull(RoutineGate.briefWeather(r, RunOrigin.UNATTENDED, rs))
        assertEquals(BriefWeatherMissing.NOT_ONLINE, RoutineGate.briefWeather(r, RunOrigin.UNATTENDED, rs.copy(online = false)))
        assertEquals(BriefWeatherMissing.NOT_ONLINE, RoutineGate.briefWeather(r.copy(online = emptySet()), RunOrigin.UNATTENDED, rs))
        assertNull(RoutineGate.briefWeather(r.copy(online = emptySet()), RunOrigin.ASKED, rs.copy(online = false)))
    }

    @Test fun theBriefOnTheLockScreen() {
        val all = BriefItem.DEFAULTS
        assertEquals(BriefPlan(all, emptyList(), true), BriefPrivacy.plan(all, locked = false, level = BriefLock.TIME_WEATHER))
        assertEquals(BriefPlan(all, emptyList(), true), BriefPrivacy.plan(all, locked = true, level = BriefLock.EVERYTHING))
        val tw = BriefPrivacy.plan(all, locked = true, level = BriefLock.TIME_WEATHER)
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER), tw.spoken)
        assertEquals(listOf(BriefItem.CALENDAR, BriefItem.REMINDERS, BriefItem.MESSAGES, BriefItem.CLOSING), tw.held)
        assertFalse(tw.placeName)
        assertTrue(tw.unlockLine)
        val nothing = BriefPrivacy.plan(all, locked = true, level = BriefLock.NOTHING)
        assertEquals(emptyList<BriefItem>(), nothing.spoken)
        assertEquals(all, nothing.held)
        // Nothing personal chosen: no "Unlock to hear your day", and the closing is said.
        val pub = BriefPrivacy.plan(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.CLOSING), locked = true, level = BriefLock.TIME_WEATHER)
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.CLOSING), pub.spoken)
        assertFalse(pub.unlockLine)
    }

    @Test fun theHeldPartContinuesAtAnUnlockSoonAfter() {
        val z = ZoneId.of("America/Toronto")
        val held = ZonedDateTime.of(2026, 10, 12, 6, 47, 0, 0, z)
        assertTrue(RoutineGate.resumes(held, held.plusMinutes(15), finishOnUnlock = true))
        assertFalse(RoutineGate.resumes(held, held.plusMinutes(16), finishOnUnlock = true))
        assertFalse(RoutineGate.resumes(held, held.plusMinutes(1), finishOnUnlock = false))
        assertFalse(RoutineGate.resumes(held, held.minusMinutes(1), finishOnUnlock = true))
        // A missed alarm's whole brief waits for the first unlock that day.
        assertTrue(RoutineGate.resumes(held, held.plusHours(3), finishOnUnlock = true, whole = true))
        assertFalse(RoutineGate.resumes(held, held.plusDays(1), finishOnUnlock = true, whole = true))
    }

    @Test fun readyCardsExpireAtTheEndOfTheDayAndSayLittleWhenLocked() {
        val today = LocalDate.of(2026, 10, 12)
        val card = ReadyCard("r-1", "Leaving work", tool("maps_search", "query" to "home"), 1_000L, today)
        assertEquals("Your “Leaving work” routine is ready to: Search Organic Maps for “home”. Tap to do it.", card.text())
        assertEquals("Robot has 1 thing ready for you", ReadyCards.publicLine(1))
        assertEquals("Robot has 2 things ready for you", ReadyCards.publicLine(2))
        val old = card.copy(day = today.minusDays(1), routineId = "r-0")
        val list = ReadyCards.add(listOf(old, card), card.copy(created = 2_000L), today)
        assertEquals(listOf(card.copy(created = 2_000L)), list)
        assertEquals(list, ReadyCards.decode(ReadyCards.encode(list)))
        assertEquals(emptyList<ReadyCard>(), ReadyCards.decode("""{"v":9,"cards":[]}"""))
        // A never-offered step in the file is not a card.
        val bad = ReadyCards.encode(listOf(card.copy(step = RoutineStep.Tool(Action.FreeStorage(StorageWay.FILES)))))
        assertEquals(emptyList<ReadyCard>(), ReadyCards.decode(bad))
        assertEquals(emptyList<ReadyCard>(), ReadyCards.live(list, today.plusDays(1)))
        assertNull(ReadyCards.runSummary("Morning", 3, 0, 0, 0))
        assertEquals("Ran “Leaving work” · 2 done, 1 ready", ReadyCards.runSummary("Leaving work", 2, 1, 0, 0))
        assertEquals("Ran “Morning” · 1 done, 2 waiting for unlock, 1 didn't work", ReadyCards.runSummary("Morning", 1, 0, 2, 1))
    }

    /**
     * A Ready notification's tap needs no unlock: on the lock screen a card whose step needs the unlock stays for after it
     * (nobody, you or someone holding the phone, uses it up by a tap that can't do it). Unlocked it is done and goes.
     */
    @Test fun aReadyCardTappedOnTheLockScreenStaysForTheUnlock() {
        val today = LocalDate.of(2026, 10, 12)
        val card = ReadyCard("r", "Leaving work", steps.getValue("open_app"), 1_000L, today)
        val locked = go("open_app", RunOrigin.READY_TAP, locked = true)
        assertEquals(StepGo.WAIT_UNLOCK, locked)
        assertTrue(ReadyCards.keptOnTap(card, locked))
        assertFalse(ReadyCards.keptOnTap(card, go("open_app", RunOrigin.READY_TAP, locked = false)))
        assertFalse(ReadyCards.keptOnTap(card.copy(step = steps.getValue("send_text")), go("send_text", RunOrigin.READY_TAP, locked = false)))
        // A missed run (the whole routine) runs what it can and says what needs the unlock.
        assertFalse(ReadyCards.keptOnTap(card.copy(missed = LocalTime.of(7, 0)), locked))
    }

    @Test fun routineSettingsDefaultsFollowTheDesign() {
        assertTrue(rs.runLocked)
        assertTrue(rs.online)
        assertFalse(rs.briefEnabled)
        assertEquals(BriefLock.TIME_WEATHER, rs.briefLock)
        assertTrue(rs.finishOnUnlock)
        assertNull(rs.briefLang)
        assertEquals(10, rs.clockSilenceMinutes)
        assertEquals(TimeWindow(LocalTime.of(4, 0), LocalTime.of(12, 0)), rs.alarmWindow)
        assertEquals(BriefLock.TIME_WEATHER, BriefLock.of("unknown"))
    }
}
