package app.heyrobot.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Attachment
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.Tip
import app.heyrobot.ui.common.NoticeRow
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTokens
import kotlinx.coroutines.launch

// VOICE PROCESS. The assistant panel's parts in the standard look, as a system bottom sheet: a plain title with a
// status line and a thin progress bar while Robot works (no orb, nothing pulses), notices as neutral rows, a standard
// text field and a floating action button whose job follows the state (talk, send, stop). Each part takes the panel's
// state and PanelActions; the Classic look's parts are in ui/classic. Words are plain and impersonal (no "I", no
// "thinking"); what Robot says aloud is not changed here.

// ------------------------------------------------------------------ the sheet's top

/** The drag handle (32 x 4 dp in a 48 dp row, as a Material sheet's); TalkBack and Switch Access close the panel with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHandle(onClose: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { onClick(label = "Close") { onClose(); true } },
        contentAlignment = Alignment.Center,
    ) {
        BottomSheetDefaults.DragHandle()
    }
}

/** What the status line says, and the symbol before it when it means something. */
data class PanelStatus(val text: String, val icon: ImageVector? = null)

/** A helper's "Claude is thinking…" reads "Waiting for Claude…" here (the other helper words are kept). */
private val HELPER_THINKING = Regex("^(.+) is thinking…$")

fun panelStatus(st: PanelState): PanelStatus {
    val loading = st.brain.readiness == BrainKeeper.Readiness.LOADING
    return when (st.phase) {
        Phase.LISTENING -> PanelStatus("Listening…", RIcons.Mic)
        Phase.TRANSCRIBING -> PanelStatus("Transcribing…")
        Phase.THINKING -> PanelStatus(
            st.helperStatus?.let { s -> HELPER_THINKING.matchEntire(s)?.let { "Waiting for ${it.groupValues[1]}…" } ?: s }
                ?: if (loading) "Loading the model…" else "Working…",
        )
        Phase.ANSWERING -> PanelStatus(st.turns.lastOrNull()?.takeIf { it.claude }?.let { "${it.by ?: "Claude"} is answering…" } ?: "Answering…")
        Phase.CONFIRMING -> when (st.cardHearing) {
            Phase.LISTENING -> PanelStatus("Say yes or no", RIcons.Mic)
            Phase.TRANSCRIBING -> PanelStatus("Transcribing…")
            else -> PanelStatus("Confirm or cancel below")
        }
        Phase.SPEAKING -> PanelStatus("Reading aloud · tap to stop", RIcons.VolumeUp)
        Phase.IDLE -> when {
            st.claudeMode -> PanelStatus("Talking with ${st.helperName ?: "Claude"}: questions go " + if (st.helperHome) "to your server" else "online",
                if (st.helperHome) RIcons.Home else RIcons.Cloud)
            st.locked -> PanelStatus("Locked: " + LockScreen.allowed(st.lockedTools), RIcons.Lock)
            loading -> PanelStatus("Loading the model…")
            else -> PanelStatus("On this phone")
        }
    }
}

/** The thin progress bar under the header: only while speech is written down, Robot works, or the model loads. */
fun showsProgress(st: PanelState): Boolean = st.phase == Phase.TRANSCRIBING || st.phase == Phase.THINKING ||
    st.cardHearing == Phase.TRANSCRIBING || st.brain.readiness == BrainKeeper.Readiness.LOADING

/** "Robot", the status line under it, and the header's actions: Keep, Speak replies, New conversation, Close. */
@Composable
fun PanelHeader(st: PanelState, actions: PanelActions, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Robot", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                PanelStatusLine(st)
            }
            if (!st.keep && st.turns.isNotEmpty()) {
                TextButton(onClick = actions::keepConversation) {
                    Icon(RIcons.Bookmark, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Keep", maxLines = 1)
                }
            }
            // A toggle, so TalkBack says "Speak replies, checked" or "not checked".
            IconToggleButton(checked = st.speak, onCheckedChange = { actions.toggleSpeak() }) {
                Icon(if (st.speak) RIcons.VolumeUp else RIcons.VolumeOff, "Speak replies")
            }
            IconButton(onClick = actions::newConversation) { Icon(RIcons.Add, "New conversation") }
            IconButton(onClick = onClose) { Icon(RIcons.Close, "Close") }
        }
        if (showsProgress(st)) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
    }
}

