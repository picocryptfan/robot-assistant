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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvPause
import app.heyrobot.core.ConversationLock
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.TranscriptExport
import app.heyrobot.core.TranscriptLine
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceMark
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.enumOr
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.SpeakerOffer
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.SimilarCard
import app.heyrobot.ui.SpeakerActions
import app.heyrobot.ui.SpeakerChip
import app.heyrobot.ui.SpeakerEdit
import app.heyrobot.ui.SpeakerEditDialog
import app.heyrobot.ui.SpeakerView
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.TickRow
import app.heyrobot.ui.theme.RobotTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// VOICE PROCESS. The Transcribe screen (docs/conversation-mode.md 5.3, phase 5.2): the live lines with their time and
// language (FR, EN, or "FR?" when the line was too short to tell: tap it to fix), the open segment's words in grey
// while you look (screen on, "Live words" on), Pause, Stop and the elapsed time. It observes ConversationSession with no
// IPC. It starts a session when opened to start one (the one-time consent reminder first), and shows over the lock
// screen only for a session started there, with nothing older (docs/conversation-mode.md 1.7). Speaker labels (phase
// 5.3): each line's chip has its speaker's colour and name; while the session runs and the phone is unlocked, tap a chip
// to name the speaker, merge them with another or say "this is me", long-press a line to give it to someone else or
// split it, and answer "S3 sounds like S1". The speaker model's download is offered once, with its size. Translation
// (phase 5.4): Translate and Speak in the top bar, each line's translation under it ("fast" when the fast engine made
// it; tap it for Robot's model's "Better translation"), and the Interpreter, the same session's other face (InterpreterUi),
// which keeps the screen on.

