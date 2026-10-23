// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.BriefItem
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminder
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.ClockNeed
import app.heyrobot.core.DeviceKind
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCards
import app.heyrobot.core.RoutineCheck
import app.heyrobot.core.RoutineConditions
import app.heyrobot.core.RoutineDraft
import app.heyrobot.core.RoutineDraftCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineLock
import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineSettingsCodec
import app.heyrobot.core.RoutineTexts
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RoutineWords
import app.heyrobot.core.RunRecord
import app.heyrobot.core.StepChoice
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.readOrNull
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.Paths
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioDialog
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.TwoTargetSwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.delay
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

// MAIN PROCESS (0.8). Settings › Routines, as Android-style pages: the morning brief and your routines with their
// switches and last runs, what waits for you (Ready), when routines may run, and what they need from the phone; a
// routine's page, which is also the editor (when, the steps from a list, where it speaks, the locked phone, Run now,
// Delete), whose Save shows the same card as by voice; and the morning brief's page. Everything is in files/robot
// (sys RoutineFiles, core RoutineStore), not in settings.json; each change tells the :voice process and sets the alarm.

/** What Settings › Routines shows, read from the files now. */
data class RoutinesData(
    /** Yours, without the built-in brief. */
    val routines: List<Routine> = emptyList(),
    val settings: RoutineSettings = RoutineSettings(),
    val runs: Map<String, RunRecord> = emptyMap(),
    val ready: List<ReadyCard> = emptyList(),
    val reminders: List<BriefReminder> = emptyList(),
    /** The routines file can't be read, or a newer Robot wrote it. */
    val problem: RoutineFile? = null,
    val facts: RoutineFacts? = null,
    /** Remembered headsets and cars (headset mode's devices.json): id to name, by kind. */
    val devices: Map<DeviceKind, List<Pair<String, String>>> = emptyMap(),
) {
    val brief: Routine get() = BriefRoutine.of(settings)

    companion object {
        fun read(ctx: android.content.Context): RoutinesData {
            val store = RoutineFiles.store(ctx)
            val file = store.file()
            val today = ZonedDateTime.now().toLocalDate()
            val memory = (DeviceMemory.decode(readOrNull(File(Paths.dir(ctx), "devices.json"))) as? DeviceFile.Ok)?.memory
            return RoutinesData(
                routines = (file as? RoutineFile.Ok)?.routines.orEmpty(),
                settings = store.settings(),
                runs = store.state().runs,
                ready = ReadyCards.live(store.ready(), today),
                reminders = store.reminders(),
                problem = file.takeIf { it !is RoutineFile.Ok },
                facts = RoutineFacts.read(ctx),
                devices = DeviceKind.entries.associateWith { k -> memory?.devices?.filter { it.role.id == k.id }?.map { it.id to it.name }.orEmpty() },
            )
        }
    }
}

/** What the Routines pages can do. */
class RoutineActions(
    /** A Routines page by route ("routines/brief", "routines/new", "routines/<id>"). */
    val open: (String) -> Unit = {},
    /** Saves a routine (after its card); false when it couldn't be written. */
    val save: (Routine) -> Boolean = { true },
    val delete: (String) -> Unit = {},
    val setEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val settings: (RoutineSettings) -> Unit = {},
    /** Run now: the panel opens and runs it, as if you had asked. */
    val runNow: (String) -> Unit = {},
    /** A Ready card: the panel opens with it. */
    val openReady: (String) -> Unit = {},
    val removeReminder: (BriefReminder) -> Unit = {},
    /** The routines file can't be read: keep it aside and start over. */
    val startOver: () -> Unit = {},
)

object RoutineRoutes {
    const val BRIEF = "routines/brief"
    const val NEW = "routines/new"
    fun routine(id: String) = "routines/$id" // l10n: not text (a route)
}

