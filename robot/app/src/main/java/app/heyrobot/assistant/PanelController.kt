package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import app.heyrobot.claude.ClaudeListener
import app.heyrobot.claude.ClaudeSession
import app.heyrobot.claude.ClaudeTurn
import android.os.PowerManager
import android.os.SystemClock
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.AskClaudeTool
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.AskWhy
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainFail
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Card
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.ChatOnly
import app.heyrobot.core.ChatOnlyTurn
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClaudeAppAnswer
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeCommand
import app.heyrobot.core.ClaudeContext
import app.heyrobot.core.ClaudeCost
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudeLeftOut
import app.heyrobot.core.ClaudePayload
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeRouter
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.ClockEcho
import app.heyrobot.core.Cue
import app.heyrobot.core.DoneCode
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.Hedge
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Personal
import app.heyrobot.core.PreRouter
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.Shared
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnModel
import app.heyrobot.core.Executed
import app.heyrobot.core.FastPath
import app.heyrobot.core.FreeUp
import app.heyrobot.core.NoQuestion
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NoReply
import app.heyrobot.core.Prepared
import app.heyrobot.core.Prompt
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.PromptRole
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.RLog
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.ScreenData
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.ShowSource
import app.heyrobot.core.StallTimer
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TextCommand
import app.heyrobot.core.TextRequest
import app.heyrobot.core.Tip
import app.heyrobot.core.Tips
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRules
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnSlot
import app.heyrobot.core.TurnView
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VolumeKeyStop
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.toPromptTurns
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.ClaudeAppIntents
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.Phones
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.ui.common.keptPicture
import app.heyrobot.voice.Cues
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
    /** The Cancel button's own words ("Start fresh"). */
    val cancel: String? = null,
    /** An app the card offers to open (Cheogram when no contact matched), by package. */
    val handOff: String? = null,
    /** The picture a share card would send. */
    val picture: Bitmap? = null,
    /** The tick box next to the choices that remembers the one tapped ("Always use this for “mom”"). */
    val remember: String? = null,
    /** While it waits, an "Allow contacts" button too. */
    val allowContacts: Boolean = false,
    /** Its button sends personal content to Claude: a spoken or typed yes doesn't, unless "Voice yes can send personal content". */
    val personal: Boolean = false,
    /** Only a tap answers it, for this reason (core Card.tapFor: a learned name, not sure who, online the first time); "no" still declines. */
    val tapFor: AskWhy? = null,
) {
    /** What a spoken or typed answer needs to know about it (core CardAnswer). */
    val waiting: CardAnswer.Waiting get() = CardAnswer.Waiting(choices.size, tapFor, personal, confirm)
}

/**
 * "Want me to check with Claude?" under a reply (docs/ask-claude.md "When Robot needs help"): a tap (or a yes, when
 * nothing personal would go) asks Claude through the API ([api], listing what goes: [sends], with the start of the
 * screen's text in [preview]) or opens the Claude app with [text]. Nothing happens without it. [asWell]: it sits under
 * Robot's own answer, given after the local model first handed the question over ("Want Claude's take as well?").
 */
data class Offer(val api: Boolean, val text: String, val sends: List<String>, val preview: String? = null, val asWell: Boolean = false)

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

/**
 * OPEN_ASSIST_SETTINGS: the Digital assistant app page, where "Use text from screen" and "Use screenshot" are.
 * OPEN_MODEL (0.4.3): Robot's Settings › Model, to choose a model that can do actions.
 */
enum class NoticeAction { NONE, OPEN_APP, OPEN_ASSIST_SETTINGS, OPEN_MODEL }

data class Notice(val text: String, val action: NoticeAction = NoticeAction.NONE)

