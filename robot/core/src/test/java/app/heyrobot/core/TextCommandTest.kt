package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texting without the model: what is read as a text, where the name ends, what is never taken for one. */
class TextCommandTest {
    private val contacts = listOf("Mom", "Mary Ann", "Mary Ann Smith", "Sarah Connor", "Jean-Luc Picard", "Alexandra Roy", "Bob", "Dr. Who")

    private fun p(s: String, typed: Boolean = true) = TextCommand.parse(s, contacts, typed)
    private fun t(name: String, msg: String?) = TextRequest(name, msg)

    @Test fun positives() {
        val cases = listOf(
            "text mom I'm running late" to t("mom", "I'm running late"),
            "Text Mom, I'm running late." to t("Mom", "I'm running late."),
            "txt mom on my way" to t("mom", "on my way"),
            "message Sarah: see you at 5" to t("Sarah", "see you at 5"),
            "msg bob ok" to t("bob", "ok"),
            "sms bob call me" to t("bob", "call me"),
            "text my mom I'm home" to t("mom", "I'm home"),
            "text to mom hello" to t("mom", "hello"),
            "text Mary Ann the keys are under the mat" to t("Mary Ann", "the keys are under the mat"),
            "text mary ann smith hi" to t("mary ann smith", "hi"),
            "text Sarah Connor I'll be there" to t("Sarah Connor", "I'll be there"),
            "text Jean-Luc engage" to t("Jean-Luc", "engage"),
            "text alex running late" to t("alex", "running late"),
            "text mom that dinner is ready" to t("mom", "dinner is ready"),
            "text mom saying I love you" to t("mom", "I love you"),
            "text mom \"happy birthday\"" to t("mom", "happy birthday"),
            "text mom “see you soon”" to t("mom", "see you soon"),
            "send a text to mom saying hi" to t("mom", "hi"),
            "send a message to Bob: the meeting moved" to t("Bob", "the meeting moved"),
            "send text to dad I'll call later" to t("dad", "I'll call later"),
            "send mom a text saying hi" to t("mom", "hi"),
            "send Bob a message that I'm late" to t("Bob", "I'm late"),
            "tell mom I'm on my way" to t("mom", "I'm on my way"),
            "tell Sarah Connor that the meeting is at 3" to t("Sarah Connor", "the meeting is at 3"),
            "let mom know I'm on my way" to t("mom", "I'm on my way"),
            "let Bob know that I'll be late" to t("Bob", "I'll be late"),
            "please text mom hi" to t("mom", "hi"),
            "can you text mom I'm late" to t("mom", "I'm late"),
            "could you please message dad call me" to t("dad", "call me"),
            "text 5145550101 I'm late" to t("5145550101", "I'm late"),
            "text +15145550101 hi" to t("+15145550101", "hi"),
            "Hey robot, text mom hi" to t("mom", "hi"),
        )
        assertTrue(cases.size >= 30)
        for ((input, want) in cases) assertEquals(input, want, p(input))
    }

    @Test fun noMessageMeansAsk() {
        assertEquals(t("mom", null), p("text mom"))
        assertEquals(t("Mom", null), p("Text Mom.", typed = false))
        assertEquals(t("Mary Ann", null), p("text Mary Ann"))
        assertEquals(t("mom", null), p("send a text to mom"))
        assertEquals("What should the text to Mom say?", TextCommand.askMessage("mom"))
        assertEquals("What should the text to Mary Ann say?", TextCommand.askMessage("mary ann"))
        // "tell" and "let ... know" need a message to be a text at all.
        assertNull(p("tell mom"))
        assertNull(p("let mom know"))
    }

    @Test fun negatives() {
        val no = listOf(
            "tell me a joke", "tell me about Paris", "tell me about the moon landing", "message me later", "what's the text on this page",
            "text", "message", "texting is fun", "send a message", "tell a story", "tell jokes", "text me", "tell him", "let me know",
            "let me know when it's done", "let it be", "can you tell me the time", "tell the difference between them", "message box",
            "text size bigger", "how do I send a text", "read my messages", "send it", "tell us everything", "what does this text say",
            "make the text bigger", "tell my story", "text on this page", "message them later", "tell everyone hi",
        )
        assertTrue(no.size >= 20)
        for (s in no) assertNull(s, p(s))
    }

