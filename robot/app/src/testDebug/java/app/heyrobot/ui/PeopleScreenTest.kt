package app.heyrobot.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PersonSource
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.RobotSettings
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › People as a contacts-style list: the ways to add someone first, import, export and Delete all in the
 * overflow, the two switches, learned names with Keep and Remove named after the person, and the list with letter avatars.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PeopleScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val log = ActionLog()
    private val opened = ArrayList<String?>()

    @Before fun twoPeople() {
        val store = PeopleFiles.store(ctx)
        store.deleteAll()
        store.update {
            listOf(
                Person("ana", "Ana Lima", phones = listOf(ContactPhone("+15145550101", PhoneKind.MOBILE, "mobile")), source = PersonSource.NOTIFICATION, confirmed = false),
                Person("bob", "Bob Tremblay", aliases = listOf("dad"), emails = listOf("bob@example.org")),
            )
        }
    }

    private fun open(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> PeopleScreen(s, u, null, log.actions, SnackbarHostState(), onOpen = { opened += it }, onBack = {}) }
        // The list is read off the main thread; the learned group, below the switches, shows once it is.
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Learned from notifications (1)") }.isSuccess }
        return host
    }

    @Test fun everyTargetIsLabelled() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Add person")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Bob Tremblay")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Bob Tremblay").assert(hasText("Also called dad", substring = true)).performClick()
        assertEquals(listOf<String?>("bob"), opened)
    }

    @Test fun theSwitchesWriteTheirSettings() {
        val host = open()
        rule.scrollToText("Remember who you pick")
        rule.onNodeWithText("Remember who you pick").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertEquals(RobotSettings(rememberPicks = false), host.last)
        rule.onNodeWithText("Learn names from message notifications").assertIsOff().performClick()
        assertEquals(RobotSettings(rememberPicks = false, learnFromCheogram = true), host.last)
        // On without access: the row to the three steps.
        rule.onNodeWithText("Notification access").assert(hasText("Needed: three steps")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)}", log.last())
    }

    @Test fun keepAndRemoveAreNamedAfterThePerson() {
        open()
        rule.onNodeWithContentDescription("Remove Ana Lima").assertExists()
        rule.onNodeWithContentDescription("Keep Ana Lima").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Keep Ana Lima").fetchSemanticsNodes().isEmpty() }
        assertTrue(PeopleFiles.store(ctx).list().all { it.confirmed })
    }

    @Test fun importExportAndDeleteAllAreInTheOverflow() {
        open()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Import contacts file (.vcf)").assertExists()
        rule.onNodeWithText("Export (.vcf)").assertExists()
        rule.onNodeWithText("Delete all People").performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Delete all People?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(2, PeopleFiles.store(ctx).list().size)
    }

    @Test fun theNotificationAccessPageHasTheSteps() {
        rule.show { NotificationAccessScreen(null, onBack = {}) }
        rule.onNodeWithText("restricted setting for apps installed from a file", substring = true).assertExists()
        rule.onNodeWithText("App info").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun light() {
        open()
        rule.scrollToText("Add person")
        rule.shot("people_light")
    }
}