class ConversationActivity : ComponentActivity() {
    /** A start asked for and waiting for the consent tick: how it was asked, the transcript to resume, and the face. */
    private data class Pending(val entry: ConvEntry, val resume: String?, val mode: ConvMode)
    private val pending = mutableStateOf<Pending?>(null)
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
        checkSpeakerOffer()
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
        val mode = enumOr(i.getStringExtra(Transcripts.EXTRA_MODE), ConvMode.TRANSCRIBE)
        if (ConsentRecord.needed(Transcripts.consent(this), s)) pending.value = Pending(entry, resume, mode)
        else begin(entry, resume, mode)
    }

    /** Your changes to speakers go through the running session (its writer is the transcript's only one meanwhile). */
    private val liveEdits = object : SpeakerActions {
        override fun rename(speaker: String, name: String) = ConversationSession.rename(speaker, name).let {}
        override fun merge(from: String, to: String) = ConversationSession.merge(from, to).let {}
        override fun thisIsMe(speaker: String) = ConversationSession.thisIsMe(speaker).let {}
        override fun move(i: Int, speaker: String) = ConversationSession.move(i, speaker).let {}
        override fun split(i: Int, at: Int, speaker: String?) = ConversationSession.split(i, at, speaker).let {}
        override fun decline(from: String, to: String) = ConversationSession.decline(from, to).let {}
    }

    /** The speaker model's download is offered: not there, speaker labels not turned off, and not answered "Not now". */
    private val offerSpeakers = mutableStateOf(false)

    private fun checkSpeakerOffer() {
        val ready = try { VoiceMark.complete(VoiceBundle.SPEAKERS, VoiceBundle.dir(filesDir, VoiceBundle.SPEAKERS)) } catch (_: Exception) { false }
        offerSpeakers.value = ConversationSettings.offerSpeakerModel(SettingsStore.load(this), ready) && !SpeakerOffer.answered(this)
    }

    /** Settings › Conversation in Robot's app, where the download starts (downloads happen in the main app only). */
    private fun openSpeakerSettings() {
        if (locked()) return
        try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.CONVERSATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {}
    }

    private fun begin(entry: ConvEntry, resume: String?, mode: ConvMode = ConvMode.TRANSCRIBE) {
        pending.value = null
        if (!ConversationService.start(this, entry, resume, mode)) ConversationNotices.couldNotStart(this)
    }

    private fun setKeepOn(on: Boolean) {
        keepOn.value = on
        applyKeepOn(interpreting)
    }

    /** The interpreter keeps the screen on (docs/conversation-mode.md 1.4), as does "Keep the screen on". */
    private var interpreting = false

    private fun applyKeepOn(interpreter: Boolean) {
        interpreting = interpreter
        if (keepOn.value || interpreter) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The summary screen for [id] (this process; needs the phone unlocked). */
    private fun openSummary(id: String?) {
        if (locked() || id == null) return
        try { startActivity(Transcripts.summaryIntent(this, id)) } catch (_: Throwable) {}
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
        val interpreter = ui.active && ui.mode == ConvMode.INTERPRET
        LaunchedEffect(interpreter) { applyKeepOn(interpreter) }
        var menu by remember { mutableStateOf(false) }
        var help by remember { mutableStateOf(false) }
        var fixing by remember { mutableStateOf<TranscriptLine?>(null) }
        var edit by remember { mutableStateOf<SpeakerEdit?>(null) }
        // Speakers are changed while the session runs, with the phone unlocked; afterwards in Robot › Transcripts.
        val editable = ui.active && ui.status != ConversationSession.Status.STOPPING && !locked
        LaunchedEffect(editable) { if (!editable) edit = null }
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
                            if (interpreter) {
                                Spacer(Modifier.width(10.dp))
                                Text("Interpreter", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, "Back") } },
                    actions = {
                        if (ui.active) {
                            // Translate (a transcript's own toggle; the interpreter always translates) and Speak.
                            if (!interpreter) TextButton(onClick = { ConversationSession.setTranslate(!ui.translate) },
                                modifier = Modifier.semantics { contentDescription = "Translate ${ui.pair.title}: " + if (ui.translate) "on" else "off" }) {
                                Text(if (ui.translate) "Translating" else "Translate")
                            }
                            TextButton(onClick = { ConversationSession.setSpeak(!ui.speak) },
                                modifier = Modifier.semantics { contentDescription = "Speak translations: " + if (ui.speak) "on" else "off" }) {
                                Text(if (ui.speak) "Speak: on" else "Speak: off")
                            }
                            if (ui.status == ConversationSession.Status.PAUSED) TextButton(onClick = { ConversationSession.userResume() }) { Text("Resume") }
                            else TextButton(onClick = { ConversationSession.userPause() }, enabled = ui.status == ConversationSession.Status.RUNNING) { Text("Pause") }
                            TextButton(onClick = { ConversationSession.stop(app.heyrobot.core.ConvStop.USER) }, enabled = ui.status != ConversationSession.Status.STOPPING) { Text("Stop") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(RIcons.MoreVert, "More options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (ui.active) DropdownMenuItem(text = { Text(if (interpreter) "Transcript view" else "Interpreter (face to face)") },
                                    onClick = { menu = false; ConversationSession.setMode(if (interpreter) ConvMode.TRANSCRIBE else ConvMode.INTERPRET) })
                                if (interpreter) DropdownMenuItem(text = { Text("Their language: " + app.heyrobot.core.InterpreterVoice.name(ui.theirs) + " (change)") },
                                    onClick = { menu = false; ConversationSession.setTheirs(ui.mine) })
                                if (!interpreter) DropdownMenuItem(text = { Text(if (keepOn.value) "Let the screen turn off" else "Keep the screen on") }, onClick = { menu = false; setKeepOn(!keepOn.value) })
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
                // The interpreter, face to face: on the lock screen only for a session started there.
                if (interpreter && (!locked || ui.lockedStart)) {
                    if (!ui.fastEngine) Text("Translated by Robot's model on this phone (the fast engine isn't installed).",
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InterpreterScreen(ui, Modifier.weight(1f))
                    return@Column
                }
                val list = rememberLazyListState()
                // The newest line in view as lines come (the header, the lines, the live words, the footer).
                LaunchedEffect(ui.lines.size, ui.partial) {
                    val last = list.layoutInfo.totalItemsCount - 1
                    if (last > 0 && ui.lines.isNotEmpty()) list.animateScrollToItem(last)
                }
                // The lines on screen: Robot's model improves those first.
                LaunchedEffect(list) {
                    androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int }.toSet() }
                        .collect { ConversationSession.visible(it) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        if (ask != null) ConsentCard(onStart = { tick -> if (tick) { Transcripts.saveConsent(this@ConversationActivity); ask?.let { begin(it.entry, it.resume, it.mode) } } },
                            onCancel = { pending.value = null; finish() })
                        else if (!ui.active && (ui.lines.isEmpty() || locked)) StartCard(onStart = { mode ->
                            val s = SettingsStore.load(this@ConversationActivity)
                            if (ConsentRecord.needed(Transcripts.consent(this@ConversationActivity), s)) pending.value = Pending(ConvEntry.APP, null, mode)
                            else begin(ConvEntry.APP, null, mode)
                        })
                        else if (ui.active) Text(ConsentRecord.LINE, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // The speaker model, offered once (with its size), unlocked only.
                    if (offerSpeakers.value && !locked && ask == null) item {
                        SpeakerOfferCard(onDownload = { openSpeakerSettings() }, onLater = { SpeakerOffer.answer(this@ConversationActivity); offerSpeakers.value = false })
                    }
                    // Two speakers who sound alike: a merge offered, never made by itself.
                    val p = ui.proposal
                    if (p != null && editable) item(key = "similar") {
                        SimilarCard(SpeakerView(ui.speakers, ui.names, ui.me, ui.retired), p.first, p.second,
                            onMerge = { ConversationSession.merge(p.first, p.second) }, onDecline = { ConversationSession.decline(p.first, p.second) })
                    }
                    // On the lock screen only the lines of a session started there, while it runs: nothing older, and
                    // no live words of a session started unlocked either.
                    val shows = !locked || (ui.active && ui.lockedStart)
                    val visible = if (shows) ui.lines else emptyList()
                    items(visible, key = { it.i }) { l ->
                        LineRow(ui, l, onFix = { fixing = l }, onSpeaker = if (editable) ({ edit = SpeakerEdit.Menu(l.speaker) }) else null,
                            onLong = if (editable) ({ edit = SpeakerEdit.LineMenu(l) }) else null)
                    }
                    if (shows && ui.partial.isNotEmpty()) item {
                        Text(ui.partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
                    }
                    item {
                        if (!ui.active && ui.savedId != null) SavedCard(locked = locked, similarLeft = ui.similarLeft, onOpen = { openInApp(ui.savedId) },
                            onAgain = { pending.value = null; begin(ConvEntry.APP, null) }, onSummary = { openSummary(ui.savedId) })
                        Text("Transcribed on this phone. Nothing leaves it unless you send it.", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        SpeakerEditDialog(edit, SpeakerView(ui.speakers, ui.names, ui.me, ui.retired), liveEdits) { edit = it }
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
                    "language: tap it to fix. With speaker labels on (Settings › Conversation), each line says who spoke: S1, S2… or the " +
                    "name you give (tap the chip; long-press a line if someone else said it). Very short replies go to whoever spoke just " +
                    "before, two people talking at once go to one of them, and the same person near and far from the phone can become two " +
                    "speakers: merge them. Translate shows each line in the other language (fast first when the fast engine is installed, " +
                    "then Robot's model; tap a translation for a better one); Speak reads the translations aloud, and Robot doesn't listen " +
                    "while it speaks. The Interpreter (in the menu) is for two people face to face: the top half turns to them. " +
                    "Nothing said to Robot while its panel is open is " +
                    "transcribed, and “hey robot” sleeps during the session unless you turn on Settings › Conversation › “Hey robot works " +
                    "during a conversation”. The audio is never kept.")
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } },
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LineRow(ui: ConversationSession.Ui, l: TranscriptLine, onFix: () -> Unit, onSpeaker: (() -> Unit)?, onLong: (() -> Unit)?) {
        Column(if (onLong != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLong, onLongClickLabel = "Change who said it") else Modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(ui.createdAt, l.t0), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                // "Speaker" while only one voice is heard and nobody is named; else the speaker's colour and name.
                SpeakerChip(l.speaker, if (ui.oneVoice) "Speaker" else ui.nameOf(l.speaker), onSpeaker)
                Spacer(Modifier.width(8.dp))
                val tag = TranscriptExport.langTag(l)
                val unsure = l.prior || l.lang == null
                Surface(shape = RoundedCornerShape(8.dp), color = if (unsure) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = (if (unsure) Modifier.minimumInteractiveComponentSize() else Modifier)
                        .heightIn(min = 24.dp).clip(RoundedCornerShape(8.dp)).then(if (unsure) Modifier.clickable(onClick = onFix) else Modifier)
                        .semantics { contentDescription = if (unsure) "Language: $tag, not sure. Tap to fix." else "Language: $tag" }) {
                    Text(tag, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
                if (l.reheard) {
                    Spacer(Modifier.width(8.dp))
                    Text("heard again", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(l.text, style = MaterialTheme.typography.bodyLarge)
            // Its translation, smaller, in italics: "fast" for the fast engine; a tap asks Robot's model for a better one.
            val tr = l.translations.values.firstOrNull()
            if (tr != null) Row(Modifier.clickable(onClickLabel = "Better translation") { ConversationSession.better(l.i) }.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (tr.fast) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("fast", Modifier.padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(tr.text, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (l.i in ui.translating) Text("Translating…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (ui.translate && ui.pair.has(l.lang)) Text("Translate", Modifier.clickable { ConversationSession.better(l.i) }.padding(top = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun SpeakerOfferCard(onDownload: () -> Unit, onLater: () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tell speakers apart?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Robot can label who says what (S1, S2…, or the names you give) by their voices, on this phone. It needs the speaker " +
                    "model: ${VoiceModelLogic.size(VoiceBundle.SPEAKERS.bytes)}, downloaded once in Robot › Settings › Conversation.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download (${VoiceModelLogic.size(VoiceBundle.SPEAKERS.bytes)})") }
                    OutlinedButton(onClick = onLater, modifier = Modifier.heightIn(min = 48.dp)) { Text("Not now") }
                }
            }
        }
    }

    @Composable
    private fun ConsentCard(onStart: (Boolean) -> Unit, onCancel: () -> Unit) {
        var tick by remember { mutableStateOf(false) }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Before you start", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(ConsentRecord.TEXT, style = MaterialTheme.typography.bodyLarge)
                TickRow(ConsentRecord.TICK, tick, { tick = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    Button(onClick = { onStart(tick) }, enabled = tick, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start") }
                }
            }
        }
    }

    @Composable
    private fun StartCard(onStart: (ConvMode) -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Transcribe a conversation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Robot writes down what it hears, in French and English, and saves it on this phone. It keeps going with the screen off. " +
                    "Or translate face to face: the phone between you, each half in one language, the translations read aloud.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { onStart(ConvMode.TRANSCRIBE) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(RIcons.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start")
                    }
                    OutlinedButton(onClick = { onStart(ConvMode.INTERPRET) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Interpreter") }
                }
            }
        }
    }

    @Composable
    private fun SavedCard(locked: Boolean, similarLeft: Int, onOpen: () -> Unit, onAgain: () -> Unit, onSummary: () -> Unit) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saved", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(if (locked) "Unlock your phone to read, export or delete it in Robot › Transcripts." else "It's in Robot › Transcripts, to read, export or delete.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (similarLeft > 0 && !locked) Text(if (similarLeft == 1) "Two speakers sounded alike: open it to merge them if they are one person."
                    else "$similarLeft pairs of speakers sounded alike: open it to merge the ones who are one person.",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!locked) OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
                    if (!locked) OutlinedButton(onClick = onSummary, modifier = Modifier.heightIn(min = 48.dp)) { Text("Summarize") }
                    OutlinedButton(onClick = onAgain, modifier = Modifier.heightIn(min = 48.dp)) { Text("New") }
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
