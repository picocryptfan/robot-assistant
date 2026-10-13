package app.heyrobot.assistant

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.conversation.ConversationService
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.assistant.routines.RoutineVoice
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.Approval
import app.heyrobot.core.AskHelperTool
import app.heyrobot.core.AskRoute
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainFail
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Card
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.ChatOnly
import app.heyrobot.core.ChatOnlyTurn
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClaudeAppAnswer
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.ClockEcho
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvStop
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.ConversationCommand
import app.heyrobot.core.ConversationLock
import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.CostText
import app.heyrobot.core.Cue
import app.heyrobot.core.DoneCode
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.Executed
import app.heyrobot.core.FastPath
import app.heyrobot.core.FreeUp
import app.heyrobot.core.Hedge
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperCommand
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperLeftOut
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperPayload
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NoQuestion
import app.heyrobot.core.NoReply
import app.heyrobot.core.PanelContinuity
import app.heyrobot.core.Personal
import app.heyrobot.core.PreRouter
import app.heyrobot.core.Prepared
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.Quoted
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.ScreenAccess
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.ScreenData
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.Shared
import app.heyrobot.core.ShowSource
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StallTimer
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TermuxCalls
import app.heyrobot.core.TextCommand
import app.heyrobot.core.TextRequest
import app.heyrobot.core.Tip
import app.heyrobot.core.Tips
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolBudget
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TtsStop
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnSlot
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VolumeKeyStop
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.feedback.FeedbackCommand
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.memory.MemoryRoute
import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.toPromptTurns
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.BrowserRouter
import app.heyrobot.core.web.Citations
import app.heyrobot.core.web.CurrentInfo
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.WebConsent
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebQueryContext
import app.heyrobot.core.web.WebStatus
import app.heyrobot.core.web.WikipediaWire
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.HelperTurn
import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.helpers.anthropic.AnthropicSession
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.helpers.web.WebStep
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.FeedbackFiles
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.HandOffIntents
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.PhoneInfo
import app.heyrobot.sys.Phones
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.sys.Transcripts
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebStore
import app.heyrobot.ui.common.RobotFace
import app.heyrobot.ui.common.keptPicture
import app.heyrobot.voice.Cues
import app.heyrobot.voice.SpeechToText
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
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
 * "Stop" said, a tap on the conversation, a volume key or "Stop speaking" in this phase, with a turn under way
 * ([turnRunning]): true when it ends the turn as the Stop button does, not only the voice (0.4.6). While the model (or
 * a helper) is still writing the reply, stopping only the voice left the turn running: a long story went on being written
 * for up to a minute, the mic button did nothing and a new question got "Robot is still on the last question". A card
 * waiting for a yes or no stays: "stop" then only quiets Robot's "Say yes or no.".
 */
fun Phase.stopEndsTurn(turnRunning: Boolean): Boolean = turnRunning && (this == Phase.THINKING || this == Phase.ANSWERING)

/**
 * 1.0: which robot the panel's header shows in this phase (both looks). Listening wins, a card's "yes" or "no" included
 * ([cardHearing]); then Robot's voice ([talking]: reading aloud, also while the reply is still being written or a card
 * is read out); then writing the reply; otherwise idle, a card waiting for a tap included.
 */
fun Phase.face(cardHearing: Phase?, talking: Boolean): RobotFace =
    if (cardHearing != null) RobotFace.LISTENING
    else when (this) {
        Phase.LISTENING, Phase.TRANSCRIBING -> RobotFace.LISTENING
        Phase.SPEAKING -> RobotFace.SPEAKING
        Phase.THINKING, Phase.ANSWERING -> if (talking) RobotFace.SPEAKING else RobotFace.TYPING
        Phase.IDLE, Phase.CONFIRMING -> if (talking) RobotFace.SPEAKING else RobotFace.IDLE
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
    /** Its button sends personal content to a helper: a spoken or typed yes doesn't, unless "Voice yes can send personal content". */
    val personal: Boolean = false,
    /** Only a tap answers it, for this reason (core Card.tapFor: a learned name, not sure who, online the first time); "no" still declines. */
    val tapFor: AskWhy? = null,
    /** 0.9: a Termux command or script's card (TermuxPanel); its line ([detail]) says why only a tap runs it. */
    val termux: app.heyrobot.ui.common.TermuxCardView? = null,
) {
    /** What a spoken or typed answer needs to know about it (core CardAnswer). */
    val waiting: CardAnswer.Waiting get() = CardAnswer.Waiting(choices.size, tapFor, personal, confirm, say = detail.takeIf { termux != null && tapFor != null })
}

/**
 * "Want me to check with Jarvis?" under a reply (docs/ask-claude.md "When Robot needs help"; any helper since 0.6.0, by
 * its [name]): a tap (or a yes, when nothing personal would go) asks the helper through its API ([api], listing what
 * goes: [sends], with the start of the screen's text in [preview]) or opens its [app] with [text]. Nothing happens
 * without it. [asWell]: it sits under Robot's own answer, given after the local model first handed the question over
 * ("Want Jarvis's take as well?").
 */
data class Offer(val api: Boolean, val text: String, val sends: List<String>, val preview: String? = null, val asWell: Boolean = false,
    val name: String = "Claude", val app: String = "Claude")

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
    /** A helper wrote it: its name as a label ([by]) and the tertiary accent. [readBack]: read off the Claude app's screen. */
    val fromHelper: Boolean = false,
    val readBack: Boolean = false,
    /** "Claude searched the web" (in the helper's name) and these domains. */
    val sources: List<String> = emptyList(),
    /** "≈ 1¢" (only with "Show cost" on), or "cost unknown". */
    val cost: String? = null,
    val offer: Offer? = null,
    /** 0.6.0: which helper wrote it (its id and its name at the time), and whether it is a server on your network. */
    val helper: String? = null,
    val by: String? = null,
    val home: Boolean = false,
    /** 0.7: the reply card under it (PanelMessages). */
    val reply: app.heyrobot.ui.common.ReplyCardView? = null,
    /** 0.9: a web answer's card (WebAnswerCard): shown, never saved (History keeps its saved line). */
    val web: AnswerCard? = null,
    /** 1.0: said while the phone was locked (core ChatTurn.locked): under "Said while the phone was locked". */
    val locked: Boolean = false,
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
    /** A helper's conversation ("go get …"): the following turns go to it ("Talking with Jarvis · Back to Robot"). Its id and name. */
    val helperMode: String? = null,
    val helperName: String? = null,
    /** That helper is a server on your network (the chip says where questions go). */
    val helperHome: Boolean = false,
    /** "ChatGPT is thinking…", "Checking with ChatGPT…" or "Jarvis is loading its model…" until the helper's first words. */
    val helperStatus: String? = null,
    /** The corner swipe came from the Claude app: "Read Claude's answer". */
    val claudeScreen: Boolean = false,
    /** What works on the lock screen (Settings › Safety & privacy), for the panel's words. */
    val lockedTools: Set<ToolName> = LockScreen.DEFAULT,
    /** Listening for "yes" or "no" to the waiting card (LISTENING, then TRANSCRIBING); null: not. The phase stays CONFIRMING. */
    val cardHearing: Phase? = null,
    /** The empty panel's three tips (core Tips), a different three each time it opens. */
    val tips: List<Tip> = emptyList(),
    /** 1.0: Robot's voice is reading aloud right now (VoiceHub's TTS), whatever the phase: the header's robot speaks. */
    val talking: Boolean = false,
) {
    /** The robot in the header. */
    val face: RobotFace get() = phase.face(cardHearing, talking)

    /** A helper's conversation is on. */
    val inHelperMode: Boolean get() = helperMode != null

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
    /**
     * 1.0 (secrets-memory-private-items-not-secure): the panel shows something private from memory: its window is kept out
     * of screenshots, recordings and casting ([on]) until the conversation changes.
     */
    fun secure(on: Boolean) {}
}

object PanelController {
    /** A panel reopened within this time continues the same conversation. */
    private const val CONTINUE_MS = 5 * 60_000L
    /** A helper's conversation ends after this long without a turn. */
    private const val HELPER_IDLE_MS = 10 * 60_000L
    /** A helper that was removed from Settings while Robot still knew it. */
    private const val GONE = "That helper isn't in Robot's settings any more."
    /** "Ask the helper …" with no helper to ask (1.0.0). */
    private const val NO_HELPER = "No helper is set up yet. Add one in Robot's settings, under Helpers."
    private const val NONE_ON = "Every helper is off. Turn one on in Robot's settings, under Helpers."
    /**
     * Failures that mean the helper can't be reached right now (your server is off, no connection, it timed out): a
     * question Robot sent by itself, before its own model ran, comes back to Robot instead.
     */
    private val FALLS_BACK = setOf(HelperFailure.NO_INTERNET, HelperFailure.UNREACHABLE, HelperFailure.TIMEOUT, HelperFailure.SERVER,
        HelperFailure.OVERLOADED, HelperFailure.NO_ADDRESS, HelperFailure.NOT_LOCAL, HelperFailure.CERTIFICATE)

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
    /** 0.8: what a headset's button or car mode asked of this show (PanelHandsFree), or null. */
    private var handsFree: PanelHandsFree.Show? = null
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

    // Helpers (docs/ask-claude.md; any helper since 0.6.0). A helper's conversation (API route), and when it last had a turn.
    private var helperSession: HelperSession? = null
    private var helperLast = 0L
    private var helperIdle: Job? = null
    /** The screen's text when the corner swipe came from the Claude app (for "Read Claude's answer"); never stored. */
    private var claudePieces: List<String>? = null
    /** The question Robot last handed to the Claude app, to find Claude's answer after it. */
    private var lastToApp: String? = null
    /** The offer waiting under a reply, by reply id, with exactly what a tap would send (at most one at a time). */
    private val offers = HashMap<String, PendingOffer>()
    /**
     * The last photo (from the camera or the picker) and its thumbnail, kept for this panel session and conversation
     * only ("ask Jarvis about this photo"). Never a screenshot.
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
     * You said "not Jarvis" in this conversation (HelperRouter.declines): no ask_claude for the local model, no
     * pre-router, no automatic escalation and no offers, until a new conversation or an explicit "ask Jarvis …" or "go
     * get Jarvis". Shown nowhere and never saved.
     */
    private var helperDeclined = false

    /**
     * What an offer's tap does, fixed when it was made: the API request's [payload], or the user's [question] for the
     * helper's app. It never picks up a screen or a picture that arrived later.
     */
    private class PendingOffer(val api: Boolean, val question: String, val payload: HelperPayload?, val voiceAsked: Boolean, val asWell: Boolean = false,
        val helper: String = HelperConfig.CLAUDE_ID)

    /** Your region for a helper's first message: the one set in Settings, else the phone's own (SIM, network, language). */
    private val userRegion get() = VoiceHub.settings.regionFor(Phones.deviceRegion(VoiceHub.app))

    private fun newId() = UUID.randomUUID().toString()

    /** Every helper's name, as the router hears it (on or off: an off one is recognised so Robot can say so). */
    private fun names(): List<HelperName> = HelperName.all(VoiceHub.settings)

    private fun who(h: HelperConfig) = HelperWho.of(h)
    private fun words(h: HelperConfig) = HelperWords(HelperWho.of(h))

    /** Whether helper [h]'s key is saved (in memory after the first look). */
    private fun keySaved(h: HelperConfig?) = HelperLink.keySaved(VoiceHub.app, h)

    /** The helper Robot checks with when it needs help ("When Robot needs help › Ask:"), or none. */
    private fun target(s: RobotSettings = VoiceHub.settings): HelperConfig? = HelperSettings.target(s) { keySaved(it) }