    @Test fun twoWordNamesMatchWholeWordsOnly() {
        // "Dan Can…" is not "Dan Cannon": the message starts at "Can".
        assertEquals(t("Dan", "Can you grab milk"), TextCommand.parse("text Dan Can you grab milk", listOf("Dan Cannon")))
        assertEquals(t("Dan Cannon", "the milk"), TextCommand.parse("text Dan Cannon the milk", listOf("Dan Cannon")))
        assertEquals(t("Mary Ann", "hi"), TextCommand.parse("text Mary Ann hi", listOf("Mary Ann Smith")))
        // A character prefix still works for one word.
        assertEquals(t("Alex", "hi"), TextCommand.parse("text Alex hi", listOf("Alexandra Roy")))
    }

    @Test fun requestsThatOnlyLookLikeTextsGoToTheModel() {
        for (s in listOf("text recognition", "text translation", "Text recognition", "message history",
            "write a message to my landlord about the heater", "send a text to Bob about the party", "write Bob a message about the rent")) {
            assertNull(s, p(s))
            assertNull(s, TextCommand.parse(s, emptyList()))
        }
        // A contact or a family word with no message still asks for it.
        assertEquals(t("Bob", null), TextCommand.parse("text Bob", listOf("Bob")))
        assertNull(TextCommand.parse("text Bob", emptyList()))
        assertEquals(t("mom", null), TextCommand.parse("text mom", emptyList()))
    }

    @Test fun theLockIsCheckedBeforeAnyContactIsRead() {
        assertEquals(TextCommand.Route.UNLOCK, TextCommand.route("text mom I'm late", null, locked = true, typed = true))
        assertEquals(TextCommand.Route.UNLOCK, TextCommand.route("text mom", null, locked = true, typed = true))
        assertEquals(TextCommand.Route.PARSE, TextCommand.route("text mom I'm late", null, locked = false, typed = true))
        // Needs the contacts to tell: locked, the model (and the gate's lock rule) handles it; unlocked, read them.
        assertEquals(TextCommand.Route.MODEL, TextCommand.route("text Bob", null, locked = true, typed = true))
        assertEquals(TextCommand.Route.PARSE, TextCommand.route("text Bob", null, locked = false, typed = true))
        assertEquals(TextCommand.Route.MODEL, TextCommand.route("tell me a joke", null, locked = false, typed = true))
        assertEquals(TextCommand.Route.MODEL, TextCommand.route("what's the weather", null, locked = true, typed = true))
        // The answer to "what should it say?" and "cancel" (the gate still checks the lock for the answer).
        assertEquals(TextCommand.Route.ANSWER, TextCommand.route("I'm running late", "mom", locked = true, typed = true))
        assertEquals(TextCommand.Route.CANCEL, TextCommand.route("never mind", "mom", locked = false, typed = false))
        // A new text request replaces the question.
        assertEquals(TextCommand.Route.PARSE, TextCommand.route("text dad hi", "mom", locked = false, typed = true))
    }

    @Test fun theModelWatchdogCoversEveryGap() {
        val s = StallTimer(60_000, start = 0)
        assertFalse(s.stalled(59_999))
        s.event(50_000)
        // A first word, then nothing: still caught, counted from the last event.
        assertFalse(s.stalled(100_000))
        assertTrue(s.stalled(110_000))
    }

    @Test fun theNameBoundaryUsesTheContacts() {
        fun n(s: String, c: List<String>, typed: Boolean = true) = TextCommand.nameLength(s.split(' '), c, typed)
        // The longest of 1 to 3 words that is a contact's name (or the start of one).
        assertEquals(2, n("Mary Ann hello there", contacts))
        assertEquals(3, n("mary ann smith hello", contacts))
        assertEquals(1, n("bob hi there", contacts))
        // "alexandra" starts "Alexandra Roy": a prefix counts.
        assertEquals(1, n("alexandra see you", contacts))
        // Nobody matches: one word, or two when a typed second word is capitalised.
        assertEquals(1, n("zed hi", emptyList()))
        assertEquals(2, n("Zed Shaw hi", emptyList()))
        assertEquals(1, n("Zed Shaw hi", emptyList(), typed = false))
        // ... unless it starts the message, or the first is a family word.
        assertEquals(1, n("Zed I'm late", emptyList()))
        assertEquals(1, n("Zed Hi there", emptyList()))
        assertEquals(1, n("Mom Hello", emptyList()))
        assertEquals(1, n("Dad Dinner is ready", emptyList()))
        // A nickname counts like a name.
        assertEquals(2, n("big bob hi", listOf("Robert", "Big Bob")))
    }

