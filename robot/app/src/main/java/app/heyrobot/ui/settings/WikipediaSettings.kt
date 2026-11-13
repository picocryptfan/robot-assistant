// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
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
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.wiki.WikiDownload
import app.heyrobot.core.wiki.WikiSet
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.WikiStore
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// MAIN PROCESS. Settings › Wikipedia on this phone (wikipedia-offline-plan.md §2, P3; sys/WikiStore.kt): each copy with its
// size and date and where it stands. A tap starts its download (Wi-Fi only: the download manager waits for it, and Robot
// never asks for mobile data), stops one under way or deletes a copy on the phone, behind a dialog. A finished download is
// checked and moved in while the page is open. Credits Kiwix and openZIM for the copies and Wikipedia for the articles.

@Composable
fun WikipediaSettingsRoute(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<WikiStore.Row>>(emptyList()) }
    var moving by remember { mutableStateOf<WikiSet?>(null) }
    var tried by remember { mutableStateOf(setOf<WikiSet>()) }
    var confirmDelete by remember { mutableStateOf<WikiSet?>(null) }
    var confirmStop by remember { mutableStateOf<WikiSet?>(null) }
    LaunchedEffect(tick) {
        while (true) {
            val now = withContext(Dispatchers.IO) { WikiStore.rows(ctx) }
            rows = now
            val done = now.firstOrNull { it.state == WikiStore.State.DOWNLOADED && it.set !in tried }
            if (done != null) {
                tried = tried + done.set
                moving = done.set
                val r = withContext(Dispatchers.IO) { WikiStore.install(ctx, done.set) }
                moving = null
                a.say(ctx.getString(when (r) {
                    WikiDownload.Moved.OK -> R.string.wiki_ready
                    WikiDownload.Moved.WRONG_FILE -> R.string.wiki_wrong_file
                    WikiDownload.Moved.NO_ROOM -> R.string.wiki_no_room_move
                    WikiDownload.Moved.FAILED -> R.string.wiki_move_failed
                }))
                continue
            }
            delay(2_000)
        }
    }
    fun start(s: WikiSet, again: Boolean) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { if (again) WikiStore.delete(ctx, s); WikiStore.start(ctx, s) }
            a.say(err ?: ctx.getString(R.string.wiki_started))
            tick++
        }
    }
    PreferenceScreen(stringResource(R.string.wiki_title), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.wiki_intro)) }
        preferenceGroup(null, "sets") {
            for (r in rows) PreferenceItem(setName(ctx, r.set), rowText(ctx, r, moving == r.set), RIcons.Download, enabled = moving == null, onClick = {
                when (r.state) {
                    WikiStore.State.NONE -> start(r.set, again = false)
                    WikiStore.State.FAILED -> start(r.set, again = true)
                    WikiStore.State.WAITING_WIFI, WikiStore.State.DOWNLOADING -> confirmStop = r.set
                    WikiStore.State.DOWNLOADED -> { tried = tried - r.set; tick++ }
                    WikiStore.State.ON_PHONE -> confirmDelete = r.set
                }
            })
        }
        item(key = "credit") { IntroPreference(stringResource(R.string.wiki_credit)) }
    }
    confirmDelete?.let { s ->
        ConfirmDialog(stringResource(R.string.wiki_delete_title), stringResource(R.string.wiki_delete_text, size(ctx, s.bytes)),
            stringResource(R.string.wiki_delete), onDismiss = { confirmDelete = null }) {
            confirmDelete = null
            scope.launch {
                withContext(Dispatchers.IO) { WikiStore.delete(ctx, s) }
                a.say(ctx.getString(R.string.wiki_deleted))
                tick++
            }
        }
    }
    confirmStop?.let { s ->
        ConfirmDialog(stringResource(R.string.wiki_stop_title), null, stringResource(R.string.wiki_stop), onDismiss = { confirmStop = null }) {
            confirmStop = null
            scope.launch {
                withContext(Dispatchers.IO) { WikiStore.delete(ctx, s) }
                tick++
            }
        }
    }
}

private fun size(ctx: Context, bytes: Long): String = VoiceModelLogic.sizeText(bytes).render(AppLanguage.langs(ctx))

private fun setName(ctx: Context, s: WikiSet): String = ctx.getString(when (s) {
    WikiSet.EN_TOP -> R.string.wiki_set_en_top
    WikiSet.FR_TOP -> R.string.wiki_set_fr_top
    WikiSet.EN_TOP1M -> R.string.wiki_set_en_top1m
    WikiSet.FR_ALL -> R.string.wiki_set_fr_all
})

/** "297 MB · September 2026 · Tap to download on Wi-Fi", in the menus' language. */
private fun rowText(ctx: Context, r: WikiStore.Row, moving: Boolean): String {
    val month = YearMonth.of(r.set.year, r.set.month).format(DateTimeFormatter.ofPattern("LLLL yyyy", ctx.resources.configuration.locales[0])) // l10n: not text (a date pattern)
    val state = when {
        moving -> ctx.getString(R.string.wiki_state_moving)
        else -> when (r.state) {
            WikiStore.State.NONE -> ctx.getString(R.string.wiki_state_none)
            WikiStore.State.WAITING_WIFI -> ctx.getString(R.string.wiki_state_waiting)
            WikiStore.State.DOWNLOADING -> ctx.getString(R.string.wiki_state_downloading, r.percent)
            WikiStore.State.DOWNLOADED -> ctx.getString(R.string.wiki_state_downloaded)
            WikiStore.State.FAILED -> ctx.getString(R.string.wiki_state_failed)
            WikiStore.State.ON_PHONE -> ctx.getString(R.string.wiki_state_on_phone)
        }
    }
    return ctx.getString(R.string.wiki_set_info, size(ctx, r.set.bytes), month, state)
}
