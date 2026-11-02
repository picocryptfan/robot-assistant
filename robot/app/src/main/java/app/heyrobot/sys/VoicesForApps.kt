// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.heyrobot.core.RLog

// Main process. The everyday set, E6 (docs/plans/everyday-set-plan.md §6.3, §2.4): Settings › Voice › "Robot's voices for
// other apps" off takes Robot out of Android's list of text-to-speech engines: the engine service and its three activities
// are disabled, so Android falls back to another engine, as it does for one that was uninstalled, and the screen reader
// never waits on an engine that would refuse it. On (the default) puts them back as the manifest has them. Applied after
// every write of everyday_settings.json (EverydaySettingsStore, a restore's too) and when Settings › Voice opens; the
// service checks the switch itself too, for the moment between the two. By class name: the main app never loads them.

object VoicesForApps {
    /** The engine's components (AndroidManifest.xml; ProcessIsolationTest checks the two lists agree). */
    val COMPONENTS = listOf(
        "app.heyrobot.tts.RobotTtsService", // l10n: not text (a class name)
        "app.heyrobot.ui.tts.TtsCheckDataActivity", // l10n: not text (a class name)
        "app.heyrobot.ui.tts.TtsSampleTextActivity", // l10n: not text (a class name)
        "app.heyrobot.ui.tts.TtsInstallDataActivity", // l10n: not text (a class name)
    )

    /** [on]: a disabled one back to the manifest's state (enabled); off: disabled. Robot's processes keep running. */
    fun sync(ctx: Context, on: Boolean) {
        val pm = ctx.packageManager
        for (n in COMPONENTS) {
            val c = ComponentName(ctx.packageName, n)
            try {
                val now = pm.getComponentEnabledSetting(c)
                val want = when {
                    on && disabled(now) -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    !on && now != PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    else -> continue
                }
                pm.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
            } catch (t: Throwable) {
                RLog.w("tts_components", t)
            }
        }
    }

    fun disabled(state: Int): Boolean = state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
}