/** Settings › Routines and its pages, read from files/robot and refreshed while they show (runs and Ready cards come from :voice). */
@Composable
fun RoutinesRoute(route: String, s: RobotSettings, snap: Snapshot, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val data = remember(tick) { RoutinesData.read(ctx) }
    val store = remember { RoutineFiles.store(ctx) }
    fun changed() { RoutineFiles.changed(ctx); tick++ }
    val act = RoutineActions(
        open = { a.openPage(Page.Settings(it)) },
        save = { r -> store.save(r).also { changed() } },
        delete = { id -> store.delete(id); changed() },
        setEnabled = { id, on -> store.setEnabled(id, on, System.currentTimeMillis()); changed() },
        settings = { rs -> store.editSettings { rs }; changed() },
        runNow = a.runRoutine,
        openReady = a.openReady,
        removeReminder = { r -> store.editReminders { list -> list.filterNot { it == r } }; tick++ },
        startOver = { store.reset(); changed() },
    )
    when {
        route == SettingsRoutes.ROUTINES -> RoutinesScreen(data, s, snap.facts, act, a, snackbar, onBack)
        route == RoutineRoutes.BRIEF -> BriefSettingsScreen(data, act, snackbar, onBack)
        route == RoutineRoutes.NEW -> {
            val id = rememberSaveable { UUID.randomUUID().toString() }
            RoutineEditScreen(Routine(id, "", RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), emptyList(), created = 0), true, data, act,
                snackbar, onBack)
        }
        else -> {
            val r = data.routines.firstOrNull { it.id == route.removePrefix("routines/") }
            if (r == null) LaunchedEffect(route) { onBack() } else RoutineEditScreen(r, false, data, act, snackbar, onBack)
        }
    }
}

/** 1.1: [routinesSummary] without a language yet (« Résumé du matin activé · 2 routines »). */
fun routinesSummaryText(brief: Boolean, routines: Int): Phrase {
    val count = if (routines == 0) RoutineStrings.noRoutines() else RoutineStrings.routineCount(routines)
    return (if (brief) RoutineStrings.summaryBriefOn else RoutineStrings.summaryBriefOff)("routines" to count)
}

/** [routinesSummary] without a language yet. */
fun routinesSummaryText(ctx: android.content.Context): Phrase = RoutineFiles.store(ctx).let { routinesSummaryText(it.settings().briefEnabled, it.routines().size) }

// ---------------------------------------------------------------------------------------------- Settings › Routines

