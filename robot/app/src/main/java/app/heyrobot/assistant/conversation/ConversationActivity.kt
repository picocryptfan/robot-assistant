package app.heyrobot.assistant.conversation

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvPause
import app.heyrobot.core.ConversationLock
import app.heyrobot.core.TranscriptExport
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.enumOr
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// VOICE PROCESS. The Transcribe screen (docs/conversation-mode.md 5.3, phase 5.2): the live lines with their time and
// language (FR, EN, or "FR?" when the line was too short to tell: tap it to fix), the open segment's words in grey
// while you look (screen on, "Live words" on), Pause, Stop and the elapsed time. It observes ConversationSession with no
// IPC. It starts a session when opened to start one (the one-time consent reminder first), and shows over the lock
// screen only for a session started there, with nothing older (docs/conversation-mode.md 1.7).

class ConversationActivity : ComponentActivity() {
    /** A start asked for and waiting for the consent tick: how it was asked, and the transcript to resume. */
    private val pending = mutableStateOf<Pair<ConvEntry, String?>?>(null)
    private val keepOn = mutableStateOf(false)
    /** The phone is locked: read again when the screen comes back and when you unlock, so what shows follows it. */
    private val lockedNow = mutableStateOf(false)
    /**
     * This screen was opened on the lock screen for a session started there: it may stay over the lock screen (with
     * nothing older) until a session started unlocked runs in it.
     */
    private var overLockGrant = false
    private val lockWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { lockedNow.value = locked() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ConversationSession.init(this)
        lockedNow.value = locked()
        try {
            registerReceiver(lockWatch, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
        } catch (_: Throwable) {}
        handle(intent, first = savedInstanceState == null)
        setContent { RobotTheme { Screen() } }
    }

    override fun onDestroy() {
        try { unregisterReceiver(lockWatch) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, first = true)
    }

    override fun onStart() {
        super.onStart()
        lockedNow.value = locked()
        ConversationSession.watching(true)
    }

    override fun onResume() {
        super.onResume()
        lockedNow.value = locked()
    }

    override fun onStop() {
        ConversationSession.watching(false)
        super.onStop()
    }

    private fun locked(): Boolean = Assistant.locked(this)

    /**
     * Over the lock screen only for the session started there: while one runs, its own flag decides; otherwise the
     * screen keeps the place [handle] gave it (a start asked for on the lock screen, or "Saved" after one that ran there).
     */
    private fun applyOverLock(ui: ConversationSession.Ui) {
        val runs = ui.status == ConversationSession.Status.RUNNING || ui.status == ConversationSession.Status.PAUSED
        if (runs && !ui.lockedStart) overLockGrant = false
        setShowWhenLocked(if (runs) ui.lockedStart else overLockGrant)
    }

    private fun handle(i: Intent?, first: Boolean) {
        val locked = locked()
        lockedNow.value = locked
        val s = SettingsStore.load(this)
        val ui = ConversationSession.state.value
        // Opened again from Recents, the screen gets the intent that first opened it: that opens the screen, it never
        // starts (or resumes) a recording by itself.
        val fromRecents = i != null && (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val wantsStart = !fromRecents && (i?.action == Transcripts.ACTION_START || i?.action == Transcripts.ACTION_RESUME)
        // Over the lock screen only for a session started there (or starting there now, when that is allowed).
        val overLock = (ui.active && ui.lockedStart) || (wantsStart && locked && ConversationLock.mayStart(true, s))
        overLockGrant = overLock
        setShowWhenLocked(overLock)
        if (locked && !overLock) getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
        if (!first || !wantsStart || ui.active) return
        val entry = enumOr(i.getStringExtra(Transcripts.EXTRA_ENTRY), ConvEntry.APP)
        val resume = if (i.action == Transcripts.ACTION_RESUME) i.getStringExtra(Transcripts.EXTRA_ID) else null
        if (ConsentRecord.needed(Transcripts.consent(this), s)) pending.value = entry to resume
        else begin(entry, resume)
    }

    private fun begin(entry: ConvEntry, resume: String?) {
        pending.value = null
        if (!ConversationService.start(this, entry, resume)) ConversationNotices.couldNotStart(this)
    }

    private fun setKeepOn(on: Boolean) {
        keepOn.value = on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The transcript in Robot's app (main process; needs the phone unlocked). */
    private fun openInApp(id: String?) {
        if (locked()) return
        try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS)
                .apply { id?.let { putExtra(AppPages.EXTRA_TRANSCRIPT, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } catch (_: Throwable) {}
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val ui by ConversationSession.state.collectAsState()
        val ask by pending
        val locked by lockedNow
        // A session that ends, or another that starts, moves the screen off or onto the lock screen.
        LaunchedEffect(ui.status, ui.lockedStart) { applyOverLock(ui) }
        var menu by remember { mutableStateOf(false) }
        var help by remember { mutableStateOf(false) }
        var fixing by remember { mutableStateOf<TranscriptLine?>(null) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(ui.active) {
            while (ui.active) {
                now = System.currentTimeMillis()
                kotlinx.coroutines.delay(1_000)
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ui.active) {
                                val paused = ui.status == ConversationSession.Status.PAUSED
                                Box(Modifier.size(12.dp).clip(CircleShape).background(if (paused) MaterialTheme.colorScheme.outline else Color(0xFFD32F2F))
                                    .semantics { contentDescription = if (paused) "Paused" else "Recording" })
                                Spacer(Modifier.width(10.dp))
                                Text(elapsed(now - ui.createdAt))
                            } else Text("Transcribe")
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, "Back") } },
                    actions = {
                        if (ui.active) {
                            if (ui.status == ConversationSession.Status.PAUSED) TextButton(onClick = { ConversationSession.userResume() }) { Text("Resume") }
                            else TextButton(onClick = { ConversationSession.userPause() }, enabled = ui.status == ConversationSession.Status.RUNNING) { Text("Pause") }
                            TextButton(onClick = { ConversationSession.stop(app.heyrobot.core.ConvStop.USER) }, enabled = ui.status != ConversationSession.Status.STOPPING) { Text("Stop") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(RIcons.Settings, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (keepOn.value) "Let the screen turn off" else "Keep the screen on") }, onClick = { menu = false; setKeepOn(!keepOn.value) })
                                DropdownMenuItem(text = { Text("Help") }, onClick = { menu = false; help = true })
                                if (!ui.active && !locked) DropdownMenuItem(text = { Text("Transcripts") }, onClick = { menu = false; openInApp(null) })
                            }
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                val notice = ui.notice ?: pauseText(ui.pausedFor)
                if (notice != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(notice, Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
                val list = rememberLazyListState()
                // The newest line in view as lines come (the header, the lines, the live words, the footer).
                LaunchedEffect(ui.lines.size, ui.partial) {
                    val last = list.layoutInfo.totalItemsCount - 1
                    if (last > 0 && ui.lines.isNotEmpty()) list.animateScrollToItem(last)
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        if (ask != null) ConsentCard(onStart = { tick -> if (tick) { Transcripts.saveConsent(this@ConversationActivity); ask?.let { begin(it.first, it.second) } } },
                            onCancel = { pending.value = null; finish() })
                        else if (!ui.active && (ui.lines.isEmpty() || locked)) StartCard(onStart = {
                            val s = SettingsStore.load(this@ConversationActivity)
                            if (ConsentRecord.needed(Transcripts.consent(this@ConversationActivity), s)) pending.value = ConvEntry.APP to null else begin(ConvEntry.APP, null)
                        })
                        else if (ui.active) Text(ConsentRecord.LINE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // On the lock screen only the lines of a session started there, while it runs: nothing older, and
                    // no live words of a session started unlocked either.
                    val shows = !locked || (ui.active && ui.lockedStart)
                    val visible = if (shows) ui.lines else emptyList()
                    items(visible, key = { it.i }) { l -> LineRow(ui, l) { fixing = l } }
                    if (shows && ui.partial.isNotEmpty()) item {
                        Text(ui.partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                    }
                    item {
                        if (!ui.active && ui.savedId != null) SavedCard(locked = locked, onOpen = { openInApp(ui.savedId) },
                            onAgain = { pending.value = null; begin(ConvEntry.APP, null) })
                        Text("Transcribed on this phone. Nothing leaves it unless you send it.", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        fixing?.let { l ->
            AlertDialog(
                onDismissRequest = { fixing = null },
                title = { Text("Which language is this line?") },
                text = { Text("Robot couldn't tell from so few words, so it guessed from the line before.") },
                confirmButton = {
                    Row {
                        TextButton(onClick = { ConversationSession.setLang(l.i, "fr"); fixing = null }) { Text("French") }
                        TextButton(onClick = { ConversationSession.setLang(l.i, "en"); fixing = null }) { Text("English") }
                    }
                },
                dismissButton = { TextButton(onClick = { fixing = null }) { Text("Cancel") } },
            )
        }
        if (help) AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("Transcribing") },
            text = {
                Text("Robot writes down what it hears, on this phone, line by line, and saves each line as it goes. It keeps working with the " +
                    "screen off; the notification is on the whole time, with Pause and Stop. “FR?” means the line was too short to tell its " +
                    "language: tap it to fix. Speaker labels come in a later version. Nothing said to Robot while its panel is open is " +
                    "transcribed, and “hey robot” sleeps during the session unless you turn on Settings › Conversation › “Hey robot works " +
                    "during a conversation”. The audio is never kept.")
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } },
        )
    }

    @Composable
    private fun LineRow(ui: ConversationSession.Ui, l: TranscriptLine, onFix: () -> Unit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(ui.createdAt, l.t0), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                // One speaker label until speaker labels come (phase 5.3).
                Text("Speaker", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                val tag = TranscriptExport.langTag(l)
                val unsure = l.prior || l.lang == null
                Surface(shape = RoundedCornerShape(8.dp), color = if (unsure) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.heightIn(min = 24.dp).clip(RoundedCornerShape(8.dp)).then(if (unsure) Modifier.clickable(onClick = onFix) else Modifier)
                        .semantics { contentDescription = if (unsure) "Language: $tag, not sure. Tap to fix." else "Language: $tag" }) {
                    Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (l.reheard) {
                    Spacer(Modifier.width(8.dp))
                    Text("heard again", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(l.text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @Composable
    private fun ConsentCard(onStart: (Boolean) -> Unit, onCancel: () -> Unit) {
        var tick by remember { mutableStateOf(false) }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Before you start", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(ConsentRecord.TEXT, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { tick = !tick }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tick, onCheckedChange = { tick = it })
                    Text(ConsentRecord.TICK, style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { onStart(tick) }, enabled = tick, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start") }
                }
            }
        }
    }

    @Composable
    private fun StartCard(onStart: () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Transcribe a conversation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Robot writes down what it hears, in French and English, and saves it on this phone. It keeps going with the screen off.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(RIcons.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start")
                }
            }
        }
    }

    @Composable
    private fun SavedCard(locked: Boolean, onOpen: () -> Unit, onAgain: () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (locked) "Unlock your phone to read, export or delete it in Robot › Transcripts." else "It's in Robot › Transcripts, to read, export or delete.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!locked) OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    OutlinedButton(onClick = onAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text("New transcript") }
                }
            }
        }
    }

    private fun pauseText(p: ConvPause?): String? = when (p) {
        null -> null
        ConvPause.USER -> "Paused. Tap Resume to go on."
        ConvPause.MIC_TAKEN -> "Paused: another app is using the microphone. It goes on by itself when the microphone is free."
        ConvPause.CALL -> "Paused for a call. It goes on by itself after."
        ConvPause.THERMAL -> "Paused: the phone is too hot. It goes on when it cools down."
        ConvPause.PANEL -> "Paused while you talk to Robot."
    }

    companion object {
        private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

        fun clock(createdAt: Long, seconds: Double): String =
            HMS.format(Instant.ofEpochMilli(createdAt + (seconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

        fun elapsed(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}
