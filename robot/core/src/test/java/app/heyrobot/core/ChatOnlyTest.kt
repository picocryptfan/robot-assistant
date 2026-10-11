package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.4.3, a short-context model (MedGemma 1.5 4B, 2,048 tokens): chat only. A request clearly worded as an action gets
 * Robot's own answer and the offer of Settings › Model; anything else, a medical question above all, goes to the model.
 */
class ChatOnlyTest {
    @Test fun actionRequestsGetRobotsOwnAnswer() {
        val asked = mapOf(
            "Set a timer for 10 minutes" to ToolName.SET_TIMER,
            "set a 5 minute timer" to ToolName.SET_TIMER,
            "Hey Robot, please start a timer for my eye drops" to ToolName.SET_TIMER,
            "Can you set a timer for five minutes?" to ToolName.SET_TIMER,
            "timer for 20 minutes" to ToolName.SET_TIMER,
            "Mets un minuteur de 10 minutes" to ToolName.SET_TIMER,
            "Set an alarm for 7:30" to ToolName.SET_ALARM,
            "wake me up at 6" to ToolName.SET_ALARM,
            "Réveille-moi à 7h" to ToolName.SET_ALARM,
            "Remind me to take my pills at 8 tonight" to ToolName.ADD_EVENT,
            "Add dentist on Tuesday to my calendar" to ToolName.ADD_EVENT,
            "add physio Tuesday at 3pm" to ToolName.ADD_EVENT,
            "Schedule a checkup with Dr Martin next week" to ToolName.ADD_EVENT,
            "Take a note: blood pressure 128 over 82" to ToolName.ADD_NOTE,
            "Note that I took 400 mg of ibuprofen at noon" to ToolName.ADD_NOTE,
            "add vitamin D to my shopping list" to ToolName.ADD_NOTE,
            "Text Mom I'm on my way" to ToolName.SEND_TEXT,
            "Send Sam a message saying I'll be late" to ToolName.SEND_TEXT,
            "tell Sarah that the appointment moved" to ToolName.SEND_TEXT,
            "let my sister know I'm home" to ToolName.SEND_TEXT,
            "Email Dr Martin about my results" to ToolName.DRAFT_EMAIL,
            "Open Signal" to ToolName.OPEN_APP,
            "could you open the camera app?" to ToolName.OPEN_APP,
            "Navigate to the hospital" to ToolName.MAPS_NAVIGATE,
            "take me to the nearest pharmacy" to ToolName.MAPS_NAVIGATE,
            "Find the nearest pharmacy" to ToolName.MAPS_SEARCH,
            "Where is the nearest hospital?" to ToolName.MAPS_SEARCH,
            "Turn on the flashlight" to ToolName.FLASHLIGHT,
            "torch on" to ToolName.FLASHLIGHT,
            "What's the weather tomorrow?" to ToolName.WEATHER,
            "will it rain today?" to ToolName.WEATHER,
            "Quel temps fait-il demain ?" to ToolName.WEATHER,
            "What's on my calendar tomorrow?" to ToolName.READ_CALENDAR,
            "Am I free on Friday?" to ToolName.READ_CALENDAR,
            "What's Mom's number?" to ToolName.LOOKUP_CONTACT,
            "what is my doctor's phone number" to ToolName.LOOKUP_CONTACT,
            "show my notes" to ToolName.FIND_NOTES,
            "How much battery do I have left?" to ToolName.PHONE_STATUS,
            "free up some space" to ToolName.FREE_STORAGE,
            "close the YouTube app" to ToolName.FREE_MEMORY,
            "What do I have on Monday?" to ToolName.READ_CALENDAR,
            "what's on this week" to ToolName.READ_CALENDAR,
            "Qu'est-ce que j'ai de prévu demain ?" to ToolName.READ_CALENDAR,
            "What's the temperature outside?" to ToolName.WEATHER,
            "what's the temperature in Paris" to ToolName.WEATHER,
            "directions to the hospital" to ToolName.MAPS_NAVIGATE,
            "what's John Smith's number" to ToolName.LOOKUP_CONTACT,
            "Create a reminder to take my pills" to ToolName.ADD_EVENT,
            "set up a meeting with Dr Martin" to ToolName.ADD_EVENT,
            "add it to the calendar" to ToolName.ADD_EVENT,
            "make a note to call the pharmacy" to ToolName.ADD_NOTE,
        )
        for ((q, tool) in asked) assertEquals(q, tool, ChatOnly.actionAsked(q))
    }

