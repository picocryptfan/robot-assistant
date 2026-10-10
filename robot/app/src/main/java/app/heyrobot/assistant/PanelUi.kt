package app.heyrobot.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Attachment
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.ActionView
import app.heyrobot.ui.common.AssistantBubble
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.UserBubble
import kotlinx.coroutines.launch

// VOICE PROCESS. The assistant panel: a bottom sheet over whatever is on screen, with the conversation,
// a text field, push-to-talk, the camera, "Ask about this screen" and a stop button.

@Composable
fun AssistantPanel(onClose: () -> Unit) {
    val st by PanelController.state.collectAsState()
    var input by rememberSaveable(st.convId) { mutableStateOf("") }
    // The session (and this composition) survives a hide: a drag offset or an open attach row must not carry over
    // into the next show, or the panel opens shifted down with its input row off-screen.
    var attachOpen by remember(st.showId) { mutableStateOf(false) }
    var dragY by remember(st.showId) { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(st.focusRequest) {
        if (st.focusRequest > 0) {
            try { focus.requestFocus() } catch (_: Exception) {}
            keyboard?.show()
        }
    }

    // The field is cleared only when the question was taken (not while a turn is under way).
    fun send() {
        if (PanelController.send(input)) {
            input = ""
            attachOpen = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheet = maxHeight * 0.88f
        // Tapping outside the panel closes it.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .graphicsLayer { translationY = dragY.coerceAtLeast(0f) }
                // Swallow taps so they don't reach the scrim.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                // Handle and header: drag down to close.
                Column(
                    Modifier.fillMaxWidth().draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> dragY += d },
                        onDragStopped = { if (dragY > 140f) { dragY = 0f; onClose() } else dragY = 0f },
                    ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    }
                    Header(st, onClose)
                }
                AnimatedVisibility(st.notice != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    st.notice?.let { NoticeBar(it) }
                }
                BrainBanner(st)
                Messages(st, Modifier.weight(1f, fill = false))
                val hearing = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING
                if (st.turns.isEmpty() && !hearing) {
                    Suggestions(st.locked) { input = it; send() }
                }
                if (hearing) ListeningRow(st.partial, transcribing = st.phase == Phase.TRANSCRIBING)
                ContextRow(st)
                AnimatedVisibility(attachOpen && st.draft == null && !st.busy) { AttachRow(st.locked) { attachOpen = false } }
                InputRow(
                    st = st,
                    input = input,
                    onInput = { input = it },
                    onSend = ::send,
                    onAttach = { attachOpen = !attachOpen },
                    focus = focus,
                )
            }
        }
    }
}

@Composable
private fun Header(st: PanelState, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusOrb(st.phase)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Robot", style = MaterialTheme.typography.titleLarge)
            Text(statusLine(st), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!st.keep && st.turns.isNotEmpty()) {
            TextButton(onClick = PanelController::keepConversation, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Bookmark, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Keep")
            }
        }
        IconButton(onClick = PanelController::toggleSpeak, modifier = Modifier.size(48.dp)) {
            Icon(if (st.speak) RIcons.VolumeUp else RIcons.VolumeOff, if (st.speak) "Speaking replies: on" else "Speaking replies: off")
        }
        IconButton(onClick = { PanelController.newConversation() }, modifier = Modifier.size(48.dp)) { Icon(RIcons.Add, "New conversation") }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Close") }
    }
}

private fun statusLine(st: PanelState): String = when (st.phase) {
    Phase.LISTENING -> "Listening…"
    Phase.TRANSCRIBING -> "Writing it down…"
    Phase.THINKING -> if (st.brain.readiness == BrainKeeper.Readiness.LOADING) "Waking up the model…" else "Thinking…"
    Phase.ANSWERING -> "Answering…"
    Phase.CONFIRMING -> "Waiting for you: Do it or Cancel"
    Phase.SPEAKING -> "Speaking · tap to stop"
    Phase.IDLE -> when {
        st.locked -> "Locked: questions, timers and alarms"
        st.brain.readiness == BrainKeeper.Readiness.LOADING -> "Getting ready…"
        else -> "On this phone, private"
    }
}

