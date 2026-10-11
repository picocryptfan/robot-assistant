package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.ToolName
import app.heyrobot.core.memory.MemoryReplies
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7, memory's cards in the panel: "Remembered" with its words, Undo and Open Memory; "Forgotten" with Undo; the pick
 * card's rows; "forget everything" pointing to Settings › Memory. Each button does what it says, every target 48 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MemoryCardTest {
    @get:Rule val rule = createComposeRule()
    private val log = ArrayList<String>()
    private val more = CardActions(
        choose = { id, i, keep -> log += "choose $id $i $keep" }, undo = { log += "undo $it" }, allow = { log += "allow" }, openApp = { log += "open $it" },
    )
    private val confirm: (String, Boolean) -> Unit = { id, yes -> log += "confirm $id $yes" }
    private val openMemory = SettingsPage.label(SettingsPage.MEMORY)

    @Test fun rememberedForgottenAndPick() {
        val saved = ActionView("m1", ToolName.REMEMBER, "Remembered", ActionStatus.DONE, MemoryReplies.CARD_SAVED_DETAIL,
            body = "My sister's birthday is May 3", undo = true, openApp = openMemory)
        val gone = ActionView("m2", ToolName.FORGET, MemoryReplies.CARD_FORGOTTEN, ActionStatus.DONE, MemoryReplies.CARD_FORGOTTEN_DETAIL,
            body = "• Léo has hockey on Tuesdays", undo = true)
        val pick = ActionView("m3", ToolName.FORGET, MemoryReplies.CARD_WHICH, ActionStatus.PENDING, null,
            choices = listOf("Julie likes tulips", "Julie is vegetarian"))
        val all = ActionView("m4", ToolName.REMEMBER, "Delete everything Robot remembers", ActionStatus.SKIPPED,
            "Only Settings › Memory deletes everything.", openApp = openMemory)
        rule.show {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (a in listOf(saved, gone, pick, all)) ActionCard(a, confirm, more)
            }
        }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("My sister's birthday is May 3").assertExists()
        rule.onNodeWithText("Not done. Only Settings › Memory deletes everything.").assertExists()
        rule.onNodeWithText("Julie is vegetarian").performClick()
        assertEquals("choose m3 1 false", log.last())
        assertEquals("Open Memory", openMemory)
        rule.onNodeWithText("• Léo has hockey on Tuesdays").assertExists()
    }
}
