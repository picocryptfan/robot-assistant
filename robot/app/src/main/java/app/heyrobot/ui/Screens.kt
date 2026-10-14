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
                Text("Hi, I'm Robot", style = MaterialTheme.typography.headlineMedium)
                Text("Your assistant runs entirely on this phone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(
            onClick = { a.talk(null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(RIcons.Mic, null)
            Spacer(Modifier.width(10.dp))
            Text("Talk to Robot", style = MaterialTheme.typography.titleMedium)
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
            SectionCard("Get started") {
                StatusRow(
                    if (snap.model == ModelStatus.VERIFIED) Mark.OK else if (snap.model == ModelStatus.BAD) Mark.WARN else Mark.TODO,
                    "Download Gemma, Robot's brain",
                    when (snap.model) {
                        ModelStatus.VERIFIED -> "Ready on this phone"
                        ModelStatus.DOWNLOADING, ModelStatus.IMPORTING, ModelStatus.VERIFYING -> snap.progress ?: ModelLogic.headline(snap.model)
                        // 0.4.7: this phone's Fast (2.6 GB on a Pixel 9a, 3.1 GB for a Pixel 10's TPU build).
                        else -> "${app.heyrobot.core.PhoneStatus.size(snap.fast.bytes)}, once. Wi-Fi recommended."
                    },
                ) { if (snap.model != ModelStatus.VERIFIED) FilledTonalButton(onClick = { a.openTab(Tab.MODEL) }) { Text("Open") } }
                val stt = snap.voice(VoiceBundle.STT)
                val voice = snap.voice(VoiceBundle.KOKORO)
                StatusRow(
                    when {
                        snap.voiceReady -> Mark.OK
                        stt?.status == ModelStatus.BAD || voice?.status == ModelStatus.BAD -> Mark.WARN
                        else -> Mark.TODO
                    },
                    "Download voice models",
                    when {
                        snap.voiceReady -> "Robot can hear you and answer out loud"
                        stt?.progress != null -> "Speech recognition: ${stt.progress}"
                        voice?.progress != null -> "Voice: ${voice.progress}"
                        else -> "${VoiceModelLogic.size(VoiceBundle.STT.bytes + VoiceBundle.KOKORO.bytes)}, once, to talk to Robot. Wi-Fi recommended."
                    },
                ) { if (!snap.voiceReady) FilledTonalButton(onClick = { a.openTab(Tab.MODEL) }) { Text("Open") } }
                StatusRow(
                    if (f?.micGranted == true && f.notificationsGranted) Mark.OK else Mark.TODO,
                    "Allow the microphone and notifications",
                    "For “hey robot”, and to tell you if Android turns Robot off after a restart.",
                ) { if (f?.micGranted != true || !f.notificationsGranted) FilledTonalButton(onClick = a.askPermissions) { Text("Allow") } }
                StatusRow(
                    if (f?.active == true) Mark.OK else Mark.TODO,
                    "Make Robot your digital assistant",
                    if (f?.active == true) "Done" else "Settings › Digital assistant app › Robot",
                ) { if (f?.active != true) FilledTonalButton(onClick = a.openAssistantSettings) { Text("Set") } }
            }
        }
        // 0.5.0: conversation mode.
        SectionCard("Conversation") {
            Text("Robot writes down a conversation, in French and English, translates it face to face, and summarizes it, on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = a.transcribe, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Transcribe")
                }
                FilledTonalButton(onClick = a.interpret, modifier = Modifier.heightIn(min = 48.dp)) { Text("Translate") }
            }
            TextButton(onClick = { a.openPage(Page.Transcripts) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Transcripts") }
        }
        SectionCard("Ways to ask") {
            Tip(RIcons.Phone, "Swipe up from a bottom corner", "Works on the lock screen too. Once the phone is unlocked, Robot can see the screen you're on.")
            Tip(RIcons.Mic, "Say “hey robot”", "Even with the screen off. Turn it off in Settings.")
            Tip(RIcons.Camera, "Show it a photo", "Tap the camera in the assistant panel.")
            Tip(RIcons.Message, "Texts, email, notes and your calendar", "“Text Mom I'm running late”, “note: buy coffee filters”, “what's on my calendar tomorrow”.")
        }
        if (recent.isNotEmpty()) {
            SectionCard("Recent") {
                for (c in recent) ConversationRow(c) { a.openConversation(c.id) }
                TextButton(onClick = { a.openTab(Tab.HISTORY) }) { Text("All conversations") }
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
                    Icon(if (c.homeOnly) RIcons.Home else RIcons.Cloud, "With $names", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Box(Modifier.weight(1f)) { ScreenTitle("History", "Saved on this phone only.") }
            if (list.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Delete all") }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(RIcons.History, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("No conversations yet", style = MaterialTheme.typography.titleMedium)
                    Text("They appear here after you ask Robot something.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text("Delete all conversations?") },
            text = { Text("This removes every saved conversation from this phone. It can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmAll = false; a.deleteAllConversations() }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConversationScreen(c: StoredConversation?, a: AppActions, snackbar: SnackbarHostState, showCost: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackBar(c?.title ?: "Conversation", onBack) {
                if (c != null) IconButton(onClick = { confirm = true }) { Icon(RIcons.Delete, "Delete") }
            }
        },
        floatingActionButton = {
            if (c != null) ExtendedFloatingActionButton(
                onClick = { a.talk(c.id) },
                // Material hides the button's text from TalkBack (it can collapse to the icon), so the icon carries the name.
                icon = { Icon(RIcons.Mic, "Continue") },
                text = { Text("Continue") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("This conversation was deleted.") }
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
                val user = t.role == TurnRole.USER
                // A picture and screen text kept with it ("Keep screenshots and photos in history"); usually neither.
                val pic = remember(t.id, t.picture) { if (user) keptPicture(t.picture) else null }
                // The panel's chat bubbles. No buttons on the cards here.
                TurnView(TurnUi(t.id, user, t.text, attachment = if (user) t.attachment else Attachment.NONE, thumb = pic, screenText = t.screenText,
                    actions = t.actions.mapIndexed { i, x -> ActionView("$i", x.tool, x.summary, x.status, x.detail) },
                    stopped = t.stopped, error = t.error, helper = t.fromHelper, readBack = t.readBack, by = t.by, home = t.home,
                    footer = if (user) emptyList() else replyFooter(t.sources, t.costMicros.takeIf { showCost && it > 0 }?.let { CostText.line(it / 1e6) }, t.by)),
                    TurnActions.None)
            }
        }
    }
    if (confirm && c != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this conversation?") },
            confirmButton = { TextButton(onClick = { confirm = false; a.deleteConversation(c.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
