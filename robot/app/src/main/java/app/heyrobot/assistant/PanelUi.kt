package app.heyrobot.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.heyrobot.core.KeyboardPad
import app.heyrobot.core.LockScreen
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.Tip
import app.heyrobot.core.TipKind
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.ui.classic.ClassicAttachRow
import app.heyrobot.ui.classic.ClassicBrainBanner
import app.heyrobot.ui.classic.ClassicContextRow
import app.heyrobot.ui.classic.ClassicDragHandle
import app.heyrobot.ui.classic.ClassicEmptyState
import app.heyrobot.ui.classic.ClassicHeader
import app.heyrobot.ui.classic.ClassicHelperModeChip
import app.heyrobot.ui.classic.ClassicInputActions
import app.heyrobot.ui.classic.ClassicInputRow
import app.heyrobot.ui.classic.ClassicInputState
import app.heyrobot.ui.classic.ClassicListeningRow
import app.heyrobot.ui.classic.ClassicNoticeBar
import app.heyrobot.ui.classic.ClassicSuggestions
import app.heyrobot.ui.classic.OrbMood
import app.heyrobot.ui.common.ActionView
import app.heyrobot.ui.common.LockedDivider
import app.heyrobot.ui.common.WithLockedDivider
import app.heyrobot.ui.common.lockedDividerAbove
import app.heyrobot.ui.common.CardActions
import app.heyrobot.ui.common.OfferView
import app.heyrobot.ui.common.TurnActions
import app.heyrobot.ui.common.TurnUi
import app.heyrobot.ui.common.TurnView
import app.heyrobot.ui.common.replyFooter
import app.heyrobot.ui.common.transcriptGap
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.LookStyle
import app.heyrobot.ui.theme.RobotTokens
import kotlinx.coroutines.delay

// VOICE PROCESS. The assistant panel: a bottom sheet over whatever is on screen, with the conversation,
// a text field, push-to-talk, the camera, "Ask about this screen" and a stop button. One panel for both looks (the
// slots at the end pick each look's leaves); keyboardPad() and the sheet's size and drag are the 0.4.2 keyboard fix,
// confirmed on the phone: leave them as they are.

@Composable
fun AssistantPanel(onClose: () -> Unit) {
    val st by PanelController.state.collectAsState()
    AssistantPanel(st, PanelActions.Default, onClose)
}

/** The panel for [st]; its parts ask [actions] for everything they do (PanelActions.Default on screen). */
@Composable
fun AssistantPanel(st: PanelState, actions: PanelActions, onClose: () -> Unit) {
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
    // A card waiting for a tap gets the room: the keyboard goes away, so who it goes to and its buttons are in view.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(st.phase) {
        if (st.phase == Phase.CONFIRMING) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }

    // 0.4.6: the screen stays on while Robot answers or reads her reply aloud. A long story outlasts the screen timeout,
    // and with the screen off the panel isn't visible: Android then runs the voice process in the background, where the
    // microphone and the "stop" spotter may not keep up, and the Stop button can't be seen. Back to the phone's own
    // timeout as soon as she is done or stopped.
    val view = LocalView.current
    val answering = st.phase == Phase.THINKING || st.phase == Phase.ANSWERING || st.phase == Phase.SPEAKING
    DisposableEffect(view, answering) {
        view.keepScreenOn = answering
        onDispose { view.keepScreenOn = false }
    }

    // The field is cleared only when the question was taken (not while a turn is under way).
    fun send() {
        if (actions.send(input)) {
            input = ""
            attachOpen = false
        }
    }

    val (bottomPad, slidUp) = keyboardPad()
    val chrome = sheetChrome()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A window Android slid up for the keyboard shows that much less of the panel's top.
        val maxSheet = (maxHeight - slidUp) * 0.88f
        // Tapping outside the panel closes it.
        Box(Modifier.fillMaxSize().background(chrome.scrim).pointerInput(Unit) { detectTapGestures { onClose() } })
        Surface(
            shape = chrome.shape,
            color = chrome.container,
            tonalElevation = chrome.tonalElevation,
            shadowElevation = chrome.shadowElevation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Landscape and large screens: centred, at most 640 dp wide.
                .widthIn(max = chrome.maxWidth)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .graphicsLayer { translationY = dragY.coerceAtLeast(0f) }
                // Swallow taps so they don't reach the scrim.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(bottom = bottomPad)) {
                // Handle and header: drag down to close.
                Column(
                    Modifier.fillMaxWidth().draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> dragY += d },
                        onDragStopped = { if (dragY > 140f) { dragY = 0f; onClose() } else dragY = 0f },
                    ),
                ) {
                    PanelTop(st, actions, onClose)
                }
                PanelNotices(st, actions)
                Messages(st, actions, Modifier.weight(1f, fill = false))
                // Listening for a question, or for the waiting card's "yes" or "no".
                val hearing = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING || st.cardHearing != null
                if (st.turns.isEmpty() && !hearing) {
                    Tips(st.tips) { tip ->
                        when (tip.kind) {
                            TipKind.ASK -> { input = tip.text; send() }
                            // It would do something or go online: its words wait in the field, to change or send.
                            TipKind.FILL -> {
                                input = tip.text
                                try { focus.requestFocus() } catch (_: Exception) {}
                                keyboard?.show()
                            }
                            TipKind.SCREEN -> actions.askAboutScreen(tip.text)
                            TipKind.PHOTO -> attachOpen = true
                        }
                    }
                }
                if (hearing) ListeningIndicator(st, actions)
                PanelInputArea(st, input, { input = it }, ::send, attachOpen, { attachOpen = it }, focus, actions)
            }
        }
    }
}

