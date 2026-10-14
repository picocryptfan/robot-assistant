package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.ConversationScreen
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A conversation's turns (ui/common Transcript.kt) in the panel and History, as 0.6.0's chat bubbles: your words as one
 * TalkBack heading per exchange, "You: …"; a helper's answer under its name; the caret and typing dots while a reply is
 * written; an error said in words; the kept screen text opens and closes by name; the offer asks before anything goes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TranscriptTest {
    @get:Rule val rule = createComposeRule()

    private val hello = TurnUi("u1", user = true, text = "Hello")
    private val hi = TurnUi("r1", user = false, text = "Hi. What do you need?")
    private val helper = TurnUi("r2", user = false, text = "Sunlight scatters off the air's molecules.", helper = true, by = "Jarvis", home = true,
        footer = listOf("Jarvis searched the web · nasa.gov"))

    @Test fun yourWordsAreAHeadingThatSaysWhoSaidThem() {
        rule.show { Column { TurnView(hello, TurnActions.None); TurnView(hi, TurnActions.None) } }
        rule.onAllNodesWithTag("classic-bubble").assertCountEquals(2)
        rule.onNodeWithContentDescription("You: Hello").assert(isHeading())
        // Robot's answer is plain text, not a heading.
        rule.onNodeWithText("Hi. What do you need?").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
    }

    @Test fun aHelpersAnswerIsUnderItsName() {
        rule.show { Column { TurnView(helper, TurnActions.None); TurnView(helper.copy(id = "r3", by = null, home = false, readBack = true, footer = emptyList()), TurnActions.None) } }
        rule.onNodeWithText("Jarvis").assertExists()
        rule.onNodeWithText("Jarvis searched the web · nasa.gov").assertExists()
        rule.onNodeWithText("Claude, from the Claude app").assertExists()
    }

    @Test fun theCaretAndTheDotsWhileItIsWritten() {
        rule.show { Column { TurnView(hi.copy(text = "Hi. What", streaming = true), TurnActions.None); TurnView(hi.copy(id = "r9", text = "", streaming = true), TurnActions.None) } }
        // 0.6.0's caret while a reply is written, and its typing dots before the first word.
        rule.onNodeWithText("Hi. What ▍").assertExists()
        rule.onAllNodesWithTag("classic-bubble").assertCountEquals(2)
    }

    @Test fun anErrorSaysSoBesidesItsColour() {
        rule.show { TurnView(hi.copy(text = "The model stopped.", error = true), TurnActions.None) }
        rule.onNodeWithText("The model stopped.").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Error"))
    }

    @Test fun theKeptScreenTextOpensAndClosesByName() {
        val long = (1..12).joinToString("\n") { "Line $it of the screen" }
        rule.show { TurnView(hello.copy(text = "What's this?", attachment = Attachment.SCREEN, screenText = long), TurnActions.None) }
        val inset = rule.onNodeWithText(long)
        assertEquals("Show all", inset.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label)
        inset.performClick()
        assertEquals("Show less", inset.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun anOfferAsksBeforeAnythingGoes() {
        val log = ArrayList<String>()
        val offer = hi.copy(offer = OfferView(api = true, text = "Why?", sends = listOf("your question", "the screen's text"), preview = "Line 1", name = "Jarvis"))
        rule.show { TurnView(offer, TurnActions(acceptOffer = { log += "accept $it" }, dismissOffer = { log += "dismiss $it" })) }
        rule.onNodeWithText("Want me to check with Jarvis?").assertExists()
        rule.onNodeWithText("Sends: Your question, the screen's text.").assertExists()
        rule.onNodeWithText("No thanks").performClick()
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf("dismiss r1", "accept r1"), log)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    // ------------------------------------------------------------------ History › a conversation

    private val saved = StoredConversation("c1", created = 0L, updated = 0L, turns = listOf(
        ChatTurn("t1", TurnRole.USER, "Set a timer for 10 minutes", 0L),
        ChatTurn("t2", TurnRole.ASSISTANT, "Timer set for 10 minutes.", 0L, actions = listOf(ActionRecord(ToolName.SET_TIMER, "Timer for 10 minutes", ActionStatus.DONE))),
        ChatTurn("t3", TurnRole.USER, "Why is the sky blue?", 0L),
        ChatTurn("t4", TurnRole.ASSISTANT, "Sunlight scatters off the air's molecules, and blue light scatters the most.", 0L, fromHelper = true, by = "Claude",
            sources = listOf("nasa.gov")),
    ))

    /** The date line is in the phone's time zone: the same zone wherever the goldens are compared. */
    private val zone = java.util.TimeZone.getDefault()
    @Before fun utc() = java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    @After fun back() = java.util.TimeZone.setDefault(zone)

    @Test fun historyShowsTheBubbles() {
        rule.show { ConversationScreen(saved, NO_ACTIONS, SnackbarHostState(), showCost = false) {} }
        assertTrue(rule.onAllNodesWithTag("classic-bubble").fetchSemanticsNodes().size >= 4)
        rule.onNodeWithContentDescription("You: Why is the sky blue?").assert(isHeading())
        rule.onNodeWithContentDescription("Done").assertExists()
        rule.onNodeWithText("Claude").assertExists()
        // Every button has a name (the Continue button's is on its icon: Material hides an extended button's text).
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithContentDescription("Continue").assertExists()
        rule.shot("history_conversation_classic")
    }

    private companion object {
        val NO_ACTIONS = AppActions(
            talk = {}, openSetup = {}, openTab = {}, openConversation = {}, askPermissions = {}, openAssistantSettings = {},
            download = {}, cancelDownload = {}, import = {}, verify = {}, deleteModel = {}, deleteConversation = {},
            deleteAllConversations = {}, speakTest = {}, say = {}, voiceDownload = {}, voiceCancel = {}, voiceDelete = {},
            voiceBench = {}, voiceReset = {}, askContacts = {}, askCalendar = {}, openAppSettings = {}, openNote = {},
            openPage = {}, bargeTest = {},
        )
    }
}
