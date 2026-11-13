// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.Manifest
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.shots.ShotsState
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.ShotsJob
import app.heyrobot.sys.ShotsVault
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MAIN PROCESS. Settings › Ask about your screenshots (screenshots-plan.md, "What's new" 5; photo access): the switch (off
// by default; on needs photo access, all or selected), what was read and when, and "Delete the index". Switching off
// deletes the index and its key, behind a dialog. Its state is its own file (files/shots/state.json), not settings.json.
@Composable
fun ShotsSettingsRoute(@Suppress("UNUSED_PARAMETER") a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(ShotsState()) }
    var confirmOff by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { state = withContext(Dispatchers.IO) { ShotsVault.store(ctx).state() } }
    fun startFresh(done: Int?) = scope.launch {
        // The Keystore can refuse the key: it stays off and says so.
        val ok = withContext(Dispatchers.IO) {
            try {
                ShotsVault.store(ctx).switchOn(System.currentTimeMillis(), MediaStore.getGeneration(ctx, MediaStore.VOLUME_EXTERNAL_PRIMARY))
                ShotsJob.schedule(ctx)
                true
            } catch (_: Exception) {
                false
            }
        }
        tick++
        (if (ok) done else R.string.shots_on_failed)?.let { snackbar.showSnackbar(ctx.getString(it)) }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneInfo.mediaGranted(ctx)) startFresh(null) else scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shots_no_access)) }
    }
    val status = when {
        !state.on -> stringResource(R.string.shots_off_summary)
        state.read == 0 -> stringResource(R.string.shots_status_none)
        else -> stringResource(R.string.shots_status, state.read,
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(state.lastPass).atZone(ZoneId.systemDefault())))
    }
    PreferenceScreen(stringResource(R.string.shots_title), onBack, snackbar = snackbar) {
        preferenceGroup(null, "intro") { IntroPreference(stringResource(R.string.shots_intro)) }
        preferenceGroup(null, "switch") {
            SwitchPreference(stringResource(R.string.shots_switch), status, state.on) { on ->
                when {
                    !on -> confirmOff = true
                    PhoneInfo.mediaGranted(ctx) -> startFresh(null)
                    else -> ask.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                }
            }
            if (state.on) PreferenceItem(stringResource(R.string.shots_delete), stringResource(if (PhoneInfo.mediaPartial(ctx)) R.string.shots_delete_summary_selected else R.string.shots_delete_summary), onClick = { startFresh(R.string.shots_deleted) })
        }
        preferenceGroup(null, "access") {
            if (PhoneInfo.mediaPartial(ctx)) IntroPreference(stringResource(R.string.shots_selected))
            IntroPreference(stringResource(R.string.shots_graphene))
        }
    }
    if (confirmOff) ConfirmDialog(stringResource(R.string.shots_off_title), stringResource(R.string.shots_off_body), stringResource(R.string.shots_off_ok),
        onDismiss = { confirmOff = false }) {
        confirmOff = false
        scope.launch {
            withContext(Dispatchers.IO) { ShotsJob.cancel(ctx); ShotsVault.store(ctx).switchOff() }
            tick++
            snackbar.showSnackbar(ctx.getString(R.string.shots_deleted))
        }
    }
}
