// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.heyrobot.core.Person
import app.heyrobot.core.link.FolderItem
import app.heyrobot.core.link.FolderRec
import app.heyrobot.core.link.FolderState
import app.heyrobot.core.link.GroupAnswer
import app.heyrobot.core.link.GroupLimits
import app.heyrobot.core.link.GroupMember
import app.heyrobot.core.link.GroupSeen
import app.heyrobot.core.link.GroupSeenPlan
import app.heyrobot.core.link.GroupStanding
import app.heyrobot.core.link.GroupState
import app.heyrobot.core.link.GroupsView
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.LinkPreset
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.PairingPhase
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.Verified
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.GrpPerson
import app.heyrobot.core.link.requests.GrpWho
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/** L11: the shared folders and group plans pages, with their goldens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TogetherScreensTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    private fun waitFor(text: String) = rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun theLinkSettingsPageOpensBoth() {
        val p = TogetherFixtures.port()
        rule.show { LinkSettingsScreen(p, log.actions, SnackbarHostState()) {} }
        rule.scrollToText("Shared folders")
        rule.onRow("Shared folders").performClick()
        rule.scrollToText("Group plans")
        rule.onRow("Group plans").performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.FOLDERS)}"))
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.GROUPS)}"))
    }

    @Test fun theFoldersPageSaysWhatJoiningMeansAndListsEach() {
        val p = TogetherFixtures.port()
        rule.show { FoldersScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("Trip photos")
        rule.onNodeWithText("up to 500 files and 1 GiB a folder", substring = true).assertExists()
        rule.onNodeWithText("Waiting for Sam Tremblay's answer").assertExists()
        rule.shot("link_folders")
        rule.onRow("Trip photos").performClick()
        assertTrue(log.contains("openPage ${Page.Settings(LinkRoutes.folder(TogetherFixtures.SAM, TogetherFixtures.FOLD))}"))
    }

    /** F71: a file they took out still counts; the row and the folder's screen say the same numbers. */
    @Test fun theRowAndTheFolderCountTheSameFiles() {
        val folder = FolderRec(TogetherFixtures.FOLD, TogetherFixtures.SAM, FolderState.JOINED, "Trip photos", true, 10, null, listOf(
            FolderItem(TogetherFixtures.ITEM, TogetherFixtures.ITEM, true, 1, 1_000_000, "itinerary.pdf", 20),
            FolderItem(TogetherFixtures.FOLD, TogetherFixtures.FOLD, false, 1, 2_000_000, "beach.jpg", 30, gone = true)))
        val p = object : LinkPort by TogetherFixtures.port() { override fun folders() = listOf(folder) }
        val folderPage = androidx.compose.runtime.mutableStateOf(false)
        rule.show { if (folderPage.value) FolderScreen(p, TogetherFixtures.SAM, TogetherFixtures.FOLD, log.actions, SnackbarHostState()) {} else FoldersScreen(p, log.actions, SnackbarHostState()) {} }
        val sentence = "2 of 500 files"
        waitFor(sentence)
        rule.runOnIdle { folderPage.value = true }
        waitFor("itinerary.pdf")
        rule.onNodeWithText(sentence, substring = true).assertExists()
    }

    @Test fun whileLockedThePagesAskForAnUnlockAndReadNothing() {
        var read = 0
        val p = object : LinkPort by TogetherFixtures.port() {
            override fun locked() = true
            override fun folders(): List<FolderRec>? { read++; return null }
        }
        rule.show { FoldersScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("Unlock")
        assertEquals(0, read)
    }

    @Test fun aNewFolderNeedsANameAndOneRobot() {
        val p = TogetherFixtures.port()
        rule.show { FolderNewScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("Folder name")
        rule.scrollToText("Send the invitation")
        rule.onRow("Send the invitation").assertIsNotEnabled()
        rule.shot("link_folder_new")
        rule.onNodeWithText("Folder name").performTextInput("Trip")
        rule.onRow("Sam Tremblay").performClick()
        rule.waitForIdle()
        rule.onRow("Send the invitation").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { p.calls.isNotEmpty() }
        assertEquals(listOf("invite ${TogetherFixtures.SAM} Trip"), p.calls)
    }

    @Test fun aFolderTakesOutOnlyYoursAndLeavingAsksFirst() {
        val p = TogetherFixtures.port()
        rule.show { FolderScreen(p, TogetherFixtures.SAM, TogetherFixtures.FOLD, log.actions, SnackbarHostState()) {} }
        waitFor("beach.jpg")
        rule.onNodeWithText("From Sam Tremblay", substring = true).assertExists()
        rule.shot("link_folder")
        assertEquals("one Take out, for your own file", 1, rule.onAllNodes(hasText("Take out")).fetchSemanticsNodes().size)
        rule.onNodeWithText("Take out").performClick()
        rule.waitUntil(5_000) { p.calls.isNotEmpty() }
        assertEquals("remove ${TogetherFixtures.SAM} ${TogetherFixtures.FOLD} ${TogetherFixtures.ITEM}", p.calls.single())
        rule.scrollToText("Leave this folder")
        rule.onRow("Leave this folder").performClick()
        rule.inDialog("stay in Robot Files", substring = true).assertExists()
        assertEquals(1, p.calls.size)
    }

    @Test fun theGroupsPageFramesWhatTheOrganizerSays() {
        val p = TogetherFixtures.port()
        rule.show { GroupsScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("Dinner")
        rule.scrollToText("Sam Tremblay's Robot says")
        rule.onNodeWithText("Robot can't check this.").assertExists()
        rule.onNodeWithText("Nina: In").assertExists()
        rule.shot("link_groups")
    }

    /** F142: on a list you pick from, a Robot known only by the name it claims says so. */
    @Test fun aPickListSaysWhoIsNotLinked() {
        val p = TogetherFixtures.port()
        p.inner.people = emptyList()
        rule.show { GroupNewScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("What is it?")
        rule.scrollToText("Not linked to anyone in People yet")
    }

    @Test fun aNewGroupStopsAtSevenOthersAndSaysWhoSeesWhat() {
        val p = TogetherFixtures.port(robots = GroupLimits.OTHERS + 1)
        rule.show { GroupNewScreen(p, log.actions, SnackbarHostState()) {} }
        waitFor("What is it?")
        rule.shot("link_group_new")
        rule.onNodeWithText("What is it?").performTextInput("Dinner")
        for (i in 1..GroupLimits.OTHERS) { rule.scrollToText("Robot $i"); rule.onRow("Robot $i").performClick(); rule.waitForIdle() }
        rule.scrollToText("Robot ${GroupLimits.OTHERS + 1}")
        rule.onRow("Robot ${GroupLimits.OTHERS + 1}").assertIsNotEnabled()
        rule.scrollToText("by the name each person chose to show", substring = true)
        rule.scrollToText("Ask their Robots")
        rule.onRow("Ask their Robots").performClick()
        rule.waitUntil(5_000) { p.calls.isNotEmpty() }
        assertEquals("start ${GroupLimits.OTHERS}", p.calls.single())
    }

    @Test fun aGroupProposesATimeOnlyAfterYouConfirm() {
        val p = TogetherFixtures.port()
        rule.show { GroupScreen(p, TogetherFixtures.GROUP, log.actions, SnackbarHostState()) {} }
        waitFor("Times that suit")
        rule.onNodeWithText("Answered").assertExists()
        rule.onNodeWithText("Won't say").assertExists()
        rule.shot("link_group")
        rule.onRow(dateTextOf(TogetherFixtures.TIME)).performClick()
        rule.waitForIdle()
        assertTrue(p.calls.isEmpty())
        rule.inDialog("Propose").performClick()
        rule.waitUntil(5_000) { p.calls.isNotEmpty() }
        assertEquals("propose ${TogetherFixtures.GROUP} ${TogetherFixtures.TIME}", p.calls.single())
    }

    @Test fun aNewGroupsWindowIsOneARobotWillAnswer() {
        val g = newGroup("Dinner", null, 60, 14, listOf(GroupMember(TogetherFixtures.SAM, "Sam")), now = 1_800_000_123)
        assertTrue(g.valid())
        assertTrue(FbAsk.windowOk(g.from, g.to))
        assertTrue(g.from > 1_800_000_123)
        assertFalse(newGroup("Dinner", null, 60, 3, emptyList()).valid())
        // F147: a name not in NFC, or with a space where it was cut, still starts the plan.
        val cut = "A".repeat(app.heyrobot.core.link.requests.RequestCaps.NAME - 1) + " B"
        for (shown in listOf("Ame\u0301lie", cut)) assertTrue(shown, newGroup("Dinner", null, 60, 3, listOf(GroupMember(TogetherFixtures.SAM, memberName(shown)))).valid())
    }

    private fun dateTextOf(s: Long) = whenText(org.robolectric.RuntimeEnvironment.getApplication(), s)
}

internal object TogetherFixtures {
    const val SAM = "0123456789abcdef0123456789abcdef"
    const val FOLD = "1123456789abcdef0123456789abcdef"
    const val ITEM = "2123456789abcdef0123456789abcdef"
    const val GROUP = "3123456789abcdef0123456789abcdef"
    const val TIME = 1_900_000_800L
    val sam = Person(UUID.nameUUIDFromBytes(byteArrayOf(1)).toString(), "Sam Tremblay")

    private fun view(id: String, person: String?) = PairingView(id, PairingPhase.ACTIVE, LinkMode.PRIVATE, 1_790_000_000L, "Sam", person, Verified.NO, 0, null,
        LinkLevels.of(LinkPreset.SOMEONE_ELSE), false, false, 0, "", 0)

    class Port(val inner: LinkScreensTest.FakePort, val calls: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()) : LinkPort by inner {
        override fun folders() = listOf(
            FolderRec(FOLD, SAM, FolderState.JOINED, "Trip photos", true, 10, null, listOf(
                FolderItem(ITEM, ITEM, true, 1, 12_400_000, "itinerary.pdf", 20),
                FolderItem(FOLD, FOLD, false, 1, 3_100_000, "beach.jpg", 30))),
            FolderRec(GROUP, SAM, FolderState.INVITED, "Taxes", true, 40, SAM))
        override fun folderInvite(pairing: String, name: String): String? { calls += "invite $pairing $name"; return FOLD }
        override fun folderRemove(pairing: String, fold: String, item: String): Boolean { calls += "remove $pairing $fold $item"; return true }
        override fun folderLeave(pairing: String, fold: String): Boolean { calls += "leave $pairing $fold"; return true }
        override fun groups() = GroupsView(listOf(
            LinkGroup(GROUP, GroupState.ASKING, "Dinner", "Chez Nina", null, 60, TIME, TIME + 86_400, 50, 0, listOf(
                GroupMember(SAM, "Sam Tremblay", answer = GroupAnswer.KNOWN), GroupMember(ITEM, "Nina", answer = GroupAnswer.WONT_SAY), GroupMember(FOLD, "Lee"))) to
                GroupStanding(listOf(GrpWho.WAITING, GrpWho.WAITING, GrpWho.WAITING), listOf(TIME, TIME + 3_600))),
            listOf(GroupSeenPlan(GroupSeen(FOLD, SAM, 2, ITEM, 60, listOf(GrpPerson("Nina", GrpWho.IN), GrpPerson("Lee", GrpWho.OUT), GrpPerson("Noor", GrpWho.WAITING))), "Picnic", TIME)))
        override fun groupStart(g: LinkGroup): String? { calls += "start ${g.members.size}"; return g.id }
        override fun groupPropose(id: String, time: Long): Boolean { calls += "propose $id $time"; return true }
        override fun groupCancel(id: String): Boolean { calls += "cancel $id"; return true }
    }

    fun port(robots: Int = 1): Port {
        val f = LinkScreensTest.FakePort(LinkSettings(on = true, name = "Matt")).apply { ready = true }
        val named = List(robots) { i -> Person(UUID.nameUUIDFromBytes(byteArrayOf(i.toByte(), 9)).toString(), if (robots == 1) sam.name else "Robot ${i + 1}") }
        f.people = named
        f.views = named.mapIndexed { i, p -> view(if (i == 0) SAM else LinkIds.newId(), p.id) }
        return Port(f)
    }
}