    /** "Ask the helper …" (1.0.0): a helper that can take the question now (its API, or its app installed), or none. */
    private fun askTarget(s: RobotSettings = VoiceHub.settings): HelperConfig? =
        HelperSettings.askTarget(s, { keySaved(it) }) { pkg -> HandOffApps.installed(VoiceHub.app, pkg) }

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
        // 0.7: after a conversation read aloud ("Reply, next, or stop?") or a reply card, one listen; and what Robot read
        // of a message counts as heard back for 15 s from now.
        if (!speaking) PanelMessages.quiet()
        if (!speaking && msgListenAfterSpeech) {
            msgListenAfterSpeech = false
            VoiceHub.main.postDelayed({ if (shown && !VoiceHub.tts.isSpeaking && _state.value.phase == Phase.IDLE) startListening() }, 350)
        }
        _state.update {
            when {
                !speaking && it.phase == Phase.SPEAKING -> it.copy(phase = Phase.IDLE, talking = false)
                speaking && it.phase == Phase.IDLE -> it.copy(phase = Phase.SPEAKING, talking = true)
                else -> it.copy(talking = speaking)
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
        // 0.5.0: a conversation being transcribed pauses while you talk to Robot (nothing said to her is transcribed).
        ConversationSession.panel(true)
        // [args]: what RobotSession's ShowGate let through (1.0, ipc-1): another app's showAssist arguments never get here.
        val source = args?.getString(RobotVoiceService.ARG_SOURCE)
        val convArg = args?.getString(RobotVoiceService.ARG_CONVERSATION)
        // Only an explicit return from CaptureActivity, while the panel is hidden for it, keeps the picture (an abandoned
        // capture does not; 1.0, ipc-2: nor a "capture" show nobody is waiting for).
        val capturePending = hidingFor == "capture"
        val back = source == RobotVoiceService.SRC_CAPTURE && capturePending
        hidingFor = null
        val s = VoiceHub.settings
        // The lock-screen rule first, with no exception (core PanelContinuity): a locked show never shows an earlier
        // conversation, not even the camera's return.
        val stale = System.currentTimeMillis() - lastActivity > CONTINUE_MS
        // 1.0: and the other way round, the lock screen's turns never steer your unlocked conversation, except quoted when
        // its last reply asked for this unlock.
        val open = PanelContinuity.open(locked, startedLocked, stale, chat.isEmpty(), convArg != null && ConversationStore.validId(convArg), back,
            unlockAsked = PanelContinuity.unlockAsked(chat, System.currentTimeMillis()))
        when (open) {
            PanelContinuity.Open.LOAD -> loadConversation(convArg!!)
            PanelContinuity.Open.NEW_LOCKED -> newConversation(locked = true)
            PanelContinuity.Open.NEW -> newConversation(locked)
            PanelContinuity.Open.CONTINUE -> {}
            PanelContinuity.Open.CONTINUE_QUOTED -> quoteLockedTurns()
        }
        // Continued unlocked: never lock-screen safe again (physical-locked-conversation-carryover).
        startedLocked = PanelContinuity.stillLocked(startedLocked, locked)
        val returning = back && (open == PanelContinuity.Open.CONTINUE || open == PanelContinuity.Open.CONTINUE_QUOTED)
        ignoreAssist = source == RobotVoiceService.SRC_APP || returning
        if (!returning) {
            screenText = null
            screenshot = null
            claudePieces = null
            assistPieces.clear()
            screenPkg = null
            val speak = when (s.speakReplies) {
                SpeakReplies.ALWAYS -> true
                SpeakReplies.NEVER -> false
                SpeakReplies.VOICE -> source == RobotVoiceService.SRC_WAKE
            }
            // focusRequest restarts at 0 so a new panel's first composition does not pop the keyboard.
            _state.update { it.copy(speak = speak, notice = null, draft = null, focusRequest = 0) }
            // 0.8: a headset's button or car mode (assistant/handsfree PanelHandsFree): a session routes the microphone first.
            handsFree = PanelHandsFree.onShow(source, args, locked)?.also { hf -> _state.update { it.copy(speak = hf.speak) } }
            draftJpeg = null
        }
        // A helper's conversation never carries over onto the lock screen unless that helper is allowed there.
        if (locked && s.helper(_state.value.helperMode)?.locked != true) endHelperMode()
        _state.update {
            it.copy(locked = locked, screenChip = ScreenChip.HIDDEN, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "",
                showId = it.showId + 1, claudeScreen = false, lockedTools = s.lockedTools, tips = if (returning) it.tips else handsFree?.tips ?: tips(s, locked))
        }
        BrainKeeper.prewarm(VoiceHub.app)
        HelperLink.warm(VoiceHub.app, s)
        PanelMessages.onShow()
        // The voice models load on first use (VoiceHub's memory policy): speech recognition when the mic or the wake
        // word starts a question, the voice when a question is sent with spoken replies on. Nothing loads just for a show.
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        shownAtE = SystemClock.elapsedRealtime()
        val from = showSource(source, showFlags)
        VoiceLog.add(VoiceEvent.SESSION_SHOWN, from)
        startScreen(from, showFlags)
        // The question first: once it is attached, the capture mode can't switch under it ("Always echo cancellation").
        if (source == RobotVoiceService.SRC_WAKE && handsFree == null) startListening()
        // 0.8: opened by Settings' Run now or a Ready card's tap.
        PanelRoutines.onShow(args)?.let { (label, work) -> routineTurn(label, work) }
        VoiceHub.updateCapture()
    }

    private fun showSource(src: String?, flags: Int): ShowSource = when (src) {
        RobotVoiceService.SRC_WAKE -> ShowSource.WAKE
        RobotVoiceService.SRC_KEYGUARD -> ShowSource.KEYGUARD
        RobotVoiceService.SRC_APP -> ShowSource.APP
        RobotVoiceService.SRC_CAPTURE -> ShowSource.CAPTURE
        RobotVoiceService.SRC_HEADSET -> ShowSource.HEADSET
        // Another app's showAssist (its arguments dropped by ShowGate); the corner swipe (and the assist gesture) come
        // without Robot's own source.
        else -> if (flags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0) ShowSource.OTHER_APP else ShowSource.SWIPE
    }

    fun onHide() {
        shown = false
        // 0.7: what was decrypted of memory goes with the panel.
        MemoryPanel.drop()
        // 0.8: a headset or car session ends with the panel, and lets go of the headset; a message that came meanwhile is announced.
        if (hidingFor == null) PanelHandsFree.onHide()
        app.heyrobot.assistant.handsfree.HandsFreeMessages.panelHidden()
        ConversationSession.panel(false)
        val st0 = _state.value
        if (st0.phase == Phase.LISTENING) VoiceLog.add(VoiceEvent.NO_QUESTION, NoQuestion.CLOSED)
        if (st0.busy && spokenTurn && hidingFor == null) VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.STOPPED)
        VoiceLog.add(VoiceEvent.SESSION_HIDDEN, ((SystemClock.elapsedRealtime() - shownAtE) / 1000).toInt())
        stopCardListening()
        VoiceHub.stt.cancel()
        VoiceHub.listener?.wakePaused = false
        listenAfterSpeech = false
        msgListenAfterSpeech = false
        PanelMessages.onHide()
        if (hidingFor == null) askingTextFor = null
        val forCapture = hidingFor == "capture"
        // A turn never goes on behind a hidden panel: closing stops it, and a card nobody can tap any more is declined.
        if (hidingFor == null || slot.confirmations.waiting) stopGenerating(TtsStop.CLOSED)
        if (hidingFor == null) VoiceHub.stopVoice(TtsStop.CLOSED)
        VoiceHub.updateCapture()
        // A helper's conversation ends when the panel closes (not when it hides for the camera or an app a helper opened).
        if (hidingFor == null) endHelperMode()
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
        // "Ask a helper …" is a tip only when some helper can take the question now (the one "ask the helper" would use);
        // on the lock screen, only one allowed there and reachable through its API.
        val t = askTarget(s)
        val helper = t != null && (!locked || (t.locked && HelperSettings.apiReady(t, keySaved(t))))
        return Tips.pick(Tips.Can(s, locked, helper = helper), tipRound++)
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
        endHelperMode()
        offers.clear()
        helperDeclined = false
        // A photo from the last conversation never goes with this one.
        lastImage = null
        lastThumb = null
        chat.clear()
        MemoryPanel.newConversation()
        host?.secure(false)
        forgetReads(allKnown = true)
        groups = setOf(ToolGroup.CORE)
        closeConvAfterTurn = false
        startedLocked = locked
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update {
            PanelState(newId(), locked = locked, speak = it.speak, sttAvailable = it.sttAvailable, micHint = it.micHint, screenChip = it.screenChip,
                showId = it.showId, brain = it.brain, lockedTools = it.lockedTools, tips = it.tips, talking = it.talking)
        }
    }