/**
 * The panel's bottom padding (above the navigation bar, or above the keyboard, less whatever Android already slid the
 * window up for it: core KeyboardPad), and that slide. The slide is read from the window's position on screen before
 * each frame.
 */
@Composable
private fun keyboardPad(): Pair<Dp, Dp> {
    val density = LocalDensity.current
    val view = LocalView.current
    val imeInsets = WindowInsets.ime
    val ime = imeInsets.getBottom(density)
    val nav = WindowInsets.navigationBars.getBottom(density)
    var slid by remember { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val at = IntArray(2)
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            view.rootView.getLocationOnScreen(at)
            val s = -at[1]
            if (s != slid) slid = s
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }
    val pad = KeyboardPad.bottom(nav, ime, slid)
    // Once the keyboard has settled: its height, the slide and the padding, as numbers (Settings › Voice › Voice log).
    val open = ime > nav
    LaunchedEffect(open) {
        if (open) {
            delay(700)
            val now = imeInsets.getBottom(density)
            VoiceLog.add(VoiceEvent.KEYBOARD, now, slid.coerceAtLeast(0), KeyboardPad.bottom(nav, now, slid))
        }
    }
    return with(density) { pad.toDp() to slid.coerceAtLeast(0).toDp() }
}

/** The sheet's scrim, container, corners and widest width. */
private class SheetChrome(val scrim: Color, val container: Color, val shape: Shape, val tonalElevation: Dp, val shadowElevation: Dp, val maxWidth: Dp)

@Composable
private fun sheetChrome(): SheetChrome {
    val c = MaterialTheme.colorScheme
    return when (LocalLook.current) {
        // Material's modal bottom sheet: a 32 % scrim, surfaceContainerLow without a tonal tint (a level 1 shadow), the
        // 28 dp top corners, at most 640 dp wide.
        LookStyle.STOCK -> SheetChrome(
            scrim = c.scrim.copy(alpha = RobotTokens.scrimAlpha), container = c.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
            tonalElevation = 0.dp, shadowElevation = 1.dp, maxWidth = RobotTokens.sheetMaxWidth,
        )
        // 0.6.0's: a 36 % black scrim, surfaceContainer with a 2 dp tonal tint, the whole width.
        LookStyle.CLASSIC -> SheetChrome(
            scrim = c.scrim.copy(alpha = 0.36f), container = c.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 2.dp, shadowElevation = 0.dp, maxWidth = Dp.Unspecified,
        )
    }
}

// ------------------------------------------------------------------ the look's slots
// The panel above is the same in both looks (state, focus, keyboard, drag, send). Only these leaves differ, by the
// look in LocalLook (Settings › Display › Classic look): the standard parts (PanelParts.kt, ui/common Transcript.kt
// and Chat.kt) or Robot 0.6.0's (ui/classic), which take plain values and what each tap does.

/** The handle and the header (with the progress bar), or the Classic handle and orb. */
@Composable
private fun PanelTop(st: PanelState, actions: PanelActions, onClose: () -> Unit) = when (LocalLook.current) {
    LookStyle.STOCK -> {
        SheetHandle(onClose)
        PanelHeader(st, actions, onClose)
    }
    LookStyle.CLASSIC -> {
        ClassicDragHandle()
        ClassicHeader(
            mood = orbMood(st.phase), status = classicStatusLine(st), showKeep = !st.keep && st.turns.isNotEmpty(), speak = st.speak,
            onKeep = actions::keepConversation, onToggleSpeak = actions::toggleSpeak, onNew = actions::newConversation, onClose = onClose,
            onSettings = actions::openSettings, face = st.face,
        )
    }
}

