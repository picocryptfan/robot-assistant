// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.memory.MeaningSettings
import app.heyrobot.core.memory.SearchModelState
import app.heyrobot.sys.SearchModelStore
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings › Memory › Better search (docs/plans/meaning-search-plan.md): the search model's one row (Download, Cancel,
// Delete) and whether its download may use mobile data. Off until you download it. A finished download is checked here
// right away, before anything can load it (SearchModelStore.install).

fun LazyListScope.meaningSearchGroup(title: String, say: (String) -> Unit) {
    preferenceGroup(title, "meaning") { MeaningSearchRows(say) }
}

private val BUSY = setOf(SearchModelState.DOWNLOADING, SearchModelState.WAITING_FOR_WIFI, SearchModelState.WAITING_ROAMING, SearchModelState.DOWNLOADED, SearchModelState.CHECKING)

@Composable
private fun MeaningSearchRows(say: (String) -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<SearchModelStore.Snap?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        while (true) {
            val s = withContext(Dispatchers.IO) { SearchModelStore.snapshot(ctx) }
            snap = s
            if (s.state == SearchModelState.DOWNLOADED) scope.launch(Dispatchers.IO) { SearchModelStore.install(ctx) }
            delay(if (s.state in BUSY) 1_000 else 5_000)
        }
    }
    val s = snap ?: return
    val summary = when (s.state) {
        SearchModelState.NONE -> stringResource(R.string.memory_search_off)
        SearchModelState.WAITING_FOR_WIFI -> stringResource(R.string.memory_search_wifi)
        SearchModelState.WAITING_ROAMING -> stringResource(R.string.memory_search_roaming)
        SearchModelState.DOWNLOADING, SearchModelState.DOWNLOADED -> stringResource(R.string.memory_search_downloading, s.percent)
        SearchModelState.CHECKING -> stringResource(R.string.memory_search_checking, s.percent)
        SearchModelState.READY -> stringResource(R.string.memory_search_ready)
        SearchModelState.FAILED -> stringResource(R.string.memory_search_failed)
    }
    val deleted = stringResource(R.string.memory_search_deleted)
    PreferenceItem(stringResource(R.string.memory_search_title), summary,
        if (s.state == SearchModelState.READY) RIcons.Delete else RIcons.Download,
        enabled = s.state != SearchModelState.CHECKING && s.state != SearchModelState.DOWNLOADED,
        onClick = {
            scope.launch {
                val said = withContext(Dispatchers.IO) {
                    when (s.state) {
                        SearchModelState.NONE, SearchModelState.FAILED -> SearchModelStore.startDownload(ctx)
                        SearchModelState.DOWNLOADING, SearchModelState.WAITING_FOR_WIFI, SearchModelState.WAITING_ROAMING -> { SearchModelStore.cancelDownload(ctx); null }
                        SearchModelState.READY -> { SearchModelStore.delete(ctx); deleted }
                        else -> null
                    }
                }
                said?.let(say)
                tick++
            }
        })
    SwitchPreference(stringResource(R.string.memory_search_mobile), stringResource(R.string.memory_search_mobile_detail), s.anyNetwork,
        enabled = s.state == SearchModelState.NONE || s.state == SearchModelState.FAILED) { on ->
        scope.launch {
            withContext(Dispatchers.IO) { SearchModelStore.saveSettings(ctx, MeaningSettings(anyNetwork = on)) }
            tick++
        }
    }
}
