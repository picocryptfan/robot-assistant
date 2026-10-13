package app.heyrobot.ui.classic

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.LockScreen
import app.heyrobot.core.Markdown
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.Tip
import app.heyrobot.core.ToolName
import app.heyrobot.ui.common.ActionView
import app.heyrobot.ui.common.CardActions
import app.heyrobot.ui.common.TermuxCard
import app.heyrobot.ui.common.MarkdownText
import app.heyrobot.ui.common.OfferView
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.common.RobotFace
import app.heyrobot.ui.common.RobotFaceIcon
import app.heyrobot.ui.common.answerSemantics
import app.heyrobot.ui.common.TurnActions
import app.heyrobot.ui.common.TurnUi
import app.heyrobot.ui.common.spans
import app.heyrobot.ui.common.toolIcon
import kotlinx.coroutines.launch

// Main and voice processes. The Classic look (Settings › Display): Robot 0.6.0's assistant panel and chat bubbles,
// copied here as they were (the round robot orb, chat bubbles, typing dots and caret, the pill text box, the round mic,
// the attach row and the tonal cards), so whoever prefers them gets them back exactly. A frozen copy: no new features;
// a new card or notice is drawn by the standard part in both looks. Like every ui/ file it never touches the
// assistant's classes: the panel (assistant/PanelUi.kt) passes plain values and what each tap does. The test tags
// (classic-orb, classic-bubble) let the tests tell the looks apart; they change nothing on screen.

// ------------------------------------------------------------------ the sheet's top

/** The old handle: 36 x 4 dp, 40 % onSurfaceVariant. */
@Composable
fun ClassicDragHandle() {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
    }
}

/** What the orb shows: the panel's phase as 0.6.0's StatusOrb grouped it. */
enum class OrbMood {
    /** Idle: still, primary. */
    IDLE,
    /** Listening or writing it down: primary, a quick pulse. */
    HEARING,
    /** Thinking or answering: tertiary, a slower glow. */
    WORKING,
    /** Speaking, or a card waiting: secondary, a quick pulse. */
    TALKING,
}

@Composable
fun ClassicHeader(
    mood: OrbMood,
    status: String,
    showKeep: Boolean,
    speak: Boolean,
    onKeep: () -> Unit,
    onToggleSpeak: () -> Unit,
    onNew: () -> Unit,
    onClose: () -> Unit,
    /** 0.7.0: Robot's Settings (the panel asks to unlock first on the lock screen). */
    onSettings: () -> Unit = {},
    /** 1.0: the robot inside the orb, by what Robot is doing. */
    face: RobotFace = RobotFace.IDLE,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusOrb(mood, face)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Robot", style = MaterialTheme.typography.titleLarge)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showKeep) {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(RIcons.Bookmark, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Keep")
            }
        }
        IconButton(onClick = onToggleSpeak, modifier = Modifier.size(48.dp)) {
            Icon(if (speak) RIcons.VolumeUp else RIcons.VolumeOff, if (speak) "Speaking replies: on" else "Speaking replies: off")
        }
        IconButton(onClick = onNew, modifier = Modifier.size(48.dp)) { Icon(RIcons.Add, "New conversation") }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(RIcons.Settings, "Settings") }
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Close") }
    }
}

/**
 * The assistant's state at a glance: a pulsing ring while listening or speaking, a slower glow while thinking. 1.0: the
 * robot inside changes too ([face]), in the orb's own content colour so it keeps its contrast on every mood.
 */
@Composable
private fun StatusOrb(mood: OrbMood, face: RobotFace) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(if (mood == OrbMood.WORKING) 900 else 600), RepeatMode.Reverse), label = "pulse")
    val active = mood != OrbMood.IDLE
    val ring by animateFloatAsState(if (active) pulse else 1f, label = "ring")
    val c = when (mood) {
        OrbMood.HEARING -> MaterialTheme.colorScheme.primary
        OrbMood.WORKING -> MaterialTheme.colorScheme.tertiary
        OrbMood.TALKING -> MaterialTheme.colorScheme.secondary
        OrbMood.IDLE -> MaterialTheme.colorScheme.primary
    }
    Box(Modifier.size(44.dp).testTag("classic-orb"), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(44.dp).scale(ring).clip(CircleShape).background(c.copy(alpha = 0.22f)))
        Box(Modifier.size(36.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
            RobotFaceIcon(face, tint = MaterialTheme.colorScheme.contentColorFor(c), modifier = Modifier.size(26.dp))
        }
    }
}

// ------------------------------------------------------------------ above the conversation

