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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.core.BriefClock
import app.heyrobot.core.BriefItem
import app.heyrobot.core.BriefLang
import app.heyrobot.core.BriefLock
import app.heyrobot.core.BriefReminder
import app.heyrobot.core.BriefRoutine
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
    fun routine(id: String) = "routines/$id"
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

/** "Morning brief off · 2 routines", "Morning brief on · no routines". */
fun routinesSummary(brief: Boolean, routines: Int): String =
    "Morning brief " + (if (brief) "on" else "off") + " · " + when (routines) { 0 -> "no routines"; 1 -> "1 routine"; else -> "$routines routines" }

/** Settings' row for Routines, from the two small files it needs. */
fun routinesSummary(ctx: android.content.Context): String = RoutineFiles.store(ctx).let { routinesSummary(it.settings().briefEnabled, it.routines().size) }

// ---------------------------------------------------------------------------------------------- Settings › Routines

@Composable
fun RoutinesScreen(d: RoutinesData, s: RobotSettings, facts: AssistantFacts?, act: RoutineActions, a: AppActions, snackbar: SnackbarHostState,
    onBack: () -> Unit, now: ZonedDateTime = ZonedDateTime.now()) {
    var briefCard by rememberSaveable { mutableStateOf(false) }
    var startOver by rememberSaveable { mutableStateOf(false) }
    val rs = d.settings
    PreferenceScreen("Routines", onBack, snackbar = snackbar) {
        val assistant = facts?.active
        if (assistant == false || d.problem != null || !rs.runLocked) {
            preferenceGroup(null, "warnings") {
                if (assistant == false) StatusPreference("Not running", RoutineTexts.NEEDS_ASSISTANT, Mark.WARN, onClick = a.openSetup)
                when (d.problem) {
                    RoutineFile.Damaged -> StatusPreference("Routines paused", RoutineTexts.DAMAGED, Mark.WARN, action = {
                        TextButton(onClick = { startOver = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start over") }
                    })
                    is RoutineFile.TooNew -> StatusPreference("Routines paused", RoutineTexts.TOO_NEW, Mark.WARN)
                    else -> {}
                }
                if (!rs.runLocked) StatusPreference("Locked phone", RoutineTexts.NOT_LOCKED, Mark.TODO)
            }
        }
        preferenceGroup("Morning brief", "brief") {
            TwoTargetSwitchPreference("Morning brief", RoutineTexts.row(d.brief).substringBefore(" · ") + " · " + RoutineTexts.lastRun(d.runs[BriefRoutine.ID], now)
                .removePrefix("Last run: "), rs.briefEnabled, onClick = { act.open(RoutineRoutes.BRIEF) },
                onCheckedChange = { on -> if (on && !rs.briefOnline) briefCard = true else act.settings(rs.copy(briefEnabled = on)) },
                switchLabel = "Morning brief on", icon = RIcons.Sunny)
        }
        preferenceGroup("Your routines", "mine") {
            for (r in d.routines) {
                TwoTargetSwitchPreference(r.name, RoutineTexts.row(r).removeSuffix(" · off") + "\n" + RoutineTexts.lastRun(d.runs[r.id], now), r.enabled,
                    onClick = { act.open(RoutineRoutes.routine(r.id)) }, onCheckedChange = { act.setEnabled(r.id, it) }, switchLabel = "${r.name} on",
                    enabled = d.problem == null)
            }
            PreferenceItem("Add a routine", if (d.routines.isEmpty()) "Or say: “every weekday at 7, tell me the weather and my calendar”" else null, RIcons.Add,
                enabled = d.problem == null && d.routines.size < RoutineCheck.MAX_ROUTINES, onClick = { act.open(RoutineRoutes.NEW) })
        }
        if (d.ready.isNotEmpty()) {
            preferenceGroup("Ready (${d.ready.size})", "ready") {
                for (c in d.ready) PreferenceItem(c.text().substringBefore(" Tap to"), "Tap to do it now", RIcons.TouchApp, onClick = { act.openReady(c.key) })
            }
        }
        preferenceGroup("When routines run", "run") {
            SwitchPreference("Routines can run while the phone is locked", if (rs.runLocked) "Only the lock screen's actions and the brief's lock level" else
                "They wait for the unlock", rs.runLocked, icon = RIcons.Lock) { act.settings(rs.copy(runLocked = it)) }
            SwitchPreference("Routines may go online by themselves", if (rs.online) "Only the services each routine's card listed" else
                "Weather skipped: routines may not go online", rs.online, icon = RIcons.Cloud) { act.settings(rs.copy(online = it)) }
        }
        preferenceGroup("What routines need", "needs") {
            val f = d.facts
            StatusPreference("Robot as the assistant", if (assistant == true) "Robot is your assistant: triggers are heard" else RoutineTexts.NEEDS_ASSISTANT,
                if (assistant == true) Mark.OK else Mark.WARN, onClick = a.openSetup)
            if (f != null) {
                StatusPreference("Exact alarms", if (f.exactAlarms) "Allowed: routines at a time run on time" else "Not allowed: a routine at a time may come late",
                    if (f.exactAlarms) Mark.OK else Mark.WARN)
                StatusPreference("Notifications", if (f.notifications) "Allowed: Ready cards and the speaking notification show" else
                    "Not allowed: you won't see what waits for you", if (f.notifications) Mark.OK else Mark.TODO,
                    action = if (f.notifications) null else ({ TextButton(onClick = a.askPermissions, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") } }))
                StatusPreference("Clock's alarms", if (f.clock) "GrapheneOS Clock is installed: Robot hears when you turn off an alarm" else
                    "GrapheneOS Clock isn't installed: “When I turn off my alarm” can't start a routine", if (f.clock) Mark.OK else Mark.TODO)
                StatusPreference("Calendar", if (f.calendar) "Allowed: the brief reads today's events" else "Not allowed: the brief can't read your day",
                    if (f.calendar) Mark.OK else Mark.TODO,
                    action = if (f.calendar) null else ({ TextButton(onClick = a.askCalendar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") } }))
                val weather = f.location || s.weatherHome != null
                StatusPreference("Where for the weather", when {
                    f.location -> "Approximate location allowed" + if (s.weatherHome != null) ", else your Home for weather" else ""
                    s.weatherHome != null -> "Your Home for weather (location not allowed)"
                    else -> "No location and no Home for weather: the brief can't tell the weather"
                }, if (weather) Mark.OK else Mark.TODO,
                    action = if (f.location) null else ({ TextButton(onClick = a.askLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") } }))
            }
        }
        preferenceFooter(
            "Routines are fixed steps you approve on a card when you save them; no model ever picks what a routine does, so " +
                "something it reads can't make it act. By themselves they read, set timers and alarms, turn the flashlight on or off and " +
                "save a fixed note; opening an app, a draft, maps or a new event waits for you as a Ready card. Freeing memory or " +
                "storage and asking a helper are never offered.\n\nOn the lock screen the brief says the time and the weather without " +
                "your town, then “Unlock to hear your day”; the rest follows if you unlock within 15 minutes. Routines go online " +
                "only to Open-Meteo for the weather, and only when their card listed it. They are kept on this phone, never logged " +
                "and never sent to a helper.",
        )
    }
    if (briefCard) BriefCardDialog(rs, onDismiss = { briefCard = false }) {
        briefCard = false
        act.settings(rs.copy(briefEnabled = true, briefOnline = true))
    }
    if (startOver) ConfirmDialog("Start over?", "Robot keeps the file it can't read as routines.json.bad and starts with no routines.", "Start over",
        onDismiss = { startOver = false }) { startOver = false; act.startOver() }
}

/** The brief's card, as a routine's: when it runs, what it says, what goes online. Turning it on is the consent. */
@Composable
private fun BriefCardDialog(rs: RoutineSettings, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val r = BriefRoutine.of(rs.copy(briefEnabled = true, briefOnline = true))
    val card = RoutineCards.card(RoutineDraft(r, BriefLang.EN))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on the morning brief?") },
        text = { Text((card.lines + "You can change when it runs and what it says on its page.").joinToString("\n"), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------- the morning brief

private fun itemTitle(i: BriefItem): String = when (i) {
    BriefItem.GREETING -> "Greeting, time and date"
    BriefItem.WEATHER -> "Weather"
    BriefItem.CALENDAR -> "Today's calendar"
    BriefItem.REMINDERS -> "Brief reminders"
    BriefItem.MESSAGES -> "Unread messages"
    BriefItem.CLOSING -> "Closing"
}

private fun lockDetail(l: BriefLock): String = when (l) {
    BriefLock.TIME_WEATHER -> "The default. The weather without your town, then “Unlock to hear your day”"
    BriefLock.EVERYTHING -> "Your calendar and reminders too, to anyone near the phone"
    BriefLock.NOTHING -> "Nothing is said until you unlock"
}

private fun langTitle(l: BriefLang?): String = when (l) {
    null -> "Follow the phone"
    BriefLang.EN -> "English"
    BriefLang.FR -> "Français (Québec)"
}

@Composable
fun BriefSettingsScreen(d: RoutinesData, act: RoutineActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val rs = d.settings
    var card by rememberSaveable { mutableStateOf(false) }
    PreferenceScreen("Morning brief", onBack, snackbar = snackbar) {
        item(key = "main") {
            MainSwitchBar("Use the morning brief", rs.briefEnabled) { on -> if (on && !rs.briefOnline) card = true else act.settings(rs.copy(briefEnabled = on)) }
        }
        preferenceGroup("When", "when") {
            val w = BriefRoutine.When.of(rs.briefTrigger)
            ListPreference("Runs", w, BriefRoutine.When.entries, { it.title }, icon = RIcons.Alarm, summary = RoutineCards.trigger(d.brief.trigger)
                .replaceFirstChar { it.uppercase() }) { act.settings(rs.copy(briefTrigger = BriefRoutine.trigger(it, rs.briefTrigger))) }
            when (val t = rs.briefTrigger) {
                is RoutineTrigger.At -> {
                    TimeRow("Time", t.time) { act.settings(rs.copy(briefTrigger = t.copy(time = it))) }
                    DaysRow("Days", t.days) { act.settings(rs.copy(briefTrigger = t.copy(days = it, once = null))) }
                }
                is RoutineTrigger.Unlock -> TimeRow("After", t.after) { act.settings(rs.copy(briefTrigger = RoutineTrigger.Unlock(it))) }
                is RoutineTrigger.AlarmDismissed -> {
                    TimeRow("Alarms from", rs.alarmWindow.from) { if (it != rs.alarmWindow.to) act.settings(rs.copy(alarmWindow = TimeWindow(it, rs.alarmWindow.to))) }
                    TimeRow("Alarms until", rs.alarmWindow.to) { if (it != rs.alarmWindow.from) act.settings(rs.copy(alarmWindow = TimeWindow(rs.alarmWindow.from, it))) }
                    ListPreference("Clock silences after", rs.clockSilenceMinutes, RoutineSettingsCodec.SILENCE_CHOICES, { "$it min" },
                        { if (it == 10) "Clock's default. Set as in Clock › Settings › Silence after" else null }, RIcons.Timer) {
                        act.settings(rs.copy(clockSilenceMinutes = it))
                    }
                }
                else -> {}
            }
        }
        preferenceGroup("What it says", "items") {
            for (i in BriefItem.entries) {
                val on = i in rs.briefItems
                val soon = i == BriefItem.MESSAGES
                SwitchPreference(itemTitle(i), if (soon) "Comes with messages (0.7)" else null, on && !soon, enabled = !soon) {
                    act.settings(rs.copy(briefItems = BriefItem.entries.filter { e -> if (e == i) it else e in rs.briefItems }))
                }
            }
        }
        preferenceGroup("Privacy and voice", "privacy") {
            ListPreference("On the lock screen", rs.briefLock, BriefLock.entries, { it.title }, ::lockDetail, RIcons.Lock) { act.settings(rs.copy(briefLock = it)) }
            SwitchPreference("Finish when I unlock", "The rest within 15 minutes of the unlock", rs.finishOnUnlock) { act.settings(rs.copy(finishOnUnlock = it)) }
            ListPreference("Speak", rs.briefOutput, RoutineOutput.entries, { it.title }, icon = RIcons.VolumeUp) { act.settings(rs.copy(briefOutput = it)) }
            ListPreference("Language", rs.briefLang, listOf(null, BriefLang.EN, BriefLang.FR), ::langTitle, icon = RIcons.RecordVoiceOver) { act.settings(rs.copy(briefLang = it)) }
            SwitchPreference("Get ready while the alarm rings", "The weather and the voice load while it rings", rs.briefPrepare) {
                act.settings(rs.copy(briefPrepare = it))
            }
            PreferenceItem("Hear it now", "Opens Robot and reads it, as if you had asked", RIcons.Robot, onClick = { act.runNow(BriefRoutine.ID) })
        }
        val reminders = d.reminders.filter { it.readAt == null }
        preferenceGroup("Brief reminders", "reminders") {
            if (reminders.isEmpty()) PreferenceItem("None waiting", "Say “remind me in the morning to bring the passport”", RIcons.Note)
            for (r in reminders) {
                PreferenceItem(r.text, BriefClock.dateEn(r.day), RIcons.Note, trailing = {
                    IconButton(onClick = { act.removeReminder(r) }) { Icon(RIcons.Delete, "Remove “${r.text}”") }
                })
            }
        }
        preferenceFooter(
            "Fixed sentences, no model: the time and date, the weather from Open-Meteo where the phone is (else your Home for " +
                "weather), today's events (how many, and the first five), and your brief reminders, about a minute at most. Only " +
                "the weather goes online: the area around you, rounded, to Open-Meteo; nothing about you. While it speaks, say " +
                "“hey robot, next”, “repeat” or “stop”, or use its notification.",
        )
    }
    if (card) BriefCardDialog(rs, onDismiss = { card = false }) {
        card = false
        act.settings(rs.copy(briefEnabled = true, briefOnline = true))
    }
}

// ---------------------------------------------------------------------------------------------- a routine's page (the editor)

/** The editor's triggers, one row each in its radio dialog. */
private enum class StartKind(val title: String) {
    TIME("At a time"), ALARM("When I turn off an alarm"), UNLOCK("At the first unlock after a time"), HEADSET_ON("When headphones connect"),
    HEADSET_OFF("When headphones disconnect"), CAR_ON("When the car connects"), CAR_OFF("When the car disconnects"), CHARGING_ON("When charging starts"),
    CHARGING_OFF("When charging stops"), ASKED("Only when I ask");

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

private fun lockTitle(l: RoutineLock): String = when (l) {
    RoutineLock.FOLLOW -> "Follow Settings › Routines"
    RoutineLock.WAIT_FOR_UNLOCK -> "Wait for the unlock"
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
    PreferenceScreen(if (isNew) "Add a routine" else initial.name, onBack, snackbar = snackbar, actions = {
        TextButton(onClick = { saving = true }, enabled = isNew || changed, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
    }) {
        preferenceGroup(null, "name") {
            TextPreference("Name", r.name, "Give it a name", "Name", "Up to ${RoutineCheck.MAX_NAME} characters, such as Leaving work.", RIcons.Bookmark,
                validate = { if (it.isBlank()) "Give the routine a name." else if (it.length > RoutineCheck.MAX_NAME) "At most ${RoutineCheck.MAX_NAME} characters." else null },
            ) { edit(r.copy(name = it)) }
            if (!isNew) SwitchPreference("On", if (r.enabled) "Runs when its trigger comes" else "Off: it never runs by itself", r.enabled) { edit(r.copy(enabled = it)) }
        }
        preferenceGroup("When", "when") {
            val kind = StartKind.of(r.trigger)
            ListPreference("Starts", kind, StartKind.entries, { it.title }, icon = RIcons.Alarm,
                summary = RoutineCards.trigger(r.trigger).replaceFirstChar { it.uppercase() }) { k ->
                if (k != kind) edit(r.copy(trigger = k.trigger(d.settings.alarmWindow), conditions = RoutineConditions()))
            }
            when (val t = r.trigger) {
                is RoutineTrigger.At -> {
                    TimeRow("Time", t.time) { edit(r.copy(trigger = t.copy(time = it))) }
                    val once = t.once
                    if (once != null) PreferenceItem("Date", BriefClock.dateEn(once), RIcons.Event)
                    else DaysRow("Days", t.days) { edit(r.copy(trigger = t.copy(days = it))) }
                }
                is RoutineTrigger.AlarmDismissed -> {
                    TimeRow("Alarms from", t.window.from) { if (it != t.window.to) edit(r.copy(trigger = RoutineTrigger.AlarmDismissed(TimeWindow(it, t.window.to)))) }
                    TimeRow("Alarms until", t.window.to) { if (it != t.window.from) edit(r.copy(trigger = RoutineTrigger.AlarmDismissed(TimeWindow(t.window.from, it)))) }
                }
                is RoutineTrigger.Unlock -> TimeRow("After", t.after) { edit(r.copy(trigger = RoutineTrigger.Unlock(it))) }
                is RoutineTrigger.Device -> {
                    val list = d.devices[t.type].orEmpty()
                    val any = if (t.type == DeviceKind.HEADSET) "Any headphones Robot remembers" else "Any car Robot remembers"
                    ListPreference("Which", t.deviceId, listOf<String?>(null) + list.map { it.first }, { id -> list.firstOrNull { it.first == id }?.second ?: any },
                        { if (it == null && list.isEmpty()) "Robot remembers headphones and cars with headset mode; until then this routine waits." else null },
                        RIcons.VolumeUp) { edit(r.copy(trigger = t.copy(deviceId = it))) }
                }
                else -> {}
            }
            if (r.trigger !is RoutineTrigger.At && r.trigger != RoutineTrigger.Asked) {
                DaysRow("Only on these days", r.conditions.days ?: RoutineWords.ALL) { edit(r.copy(conditions = r.conditions.copy(days = it.takeIf { d -> d != RoutineWords.ALL }))) }
                SwitchPreference("At most once a day", "A trigger that comes again the same day is ignored", r.conditions.oncePerDay) {
                    edit(r.copy(conditions = r.conditions.copy(oncePerDay = it)))
                }
            }
        }
        preferenceGroup("Do", "steps") {
            r.steps.forEachIndexed { i, step ->
                PreferenceItem("${i + 1}. ${step.describe()}", StepChoice.badge(step), trailing = {
                    Row {
                        if (i > 0) IconButton(onClick = { edit(r.copy(steps = r.steps.toMutableList().apply { add(i - 1, removeAt(i)) })) }) {
                            Icon(RIcons.Back, "Move step ${i + 1} up", Modifier.rotate(90f))
                        }
                        IconButton(onClick = { edit(r.copy(steps = r.steps.filterIndexed { j, _ -> j != i })) }) { Icon(RIcons.Delete, "Remove step ${i + 1}") }
                    }
                })
            }
            PreferenceItem("Add a step", if (r.steps.isEmpty()) "Read the brief, say a sentence, set a timer…" else null, RIcons.Add,
                enabled = r.steps.size < RoutineCheck.MAX_STEPS, onClick = { adding = true })
        }
        preferenceGroup("Speak and the lock screen", "output") {
            ListPreference("Speak", r.output, RoutineOutput.entries, { it.title }, icon = RIcons.VolumeUp) { edit(r.copy(output = it)) }
            ListPreference("Locked phone", r.lock, RoutineLock.entries, ::lockTitle, icon = RIcons.Lock) { edit(r.copy(lock = it)) }
        }
        if (!isNew) {
            preferenceGroup(null, "run") {
                PreferenceItem("Last run", RoutineTexts.lastRun(d.runs[r.id], now).removePrefix("Last run: "), RIcons.History)
                PreferenceItem("Run now", "Opens Robot and runs it, as if you had asked", RIcons.Robot, enabled = !changed, onClick = { act.runNow(r.id) })
                PreferenceItem("Delete", null, RIcons.Delete, onClick = { deleting = true })
            }
        }
        val problems = RoutineCheck.problems(r)
        preferenceFooter(
            (if (problems.isEmpty()) "" else problems.joinToString(" ") + "\n\n") +
                "Each step's words are fixed when you save; a routine never asks a model what to do. Save shows the routine's card " +
                "first: what starts it, what each step does by itself, where it speaks and what goes online.",
        )
    }
    if (saving) {
        val problems = RoutineCheck.problems(r)
        val now0 = System.currentTimeMillis()
        val toSave = r.copy(online = r.needsOnline, created = if (r.created == 0L) now0 else r.created, edited = now0)
        if (problems.isNotEmpty()) {
            ConfirmDialog("Can't save yet", problems.joinToString("\n"), "OK", onDismiss = { saving = false }) { saving = false }
        } else {
            val card = RoutineCards.card(RoutineDraft(toSave, BriefLang.EN))
            AlertDialog(
                onDismissRequest = { saving = false },
                title = { Text(card.title) },
                text = { Text(card.lines.joinToString("\n"), Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = { saving = false; if (act.save(toSave)) onBack() }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } },
            )
        }
    }
    if (deleting) ConfirmDialog("Delete “${initial.name}”?", "It won't run again. This can't be undone.", "Delete", onDismiss = { deleting = false }) {
        deleting = false
        act.delete(initial.id)
        onBack()
    }
    if (adding) RadioDialog<StepChoice?>("Add a step", null, StepChoice.entries, { it?.title.orEmpty() }, { it?.let { c -> StepChoice.badge(c.sample()) } },
        onDismiss = { adding = false }) { c ->
        adding = false
        if (c == null) return@RadioDialog
        if (c.field == null) c.build()?.let { edit(r.copy(steps = r.steps + it)) } else arg = c.name
    }
    arg?.let { name ->
        val c = StepChoice.valueOf(name)
        EditDialog(c.title, "", c.field ?: "", c.hint ?: "", validate = { if (it.isBlank()) null else c.problem(it) }, confirm = "Add",
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
    TextPreference(title, RoutineDraftCodec.hm(t), "", title, "On a 24-hour clock, as 07:30.", RIcons.Timer,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        validate = { if (RoutineDraftCodec.time(it) == null) "A time such as 07:30." else null }) { text -> RoutineDraftCodec.time(text)?.let(onSave) }
}

/** "Every weekday", "Monday, Wednesday and Friday": a dialog of the seven days, at least one. */
@Composable
private fun DaysRow(title: String, days: Set<DayOfWeek>, onSave: (Set<DayOfWeek>) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    PreferenceItem(title, RoutineCards.days(days).replaceFirstChar { it.uppercase() }, RIcons.Event, onClick = { open = true })
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
                        Text(BriefClock.dayEn(day), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked.map { DayOfWeek.of(it) }.toSet()) }, enabled = picked.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
