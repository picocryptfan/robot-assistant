package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGateTest {
    private val defaults = RobotSettings()
    private val allAuto = defaults.copy(approvals = ToolName.entries.associateWith { Approval.AUTO })

    @Test fun timersAndAlarmsJustRunByDefaultEvenLocked() {
        for (locked in listOf(false, true)) {
            assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_TIMER, locked, defaults))
            assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_ALARM, locked, defaults))
        }
    }

    @Test fun everythingElseNeedsUnlockOnTheLockScreen() {
        for (t in ToolName.entries - LockScreen.DEFAULT) {
            assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, true, defaults))
        }
    }

    /** Settings › Safety & privacy › "Works on the lock screen": the default is today's (timers and alarms), and it's a choice. */
    @Test fun theLockScreenFollowsTheSetting() {
        assertEquals(setOf(ToolName.SET_TIMER, ToolName.SET_ALARM), defaults.lockedTools)
        assertEquals(LockScreen.DEFAULT, defaults.lockedTools)
        val flashlight = defaults.copy(lockedTools = setOf(ToolName.FLASHLIGHT))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.FLASHLIGHT, true, flashlight))
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.SET_TIMER, true, flashlight))
        val none = defaults.copy(lockedTools = emptySet())
        for (t in ToolName.entries.filter { defaults.approval(it) != Approval.OFF }) assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, true, none))
        // Allowed there, a level still counts: Ask first asks, Off stays off.
        val texts = defaults.copy(lockedTools = setOf(ToolName.SEND_TEXT))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SEND_TEXT, true, texts))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.SEND_TEXT, true, texts.copy(approvals = texts.approvals + (ToolName.SEND_TEXT to Approval.OFF))))
        // The Claude app hand-off never: it isn't one of the choices.
        assertFalse(ToolName.ASK_CLAUDE in LockScreen.CHOICES)
        assertEquals("questions, timers and alarms", LockScreen.allowed(defaults.lockedTools))
        assertEquals("questions only", LockScreen.allowed(emptySet()))
        assertEquals("questions and flashlight", LockScreen.allowed(setOf(ToolName.FLASHLIGHT)))
    }

    @Test fun unlockedDefaultsOpenAppsAndMapsButAskBeforeWriting() {
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.OPEN_APP, false, defaults))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.MAPS_SEARCH, false, defaults))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.MAPS_NAVIGATE, false, defaults))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.FLASHLIGHT, false, defaults))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SEND_TEXT, false, defaults))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.DRAFT_EMAIL, false, defaults))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_EVENT, false, defaults))
    }

    @Test fun settingsChangeTheLevel() {
        val s = defaults.copy(approvals = defaults.approvals + (ToolName.OPEN_APP to Approval.AUTO) + (ToolName.SET_TIMER to Approval.ASK) + (ToolName.FLASHLIGHT to Approval.OFF))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.OPEN_APP, false, s))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SET_TIMER, false, s))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SET_TIMER, true, s))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.FLASHLIGHT, false, s))
        // Off wins over the lock-screen rule (nothing to unlock for).
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.FLASHLIGHT, true, s))
        // Just do it never overrides the lock screen.
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.OPEN_APP, true, s))
    }

    /** "Always after Robot reads something": the rule before 0.4.0's switch, asked for or not. */
    @Test fun afterReadingScreenTextOrAPictureTurnsJustDoItIntoAsk() {
        for (s in listOf(defaults, allAuto).map { it.copy(safetyCards = SafetyCards.AFTER_READING) }) {
            // Every tool that writes or leaves the phone; the read tools only bring more to read (below).
            for (t in ToolName.entries.filter { !it.reads }) for (asked in listOf(false, true)) {
                assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, locked = false, settings = s, content = true, asked = asked))
            }
        }
        val after = defaults.copy(safetyCards = SafetyCards.AFTER_READING)
        assertEquals(setOf(ToolName.LOOKUP_CONTACT, ToolName.FIND_NOTES, ToolName.READ_CALENDAR, ToolName.WEATHER, ToolName.PHONE_STATUS),
            ToolName.entries.filter { it.reads }.toSet())
        for (t in ToolName.entries.filter { it.reads && !it.online }) assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, after, content = true))
        // A read that goes online (the weather) asks: screen text could otherwise send a word, or where you are, to Open-Meteo.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEATHER, false, after, content = true, asked = true))
        // Never looser than without it: off stays off, the lock screen still says unlock.
        val off = after.copy(approvals = after.approvals + (ToolName.SET_TIMER to Approval.OFF))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.SET_TIMER, false, off, content = true))
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.FLASHLIGHT, true, after, content = true))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SET_ALARM, true, after, content = true))
    }

    /** The default, "When you didn't ask for it": content forces a card only for an action your own words didn't ask for. */
    @Test fun byDefaultContentAsksOnlyForWhatYouDidNotAskFor() {
        assertEquals(SafetyCards.UNASKED, defaults.safetyCards)
        for (s in listOf(defaults, allAuto)) for (t in ToolName.entries.filter { !it.reads || it.online }) {
            val level = s.approval(t)
            // Not asked for: as before, a card.
            assertEquals(t.name, if (level == Approval.OFF) GateDecision.DISABLED else GateDecision.CONFIRM,
                ApprovalGate.decide(t, false, s, content = true, asked = false))
            // Asked for in your own words: the level decides, as in a conversation without content.
            assertEquals(t.name, ApprovalGate.decide(t, false, s), ApprovalGate.decide(t, false, s, content = true, asked = true))
        }
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_TIMER, false, defaults, content = true, asked = true))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEATHER, false, defaults, content = true, asked = true))
        // Texts still ask first: their level is Ask first.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SEND_TEXT, false, defaults, content = true, asked = true))
    }

    /** "Only when Robot isn't sure who": content never forces a card; a reason the action gives still does. */
    @Test fun unsureOnlyIgnoresContent() {
        val s = allAuto.copy(safetyCards = SafetyCards.UNSURE_ONLY)
        for (t in ToolName.entries - ToolName.ASK_CLAUDE) {
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, s, content = true))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, s, why = setOf(AskWhy.WHO), content = true))
        }
    }

    @Test fun eachReasonHasItsOwnSwitch() {
        val t = ToolName.SEND_TEXT
        val s = allAuto
        assertEquals(GateDecision.RUN, ApprovalGate.decide(t, false, s))
        for (why in AskWhy.entries) assertEquals(why.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, s, why = setOf(why)))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(t, false, s.copy(whoCards = false), why = setOf(AskWhy.WHO)))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(t, false, s.copy(learnedCards = false), why = setOf(AskWhy.LEARNED)))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.FREE_STORAGE, false, s.copy(deleteCards = false), why = setOf(AskWhy.DELETE)))
        // A pick that isn't about safety (which app's cache) always shows its list.
        val loose = s.copy(whoCards = false, learnedCards = false, deleteCards = false, safetyCards = SafetyCards.UNSURE_ONLY)
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.FREE_STORAGE, false, loose, why = setOf(AskWhy.PICK)))
        // Several reasons: each switch that is on asks.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(t, false, s.copy(whoCards = false), why = setOf(AskWhy.WHO, AskWhy.LEARNED)))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(t, false, s.copy(whoCards = false, learnedCards = false), why = setOf(AskWhy.WHO, AskWhy.LEARNED)))
        // Ask first is a level, not a reason: it asks whatever the switches say.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(t, false, loose.copy(approvals = loose.approvals + (t to Approval.ASK))))
    }

    @Test fun claudesActionsHaveTheirOwnSwitch() {
        // On (the default): every call Claude makes shows a card, read tools included.
        for (t in ClaudeTools.OFFERED) assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, allAuto, content = true, asked = true, remote = true))
        // Off: the usual rules; Claude's words are content, so what you didn't ask for still asks (under the default).
        val off = allAuto.copy(claudeActionCards = false)
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_TIMER, false, off, content = true, asked = true, remote = true))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SET_TIMER, false, off, content = true, asked = false, remote = true))
        // What a read finds for Claude goes to Claude: a tap first, unless personal content may go by itself.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.READ_CALENDAR, false, off, content = true, asked = true, remote = true))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.READ_CALENDAR, false, off.copy(personalToHelpers = true), content = true, asked = true, remote = true))
        // Never past the lock screen or a level of Off.
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.ADD_NOTE, true, off, remote = true))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.ADD_NOTE, false, off.copy(approvals = off.approvals + (ToolName.ADD_NOTE to Approval.OFF)), remote = true))
        // The Claude app hand-off always shows "Open Claude with".
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ASK_CLAUDE, false, allAuto.copy(claudeActionCards = false, safetyCards = SafetyCards.UNSURE_ONLY)))
    }

    /**
     * Every combination of tool, lock, level, mode, switch, reason, content, asked and remote, against the rules as the
     * Safety & privacy page states them.
     */
    @Test fun everyCombination() {
        val bools = listOf(false, true)
        val reasons = (0 until (1 shl AskWhy.entries.size)).map { m -> AskWhy.entries.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet() }
        var n = 0
        for (t in ToolName.entries) for (level in Approval.entries) for (mode in SafetyCards.entries) for (lockSet in listOf(LockScreen.DEFAULT, setOf(t)))
            for (claudeCards in bools) for (who in bools) for (learned in bools) for (delete in bools) for (helpers in bools) {
                val s = defaults.copy(approvals = defaults.approvals + (t to level), safetyCards = mode, lockedTools = lockSet, claudeActionCards = claudeCards,
                    whoCards = who, learnedCards = learned, deleteCards = delete, personalToHelpers = helpers)
                for (locked in bools) for (why in reasons) for (content in bools) for (asked in bools) for (remote in bools) {
                    n++
                    val got = ApprovalGate.decide(t, locked, s, why, content, asked, remote)
                    val cardReasons = listOf(
                        t == ToolName.ASK_CLAUDE,
                        remote && claudeCards,
                        remote && t.reads && !helpers,
                        level == Approval.ASK,
                        AskWhy.PICK in why,
                        AskWhy.WHO in why && who,
                        AskWhy.LEARNED in why && learned,
                        AskWhy.DELETE in why && delete,
                        content && (!t.reads || t.online) && (mode == SafetyCards.AFTER_READING || (mode == SafetyCards.UNASKED && !asked)),
                    )
                    val want = when {
                        level == Approval.OFF -> GateDecision.DISABLED
                        locked && t !in lockSet -> GateDecision.NEEDS_UNLOCK
                        cardReasons.any { it } -> GateDecision.CONFIRM
                        else -> GateDecision.RUN
                    }
                    if (got != want) throw AssertionError("$t level=$level mode=$mode locked=$locked lockSet=$lockSet why=$why content=$content asked=$asked remote=$remote " +
                        "claude=$claudeCards who=$who learned=$learned delete=$delete helpers=$helpers: $got, want $want")
                }
            }
        assertTrue(n > 1_000_000)
    }

    @Test fun allSevenPhaseTwoToolsNeedUnlockOnTheLockScreen() {
        val seven = listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES,
            ToolName.ADD_EVENT, ToolName.READ_CALENDAR)
        assertEquals(seven.toSet(), ToolName.entries.filter { it.group in setOf(ToolGroup.PEOPLE, ToolGroup.NOTES, ToolGroup.CALENDAR) }.toSet())
        for (t in seven) {
            for (s in listOf(defaults, allAuto)) {
                assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, locked = true, settings = s))
                assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, locked = true, settings = s, content = true))
            }
        }
        // The lock-screen rules stay questions, alarms and timers by default.
        assertEquals(setOf(ToolName.SET_TIMER, ToolName.SET_ALARM), RobotSettings().lockedTools)
    }

    @Test fun phaseTwoDefaultsAskBeforeAnythingLeavesThePhone() {
        for (t in listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT)) assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, defaults))
        for (t in listOf(ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) {
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, defaults))
        }
        // After a read tool brought content in: a new note you didn't ask for (it writes) asks first; reading more never does.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_NOTE, false, defaults, content = true))
        for (t in listOf(ToolName.LOOKUP_CONTACT, ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) {
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, defaults, content = true))
            // A read set to "Ask first" still asks, and Off stays off.
            val ask = defaults.copy(approvals = defaults.approvals + (t to Approval.ASK))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, ask, content = true))
            val off = defaults.copy(approvals = defaults.approvals + (t to Approval.OFF))
            assertEquals(t.name, GateDecision.DISABLED, ApprovalGate.decide(t, false, off, content = true))
        }
    }

    @Test fun newStatusesHaveFixedToolResponses() {
        assertEquals(mapOf("status" to "permission_needed_user_must_allow", "detail" to "Allow Robot to read your contacts in the Robot app."),
            ActionStatus.NEEDS_PERMISSION.toolResponse("Allow Robot to read your contacts in the Robot app."))
        assertEquals("not_found", ActionStatus.NOT_FOUND.toolResponse(null)["status"])
        assertEquals("app_not_installed", ActionStatus.NO_APP.toolResponse("Install Cheogram.")["status"])
        assertEquals("undone_by_user", ActionStatus.UNDONE.toolResponse(null)["status"])
        // A read tool's findings ride along as results.
        val r = ActionStatus.DONE.toolResponse("1 event on Sun Oct 11.", listOf(mapOf("when" to "15:00–16:00", "title" to "Dentist")))
        assertEquals(listOf(mapOf("when" to "15:00–16:00", "title" to "Dentist")), r["results"])
        // Every status maps to its own response.
        assertEquals(ActionStatus.entries.size, ActionStatus.entries.map { it.toolResponse(null)["status"] }.distinct().size)
    }

    @Test fun toolResponsesTellTheModelWhatHappened() {
        assertEquals(mapOf("status" to "done", "detail" to "Timer set"), ActionStatus.DONE.toolResponse("Timer set"))
        assertEquals(mapOf("status" to "phone_locked_user_must_unlock"), ActionStatus.NEEDS_UNLOCK.toolResponse(null))
        assertEquals("cancelled_by_user", ActionStatus.DECLINED.toolResponse("").getValue("status"))
    }
}
