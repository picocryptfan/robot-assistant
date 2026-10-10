package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.heyrobot.claude.ClaudeListener
import app.heyrobot.claude.ClaudeSession
import app.heyrobot.claude.ClaudeTurn
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClaudeAppAnswer
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeCommand
import app.heyrobot.core.ClaudeContext
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeRouter
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.Hedge
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Personal
import app.heyrobot.core.PreRouter
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.Shared
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnModel
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.Prompt
import app.heyrobot.core.RLog
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnSlot
import app.heyrobot.core.TurnView
import app.heyrobot.core.toPromptTurns
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.NotesFiles
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

// VOICE PROCESS. The assistant panel's state and logic, kept per process (not per session) so a
// conversation survives the panel hiding for the camera or a new session object. All state changes
// happen on the main thread. Nothing here is logged except counts and outcomes.

/**
 * TRANSCRIBING: the person stopped talking and the speech is being written down. CONFIRMING: a card waits for
 * "Do it" or "Cancel"; the turn is still under way (no new question until it ends; Stop declines the card).
 */
enum class Phase { IDLE, LISTENING, TRANSCRIBING, THINKING, ANSWERING, CONFIRMING, SPEAKING }

/**
 * The phase once a turn has ended ([speaking]: the voice is still reading the reply). Only a turn's own phases
 * give way: listening for the next question ("hey robot" during the answer) or anything else already shown stays.
 */
fun Phase.afterTurn(speaking: Boolean): Phase = when (this) {
    Phase.THINKING, Phase.ANSWERING, Phase.CONFIRMING -> if (speaking) Phase.SPEAKING else Phase.IDLE
    else -> this
}

/**
 * [icon]: the real app's launcher icon on an open_app card. [body]: the full message, email or note, or what a read
 * tool found. [choices]: the people or numbers to tap while it waits. [confirm]: the button ("Open in Cheogram").
 * [undo]: a saved note's key for Undo. [content]: a read tool brought content into the conversation.
 */
data class UiAction(
    val id: String,
    val tool: ToolName?,
    val summary: String,
    val status: ActionStatus,
    val detail: String?,
    val icon: Bitmap? = null,
    val body: String? = null,
    val choices: List<String> = emptyList(),
    val confirm: String? = null,
    val undo: String? = null,
    val content: Boolean = false,
    /** More people or numbers fitted than [choices] lists. */
    val more: Int = 0,
    /** The Cancel button's own words ("Leave out"). */
    val cancel: String? = null,
)

/**
 * "Want me to check with Claude?" under a reply (docs/ask-claude.md "When Robot needs help"): a tap or a yes asks Claude
 * through the API ([api], listing what goes: [sends]) or opens the Claude app with [text]. Nothing happens without it.
 */
data class Offer(val api: Boolean, val text: String, val sends: List<String>)

data class UiTurn(
    val id: String,
    val role: TurnRole,
    val text: String,
    val attachment: Attachment = Attachment.NONE,
    val thumb: Bitmap? = null,
    val actions: List<UiAction> = emptyList(),
    val streaming: Boolean = false,
    val stopped: Boolean = false,
    val error: Boolean = false,
    /** Claude wrote it: a "Claude" label and the tertiary accent. [readBack]: read off the Claude app's screen. */
    val claude: Boolean = false,
    val readBack: Boolean = false,
    /** "Claude searched the web" and these domains. */
    val sources: List<String> = emptyList(),
    /** "≈ 1¢" (only with "Show cost" on). */
    val cost: String? = null,
    val offer: Offer? = null,
)

data class Draft(val kind: Attachment, val thumb: Bitmap?)

enum class NoticeAction { NONE, OPEN_APP }

data class Notice(val text: String, val action: NoticeAction = NoticeAction.NONE)

data class PanelState(
    val convId: String,
    val turns: List<UiTurn> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val notice: Notice? = null,
    val draft: Draft? = null,
    val screenAvailable: Boolean = false,
    val locked: Boolean = false,
    val speak: Boolean = false,
    /** Saved to the history (a conversation that used the screen is saved only when the person keeps it). */
    val keep: Boolean = true,
    val sttAvailable: Boolean = false,
    /** Why voice input is unavailable (the mic button's tooltip, e.g. "Download voice models"); null when it works. */
    val micHint: String? = null,
    /** Bumped to ask the panel to focus the text field and show the keyboard. */
    val focusRequest: Int = 0,
    /** Bumped on every show: the panel's own state (a drag offset, the attach row) starts fresh each time. */
    val showId: Int = 0,
    val brain: BrainKeeper.State = BrainKeeper.State(),
    /** Claude mode: the following turns go to Claude ("Talking with Claude · Back to Robot"). */
    val claudeMode: Boolean = false,
    /** "Claude is thinking…" or "Checking with Claude…" until Claude's first words. */
    val claudeStatus: String? = null,
    /** The corner swipe came from the Claude app: "Read Claude's answer". */
    val claudeScreen: Boolean = false,
) {
    /** A turn is under way, a confirmation card included: no new question, no mic, no camera until it ends. */
    val busy: Boolean get() = phase == Phase.THINKING || phase == Phase.ANSWERING || phase == Phase.CONFIRMING
}

/** What the session offers the controller. */
interface PanelHost {
    fun hidePanel()
    /** Starts another app's activity from the panel; false if it could not. */
    fun launch(intent: Intent): Boolean
    /** Starts Robot's capture activity as an assistant activity; false if it could not. */
    fun startCapture(intent: Intent): Boolean
}

object PanelController {
    /** A panel reopened within this time continues the same conversation. */
    private const val CONTINUE_MS = 5 * 60_000L
    /** Claude mode ends after this long without a turn. */
    private const val CLAUDE_IDLE_MS = 10 * 60_000L

    private val scope = MainScope()
    /** The one turn that may run at a time, and its confirmation cards. */
    private val slot = TurnSlot(scope)
    private val _state = MutableStateFlow(PanelState(newId()))
    val state: StateFlow<PanelState> = _state
    @Volatile var shown = false; private set

    private var host: PanelHost? = null
    private val chat = mutableListOf<ChatTurn>()
    private var screenText: String? = null
    private var screenshot: Bitmap? = null
    private var draftJpeg: ByteArray? = null
    private var lastActivity = 0L
    private var startedLocked = false
    private var keptByUser = false
    private var ignoreAssist = false
    private var hidingFor: String? = null
    private var reqId = 0
    private var client: BrainClient? = null
    /** What of the streaming reply is read aloud (muted by "stop", silent once cancelled). */
    private val voice = ReplyVoice()
    private val icons = HashMap<String, Bitmap?>()
    private var initialized = false
    /** The tool groups this conversation has used: only ever added to (ToolRouter), reset with the conversation. */
    private var groups: Set<ToolGroup> = setOf(ToolGroup.CORE)
    /** Undo changed what the live conversation believes: close it once the turn under way (if any) has ended. */
    private var closeConvAfterTurn = false

    // Ask Claude (docs/ask-claude.md). Claude mode's conversation (API route), and when it last had a turn.
    private var claudeSession: ClaudeSession? = null
    private var claudeLast = 0L
    private var claudeIdle: Job? = null
    /** The screen's text when the corner swipe came from the Claude app (for "Read Claude's answer"); never stored. */
    private var claudePieces: List<String>? = null
    /** The question Robot last handed to the Claude app, to find Claude's answer after it. */
    private var lastToApp: String? = null
    /** Offers waiting under replies, by reply id, with what a tap would send. */
    private val offers = HashMap<String, PendingOffer>()
    /** The picture of the last question, kept for this panel session only, so an offer's tap can share it. */
    private var lastImage: ByteArray? = null
    /** The day the "budget used up" sentence was said (once a day). */
    private var budgetSaid: String? = null

