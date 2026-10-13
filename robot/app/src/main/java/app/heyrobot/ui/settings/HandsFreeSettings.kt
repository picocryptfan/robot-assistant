package app.heyrobot.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarAnnounce
import app.heyrobot.core.car.CarAnnounceFrom
import app.heyrobot.core.car.CarCandidate
import app.heyrobot.core.car.CarDetect
import app.heyrobot.core.car.CarLockUse
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.CarSetup
import app.heyrobot.core.car.NewTexts
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.headset.AnnounceFrom
import app.heyrobot.core.headset.AnnounceRead
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.headset.HeadsetSetup
import app.heyrobot.core.headset.ListenWith
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.HandsFreeNotice
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.RememberedDevice
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.HandsFreeFacts
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.Paths
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.common.NoticeRow
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetSwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

// MAIN PROCESS (0.8). Settings › Headset and Settings › Car mode (design §3.4, §4.4), as Android-style pages, with the
// design's defaults: the headset button opens Robot, car mode is off until you turn it on and add your car. Everything is
// in files/robot (sys HandsFreeFiles, core HandsFreeStore: headset.json, car.json, places.json, devices.json), not in
// settings.json; each change tells the :voice process. The optional permissions are asked only from their own rows:
// Nearby devices, the Phone permission, and Do Not Disturb access (Android's own page).

/** What Settings › Headset shows. [announce]: Settings › Messages reads messages and "Announce new messages" is on (0.8). */
data class HeadsetPageData(
    val file: HeadsetFile = HeadsetFile(),
    val memory: DeviceMemory = DeviceMemory(),
    val facts: HandsFreeFacts? = null,
    val announce: Boolean = false,
) {
    companion object {
        fun read(ctx: Context): HeadsetPageData {
            val s = HandsFreeFiles.store(ctx)
            return HeadsetPageData(s.headset(), s.devices(), HandsFreeFacts.read(ctx), announcing(ctx))
        }
    }
}

/** 0.8: Settings › Messages reads your messages and announces new ones (messages.json; both off by default). */
internal fun announcing(ctx: Context): Boolean = try { MessagesStore.current(ctx).settings.let { it.on && it.announce } } catch (_: Throwable) { false }

/** What Settings › Car mode and Add your car show. [candidates]: the Bluetooth devices connected now that could be your car. */
data class CarPageData(
    val file: CarFile = CarFile(),
    val places: Places = Places(),
    val memory: DeviceMemory = DeviceMemory(),
    val callAfterYes: Boolean = true,
    val facts: HandsFreeFacts? = null,
    val candidates: List<CarCandidate> = emptyList(),
    /** Settings › Messages announces new messages (0.8). */
    val announce: Boolean = false,
) {
    val settings: CarSettings get() = file.settings

    companion object {
        fun read(ctx: Context): CarPageData {
            val s = HandsFreeFiles.store(ctx)
            val memory = s.devices()
            return CarPageData(s.car(), s.places(), memory, s.headset().safety.callAfterSpokenYes, HandsFreeFacts.read(ctx),
                CarSetup.candidates(AudioDevices.all(ctx), memory), announcing(ctx))
        }
    }
}

/** What the two pages can do. */
class HandsFreeActions(
    val headset: (HeadsetFile) -> Unit = {},
    val car: (CarFile) -> Unit = {},
    val places: (Places) -> Unit = {},
    val devices: ((DeviceMemory) -> DeviceMemory) -> Unit = {},
    val callAfterYes: (Boolean) -> Unit = {},
    /** A runtime permission, asked by Android's own dialog (Nearby devices, Phone). */
    val ask: (String) -> Unit = {},
    /** Android's Do Not Disturb access page. */
    val openDndAccess: () -> Unit = {},
    val open: (String) -> Unit = {},
    /** Where the phone is now (approximate), for a place; null when Robot may not know. */
    val here: suspend () -> Pair<Double, Double>? = { null },
)

object HandsFreeRoutes {
    const val ADD_CAR = "car/add"
}