data class PanelState(
    val convId: String,
    val turns: List<UiTurn> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val notice: Notice? = null,
    val draft: Draft? = null,
    /** "Ask about this screen" (core ScreenAccess): hidden, or what a tap does. */
    val screenChip: ScreenChip = ScreenChip.HIDDEN,
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
    /** What works on the lock screen (Settings › Safety & privacy), for the panel's words. */
    val lockedTools: Set<ToolName> = LockScreen.DEFAULT,
    /** Listening for "yes" or "no" to the waiting card (LISTENING, then TRANSCRIBING); null: not. The phase stays CONFIRMING. */
    val cardHearing: Phase? = null,
    /** The empty panel's three tips (core Tips), a different three each time it opens. */
    val tips: List<Tip> = emptyList(),
) {
    /** A turn is under way, a confirmation card included: no new question, no mic, no camera until it ends. */
    val busy: Boolean get() = phase == Phase.THINKING || phase == Phase.ANSWERING || phase == Phase.CONFIRMING
    /** The screen's text or a screenshot came with this panel. */
    val screenAvailable: Boolean get() = screenChip == ScreenChip.READY
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
    /** The text of every window the corner swipe described, in order (split screen gives several). */
    private val assistPieces = ArrayList<String>()
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
    /** For the voice log: when the panel was shown, and whether the turn under way began as a spoken question. */
    private var shownAtE = 0L
    private var spokenTurn = false
    /** Robot asked what a text should say ("text mom"): the name, until the next question answers it. */
    private var askingTextFor: String? = null
    /** That question was asked aloud: listen for the answer once Robot has said it. */
    private var listenAfterSpeech = false
    /**
     * "Answer cards by voice" (0.4.0): listen for the waiting card's yes or no once Robot has stopped talking. [cardListen]
     * tells the callbacks of a listening that was stopped from the current one; [cardRetry] asks again once.
     */
    private var cardListenAfterSpeech = false
    private var cardListen = 0
    private var cardRetry = 0
    /** askToShare said its own words about the card: no "Say yes or no." on top. */
    private var cardPromptSaid = false
    /** Which three tips the next panel shows: counts panel opens, from a random start so a new process doesn't repeat itself. */
    private var tipRound = kotlin.random.Random.nextInt(0, 1_000)

    // Ask Claude (docs/ask-claude.md). Claude mode's conversation (API route), and when it last had a turn.
    private var claudeSession: ClaudeSession? = null
    private var claudeLast = 0L
    private var claudeIdle: Job? = null
    /** The screen's text when the corner swipe came from the Claude app (for "Read Claude's answer"); never stored. */
    private var claudePieces: List<String>? = null
    /** The question Robot last handed to the Claude app, to find Claude's answer after it. */
    private var lastToApp: String? = null
    /** The offer waiting under a reply, by reply id, with exactly what a tap would send (at most one at a time). */
    private val offers = HashMap<String, PendingOffer>()
    /**
     * The last photo (from the camera or the picker) and its thumbnail, kept for this panel session and conversation
     * only ("ask Claude about this photo"). Never a screenshot.
     */
    private var lastImage: ByteArray? = null
    private var lastThumb: Bitmap? = null
    /** The app on screen when the corner swipe came from a single app (its screenshot's label), by package. */
    private var screenPkg: String? = null
    /**
     * For "Ask about this screen" (core ScreenAccess): how this panel was opened, its show flags, and what Android sent
     * of the screen so far (null: nothing yet, false: it called with nothing). Cleared when the panel hides, not when
     * it shows: on a reused session Android sends its "nothing" before the show. Logged once per panel (SCREEN_SHARED).
     */
    private var shownFrom = ShowSource.APP
    private var showFlags = 0
    private var textCame: Boolean? = null
    private var shotCame: Boolean? = null
    /** "Use text from screen" and "Use screenshot", read at each show (null: Android wouldn't say). */
    private var screenSettings: Pair<Boolean?, Boolean?> = null to null
    private var screenWait: Job? = null
    private var screenLogged = true
    /** The notice a tap on the chip put up: taken down if the screen comes after all. */
    private var screenNotice: Notice? = null
    /** The day the "budget used up" sentence was said (once a day). */
    private var budgetSaid: String? = null
    /**
     * You said "not Claude" in this conversation (ClaudeRouter.declines): no ask_claude for the local model, no
     * pre-router, no automatic escalation and no offers, until a new conversation or an explicit "ask Claude …" or "go
     * get Claude". Shown nowhere and never saved.
     */
    private var claudeDeclined = false

    /**
     * What an offer's tap does, fixed when it was made: the API request's [payload], or the user's [question] for the
     * Claude app. It never picks up a screen or a picture that arrived later.
     */
    private class PendingOffer(val api: Boolean, val question: String, val payload: ClaudePayload?, val voiceAsked: Boolean, val asWell: Boolean = false)

    /** Your region for Claude's first message: the one set in Settings, else the phone's own (SIM, network, language). */
    private val userRegion get() = VoiceHub.settings.regionFor(Phones.deviceRegion(VoiceHub.app))

    private fun newId() = UUID.randomUUID().toString()

    private fun init() {
        if (initialized) return
        initialized = true
        scope.launch {
            // A new fallback (Robot is using Fast instead of the model you chose) shows once as a notice, with Open Robot.
            var seenFallback = 0
            BrainKeeper.state.collect { s ->
                val tell = s.fallback != null && s.fallbackSeq != seenFallback
                seenFallback = s.fallbackSeq
                _state.update { it.copy(brain = s, notice = if (tell) Notice(s.fallback!!, NoticeAction.OPEN_APP) else it.notice) }
            }
        }
    }

    /** From VoiceHub (which owns the voice's listener since 0.2.3): Robot's voice started or stopped. */
    fun onSpeakingChanged(speaking: Boolean) {
        // A sentence ends between two: listen only once Robot has really stopped talking.
        if (!speaking && cardListenAfterSpeech) VoiceHub.main.postDelayed({ if (cardListenAfterSpeech && !VoiceHub.tts.isSpeaking) listenForCard() }, 350)
        if (!speaking && listenAfterSpeech) {
            listenAfterSpeech = false
            // "What should the text to Mom say?" was said aloud: the answer comes by voice too.
            if (shown && askingTextFor != null) VoiceHub.main.post { if (shown && askingTextFor != null && _state.value.phase == Phase.IDLE) startListening() }
        }
        _state.update {
            when {
                !speaking && it.phase == Phase.SPEAKING -> it.copy(phase = Phase.IDLE)
                speaking && it.phase == Phase.IDLE -> it.copy(phase = Phase.SPEAKING)
                else -> it
            }
        }
    }

    fun attach(h: PanelHost) { host = h }
    fun detach(h: PanelHost) { if (host === h) host = null }

    // ------------------------------------------------------------------ show / hide

    /** [showFlags]: VoiceInteractionSession's, for the voice log. */
    fun onShow(args: Bundle?, locked: Boolean, showFlags: Int = 0) {
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
            assistPieces.clear()
            screenPkg = null
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
            it.copy(locked = locked, screenChip = ScreenChip.HIDDEN, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "",
                showId = it.showId + 1, claudeScreen = false, lockedTools = s.lockedTools, tips = if (returning) it.tips else tips(s, locked))
        }
        BrainKeeper.prewarm(VoiceHub.app)
        ClaudeLink.warm(VoiceHub.app, s)
        // The voice models load on first use (VoiceHub's memory policy): speech recognition when the mic or the wake
        // word starts a question, the voice when a question is sent with spoken replies on. Nothing loads just for a show.
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        shownAtE = SystemClock.elapsedRealtime()
        VoiceLog.add(VoiceEvent.SESSION_SHOWN, showSource(source))
        startScreen(showSource(source), showFlags)
        // The question first: once it is attached, the capture mode can't switch under it ("Always echo cancellation").
        if (source == RobotVoiceService.SRC_WAKE) startListening()
        VoiceHub.updateCapture()
    }

    private fun showSource(src: String?): ShowSource = when (src) {
        RobotVoiceService.SRC_WAKE -> ShowSource.WAKE
        RobotVoiceService.SRC_KEYGUARD -> ShowSource.KEYGUARD
        RobotVoiceService.SRC_APP -> ShowSource.APP
        RobotVoiceService.SRC_CAPTURE -> ShowSource.CAPTURE
        // The corner swipe (and the assist gesture) come without Robot's own source.
        else -> ShowSource.SWIPE
    }

    fun onHide() {
        shown = false
        val st0 = _state.value
        if (st0.phase == Phase.LISTENING) VoiceLog.add(VoiceEvent.NO_QUESTION, NoQuestion.CLOSED)
        if (st0.busy && spokenTurn && hidingFor == null) VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.STOPPED)
        VoiceLog.add(VoiceEvent.SESSION_HIDDEN, ((SystemClock.elapsedRealtime() - shownAtE) / 1000).toInt())
        stopCardListening()
        VoiceHub.stt.cancel()
        VoiceHub.listener?.wakePaused = false
        listenAfterSpeech = false
        if (hidingFor == null) askingTextFor = null
        val forCapture = hidingFor == "capture"
        // A turn never goes on behind a hidden panel: closing stops it, and a card nobody can tap any more is declined.
        if (hidingFor == null || slot.confirmations.waiting) stopGenerating(TtsStop.CLOSED)
        if (hidingFor == null) VoiceHub.stopVoice(TtsStop.CLOSED)
        VoiceHub.updateCapture()
        // Claude mode ends when the panel closes (not when it hides for the camera or an app Claude opened).
        if (hidingFor == null) endClaudeMode()
        // Screen content lives only while the panel is open, and so do offers.
        screenWait?.cancel()
        logScreen()
        textCame = null
        shotCame = null
        screenText = null
        screenshot = null
        claudePieces = null
        assistPieces.clear()
        screenPkg = null
        dropOffers()
        if (!forCapture) {
            draftJpeg = null
            lastImage = null
            _state.update { it.copy(draft = null) }
        }
        _state.update { it.copy(phase = if (it.busy) it.phase else Phase.IDLE, partial = "", screenChip = ScreenChip.HIDDEN, claudeScreen = false) }
        lastActivity = System.currentTimeMillis()
        // Whatever the reason (a camera left with Home must not keep Gemma loaded): showing the panel again cancels it.
        BrainKeeper.scheduleUnload(VoiceHub.settings.keepModelMinutes)
    }

    /**
     * AssistStructure text (corner swipe only). Over the Claude app ([pkg]) the panel also offers "Read Claude's answer":
     * your own swipe, never a background read of the app.
     */
    fun onAssistText(pieces: List<String>, pkg: String? = null, index: Int = 0, count: Int = 1) {
        if (ignoreAssist || !shown) return
        // One call per visible activity ([index] of [count]): the screen's text is all of them together.
        if (index == 0) {
            assistPieces.clear()
            claudePieces = null
            screenPkg = pkg.takeIf { count == 1 }
        }
        assistPieces += pieces
        screenText = ScreenText.build(assistPieces)
        // Only the Claude app's own window, and only when an answer can be found in it (a secure window gives no text).
        if (pkg == ClaudeAppLink.PACKAGE && ClaudeAppAnswer.extract(pieces, lastToApp) != null) claudePieces = pieces
        textCame = screenText != null
        _state.update { it.copy(claudeScreen = claudePieces != null) }
        refreshScreen()
    }

    /**
     * Android called without the screen's text: "Use text from screen" is off, or the screen is private. Noted even
     * before the panel shows (a reused session gets it ahead of the show), so the chip can say why at once.
     */
    fun onAssistNone() {
        if (textCame != true) textCame = false
        if (shown) refreshScreen()
    }

    /** Null: Android called without a picture. As with [onAssistNone], a "no" counts before the show; a picture doesn't. */
    fun onScreenshot(bmp: Bitmap?) {
        if (bmp == null) {
            if (shotCame != true) shotCame = false
        } else {
            if (ignoreAssist || !shown) return
            screenshot = bmp
            shotCame = true
        }
        if (shown) refreshScreen()
    }

    /** A new panel: nothing of the screen yet; the chip explains if it hasn't come in [ScreenAccess.WAIT_MS]. */
    private fun startScreen(source: ShowSource, flags: Int) {
        shownFrom = source
        showFlags = flags
        screenSettings = Assistant.screenSharing(VoiceHub.app)
        screenLogged = ignoreAssist
        screenWait?.cancel()
        screenWait = scope.launch {
            delay(ScreenAccess.WAIT_MS)
            refreshScreen()
            logScreen()
        }
        refreshScreen()
    }

    private fun refreshScreen() {
        val chip = ScreenAccess.chip(shownFrom, _state.value.locked, textCame, shotCame, SystemClock.elapsedRealtime() - shownAtE, screenSettings.first)
        _state.update { st ->
            // The screen came after a tap said it hadn't ("still sending", "didn't share"): that notice no longer holds.
            val stale = chip == ScreenChip.READY && st.notice != null && st.notice === screenNotice
            st.copy(screenChip = chip, notice = if (stale) null else st.notice, tips = if (chip == ScreenChip.READY) Tips.withScreen(st.tips) else st.tips)
        }
    }

    /** The next three tips for an empty panel: only what is on and can work now (core Tips). */
    private fun tips(s: RobotSettings, locked: Boolean): List<Tip> {
        val keySaved = ClaudeLink.keySaved(VoiceHub.app)
        val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
        val claude = ClaudeSettings.enabled(s, keySaved) && (if (locked) api && s.claudeLocked else api || ClaudeAppIntents.installed(VoiceHub.app))
        return Tips.pick(Tips.Can(s, locked, claude = claude), tipRound++)
    }

    /** A tip that asks about the screen: the screen goes with it (or the panel says why it can't). */
    fun askAboutScreen(question: String) {
        attachScreen()
        if (_state.value.draft?.kind == Attachment.SCREEN) send(question)
    }

    /**
     * Once per panel: its show flags and whether the screen's text and a screenshot came (codes only, never content).
     * Written 2 s after the panel opened, or when it closes if that is sooner, so it says what a tap on the chip
     * would have found by then; a screen that comes later changes the chip but isn't logged again.
     */
    private fun logScreen() {
        if (screenLogged) return
        screenLogged = true
        VoiceLog.add(VoiceEvent.SCREEN_SHARED, showFlags, ScreenData.of(textCame).id, ScreenData.of(shotCame).id)
    }

    // ------------------------------------------------------------------ conversation

    fun newConversation(locked: Boolean = _state.value.locked) {
        stopGenerating(TtsStop.OTHER)
        VoiceHub.stopVoice(TtsStop.OTHER)
        askingTextFor = null
        endClaudeMode()
        offers.clear()
        claudeDeclined = false
        // A photo from the last conversation never goes with this one.
        lastImage = null
        lastThumb = null
        chat.clear()
        groups = setOf(ToolGroup.CORE)
        closeConvAfterTurn = false
        startedLocked = locked
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update {
            PanelState(newId(), locked = locked, speak = it.speak, sttAvailable = it.sttAvailable, micHint = it.micHint, screenChip = it.screenChip,
                showId = it.showId, brain = it.brain, lockedTools = it.lockedTools, tips = it.tips)
        }
    }

    private fun loadConversation(id: String) {
        val c = ConversationStore.load(VoiceHub.app, id) ?: return newConversation(false)
        stopGenerating()
        endClaudeMode()
        offers.clear()
        claudeDeclined = false
        lastImage = null
        lastThumb = null
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
                showId = it.showId, brain = it.brain, lockedTools = it.lockedTools, tips = it.tips)
        }
    }

    private fun ChatTurn.toUi() = UiTurn(id, role, text, attachment, keptPicture(picture),
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail, content = it.content) }, stopped = stopped, error = error,
        claude = claude, readBack = readBack, sources = sources, cost = costMicros.takeIf { it > 0 && VoiceHub.settings.claudeShowCost }?.let { ClaudeCost.line(it / 1e6) })

    /**
     * "Remove saved screenshots and photos" (the main app): the conversation still held here loses its kept thumbnails
     * and screen text too, and is saved again without them (its file may have been written back meanwhile).
     */
    fun forgetKeptMedia() {
        var changed = false
        for (i in chat.indices) {
            val t = chat[i]
            if (t.picture != null || t.screenText != null) {
                chat[i] = t.copy(picture = null, screenText = null)
                changed = true
            }
        }
        if (changed) persist()
    }

    /**
     * History deleted the conversation still held here ([id]), or every conversation (null): start a new one, so the next
     * save after a reply can't write the deleted one back.
     */
    fun forgetDeleted(id: String?) {
        if (id == null || id == _state.value.convId) newConversation()
    }

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
        if (!on) VoiceHub.stopVoice(TtsStop.MUTED)
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
        stopGenerating(TtsStop.OTHER)
        VoiceHub.stopVoice(TtsStop.OTHER)
        if (h.launch(i)) {
            hidingFor = "action"
            h.hidePanel()
        }
    }

    fun notify(text: String) = _state.update { it.copy(notice = Notice(text)) }

    /** "Open Model" under a chat-only model's answer to an action: Settings › Model, where you choose (0.4.3). */
    fun openModelSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open the Robot app › Model.")
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.MODEL))
    }

    /** "Allow" on a card that needs a permission: the main app's permission screen (it asks; the panel never does). */
    fun openPermissions() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open the Robot app.")
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.PERMISSIONS))
    }

    // ------------------------------------------------------------------ attachments

    /** "Ask about this screen": the screen goes with the next question, or the panel says why it can't. */
    fun attachScreen() {
        when (_state.value.screenChip) {
            ScreenChip.READY -> _state.update { it.copy(draft = Draft(Attachment.SCREEN, screenshot?.let { b -> Images.scaled(b, Images.THUMB_EDGE) })) }
            ScreenChip.WAITING -> screenHint(ScreenAccess.WAITING_HINT)
            ScreenChip.NOT_SHARED -> screenHint(ScreenAccess.notSharedHint(screenSettings.first, screenSettings.second))
            ScreenChip.SWIPE_ONLY -> screenHint(ScreenAccess.swipeHint(screenSettings.first))
            ScreenChip.HIDDEN -> {}
        }
    }

    private fun screenHint(h: ScreenAccess.Hint) {
        val n = Notice(h.text, if (h.openSettings) NoticeAction.OPEN_ASSIST_SETTINGS else NoticeAction.NONE)
        screenNotice = n
        _state.update { it.copy(notice = n) }
    }

    /** "Open settings" under that notice: Settings › Apps › Default apps › Digital assistant app (voice input settings first). */
    fun openAssistSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open Settings.")
        val h = host ?: return
        val i = Assistant.settingsIntent(VoiceHub.app) ?: return notify("Settings didn't open. Go to Settings › Apps › Default apps › Digital assistant app.")
        // A pending "what should the text say?" ends here: the next question after Settings must not become that text.
        askingTextFor = null
        listenAfterSpeech = false
        // As for Robot's own app: nothing goes on behind Settings.
        leaveForRobot(h, i)
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
        VoiceHub.stopVoice(TtsStop.OTHER)
        hidingFor = "capture"
        if (h.startCapture(CaptureActivity.intent(VoiceHub.app, mode))) {
            h.hidePanel()
        } else {
            // The panel stays, so the reason can be read.
            hidingFor = null
            notify("Couldn't open the camera or the photo picker.")
        }
    }

    /**
     * From MediaDeleteActivity (same process, 0.4.0): Android's confirmation for a picked file answered. The card that
     * waited for it (and the saved conversation) says whether it was deleted.
     */
    fun mediaDeleteAnswered(deleted: Boolean) {
        fun settle(tool: ToolName?, detail: String?) = tool == ToolName.FREE_STORAGE && detail == FreeUp.DELETE_WAITING
        val status = if (deleted) ActionStatus.DONE else ActionStatus.DECLINED
        val detail = if (deleted) FreeUp.DELETED else FreeUp.KEPT
        _state.update { st ->
            st.copy(turns = st.turns.map { t -> t.copy(actions = t.actions.map { if (settle(it.tool, it.detail)) it.copy(status = status, detail = detail) else it }) })
        }
        chat.replaceAll { t -> t.copy(actions = t.actions.map { if (settle(it.tool, it.detail)) it.copy(status = status, detail = detail) else it }) }
        persist()
    }

    /** From CaptureActivity (same process): the picture to send with the next question. */
    fun onImagePicked(jpeg: ByteArray, thumb: Bitmap) {
        draftJpeg = jpeg
        _state.update { it.copy(draft = Draft(Attachment.PHOTO, thumb), notice = null, focusRequest = it.focusRequest + 1) }
    }

    // ------------------------------------------------------------------ voice input

    fun micPressed() {
        // A card waits: the mic answers it ("yes", "no"), with "Answer cards by voice" on (off: a tap or a typed answer).
        if (_state.value.phase == Phase.CONFIRMING) {
            if (!VoiceHub.settings.voiceCards) return notify(CARD_TYPE_HINT)
            VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
            cardRetry = 0
            return listenForCard()
        }
        if (_state.value.busy || slot.running) return
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    fun micReleased() = VoiceHub.stt.stop()

    fun onWakeWhileShown() {
        VoiceHub.stopVoice(TtsStop.WAKE)
        // "Hey robot" during an answer works like Stop (a waiting card is declined); the new question is taken once
        // that turn has ended (sendSpoken).
        if (_state.value.busy || slot.running) stopGenerating(TtsStop.WAKE)
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    private fun startListening() {
        VoiceHub.listener?.wakePaused = true
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", notice = null, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint) }
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
                    // No words came back: say so rather than going quiet.
                    if (text.isNotBlank()) sendSpoken(text) else missedQuestion(NoQuestion.EMPTY)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    endListening()
                    VoiceLog.add(VoiceEvent.NO_QUESTION, when (reason) {
                        SpeechToText.Reason.NO_SPEECH -> NoQuestion.NO_SPEECH
                        SpeechToText.Reason.UNAVAILABLE -> NoQuestion.UNAVAILABLE
                        else -> NoQuestion.ERROR
                    })
                    // The notice says why; the chime says so to someone across the room.
                    cue(Cue.CHIME)
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
        // After the question's model load is queued, so the voice's load (NativeLoads, one at a time) doesn't go first.
        if (_state.value.speak) VoiceHub.tts.prewarm()
    }

    /** A spoken question came back without words: say so, in the panel and aloud (or with a chime). */
    private fun missedQuestion(why: NoQuestion) {
        VoiceLog.add(VoiceEvent.NO_QUESTION, why)
        notify(SORRY)
        cue(Cue.SORRY)
    }

    /** "Sorry, I didn't catch that." spoken when replies are spoken, otherwise a short chime. */
    private fun cue(c: Cue) {
        if (c != Cue.CHIME && _state.value.speak) {
            VoiceLog.add(VoiceEvent.CUE, c)
            VoiceHub.tts.speak(if (c == Cue.SORRY_NO_ANSWER) SORRY_NO_ANSWER else SORRY)
        } else {
            VoiceLog.add(VoiceEvent.CUE, Cue.CHIME)
            Cues.missed()
        }
    }

    private fun endListening() {
        VoiceHub.listener?.wakePaused = false
        _state.update { it.copy(phase = if (it.phase == Phase.LISTENING || it.phase == Phase.TRANSCRIBING) Phase.IDLE else it.phase, partial = "") }
    }

    // ------------------------------------------------------------------ answering a card by voice (0.4.0)

    /** The card waiting for an answer (the newest one still pending), or null. */
    private fun pendingCard(): UiAction? = _state.value.turns.asReversed().firstNotNullOfOrNull { t -> t.actions.lastOrNull { it.status == ActionStatus.PENDING } }

    /**
     * A card now waits. With "Answer cards by voice" on, when replies are spoken or you are talking to Robot (a spoken
     * question, a wake-word panel), Robot says how to answer and listens for "yes" or "no" once it has stopped talking.
     */
    private fun cardWaiting() {
        val st = _state.value
        val s = VoiceHub.settings
        val said = cardPromptSaid
        cardPromptSaid = false
        cardRetry = 0
        if (!s.voiceCards || !shown || !VoiceHub.stt.available || !(st.speak || spokenTurn || shownFrom == ShowSource.WAKE)) return
        val card = pendingCard() ?: return
        if (st.speak) {
            // "Say yes or no.", or why it needs a tap (a learned name, not sure who, online the first time) and that "no" works.
            if (!said) VoiceHub.tts.speak(CardAnswer.prompt(card.waiting))
            cardListenAfterSpeech = true
        } else {
            listenForCard()
        }
    }

    /** Listens for the waiting card's answer; the phase stays CONFIRMING (the turn is still waiting on the card). */
    private fun listenForCard() {
        cardListenAfterSpeech = false
        val st = _state.value
        if (st.phase != Phase.CONFIRMING || st.cardHearing != null || !shown || pendingCard() == null) return
        if (!VoiceHub.stt.available) return notify(VoiceHub.stt.hint ?: "Voice input isn't available. Tap a button on the card.")
        val token = ++cardListen
        VoiceHub.listener?.wakePaused = true
        _state.update { it.copy(cardHearing = Phase.LISTENING, partial = "", notice = null) }
        val main = VoiceHub.main
        VoiceHub.stt.start(object : SpeechToText.Listener {
            fun mine() = token == cardListen
            override fun onPartial(text: String) { main.post { if (mine()) _state.update { if (it.cardHearing == Phase.LISTENING) it.copy(partial = text) else it } } }
            override fun onEndOfSpeech() {
                main.post {
                    if (!mine()) return@post
                    VoiceHub.listener?.wakePaused = false
                    _state.update { if (it.cardHearing != null) it.copy(cardHearing = Phase.TRANSCRIBING) else it }
                }
            }
            override fun onFinal(text: String) {
                main.post {
                    if (!mine()) return@post
                    endCardListening()
                    heardForCard(text)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    if (!mine()) return@post
                    endCardListening()
                    notify(if (reason == SpeechToText.Reason.NO_SPEECH) CARD_HINT else message)
                }
            }
        })
    }

    private fun endCardListening() {
        VoiceHub.listener?.wakePaused = false
        _state.update { it.copy(cardHearing = null, partial = "") }
    }

    /** The card was answered or the turn stopped: no more listening for it. */
    private fun stopCardListening() {
        cardListenAfterSpeech = false
        if (_state.value.cardHearing == null) return
        cardListen++
        VoiceHub.stt.cancel()
        endCardListening()
    }

    /** What was said while the card waited: its answer, or (once) a reminder and another listen. */
    private fun heardForCard(text: String) {
        if (text.isNotBlank() && answerCard(text, spoken = true)) return
        if (pendingCard() == null) return
        if (cardRetry++ < 1) {
            notify(CARD_HINT)
            if (_state.value.speak) {
                VoiceHub.tts.speak(pendingCard()?.waiting?.let(CardAnswer::prompt) ?: CardAnswer.PROMPT)
                cardListenAfterSpeech = true
            } else {
                listenForCard()
            }
        } else {
            notify(CARD_HINT)
            cue(Cue.CHIME)
        }
    }

    /**
     * [text] answers the waiting card (core CardAnswer.reply, tested): "no" declines it; "yes" confirms it, except a card
     * that needs a tap (a learned name, not sure who or which, online the first time), one with several choices, or one
     * that sends personal content to Claude (unless "Voice yes can send personal content"): Robot says why. False: not a
     * yes or no, no card waits, or it was [spoken] with "Answer cards by voice" off (a typed answer still works then).
     */
    private fun answerCard(text: String, spoken: Boolean): Boolean {
        val card = pendingCard() ?: return false
        val s = VoiceHub.settings
        val r = CardAnswer.reply(text, card.waiting, s.voiceCards, s.voiceYesPersonal, spoken, s.wakePhrases.filter { it.enabled }.map { it.text })
            ?: return false
        RLog.i("card_answer", "yes" to (r.does != CardAnswer.Does.DECLINE), "spoken" to spoken)
        when (r.does) {
            CardAnswer.Does.DECLINE -> confirm(card.id, false)
            CardAnswer.Does.CONFIRM -> confirm(card.id, true)
            CardAnswer.Does.CHOOSE_ONLY -> choose(card.id, 0)
            CardAnswer.Does.TAP -> r.say?.let { words ->
                notify(words)
                if (_state.value.speak) VoiceHub.tts.speak(words)
            }
        }
        return true
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
        // A card waits: a typed or spoken "yes" or "no" answers it (Settings › Safety & privacy).
        if (st.phase == Phase.CONFIRMING && st.draft == null && answerCard(q, fromVoice)) return true
        if (st.busy || slot.running) {
            if (st.phase == Phase.CONFIRMING) {
                notify(if (fromVoice && !VoiceHub.settings.voiceCards) CARD_TYPE_HINT else CARD_HINT)
                if (fromVoice) cue(Cue.CHIME)
            } else if (fromVoice) {
                notify("Robot is still on the last question. Ask again when it's done.")
                VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.BUSY)
                cue(Cue.CHIME)
            }
            return false
        }
        // An offer under the last reply waits for a tap or a short yes (a yes never sends anything personal).
        if (st.draft == null && ClaudeRouter.isYes(q)) offers.keys.lastOrNull { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.let {
            acceptOffer(it, tapped = false)
            return true
        }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        spokenTurn = fromVoice
        turnSaid = q
        val textingFor = askingTextFor
        askingTextFor = null
        expireClaudeMode()
        // Ask Claude's phrases first, then texting without the model, then the model (FastPath).
        var cmd = if (q.isNotEmpty()) ClaudeRouter.route(q) else null
        // "Thanks Claude" outside Claude mode is ordinary talk.
        if (cmd == ClaudeCommand.Leave && !_state.value.claudeMode) cmd = null
        // "Not Claude", "you tell me": Robot answers, now and for the rest of the conversation (Claude mode ends too).
        // Only an explicit "ask Claude …" or "go get Claude" brings Claude back.
        if (q.isNotEmpty() && ClaudeRouter.declines(q)) {
            if (cmd is ClaudeCommand.Ask || cmd is ClaudeCommand.Join) cmd = null
            if (!claudeDeclined) RLog.i("claude_declined", "claudeMode" to _state.value.claudeMode)
            claudeDeclined = true
            endClaudeMode()
        } else if (cmd is ClaudeCommand.Ask || cmd is ClaudeCommand.Join) {
            claudeDeclined = false
        }
        if (cmd != null) RLog.i("claude_command", "kind" to RLog.Code(cmd.javaClass.simpleName))
        dropOffers()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> screenshot?.let { Images.jpeg(it) }
            Attachment.NONE -> null
        }
        // Only a photo: "ask Claude about this photo" must never pick up a screenshot.
        if (attachment == Attachment.PHOTO && image != null) {
            lastImage = image
            lastThumb = st.draft?.thumb
        }
        val thumb = st.draft?.thumb
        val sText = if (attachment == Attachment.SCREEN) screenText else null
        val history = chat.toList()
        val now = System.currentTimeMillis()
        // "Ask Claude about my screen" said without attaching it is still a screen question: the conversation is
        // personal from here on, and it is saved only with Keep.
        val marked = if (attachment != Attachment.NONE) attachment else (cmd as? ClaudeCommand.Ask)?.extra ?: Attachment.NONE
        // "Keep screenshots and photos in history" (off by default): the thumbnail and the screen's text go into the saved
        // conversation, for History only (never replayed to the model, never sent to Claude).
        val kept = VoiceHub.settings.let { it.keepMedia && it.saveHistory } && attachment != Attachment.NONE
        val user = ChatTurn(newId(), TurnRole.USER, q.ifEmpty { Prompt.defaultQuestion(attachment) }, now, marked,
            picture = if (kept) st.draft?.thumb?.let { b -> java.util.Base64.getEncoder().encodeToString(Images.jpeg(b, Images.THUMB_EDGE, 70)) } else null,
            screenText = if (kept) sText?.let(ScreenText::sanitize)?.takeIf { it.isNotBlank() }?.let { ToolCalls.safeTake(it, ConversationCodec.MAX_SCREEN_TEXT) } else null)
        chat += user
        val way = FastPath.of(cmd, _state.value.claudeMode, attachment)
        val toClaude = way == FastPath.Way.CLAUDE
        // Groups are only ever added: a question that needs a new one rebuilds the conversation once (:brain).
        if (cmd == null && !toClaude) groups = groups + ToolRouter.route(q)
        val replyId = newId()
        // Decided per turn: a screen turn stops saving until the person taps Keep.
        val keep = if (marked == Attachment.SCREEN && !keptByUser) false else st.keep
        // Screen text, a picture, a read tool's findings or Claude's words anywhere in this conversation: every action asks first.
        val untrusted = TurnRules.untrusted(chat)
        val api = toClaude && ClaudeSettings.route(VoiceHub.settings, ClaudeLink.keySaved(VoiceHub.app)) == ClaudeRoute.API
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, marked, st.draft?.thumb) +
                    UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true, claude = api && !(cmd is ClaudeCommand.Join && cmd.question == null)),
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
                    readBack()
                } else {
                    localReply(replyId, ClaudeWords.SWIPE_TO_READ)
                }
                way == FastPath.Way.CLAUDE -> claudeTurn(replyId, cmd, user.text, attachment, sText, image, thumb, history, fromVoice)
                // A plain request to text someone (or the answer to "what should it say?") goes straight to send_text.
                way == FastPath.Way.TEXT && directText(replyId, q, textingFor, fromVoice, untrusted) -> {}
                else -> runTurn(replyId, user.text, attachment, sText, image, history, untrusted, guard, fromVoice)
            }
        }
        return true
    }

    /**
     * Runs a turn in the slot; a stop finishes every reply still streaming as stopped, a crash fails it in words. A model
     * unload free_memory asked for is done before the turn completes (0.4.0), so the next question can't start meanwhile.
     */
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
        } finally {
            withContext(NonCancellable) { unloadIfAsked(replyId) }
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
        // Not after "not Claude": that conversation stays with Robot. With the weather tool on, a weather question is
        // Robot's to answer (0.4.0). Locked, the tool can't run: as before, the question may go to Claude when Claude is
        // allowed on the lock screen.
        val localWeather = s.approval(ToolName.WEATHER) != Approval.OFF && (!Assistant.locked(VoiceHub.app) || ToolName.WEATHER in s.lockedTools)
        if (attachment == Attachment.NONE && !claudeDeclined) PreRouter.signal(question, localWeather)?.let { signal ->
            RLog.i("claude_trigger", "kind" to EscalationTrigger.PRE_ROUTER, "signal" to signal)
            when (decide(attachment, guardFree = !guard.used)) {
                EscalationDecision.AUTO -> {
                    guard.take(fromClaude = false)
                    val blocks = ClaudeContext.escalation(question, history, ZonedDateTime.now(), userRegion, personal = personalNow(null),
                        includePersonal = s.personalToHelpers)
                    return escalateNow(replyId, question, blocks, voiceAsked, reuseBubble = true)
                }
                EscalationDecision.OFFER -> offerAfter = EscalationTrigger.PRE_ROUTER
                else -> {}
            }
        }
        val c = BrainKeeper.ensureLoaded(VoiceHub.app)
        if (c == null) {
            val b = BrainKeeper.state.value
            VoiceLog.add(VoiceEvent.BRAIN_FAILED, if (b.readiness == BrainKeeper.Readiness.NO_MODEL) BrainFail.NO_MODEL else BrainFail.LOAD_FAILED)
            return failReply(replyId, b.problem ?: "Robot's model isn't ready.", if (b.readiness == BrainKeeper.Readiness.NO_MODEL) NoticeAction.OPEN_APP else NoticeAction.NONE)
        }
        client = c
        val loaded = BrainKeeper.state.value
        // 0.4.3, a short-context model (chat only): a request clearly worded as an action gets Robot's own answer, and an
        // offer to open Settings › Model, never a switch made for you. (Texting and Ask Claude's phrases went their own way
        // before the model was ever asked, as always.)
        val chatOnly = loaded.chatOnly
        if (chatOnly) ChatOnly.actionAsked(question)?.let { asked ->
            RLog.i("chat_only_action", "tool" to RLog.Code(asked.id))
            localReply(replyId, ChatOnly.REPLY, spoken = ChatOnly.SPOKEN)
            _state.update { it.copy(notice = Notice(ChatOnly.NOTICE, NoticeAction.OPEN_MODEL)) }
            return
        }
        // The model loaded without its vision encoder: a screen question still has the screen's text.
        val vision = loaded.vision
        // A model that reads text only (your own GGUF file) says so, for a photo and for the screen.
        val textOnly = loaded.textOnly
        if (image != null && !vision && attachment == Attachment.PHOTO) {
            return failReply(replyId, when {
                textOnly -> TEXT_ONLY_PHOTO
                loaded.fileVision -> BrainError.NO_VISION.text()
                else -> NO_VISION_IN_FILE
            })
        }
        if (textOnly && attachment == Attachment.SCREEN) notify(TEXT_ONLY_SCREEN)
        val img = if (vision) image else null
        val convId = _state.value.convId
        val now = ZonedDateTime.now()
        // ask_claude is described (with its one line in the system prompt) only with Ask Claude on, escalation not Off,
        // somewhere for the question to go (the API with a key, or the Claude app installed), no "not Claude" in this
        // conversation, and no screen or photo with the question or before it in the conversation: Robot answers those
        // itself first, and a follow-up keeps the live conversation that still holds the screen (AskClaudeTool).
        val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
        val reachable = ClaudeSettings.localTool(s, keySaved) && (api || withContext(Dispatchers.IO) { ClaudeAppIntents.installed(VoiceHub.app) })
        val leftOut = AskClaudeTool.leftOut(s, keySaved, reachable, claudeDeclined, AskClaudeTool.picture(attachment, history))
        if (leftOut != null && leftOut != ClaudeLeftOut.OFF && leftOut != ClaudeLeftOut.NOWHERE) {
            RLog.i("claude_left_out", "why" to leftOut)
            VoiceLog.add(VoiceEvent.CLAUDE_LEFT_OUT, leftOut)
        }
        var cutSaid = false
        // What you asked for in your own words this turn (never the screen's text or a photo): Safety cards' "asked for".
        val askedNow = ToolRouter.asked(question)
        // The loaded model's context: every budget below is made from it (4,096, or a short-context file's own).
        val maxTokens = loaded.contextTokens
        // One run of the model on this question into the reply, with or without ask_claude (the second one, without it,
        // is Robot's own answer after the model handed over: afterLocal).
        suspend fun gemma(askClaude: Boolean): TurnResult {
            // Only the groups this conversation needs, and nothing turned off in settings, is described to the model; to a
            // chat-only model nothing at all, with its compact prompt (Settings › "Direct answers" as you set it).
            val tools = if (chatOnly) emptyList() else ToolRouter.tools(groups, s, askClaude)
            val system = if (chatOnly) Prompt.chatSystem(now, direct = s.directAnswers)
            else Prompt.system(now, Assistant.locked(VoiceHub.app), askClaude, texting = ToolName.SEND_TEXT in tools,
                weather = ToolName.WEATHER in tools, phone = ToolName.PHONE_STATUS in tools, direct = s.directAnswers, lockedTools = s.lockedTools)
            // The time only for a question that needs it, after the question: the model copied a leading time into its replies.
            val clock = if (Prompt.needsClock(question, groups)) Prompt.clock(now) else ""
            // The new turn always fits the model's context: cut (and say so) rather than overflow.
            val fitted = Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools), clock, maxTokens)
            if (fitted.shortened && !cutSaid) {
                cutSaid = true
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
                // Settings › Model › "Let the model think first" (0.4.5): :brain tells a model that can think whether it may.
                think = s.modelThinks,
            )
            // About what the context has left for a tool's answer beside the reply (as :brain will fit the history): the
            // weather gives fewer days when it is short.
            val fixedTokens = Prompt.fixedTokens(system, tools)
            val budget = ContextWindow.historyBudget(fixedTokens, TokenEstimate.turn(PromptTurn(PromptRole.USER, fitted.text, img != null)), maxTokens)
            toolRoom = budget - ContextWindow.fit(gen.history, budget).sumOf(TokenEstimate::turn) - TOOL_CALL_TOKENS
            // Chat only: whatever the model emits, no call reaches the approval gate (core ChatOnlyTurn; :brain drops them too).
            val model = BrainTurn(c, convId, replyId, gen, img).let { if (chatOnly) ChatOnlyTurn(it) else it }
            return TurnRunner(model, PanelActions, PanelView(replyId), slot.confirmations, untrusted,
                asked = askedNow, newId = ::newId).run()
        }
        // ask_claude is a tool: a chat-only model gets none (the escalation triggers outside the model still apply).
        val result = gemma(askClaude = leftOut == null && !chatOnly)
        result.failure?.let { return failReply(replyId, it) }
        // Escalation trigger b: Gemma called ask_claude. Its turn ended there; Claude answers (or is offered).
        if (result.escalate) {
            RLog.i("claude_trigger", "kind" to EscalationTrigger.LOCAL_TOOL)
            return afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.LOCAL_TOOL,
                ownAnswer = { gemma(askClaude = false) })
        }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) return leaveForApp()
        // Escalation trigger c: the reply hedged ("I don't know", "I can't browse", nothing at all).
        val reply = chat.lastOrNull { it.id == replyId }
        if (result.reason == DoneReason.END && reply != null && reply.actions.isEmpty() && Hedge.detect(reply.text)) {
            RLog.i("claude_trigger", "kind" to EscalationTrigger.HEDGE)
            return afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.HEDGE)
        }
        offerAfter?.let { afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, it, offerOnly = true) }
    }

    /**
     * The escalation policy for this question now ([attachment]: its screen or photo, which only a tap may send, unless
     * "Send personal content to helpers automatically" is on).
     */
    private suspend fun decide(attachment: Attachment, guardFree: Boolean): EscalationDecision {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        return EscalationPolicy.decide(s, ClaudeLink.keySaved(app), attachment != Attachment.NONE && !s.personalToHelpers, Assistant.locked(app),
            ClaudeLink.budgetReached(app, s), !guardFree, declined = claudeDeclined)
    }

    /** The personal content in the conversation now, the reply [replyId] included while it is still on screen only. */
    private fun personalNow(replyId: String?): Set<Personal> {
        val ui = replyId?.takeIf { id -> chat.none { it.id == id } }?.let { id -> _state.value.turns.firstOrNull { it.id == id } }
        val reply = ui?.let { listOf(ChatTurn(it.id, TurnRole.ASSISTANT, it.text, 0, actions = records(it.actions))) }.orEmpty()
        return ClaudeContext.personal(chat + reply)
    }

    /**
     * Robot's reply [replyId] is in (or Gemma handed over with ask_claude): bring Claude in now, offer it, or say why not.
     * At most one escalation per turn ([guard]); [offerOnly]: the pre-router's offer, after Gemma's answer. [screen] and
     * [image]: this question's screen text and picture, which only an offer's tap may send. [ownAnswer]: the model once
     * more on the same question without ask_claude, for when it handed over without a word and Claude isn't asked right
     * away (EscalationPolicy.ownAnswerFirst): its answer comes first and any offer goes under it.
     */
    private suspend fun afterLocal(replyId: String, question: String, history: List<ChatTurn>, attachment: Attachment, screen: String?, image: ByteArray?,
        voiceAsked: Boolean, guard: EscalationGuard, trigger: EscalationTrigger, offerOnly: Boolean = false, ownAnswer: (suspend () -> TurnResult)? = null) {
        // A turn that already escalated decides NONE here.
        val decision = decide(attachment, guardFree = !guard.used)
        guard.take(fromClaude = false)
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val keySaved = ClaudeLink.keySaved(app)
        // Never only "I'd rather check that with Claude.": the model's own answer first, in the same reply (and turn).
        var ranOwn = false
        var ownFailure: String? = null
        var asWell = false
        if (ownAnswer != null && EscalationPolicy.ownAnswerFirst(decision, trigger == EscalationTrigger.LOCAL_TOOL, said = !saidNothing(replyId))) {
            ranOwn = true
            val r = ownAnswer()
            ownFailure = r.failure
            // Stopped meanwhile: the turn ends as stopped, with no offer.
            currentCoroutineContext().ensureActive()
            val answered = !saidNothing(replyId)
            VoiceLog.add(VoiceEvent.OWN_ANSWER, if (answered) 1 else 0)
            RLog.i("claude_own_answer", "answered" to answered, "decision" to decision, "failed" to (r.failure != null), "again" to r.escalate)
            if (r.launchedApp) {
                finishReply(replyId, DoneReason.END)
                return leaveForApp()
            }
            if (r.reason == DoneReason.CANCELLED) return finishReply(replyId, DoneReason.CANCELLED)
            if (answered) {
                finishReply(replyId, r.reason ?: DoneReason.END)
                asWell = true
            }
        }
        // The budget is used up for today: Automatic asks first, and Robot says so once a day.
        if (decision == EscalationDecision.OFFER && keySaved && ClaudeSettings.escalation(s, keySaved) == Escalation.AUTO && ClaudeLink.budgetReached(app, s)) {
            val day = LocalDate.now().toString()
            if (budgetSaid != day) {
                budgetSaid = day
                notify(ClaudeWords.BUDGET)
                if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.BUDGET)
            }
        }
        // A personal conversation (this reply's read included): Robot sends only the question.
        val personal = personalNow(replyId)
        when (decision) {
            EscalationDecision.AUTO -> if (offerOnly) finishOrDrop(replyId) else {
                // A screen or photo question gets here only with personal content allowed to go by itself: it goes along.
                val shared = if (attachment != Attachment.NONE && s.personalToHelpers) Shared(screenText = screen, image = image) else Shared()
                val blocks = ClaudeContext.escalation(question, history, ZonedDateTime.now(), userRegion, shared, personal = personal,
                    includePersonal = s.personalToHelpers)
                finishOrDrop(replyId)
                escalateNow(null, question, blocks, voiceAsked, reuseBubble = false)
            }
            EscalationDecision.OFFER -> {
                val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
                // No Claude app to open: nothing to offer.
                if (!api && !withContext(Dispatchers.IO) { ClaudeAppIntents.installed(app) }) {
                    if (saidNothing(replyId)) sayInto(replyId, "I'm not sure about that one.")
                    if (ranOwn) finishReply(replyId, DoneReason.END) else finishOrDrop(replyId)
                    return RLog.i("claude_escalation", "trigger" to trigger, "decision" to EscalationDecision.NONE)
                }
                if (chat.none { it.id == replyId }) {
                    // Gemma said nothing before asking for Claude, not even when asked again: the offer stands on its own.
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) sayInto(replyId, if (api) ClaudeWords.RATHER else "Claude could help with that.")
                    finishReply(replyId, DoneReason.END)
                }
                // What the tap sends is fixed now: this question's screen or picture, never one that arrives later.
                val payload = if (!api) null else ClaudeContext.payload(question, history, ZonedDateTime.now(), userRegion, personal, attachment,
                    Shared(screenText = screen, image = image), includePersonal = s.personalToHelpers)
                offer(replyId, PendingOffer(api, question, payload, voiceAsked, asWell))
            }
            EscalationDecision.NEEDS_UNLOCK -> {
                if (chat.none { it.id == replyId }) {
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) sayInto(replyId, "Unlock your phone to check with Claude.")
                    finishReply(replyId, DoneReason.END)
                }
            }
            // Asked again and still nothing: why, or "Robot had no answer", rather than no reply at all.
            EscalationDecision.NONE -> when {
                !ranOwn -> finishOrDrop(replyId)
                ownFailure != null && saidNothing(replyId) -> failReply(replyId, ownFailure)
                else -> finishReply(replyId, DoneReason.END)
            }
        }
        RLog.i("claude_escalation", "trigger" to trigger, "decision" to decision)
    }

    /** Robot's reply [replyId] is still open and holds neither a word nor a card (Gemma handed straight over). */
    private fun saidNothing(replyId: String): Boolean = chat.none { it.id == replyId } &&
        _state.value.turns.firstOrNull { it.id == replyId }?.let { it.text.isBlank() && it.actions.isEmpty() } == true

    /** Robot's own words into its empty reply [replyId], spoken in Robot's voice when replies are spoken. */
    private fun sayInto(replyId: String, text: String) {
        setText(replyId, text)
        voice.feed(replyId, text, _state.value.speak).forEach { speakSentence(it) }
    }

    /** Finishes Robot's reply [replyId]; an empty one with no card is dropped instead (Gemma handed straight over). */
    private fun finishOrDrop(replyId: String) {
        if (!current(replyId)) return
        val ui = _state.value.turns.firstOrNull { it.id == replyId } ?: return
        if (ui.text.isBlank() && ui.actions.isEmpty()) {
            voice.finish(replyId, cancelled = true, speak = false)
            _state.update { st -> st.copy(phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking), claudeStatus = null, turns = st.turns.filter { it.id != replyId }) }
        } else {
            finishReply(replyId, DoneReason.END)
        }
    }

    /**
     * Shows "Want me to check with Claude?" under [replyId], or "Want Claude's take as well?" under Robot's own answer
     * (the Claude app: "Open Claude with: «…»").
     */
    private fun offer(replyId: String, o: PendingOffer) {
        dropOffers()
        offers[replyId] = o
        val text = if (o.api) o.question else ClaudeAppLink.cap(o.question)
        _state.update { st ->
            st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = Offer(o.api, text, o.payload?.sends.orEmpty(), o.payload?.preview, o.asWell)) else it })
        }
        // Said too, so you know a yes will do.
        if (_state.value.speak) VoiceHub.tts.speak(if (!o.api) "Want me to open Claude?" else if (o.asWell) ClaudeWords.OFFER_AS_WELL else ClaudeWords.OFFER)
    }

    private fun dropOffers() {
        if (offers.isEmpty()) return
        offers.clear()
        _state.update { st -> st.copy(turns = st.turns.map { if (it.offer != null) it.copy(offer = null) else it }) }
    }

    /**
     * The offer under [replyId] was accepted: [tapped] on its Send button, or a yes. A yes sends only an offer with
     * nothing personal in it; otherwise Robot says what needs the tap and waits for it.
     */
    fun acceptOffer(replyId: String, tapped: Boolean = true) {
        if (_state.value.busy || slot.running) return
        val o = offers[replyId] ?: return
        // Ask Claude was turned off since the offer was made.
        if (!ClaudeSettings.enabled(VoiceHub.settings)) {
            dropOffers()
            return notify(ClaudeWords.OFF)
        }
        val personal = o.payload?.personal.orEmpty()
        // "Voice yes can send personal content" (off by default): otherwise only the tap sends it.
        if (!tapped && personal.isNotEmpty() && !VoiceHub.settings.voiceYesPersonal) {
            val say = ClaudeWords.tapToInclude(personal)
            notify(say)
            if (_state.value.speak) VoiceHub.tts.speak(say)
            return
        }
        offers.remove(replyId)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        RLog.i("claude_offer", "accepted" to true, "api" to o.api, "tapped" to tapped)
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
        val p = o.payload ?: return
        val id = claudeBubble()
        startTurn(id) { escalateNow(id, p.question, p.blocks, _state.value.speak, reuseBubble = true) }
    }

    fun dismissOffer(replyId: String) {
        offers.remove(replyId) ?: return
        RLog.i("claude_offer", "accepted" to false)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
    }

    /** The key was saved or deleted: without one, Claude mode and API offers end (the old client closes with them). */
    fun claudeKeyChanged() {
        if (ClaudeLink.keySaved(VoiceHub.app)) return
        if (offers.values.any { it.api }) dropOffers()
        endClaudeMode()
    }

    /** Settings changed in the main app: with Ask Claude turned off, offers and Claude mode end. */
    fun settingsChanged() {
        if (ClaudeSettings.enabled(VoiceHub.settings)) return
        dropOffers()
        endClaudeMode()
    }

    /** A new, empty Claude reply bubble at the end of the conversation. */
    private fun claudeBubble(): String {
        val id = newId()
        _state.update { it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, "", streaming = true, claude = true), phase = Phase.THINKING, claudeStatus = ClaudeWords.CHECKING) }
        voice.start(id)
        return id
    }

    /**
     * Robot checks with Claude (automatically, or after your tap): "Checking with Claude…" in Robot's voice, then Claude's
     * answer in Claude's. [blocks]: exactly what goes (ClaudeContext.escalation, or an offer's frozen payload).
     */
    private suspend fun escalateNow(replyId: String?, question: String, blocks: List<ClaudeBlock>, voiceAsked: Boolean, reuseBubble: Boolean) {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val id = if (reuseBubble && replyId != null) replyId.also { r -> _state.update { st -> st.copy(claudeStatus = ClaudeWords.CHECKING, turns = st.turns.map { if (it.id == r) it.copy(claude = true) else it }) } }
            else claudeBubble()
        if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.CHECKING.trimEnd('…') + ".")
        if (Assistant.locked(app) && !s.claudeLocked) return localReply(id, ClaudeWords.LOCKED, claude = false)
        if (!ClaudeLink.networkAllowed(app)) return networkCard(id)
        // The key is decrypted and the client made off the main thread.
        val session = withContext(Dispatchers.IO) { ClaudeLink.session(app, s, conversation = false) } ?: return noKey(id)
        try {
            runClaude(id, session, blocks, ClaudePlan.of(s.claudeEffort, voiceAsked, ClaudeRouter.deep(question)), ToolRouter.asked(question))
        } finally {
            ClaudeLink.done(session)
        }
    }

    /** The app's name for the Send card ("a screenshot of Signal"), or null when it isn't known or visible to Robot. */
    private fun appLabel(pkg: String?): String? = pkg?.let {
        try {
            val pm = VoiceHub.app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().trim().takeIf { l -> l.isNotEmpty() && l != it }
        } catch (_: Exception) { null }
    }

    /** No key to use: none saved, or the saved one couldn't be read (then it was removed, and Robot says so). */
    private fun noKey(replyId: String) {
        val text = if (ClaudeLink.unreadable) ClaudeWords.KEY_UNREADABLE else ClaudeFailure.NO_KEY.text
        failReply(replyId, text)
    }

    /** A question for Claude ("ask Claude …", "go get Claude", or any question in Claude mode). */
    private suspend fun claudeTurn(replyId: String, cmd: ClaudeCommand?, said: String, attachment: Attachment, sText: String?, image: ByteArray?,
        thumb: Bitmap?, history: List<ChatTurn>, voiceAsked: Boolean) {
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
            startClaudeMode(joinedAt = history.size, misheard = cmd.misheard)
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
            val photo = extra == Attachment.PHOTO
            val img = if (photo) (if (attachment == Attachment.PHOTO) image else lastImage) else screenshot?.let { Images.jpeg(it) }
            if (screen == null && img == null) {
                return localReply(replyId, "Robot doesn't have your screen or a photo this time. Swipe up from the corner over what Claude should see, or add a photo, then ask again.", claude = false)
            }
            // The card says exactly what goes and shows the picture: the photo, or a screenshot of the app on screen.
            val pic = if (photo) (if (attachment == Attachment.PHOTO) thumb else lastThumb) else screenshot?.let { Images.scaled(it, Images.THUMB_EDGE) }
            val app = if (!photo && img != null) withContext(Dispatchers.IO) { appLabel(screenPkg) } else null
            val what = if (photo) "the photo" else listOfNotNull(screen?.let { "your screen's text" },
                img?.let { "a screenshot" + (app?.let { a -> " of $a" } ?: "") }).joinToString(" and ")
            if (!askToShare(replyId, "Send $what to Claude?", screen?.let { ToolCalls.safeTake(it, 600) }, "Send", picture = pic)) {
                return localReply(replyId, "Okay, nothing was sent to Claude.", claude = false)
            }
            shared = Shared(screenText = screen, image = img)
        }
        // The key is decrypted and the client made off the main thread.
        val session = (if (mode) claudeSession ?: withContext(Dispatchers.IO) { ClaudeLink.session(app, s, conversation = true) }?.also { claudeSession = it }
            else withContext(Dispatchers.IO) { ClaudeLink.session(app, s, conversation = false) }) ?: return noKey(replyId)
        val now = ZonedDateTime.now()
        try {
            val blocks = when {
                !mode -> ClaudeContext.oneShot(question ?: said, now, userRegion, shared)
                session.fresh -> {
                    // Claude joins the conversation: a personal one only after "Include" ("Start fresh" sends none of it).
                    val before = history.take(claudeJoinedAt.coerceIn(0, history.size))
                    // Claude mode from a word that only sounded like the name asks too, personal or not.
                    val include = ClaudeContext.includeQuestion(before, claudeMisheard)?.let { q ->
                        askToShare(replyId, q, null, "Include", "Start fresh", detail = "Start fresh: Claude sees only what you ask from now on.",
                            personal = ClaudeContext.personal(before).isNotEmpty())
                    } ?: false
                    ClaudeContext.join(before, question ?: said, now, userRegion, shared, include, claudeMisheard)
                }
                else -> ClaudeContext.next(question ?: said, shared)
            }
            runClaude(replyId, session, blocks, ClaudePlan.of(s.claudeEffort, voiceAsked, deep), ToolRouter.asked(said))
        } finally {
            if (!mode) ClaudeLink.done(session)
        }
        if (mode) touchClaudeMode()
    }

    /**
     * One Claude turn through the API into the reply [replyId]: streamed into the bubble and spoken in Claude's voice,
     * Claude's calls as cards (always), then the cost line, today's spend, and Robot's own conversation rebuilt with it.
     */
    private suspend fun runClaude(replyId: String, session: ClaudeSession, blocks: List<ClaudeBlock>, plan: ClaudePlan, asked: Set<ToolName>) {
        _state.update { st -> st.copy(claudeStatus = st.claudeStatus ?: ClaudeWords.THINKING, turns = st.turns.map { if (it.id == replyId) it.copy(claude = true) else it }) }
        val t0 = System.currentTimeMillis()
        var first = -1L
        // As for Robot's replies: each round's start is checked for a copied clock ("[Now 14:05]"), never shown or said.
        var echo = ClockEcho()
        fun stream(text: String) {
            if (text.isEmpty()) return
            appendText(replyId, text)
            voice.feed(replyId, text, _state.value.speak).forEach { speakSentence(it, claude = true) }
        }
        val heard = object : ClaudeListener {
            override fun text(delta: String) {
                if (first < 0) first = System.currentTimeMillis() - t0
                stream(echo.feed(delta))
            }

            override fun declined() {
                // Robot's own words, in Robot's voice and Robot's bubble, in place of what was shown.
                voice.mute()
                VoiceHub.stopVoice(TtsStop.OTHER)
                setText(replyId, ClaudeWords.DECLINED)
                _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(claude = false) else it }) }
                if (_state.value.speak) VoiceHub.tts.speak(ClaudeWords.DECLINED)
            }

            override fun thinking() {
                _state.update { st -> if (st.phase == Phase.THINKING) st.copy(claudeStatus = ClaudeWords.THINKING) else st }
            }

            override fun spent(dollars: Double) {
                // Into today's spend as soon as it is known, so a crash mid-answer loses none of it.
                scope.launch(Dispatchers.IO) { app.heyrobot.sys.ClaudeSpendStore.add(VoiceHub.app, dollars) }
            }
        }
        val claudeTurn = ClaudeTurn(session, blocks, plan, heard)
        // One echo filter per round: the question's answer, then the text after each tool round.
        val rounds = object : TurnModel {
            override suspend fun ask(): ModelOutcome = claudeTurn.ask().also { stream(echo.flush()) }
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
                echo = ClockEcho()
                return claudeTurn.answer(results).also { stream(echo.flush()) }
            }
            override fun close() = claudeTurn.close()
            override fun abandon(results: List<Pair<String, Map<String, Any?>>>) = claudeTurn.abandon(results)
            override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = claudeTurn.stopped(results)
        }
        val result = try {
            TurnRunner(rounds, PanelActions, PanelView(replyId), slot.confirmations, untrusted = true, remote = true, asked = asked, newId = ::newId).run()
        } finally {
            val r = session.lastReport
            RLog.i("claude_turn", "model" to session.model, "effort" to plan.effort, "firstMs" to first, "ms" to (System.currentTimeMillis() - t0),
                "requests" to r.requests, "searches" to r.searches, "searchErrors" to r.searchErrors, "cache_read_input_tokens" to r.usage.cacheRead)
        }
        _state.update { it.copy(claudeStatus = null) }
        val report = session.lastReport
        result.failure?.let {
            // Robot's copy of the conversation can't make a valid request any more: Claude mode starts over.
            if (it == ClaudeFailure.RESET.text) endClaudeMode()
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
        // Cut off before it said anything (its thinking used the room): say so rather than leave an empty bubble.
        if (result.reason == DoneReason.LENGTH && _state.value.turns.firstOrNull { it.id == replyId }?.let { it.text.isBlank() && it.actions.isEmpty() } == true) {
            _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(claude = false) else it }) }
            sayInto(replyId, ClaudeWords.CUT_EMPTY)
        }
        finishReply(replyId, result.reason ?: DoneReason.END, costMicros = ClaudeCost.micros(report.dollars))
        // Claude's answer joins Robot's own conversation: :brain rebuilds it from the history next time.
        closeLiveConversation()
        if (result.launchedApp) leaveForApp()
    }

    /**
     * "Send …to Claude?" with Send and Cancel (or Include and Start fresh): true only after the tap. Said too when replies
     * are spoken, so you know a tap is needed.
     */
    private suspend fun askToShare(replyId: String, summary: String, body: String?, confirm: String, cancel: String = "Cancel",
        detail: String = "Nothing goes to Claude until you tap $confirm.", picture: Bitmap? = null, personal: Boolean = true): Boolean {
        val id = newId()
        val view = PanelView(replyId)
        upsertAction(replyId, UiAction(id, ToolName.ASK_CLAUDE, summary, ActionStatus.PENDING, detail, body = body, confirm = confirm, cancel = cancel, picture = picture,
            personal = personal))
        if (_state.value.speak) {
            val s = VoiceHub.settings
            // A spoken yes sends personal content only with "Voice yes can send personal content" on; a no always works.
            VoiceHub.tts.speak(when {
                !s.voiceCards -> "$summary Tap $confirm or $cancel."
                !personal || s.voiceYesPersonal -> "$summary Say yes or no."
                else -> "$summary Tap $confirm, or say no."
            })
            cardPromptSaid = true
        }
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

    /** GrapheneOS's Network toggle is off for Robot: a card that says so (Claude can't be reached at all), said too. */
    private fun networkCard(replyId: String) {
        PanelView(replyId).card(Card(newId(), ToolName.ASK_CLAUDE, "Robot can't use the network", ActionStatus.FAILED, ClaudeFailure.NO_NETWORK_PERMISSION.text))
        if (_state.value.speak) VoiceHub.tts.speak(ClaudeFailure.NO_NETWORK_PERMISSION.text)
        _state.update { it.copy(claudeStatus = null) }
        finishReply(replyId, DoneReason.END)
    }

    /**
     * The Claude app route: the card "Open Claude with: «…»", then the app opens with the question and the panel steps
     * aside (the launchedApp path). Robot never hears the answer; you can read it back with the corner swipe.
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

    /**
     * Robot's own words as the reply (no model): spoken in Robot's voice ([claude] false) when replies are spoken;
     * [spoken] is the same as the voice says it, when it differs (no "›").
     */
    private fun localReply(replyId: String, text: String, claude: Boolean = false, spoken: String = text) {
        _state.update { st -> st.copy(claudeStatus = null, turns = st.turns.map { if (it.id == replyId) it.copy(claude = claude) else it }) }
        setText(replyId, text)
        voice.feed(replyId, spoken, _state.value.speak).forEach { speakSentence(it, claude) }
        finishReply(replyId, DoneReason.END)
    }

    // ------------------------------------------------------------------ Claude mode

    /** Where Claude mode started in the conversation: what came before is what Claude joins. */
    private var claudeJoinedAt = 0
    /** Claude mode came from a word that only sounded like the name: what came before goes only after a tap. */
    private var claudeMisheard = false

    private fun startClaudeMode(joinedAt: Int, misheard: Boolean = false) {
        if (!_state.value.claudeMode) {
            ClaudeLink.done(claudeSession)
            claudeSession = null
            claudeJoinedAt = joinedAt
            claudeMisheard = misheard
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
        ClaudeLink.done(claudeSession)
        claudeSession = null
        _state.update { it.copy(claudeMode = false) }
        RLog.i("claude_mode", "on" to false)
    }

    // ------------------------------------------------------------------ reading Claude's answer off the Claude app

    /**
     * "Read Claude's answer": the last answer on the Claude app's screen, from the text your corner swipe gave
     * the panel. It is spoken in Claude's voice and joins Robot's conversation as screen content (later actions ask
     * first). Nothing usable found: "Ask about this screen" instead.
     */
    fun readClaudeAnswer() {
        if (_state.value.busy || slot.running) return
        readBack()
    }

    private fun readBack() {
        val st = _state.value
        val pieces = claudePieces ?: return notify(ClaudeWords.SWIPE_TO_READ)
        val answer = ClaudeAppAnswer.extract(pieces, lastToApp)
        RLog.i("claude_read_back", "found" to (answer != null), "chars" to (answer?.length ?: 0))
        if (answer == null) {
            notify("Robot couldn't find Claude's answer on this screen. You can ask about the screen instead.")
            attachScreen()
            return
        }
        VoiceHub.stopVoice(TtsStop.OTHER)
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

    /**
     * Texting without the model (core TextCommand): "text mom I'm late" becomes send_text, prepared, gated and carded as
     * the model's call would be; "text mom" asks what it should say and takes the next question as the message
     * ("cancel" drops it). False: not a text, the model answers.
     */
    private suspend fun directText(replyId: String, q: String, textingFor: String?, fromVoice: Boolean, untrusted: Boolean): Boolean {
        val typed = !fromVoice
        // The wake word is paused while Robot waits for the message, so a spoken "hey robot, …" comes back whole.
        val wakes = VoiceHub.settings.wakePhrases.filter { it.enabled }.map { it.text }
        val answer = if (fromVoice && textingFor != null) TextCommand.stripWake(q, wakes) else q
        // Decided before any contact is read or anything asked: the lock first.
        val locked = Assistant.locked(VoiceHub.app) && ToolName.SEND_TEXT !in VoiceHub.settings.lockedTools
        val req: TextRequest = when (TextCommand.route(answer, textingFor, locked, typed, wakes)) {
            TextCommand.Route.MODEL -> return false
            // Said aloud as well: the question may have come by voice from across the room.
            TextCommand.Route.UNLOCK -> {
                localReply(replyId, "Unlock your phone to send a text.")
                return true
            }
            TextCommand.Route.CANCEL -> {
                val kind = TextCommand.cancelKind(answer, wakes) ?: TextCommand.Cancel.EXPLICIT
                localReply(replyId, TextCommand.cancelReply(textingFor!!, answer, kind, wakes))
                return true
            }
            // The message itself; the approval gate still checks the lock. Only the wake phrase: Robot asks again.
            TextCommand.Route.ANSWER -> TextRequest(textingFor!!, answer.trim().ifEmpty { null })
            TextCommand.Route.PARSE -> {
                // People's names and what you call them, with the phone's contacts when Robot may read them. Not names learned
                // from notifications: whoever sent the message chose those, so they never decide where the message starts.
                val names = withContext(Dispatchers.IO) {
                    PeopleFiles.book(VoiceHub.app).names.filter { !it.learned }.flatMap { listOf(it.name) + it.nicknames + it.aliases }
                }
                TextCommand.parse(answer, names, typed) ?: return false
            }
        }
        val name = TextCommand.display(req.name)
        if (req.message == null) {
            val ask = TextCommand.askMessage(req.name)
            setText(replyId, ask)
            askingTextFor = req.name
            if (_state.value.speak) {
                speakSentence(ask)
                listenAfterSpeech = fromVoice
            }
            finishReply(replyId, DoneReason.END)
            // Asked by voice with replies silent: listen for the answer now.
            if (fromVoice && !_state.value.speak && shown) startListening()
            return true
        }
        RLog.i("text_direct", "typed" to typed)
        // A short line for the conversation, so later questions know what happened (the card has the rest).
        setText(replyId, "Texting $name.")
        val call = RawToolCall(ToolName.SEND_TEXT.id, mapOf("to" to req.name, "message" to req.message))
        // Your own words asked for this text (or answered "what should it say?").
        val result = TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOf(ToolName.SEND_TEXT),
            newId = ::newId).run()
        result.failure?.let { failReply(replyId, it); return true }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) leaveForApp()
        return true
    }

    /** A turn whose one action is already known: no model asked, nothing to answer after the action. */
    private class DirectCall(private val call: RawToolCall) : TurnModel {
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(call))
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
        override fun close() {}
    }

    /**
     * "Open Cheogram" on a card: the app's launcher activity (nothing passed to it). 0.4.0: or a page of Android's
     * settings ("Open storage settings", "Turn on Usage access"), the first of its intents that opens.
     */
    fun openHandOff(actionId: String) {
        val a = _state.value.turns.flatMap { it.actions }.firstOrNull { it.id == actionId } ?: return
        val pkg = a.handOff ?: return
        val page = SettingsPage.isPage(pkg)
        if (Assistant.locked(VoiceHub.app)) return notify(if (page) "Unlock your phone, then open Settings." else "Unlock your phone, then open Cheogram.")
        val h = host ?: return
        val tries = if (page) SettingsIntents.of(VoiceHub.app, pkg)
        else listOfNotNull(VoiceHub.app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (tries.isEmpty()) return notify(if (page) "Settings didn't open." else "Cheogram isn't installed.")
        stopGenerating(TtsStop.OTHER)
        if (tries.any { h.launch(it) }) {
            hidingFor = "action"
            h.hidePanel()
        } else if (page) {
            notify("Settings didn't open.")
        }
    }

    /** The model side of a turn: :brain through [BrainClient]. Nothing is sent once the turn was stopped. */
    private class BrainTurn(
        private val c: BrainClient, private val convId: String, private val replyId: String,
        private val gen: BrainMsg.Generate, private val img: ByteArray?,
    ) : TurnModel {
        private var round = 0

        override suspend fun ask(): ModelOutcome {
            currentCoroutineContext().ensureActive()
            VoiceLog.add(VoiceEvent.BRAIN_SENT, 0, if (img != null || gen.hasImage) 1 else 0)
            return collect(c, c.request(BrainProtocol.REQ_GENERATE, gen, img), replyId)
        }

        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            currentCoroutineContext().ensureActive()
            VoiceLog.add(VoiceEvent.BRAIN_SENT, ++round, 0)
            return collect(c, c.request(BrainProtocol.REQ_TOOL_RESULT, BrainMsg.ToolResult(convId, results)), replyId)
        }

        override fun close() = c.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(convId))
    }

    /**
     * The phone side of a turn: the approval inputs and ToolExecutor (contacts, calendar and files off the main thread).
     * An online tool (the weather) runs through [OnlineRun]: Stop ends it at once, and so does its deadline.
     */
    private object PanelActions : TurnActions {
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override val settings: RobotSettings get() = VoiceHub.settings
        override suspend fun prepare(action: Action): Prepared = withContext(Dispatchers.IO) { executor().prepare(action) }
        override suspend fun run(action: Action): Executed {
            if (!action.online) return withContext(Dispatchers.IO) { executor().run(action) }
            val weather = action.tool == ToolName.WEATHER
            val deadline = if (weather) WeatherAsk.DEADLINE_MS else Nearby.DEADLINE_MS
            val abort = Abort(deadline)
            return try {
                OnlineRun.run(abort, deadline) { a -> executor(a).run(action) } ?: if (weather) {
                    Executed(ActionStatus.FAILED, WeatherFail.TIMEOUT.text).also { VoiceLog.add(VoiceEvent.WEATHER_FAILED, WeatherFail.TIMEOUT) }
                } else if (!abort.claim()) {
                    // The lookup got to open Organic Maps just as its time ran out: that one stays, nothing opens twice.
                    Executed(ActionStatus.DONE, "Opened Organic Maps.", launchedApp = true)
                } else {
                    // The nearest place took too long to find: Organic Maps' search instead, as with it turned off.
                    RLog.i("maps_nearest", "found" to false, "why" to NearbyFail.TIMEOUT)
                    withContext(Dispatchers.IO) { executor().run(Nearby.plain(action)) }.let { r ->
                        if (r.status.succeeded) r.copy(detail = Nearby.instead(NearbyFail.TIMEOUT, navigate = action.tool == ToolName.MAPS_NAVIGATE)) else r
                    }
                }
            } catch (e: CancellationException) {
                if (weather) VoiceLog.add(VoiceEvent.WEATHER_FAILED, WeatherFail.STOPPED)
                throw e
            }
        }
    }

    private fun executor(abort: Abort = Abort()) = ToolExecutor(VoiceHub.app, VoiceHub.settings, abort, toolRoom, turnSaid) { i -> host?.launch(i) ?: false }

    /** What you typed or said this turn (never screen text): "the nearest" in your own words counts for the maps tools. */
    @Volatile private var turnSaid: String? = null

    /** The room for a tool's answer in the turn under way (runTurn's estimate); no limit outside one. */
    @Volatile private var toolRoom = Int.MAX_VALUE

    /** The model's tool call itself, about. */
    private const val TOOL_CALL_TOKENS = 40

    /** Your own GGUF file reads text only (Robot's llama.cpp has no vision part). */
    private const val TEXT_ONLY_PHOTO = "This model can't see photos: it reads text only. Choose Fast or Smarter in the Robot app › Model to ask about a photo."
    private const val TEXT_ONLY_SCREEN = "This model can't see the screen: it reads only the screen's text."

    /** A photo question to a model whose file has no vision encoder (your own file, as its check found). */
    private const val NO_VISION_IN_FILE = "This model can't look at photos: its file has no vision part. Settings › Model says which ones can."

    /** What the turn shows: the reply bubble [replyId], its cards and the panel's phase. */
    private class PanelView(private val replyId: String) : TurnView {
        override fun phase(p: TurnPhase) {
            if (!current(replyId)) return
            _state.update { it.copy(phase = if (p == TurnPhase.CONFIRMING) Phase.CONFIRMING else Phase.THINKING) }
            if (p == TurnPhase.CONFIRMING) cardWaiting() else stopCardListening()
        }

        override fun card(c: Card) {
            val icon = (c.action as? Action.OpenApp)?.key?.let { key -> icons.getOrPut(key) { executor().appIcon(key) } }
            // What a read tool finds goes to Claude only after this tap: the button says so.
            val confirm = c.confirm ?: if (c.remote && c.tool?.reads == true) "Share with Claude" else c.action?.confirmLabel
            upsertAction(replyId, UiAction(c.id, c.tool, c.summary, c.status, c.detail, icon, c.body, c.choices.map { it.label }, confirm, c.undo, c.content, c.more,
                c.cancel, c.handOff, remember = c.remember, allowContacts = c.allowContacts,
                // What it finds goes to Claude: a read, or an online lookup around where you are (the nearest place).
                personal = c.remote && (c.tool?.reads == true || c.action?.online == true), tapFor = c.tapFor))
        }

        override val text: String get() = _state.value.turns.firstOrNull { it.id == replyId }?.text.orEmpty()
        override fun replaceText(text: String) = setText(replyId, text)
        override fun paragraphBreak() = paragraphBreak(replyId)
        override fun say(sentence: String) {
            voice.say(replyId, sentence, _state.value.speak)?.let(::speakSentence)
        }
    }

    private suspend fun collect(c: BrainClient, req: Pair<Int, Flow<BrainMsg>>, replyId: String): ModelOutcome {
        reqId = req.first
        var calls: List<RawToolCall>? = null
        var out: ModelOutcome = ModelOutcome.Failed("The model didn't answer.")
        // Each model round (the reply, then its text after a tool round) is checked for a copy of the clock at its start.
        val echo = ClockEcho()
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        var chars = 0
        var timedOut = false
        // A model that stops answering (a hung engine) must not leave "Thinking…" for ever: no event from :brain for
        // EVENT_GAP_MS, before the first one or between any two, ends the request here (it is cancelled there too).
        val stall = StallTimer(EVENT_GAP_MS, SystemClock.elapsedRealtime())
        val watchdog: Job = scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                if (stall.stalled(SystemClock.elapsedRealtime())) {
                    timedOut = true
                    c.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(req.first))
                    c.abandon(req.first)
                    break
                }
            }
        }
        try {
            req.second.collect { m ->
                stall.event(SystemClock.elapsedRealtime())
                when (m) {
                    is BrainMsg.Chunk -> if (m.text.isNotEmpty()) {
                        if (first < 0) {
                            first = SystemClock.elapsedRealtime() - t0
                            VoiceLog.add(VoiceEvent.BRAIN_FIRST, first.toInt())
                        }
                        chars += m.text.length
                        streamText(replyId, echo.feed(m.text))
                    }
                    is BrainMsg.ToolCallsMsg -> calls = m.calls
                    is BrainMsg.Done -> {
                        out = calls?.takeIf { m.reason == DoneReason.TOOL_CALLS }?.let { ModelOutcome.Calls(it) }
                            ?: ModelOutcome.Done(m.reason, m.textCalls)
                        VoiceLog.add(VoiceEvent.BRAIN_DONE, (SystemClock.elapsedRealtime() - t0).toInt(), DoneCode.of(m.reason).id, chars)
                    }
                    is BrainMsg.Error -> {
                        out = ModelOutcome.Failed(m.code.text())
                        VoiceLog.add(VoiceEvent.BRAIN_FAILED, if (timedOut) BrainFail.TIMEOUT else BrainFail.of(m.code))
                    }
                    else -> {}
                }
            }
            // A reply shorter than the hold-back window.
            streamText(replyId, echo.flush())
        } finally {
            watchdog.cancel()
            reqId = 0
        }
        if (timedOut) out = ModelOutcome.Failed("Robot's model didn't answer in time. Please try again.")
        return out
    }

    /** Streamed reply text: into the bubble, and the sentences it completes to the voice. */
    private fun streamText(replyId: String, text: String) {
        if (text.isEmpty()) return
        appendText(replyId, text)
        voice.feed(replyId, text, _state.value.speak).forEach(::speakSentence)
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
        // Claude's voice per sentence: Kokoro's French voice for a French one, Claude's own otherwise.
        if (claude) ClaudeVoices.forSentence(VoiceHub.settings.voiceChoice, VoiceHub.settings.claudeVoice, clean).let { (sid, lang) -> VoiceHub.tts.speakOther(clean, sid, lang) }
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

    /** The person tapped one of the people or numbers a card listed; [remember]: with "Always use this for …" ticked. */
    fun choose(actionId: String, index: Int, remember: Boolean = false) {
        slot.confirmations.choose(actionId, index, remember)
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
    fun stopGenerating(why: TtsStop = TtsStop.BUTTON) {
        stopCardListening()
        val id = reqId
        if (id != 0) client?.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
        // The voice stops now: nothing already queued, and not the cut-off last sentence (finishReply drops it).
        voice.mute()
        VoiceHub.stopVoice(why)
        // The turn ends as "stopped" (send's cancellation handler, which runs later on the main thread); :brain
        // rebuilds that conversation next time.
        slot.stop()
    }

    /** "Stop" said, a tap or a volume key: the voice stops, and the rest of a reply still streaming is shown but not spoken. */
    fun stopSpeaking(why: TtsStop = TtsStop.TAP) {
        voice.mute()
        VoiceHub.stopVoice(why)
        _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.IDLE) else it }
    }

    /**
     * A volume key pressed in the panel's window (RobotSession): true if it stopped Robot talking, so the key is used
     * up and the volume stays as it was (Settings › Voice › volume key). Otherwise the system changes the volume.
     */
    fun volumeKey(keyCode: Int): Boolean {
        val interactive = try { VoiceHub.app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true }
        if (!VolumeKeyStop.stops(keyCode, VoiceHub.settings.stopKey, VoiceHub.tts.isSpeaking, shown, interactive)) return false
        VoiceLog.add(VoiceEvent.VOLUME_KEY, keyCode)
        stopSpeaking(TtsStop.VOLUME_KEY)
        return true
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
        // The model finished without a word or an action: say so rather than leave an empty bubble.
        if (text.isEmpty() && actions.isEmpty() && !stopped) {
            text = "Robot had no answer. Try asking another way."
            VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.EMPTY)
            if (spokenTurn) cue(Cue.SORRY_NO_ANSWER)
        }
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

    /**
     * free_memory asked for Robot's model to be unloaded during this answer (0.4.0): now that it has ended, release it
     * (inside the turn), then put what Android reports came back on the card and in the saved conversation.
     */
    private suspend fun unloadIfAsked(replyId: String) {
        val before = ModelUnload.take() ?: return
        BrainKeeper.release("free_memory").join()
        scope.launch {
            val after = ModelUnload.settled(VoiceHub.app, before)
            val line = FreeUp.freedLine(before, after)
            _state.update { st ->
                st.copy(turns = st.turns.map { t ->
                    if (t.id != replyId) t else t.copy(actions = t.actions.map { if (it.tool == ToolName.FREE_MEMORY && it.detail == FreeUp.UNLOADING) it.copy(detail = line) else it })
                })
            }
            chat.replaceAll { t ->
                if (t.id != replyId) t else t.copy(actions = t.actions.map { if (it.tool == ToolName.FREE_MEMORY && it.detail == FreeUp.UNLOADING) it.copy(detail = line) else it })
            }
            persist()
        }
    }

    private fun failReply(replyId: String, message: String, action: NoticeAction = NoticeAction.NONE) {
        if (!current(replyId)) return
        voice.finish(replyId, cancelled = true, speak = false)
        VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.FAILED)
        if (spokenTurn) cue(Cue.SORRY_NO_ANSWER)
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

    /** Nothing from the model this long, before its first word or between two: it is given up (a first word takes about 0.3 s). */
    private const val EVENT_GAP_MS = 60_000L
    private const val WATCHDOG_TICK_MS = 5_000L
    private const val SORRY = "Sorry, I didn't catch that."
    private const val CARD_HINT = "A card is waiting: say yes or no, or tap a button on it."
    private const val CARD_TYPE_HINT = "A card is waiting: tap a button on it, or type yes or no."
    private const val SORRY_NO_ANSWER = "Sorry, I couldn't answer that."
}
