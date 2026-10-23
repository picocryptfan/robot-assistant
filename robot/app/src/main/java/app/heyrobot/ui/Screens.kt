// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.Attachment
import app.heyrobot.core.CostText
import app.heyrobot.core.ModelLogic
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.HomeGuide
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.ui.common.ActionView
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.TurnActions
import app.heyrobot.ui.common.TurnUi
import app.heyrobot.ui.common.TURN_GAP
import app.heyrobot.ui.common.TurnView
import app.heyrobot.ui.common.replyFooter
import app.heyrobot.ui.common.keptPicture
import app.heyrobot.ui.common.replyFooterText
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str

// MAIN PROCESS. Home, history and one saved conversation.

@Composable
fun HomeScreen(snap: Snapshot, recent: List<StoredConversation>, a: AppActions, modifier: Modifier, guide: GuideState = GuideState()) {
    val f = snap.facts
    val ready = f?.active == true && snap.model == ModelStatus.VERIFIED && snap.voiceReady
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(RIcons.Robot, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(stringResource(R.string.home_hi), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.home_runs_here), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = { a.talk(null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(RIcons.Mic, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.home_talk_to_robot), style = MaterialTheme.typography.titleMedium)
        }
        // 1.0: the setup guide left with Finish later: where it was, and Continue.
        val underWay = guide.at?.takeIf { SetupGuide.home(guide) == HomeGuide.CONTINUE }
        if (underWay != null) {
            SectionCard(stringResource(R.string.guide_home_title)) {
                Text(stringResource(R.string.guide_home_continue_detail, underWay.number, SetupGuide.COUNT), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = a.openGuide, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.guide_home_continue)) }
            }
        }
        if (!ready || !f.micGranted || !f.notificationsGranted) {
            SectionCard(stringResource(R.string.home_get_started)) {
                StatusRow(
                    if (snap.model == ModelStatus.VERIFIED) Mark.OK else if (snap.model == ModelStatus.BAD) Mark.WARN else Mark.TODO,
                    stringResource(R.string.home_download_gemma),
                    when (snap.model) {
                        ModelStatus.VERIFIED -> stringResource(R.string.home_ready_here)
                        ModelStatus.DOWNLOADING, ModelStatus.IMPORTING, ModelStatus.VERIFYING -> snap.progress ?: ModelLogic.headlineText(snap.model).str()
                        // 0.4.7: this phone's Fast (2.6 GB on a Pixel 9a, 3.1 GB for a Pixel 10's TPU build).
                        else -> stringResource(R.string.home_size_once, app.heyrobot.core.PhoneStatus.sizeText(snap.fast.bytes).str())
                    },
                ) { if (snap.model != ModelStatus.VERIFIED) FilledTonalButton(onClick = { a.openPage(Page.Settings(app.heyrobot.ui.settings.SettingsRoutes.MODEL)) }) { Text(stringResource(R.string.home_open)) } }
                val stt = snap.voice(VoiceBundle.STT)
                val voice = snap.voice(VoiceBundle.KOKORO)
                StatusRow(
                    when {
                        snap.voiceReady -> Mark.OK
                        stt?.status == ModelStatus.BAD || voice?.status == ModelStatus.BAD -> Mark.WARN
                        else -> Mark.TODO
                    },
                    stringResource(R.string.home_download_voice),
                    when {
                        snap.voiceReady -> stringResource(R.string.home_voice_works)
                        stt?.progress != null -> stringResource(R.string.home_stt_progress, stt.progress)
                        voice?.progress != null -> stringResource(R.string.home_voice_progress, voice.progress)
                        else -> stringResource(R.string.home_voice_size, voiceSize(VoiceBundle.STT.bytes + VoiceBundle.KOKORO.bytes))
                    },
                ) { if (!snap.voiceReady) FilledTonalButton(onClick = { a.openPage(Page.Settings(app.heyrobot.ui.settings.SettingsRoutes.MODEL)) }) { Text(stringResource(R.string.home_open)) } }
                StatusRow(
                    if (f?.micGranted == true && f.notificationsGranted) Mark.OK else Mark.TODO,
                    stringResource(R.string.home_allow_mic),
                    stringResource(R.string.home_allow_mic_why),
                ) { if (f?.micGranted != true || !f.notificationsGranted) FilledTonalButton(onClick = a.askPermissions) { Text(stringResource(R.string.home_allow)) } }
                StatusRow(
                    if (f?.active == true) Mark.OK else Mark.TODO,
                    stringResource(R.string.home_make_assistant_row),
                    stringResource(if (f?.active == true) R.string.home_done else R.string.home_assistant_path),
                ) { if (f?.active != true) FilledTonalButton(onClick = a.openAssistantSettings) { Text(stringResource(R.string.home_set)) } }
            }
        }
        // 0.5.0: conversation mode.
        SectionCard(stringResource(R.string.home_conversation)) {
            Text(stringResource(R.string.home_conversation_detail),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = a.transcribe, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_transcribe))
                }
                FilledTonalButton(onClick = a.interpret, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.home_translate)) }
            }
            TextButton(onClick = { a.openPage(Page.Transcripts) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.home_transcripts)) }
        }
        SectionCard(stringResource(R.string.home_ways_to_ask)) {
            Tip(RIcons.Phone, stringResource(R.string.home_swipe), stringResource(R.string.home_swipe_detail))
            Tip(RIcons.Mic, stringResource(R.string.home_say_hey), stringResource(R.string.home_say_hey_detail))
            Tip(RIcons.Camera, stringResource(R.string.home_show_photo), stringResource(R.string.home_show_photo_detail))
            Tip(RIcons.Message, stringResource(R.string.home_texts), stringResource(R.string.home_texts_detail))
        }
        if (recent.isNotEmpty()) {
            SectionCard(stringResource(R.string.home_recent)) {
                for (c in recent) ConversationRow(c) { a.openConversation(c.id) }
                TextButton(onClick = { a.openTab(Tab.HISTORY) }) { Text(stringResource(R.string.home_all_conversations)) }
            }
        }
        if (underWay == null) {
            TextButton(onClick = a.openGuide, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.guide_home_again)) }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Tip(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConversationRow(c: StoredConversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A helper took part: a small cloud, or a house when only your own server did.
                if (c.fromHelper) {
                    val names = c.turns.filter { it.fromHelper }.map { it.author }.distinct().joinToString(", ")
                    Icon(if (c.homeOnly) RIcons.Home else RIcons.Cloud, stringResource(R.string.home_with, names), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(c.titleText.str(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (c.preview.isNotEmpty()) Text(c.preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(DateUtils.getRelativeTimeSpanString(c.updated).toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HistoryScreen(list: List<StoredConversation>, a: AppActions, modifier: Modifier) {
    var confirmAll by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ScreenTitle(UiStrings.history().str(), stringResource(R.string.home_saved_here)) }
            if (list.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.home_delete_all)) }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(RIcons.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.home_no_conversations), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_no_conversations_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { c ->
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { ConversationRow(c) { a.openConversation(c.id) } }
                    }
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.home_delete_all_title)) },
            text = { Text(stringResource(R.string.home_delete_all_detail)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; a.deleteAllConversations() }) { Text(stringResource(R.string.home_delete_all)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

@Composable
fun ConversationScreen(c: StoredConversation?, a: AppActions, snackbar: SnackbarHostState, showCost: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackBar(c?.titleText?.str() ?: stringResource(R.string.home_conversation), onBack) {
                if (c != null) IconButton(onClick = { confirm = true }) { Icon(RIcons.Delete, stringResource(R.string.home_delete)) }
            }
        },
        floatingActionButton = {
            if (c != null) ExtendedFloatingActionButton(
                onClick = { a.talk(c.id) },
                // Material hides the button's text from TalkBack (it can collapse to the icon), so the icon carries the name.
                icon = { Icon(RIcons.Mic, stringResource(R.string.home_continue)) },
                text = { Text(stringResource(R.string.home_continue)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text(stringResource(R.string.home_conversation_deleted)) }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(TURN_GAP),
        ) {
            item {
                Text(DateUtils.formatDateTime(ctx, c.created, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
            items(c.turns, key = { it.id }) { t ->
                val langs = LocalLangs.current
                val user = t.role == TurnRole.USER
                // A picture and screen text kept with it ("Keep screenshots and photos in history"); usually neither.
                val pic = remember(t.id, t.picture) { if (user) keptPicture(t.picture) else null }
                // The panel's chat bubbles. No buttons on the cards here.
                TurnView(TurnUi(t.id, user, t.text, attachment = if (user) t.attachment else Attachment.NONE, thumb = pic, screenText = t.screenText,
                    actions = t.actions.mapIndexed { i, x -> ActionView("$i", x.tool, x.summary, x.status, x.detail) },
                    stopped = t.stopped, error = t.error, helper = t.fromHelper, readBack = t.readBack, by = t.by, home = t.home,
                    footer = if (user) emptyList() else replyFooterText(t.sources, t.costMicros.takeIf { showCost && it > 0 }?.let { CostText.line(it / 1e6) }, t.by).map { it.render(langs) }),
                    TurnActions.None)
            }
        }
    }
    if (confirm && c != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.home_delete_this)) },
            confirmButton = { TextButton(onClick = { confirm = false; a.deleteConversation(c.id) }) { Text(stringResource(R.string.home_delete)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

/**
 * The voice models' size: 1.0's "1.02 GB" in English (VoiceModelLogic.size); in French core's « 1 Go » (PhoneStatus's), as
 * the other sizes on Home.
 */
@Composable
private fun voiceSize(bytes: Long): String =
    if (LocalLangs.current.ui == Lang.FR) app.heyrobot.core.PhoneStatus.sizeText(bytes).str() else VoiceModelLogic.sizeText(bytes).en()
