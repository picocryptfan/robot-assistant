package app.heyrobot.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.heyrobot.core.Abort
import app.heyrobot.core.OpenMeteo
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherPlace
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.OpenMeteoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Weather (the Home for weather) and Assistant setup › Weather and phone status (0.4.0): the two
// optional permissions, approximate location and Usage access.

/**
 * Settings › Weather: what goes to Open-Meteo, the Home for weather (looked up once here, when you save it; the only
 * time the main app goes online besides the model downloads) and the location permission.
 */
@Composable
internal fun WeatherSettingsCard(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions) {
    val f = snap.facts
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SectionCard("Weather") {
        Text("Robot gets the weather from Open-Meteo (open-meteo.com) over the internet, with no account and no key. Only the place " +
            "goes: a town you name, your Home for weather, or where the phone is, rounded to about 1 km. °F in the United States, " +
            "Liberia and Myanmar (by the phone region), °C elsewhere.", style = MaterialTheme.typography.bodyMedium, color = muted)
        HomeForWeather(s, update, a)
        LocationRow(f, a)
    }
}

/** The Home for weather: shown with Clear when set; a field and Look up to set or change it. */
@Composable
private fun HomeForWeather(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // The lookup takes a moment: it saves onto the settings as they are when it ends.
    val current by rememberUpdatedState(s)
    val home = s.weatherHome
    var text by remember(home) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun lookUp() {
        val asked = text.trim()
        if (asked.isEmpty() || busy) return
        busy = true
        scope.launch {
            val r: Any? = withContext(Dispatchers.IO) {
                try { OpenMeteoClient.geocode(ctx, asked, Abort(WeatherAsk.DEADLINE_MS)) } catch (e: OpenMeteoClient.Failure) { e } catch (_: Exception) { null }
            }
            busy = false
            when (r) {
                is WeatherPlace -> {
                    update(current.copy(weatherHome = r.copy(name = r.name.take(ToolCalls.MAX_PLACE))))
                    text = ""
                    a.say("Home for weather: ${r.name}.")
                }
                is OpenMeteoClient.Failure -> a.say(r.why.text)
                else -> a.say("Robot couldn't look that up.")
            }
        }
    }
    Text("Home for weather", style = MaterialTheme.typography.titleSmall)
    if (home != null) {
        StatusRow(Mark.OK, home.name, "${OpenMeteo.coord(home.lat)}, ${OpenMeteo.coord(home.lon)}: used when Robot can't tell where you are.") {
            TextButton(onClick = { update(current.copy(weatherHome = null)) }) { Text("Clear") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, enabled = !busy,
            label = { Text(if (home == null) "Home for weather" else "Change it") },
            supportingText = { Text("A town, like Montreal or Paris, Texas. Looked up once with Open-Meteo, then kept on the phone.") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { lookUp() }),
            modifier = Modifier.weight(1f),
        )
        if (text.isNotBlank()) TextButton(onClick = ::lookUp, enabled = !busy) { Text("Look up") }
    }
    if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun LocationRow(f: AssistantFacts?, a: AppActions) {
    val on = f?.locationGranted == true
    StatusRow(if (on) Mark.OK else Mark.TODO, "Location (optional)",
        if (on) "Allowed, approximate only: “the weather here” uses where the phone is, rounded to about 1 km."
        else "For “the weather here” when you travel. Without it, Robot uses your Home for weather.") {
        if (!on) FilledTonalButton(onClick = a.askLocation) { Text("Allow") }
    }
}

/** Assistant setup › Weather and phone status: all optional, all yours to turn off again in Android's settings. */
@Composable
internal fun ToolsSetupCard(f: AssistantFacts, a: AppActions) {
    SectionCard("Weather and phone status") {
        Text("All optional. The weather comes from Open-Meteo over the internet; your phone's status is read on the phone and never leaves it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LocationRow(f, a)
        StatusRow(if (f.usageAccess) Mark.OK else Mark.TODO, "Usage access (optional)",
            if (f.usageAccess) "On: Robot can tell which apps use the most space."
            else "So Robot can tell which apps use the most space. You turn it on for Robot in Android's settings.") {
            if (!f.usageAccess) FilledTonalButton(onClick = a.openUsageAccess) { Text("Open") }
        }
        StatusRow(if (f.mediaGranted) Mark.OK else Mark.TODO, "Photos and videos (optional)",
            when {
                f.mediaPartial -> "Only the ones you picked: Robot can offer those of them that are large. Allow again to pick more, or all."
                f.mediaGranted -> "Allowed: to free storage, Robot can show your largest photos and videos. Android asks before any is deleted."
                else -> "So Robot can show your largest photos and videos when you want to free storage. Android asks before any is deleted."
            }) {
            if (!f.mediaGranted || f.mediaPartial) FilledTonalButton(onClick = a.askMedia) { Text("Allow") }
        }
        Text("Robot doesn't ask for All files access: Android clears apps' caches by itself when space runs low, and each app's info page clears its own.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
