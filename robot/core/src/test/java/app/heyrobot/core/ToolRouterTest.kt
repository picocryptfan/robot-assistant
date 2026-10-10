package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRouterTest {
    private val core = setOf(ToolGroup.CORE)
    private fun route(q: String) = ToolRouter.route(q)

    @Test fun aTimerQuestionIsCoreOnly() {
        assertEquals(core, route("Set a timer for 5 minutes"))
        assertEquals(core, route("Turn on the flashlight"))
        assertEquals(core, route("What's the capital of France?"))
        assertEquals(core, route("Wake me up at 7"))
    }

    @Test fun textMomIsCoreAndPeople() {
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("text Mom I'm late"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("Texting Alex now"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("What's Sam's phone number?"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("write to the landlord"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("Envoie un texto à Marie"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), route("Un courriel pour Paul"))
        assertTrue(ToolGroup.PEOPLE in route("tell Mom I'm on my way"))
        // "tell me" is a question, not a message.
        assertEquals(core, route("Tell me a joke"))
    }

    @Test fun notesAndCalendarWords() {
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES), route("note: buy coffee filters"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES), route("what did I note about coffee"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES), route("Remember that the code is 4521"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES), route("jot this down"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("what's on my calendar tomorrow"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("add dentist Tuesday at 3"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("Am I busy on October 14?"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("Mon rendez-vous de vendredi"))
        // Several at once.
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE, ToolGroup.CALENDAR), route("email Alex about Friday's plan"))
        assertEquals(ToolGroup.entries.toSet(), route("Text Sam the list of meetings"))
    }

    @Test fun reviewPhrasingsReachTheirGroups() {
        // Review A M6: each of these routed to CORE only in 0.2.0.
        val people = listOf("what's Alex's cell", "ping Sam", "let Sarah know I'm late", "let my sister know", "write Sarah that I'm late",
            "msg Sam", "DM Sam", "how do I reach Sam", "what's Sam's address", "écris à Marie que j'arrive", "dis à Marie que j'arrive",
            "what's Alex's mobile")
        for (q in people) assertTrue(q, ToolGroup.PEOPLE in route(q))
        val notes = listOf("remind me to call the bank", "save this: buy milk", "what did I save about coffee", "rappelle-moi d'acheter du lait")
        for (q in notes) assertTrue(q, ToolGroup.NOTES in route(q))
        val calendar = listOf("what's on next week", "what do I have this week", "anything this afternoon?", "add gym this evening",
            "add dentist at 3pm", "dentist at 3 p.m.", "lunch at 12:30", "rdv à 15h", "dentist on the 14th at 3", "dentist March 3",
            "lunch with Bob May 5", "the 3rd of March", "what's coming up", "remind me to call the bank at 3", "anything this morning")
        for (q in calendar) assertTrue(q, ToolGroup.CALENDAR in route(q))
        // "remind me" is both a note and a calendar thing.
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES, ToolGroup.CALENDAR), route("remind me to buy milk"))
        // March and May alone are verbs, not dates.
        assertEquals(core, route("may I ask something"))
        assertEquals(core, route("they march on"))
        // An alarm question may bring the calendar too (only tokens), never the other groups.
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("set an alarm for 7am"))
    }

    @Test fun toolsFollowTheGroupsAndTheSettings() {
        val d = RobotSettings()
        assertEquals(Prompt.CORE_TOOLS, ToolRouter.tools(core, d))
        assertEquals(Prompt.CORE_TOOLS + listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.LOOKUP_CONTACT),
            ToolRouter.tools(route("text Mom I'm late"), d))
        assertEquals(ToolName.entries, ToolRouter.tools(ToolGroup.entries.toSet(), d))
        // Turned off in settings: never described. Texting app off: no send_text.
        val off = d.copy(approvals = d.approvals + (ToolName.FIND_NOTES to Approval.OFF) + (ToolName.SET_TIMER to Approval.OFF))
        val tools = ToolRouter.tools(ToolGroup.entries.toSet(), off)
        assertFalse(ToolName.FIND_NOTES in tools || ToolName.SET_TIMER in tools)
        assertTrue(ToolName.ADD_NOTE in tools)
        assertFalse(ToolName.SEND_TEXT in ToolRouter.tools(ToolGroup.entries.toSet(), d.copy(textApp = TextApp.OFF)))
        assertTrue(ToolName.SEND_TEXT in ToolRouter.tools(ToolGroup.entries.toSet(), d.copy(textApp = TextApp.SMS)))
    }

    @Test fun aConversationKeepsTheGroupsItUsed() {
        val turns = listOf(
            ChatTurn("1", TurnRole.USER, "what's on my calendar tomorrow", 1),
            ChatTurn("2", TurnRole.ASSISTANT, "Nothing.", 2),
            ChatTurn("3", TurnRole.USER, "ok thanks", 3),
            ChatTurn("4", TurnRole.ASSISTANT, "", 4, actions = listOf(ActionRecord(ToolName.ADD_NOTE, "Save a note in Robot Notes", ActionStatus.DONE))),
        )
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR, ToolGroup.NOTES), ToolRouter.used(turns))
        assertEquals(core, ToolRouter.used(emptyList()))
    }

    @Test fun theLogLabelNamesGroupsOnly() {
        assertEquals("core", ToolRouter.label(Prompt.CORE_TOOLS))
        assertEquals("core.people.calendar", ToolRouter.label(Prompt.CORE_TOOLS + ToolName.SEND_TEXT + ToolName.READ_CALENDAR))
        assertEquals("none", ToolRouter.label(emptyList()))
    }
}
