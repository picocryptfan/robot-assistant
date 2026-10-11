package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Conversation mode's voice commands (docs/conversation-mode.md 5.4): imperatives at the start, English and French. */
class ConversationRouterTest {
    private val transcribe = ConversationCommand.Start(ConvMode.TRANSCRIBE)
    private val interpret = ConversationCommand.Start(ConvMode.INTERPRET)

    private fun route(t: String, running: Boolean = false) = ConversationRouter.route(t, running)

    @Test fun startTranscribing() {
        for (t in listOf("Hey robot, transcribe this conversation", "transcribe this conversation please", "Transcribe.", "Start transcribing",
            "Can you start transcribing this meeting?", "Please transcribe our conversation", "start a transcript", "ok robot transcribe this",
            "Transcris cette conversation", "Peux-tu transcrire la conversation ?", "Commence à transcrire", "Démarre la transcription s'il te plaît",
            "Dis robot, transcris ça")) {
            assertEquals(t, transcribe, route(t))
        }
    }

    @Test fun startTheInterpreter() {
        for (t in listOf("Translate this conversation", "hey robot translate this conversation for us", "Be my interpreter", "Can you be our translator?",
            "Interpret for me", "start interpreting", "Traduis cette conversation", "Pouvez-vous traduire la conversation ?", "Sois mon interprète",
            "Fais l'interprète", "Commence à traduire")) {
            assertEquals(t, interpret, route(t))
        }
        // While transcribing, it switches the session to the Interpreter.
        assertEquals(interpret, route("translate this conversation", running = true))
    }

    @Test fun whileASessionRuns() {
        for (t in listOf("stop transcribing", "Stop the transcript", "hey robot, end the session", "stop translating", "Arrête de transcrire",
            "Arrête la transcription", "Termine la conversation")) {
            assertEquals(t, ConversationCommand.Stop, route(t, running = true))
        }
        for (t in listOf("pause", "Pause the transcript", "pause transcribing", "Mets en pause", "Mets la transcription en pause", "Pause la transcription")) {
            assertEquals(t, ConversationCommand.Pause, route(t, running = true))
        }
        for (t in listOf("resume", "Continue transcribing", "keep going", "Reprends", "Reprends la transcription", "Continue à transcrire")) {
            assertEquals(t, ConversationCommand.Resume, route(t, running = true))
        }
        for (t in listOf("Summarize this conversation", "summarise the meeting please", "Résume cette conversation", "Fais un résumé de la conversation")) {
            assertEquals(t, ConversationCommand.Summarize, route(t, running = true))
        }
    }

    @Test fun withoutASessionTheseAreJustWords() {
        for (t in listOf("stop transcribing", "pause", "resume", "summarize this conversation", "Résume cette conversation")) assertNull(t, route(t))
    }

    @Test fun questionsStayQuestions() {
        for (t in listOf("How do I transcribe a song?", "Can you translate 'merci'?", "Translate good morning into French", "What's a transcript?",
            "transcribe a song", "Who will translate this conversation?", "I want to translate this conversation later", "Comment traduire « merci » ?",
            "Traduis bonjour en anglais", "Is it legal to transcribe this conversation?", "translate this conversation about cars", "")) {
            assertNull(t, route(t))
            assertNull(t, route(t, running = true))
        }
        // "Stop" alone is the stop-talking word, not the end of a session.
        assertNull(route("stop", running = true))
    }
}
