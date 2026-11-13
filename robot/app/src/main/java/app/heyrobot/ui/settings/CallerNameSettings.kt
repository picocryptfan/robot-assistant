// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.R
import app.heyrobot.calls.CallerNameStore
import app.heyrobot.core.calls.CallerNameSettings
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.SwitchPreference

// Settings › People, notes and calendar › Calls (docs/plans/caller-name-plan.md): Caller name, off by default. Turning it
// on asks Android for the call screening role; its line says first what it does and that it takes the place of an app
// that screens calls for spam, and that turning it off doesn't give that place back. Saying no in Android's dialog leaves
// the other app as it was and the switch off. An app can't hand a role back: turning it off says the role is still
// Robot's and opens Android's Default apps page, and a line stays until another app has it (review-caller-name, High 2).

@Composable
fun CallerNamePreference() {
    if (!CallerNameSettings.AVAILABLE) return // hidden in 1.4: no switch, no role request
    val ctx = LocalContext.current
    val roles = remember { ctx.getSystemService(RoleManager::class.java) }
    fun held(): Boolean = try { roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true } catch (_: Exception) { false }
    var holds by remember { mutableStateOf(held()) }
    var on by remember { mutableStateOf(CallerNameStore.current(ctx).on && holds) }
    // Back from Default apps (or anywhere): read the role again, and a role given to another app turns the switch off.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                holds = held()
                // The file too, when the role left before this page opened (on was already false; review-caller-name-c, Low 4).
                if (CallerNameStore.current(ctx).on && !holds) { CallerNameStore.save(ctx, CallerNameSettings(on = false)); on = false }
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    // Android no longer shows its dialog after you've said no twice: then say why, and offer Default apps (Low 6).
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ok = held()
        holds = ok
        CallerNameStore.save(ctx, CallerNameSettings(on = ok))
        on = ok
        refused = !ok
    }
    SwitchPreference(stringResource(R.string.people_caller_name), stringResource(R.string.people_caller_name_detail), on) { want ->
        when {
            !want -> {
                CallerNameStore.save(ctx, CallerNameSettings(on = false))
                on = false
                holds = held()
                if (holds) { app.heyrobot.ui.common.RobotToast.show(ctx, ctx.getString(R.string.people_caller_name_still), long = true); defaultApps(ctx) }
            }
            held() -> { CallerNameStore.save(ctx, CallerNameSettings(on = true)); on = true; refused = false }
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                try { ask.launch(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) } catch (_: Exception) { on = false; refused = true }
            else -> refused = true
        }
    }
    val still = !on && holds
    if (still || refused) {
        val line = stringResource(if (still) R.string.people_caller_name_still else R.string.people_caller_name_refused)
        PreferenceItem(title = line, onClick = { defaultApps(ctx) })
    }
}

/** Android's Default apps page, where you pick the app that screens calls; false when this phone has none. */
private fun defaultApps(ctx: Context): Boolean = try {
    ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch (_: Exception) { false }