/** Settings › Headset and Settings › Car mode, read from files/robot and refreshed while they show. */
@Composable
fun HandsFreeRoute(route: String, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val store = remember { HandsFreeFiles.store(ctx) }
    fun changed() { HandsFreeFiles.changed(ctx); tick++ }
    val act = HandsFreeActions(
        headset = { f -> store.editHeadset { f }; changed() },
        car = { f -> store.editCar { f }; changed() },
        places = { p -> store.editPlaces { p }; changed() },
        devices = { change -> store.editDevices(change); changed() },
        callAfterYes = { on -> store.editHeadset { it.copy(safety = it.safety.copy(callAfterSpokenYes = on)) }; changed() },
        ask = { p -> a.askPermission(p) },
        openDndAccess = { try { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} },
        open = { a.openPage(Page.Settings(it)) },
        here = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CoarseLocation.find(ctx, 5_000L).fix?.let { it.lat to it.lon } } },
    )
    when (route) {
        SettingsRoutes.HEADSET -> HeadsetSettingsScreen(remember(tick) { HeadsetPageData.read(ctx) }, act, snackbar, onBack)
        SettingsRoutes.CAR -> CarSettingsScreen(remember(tick) { CarPageData.read(ctx) }, act, snackbar, onBack)
        HandsFreeRoutes.ADD_CAR -> AddCarScreen(remember(tick) { CarPageData.read(ctx) }, act, snackbar, onBack)
        else -> LaunchedEffect(route) { onBack() }
    }
}

/** Settings' Headset row: "Button opens Robot · 1 headphone", "Button off". */
fun headsetSummary(f: HeadsetFile, memory: DeviceMemory): String {
    val n = memory.headsets.size
    return (if (f.settings.buttonOpensRobot) "Button opens Robot" else "Button off") + " · " + when (n) { 0 -> "no headphones yet"; 1 -> "1 headphone"; else -> "$n headphones" }
}

fun headsetSummary(ctx: Context): String = HandsFreeFiles.store(ctx).let { headsetSummary(it.headset(), it.devices()) }

fun carSummary(ctx: Context): String = HandsFreeFiles.store(ctx).let { CarSetup.summary(it.car().on, it.devices()) }

// ---------------------------------------------------------------------------------------------- Settings › Headset

private fun listenTitle(l: ListenWith): String = l.title

private fun listenDetail(l: ListenWith): String = when (l) {
    ListenWith.HEADSET_MIC -> "The default. Works with the phone in a pocket; music pauses while Robot listens and answers (call-quality sound)."
    ListenWith.PHONE_MIC -> "Keep the phone near you. No switch: faster, and music keeps its quality."
}

private val FOLLOW_UP = listOf(0, 3, 6, 10, 15)

private fun followUpTitle(s: Int): String = if (s == 0) "Don't wait" else "$s s"

private fun announceFromDetail(a: AnnounceFrom): String = when (a) {
    AnnounceFrom.CHOSEN_PEOPLE -> "Choose them in People › a person"
    AnnounceFrom.EVERYONE -> "Anyone who writes in the apps Settings › Messages reads"
}

private fun readDetail(r: AnnounceRead): String = when (r) {
    AnnounceRead.ASK_FIRST -> "Who wrote first; “yes” reads it, “no” is quiet"
    AnnounceRead.ALWAYS -> "The whole message at once"
}