    /** What an offer's tap does: the owner's [question], the conversation before it, what it may share. */
    private class PendingOffer(val api: Boolean, val question: String, val history: List<ChatTurn>, val attachment: Attachment, val personal: Set<Personal>)

    private val ownerRegion get() = VoiceHub.settings.phoneRegion

    private fun newId() = UUID.randomUUID().toString()

    private fun init() {
        if (initialized) return
        initialized = true
        scope.launch { BrainKeeper.state.collect { s -> _state.update { it.copy(brain = s) } } }
        VoiceHub.tts.setListener { speaking ->
            VoiceHub.onSpeakingChanged(speaking)
            _state.update {
                when {
                    !speaking && it.phase == Phase.SPEAKING -> it.copy(phase = Phase.IDLE)
                    speaking && it.phase == Phase.IDLE -> it.copy(phase = Phase.SPEAKING)
                    else -> it
                }
            }
        }
    }

    fun attach(h: PanelHost) { host = h }
    fun detach(h: PanelHost) { if (host === h) host = null }

    // ------------------------------------------------------------------ show / hide

    fun onShow(args: Bundle?, locked: Boolean) {
        init()
        shown = true
        val source = args?.getString(RobotVoiceService.ARG_SOURCE)
        val convArg = args?.getString(RobotVoiceService.ARG_CONVERSATION)
        // Only an explicit return from CaptureActivity keeps the picture (an abandoned capture does not).
        val returning = source == RobotVoiceService.SRC_CAPTURE
        hidingFor = null
        ignoreAssist = source == RobotVoiceService.SRC_APP || returning
        val s = VoiceHub.settings
        if (!returning) {
            screenText = null
            screenshot = null
            claudePieces = null
            val stale = System.currentTimeMillis() - lastActivity > CONTINUE_MS
            when {
                convArg != null && !locked && ConversationStore.validId(convArg) -> loadConversation(convArg)
                // Never show an earlier conversation on the lock screen.
                locked && (!startedLocked || stale) -> newConversation(locked = true)
                stale || chat.isEmpty() -> newConversation(locked)
            }
            val speak = when (s.speakReplies) {
                SpeakReplies.ALWAYS -> true
                SpeakReplies.NEVER -> false
                SpeakReplies.VOICE -> source == RobotVoiceService.SRC_WAKE
            }
            // focusRequest restarts at 0 so a new panel's first composition does not pop the keyboard.
            _state.update { it.copy(speak = speak, notice = null, draft = null, focusRequest = 0) }
            draftJpeg = null
        }
        // Claude mode never carries over onto the lock screen unless Claude is allowed there.
        if (locked && !s.claudeLocked) endClaudeMode()
        _state.update {
            it.copy(locked = locked, screenAvailable = false, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "",
                showId = it.showId + 1, claudeScreen = false)
        }
        BrainKeeper.prewarm(VoiceHub.app)
        if (ClaudeSettings.route(s, ClaudeLink.keySaved(VoiceHub.app)) == ClaudeRoute.API) ClaudeLink.warm(VoiceHub.app, s)
        // The voice models load on first use (VoiceHub's memory policy): speech recognition when the mic or the wake
        // word starts a question, the voice when a question is sent with spoken replies on. Nothing loads just for a show.
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        if (source == RobotVoiceService.SRC_WAKE) startListening()
    }

    fun onHide() {
        shown = false
        VoiceHub.stt.cancel()
        VoiceHub.listener?.wakePaused = false
        val forCapture = hidingFor == "capture"
        // A turn never goes on behind a hidden panel: closing stops it, and a card nobody can tap any more is declined.
        if (hidingFor == null || slot.confirmations.waiting) stopGenerating()
        if (hidingFor == null) VoiceHub.tts.stop()
        // Claude mode ends when the panel closes (not when it hides for the camera or an app Claude opened).
        if (hidingFor == null) endClaudeMode()
        // Screen content lives only while the panel is open.
        screenText = null
        screenshot = null
        claudePieces = null
        if (!forCapture) {
            draftJpeg = null
            lastImage = null
            _state.update { it.copy(draft = null) }
        }
        _state.update { it.copy(phase = if (it.busy) it.phase else Phase.IDLE, partial = "", screenAvailable = false, claudeScreen = false) }
        lastActivity = System.currentTimeMillis()
        // Whatever the reason (a camera left with Home must not keep Gemma loaded): showing the panel again cancels it.
        BrainKeeper.scheduleUnload(VoiceHub.settings.keepModelMinutes)
    }

    /**
     * AssistStructure text (corner swipe only). Over the Claude app ([pkg]) the panel also offers "Read Claude's answer":
     * the owner's own swipe, never a background read of the app.
     */
    fun onAssistText(pieces: List<String>, pkg: String? = null) {
        if (ignoreAssist || !shown) return
        screenText = ScreenText.build(pieces)
        claudePieces = pieces.takeIf { pkg == ClaudeAppLink.PACKAGE }
        _state.update { it.copy(screenAvailable = screenText != null || screenshot != null, claudeScreen = claudePieces != null) }
    }

    fun onScreenshot(bmp: Bitmap?) {
        if (ignoreAssist || !shown || bmp == null) return
        screenshot = bmp
        _state.update { it.copy(screenAvailable = true) }
    }

    // ------------------------------------------------------------------ conversation

    fun newConversation(locked: Boolean = _state.value.locked) {
        stopGenerating()
        VoiceHub.tts.stop()
        endClaudeMode()
        offers.clear()
        chat.clear()
        groups = setOf(ToolGroup.CORE)
        closeConvAfterTurn = false
        startedLocked = locked
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update {
            PanelState(newId(), locked = locked, speak = it.speak, sttAvailable = it.sttAvailable, micHint = it.micHint, screenAvailable = it.screenAvailable,
                showId = it.showId, brain = it.brain)
        }
    }

    private fun loadConversation(id: String) {
        val c = ConversationStore.load(VoiceHub.app, id) ?: return newConversation(false)
        stopGenerating()
        endClaudeMode()
        offers.clear()
        chat.clear()
        chat += c.turns
        // The groups its questions and actions used: continuing it describes the same tools.
        groups = ToolRouter.used(c.turns)
        closeConvAfterTurn = false
        startedLocked = false
        // Opening it from the history keeps saving it, but a screen question in it still needs "Keep" (only the
        // person's tap sets keptByUser).
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update {
            PanelState(c.id, turns = c.turns.map { t -> t.toUi() }, keep = true, sttAvailable = it.sttAvailable, micHint = it.micHint,
                showId = it.showId, brain = it.brain)
        }
    }

