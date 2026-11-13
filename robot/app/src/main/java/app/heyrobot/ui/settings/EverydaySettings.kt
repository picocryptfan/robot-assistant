// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.everyday.MapsApp
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.car.AppPin
import app.heyrobot.sys.MapsApps
import app.heyrobot.core.music.MusicAppCode
import app.heyrobot.sys.MusicApps
import app.heyrobot.ui.pref.RadioDialog
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. The everyday set's Settings rows (docs/plans/everyday-set-plan.md §2.4), laid by E0: everyday_settings.json
// through its store (sys EverydaySettingsStore), never settings.json. Each screen adds its group with one line. A row
// shows only once the job that makes its switch do something turns it on in [EverydayRows], so a build never offers a
// switch that changes nothing.

/**
 * Which rows show. E0 builds them all hidden; each job turns on its own when its feature works (E1 music, E3 What went
 * online, E5 listening, E6 voices, E7 Ask Robot). One line each, a line apart, so jobs side by side merge cleanly.
 */
data class EverydayRows(
    val music: Boolean = MUSIC,
    val online: Boolean = ONLINE,
    val listen: Boolean = LISTEN,
    val voices: Boolean = VOICES,
    val ask: Boolean = ASK,
    val maps: Boolean = MAPS,
) {
    companion object {
        // E1: Settings › Actions › Music (everyday-set-plan.md §3): the keys, play by name through door 1, what's playing.
        val MUSIC = true

        // E3: Settings › Safety & privacy › What went online.
        val ONLINE = true

        // E5: Settings › Voice, listening for other apps.
        val LISTEN = true

        // E6: Settings › Voice, Robot's voices for other apps.
        val VOICES = true

        // E7: Settings › Actions, Ask Robot in other apps (on: AskRobotActivity, and its switch disables it, sys AskRobotDoor).
        val ASK = true

        // Car mode's music and maps: Settings › Actions › Maps, your maps app.
        val MAPS = true

        /** Every row, for tests and previews. */
        val ALL = EverydayRows(music = true, online = true, listen = true, voices = true, ask = true, maps = true)
    }
}

/**
 * The set's switches as the file has them, the groups' titles, and a change written over the file as it is now
 * (EverydaySettingsStore.update).
 */
class EverydayState(val settings: EverydaySettings, val rows: EverydayRows, val titles: Titles, val change: ((EverydaySettings) -> EverydaySettings) -> Unit) {
    class Titles(val music: String, val otherApps: String, val online: String, val maps: String = "", val read: String = "")
}

/** Reads everyday_settings.json off the main thread; each change is written there too, then read back. */
@Composable
fun rememberEveryday(rows: EverydayRows = EverydayRows()): EverydayState {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf(EverydaySettings()) }
    LaunchedEffect(Unit) { s = withContext(Dispatchers.IO) { EverydaySettingsStore.current(app) } }
    val titles = EverydayState.Titles(stringResource(R.string.everyday_group_music), stringResource(R.string.everyday_group_other_apps),
        stringResource(R.string.everyday_group_online), stringResource(R.string.everyday_group_maps), stringResource(R.string.everyday_group_read))
    return EverydayState(s, rows, titles) { change ->
        scope.launch {
            s = withContext(Dispatchers.IO) {
                EverydaySettingsStore.update(app, change)
                EverydaySettingsStore.current(app)
            }
        }
    }
}