/** One line, with a symbol only when it means something; a polite live region, so TalkBack says each new state once. */
@Composable
fun PanelStatusLine(st: PanelState) {
    val s = panelStatus(st)
    val c = MaterialTheme.colorScheme
    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (s.icon != null) {
            Icon(s.icon, null, Modifier.size(18.dp), tint = if (s.icon == RIcons.Mic) c.primary else c.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ above the conversation

/** A notice's button: its words and what it does, or null. */
internal fun noticeAction(a: NoticeAction, actions: PanelActions): Pair<String, () -> Unit>? = when (a) {
    NoticeAction.OPEN_APP -> "Open Robot" to actions::openRobotApp
    NoticeAction.OPEN_ASSIST_SETTINGS -> "Open settings" to actions::openAssistSettings
    NoticeAction.OPEN_MODEL -> "Open Model" to actions::openModelSettings
    NoticeAction.NONE -> null
}

/** A notice as a neutral row: an info symbol, the text, its button and "Dismiss". */
@Composable
fun PanelNotice(n: Notice, actions: PanelActions) {
    val a = noticeAction(n.action, actions)
    NoticeRow(n.text, onDismiss = actions::dismissNotice, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        action = a?.first, onAction = a?.second ?: {})
}

/** The model is missing or failed (a real problem, so the error container), with "Open Robot" to download one. */
@Composable
fun ModelProblem(st: PanelState, actions: PanelActions) {
    val b = st.brain
    if (st.notice != null) return
    if (b.readiness != BrainKeeper.Readiness.NO_MODEL && b.readiness != BrainKeeper.Readiness.FAILED) return
    val c = MaterialTheme.colorScheme
    Surface(color = c.errorContainer, contentColor = c.onErrorContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.heightIn(min = RobotTokens.minTouch).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Error, null)
            Spacer(Modifier.width(12.dp))
            Text(b.problem ?: "The model isn't ready.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (b.readiness == BrainKeeper.Readiness.NO_MODEL) {
                TextButton(onClick = actions::openRobotApp, colors = ButtonDefaults.textButtonColors(contentColor = c.onErrorContainer)) { Text("Open Robot") }
            }
        }
    }
}

/** A helper's conversation is on (the status line says which): "Back to Robot" ends it. */
@Composable
fun HelperModeChip(actions: PanelActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AssistChip(
            onClick = actions::endHelperMode,
            label = { Text("Back to Robot") },
            leadingIcon = { Icon(RIcons.Back, null, Modifier.size(AssistChipDefaults.IconSize)) },
        )
    }
}

// ------------------------------------------------------------------ the empty panel

