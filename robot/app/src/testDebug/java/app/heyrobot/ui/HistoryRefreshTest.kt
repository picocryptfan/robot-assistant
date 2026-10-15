// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.sys.ConversationStore
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 0.7.0, the tester's 0.6.0 report: History kept showing the old list after conversations were deleted or new ones were
 * saved, until Settings was closed and opened again. The list now follows the conversations folder: a save or a delete by
 * either process changes its stamp, and the list shown is read again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = PHONE)
class HistoryRefreshTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun conv(id: String, question: String, at: Long) =
        StoredConversation(id, at, at, listOf(ChatTurn("u-$id", TurnRole.USER, question, at), ChatTurn("a-$id", TurnRole.ASSISTANT, "OK.", at)))

    @Test fun theListShownFollowsChangesWithoutLeavingThePage() {
        val stored = mutableListOf(conv("c1", "What is the capital of Peru?", 1_000L))
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        var loads = 0
        rule.show {
            val h = rememberHistory(active = true, tick = 0, changes = changes) { loads++; stored.toList() }
            HistoryScreen(h, ActionLog().actions, Modifier)
        }
        rule.onNodeWithText("What is the capital of Peru?").assertExists()
        // The panel saves a new conversation (the voice process), and one is deleted: the page shows it at once.
        stored.add(0, conv("c2", "Set a timer for ten minutes", 2_000L))
        changes.tryEmit(1)
        rule.waitForIdle()
        rule.onNodeWithText("Set a timer for ten minutes").assertExists()
        stored.removeAt(1)
        changes.tryEmit(2)
        rule.waitForIdle()
        rule.onNodeWithText("What is the capital of Peru?").assertDoesNotExist()
        // Everything deleted: the empty page.
        stored.clear()
        changes.tryEmit(3)
        rule.waitForIdle()
        rule.onNodeWithText("No conversations yet").assertExists()
        assertEquals(4, loads)
    }

    @Test fun theFoldersStampChangesWhenEitherProcessSavesOrDeletes() {
        ConversationStore.deleteAll(ctx)
        val empty = ConversationStore.stamp(ctx)
        ConversationStore.save(ctx, conv("c1", "Hello", 1_000L))
        val one = ConversationStore.stamp(ctx)
        assertNotEquals(empty, one)
        Thread.sleep(20)
        // Saved again (a reply added): a new file renamed over it.
        ConversationStore.save(ctx, conv("c1", "Hello again", 2_000L))
        val again = ConversationStore.stamp(ctx)
        assertNotEquals(one, again)
        Thread.sleep(20)
        ConversationStore.delete(ctx, "c1")
        assertNotEquals(again, ConversationStore.stamp(ctx))
        assertEquals(emptyList<StoredConversation>(), ConversationStore.list(ctx))
    }
}