@Composable
fun RoutinesScreen(d: RoutinesData, s: RobotSettings, facts: AssistantFacts?, act: RoutineActions, a: AppActions, snackbar: SnackbarHostState,
    onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    var briefCard by rememberSaveable { mutableStateOf(false) }
    var startOver by rememberSaveable { mutableStateOf(false) }
    val rs = d.settings
    val langs = LocalLangs.current
    val tBrief = stringResource(R.string.brief_title)
    val tYours = stringResource(R.string.routine_yours)
    val tReady = stringResource(R.string.routine_ready, d.ready.size)
    val tWhenRun = stringResource(R.string.routine_when_run)
    val tNeeds = stringResource(R.string.routine_needs)
    val footer = stringResource(R.string.routine_footer)
    PreferenceScreen(UiStrings.routines().str(), onBack, snackbar = snackbar) {
        val assistant = facts?.active
        if (assistant == false || d.problem != null || !rs.runLocked) {
            preferenceGroup(null, "warnings") {
                if (assistant == false) StatusPreference(stringResource(R.string.routine_not_running), RoutineStrings.needsAssistant().str(), Mark.WARN, onClick = a.openSetup)
                when (d.problem) {
                    RoutineFile.Damaged -> StatusPreference(stringResource(R.string.routine_paused), RoutineStrings.damaged().str(), Mark.WARN, action = {
                        TextButton(onClick = { startOver = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.routine_start_over)) }
                    })
                    is RoutineFile.TooNew -> StatusPreference(stringResource(R.string.routine_paused), RoutineStrings.tooNew().str(), Mark.WARN)
                    else -> {}
                }
                if (!rs.runLocked) StatusPreference(stringResource(R.string.routine_locked_phone), RoutineStrings.notLocked().str(), Mark.TODO)
            }
        }
        preferenceGroup(tBrief, "brief") {
            TwoTargetSwitchPreference(tBrief, RoutineTexts.whenRow(d.brief, langs) + " · " + RoutineTexts.lastRunShort(d.runs[BriefRoutine.ID], now, langs),
                rs.briefEnabled, onClick = { act.open(RoutineRoutes.BRIEF) },
                onCheckedChange = { on -> if (on && !rs.briefOnline) briefCard = true else act.settings(rs.copy(briefEnabled = on)) },
                switchLabel = stringResource(R.string.routine_brief_on), icon = RIcons.Sunny)
        }
        preferenceGroup(tYours, "mine") {
            for (r in d.routines) {
                TwoTargetSwitchPreference(r.name, RoutineTexts.row(r.copy(enabled = true), langs) + "\n" + RoutineTexts.lastRun(d.runs[r.id], now, langs), r.enabled,
                    onClick = { act.open(RoutineRoutes.routine(r.id)) }, onCheckedChange = { act.setEnabled(r.id, it) },
                    switchLabel = stringResource(R.string.routine_on, r.name), enabled = d.problem == null)
            }
            PreferenceItem(stringResource(R.string.routine_add), if (d.routines.isEmpty()) stringResource(R.string.routine_add_hint) else null, RIcons.Add,
                enabled = d.problem == null && d.routines.size < RoutineCheck.MAX_ROUTINES, onClick = { act.open(RoutineRoutes.NEW) })
        }
        if (d.ready.isNotEmpty()) {
            preferenceGroup(tReady, "ready") {
                for (c in d.ready) PreferenceItem(c.line().str(), RoutineStrings.tapToDoItNow().str(), RIcons.TouchApp, onClick = { act.openReady(c.key) })
            }
        }
        preferenceGroup(tWhenRun, "run") {
            SwitchPreference(stringResource(R.string.routine_run_locked), stringResource(if (rs.runLocked) R.string.routine_run_locked_on else R.string.routine_run_locked_off),
                rs.runLocked, icon = RIcons.Lock) { act.settings(rs.copy(runLocked = it)) }
            SwitchPreference(stringResource(R.string.routine_online), stringResource(if (rs.online) R.string.routine_online_on else R.string.routine_online_off),
                rs.online, icon = RIcons.Cloud) { act.settings(rs.copy(online = it)) }
        }
        preferenceGroup(tNeeds, "needs") {
            val f = d.facts
            StatusPreference(stringResource(R.string.routine_assistant), if (assistant == true) stringResource(R.string.routine_assistant_ok) else RoutineStrings.needsAssistant().str(),
                if (assistant == true) Mark.OK else Mark.WARN, onClick = a.openSetup)
            if (f != null) {
                val allow = stringResource(R.string.routine_allow)
                StatusPreference(stringResource(R.string.routine_exact_alarms), stringResource(if (f.exactAlarms) R.string.routine_exact_alarms_ok else R.string.routine_exact_alarms_no),
                    if (f.exactAlarms) Mark.OK else Mark.WARN)
                StatusPreference(stringResource(R.string.routine_notifications), stringResource(if (f.notifications) R.string.routine_notifications_ok else R.string.routine_notifications_no),
                    if (f.notifications) Mark.OK else Mark.TODO,
                    action = if (f.notifications) null else ({ TextButton(onClick = a.askPermissions, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) } }))
                // 1.2: a warning, not a to-do, when a routine that is on (or the brief) waits for an alarm it won't hear.
                StatusPreference(stringResource(R.string.routine_clock), stringResource(if (f.clock) R.string.routine_clock_ok else R.string.routine_clock_no),
                    if (f.clock) Mark.OK else if (ClockNeed.unmet(d.routines, d.settings, f.clock)) Mark.WARN else Mark.TODO)
                StatusPreference(stringResource(R.string.routine_calendar), stringResource(if (f.calendar) R.string.routine_calendar_ok else R.string.routine_calendar_no),
                    if (f.calendar) Mark.OK else Mark.TODO,
                    action = if (f.calendar) null else ({ TextButton(onClick = a.askCalendar, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) } }))
                val weather = f.location || s.weatherHome != null
                StatusPreference(stringResource(R.string.routine_where), stringResource(when {
                    f.location -> if (s.weatherHome != null) R.string.routine_where_location_home else R.string.routine_where_location
                    s.weatherHome != null -> R.string.routine_where_home
                    else -> R.string.routine_where_none
                }), if (weather) Mark.OK else Mark.TODO,
                    action = if (f.location) null else ({ TextButton(onClick = a.askLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) } }))
            }
        }
        preferenceFooter(footer)
    }
    if (briefCard) BriefCardDialog(rs, onDismiss = { briefCard = false }) {
        briefCard = false
        act.settings(rs.copy(briefEnabled = true, briefOnline = true))
    }
    if (startOver) ConfirmDialog(stringResource(R.string.routine_start_over_title), stringResource(R.string.routine_start_over_text),
        stringResource(R.string.routine_start_over), onDismiss = { startOver = false }) { startOver = false; act.startOver() }
}

