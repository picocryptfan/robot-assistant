package app.heyrobot.ui

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
    PreferenceScreen("Weather", onBack, snackbar = snackbar) {
        preferenceGroup(null, "home") {
            PreferenceItem("Home for weather", home?.let { "${it.name} · ${OpenMeteo.coord(it.lat)}, ${OpenMeteo.coord(it.lon)}" } ?: "Not set",
                RIcons.Home, onClick = { editing = true })
            LocationRow(snap.facts, a)
        }
        preferenceFooter(
            "Robot gets the weather from Open-Meteo (open-meteo.com) over the internet, with no account and no key. Only the place goes: " +
                "a town you name, your Home for weather, or where the phone is, rounded to about 1 km. °F in the United States, Liberia " +
                "and Myanmar (by the phone region), °C elsewhere.\n\nThe Home for weather is used when Robot can't tell where you are. " +
                "It is looked up once with Open-Meteo when you save it, then kept on the phone.",
        )
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
                    a.say("Home for weather: ${r.name}.")
                }
                is OpenMeteoClient.Failure -> problem = r.text
                else -> problem = "Robot couldn't look that up."
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Home for weather") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (home != null) Text("Now: ${home.name} (${OpenMeteo.coord(home.lat)}, ${OpenMeteo.coord(home.lon)})")
                OutlinedTextField(
                    value = text, onValueChange = { text = it; problem = null }, singleLine = true, enabled = !busy,
                    label = { Text(if (home == null) "Town" else "Another town") }, isError = problem != null,
                    supportingText = { Text(problem ?: "A town, like Montreal or Paris, Texas.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { lookUp() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = ::lookUp, enabled = text.isNotBlank() && !busy) { Text("Look up") } },
        dismissButton = {
            Row {
                if (home != null) TextButton(onClick = { update(current.copy(weatherHome = null)); onClose() }, enabled = !busy) { Text("Clear") }
                TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}

/** Approximate location, optional: what it is for, and Allow. */
@Composable
private fun LocationRow(f: AssistantFacts?, a: AppActions) {
    val on = f?.locationGranted == true
    StatusPreference("Location (optional)",
        if (on) "Allowed, approximate only (about 1 km)" else "For “the weather here” and “the nearest …”",
        if (on) Mark.OK else Mark.TODO,
        action = if (on) null else ({ FilledTonalButton(onClick = a.askLocation) { Text("Allow") } }))
}

/** Assistant setup › Weather and phone status: all optional, all yours to turn off again in Android's settings. */
@Composable
internal fun ToolsSetupRows(f: AssistantFacts, a: AppActions) {
    LocationRow(f, a)
    StatusPreference("Usage access (optional)",
        if (f.usageAccess) "On: Robot can tell which apps use the most space" else "So Robot can tell which apps use the most space",
        if (f.usageAccess) Mark.OK else Mark.TODO,
        action = if (f.usageAccess) null else ({ FilledTonalButton(onClick = a.openUsageAccess) { Text("Open") } }))
    StatusPreference("Photos and videos (optional)",
        when {
            f.mediaPartial -> "Only the ones you picked"
            f.mediaGranted -> "Allowed: Robot can show your largest ones"
            else -> "So Robot can show your largest ones to free storage"
        },
        if (f.mediaGranted) Mark.OK else Mark.TODO,
        action = if (f.mediaGranted && !f.mediaPartial) null else ({ FilledTonalButton(onClick = a.askMedia) { Text("Allow") } }))
}

/** What Assistant setup's footer says about the weather and phone status rows. */
internal const val TOOLS_SETUP_NOTE =
    "Weather and phone status are all optional. The weather comes from Open-Meteo over the internet; your phone's status is read on " +
        "the phone and never leaves it. Location is approximate only: “the weather here” and “the nearest pharmacy” use where the " +
        "phone is, rounded to about 1 km; without it, Robot uses your Home for weather and Organic Maps' search. You turn Usage access " +
        "on for Robot in Android's settings. Photos and videos: to free storage, Robot can show your largest ones (only the ones you " +
        "picked, if you chose that; allow again to pick more, or all), and Android asks before any is deleted. Robot doesn't ask for " +
        "All files access: Android clears apps' caches by itself when space runs low, and each app's info page clears its own."
