// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.AppLanguage

// Main and voice processes. The languages core's words render in on Robot's screens (1.1):
// RobotTheme provides them from the configuration (the app language, which Android's per-app language sets) and the
// phone's languages, so a screen shows a core Phrase with `phrase.str()`.

/** Robot's screens' languages; English everywhere until RobotTheme provides them. */
val LocalLangs = staticCompositionLocalOf { Langs.EN }

/** The languages of this configuration (they change when the menus' language does: Android recreates the activity). */
@Composable
fun rememberLangs(): Langs {
    val config = LocalConfiguration.current
    val ctx = LocalContext.current
    return remember(config) { AppLanguage.langs(ctx, config) }
}

/** This sentence in the screen's languages. */
@Composable
@ReadOnlyComposable
fun Phrase.str(): String = render(LocalLangs.current)