    private fun ChatTurn.toUi() = UiTurn(id, role, text, attachment, null,
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail, content = it.content) }, stopped = stopped, error = error,
        claude = claude, readBack = readBack, sources = sources, cost = costMicros.takeIf { it > 0 && VoiceHub.settings.claudeShowCost }?.let { ClaudeCost.line(it / 1e6) })

    /** The only way a conversation with screen content gets saved. */
    fun keepConversation() {
        keptByUser = true
        _state.update { it.copy(keep = true) }
        persist()
    }

    private fun persist() {
        val st = _state.value
        if (!st.keep || !VoiceHub.settings.saveHistory || chat.isEmpty()) return
        val c = StoredConversation(st.convId, chat.first().time, System.currentTimeMillis(), chat.toList())
        scope.launch(Dispatchers.IO) { ConversationStore.save(VoiceHub.app, c) }
    }

    fun toggleSpeak() {
        val on = !_state.value.speak
        if (!on) VoiceHub.tts.stop()
        _state.update { it.copy(speak = on) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** "Open Robot" (to download the model): the app needs the phone unlocked. */
    fun openRobotApp() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open the Robot app.")
        val h = host ?: return
        val i = Intent().setClassName(VoiceHub.app.packageName, "app.heyrobot.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        leaveForRobot(h, i)
    }

    /**
     * Robot's own app over the panel: the turn and the voice stop first, so nothing goes on (a card waiting where
     * nobody sees it, a "Just do it" action) behind the app.
     */
    private fun leaveForRobot(h: PanelHost, i: Intent) {
        stopGenerating()
        VoiceHub.tts.stop()
        if (h.launch(i)) {
            hidingFor = "action"
            h.hidePanel()
        }
    }

    fun notify(text: String) = _state.update { it.copy(notice = Notice(text)) }

    /** "Allow" on a card that needs a permission: the main app's permission screen (it asks; the panel never does). */
    fun openPermissions() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open the Robot app.")
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.PERMISSIONS))
    }

    // ------------------------------------------------------------------ attachments

    fun attachScreen() {
        if (!_state.value.screenAvailable) return
        _state.update { it.copy(draft = Draft(Attachment.SCREEN, screenshot?.let { b -> Images.scaled(b, Images.THUMB_EDGE) })) }
    }

    fun removeDraft() {
        draftJpeg = null
        _state.update { it.copy(draft = null) }
    }

    fun openCapture(mode: String) {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock to use the camera or your photos.")
        // Not while a turn runs: it would go on hidden behind the camera.
        if (_state.value.busy || slot.running) return
        val h = host ?: return
        VoiceHub.tts.stop()
        hidingFor = "capture"
        if (h.startCapture(CaptureActivity.intent(VoiceHub.app, mode))) {
            h.hidePanel()
        } else {
            // The panel stays, so the reason can be read.
            hidingFor = null
            notify("Couldn't open the camera or the photo picker.")
        }
    }

    /** From CaptureActivity (same process): the picture to send with the next question. */
    fun onImagePicked(jpeg: ByteArray, thumb: Bitmap) {
        draftJpeg = jpeg
        _state.update { it.copy(draft = Draft(Attachment.PHOTO, thumb), notice = null, focusRequest = it.focusRequest + 1) }
    }

    // ------------------------------------------------------------------ voice input

    fun micPressed() {
        if (_state.value.busy || slot.running) return
        VoiceHub.tts.stop()
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    fun micReleased() = VoiceHub.stt.stop()

    fun onWakeWhileShown() {
        VoiceHub.tts.stop()
        // "Hey robot" during an answer works like Stop (a waiting card is declined); the new question is taken once
        // that turn has ended (sendSpoken).
        if (_state.value.busy || slot.running) stopGenerating()
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    private fun startListening() {
        VoiceHub.listener?.wakePaused = true
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", notice = null, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint) }
        if (_state.value.speak) VoiceHub.tts.prewarm()
        val main = VoiceHub.main
        VoiceHub.stt.start(object : SpeechToText.Listener {
            override fun onPartial(text: String) { main.post { _state.update { if (it.phase == Phase.LISTENING) it.copy(partial = text) else it } } }
            override fun onEndOfSpeech() {
                main.post {
                    // The wake word may listen again while the words are written down.
                    VoiceHub.listener?.wakePaused = false
                    _state.update { if (it.phase == Phase.LISTENING) it.copy(phase = Phase.TRANSCRIBING) else it }
                }
            }
            override fun onFinal(text: String) {
                main.post {
                    endListening()
                    if (text.isNotBlank()) sendSpoken(text)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    endListening()
                    val unavailable = reason == SpeechToText.Reason.UNAVAILABLE
                    _state.update {
                        it.copy(
                            // "Open Robot" leads to the voice models (the app needs the phone unlocked).
                            notice = Notice(message, if (unavailable && !it.locked) NoticeAction.OPEN_APP else NoticeAction.NONE),
                            focusRequest = if (unavailable) it.focusRequest + 1 else it.focusRequest,
                        )
                    }
                }
            }
        })
    }

    private fun endListening() {
        VoiceHub.listener?.wakePaused = false
        _state.update { it.copy(phase = if (it.phase == Phase.LISTENING || it.phase == Phase.TRANSCRIBING) Phase.IDLE else it.phase, partial = "") }
    }

    // ------------------------------------------------------------------ asking

    /**
     * Asks [text] (with the draft picture or screen, if any). False when it can't be taken now: a turn is under way
     * (a confirmation card included) or there is nothing to send. The caller keeps the typed text then.
     *
     * Ask Claude's router runs first, on the words as they are (docs/ask-claude.md): "ask Claude …", "go get Claude",
     * "thanks Claude" and the rest; in Claude mode every other question goes to Claude. The local model never decides.
     */
    fun send(text: String, fromVoice: Boolean = false): Boolean {
        val st = _state.value
        val q = text.trim()
        if (q.isEmpty() && st.draft == null) return false
        if (st.busy || slot.running) {
            if (fromVoice) notify("Robot is still on the last question. Ask again when it's done.")
            return false
        }
        // An offer under the last reply waits for a tap or a short yes.
        if (st.draft == null && ClaudeRouter.isYes(q)) offers.keys.lastOrNull { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.let {
            acceptOffer(it)
            return true
        }
        VoiceHub.tts.stop()
        expireClaudeMode()
        var cmd = if (q.isNotEmpty()) ClaudeRouter.route(q) else null
        // "Thanks Claude" outside Claude mode is ordinary talk.
        if (cmd == ClaudeCommand.Leave && !_state.value.claudeMode) cmd = null
        if (cmd != null) RLog.i("claude_command", "kind" to RLog.Code(cmd.javaClass.simpleName))
        dropOffers()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> screenshot?.let { Images.jpeg(it) }
            Attachment.NONE -> null
        }
        if (image != null) lastImage = image
        val sText = if (attachment == Attachment.SCREEN) screenText else null
        val history = chat.toList()
        val now = System.currentTimeMillis()
        val user = ChatTurn(newId(), TurnRole.USER, q.ifEmpty { Prompt.defaultQuestion(attachment) }, now, attachment)
        chat += user
        val toClaude = cmd is ClaudeCommand.Ask || cmd is ClaudeCommand.Join || (cmd == null && _state.value.claudeMode)
        // Groups are only ever added: a question that needs a new one rebuilds the conversation once (:brain).
        if (cmd == null && !toClaude) groups = groups + ToolRouter.route(q)
        val replyId = newId()
        // Decided per turn: a screen turn stops saving until the person taps Keep.
        val keep = if (attachment == Attachment.SCREEN && !keptByUser) false else st.keep
        // Screen text, a picture, a read tool's findings or Claude's words anywhere in this conversation: every action asks first.
        val untrusted = TurnRules.untrusted(chat)
        val api = toClaude && ClaudeSettings.route(VoiceHub.settings, ClaudeLink.keySaved(VoiceHub.app)) == ClaudeRoute.API
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, attachment, st.draft?.thumb) +
                    UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true, claude = api && cmd != ClaudeCommand.Join(null)),
                phase = Phase.THINKING, draft = null, partial = "", notice = null, keep = keep,
                locked = Assistant.locked(VoiceHub.app),
                claudeStatus = if (api) ClaudeWords.THINKING else null,
            )
        }
        draftJpeg = null
        lastActivity = now
        voice.start(replyId)
        // The reply will be read aloud: the voice loads while the model thinks.
        if (st.speak) VoiceHub.tts.prewarm()
        val guard = EscalationGuard()
        startTurn(replyId) {
            when {
                cmd == ClaudeCommand.Leave -> {
                    endClaudeMode()
                    localReply(replyId, ClaudeWords.LEFT)
                }
                cmd == ClaudeCommand.ReadAnswer -> if (claudePieces != null) {
                    finishOrDrop(replyId)
                    readClaudeAnswer()
                } else {
                    localReply(replyId, ClaudeWords.SWIPE_TO_READ)
                }
                toClaude -> claudeTurn(replyId, cmd, user.text, attachment, sText, image, history, fromVoice)
                else -> runTurn(replyId, user.text, attachment, sText, image, history, untrusted, guard, fromVoice)
            }
        }
        return true
    }

    /** Runs a turn in the slot; a stop finishes every reply still streaming as stopped, a crash fails it in words. */
    private fun startTurn(replyId: String, block: suspend () -> Unit) = slot.start {
        try {
            block()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                (listOf(replyId) + _state.value.turns.filter { it.streaming }.map { it.id }).distinct().forEach { finishReply(it, DoneReason.CANCELLED) }
            }
            throw e
        } catch (t: Throwable) {
            RLog.w("panel_turn", t)
            _state.value.turns.filter { it.streaming }.forEach { failReply(it.id, "Something went wrong. Please try again.") }
        }
    }

    /** A spoken question: if a stopped turn is still finishing (a moment after "hey robot"), it goes right after. */
    private fun sendSpoken(text: String) {
        if (!_state.value.busy && slot.running) {
            scope.launch {
                slot.join()
                send(text, fromVoice = true)
            }
        } else {
            send(text, fromVoice = true)
        }
    }

    private suspend fun runTurn(
        replyId: String, question: String, attachment: Attachment, screen: String?, image: ByteArray?, history: List<ChatTurn>, untrusted: Boolean,
        guard: EscalationGuard, voiceAsked: Boolean,
    ) {
        val s = VoiceHub.settings
        val keySaved = ClaudeLink.keySaved(VoiceHub.app)
        // Escalation trigger a: strong signals go to Claude before Gemma spends a prefill (Automatic only); with Ask
        // first, Gemma answers and the offer follows its reply.
        var offerAfter: EscalationTrigger? = null
        if (attachment == Attachment.NONE) PreRouter.signal(question)?.let { signal ->
            RLog.i("claude_trigger", "kind" to EscalationTrigger.PRE_ROUTER, "signal" to signal)
            when (decide(history, attachment, guardFree = !guard.used)) {
                EscalationDecision.AUTO -> {
                    guard.take(fromClaude = false)
                    return escalateNow(replyId, question, history, attachment, voiceAsked, reuseBubble = true)
                }
                EscalationDecision.OFFER -> offerAfter = EscalationTrigger.PRE_ROUTER
                else -> {}
            }
        }
        val c = BrainKeeper.ensureLoaded(VoiceHub.app)
        if (c == null) {
            val b = BrainKeeper.state.value
            return failReply(replyId, b.problem ?: "Robot's model isn't ready.", if (b.readiness == BrainKeeper.Readiness.NO_MODEL) NoticeAction.OPEN_APP else NoticeAction.NONE)
        }
        client = c
        // The TPU build loaded without its vision encoder: a screen question still has the screen's text.
        val vision = BrainKeeper.state.value.vision
        if (image != null && !vision && attachment == Attachment.PHOTO) return failReply(replyId, BrainError.NO_VISION.text())
        val img = if (vision) image else null
        val convId = _state.value.convId
        val now = ZonedDateTime.now()
        // ask_claude is described (with its one line in the system prompt) only with Ask Claude on and escalation not Off.
        val askClaude = ClaudeSettings.localTool(s, keySaved)
        val system = Prompt.system(now, Assistant.locked(VoiceHub.app), askClaude)
        // Only the groups this conversation needs, and nothing turned off in settings, is described to the model.
        val tools = ToolRouter.tools(groups, s, askClaude)
        // The new turn always fits the model's context: cut (and say so) rather than overflow.
        val fitted = Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools), Prompt.clock(now))
        if (fitted.shortened) {
            notify(if (fitted.questionCut) "Your message was too long, so Robot read only its first part." else "The screen had a lot of text, so Robot read only the first part.")
        }
        val gen = BrainMsg.Generate(
            convId = convId,
            system = system,
            history = history.toPromptTurns(),
            historyCount = history.size,
            text = fitted.text,
            hasImage = img != null,
            tools = tools.map { it.id },
        )
        val result = TurnRunner(BrainTurn(c, convId, replyId, gen, img), PanelActions, PanelView(replyId), slot.confirmations, untrusted, newId = ::newId).run()
        result.failure?.let { return failReply(replyId, it) }
        // Escalation trigger b: Gemma called ask_claude. Its turn ended there; Claude answers (or is offered).
        if (result.escalate) {
            RLog.i("claude_trigger", "kind" to EscalationTrigger.LOCAL_TOOL)
            return afterLocal(replyId, question, history, attachment, voiceAsked, guard, EscalationTrigger.LOCAL_TOOL)
        }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) return leaveForApp()
        // Escalation trigger c: the reply hedged ("I don't know", "I can't browse", nothing at all).
        val reply = chat.lastOrNull { it.id == replyId }
        if (result.reason == DoneReason.END && reply != null && reply.actions.isEmpty() && Hedge.detect(reply.text)) {
            RLog.i("claude_trigger", "kind" to EscalationTrigger.HEDGE)
            return afterLocal(replyId, question, history, attachment, voiceAsked, guard, EscalationTrigger.HEDGE)
        }
        offerAfter?.let { afterLocal(replyId, question, history, attachment, voiceAsked, guard, it, offerOnly = true) }
    }

    /** The escalation policy for this conversation now. */
    private fun decide(history: List<ChatTurn>, attachment: Attachment, guardFree: Boolean): EscalationDecision {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val personal = ClaudeContext.personal(ClaudeContext.recent(history), attachment)
        return EscalationPolicy.decide(s, ClaudeLink.keySaved(app), personal.isNotEmpty(), Assistant.locked(app), ClaudeLink.budgetReached(app, s), !guardFree)
    }

    /**
     * Robot's reply [replyId] is in (or Gemma handed over with ask_claude): bring Claude in now, offer it, or say why not.
     * At most one escalation per turn ([guard]); [offerOnly]: the pre-router's offer, after Gemma's answer.
     */
    private suspend fun afterLocal(replyId: String, question: String, history: List<ChatTurn>, attachment: Attachment, voiceAsked: Boolean,
        guard: EscalationGuard, trigger: EscalationTrigger, offerOnly: Boolean = false) {
        val decision = decide(history, attachment, guardFree = !guard.used)
        if (!guard.take(fromClaude = false)) return finishOrDrop(replyId)
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val keySaved = ClaudeLink.keySaved(app)
        // The budget is used up for today: Automatic asks first, and Robot says so once a day.
        if (decision == EscalationDecision.OFFER && keySaved && ClaudeSettings.escalation(s, keySaved) == Escalation.AUTO && ClaudeLink.budgetReached(app, s)) {
            val day = LocalDate.now().toString()
            if (budgetSaid != day) {
                budgetSaid = day
                notify(ClaudeWords.BUDGET)
                if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.BUDGET)
            }
        }
        when (decision) {
            EscalationDecision.AUTO -> if (offerOnly) finishOrDrop(replyId) else {
                finishOrDrop(replyId)
                escalateNow(null, question, history, attachment, voiceAsked, reuseBubble = false)
            }
            EscalationDecision.OFFER -> {
                val personal = ClaudeContext.personal(ClaudeContext.recent(history), attachment)
                val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
                if (chat.none { it.id == replyId }) {
                    // Gemma said nothing before asking for Claude: the offer stands on its own.
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) setText(replyId, if (api) "I'd rather check that with Claude." else "Claude could help with that.")
                    finishReply(replyId, DoneReason.END)
                }
                offer(replyId, PendingOffer(api, question, history, attachment, personal))
            }
            EscalationDecision.NEEDS_UNLOCK -> {
                if (chat.none { it.id == replyId }) {
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) setText(replyId, "Unlock your phone to check with Claude.")
                    finishReply(replyId, DoneReason.END)
                }
            }
            EscalationDecision.NONE -> finishOrDrop(replyId)
        }
        RLog.i("claude_escalation", "trigger" to trigger, "decision" to decision)
    }

    /** Finishes Robot's reply [replyId]; an empty one with no card is dropped instead (Gemma handed straight over). */
    private fun finishOrDrop(replyId: String) {
        if (!current(replyId)) return
        val ui = _state.value.turns.firstOrNull { it.id == replyId } ?: return
        if (ui.text.isBlank() && ui.actions.isEmpty()) {
            voice.finish(replyId, cancelled = true, speak = false)
            _state.update { st -> st.copy(turns = st.turns.filter { it.id != replyId }) }
        } else {
            finishReply(replyId, DoneReason.END)
        }
    }

    /** Shows "Want me to check with Claude?" under [replyId] (the Claude app: "Open Claude with: «…»"). */
    private fun offer(replyId: String, o: PendingOffer) {
        offers[replyId] = o
        val sends = if (!o.api) emptyList() else listOf("Your question", "The last ${ClaudeContext.RECENT_TURNS} turns of this conversation") + o.personal.map { it.label }
        val text = if (o.api) o.question else ClaudeAppLink.cap(o.question)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = Offer(o.api, text, sends)) else it }) }
    }

    private fun dropOffers() {
        if (offers.isEmpty()) return
        offers.clear()
        _state.update { st -> st.copy(turns = st.turns.map { if (it.offer != null) it.copy(offer = null) else it }) }
    }

    /** The owner tapped the offer under [replyId] (or said yes): ask Claude, or open the Claude app. */
    fun acceptOffer(replyId: String) {
        if (_state.value.busy || slot.running) return
        val o = offers.remove(replyId) ?: return
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
        VoiceHub.tts.stop()
        RLog.i("claude_offer", "accepted" to true, "api" to o.api)
        if (!o.api) {
            // The offer was the card: the Claude app opens now (never on the lock screen).
            if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone to open Claude.")
            lastToApp = o.question
            scope.launch {
                val r = withContext(Dispatchers.IO) { executor().run(Action.AskClaude(o.question)) }
                if (r.launchedApp && r.status.succeeded) leaveForApp() else notify(r.detail)
            }
            return
        }
        val id = claudeBubble()
        startTurn(id) { escalateNow(id, o.question, o.history, o.attachment, _state.value.speak, reuseBubble = true, shared = sharedFor(o.personal)) }
    }

    /** What an accepted offer may share: the screen's text and the picture while this panel still has them, and what Robot read. */
    private fun sharedFor(p: Set<Personal>) = if (p.isEmpty()) Shared() else
        Shared(screenText = screenText.takeIf { Personal.SCREEN in p }, image = lastImage.takeIf { Personal.PHOTO in p }, readResults = Personal.READ in p)

    fun dismissOffer(replyId: String) {
        offers.remove(replyId) ?: return
        RLog.i("claude_offer", "accepted" to false)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
    }

    /** A new, empty Claude reply bubble at the end of the conversation. */
    private fun claudeBubble(): String {
        val id = newId()
        _state.update { it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, "", streaming = true, claude = true), phase = Phase.THINKING, claudeStatus = ClaudeWords.CHECKING) }
        voice.start(id)
        return id
    }

    /**
     * Robot checks with Claude (automatically, or after the owner's tap): "Checking with Claude…" in Robot's voice, then
     * Claude's answer in Claude's. Only the owner's question and the last turns go, plus what a tap [shared].
     */
    private suspend fun escalateNow(replyId: String?, question: String, history: List<ChatTurn>, attachment: Attachment, voiceAsked: Boolean,
        reuseBubble: Boolean, shared: Shared = Shared()) {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val id = if (reuseBubble && replyId != null) replyId.also { r -> _state.update { st -> st.copy(claudeStatus = ClaudeWords.CHECKING, turns = st.turns.map { if (it.id == r) it.copy(claude = true) else it }) } }
            else claudeBubble()
        if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.CHECKING.trimEnd('…') + ".")
        if (Assistant.locked(app) && !s.claudeLocked) return localReply(id, ClaudeWords.LOCKED, claude = false)
        if (!ClaudeLink.networkAllowed(app)) return networkCard(id)
        val session = ClaudeLink.session(app, s, conversation = false) ?: return failReply(id, ClaudeFailure.NO_KEY.text)
        val blocks = ClaudeContext.escalation(question, history, ZonedDateTime.now(), ownerRegion, shared)
        runClaude(id, session, blocks, ClaudePlan.of(s.claudeEffort, voiceAsked, ClaudeRouter.deep(question)))
    }

    /** A question for Claude ("ask Claude …", "go get Claude", or any question in Claude mode). */
    private suspend fun claudeTurn(replyId: String, cmd: ClaudeCommand?, said: String, attachment: Attachment, sText: String?, image: ByteArray?,
        history: List<ChatTurn>, voiceAsked: Boolean) {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val keySaved = ClaudeLink.keySaved(app)
        if (!ClaudeSettings.enabled(s, keySaved)) return localReply(replyId, ClaudeWords.OFF, claude = false)
        val question = when (cmd) {
            is ClaudeCommand.Ask -> cmd.question
            is ClaudeCommand.Join -> cmd.question
            else -> said
        }
        if (ClaudeSettings.route(s, keySaved) == ClaudeRoute.APP) {
            // The Claude app: the question, or for "go get Claude" a short summary of the conversation so far.
            return handOff(replyId, question ?: ClaudeAppLink.summary(history).orEmpty())
        }
        if (!keySaved) return localReply(replyId, ClaudeFailure.NO_KEY.text, claude = false)
        if (Assistant.locked(app) && !s.claudeLocked) {
            endClaudeMode()
            return localReply(replyId, ClaudeWords.LOCKED, claude = false)
        }
        if (!ClaudeLink.networkAllowed(app)) return networkCard(replyId)
        if (cmd is ClaudeCommand.Join) {
            startClaudeMode(joinedAt = history.size)
            // Nothing goes to Claude until there is a question.
            if (question == null) return localReply(replyId, ClaudeWords.JOINED, claude = false)
        }
        val mode = _state.value.claudeMode
        val deep = (cmd as? ClaudeCommand.Ask)?.deep ?: ClaudeRouter.deep(said)
        // The screen or a photo goes only after a tap on its card ("Send your screen's text to Claude?").
        val extra = if (attachment != Attachment.NONE) attachment else (cmd as? ClaudeCommand.Ask)?.extra ?: Attachment.NONE
        var shared = Shared()
        if (extra != Attachment.NONE) {
            val screen = if (extra == Attachment.SCREEN) sText ?: screenText else null
            val img = if (extra == Attachment.PHOTO) image ?: lastImage else screenshot?.let { Images.jpeg(it) }
            if (screen == null && img == null) {
                return localReply(replyId, "Robot doesn't have your screen or a photo this time. Swipe up from the corner over what Claude should see, or add a photo, then ask again.", claude = false)
            }
            val what = if (extra == Attachment.SCREEN) "your screen's text" + (if (img != null) " and a screenshot" else "") else "the photo"
            if (!askToShare(replyId, "Send $what to Claude?", screen?.let { ToolCalls.safeTake(it, 600) }, "Send")) {
                return localReply(replyId, "Okay, nothing was sent to Claude.", claude = false)
            }
            shared = Shared(screenText = screen, image = img)
        }
        val session = (if (mode) claudeSession ?: ClaudeLink.session(app, s, conversation = true)?.also { claudeSession = it }
            else ClaudeLink.session(app, s, conversation = false)) ?: return failReply(replyId, ClaudeFailure.NO_KEY.text)
        val now = ZonedDateTime.now()
        val blocks = when {
            !mode -> ClaudeContext.oneShot(question ?: said, now, ownerRegion, shared)
            session.fresh -> {
                // Claude joins the conversation: what came before, with what Robot read only after "Include".
                val before = history.take(claudeJoinedAt.coerceIn(0, history.size))
                if (ClaudeContext.personal(before, Attachment.NONE).contains(Personal.READ) &&
                    askToShare(replyId, "Include what Robot read from your contacts, calendar or notes?", null, "Include", "Leave out")) {
                    shared = shared.copy(readResults = true)
                }
                ClaudeContext.join(before, question ?: said, now, ownerRegion, shared)
            }
            else -> ClaudeContext.next(question ?: said, shared)
        }
        runClaude(replyId, session, blocks, ClaudePlan.of(s.claudeEffort, voiceAsked, deep))
        if (mode) touchClaudeMode()
    }

    /**
     * One Claude turn through the API into the reply [replyId]: streamed into the bubble and spoken in Claude's voice,
     * Claude's calls as cards (always), then the cost line, today's spend, and Robot's own conversation rebuilt with it.
     */
    private suspend fun runClaude(replyId: String, session: ClaudeSession, blocks: List<ClaudeBlock>, plan: ClaudePlan) {
        _state.update { st -> st.copy(claudeStatus = st.claudeStatus ?: ClaudeWords.THINKING, turns = st.turns.map { if (it.id == replyId) it.copy(claude = true) else it }) }
        val t0 = System.currentTimeMillis()
        var first = -1L
        val heard = object : ClaudeListener {
            override fun text(delta: String) {
                if (first < 0) first = System.currentTimeMillis() - t0
                appendText(replyId, delta)
                voice.feed(replyId, delta, _state.value.speak).forEach { speakSentence(it, claude = true) }
            }

            override fun declined() {
                // Robot's own words, in Robot's voice, in place of what was shown.
                voice.mute()
                VoiceHub.tts.stop()
                setText(replyId, ClaudeWords.DECLINED)
                if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.DECLINED)
            }
        }
        val result = try {
            TurnRunner(ClaudeTurn(session, blocks, plan, heard), PanelActions, PanelView(replyId), slot.confirmations, untrusted = true, remote = true, newId = ::newId).run()
        } finally {
            val r = session.lastReport
            if (r.requests > 0) withContext(NonCancellable + Dispatchers.IO) { app.heyrobot.sys.ClaudeSpendStore.add(VoiceHub.app, r.dollars) }
            RLog.i("claude_turn", "model" to session.model, "effort" to plan.effort, "firstMs" to first, "ms" to (System.currentTimeMillis() - t0),
                "requests" to r.requests, "searches" to r.searches, "searchErrors" to r.searchErrors, "cache_read_input_tokens" to r.usage.cacheRead)
        }
        _state.update { it.copy(claudeStatus = null) }
        val report = session.lastReport
        result.failure?.let {
            // Said aloud, in Robot's voice ("No internet", "Claude is busy, try again in a minute").
            if (_state.value.speak) VoiceHub.tts.speak(it)
            return failReply(replyId, it)
        }
        _state.update { st ->
            st.copy(turns = st.turns.map {
                if (it.id == replyId) it.copy(sources = report.domains.takeIf { report.searches > 0 } ?: emptyList(),
                    cost = ClaudeCost.line(report.dollars).takeIf { VoiceHub.settings.claudeShowCost && report.requests > 0 }) else it
            })
        }
        finishReply(replyId, result.reason ?: DoneReason.END, costMicros = ClaudeCost.micros(report.dollars))
        // Claude's answer joins Robot's own conversation: :brain rebuilds it from the history next time.
        closeLiveConversation()
        if (result.launchedApp) leaveForApp()
    }

    /** "Send …to Claude?" with Send and Cancel (or Include and Leave out): true only after the tap. */
    private suspend fun askToShare(replyId: String, summary: String, body: String?, confirm: String, cancel: String = "Cancel"): Boolean {
        val id = newId()
        val view = PanelView(replyId)
        view.card(Card(id, ToolName.ASK_CLAUDE, summary, ActionStatus.PENDING, "Nothing goes to Claude until you tap $confirm.", body = body, confirm = confirm, cancel = cancel))
        view.phase(TurnPhase.CONFIRMING)
        val pick = try {
            slot.confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, ToolName.ASK_CLAUDE, summary, ActionStatus.DECLINED, null))
            throw e
        }
        view.card(Card(id, ToolName.ASK_CLAUDE, summary, if (pick >= 0) ActionStatus.DONE else ActionStatus.DECLINED, if (pick >= 0) "Shared with Claude." else "Not shared."))
        view.phase(TurnPhase.THINKING)
        return pick >= 0
    }

    /** GrapheneOS's Network toggle is off for Robot: a card that says so (Claude can't be reached at all). */
    private fun networkCard(replyId: String) {
        PanelView(replyId).card(Card(newId(), ToolName.ASK_CLAUDE, "Robot can't use the network", ActionStatus.FAILED, ClaudeFailure.NO_NETWORK_PERMISSION.text))
        _state.update { it.copy(claudeStatus = null) }
        finishReply(replyId, DoneReason.END)
    }

    /**
     * The Claude app route: the card "Open Claude with: «…»", then the app opens with the question and the panel steps
     * aside (the launchedApp path). Robot never hears the answer; the owner can read it back with the corner swipe.
     */
    private suspend fun handOff(replyId: String, text: String) {
        lastToApp = text.takeIf { it.isNotBlank() }
        val scripted = object : TurnModel {
            override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(RawToolCall(ToolName.ASK_CLAUDE.id, mapOf("question" to text))))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
            override fun close() {}
        }
        val r = TurnRunner(scripted, PanelActions, PanelView(replyId), slot.confirmations, untrusted = false, newId = ::newId).runHandOff()
        finishReply(replyId, DoneReason.END)
        if (r.launchedApp) leaveForApp()
    }

    /** Robot's own words as the reply (no model): spoken in Robot's voice ([claude] false) when replies are spoken. */
    private fun localReply(replyId: String, text: String, claude: Boolean = false) {
        _state.update { st -> st.copy(claudeStatus = null, turns = st.turns.map { if (it.id == replyId) it.copy(claude = claude) else it }) }
        setText(replyId, text)
        voice.feed(replyId, text, _state.value.speak).forEach { speakSentence(it, claude) }
        finishReply(replyId, DoneReason.END)
    }

    // ------------------------------------------------------------------ Claude mode

    /** Where Claude mode started in the conversation: what came before is what Claude joins. */
    private var claudeJoinedAt = 0

    private fun startClaudeMode(joinedAt: Int) {
        if (!_state.value.claudeMode) {
            claudeSession = null
            claudeJoinedAt = joinedAt
        }
        _state.update { it.copy(claudeMode = true) }
        touchClaudeMode()
        RLog.i("claude_mode", "on" to true)
    }

    /** A turn in Claude mode: the 10 minutes start again. */
    private fun touchClaudeMode() {
        claudeLast = System.currentTimeMillis()
        claudeIdle?.cancel()
        claudeIdle = scope.launch {
            delay(CLAUDE_IDLE_MS)
            if (!slot.running) endClaudeMode()
        }
    }

    private fun expireClaudeMode() {
        if (_state.value.claudeMode && System.currentTimeMillis() - claudeLast > CLAUDE_IDLE_MS) endClaudeMode()
    }

    /** "Back to Robot" on the chip, "thanks Claude", the panel closing, 10 minutes without a turn, or the lock screen. */
    fun endClaudeMode() {
        claudeIdle?.cancel()
        claudeIdle = null
        if (claudeSession == null && !_state.value.claudeMode) return
        claudeSession = null
        _state.update { it.copy(claudeMode = false) }
        RLog.i("claude_mode", "on" to false)
    }

    // ------------------------------------------------------------------ reading Claude's answer off the Claude app

    /**
     * "Read Claude's answer": the last answer on the Claude app's screen, from the text the owner's corner swipe gave
     * the panel. It is spoken in Claude's voice and joins Robot's conversation as screen content (later actions ask
     * first). Nothing usable found: "Ask about this screen" instead.
     */
    fun readClaudeAnswer() {
        val st = _state.value
        if (st.busy) return
        val pieces = claudePieces ?: return notify(ClaudeWords.SWIPE_TO_READ)
        val answer = ClaudeAppAnswer.extract(pieces, lastToApp)
        RLog.i("claude_read_back", "found" to (answer != null), "chars" to (answer?.length ?: 0))
        if (answer == null) {
            notify("Robot couldn't find Claude's answer on this screen. You can ask about the screen instead.")
            attachScreen()
            return
        }
        VoiceHub.tts.stop()
        val id = newId()
        val turn = ChatTurn(id, TurnRole.ASSISTANT, answer, System.currentTimeMillis(), claude = true, readBack = true)
        chat += turn
        // Screen content: saved only when the person keeps the conversation, as for any screen question.
        val keep = if (keptByUser) st.keep else false
        _state.update { it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, answer, claude = true, readBack = true), keep = keep, claudeScreen = false) }
        voice.start(id)
        voice.feed(id, answer, st.speak).forEach { speakSentence(it, claude = true) }
        voice.finish(id, cancelled = false, speak = st.speak)?.let { speakSentence(it, claude = true) }
        claudePieces = null
        lastActivity = System.currentTimeMillis()
        persist()
        closeLiveConversation()
    }

    /** The model side of a turn: :brain through [BrainClient]. Nothing is sent once the turn was stopped. */
    private class BrainTurn(
        private val c: BrainClient, private val convId: String, private val replyId: String,
        private val gen: BrainMsg.Generate, private val img: ByteArray?,
    ) : TurnModel {
        override suspend fun ask(): ModelOutcome {
            currentCoroutineContext().ensureActive()
            return collect(c.request(BrainProtocol.REQ_GENERATE, gen, img), replyId)
        }

        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            currentCoroutineContext().ensureActive()
            return collect(c.request(BrainProtocol.REQ_TOOL_RESULT, BrainMsg.ToolResult(convId, results)), replyId)
        }

        override fun close() = c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))
    }

    /** The phone side of a turn: the approval inputs and ToolExecutor (contacts, calendar and files off the main thread). */
    private object PanelActions : TurnActions {
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override val settings: RobotSettings get() = VoiceHub.settings
        override suspend fun prepare(action: Action): Prepared = withContext(Dispatchers.IO) { executor().prepare(action) }
        override suspend fun run(action: Action): Executed = withContext(Dispatchers.IO) { executor().run(action) }
    }

    private fun executor() = ToolExecutor(VoiceHub.app, VoiceHub.settings) { i -> host?.launch(i) ?: false }

    /** What the turn shows: the reply bubble [replyId], its cards and the panel's phase. */
    private class PanelView(private val replyId: String) : TurnView {
        override fun phase(p: TurnPhase) {
            if (!current(replyId)) return
            _state.update { it.copy(phase = if (p == TurnPhase.CONFIRMING) Phase.CONFIRMING else Phase.THINKING) }
        }

        override fun card(c: Card) {
            val icon = (c.action as? Action.OpenApp)?.key?.let { key -> icons.getOrPut(key) { executor().appIcon(key) } }
            // What a read tool finds goes to Claude only after this tap: the button says so.
            val confirm = c.confirm ?: if (c.remote && c.tool?.reads == true) "Share with Claude" else c.action?.confirmLabel
            upsertAction(replyId, UiAction(c.id, c.tool, c.summary, c.status, c.detail, icon, c.body, c.choices.map { it.label }, confirm, c.undo, c.content, c.more, c.cancel))
        }

        override val text: String get() = _state.value.turns.firstOrNull { it.id == replyId }?.text.orEmpty()
        override fun replaceText(text: String) = setText(replyId, text)
        override fun paragraphBreak() = paragraphBreak(replyId)
        override fun say(sentence: String) {
            voice.say(replyId, sentence, _state.value.speak)?.let(::speakSentence)
        }
    }

    private suspend fun collect(req: Pair<Int, Flow<BrainMsg>>, replyId: String): ModelOutcome {
        reqId = req.first
        var calls: List<RawToolCall>? = null
        var out: ModelOutcome = ModelOutcome.Failed("The model didn't answer.")
        try {
            req.second.collect { m ->
                when (m) {
                    is BrainMsg.Chunk -> if (m.text.isNotEmpty()) {
                        appendText(replyId, m.text)
                        voice.feed(replyId, m.text, _state.value.speak).forEach(::speakSentence)
                    }
                    is BrainMsg.ToolCallsMsg -> calls = m.calls
                    is BrainMsg.Done -> out = calls?.takeIf { m.reason == DoneReason.TOOL_CALLS }?.let { ModelOutcome.Calls(it) }
                        ?: ModelOutcome.Done(m.reason, m.textCalls)
                    is BrainMsg.Error -> out = ModelOutcome.Failed(m.code.text())
                    else -> {}
                }
            }
        } finally {
            reqId = 0
        }
        return out
    }

    private fun appendText(replyId: String, text: String) = _state.update { st ->
        if (st.turns.none { it.id == replyId }) st
        else st.copy(phase = Phase.ANSWERING, claudeStatus = null, turns = st.turns.map { if (it.id == replyId) it.copy(text = it.text + text) else it })
    }

    /** The model's text after a tool round starts a new paragraph (and a new sentence for speech). */
    private fun paragraphBreak(replyId: String) {
        val t = _state.value.turns.firstOrNull { it.id == replyId }?.text ?: return
        if (t.isBlank() || t.endsWith("\n\n")) return
        setText(replyId, t.trimEnd() + "\n\n")
        voice.feed(replyId, "\n", _state.value.speak).forEach(::speakSentence)
    }

    private fun setText(replyId: String, text: String) = _state.update { st ->
        st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(text = text) else it })
    }

    /** One sentence aloud: Robot's voice, or Claude's ([claude]: another voice of the same model, so you hear who talks). */
    private fun speakSentence(s: String, claude: Boolean = false) {
        // Never read out a tool call the model wrote as text.
        if (!claude && (s.contains("call:") || s.contains("<start_function_call>") || ToolCalls.parseText(s).isNotEmpty())) return
        val clean = SpeechText.clean(s)
        if (clean.isEmpty()) return
        if (claude) VoiceHub.tts.speakOther(clean, ClaudeVoices.speaker(VoiceHub.settings.voiceChoice, VoiceHub.settings.claudeVoice))
        else VoiceHub.tts.speak(clean)
    }

    private suspend fun leaveForApp() {
        hidingFor = "action"
        delay(900)
        if (shown) host?.hidePanel()
    }

    /** The person tapped "Do it" (true) or "Cancel" on a confirmation card. */
    fun confirm(actionId: String, yes: Boolean) {
        slot.confirmations.answer(actionId, yes)
    }

    /** The person tapped one of the people or numbers a card listed. */
    fun choose(actionId: String, index: Int) {
        slot.confirmations.choose(actionId, index)
    }

    /** Undo on a note Robot just saved: it leaves Robot Notes, the card says so, the history keeps it as undone. */
    fun undo(actionId: String) {
        val st = _state.value
        val turn = st.turns.firstOrNull { t -> t.actions.any { it.id == actionId } } ?: return
        val a = turn.actions.first { it.id == actionId }
        val key = a.undo ?: return
        upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = "Removed from Robot Notes.", undo = null))
        scope.launch(Dispatchers.IO) { NotesFiles.store(VoiceHub.app).delete(key) }
        // The live conversation still believes the note was saved: it is rebuilt from the history ("undone") next time.
        if (slot.running) closeConvAfterTurn = true else closeLiveConversation()
        // A finished reply's record changes with it (a reply still running records its cards when it ends).
        val i = chat.indexOfFirst { it.id == turn.id }
        if (i >= 0) {
            val ui = _state.value.turns.first { it.id == turn.id }
            chat[i] = chat[i].copy(actions = records(settled(ui.actions)))
            persist()
        }
    }

    private fun closeLiveConversation() {
        closeConvAfterTurn = false
        client?.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(_state.value.convId))
    }

    private fun upsertAction(replyId: String, a: UiAction) = _state.update { st ->
        st.copy(turns = st.turns.map { t ->
            if (t.id != replyId) t
            else if (t.actions.any { it.id == a.id }) t.copy(actions = t.actions.map { if (it.id == a.id) a else it })
            else t.copy(actions = t.actions + a)
        })
    }

    // ------------------------------------------------------------------ ending a reply

    /** Stop button: stops the reply (or the model load) and its voice; a waiting card is declined. */
    fun stopGenerating() {
        val id = reqId
        if (id != 0) client?.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
        // The voice stops now: nothing already queued, and not the cut-off last sentence (finishReply drops it).
        voice.mute()
        VoiceHub.tts.stop()
        // The turn ends as "stopped" (send's cancellation handler, which runs later on the main thread); :brain
        // rebuilds that conversation next time.
        slot.stop()
    }

    /** "Stop" said, or a tap: the voice stops, and the rest of a reply still streaming is shown but not spoken. */
    fun stopSpeaking() {
        voice.mute()
        VoiceHub.tts.stop()
        _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
    }

    /** False once the conversation moved on (a new or loaded conversation replaced the one this reply belonged to). */
    private fun current(replyId: String) = _state.value.turns.any { it.id == replyId } && chat.none { it.id == replyId }

    /** [costMicros]: a Claude reply's estimated price, kept with it in the history. */
    private fun finishReply(replyId: String, reason: DoneReason, costMicros: Long = 0) {
        if (!current(replyId)) return
        val ui = _state.value.turns.firstOrNull { it.id == replyId }
        val claude = ui?.claude == true
        // A stopped reply's last, unfinished sentence is not read out (nor anything after "stop").
        voice.finish(replyId, cancelled = reason == DoneReason.CANCELLED, speak = _state.value.speak)?.let { speakSentence(it, claude) }
        val stopped = reason == DoneReason.CANCELLED || reason == DoneReason.LENGTH || reason == DoneReason.GARBAGE
        var text = ui?.text?.trim().orEmpty()
        if (reason == DoneReason.GARBAGE) text = (text.take(200) + "\n\n(The answer went wrong, so Robot stopped it.)").trim()
        val actions = settled(ui?.actions.orEmpty())
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(), actions = records(actions), stopped = stopped,
            claude = claude, sources = ui?.sources.orEmpty(), costMicros = costMicros)
        _state.update { st ->
            st.copy(
                // Only the turn's phases end here: "hey robot" during the answer is already listening.
                phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking),
                claudeStatus = null,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = text, streaming = false, stopped = stopped, actions = actions) else it },
            )
        }
        lastActivity = System.currentTimeMillis()
        persist()
        if (closeConvAfterTurn) closeLiveConversation()
    }

    private fun failReply(replyId: String, message: String, action: NoticeAction = NoticeAction.NONE) {
        if (!current(replyId)) return
        voice.finish(replyId, cancelled = true, speak = false)
        // Actions that ran before the failure stay on record.
        val actions = settled(_state.value.turns.firstOrNull { it.id == replyId }?.actions.orEmpty())
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, message, System.currentTimeMillis(), actions = records(actions), error = true)
        _state.update { st ->
            st.copy(
                phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking),
                claudeStatus = null,
                notice = if (action != NoticeAction.NONE) Notice(message, action) else st.notice,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = message, streaming = false, error = true, actions = actions, claude = false) else it },
            )
        }
        persist()
        if (closeConvAfterTurn) closeLiveConversation()
    }

    /** A finished reply has no card left waiting: one still pending was not done (and is never saved as waiting). */
    private fun settled(actions: List<UiAction>) = actions.map { if (it.status == ActionStatus.PENDING) it.copy(status = ActionStatus.DECLINED) else it }

    private fun records(actions: List<UiAction>) = actions.mapNotNull { a -> a.tool?.let { ActionRecord(it, a.summary, a.status, a.detail, a.content) } }
}