    /**
     * A photo or a body is no calendar, a fever's temperature no forecast, a blood count no contact: these words of an
     * action in a medical question never turn it away (and a question about a photo least of all).
     */
    @Test fun medicalQuestionsWithAnActionsWordsGoToTheModel() {
        for (q in listOf(
            "What do I have on my arm?",
            "what do I have on my skin",
            "What do I have? This rash on my leg itches",
            "What's on this X-ray?",
            "what is on this photo",
            "Qu'est-ce que j'ai au bras ?",
            "qu'est-ce que j'ai",
            "J'ai quoi comme maladie ?",
            "What is the temperature for a fever?",
            "what is the temperature of a healthy adult",
            "what's the temperature in children with a fever",
            "whats the forecast for recovery after knee surgery",
            "How do I get to sleep faster?",
            "How hot is it safe for a sauna when pregnant?",
            "how cold is it dangerous for a baby",
            "Quel temps de récupération après une entorse ?",
            "quel temps faut-il pour guérir",
            "What is the white cells number?",
            "what is a normal platelets number",
            "look at this mole, is there another one close by",
            "open angle glaucoma",
            "open pneumothorax",
            "text neck",
            "mail order pharmacy",
            "directions for use of amoxicillin",
            "Directions to take metformin",
            "route for insulin injection",
            "Create a meal plan for after a cardiac event",
            "add more fiber to my diet to lower the risk of a cardiac event",
            "Write a note to my doctor about my symptoms",
        )) assertNull(q, ChatOnly.actionAsked(q))
    }

    /** Questions for the model: about the body, symptoms, medicines and the like, even with an action's word in them. */
    @Test fun questionsGoToTheModel() {
        for (q in listOf(
            "What does a high white blood cell count mean?",
            "I have a rash on my arm that itches, what could it be?",
            "Open wound on my knee, should I cover it?",
            "open wound on my knee",
            "Light sensitivity and a headache, is that a migraine?",
            "Can cold weather trigger asthma?",
            "Is the heat bad for eczema?",
            "Set of symptoms: fever, sore throat and swollen glands. What is it?",
            "Remind me what you said about ibuprofen",
            "Remind me about the side effects of metformin",
            "Tell me about melanoma",
            "Can you tell me how much paracetamol is safe?",
            "Let me know if this mole looks normal",
            "Note that I'm diabetic: can I eat bananas?",
            "Quit smoking",
            "How do I stop bleeding from a small cut?",
            "Close the wound with what?",
            "Am I free of infection after five days of antibiotics?",
            "What's my memory like after a concussion?",
            "How much memory loss is normal at 70?",
            "Find out why my knee hurts",
            "What is a schedule II drug?",
            "Is it safe to text while on morphine?",
            "Put ice on my ankle tonight or heat?",
            "Should I take my blood pressure in the morning?",
            "Explain this X-ray",
            "Qu'est-ce qu'une otite ?",
            "",
            "   ",
        )) assertNull(q, ChatOnly.actionAsked(q))
    }

    /** A pasted article or a long dictation is read in one pass (anchored patterns, bounded repeats): no hang, no action. */
    @Test(timeout = 10_000) fun aLongTextIsReadQuicklyAndIsNoAction() {
        val article = "The history of blood pressure measurement is long and full of clever ideas, find the nearest. ".repeat(600)
        assertNull(ChatOnly.actionAsked(article + "?"))
        assertEquals(ToolName.MAPS_SEARCH, ChatOnly.actionAsked("find " + article))
        assertNull(ChatOnly.actionAsked("please ".repeat(5_000)))
        assertNull(ChatOnly.actionAsked("add ".repeat(5_000) + "?"))
    }

    @Test fun theAnswerSaysWhatItCantDoAndWhereToChoose() {
        assertTrue(ChatOnly.REPLY.contains("can only chat"))
        assertTrue(ChatOnly.REPLY.contains("choose Fast in Settings › Model"))
        assertFalse(ChatOnly.SPOKEN.contains("›"))
        assertTrue(ChatOnly.SPOKEN.contains("choose Fast in Settings, Model"))
        assertTrue(ChatOnly.NOTICE.contains("Settings › Model"))
        // Said as Robot says things: plain words, "you", no symbols the voice would spell out.
        assertEquals(ChatOnly.SPOKEN, SpeechText.clean(ChatOnly.SPOKEN))
    }

    private class Model(private val first: ModelOutcome) : TurnModel {
        var asked = 0
        var answered = 0
        var closed = 0
        override suspend fun ask(): ModelOutcome { asked++; return first }
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { answered++; return ModelOutcome.Done(DoneReason.END) }
        override fun close() { closed++ }
    }

    private class Actions : TurnActions {
        val prepared = mutableListOf<Action>()
        val ran = mutableListOf<Action>()
        override val locked = false
        override val settings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared { prepared += action; return Prepared.Ready(action) }
        override suspend fun run(action: Action): Executed { ran += action; return Executed(ActionStatus.DONE, "done") }
    }

