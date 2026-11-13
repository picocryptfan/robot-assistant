// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    init { app.heyrobot.sys.HistoryFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun conv(id: String, question: String, at: Long) =
        StoredConversation(id, at, at, listOf(ChatTurn("u-$id", TurnRole.USER, question, at), ChatTurn("a-$id", TurnRole.ASSISTANT, "OK.", at)))

    /** seal-history-fix-c 2: the list is dropped when the window stops (not kept until Android closes it), and none while it loads. */
    @Test fun theListIsDroppedWhenTheWindowStops() {
        val active = androidx.compose.runtime.mutableStateOf(true)
        var seen: List<StoredConversation>? = emptyList()
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        rule.show { seen = rememberHistory(active = active.value, tick = 0, changes = changes) { listOf(conv("c1", "Peru?", 1_000L)) } }
        rule.waitForIdle()
        assertEquals(1, seen?.size)
        active.value = false
        rule.waitForIdle()
        assertNull(seen)
        active.value = true
        rule.waitForIdle()
        assertEquals(1, seen?.size)
    }

    /** seal-history-fix-c 5: Delete these also removes a newer Robot's conversations, and its confirm says so. */
    @Test fun deleteTheseSaysANewerRobotsConversationsGoToo() {
        assertTrue(app.heyrobot.sys.ConversationStore.save(ctx, conv("n1", "Peru?", 1_000L)))
        val f = java.io.File(app.heyrobot.sys.Paths.conversations(ctx), "n1.enc")
        f.writeBytes(f.readBytes().also { it[4] = 99; it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) // a newer layout
        app.heyrobot.sys.HistoryFiles.drop()
        rule.show { HistoryScreen(emptyList(), ActionLog().actions, Modifier) }
        rule.waitForIdle()
        rule.onNodeWithText("Delete these").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("though an update would open it", substring = true).assertExists()
    }

    @Test fun theListShownFollowsChangesWithoutLeavingThePage() {
        val stored = mutableListOf(conv("c1", "What is the capital of Peru?", 1_000L))
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        var loads = 0
        rule.show {
            val h = rememberHistory(active = true, tick = 0, changes = changes) { loads++; stored.toList() }
            HistoryScreen(h.orEmpty(), ActionLog().actions, Modifier, loading = h == null)
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

    /** seal-history step 5: conversations whose key Android removed are kept aside, and History says so with Delete all. */
    @Test fun conversationsKeptAsideAreNamedAndDeleteAllIsOffered() {
        ConversationStore.deleteAll(ctx)
        java.io.File(app.heyrobot.sys.HistoryFiles.vault(ctx).dir, "c9.enc.bad").writeBytes(byteArrayOf(1))
        rule.show { HistoryScreen(emptyList(), ActionLog().actions, Modifier) }
        rule.waitForIdle()
        rule.onNodeWithText("1 conversation can’t be opened").assertExists()
        rule.onNodeWithText("Delete all").assertExists()
        ConversationStore.deleteAll(ctx)
        assertEquals(0, ConversationStore.keptAside(ctx))
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
