// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReplyLangChoice
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.Menus
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Language (1.1): the menus' language goes to Android, the answers' to settings.json,
 * Android's page opens (or the page says it didn't), and in French menus the page is French: values-fr-rCA and core's
 * catalog through LocalLangs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LanguageSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ArrayList<String>()

    private fun open(host: SettingsHost = SettingsHost(), menus: Menus = Menus.PHONE, opens: Boolean = true, snackbar: SnackbarHostState = SnackbarHostState()) =
        rule.showSettings(host) { s, u ->
            LanguageSettingsScreen(s, u, snackbar, onBack = {}, menus = menus, onMenus = { log += "menus $it" }, openAndroid = { log += "android"; opens })
        }

    @Test fun theRowsSayWhatIsSetNow() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Menus").assert(hasStateDescription("Phone's language"))
        rule.onRow("Robot answers in").assert(hasStateDescription("The language you speak"))
        rule.onRow("Android's language page for Robot").assert(hasText("Settings › Apps › Robot › Language"))
        rule.scrollToText("Robot understands English and French whatever you choose here. Words the model reads stay in English.")
    }

    @Test fun theMenusGoToAndroid() {
        open(menus = Menus.EN)
        rule.onRow("Menus").assert(hasStateDescription("English")).performClick()
        rule.inDialog("Phone's language").assertExists()
        rule.inDialog("Français (Canada)").performClick()
        assertEquals(listOf("menus ${Menus.FR}"), log)
        rule.onRow("Menus").assert(hasStateDescription("Français (Canada)"))
    }

    @Test fun theAnswersGoToSettings() {
        val host = SettingsHost()
        open(host)
        rule.onRow("Robot answers in").performClick()
        for (t in listOf("The language you speak", "Same as the menus", "English")) rule.inDialog(t).assertExists()
        rule.inDialog("Français").performClick()
        assertEquals(RobotSettings(replyLang = ReplyLangChoice.FR), host.last)
        rule.onRow("Robot answers in").assert(hasStateDescription("Français"))
    }

    @Test fun androidsPageOpens() {
        open()
        rule.onRow("Android's language page for Robot").performClick()
        assertEquals(listOf("android"), log)
    }

    @Test fun androidsPageThatDoesntOpenSaysSo() {
        open(opens = false)
        rule.onRow("Android's language page for Robot").performClick()
        rule.onNodeWithText("Android's language page didn't open.").assertExists()
    }

    /** French menus: Android picks values-fr-rCA. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        rule.show { LanguageSettingsScreen(RobotSettings(), {}, SnackbarHostState(), onBack = {}, menus = Menus.FR, onMenus = {}, openAndroid = { true }) }
        rule.onAllNodesWithText("Langue").onFirst().assertExists()
        rule.onRow("Robot répond en").assert(hasStateDescription("La langue que tu parles"))
        rule.onRow("Menus").assert(hasStateDescription("Français (Canada)"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** French menus: RobotTheme's LocalLangs renders core's words in French. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun coreWordsInFrench() {
        var ui: Lang? = null
        rule.show {
            ui = LocalLangs.current.ui
            Text(Phrase.M(UiStrings.settings).str())
        }
        assertEquals(Lang.FR, ui)
        rule.onNodeWithText("Réglages").assertExists()
    }
}