/** How the Classic orb groups the phases. */
private fun orbMood(p: Phase): OrbMood = when (p) {
    Phase.IDLE -> OrbMood.IDLE
    Phase.LISTENING, Phase.TRANSCRIBING -> OrbMood.HEARING
    Phase.THINKING, Phase.ANSWERING -> OrbMood.WORKING
    Phase.SPEAKING, Phase.CONFIRMING -> OrbMood.TALKING
}

/** The Classic header's words, as 0.6.0 said them (the standard look's are panelStatus). */
private fun classicStatusLine(st: PanelState): String = when (st.phase) {
    Phase.LISTENING -> "Listening…"
    Phase.TRANSCRIBING -> "Writing it down…"
    // "Claude is thinking…" (or "Checking with Claude…", "My server is loading its model…") until the helper's first words.
    Phase.THINKING -> st.helperStatus ?: if (st.brain.readiness == BrainKeeper.Readiness.LOADING) "Waking up the model…" else "Thinking…"
    Phase.ANSWERING -> st.turns.lastOrNull()?.takeIf { it.fromHelper }?.let { "${it.by ?: "The helper"} is answering…" } ?: "Answering…"
    Phase.CONFIRMING -> if (st.cardHearing != null) "Say yes or no…" else "Waiting for you: tap the card, or answer yes or no"
    Phase.SPEAKING -> "Speaking · tap to stop"
    Phase.IDLE -> when {
        st.inHelperMode -> "Talking with ${st.helperName ?: "the helper"}: your questions go " + if (st.helperHome) "to your server" else "online"
        st.locked -> "Locked: " + LockScreen.allowed(st.lockedTools)
        st.brain.readiness == BrainKeeper.Readiness.LOADING -> "Getting ready…"
        else -> "On this phone, private"
    }
}

/** A notice, the missing model, and a helper's conversation ("Back to Robot"). */
@Composable
private fun ColumnScope.PanelNotices(st: PanelState, actions: PanelActions) {
    val look = LocalLook.current
    AnimatedVisibility(st.notice != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        st.notice?.let { n ->
            if (look == LookStyle.STOCK) PanelNotice(n, actions)
            else {
                val a = noticeAction(n.action, actions)
                ClassicNoticeBar(n.text, a?.first, onAction = a?.second ?: {}, onDismiss = actions::dismissNotice)
            }
        }
    }
    when (look) {
        LookStyle.STOCK -> {
            ModelProblem(st, actions)
            if (st.inHelperMode) HelperModeChip(actions)
        }
        LookStyle.CLASSIC -> {
            val b = st.brain
            if (st.notice == null && (b.readiness == BrainKeeper.Readiness.NO_MODEL || b.readiness == BrainKeeper.Readiness.FAILED)) {
                ClassicBrainBanner(b.problem, openRobot = b.readiness == BrainKeeper.Readiness.NO_MODEL, onOpenRobot = actions::openRobotApp)
            }
            if (st.inHelperMode) ClassicHelperModeChip(st.helperName ?: "Helper", actions::endHelperMode)
        }
    }
}

/** A panel turn as both looks draw it (ui/common TurnUi). */
internal fun UiTurn.toTurnUi() = TurnUi(
    id = id, user = role == TurnRole.USER, text = text, attachment = attachment, thumb = thumb,
    actions = actions.map {
        ActionView(it.id, it.tool, it.summary, it.status, it.detail, it.icon, it.body, it.choices, it.confirm, it.undo != null, it.more,
            cancel = it.cancel, openApp = it.handOff?.let { h -> SettingsPage.label(h) ?: "Open Cheogram" }, picture = it.picture, remember = it.remember,
            allowContacts = it.allowContacts, termux = it.termux)
    },
    streaming = streaming, stopped = stopped, error = error, helper = fromHelper, readBack = readBack, by = by, home = home,
    footer = if (role == TurnRole.USER) emptyList() else replyFooter(sources, cost, by),
    offer = offer?.let { OfferView(it.api, it.text, it.sends, it.preview, it.asWell, it.name, it.app) },
    reply = reply,
    web = web,
)

