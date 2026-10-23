// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.Clocks
import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockLimits
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.lock.RecordEvent
import app.heyrobot.core.lock.RecordLine
import app.heyrobot.lock.LockAccess
import app.heyrobot.lock.LockSignals
import app.heyrobot.lock.LockStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetSwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

// MAIN PROCESS. Settings › Phone lock (1.1, docs/plans/phone-lock-design.md §10, §11): its rows, the setup, What happened
// and What a lock can't stop; the Time of day editor is PhoneLockEditor.kt. This process writes locks.json only
// (LockStore.editLocks, which tells :lock); it reads state.json for what is on, whether a code is set and whether an edit
// session is open, and asks :lock's code pad (by class name) for the code. With a code set, every change but turning
// something on needs the code first (§8.2): the code opens a 5-minute edit session, which ends when you leave these pages
// or Robot's app, or the screen goes off (PhoneLockPages).

object PhoneLockRoutes {
    const val SETUP = SettingsRoutes.PHONE_LOCK + "/setup"
    const val HISTORY = SettingsRoutes.PHONE_LOCK + "/history"
    const val CANT_STOP = SettingsRoutes.PHONE_LOCK + "/cant-stop"
    const val NEW = SettingsRoutes.PHONE_LOCK + "/new"
    /** Phase B: a new After the alarm or One app first lock. */
    const val NEW_ALARM = SettingsRoutes.PHONE_LOCK + "/new-alarm"
    const val NEW_APP = SettingsRoutes.PHONE_LOCK + "/new-app"
    fun lock(id: String) = SettingsRoutes.PHONE_LOCK + "/" + id
}

/** What the pages show, read again every 2 seconds (the code pad, Android's settings and :lock change it from outside). */
class PhoneLockData(val read: LockFileRead, val state: LockState, val access: Boolean, val notifications: Boolean, val usage: Boolean = false) {
    val file: LockFile? get() = read.ok
    fun needsCode(now: Long = System.currentTimeMillis()): Boolean = state.codeSet && !state.editOpen(now)

    companion object {
        fun read(ctx: Context) = PhoneLockData(LockStore.read(ctx), LockStore.readState(ctx), LockAccess.enabled(ctx),
            try { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() } catch (_: Throwable) { false },
            app.heyrobot.sys.PhoneInfo.usageAccess(ctx))
    }
}

/**
 * The edit session ends when you leave (§8.2): when the last Phone lock page closes (a page opening another doesn't end
 * it), when Robot's app leaves the screen ([left]), and at screen-off (:lock).
 */
internal object PhoneLockPages {
    private var open = 0
    private val main = Handler(Looper.getMainLooper())

    fun opened() { open++ }
    fun closed(ctx: Context) {
        open--
        main.postDelayed({ if (open == 0 && LockStore.readState(ctx).edit > 0) LockSignals.editEnded(ctx) }, 1_000)
    }

    /**
     * MainActivity's onStop, never for a rotation: Home, Recents, the power button or another app's window (Android's
     * settings pages too) ends the session. Robot's code pad, opened from these pages over it, doesn't: the pad reads the
     * session as it opens (Change the code, Remove the code) or opens one, and leaving the pad ends the session there.
     * Android's permission dialog doesn't stop the activity under it.
     */
    fun left(a: Activity) {
        if (padOnTop(a)) return
        val ctx = a.applicationContext
        if (LockStore.readState(ctx).edit > 0) LockSignals.editEnded(ctx)
    }

    /** Robot's code pad is at the top of [a]'s task now. */
    private fun padOnTop(a: Activity): Boolean = try {
        a.getSystemService(ActivityManager::class.java).appTasks.any { t -> t.taskInfo.let { it.taskId == a.taskId && it.topActivity?.className == LockSignals.CODE } }
    } catch (_: Throwable) {
        false
    }
}

