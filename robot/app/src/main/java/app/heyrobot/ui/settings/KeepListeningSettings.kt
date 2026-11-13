// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

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
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.ui.pref.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Voice › "Keep listening after an answer" (idea 7, off; core PanelFollowUp): kept in headset.json beside the
 * headset's own follow-up (HeadsetSettings.panelFollowUp), as settings.json stays at schema 13. :voice reads it again on change.
 */
@Composable
internal fun KeepListeningPreference() {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = withContext(Dispatchers.IO) { HandsFreeFiles.store(app).headset().settings.panelFollowUp } }
    SwitchPreference(stringResource(R.string.voice_keep_listening),
        stringResource(if (on) R.string.voice_keep_listening_on else R.string.voice_keep_listening_off), on) { v ->
        on = v
        scope.launch(Dispatchers.IO) {
            HandsFreeFiles.store(app).editHeadset { it.copy(settings = it.settings.copy(panelFollowUp = v)) }
            HandsFreeFiles.changed(app)
        }
    }
}
