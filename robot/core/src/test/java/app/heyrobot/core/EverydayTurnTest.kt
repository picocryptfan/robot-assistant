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

    private class Actions : TurnActions {
        val ran = mutableListOf<Action>()
        override val locked: Boolean get() = false
        override val settings: RobotSettings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed {
            ran += action
            return Executed(ActionStatus.DONE, app.heyrobot.core.i18n.ToolStrings.savedToNotes())
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