/** Settings › Actions: Music (by voice, and your music app), and Ask Robot in other apps. */
fun LazyListScope.everydayActionRows(e: EverydayState) {
    val s = e.settings
    if (e.rows.music) preferenceGroup(e.titles.music, "everyday-music") { // l10n: not text (a list key)
        SwitchPreference(stringResource(R.string.everyday_music), stringResource(R.string.everyday_music_summary), s.music) { on -> e.change { it.copy(music = on) } }
        // E1: pick or change it here too (Q1: otherwise the first "play …" by name asks).
        MusicAppRow(s) { app -> e.change { it.copy(musicApp = app) } }
        PodcastAppRow(s) { app -> e.change { it.copy(podcastApp = app) } }
    }
    if (e.rows.maps) preferenceGroup(e.titles.maps, "everyday-maps") { // l10n: not text (a list key)
        MapsAppRow(s) { app -> e.change { it.copy(mapsApp = app) } }
    }
    if (e.rows.ask) preferenceGroup(e.titles.otherApps, "everyday-ask") { // l10n: not text (a list key)
        SwitchPreference(stringResource(R.string.everyday_ask), stringResource(R.string.everyday_ask_summary), s.askRobot) { on -> e.change { it.copy(askRobot = on) } }
    }
    // 1.3 point and read: small print in four parts (off by default).
    preferenceGroup(e.titles.read.ifEmpty { null }, "everyday-read") { // l10n: not text (a list key)
        SwitchPreference(stringResource(R.string.everyday_read_tiles), stringResource(R.string.everyday_read_tiles_summary), s.readTiles) { on -> e.change { it.copy(readTiles = on) } }
    }
}

/** Settings › Voice: listening for other apps' mic buttons, and Robot's voices for other apps. */
fun LazyListScope.everydayVoiceRows(e: EverydayState) {
    val s = e.settings
    if (e.rows.listen || e.rows.voices) preferenceGroup(e.titles.otherApps, "everyday-voice-apps") { // l10n: not text (a list key)
        if (e.rows.listen) SwitchPreference(stringResource(R.string.everyday_listen), stringResource(R.string.everyday_listen_summary), s.listen) { on ->
            e.change { it.copy(listen = on) }
        }
        if (e.rows.voices) SwitchPreference(stringResource(R.string.everyday_voices), stringResource(R.string.everyday_voices_summary), s.voices) { on ->
            e.change { it.copy(voices = on) }
        }
        // E6: which voices, Siwis named as French from France, and where to pick Robot (Android's own page).
        if (e.rows.voices) {
            val ctx = LocalContext.current
            PreferenceItem(stringResource(R.string.everyday_voices_android), stringResource(R.string.everyday_voices_list), enabled = s.voices,
                onClick = { openTtsSettings(ctx) })
        }
    }
}