/** The brief's card, as a routine's: when it runs, what it says, what goes online. Turning it on is the consent. */
@Composable
internal fun BriefCardDialog(rs: RoutineSettings, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val r = BriefRoutine.of(rs.copy(briefEnabled = true, briefOnline = true))
    val card = RoutineCards.cardText(RoutineDraft(r, Lang.EN)).render(LocalLangs.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brief_turn_on_title)) },
        text = { Text((card.lines + stringResource(R.string.brief_card_change)).joinToString("\n"), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.brief_turn_on)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routine_cancel)) } },
    )
}

// ---------------------------------------------------------------------------------------------- the morning brief

private fun itemTitle(i: BriefItem): Int = when (i) {
    BriefItem.GREETING -> R.string.brief_item_greeting
    BriefItem.WEATHER -> R.string.brief_item_weather
    BriefItem.CALENDAR -> R.string.brief_item_calendar
    BriefItem.REMINDERS -> R.string.brief_item_reminders
    BriefItem.MESSAGES -> R.string.brief_item_messages
    BriefItem.CLOSING -> R.string.brief_item_closing
    // 1.4: never listed here (above); Settings › Files' row names it.
    BriefItem.FILES -> R.string.brief_item_reminders
}

private fun lockDetail(l: BriefLock): Int = when (l) {
    BriefLock.TIME_WEATHER -> R.string.brief_lock_time_weather
    BriefLock.EVERYTHING -> R.string.brief_lock_everything
    BriefLock.NOTHING -> R.string.brief_lock_nothing
}

/** 1.1: unset follows Robot's reply language (Settings › Language › "Robot answers in", else the menus'), no longer the phone's. */
private fun langTitle(l: Lang?): Int = when (l) {
    null -> R.string.brief_lang_follow
    Lang.EN -> R.string.lang_name_en
    Lang.FR -> R.string.brief_lang_fr
}