/** 1.1: Settings › Phone lock's row in Settings. */
fun phoneLockSummaryText(ctx: Context): Phrase {
    val read = LockStore.read(ctx)
    val f = read.ok ?: return LockStrings.summaryDamaged()
    if (!f.settings.on) return LockStrings.summaryOff()
    val st = LockStore.readState(ctx)
    val opens = if (st.active.isEmpty()) null else LockSchedule.opensAt(f, st, Clocks(ZonedDateTime.now(), SystemClock.elapsedRealtime(), null))
    return if (opens != null && opens.isAfter(ZonedDateTime.now())) LockStrings.summaryNow("time" to opens.toLocalTime()) else LockStrings.summaryOn(f.locks.size)
}

@Composable
fun PhoneLockRoute(route: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    DisposableEffect(Unit) {
        PhoneLockPages.opened()
        onDispose { PhoneLockPages.closed(ctx.applicationContext) }
    }
    val d = remember(tick) { PhoneLockData.read(ctx) }
    val code = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val removed = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Without a code, nothing can be With the code or for someone else.
        if (r.resultCode == Activity.RESULT_OK) LockStore.editLocks(ctx) { f ->
            f.copy(settings = f.settings.copy(forWho = LockFor.ME, endEarly = if (f.settings.endEarly == EndEarly.CODE) EndEarly.OFF else f.settings.endEarly))
        }
        tick++
    }
    val act = PhoneLockActions(
        open = { a.openPage(Page.Settings(it)) },
        edit = { change -> LockStore.editLocks(ctx, change).also { tick++ } != null },
        askCode = { purpose -> code.launch(LockSignals.code(ctx, purpose)) },
        removeCode = { removed.launch(LockSignals.code(ctx, LockSignals.PURPOSE_REMOVE)) },
        startOver = { LockStore.startOver(ctx); tick++ },
        askPermission = a.askPermission,
    )
    when {
        route == SettingsRoutes.PHONE_LOCK -> PhoneLockScreen(d, act, snackbar, onBack)
        route == PhoneLockRoutes.SETUP -> PhoneLockSetupScreen(d, onBack)
        route == PhoneLockRoutes.HISTORY -> PhoneLockHistoryScreen(d, act, onBack)
        route == PhoneLockRoutes.CANT_STOP -> PhoneLockCantStopScreen(onBack)
        route == PhoneLockRoutes.NEW || route == PhoneLockRoutes.NEW_ALARM || route == PhoneLockRoutes.NEW_APP -> {
            val id = rememberSaveable { newLockId() }
            val rule = when (route) {
                // Defaults: 60 minutes after an alarm 4:00–12:00 on weekdays; 20 minutes in the app from 16:00 on weekdays.
                PhoneLockRoutes.NEW_ALARM -> LockRule.Alarm(app.heyrobot.core.TimeWindow.ALARM, 60, LockRule.WEEKDAYS)
                PhoneLockRoutes.NEW_APP -> LockRule.App(app.heyrobot.core.lock.AppMode.FIRST, "")
                else -> LockRule.Time(app.heyrobot.core.TimeWindow(java.time.LocalTime.of(22, 0), java.time.LocalTime.of(7, 0)))
            }
            val name = stringResource(when (route) {
                PhoneLockRoutes.NEW_ALARM -> R.string.lock_kind_alarm
                PhoneLockRoutes.NEW_APP -> R.string.lock_kind_app
                else -> R.string.lock_default_name
            })
            PhoneLockEditScreen(PhoneLock(id, name, rule), isNew = true, d, act, snackbar, onBack)
        }
        else -> {
            val l = d.file?.lock(route.removePrefix(SettingsRoutes.PHONE_LOCK + "/"))
            if (l == null) LaunchedEffect(route) { onBack() } else PhoneLockEditScreen(l, isNew = false, d, act, snackbar, onBack)
        }
    }
}

/** "lk_" and 8 random lowercase hex digits. */
fun newLockId(): String = "lk_" + java.util.UUID.randomUUID().toString().replace("-", "").take(8) // l10n: not text (an id)

class PhoneLockActions(
    val open: (String) -> Unit,
    /** Changes locks.json; false when it couldn't be written. */
    val edit: ((LockFile) -> LockFile) -> Boolean,
    /** The code pad for [LockSignals]' purpose (the result refreshes the page). */
    val askCode: (String) -> Unit,
    val removeCode: () -> Unit,
    val startOver: () -> Unit,
    /** A permission a lock's row needs (the People row's Phone permission), asked by the main app as its other rows' are. */
    val askPermission: (String) -> Unit = {},
)

