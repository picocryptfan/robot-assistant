package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalGateTest {
    private val defaults = RobotSettings()

    @Test fun timersAndAlarmsJustRunByDefaultEvenLocked() {
        for (locked in listOf(false, true)) {
            assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_TIMER, locked, defaults))
            assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.SET_ALARM, locked, defaults))
        }
    }

    @Test fun everythingElseNeedsUnlockOnTheLockScreen() {
        for (t in ToolName.entries - ApprovalGate.ALLOWED_WHEN_LOCKED) {
            assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, true, defaults))
        }
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

    @Test fun screenTextOrAPictureTurnsJustDoItIntoAsk() {
        // Every tool that writes or leaves the phone; the read tools only bring more to read (below).
        val allAuto = defaults.copy(approvals = ToolName.entries.associateWith { Approval.AUTO })
        for (t in ToolName.entries.filter { !it.reads }) {
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, locked = false, settings = defaults, mustAsk = true))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, locked = false, settings = allAuto, mustAsk = true))
        }
        assertEquals(setOf(ToolName.LOOKUP_CONTACT, ToolName.FIND_NOTES, ToolName.READ_CALENDAR, ToolName.WEATHER, ToolName.PHONE_STATUS),
            ToolName.entries.filter { it.reads }.toSet())
        for (t in ToolName.entries.filter { it.reads }) assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, defaults, mustAsk = true))
        // Never looser than without it: off stays off, the lock screen still says unlock.
        val off = defaults.copy(approvals = defaults.approvals + (ToolName.SET_TIMER to Approval.OFF))
        assertEquals(GateDecision.DISABLED, ApprovalGate.decide(ToolName.SET_TIMER, false, off, mustAsk = true))
        assertEquals(GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(ToolName.FLASHLIGHT, true, defaults, mustAsk = true))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.SET_ALARM, true, defaults, mustAsk = true))
    }

    @Test fun allSevenPhaseTwoToolsNeedUnlockOnTheLockScreen() {
        val seven = listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES,
            ToolName.ADD_EVENT, ToolName.READ_CALENDAR)
        assertEquals(seven.toSet(), ToolName.entries.filter { it.group in setOf(ToolGroup.PEOPLE, ToolGroup.NOTES, ToolGroup.CALENDAR) }.toSet())
        val allAuto = defaults.copy(approvals = ToolName.entries.associateWith { Approval.AUTO })
        for (t in seven) {
            for (s in listOf(defaults, allAuto)) {
                assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, locked = true, settings = s))
                assertEquals(t.name, GateDecision.NEEDS_UNLOCK, ApprovalGate.decide(t, locked = true, settings = s, mustAsk = true))
            }
        }
        // The lock-screen rules stay questions, alarms and timers.
        assertEquals(setOf(ToolName.SET_TIMER, ToolName.SET_ALARM), ApprovalGate.ALLOWED_WHEN_LOCKED)
    }

    @Test fun phaseTwoDefaultsAskBeforeAnythingLeavesThePhone() {
        for (t in listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_EVENT)) assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, defaults))
        for (t in listOf(ToolName.LOOKUP_CONTACT, ToolName.ADD_NOTE, ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) {
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, defaults))
        }
        // After a read tool brought content in: a new note (it writes) asks first; reading more never does.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_NOTE, false, defaults, mustAsk = true))
        for (t in listOf(ToolName.LOOKUP_CONTACT, ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) {
            assertEquals(t.name, GateDecision.RUN, ApprovalGate.decide(t, false, defaults, mustAsk = true))
            // A read set to "Ask first" still asks, and Off stays off.
            val ask = defaults.copy(approvals = defaults.approvals + (t to Approval.ASK))
            assertEquals(t.name, GateDecision.CONFIRM, ApprovalGate.decide(t, false, ask, mustAsk = true))
            val off = defaults.copy(approvals = defaults.approvals + (t to Approval.OFF))
            assertEquals(t.name, GateDecision.DISABLED, ApprovalGate.decide(t, false, off, mustAsk = true))
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
