// SPDX-License-Identifier: GPL-3.0-only
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    init { app.heyrobot.sys.PeopleFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }

    // seal-people: over the lock screen People can't be opened, so "no contact matches" says it opens once you unlock.
    @Test fun noContactSaysPeopleOpensOnceYouUnlockWhileLocked() {
        val kg = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.KeyguardManager::class.java))
        assertEquals("No contact matches “aunt”.", PeopleFiles.noContact(ctx, "aunt").en())
        kg.setIsDeviceLocked(true)
        try {
            assertEquals("No contact matches “aunt”, and People opens once you unlock.", PeopleFiles.noContact(ctx, "aunt").en())
        } finally { kg.setIsDeviceLocked(false) }
    }
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
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Not confirmed yet (1)") }.isSuccess }
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

    // fix-seal-14-f 1: copies kept aside get one card, the count and where they are, never a name; none, no card.
    // Its only wait is open()'s waitUntil (5 s), bounded; no Timeout rule, since Robolectric runs the test on its main looper.
    @Test fun copiesKeptAsideHaveOneCardWithoutNames() {
        val bad = java.io.File(PeopleFiles.dir(ctx), "people.enc.bad").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            open()
            rule.scrollToText("1 copy of People is kept aside")
            rule.onNodeWithText("so it keeps it whole, on this phone only", substring = true).assertExists()
            rule.onNodeWithText("Delete all People, in the menu, removes it", substring = true).assertExists()
        } finally { bad.delete() }
    }

    @Test fun noCopyKeptAsideNoCard() {
        open()
        assertEquals(0, rule.onAllNodes(hasText("kept aside", substring = true)).fetchSemanticsNodes().size)
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

    /** 1.1: French menus (values-fr-rCA/strings_people.xml): the list and the notification access steps in Québec French. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        val host = SettingsHost(RobotSettings())
        rule.showSettings(host) { s, u -> PeopleScreen(s, u, null, log.actions, SnackbarHostState(), onOpen = { opened += it }, onBack = {}) }
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Pas encore confirmés (1)") }.isSuccess }
        rule.scrollToText("Ajouter une personne")
        rule.onNodeWithText("Ajouter depuis les contacts du téléphone").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun notificationAccessInFrench() {
        rule.show { NotificationAccessScreen(null, onBack = {}) }
        rule.onNodeWithText("paramètre restreint pour les applis installées", substring = true).assertExists()
        rule.onNodeWithText("Infos sur l’appli").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }
}
