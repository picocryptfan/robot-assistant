// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.ReplyLangChoice
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Menus
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import kotlinx.coroutines.launch

// MAIN PROCESS. Settings › Language (1.1): the menus' language (Android's per-app language: Android
// keeps it, Settings › Apps › Robot › Language shows the same, and choosing recreates the screen in it), the language
// Robot answers in (settings.json "replyLang"; core ReplyLang decides each turn, as each area moves onto it), and
// Android's own language page for Robot. Every word is in values/strings_language.xml, with its French.

/** The menus' language as the Settings row and the list say it. */
@Composable
fun menusLabel(m: Menus): String = stringResource(
    when (m) {
        Menus.PHONE -> R.string.lang_menus_phone
        Menus.EN -> R.string.lang_name_en
        Menus.FR -> R.string.lang_name_fr_ca
    },
)

@Composable
private fun replyLabel(c: ReplyLangChoice): String = stringResource(
    when (c) {
        ReplyLangChoice.SPOKEN -> R.string.lang_reply_spoken
        ReplyLangChoice.MENUS -> R.string.lang_reply_menus
        ReplyLangChoice.EN -> R.string.lang_name_en
        ReplyLangChoice.FR -> R.string.lang_name_fr
    },
)

/** Settings' row summary: the menus' language. */
@Composable
fun languageSummary(): String = menusLabel(AppLanguage.menus(LocalContext.current))

/**
 * Settings › Language. [menus] and [onMenus] are Android's per-app language ([AppLanguage]); [openAndroid] opens Android's
 * page for Robot and says whether something did. A test passes its own.
 */
@Composable
fun LanguageSettingsScreen(
    s: RobotSettings,
    update: (RobotSettings) -> Unit,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    menus: Menus = AppLanguage.menus(LocalContext.current),
    onMenus: (Menus) -> Unit = LocalContext.current.let { ctx -> { m: Menus -> AppLanguage.setMenus(ctx, m) } },
    openAndroid: () -> Boolean = LocalContext.current.let { ctx -> { AppLanguage.openAndroidPage(ctx) } },
) {
    var shown by remember(menus) { mutableStateOf(menus) }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.lang_android_failed)
    val footer = stringResource(R.string.lang_footer)
    val androidPath = AndroidPath.APP_LANGUAGE.render(LocalLangs.current.system)
    val menuLabels = Menus.entries.associateWith { menusLabel(it) }
    val replyLabels = ReplyLangChoice.entries.associateWith { replyLabel(it) }
    PreferenceScreen(stringResource(R.string.lang_title), onBack, snackbar = snackbar) {
        preferenceGroup(null, "choices") {
            ListPreference(stringResource(R.string.lang_menus), shown, Menus.entries, label = { menuLabels.getValue(it) }, icon = RIcons.Message) {
                shown = it
                onMenus(it)
            }
            ListPreference(stringResource(R.string.lang_reply), s.replyLang, ReplyLangChoice.entries, label = { replyLabels.getValue(it) },
                icon = RIcons.RecordVoiceOver) { update(s.copy(replyLang = it)) }
        }
        preferenceGroup(null, "android") {
            PreferenceItem(stringResource(R.string.lang_android), androidPath, RIcons.Settings, onClick = {
                if (!openAndroid()) scope.launch { snackbar.showSnackbar(failed) }
            })
        }
        preferenceFooter(footer)
    }
}
