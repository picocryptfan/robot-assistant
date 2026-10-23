// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.lock.AppMode
import app.heyrobot.core.lock.AppStart
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockGate
import app.heyrobot.core.lock.LockLimits
import app.heyrobot.core.lock.LockProblem
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.lock.LockSignals
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.core.SettingsPage
import app.heyrobot.ui.Mark
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

// MAIN PROCESS. A lock's page: its name; its kind's rows (Time of day: its hours and days;
// After the alarm: its length, the alarms' hours and days; One app first: the app, then the time in it and when it starts,
// or Daily limit's screen time and until when); what stays open (the always-open set, then the extras: calls to People you
// choose, Maps, a music app, Other apps up to 5, all off); its switch; Delete. Changes wait for Save; with a code set, the
// page opens only inside an edit session (Settings › Phone lock asks for the code first).

/** One app first's choices (minutes): the time in the app, its latest end, Daily limit's amount. */
private val APP_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
private val LATEST_MINUTES = listOf(60, 90, 120, 180, 240, 360, 480, 720)
private val LIMIT_MINUTES = listOf(15, 30, 45, 60, 90, 120, 180, 240, 300, 360, 480)

@Composable
fun PhoneLockEditScreen(initial: PhoneLock, isNew: Boolean, d: PhoneLockData, act: PhoneLockActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val langs = LocalLangs.current
    val r0 = initial.rule
    val kind = r0.kind
    val t0 = r0 as? LockRule.Time
    val a0 = r0 as? LockRule.Alarm
    val p0 = r0 as? LockRule.App
    val at0 = p0?.start as? AppStart.At
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var from by rememberSaveable { mutableStateOf(LockCodec.hm(t0?.window?.from ?: a0?.window?.from ?: TimeWindow.ALARM.from)) }
    var to by rememberSaveable { mutableStateOf(LockCodec.hm(t0?.window?.to ?: a0?.window?.to ?: TimeWindow.ALARM.to)) }
    var days by rememberSaveable { mutableStateOf((t0?.days ?: a0?.days ?: at0?.days ?: LockRule.WEEKDAYS).map { it.value }.sorted()) }
    var alarmMin by rememberSaveable { mutableStateOf(a0?.minutes ?: 60) }
    var app by rememberSaveable { mutableStateOf(p0?.app.orEmpty()) }
    var limit by rememberSaveable { mutableStateOf(p0?.mode == AppMode.LIMIT) }
    var startAlarm by rememberSaveable { mutableStateOf(p0?.start == AppStart.Alarm) }
    var at by rememberSaveable { mutableStateOf(LockCodec.hm(at0?.time ?: LocalTime.of(16, 0))) }
    var appMin by rememberSaveable { mutableStateOf(p0?.minutes ?: 20) }
    var latest by rememberSaveable { mutableStateOf(p0?.latestMin ?: 180) }
    var limitMin by rememberSaveable { mutableStateOf(p0?.limitMin ?: 120) }
    var until by rememberSaveable { mutableStateOf(LockCodec.hm(p0?.until ?: LocalTime.of(5, 0))) }
    var apps by rememberSaveable { mutableStateOf(initial.extras.apps) }
    var people by rememberSaveable { mutableStateOf(initial.extras.people) }
    var maps by rememberSaveable { mutableStateOf(initial.extras.maps) }
    var music by rememberSaveable { mutableStateOf(initial.extras.music) }
    var on by rememberSaveable { mutableStateOf(initial.on) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val sameTimes = stringResource(R.string.lock_same_times)
    val nameProblem = stringResource(R.string.lock_name_problem)
    val timeProblem = stringResource(R.string.lock_time_problem)
    val tooMany = stringResource(R.string.lock_too_many)
    val notSaved = stringResource(R.string.lock_not_saved)
    val appNeeded = stringResource(R.string.lock_app_needed)
    val latestProblem = stringResource(R.string.lock_latest_problem)
    // Opened without an edit session (a link, a session that ran out): back to Settings › Phone lock, which asks for the code.
    if (d.needsCode()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { act.askCode(LockSignals.PURPOSE_EDIT); onBack() }
        return
    }
    fun dayset() = days.map { DayOfWeek.of(it) }.toSet()
    fun draft(): PhoneLock? {
        val rule = when (kind) {
            LockRule.KIND_TIME -> LockRule.Time(TimeWindow(time(from) ?: return null, time(to) ?: return null), dayset())
            LockRule.KIND_ALARM -> LockRule.Alarm(TimeWindow(time(from) ?: return null, time(to) ?: return null), alarmMin, dayset())
            else -> if (limit) LockRule.App(AppMode.LIMIT, app, limitMin = limitMin, until = time(until) ?: return null)
                else LockRule.App(AppMode.FIRST, app, if (startAlarm) AppStart.Alarm else AppStart.At(time(at) ?: return null, dayset()), minutes = appMin, latestMin = latest)
        }
        return initial.copy(name = LockLimits.name(name) ?: name, on = on, rule = rule,
            extras = LockExtras(people = people, maps = maps, music = music, apps = apps))
    }
    fun save() {
        val l = draft()
        val problem = if (l == null) LockProblem.TIME else LockLimits.problem(l)
        val msg = when {
            problem == null -> null
            kind == LockRule.KIND_APP && app.isEmpty() -> appNeeded
            problem == LockProblem.NAME -> nameProblem
            problem == LockProblem.SAME_TIMES -> sameTimes
            problem == LockProblem.TIME -> timeProblem
            problem == LockProblem.LENGTH && kind == LockRule.KIND_APP && !limit -> latestProblem
            else -> notSaved
        }
        if (msg != null) { scope.launch { snackbar.showSnackbar(msg) }; return }
        var full = false
        val ok = act.edit { f ->
            when {
                f.locks.any { it.id == l!!.id } -> f.copy(locks = f.locks.map { if (it.id == l!!.id) l else it })
                f.locks.size >= LockLimits.MAX_LOCKS -> { full = true; f }
                else -> f.copy(locks = f.locks + l!!)
            }
        }
        if (!ok || full) scope.launch { snackbar.showSnackbar(if (full) tooMany else notSaved) } else onBack()
    }
    val labels = remember(apps, music, app) { (apps + listOfNotNull(music, app.takeIf { it.isNotEmpty() })).associateWith { appLabel(ctx, it) } }
    val everyone = remember { try { PeopleFiles.store(ctx).list().filter { LockLimits.validPerson(it.id) } } catch (_: Throwable) { emptyList() } }
    val names = everyone.associate { it.id to it.name }
    val usage = remember(d) { PhoneInfo.usageAccess(ctx) }
    // Calls to People you choose are placed from the call card during a lock (the lock covers the dialer): that needs the
    // Phone permission, asked by the main app and read again with the page (every 2 s).
    val phone = remember(d) { Assistant.granted(ctx, Manifest.permission.CALL_PHONE) }
    val allow = stringResource(R.string.car_allow)
    val clock = remember { RoutineFacts.read(ctx).clock }
    val tWhen = stringResource(when (kind) { LockRule.KIND_ALARM -> R.string.lock_kind_alarm; LockRule.KIND_APP -> R.string.lock_kind_app; else -> R.string.lock_kind_time })
    val tOpen = stringResource(R.string.lock_stays_open)
    val hint = stringResource(R.string.lock_extra_apps_hint)
    val none = stringResource(R.string.lock_none)
    val minutes: (Int) -> String = { LockStrings.minutes(it).render(langs) }
    PreferenceScreen(if (isNew) stringResource(R.string.lock_new) else initial.name, onBack, snackbar = snackbar) {
        preferenceGroup(tWhen, "when") {
            TextPreference(stringResource(R.string.lock_name), name, "", stringResource(R.string.lock_name), stringResource(R.string.lock_name_hint), RIcons.Bookmark,
                validate = { if (LockLimits.name(it) == null) nameProblem else null }) { name = it.trim() }
            when (kind) {
                LockRule.KIND_TIME -> {
                    TimePref(stringResource(R.string.lock_from), from, timeProblem) { from = it }
                    TimePref(stringResource(R.string.lock_to), to, timeProblem) { to = it }
                    DaysPref(stringResource(R.string.lock_days), days) { days = it }
                    PreferenceBlock {
                        Text(stringResource(R.string.lock_days_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LockRule.KIND_ALARM -> {
                    if (!clock) StatusPreference(stringResource(R.string.lock_alarm_needs_clock), null, Mark.WARN)
                    PreferenceItem(stringResource(R.string.lock_alarm_starts), null, RIcons.Alarm)
                    ListPreference(stringResource(R.string.lock_alarm_length), alarmMin, LockLimits.ALARM_MINUTES, label = minutes, icon = RIcons.Timer) { alarmMin = it }
                    TimePref(stringResource(R.string.lock_alarm_from), from, timeProblem) { from = it }
                    TimePref(stringResource(R.string.lock_alarm_to), to, timeProblem) { to = it }
                    DaysPref(stringResource(R.string.lock_days), days) { days = it }
                    PreferenceBlock {
                        Text(stringResource(R.string.lock_alarm_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    PreferenceItem(stringResource(R.string.lock_app), if (app.isEmpty()) stringResource(R.string.lock_app_choose) else labels[app] ?: app, RIcons.Apps,
                        onClick = { picking = PICK_APP })
                    if (!usage) StatusPreference(stringResource(R.string.lock_access_usage), stringResource(R.string.lock_access_usage_off), Mark.WARN,
                        onClick = { openUsage(ctx) })
                    ListPreference(stringResource(R.string.lock_app_mode), limit, listOf(false, true),
                        label = { if (it) ctx.getString(R.string.lock_mode_limit) else ctx.getString(R.string.lock_mode_first) },
                        detail = { if (it) ctx.getString(R.string.lock_mode_limit_detail) else null }) { limit = it }
                    if (!limit) {
                        ListPreference(stringResource(R.string.lock_app_minutes), appMin, APP_MINUTES, label = minutes, icon = RIcons.Timer) { appMin = it }
                        ListPreference(stringResource(R.string.lock_app_start), startAlarm, listOf(false, true),
                            label = { if (it) ctx.getString(R.string.lock_app_start_alarm) else ctx.getString(R.string.lock_app_start_at) }, icon = RIcons.Alarm) { startAlarm = it }
                        if (!startAlarm) {
                            TimePref(stringResource(R.string.lock_app_at), at, timeProblem) { at = it }
                            DaysPref(stringResource(R.string.lock_days), days) { days = it }
                        } else if (!clock) StatusPreference(stringResource(R.string.lock_alarm_needs_clock), null, Mark.WARN)
                        ListPreference(stringResource(R.string.lock_app_latest), latest, LATEST_MINUTES, label = minutes) { latest = it }
                        PreferenceBlock {
                            Text(stringResource(R.string.lock_app_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        ListPreference(stringResource(R.string.lock_limit_amount), limitMin, LIMIT_MINUTES, label = minutes, icon = RIcons.Timer) { limitMin = it }
                        TimePref(stringResource(R.string.lock_limit_until), until, timeProblem) { until = it }
                    }
                }
            }
        }
        preferenceGroup(tOpen, "open") {
            PreferenceItem(stringResource(R.string.lock_always_open), null, RIcons.Phone)
            PreferenceItem(stringResource(R.string.lock_extra_people), if (people.isEmpty()) none else people.joinToString(", ") { names[it] ?: it }, RIcons.Person,
                onClick = { picking = PICK_PEOPLE }) // l10n: not text (joins your People's names)
            if (people.isNotEmpty()) StatusPreference(stringResource(R.string.car_phone_permission), null, if (phone) Mark.OK else Mark.TODO,
                action = if (phone) null else ({
                    TextButton(onClick = { act.askPermission(Manifest.permission.CALL_PHONE) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) }
                }))
            SwitchPreference(stringResource(R.string.lock_extra_maps), stringResource(if (maps) R.string.lock_extra_maps_on else R.string.lock_extra_maps_off), maps,
                icon = RIcons.Place) { maps = it }
            PreferenceItem(stringResource(R.string.lock_extra_music), music?.let { labels[it] ?: it } ?: none, RIcons.Apps, onClick = { picking = PICK_MUSIC })
            PreferenceItem(stringResource(R.string.lock_extra_apps), if (apps.isEmpty()) stringResource(R.string.lock_extra_apps_none) else apps.joinToString(", ") { labels[it] ?: it },
                RIcons.Apps, onClick = { picking = PICK_APPS }) // l10n: not text (joins your apps' names)
        }
        preferenceGroup(null, "this") {
            SwitchPreference(stringResource(R.string.lock_this_lock), stringResource(if (on) R.string.lock_this_lock_on else R.string.lock_this_lock_off), on) { on = it }
            PreferenceBlock {
                Row {
                    Button(onClick = ::save, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_save)) }
                    Spacer(Modifier.width(12.dp))
                    if (!isNew) TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_delete)) }
                }
            }
        }
        preferenceFooter(hint)
    }
    when (picking) {
        PICK_APPS -> AppsDialog(stringResource(R.string.lock_extra_apps), apps, LockLimits.MAX_APPS, onDismiss = { picking = null }) { picking = null; apps = it }
        PICK_APP -> AppsDialog(stringResource(R.string.lock_app), listOf(app).filter { it.isNotEmpty() }, 1, onDismiss = { picking = null }) {
            picking = null; app = it.firstOrNull().orEmpty()
        }
        PICK_MUSIC -> AppsDialog(stringResource(R.string.lock_extra_music), listOfNotNull(music), 1, music = true, onDismiss = { picking = null }) {
            picking = null; music = it.firstOrNull()
        }
        PICK_PEOPLE -> PeopleDialog(stringResource(R.string.lock_extra_people), everyone.map { it.id to it.name }, people, onDismiss = { picking = null }) {
            picking = null; people = it
        }
    }
    if (deleting) ConfirmDialog(stringResource(R.string.lock_delete_title), initial.name, stringResource(R.string.lock_delete), onDismiss = { deleting = false }) {
        deleting = false
        if (act.edit { f -> f.copy(locks = f.locks.filterNot { it.id == initial.id }) }) onBack()
    }
}

private const val PICK_APPS = "apps"
private const val PICK_APP = "app"
private const val PICK_MUSIC = "music"
private const val PICK_PEOPLE = "people"

/** Android's usage access page: Robot's own switch where Android supports it, else the list (as phone status opens it). */
internal fun openUsage(ctx: Context) {
    for (i in SettingsIntents.of(ctx, SettingsPage.USAGE_ACCESS)) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
    }
}

/** People you choose for calls during a lock (up to [LockLimits.MAX_PEOPLE]). */
@Composable
private fun PeopleDialog(title: String, all: List<Pair<String, String>>, picked0: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(picked0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text(stringResource(R.string.lock_extra_people_empty), style = MaterialTheme.typography.bodyMedium)
                for ((id, name) in all) {
                    CheckRow(name, id in picked) { picked = if (it && picked.size < LockLimits.MAX_PEOPLE) picked + id else picked - id }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked.filter { p -> all.any { it.first == p } }) }) { Text(stringResource(R.string.lock_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

private val HM = Regex("([01]?[0-9]|2[0-3]):[0-5][0-9]")

/** "22:00" or "7:30" as a time, or null. */
private fun time(s: String): LocalTime? = s.trim().takeIf { HM.matches(it) }?.let { LocalTime.parse(it.padStart(5, '0')) }

@Composable
private fun TimePref(title: String, value: String, problem: String, onSave: (String) -> Unit) {
    TextPreference(title, value, "", title, stringResource(R.string.lock_time_hint), RIcons.Timer,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        validate = { if (time(it) == null) problem else null }) { t -> time(t)?.let { onSave(LockCodec.hm(it)) } }
}

@Composable
private fun DaysPref(title: String, days: List<Int>, onSave: (List<Int>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceItem(title, RoutineCards.daysText(days.map { DayOfWeek.of(it) }.toSet()).str().replaceFirstChar { it.uppercase() }, RIcons.Event, onClick = { open = true })
    if (open) {
        var picked by rememberSaveable { mutableStateOf(days) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (day in DayOfWeek.entries) {
                        val on = day.value in picked
                        CheckRow(RoutineCards.dayName(day).str().replaceFirstChar { it.uppercase() }, on) { picked = if (it) (picked + day.value).sorted() else picked - day.value }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false; onSave(picked) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.lock_save)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.lock_cancel)) } },
        )
    }
}

@Composable
private fun CheckRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The apps a lock may leave open: the launcher's apps (the existing <queries>), without Robot and core
 * [LockGate.NEVER_OPEN] (Settings, app stores, installers, the apps that can remove, stop or pause Robot), which a lock
 * always covers. Up to [LockLimits.MAX_APPS]. 1.2: one of those saved before the list grew is dropped when you save here.
 */
@Composable
private fun AppsDialog(title: String, apps: List<String>, max: Int, music: Boolean = false, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val all = remember { launcherApps(ctx, music) }
    var picked by rememberSaveable { mutableStateOf(apps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((pkg, label) in all) {
                    val on = pkg in picked
                    // One app at most: picking another replaces it.
                    CheckRow(label, on) { picked = if (!it) picked - pkg else if (max == 1) listOf(pkg) else if (picked.size < max) picked + pkg else picked }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked.filter { it !in LockGate.NEVER_OPEN }) }) { Text(stringResource(R.string.lock_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

private fun launcherApps(ctx: Context, music: Boolean = false): List<Pair<String, String>> = try {
    val pm = ctx.packageManager
    // Music: the apps that say they are one (CATEGORY_APP_MUSIC, in <queries>), else every launcher app.
    val musicApps = if (!music) emptyList() else pm.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }
    (musicApps.ifEmpty { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL).map { it.activityInfo.packageName } })
        .distinct()
        .filter { it != ctx.packageName && it !in LockGate.NEVER_OPEN && LockLimits.validPackage(it) }
        .map { it to appLabel(ctx, it) }.sortedBy { it.second.lowercase() }
} catch (_: Throwable) {
    emptyList()
}

private fun appLabel(ctx: Context, pkg: String): String = try {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Throwable) {
    pkg
}