    private class View(override var text: String = "") : TurnView {
        val cards = mutableListOf<Card>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards += c }
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    /** No tools offered, and none run: a call the model makes anyway never reaches the approval gate, nor a text call. */
    @Test fun aChatOnlyTurnNeverRunsAnAction() = runBlocking {
        val calls = listOf(RawToolCall("set_timer", mapOf("minutes" to 5)), RawToolCall("send_text", mapOf("to" to "Mom", "message" to "hi")))
        for (first in listOf(ModelOutcome.Calls(calls), ModelOutcome.Calls(listOf(RawToolCall("ask_claude", mapOf("question" to "x")))))) {
            val model = Model(first)
            val actions = Actions()
            val view = View("Okay, ")
            val r = TurnRunner(ChatOnlyTurn(model), actions, view, Confirmations(), untrusted = false) { "a" }.run()
            assertEquals(TurnResult(DoneReason.END), r)
            assertTrue(actions.prepared.isEmpty() && actions.ran.isEmpty() && view.cards.isEmpty())
            assertEquals(0, model.answered)
            assertEquals(1, model.closed)
            assertEquals("Okay, ", view.text)
        }
        // A reply that is nothing but a call, in a conversation claiming text-call mode: still just text.
        val model = Model(ModelOutcome.Done(DoneReason.END, textCalls = true))
        val actions = Actions()
        val view = View("call:set_timer{minutes:5}")
        val r = TurnRunner(ChatOnlyTurn(model), actions, view, Confirmations(), untrusted = false) { "a" }.run()
        assertEquals(TurnResult(DoneReason.END), r)
        assertTrue(actions.ran.isEmpty() && view.cards.isEmpty())
        assertEquals("call:set_timer{minutes:5}", view.text)
        // The same model without the wrapper would have run the timer (what a tool-capable model does).
        val plain = Actions()
        TurnRunner(Model(ModelOutcome.Calls(calls.take(1))), plain, View(), Confirmations(), untrusted = false) { "a" }.run()
        assertEquals(listOf<Action>(Action.SetTimer(300)), plain.ran)
        // Failures and plain answers pass through unchanged.
        assertEquals(TurnResult(null, failure = "x"), TurnRunner(ChatOnlyTurn(Model(ModelOutcome.Failed("x"))), Actions(), View(), Confirmations(), false) { "a" }.run())
        assertEquals(TurnResult(DoneReason.LENGTH), TurnRunner(ChatOnlyTurn(Model(ModelOutcome.Done(DoneReason.LENGTH))), Actions(), View(), Confirmations(), false) { "a" }.run())
    }

    /**
     * Fallback unchanged: a chat-only file is chosen like any other of your own files (ready once checked), and when it
     * isn't ready or doesn't load, Robot uses Fast with the same messages as before.
     */
    @Test fun aChatOnlyFileIsChosenAndFallsBackLikeAnyOther() {
        val r = LiteRtLmReport(readable = true, format = "1.5.0", decoder = true, modelType = "gemma3n", maxTokens = 2048, vision = true)
        val own = OwnModel("medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm", 3_023_069_488, "ab".repeat(32), "3023069488|1", r)
        val ready = own.availability("3023069488|1")
        assertEquals(ModelAvailability.READY, ready)
        val r1 = ModelAvailability.READY
        assertEquals(ModelChoice.Decision.Load(BrainModel.OWN), ModelChoice.decide(BrainModel.OWN, ready, r1, chosenFailedBefore = false))
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.OWN, FallbackWhy.NOT_CHECKED),
            ModelChoice.decide(BrainModel.OWN, own.availability("3023069488|2"), r1, chosenFailedBefore = false))
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.OWN, FallbackWhy.LOAD_FAILED),
            ModelChoice.afterFailure(BrainModel.OWN, LoadFailure.ENGINE, r1))
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.OWN, FallbackWhy.FAILED_EARLIER),
            ModelChoice.decide(BrainModel.OWN, ready, r1, chosenFailedBefore = true))
        // What :brain is then asked for: the file's own context, chat only; Fast keeps 4,096.
        val plan = LoadPlan(BrainModel.OWN, "/x/own_model.litertlm", ModelBackend.CPU, r.contextTokens, "3023069488|1", r.vision)
        assertTrue(plan.chatOnly)
        assertEquals(2048, plan.contextTokens)
        assertFalse(LoadPlan(BrainModel.FAST, "/x/f", ModelBackend.TPU, BrainModel.FAST.contextTokens, "1|1").chatOnly)
    }
}