    @Test fun messagesLoseTheirLeadInAndQuotes() {
        assertEquals("hi", TextCommand.message(": hi"))
        assertEquals("hi", TextCommand.message("that hi"))
        assertEquals("hi", TextCommand.message("saying \"hi\""))
        assertEquals("it's 'fine'", TextCommand.message("it's 'fine'"))
        assertEquals("fine", TextCommand.message("'fine'"))
        assertNull(TextCommand.message("  "))
        assertNull(TextCommand.message("saying"))
    }

    @Test fun cancelWords() {
        for (s in listOf("cancel", "Never mind.", "nevermind", "forget it", "No")) assertTrue(s, TextCommand.isCancel(s))
        for (s in listOf("I'm late", "no worries, see you", "cancel the meeting tomorrow")) assertFalse(s, TextCommand.isCancel(s))
    }

    // ------------------------------------------------------------------ lookup_contact NOT_FOUND (D2)

    @Test fun lookupNotFoundPointsToCheogramOnlyWhenItIsTheTextingApp() {
        val c = LookupFallback.notFound("Bob", TextApp.CHEOGRAM, cheogramInstalled = true)
        assertEquals("not_in_phone_contacts_may_be_in_cheogram", c.forModel)
        assertTrue(c.openCheogram)
        assertTrue(c.detail.contains("Cheogram"))
        for ((app, installed) in listOf(TextApp.SMS to true, TextApp.OFF to true, TextApp.CHEOGRAM to false)) {
            val n = LookupFallback.notFound("Bob", app, installed)
            assertFalse(n.openCheogram)
            assertEquals("No contact matches “Bob”.", n.forModel)
        }
    }

    @Test fun theModelGetsTheCodeAndTheCardTheWords() = runBlocking {
        val code = LookupFallback.CODE_MAY_BE_IN_CHEOGRAM
        val cards = mutableListOf<Card>()
        val model = object : TurnModel {
            var answered: List<Pair<String, Map<String, Any?>>>? = null
            override suspend fun ask() = ModelOutcome.Calls(listOf(RawToolCall("lookup_contact", mapOf("name" to "Bob"))))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { answered = results; return ModelOutcome.Done(DoneReason.END) }
            override fun close() {}
        }
        val actions = object : TurnActions {
            override val locked = false
            override val settings = RobotSettings()
            override suspend fun prepare(action: Action) = Prepared.Ready(action)
            override suspend fun run(action: Action) = Executed(ActionStatus.NOT_FOUND, "No contact matches “Bob” on the phone.", forModel = code, handOff = "com.cheogram.android")
        }
        val view = object : TurnView {
            override fun phase(p: TurnPhase) {}
            override fun card(c: Card) { cards += c }
            override val text = ""
            override fun replaceText(text: String) {}
            override fun paragraphBreak() {}
            override fun say(sentence: String) {}
        }
        var n = 0
        TurnRunner(model, actions, view, Confirmations(), untrusted = false) { "id${n++}" }.run()
        assertEquals(code, model.answered!!.single().second["detail"])
        assertEquals("No contact matches “Bob” on the phone.", cards.last().detail)
        assertEquals("com.cheogram.android", cards.last().handOff)
    }

    @Test fun theSystemPromptSaysToTextByNameWhenTextsAreOffered() {
        val now = java.time.ZonedDateTime.now()
        val s = Prompt.system(now, locked = false, texting = true)
        assertTrue(s.contains("To text someone, always call send_text with their name, even if they aren't in the phone's contacts."))
        assertFalse(Prompt.system(now, locked = false).contains("send_text"))
        // Still short: it is paid on every rebuild of a conversation that can text.
        assertTrue(TokenEstimate.text(s) < 290)
    }
}
