// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.heyrobot.core.RLog

// MAIN PROCESS. Ask Robot's door (docs/plans/everyday-set-plan.md §7.6, E7): "Ask Robot in other apps" off disables the
// :voice activity, so the text-selection menu's item and the share sheet's entry are gone; on puts it back. It follows
// everyday_settings.json wherever that is written (EverydaySettingsStore: Settings › Actions and a restore's finish). The
// activity is named as a string: the main process never refers to the assistant's classes. Unlike the recognizer, this
// component can be switched off safely (it isn't what keeps Robot the assistant).

object AskRobotDoor {
    const val ACTIVITY = "app.heyrobot.assistant.ask.AskRobotActivity"  // l10n: not text (a class name)

    fun apply(ctx: Context, on: Boolean) {
        try {
            val c = ComponentName(ctx.packageName, ACTIVITY)
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (ctx.packageManager.getComponentEnabledSetting(c) != want) ctx.packageManager.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        } catch (t: Throwable) {
            RLog.w("ask_door", t)
        }
    }
}
