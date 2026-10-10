package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Prepared
import app.heyrobot.core.Prompt
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
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
)

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
        _state.update {
            it.copy(locked = locked, screenAvailable = false, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "",
                showId = it.showId + 1)
        }
        BrainKeeper.prewarm(VoiceHub.app)
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
        // Screen content lives only while the panel is open.
        screenText = null
        screenshot = null
        if (!forCapture) {
            draftJpeg = null
            _state.update { it.copy(draft = null) }
        }
        _state.update { it.copy(phase = if (it.busy) it.phase else Phase.IDLE, partial = "", screenAvailable = false) }
        lastActivity = System.currentTimeMillis()
        // Whatever the reason (a camera left with Home must not keep Gemma loaded): showing the panel again cancels it.
        BrainKeeper.scheduleUnload(VoiceHub.settings.keepModelMinutes)
    }

    /** AssistStructure text (corner swipe only). */
    fun onAssistText(pieces: List<String>) {
        if (ignoreAssist || !shown) return
        screenText = ScreenText.build(pieces)
        _state.update { it.copy(screenAvailable = screenText != null || screenshot != null) }
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
        chat.clear()
        groups = setOf(ToolGroup.CORE)
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
        chat.clear()
        chat += c.turns
        // The groups its questions and actions used: continuing it describes the same tools.
        groups = ToolRouter.used(c.turns)
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
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail, content = it.content) }, stopped = stopped, error = error)

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
        if (h.launch(HandOffApps.robotPage(VoiceHub.app, AppPages.PERMISSIONS))) {
            hidingFor = "action"
            h.hidePanel()
        }
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
     */
    fun send(text: String, fromVoice: Boolean = false): Boolean {
        val st = _state.value
        val q = text.trim()
        if (q.isEmpty() && st.draft == null) return false
        if (st.busy || slot.running) {
            if (fromVoice) notify("Robot is still on the last question. Ask again when it's done.")
            return false
        }
        VoiceHub.tts.stop()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> screenshot?.let { Images.jpeg(it) }
            Attachment.NONE -> null
        }
        val sText = if (attachment == Attachment.SCREEN) screenText else null
        val history = chat.toList()
        val now = System.currentTimeMillis()
        val user = ChatTurn(newId(), TurnRole.USER, q.ifEmpty { Prompt.defaultQuestion(attachment) }, now, attachment)
        chat += user
        // Groups are only ever added: a question that needs a new one rebuilds the conversation once (:brain).
        groups = groups + ToolRouter.route(q)
        val replyId = newId()
        // Decided per turn: a screen turn stops saving until the person taps Keep.
        val keep = if (attachment == Attachment.SCREEN && !keptByUser) false else st.keep
        // Screen text, a picture or a read tool's findings anywhere in this conversation: every action asks first.
        val untrusted = TurnRules.untrusted(chat)
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, attachment, st.draft?.thumb) + UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true),
                phase = Phase.THINKING, draft = null, partial = "", notice = null, keep = keep,
                locked = Assistant.locked(VoiceHub.app),
            )
        }
        draftJpeg = null
        lastActivity = now
        voice.start(replyId)
        // The reply will be read aloud: the voice loads while the model thinks.
        if (st.speak) VoiceHub.tts.prewarm()
        slot.start {
            try {
                runTurn(replyId, user.text, attachment, sText, image, history, untrusted)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finishReply(replyId, DoneReason.CANCELLED) }
                throw e
            } catch (t: Throwable) {
                RLog.w("panel_turn", t)
                failReply(replyId, "Something went wrong. Please try again.")
            }
        }
        return true
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
    ) {
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
        val system = Prompt.system(now, Assistant.locked(VoiceHub.app))
        // Only the groups this conversation needs, and nothing turned off in settings, is described to the model.
        val tools = ToolRouter.tools(groups, VoiceHub.settings)
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
        val result = TurnRunner(BrainTurn(c, convId, replyId, gen, img), PanelActions, PanelView(replyId), slot.confirmations, untrusted, ::newId).run()
        result.failure?.let { return failReply(replyId, it) }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) leaveForApp()
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
            upsertAction(replyId, UiAction(c.id, c.tool, c.summary, c.status, c.detail, icon, c.body, c.choices.map { it.label }, c.action?.confirmLabel, c.undo, c.content))
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
        else st.copy(phase = Phase.ANSWERING, turns = st.turns.map { if (it.id == replyId) it.copy(text = it.text + text) else it })
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

    private fun speakSentence(s: String) {
        // Never read out a tool call the model wrote as text.
        if (s.contains("call:") || s.contains("<start_function_call>") || ToolCalls.parseText(s).isNotEmpty()) return
        val clean = SpeechText.clean(s)
        if (clean.isNotEmpty()) VoiceHub.tts.speak(clean)
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
        // A finished reply's record changes with it (a reply still running records its cards when it ends).
        val i = chat.indexOfFirst { it.id == turn.id }
        if (i >= 0) {
            val ui = _state.value.turns.first { it.id == turn.id }
            chat[i] = chat[i].copy(actions = records(settled(ui.actions)))
            persist()
        }
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

    private fun finishReply(replyId: String, reason: DoneReason) {
        if (!current(replyId)) return
        // A stopped reply's last, unfinished sentence is not read out (nor anything after "stop").
        voice.finish(replyId, cancelled = reason == DoneReason.CANCELLED, speak = _state.value.speak)?.let(::speakSentence)
        val stopped = reason == DoneReason.CANCELLED || reason == DoneReason.LENGTH || reason == DoneReason.GARBAGE
        val ui = _state.value.turns.firstOrNull { it.id == replyId }
        var text = ui?.text?.trim().orEmpty()
        if (reason == DoneReason.GARBAGE) text = (text.take(200) + "\n\n(The answer went wrong, so Robot stopped it.)").trim()
        val actions = settled(ui?.actions.orEmpty())
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(), actions = records(actions), stopped = stopped)
        _state.update { st ->
            st.copy(
                // Only the turn's phases end here: "hey robot" during the answer is already listening.
                phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking),
                turns = st.turns.map { if (it.id == replyId) it.copy(text = text, streaming = false, stopped = stopped, actions = actions) else it },
            )
        }
        lastActivity = System.currentTimeMillis()
        persist()
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
                notice = if (action != NoticeAction.NONE) Notice(message, action) else st.notice,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = message, streaming = false, error = true, actions = actions) else it },
            )
        }
        persist()
    }

    /** A finished reply has no card left waiting: one still pending was not done (and is never saved as waiting). */
    private fun settled(actions: List<UiAction>) = actions.map { if (it.status == ActionStatus.PENDING) it.copy(status = ActionStatus.DECLINED) else it }

    private fun records(actions: List<UiAction>) = actions.mapNotNull { a -> a.tool?.let { ActionRecord(it, a.summary, a.status, a.detail, a.content) } }
}
