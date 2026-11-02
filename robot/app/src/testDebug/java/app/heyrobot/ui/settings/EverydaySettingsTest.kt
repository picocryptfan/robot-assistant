// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The everyday set's Settings rows (docs/plans/everyday-set-plan.md §2.4), laid by E0: hidden until each job turns its own
 * on; once shown, bound to everyday_settings.json (a switch writes only its own key), in English and Québec French.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class EverydaySettingsTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() {
        EverydaySettingsStore.file(ctx).delete()
        EverydaySettingsStore.forget()
    }

    private fun page(rows: EverydayRows) = rule.show {
        val e = rememberEveryday(rows)
        PreferenceScreen("Everyday", onBack = null) { // l10n: not text (a test page's title)
            everydayActionRows(e)
            everydayVoiceRows(e)
            everydaySafetyRows(e)
        }
    }

    /**
     * E0 builds every row hidden: no build offers a switch that changes nothing yet. Each job turns its own row on once its
     * feature works, deliberately here: E1's Settings › Actions › Music (the keys, play by name through door 1, what's
     * playing), E3's What went online (its row opens the page, where its switch now is: OnlineScreenTest) E5's listening for
     * other apps (Settings › Voice) E6's voices for other apps (tts/RobotTtsService) and E7's Ask Robot (assistant/ask).
     */
    @Test fun everyRowIsHiddenUntilItsJobTurnsItOn() {
        assertEquals(EverydayRows(), EverydayRows(music = true, online = true, listen = true, voices = true, ask = true))
        page(EverydayRows())
        rule.onNodeWithText("Music by voice").assertExists()
        for (t in listOf(
            "Keep a record of what went online")) rule.onNodeWithText(t).assertDoesNotExist()
        rule.onNodeWithText("Robot's voices for other apps").assertExists()
        rule.onNodeWithText("Listening for other apps").assertExists()
        rule.onNodeWithText("Ask Robot in other apps").assertExists()
        rule.scrollToText("See what went online")
    }

    /** E1: Your music app's row lists the music apps Robot can ask, and None forgets the one you picked. */
    @Test fun theMusicAppRowPicksAndForgets() {
        val app = MusicApp("org.example.music", "d".repeat(64), "Music Player")
        EverydaySettingsStore.update(ctx) { it.copy(musicApp = app) }
        page(EverydayRows(music = true))
        rule.onRow("Your music app").performClick()
        // The list is read off the main thread.
        rule.waitUntil(5_000) { rule.onAllNodesWithText("None").fetchSemanticsNodes().isNotEmpty() }
        // No music app with a media service on this test phone: only None, and why.
        rule.onNodeWithText("No music app on this phone takes requests from Robot.").assertExists()
        rule.inDialog("None").performClick()
        rule.waitUntil(5_000) { EverydaySettingsStore.current(ctx).musicApp == null }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("None yet. Robot asks the first time you play something by name.").assertExists() }.isSuccess }
        assertEquals(EverydaySettings(), EverydaySettingsStore.current(ctx))
    }

    /** Settings › Actions › Podcasts: the same kind of row and pin as your music app, its own key. */
    @Test fun thePodcastAppRowIsItsOwnPin() {
        val music = MusicApp("org.example.music", "d".repeat(64), "Music Player")
        val pods = MusicApp("org.example.pods", "e".repeat(64), "Pod Player")
        EverydaySettingsStore.update(ctx) { it.copy(musicApp = music, podcastApp = pods) }
        page(EverydayRows(music = true))
        rule.onRow("Your podcast app").assertExists()
        rule.onNodeWithText("Pod Player").assertExists()
        rule.onNodeWithText("Music Player").assertExists()
    }

    /**
     * E7: Ask Robot's switch writes its own key, and its door follows it (sys AskRobotDoor): off, the text menu's item and the
     * share entry are gone (the component disabled); on, back.
     */
    @Test fun askRobotsSwitchDisablesItsDoor() {
        val door = android.content.ComponentName(ctx, app.heyrobot.sys.AskRobotDoor.ACTIVITY)
        val pm = ctx.packageManager
        page(EverydayRows())
        rule.onRow("Ask Robot in other apps").assertIsOn().performClick()
        rule.waitUntil(5_000) { !EverydaySettingsStore.current(ctx).askRobot }
        // The door follows right after the write, on the same worker.
        rule.waitUntil(5_000) { pm.getComponentEnabledSetting(door) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED }
        assertEquals(EverydaySettings(askRobot = false), EverydaySettingsStore.current(ctx))
        rule.waitForIdle()
        rule.onRow("Ask Robot in other apps").assertIsOff().performClick()
        rule.waitUntil(5_000) { pm.getComponentEnabledSetting(door) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT }
        assertEquals(true, EverydaySettingsStore.current(ctx).askRobot)
        rule.onNodeWithText("In the text-selection menu and the share sheet. Robot reads what you hand it and never writes back.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun eachSwitchWritesItsOwnKey() {
        val app = MusicApp("org.example.music", "d".repeat(64), "Music Player")
        EverydaySettingsStore.update(ctx) { it.copy(musicApp = app) }
        page(EverydayRows.ALL)
        rule.onNodeWithText("Music Player").assertExists()
        rule.onRow("Music by voice").assertIsOn().performClick()
        rule.waitUntil(5_000) { !EverydaySettingsStore.current(ctx).music }
        rule.waitUntil(5_000) { runCatching { rule.onRow("Music by voice").assertIsOff() }.isSuccess }
        // E3, deliberately: What went online's row opens its page; the switch is there (OnlineScreenTest).
        rule.scrollToText("See what went online")
        rule.onNodeWithText("Every connection Robot makes, kept 30 days on this phone. Robot never sends it.").assertExists()
        assertEquals(EverydaySettings(music = false, musicApp = app), EverydaySettingsStore.current(ctx))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun inFrench() {
        page(EverydayRows.ALL)
        rule.onNodeWithText("Musique à la voix").assertExists()
        rule.onNodeWithText("Demander à Robot dans les autres applis").assertExists()
        rule.onNodeWithText("Aucune pour l’instant. Robot te la demande la première fois que tu fais jouer quelque chose par son nom.").assertExists()
        rule.scrollToText("Voir ce qui est allé en ligne")
        rule.onNodeWithText("Chaque connexion de Robot, notée et gardée 30 jours sur ce téléphone. Robot n’envoie jamais cette trace.").assertExists()
        assertFalse(EverydaySettingsStore.file(ctx).isFile)
    }
}