/** The assistant's state at a glance: a pulsing ring while listening or speaking, a slower glow while thinking. */
@Composable
private fun StatusOrb(phase: Phase) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(if (phase == Phase.THINKING || phase == Phase.ANSWERING) 900 else 600), RepeatMode.Reverse), label = "pulse")
    val active = phase != Phase.IDLE
    val ring by animateFloatAsState(if (active) pulse else 1f, label = "ring")
    val c = when (phase) {
        Phase.LISTENING, Phase.TRANSCRIBING -> MaterialTheme.colorScheme.primary
        Phase.THINKING, Phase.ANSWERING -> MaterialTheme.colorScheme.tertiary
        Phase.SPEAKING, Phase.CONFIRMING -> MaterialTheme.colorScheme.secondary
        Phase.IDLE -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(44.dp).scale(ring).clip(CircleShape).background(c.copy(alpha = 0.22f)))
        Box(Modifier.size(36.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
            Icon(RIcons.Robot, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.contentColorFor(c))
        }
    }
}

@Composable
private fun NoticeBar(n: Notice) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Info, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(n.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (n.action == NoticeAction.OPEN_APP) TextButton(onClick = PanelController::openRobotApp) { Text("Open Robot") }
            IconButton(onClick = PanelController::dismissNotice, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Dismiss", Modifier.size(18.dp)) }
        }
    }
}

/** The model is missing or failed: say so before the person types a whole question. */
@Composable
private fun BrainBanner(st: PanelState) {
    val b = st.brain
    if (st.notice != null) return
    if (b.readiness != BrainKeeper.Readiness.NO_MODEL && b.readiness != BrainKeeper.Readiness.FAILED) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Warning, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(b.problem ?: "The model isn't ready.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (b.readiness == BrainKeeper.Readiness.NO_MODEL) TextButton(onClick = PanelController::openRobotApp) { Text("Open Robot") }
        }
    }
}