// ---------------------------------------------------------------------------------------------- Settings › Phone lock

@Composable
fun PhoneLockScreen(d: PhoneLockData, act: PhoneLockActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var startOver by rememberSaveable { mutableStateOf(false) }
    var removeCode by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val langs = LocalLangs.current
    val needSomeone = stringResource(R.string.lock_code_needed_someone)
    val f = d.file
    val locked = d.needsCode()
    /** A change that needs the code when one is set: the code pad first. */
    fun guarded(block: () -> Unit) = if (locked) act.askCode(LockSignals.PURPOSE_EDIT) else block()
    fun settings(change: (app.heyrobot.core.lock.LockSettings) -> app.heyrobot.core.lock.LockSettings) = act.edit { it.copy(settings = change(it.settings)) }
    val tLocks = stringResource(R.string.lock_locks)
    val tRules = stringResource(R.string.lock_rules)
    val tMore = stringResource(R.string.lock_more)
    val off = stringResource(R.string.lock_end_off)
    val forLabels = LockFor.entries.associateWith { stringResource(if (it == LockFor.ME) R.string.lock_for_me else R.string.lock_for_someone) }
    val endLabels = EndEarly.entries.associateWith { endText(it) }
    val endDetails = EndEarly.entries.associateWith { endDetail(it) }
    val footer = stringResource(R.string.lock_intro)
    val tWho = stringResource(R.string.lock_who)
    val tEnd = stringResource(R.string.lock_end_early)
    val tWait = stringResource(R.string.lock_wait_len)
    val tWarn = stringResource(R.string.lock_warn)
    PreferenceScreen(UiStrings.phoneLock().str(), onBack, snackbar = snackbar) {
        if (f == null) {
            preferenceGroup(null, "damaged") {
                StatusPreference(stringResource(R.string.lock_damaged), null, Mark.WARN, action = {
                    TextButton(onClick = { startOver = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_start_over)) }
                })
            }
            return@PreferenceScreen
        }
        val s = f.settings
        preferenceGroup(null, "main") {
            if (s.on && !d.access) StatusPreference(stringResource(R.string.lock_access_a11y), stringResource(R.string.lock_access_off), Mark.WARN,
                onClick = { act.open(PhoneLockRoutes.SETUP) })
            if (locked) StatusPreference(stringResource(R.string.lock_needs_code_title), stringResource(R.string.lock_needs_code), Mark.TODO, action = {
                TextButton(onClick = { act.askCode(LockSignals.PURPOSE_EDIT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_enter_code)) }
            })
            SwitchPreference(UiStrings.phoneLock().str(), stringResource(if (s.on) R.string.lock_switch_on else R.string.lock_switch_off), s.on, icon = RIcons.Lock) { on ->
                // Turning it on only makes the phone stricter: no code needed (§8.2). The first time, the setup.
                if (on) { settings { it.copy(on = true) }; if (!d.access) act.open(PhoneLockRoutes.SETUP) } else guarded { settings { it.copy(on = false) } }
            }
            ListPreference(tWho, s.forWho, LockFor.entries, label = { forLabels.getValue(it) }, icon = RIcons.Person) { w ->
                guarded {
                    if (w == LockFor.SOMEONE_ELSE && !d.state.codeSet) scope.launch { snackbar.showSnackbar(needSomeone) } else settings { it.copy(forWho = w) }
                }
            }
            if (!d.state.codeSet) {
                PreferenceItem(stringResource(R.string.lock_code), stringResource(R.string.lock_code_none), RIcons.Key, onClick = { act.askCode(LockSignals.PURPOSE_SET) })
            } else {
                PreferenceItem(stringResource(R.string.lock_code_change), stringResource(R.string.lock_code_is_set), RIcons.Key,
                    onClick = { guarded { act.askCode(LockSignals.PURPOSE_SET) } })
                PreferenceItem(stringResource(R.string.lock_code_remove), null, RIcons.Delete, onClick = { guarded { removeCode = true } })
            }
            PreferenceBlock {
                Text(stringResource(R.string.lock_code_forgot), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        preferenceGroup(tLocks, "locks") {
            for (l in f.locks) {
                TwoTargetSwitchPreference(l.name, lockRow(l) { p -> appName(ctx, p) }.str(), l.on,
                    onClick = { guarded { act.open(PhoneLockRoutes.lock(l.id)) } },
                    onCheckedChange = { on ->
                        val change: () -> Unit = { act.edit { file -> file.copy(locks = file.locks.map { if (it.id == l.id) it.copy(on = on) else it }) } }
                        if (on) change() else guarded(change)
                    },
                    switchLabel = stringResource(R.string.lock_on, l.name))
            }
            PreferenceItem(stringResource(R.string.lock_add), if (f.locks.isEmpty()) stringResource(R.string.lock_add_hint) else null,
                RIcons.Add, enabled = f.locks.size < LockLimits.MAX_LOCKS, onClick = { guarded { adding = true } })
        }
        preferenceGroup(tRules, "rules") {
            if (s.forWho == LockFor.SOMEONE_ELSE) {
                StatusPreference(stringResource(R.string.lock_end_early), stringResource(R.string.lock_end_fixed), Mark.OK)
            } else {
                ListPreference(tEnd, s.endEarly, EndEarly.entries, label = { endLabels.getValue(it) }, detail = { endDetails.getValue(it) }, icon = RIcons.Timer) { e ->
                    guarded {
                        if (e == EndEarly.CODE && !d.state.codeSet) scope.launch { snackbar.showSnackbar(needSomeone) } else settings { it.copy(endEarly = e) }
                    }
                }
                if (s.endEarly == EndEarly.WAIT) ListPreference(tWait, s.waitMin, LockLimits.WAIT_CHOICES,
                    label = { LockStrings.minutes(it).render(langs) }) { m -> guarded { settings { it.copy(waitMin = m) } } }
            }
            ListPreference(tWarn, s.warnMin, LockLimits.WARN_CHOICES,
                label = { if (it == 0) off else LockStrings.warnMinutes(it).render(langs) }, icon = RIcons.Alarm) { m -> guarded { settings { it.copy(warnMin = m) } } }
            SwitchPreference(stringResource(R.string.lock_show_left), stringResource(if (s.showLeft) R.string.lock_show_left_on else R.string.lock_show_left_off), s.showLeft) { v ->
                guarded { settings { it.copy(showLeft = v) } }
            }
        }
        preferenceGroup(tMore, "more") {
            PreferenceItem(stringResource(R.string.lock_history), stringResource(R.string.lock_history_summary), RIcons.History,
                onClick = { guarded { act.open(PhoneLockRoutes.HISTORY) } })
            StatusPreference(stringResource(R.string.lock_access_a11y), stringResource(if (d.access) R.string.lock_access_on else R.string.lock_access_off),
                if (d.access) Mark.OK else Mark.TODO, onClick = { act.open(PhoneLockRoutes.SETUP) })
            // Phase B: usage access, only with a One app first lock (it counts the time in the app).
            if (f.locks.any { it.rule is LockRule.App }) StatusPreference(stringResource(R.string.lock_access_usage),
                stringResource(if (d.usage) R.string.lock_access_usage_on else R.string.lock_access_usage_off), if (d.usage) Mark.OK else Mark.WARN,
                onClick = { act.open(PhoneLockRoutes.SETUP) })
            StatusPreference(stringResource(R.string.lock_access_notifications),
                stringResource(if (d.notifications) R.string.lock_access_notifications_ok else R.string.lock_access_notifications_no), if (d.notifications) Mark.OK else Mark.TODO)
            PreferenceItem(stringResource(R.string.lock_cant_stop), stringResource(R.string.lock_cant_stop_summary), RIcons.Info, onClick = { act.open(PhoneLockRoutes.CANT_STOP) })
        }
        preferenceFooter(footer)
    }
    if (startOver) ConfirmDialog(stringResource(R.string.lock_start_over_title), stringResource(R.string.lock_start_over_text),
        stringResource(R.string.lock_start_over), onDismiss = { startOver = false }) { startOver = false; act.startOver() }
    if (adding) AddLockDialog(onDismiss = { adding = false }) { route -> adding = false; act.open(route) }
    if (removeCode) ConfirmDialog(stringResource(R.string.lock_code_remove_title), stringResource(R.string.lock_code_remove_text),
        stringResource(R.string.lock_code_remove), onDismiss = { removeCode = false }) { removeCode = false; act.removeCode() }
}

/** "Every day, 22:00 to 7:00", "Weekdays, 60 minutes after the alarm", "20 minutes in Libby first". [label]: an app's name. */
fun lockRow(l: PhoneLock, label: (String) -> String = { it }): Phrase = when (val r = l.rule) {
    is LockRule.Time -> LockStrings.timeRow("days" to RoutineCards.daysText(r.days), "from" to r.window.from, "to" to r.window.to)
    is LockRule.Alarm -> LockStrings.alarmRow("days" to RoutineCards.daysText(r.days), "n" to r.minutes)
    is LockRule.App -> if (r.mode == app.heyrobot.core.lock.AppMode.LIMIT) LockStrings.limitRow("n" to r.limitMin, "label" to label(r.app))
        else LockStrings.appFirstRow("n" to r.minutes, "label" to label(r.app))
}

/** Add a lock: After the alarm / Time of day / One app first (§10). */
@Composable
private fun AddLockDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val kinds = listOf(
        Triple(PhoneLockRoutes.NEW_ALARM, R.string.lock_kind_alarm, R.string.lock_kind_alarm_detail),
        Triple(PhoneLockRoutes.NEW, R.string.lock_kind_time, R.string.lock_kind_time_detail),
        Triple(PhoneLockRoutes.NEW_APP, R.string.lock_kind_app, R.string.lock_kind_app_detail),
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lock_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((route, title, detail) in kinds) PreferenceItem(stringResource(title), stringResource(detail), onClick = { onPick(route) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.lock_cancel)) } },
    )
}

