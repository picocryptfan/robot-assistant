// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.R
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.theme.LocalLangs

/**
 * review-auto-open 5 (auto-open-plan §3): a row per app the routine's or skill's save card ticked to open without asking,
 * on; turning one off unticks it, so a card asks before that app opens. The installed app's name, else its package.
 */
@Composable
fun OpenTickRows(opens: Set<String>, onUntick: (String) -> Unit) {
    if (opens.isEmpty()) return
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    // The main process never reaches the assistant package (ProcessIsolationTest, verify_apk.sh): the label comes from
    // PackageManager here, not :voice's LauncherApps.
    val labels = remember(opens) { opens.associateWith { label(ctx, it) } }
    val detail = stringResource(R.string.actions_open_tick_row_detail)
    for (pkg in opens.sorted()) {
        SwitchPreference(SkillStrings.openTick("name" to (labels[pkg] ?: pkg)).render(langs), detail, true) { on -> if (!on) onUntick(pkg) }
    }
}

private fun label(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString().ifBlank { pkg }
} catch (_: Exception) { pkg }
