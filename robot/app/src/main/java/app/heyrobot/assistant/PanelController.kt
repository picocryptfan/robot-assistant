package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.GateDecision
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Prompt
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SentenceSplitter
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.toPromptTurns
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ConversationStore
import app.heyrobot.voice.SpeechToText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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

/** TRANSCRIBING: the person stopped talking and the speech is being written down. */
enum class Phase { IDLE, LISTENING, TRANSCRIBING, THINKING, ANSWERING, SPEAKING }

data class UiAction(val id: String, val tool: ToolName?, val summary: String, val status: ActionStatus, val detail: String?)

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
    val brain: BrainKeeper.State = BrainKeeper.State(),
) {
    val busy: Boolean get() = phase == Phase.THINKING || phase == Phase.ANSWERING
}

/** What the session offers the controller. */
interface PanelHost {
    fun hidePanel()
    /** Starts another app's activity from the panel; false if it could not. */
    fun launch(intent: Intent): Boolean
    /** Starts Robot's capture activity as an assistant activity. */
    fun startCapture(intent: Intent)
}

object PanelController {
    /** A panel reopened within this time continues the same conversation. */
    private const val CONTINUE_MS = 5 * 60_000L
    private const val MAX_TOOL_ROUNDS = 3

    private val scope = MainScope()
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
    private var job: Job? = null
    private var reqId = 0
    private var client: BrainClient? = null
    private val confirms = HashMap<String, CompletableDeferred<Boolean>>()
    private val splitter = SentenceSplitter()
    private var initialized = false

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
        _state.update { it.copy(locked = locked, screenAvailable = false, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "") }
        BrainKeeper.prewarm(VoiceHub.app)
        // Speech recognition loads while the panel opens (and the person starts talking); the voice too if replies are spoken.
        VoiceHub.prewarmVoice(_state.value.speak || source == RobotVoiceService.SRC_WAKE)
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        if (source == RobotVoiceService.SRC_WAKE) startListening()
    }

    fun onHide() {
        shown = false
        VoiceHub.stt.cancel()
        VoiceHub.listener?.wakePaused = false
        val forCapture = hidingFor == "capture"
        if (hidingFor == null) {
            stopGenerating()
            VoiceHub.tts.stop()
        }
        confirms.values.forEach { it.complete(false) }
        // Screen content lives only while the panel is open.
        screenText = null
        screenshot = null
        if (!forCapture) {
            draftJpeg = null
            _state.update { it.copy(draft = null) }
        }
        _state.update { it.copy(phase = if (it.busy) it.phase else Phase.IDLE, partial = "", screenAvailable = false) }
        lastActivity = System.currentTimeMillis()
        if (!forCapture) BrainKeeper.scheduleUnload(VoiceHub.settings.keepModelMinutes)
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
        job?.cancel()
        VoiceHub.tts.stop()
        chat.clear()
        startedLocked = locked
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update { PanelState(newId(), locked = locked, speak = it.speak, sttAvailable = it.sttAvailable, micHint = it.micHint, screenAvailable = it.screenAvailable, brain = it.brain) }
    }

    private fun loadConversation(id: String) {
        val c = ConversationStore.load(VoiceHub.app, id) ?: return newConversation(false)
        job?.cancel()
        chat.clear()
        chat += c.turns
        startedLocked = false
        keptByUser = true
        lastActivity = System.currentTimeMillis()
        _state.update { PanelState(c.id, turns = c.turns.map { it.toUi() }, keep = true, sttAvailable = it.sttAvailable, micHint = it.micHint, brain = it.brain) }
    }

    private fun ChatTurn.toUi() = UiTurn(id, role, text, attachment, null,
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail) }, stopped = stopped, error = error)

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
        val h = host ?: return
        hidingFor = "capture"
        h.startCapture(CaptureActivity.intent(VoiceHub.app, mode))
        h.hidePanel()
    }

    /** From CaptureActivity (same process): the picture to send with the next question. */
    fun onImagePicked(jpeg: ByteArray, thumb: Bitmap) {
        draftJpeg = jpeg
        _state.update { it.copy(draft = Draft(Attachment.PHOTO, thumb), notice = null, focusRequest = it.focusRequest + 1) }
    }

    // ------------------------------------------------------------------ voice input

    fun micPressed() {
        if (_state.value.busy) return
        VoiceHub.tts.stop()
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    fun micReleased() = VoiceHub.stt.stop()

    fun onWakeWhileShown() {
        VoiceHub.tts.stop()
        if (_state.value.busy) stopGenerating()
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
                    if (text.isNotBlank()) send(text)
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

    fun send(text: String) {
        val st = _state.value
        val q = text.trim()
        if (st.busy || (q.isEmpty() && st.draft == null)) return
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
        val replyId = newId()
        val keep = if (attachment == Attachment.SCREEN && !keptByUser) false else st.keep
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, attachment, st.draft?.thumb) + UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true),
                phase = Phase.THINKING, draft = null, partial = "", notice = null, keep = keep,
                locked = Assistant.locked(VoiceHub.app),
            )
        }
        draftJpeg = null
        lastActivity = now
        splitter.reset()
        job = scope.launch {
            try {
                runTurn(replyId, user.text, attachment, sText, image, history)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finishReply(replyId, DoneReason.CANCELLED) }
                throw e
            } catch (t: Throwable) {
                RLog.w("panel_turn", t)
                failReply(replyId, "Something went wrong. Please try again.")
            }
        }
    }

    private sealed class Outcome {
        data class Done(val reason: DoneReason) : Outcome()
        data class Calls(val calls: List<RawToolCall>) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private class CallResult(val name: String, val response: Map<String, Any?>, val launched: Boolean)

    private suspend fun runTurn(replyId: String, question: String, attachment: Attachment, screen: String?, image: ByteArray?, history: List<ChatTurn>) {
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
        val gen = BrainMsg.Generate(
            convId = convId,
            system = Prompt.system(ZonedDateTime.now(), Assistant.locked(VoiceHub.app)),
            history = history.toPromptTurns(),
            historyCount = history.size,
            text = Prompt.userText(question, screen, attachment),
            hasImage = img != null,
        )
        var outcome = collect(c.request(BrainProtocol.REQ_GENERATE, gen, img), replyId)
        var rounds = 0
        while (outcome is Outcome.Calls && rounds < MAX_TOOL_ROUNDS) {
            rounds++
            val results = runCalls(outcome.calls, replyId)
            if (results.any { it.launched }) {
                // Another app is opening: no follow-up reply. The model rebuilds this conversation from history next time.
                c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))
                finishReply(replyId, DoneReason.END)
                leaveForApp()
                return
            }
            _state.update { it.copy(phase = Phase.THINKING) }
            paragraphBreak(replyId)
            outcome = collect(c.request(BrainProtocol.REQ_TOOL_RESULT, BrainMsg.ToolResult(convId, results.map { it.name to it.response })), replyId)
        }
        when (outcome) {
            is Outcome.Failed -> failReply(replyId, outcome.message)
            is Outcome.Done -> {
                if (outcome.reason == DoneReason.END && textCalls(replyId, c, convId)) return
                finishReply(replyId, outcome.reason)
            }
            is Outcome.Calls -> {
                c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))
                finishReply(replyId, DoneReason.END)
            }
        }
    }

    private suspend fun collect(req: Pair<Int, Flow<BrainMsg>>, replyId: String): Outcome {
        reqId = req.first
        var calls: List<RawToolCall>? = null
        var out: Outcome = Outcome.Failed("The model didn't answer.")
        try {
            req.second.collect { m ->
                when (m) {
                    is BrainMsg.Chunk -> if (m.text.isNotEmpty()) {
                        appendText(replyId, m.text)
                        if (_state.value.speak) splitter.feed(m.text).forEach(::speakSentence)
                    }
                    is BrainMsg.ToolCallsMsg -> calls = m.calls
                    is BrainMsg.Done -> out = calls?.takeIf { m.reason == DoneReason.TOOL_CALLS }?.let { Outcome.Calls(it) } ?: Outcome.Done(m.reason)
                    is BrainMsg.Error -> out = Outcome.Failed(m.code.text())
                    else -> {}
                }
            }
        } finally {
            reqId = 0
        }
        return out
    }

    private fun appendText(replyId: String, text: String) = _state.update { st ->
        st.copy(phase = Phase.ANSWERING, turns = st.turns.map { if (it.id == replyId) it.copy(text = it.text + text) else it })
    }

    /** The model's text after a tool round starts a new paragraph (and a new sentence for speech). */
    private fun paragraphBreak(replyId: String) {
        val t = _state.value.turns.firstOrNull { it.id == replyId }?.text ?: return
        if (t.isBlank() || t.endsWith("\n\n")) return
        setText(replyId, t.trimEnd() + "\n\n")
        if (_state.value.speak) splitter.feed("\n").forEach(::speakSentence)
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

    /** Tool calls written as text (small models do this): run them like real ones, without a follow-up reply. */
    private suspend fun textCalls(replyId: String, c: BrainClient, convId: String): Boolean {
        val text = _state.value.turns.firstOrNull { it.id == replyId }?.text ?: return false
        val found = ToolCalls.parseText(text)
        if (found.isEmpty()) return false
        val rest = ToolCalls.stripText(text, found)
        // A real answer that merely mentions a call is left alone.
        if (rest.length > 160) return false
        setText(replyId, rest)
        val results = runCalls(found.map { it.call }, replyId, speakDetail = true)
        c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))
        finishReply(replyId, DoneReason.END)
        if (results.any { it.launched }) leaveForApp()
        return true
    }

    private suspend fun leaveForApp() {
        hidingFor = "action"
        delay(900)
        if (shown) host?.hidePanel()
    }

    // ------------------------------------------------------------------ actions

    private suspend fun runCalls(calls: List<RawToolCall>, replyId: String, speakDetail: Boolean = false): List<CallResult> {
        val out = mutableListOf<CallResult>()
        val executor = ToolExecutor(VoiceHub.app) { i -> host?.launch(i) ?: false }
        for (raw in calls) {
            when (val p = ToolCalls.parse(raw)) {
                is ParsedCall.Invalid -> {
                    addAction(replyId, UiAction(newId(), ToolName.of(p.tool), "Couldn't do “${p.tool}”", ActionStatus.INVALID, p.reason))
                    out += CallResult(raw.name, ActionStatus.INVALID.toolResponse(p.reason), false)
                }
                is ParsedCall.Ok -> out += decideAndRun(p.action, replyId, executor, speakDetail)
            }
        }
        return out
    }

    private suspend fun decideAndRun(a: Action, replyId: String, executor: ToolExecutor, speakDetail: Boolean): CallResult {
        val locked = Assistant.locked(VoiceHub.app)
        val id = newId()
        val decision = ApprovalGate.decide(a.tool, locked, VoiceHub.settings)
        RLog.i("tool_gate", "tool" to a.tool, "decision" to decision, "locked" to locked)
        val status: ActionStatus
        var detail: String? = null
        var launched = false
        when (decision) {
            GateDecision.NEEDS_UNLOCK -> { status = ActionStatus.NEEDS_UNLOCK; detail = "Unlock your phone, then ask again." }
            GateDecision.DISABLED -> { status = ActionStatus.DISABLED; detail = "${a.tool.title} are turned off in Robot's settings." }
            GateDecision.RUN, GateDecision.CONFIRM -> {
                val go = if (decision == GateDecision.RUN) true else {
                    addAction(replyId, UiAction(id, a.tool, a.describe(), ActionStatus.PENDING, null))
                    _state.update { it.copy(phase = Phase.IDLE) }
                    val d = CompletableDeferred<Boolean>().also { confirms[id] = it }
                    try { d.await() } finally { confirms.remove(id) }
                }
                if (go) {
                    val r = executor.run(a)
                    status = r.status
                    detail = r.detail
                    launched = r.launchedApp && r.status == ActionStatus.DONE
                    if (speakDetail && _state.value.speak && !launched) speakSentence(r.detail)
                } else {
                    status = ActionStatus.DECLINED
                }
            }
        }
        upsertAction(replyId, UiAction(id, a.tool, a.describe(), status, detail))
        return CallResult(a.tool.id, status.toolResponse(detail), launched)
    }

    /** The person tapped "Do it" (true) or "Cancel" on a confirmation card. */
    fun confirm(actionId: String, yes: Boolean) {
        confirms[actionId]?.complete(yes)
    }

    private fun addAction(replyId: String, a: UiAction) = _state.update { st ->
        st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(actions = it.actions + a) else it })
    }

    private fun upsertAction(replyId: String, a: UiAction) = _state.update { st ->
        st.copy(turns = st.turns.map { t ->
            if (t.id != replyId) t
            else if (t.actions.any { it.id == a.id }) t.copy(actions = t.actions.map { if (it.id == a.id) a else it })
            else t.copy(actions = t.actions + a)
        })
    }

    // ------------------------------------------------------------------ ending a reply

    /** Stop button: stops the reply (or the model load) and the voice. */
    fun stopGenerating() {
        val id = reqId
        if (id != 0) client?.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
        confirms.values.forEach { it.complete(false) }
        // The turn ends as "stopped" (runTurn's cancellation handler); :brain rebuilds that conversation next time.
        job?.cancel()
    }

    fun stopSpeaking() {
        VoiceHub.tts.stop()
        _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
    }

    /** False once the conversation moved on (a new or loaded conversation replaced the one this reply belonged to). */
    private fun current(replyId: String) = _state.value.turns.any { it.id == replyId } && chat.none { it.id == replyId }

    private fun finishReply(replyId: String, reason: DoneReason) {
        if (!current(replyId)) return
        if (_state.value.speak) splitter.flush()?.let(::speakSentence)
        val stopped = reason == DoneReason.CANCELLED || reason == DoneReason.LENGTH || reason == DoneReason.GARBAGE
        val ui = _state.value.turns.firstOrNull { it.id == replyId }
        var text = ui?.text?.trim().orEmpty()
        if (reason == DoneReason.GARBAGE) text = (text.take(200) + "\n\n(The answer went wrong, so Robot stopped it.)").trim()
        val records = ui?.actions.orEmpty().mapNotNull { a -> a.tool?.let { ActionRecord(it, a.summary, a.status, a.detail) } }
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(), actions = records, stopped = stopped)
        _state.update { st ->
            st.copy(
                phase = if (VoiceHub.tts.isSpeaking) Phase.SPEAKING else Phase.IDLE,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = text, streaming = false, stopped = stopped) else it },
            )
        }
        lastActivity = System.currentTimeMillis()
        persist()
    }

    private fun failReply(replyId: String, message: String, action: NoticeAction = NoticeAction.NONE) {
        if (!current(replyId)) return
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, message, System.currentTimeMillis(), error = true)
        _state.update { st ->
            st.copy(
                phase = Phase.IDLE,
                notice = if (action != NoticeAction.NONE) Notice(message, action) else st.notice,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = message, streaming = false, error = true) else it },
            )
        }
        persist()
    }
}