@Composable
private fun endText(e: EndEarly): String = stringResource(when (e) { EndEarly.OFF -> R.string.lock_end_off; EndEarly.WAIT -> R.string.lock_end_wait; EndEarly.CODE -> R.string.lock_end_code })

@Composable
private fun endDetail(e: EndEarly): String = stringResource(when (e) {
    EndEarly.OFF -> R.string.lock_end_off_detail
    EndEarly.WAIT -> R.string.lock_end_wait_detail
    EndEarly.CODE -> R.string.lock_end_code_detail
})

private fun appName(ctx: Context, pkg: String): String = try {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Throwable) { pkg }

// ---------------------------------------------------------------------------------------------- setup (§11)

@Composable
fun PhoneLockSetupScreen(d: PhoneLockData, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun access() { try { ctx.startActivity(LockAccess.settings()) } catch (_: Exception) {} }
    fun appInfo() { try { ctx.startActivity(NotificationAccess.appInfo(ctx)) } catch (_: Exception) {} }
    val reminder = stringResource(R.string.lock_setup_reminder)
    PreferenceScreen(stringResource(R.string.lock_setup_title), onBack) {
        if (d.access) preferenceGroup(null, "done") { StatusPreference(stringResource(R.string.lock_setup_done), null, Mark.OK) }
        preferenceGroup(null, "steps") {
            PreferenceBlock {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.lock_setup_why), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.lock_setup_restricted), style = MaterialTheme.typography.bodyMedium, color = muted)
                    Text(stringResource(R.string.lock_setup_1), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = ::access, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_setup_open)) }
                    Text(stringResource(R.string.lock_setup_2), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = ::appInfo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_app_info)) }
                    Text(stringResource(R.string.lock_setup_3), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = ::access, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_setup_again)) }
                    // Phase B: step 4, only for One app first (usage access counts the time in its app).
                    if (d.file?.locks.orEmpty().any { it.rule is LockRule.App }) {
                        Text(stringResource(R.string.lock_setup_4), style = MaterialTheme.typography.bodyMedium)
                        if (d.usage) Text(stringResource(R.string.lock_access_usage_on), style = MaterialTheme.typography.bodyMedium, color = muted)
                        else OutlinedButton(onClick = { openUsage(ctx) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_setup_usage)) }
                    }
                }
            }
        }
        preferenceFooter(reminder)
    }
}