/** A helper's conversation is on: every question goes to [name] until "Back to Robot". */
@Composable
fun ClassicHelperModeChip(name: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        AssistChip(
            onClick = onBack,
            label = { Text("Talking with $name · Back to Robot") },
            leadingIcon = { Icon(RIcons.Cloud, null, Modifier.size(AssistChipDefaults.IconSize), tint = MaterialTheme.colorScheme.tertiary) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, labelColor = MaterialTheme.colorScheme.onTertiaryContainer),
            modifier = Modifier.heightIn(min = 48.dp),
        )
    }
}

/** A notice, with its one button when it has one ([action]: "Open Robot", "Open settings", "Open Model"). */
@Composable
fun ClassicNoticeBar(text: String, action: String?, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Info, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (action != null) TextButton(onClick = onAction) { Text(action) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Dismiss", Modifier.size(18.dp)) }
        }
    }
}

/** The model is missing or failed ([problem]); [openRobot]: "Open Robot" to download it. */
@Composable
fun ClassicBrainBanner(problem: String?, openRobot: Boolean, onOpenRobot: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(RIcons.Warning, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(problem ?: "The model isn't ready.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (openRobot) TextButton(onClick = onOpenRobot) { Text("Open Robot") }
        }
    }
}

// ------------------------------------------------------------------ the empty panel