@Composable
fun BriefSettingsScreen(d: RoutinesData, act: RoutineActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val rs = d.settings
    var card by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val tWhen = stringResource(R.string.routine_when)
    val tWhat = stringResource(R.string.brief_what)
    val tPrivacy = stringResource(R.string.brief_privacy)
    val tReminders = stringResource(R.string.brief_item_reminders)
    val use = stringResource(R.string.brief_use)
    val footer = stringResource(R.string.brief_footer)
    PreferenceScreen(stringResource(R.string.brief_title), onBack, snackbar = snackbar) {
        item(key = "main") {
            MainSwitchBar(use, rs.briefEnabled) { on -> if (on && !rs.briefOnline) card = true else act.settings(rs.copy(briefEnabled = on)) }
        }
        preferenceGroup(tWhen, "when") {
            val w = BriefRoutine.When.of(rs.briefTrigger)
            ListPreference(stringResource(R.string.brief_runs), w, BriefRoutine.When.entries, { it.text.render(langs) }, icon = RIcons.Alarm,
                summary = RoutineCards.triggerText(d.brief.trigger).str().replaceFirstChar { it.uppercase() }) {
                act.settings(rs.copy(briefTrigger = BriefRoutine.trigger(it, rs.briefTrigger)))
            }
            when (val t = rs.briefTrigger) {
                is RoutineTrigger.At -> {
                    TimeRow(stringResource(R.string.routine_time), t.time) { act.settings(rs.copy(briefTrigger = t.copy(time = it))) }
                    DaysRow(stringResource(R.string.routine_days), t.days) { act.settings(rs.copy(briefTrigger = t.copy(days = it, once = null))) }
                }
                is RoutineTrigger.Unlock -> TimeRow(stringResource(R.string.routine_after), t.after) { act.settings(rs.copy(briefTrigger = RoutineTrigger.Unlock(it))) }
                is RoutineTrigger.AlarmDismissed -> {
                    ClockNeeded(d, t)
                    TimeRow(stringResource(R.string.routine_alarms_from), rs.alarmWindow.from) {
                        if (it != rs.alarmWindow.to) act.settings(rs.copy(alarmWindow = TimeWindow(it, rs.alarmWindow.to)))
                    }
                    TimeRow(stringResource(R.string.routine_alarms_until), rs.alarmWindow.to) {
                        if (it != rs.alarmWindow.from) act.settings(rs.copy(alarmWindow = TimeWindow(rs.alarmWindow.from, it)))
                    }
                    val silenceDefault = stringResource(R.string.brief_silences_default)
                    ListPreference(stringResource(R.string.brief_silences), rs.clockSilenceMinutes, RoutineSettingsCodec.SILENCE_CHOICES,
                        { ctx.getString(R.string.brief_minutes, it) }, { if (it == 10) silenceDefault else null }, RIcons.Timer) {
                        act.settings(rs.copy(clockSilenceMinutes = it))
                    }
                }
                else -> {}
            }
        }
        preferenceGroup(tWhat, "items") {
            // 1.4: Robot Files' part is Settings › Files' "Show in morning brief", not a switch here.
            for (i in BriefItem.DEFAULTS) {
                val on = i in rs.briefItems
                // 0.8: how many messages, by app, from Settings › Messages (never who or what); nothing while it is off.
                val detail = if (i == BriefItem.MESSAGES) stringResource(R.string.brief_messages_detail) else null
                SwitchPreference(stringResource(itemTitle(i)), detail, on) {
                    act.settings(rs.copy(briefItems = BriefItem.DEFAULTS.filter { e -> if (e == i) it else e in rs.briefItems }))
                }
            }
        }
        preferenceGroup(tPrivacy, "privacy") {
            ListPreference(stringResource(R.string.brief_lock), rs.briefLock, BriefLock.entries, { it.text.render(langs) }, { ctx.getString(lockDetail(it)) },
                RIcons.Lock) { act.settings(rs.copy(briefLock = it)) }
            SwitchPreference(stringResource(R.string.brief_finish), stringResource(R.string.brief_finish_detail), rs.finishOnUnlock) {
                act.settings(rs.copy(finishOnUnlock = it))
            }
            ListPreference(stringResource(R.string.routine_speak), rs.briefOutput, RoutineOutput.entries, { it.text.render(langs) }, icon = RIcons.VolumeUp) {
                act.settings(rs.copy(briefOutput = it))
            }
            val followDetail = stringResource(R.string.brief_lang_follow_detail)
            ListPreference(stringResource(R.string.brief_language), rs.briefLang, listOf(null, Lang.EN, Lang.FR), { ctx.getString(langTitle(it)) },
                { if (it == null) followDetail else null }, RIcons.RecordVoiceOver) { act.settings(rs.copy(briefLang = it)) }
            SwitchPreference(stringResource(R.string.brief_prepare), stringResource(R.string.brief_prepare_detail), rs.briefPrepare) {
                act.settings(rs.copy(briefPrepare = it))
            }
            PreferenceItem(stringResource(R.string.brief_hear_now), stringResource(R.string.brief_hear_now_detail), RIcons.Robot,
                onClick = { act.runNow(BriefRoutine.ID) })
        }
        val reminders = d.reminders.filter { it.readAt == null }
        preferenceGroup(tReminders, "reminders") {
            if (reminders.isEmpty()) PreferenceItem(stringResource(R.string.brief_none_waiting), stringResource(R.string.brief_none_hint), RIcons.Note)
            for (r in reminders) {
                PreferenceItem(r.text, Fmt.day(r.day, langs.say), RIcons.Note, trailing = {
                    IconButton(onClick = { act.removeReminder(r) }) { Icon(RIcons.Delete, stringResource(R.string.brief_remove, r.text)) }
                })
            }
        }
        preferenceFooter(footer)
    }
    if (card) BriefCardDialog(rs, onDismiss = { card = false }) {
        card = false
        act.settings(rs.copy(briefEnabled = true, briefOnline = true))
    }
}

/**
 * 1.2 (every-android-phone design, Step 6): a start after an alarm on a phone without GrapheneOS Clock says so where you
 * pick it, instead of never starting (core ClockNeed). Nothing shows where Clock is installed, or before the facts are read.
 */
@Composable
private fun ClockNeeded(d: RoutinesData, t: RoutineTrigger) {
    if (d.facts != null && ClockNeed.unmet(t, d.facts.clock)) StatusPreference(stringResource(R.string.routine_alarm_needs_clock), null, Mark.WARN)
}

// ---------------------------------------------------------------------------------------------- a routine's page (the editor)

/** The editor's triggers, one row each in its radio dialog. */
private enum class StartKind(val title: Int) {
    TIME(R.string.routine_start_time), ALARM(R.string.routine_start_alarm), UNLOCK(R.string.routine_start_unlock),
    HEADSET_ON(R.string.routine_start_headset_on), HEADSET_OFF(R.string.routine_start_headset_off), CAR_ON(R.string.routine_start_car_on),
    CAR_OFF(R.string.routine_start_car_off), CHARGING_ON(R.string.routine_start_charging_on), CHARGING_OFF(R.string.routine_start_charging_off),
    ASKED(R.string.routine_start_asked);