// ---------------------------------------------------------------------------------------------- What happened (§3)

@Composable
fun PhoneLockHistoryScreen(d: PhoneLockData, act: PhoneLockActions, onBack: () -> Unit) {
    // Behind the code when one is set: without a session, the code pad first.
    LaunchedEffect(d.needsCode()) { if (d.needsCode()) { act.askCode(LockSignals.PURPOSE_EDIT); onBack() } }
    val langs = LocalLangs.current
    val lines = d.state.record.reversed()
    val historyFooter = stringResource(R.string.lock_history_footer)
    val empty = stringResource(R.string.lock_history_empty)
    PreferenceScreen(stringResource(R.string.lock_history), onBack) {
        preferenceGroup(null, "lines") {
            if (lines.isEmpty()) PreferenceItem(empty)
            for (r in lines) PreferenceItem(recordText(r, d.file).render(langs))
        }
        preferenceFooter(historyFooter)
    }
}

/** One line of the record: "Saturday at 22:00: Started: Bedtime". */
fun recordText(r: RecordLine, f: LockFile?): Phrase {
    val id = r.lock
    val name: Any = when {
        id == ActiveLock.VOICE -> LockStrings.byVoice()
        id != null -> f?.lock(id)?.name ?: LockStrings.recordDeleted()
        else -> LockStrings.recordDeleted()
    }
    val at = Instant.ofEpochMilli(r.t).atZone(ZonedDateTime.now().zone)
    val what = when (r.e) {
        RecordEvent.STARTED -> LockStrings.recordStarted("name" to name)
        RecordEvent.ENDED -> LockStrings.recordEnded("name" to name)
        RecordEvent.ENDED_CODE -> LockStrings.recordEndedCode()
        RecordEvent.ENDED_WAIT -> LockStrings.recordEndedWait()
        RecordEvent.ENDED_APP -> LockStrings.recordEndedApp((r.n ?: 0).toLong(), "name" to name)
        RecordEvent.SKIPPED -> LockStrings.recordSkipped("name" to name)
        RecordEvent.ACCESS_OFF -> LockStrings.recordAccessOff()
        RecordEvent.ACCESS_ON -> LockStrings.recordAccessOn()
        RecordEvent.GAP -> LockStrings.recordGap((r.n ?: 0).toLong())
        RecordEvent.RESTARTED -> LockStrings.recordRestarted()
        RecordEvent.CAR -> LockStrings.recordCar()
        RecordEvent.WRONG_CODES -> LockStrings.recordWrongCodes((r.n ?: 0).toLong())
        RecordEvent.KEY_LOST -> LockStrings.recordKeyLost()
        RecordEvent.COULD_NOT_START -> LockStrings.recordNotStarted("name" to name)
        RecordEvent.LONGER -> LockStrings.recordLonger("time" to at.plusMinutes((r.n ?: 0).toLong()).toLocalTime())
    }
    return LockStrings.recordLine("day" to at.toLocalDate(), "time" to at.toLocalTime(), "what" to what)
}

// ---------------------------------------------------------------------------------------------- What a lock can't stop

@Composable
fun PhoneLockCantStopScreen(onBack: () -> Unit) {
    val ids = listOf(R.string.lock_cant_stop_1, R.string.lock_cant_stop_2, R.string.lock_cant_stop_3, R.string.lock_cant_stop_4, R.string.lock_cant_stop_5,
        R.string.lock_cant_stop_6, R.string.lock_cant_stop_7, R.string.lock_cant_stop_8)
    val texts = ids.map { stringResource(it) }
    PreferenceScreen(stringResource(R.string.lock_cant_stop), onBack) {
        preferenceGroup(null, "text") {
            PreferenceBlock {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (t in texts) Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
