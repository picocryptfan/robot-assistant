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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.heyrobot.ui.classic.AssistantBubble
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
import app.heyrobot.ui.classic.ClassicOfferCard
import app.heyrobot.ui.classic.ClassicSuggestions
import app.heyrobot.ui.classic.OrbMood
import app.heyrobot.ui.classic.UserBubble
import app.heyrobot.ui.common.ActionView
import app.heyrobot.ui.common.CardActions
import app.heyrobot.ui.common.OfferView
import app.heyrobot.ui.common.claudeFooter
import kotlinx.coroutines.delay

// VOICE PROCESS. The assistant panel: a bottom sheet over whatever is on screen, with the conversation,
// a text field, push-to-talk, the camera, "Ask about this screen" and a stop button.

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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A window Android slid up for the keyboard shows that much less of the panel's top.
        val maxSheet = (maxHeight - slidUp) * 0.88f
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
            Column(Modifier.padding(bottom = bottomPad)) {
                // Handle and header: drag down to close.
                Column(
                    Modifier.fillMaxWidth().draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { d -> dragY += d },
                        onDragStopped = { if (dragY > 140f) { dragY = 0f; onClose() } else dragY = 0f },
                    ),
                ) {
                    ClassicDragHandle()
                    Header(st, actions, onClose)
                }
                AnimatedVisibility(st.notice != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    st.notice?.let { NoticeBar(it, actions) }
                }
                BrainBanner(st, actions)
                if (st.claudeMode) HelperModeChip(st.helperName ?: "Claude", actions)
                Messages(st, actions, Modifier.weight(1f, fill = false))
                // Listening for a question, or for the waiting card's "yes" or "no".
                val hearing = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING || st.cardHearing != null
                if (st.turns.isEmpty() && !hearing) {
                    Suggestions(st.tips) { tip ->
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
                if (hearing) ListeningRow(st.partial, transcribing = st.phase == Phase.TRANSCRIBING || st.cardHearing == Phase.TRANSCRIBING, card = st.cardHearing != null, actions = actions)
                ContextRow(st, actions)
                AnimatedVisibility(attachOpen && st.draft == null && !st.busy) { AttachRow(st.locked, actions) { attachOpen = false } }
                InputRow(
                    st = st,
                    input = input,
                    onInput = { input = it },
                    onSend = ::send,
                    onAttach = { attachOpen = !attachOpen },
                    focus = focus,
                    actions = actions,
                )
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

@Composable
private fun Header(st: PanelState, actions: PanelActions, onClose: () -> Unit) {
    ClassicHeader(
        mood = orbMood(st.phase), status = statusLine(st), showKeep = !st.keep && st.turns.isNotEmpty(), speak = st.speak,
        onKeep = actions::keepConversation, onToggleSpeak = actions::toggleSpeak, onNew = actions::newConversation, onClose = onClose,
    )
}

/** How the Classic orb groups the phases. */
private fun orbMood(p: Phase): OrbMood = when (p) {
    Phase.IDLE -> OrbMood.IDLE
    Phase.LISTENING, Phase.TRANSCRIBING -> OrbMood.HEARING
    Phase.THINKING, Phase.ANSWERING -> OrbMood.WORKING
    Phase.SPEAKING, Phase.CONFIRMING -> OrbMood.TALKING
}

private fun statusLine(st: PanelState): String = when (st.phase) {
    Phase.LISTENING -> "Listening…"
    Phase.TRANSCRIBING -> "Writing it down…"
    // "Claude is thinking…" (or "Checking with Claude…", "My server is loading its model…") until the helper's first words.
    Phase.THINKING -> st.helperStatus ?: if (st.brain.readiness == BrainKeeper.Readiness.LOADING) "Waking up the model…" else "Thinking…"
    Phase.ANSWERING -> st.turns.lastOrNull()?.takeIf { it.claude }?.let { "${it.by ?: "Claude"} is answering…" } ?: "Answering…"
    Phase.CONFIRMING -> if (st.cardHearing != null) "Say yes or no…" else "Waiting for you: tap the card, or answer yes or no"
    Phase.SPEAKING -> "Speaking · tap to stop"
    Phase.IDLE -> when {
        st.claudeMode -> "Talking with ${st.helperName ?: "Claude"}: your questions go " + if (st.helperHome) "to your server" else "online"
        st.locked -> "Locked: " + LockScreen.allowed(st.lockedTools)
        st.brain.readiness == BrainKeeper.Readiness.LOADING -> "Getting ready…"
        else -> "On this phone, private"
    }
}

/** A helper's conversation is on: every question goes to [name] until "Back to Robot". */
@Composable
private fun HelperModeChip(name: String, actions: PanelActions) = ClassicHelperModeChip(name, actions::endHelperMode)

/** A notice's button: its words and what it does, or null. */
private fun noticeAction(a: NoticeAction, actions: PanelActions): Pair<String, () -> Unit>? = when (a) {
    NoticeAction.OPEN_APP -> "Open Robot" to actions::openRobotApp
    NoticeAction.OPEN_ASSIST_SETTINGS -> "Open settings" to actions::openAssistSettings
    NoticeAction.OPEN_MODEL -> "Open Model" to actions::openModelSettings
    NoticeAction.NONE -> null
}

@Composable
private fun NoticeBar(n: Notice, actions: PanelActions) {
    val a = noticeAction(n.action, actions)
    ClassicNoticeBar(n.text, a?.first, onAction = a?.second ?: {}, onDismiss = actions::dismissNotice)
}

/** The model is missing or failed: say so before the person types a whole question. */
@Composable
private fun BrainBanner(st: PanelState, actions: PanelActions) {
    val b = st.brain
    if (st.notice != null) return
    if (b.readiness != BrainKeeper.Readiness.NO_MODEL && b.readiness != BrainKeeper.Readiness.FAILED) return
    ClassicBrainBanner(b.problem, openRobot = b.readiness == BrainKeeper.Readiness.NO_MODEL, onOpenRobot = actions::openRobotApp)
}

private fun Offer.view() = OfferView(api, text, sends, preview, asWell, name)

@Composable
private fun Messages(st: PanelState, actions: PanelActions, modifier: Modifier) {
    val cardActions = remember(actions) { CardActions(choose = actions::choose, undo = actions::undo, allow = actions::openPermissions, openApp = actions::openHandOff) }
    if (st.turns.isEmpty()) {
        ClassicEmptyState(st.locked, st.lockedTools, st.screenAvailable, modifier)
        return
    }
    val reversed = remember(st.turns) { st.turns.asReversed() }
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(reversed, key = { it.id }) { t ->
            if (t.role == TurnRole.USER) UserBubble(t.text, t.attachment, t.thumb)
            else Column {
                AssistantBubble(
                    text = t.text, streaming = t.streaming, stopped = t.stopped, error = t.error,
                    actions = t.actions.map { ActionView(it.id, it.tool, it.summary, it.status, it.detail, it.icon, it.body, it.choices, it.confirm, it.undo != null, it.more,
                        cancel = it.cancel, openApp = it.handOff?.let { h -> SettingsPage.label(h) ?: "Open Cheogram" }, picture = it.picture, remember = it.remember,
                        allowContacts = it.allowContacts) },
                    onConfirm = actions::confirm,
                    cardActions = cardActions,
                    claude = t.claude, readBack = t.readBack, footer = claudeFooter(t.sources, t.cost, t.by), by = t.by, home = t.home,
                )
                t.offer?.let { ClassicOfferCard(it.view(), onDismiss = { actions.dismissOffer(t.id) }, onAccept = { actions.acceptOffer(t.id) }) }
            }
        }
    }
}

/** Three tips that show what Robot can do (core Tips: only what is on and can work now, a new three each open). */
@Composable
private fun Suggestions(tips: List<Tip>, onPick: (Tip) -> Unit) = ClassicSuggestions(tips, onPick)

@Composable
private fun ListeningRow(partial: String, transcribing: Boolean, card: Boolean = false, actions: PanelActions) =
    ClassicListeningRow(partial, transcribing, card, onDone = actions::micReleased)

/**
 * The picture or screen that goes with the next question, or "Read Claude's answer" over the Claude app. "Ask about
 * this screen" is the screen button next to the camera (0.4.0).
 */
@Composable
private fun ContextRow(st: PanelState, actions: PanelActions) =
    ClassicContextRow(st.draft?.kind, st.draft?.thumb, readClaude = st.claudeScreen && !st.busy, onRemove = actions::removeDraft, onReadClaude = actions::readClaudeAnswer)

@Composable
private fun AttachRow(locked: Boolean, actions: PanelActions, onDone: () -> Unit) = ClassicAttachRow(locked, onDone,
    onCamera = { actions.openCapture(CaptureActivity.MODE_CAMERA) }, onPhotos = { actions.openCapture(CaptureActivity.MODE_PHOTOS) })

@Composable
private fun InputRow(st: PanelState, input: String, onInput: (String) -> Unit, onSend: () -> Unit, onAttach: () -> Unit, focus: FocusRequester, actions: PanelActions) {
    val state = ClassicInputState(
        busy = st.busy, confirming = st.phase == Phase.CONFIRMING, speaking = st.phase == Phase.SPEAKING,
        listening = st.phase == Phase.LISTENING || st.phase == Phase.TRANSCRIBING, cardListening = st.cardHearing != null,
        hasDraft = st.draft != null, screenChip = st.screenChip, micHint = st.micHint,
    )
    val buttons = ClassicInputActions(attach = onAttach, attachScreen = actions::attachScreen, stop = actions::stopGenerating,
        stopSpeaking = actions::stopSpeaking, micPressed = actions::micPressed, micReleased = actions::micReleased)
    ClassicInputRow(state, input, onInput, onSend, focus, buttons)
}
