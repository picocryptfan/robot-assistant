// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
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
import app.heyrobot.core.i18n.Lang
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

// MAIN PROCESS (0.8). Settings › Headset and Settings › Car mode, as Android-style pages, with the
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

/** 1.1: [headsetSummary] without a language yet (« Le bouton ouvre Robot · 1 paire d'écouteurs »). */
fun headsetSummaryText(f: HeadsetFile, memory: DeviceMemory): Phrase {
    val n = memory.headsets.size
    val devices = if (n == 0) HandsFreeStrings.noHeadphones() else HandsFreeStrings.headphones(n)
    return (if (f.settings.buttonOpensRobot) HandsFreeStrings.buttonOpensSummary else HandsFreeStrings.buttonOffSummary)("devices" to devices)
}

fun headsetSummaryText(ctx: Context): Phrase = HandsFreeFiles.store(ctx).let { headsetSummaryText(it.headset(), it.devices()) }

fun carSummaryText(ctx: Context): Phrase = HandsFreeFiles.store(ctx).let { CarSetup.summaryText(it.car().on, it.devices()) }

// ---------------------------------------------------------------------------------------------- Settings › Headset

private fun listenDetail(l: ListenWith): Int = when (l) {
    ListenWith.HEADSET_MIC -> R.string.headset_listen_headset_detail
    ListenWith.PHONE_MIC -> R.string.headset_listen_phone_detail
}

private val FOLLOW_UP = listOf(0, 3, 6, 10, 15)

private fun followUpTitle(ctx: Context, s: Int): String = if (s == 0) ctx.getString(R.string.headset_dont_wait) else ctx.getString(R.string.headset_seconds, s)

private fun announceFromDetail(a: AnnounceFrom): Int = when (a) {
    AnnounceFrom.CHOSEN_PEOPLE -> R.string.headset_from_chosen_detail
    AnnounceFrom.EVERYONE -> R.string.headset_from_everyone_detail
}

private fun readDetail(r: AnnounceRead): Int = when (r) {
    AnnounceRead.ASK_FIRST -> R.string.headset_read_ask_detail
    AnnounceRead.ALWAYS -> R.string.headset_read_always_detail
}

