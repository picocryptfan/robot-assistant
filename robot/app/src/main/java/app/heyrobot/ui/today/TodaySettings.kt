// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.LockSwitch
import app.heyrobot.core.TodayAlarm
import app.heyrobot.core.TodayGlance
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.settings.BriefCardDialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Main process. Today outside the app (home-design H9), in Settings › Display beside Today as your home page: the lock
// screen summary (HomeSettings.lockSummary, off until you turn it on) and the day read after your morning alarm (the brief,
// after the alarm: TodayAlarm). Turning the brief on shows its card first when you haven't seen it, as Settings › Routines does.

@Composable
fun TodayOutsideSettings() {
    val ctx = LocalContext.current
    var home by remember { mutableStateOf(HomeSettingsStore.current(ctx)) }
    val saves = androidx.compose.runtime.rememberCoroutineScope()
    // today-fix-c 3: on asks for Robot's notifications when they're off; refused, the switch stays off and says why.
    var refused by remember { mutableStateOf(false) }
    fun lock(on: Boolean) {
        refused = false
        home = home.copy(lockSummary = on)
        TodaySave.save(saves, ctx, { it.copy(lockSummary = on) }) { r -> r?.let { home = it }; TodayGlances.changed(ctx) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && TodayGlances.canNotify(ctx)) lock(true) else refused = true
    }
    val lockSummary = when {
        refused || (home.lockSummary && !TodayGlances.canNotify(ctx)) -> R.string.today_lock_setting_refused
        home.lockSummary -> R.string.today_lock_setting_on
        else -> R.string.today_lock_setting_off
    }
    SwitchPreference(stringResource(R.string.today_lock_setting), stringResource(lockSummary), home.lockSummary) { on ->
        when (TodayGlance.lockSwitch(on, TodayGlances.canNotify(ctx), Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS))) {
            LockSwitch.SET -> lock(on)
            LockSwitch.ASK -> ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            LockSwitch.REFUSED -> refused = true
        }
    }
    val store = remember { RoutineFiles.store(ctx) }
    var rs by remember { mutableStateOf(store.settings()) }
    var card by remember { mutableStateOf(false) }
    fun save(on: Boolean, consent: Boolean = false) {
        rs = store.editSettings { TodayAlarm.set(it, on).let { s -> if (consent) s.copy(briefOnline = true) else s } } ?: rs
        RoutineFiles.changed(ctx)
    }
    val on = TodayAlarm.on(rs)
    val clock = remember { RoutineFacts.clockInstalled(ctx) }
    val f = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    fun t(x: LocalTime) = x.format(f)
    val summary = when {
        on && !clock -> stringResource(R.string.today_alarm_no_clock)
        on -> stringResource(R.string.today_alarm_setting_on, t(rs.alarmWindow.from), t(rs.alarmWindow.to))
        else -> stringResource(R.string.today_alarm_setting_off)
    }
    SwitchPreference(stringResource(R.string.today_alarm_setting), summary, on) { want ->
        if (want && !rs.briefOnline) card = true else save(want)
    }
    if (card) BriefCardDialog(TodayAlarm.set(rs, true), onDismiss = { card = false }) {
        card = false
        save(true, consent = true)
    }
}