/** One quiet line (the lock screen's when locked), and what the screen button does when the screen came along. */
@Composable
fun PanelEmptyState(st: PanelState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val c = MaterialTheme.colorScheme
        Text(if (st.locked) LockScreen.headline(st.lockedTools) else "Type a question or tap the microphone.",
            style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (st.screenAvailable) Text("The screen button adds what's on screen.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    }
}

/** The empty panel's tips as one-line suggestion chips in a row that scrolls. */
@Composable
fun PanelTips(tips: List<Tip>, onPick: (Tip) -> Unit) {
    if (tips.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tips) { t -> SuggestionChip(onClick = { onPick(t) }, label = { Text(t.text, maxLines = 1) }) }
    }
}

/** While listening: the words heard so far, as your line at the bottom of the conversation (nothing before the first word). */
@Composable
fun PendingLine(partial: String) {
    if (partial.isBlank()) return
    Text(partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

// ------------------------------------------------------------------ under the conversation

/**
 * The photo or screen that goes with the next question, as a list row ("Remove photo", "Remove screen"), or "Read
 * Claude's answer" over the Claude app.
 */
@Composable
fun ContextRow(st: PanelState, actions: PanelActions) {
    val d = st.draft
    val c = MaterialTheme.colorScheme
    if (d != null) {
        val screen = d.kind == Attachment.SCREEN
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d.thumb != null) {
                Image(d.thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
            } else {
                Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(c.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(if (screen) RIcons.ScreenShare else RIcons.Image, null, tint = c.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (screen) "This screen" else "Photo", style = MaterialTheme.typography.bodyLarge)
                Text("Sent with your next question", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            IconButton(onClick = actions::removeDraft) { Icon(RIcons.Close, if (screen) "Remove screen" else "Remove photo") }
        }
    } else if (st.claudeScreen && !st.busy) {
        // Over the Claude app (your corner swipe): its last answer, read aloud in Claude's voice.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            AssistChip(
                onClick = actions::readClaudeAnswer,
                label = { Text("Read Claude's answer") },
                leadingIcon = { Icon(RIcons.Cloud, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

/**
 * The camera button and its menu: "Take a photo", "Choose a photo" (both off on the lock screen, which says why). No
 * camera while a turn runs: the panel would hide with the answer still going.
 */
@Composable
fun AttachMenu(st: PanelState, open: Boolean, onOpen: (Boolean) -> Unit, actions: PanelActions) {
    Box {
        IconButton(onClick = { onOpen(!open) }, enabled = !st.busy) { Icon(RIcons.Camera, "Add a photo") }
        DropdownMenu(expanded = open && st.draft == null && !st.busy, onDismissRequest = { onOpen(false) }) {
            DropdownMenuItem(
                text = { Text("Take a photo") }, leadingIcon = { Icon(RIcons.Camera, null) }, enabled = !st.locked,
                onClick = { onOpen(false); actions.openCapture(CaptureActivity.MODE_CAMERA) },
            )
            DropdownMenuItem(
                text = { Text("Choose a photo") }, leadingIcon = { Icon(RIcons.Image, null) }, enabled = !st.locked,
                onClick = { onOpen(false); actions.openCapture(CaptureActivity.MODE_PHOTOS) },
            )
            if (st.locked) {
                Text("Unlock to use the camera or photos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * "Ask about this screen": a tap attaches the screen, or, when it didn't come, says why (core ScreenAccess); then the
 * symbol is dimmed and the reason is its state for TalkBack.
 */
@Composable
fun ScreenButton(st: PanelState, actions: PanelActions) {
    val ready = st.screenAvailable
    val state = ScreenAccess.iconDescription(st.screenChip)
    IconButton(
        onClick = actions::attachScreen, enabled = !st.busy && st.draft == null,
        modifier = Modifier.semantics {
            contentDescription = "Ask about this screen"
            if (state != null) stateDescription = state
        },
    ) {
        Icon(RIcons.ScreenShare, null, tint = if (ready) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f))
    }
}

/** What the text field says while empty (TalkBack reads it as the field's hint). */
fun inputPlaceholder(st: PanelState): String = when {
    st.cardHearing == Phase.LISTENING -> "Listening: yes or no?"
    st.phase == Phase.LISTENING -> "Listening…"
    st.phase == Phase.CONFIRMING -> "Type yes or no"
    st.draft != null -> if (st.draft.kind == Attachment.SCREEN) "Ask about this screen" else "Ask about the photo"
    else -> "Type a question"
}

/**
 * The input row: camera, screen, a standard filled text field (never narrower than 120 dp) and the floating action
 * button. While a card waits, a Cancel button (Stop: declines it and ends the answer) sits left of the button.
 */
@Composable
fun PanelInput(
    st: PanelState,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    attachOpen: Boolean,
    onAttachOpen: (Boolean) -> Unit,
    focus: FocusRequester,
    actions: PanelActions,
) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AttachMenu(st, attachOpen, onAttachOpen, actions)
        if (st.screenChip != ScreenChip.HIDDEN) ScreenButton(st, actions)
        Spacer(Modifier.width(4.dp))
        TextField(
            value = input,
            onValueChange = onInput,
            placeholder = { Text(inputPlaceholder(st)) },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            // While a turn runs the keyboard's Send does nothing (the text stays for later), except to answer a waiting card.
            keyboardActions = KeyboardActions(onSend = { if (!st.busy || st.phase == Phase.CONFIRMING) onSend() }),
            modifier = Modifier.weight(1f).widthIn(min = 120.dp).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        if (st.phase == Phase.CONFIRMING) IconButton(onClick = actions::stopGenerating) { Icon(RIcons.Stop, "Cancel") }
        MicFab(fabMode(st, input), st.micHint, actions, onSend)
    }
}

// ------------------------------------------------------------------ the floating action button

/** The one main button's job, which follows the state. */
enum class FabMode(val label: String, val icon: ImageVector) {
    /** Tap to talk (ends on silence), or hold and release. */
    TALK("Talk", RIcons.Mic),
    SEND("Send", RIcons.Send),
    /** Listening or writing it down: ends listening now. */
    STOP_LISTENING("Stop listening", RIcons.StopCircle),
    /** Robot works on the answer or writes it: Stop. */
    STOP_ANSWER("Stop answer", RIcons.Stop),
    /** Robot reads its reply aloud. */
    STOP_READING("Stop reading aloud", RIcons.Stop),
    /** A card waits: answer it by voice ("yes", "no"). */
    ANSWER_BY_VOICE("Answer by voice", RIcons.Mic);

    /** A press starts listening; a hold longer than a tap ends it on release (push-to-talk). */
    val talks: Boolean get() = this == TALK || this == ANSWER_BY_VOICE
}

/** The button's job for [st] with [input] in the field (text, or a photo or screen, can be sent). */
fun fabMode(st: PanelState, input: String): FabMode {
    val canSend = input.isNotBlank() || st.draft != null
    return when {
        // A card waits: the mic (or Send, for a typed "yes" or "no") answers it.
        st.phase == Phase.CONFIRMING -> when {
            canSend -> FabMode.SEND
            st.cardHearing != null -> FabMode.STOP_LISTENING
            else -> FabMode.ANSWER_BY_VOICE
        }
        st.busy -> FabMode.STOP_ANSWER
        st.phase == Phase.SPEAKING && !canSend -> FabMode.STOP_READING
        canSend -> FabMode.SEND
        st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING -> FabMode.STOP_LISTENING
        else -> FabMode.TALK
    }
}

/** The button's container: the primary colour while listening, secondary while reading aloud, else primaryContainer. */
fun fabContainer(mode: FabMode, c: ColorScheme) = when (mode) {
    FabMode.STOP_LISTENING -> c.primary
    FabMode.STOP_READING -> c.secondaryContainer
    else -> c.primaryContainer
}

/** A push shorter than this is a tap: listening ends on silence. Longer, it ends on release (push-to-talk). */
private const val HOLD_MS = 450L

/**
 * The panel's floating action button (56 dp, 16 dp corners, flat): talk, send or stop, as [mode] says. One button in
 * every state, so a hold that starts listening keeps its gesture when the button turns into "Stop listening", and the
 * release ends it. While voice input can't work ([hint], e.g. "Download voice models"), the tooltip says why when the
 * panel opens and on every press; the press still shows the notice and the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicFab(mode: FabMode, hint: String?, actions: PanelActions, onSend: () -> Unit) {
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val now by rememberUpdatedState(mode)
    val hintNow by rememberUpdatedState(hint)
    val send by rememberUpdatedState(onSend)
    val act by rememberUpdatedState(actions)
    val press = remember { MutableInteractionSource() }
    LaunchedEffect(hint) { if (hint != null) tip.show() }
    fun click(m: FabMode) = when (m) {
        FabMode.TALK, FabMode.ANSWER_BY_VOICE -> act.micPressed()
        FabMode.SEND -> send()
        FabMode.STOP_LISTENING -> act.micReleased()
        FabMode.STOP_ANSWER -> act.stopGenerating()
        FabMode.STOP_READING -> act.stopSpeaking()
    }
    val container = fabContainer(mode, MaterialTheme.colorScheme)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(hint.orEmpty()) } },
        state = tip,
        focusable = false,
        enableUserInput = false,
    ) {
        Box(
            Modifier.size(56.dp)
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .semantics {
                    contentDescription = if (mode.talks && hint != null) "${mode.label}. $hint" else mode.label
                    role = Role.Button
                    if (mode == FabMode.STOP_LISTENING) stateDescription = "Listening"
                    onClick(label = mode.label) { click(mode); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        // The job when the finger went down: a hold that starts listening ends it on release.
                        val pressed = now
                        val p = PressInteraction.Press(down.position)
                        scope.launch { press.emit(p) }
                        if (pressed.talks) {
                            if (hintNow != null) scope.launch { tip.show() }
                            act.micPressed()
                        }
                        val up = waitForUpOrCancellation()
                        scope.launch { press.emit(if (up != null) PressInteraction.Release(p) else PressInteraction.Cancel(p)) }
                        if (up != null) {
                            up.consume()
                            if (!pressed.talks) click(pressed)
                            else if (up.uptimeMillis - down.uptimeMillis > HOLD_MS) act.micReleased()
                        }
                    }
                }
                .indication(press, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.contentColorFor(container)) { Icon(mode.icon, null) }
        }
    }
}