@Composable
fun HeadsetSettingsScreen(d: HeadsetPageData, act: HandsFreeActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = d.file
    val s = f.settings
    fun set(n: HeadsetSettings) = act.headset(f.copy(settings = n))
    var device by rememberSaveable { mutableStateOf<Int?>(null) }
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val tListening = stringResource(R.string.headset_listening)
    val tPrivacy = stringResource(R.string.headset_privacy)
    val tNew = stringResource(R.string.headset_new_messages)
    val tYours = stringResource(R.string.headset_yours)
    val tNearby = stringResource(R.string.headset_nearby)
    val footer = stringResource(R.string.headset_footer)
    PreferenceScreen(UiStrings.headset().str(), onBack, snackbar = snackbar) {
        preferenceGroup(null, "button") {
            SwitchPreference(stringResource(R.string.headset_button), stringResource(if (s.buttonOpensRobot) R.string.headset_button_on else R.string.headset_button_off),
                s.buttonOpensRobot, icon = RIcons.Headset) { set(s.copy(buttonOpensRobot = it)) }
            val row = HeadsetSetup.row(s.buttonOpensRobot, d.facts?.buttonHandlers.orEmpty())
            val title = row.titleText.str()
            if (d.facts != null && row.ok != null) StatusPreference(title, row.textPhrase.str().removePrefix("$title "), if (row.ok == true) Mark.OK else Mark.TODO)
        }
        preferenceGroup(tListening, "listening") {
            ListPreference(stringResource(R.string.headset_listen_with), s.listenWith, ListenWith.entries, { it.text.render(langs) }, { ctx.getString(listenDetail(it)) },
                RIcons.Mic) { set(s.copy(listenWith = it)) }
            SwitchPreference(stringResource(R.string.headset_fallback), stringResource(if (s.fallbackToPhoneMic) R.string.headset_fallback_on else R.string.headset_fallback_off),
                s.fallbackToPhoneMic) { set(s.copy(fallbackToPhoneMic = it)) }
            ListPreference(stringResource(R.string.headset_follow_up), HeadsetSettings.clampFollowUp(s.followUpSeconds), FOLLOW_UP, { followUpTitle(ctx, it) }) {
                set(s.copy(followUpSeconds = it))
            }
            SwitchPreference(stringResource(R.string.headset_speak), stringResource(if (s.speakInHeadset) R.string.headset_speak_on else R.string.headset_speak_off),
                s.speakInHeadset, icon = RIcons.VolumeUp) { set(s.copy(speakInHeadset = it)) }
        }
        preferenceGroup(tPrivacy, "privacy") {
            SwitchPreference(stringResource(R.string.headset_personal),
                stringResource(if (f.safety.personalOnlyPrivate) R.string.headset_personal_on else R.string.headset_personal_off), f.safety.personalOnlyPrivate,
                icon = RIcons.Shield) {
                act.headset(f.copy(safety = f.safety.copy(personalOnlyPrivate = it)))
            }
            SwitchPreference(stringResource(R.string.headset_stop), stringResource(if (s.stopOnDisconnect) R.string.headset_stop_on else R.string.headset_stop_off),
                s.stopOnDisconnect) { set(s.copy(stopOnDisconnect = it)) }
        }
        // 0.8: 0.7's messages announced in private headphones, once Settings › Messages announces them.
        preferenceGroup(tNew, "announce") {
            if (!d.announce) PreferenceItem(stringResource(R.string.headset_announce_is_off), stringResource(R.string.headset_announce_turn_on), RIcons.Message,
                onClick = { act.open(SettingsRoutes.MESSAGES) })
            val on = d.announce && s.announceMessages
            SwitchPreference(stringResource(R.string.headset_announce), stringResource(if (on) R.string.headset_announce_on else R.string.headset_off),
                on, enabled = d.announce) { set(s.copy(announceMessages = it)) }
            ListPreference(stringResource(R.string.headset_announce_from), s.announceFrom, AnnounceFrom.entries, { it.text.render(langs) },
                detail = { ctx.getString(announceFromDetail(it)) }, enabled = on) {
                set(s.copy(announceFrom = it))
            }
            ListPreference(stringResource(R.string.headset_read), s.announceRead, AnnounceRead.entries, { it.text.render(langs) },
                detail = { ctx.getString(readDetail(it)) }, enabled = on) {
                set(s.copy(announceRead = it))
            }
            SwitchPreference(stringResource(R.string.headset_announce_locked),
                stringResource(if (s.announceLocked) R.string.headset_announce_locked_on else R.string.headset_announce_locked_off), s.announceLocked, enabled = on) {
                set(s.copy(announceLocked = it))
            }
        }
        preferenceGroup(tYours, "devices") {
            val list = HeadsetSetup.headphones(d.memory)
            if (list.isEmpty()) PreferenceItem(stringResource(R.string.headset_none), stringResource(R.string.headset_none_hint), RIcons.Headset)
            for (h in list) PreferenceItem(h.name.ifEmpty { stringResource(R.string.headset_number, h.number) }, HeadsetSetup.deviceLineText(h).str(), RIcons.Headset,
                onClick = { device = h.number })
        }
        preferenceGroup(tNearby, "nearby") {
            val granted = d.facts?.bluetooth == true
            SwitchPreference(stringResource(R.string.headset_answer_button), when {
                !granted -> stringResource(R.string.headset_answer_ask)
                s.answerVoiceButton -> stringResource(R.string.headset_answer_on)
                else -> stringResource(R.string.headset_off)
            }, s.answerVoiceButton && granted, icon = RIcons.Bluetooth) { on ->
                if (on && !granted) act.ask(Manifest.permission.BLUETOOTH_CONNECT)
                set(s.copy(answerVoiceButton = on))
            }
        }
        preferenceFooter(footer)
    }
    device?.let { n -> d.memory.byNumber(n)?.let { h -> DeviceDialog(h, onDismiss = { device = null }, act = act, car = false) } }
}

