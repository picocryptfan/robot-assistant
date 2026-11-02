// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The everyday set, E6: Settings › Voice's row for Robot's voices for other apps, shown now that they work. The switch
 * writes its own key; the row below names the voices, Siwis as French from France, and opens Android's own page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class VoicesForAppsRowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() {
        EverydaySettingsStore.file(ctx).delete()
        EverydaySettingsStore.forget()
    }

    private fun page() = rule.show {
        val e = rememberEveryday()
        PreferenceScreen("Voice", onBack = null) { everydayVoiceRows(e) } // l10n: not text (a test page's title)
    }

    @Test fun theRowNamesTheVoicesAndItsSwitchWritesItsKey() {
        page()
        rule.onNodeWithText("Heart and Kitten speak English (US). Siwis speaks French, from France.").assertExists()
        rule.onNodeWithText("Android's text-to-speech settings").assertExists()
        rule.onRow("Robot's voices for other apps").assertIsOn().performClick()
        rule.waitUntil(5_000) { !EverydaySettingsStore.current(ctx).voices }
        rule.waitUntil(5_000) { runCatching { rule.onRow("Robot's voices for other apps").assertIsOff() }.isSuccess }
        assertEquals(EverydaySettings(voices = false), EverydaySettingsStore.current(ctx))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    fun inFrench() {
        page()
        rule.onNodeWithText("Voix de Robot pour les autres applis").assertExists()
        rule.onNodeWithText("Heart et Kitten parlent anglais (États-Unis). Siwis parle français, de France.").assertExists()
        rule.onNodeWithText("Paramètres de synthèse vocale d’Android").assertExists()
    }
}