/** Android's text-to-speech page (« Synthèse vocale »), else its accessibility page, where the screen reader is. */
private fun openTtsSettings(ctx: Context) {
    for (action in listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS)) { // l10n: not text (an intent action)
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/**
 * Settings › Safety & privacy: What went online, a row that opens its page ([open]: SettingsRoutes.ONLINE, E3's
 * OnlineScreen), where the record, its filter, its switch, the days kept and Delete are.
 */
fun LazyListScope.everydaySafetyRows(e: EverydayState, open: () -> Unit = {}) {
    val s = e.settings
    if (e.rows.online) preferenceGroup(e.titles.online, "everyday-online") { // l10n: not text (a list key)
        PreferenceItem(stringResource(R.string.everyday_online_page),
            if (s.online) pluralStringResource(R.plurals.everyday_online_summary, s.onlineDays, s.onlineDays) else stringResource(R.string.everyday_online_off_summary),
            onClick = open)
    }
}

/**
 * E1, Settings › Actions › Music › Your music app (everyday-set-plan.md §3.4, D5): its name, or that it was replaced by
 * an app signed by someone else; a tap lists the music apps Robot can ask (sys MusicApps), with None to forget it. The
 * pick pins the package and its signer's SHA-256, as the panel's card does.
 */
@Composable
internal fun MusicAppRow(s: EverydaySettings, onPick: (MusicApp?) -> Unit) {
    MusicAppRow(s.musicApp, R.string.everyday_music_app, R.string.everyday_music_app_none, s.music, onPick)
}

/** Settings › Actions › Podcasts: the same list and the same pin. With both set, car mode asks "Music or podcasts?". */
@Composable
internal fun PodcastAppRow(s: EverydaySettings, onPick: (MusicApp?) -> Unit) {
    MusicAppRow(s.podcastApp, R.string.everyday_podcast_app, R.string.everyday_podcast_app_none, true, onPick)
}

@Composable
private fun MusicAppRow(chosen: MusicApp?, title: Int, noneYet: Int, enabled: Boolean, onPick: (MusicApp?) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<MusicApps.Found>?>(null) }
    var replaced by remember { mutableStateOf(false) }
    LaunchedEffect(chosen) { replaced = chosen?.let { withContext(Dispatchers.IO) { MusicApps.check(app, it) } } == MusicApps.PinNow.REPLACED }
    val summary = chosen?.let { if (replaced) stringResource(R.string.everyday_music_app_replaced, it.label) else it.label }
        ?: stringResource(noneYet)
    PreferenceItem(stringResource(title), summary, enabled = enabled, onClick = { open = true })
    if (!open) return
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { MusicApps.find(app) } }
    val list = found ?: return
    val none = stringResource(R.string.everyday_music_app_forget)
    val noneFound = stringResource(R.string.everyday_music_app_none_found)
    RadioDialog(
        title = stringResource(title),
        selected = list.firstOrNull { it.pkg == chosen?.pkg },
        entries = list + listOf(null),
        label = { it?.label ?: none },
        detail = { if (it == null && list.isEmpty()) noneFound else null },
        onDismiss = { open = false; found = null },
        onPick = { f ->
            open = false
            found = null
            scope.launch {
                val pin = f?.let { withContext(Dispatchers.IO) { MusicApps.pin(app, it) } }
                if (f != null && pin == null) return@launch
                onPick(pin)
                VoiceLog.add(VoiceEvent.MUSIC_APP, when {
                    pin == null -> MusicAppCode.FORGOTTEN
                    chosen == null -> MusicAppCode.PICKED
                    else -> MusicAppCode.CHANGED
                }, list.size, 1)
            }
        },
    )
}

/**
 * Settings › Actions › Maps › Your maps app (docs/pm/handoff-car-media-maps.md): Automatic (Organic Maps when it's
 * installed, else the phone's default app for map links) or an app that opens map links, pinned with its signer as your
 * music app is. Every route Robot opens goes there, and Phone lock's Maps leaves it open.
 */
@Composable
internal fun MapsAppRow(s: EverydaySettings, onPick: (MapsApp?) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<MapsApps.Found>?>(null) }
    var replaced by remember { mutableStateOf(false) }
    LaunchedEffect(s.mapsApp) { replaced = s.mapsApp?.let { withContext(Dispatchers.IO) { MapsApps.check(app, it) } } == AppPin.REPLACED }
    val automatic = stringResource(R.string.everyday_maps_automatic)
    val automaticDetail = stringResource(R.string.everyday_maps_automatic_summary)
    val summary = s.mapsApp?.let { if (replaced) stringResource(R.string.everyday_maps_app_replaced, it.label) else it.label } ?: automaticDetail
    PreferenceItem(stringResource(R.string.everyday_maps_app), summary, onClick = { open = true })
    if (!open) return
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { MapsApps.find(app) } }
    val list = found ?: return
    RadioDialog(
        title = stringResource(R.string.everyday_maps_app),
        selected = if (s.mapsApp == null) null else list.firstOrNull { it.pkg == s.mapsApp?.pkg },
        entries = listOf(null) + list,
        label = { it?.label ?: automatic },
        detail = { if (it == null) automaticDetail else null },
        onDismiss = { open = false; found = null },
        onPick = { f ->
            open = false
            found = null
            scope.launch {
                val pin = f?.let { withContext(Dispatchers.IO) { MapsApps.pin(app, it) } }
                if (f != null && pin == null) return@launch
                onPick(pin)
            }
        },
    )
}
