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

    @Test fun unlockedDefaultsAskForAppsAndMaps() {
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.OPEN_APP, false, defaults))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.MAPS_SEARCH, false, defaults))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.MAPS_NAVIGATE, false, defaults))
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.FLASHLIGHT, false, defaults))
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

    @Test fun toolResponsesTellTheModelWhatHappened() {
        assertEquals(mapOf("status" to "done", "detail" to "Timer set"), ActionStatus.DONE.toolResponse("Timer set"))
        assertEquals(mapOf("status" to "phone_locked_user_must_unlock"), ActionStatus.NEEDS_UNLOCK.toolResponse(null))
        assertEquals("cancelled_by_user", ActionStatus.DECLINED.toolResponse("").getValue("status"))
    }
}