@Composable
fun HeadsetSettingsScreen(d: HeadsetPageData, act: HandsFreeActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = d.file
    val s = f.settings
    fun set(n: HeadsetSettings) = act.headset(f.copy(settings = n))
    var device by rememberSaveable { mutableStateOf<Int?>(null) }
    PreferenceScreen("Headset", onBack, snackbar = snackbar) {
        preferenceGroup(null, "button") {
            SwitchPreference("Headset button opens Robot", if (s.buttonOpensRobot) "A long press of your headset's or car's voice button" else
                "Off: other apps get the button", s.buttonOpensRobot, icon = RIcons.Headset) { set(s.copy(buttonOpensRobot = it)) }
            val row = HeadsetSetup.row(s.buttonOpensRobot, d.facts?.buttonHandlers.orEmpty())
            if (d.facts != null && row.ok != null) StatusPreference(row.title, row.text.removePrefix("Headset button "), if (row.ok == true) Mark.OK else Mark.TODO)
        }
        preferenceGroup("Listening", "listening") {
            ListPreference("Listen with", s.listenWith, ListenWith.entries, ::listenTitle, ::listenDetail, RIcons.Mic) { set(s.copy(listenWith = it)) }
            SwitchPreference("Fall back to the phone mic", if (s.fallbackToPhoneMic) "If the headset's microphone doesn't connect in 3 s" else
                "Off: the session ends instead", s.fallbackToPhoneMic) { set(s.copy(fallbackToPhoneMic = it)) }
            ListPreference("Keep listening for a follow-up", HeadsetSettings.clampFollowUp(s.followUpSeconds), FOLLOW_UP, ::followUpTitle) {
                set(s.copy(followUpSeconds = it))
            }
            SwitchPreference("Speak replies in the headset", if (s.speakInHeadset) "Robot answers aloud in your ears" else "Replies show on screen only",
                s.speakInHeadset, icon = RIcons.VolumeUp) { set(s.copy(speakInHeadset = it)) }
        }
        preferenceGroup("Privacy", "privacy") {
            SwitchPreference("Personal answers only in private headphones", if (f.safety.personalOnlyPrivate) "Your calendar, contacts and notes never play on a speaker"
                else "Off: personal answers play wherever the headset's sound goes", f.safety.personalOnlyPrivate, icon = RIcons.Shield) {
                act.headset(f.copy(safety = f.safety.copy(personalOnlyPrivate = it)))
            }
            SwitchPreference("Stop when the headset disconnects", if (s.stopOnDisconnect) "Nothing more is said once it's gone; nothing moves to the speaker"
                else "Off: a public answer may go on, on the speaker", s.stopOnDisconnect) { set(s.copy(stopOnDisconnect = it)) }
        }
        // 0.8: 0.7's messages announced in private headphones, once Settings › Messages announces them.
        preferenceGroup("New messages", "announce") {
            if (!d.announce) PreferenceItem("Announce new messages is off", "Turn it on in Settings › Messages", RIcons.Message,
                onClick = { act.open(SettingsRoutes.MESSAGES) })
            val on = d.announce && s.announceMessages
            SwitchPreference("Announce new messages in the headset", if (on) "“Message from Marie. Read it?” in private headphones only" else "Off",
                on, enabled = d.announce) { set(s.copy(announceMessages = it)) }
            ListPreference("Announce from", s.announceFrom, AnnounceFrom.entries, { it.title }, detail = { announceFromDetail(it) }, enabled = on) {
                set(s.copy(announceFrom = it))
            }
            ListPreference("Read the text", s.announceRead, AnnounceRead.entries, { it.title }, detail = { readDetail(it) }, enabled = on) {
                set(s.copy(announceRead = it))
            }
            SwitchPreference("Announce while locked", if (s.announceLocked) "Only with Settings › Messages › Messages on the lock screen: Read and reply"
                else "Off: nothing is announced until you unlock", s.announceLocked, enabled = on) { set(s.copy(announceLocked = it)) }
        }
        preferenceGroup("Your headphones", "devices") {
            val list = HeadsetSetup.headphones(d.memory)
            if (list.isEmpty()) PreferenceItem("None yet", "A headset is added the first time you use its button", RIcons.Headset)
            for (h in list) PreferenceItem(h.name.ifEmpty { "Headset #${h.number}" }, HeadsetSetup.deviceLine(h), RIcons.Headset, onClick = { device = h.number })
        }
        preferenceGroup("Nearby devices", "nearby") {
            val granted = d.facts?.bluetooth == true
            SwitchPreference("Answer the headset's voice button properly", when {
                !granted -> "Asks for Nearby devices. Tells your headset Robot is listening, so it doesn't beep an error after 5 s"
                s.answerVoiceButton -> "On: the headset is told Robot is listening"
                else -> "Off"
            }, s.answerVoiceButton && granted, icon = RIcons.Bluetooth) { on ->
                if (on && !granted) act.ask(Manifest.permission.BLUETOOTH_CONNECT)
                set(s.copy(answerVoiceButton = on))
            }
        }
        preferenceFooter(
            "A Bluetooth headset's or car's voice button, and a wired or USB headset's long press, open Robot. Robot never takes " +
                "your play and pause buttons and holds no media session, so your music app keeps them; it resumes after Robot " +
                "answers. During a phone call the button does nothing to the call.\n\nWith the headset's microphone, Robot uses " +
                "the headset as for a call while it listens and answers, and lets go right after. Personal answers play only in " +
                "headphones you said only you can hear: wired and USB headsets are, a Bluetooth headset once you say so, a car or " +
                "a speaker never. If the headset goes, Robot stops at once. Device names stay on this phone and never enter the " +
                "voice log.",
        )
    }
    device?.let { n -> d.memory.byNumber(n)?.let { h -> DeviceDialog(h, onDismiss = { device = null }, act = act, car = false) } }
}