@Composable
fun ClassicEmptyState(locked: Boolean, lockedTools: Set<ToolName>, screenAvailable: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(if (locked) LockScreen.headline(lockedTools) else "Ask me anything.", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (screenAvailable) "I can also look at what's on your screen."
            else "Type, take a photo, or say “hey robot” next time.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Three tips that show what Robot can do (core Tips: only what is on and can work now, a new three each open). */
@Composable
fun ClassicSuggestions(tips: List<Tip>, onPick: (Tip) -> Unit) {
    if (tips.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in tips) SuggestionChip(onClick = { onPick(t) }, label = { Text(t.text, maxLines = 2) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
    }
}

@Composable
fun ClassicListeningRow(partial: String, transcribing: Boolean, card: Boolean = false, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val t = rememberInfiniteTransition(label = "listen")
        val s by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "mic")
        Box(Modifier.size(36.dp).scale(s).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(RIcons.Mic, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Text(partial.ifEmpty { if (transcribing) "Writing it down…" else if (card) "Listening: yes or no?" else "Listening…" },
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!transcribing) TextButton(onClick = onDone) { Text("Done") }
    }
}

// ------------------------------------------------------------------ under the conversation

/**
 * The picture or screen that goes with the next question ([draft], with [thumb]), or "Read Claude's answer" over the
 * Claude app ([readClaude]).
 */
@Composable
fun ClassicContextRow(draft: Attachment?, thumb: Bitmap?, readClaude: Boolean, onRemove: () -> Unit, onReadClaude: () -> Unit) {
    if (draft != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (thumb != null) {
                    Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(RIcons.Phone, null)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (draft == Attachment.SCREEN) "Your screen" else "Photo", style = MaterialTheme.typography.titleSmall)
                Text("Goes with your next question", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) { Icon(RIcons.Close, "Remove") }
        }
    } else if (readClaude) {
        // Over the Claude app (your corner swipe): its last answer, read aloud in Claude's voice.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            AssistChip(
                onClick = onReadClaude,
                label = { Text("Read Claude's answer") },
                leadingIcon = { Icon(RIcons.Cloud, null, Modifier.size(AssistChipDefaults.IconSize)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
fun ClassicAttachRow(locked: Boolean, onDone: () -> Unit, onCamera: () -> Unit, onPhotos: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigChoice(RIcons.Camera, "Camera", Modifier.weight(1f)) { onDone(); onCamera() }
        BigChoice(RIcons.Image, "Photos", Modifier.weight(1f)) { onDone(); onPhotos() }
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

/** What the old input row needs to know about the panel. */
data class ClassicInputState(
    /** A turn is under way (a waiting card included). */
    val busy: Boolean,
    /** A card waits for its answer. */
    val confirming: Boolean,
    /** Robot reads its reply aloud. */
    val speaking: Boolean,
    /** Listening for a question, or writing it down. */
    val listening: Boolean,
    /** Listening for the waiting card's yes or no. */
    val cardListening: Boolean,
    val hasDraft: Boolean,
    val screenChip: ScreenChip,
    val micHint: String?,
)

/** What the old input row's buttons do. */
class ClassicInputActions(
    val attach: () -> Unit,
    val attachScreen: () -> Unit,
    val stop: () -> Unit,
    val stopSpeaking: () -> Unit,
    val micPressed: () -> Unit,
    val micReleased: () -> Unit,
)

@Composable
fun ClassicInputRow(st: ClassicInputState, input: String, onInput: (String) -> Unit, onSend: () -> Unit, focus: FocusRequester, actions: ClassicInputActions) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // No camera while a turn runs: the panel would hide with the answer still going.
        IconButton(onClick = actions.attach, enabled = !st.busy, modifier = Modifier.size(52.dp)) { Icon(RIcons.Camera, "Add a photo") }
        if (st.screenChip != ScreenChip.HIDDEN) ScreenButton(st, actions.attachScreen)
        TextField(
            value = input,
            onValueChange = onInput,
            placeholder = { Text(if (st.confirming) "Yes or no…" else if (st.hasDraft) "Ask about it…" else "Ask Robot…") },
            shape = RoundedCornerShape(26.dp),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            // While a turn runs the keyboard's Send does nothing (the text stays for later), except to answer a waiting card.
            keyboardActions = KeyboardActions(onSend = { if (!st.busy || st.confirming) onSend() }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).focusRequester(focus),
        )
        Spacer(Modifier.width(8.dp))
        val canSend = input.isNotBlank() || st.hasDraft
        when {
            // A card waits: the mic (or Send, for a typed "yes" or "no") answers it; Stop still declines it and ends the answer.
            st.confirming -> {
                IconButton(onClick = actions.stop, modifier = Modifier.size(48.dp)) { Icon(RIcons.Stop, "Stop") }
                if (canSend) FilledIconButton(onClick = onSend, modifier = Modifier.size(56.dp)) { Icon(RIcons.Send, "Send") }
                else MicButton(listening = st.cardListening, hint = st.micHint, actions = actions)
            }
            st.busy -> FilledIconButton(onClick = actions.stop, modifier = Modifier.size(56.dp)) { Icon(RIcons.Stop, "Stop") }
            st.speaking && !canSend -> FilledTonalIconButton(onClick = actions.stopSpeaking, modifier = Modifier.size(56.dp)) { Icon(RIcons.Stop, "Stop speaking") }
            canSend -> FilledIconButton(onClick = onSend, modifier = Modifier.size(56.dp)) { Icon(RIcons.Send, "Send") }
            else -> MicButton(listening = st.listening, hint = st.micHint, actions = actions)
        }
    }
}

/**
 * "Ask about this screen", next to the camera: on every corner-swipe and wake-word panel. With the screen here, a tap
 * attaches it; without it, the button is dimmed with a dot, and a tap says why (core ScreenAccess), as the chip did.
 */
@Composable
private fun ScreenButton(st: ClassicInputState, onClick: () -> Unit) {
    val ready = st.screenChip == ScreenChip.READY
    val state = ScreenAccess.iconDescription(st.screenChip)
    IconButton(
        onClick = onClick, enabled = !st.busy && !st.hasDraft,
        modifier = Modifier.size(52.dp).semantics { contentDescription = "Ask about this screen" + (state?.let { ". $it" } ?: "") },
    ) {
        BadgedBox(badge = { if (!ready) Badge(containerColor = MaterialTheme.colorScheme.tertiary) }) {
            // 1.0: the theme's outline colour (3:1 or more against the panel) instead of a faded one, with the dot.
            Icon(RIcons.ScreenShare, null, tint = if (ready) LocalContentColor.current else MaterialTheme.colorScheme.outline)
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
private fun MicButton(listening: Boolean, hint: String?, actions: ClassicInputActions) {
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
                    onClick(label = "Talk") { actions.micPressed(); true }
                }
                .pointerInput(hint) {
                    detectTapGestures(onPress = {
                        if (hint != null) scope.launch { tip.show() }
                        val t0 = System.currentTimeMillis()
                        actions.micPressed()
                        val released = tryAwaitRelease()
                        if (released && System.currentTimeMillis() - t0 > 450) actions.micReleased()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(RIcons.Mic, null, tint = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ------------------------------------------------------------------ the conversation: bubbles

/** One turn as 0.6.0's bubbles: the Classic look of ui/common TurnView, in the panel and in History. */
@Composable
fun ClassicBubbleTurn(t: TurnUi, actions: TurnActions, modifier: Modifier = Modifier) {
    if (t.user) UserBubble(t.text, t.attachment, t.thumb, modifier, screenText = t.screenText)
    else Column(modifier) {
        AssistantBubble(
            text = t.text, streaming = t.streaming, stopped = t.stopped, error = t.error, actions = t.cards, announce = t.announce,
            onConfirm = actions.confirm, cardActions = actions.card,
            fromHelper = t.helper, readBack = t.readBack, footer = t.footer, by = t.by, home = t.home,
        )
        t.web?.let { app.heyrobot.ui.common.WebAnswerCard(it, onOpen = { url -> actions.openSource(t.id, url) }, modifier = Modifier.padding(top = 6.dp)) }
        t.offer?.let { ClassicOfferCard(it, onDismiss = { actions.dismissOffer(t.id) }, onAccept = { actions.acceptOffer(t.id) }) }
        // 0.7: the reply card, the same card in Classic's colours.
        t.reply?.let { r -> app.heyrobot.ui.common.ReplyCard(r, actions.reply?.let { f -> { op: app.heyrobot.ui.common.ReplyOp -> f(t.id, op) } },
            Modifier.padding(top = 8.dp)) }
    }
}

@Composable
fun UserBubble(text: String, attachment: Attachment, thumb: Bitmap?, modifier: Modifier = Modifier, screenText: String? = null) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(), contentDescription = if (attachment == Attachment.SCREEN) "Screen" else "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(bottom = 4.dp).size(132.dp).clip(RoundedCornerShape(18.dp)),
            )
        } else if (attachment != Attachment.NONE) {
            AttachmentTag(attachment)
        }
        // "Keep screenshots and photos in history": the screen's text kept with the question, a few lines until tapped.
        if (!screenText.isNullOrBlank()) {
            var open by remember { mutableStateOf(false) }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 320.dp)
                    .clickable(onClickLabel = if (open) "Show less" else "Show all") { open = !open },
            ) {
                Text(screenText, style = MaterialTheme.typography.bodySmall, maxLines = if (open) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
                modifier = Modifier.widthIn(max = 320.dp).testTag("classic-bubble"),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

@Composable
private fun AttachmentTag(a: Attachment) {
    Row(
        Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (a == Attachment.SCREEN) RIcons.Phone else RIcons.Image, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(6.dp))
        Text(if (a == Attachment.SCREEN) "Screen" else "Photo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * A reply. [fromHelper]: a helper wrote it: a small label with its name ([by], "Helper" when it isn't known; "Claude,
 * from the Claude app" when [readBack]; a house for a server on your network, [home]) and the dynamic colour's tertiary
 * tone, so a helper's turn is never mistaken for Robot's. [footer]: "Jarvis searched the web · nasa.gov" and the cost
 * line, under the bubble.
 */
@Composable
fun AssistantBubble(
    text: String,
    streaming: Boolean,
    stopped: Boolean,
    error: Boolean,
    actions: List<ActionView>,
    modifier: Modifier = Modifier,
    onConfirm: ((String, Boolean) -> Unit)? = null,
    cardActions: CardActions? = null,
    fromHelper: Boolean = false,
    readBack: Boolean = false,
    footer: List<String> = emptyList(),
    by: String? = null,
    home: Boolean = false,
    announce: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        if (fromHelper && !error) {
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (home) RIcons.Home else RIcons.Cloud, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(5.dp))
                Text(if (readBack) "Claude, from the Claude app" else by ?: "Helper", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        for (a in actions) {
            ClassicActionCard(a, onConfirm, cardActions)
            Spacer(Modifier.size(6.dp))
        }
        if (text.isNotBlank() || (streaming && actions.isEmpty())) {
            Surface(
                color = when {
                    error -> MaterialTheme.colorScheme.errorContainer
                    fromHelper -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = when {
                    error -> MaterialTheme.colorScheme.onErrorContainer
                    fromHelper -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp),
                modifier = Modifier.widthIn(max = 340.dp).testTag("classic-bubble"),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp).answerSemantics(streaming, announce && text.isNotBlank())) {
                    if (text.isBlank()) TypingDots() else MarkdownText(text, streaming)
                }
            }
        }
        if (stopped) {
            Text("Stopped", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
        for (line in footer) {
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        }
    }
}

/**
 * One action: what it does (light Markdown: the person or title in bold), its state, and right under it the buttons
 * while it waits: "Do it" (or its own words, "Open in Cheogram for Mom") or the people and numbers to pick from. The
 * full text it carries comes last, at most about 8 lines tall and scrolling inside the card, so a long message never
 * pushes who it goes to and the buttons apart. The panel passes [onConfirm] and [more]; the history shows no buttons.
 */
@Composable
fun ClassicActionCard(a: ActionView, onConfirm: ((String, Boolean) -> Unit)?, more: CardActions? = null) {
    // 0.9: a Termux command's card, in this look's colours (ui/common TermuxCard).
    a.termux?.let { return TermuxCard(a, it, onConfirm, more?.termux) }
    val bad = a.status in setOf(ActionStatus.FAILED, ActionStatus.INVALID, ActionStatus.NEEDS_UNLOCK, ActionStatus.DISABLED, ActionStatus.SKIPPED,
        ActionStatus.NEEDS_PERMISSION, ActionStatus.NOT_FOUND, ActionStatus.NO_APP)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.icon != null) {
                    Image(a.icon.asImageBitmap(), null, Modifier.size(40.dp))
                } else {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(toolIcon(a.tool), null, Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(spans(Markdown.inline(a.summary), Color.Unspecified, caret = false), style = MaterialTheme.typography.titleSmall)
                    val line = when (a.status) {
                        ActionStatus.PENDING -> a.detail ?: if (a.choices.isNotEmpty()) "Which one?" else "Do you want Robot to do this?"
                        ActionStatus.DONE, ActionStatus.DRAFT_OPENED -> a.detail ?: a.status.label
                        else -> listOfNotNull(a.status.label, a.detail).joinToString(". ")
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                }
                if (a.status.succeeded) Icon(RIcons.Check, a.status.label, Modifier.size(22.dp))
                if (a.status == ActionStatus.NEEDS_UNLOCK) Icon(RIcons.Lock, "Locked", Modifier.size(22.dp))
            }
            a.picture?.let {
                Image(it.asImageBitmap(), "The picture that would be sent", Modifier.padding(top = 10.dp).heightIn(max = 160.dp).clip(RoundedCornerShape(12.dp)))
            }
            if (a.status == ActionStatus.PENDING && onConfirm != null) {
                if (a.choices.isNotEmpty() && more != null) {
                    var keep by remember(a.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.remember?.let { words ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = keep, role = Role.Checkbox) { keep = it },
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = keep, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(words, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        a.choices.forEachIndexed { i, label ->
                            FilledTonalButton(onClick = { more.choose(a.id, i, keep) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                        }
                        if (a.more > 0) {
                            Text("…and ${a.more} more. Say the full name to pick one of those.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f))
                        }
                        OutlinedButton(onClick = { onConfirm(a.id, false) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Cancel") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { onConfirm(a.id, false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(a.cancel ?: "Cancel") }
                        Button(onClick = { onConfirm(a.id, true) }, modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp)) { Text(a.confirm ?: "Do it") }
                    }
                }
            }
            val allowContacts = a.allowContacts && a.status == ActionStatus.PENDING
            if (more != null && (a.undo || a.status == ActionStatus.NEEDS_PERMISSION || a.openApp != null || allowContacts)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    if (a.undo) TextButton(onClick = { more.undo(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo") }
                    a.openApp?.let { label -> FilledTonalButton(onClick = { more.openApp(a.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) } }
                    if (a.status == ActionStatus.NEEDS_PERMISSION) FilledTonalButton(onClick = more.allow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow") }
                    if (allowContacts) TextButton(onClick = more.allow, modifier = Modifier.heightIn(min = 48.dp)) { Text("Allow contacts") }
                }
            }
            if (!a.body.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(a.body, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = BODY_MAX).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** About 8 lines of body text on a card; more scrolls inside it. */
private val BODY_MAX = 176.dp

/**
 * "Want me to check with Jarvis?" under a reply (any helper, by its name; "Want Jarvis's take as well?" under Robot's own
 * answer, after the local model first handed the question over), with what Send would send (and the start of the
 * screen's text); or, for a helper's app, "Open the ChatGPT app with: «…»". Nothing happens until the tap (or a yes, when nothing
 * personal goes).
 */
@Composable
fun ClassicOfferCard(o: OfferView, onDismiss: () -> Unit, onAccept: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.padding(top = 6.dp).widthIn(max = 340.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(RIcons.Cloud, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (!o.api) "Open the ${o.app} app with: «${o.text.take(120)}${if (o.text.length > 120) "…" else ""}»"
                    else if (o.asWell) "Want ${o.name}'s take as well?" else "Want me to check with ${o.name}?",
                    style = MaterialTheme.typography.titleSmall)
            }
            if (o.sends.isNotEmpty()) {
                Text("Sends: " + o.sends.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() } + ".", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp))
            }
            o.preview?.let {
                Text("“" + it.take(300).trim() + (if (it.length > 300) "…" else "") + "”", style = MaterialTheme.typography.bodySmall, maxLines = 6,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("No thanks") }
                FilledTonalButton(onClick = onAccept, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (o.api) "Send" else "Open the app")
                }
            }
        }
    }
}

@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        for (i in 0 until 3) {
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 180), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}