/** A remembered device: private or not (a headset), on or off (a car), Forget. */
@Composable
private fun DeviceDialog(d: RememberedDevice, onDismiss: () -> Unit, act: HandsFreeActions, car: Boolean) {
    var forget by rememberSaveable { mutableStateOf(false) }
    if (forget) return ConfirmDialog(stringResource(R.string.headset_forget_title, d.name),
        stringResource(if (car) R.string.car_forget_text else R.string.headset_forget_text), stringResource(R.string.headset_forget), onDismiss = onDismiss) {
        act.devices { it.forget(d.number) }; onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.name.ifEmpty { stringResource(if (car) R.string.car_number else R.string.headset_number, d.number) }) },
        text = { Text(stringResource(if (car) R.string.car_device_text else R.string.headset_device_text)) },
        confirmButton = {
            if (car) TextButton(onClick = { act.devices { it.setEnabled(d.number, !d.enabled) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (d.enabled) R.string.car_turn_off else R.string.car_turn_on))
            } else TextButton(onClick = { act.devices { it.answerPrivate(d.number, true) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.headset_private))
            }
        },
        dismissButton = {
            if (!car) TextButton(onClick = { act.devices { it.answerPrivate(d.number, false) }; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.headset_not_private))
            }
            TextButton(onClick = { forget = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.headset_forget)) }
        },
    )
}

// ---------------------------------------------------------------------------------------------- Settings › Car mode

private fun graceTitle(ctx: Context, s: Int): String = when (s) {
    0 -> ctx.getString(R.string.car_grace_now)
    else -> if (s < 60) ctx.getString(R.string.car_grace_seconds, s) else ctx.getString(R.string.car_grace_minutes, s / 60)
}

private fun lockDetail(l: CarLockUse): Int = when (l) {
    CarLockUse.AFTER_ONE_UNLOCK -> R.string.car_lock_after_detail
    CarLockUse.ALWAYS -> R.string.car_lock_always_detail
    CarLockUse.NEVER -> R.string.car_lock_never_detail
}

private fun callDetail(c: CallSetting): Int = when (c) {
    CallSetting.OPEN_DIALER -> R.string.car_call_dialer_detail
    CallSetting.CALL_AFTER_YES -> R.string.car_call_yes_detail
}

private fun placeLine(ctx: Context, p: SavedPlace?): String =
    p?.let { it.label + " · " + String.format(Locale.US, "%.4f, %.4f", it.lat, it.lon) } ?: ctx.getString(R.string.car_not_set)

