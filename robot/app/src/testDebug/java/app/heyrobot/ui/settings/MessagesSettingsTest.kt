package app.heyrobot.ui.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.messages.AfterReading
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRoute
import app.heyrobot.sys.MessagesStore
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Settings › Messages (0.7, design §B11): everything off until you turn it on; turning it on says what Robot will do,
 * pre-ticks only your texting app and asks you to confirm; then the notification access steps; each app, group chats,
 * how replies go, the card, a spoken yes, reading, the lock screen; "Announce" waits for headset mode. It writes
 * messages.json, never settings.json.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MessagesSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val log = ActionLog()
    private val saved = ArrayList<MessagesFile>()
    private val cheogramOnly: (MessageApp) -> Boolean = { it == MessageApp.CHEOGRAM }

    @Before fun fresh() {
        MessagesStore.file(ctx).delete()
        MessagesStore.reload(ctx)
    }

    /** What "Android" says of Cheogram now: its F-Droid build, with its signer. */
    private val cheogramPin = AppPin(setOf("c".repeat(64)), "org.fdroid.fdroid")
    private val pinsNow: (MessageApp) -> Map<String, AppPin> = { if (it == MessageApp.CHEOGRAM) mapOf("com.cheogram.android" to cheogramPin) else emptyMap() }

    private fun open(start: MessagesFile = MessagesFile(), access: Boolean = false, installed: (MessageApp) -> Boolean = cheogramOnly,
        problems: (MessagesFile) -> List<String> = { emptyList() }) {
        rule.show {
            var f by remember { mutableStateOf(start) }
            MessagesSettingsScreen(f, facts().copy(notificationAccess = access), onEdit = { e -> f = e(f); saved += f }, log.actions, onBack = {},
                installed = installed, pinsNow = pinsNow, problems = problems)
        }
    }

    private val on = MessagesFile(MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM))))

    @Test fun everythingIsOffAndTurningItOnAsksFirst() {
        open()
        rule.onNodeWithText("Read my messages").assertIsOff()
        rule.scrollToText("Cheogram")
        rule.onRow("Cheogram").assertIsOff().assertIsNotEnabled().assert(hasText("Installed · Mark as read tells the sender"))
        rule.scrollToText("Signal")
        rule.onRow("Signal").assert(hasText("Not installed · a reply marks the chat read"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // On: one screen first, your texting app ticked, then nothing changes until "Turn on".
        rule.onNode(androidx.compose.ui.test.hasText("Read my messages") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.inDialog("Robot will read the notifications of the apps you tick here", substring = true).assertExists()
        rule.inDialog("Cheogram").assertIsOn()
        assertTrue(saved.isEmpty())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog("Turn on").performClick()
        // Cheogram pinned to the key it is signed with now.
        assertEquals(MessagesFile(MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM))),
            pins = mapOf("com.cheogram.android" to cheogramPin)), saved.single())
    }

    /** 0.7 after the review: an app whose signing key changed is named, with what to do; turning it off and on pins it again. */
    @Test fun anAppWhoseKeyChangedIsNamed() {
        val line = "Cheogram changed its signing key: turn it on again to trust it"
        open(on, access = true, problems = { f -> if (f.pins.isEmpty() && MessageApp.CHEOGRAM in f.settings.allowlist.apps) listOf(line) else emptyList() })
        rule.onNodeWithText(line).assertExists()
        rule.scrollToText("Cheogram", substring = false)
        rule.onRow("Cheogram").performClick()
        assertTrue(saved.last().pins.isEmpty())
        rule.onRow("Cheogram").performClick()
        assertEquals(mapOf("com.cheogram.android" to cheogramPin), saved.last().pins)
        rule.onNodeWithText(line).assertDoesNotExist()
    }

    @Test fun cancellingTheSetupChangesNothing() {
        open()
        rule.onNode(hasText("Read my messages") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        rule.inDialog("Cancel").performClick()
        assertTrue(saved.isEmpty())
        rule.onNodeWithText("Read my messages").assertIsOff()
    }

    @Test fun accessIsTheThreeStepsPeopleHas() {
        open(on)
        rule.onRow("Notification access").assert(hasText("Needed: three steps")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)}", log.last())
    }

    @Test fun eachChoiceIsWrittenAsItIs() {
        open(on, access = true)
        rule.onNodeWithText("Notification access allowed").assertExists()
        rule.scrollToText("Signal")
        rule.onRow("Signal").assertIsEnabled().performClick()
        assertEquals(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL), saved.last().settings.allowlist.apps)
        rule.scrollToText("Include group chats")
        rule.onRow("Include group chats").assertIsOn().performClick()
        assertFalse(saved.last().settings.allowlist.groups)
        fun pick(row: String, choice: String) {
            rule.scrollToText(row)
            rule.onRow(row).performClick()
            assertEquals(row, emptyList<String>(), rule.touchTargetProblems())
            rule.inDialog(choice).performClick()
        }
        pick("How replies go", "Open the chat with my reply; I press Send")
        assertEquals(ReplyRoute.OPEN_CHAT, saved.last().settings.replyRoute)
        // With "Open the chat", the card and a spoken yes have nothing to do.
        rule.scrollToText("Card before sending a reply")
        rule.onRow("Card before sending a reply").assertIsNotEnabled()
        pick("How replies go", "Robot sends after you confirm")
        rule.scrollToText("Answer reply cards by voice")
        rule.onRow("Answer reply cards by voice").assertIsOff().performClick()
        assertTrue(saved.last().settings.voiceYesSends)
        pick("After Robot reads a message", "Mark as read")
        assertEquals(AfterReading.MARK_READ, saved.last().settings.afterReading)
        pick("Messages on the lock screen", "Who messaged")
        assertEquals(MessagesLocked.WHO_MESSAGED, saved.last().settings.locked)
        // 0.8: Announce new messages, off by default; on, it offers the headset's and the car's pages.
        rule.scrollToText("Announce new messages")
        rule.onRow("Announce new messages").assertIsOff().performClick()
        assertTrue(saved.last().settings.announce)
        rule.scrollToText("A sent message can't be recalled", substring = true)
    }

    @Test fun theSettingsRowSaysWhatIsOn() {
        assertEquals("Off", messagesSummary(MessagesFile()))
        assertEquals("On · Cheogram", messagesSummary(on))
        assertEquals("On · Cheogram, Signal", messagesSummary(on.copy(settings = on.settings.copy(allowlist = MessageAllowlist(setOf(MessageApp.SIGNAL, MessageApp.CHEOGRAM))))))
        assertEquals("On · no app yet", messagesSummary(MessagesFile(MessageSettings(on = true))))
    }

    @Test fun thePageWritesMessagesJsonNotSettingsJson() {
        rule.setContent {
            RobotTheme(dark = false) {
                SettingsNav(SettingsRoutes.MESSAGES, RobotSettings(), Snapshot(facts = facts()), emptyList(), {}, log.actions, SnackbarHostState(), onBack = {})
            }
        }
        rule.onNode(hasText("Read my messages") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        rule.inDialog("Turn on").performClick()
        MessagesStore.awaitWrites()
        assertTrue(MessagesFile.fromJson(MessagesStore.file(ctx).readText()).settings.on)
        val settings = File(MessagesStore.file(ctx).parentFile, "settings.json")
        assertFalse(settings.exists() && settings.readText().let { it.contains("msgOn") || it.contains("\"apps\"") })
    }

    @Test fun light() {
        open(on, access = true)
        rule.shot("messages_light")
    }

    @Test fun setupDialog() {
        open()
        rule.onNode(hasText("Read my messages") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        rule.shot("messages_setup")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        open(on, access = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("messages_font200")
    }
}