    companion object {
        fun of(t: RoutineTrigger): StartKind = when (t) {
            is RoutineTrigger.At -> TIME
            is RoutineTrigger.AlarmDismissed -> ALARM
            is RoutineTrigger.Unlock -> UNLOCK
            is RoutineTrigger.Device -> when {
                t.type == DeviceKind.HEADSET && t.connect -> HEADSET_ON
                t.type == DeviceKind.HEADSET -> HEADSET_OFF
                t.connect -> CAR_ON
                else -> CAR_OFF
            }
            is RoutineTrigger.Charging -> if (t.start) CHARGING_ON else CHARGING_OFF
            RoutineTrigger.Asked -> ASKED
        }
    }

    fun trigger(window: TimeWindow): RoutineTrigger = when (this) {
        TIME -> RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS)
        ALARM -> RoutineTrigger.AlarmDismissed(window)
        UNLOCK -> RoutineTrigger.Unlock()
        HEADSET_ON -> RoutineTrigger.Device(DeviceKind.HEADSET, null, true)
        HEADSET_OFF -> RoutineTrigger.Device(DeviceKind.HEADSET, null, false)
        CAR_ON -> RoutineTrigger.Device(DeviceKind.CAR, null, true)
        CAR_OFF -> RoutineTrigger.Device(DeviceKind.CAR, null, false)
        CHARGING_ON -> RoutineTrigger.Charging(true)
        CHARGING_OFF -> RoutineTrigger.Charging(false)
        ASKED -> RoutineTrigger.Asked
    }
}

private fun lockTitle(l: RoutineLock): Int = when (l) {
    RoutineLock.FOLLOW -> R.string.routine_lock_follow
    RoutineLock.WAIT_FOR_UNLOCK -> R.string.routine_lock_wait
}