    private fun loadConversation(id: String) {
        val c = ConversationStore.load(VoiceHub.app, id) ?: return newConversation(false)
        stopGenerating()
        endHelperMode()
        offers.clear()
        helperDeclined = false
        lastImage = null
        lastThumb = null
        chat.clear()
        MemoryPanel.newConversation()
        host?.secure(false)
        // What its earlier read tools returned isn't saved: any word not yours may be from it (QueryCheck).
        forgetReads(allKnown = false)
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
                showId = it.showId, brain = it.brain, lockedTools = it.lockedTools, tips = it.tips, talking = it.talking)
        }
    }

    private fun ChatTurn.toUi() = UiTurn(id, role, text, attachment, keptPicture(picture),
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail, content = it.content) }, stopped = stopped, error = error,
        fromHelper = fromHelper, readBack = readBack, sources = sources, cost = costMicros.takeIf { it > 0 && VoiceHub.settings.helperShowCost }?.let { CostText.line(it / 1e6) },
        helper = helper, by = by, home = home, locked = locked)

    /**
     * 1.0: a conversation begun on the lock screen goes on unlocked (core PanelContinuity: the continuation its "Unlock
     * first" asked for, or the panel still open after the unlock). Its turns stay on screen under "Said while the phone was
     * locked", are quoted for the model, and make the conversation content. :brain's live conversation holds them as they
     * were said, a helper's its own copy and an offer what it would have sent: all end, and the next turn rebuilds from the
     * quoted history.
     */
    private fun quoteLockedTurns() {
        val quoted = PanelContinuity.quoted(chat)
        chat.clear()
        chat += quoted
        _state.update { st -> st.copy(turns = st.turns.map { it.copy(locked = true) }) }
        dropOffers()
        endHelperMode()
        closeLiveConversation()
        RLog.i("locked_turns_quoted", "turns" to quoted.size)
        persist()
    }

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
        // 1.0: a conversation begun on the lock screen is saved with its turns marked, so History's Continue quotes them.
        val c = StoredConversation(st.convId, chat.first().time, System.currentTimeMillis(), PanelContinuity.stored(chat.toList(), startedLocked))
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

    /**
     * 0.7.0, the header's Settings button (the tester's 0.6.0 report: "add a setting button to the robot chat page"):
     * Robot's Settings tab over the panel, as Open Robot does; on the lock screen it asks you to unlock first.
     */
    fun openSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then tap Settings again.")
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.SETTINGS))
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
                    PanelHandsFree.heard(text.isBlank())
                    // No words came back: say so rather than going quiet.
                    if (text.isNotBlank()) sendSpoken(text) else missedQuestion(NoQuestion.EMPTY)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    endListening()
                    PanelHandsFree.heard(true)
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

    /** 0.8: a headset or car session's follow-up window ended with nothing said: listening stops, quietly. */
    fun stopListeningQuietly() {
        if (_state.value.phase != Phase.LISTENING && _state.value.phase != Phase.TRANSCRIBING) return
        VoiceHub.stt.cancel()
        endListening()
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
     * that sends personal content to a helper (unless "Voice yes can send personal content"): Robot says why. False: not a
     * yes or no, no card waits, or it was [spoken] with "Answer cards by voice" off (a typed answer still works then).
     */
    private fun answerCard(text: String, spoken: Boolean): Boolean {
        val card = pendingCard() ?: return false
        val s = VoiceHub.settings
        // 0.8: a routine from the screen's text, or deleting one: only a tap (a "no" still declines).
        routineTapOnly?.takeIf { it.first == card.id }?.let { (_, words) ->
            if (CardAnswer.of(text, s.wakePhrases.filter { it.enabled }.map { it.text }) == CardAnswer.Kind.YES) {
                notify(words)
                if (_state.value.speak) VoiceHub.tts.speak(words)
                return true
            }
        }
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
     * The helpers' router runs first, on the words as they are (docs/ask-claude.md): "ask Jarvis …", "go get ChatGPT",
     * "thanks Jarvis" and the rest; in a helper's conversation every other question goes to the helper. The local model
     * never decides.
     */
    fun send(text: String, fromVoice: Boolean = false): Boolean {
        val st = _state.value
        val q = text.trim()
        if (q.isEmpty() && st.draft == null) return false
        // A card waits: a typed or spoken "yes" or "no" answers it (Settings › Safety & privacy).
        if (st.phase == Phase.CONFIRMING && st.draft == null && answerCard(q, fromVoice)) return true
        // 0.7: a reply card waiting: "yes" (where allowed), "no", or "stop" in its 3 seconds.
        if (st.draft == null && PanelMessages.answerCard(q, fromVoice)) return true
        // 0.8: "next", "skip", "repeat" or "stop" while a routine or the brief speaks (or just paused for "hey robot").
        if (q.isNotEmpty() && st.draft == null && RoutineVoice.control(q)) return true
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
        // A question asked unlocked: this conversation is never shown on the lock screen again (core PanelContinuity), and
        // what was said in it while the phone was locked is quoted from now on (1.0).
        if (PanelContinuity.quotesNow(startedLocked, Assistant.locked(VoiceHub.app), chat.isEmpty())) quoteLockedTurns()
        startedLocked = PanelContinuity.stillLocked(startedLocked, Assistant.locked(VoiceHub.app))
        turnMemContent = false
        val names = names()
        // An offer under the last reply waits for a tap or a short yes (a yes never sends anything personal).
        if (st.draft == null && HelperRouter.isYes(q, names)) offers.keys.lastOrNull { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.let {
            acceptOffer(it, tapped = false)
            return true
        }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        spokenTurn = fromVoice
        turnSaid = q
        val textingFor = askingTextFor
        askingTextFor = null
        expireHelperMode()
        // Conversation mode's commands first (0.5.0, docs/conversation-mode.md 5.4): "transcribe this conversation",
        // and while one runs "stop transcribing", "pause", "resume". Never the model's to decide.
        // 0.7, your messages (PanelMessages, core MessagesRouter) before anything else: "read my messages", "reply …"; and a
        // message's own words heard back while Robot read it are never a request (not even "remember that …" or "report a bug").
        val msgCmd = if (q.isNotEmpty() && st.draft == null) PanelMessages.route(q, fromVoice) else null
        val convCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null) ConversationRouter.route(q, ConversationSession.running) else null
        // 0.7: "report a bug", "I have an idea", « signaler un bogue »: Send feedback opens in the app; the panel sends nothing.
        // After the messages router and its echo check (a message read aloud saying "report a bug: …" is the message's), before the model.
        val fbCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null) FeedbackRouter.route(q) else null
        // 0.8: routines' phrases next ("every weekday at 7, tell me the weather", "brief me", "remind me in the morning to …"),
        // outside a helper's conversation. A routine said in a conversation holding screen text needs a tap.
        val routineCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode)
            PanelRoutines.route(q, TurnRules.untrusted(chat)) else null
        // 0.8: then headset and car mode's phrases, and "call …" (the call tool's own words).
        val handsFreeCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && routineCmd == null &&
            !_state.value.inHelperMode) PanelHandsFree.route(q) else null
        val routineOrHandsFree = routineCmd != null || handsFreeCmd != null
        // 0.9: "search the web for …" next: web_search with exactly your words, without the model (WebRouter); outside a
        // helper's conversation and without an attachment. 1.0: or a search in your browser ("search DuckDuckGo for …").
        val webQuery = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree &&
            !_state.value.inHelperMode) BrowserRouter.route(q) else null
        // 0.9: then "run backup", your own Termux script named in your own words (TermuxTool.direct), the same way.
        val termux = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null &&
            !_state.value.inHelperMode) TermuxTool.direct(VoiceHub.app, q, typed = !fromVoice) else null
        // 0.7: memory's commands next ("remember that …", "forget that", "what do you know about me?"), Robot's own even in a
        // helper's conversation: what you ask Robot to remember never goes to a helper (core MemoryRouting).
        val memRoute = if (q.isNotEmpty() && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null &&
            termux == null) MemoryPanel.route(q, Assistant.locked(VoiceHub.app)) else null
        // 0.7.0: "free up memory", "close apps", « libère de la mémoire »: Robot's own answer (what Android reports, what
        // unloading its model gives, the apps used lately) and a card to pick from (core FreeUp), not the model's guess.
        val freeAsk = if (q.isNotEmpty() && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null &&
            termux == null && memRoute == null) FreeUp.asked(q) else null
        // The helpers' phrases next ("ask ChatGPT …", "go get Jarvis"), then texting without the model, then the model (FastPath).
        var cmd = if (q.isNotEmpty() && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null &&
            termux == null && memRoute == null && freeAsk == null) HelperRouter.route(q, names) else null
        // "Thanks Jarvis" outside a helper's conversation is ordinary talk.
        if (cmd == HelperCommand.Leave && !_state.value.inHelperMode) cmd = null
        // "Not Jarvis", "you tell me": Robot answers, now and for the rest of the conversation (a helper's conversation ends too).
        // Only an explicit "ask …" or "go get …" brings a helper back.
        if (q.isNotEmpty() && memRoute == null && fbCmd == null && !routineOrHandsFree && webQuery == null && termux == null && freeAsk == null &&
            HelperRouter.declines(q, names)) {
            if (cmd is HelperCommand.Ask || cmd is HelperCommand.Join) cmd = null
            if (!helperDeclined) RLog.i("helper_declined", "helperMode" to _state.value.inHelperMode)
            helperDeclined = true
            endHelperMode()
        } else if (cmd is HelperCommand.Ask || cmd is HelperCommand.Join) {
            helperDeclined = false
        }
        if (cmd != null) RLog.i("helper_command", "kind" to RLog.Code(cmd.javaClass.simpleName))
        dropOffers()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> screenshot?.let { Images.jpeg(it) }
            Attachment.NONE -> null
        }
        // Only a photo: "ask Jarvis about this photo" must never pick up a screenshot.
        if (attachment == Attachment.PHOTO && image != null) {
            lastImage = image
            lastThumb = st.draft?.thumb
        }
        val thumb = st.draft?.thumb
        val sText = if (attachment == Attachment.SCREEN) screenText else null
        val history = chat.toList()
        val now = System.currentTimeMillis()
        // "Ask Jarvis about my screen" said without attaching it is still a screen question: the conversation is
        // personal from here on, and it is saved only with Keep.
        val marked = if (attachment != Attachment.NONE) attachment else (cmd as? HelperCommand.Ask)?.extra ?: Attachment.NONE
        // "Keep screenshots and photos in history" (off by default): the thumbnail and the screen's text go into the saved
        // conversation, for History only (never replayed to the model, never sent to a helper).
        val kept = VoiceHub.settings.let { it.keepMedia && it.saveHistory } && attachment != Attachment.NONE
        // 0.7: a message's words heard back while Robot read it are the message's, never your question: History keeps their
        // placeholder and the model is given it (core ChatTurn.messages), never the words.
        val heardBack = msgCmd is PanelMessages.Routed.Echo
        val user = ChatTurn(newId(), TurnRole.USER, memRoute?.let { MemoryPanel.question(it, q) } ?: q.ifEmpty { Prompt.defaultQuestion(attachment) }, now, marked,
            picture = if (kept) st.draft?.thumb?.let { b -> java.util.Base64.getEncoder().encodeToString(Images.jpeg(b, Images.THUMB_EDGE, 70)) } else null,
            screenText = if (kept) sText?.let(ScreenText::sanitize)?.takeIf { it.isNotBlank() }?.let { ToolCalls.safeTake(it, ConversationCodec.MAX_SCREEN_TEXT) } else null,
            messages = heardBack, kept = if (heardBack) app.heyrobot.core.messages.MessageReading.HEARD_BACK else null)
        chat += user
        val way = if (memRoute != null || freeAsk != null) FastPath.Way.MODEL else FastPath.of(cmd, _state.value.inHelperMode && fbCmd == null, attachment)
        val toHelper = way == FastPath.Way.HELPER
        // Groups are only ever added: a question that needs a new one rebuilds the conversation once (:brain).
        if (cmd == null && !toHelper && convCmd == null && msgCmd == null && fbCmd == null && memRoute == null && freeAsk == null) groups = groups + ToolRouter.route(q)
        val replyId = newId()
        // Decided per turn: a screen turn stops saving until the person taps Keep.
        val keep = if (marked == Attachment.SCREEN && !keptByUser) false else st.keep
        // Screen text, a picture, a read tool's findings or a helper's words anywhere in this conversation: every action asks first.
        val untrusted = TurnRules.untrusted(chat)
        // The helper this turn goes to: the one named, or the one whose conversation is on. 1.0.0: "ask the helper …" names
        // none (HelperName.ANY_ID): one that can take it now, the one you chose first (HelperSettings.askTarget); none, and
        // helperTurn says what's missing.
        val askedId = (cmd as? HelperCommand.Ask)?.helper ?: (cmd as? HelperCommand.Join)?.helper ?: _state.value.helperMode
        val turnHelper = if (!toHelper) null else if (askedId == HelperName.ANY_ID) askTarget() else VoiceHub.settings.helper(askedId)
        val api = turnHelper != null && turnHelper.on && HelperSettings.route(turnHelper, keySaved(turnHelper)) == AskRoute.API
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, marked, st.draft?.thumb) +
                    UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true, fromHelper = api && !(cmd is HelperCommand.Join && cmd.question == null),
                        helper = turnHelper?.id, by = turnHelper?.name, home = turnHelper?.home == true),
                phase = Phase.THINKING, draft = null, partial = "", notice = null, keep = keep,
                locked = Assistant.locked(VoiceHub.app),
                helperStatus = if (api) words(turnHelper!!).thinking else null,
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
                msgCmd != null -> PanelMessages.run(replyId, msgCmd)
                convCmd != null -> conversationCommand(replyId, convCmd)
                fbCmd != null -> feedbackCommand(replyId, fbCmd)
                routineCmd != null -> PanelRoutines.handle(replyId, routineCmd, routineHost, said = q)
                handsFreeCmd != null -> PanelHandsFree.handle(replyId, handsFreeCmd, routineHost)
                webQuery != null -> directWeb(replyId, webQuery, user.text, history, untrusted)
                // 0.9: "run backup", your own script named in your own words: termux_script without the model.
                termux != null -> termuxScript(replyId, termux, untrusted)
                memRoute != null -> memoryCommand(replyId, memRoute, fromVoice)
                freeAsk != null -> freeMemoryCommand(replyId, freeAsk)
                cmd == HelperCommand.Leave -> {
                    endHelperMode()
                    localReply(replyId, ClaudeWords.LEFT)
                }
                cmd == HelperCommand.ReadAnswer -> if (claudePieces != null) {
                    finishOrDrop(replyId)
                    readBack()
                } else {
                    localReply(replyId, ClaudeWords.SWIPE_TO_READ)
                }
                way == FastPath.Way.HELPER -> helperTurn(replyId, cmd, turnHelper, user.text, attachment, sText, image, thumb, history, fromVoice)
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
        val target = target(s)
        val keySaved = keySaved(target)
        // Escalation trigger a: strong signals go to the helper before Gemma spends a prefill (Automatic only); with Ask
        // first, Gemma answers and the offer follows its reply.
        var offerAfter: EscalationTrigger? = null
        // The offer is for current information: decided again after Gemma's answer, a helper that can't search still offers.
        var offerNeedsSearch = false
        // Not after "not Jarvis": that conversation stays with Robot. With the weather tool on, a weather question is
        // Robot's to answer (0.4.0). Locked, the tool can't run: as before, the question may go to the helper when it is
        // allowed on the lock screen. Current information goes by itself only to a helper that can search (0.6.0).
        val localWeather = s.approval(ToolName.WEATHER) != Approval.OFF && (!Assistant.locked(VoiceHub.app) || ToolName.WEATHER in s.lockedTools)
        // 0.9: with web search on (and "Robot searches first"), current information is Robot's to look up, as the weather.
        val web = withContext(Dispatchers.IO) { WebStore.load(VoiceHub.app) to WebAnswers.load(VoiceHub.app) }
        val webOn = WebConsent.on(web.first, web.second)
        val localWeb = webOn && web.first.currentInfo == CurrentInfo.ROBOT_FIRST && s.approval(ToolName.WEB_SEARCH) != Approval.OFF &&
            (!Assistant.locked(VoiceHub.app) || ToolName.WEB_SEARCH in s.lockedTools)
        if (attachment == Attachment.NONE && !helperDeclined && target != null) PreRouter.signal(question, localWeather, localWeb)?.let { signal ->
            RLog.i("helper_trigger", "kind" to EscalationTrigger.PRE_ROUTER, "signal" to signal)
            when (decide(attachment, guardFree = !guard.used, needsSearch = signal == PreRouter.Signal.CURRENT_INFO)) {
                EscalationDecision.AUTO -> {
                    guard.take(fromHelper = false)
                    val personal = personalNow(null)
                    val blocks = HelperContext.escalation(question, history, ZonedDateTime.now(), userRegion, personal = personal,
                        includePersonal = HelperTrust.conversation(s, target, personal, MemoryPanel.toTrusted()))
                    // A helper that can't be reached (a server that's off, no connection): Robot answers itself instead.
                    if (!escalateNow(replyId, question, blocks, voiceAsked, reuseBubble = true, h = target, fallBack = true)) return
                    RLog.i("helper_fallback", "kind" to target.kind)
                }
                EscalationDecision.OFFER -> {
                    offerAfter = EscalationTrigger.PRE_ROUTER
                    offerNeedsSearch = signal == PreRouter.Signal.CURRENT_INFO
                }
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
        // offer to open Settings › Model, never a switch made for you. (Texting and the helpers' phrases went their own way
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
        // ask_claude is described (with its one line in the system prompt) only with a helper to check with, escalation
        // not Off, somewhere for the question to go (its API, or its app installed), no "not Jarvis" in this
        // conversation, and no screen or photo with the question or before it in the conversation: Robot answers those
        // itself first, and a follow-up keeps the live conversation that still holds the screen (AskHelperTool).
        val api = target != null && HelperSettings.apiReady(target, keySaved)
        val localTool = HelperSettings.localTool(s, target, keySaved)
        val reachable = localTool && (api || (target?.presetOf?.app?.let { pkg -> withContext(Dispatchers.IO) { HandOffIntents.installed(VoiceHub.app, pkg) } } == true))
        val leftOut = AskHelperTool.leftOut(localTool, reachable, helperDeclined, AskHelperTool.picture(attachment, history))
        if (leftOut != null && leftOut != HelperLeftOut.OFF && leftOut != HelperLeftOut.NOWHERE) {
            RLog.i("helper_left_out", "why" to leftOut)
            VoiceLog.add(VoiceEvent.CLAUDE_LEFT_OUT, leftOut)
        }
        var cutSaid = false
        // What you asked for in your own words this turn (never the screen's text or a photo): Safety cards' "asked for".
        val askedNow = ToolRouter.asked(question)
        // The loaded model's context: every budget below is made from it (4,096, or a short-context file's own).
        val maxTokens = loaded.contextTokens
        // 0.7: what Robot remembers goes with this panel chat question (pinned preferences, found items), within that context;
        // the question keeps their ids for History, never their words. An answer with a secret in it is shown, not spoken.
        val locked0 = Assistant.locked(VoiceHub.app)
        // 1.0 (injection-memory-recall-taint): every turn's items, a recall answer's too (core MemoryTaint).
        val given = app.heyrobot.core.memory.MemoryTaint.given(chat)
        val mem = withContext(Dispatchers.IO) { MemoryPanel.forTurn(question, locked0, given, maxTokens) }
        turnMemContent = mem.content
        if (mem.packed.retrievedIds.isNotEmpty()) chat.indexOfLast { it.role == TurnRole.USER }.takeIf { it >= 0 }?.let { i ->
            chat[i] = chat[i].copy(memoryIds = mem.packed.retrievedIds)
        }
        MemoryPanel.mark(replyId, mem.packed.retrievedIds, secret = mem.recall.secret)
        // A private item went in: the live conversation goes after this answer, so no later answer can repeat it (spoken, or
        // kept in History); rebuilt, it holds History's line instead, and naming the item again gives it again.
        if (mem.recall.secret) {
            closeConvAfterTurn = true
            host?.secure(true)
        }
        if (mem.quiet) {
            voice.mute()
            notify("Not read aloud: the answer holds something private from your memory.")
        }
        // One run of the model on this question into the reply, with or without ask_claude (the second one, without it,
        // is Robot's own answer after the model handed over: afterLocal).
        suspend fun gemma(askHelper: Boolean): TurnResult {
            // Only the groups this conversation needs, and nothing turned off in settings, is described to the model; to a
            // chat-only model nothing at all, with its compact prompt (Settings › "Direct answers" as you set it).
            val locked = Assistant.locked(VoiceHub.app)
            val driving = CarModeController.on
            // 0.9: the Termux tools only while Settings › Termux is on and set up (and the words call for them), and only when
            // they leave their own larger room for the new turn (TermuxCalls.MIN_TURN_TOKENS, in ToolBudget).
            val termuxTools = if (chatOnly || ToolGroup.TERMUX !in groups) emptySet() else withContext(Dispatchers.IO) { TermuxTool.tools(VoiceHub.app, locked) }
            val termuxLine = if (termuxTools.isEmpty()) null else withContext(Dispatchers.IO) { TermuxTool.promptLine(VoiceHub.app, termuxTools, locked) }
            fun toolsFor(g: Set<ToolGroup>): List<ToolName> =
                if (chatOnly) emptyList() else ToolRouter.tools(g, s, askHelper, memory = mem.tools, web = webOn, termux = termuxTools,
                    browser = BrowserRouter.named(question))
            fun systemFor(tools: List<ToolName>): String = app.heyrobot.core.memory.MemoryPrompt.system(if (chatOnly) Prompt.chatSystem(now, direct = s.directAnswers)
            else Prompt.system(now, locked, askHelper, texting = ToolName.SEND_TEXT in tools,
                weather = ToolName.WEATHER in tools, phone = ToolName.PHONE_STATUS in tools, direct = s.directAnswers, lockedTools = s.lockedTools,
                helper = target?.name, helperSearches = target?.searching ?: true, helperHome = target?.home == true, driving = driving,
                web = ToolName.WEB_SEARCH in tools, termux = termuxLine?.takeIf { tools.any { it.termux } }, browser = ToolName.BROWSER_SEARCH in tools),
                tools = ToolName.REMEMBER in tools, pinned = mem.packed.systemLine)
            // 0.9: with every group this conversation called for, the new turn may not fit: the groups this question doesn't call
            // for give way first, then the others, until it keeps its room (core ToolBudget).
            val wanted = if (termuxTools.isEmpty()) groups - ToolGroup.TERMUX else groups
            val described = if (chatOnly) wanted else ToolBudget.fit(wanted, ToolRouter.route(question), maxTokens) { g ->
                toolsFor(g).let { Prompt.fixedTokens(systemFor(it), it) }
            }
            if (described != wanted) RLog.i("tool_budget", "dropped" to (wanted - described).size, "kept" to described.size)
            val tools = toolsFor(described)
            val system = systemFor(tools)
            // The time only for a question that needs it, after the question: the model copied a leading time into its replies.
            val clock = if (Prompt.needsClock(question, groups)) Prompt.clock(now) else ""
            // The new turn always fits the model's context: cut (and say so) rather than overflow. Memory's notes go before it only
            // when the question still fits whole with them.
            val notes = mem.packed.notesBlock?.takeIf {
                !Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools) + mem.packed.notesTokens, clock, maxTokens).shortened
            }
            val fitted = Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools) + (if (notes != null) mem.packed.notesTokens else 0),
                clock, maxTokens).let { if (notes != null) it.copy(text = notes + it.text) else it }
            if (fitted.shortened && !cutSaid) {
                cutSaid = true
                notify(if (fitted.questionCut) "Your message was too long, so Robot read only its first part." else "The screen had a lot of text, so Robot read only the first part.")
            }
            val gen = BrainMsg.Generate(
                convId = convId,
                system = system,
                history = history.toPromptTurns(mem.notes),
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
            // 0.7: notes kept from content (not your own words) make the turn content too (MemoryTurn.content).
            return TurnRunner(model, PanelActions, PanelView(replyId), slot.confirmations, untrusted || mem.content,
                asked = askedNow, messages = PanelMessages.taint(chat), newId = ::newId).run()
        }
        // ask_claude is a tool: a chat-only model gets none (the escalation triggers outside the model still apply).
        val result = gemma(askHelper = leftOut == null && !chatOnly)
        if (mem.packed.retrievedIds.isNotEmpty()) scope.launch(Dispatchers.IO) { MemoryPanel.used(mem.packed.retrievedIds) }
        result.failure?.let { return failReply(replyId, it) }
        // 0.9: Gemma called web_search and it ran: its turn ended there, and a one-shot answers from what was found.
        result.web?.let { return webAnswer(replyId, question, history, it) }
        // Escalation trigger b: Gemma called ask_claude. Its turn ended there; the helper answers (or is offered).
        if (result.escalate) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.LOCAL_TOOL)
            return afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.LOCAL_TOOL,
                ownAnswer = { gemma(askHelper = false) })
        }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) return leaveForApp()
        // Escalation trigger c: the reply hedged ("I don't know", "I can't browse", nothing at all). One that says it has no
        // current information goes by itself, like current information, only to a helper that can search (0.6.0).
        val reply = chat.lastOrNull { it.id == replyId }
        if (result.reason == DoneReason.END && reply != null && reply.actions.isEmpty() && Hedge.detect(reply.text)) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.HEDGE)
            return afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.HEDGE,
                needsSearch = Hedge.needsSearch(reply.text))
        }
        offerAfter?.let { afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, it, offerOnly = true, needsSearch = offerNeedsSearch) }
    }

    /**
     * The escalation policy for this question now ([attachment]: its screen or photo, which only a tap may send, unless
     * "Send personal content to helpers automatically" is on).
     */
    private suspend fun decide(attachment: Attachment, guardFree: Boolean, needsSearch: Boolean = false): EscalationDecision {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val target = target(s)
        return EscalationPolicy.decide(s, target, keySaved(target), attachment != Attachment.NONE && !HelperTrust.attachments(s), Assistant.locked(app),
            HelperLink.budgetReached(app, s, target), !guardFree, declined = helperDeclined, priceKnown = target?.price?.known ?: false, needsSearch = needsSearch)
    }

    /** The personal content in the conversation now, the reply [replyId] included while it is still on screen only. */
    private fun personalNow(replyId: String?): Set<Personal> {
        val ui = replyId?.takeIf { id -> chat.none { it.id == id } }?.let { id -> _state.value.turns.firstOrNull { it.id == id } }
        val reply = ui?.let { listOf(ChatTurn(it.id, TurnRole.ASSISTANT, it.text, 0, actions = records(it.actions))) }.orEmpty()
        return HelperContext.personal(chat + reply)
    }

    /**
     * Robot's reply [replyId] is in (or Gemma handed over with ask_claude): bring the helper in now, offer it, or say why not.
     * At most one escalation per turn ([guard]); [offerOnly]: the pre-router's offer, after Gemma's answer. [screen] and
     * [image]: this question's screen text and picture, which only an offer's tap may send. [ownAnswer]: the model once
     * more on the same question without ask_claude, for when it handed over without a word and the helper isn't asked right
     * away (EscalationPolicy.ownAnswerFirst): its answer comes first and any offer goes under it. [needsSearch]: the
     * question needs today's sources (the pre-router's current information, or a reply that said it has none): it goes by
     * itself only to a helper that can search, as the pre-router decided (0.6.0).
     */
    private suspend fun afterLocal(replyId: String, question: String, history: List<ChatTurn>, attachment: Attachment, screen: String?, image: ByteArray?,
        voiceAsked: Boolean, guard: EscalationGuard, trigger: EscalationTrigger, offerOnly: Boolean = false, ownAnswer: (suspend () -> TurnResult)? = null,
        needsSearch: Boolean = false) {
        // A turn that already escalated decides NONE here.
        val decision = decide(attachment, guardFree = !guard.used, needsSearch = needsSearch)
        guard.take(fromHelper = false)
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val target = target(s)
        val keySaved = keySaved(target)
        // Never only "I'd rather check that with Jarvis.": the model's own answer first, in the same reply (and turn).
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
            RLog.i("helper_own_answer", "answered" to answered, "decision" to decision, "failed" to (r.failure != null), "again" to r.escalate)
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
        if (decision == EscalationDecision.OFFER && target != null && HelperSettings.apiReady(target, keySaved) &&
            HelperSettings.escalation(s, target, keySaved) == Escalation.AUTO && HelperLink.budgetReached(app, s, target)) {
            val day = LocalDate.now().toString()
            if (budgetSaid != day) {
                budgetSaid = day
                notify(words(target).budget)
                if (_state.value.speak) VoiceHub.tts.speak(words(target).budget)
            }
        }
        // A personal conversation (this reply's read included): Robot sends only the question.
        val personal = personalNow(replyId)
        when (decision) {
            EscalationDecision.AUTO -> if (offerOnly || target == null) finishOrDrop(replyId) else {
                // A screen or photo question gets here only with personal content allowed to go by itself: it goes along.
                val shared = if (attachment != Attachment.NONE && HelperTrust.attachments(s)) Shared(screenText = screen, image = image) else Shared()
                val blocks = HelperContext.escalation(question, history, ZonedDateTime.now(), userRegion, shared, personal = personal,
                    includePersonal = HelperTrust.conversation(s, target, personal, MemoryPanel.toTrusted()))
                finishOrDrop(replyId)
                escalateNow(null, question, blocks, voiceAsked, reuseBubble = false, h = target)
            }
            EscalationDecision.OFFER -> {
                target!!
                val api = HelperSettings.apiReady(target, keySaved)
                // No API to ask and no app to open: nothing to offer.
                if (!api && target.presetOf.app?.let { pkg -> withContext(Dispatchers.IO) { HandOffIntents.installed(app, pkg) } } != true) {
                    if (saidNothing(replyId)) sayInto(replyId, "I'm not sure about that one.")
                    if (ranOwn) finishReply(replyId, DoneReason.END) else finishOrDrop(replyId)
                    return RLog.i("helper_escalation", "trigger" to trigger, "decision" to EscalationDecision.NONE)
                }
                if (chat.none { it.id == replyId }) {
                    // Gemma said nothing before asking for a helper, not even when asked again: the offer stands on its own.
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) sayInto(replyId, if (api) words(target).rather else "${target.name} could help with that.")
                    finishReply(replyId, DoneReason.END)
                }
                // What the tap sends is fixed now: this question's screen or picture, never one that arrives later.
                val payload = if (!api) null else HelperContext.payload(question, history, ZonedDateTime.now(), userRegion, personal, attachment,
                    Shared(screenText = screen, image = image), includePersonal = HelperTrust.conversation(s, target, personal, MemoryPanel.toTrusted()))
                offer(replyId, PendingOffer(api, question, payload, voiceAsked, asWell, target.id))
            }
            EscalationDecision.NEEDS_UNLOCK -> {
                if (chat.none { it.id == replyId }) {
                    if (_state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) sayInto(replyId, target?.let { words(it).unlockToCheck } ?: "Unlock your phone first.")
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
        RLog.i("helper_escalation", "trigger" to trigger, "decision" to decision)
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
            _state.update { st -> st.copy(phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking), helperStatus = null, turns = st.turns.filter { it.id != replyId }) }
        } else {
            finishReply(replyId, DoneReason.END)
        }
    }

    /**
     * Shows "Want me to check with Jarvis?" under [replyId] (any helper, by its name), or "Want Jarvis's take as well?"
     * under Robot's own answer (a helper's app: "Open the ChatGPT app with: «…»").
     */
    private fun offer(replyId: String, o: PendingOffer) {
        dropOffers()
        val h = VoiceHub.settings.helper(o.helper) ?: return
        offers[replyId] = o
        val text = if (o.api) o.question else AppHandOff.cap(o.question)
        _state.update { st ->
            st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = Offer(o.api, text, o.payload?.sends.orEmpty(), o.payload?.preview, o.asWell, h.name,
                h.presetOf.defaultName)) else it })
        }
        // Said too, so you know a yes will do.
        if (_state.value.speak) VoiceHub.tts.speak(if (!o.api) "Want me to open the ${h.presetOf.defaultName} app?" else if (o.asWell) words(h).offerAsWell else words(h).offer)
    }

    private fun dropOffers() {
        if (offers.isEmpty()) return
        offers.clear()
        _state.update { st -> st.copy(turns = st.turns.map { if (it.offer != null) it.copy(offer = null) else it }) }
    }

    /**
     * The offer under [replyId] was accepted: [tapped] on its Send button, or a yes. A yes sends only an offer with
     * nothing personal in it (or, to a helper you trust, nothing from your screen or a photo); otherwise Robot says what
     * needs the tap and waits for it.
     */
    fun acceptOffer(replyId: String, tapped: Boolean = true) {
        if (_state.value.busy || slot.running) return
        val o = offers[replyId] ?: return
        val s = VoiceHub.settings
        val h = s.helper(o.helper)
        // The helper was turned off or removed since the offer was made.
        if (h == null || !h.on) {
            dropOffers()
            return notify(h?.let { words(it).off } ?: GONE)
        }
        val personal = o.payload?.personal.orEmpty()
        // "Voice yes can send personal content" (off by default): otherwise only the tap sends it.
        if (!tapped && !HelperTrust.voiceYes(s, h, personal)) {
            val say = words(h).tapToInclude(personal)
            notify(say)
            if (_state.value.speak) VoiceHub.tts.speak(say)
            return
        }
        offers.remove(replyId)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        RLog.i("helper_offer", "accepted" to true, "api" to o.api, "tapped" to tapped, "kind" to h.kind)
        if (!o.api) {
            // The offer was the card: the helper's app opens now (never on the lock screen).
            if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone to open the ${h.presetOf.defaultName} app.")
            lastToApp = o.question
            scope.launch {
                val r = withContext(Dispatchers.IO) { executor().run(Action.AskHelper(o.question, helper = h.id)) }
                if (r.launchedApp && r.status.succeeded) leaveForApp() else notify(r.detail)
            }
            return
        }
        val p = o.payload ?: return
        val id = helperBubble(h)
        startTurn(id) { escalateNow(id, p.question, p.blocks, _state.value.speak, reuseBubble = true, h = h) }
    }

    fun dismissOffer(replyId: String) {
        offers.remove(replyId) ?: return
        RLog.i("helper_offer", "accepted" to false)
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(offer = null) else it }) }
    }

    /**
     * Helper [id]'s key was saved or deleted (or the helper removed): without a key it needs, its conversation and its API
     * offers end (the old client closes with them).
     */
    fun helperKeyChanged(id: String) {
        val h = VoiceHub.settings.helper(id)
        if (h != null && (!h.presetOf.keyRequired || keySaved(h))) return
        if (offers.values.any { it.api && it.helper == id }) dropOffers()
        if (_state.value.helperMode == id) endHelperMode()
    }

    /** Settings changed in the main app: the offers and the conversation of a helper turned off or removed end. */
    fun settingsChanged() {
        val s = VoiceHub.settings
        fun gone(id: String) = s.helper(id)?.on != true
        if (offers.values.any { gone(it.helper) }) dropOffers()
        val mode = _state.value.helperMode ?: return
        if (gone(mode)) return endHelperMode()
        // Renamed meanwhile: the chip says the new name.
        val name = s.helper(mode)?.name
        if (name != null && name != _state.value.helperName) _state.update { it.copy(helperName = name) }
    }

    /** A new, empty reply bubble of [h]'s at the end of the conversation. */
    private fun helperBubble(h: HelperConfig): String {
        val id = newId()
        _state.update {
            it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, "", streaming = true, fromHelper = true, helper = h.id, by = h.name, home = h.home),
                phase = Phase.THINKING, helperStatus = words(h).checking)
        }
        voice.start(id)
        return id
    }

    /** Reply [replyId] is [h]'s (its label and accent), or Robot's again ([h] null). */
    private fun markHelper(replyId: String, h: HelperConfig?) = _state.update { st ->
        st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(fromHelper = h != null, helper = h?.id, by = h?.name, home = h?.home == true) else it })
    }

    /**
     * Robot checks with helper [h] (automatically, or after your tap): "Checking with Jarvis…" in Robot's voice, then the
     * answer in the helper's. [blocks]: exactly what goes (HelperContext.escalation, or an offer's frozen payload).
     * [fallBack] (Automatic, before Robot's own model ran): a helper that can't be reached before it said anything gives
     * the question back; then true is returned, the reply is Robot's again, and Robot answers itself.
     */
    private suspend fun escalateNow(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean,
        h: HelperConfig, fallBack: Boolean = false): Boolean {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        val w = words(h)
        val id = if (reuseBubble && replyId != null) replyId.also { r ->
            markHelper(r, h)
            _state.update { it.copy(helperStatus = w.checking) }
        } else helperBubble(h)
        if (_state.value.speak) VoiceHub.tts.speak(w.checking.trimEnd('…') + ".")
        if (Assistant.locked(app) && !h.locked) {
            localReply(id, w.locked)
            return false
        }
        if (!HelperLink.networkAllowed(app)) {
            networkCard(id)
            return false
        }
        // The key is decrypted and the client made off the main thread.
        val session = when (val made = withContext(Dispatchers.IO) { HelperLink.session(app, s, h, conversation = false) }) {
            is HelperLink.Made.Ok -> made.session
            is HelperLink.Made.Not -> {
                if (fallBack && made.failure in FALLS_BACK) return fellBack(id, h, made.failure)
                noKey(id, h, made.failure)
                return false
            }
        }
        return try {
            runHelper(id, h, session, blocks, HelperPlan.of(h.effort, voiceAsked, HelperRouter.deep(question)), ToolRouter.asked(question), fallBack)
        } finally {
            HelperLink.done(session)
        }
    }

    /** [h] couldn't be reached before it said anything: the reply is Robot's again (Robot answers), with a notice why. */
    private fun fellBack(replyId: String, h: HelperConfig, failure: HelperFailure): Boolean {
        markHelper(replyId, null)
        _state.update { st -> st.copy(helperStatus = null, phase = Phase.THINKING, turns = st.turns.map { if (it.id == replyId) it.copy(text = "", actions = emptyList()) else it }) }
        voice.start(replyId)
        notify(failure.say(who(h)) + " Robot answered instead.")
        RLog.i("helper_fallback", "kind" to h.kind, "failure" to failure)
        return true
    }

    /** The app's name for the Send card ("a screenshot of Signal"), or null when it isn't known or visible to Robot. */
    private fun appLabel(pkg: String?): String? = pkg?.let {
        try {
            val pm = VoiceHub.app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().trim().takeIf { l -> l.isNotEmpty() && l != it }
        } catch (_: Exception) { null }
    }

    /**
     * No session for [h]: no key (none saved; the saved one couldn't be read just now: it is kept, and the next question
     * tries again; or it could never be read again: then it was removed, and Robot says so), a server whose port Test
     * connection hasn't found, or an address Robot won't use.
     */
    private fun noKey(replyId: String, h: HelperConfig, failure: HelperFailure) {
        val text = when {
            failure == HelperFailure.NO_KEY && HelperLink.unavailable(h.id) -> words(h).keyUnavailable
            failure == HelperFailure.NO_KEY && HelperLink.unreadable(h.id) -> words(h).keyUnreadable
            else -> failure.say(who(h))
        }
        failReply(replyId, text)
    }

    /** A question for helper [h] ("ask ChatGPT …", "go get Jarvis", or any question in a helper's conversation). */
    private suspend fun helperTurn(replyId: String, cmd: HelperCommand?, h: HelperConfig?, said: String, attachment: Attachment, sText: String?, image: ByteArray?,
        thumb: Bitmap?, history: List<ChatTurn>, voiceAsked: Boolean) {
        val s = VoiceHub.settings
        val app = VoiceHub.app
        if (h == null) {
            endHelperMode()
            val any = (cmd as? HelperCommand.Ask)?.helper == HelperName.ANY_ID || (cmd as? HelperCommand.Join)?.helper == HelperName.ANY_ID
            // "Ask the helper …" with helpers on but none ready: what the one you'd get still needs (a key, or its app).
            val waiting = s.helper(s.helpDefault)?.takeIf { it.on } ?: s.helpers.firstOrNull { it.on }
            return localReply(replyId, when {
                !any -> GONE
                s.helpers.isEmpty() -> NO_HELPER
                waiting == null -> NONE_ON
                else -> words(waiting).notReady
            })
        }
        val w = words(h)
        val keySaved = keySaved(h)
        if (!h.on) return localReply(replyId, w.off)
        val question = when (cmd) {
            is HelperCommand.Ask -> cmd.question
            is HelperCommand.Join -> cmd.question
            else -> said
        }
        if (HelperSettings.route(h, keySaved) == AskRoute.APP) {
            // Its app (the Claude app, ChatGPT, …): the question, or for "go get …" a short summary of the conversation so far.
            return handOff(replyId, question ?: AppHandOff.summary(history).orEmpty(), h)
        }
        if (h.presetOf.keyRequired && !keySaved) return localReply(replyId, HelperFailure.NO_KEY.say(who(h)))
        if (Assistant.locked(app) && !h.locked) {
            endHelperMode()
            return localReply(replyId, w.locked)
        }
        if (!HelperLink.networkAllowed(app)) return networkCard(replyId)
        if (cmd is HelperCommand.Join) {
            startHelperMode(h, joinedAt = history.size, misheard = cmd.misheard)
            // Nothing goes to the helper until there is a question.
            if (question == null) return localReply(replyId, w.joined)
        }
        val mode = _state.value.helperMode == h.id
        val deep = (cmd as? HelperCommand.Ask)?.deep ?: HelperRouter.deep(said)
        // The screen or a photo goes only after a tap on its card ("Send your screen's text to Jarvis?").
        val extra = if (attachment != Attachment.NONE) attachment else (cmd as? HelperCommand.Ask)?.extra ?: Attachment.NONE
        var shared = Shared()
        if (extra != Attachment.NONE) {
            val screen = if (extra == Attachment.SCREEN) sText ?: screenText else null
            val photo = extra == Attachment.PHOTO
            val img = if (photo) (if (attachment == Attachment.PHOTO) image else lastImage) else screenshot?.let { Images.jpeg(it) }
            if (screen == null && img == null) {
                return localReply(replyId, "Robot doesn't have your screen or a photo this time. Swipe up from the corner over what ${h.name} should see, or add a photo, then ask again.")
            }
            // The card says exactly what goes and shows the picture: the photo, or a screenshot of the app on screen.
            val pic = if (photo) (if (attachment == Attachment.PHOTO) thumb else lastThumb) else screenshot?.let { Images.scaled(it, Images.THUMB_EDGE) }
            val appName = if (!photo && img != null) withContext(Dispatchers.IO) { appLabel(screenPkg) } else null
            val what = if (photo) "the photo" else listOfNotNull(screen?.let { "your screen's text" },
                img?.let { "a screenshot" + (appName?.let { a -> " of $a" } ?: "") }).joinToString(" and ")
            if (!askToShare(replyId, "Send $what to ${h.name}?", screen?.let { ToolCalls.safeTake(it, 600) }, "Send", picture = pic, name = h.name)) {
                return localReply(replyId, w.nothingSent)
            }
            shared = Shared(screenText = screen, image = img)
        }
        // The key is decrypted and the client made off the main thread.
        val made = if (mode) helperSession?.let { HelperLink.Made.Ok(it) }
            ?: withContext(Dispatchers.IO) { HelperLink.session(app, s, h, conversation = true) }.also { if (it is HelperLink.Made.Ok) helperSession = it.session }
            else withContext(Dispatchers.IO) { HelperLink.session(app, s, h, conversation = false) }
        val session = when (made) {
            is HelperLink.Made.Ok -> made.session
            is HelperLink.Made.Not -> return noKey(replyId, h, made.failure)
        }
        val now = ZonedDateTime.now()
        try {
            val blocks = when {
                !mode -> HelperContext.oneShot(question ?: said, now, userRegion, shared)
                session.fresh -> {
                    // The helper joins the conversation: a personal one only after "Include" ("Start fresh" sends none of it),
                    // or by itself to a helper you trust that you called by its name (never with a screen or a photo in it).
                    val before = history.take(helperJoinedAt.coerceIn(0, history.size))
                    val personal = HelperContext.personal(before)
                    // A conversation from a word that only sounded like the name asks too, personal or not.
                    val include = HelperTrust.joinWithoutCard(h, personal, helperMisheard, MemoryPanel.toTrusted()) ||
                        HelperContext.includeQuestion(before, helperMisheard)?.let { q ->
                            askToShare(replyId, q, null, "Include", "Start fresh", detail = "Start fresh: ${h.name} sees only what you ask from now on.",
                                personal = personal.isNotEmpty(), name = h.name)
                        } ?: false
                    HelperContext.join(before, question ?: said, now, userRegion, shared, include, helperMisheard)
                }
                else -> HelperContext.next(question ?: said, shared)
            }
            runHelper(replyId, h, session, blocks, HelperPlan.of(h.effort, voiceAsked, deep), ToolRouter.asked(said))
        } finally {
            if (!mode) HelperLink.done(session)
        }
        if (mode) touchHelperMode()
    }

    /**
     * One turn of helper [h]'s into the reply [replyId]: streamed into the bubble and spoken in the helper's voice, its
     * calls as cards (always), then the cost line, today's spend, and Robot's own conversation rebuilt with it.
     * [fallBack]: see [escalateNow]; true when the question went back to Robot.
     */
    private suspend fun runHelper(replyId: String, h: HelperConfig, session: HelperSession, blocks: List<HelperBlock>, plan: HelperPlan, asked: Set<ToolName>,
        fallBack: Boolean = false): Boolean {
        val w = words(h)
        val s = VoiceHub.settings
        markHelper(replyId, h)
        _state.update { st -> st.copy(helperStatus = st.helperStatus ?: w.thinking) }
        val t0 = System.currentTimeMillis()
        var first = -1L
        // As for Robot's replies: each round's start is checked for a copied clock ("[Now 14:05]"), never shown or said.
        var echo = ClockEcho()
        fun stream(text: String) {
            if (text.isEmpty()) return
            appendText(replyId, text)
            voice.feed(replyId, text, _state.value.speak).forEach { speakSentence(it, byHelper = true, voice = h.voice) }
        }
        val heard = object : HelperTurnListener {
            override fun text(delta: String) {
                if (first < 0) first = System.currentTimeMillis() - t0
                stream(echo.feed(delta))
            }

            override fun declined() {
                // Robot's own words, in Robot's voice and Robot's bubble, in place of what was shown.
                voice.mute()
                VoiceHub.stopVoice(TtsStop.OTHER)
                setText(replyId, w.declined)
                markHelper(replyId, null)
                if (_state.value.speak) VoiceHub.tts.speak(w.declined)
            }

            override fun thinking() {
                _state.update { st -> if (st.phase == Phase.THINKING) st.copy(helperStatus = w.thinking) else st }
            }

            override fun loading() {
                // Your server is loading the model (nothing for a few seconds): said on screen, so the wait makes sense.
                _state.update { st -> if (st.phase == Phase.THINKING) st.copy(helperStatus = w.loading) else st }
            }

            override fun spent(dollars: Double) {
                // Into today's spend as soon as it is known, so a crash mid-answer loses none of it.
                scope.launch(Dispatchers.IO) { HelperSpendStore.add(VoiceHub.app, h.id, dollars) }
            }
        }
        val turn = HelperTurn(session, blocks, plan, heard) { it.say(who(h)) }
        // One echo filter per round: the question's answer, then the text after each tool round.
        val rounds = object : TurnModel {
            override suspend fun ask(): ModelOutcome = turn.ask().also { stream(echo.flush()) }
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
                echo = ClockEcho()
                return turn.answer(results).also { stream(echo.flush()) }
            }
            override fun close() = turn.close()
            override fun abandon(results: List<Pair<String, Map<String, Any?>>>) = turn.abandon(results)
            override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = turn.stopped(results)
        }
        val result = try {
            TurnRunner(rounds, PanelActions, PanelView(replyId, h.name), slot.confirmations, untrusted = true, remote = true, asked = asked,
                trusted = HelperTrust.reads(s, h), offered = session.offered, messages = PanelMessages.taint(chat), newId = ::newId).run()
        } finally {
            val r = session.lastReport
            RLog.i("helper_turn", "kind" to h.kind, "model" to ((session as? AnthropicSession)?.model ?: RLog.Code("other")), "effort" to plan.effort,
                "firstMs" to first, "ms" to (System.currentTimeMillis() - t0), "requests" to r.requests, "searches" to r.searches,
                "searchErrors" to r.searchErrors, "cache_read_input_tokens" to r.usage.cacheRead)
        }
        _state.update { it.copy(helperStatus = null) }
        val report = session.lastReport
        result.failure?.let {
            // A server that can't be reached, before it said or did anything: Robot answers itself (Automatic only).
            val f = turn.failure
            if (fallBack && f != null && f in FALLS_BACK && first < 0 && _state.value.turns.firstOrNull { t -> t.id == replyId }?.actions.isNullOrEmpty()) {
                return fellBack(replyId, h, f)
            }
            // Robot's copy of the conversation can't make a valid request any more: the helper's conversation starts over.
            if (f == HelperFailure.RESET) endHelperMode()
            // Said aloud, in Robot's voice ("No internet", "Jarvis is busy, try again in a minute").
            if (_state.value.speak) VoiceHub.tts.speak(it)
            failReply(replyId, it)
            return false
        }
        val cost = when {
            !s.helperShowCost || report.requests == 0 -> null
            !report.costKnown -> "cost unknown"
            else -> Pricing.line(report.dollars, free = h.price is HelperPrice.Free)
        }
        _state.update { st ->
            st.copy(turns = st.turns.map {
                if (it.id == replyId) it.copy(sources = report.domains.takeIf { report.searches > 0 } ?: emptyList(), cost = cost) else it
            })
        }
        // Cut off before it said anything (its thinking used the room): say so rather than leave an empty bubble.
        if (result.reason == DoneReason.LENGTH && _state.value.turns.firstOrNull { it.id == replyId }?.let { it.text.isBlank() && it.actions.isEmpty() } == true) {
            markHelper(replyId, null)
            sayInto(replyId, w.cutEmpty)
        }
        finishReply(replyId, result.reason ?: DoneReason.END, costMicros = if (report.costKnown) Pricing.micros(report.dollars) else 0)
        // The helper's answer joins Robot's own conversation: :brain rebuilds it from the history next time.
        closeLiveConversation()
        if (result.launchedApp) leaveForApp()
        return false
    }

    /**
     * "Send …to Jarvis?" with Send and Cancel (or Include and Start fresh): true only after the tap. Said too when replies
     * are spoken, so you know a tap is needed. [name]: the helper's.
     */
    private suspend fun askToShare(replyId: String, summary: String, body: String?, confirm: String, cancel: String = "Cancel",
        detail: String? = null, picture: Bitmap? = null, personal: Boolean = true, name: String = "Claude"): Boolean {
        val id = newId()
        val view = PanelView(replyId, name)
        upsertAction(replyId, UiAction(id, ToolName.ASK_HELPER, summary, ActionStatus.PENDING, detail ?: "Nothing goes to $name until you tap $confirm.", body = body,
            confirm = confirm, cancel = cancel, picture = picture, personal = personal))
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
            view.card(Card(id, ToolName.ASK_HELPER, summary, ActionStatus.DECLINED, null))
            throw e
        }
        view.card(Card(id, ToolName.ASK_HELPER, summary, if (pick >= 0) ActionStatus.DONE else ActionStatus.DECLINED, if (pick >= 0) "Shared with $name." else "Not shared."))
        view.phase(TurnPhase.THINKING)
        return pick >= 0
    }

    /** GrapheneOS's Network toggle is off for Robot: a card that says so (no helper can be reached at all), said too. */
    private fun networkCard(replyId: String) {
        PanelView(replyId).card(Card(newId(), ToolName.ASK_HELPER, "Robot can't use the network", ActionStatus.FAILED, HelperFailure.NO_NETWORK_PERMISSION.text))
        if (_state.value.speak) VoiceHub.tts.speak(HelperFailure.NO_NETWORK_PERMISSION.text)
        _state.update { it.copy(helperStatus = null) }
        markHelper(replyId, null)
        finishReply(replyId, DoneReason.END)
    }

    /**
     * The app route (any helper with an app, 1.0.0): the card "Open the ChatGPT app with: «…»", then [h]'s app opens with
     * the question and the panel steps aside (the launchedApp path). Robot never hears the answer; with the Claude app you
     * can read it back with the corner swipe.
     */
    private suspend fun handOff(replyId: String, text: String, h: HelperConfig) {
        lastToApp = text.takeIf { it.isNotBlank() }
        markHelper(replyId, null)
        val scripted = object : TurnModel {
            override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(RawToolCall(ToolName.ASK_HELPER.id, mapOf("question" to text, "helper" to h.id))))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
            override fun close() {}
        }
        val r = TurnRunner(scripted, PanelActions, PanelView(replyId), slot.confirmations, untrusted = false, messages = PanelMessages.taint(chat),
            newId = ::newId).runHandOff()
        finishReply(replyId, DoneReason.END)
        if (r.launchedApp) leaveForApp()
    }

    /**
     * Conversation mode by voice (0.5.0): start transcribing or the interpreter (the session starts from here, while the
     * panel is on screen, then its screen opens and the panel goes), switch the running one between them, stop, pause or
     * resume it, or summarize it (it stops, and the summary screen opens: unlocked only).
     */
    private suspend fun conversationCommand(replyId: String, c: ConversationCommand) {
        val ctx = VoiceHub.app
        when (c) {
            is ConversationCommand.Start -> when {
                ConversationSession.running -> {
                    val now = ConversationSession.state.value.mode
                    if (now != c.mode) ConversationSession.setMode(c.mode)
                    localReply(replyId, when {
                        now == c.mode && c.mode == ConvMode.INTERPRET -> "Robot is already translating."
                        now == c.mode -> "Robot is already transcribing."
                        c.mode == ConvMode.INTERPRET -> "Translating now: the interpreter is on the screen."
                        else -> "Back to the transcript."
                    })
                    openConversation(ConversationEntry.OPEN, c.mode)
                }
                !ConversationLock.mayStart(Assistant.locked(ctx), VoiceHub.settings) ->
                    localReply(replyId, "Unlock your phone to start (Settings › Conversation › Start from the lock screen is off).",
                        spoken = "Unlock your phone to start: starting from the lock screen is off in Robot's conversation settings.")
                else -> {
                    // The first time, the transcript screen asks you to tell people first; after that it starts from here
                    // (the panel is on screen), or from the transcript screen if Android won't let it start here.
                    val consented = !ConsentRecord.needed(Transcripts.consent(ctx), VoiceHub.settings)
                    val started = consented && ConversationService.start(ctx, ConvEntry.VOICE, mode = c.mode)
                    val what = if (c.mode == ConvMode.INTERPRET) "Translating" else "Transcribing"
                    localReply(replyId, when {
                        started -> "$what. ${ConsentRecord.LINE}"
                        consented -> "Opening the screen to start."
                        else -> "First, a word about telling the people you're with: it's on the screen."
                    })
                    // On the lock screen the screen opens as a start, so it may show there (it doesn't start a second one).
                    openConversation(if (started && !Assistant.locked(ctx)) ConversationEntry.OPEN else ConversationEntry.START, c.mode)
                }
            }
            ConversationCommand.Stop -> {
                ConversationSession.stop(ConvStop.VOICE)
                localReply(replyId, "Stopped. The transcript is saved in Robot › Transcripts.", spoken = "Stopped. The transcript is saved in Robot's transcripts.")
            }
            ConversationCommand.Pause -> {
                ConversationSession.userPause()
                localReply(replyId, "Paused. Say “resume transcribing”, or tap Resume in the notification.")
            }
            ConversationCommand.Resume -> {
                ConversationSession.userResume()
                localReply(replyId, "Going on.")
            }
            ConversationCommand.Summarize -> {
                // A summary needs the phone unlocked (docs/conversation-mode.md 1.7): the session stops, the screen opens.
                val id = ConversationSession.state.value.id
                ConversationSession.stop(ConvStop.VOICE)
                if (Assistant.locked(ctx) || id == null) {
                    localReply(replyId, "Stopped. Unlock your phone, then open the transcript in Robot › Transcripts to summarize it.",
                        spoken = "Stopped. Unlock your phone, then open the transcript in Robot's transcripts to summarize it.")
                } else {
                    localReply(replyId, "Stopped. The summary screen is opening.")
                    if (host?.launch(Transcripts.summaryIntent(ctx, id)) == true) leaveForApp()
                }
            }
        }
    }

    /**
     * 0.7, a memory command (core MemoryRouting), before the model and never a helper's: the vault changes, the card under
     * the reply (Remembered or Forgotten with Undo, Open Memory), and Robot's own words, said back; several items that fit
     * "forget …" make a pick card first. On the lock screen it only says to unlock, unless memory is allowed there.
     */
    private suspend fun memoryCommand(replyId: String, route: MemoryRoute, spoken: Boolean) {
        // 0.7, memory and messages: your "remember that …" in a conversation that holds a message is never saved at once.
        // It goes through the turn runner as one remember call of your own words: a card that shows exactly the words kept,
        // answered only by a tap, kept as content (core MessageQuarantine.rememberAfterMessages); a code is refused there.
        when (val after = MessageQuarantine.rememberAfterMessages(route, PanelMessages.taint(chat))) {
            null -> {}
            is MessageQuarantine.AfterMessages.Refused -> return localReply(replyId, after.text)
            is MessageQuarantine.AfterMessages.Card -> {
                setText(replyId, if (after.fr) MessageQuarantine.CHECK_AND_TAP_FR else MessageQuarantine.CHECK_AND_TAP)
                val result = TurnRunner(DirectCall(after.call), PanelActions, PanelView(replyId), slot.confirmations, untrusted = true,
                    asked = setOf(ToolName.REMEMBER), messages = PanelMessages.taint(chat), origin = ActionOrigin.USER_WORDS, newId = ::newId).run()
                result.failure?.let { return failReply(replyId, it) }
                return finishReply(replyId, result.reason ?: DoneReason.END)
            }
        }
        var a = withContext(Dispatchers.IO) { MemoryPanel.command(route, Assistant.locked(VoiceHub.app), spoken) }
        if (a.pick.isNotEmpty()) {
            val id = newId()
            upsertAction(replyId, UiAction(id, ToolName.FORGET, app.heyrobot.core.memory.MemoryReplies.CARD_WHICH, ActionStatus.PENDING, null,
                choices = MemoryPanel.pickLabels(a.pick)))
            sayInto(replyId, a.text)
            _state.update { it.copy(phase = Phase.CONFIRMING) }
            cardWaiting()
            val pick = try {
                slot.confirmations.ask(id)
            } finally {
                stopCardListening()
                _state.value.turns.firstOrNull { it.id == replyId }?.actions?.firstOrNull { it.id == id }?.let { upsertAction(replyId, it.copy(status = ActionStatus.DECLINED)) }
            }
            _state.update { it.copy(phase = Phase.THINKING) }
            val chosen = a.pick.getOrNull(if (pick >= app.heyrobot.core.Confirmations.REMEMBER) pick - app.heyrobot.core.Confirmations.REMEMBER else pick)
            _state.update { st -> st.copy(turns = st.turns.map { t -> if (t.id != replyId) t else t.copy(text = "", actions = t.actions.filter { it.id != id }) }) }
            a = withContext(Dispatchers.IO) { MemoryPanel.forgetPicked(chosen, a.fr) }
        }
        a.card?.let { upsertAction(replyId, it.copy(id = newId())) }
        MemoryPanel.mark(replyId, a.ids)
        localReply(replyId, a.text)
    }

    /** Settings › Memory changed something (Cmd.MEMORY_CHANGED): what the panel holds goes, and the live conversation starts again. */
    fun memoryChanged() {
        MemoryPanel.changedElsewhere()
        if (slot.running) closeConvAfterTurn = true else closeLiveConversation()
    }

    /**
     * 0.7: a feedback phrase opens Settings › About › Send feedback in the app with its kind, and what you said after it
     * starts your text (handed over in a file of Robot's own). The panel never sends anything: the page opens, you choose.
     */
    private suspend fun feedbackCommand(replyId: String, c: FeedbackCommand) {
        val ctx = VoiceHub.app
        val locked = Assistant.locked(ctx)
        VoiceLog.add(VoiceEvent.FEEDBACK_ASKED, c.kind, if (c.text != null) 1 else 0, if (locked) 1 else 0)
        if (locked) return localReply(replyId, "Unlock your phone, then say it again, or open Robot › Settings › About › Send feedback.",
            spoken = "Unlock your phone, then say it again.")
        withContext(Dispatchers.IO) { FeedbackFiles.prefill(ctx, c.kind, c.text) }
        localReply(replyId, "Opening Send feedback. Nothing is sent until you choose an app.")
        if (host?.launch(FeedbackFiles.openIntent(ctx, c.kind)) == true) leaveForApp()
    }

    /**
     * 0.7.0, "free up memory" ([fr]: asked in French): what Android reports free, what unloading Robot's model gives, the
     * apps used lately that are likely still in memory (Usage access), said; then free_memory's card to pick from, as the
     * model's call would make it (ToolExecutor): unload the model, or open an app's info where you tap Force stop, or turn
     * on Usage access. Robot never stops an app itself. On the lock screen only what is free, nothing about your apps.
     */
    private suspend fun freeMemoryCommand(replyId: String, fr: Boolean) {
        val ctx = VoiceHub.app
        val locked = Assistant.locked(ctx)
        val brain = BrainKeeper.state.value
        val loaded = brain.readiness == BrainKeeper.Readiness.READY || brain.readiness == BrainKeeper.Readiness.LOADING
        val modelBytes = brain.model?.memoryBytes?.takeIf { it > 0 } ?: app.heyrobot.core.PhoneStatus.ROBOT_MODEL_BYTES
        val (mem, recent) = withContext(Dispatchers.IO) { PhoneInfo.memory(ctx) to if (locked) emptyList() else PhoneInfo.recentInMemory(ctx) }
        if (locked) return localReply(replyId, FreeUp.memoryNow(mem, fr) + " " + FreeUp.unlockToFree(fr))
        sayInto(replyId, FreeUp.advice(mem, if (loaded) modelBytes else null, recent, System.currentTimeMillis(), fr))
        val result = TurnRunner(DirectCall(RawToolCall(ToolName.FREE_MEMORY.id, emptyMap())), PanelActions, PanelView(replyId), slot.confirmations,
            untrusted = TurnRules.untrusted(chat), asked = setOf(ToolName.FREE_MEMORY), messages = PanelMessages.taint(chat), newId = ::newId).run()
        result.failure?.let { return failReply(replyId, it) }
        finishReply(replyId, result.reason ?: DoneReason.END)
    }

    private enum class ConversationEntry { START, OPEN }

    /** The transcript screen (voice process), then the panel goes so nothing more is said to it. */
    private suspend fun openConversation(how: ConversationEntry, mode: ConvMode = ConvMode.TRANSCRIBE) {
        val ctx = VoiceHub.app
        val i = if (how == ConversationEntry.START) Transcripts.startIntent(ctx, ConvEntry.VOICE.name, mode.name) else Transcripts.openIntent(ctx)
        if (host?.launch(i) == true) leaveForApp()
    }

    /**
     * Robot's own words as the reply (no model), in Robot's bubble and voice; [spoken] is the same as the voice says it,
     * when it differs (no "›").
     */
    private fun localReply(replyId: String, text: String, spoken: String = text) {
        markHelper(replyId, null)
        _state.update { it.copy(helperStatus = null) }
        setText(replyId, text)
        voice.feed(replyId, spoken, _state.value.speak).forEach { speakSentence(it) }
        finishReply(replyId, DoneReason.END)
    }

    // ------------------------------------------------------------------ messages (0.7, PanelMessages)

    /** Listen once when Robot stops talking (after a conversation read aloud, or a reply card), or [now] (a reply's 3 seconds). */
    private var msgListenAfterSpeech = false

    internal fun messagesListen(now: Boolean) {
        if (!now) msgListenAfterSpeech = true
        else if (shown && !VoiceHub.tts.isSpeaking && _state.value.phase == Phase.IDLE) startListening()
    }

    /** 0.8: an announced message's session ended ("no", or nothing more to say): the panel it opened goes. */
    internal fun hideAfterAnnouncement() {
        if (shown) host?.hidePanel()
    }

    /** Ends [replyId] with [text] shown; PanelMessages speaks it in each piece's own voice. */
    internal fun messagesReply(replyId: String, text: String) {
        markHelper(replyId, null)
        _state.update { it.copy(helperStatus = null) }
        setText(replyId, text)
        finishReply(replyId, DoneReason.END)
    }

    /** The reply card under [replyId] (null: none). */
    internal fun messagesCard(replyId: String, card: app.heyrobot.ui.common.ReplyCardView?) =
        _state.update { st -> st.copy(turns = st.turns.map { if (it.id == replyId) it.copy(reply = card) else it }) }

    /** What History keeps of [replyId] changed ("Sent your reply to Mom"). */
    internal fun messagesKept(replyId: String, kept: String) {
        val i = chat.indexOfFirst { it.id == replyId }
        if (i < 0) return
        chat[i] = chat[i].copy(messages = true, kept = kept)
        persist()
    }

    /**
     * Your messages' words leave this process (PanelMessages.dropText: the panel hid, or 2 minutes without a question):
     * every turn that held some (a conversation read, a summary, a translation, an answer after them, words heard back)
     * shows and keeps its placeholder, as a conversation continued from History does, and the model gets that, framed.
     */
    internal fun messagesDropText() {
        val dropped = HashMap<String, String>()
        for (i in chat.indices) {
            val t = chat[i]
            if (!t.messages) continue
            val k = t.kept ?: app.heyrobot.core.messages.MessageReading.NOT_KEPT
            if (t.text != k) {
                chat[i] = t.copy(text = k)
                dropped[t.id] = k
            }
        }
        if (dropped.isEmpty()) return
        _state.update { st -> st.copy(turns = st.turns.map { u -> dropped[u.id]?.let { u.copy(text = it) } ?: u }) }
    }

    /** "Open chat": the app opens over the panel with your reply as a draft. */
    internal fun messagesLaunch(i: Intent): Boolean {
        val ok = host?.launch(i) == true
        if (ok) scope.launch { leaveForApp() }
        return ok
    }

    /** A card button (PanelActions.reply). */
    fun reply(replyId: String, op: app.heyrobot.ui.common.ReplyOp) = PanelMessages.onCard(replyId, op)

    // ------------------------------------------------------------------ a helper's conversation ("go get Jarvis")

    /** Where the helper's conversation started: what came before is what it joins. */
    private var helperJoinedAt = 0
    /** It came from a word that only sounded like the name: what came before goes only after a tap. */
    private var helperMisheard = false

    /** [h]'s conversation starts (another helper's ends: one at a time). */
    private fun startHelperMode(h: HelperConfig, joinedAt: Int, misheard: Boolean = false) {
        if (_state.value.helperMode != h.id) {
            HelperLink.done(helperSession)
            helperSession = null
            helperJoinedAt = joinedAt
            helperMisheard = misheard
        }
        _state.update { it.copy(helperMode = h.id, helperName = h.name, helperHome = h.home) }
        touchHelperMode()
        RLog.i("helper_mode", "on" to true, "kind" to h.kind)
    }

    /** A turn in the helper's conversation: the 10 minutes start again. */
    private fun touchHelperMode() {
        helperLast = System.currentTimeMillis()
        helperIdle?.cancel()
        helperIdle = scope.launch {
            delay(HELPER_IDLE_MS)
            if (!slot.running) endHelperMode()
        }
    }

    private fun expireHelperMode() {
        if (_state.value.helperMode != null && System.currentTimeMillis() - helperLast > HELPER_IDLE_MS) endHelperMode()
    }

    /** "Back to Robot" on the chip, "thanks Jarvis", the panel closing, 10 minutes without a turn, or the lock screen. */
    fun endHelperMode() {
        helperIdle?.cancel()
        helperIdle = null
        if (helperSession == null && _state.value.helperMode == null) return
        HelperLink.done(helperSession)
        helperSession = null
        _state.update { it.copy(helperMode = null, helperName = null, helperHome = false) }
        RLog.i("helper_mode", "on" to false)
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
        val turn = ChatTurn(id, TurnRole.ASSISTANT, answer, System.currentTimeMillis(), fromHelper = true, readBack = true)
        chat += turn
        // Screen content: saved only when the person keeps the conversation, as for any screen question.
        val keep = if (keptByUser) st.keep else false
        _state.update { it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, answer, fromHelper = true, readBack = true), keep = keep, claudeScreen = false) }
        voice.start(id)
        val v = VoiceHub.settings.claude?.voice
        voice.feed(id, answer, st.speak).forEach { speakSentence(it, byHelper = true, voice = v) }
        voice.finish(id, cancelled = false, speak = st.speak)?.let { speakSentence(it, byHelper = true, voice = v) }
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
            messages = PanelMessages.taint(chat), newId = ::newId).run()
        result.failure?.let { failReply(replyId, it); return true }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) leaveForApp()
        return true
    }

    // ------------------------------------------------------------------ web search (0.9, core web; design Part A)

    /**
     * "Search the web for …": web_search with your words as they are, gated and carded as Gemma's call would be. 1.0: a
     * search in your browser instead when your words name one, or while Robot's own web search is off (BrowserSearchTool).
     */
    private suspend fun directWeb(replyId: String, route: app.heyrobot.core.web.WebRoute, question: String, history: List<ChatTurn>, untrusted: Boolean) {
        RLog.i("web_direct")
        val call = withContext(Dispatchers.IO) { BrowserSearchTool.directCall(VoiceHub.app, route) } ?: RawToolCall(ToolName.WEB_SEARCH.id, mapOf("query" to route.query))
        // 0.7's quarantine: after a message, even your own search is a card only a tap answers (core MessageQuarantine).
        val result = TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOfNotNull(ToolName.of(call.name)),
            messages = PanelMessages.taint(chat), newId = ::newId).run()
        result.failure?.let { return failReply(replyId, it) }
        result.web?.let { return webAnswer(replyId, question, history, it) }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) leaveForApp()
    }

    /** web_search's run: on its own thread with one deadline (the request, then the pages); Stop disconnects it. */
    private suspend fun webRun(action: Action.WebSearch): Executed {
        val abort = Abort(WebSearch.DEADLINE_MS)
        return try {
            OnlineRun.run(abort, WebSearch.DEADLINE_MS) { a -> executor(a).run(action) } ?: WebOutcome.Failed(null, WebFail.TIMEOUT).let { o ->
                VoiceLog.add(VoiceEvent.WEB_FAILED, WebFail.TIMEOUT.id)
                Executed(ActionStatus.FAILED, o.text, web = o)
            }
        } finally {
            _state.update { it.copy(helperStatus = null) }
        }
    }

    /** What the search is doing ("Searching the web…", "Reading 3 pages…"): the panel's status, and said once each. */
    private fun webStep(step: WebStep, lang: String) {
        VoiceHub.main.post {
            val shown = when (step) {
                WebStep.Searching -> WebStatus.searching(lang)
                is WebStep.Reading -> WebStatus.reading(step.pages, lang, step.done.takeIf { it > 0 })
            }
            _state.update { if (it.busy) it.copy(helperStatus = shown) else it }
            val say = when (step) {
                WebStep.Searching -> WebStatus.searching(lang)
                is WebStep.Reading -> if (step.done == 0) WebStatus.reading(step.pages, lang) else null
            }
            val id = _state.value.turns.lastOrNull { it.streaming }?.id ?: return@post
            if (say != null) voice.say(id, say, _state.value.speak)?.let(::speakSentence)
        }
    }

    /**
     * Where this conversation's words came from, for QueryCheck (core web WebQueryContext): your words, content Robot
     * didn't write, and personal content (with what read tools' cards showed).
     */
    private fun webContext(): QueryContext {
        val bodies = _state.value.turns.flatMap { t -> t.actions.filter { it.content && it.tool != ToolName.WEB_SEARCH }.mapNotNull { it.body } }
        val screen = screenText?.takeIf { chat.any { it.attachment == Attachment.SCREEN } }
        // 0.7's memory: what the model was given from it never goes to a search service (QueryCheck refuses it).
        val memory = MemoryPanel.forWebCheck(chat.flatMap { it.memoryIds })
        val reads = synchronized(readTexts) { readTexts.toList() }
        return WebQueryContext.of(chat.toList(), turnSaid.orEmpty(), screen, bodies + reads, memory, fresh = readsAllKnown)
    }

    /** The search ran: the answer from what it found (WebReply), streamed and spoken without its [n] marks, then its card. */
    private suspend fun webAnswer(replyId: String, question: String, history: List<ChatTurn>, outcome: WebOutcome) {
        _state.update { it.copy(helperStatus = null) }
        val found = when (outcome) {
            is WebOutcome.Failed -> {
                if (outcome.why == WebFail.STOPPED) return finishReply(replyId, DoneReason.CANCELLED)
                sayInto(replyId, outcome.text)
                return finishReply(replyId, DoneReason.END)
            }
            is WebOutcome.Found -> outcome
        }
        val r = WebReply.answer(VoiceHub.app, question, history, found, _state.value.speak, VoiceHub.settings.helperShowCost, onRestart = { setText(replyId, "") }) { delta ->
            appendText(replyId, delta)
            voice.feed(replyId, delta, _state.value.speak).forEach { speakSentence(Citations.forSpeech(it)) }
        }
        val a = when (r) {
            is WebReply.Result.Answered -> r
            WebReply.Result.Cancelled -> return finishReply(replyId, DoneReason.CANCELLED)
            WebReply.Result.Failed -> return failReply(replyId, WebReply.NO_ANSWER)
        }
        // The last words, without their marks; the search's card becomes its saved line.
        voice.finish(replyId, cancelled = !a.complete, speak = _state.value.speak)?.let { speakSentence(Citations.forSpeech(it)) }
        _state.update { st ->
            st.copy(turns = st.turns.map { t ->
                if (t.id != replyId) t else t.copy(text = a.text, web = a.card, actions = t.actions.map { x ->
                    if (x.tool == ToolName.WEB_SEARCH && x.status == ActionStatus.DONE) x.copy(summary = a.saved, detail = a.card.readLine, body = null, content = true) else x
                })
            })
        }
        finishReply(replyId, if (a.complete) DoneReason.END else DoneReason.LENGTH)
    }

    /**
     * A web answer's source tapped: it opens in the browser (only the card's own links, or Wikipedia's licence), as an app
     * opens; on the lock screen, unlock first.
     */
    fun openSource(replyId: String, url: String) {
        val card = _state.value.turns.firstOrNull { it.id == replyId }?.web ?: return
        if (url !in (card.cited + card.alsoFound).map { it.url } + WikipediaWire.LICENCE_URL) return
        if (Assistant.locked(VoiceHub.app)) return notify("Unlock your phone, then open the page.")
        val h = host ?: return
        VoiceHub.stopVoice(TtsStop.OTHER)
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (h.launch(i)) {
            hidingFor = "action"
            h.hidePanel()
        } else notify("No browser opened the page.")
    }

    /**
     * 0.9: your script named in your own words ("run backup"): termux_script prepared, gated and carded as Gemma's call
     * would be, without the model; what it printed is said in a sentence (two short lines at most).
     */
    private suspend fun termuxScript(replyId: String, m: app.heyrobot.core.termux.ScriptMatch, untrusted: Boolean) {
        setText(replyId, "Your script “${m.name}”.")
        val call = RawToolCall(ToolName.TERMUX_SCRIPT.id, mapOf("name" to m.name, "args" to m.args))
        val said = object : TurnModel {
            override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(call))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
                results.firstOrNull()?.second?.let(TermuxTool::spokenResult)?.let { streamText(replyId, it) }
                return ModelOutcome.Done(DoneReason.END)
            }
            override fun close() {}
        }
        // 0.7's quarantine: after a message, even your own script is a card only a tap answers (core MessageQuarantine).
        val result = TurnRunner(said, PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOf(ToolName.TERMUX_SCRIPT),
            messages = PanelMessages.taint(chat), newId = ::newId).run()
        result.failure?.let { return failReply(replyId, it) }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) leaveForApp()
    }

    // 0.9, Termux cards (TermuxPanel): found by id, changed in place, drawn again when a run's state changes.
    internal fun cardById(id: String): UiAction? = _state.value.turns.firstNotNullOfOrNull { t -> t.actions.firstOrNull { it.id == id } }

    internal fun updateCard(id: String, change: (UiAction) -> UiAction) =
        _state.update { st -> st.copy(turns = st.turns.map { t -> t.copy(actions = t.actions.map { if (it.id == id) change(it) else it }) }) }

    internal fun termuxChanged(key: String) =
        _state.update { st -> st.copy(turns = st.turns.map { t -> t.copy(actions = t.actions.map { if (it.termux?.key == key) TermuxPanel.redress(it) else it }) }) }

    /** A turn whose one action is already known: no model asked, nothing to answer after the action. */
    private class DirectCall(private val call: RawToolCall) : TurnModel {
        /** The panel's own call from your words (never a model's): it may be the call tool, which no model is told about. */
        override val appCalls: Boolean get() = true
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
        if (Assistant.locked(VoiceHub.app)) return notify(if (page) "Unlock your phone, then open Settings." else "Unlock your phone, then open your messaging app.")
        val h = host ?: return
        val tries = if (page) SettingsIntents.of(VoiceHub.app, pkg)
        else listOfNotNull(VoiceHub.app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (tries.isEmpty()) return notify(if (page) "Settings didn't open." else "Your messaging app isn't installed.")
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

        /**
         * Stopped while its cards waited (Stop, the panel closed): no results will come, but :brain's conversation would
         * go on waiting for them, and that wait keeps conversation mode's translations and summaries off the model for
         * up to 90 s (BrainScheduler.TOOL_HOLD_MS). It goes now; the next question rebuilds it from the history, as it
         * would have anyway.
         */
        override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = close()
    }

    /**
     * The phone side of a turn: the approval inputs and ToolExecutor (contacts, calendar and files off the main thread).
     * An online tool (the weather) runs through [OnlineRun]: Stop ends it at once, and so does its deadline.
     */
    private object PanelActions : TurnActions {
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override val settings: RobotSettings get() = VoiceHub.settings
        override suspend fun prepare(action: Action): Prepared = withContext(Dispatchers.IO) { MemoryPanel.prepare(action, locked) ?: executor().prepare(action) }
        // 1.0 (injection-termux-policy-unwired): a Termux call is prepared by TermuxTool (TermuxPolicy, the lock, the signer, the
        // permission), into its card or a refusal; everything else as above.
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            TermuxTool.prepareTurn(VoiceHub.app, action, untrusted, remote, turnSaid) { prepare(it) }
        // 1.0 (injection-querycheck-notes-personal): what a read tool returned to the model (a note's text, not just its title)
        // is kept for QueryCheck's personal words while this conversation lives.
        override suspend fun run(action: Action): Executed = runAction(action).also(::noteRead)

        private suspend fun runAction(action: Action): Executed {
            // 0.7: remember and forget (core memory/); what the conversation holds decides where a remembered item came from.
            if (action.tool.group == ToolGroup.MEMORY) return withContext(Dispatchers.IO) {
                // (The last: a message is in the conversation, so only your own "remember that …" got here, after its tap.)
                MemoryPanel.run(action, spokenTurn, app.heyrobot.core.memory.MemoryTaint.fromContent(TurnRules.untrusted(chat),
                    _state.value.turns.lastOrNull()?.actions?.any { it.content } == true, turnMemContent), PanelMessages.taint(chat).active)
            } ?: Executed(ActionStatus.FAILED, "Something went wrong.")
            // 0.9: a Termux run waits for its result (Stop ends its process tree), off the main thread.
            if (action.tool.termux) return withContext(Dispatchers.IO) { TermuxTool.run(VoiceHub.app, action) { i -> host?.launch(i) ?: false } }
            if (!action.online) return withContext(Dispatchers.IO) { executor().run(action) }
            if (action is Action.WebSearch) return webRun(action)
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

    /** Everything read tools returned to the model in this conversation (QueryCheck), and whether that is all of it. */
    private val readTexts = ArrayList<String>()
    @Volatile private var readsAllKnown = true

    private fun noteRead(r: Executed) {
        if (r.results.isNullOrEmpty() || r.web != null) return
        synchronized(readTexts) {
            readTexts += app.heyrobot.core.web.readTexts(r.results)
            r.body?.let { readTexts += it }
        }
    }

    private fun forgetReads(allKnown: Boolean) {
        synchronized(readTexts) { readTexts.clear() }
        readsAllKnown = allKnown
    }

    private fun executor(abort: Abort = Abort()) = ToolExecutor(VoiceHub.app, VoiceHub.settings, abort, toolRoom, turnSaid, ::webContext, ::webStep) { i -> host?.launch(i) ?: false }

    /** What you typed or said this turn (never screen text): "the nearest" in your own words counts for the maps tools. */
    @Volatile private var turnSaid: String? = null

    /** This turn went with a memory item from content (MemoryPanel.forTurn's content): what it remembers is content too. */
    @Volatile private var turnMemContent = false

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
    /** [helper]: the helper whose turn it is, by name, for "Share with Claude" (Claude: the name used before 0.6.0). */
    private class PanelView(private val replyId: String, private val helper: String = "Claude") : TurnView {
        override fun phase(p: TurnPhase) {
            if (!current(replyId)) return
            _state.update { it.copy(phase = if (p == TurnPhase.CONFIRMING) Phase.CONFIRMING else Phase.THINKING) }
            if (p == TurnPhase.CONFIRMING) cardWaiting() else stopCardListening()
        }

        override fun card(c: Card) {
            val icon = (c.action as? Action.OpenApp)?.key?.let { key -> icons.getOrPut(key) { executor().appIcon(key) } }
            // What a read tool finds goes to the helper only after this tap: the button says so.
            val confirm = c.confirm ?: if (c.remote && c.tool?.reads == true) "Share with $helper" else c.action?.confirmLabel
            upsertAction(replyId, TermuxPanel.dress(UiAction(c.id, c.tool, c.summary, c.status, c.detail, icon, c.body, c.choices.map { it.label }, confirm, c.undo,
                c.content, c.more, c.cancel, c.handOff, remember = c.remember, allowContacts = c.allowContacts,
                // What it finds goes to the helper: a read, or an online lookup around where you are (the nearest place).
                personal = c.remote && (c.tool?.reads == true || c.action?.online == true), tapFor = c.tapFor), c))
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
        else st.copy(phase = Phase.ANSWERING, helperStatus = null, turns = st.turns.map { if (it.id == replyId) it.copy(text = it.text + text) else it })
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

    /**
     * One sentence aloud: Robot's voice, or a helper's ([byHelper], in its [voice]: another voice of the same model, so you
     * hear who talks).
     */
    private fun speakSentence(s: String, byHelper: Boolean = false, voice: String? = null, personal: Boolean = false) {
        // Never read out a tool call the model wrote as text.
        if (!byHelper && (s.contains("call:") || s.contains("<start_function_call>") || ToolCalls.parseText(s).isNotEmpty())) return
        val clean = SpeechText.clean(s)
        if (clean.isEmpty()) return
        // 0.8: in a headset or car session, OutputGuard first (personal sentences only on a private device, the car's two).
        if (!PanelHandsFree.allow(clean) { personal || personalNow(_state.value.turns.lastOrNull()?.id).isNotEmpty() }) return
        // The helper's voice per sentence: Kokoro's French voice for a French one, its own otherwise (Claude's when the
        // helper has none, as before 0.6.0).
        if (byHelper) HelperVoices.forSentence(VoiceHub.settings.voiceChoice, voice ?: VoiceHub.settings.claude?.voice, clean).let { (sid, lang) -> VoiceHub.tts.speakOther(clean, sid, lang) }
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
        if (MemoryPanel.isUndo(key)) {
            // 0.7: a memory card's Undo (an item saved, or items forgotten); the card says what it did.
            upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val line = withContext(Dispatchers.IO) { MemoryPanel.undo(key) }
                _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.let { upsertAction(turn.id, it.copy(detail = line)) }
            }
        } else {
            upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = "Removed from Robot Notes.", undo = null))
            scope.launch(Dispatchers.IO) { NotesFiles.store(VoiceHub.app).delete(key) }
        }
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
        msgListenAfterSpeech = false
        PanelMessages.stop()
        val id = reqId
        if (id != 0) client?.post(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(id))
        // The voice stops now: nothing already queued, and not the cut-off last sentence (finishReply drops it).
        voice.mute()
        VoiceHub.stopVoice(why)
        // The turn ends as "stopped" (send's cancellation handler, which runs later on the main thread); :brain
        // rebuilds that conversation next time.
        slot.stop()
    }

    /**
     * "Stop" said, a tap, a volume key or "Stop speaking": the voice stops. A reply still being written (Robot's or
     * a helper's) stops too, as with the Stop button (0.4.6; before, the rest was written on, shown but not spoken, and the
     * turn stayed busy until it was done), so the next question can come at once.
     */
    fun stopSpeaking(why: TtsStop = TtsStop.TAP) {
        msgListenAfterSpeech = false
        PanelMessages.stop()
        if (_state.value.phase.stopEndsTurn(slot.running)) return stopGenerating(why)
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

    /** [costMicros]: a helper's reply's estimated price, kept with it in the history. */
    private fun finishReply(replyId: String, reason: DoneReason, costMicros: Long = 0) {
        if (!current(replyId)) return
        val ui = _state.value.turns.firstOrNull { it.id == replyId }
        val byHelper = ui?.fromHelper == true
        // A stopped reply's last, unfinished sentence is not read out (nor anything after "stop").
        voice.finish(replyId, cancelled = reason == DoneReason.CANCELLED, speak = _state.value.speak)
            ?.let { speakSentence(it, byHelper, VoiceHub.settings.helper(ui?.helper)?.voice) }
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
        // 0.7: a turn with messages in it, or after one, is saved as a placeholder (core ChatTurn.messages); memory's History
        // record (its item ids, a line for a private answer) keeps that placeholder (MemoryPanel.forHistory copies the turn).
        val kept = PanelMessages.kept(replyId) ?: if (chat.any { it.messages }) app.heyrobot.core.messages.MessageReading.ANSWER_NOT_KEPT else null
        val quoted = synchronized(quotedReplies) { quotedReplies.remove(replyId) }
        chat += MemoryPanel.forHistory(ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(),
            actions = records(actions) + if (quoted) listOf(Quoted.record()) else emptyList(), stopped = stopped,
            fromHelper = byHelper, sources = ui?.sources.orEmpty(), costMicros = costMicros,
            helper = ui?.helper.takeIf { byHelper }, by = ui?.by.takeIf { byHelper }, home = byHelper && ui?.home == true, messages = kept != null, kept = kept))
        _state.update { st ->
            st.copy(
                // Only the turn's phases end here: "hey robot" during the answer is already listening.
                phase = st.phase.afterTurn(VoiceHub.tts.isSpeaking),
                helperStatus = null,
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
                helperStatus = null,
                notice = if (action != NoticeAction.NONE) Notice(message, action) else st.notice,
                turns = st.turns.map { if (it.id == replyId) it.copy(text = message, streaming = false, error = true, actions = actions, fromHelper = false) else it },
            )
        }
        persist()
        if (closeConvAfterTurn) closeLiveConversation()
    }

    /** A finished reply has no card left waiting: one still pending was not done (and is never saved as waiting). */
    private fun settled(actions: List<UiAction>) = actions.map { if (it.status == ActionStatus.PENDING) it.copy(status = ActionStatus.DECLINED) else it }

    private fun records(actions: List<UiAction>) = actions.mapNotNull { a -> a.tool?.let { ActionRecord(it, a.summary, a.status, a.detail, a.content) } }

    // ------------------------------------------------------------------ routines (0.8)

    /** The routine card waiting that only a tap answers, and what Robot says to a spoken yes. */
    private var routineTapOnly: Pair<String, String>? = null

    /** What a routine turn does in the panel (assistant/routines PanelRoutines): this turn's bubble, voice and cards. */
    private val routineHost = object : PanelRoutines.Host {
        override val speak: Boolean get() = _state.value.speak
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override fun text(replyId: String, text: String) = setText(replyId, text)
        override fun append(replyId: String, text: String) {
            paragraphBreak(replyId)
            appendText(replyId, text)
        }
        override fun say(sentence: String, personal: Boolean) { if (_state.value.speak) speakSentence(sentence, personal = personal) }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean {
            val view = PanelView(replyId)
            routineTapOnly = tapOnly?.let { card.id to it }
            view.card(card)
            view.phase(TurnPhase.CONFIRMING)
            val pick = try { slot.confirmations.ask(card.id) } finally { routineTapOnly = null }
            view.phase(TurnPhase.THINKING)
            return pick >= 0
        }
        override fun show(replyId: String, card: Card) = PanelView(replyId).card(card)
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? {
            // As a typed command: asked for, never "from content", a card where the gate wants one; what ran comes back to be said.
            var ran: Executed? = null
            val actions = object : TurnActions by PanelActions {
                override suspend fun run(action: Action): Executed = PanelActions.run(action).also { ran = it }
            }
            val raw = RawToolCall(step.action.tool.id, RoutineCodec.args(step.action))
            val r = TurnRunner(DirectCall(raw), actions, PanelView(replyId), slot.confirmations, false, asked = setOf(step.action.tool),
                messages = PanelMessages.taint(chat), newId = ::newId).run()
            r.failure?.let { appendText(replyId, it) }
            return ran
        }
        override fun finish(replyId: String) = finishReply(replyId, DoneReason.END)
        override fun quoted(replyId: String) { synchronized(quotedReplies) { quotedReplies += replyId } }
        override suspend fun leave() = leaveForApp()
    }

    /** Replies that read out your calendar or reminders as they are (core Quoted): their saved turn carries its content record. */
    private val quotedReplies = HashSet<String>()

    /** Settings' Run now or a Ready card's tap: a turn of its own, as if you had asked. */
    private fun routineTurn(label: String, work: suspend (String, PanelRoutines.Host) -> Unit) {
        if (_state.value.busy || slot.running) return notify("Robot is still on the last question.")
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        startedLocked = PanelContinuity.stillLocked(startedLocked, Assistant.locked(VoiceHub.app))
        val user = ChatTurn(newId(), TurnRole.USER, label, System.currentTimeMillis())
        chat += user
        val replyId = newId()
        _state.update {
            it.copy(turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text) + UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true),
                phase = Phase.THINKING, notice = null, locked = Assistant.locked(VoiceHub.app))
        }
        lastActivity = System.currentTimeMillis()
        voice.start(replyId)
        startTurn(replyId) { work(replyId, routineHost) }
    }

    /** Nothing from the model this long, before its first word or between two: it is given up (a first word takes about 0.3 s). */
    private const val EVENT_GAP_MS = 60_000L
    private const val WATCHDOG_TICK_MS = 5_000L
    private const val SORRY = "Sorry, I didn't catch that."
    private const val CARD_HINT = "A card is waiting: say yes or no, or tap a button on it."
    private const val CARD_TYPE_HINT = "A card is waiting: tap a button on it, or type yes or no."
    private const val SORRY_NO_ANSWER = "Sorry, I couldn't answer that."
}
