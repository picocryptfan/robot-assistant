// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Test Bench everyday-1.2.2-a, bugs 6 and 7, in a turn: a dictated note is saved in your words (the model wrote "Plumber
 * Appointment": "Plumber comes Thursday at E122"), and an answer to a question, with nothing called, never says it saved
 * something ("Your doctor is Dr. Anika Patel. I have saved that information for you.").
 */
class EverydayTurnTest {
    private val now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun heard(words: String, lang: Lang = Lang.EN) = TurnWords.of(words, now, lang)
    private var ids = 0

    private class Model(private val calls: List<RawToolCall>, private val view: View, private val reply: String) : TurnModel {
        override suspend fun ask(): ModelOutcome {
            if (calls.isNotEmpty()) return ModelOutcome.Calls(calls)
            view.text += reply
            return ModelOutcome.Done(DoneReason.END)
        }
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            view.text += reply
            return ModelOutcome.Done(DoneReason.END)
        }
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) {}
    }

    private class Actions(private val status: ActionStatus = ActionStatus.DONE) : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked: Boolean get() = false
        override val settings: RobotSettings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed {
            ran += action
            return Executed(status, app.heyrobot.core.i18n.ToolStrings.savedToNotes())
        }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private fun note(said: String, text: String, title: String?, lang: Lang = Lang.EN): Action {
        val view = View()
        val actions = Actions()
        val args = mutableMapOf<String, Any?>("text" to text).apply { if (title != null) put("title", title) }
        runBlocking {
            TurnRunner(Model(listOf(RawToolCall("add_note", args)), view, "Saved."), actions, view, Confirmations(), false,
                asked = setOf(ToolName.ADD_NOTE), newId = { "n${++ids}" }, heard = heard(said, lang)).run()
        }
        return view.cards.values.single().action!!
    }

    @Test fun aDictatedNoteKeepsYourWords() {
        assertEquals(Action.AddNote("the plumber comes Thursday E122", null),
            note("take a note that the plumber comes Thursday E122", "Plumber comes Thursday at E122", "Plumber Appointment"))
        assertEquals(Action.AddNote("call the bank E122", null), note("jot down call the bank E122", "Call the bank at E122", "Bank Call"))
        assertEquals(Action.AddNote("le plombier vient jeudi", null), note("prends en note que le plombier vient jeudi", "Le plombier viendra jeudi", "Visite du plombier", Lang.FR))
        // A title made of your own words stays.
        assertEquals(Action.AddNote("the plumber comes Thursday", "Plumber"), note("Note that the plumber comes Thursday.", "Plumber comes Thursday", "Plumber"))
    }

    @Test fun aNoteTheModelWritesFromOtherWordsIsLeftAlone() {
        // "Save the recipe on my screen as a note": no words dictated, the model's text is the note.
        assertEquals(Action.AddNote("2 eggs, 1 cup flour", "Pancakes"), note("save the recipe on my screen as a note", "2 eggs, 1 cup flour", "Pancakes"))
    }

    private fun answer(said: String, reply: String, lang: Lang = Lang.EN): String {
        val view = View()
        runBlocking {
            TurnRunner(Model(emptyList(), view, reply), Actions(), view, Confirmations(), false, newId = { "r${++ids}" }, heard = heard(said, lang)).run()
        }
        assertTrue(view.cards.isEmpty())
        return view.text
    }

    @Test fun aRecallAnswerNeverSaysItSaved() {
        assertEquals("Your doctor's name is Dr. Anika Patel.", answer("what's my doctor's name", "Your doctor's name is Dr. Anika Patel. I have saved that information for you."))
        assertEquals("Your mom's anniversary is August 20.", answer("What date is my mom's anniversary?", "Your mom's anniversary is August 20. I've noted that for you."))
        assertEquals("Ton dentiste s'appelle docteur Martin.", answer("Qui est mon dentiste ?", "Ton dentiste s'appelle docteur Martin. Je l'ai enregistré pour toi.", Lang.FR))
    }

    @Test fun otherAnswersAreLeftAlone() {
        val joke = "Why did the robot cross the road? It was programmed to."
        assertEquals(joke, answer("tell me a joke", joke))
        // A reply that is nothing but the claim is kept: Robot would say nothing.
        assertFalse(answer("what's my doctor's name", "I have saved that.").isEmpty())
    }

    @Test fun aDictatedNoteIsSavedWithoutTheModel() {
        // fix-123-d, Test Bench bug-everyday-b-5 on 1.2.3: the second dictated note of a conversation got an answer and no
        // add_note call (brain_reply calls=0). Each is add_note's call in your words, run without the model.
        val actions = Actions()
        val lines = listOf("take a note that the gate code changed", "take a note that the plumber comes Thursday").map { said ->
            val view = View()
            val call = NoteDictation.direct(said)!!
            runBlocking {
                TurnRunner(NoteDictation.Direct(call), actions, view, Confirmations(), false, asked = setOf(ToolName.ADD_NOTE),
                    newId = { "d${++ids}" }, heard = heard(said)).run()
            }
            assertEquals(ActionStatus.DONE, view.cards.values.single().status)
            view.text
        }
        assertEquals(listOf(Action.AddNote("the gate code changed", null), Action.AddNote("the plumber comes Thursday", null)), actions.ran)
        assertTrue(lines.toString(), lines.all { it.isNotBlank() })
        assertEquals("milk and eggs", NoteDictation.direct("Robot, jot down milk and eggs.")!!.args["text"])
        assertEquals("le plombier vient jeudi", NoteDictation.direct("prends en note que le plombier vient jeudi")!!.args["text"])
    }

    @Test fun aNotedQuestionIsAnsweredThenSavedWithItsAnswer() {
        // fix-note-answer (phone-142-rc1 J3): "write down how to say thank you in French" went to the model, which called
        // add_note with the question: the note held only it, and the question was never answered.
        val asked = mapOf(
            "write down how to say thank you in French" to "how to say thank you in French",
            "Robot, jot down what is the capital of Australia" to "what is the capital of Australia",
            "take a note of how do I boil an egg?" to "how do I boil an egg",
            "prends note de comment dit-on bonjour en espagnol" to "comment dit-on bonjour en espagnol",
            "note que qu'est-ce que la photosynthèse" to "qu'est-ce que la photosynthèse",
        )
        for ((said, question) in asked) {
            assertEquals(said, question, NoteDictation.question(said))
            assertEquals(said, null, NoteDictation.direct(said))
            assertEquals(said, question, NoteDictation.questionForTurn(said, FastPath.of(null, false, Attachment.NONE), null, Attachment.NONE, false))
        }
        // rc1's dictations stay notes in your words, never a question.
        for (said in listOf("jot down where I parked: level 3", "take a note of my parking spot level 3", "prends note d'acheter du lait",
                "take a note that the plumber comes Thursday", "jot down call the bank E122", "jot down what you just said", "write down what is on my screen",
                "jot down when is the dentist: Tuesday")) assertEquals(said, null, NoteDictation.question(said))
        assertEquals("my parking spot level 3", NoteDictation.direct("take a note of my parking spot level 3")!!.args["text"])
        assertEquals("acheter du lait", NoteDictation.direct("prends note d'acheter du lait")!!.args["text"])
        assertEquals("where I parked: level 3", NoteDictation.direct("jot down where I parked: level 3")!!.args["text"])
        // A helper's turn or something attached: not Robot's.
        assertEquals(null, NoteDictation.questionForTurn("write down how to say thank you in French", FastPath.Way.HELPER, null, Attachment.NONE, false))
        assertEquals(null, NoteDictation.questionForTurn("write down how to say thank you in French", FastPath.Way.TEXT, null, Attachment.SCREEN, false))

        // The model's answer (no tools), then add_note's call with the question and the answer.
        fun run(reply: String, outcome: ModelOutcome, actions: Actions = Actions(), question: String = "how to say thank you in French"): Triple<TurnResult, Actions, String> {
            val view = View()
            val inner = object : TurnModel {
                override suspend fun ask() = outcome
                override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = error("no tool was asked for")
                override fun close() {}
            }
            val r = runBlocking {
                TurnRunner(NoteDictation.Answered(inner, question, { reply }, "none", show = view::replaceText), actions, view, Confirmations(), false,
                    asked = setOf(ToolName.ADD_NOTE), newId = { "q${++ids}" }, heard = heard(question)).run()
            }
            return Triple(r, actions, view.text)
        }
        val (ok, saved, _) = run("Merci.", ModelOutcome.Done(DoneReason.END))
        assertEquals(null, ok.failure)
        assertEquals(listOf(Action.AddNote("How to say thank you in French: Merci.", null)), saved.ran)
        // review-fix-note-answer M2, L1: no answer (nothing, a cut one, a failure, a hedge, the model's own call with a real reply,
        // calls written as text): your words are saved as you said them, and Robot says it found no answer in place of the reply.
        for ((reply, outcome) in listOf("  " to ModelOutcome.Done(DoneReason.END), "Mer" to ModelOutcome.Done(DoneReason.LENGTH),
                "" to ModelOutcome.Failed("model failed"), "I don't know." to ModelOutcome.Done(DoneReason.END),
                "Sure, I'll note that." to ModelOutcome.Calls(listOf(RawToolCall(ToolName.ADD_NOTE.id, mapOf("text" to "how to say thank you in French")))),
                "add_note(text=\"how to say thank you in French\")" to ModelOutcome.Done(DoneReason.END, textCalls = true),
                // review-fix-note-answer-c L-b: calls written as text come back as a reply on this chat-only turn (textCalls false).
                "add_note(text=\"how to say thank you in French\")" to ModelOutcome.Done(DoneReason.END))) {
            val (r, a, text) = run(reply, outcome)
            assertEquals(outcome.toString(), listOf(Action.AddNote("how to say thank you in French", null)), a.ran)
            assertEquals(outcome.toString(), null, r.failure)
            assertTrue(text, text.startsWith("none") && "Sure" !in text && "know" !in text)
        }
        // A stopped reply stays as it is: nothing saved.
        assertTrue(run("Mer", ModelOutcome.Done(DoneReason.CANCELLED)).second.ran.isEmpty())
        // review-fix-note-answer-c L-a: a save that failed never says Robot kept your note; the card's line says what happened.
        val (_, failed, said) = run("I don't know.", ModelOutcome.Done(DoneReason.END), Actions(ActionStatus.FAILED))
        assertEquals(1, failed.ran.size)
        assertTrue(said, "none" !in said && "know" !in said)
        // L-c: hedge words that are the answer ("how to say I don't know in Spanish") are still an answer.
        val (_, hedged, _) = run("'I don't know' is 'No se'.", ModelOutcome.Done(DoneReason.END), question = "how to say I don't know in Spanish")
        assertEquals(listOf(Action.AddNote("How to say I don't know in Spanish: 'I don't know' is 'No se'.", null)), hedged.ran)
        // fix-142-lows: only the question's own hedge (or its twin in the other language) is the answer; another hedge still counts.
        val (_, other, _) = run("I don't know. I'm not sure what you mean.", ModelOutcome.Done(DoneReason.END), question = "how to say I don't know in Spanish")
        assertEquals(listOf(Action.AddNote("how to say I don't know in Spanish", null)), other.ran)
        val (_, twin, _) = run("Je ne sais pas.", ModelOutcome.Done(DoneReason.END), question = "how to say I don't know in French")
        assertEquals(listOf(Action.AddNote("How to say I don't know in French: Je ne sais pas.", null)), twin.ran)
        val (_, jumeau, _) = run("I don't know.", ModelOutcome.Done(DoneReason.END), question = "comment dit-on je ne sais pas en anglais")
        assertEquals(listOf(Action.AddNote("Comment dit-on je ne sais pas en anglais: I don't know.", null)), jumeau.ran)
        val (_, autre, _) = run("Je ne suis pas sûr.", ModelOutcome.Done(DoneReason.END), question = "comment dit-on je ne sais pas en anglais")
        assertEquals(listOf(Action.AddNote("comment dit-on je ne sais pas en anglais", null)), autre.ran)
        // L2: French joins with " : ", English with ": ".
        assertEquals("Comment dit-on bonjour en espagnol : hola", NoteDictation.noteOf("comment dit-on bonjour en espagnol", "hola", Lang.FR)!!.args["text"])
        assertEquals("How to say hi in Spanish: hola", NoteDictation.noteOf("how to say hi in Spanish", "hola", Lang.EN)!!.args["text"])
        // M1: a question with a colon tells: your words, never the model's, in French and in English.
        for ((said, mine) in listOf("prends note de comment dit-on merci en espagnol : gracias" to "comment dit-on merci en espagnol : gracias",
                "prends note de quel est le code de la porte : 4521" to "quel est le code de la porte : 4521",
                "jot down what is the door code: 4521" to "what is the door code: 4521", "write down buy milk" to "buy milk",
                // L-d: an asking question with a colon tells too.
                "jot down how to reset the router: hold the button" to "how to reset the router: hold the button",
                "jot down where is the spare key: under the mat" to "where is the spare key: under the mat",
                "prends note de où est la clé : sous le tapis" to "où est la clé : sous le tapis")) {
            assertEquals(said, mine, NoteDictation.direct(said)?.args?.get("text"))
            assertEquals(said, null, NoteDictation.question(said))
        }
    }

    @Test fun aSecondDictatedNoteInOneConversationIsSavedWithoutTheModel() {
        // fix-jot-down-after-note (phone-141-followups 1, Pixel 8 on 1.4.1): "jot down call the bank E122" right after
        // "take a note that the plumber comes Thursday E122" went to the model, which once answered "Nothing was done.".
        val chat = mutableListOf<ChatTurn>()
        for ((i, said) in listOf("take a note that the plumber comes Thursday E122", "jot down call the bank E122").withIndex()) {
            val way = FastPath.of(null, false, Attachment.NONE)
            val call = NoteDictation.forTurn(said, way, null, Attachment.NONE, TurnRules.untrusted(chat), false)
            assertEquals(said, NoteDictation.said(said), call?.args?.get("text"))
            // The state the note leaves: your words, and its card's record (Robot's own action, no content).
            chat += ChatTurn("u$i", TurnRole.USER, said, 0)
            chat += ChatTurn("a$i", TurnRole.ASSISTANT, "", 0, actions = listOf(ActionRecord(ToolName.ADD_NOTE, "x", ActionStatus.DONE)))
        }
        val said = "jot down call the bank E122"
        // A helper's turn, a helper phrase, something attached, or content Robot didn't write: never the direct path.
        assertEquals(null, NoteDictation.forTurn(said, FastPath.Way.HELPER, null, Attachment.NONE, false, false))
        assertEquals(null, NoteDictation.forTurn(said, FastPath.Way.TEXT, null, Attachment.NONE, false, true))
        assertEquals(null, NoteDictation.forTurn(said, FastPath.Way.MODEL, null, Attachment.SCREEN, false, false))
        assertEquals(null, NoteDictation.forTurn(said, FastPath.Way.MODEL, HelperCommand.Leave, Attachment.NONE, false, false))
        assertEquals(null, NoteDictation.forTurn(said, FastPath.Way.TEXT, null, Attachment.NONE, true, false))
    }

    @Test fun aQuestionAfterTheNotePhraseIsTheModels() {
        // review-fix-jot-down-after-note L3: voice has no "?", so a question after the note phrase is the model's to answer.
        for (s in listOf("write down how to say thank you in French", "jot down where is the nearest pharmacy", "note down when does the pool open",
            "write down who was the first prime minister", "jot down why is the sky blue", "write down how do I reset the router",
            "prends en note comment faire un nœud plat", "prends en note quand est-ce que la piscine ouvre", "prends en note où est la clé de secours"))
            assertEquals(s, null, NoteDictation.direct(s))
        // Narrow: what you tell it is still a note.
        assertEquals("where I parked: level 3", NoteDictation.direct("jot down where I parked: level 3")!!.args["text"])
        assertEquals("when the dentist called, she said Tuesday", NoteDictation.direct("note that when the dentist called, she said Tuesday")!!.args["text"])
        assertEquals("où j'ai stationné : niveau 3", NoteDictation.direct("prends en note où j'ai stationné : niveau 3")!!.args["text"])
    }

    @Test fun aNoteOfSavesOnlyWhatFollows() {
        // review-fix-jot-down-after-note L4: "take a note of my parking spot" saved "of my parking spot".
        for ((s, mine) in listOf("take a note of my parking spot" to "my parking spot", "make a note of the gate code 4512" to "the gate code 4512",
            "take note of the gate code 4512" to "the gate code 4512", "prends note de rappeler Sam" to "rappeler Sam",
            "prends note d'acheter du lait" to "acheter du lait", "fais une note de rappeler Sam" to "rappeler Sam",
            "prends une note d’appeler la banque" to "appeler la banque"))
            assertEquals(s, mine, NoteDictation.direct(s)?.args?.get("text"))
        assertEquals(null, NoteDictation.direct("take a note of the recipe on my screen"))
    }

    @Test fun compoundAsksAreThePlansAndAPlainQuestionIsNoNote() {
        // review-fix-jot-down-after-note L5: PlanRouter or MultiAsk claims these before the note path (PanelController's when).
        val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
        for (s in listOf("take a note of my parking spot and remind me at 5", "note that the meeting moved, and tell Sam", "note that the meeting moved and let Sam know"))
            assertTrue(s, app.heyrobot.core.skills.PlanRouter.route(s) != null || app.heyrobot.core.skills.MultiAsk.split(s, now) != null)
        for (s in listOf("what's the weather", "how do I take a note", "quelle est la météo"))
            assertEquals(s, null, NoteDictation.forTurn(s, FastPath.of(null, false, Attachment.NONE), null, Attachment.NONE, false, false))
    }

    @Test fun aNoteThatPointsElsewhereStaysTheModels() {
        for (s in listOf("jot down what you just said", "write this down", "take a note of the recipe on my screen", "note that down",
            "can you take a note?", "take a note", "write down everything above", "note ça", "prends en note ce que tu viens de dire",
            "what's on my calendar", "remember my locker is 12")) {
            assertEquals(s, null, NoteDictation.direct(s))
        }
    }

    @Test fun aPaddedRewordingOfYourNoteIsKeptInYourWords() {
        // fix-123-b, Test Bench bug-everyday-b-5 on 1.2.3: the model's made-up title and its padded rewording (4 of its 11 words yours).
        val a = NoteDictation.keep(Action.AddNote("The gate code has changed. Please update all necessary access codes.", "Gate Code Update"),
            "take a note that the gate code changed")
        assertEquals(Action.AddNote("the gate code changed", null), a)
    }
}