/** A remembered device: private or not (a headset), on or off (a car), Forget. */
@Composable
private fun DeviceDialog(d: RememberedDevice, onDismiss: () -> Unit, act: HandsFreeActions, car: Boolean) {
    var forget by rememberSaveable { mutableStateOf(false) }
    if (forget) return ConfirmDialog("Forget “${d.name}”?", if (car) "Car mode won't start when it connects." else
        "Robot asks again whether it is private the next time.", "Forget", onDismiss = onDismiss) { act.devices { it.forget(d.number) }; onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.name.ifEmpty { if (car) "Car #${d.number}" else "Headset #${d.number}" }) },
        text = { Text(if (car) "Car mode starts when this car connects, and ends 30 s after it goes." else "Is it headphones only you can hear?") },
        confirmButton = {
            if (car) TextButton(onClick = { act.devices { it.setEnabled(d.number, !d.enabled) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (d.enabled) "Turn off" else "Turn on")
            } else TextButton(onClick = { act.devices { it.answerPrivate(d.number, true) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Private") }
        },
        dismissButton = {
            if (!car) TextButton(onClick = { act.devices { it.answerPrivate(d.number, false) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not private") }
            TextButton(onClick = { forget = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Forget") }
        },
    )
}

// ---------------------------------------------------------------------------------------------- Settings › Car mode

private fun graceTitle(s: Int): String = when (s) { 0 -> "At once"; else -> if (s < 60) "$s s after it disconnects" else "${s / 60} min after it disconnects" }

private fun hoursTitle(h: Int): String = if (h == 1) "1 hour" else "$h hours"

private fun lockDetail(l: CarLockUse): String = when (l) {
    CarLockUse.AFTER_ONE_UNLOCK -> "The default. Unlock once in the car: calls, what's next and navigating home work locked until the car goes"
    CarLockUse.ALWAYS -> "Car functions work locked whenever your car is connected, unlocked or not"
    CarLockUse.NEVER -> "Unlock each time"
}

private fun callDetail(c: CallSetting): String = when (c) {
    CallSetting.OPEN_DIALER -> "The default. The dialer opens with the number; you tap Call"
    CallSetting.CALL_AFTER_YES -> "Needs the Phone permission. Robot reads back who and which number, then calls after your yes"
}

private fun placeLine(p: SavedPlace?): String = p?.let { it.label + " · " + String.format(Locale.US, "%.4f, %.4f", it.lat, it.lon) } ?: "Not set"

@Composable
fun CarSettingsScreen(d: CarPageData, act: HandsFreeActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = d.file
    val s = d.settings
    fun set(n: CarSettings) = act.car(f.copy(settings = n))
    var car by rememberSaveable { mutableStateOf<Int?>(null) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    PreferenceScreen("Car mode", onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar("Use car mode", f.on) { act.car(f.copy(on = it)) } }
        preferenceGroup("Your cars", "cars") {
            for (c in d.memory.cars) TwoTargetSwitchPreference(c.name.ifEmpty { "Car #${c.number}" }, if (c.enabled) "Car mode starts when it connects" else "Off",
                c.enabled, onClick = { car = c.number }, onCheckedChange = { on -> act.devices { it.setEnabled(c.number, on) } }, switchLabel = "${c.name} starts car mode",
                enabled = f.on, icon = RIcons.Car)
            PreferenceItem("Add your car", if (d.memory.cars.isEmpty()) "Car mode starts by itself once your car is added. Connect it first" else null,
                RIcons.Add, enabled = f.on, onClick = { act.open(HandsFreeRoutes.ADD_CAR) })
        }
        preferenceGroup("When car mode runs", "run") {
            SwitchPreference("Start car mode when my car connects", if (s.startOnConnect) "In under 5 s" else "Off: say “car mode”", s.startOnConnect,
                enabled = f.on) { set(s.copy(startOnConnect = it)) }
            ListPreference("End car mode", CarSettings.clampGrace(s.endGraceSeconds), CarSettings.GRACE_CHOICES, ::graceTitle, enabled = f.on) {
                set(s.copy(endGraceSeconds = it))
            }
            SwitchPreference("Speak through the car as a call", if (s.speakAsCall) "Cars lower the radio for it" else "Off: through the car's media, only if it plays Bluetooth",
                s.speakAsCall, enabled = f.on, icon = RIcons.VolumeUp) { set(s.copy(speakAsCall = it)) }
            ListPreference("After “hey robot” in the car, listen with", s.wakeListenWith, ListenWith.entries, { if (it == ListenWith.PHONE_MIC) "Phone microphone" else
                "The car's microphone" }, { if (it == ListenWith.PHONE_MIC) "The default: nothing of the question is lost" else "About 1 s later" }, enabled = f.on) {
                set(s.copy(wakeListenWith = it))
            }
            ListPreference("Use car mode without unlocking", s.lockUse, CarLockUse.entries, { it.title }, ::lockDetail, RIcons.Lock, enabled = f.on) {
                set(s.copy(lockUse = it))
            }
            ListPreference("Unlocked features last", CarSettings.clampHours(s.trustedHours), CarSettings.HOUR_CHOICES, { "Until the car goes, at most ${hoursTitle(it)}" },
                enabled = f.on) { set(s.copy(trustedHours = it)) }
            SwitchPreference("Keep the screen on", if (s.keepScreenOn) "While the car screen shows" else "Off", s.keepScreenOn, enabled = f.on) {
                set(s.copy(keepScreenOn = it))
            }
        }
        preferenceGroup("Calls", "calls") {
            val phone = d.facts?.callPhone == true
            ListPreference("Calls", s.calls, CallSetting.entries, { it.title }, ::callDetail, RIcons.Call, enabled = f.on) { c ->
                if (c == CallSetting.CALL_AFTER_YES && !phone) act.ask(Manifest.permission.CALL_PHONE)
                set(s.copy(calls = c))
            }
            if (s.calls == CallSetting.CALL_AFTER_YES) {
                StatusPreference("Phone permission", if (phone) "Allowed: Robot calls after your spoken yes" else "Not allowed: the dialer opens instead",
                    if (phone) Mark.OK else Mark.TODO, action = if (phone) null else ({
                        TextButton(onClick = { act.ask(Manifest.permission.CALL_PHONE) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
                    }))
                SwitchPreference("Place a call after a spoken yes", if (d.callAfterYes) "On (Safety & privacy)" else "Off: every call opens the dialer",
                    d.callAfterYes, enabled = f.on) { act.callAfterYes(it) }
            }
        }
        preferenceGroup("Places", "places") {
            for ((which, p) in listOf("home" to d.places.home, "work" to d.places.work)) {
                val title = if (which == "home") "Home" else "Work"
                PreferenceItem(title, placeLine(p), if (which == "home") RIcons.Home else RIcons.Place, enabled = f.on, onClick = { place = which }, trailing = {
                    // Where the phone is now (approximate: Robot has no precise location).
                    TextButton(onClick = {
                        scope.launch {
                            val here = act.here()
                            if (here == null) snackbar.showSnackbar("Robot doesn't know where the phone is. Allow approximate location, or paste coordinates.")
                            else act.places((SavedPlace.of(title, here.first, here.second)).let { sp -> if (which == "home") d.places.copy(home = sp) else d.places.copy(work = sp) })
                        }
                    }, enabled = f.on, modifier = Modifier.heightIn(min = 48.dp)) { Text("Here") }
                })
            }
        }
        // 0.8: 0.7's messages announced in the car while car mode runs, once Settings › Messages announces them.
        preferenceGroup("New messages", "announce") {
            if (!d.announce) PreferenceItem("Announce new messages is off", "Turn it on in Settings › Messages", RIcons.Message,
                onClick = { act.open(SettingsRoutes.MESSAGES) })
            val on = f.on && d.announce
            ListPreference("Announce new messages", if (d.announce) s.announce else CarAnnounce.OFF, CarAnnounce.entries, { it.title }, enabled = on) {
                set(s.copy(announce = it))
            }
            val announcing = on && s.announce != CarAnnounce.OFF
            ListPreference("Announce from", s.announceFrom, CarAnnounceFrom.entries, { it.title }, enabled = announcing) { set(s.copy(announceFrom = it)) }
            ListPreference("Read message text aloud when others may hear", s.readAloud, ReadAloud.entries, { it.title }, enabled = announcing) {
                set(s.copy(readAloud = it))
            }
            SwitchPreference("Replies by voice", if (s.voiceReplies) "Read back word for word, then “send”, where Settings › Messages lets a spoken yes send; " +
                "otherwise kept for when you park" else "Off: replies wait for when you park", s.voiceReplies, enabled = announcing) { set(s.copy(voiceReplies = it)) }
        }
        preferenceGroup("While you drive", "drive") {
            ListPreference("New texts while driving", s.newTexts, NewTexts.entries, { it.title }, enabled = f.on) { set(s.copy(newTexts = it)) }
            val dnd = d.facts?.dndAccess == true
            SwitchPreference("Do Not Disturb while driving", when {
                !dnd -> "Needs Do Not Disturb access: Robot switches only its own “Driving (Robot)” mode"
                s.dndWhileDriving -> "“Driving (Robot)” is on while car mode is"
                else -> "Off"
            }, s.dndWhileDriving && dnd, enabled = f.on) { on ->
                if (on && !dnd) act.openDndAccess()
                set(s.copy(dndWhileDriving = on))
            }
            val bt = d.facts?.bluetooth == true
            StatusPreference("Nearby devices", if (bt) "Allowed: Robot knows your car exactly" else "Optional: Robot knows your car by its name",
                if (bt) Mark.OK else Mark.TODO, action = if (bt) null else ({
                    TextButton(onClick = { act.ask(Manifest.permission.BLUETOOTH_CONNECT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
                }))
        }
        preferenceFooter(
            "Car mode is Robot's own hands-free mode over your car's Bluetooth, not Android Auto. When your car connects, Robot " +
                "says “Car mode on”, talks through the car, and shows big buttons. Press your car's voice button or say “hey " +
                "robot”, then “navigate home”, “call Mom” or “what's next”. Answers are short; anything that needs the screen, and " +
                "new texts, wait for when you park: “From your drive” shows when car mode ends.\n\nCalls open the dialer unless " +
                "you choose to call after a spoken yes; emergency numbers always open the dialer, and Robot never calls one " +
                "itself. Navigation opens your maps app's route preview: tap Start when you're stopped. Places are kept on this " +
                "phone only: type or paste coordinates (in your maps app: a place › Copy coordinates), or use where you are now.",
        )
    }
    car?.let { n -> d.memory.byNumber(n)?.let { c -> DeviceDialog(c, onDismiss = { car = null }, act = act, car = true) } }
    place?.let { which -> PlaceDialog(which, if (which == "home") d.places.home else d.places.work, onDismiss = { place = null }) { p ->
        act.places(if (which == "home") d.places.copy(home = p) else d.places.copy(work = p))
    } }
}

/** Home or Work: a name and coordinates (typed, pasted from Organic Maps, or where the phone is now). Clear removes it. */
@Composable
private fun PlaceDialog(which: String, now: SavedPlace?, onDismiss: () -> Unit, onSave: (SavedPlace?) -> Unit) {
    val title = if (which == "home") "Home" else "Work"
    val initial = now?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: ""
    EditDialog(title, initial, "Coordinates", "Such as 45.50170, -73.56730 (in your maps app: a place › Copy coordinates). Empty: none.",
        validate = { t -> if (t.isBlank() || CarSetup.coordinates(t) != null) null else "Coordinates, such as 45.5017, -73.5673" },
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
        onDismiss = onDismiss,
        onSave = { t ->
            onSave(if (t.isBlank()) null else CarSetup.coordinates(t)?.let { (lat, lon) -> SavedPlace.of(title, lat, lon) })
            onDismiss()
        })
}

// ---------------------------------------------------------------------------------------------- Add your car

@Composable
fun AddCarScreen(d: CarPageData, act: HandsFreeActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var ask by remember { mutableStateOf<SeenDevice?>(null) }
    val lang = SpokenLang.of(Locale.getDefault().toLanguageTag())
    PreferenceScreen("Add your car", onBack, snackbar = snackbar) {
        preferenceGroup("Connected now", "now") {
            if (d.candidates.isEmpty()) PreferenceItem("No Bluetooth device is connected", "Turn the car on and let the phone connect to it, then come back", RIcons.Bluetooth)
            for (c in d.candidates) PreferenceItem(c.seen.name.ifEmpty { "A Bluetooth device" }, (if (c.suggested) "Bluetooth says it's a car · " else "") +
                (c.seen.tail?.let { "…$it" } ?: ""), if (c.suggested) RIcons.Car else RIcons.Bluetooth, onClick = { ask = c.seen })
        }
        preferenceFooter("Robot lists the Bluetooth audio devices connected now. Your car is remembered by its name and the end of its " +
            "address (all of it with Nearby devices), on this phone only. A car is never treated as private headphones.")
    }
    ask?.let { seen ->
        ConfirmDialog(CarDetect.question(seen, lang), "Car mode will start when it connects.", "Yes, it's my car", onDismiss = { ask = null }) {
            act.devices { m -> m.remember(seen, DeviceRole.CAR, System.currentTimeMillis())?.first ?: m }
            ask = null
            onBack()
        }
    }
}

// ---------------------------------------------------------------------------------------------- the 0.8 notice

/**
 * The one-time line after updating to 0.8 on Home and Settings (core HandsFreeNotice): shown to someone who had Robot
 * before (settings.json was there when it first looked) until closed; a new install marks it seen at once.
 */
@Composable
fun HandsFreeNoticeLine(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        show = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = HandsFreeFiles.store(ctx)
            val f = store.headset()
            val had = File(Paths.dir(ctx), "settings.json").exists()
            if (!had && !f.noticeSeen) store.editHeadset { it.copy(noticeSeen = true) }
            HandsFreeNotice.shows(had, f.noticeSeen)
        }
    }
    if (!show) return
    fun seen() { show = false; HandsFreeFiles.store(ctx).editHeadset { it.copy(noticeSeen = true) } }
    NoticeRow(HandsFreeNotice.TEXT, onDismiss = ::seen, icon = RIcons.Headset, action = "Open", onAction = { seen(); onOpen() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}