@Composable
private fun Messages(st: PanelState, modifier: Modifier) {
    if (st.turns.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(if (st.locked) "Ask a question, or set a timer or alarm." else "Ask me anything.", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (st.screenAvailable) "I can also look at what's on your screen."
                else "Type, take a photo, or say “hey robot” next time.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }
    val reversed = remember(st.turns) { st.turns.asReversed() }
    LazyColumn(
        modifier = modifier.fillMaxWidth().pointerInput(st.phase) {
            // Tapping the conversation stops speech (spec item 4).
            detectTapGestures { if (st.phase == Phase.SPEAKING) PanelController.stopSpeaking() }
        },
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(reversed, key = { it.id }) { t ->
            if (t.role == TurnRole.USER) UserBubble(t.text, t.attachment, t.thumb)
            else AssistantBubble(
                text = t.text, streaming = t.streaming, stopped = t.stopped, error = t.error,
                actions = t.actions.map { ActionView(it.id, it.tool, it.summary, it.status, it.detail, it.icon) },
                onConfirm = PanelController::confirm,
            )
        }
    }
}

@Composable
private fun Suggestions(locked: Boolean, onPick: (String) -> Unit) {
    val items = if (locked) listOf("Set a timer for 5 minutes", "Set an alarm for 7:00", "Tell me a fun fact")
    else listOf("Set a timer for 10 minutes", "Turn on the flashlight", "What can you do?")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in items) SuggestionChip(onClick = { onPick(s) }, label = { Text(s, maxLines = 2) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
    }
}

@Composable
private fun ListeningRow(partial: String, transcribing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val t = rememberInfiniteTransition(label = "listen")
        val s by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "mic")
        Box(Modifier.size(36.dp).scale(s).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(RIcons.Mic, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(partial.ifEmpty { if (transcribing) "Writing it down…" else "Listening…" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!transcribing) TextButton(onClick = PanelController::micReleased) { Text("Done") }
    }
}

/** "Ask about this screen", or the picture that goes with the next question. */
@Composable
private fun ContextRow(st: PanelState) {
    val d = st.draft
    if (d != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (d.thumb != null) {
                    Image(d.thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(RIcons.Phone, null)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (d.kind == Attachment.SCREEN) "Your screen" else "Photo", style = MaterialTheme.typography.titleSmall)
                Text("Goes with your next question", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = PanelController::removeDraft, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Remove") }
        }
    } else if (st.screenAvailable && !st.busy) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            AssistChip(
                onClick = PanelController::attachScreen,
                label = { Text("Ask about this screen") },
                leadingIcon = { Icon(RIcons.Phone, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun AttachRow(locked: Boolean, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigChoice(RIcons.Camera, "Camera", Modifier.weight(1f)) { onDone(); PanelController.openCapture(CaptureActivity.MODE_CAMERA) }
        BigChoice(RIcons.Image, "Photos", Modifier.weight(1f)) { onDone(); PanelController.openCapture(CaptureActivity.MODE_PHOTOS) }
    }
    if (locked) Text("Unlock to use the camera or your photos.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun BigChoice(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun InputRow(st: PanelState, input: String, onInput: (String) -> Unit, onSend: () -> Unit, onAttach: () -> Unit, focus: FocusRequester) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // No camera while a turn runs: the panel would hide with the answer still going.
        IconButton(onClick = onAttach, enabled = !st.busy, modifier = Modifier.size(52.dp)) { Icon(RIcons.Camera, "Add a photo") }
        TextField(
            value = input,
            onValueChange = onInput,
            placeholder = { Text(if (st.draft != null) "Ask about it…" else "Ask Robot…") },
            shape = RoundedCornerShape(26.dp),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            // While a turn runs the keyboard's Send does nothing (the text stays for later).
            keyboardActions = KeyboardActions(onSend = { if (!st.busy) onSend() }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        val canSend = input.isNotBlank() || st.draft != null
        when {
            st.busy -> FilledIconButton(onClick = PanelController::stopGenerating, modifier = Modifier.size(56.dp)) { Icon(RIcons.Stop, "Stop") }
            st.phase == Phase.SPEAKING && !canSend -> FilledTonalIconButton(onClick = PanelController::stopSpeaking, modifier = Modifier.size(56.dp)) { Icon(RIcons.Stop, "Stop speaking") }
            canSend -> FilledIconButton(onClick = onSend, modifier = Modifier.size(56.dp)) { Icon(RIcons.Send, "Send") }
            else -> MicButton(listening = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING, hint = st.micHint)
        }
    }
}

/**
 * Tap to talk (ends on silence), or hold and release (push-to-talk). While voice input can't work ([hint], e.g.
 * "Download voice models"), the tooltip says why when the panel opens and on every press; the press still shows
 * the notice and the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MicButton(listening: Boolean, hint: String?) {
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(hint) { if (hint != null) tip.show() }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(hint.orEmpty()) } },
        state = tip,
        focusable = false,
        enableUserInput = false,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape)
                .background(if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
                .semantics {
                    contentDescription = if (hint != null) "Talk to Robot. $hint" else "Talk to Robot"
                    role = Role.Button
                    onClick(label = "Talk") { PanelController.micPressed(); true }
                }
                .pointerInput(hint) {
                    detectTapGestures(onPress = {
                        if (hint != null) scope.launch { tip.show() }
                        val t0 = System.currentTimeMillis()
                        PanelController.micPressed()
                        val released = tryAwaitRelease()
                        if (released && System.currentTimeMillis() - t0 > 450) PanelController.micReleased()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RIcons.Mic, null, tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
