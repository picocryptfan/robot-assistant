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
        // 0.7: "remember" also calls memory's tools (Settings › Memory decides whether they are described).
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES, ToolGroup.MEMORY), route("Remember that the code is 4521"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.NOTES), route("jot this down"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("what's on my calendar tomorrow"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("add dentist Tuesday at 3"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("Am I busy on October 14?"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.CALENDAR), route("Mon rendez-vous de vendredi"))
        // Several at once.
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE, ToolGroup.CALENDAR), route("email Alex about Friday's plan"))
        assertEquals(setOf(ToolGroup.CORE, ToolGroup.PEOPLE, ToolGroup.NOTES, ToolGroup.CALENDAR), route("Text Sam the list of meetings"))
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
        // 0.9: the Termux tools only when Settings › Termux turned them on (TermuxTool.tools), never by the groups alone.
        val termux = setOf(ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND)
        assertEquals(ToolName.entries - ToolName.ASK_CLAUDE - ToolName.CALL - ToolName.REMEMBER - ToolName.FORGET - ToolName.WEB_SEARCH - termux,
            ToolRouter.tools(ToolGroup.entries.toSet(), d))
        assertEquals(ToolName.entries - ToolName.ASK_CLAUDE - ToolName.CALL - ToolName.WEB_SEARCH - termux, ToolRouter.tools(ToolGroup.entries.toSet(), d, memory = true))
        // 0.9: web_search only with Settings › Web search on (and its group: the words called for it).
        assertEquals(ToolName.entries - ToolName.ASK_CLAUDE - ToolName.CALL - ToolName.REMEMBER - ToolName.FORGET - termux,
            ToolRouter.tools(ToolGroup.entries.toSet(), d, web = true))
        assertEquals(ToolName.entries - ToolName.ASK_CLAUDE - ToolName.CALL - ToolName.REMEMBER - ToolName.FORGET - ToolName.WEB_SEARCH,
            ToolRouter.tools(ToolGroup.entries.toSet(), d, termux = termux))
        assertEquals(Prompt.CORE_TOOLS, ToolRouter.tools(core, d, termux = termux))
        assertEquals(Prompt.CORE_TOOLS + ToolName.TERMUX_COMMAND, ToolRouter.tools(core + ToolGroup.TERMUX, d, termux = setOf(ToolName.TERMUX_COMMAND)))
        assertEquals(Prompt.CORE_TOOLS, ToolRouter.tools(core, d, web = true))
        assertEquals(Prompt.CORE_TOOLS + ToolName.WEB_SEARCH, ToolRouter.tools(route("search the web for the Stanley Cup winner"), d, web = true))
        // ask_claude only with Ask Claude on and escalation not Off (ClaudeSettings.localTool).
        assertEquals(Prompt.CORE_TOOLS + ToolName.ASK_CLAUDE, ToolRouter.tools(core, d, askClaude = true))
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

    /** "Safety cards: When you didn't ask for it": which actions your own words asked for this turn. */
    @Test fun yourOwnWordsSayWhichActionsYouAskedFor() {
        fun asked(q: String) = ToolRouter.asked(q)
        assertEquals(setOf(ToolName.SET_TIMER), asked("set a timer for 10 minutes"))
        assertTrue(ToolName.SET_ALARM in asked("wake me up at 7"))
        assertTrue(ToolName.SET_ALARM in asked("mets une alarme à 7h"))
        assertTrue(ToolName.FLASHLIGHT in asked("turn on the flashlight"))
        assertTrue(ToolName.OPEN_APP in asked("open Signal"))
        assertTrue(ToolName.MAPS_NAVIGATE in asked("directions to the nearest pharmacy"))
        assertTrue(ToolName.MAPS_SEARCH in asked("where's the closest gas station"))
        assertTrue(ToolName.SEND_TEXT in asked("text Mom I'm late") && ToolName.DRAFT_EMAIL !in asked("text Mom I'm late"))
        assertTrue(ToolName.SEND_TEXT in asked("tell Sam I'm on my way") && ToolName.SEND_TEXT in asked("let my sister know I landed"))
        assertTrue(ToolName.SEND_TEXT in asked("send this to Sam") && ToolName.DRAFT_EMAIL in asked("send this to Sam"))
        assertTrue(ToolName.DRAFT_EMAIL in asked("Email Sam about dinner on Friday"))
        assertTrue(ToolName.ADD_NOTE in asked("note that the wifi code is 1234"))
        assertTrue(ToolName.ADD_NOTE in asked("Remember that I parked on level 3"))
        assertTrue(ToolName.ADD_NOTE in asked("add milk to my list") && ToolName.ADD_EVENT !in asked("add milk to my list"))
        assertTrue(ToolName.ADD_EVENT in asked("add the dentist to my calendar on Friday"))
        assertTrue(ToolName.ADD_EVENT in asked("Add lunch with Alex on Friday at noon"))
        assertTrue(ToolName.ADD_EVENT in asked("remind me to call the dentist tomorrow"))
        assertTrue(ToolName.WEATHER in asked("will it rain tomorrow"))
        assertTrue(ToolName.FREE_STORAGE in asked("free up some space") && ToolName.FREE_STORAGE in asked("Help me free up space"))
        assertTrue(ToolName.FREE_MEMORY in asked("close YouTube"))
        // A question about the screen asks for no action: what the page says never counts as your words.
        assertEquals(emptySet<ToolName>(), asked("what does this page say?"))
        assertEquals(emptySet<ToolName>(), asked("summarize this"))
        assertFalse(ToolName.SET_TIMER in asked("what's the weather tomorrow"))
        assertFalse(ToolName.OPEN_APP in asked("tell me a joke"))
        // Words that only name a thing on the screen (or a topic) ask for nothing that writes or leaves the phone.
        val writes = setOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ADD_NOTE, ToolName.ADD_EVENT, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE)
        for (q in listOf("what does this message say?", "read me the email", "check my email", "any new messages?", "what's in this text",
                "can you tell what this is", "what's on this list", "is this event today?", "what's on my calendar today?", "am I free on Friday",
                "what's the phone number on this page", "what's this address", "how much space is free?", "how's my battery?",
                "what does cette note dit", "c'est quoi ce message")) {
            assertEquals(q, emptySet<ToolName>(), asked(q).intersect(writes))
        }
        // Robot's own hand-off never counts: it always shows its card.
        assertFalse(ToolName.ASK_CLAUDE in asked("ask Claude to open the maps"))
    }
}