@Composable
private fun Messages(st: PanelState, actions: PanelActions, modifier: Modifier) {
    if (st.turns.isEmpty()) {
        when (LocalLook.current) {
            LookStyle.STOCK -> PanelEmptyState(st, modifier)
            LookStyle.CLASSIC -> ClassicEmptyState(st.locked, st.lockedTools, st.screenAvailable, modifier)
        }
        return
    }
    val turnActions = remember(actions) {
        TurnActions(
            confirm = actions::confirm,
            card = CardActions(choose = actions::choose, undo = actions::undo, allow = actions::openPermissions, openApp = actions::openHandOff,
                termux = actions::termux),
            acceptOffer = actions::acceptOffer,
            dismissOffer = actions::dismissOffer,
            reply = actions::reply,
            openSource = actions::openSource,
        )
    }
    val reversed = remember(st.turns) { st.turns.asReversed() }
    val first = st.turns.first().id
    // 1.0: what was said while the phone was locked, set apart (ui/common LockedTurns).
    val saidLocked = remember(st.turns) { st.turns.map { it.locked } }
    val list = rememberLazyListState()
    // Follow the newest message. A LazyColumn keeps the item on screen in place when one is added below it, so a
    // new message would land out of sight; jump to it unless the person has scrolled up to read.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
        }
    }
    val newest = st.turns.last()
    LaunchedEffect(newest.id) {
        if (newest.role == TurnRole.USER) follow = true
        if (follow) list.animateScrollToItem(0)
    }
    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth().pointerInput(st.phase, st.speak) {
            // Tapping the conversation stops speech (spec item 4), also while the spoken reply still streams.
            detectTapGestures { if (st.phase == Phase.SPEAKING || (st.phase == Phase.ANSWERING && st.speak)) actions.stopSpeaking() }
        }.semantics {
            // The same for TalkBack, which can't tap the list itself: an action on it while Robot talks.
            if (st.phase == Phase.SPEAKING || (st.phase == Phase.ANSWERING && st.speak)) {
                customActions = listOf(CustomAccessibilityAction("Stop speaking") { actions.stopSpeaking(); true })
            }
        },
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(transcriptGap()),
    ) {
        itemsIndexed(reversed, key = { _, t -> t.id }) { i, t ->
            // 1.0: with replies not spoken, TalkBack reads each answer once it is complete (ui/common answerSemantics).
            val turn = remember(t, st.speak) { t.toTurnUi().copy(announce = !st.speak) }
            val divider = lockedDividerAbove(saidLocked, reversed.size - 1 - i)
            WithLockedDivider(divider) { TurnView(turn, turnActions, startsExchange = turn.user && t.id != first && divider != LockedDivider.LABEL) }
        }
    }
}

/** The empty panel's tips. */
@Composable
private fun Tips(tips: List<Tip>, onPick: (Tip) -> Unit) = when (LocalLook.current) {
    LookStyle.STOCK -> PanelTips(tips, onPick)
    LookStyle.CLASSIC -> ClassicSuggestions(tips, onPick)
}

/** While listening: the words so far as your pending line, or the Classic pulsing mic with "Done". */
@Composable
private fun ListeningIndicator(st: PanelState, actions: PanelActions) = when (LocalLook.current) {
    LookStyle.STOCK -> PendingLine(st.partial)
    LookStyle.CLASSIC -> ClassicListeningRow(st.partial, transcribing = st.phase == Phase.TRANSCRIBING || st.cardHearing == Phase.TRANSCRIBING,
        card = st.cardHearing != null, onDone = actions::micReleased)
}

/** What goes with the next question, the camera's choices, the text field and the main button. */
@Composable
private fun ColumnScope.PanelInputArea(
    st: PanelState,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    attachOpen: Boolean,
    onAttachOpen: (Boolean) -> Unit,
    focus: FocusRequester,
    actions: PanelActions,
) {
    when (LocalLook.current) {
        LookStyle.STOCK -> {
            ContextRow(st, actions)
            PanelInput(st, input, onInput, onSend, attachOpen, onAttachOpen, focus, actions)
        }
        LookStyle.CLASSIC -> {
            ClassicContextRow(st.draft?.kind, st.draft?.thumb, readClaude = st.claudeScreen && !st.busy, onRemove = actions::removeDraft,
                onReadClaude = actions::readClaudeAnswer)
            AnimatedVisibility(attachOpen && st.draft == null && !st.busy) {
                ClassicAttachRow(st.locked, onDone = { onAttachOpen(false) },
                    onCamera = { actions.openCapture(CaptureActivity.MODE_CAMERA) }, onPhotos = { actions.openCapture(CaptureActivity.MODE_PHOTOS) })
            }
            val state = ClassicInputState(
                busy = st.busy, confirming = st.phase == Phase.CONFIRMING, speaking = st.phase == Phase.SPEAKING,
                listening = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING, cardListening = st.cardHearing != null,
                hasDraft = st.draft != null, screenChip = st.screenChip, micHint = st.micHint,
            )
            val buttons = ClassicInputActions(attach = { onAttachOpen(!attachOpen) }, attachScreen = actions::attachScreen, stop = actions::stopGenerating,
                stopSpeaking = actions::stopSpeaking, micPressed = actions::micPressed, micReleased = actions::micReleased)
            ClassicInputRow(state, input, onInput, onSend, focus, buttons)
        }
    }
}
