// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.heyrobot.core.HomeSettings
import kotlinx.coroutines.flow.Flow

// Main process. H4: Today's settings as the Home tab draws them. "put my calendar first" writes the file from :voice while
// the app stays open behind the panel. today-fix-d: the panel only takes the focus from the app, never pauses it, so a read
// on resume alone left the old order showing until you left the tab. As History does (ui/HistoryWatch), the page follows
// the file's stamp (HomeSettingsStore.stamp), read once a second while the app is in front.

/** The settings, [initial] at first, read with [load] each time [active] (the app resumed) and whenever [changes] emits. */
@Composable
fun rememberHomeSettings(initial: () -> HomeSettings, active: Boolean, changes: Flow<Long>, load: suspend () -> HomeSettings): MutableState<HomeSettings> {
    val home = remember { mutableStateOf(initial()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        home.value = load()
        changes.collect { home.value = load() }
    }
    return home
}
