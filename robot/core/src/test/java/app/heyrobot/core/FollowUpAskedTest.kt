package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-everyday-c: the Pixel 8 row's "and tomorrow?" after "what's the weather" waited on a card (phone-1.2.3-b). */
class FollowUpAskedTest {
    private val s = RobotSettings()

    @Test fun andTomorrowAfterTheWeatherRunsWithNoCard() {
        // As it was: the forecast in the conversation is content, and "and tomorrow?" names no tool of its own.
        assertFalse(ToolName.WEATHER in ToolRouter.asked("and tomorrow?"))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.WEATHER, false, s, content = true, asked = false))
        val asked = ToolRouter.asked("and tomorrow?", "what's the weather")
        assertTrue(ToolName.WEATHER in asked)
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.WEATHER, false, s, content = true, asked = ToolName.WEATHER in asked))
        assertTrue(ToolName.WEATHER in ToolRouter.asked("et demain ?", "quelle est la météo"))
        assertTrue(ToolName.WEATHER in ToolRouter.asked("what about Paris", "what's the weather"))
    }

    @Test fun onlyReadToolsCarryOverAndOnlyToAShortFollowUp() {
        assertFalse(ToolName.SET_TIMER in ToolRouter.asked("and another one?", "set a timer for 5 minutes"))
        assertFalse(ToolName.SEND_TEXT in ToolRouter.asked("and tomorrow?", "text Sam that I'm late"))
        assertFalse(ToolName.WEATHER in ToolRouter.asked("tell me a joke about cats and dogs please now", "what's the weather"))
        assertFalse(ToolRouter.followUp("tomorrow is my birthday"))
        assertEquals(ToolRouter.asked("and tomorrow?"), ToolRouter.asked("and tomorrow?", null))
    }
}