@Composable
fun RoutineEditScreen(initial: Routine, isNew: Boolean, d: RoutinesData, act: RoutineActions, snackbar: SnackbarHostState, onBack: () -> Unit,
    now: ZonedDateTime = ZonedDateTime.now()) {
    var json by rememberSaveable(initial.id) { mutableStateOf(RoutineDraftCodec.encode(initial)) }
    val r = RoutineDraftCodec.decode(json) ?: initial
    fun edit(next: Routine) { json = RoutineDraftCodec.encode(next) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var arg by rememberSaveable { mutableStateOf<String?>(null) }
    val changed = r != initial
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val tWhen = stringResource(R.string.routine_when)
    val tDo = stringResource(R.string.routine_do)
    val tSpeak = stringResource(R.string.routine_speak_group)
    val save = stringResource(R.string.routine_save)
    val problems = RoutineCheck.problemTexts(r).map { it.render(langs) }
    val footer = stringResource(R.string.routine_edit_footer)
    PreferenceScreen(if (isNew) stringResource(R.string.routine_add) else initial.name, onBack, snackbar = snackbar, actions = {
        TextButton(onClick = { saving = true }, enabled = isNew || changed, modifier = Modifier.heightIn(min = 48.dp)) { Text(save) }
    }) {
        preferenceGroup(null, "name") {
            val noName = RoutineStrings.noName().str()
            val atMost = stringResource(R.string.routine_at_most, RoutineCheck.MAX_NAME)
            val nameLabel = stringResource(R.string.routine_name)
            TextPreference(nameLabel, r.name, stringResource(R.string.routine_name_empty), nameLabel, stringResource(R.string.routine_name_hint, RoutineCheck.MAX_NAME),
                RIcons.Bookmark, validate = { if (it.isBlank()) noName else if (it.length > RoutineCheck.MAX_NAME) atMost else null },
            ) { edit(r.copy(name = it)) }
            if (!isNew) SwitchPreference(stringResource(R.string.routine_on_title), stringResource(if (r.enabled) R.string.routine_on_detail else R.string.routine_off_detail),
                r.enabled) { edit(r.copy(enabled = it)) }
        }
        preferenceGroup(tWhen, "when") {
            val kind = StartKind.of(r.trigger)
            ListPreference(stringResource(R.string.routine_starts), kind, StartKind.entries, { ctx.getString(it.title) }, icon = RIcons.Alarm,
                summary = RoutineCards.triggerText(r.trigger).str().replaceFirstChar { it.uppercase() }) { k ->
                if (k != kind) edit(r.copy(trigger = k.trigger(d.settings.alarmWindow), conditions = RoutineConditions()))
            }
            when (val t = r.trigger) {
                is RoutineTrigger.At -> {
                    TimeRow(stringResource(R.string.routine_time), t.time) { edit(r.copy(trigger = t.copy(time = it))) }
                    val once = t.once
                    if (once != null) PreferenceItem(stringResource(R.string.routine_date), Fmt.day(once, langs.say), RIcons.Event)
                    else DaysRow(stringResource(R.string.routine_days), t.days) { edit(r.copy(trigger = t.copy(days = it))) }
                }
                is RoutineTrigger.AlarmDismissed -> {
                    ClockNeeded(d, t)
                    TimeRow(stringResource(R.string.routine_alarms_from), t.window.from) {
                        if (it != t.window.to) edit(r.copy(trigger = RoutineTrigger.AlarmDismissed(TimeWindow(it, t.window.to))))
                    }
                    TimeRow(stringResource(R.string.routine_alarms_until), t.window.to) {
                        if (it != t.window.from) edit(r.copy(trigger = RoutineTrigger.AlarmDismissed(TimeWindow(t.window.from, it))))
                    }
                }
                is RoutineTrigger.Unlock -> TimeRow(stringResource(R.string.routine_after), t.after) { edit(r.copy(trigger = RoutineTrigger.Unlock(it))) }
                is RoutineTrigger.Device -> {
                    val list = d.devices[t.type].orEmpty()
                    val any = stringResource(if (t.type == DeviceKind.HEADSET) R.string.routine_any_headphones else R.string.routine_any_car)
                    val none = stringResource(R.string.routine_which_none)
                    ListPreference(stringResource(R.string.routine_which), t.deviceId, listOf<String?>(null) + list.map { it.first },
                        { id -> list.firstOrNull { it.first == id }?.second ?: any }, { if (it == null && list.isEmpty()) none else null },
                        RIcons.VolumeUp) { edit(r.copy(trigger = t.copy(deviceId = it))) }
                }
                else -> {}
            }
            if (r.trigger !is RoutineTrigger.At && r.trigger != RoutineTrigger.Asked) {
                DaysRow(stringResource(R.string.routine_only_days), r.conditions.days ?: RoutineWords.ALL) {
                    edit(r.copy(conditions = r.conditions.copy(days = it.takeIf { d -> d != RoutineWords.ALL })))
                }
                SwitchPreference(stringResource(R.string.routine_once_a_day), stringResource(R.string.routine_once_a_day_detail), r.conditions.oncePerDay) {
                    edit(r.copy(conditions = r.conditions.copy(oncePerDay = it)))
                }
            }
        }
        preferenceGroup(tDo, "steps") {
            r.steps.forEachIndexed { i, step ->
                PreferenceItem("${i + 1}. ${step.summary().str()}", RoutineCards.badgeText(step).str(), trailing = {
                    Row {
                        if (i > 0) IconButton(onClick = { edit(r.copy(steps = r.steps.toMutableList().apply { add(i - 1, removeAt(i)) })) }) {
                            Icon(RIcons.Back, stringResource(R.string.routine_move_up, i + 1), Modifier.rotate(90f))
                        }
                        IconButton(onClick = { edit(r.copy(steps = r.steps.filterIndexed { j, _ -> j != i })) }) {
                            Icon(RIcons.Delete, stringResource(R.string.routine_remove_step, i + 1))
                        }
                    }
                })
            }
            PreferenceItem(stringResource(R.string.routine_add_step), if (r.steps.isEmpty()) stringResource(R.string.routine_add_step_hint) else null, RIcons.Add,
                enabled = r.steps.size < RoutineCheck.MAX_STEPS, onClick = { adding = true })
        }
        preferenceGroup(tSpeak, "output") {
            ListPreference(stringResource(R.string.routine_speak), r.output, RoutineOutput.entries, { it.text.render(langs) }, icon = RIcons.VolumeUp) {
                edit(r.copy(output = it))
            }
            ListPreference(stringResource(R.string.routine_locked_phone), r.lock, RoutineLock.entries, { ctx.getString(lockTitle(it)) }, icon = RIcons.Lock) {
                edit(r.copy(lock = it))
            }
        }
        if (!isNew) {
            preferenceGroup(null, "run") {
                PreferenceItem(stringResource(R.string.routine_last_run), RoutineTexts.lastRunShort(d.runs[r.id], now, langs), RIcons.History)
                PreferenceItem(stringResource(R.string.routine_run_now), stringResource(R.string.routine_run_now_detail), RIcons.Robot, enabled = !changed,
                    onClick = { act.runNow(r.id) })
                PreferenceItem(stringResource(R.string.routine_delete), null, RIcons.Delete, onClick = { deleting = true })
            }
        }
        preferenceFooter((if (problems.isEmpty()) "" else problems.joinToString(" ") + "\n\n") + footer)
    }
    if (saving) {
        val now0 = System.currentTimeMillis()
        val toSave = r.copy(online = r.needsOnline, created = if (r.created == 0L) now0 else r.created, edited = now0)
        if (problems.isNotEmpty()) {
            ConfirmDialog(stringResource(R.string.routine_cant_save), problems.joinToString("\n"), stringResource(R.string.routine_ok), onDismiss = { saving = false }) {
                saving = false
            }
        } else {
            val card = RoutineCards.cardText(RoutineDraft(toSave, Lang.EN)).render(langs)
            AlertDialog(
                onDismissRequest = { saving = false },
                title = { Text(card.title) },
                text = { Text(card.lines.joinToString("\n"), Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { saving = false; if (act.save(toSave)) onBack() }) { Text(save) } },
                dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(R.string.routine_cancel)) } },
            )
        }
    }
    if (deleting) ConfirmDialog(stringResource(R.string.routine_delete_title, initial.name), stringResource(R.string.routine_delete_text),
        stringResource(R.string.routine_delete), onDismiss = { deleting = false }) {
        deleting = false
        act.delete(initial.id)
        onBack()
    }
    if (adding) RadioDialog<StepChoice?>(stringResource(R.string.routine_add_step), null, StepChoice.entries, { it?.titleText?.render(langs).orEmpty() },
        { it?.let { c -> RoutineCards.badgeText(c.sample()).render(langs) } }, onDismiss = { adding = false }) { c ->
        adding = false
        if (c == null) return@RadioDialog
        if (c.field == null) c.build()?.let { edit(r.copy(steps = r.steps + it)) } else arg = c.name
    }
    arg?.let { name ->
        val c = StepChoice.valueOf(name)
        EditDialog(c.titleText.str(), "", c.fieldText?.str() ?: "", c.hintText?.str() ?: "", validate = { if (it.isBlank()) null else c.problem(it, langs) },
            confirm = stringResource(R.string.routine_add_button),
            keyboard = if (c == StepChoice.TIMER) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            canSave = { it.isNotBlank() }, onDismiss = { arg = null }) { text ->
            arg = null
            c.build(text)?.let { edit(r.copy(steps = r.steps + it)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- rows

/** A time on a 24-hour clock, edited as "07:30". */
@Composable
private fun TimeRow(title: String, t: LocalTime, onSave: (LocalTime) -> Unit) {
    val problem = stringResource(R.string.routine_time_problem)
    TextPreference(title, RoutineDraftCodec.hm(t), "", title, stringResource(R.string.routine_time_hint), RIcons.Timer,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        validate = { if (RoutineDraftCodec.time(it) == null) problem else null }) { text -> RoutineDraftCodec.time(text)?.let(onSave) }
}

/** "Every weekday", "Monday, Wednesday and Friday": a dialog of the seven days, at least one. */
@Composable
private fun DaysRow(title: String, days: Set<DayOfWeek>, onSave: (Set<DayOfWeek>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceItem(title, RoutineCards.daysText(days).str().replaceFirstChar { it.uppercase() }, RIcons.Event, onClick = { open = true })
    if (open) DaysDialog(title, days, onDismiss = { open = false }) { open = false; onSave(it) }
}

@Composable
private fun DaysDialog(title: String, days: Set<DayOfWeek>, onDismiss: () -> Unit, onSave: (Set<DayOfWeek>) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(days.map { it.value }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                for (day in DayOfWeek.entries) {
                    val on = day.value in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = on, role = Role.Checkbox) { picked = if (it) (picked + day.value).sorted() else picked - day.value }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                        Text(RoutineCards.dayName(day).str().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked.map { DayOfWeek.of(it) }.toSet()) }, enabled = picked.isNotEmpty()) {
            Text(stringResource(R.string.routine_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routine_cancel)) } },
    )
}
