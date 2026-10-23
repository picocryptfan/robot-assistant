// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Abort
import app.heyrobot.core.OpenMeteo
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherPlace
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.OpenMeteoClient
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Weather (the Home for weather) and Assistant setup › Weather and phone status (0.4.0): the two
// optional permissions, approximate location and Usage access. Built like Android's Settings (0.4.x redesign): the Home
// for weather is a row whose dialog looks the town up.

/**
 * Settings › Weather: the Home for weather (looked up once here, when you save it; the only time the main app goes online
 * besides the model downloads), the location permission, and what goes to Open-Meteo in the footer.
 */
@Composable
fun WeatherSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val home = s.weatherHome
    val footer = stringResource(R.string.settings_weather_footer)
    PreferenceScreen(UiStrings.weather().str(), onBack, snackbar = snackbar) {
        preferenceGroup(null, "home") {
            PreferenceItem(stringResource(R.string.settings_weather_home),
                home?.let { "${it.name} · ${OpenMeteo.coord(it.lat)}, ${OpenMeteo.coord(it.lon)}" } ?: stringResource(R.string.settings_weather_not_set),
                RIcons.Home, onClick = { editing = true })
            LocationRow(snap.facts, a)
        }
        preferenceFooter(footer)
    }
    if (editing) HomeDialog(s, update, a, onClose = { editing = false })
}

/**
 * The Home for weather's dialog: a town, Look up (a progress bar while Open-Meteo answers, its error under the field), and
 * Clear when one is set. A found place is saved onto the settings as they are when the lookup ends.
 */
@Composable
internal fun HomeDialog(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val langs = LocalLangs.current
    val current by rememberUpdatedState(s)
    val home = s.weatherHome
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun lookUp() {
        val asked = text.trim()
        if (asked.isEmpty() || busy) return
        busy = true
        problem = null
        scope.launch {
            val r: Any? = withContext(Dispatchers.IO) {
                try { OpenMeteoClient.geocode(ctx, asked, Abort(WeatherAsk.DEADLINE_MS)) } catch (e: OpenMeteoClient.Failure) { e } catch (_: Exception) { null }
            }
            busy = false
            when (r) {
                is WeatherPlace -> {
                    update(current.copy(weatherHome = r.copy(name = r.name.take(ToolCalls.MAX_PLACE))))
                    onClose()
                    a.say(ctx.getString(R.string.settings_weather_saved, r.name))
                }
                is OpenMeteoClient.Failure -> problem = r.phrase.render(langs)
                else -> problem = ctx.getString(R.string.settings_weather_failed)
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(stringResource(R.string.settings_weather_home)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (home != null) Text(stringResource(R.string.settings_weather_now, home.name, OpenMeteo.coord(home.lat), OpenMeteo.coord(home.lon)))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; problem = null }, singleLine = true, enabled = !busy,
                    label = { Text(stringResource(if (home == null) R.string.settings_weather_town else R.string.settings_weather_another_town)) },
                    isError = problem != null,
                    supportingText = { Text(problem ?: stringResource(R.string.settings_weather_town_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { lookUp() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = ::lookUp, enabled = text.isNotBlank() && !busy) { Text(stringResource(R.string.settings_weather_look_up)) } },
        dismissButton = {
            Row {
                if (home != null) TextButton(onClick = { update(current.copy(weatherHome = null)); onClose() }, enabled = !busy) { Text(stringResource(R.string.settings_weather_clear)) }
                TextButton(onClick = onClose, enabled = !busy) { Text(UiStrings.cancel().str()) }
            }
        },
    )
}

/** Approximate location, optional: what it is for, and Allow. */
@Composable
private fun LocationRow(f: AssistantFacts?, a: AppActions) {
    val on = f?.locationGranted == true
    StatusPreference(stringResource(R.string.settings_location),
        stringResource(if (on) R.string.settings_location_ok else R.string.settings_location_todo),
        if (on) Mark.OK else Mark.TODO,
        action = if (on) null else ({ FilledTonalButton(onClick = a.askLocation) { Text(UiStrings.allow().str()) } }))
}

/** Assistant setup › Weather and phone status: all optional, all yours to turn off again in Android's settings. */
@Composable
internal fun ToolsSetupRows(f: AssistantFacts, a: AppActions) {
    LocationRow(f, a)
    StatusPreference(stringResource(R.string.settings_usage),
        stringResource(if (f.usageAccess) R.string.settings_usage_ok else R.string.settings_usage_todo),
        if (f.usageAccess) Mark.OK else Mark.TODO,
        action = if (f.usageAccess) null else ({ FilledTonalButton(onClick = a.openUsageAccess) { Text(UiStrings.open().str()) } }))
    StatusPreference(stringResource(R.string.settings_media),
        stringResource(when {
            f.mediaPartial -> R.string.settings_media_partial
            f.mediaGranted -> R.string.settings_media_ok
            else -> R.string.settings_media_todo
        }),
        if (f.mediaGranted) Mark.OK else Mark.TODO,
        action = if (f.mediaGranted && !f.mediaPartial) null else ({ FilledTonalButton(onClick = a.askMedia) { Text(UiStrings.allow().str()) } }))
}