@Composable
fun CarSettingsScreen(d: CarPageData, act: HandsFreeActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val f = d.file
    val s = d.settings
    fun set(n: CarSettings) = act.car(f.copy(settings = n))
    var car by rememberSaveable { mutableStateOf<Int?>(null) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val use = stringResource(R.string.car_use)
    val tYours = stringResource(R.string.car_yours)
    val tWhen = stringResource(R.string.car_when_run)
    val tCalls = UiStrings.calls().str()
    val tPlaces = stringResource(R.string.car_places)
    val tNew = stringResource(R.string.headset_new_messages)
    val tDrive = stringResource(R.string.car_while_drive)
    val footer = stringResource(R.string.car_footer)
    PreferenceScreen(UiStrings.carMode().str(), onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar(use, f.on) { act.car(f.copy(on = it)) } }
        preferenceGroup(tYours, "cars") {
            for (c in d.memory.cars) TwoTargetSwitchPreference(c.name.ifEmpty { stringResource(R.string.car_number, c.number) },
                stringResource(if (c.enabled) R.string.car_starts_on_connect else R.string.car_off),
                c.enabled, onClick = { car = c.number }, onCheckedChange = { on -> act.devices { it.setEnabled(c.number, on) } },
                switchLabel = stringResource(R.string.car_starts_label, c.name), enabled = f.on, icon = RIcons.Car)
            PreferenceItem(UiStrings.addYourCar().str(), if (d.memory.cars.isEmpty()) stringResource(R.string.car_add_hint) else null,
                RIcons.Add, enabled = f.on, onClick = { act.open(HandsFreeRoutes.ADD_CAR) })
        }
        preferenceGroup(tWhen, "run") {
            SwitchPreference(stringResource(R.string.car_start_connect), stringResource(if (s.startOnConnect) R.string.car_start_connect_on else R.string.car_start_connect_off),
                s.startOnConnect, enabled = f.on) { set(s.copy(startOnConnect = it)) }
            ListPreference(stringResource(R.string.car_end_mode), CarSettings.clampGrace(s.endGraceSeconds), CarSettings.GRACE_CHOICES, { graceTitle(ctx, it) },
                enabled = f.on) {
                set(s.copy(endGraceSeconds = it))
            }
            SwitchPreference(stringResource(R.string.car_speak_call), stringResource(if (s.speakAsCall) R.string.car_speak_call_on else R.string.car_speak_call_off),
                s.speakAsCall, enabled = f.on, icon = RIcons.VolumeUp) { set(s.copy(speakAsCall = it)) }
            val phoneMicDetail = stringResource(R.string.car_phone_mic_detail)
            val carMicDetail = stringResource(R.string.car_car_mic_detail)
            ListPreference(stringResource(R.string.car_wake_listen), s.wakeListenWith, ListenWith.entries,
                { ctx.getString(if (it == ListenWith.PHONE_MIC) R.string.car_phone_mic else R.string.car_car_mic) },
                { if (it == ListenWith.PHONE_MIC) phoneMicDetail else carMicDetail }, enabled = f.on) {
                set(s.copy(wakeListenWith = it))
            }
            ListPreference(stringResource(R.string.car_lock_use), s.lockUse, CarLockUse.entries, { it.text.render(langs) }, { ctx.getString(lockDetail(it)) },
                RIcons.Lock, enabled = f.on) {
                set(s.copy(lockUse = it))
            }
            ListPreference(stringResource(R.string.car_trusted_last), CarSettings.clampHours(s.trustedHours), CarSettings.HOUR_CHOICES,
                { ctx.resources.getQuantityString(R.plurals.car_trusted_hours, it, it) }, enabled = f.on) { set(s.copy(trustedHours = it)) }
            SwitchPreference(stringResource(R.string.car_screen_on), stringResource(if (s.keepScreenOn) R.string.car_screen_on_on else R.string.car_off),
                s.keepScreenOn, enabled = f.on) {
                set(s.copy(keepScreenOn = it))
            }
        }
        preferenceGroup(tCalls, "calls") {
            val phone = d.facts?.callPhone == true
            ListPreference(tCalls, s.calls, CallSetting.entries, { it.text.render(langs) }, { ctx.getString(callDetail(it)) }, RIcons.Call, enabled = f.on) { c ->
                if (c == CallSetting.CALL_AFTER_YES && !phone) act.ask(Manifest.permission.CALL_PHONE)
                set(s.copy(calls = c))
            }
            if (s.calls == CallSetting.CALL_AFTER_YES) {
                val allow = stringResource(R.string.car_allow)
                StatusPreference(stringResource(R.string.car_phone_permission),
                    stringResource(if (phone) R.string.car_phone_permission_ok else R.string.car_phone_permission_no),
                    if (phone) Mark.OK else Mark.TODO, action = if (phone) null else ({
                        TextButton(onClick = { act.ask(Manifest.permission.CALL_PHONE) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) }
                    }))
                SwitchPreference(stringResource(R.string.car_call_after_yes),
                    stringResource(if (d.callAfterYes) R.string.car_call_after_yes_on else R.string.car_call_after_yes_off),
                    d.callAfterYes, enabled = f.on) { act.callAfterYes(it) }
            }
        }
        preferenceGroup(tPlaces, "places") {
            for ((which, p) in listOf("home" to d.places.home, "work" to d.places.work)) {
                val title = stringResource(if (which == "home") R.string.car_home else R.string.car_work)
                val unknown = stringResource(R.string.car_where_unknown)
                PreferenceItem(title, placeLine(ctx, p), if (which == "home") RIcons.Home else RIcons.Place, enabled = f.on, onClick = { place = which }, trailing = {
                    // Where the phone is now (approximate: Robot has no precise location).
                    TextButton(onClick = {
                        scope.launch {
                            val here = act.here()
                            if (here == null) snackbar.showSnackbar(unknown)
                            else act.places((SavedPlace.of(title, here.first, here.second)).let { sp -> if (which == "home") d.places.copy(home = sp) else d.places.copy(work = sp) })
                        }
                    }, enabled = f.on, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.car_here)) }
                })
            }
        }
        // 0.8: 0.7's messages announced in the car while car mode runs, once Settings › Messages announces them.
        preferenceGroup(tNew, "announce") {
            if (!d.announce) PreferenceItem(stringResource(R.string.headset_announce_is_off), stringResource(R.string.headset_announce_turn_on), RIcons.Message,
                onClick = { act.open(SettingsRoutes.MESSAGES) })
            val on = f.on && d.announce
            ListPreference(UiStrings.announceNewMessages().str(), if (d.announce) s.announce else CarAnnounce.OFF, CarAnnounce.entries, { it.text.render(langs) },
                enabled = on) {
                set(s.copy(announce = it))
            }
            val announcing = on && s.announce != CarAnnounce.OFF
            ListPreference(stringResource(R.string.headset_announce_from), s.announceFrom, CarAnnounceFrom.entries, { it.text.render(langs) },
                enabled = announcing) { set(s.copy(announceFrom = it)) }
            ListPreference(stringResource(R.string.car_read_aloud), s.readAloud, ReadAloud.entries, { it.text.render(langs) }, enabled = announcing) {
                set(s.copy(readAloud = it))
            }
            SwitchPreference(stringResource(R.string.car_voice_replies),
                stringResource(if (s.voiceReplies) R.string.car_voice_replies_on else R.string.car_voice_replies_off), s.voiceReplies, enabled = announcing) {
                set(s.copy(voiceReplies = it))
            }
        }
        preferenceGroup(tDrive, "drive") {
            // 1.0 (the user, 13 Oct 2026): no driving restrictions, so no row holds anything for when you park.
            val dnd = d.facts?.dndAccess == true
            SwitchPreference(stringResource(R.string.car_dnd), when {
                !dnd -> stringResource(R.string.car_dnd_needs)
                s.dndWhileDriving -> stringResource(R.string.car_dnd_on)
                else -> stringResource(R.string.car_off)
            }, s.dndWhileDriving && dnd, enabled = f.on) { on ->
                if (on && !dnd) act.openDndAccess()
                set(s.copy(dndWhileDriving = on))
            }
            val bt = d.facts?.bluetooth == true
            val allow = stringResource(R.string.car_allow)
            StatusPreference(stringResource(R.string.headset_nearby), stringResource(if (bt) R.string.car_nearby_ok else R.string.car_nearby_no),
                if (bt) Mark.OK else Mark.TODO, action = if (bt) null else ({
                    TextButton(onClick = { act.ask(Manifest.permission.BLUETOOTH_CONNECT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(allow) }
                }))
        }
        preferenceFooter(footer)
    }
    car?.let { n -> d.memory.byNumber(n)?.let { c -> DeviceDialog(c, onDismiss = { car = null }, act = act, car = true) } }
    place?.let { which -> PlaceDialog(which, if (which == "home") d.places.home else d.places.work, onDismiss = { place = null }) { p ->
        act.places(if (which == "home") d.places.copy(home = p) else d.places.copy(work = p))
    } }
}

