// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import app.heyrobot.core.link.ListShare
import app.heyrobot.core.link.ListShareRec
import app.heyrobot.core.link.ListShareState
import app.heyrobot.core.lists.ListsShared
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ItemOp
import app.heyrobot.core.lists.JournalAppend
import app.heyrobot.core.lists.ListsJournal
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.NewItem
import app.heyrobot.sys.ListsWrite
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * LS5 (lists-plan.md §7.1 to §7.3, Q1): the Notes tab's two sections, the lists screen (each list with what's left, New
 * list, the empty and locked states) and a list's page (the add field with the spoken rules, ticks, Done, edit, Move to
 * top, Move to another list, Delete with Undo, details with history and where an item came from, Rename, Share as text,
 * Delete list behind its dialog), and Make a list from this note; in English and Québec French, light and dark, with
 * goldens. The lists are held in memory here (the vault has its own tests): every change goes through the port's update
 * as the phone's goes through the vault's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ListsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val t0 = 1_761_150_000_000 // 22 Oct 2025, 16:20 UTC
    private var n = 0x100
    private val ids: () -> ListsId = { ListsId.of("%032x".format(n++))!! }
    private var zone: TimeZone? = null
    private companion object {
        const val SMALL = "w320dp-h640dp-mdpi"
    }

    /** The lists in memory, changed as the vault changes them. */
    private inner class MemPort(var file: ListsFile) : ListsPort {
        var locked = false
        var on = true
        val events = ArrayList<String>()
        override fun load(): ListsLoad<ListsFile> = if (locked) ListsLoad.Locked else ListsLoad.Loaded(file)
        override fun update(how: ItemHow, change: (ListsFile, ListsWho) -> ListsEdit): ListsWrite {
            if (locked) return ListsWrite(ListsLoad.Locked)
            val e = change(file, ListsWho(ItemBy.YOU, how, t0 + 60_000))
            if (e is ListsEdit.Done && e.changed) file = e.file
            return ListsWrite(ListsLoad.Loaded(file), e)
        }
        override fun on() = on
        override fun changed() { events += "changed" }
        var sharing = ListSharing()
        var offers = true
        override fun sharing() = sharing
        override fun share(l: ListsList, name: String, pairing: String): Boolean {
            events += "share $pairing"
            if (offers) file = ListShare.shared(file, l.id, pairing)
            return offers
        }
        override fun stopSharing(list: ListsId, pairing: String): Boolean {
            events += "stop $pairing"
            file = ListShare.left(file, list)
            return true
        }
    }

    private val sam = "a".repeat(32)
    private val samRobot = ListSharing(listOf(ListRobot(sam, "Sam")), named = mapOf(sam to "Sam"))
    private fun sharedPort(state: ListShareState = ListShareState.JOINED) = MemPort(ListShare.shared(seeded(), shopping, sam)).apply {
        sharing = ListSharing(listOf(ListRobot(sam, "Sam")), listOf(ListShareRec(shopping.hex, sam, state, 0)), mapOf(sam to "Sam"))
    }

    private val start = ListsEdits.firstUse(t0, ids)
    private val shopping = start.withRole(ListRole.SHOPPING)!!.id
    private val todo = start.withRole(ListRole.TODO)!!.id

    private fun ListsFile.plus(list: ListsId, vararg items: NewItem, by: ItemBy = ItemBy.YOU, how: ItemHow = ItemHow.VOICE): ListsFile =
        (ListsEdits.add(this, list, items.toList(), ListsWho(by, how, t0), ids) as ListsEdit.Done).file

    /** Shopping with oat milk (2 L), eggs (said while locked) and bread; To-do with one item. */
    private fun seeded(): ListsFile = start.plus(shopping, NewItem("oat milk", "2 L"))
        .plus(shopping, NewItem("eggs"), by = ItemBy.LOCKED)
        .plus(shopping, NewItem("bread"))
        .plus(todo, NewItem("call the bank"), how = ItemHow.TYPED)

    private fun item(p: MemPort, text: String) = p.file.list(shopping)!!.items.single { it.text == text }

    @Before fun utc() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun back() {
        zone?.let(TimeZone::setDefault)
    }

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(text, substring = substring).assertExists() }.isSuccess }

    private fun says(text: String) = rule.waitUntil(5_000) { log.lastOrNull() == "say $text" }

    // ------------------------------------------------------------------ the lists screen

    @Test fun theListsScreenShowsWhatsLeftOnEachAndMakesANewList() {
        val p = MemPort(seeded())
        var opened: ListsId? = null
        rule.show { ListsSection(p, log.actions, onOpen = { opened = it }) }
        waitFor("Shopping")
        rule.onNodeWithText("3 items left").assertExists()
        rule.onNodeWithText("Oat milk, eggs, bread").assertExists()
        rule.onNodeWithText("1 item left").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_screen")
        rule.onNodeWithText("Shopping").performClick()
        assertEquals(shopping, opened)
    }

    /**
     * LS13 (LS11c on the Pixel 8): Notes › Lists read 14 for Shopping right after an add made in the panel over it, and 15
     * only after a list was opened and closed. The panel is a window over Robot, which stays started under it, so the
     * screen didn't read the lists again; now it does each time its window has the focus back.
     */
    @Test fun anAddMadeInThePanelOverTheScreenIsCountedWhenThePanelCloses() {
        val p = MemPort(seeded())
        val focused = mutableStateOf(true)
        rule.show {
            val real = LocalWindowInfo.current
            val info = remember(real) { object : WindowInfo by real { override val isWindowFocused: Boolean get() = focused.value } }
            CompositionLocalProvider(LocalWindowInfo provides info) { ListsSection(p, log.actions, onOpen = {}) }
        }
        waitFor("3 items left")
        // The panel shows over Robot (its window has the focus now) and adds to Shopping; then it closes.
        rule.runOnIdle { focused.value = false }
        rule.waitForIdle()
        p.file = p.file.plus(shopping, NewItem("plums"))
        rule.runOnIdle { focused.value = true }
        waitFor("4 items left")
    }

    /** A dialog with a text field: on the small screen, as Files' tests type into theirs. */
    @Test @Config(qualifiers = SMALL) fun newListMakesOneAndOpensIt() {
        val p = MemPort(seeded())
        var opened: ListsId? = null
        rule.show { ListsSection(p, log.actions, onOpen = { opened = it }) }
        waitFor("New list")
        // New list: its dialog, then its page.
        rule.onNodeWithText("New list").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Camping")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { opened != null }
        assertEquals("Camping", p.file.list(opened!!)!!.name)
        assertEquals(listOf("changed"), p.events)
    }

    @Test @Config(qualifiers = SMALL) fun aNewListNamedShoppingIsRefusedWhileYouHaveOne() {
        val p = MemPort(seeded())
        rule.show { ListsSection(p, log.actions, onOpen = {}) }
        waitFor("New list")
        rule.onNodeWithText("New list").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("shopping")
        rule.inDialog("Save").performClick()
        says("You already have a list called shopping.")
        assertEquals(2, p.file.lists.size)
    }

    @Test fun noListsYet() {
        rule.show { ListsSection(MemPort(ListsFile()), log.actions, onOpen = {}) }
        waitFor("No lists yet. Say “add oat milk to the shopping list”, or tap New list.")
        rule.onNodeWithText("New list").assertExists()
    }

    @Test fun lockedSaysUnlock() {
        val p = MemPort(seeded()).apply { locked = true }
        rule.show { ListsSection(p, log.actions, onOpen = {}) }
        waitFor("Unlock your phone to see your lists.")
        rule.onNodeWithText("Shopping").assertDoesNotExist()
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun aListFromSomeoneElsesBackupIsMarked() {
        val f = seeded()
        val theirs = f.copy(lists = f.lists + app.heyrobot.core.lists.ListsList(ids(), name = "Hardware", made = t0, from = app.heyrobot.core.lists.ListFrom.BACKUP))
        rule.show { ListsSection(MemPort(theirs), log.actions, onOpen = {}) }
        waitFor("Hardware")
        rule.onNodeWithText("From a backup that isn't yours").assertExists()
    }

    /**
     * LS11a (N2): the widget's picker. Someone else's "Shopping" from their backup sits beside yours: the two rows read the
     * same name, and only theirs carries the mark the Lists screens use.
     */
    @Test fun theWidgetsPickerMarksSomeoneElsesList() {
        val f = seeded()
        val theirs = f.copy(lists = f.lists + app.heyrobot.core.lists.ListsList(ids(), name = "Shopping", made = t0, from = app.heyrobot.core.lists.ListFrom.BACKUP))
        rule.show { PickWindow(ListsLoad.Loaded(theirs), onPick = {}, onClose = {}) }
        waitFor("To-do")
        rule.onAllNodesWithText("Shopping").assertCountEquals(2)
        rule.onAllNodesWithText("From a backup that isn't yours").assertCountEquals(1)
    }

    /**
     * LS13 (LT8 step 4 on the Pixel 8): the picker opened during a phone lock said "Unlock your phone to see your lists."
     * while the phone was unlocked. During a phone lock it says what the widget says; with only the lock screen up, it still
     * says to unlock.
     */
    @Test fun thePickerDuringAPhoneLockSaysRobotIsLocked() {
        rule.show { PickWindow(ListsLoad.Locked, onPick = {}, onClose = {}, robotLocked = true) }
        waitFor("Robot is locked.")
        rule.onNodeWithText("Unlock your phone to see your lists.").assertDoesNotExist()
        rule.onNodeWithText("Cancel").assertExists()
    }

    @Test fun thePickerWhileThePhoneIsLockedSaysUnlock() {
        rule.show { PickWindow(ListsLoad.Locked, onPick = {}, onClose = {}) }
        waitFor("Unlock your phone to see your lists.")
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun thePickerDuringAPhoneLockInFrench() {
        rule.show { PickWindow(ListsLoad.Locked, onPick = {}, onClose = {}, robotLocked = true) }
        waitFor("Robot est bloqué.")
    }

    // ------------------------------------------------------------------ the Notes tab (Q1)

    @Test fun theNotesTabHasListsFirstAndNotes() {
        val p = MemPort(seeded())
        var section = NotesSection.LISTS
        rule.show {
            val s = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(section) }
            NotesTab(s.value, { s.value = it; section = it }, log.actions, SnackbarHostState(), androidx.compose.ui.Modifier, port = { p }) { Text("The notes") }
        }
        waitFor("Oat milk, eggs, bread")
        rule.onNodeWithText("Notes").performClick()
        waitFor("The notes")
        assertEquals(NotesSection.NOTES, section)
        rule.onNodeWithText("Lists").performClick()
        waitFor("Oat milk, eggs, bread")
    }

    @Test fun withListsOffTheNotesTabIsNotesAlone() {
        val p = MemPort(seeded()).apply { on = false }
        rule.show { NotesTab(NotesSection.LISTS, {}, log.actions, SnackbarHostState(), androidx.compose.ui.Modifier, port = { p }) { Text("The notes") } }
        waitFor("The notes")
        rule.onNodeWithText("Lists").assertDoesNotExist()
        rule.onNodeWithText("Shopping").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ a list's page

    private fun page(p: MemPort, list: ListsId = shopping, onBack: () -> Unit = {}) {
        rule.show { ListPage(p, list, log.actions, SnackbarHostState(), onBack) }
        waitFor("Add an item")
    }

    @Test fun thePageAddsByTheSpokenRulesAndTicks() {
        val p = MemPort(seeded())
        page(p)
        rule.onNodeWithText("Oat milk").assertExists()
        rule.onNodeWithText("2 L").assertExists()
        rule.onNodeWithText("Added while the phone was locked").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_page")
        // Typed: commas and "and" split, the article off, the quantity read.
        rule.onNode(hasSetTextAction()).performTextInput("some coffee and 3 avocados")
        rule.onNode(hasSetTextAction()).performImeAction()
        waitFor("Coffee")
        rule.onNodeWithText("Avocados").assertExists()
        assertEquals("3", item(p, "avocados").qty)
        assertEquals(ItemHow.TYPED, item(p, "coffee").how)
        // A secret is refused, and nothing added.
        rule.onNode(hasSetTextAction()).performTextInput("my PIN 4821")
        rule.onNode(hasSetTextAction()).performImeAction()
        says("That looks like a code or a card number. Robot keeps those in Memory, not on a list.")
        // A tick: under Done (collapsed), then shown, then Clear done with Undo.
        rule.onNode(hasContentDescription("Bread") and androidx.compose.ui.test.isToggleable()).performClick()
        waitFor("Done (1)")
        assertTrue(item(p, "bread").done)
        rule.onNodeWithText("Done (1)").performClick()
        waitFor("Clear done")
        rule.shot("lists_page_done")
        rule.onNodeWithText("Clear done").performClick()
        waitFor("Cleared 1 done item.")
        assertFalse(item(p, "bread").live)
        rule.onNodeWithText("Undo").performClick()
        waitFor("Done (1)")
        assertTrue(item(p, "bread").live && item(p, "bread").done)
    }

    @Test @Config(qualifiers = SMALL) fun deleteWithUndoEditMoveAndDetails() {
        val p = MemPort(seeded())
        page(p)
        // Delete from the item's menu: gone at once, Undo for 10 seconds.
        rule.onNodeWithContentDescription("Options for Bread").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitFor("Deleted Bread.")
        rule.onNodeWithText("Bread").assertDoesNotExist()
        rule.onNodeWithText("Undo").performClick()
        waitFor("Bread")
        assertTrue(item(p, "bread").live)
        // Edit: a tap on its words; the words and the quantity.
        rule.onNodeWithText("Oat milk").performClick()
        rule.inDialog("Edit item").assertExists()
        rule.onNode(hasText("2 L") and hasSetTextAction()).performTextClearance()
        rule.onNode(hasText("Quantity") and hasSetTextAction()).performTextInput("1 L")
        rule.inDialog("Save").performClick()
        waitFor("1 L")
        assertEquals("1 L", item(p, "oat milk").qty)
        // Move to top.
        rule.onNodeWithContentDescription("Options for Bread").performClick()
        rule.onNodeWithText("Move to top").performClick()
        rule.waitUntil(5_000) { p.file.list(shopping)!!.open.first().text == "bread" }
        // Move to another list.
        rule.onNodeWithContentDescription("Options for Eggs").performClick()
        rule.onNodeWithText("Move to another list").performClick()
        rule.inDialog("To-do").performClick()
        says("Moved Eggs to To-do.")
        assertEquals(listOf("call the bank", "eggs"), p.file.list(todo)!!.open.map { it.text })
        assertFalse(p.file.list(shopping)!!.items.single { it.text == "eggs" }.live)
        // Details: who added it, how and when, with each change.
        rule.onNodeWithContentDescription("Options for Oat milk").performClick()
        rule.onNodeWithText("Details").performClick()
        rule.inDialog("Oat milk (1 L)").assertExists()
        rule.inDialog("Added by voice · Oct 22, 2025, 4:20", substring = true).assertExists()
        rule.inDialog("Changed in the app · Oct 22, 2025, 4:21", substring = true).assertExists()
        rule.inDialog("Close").performClick()
        // Moved eggs keep where they came from: added while locked.
        val eggs = p.file.list(todo)!!.open.single { it.text == "eggs" }
        assertEquals(ItemBy.LOCKED, eggs.by)
        assertEquals(listOf(ItemOp.ADD, ItemOp.MOVE), eggs.hist.map { it.op })
    }

    /**
     * LS10 (M3, §4.6, §5): an add said while the phone was locked puts a ticked item back; its row says it changed while
     * locked, though the item stays yours. The quantity of an item already open isn't changed by it, so that row has no mark.
     */
    @Test fun anItemPutBackWhileLockedIsMarked() {
        var f = start.plus(shopping, NewItem("oat milk")).plus(shopping, NewItem("eggs"))
        val eggs = f.list(shopping)!!.items.single { it.text == "eggs" }.id
        f = (ListsEdits.tick(f, shopping, eggs, ListsWho(ItemBy.YOU, ItemHow.VOICE, t0)) as ListsEdit.Done).file
        val waiting = ListsJournal.append(ListsJournal.snapshot(f), shopping, listOf(NewItem("eggs"), NewItem("oat milk", "40 L")), t0 + 5, ids) as JournalAppend.Ok
        val p = MemPort(ListsJournal.merge(f, waiting.journal).file)
        page(p)
        waitFor("Eggs")
        rule.onNodeWithText("Changed while the phone was locked").assertExists()
        assertEquals(1, rule.onAllNodesWithText("Changed while the phone was locked").fetchSemanticsNodes().size)
        rule.onNodeWithText("Added while the phone was locked").assertDoesNotExist()
        assertFalse(item(p, "eggs").done)
        assertNull(item(p, "oat milk").qty)
    }

    @Test fun detailsSayWhereAnItemCameFrom() {
        val p = MemPort(seeded())
        page(p)
        rule.onNodeWithContentDescription("Options for Eggs").performClick()
        rule.onNodeWithText("Details").performClick()
        rule.inDialog("Added by voice, while the phone was locked", substring = true).assertExists()
        rule.shot("lists_details")
    }

    // ------------------------------------------------------------------ 1.5: lists shared between Robots

    @Test fun aSharedListSaysWithWhomAndStoppingAsksFirstAndKeepsYourCopy() {
        val p = sharedPort()
        page(p)
        waitFor("Shared with Sam")
        rule.shot("lists_shared")
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Stop sharing").performClick()
        rule.inDialog("Stop sharing this list?").assertExists()
        rule.shot("lists_stop_sharing")
        rule.inDialog("Stop sharing").performClick()
        rule.waitUntil(5_000) { "stop $sam" in p.events }
        assertTrue(p.file.list(shopping)!!.open.isNotEmpty())
        rule.onAllNodesWithText("Shared with Sam").assertCountEquals(0)
    }

    @Test fun changesThatDidntFitAreSaidOnTheListsPageUntilDismissed() {
        val p = sharedPort()
        val l = p.file.list(shopping)!!
        p.file = p.file.with(l.copy(extra = l.extra + (ListsShared.PEER_FULL to true)))
        page(p)
        waitFor("Some changes from Sam didn't fit: this list is full here, so your copy and theirs now differ. Once there's room, they'll need to make those changes again.")
        rule.onNodeWithText("Dismiss").performClick()
        rule.waitUntil(5_000) { ListsShared.PEER_FULL !in p.file.list(shopping)!!.extra }
        rule.onAllNodesWithText("Dismiss").assertCountEquals(0)
    }

    @Test fun anOfferWaitsForTheirYes() {
        page(sharedPort(ListShareState.OFFERED))
        waitFor("Waiting for Sam to say yes")
    }

    @Test fun sharingPicksARobotAndTheyreAskedFirst() {
        val p = MemPort(seeded()).apply { sharing = samRobot }
        page(p)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share with a Robot").performClick()
        rule.inDialog("Sam").assertExists()
        rule.shot("lists_share_robot")
        rule.inDialog("Sam").performClick()
        says("Sent to Sam. They're asked first.")
        assertTrue("share $sam" in p.events)
        waitFor("Shared with Sam")
    }

    @Test fun anOfferThatCouldntGoSaysSoAndTheListIsntShared() {
        val p = MemPort(seeded()).apply { sharing = samRobot; offers = false }
        page(p)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share with a Robot").performClick()
        rule.inDialog("Sam").performClick()
        says("Couldn't send the list to Sam. Try again later.")
        assertTrue(ListsShared.with(p.file.list(shopping)!!).isEmpty())
    }

    @Test fun withNoRobotThePickerSaysWhy() {
        page(MemPort(seeded()))
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share with a Robot").performClick()
        rule.inDialog("No paired Robot can share lists yet. You can allow it in that Robot's settings.").assertExists()
    }

    @Test fun aListFromAnotherPersonSaysSoAfterTheShareEnds() {
        val f = seeded()
        page(MemPort(f.with(f.list(shopping)!!.copy(from = ListFrom.PEER))))
        waitFor("From another person's Robot")
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun aSharedListInFrench() {
        val p = sharedPort()
        rule.show { ListPage(p, shopping, log.actions, SnackbarHostState(), {}) }
        waitFor("Partagée avec Sam")
        rule.shot("lists_shared_fr")
        rule.onNodeWithContentDescription("Plus d’options").performClick()
        rule.onNodeWithText("Arrêter le partage").performClick()
        rule.shot("lists_stop_sharing_fr")
    }

    @Test fun deleteListAsksFirst() {
        val p = MemPort(seeded())
        page(p)
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete list").performClick()
        rule.inDialog("Delete Shopping and its 3 items?").assertExists()
        rule.shot("lists_delete_list")
        rule.inDialog("Cancel").performClick()
        assertTrue(p.file.list(shopping) != null && p.events.isEmpty())
    }

    @Test @Config(qualifiers = SMALL) fun renameShareAndDeleteTheList() {
        val p = MemPort(seeded())
        var back = false
        page(p, onBack = { back = true })
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Share as text").performClick()
        val chooser = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        val sent = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("Shopping\n- Oat milk (2 L)\n- Eggs\n- Bread", sent.getStringExtra(Intent.EXTRA_TEXT))
        // Rename: a role list keeps its role.
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.inDialog("Rename Shopping").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Shopping")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasAnyDialog()).performTextInput("Groceries")
        rule.inDialog("Save").performClick()
        waitFor("Groceries")
        assertEquals("Groceries", p.file.list(shopping)!!.name)
        assertEquals(ListRole.SHOPPING, p.file.list(shopping)!!.role)
        // Delete list: its own dialog.
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete list").performClick()
        rule.inDialog("Delete Groceries and its 3 items?").assertExists()
        rule.inDialog("Delete").performClick()
        rule.waitUntil(5_000) { back }
        assertNull(p.file.list(shopping))
        assertEquals("say Deleted the list Groceries.", log.lastOrNull())
    }

    private fun hasAnyDialog() = androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())

    @Test fun aListGoneOrLocked() {
        val p = MemPort(seeded())
        rule.show { ListPage(p, ids(), log.actions, SnackbarHostState(), onBack = {}) }
        waitFor("This list isn't here any more.")
    }

    @Test fun thePageWhileLocked() {
        val p = MemPort(seeded()).apply { locked = true }
        rule.show { ListPage(p, shopping, log.actions, SnackbarHostState(), onBack = {}) }
        waitFor("Unlock your phone to see your lists.")
        rule.onNodeWithText("Oat milk").assertDoesNotExist()
    }

    @Test fun keyGoneOpensSettings() {
        val p = object : ListsPort by MemPort(seeded()) {
            override fun load(): ListsLoad<ListsFile> = ListsLoad.KeyGone
        }
        rule.show { ListsSection(p, log.actions, onOpen = {}) }
        waitFor("Robot's lists key is gone, so your lists can't be read.")
        rule.onNodeWithText("Lists settings").performClick()
        assertEquals("openPage ${Page.Settings("lists")}", log.lastOrNull())
    }

    // ------------------------------------------------------------------ Make a list from this note

    @Test @Config(qualifiers = SMALL) fun makeAListFromANote() {
        val p = MemPort(seeded())
        val snackbar = SnackbarHostState()
        rule.show {
            androidx.compose.material3.Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }) { _ ->
                MakeListButton("Pancakes", "- flour\n- 2 eggs\n- milk\n- my PIN 4821", log.actions, snackbar, port = { p })
            }
        }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithContentDescription("Make a list from this note").assertExists() }.isSuccess }
        rule.onNodeWithContentDescription("Make a list from this note").performClick()
        waitFor("Made the list Pancakes with 3 items. Left out 1 line that can't be an item.")
        val made = p.file.lists.single { it.name == "Pancakes" }
        assertEquals(listOf("flour", "eggs", "milk"), made.open.map { it.text })
        assertEquals("2", made.open[1].qty)
        assertTrue(made.open.all { it.how == ItemHow.NOTE })
        rule.onNodeWithText("Open").performClick()
        rule.waitUntil(5_000) { log.lastOrNull() == "openPage ${Page.ListPage(made.id.hex)}" }
        // Again: the name is taken, so a dialog asks for one.
        rule.onNodeWithContentDescription("Make a list from this note").performClick()
        says("You already have a list called Pancakes.")
        rule.inDialog("New list").assertExists()
    }

    @Test fun noMakeAListWithListsOff() {
        val p = MemPort(seeded()).apply { on = false }
        rule.show { MakeListButton("Pancakes", "flour", log.actions, SnackbarHostState(), port = { p }) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Make a list from this note").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ French and dark

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun inFrench() {
        val p = MemPort(seeded().let { f -> (ListsEdits.tick(f, shopping, f.list(shopping)!!.open.last().id, ListsWho(ItemBy.YOU, ItemHow.CARD, t0)) as ListsEdit.Done).file })
        rule.show { ListsSection(p, log.actions, onOpen = {}) }
        waitFor("Épicerie")
        rule.onNodeWithText("2 éléments restants").assertExists()
        rule.onNodeWithText("Nouvelle liste").assertExists()
        rule.shot("lists_screen_fr")
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun thePageInFrench() {
        val p = MemPort(seeded().let { f -> (ListsEdits.tick(f, shopping, f.list(shopping)!!.open.last().id, ListsWho(ItemBy.YOU, ItemHow.CARD, t0)) as ListsEdit.Done).file })
        rule.show { ListPage(p, shopping, log.actions, SnackbarHostState(), onBack = {}) }
        waitFor("Ajouter un élément")
        rule.onNodeWithText("Fait (1)").assertExists()
        rule.onNodeWithText("Ajouté quand le téléphone était verrouillé").assertExists()
        rule.shot("lists_page_fr")
    }

    @Test fun dark() {
        val p = MemPort(seeded())
        rule.show(dark = true) { ListPage(p, shopping, log.actions, SnackbarHostState(), onBack = {}) }
        waitFor("Add an item")
        rule.shot("lists_page_dark")
    }
}