/** Home or Work: a name and coordinates (typed, pasted from Organic Maps, or where the phone is now). Clear removes it. */
@Composable
private fun PlaceDialog(which: String, now: SavedPlace?, onDismiss: () -> Unit, onSave: (SavedPlace?) -> Unit) {
    val title = stringResource(if (which == "home") R.string.car_home else R.string.car_work)
    val initial = now?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: ""
    val problem = stringResource(R.string.car_coordinates_problem)
    EditDialog(title, initial, stringResource(R.string.car_coordinates), stringResource(R.string.car_coordinates_hint),
        validate = { t -> if (t.isBlank() || CarSetup.coordinates(t) != null) null else problem },
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
    val tNow = stringResource(R.string.car_connected_now)
    val footer = stringResource(R.string.car_add_footer)
    PreferenceScreen(UiStrings.addYourCar().str(), onBack, snackbar = snackbar) {
        preferenceGroup(tNow, "now") {
            if (d.candidates.isEmpty()) PreferenceItem(stringResource(R.string.car_none_connected), stringResource(R.string.car_none_hint), RIcons.Bluetooth)
            val saysCar = stringResource(R.string.car_says_car)
            for (c in d.candidates) PreferenceItem(c.seen.name.ifEmpty { stringResource(R.string.car_a_device) },
                listOfNotNull(saysCar.takeIf { c.suggested }, c.seen.tail?.let { "…$it" }).joinToString(" · "),
                if (c.suggested) RIcons.Car else RIcons.Bluetooth, onClick = { ask = c.seen })
        }
        preferenceFooter(footer)
    }
    ask?.let { seen ->
        ConfirmDialog(CarDetect.questionText(seen).str(), stringResource(R.string.car_add_confirm_text), stringResource(R.string.car_add_yes), onDismiss = { ask = null }) {
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
    NoticeRow(HandsFreeNotice.text.str(), onDismiss = ::seen, icon = RIcons.Headset, action = stringResource(R.string.headset_open), onAction = { seen(); onOpen() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}
