// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.lock.PhoneLockNow
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.assistant.skills.PanelSkills
import app.heyrobot.assistant.routines.RoutineVoice
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.AskWhy
import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.AskHelperTool
import app.heyrobot.core.AskRoute
import app.heyrobot.core.Attachment
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainFail
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.Card
import app.heyrobot.core.ChatOnly
import app.heyrobot.core.ChatOnlyTurn
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClaudeAppAnswer
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeWords
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.ControlRefusal
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.ClaudeSettings
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.CostText
import app.heyrobot.core.Cue
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.Executed
import app.heyrobot.core.FastPath
import app.heyrobot.core.FreeUp
import app.heyrobot.core.Hedge
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperCommand
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperLeftOut
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NoQuestion
import app.heyrobot.core.NoReply
import app.heyrobot.core.PanelContinuity
import app.heyrobot.core.Personal
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
import app.heyrobot.core.ScreenChip
import app.heyrobot.core.ScreenText
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.ShowSource
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.SpeechText
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TermuxCalls
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
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.toPromptTurns
import app.heyrobot.core.SpeechLang
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReplyLang
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.messages.MessageVoices
import app.heyrobot.sys.AppLanguage
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.core.web.BrowserRouter
import app.heyrobot.core.web.CurrentInfo
import app.heyrobot.core.web.WebConsent
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WikipediaWire
import app.heyrobot.helpers.HelperSession
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.HandOffIntents
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Phones
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebStore
import app.heyrobot.ui.common.keptPicture
import app.heyrobot.voice.Cues
import app.heyrobot.voice.SpeechToText
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. The assistant panel's state and logic, kept per process (not per session) so a
// conversation survives the panel hiding for the camera or a new session object. All state changes
// happen on the main thread. Nothing here is logged except counts and outcomes.

object PanelController {
    /** A panel reopened within this time continues the same conversation. */
    private const val CONTINUE_MS = 5 * 60_000L
    private val scope = MainScope()
    /** The one turn that may run at a time, and its confirmation cards. */
    private val slot = TurnSlot(scope)
    private val _state = MutableStateFlow(PanelState(newId()))
    val state: StateFlow<PanelState> = _state
    /** The turn surface: every write to a reply's text, cards and owner goes through it (PanelReplies). */
    private val replies = PanelReplies(_state)
    @Volatile var shown = false; private set

    private var host: PanelHost? = null
    private val chat = mutableListOf<ChatTurn>()
    private var draftJpeg: ByteArray? = null
    private var lastActivity = 0L
    private var startedLocked = false
    private var keptByUser = false
    private var hidingFor: String? = null
    /** 0.8: what a headset's button or car mode asked of this show (PanelHandsFree), or null. */
    private var handsFree: PanelHandsFree.Show? = null
    private var reqId = 0
    private var client: BrainClient? = null
    /** What of the streaming reply is read aloud (muted by "stop", silent once cancelled). */
    private val voice = ReplyVoice()
    /** The model stream (PanelStream): the request under way, a refused control text, the languages and the voice stay here. */
    private val stream = PanelStream(scope, replies, object : PanelStream.Port {
        override var reqId: Int
            get() = this@PanelController.reqId
            set(v) { this@PanelController.reqId = v }
        override val langs: Langs get() = turnLangs
        override fun refused(f: ControlRefusal.Found) { controlRefused = f }
        override fun speak(replyId: String, text: String) = voice.feed(replyId, text, _state.value.speak).forEach(::speakSentence)
    })
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
    /** Which three tips the next panel shows: counts panel opens, from a random start so a new process doesn't repeat itself. */
    private var tipRound = kotlin.random.Random.nextInt(0, 1_000)

    /** A helper's conversation ("go get Jarvis", PanelHelperMode): its session, its join point and its 10 minutes. */
    private val helperMode = PanelHelperMode(scope, _state) { slot.running }
    /** The screen's text when the corner swipe came from the Claude app (for "Read Claude's answer"); never stored. */
    private var claudePieces: List<String>? = null
    /** The question Robot last handed to the Claude app, to find Claude's answer after it. */
    private var lastToApp: String? = null
    /**
     * The last photo (from the camera or the picker) and its thumbnail, kept for this panel session and conversation
     * only ("ask Jarvis about this photo"). Never a screenshot.
     */
    private var lastImage: ByteArray? = null
    private var lastThumb: Bitmap? = null
    /**
     * "Ask about this screen" (PanelScreenShare): what Android sent of the screen to this panel, how the panel was
     * opened, and the chip; send, PanelHelperTurn and PanelWebSearch's context read the screen's text, screenshot and app through it.
     */
    private val screenShare = PanelScreenShare(scope, _state, { shown }, { shownAtE }, ::screenLangs) { Assistant.screenSharing(VoiceHub.app) }
    /**
     * "Answer cards by voice" (0.4.0, PanelCardVoice): once a card waits, listen for its yes or no once Robot has stopped
     * talking; the microphone, the voice, the settings and the card buttons stay here.
     */
    private val cardVoice = PanelCardVoice(_state, replies, screenShare, object : PanelCardVoice.Port {
        override val main get() = VoiceHub.main
        override val stt get() = VoiceHub.stt
        override val settings get() = VoiceHub.settings
        override val speaking get() = VoiceHub.tts.isSpeaking
        override val shown get() = this@PanelController.shown
        override val spokenTurn get() = this@PanelController.spokenTurn
        override val turnLangs get() = this@PanelController.turnLangs
        override fun screenLangs() = this@PanelController.screenLangs()
        override val routineTapOnly get() = this@PanelController.routineTapOnly
        override fun wakePaused(paused: Boolean) { VoiceHub.listener?.wakePaused = paused }
        override fun notify(text: String) = this@PanelController.notify(text)
        override fun speakRobot(text: String) = this@PanelController.speakRobot(text)
        override fun said(p: Phrase) = p.said
        override fun speak(text: String) = VoiceHub.tts.speak(text)
        override fun cue(c: Cue) = this@PanelController.cue(c)
        override fun confirm(actionId: String, yes: Boolean) = this@PanelController.confirm(actionId, yes)
        override fun choose(actionId: String, index: Int) = this@PanelController.choose(actionId, index)
    })
    /**
     * Web search (0.9, PanelWebSearch): "search the web for …", web_search's run, its steps, QueryCheck's context with what
     * read tools returned, and the answer; the turn runner, the tools, the reply's end and the voice's guard stay here.
     */
    private val webSearch: PanelWebSearch = PanelWebSearch(_state, replies, screenShare, chat, voice, object : PanelWebSearch.Port {
        override val ctx get() = VoiceHub.app
        override val main get() = VoiceHub.main
        override val settings get() = VoiceHub.settings
        override val turnSaid get() = this@PanelController.turnSaid
        override val turnLangs get() = this@PanelController.turnLangs
        override fun search(abort: Abort, action: Action.WebSearch): Executed = executor(abort).run(action)
        override suspend fun runDirect(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult = webDirectTurn(replyId, call, untrusted)
        override fun said(p: Phrase) = p.said
        override fun sayInto(replyId: String, text: Phrase) = this@PanelController.sayInto(replyId, text)
        override fun finishReply(replyId: String, reason: DoneReason) = this@PanelController.finishReply(replyId, reason)
        override fun failReply(replyId: String, message: String) = this@PanelController.failReply(replyId, message)
        override suspend fun leaveForApp() = this@PanelController.leaveForApp()
        override fun speakSentence(s: String) = this@PanelController.speakSentence(s)
    })
    /** 1.4, read_file's answer (PanelFileAnswer): a tool-free one-shot over one file's text, after the model's turn ended. */
    private val fileAnswer = PanelFileAnswer(_state, replies, voice, object : PanelFileAnswer.Port {
        override val ctx get() = VoiceHub.app
        override val turnLangs get() = this@PanelController.turnLangs
        override fun sayInto(replyId: String, text: Phrase) = this@PanelController.sayInto(replyId, text)
        override fun finishReply(replyId: String, reason: DoneReason) = this@PanelController.finishReply(replyId, reason)
        override fun failReply(replyId: String, message: String) = this@PanelController.failReply(replyId, message)
        override fun speakSentence(s: String) = this@PanelController.speakSentence(s)
        override fun noteRead(text: String) = webSearch.noteRead(Executed(ActionStatus.DONE, "", results = listOf(mapOf("answer" to text))))
        override fun privateAnswer(replyId: String) {
            // As memory's private items: shown only, the conversation closed after it, never a screenshot.
            PanelFileAnswer.markPrivate(replyId)
            closeConvAfterTurn = true
            host?.secure(true)
            if (_state.value.speak) notify(app.heyrobot.core.i18n.FilesStrings.notReadAloud())
        }
    })
    /**
     * The direct commands (conversation mode, feedback, free memory, memory, Termux scripts, texting; PanelConversationMode,
     * PanelFeedback, PanelFreeMemory, PanelMemoryCommand, PanelTermuxScript, PanelTexting), a helper's turn
     * (PanelHelperTurn), the offers (PanelOffers) and escalation (PanelEscalation): what they need of PanelController, in one port. The reply's end, the host, the confirmations, the
     * voice, the microphone, the name a text waits on, the turn runners and the turns' view stay here (DirectCall,
     * PanelActions and PanelView are private).
     */
    private val commands = object : PanelConversationMode.Port, PanelFeedback.Port, PanelFreeMemory.Port, PanelMemoryCommand.Port, PanelTermuxScript.Port,
        PanelTexting.Port, PanelHelperTurn.Port, PanelOffers.Port, PanelEscalation.Port {
        override val ctx get() = VoiceHub.app
        override val settings get() = VoiceHub.settings
        override val host get() = this@PanelController.host
        override fun locked() = Assistant.locked(VoiceHub.app)
        override val brain get() = BrainKeeper.state.value
        override fun unlockFirst(p: Phrase) = this@PanelController.unlockFirst(p)
        override val confirmations get() = slot.confirmations
        override fun newId() = this@PanelController.newId()
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) = this@PanelController.localReply(replyId, text, spoken)
        override fun localReply(replyId: String, text: String, spoken: String) = this@PanelController.localReply(replyId, text, spoken)
        override fun sayInto(replyId: String, text: Phrase) = this@PanelController.sayInto(replyId, text)
        override fun sayInto(replyId: String, text: String) = this@PanelController.sayInto(replyId, text)
        override suspend fun freeMemoryTurn(replyId: String) = this@PanelController.freeMemoryTurn(replyId)
        override suspend fun memoryCardTurn(replyId: String, call: RawToolCall) = this@PanelController.memoryCardTurn(replyId, call)
        override suspend fun textTurn(replyId: String, call: RawToolCall, untrusted: Boolean) = this@PanelController.textTurn(replyId, call, untrusted)
        override val shown get() = this@PanelController.shown
        override var askingTextFor: String?
            get() = this@PanelController.askingTextFor
            set(v) { this@PanelController.askingTextFor = v }
        override var listenAfterSpeech: Boolean
            get() = this@PanelController.listenAfterSpeech
            set(v) { this@PanelController.listenAfterSpeech = v }
        override fun said(p: Phrase) = p.said
        override fun speakSentence(s: String) = this@PanelController.speakSentence(s)
        override fun startListening() = this@PanelController.startListening()
        override suspend fun termuxTurn(replyId: String, model: TurnModel, untrusted: Boolean) = this@PanelController.termuxTurn(replyId, model, untrusted)
        override val turnLangs get() = this@PanelController.turnLangs
        override fun finishReply(replyId: String, reason: DoneReason) = this@PanelController.finishReply(replyId, reason)
        override fun failReply(replyId: String, message: String) = this@PanelController.failReply(replyId, message)
        override suspend fun leaveForApp() = this@PanelController.leaveForApp()
        override val buttonLangs get() = this@PanelController.buttonLangs
        override fun screenLangs() = this@PanelController.screenLangs()
        override fun phoneLockOn() = PhoneLockNow.on(VoiceHub.app)
        override fun someoneElse() = PhoneLockNow.someoneElse(VoiceHub.app)
        override fun networkAllowed() = HelperLink.networkAllowed(VoiceHub.app)
        override fun keySaved(h: HelperConfig?) = this@PanelController.keySaved(h)
        override fun session(s: RobotSettings, h: HelperConfig, conversation: Boolean) = HelperLink.session(VoiceHub.app, s, h, conversation)
        override val userRegion get() = this@PanelController.userRegion
        override val lastImage get() = this@PanelController.lastImage
        override val lastThumb get() = this@PanelController.lastThumb
        override fun handedToApp(text: String?) { lastToApp = text }
        override fun notify(p: Phrase) = this@PanelController.notify(p)
        override fun speakRobot(text: String) = this@PanelController.speakRobot(text)
        override fun speakSentence(s: String, byHelper: Boolean, voice: String?) = this@PanelController.speakSentence(s, byHelper, voice)
        override fun stopVoice(why: TtsStop) = VoiceHub.stopVoice(why)
        override fun finishReply(replyId: String, reason: DoneReason, costMicros: Long) = this@PanelController.finishReply(replyId, reason, costMicros)
        override fun failReply(replyId: String, message: String, noReply: Boolean) = this@PanelController.failReply(replyId, message, noReply = noReply)
        override fun closeLiveConversation() = this@PanelController.closeLiveConversation()
        override fun view(replyId: String): TurnView = PanelView(replyId)
        override fun view(replyId: String, helper: String): TurnView = PanelView(replyId, helper)
        override suspend fun helperRun(replyId: String, rounds: TurnModel, h: HelperConfig, s: RobotSettings, asked: Set<ToolName>, session: HelperSession) =
            this@PanelController.helperRun(replyId, rounds, h, s, asked, session)
        override suspend fun handOffRun(replyId: String, model: TurnModel) = this@PanelController.handOffRun(replyId, model)
        override val running get() = slot.running
        override fun notify(text: String) = this@PanelController.notify(text)
        override fun execute(action: Action): Executed = executor().run(action)
        override fun helperBubble(h: HelperConfig): String = helperTurn.bubble(h)
        override fun startTurn(replyId: String, block: suspend () -> Unit) { this@PanelController.startTurn(replyId, block) }
        override suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean, h: HelperConfig,
            fallBack: Boolean): Boolean = helperTurn.escalate(replyId, question, blocks, voiceAsked, reuseBubble, h, fallBack)
        override suspend fun budgetReached(s: RobotSettings, h: HelperConfig?) = HelperLink.budgetReached(VoiceHub.app, s, h)
        override val helperDeclined get() = this@PanelController.helperDeclined
        override fun personalNow(replyId: String?) = this@PanelController.personalNow(replyId)
        override fun finishOrDrop(replyId: String) = this@PanelController.finishOrDrop(replyId)
        override suspend fun webAnswer(replyId: String, question: String, history: List<ChatTurn>, found: WebOutcome) = webSearch.answer(replyId, question, history, found)
        override fun offer(replyId: String, o: PanelOffers.Pending) = offers.show(replyId, o)
    }
    /** "Text mom I'm late", "text mom" (PanelTexting): send_text from your own words, without the model. */
    private val texting = PanelTexting(_state, replies, commands)
    /** "Remember that …", "forget that" (0.7, PanelMemoryCommand): memory's commands, Robot's own even in a helper's conversation. */
    private val memoryCommand = PanelMemoryCommand(_state, replies, cardVoice, chat, commands)
    /** "Run backup" (0.9, PanelTermuxScript): your own Termux script named in your own words, without the model. */
    private val termuxScript = PanelTermuxScript(replies, stream, commands)
    /** Conversation mode by voice (0.5.0, PanelConversationMode): start, switch, stop, pause, resume or summarize. */
    private val conversationMode = PanelConversationMode(commands)
    /** "Report a bug", "I have an idea" (0.7, PanelFeedback): Send feedback opens in the app; the panel sends nothing. */
    private val feedback = PanelFeedback(commands)
    /** "Free up memory" (0.7.0, PanelFreeMemory): Robot's own answer and free_memory's card; it never stops an app itself. */
    private val freeMemory = PanelFreeMemory(commands)
    /**
     * A helper's turn (PanelHelperTurn): "ask Jarvis …", "go get Jarvis", Robot checking with a helper, the cards that ask
     * before anything personal goes, and the hand-off to a helper's app; its TurnRunners stay here.
     */
    private val helperTurn: PanelHelperTurn = PanelHelperTurn(scope, _state, replies, voice, helperMode, screenShare, cardVoice, commands)
    /** The offer under a reply (PanelOffers), with exactly what its tap or a yes sends, fixed when it was made. */
    private val offers: PanelOffers = PanelOffers(scope, _state, commands)
    /** 1.1: other apps' sound pauses while you ask; the panel says when its turn is over (voice TurnFocus). */
    internal val mediaPause = PanelMediaPause(_state, PanelMediaPause.port { shown })
    /** When Robot brings a helper in by itself (PanelEscalation): before its own model, after a hand-over or a hedge. */
    private val escalation = PanelEscalation(_state, chat, commands)
    /**
     * 1.1: the languages of the turn under way (core i18n): what Robot says in its reply language
     * ([Langs.say], decided once per question by ReplyLang), Robot's screens named in it and the cards' buttons in the menus'
     * ([Langs.ui]), Android's screens in the phone's. The model is told English whatever they are.
     */
    @Volatile private var turnLangs: Langs = Langs.EN
    /** The last turn's reply language, which a short line after it keeps ("OK", "5 minutes", a name: ReplyLang.KEEP_MS). */
    private var lastReply: ReplyLang.Last? = null
    /**
     * You said "not Claude" in this conversation (HelperRouter.declines): no ask_claude for the local model, no
     * pre-router, no automatic escalation and no offers, until a new conversation or an explicit "ask Claude …" or "go
     * get Claude". Shown nowhere and never saved.
     */
    private var helperDeclined = false

    /** Your region for a helper's first message: the one set in Settings, else the phone's own (SIM, network, language). */
    private val userRegion get() = VoiceHub.settings.regionFor(Phones.deviceRegion(VoiceHub.app))

    private fun newId() = UUID.randomUUID().toString()

    /** Robot's screens' languages now (the menus', the phone's): a notice, a button, a status line. */
    private fun screenLangs(): Langs = AppLanguage.langs(VoiceHub.app)

    /** A button's words: the menus' language, whatever the reply language. */
    private val buttonLangs: Langs get() = turnLangs.let { Langs(it.ui, it.ui, it.system, pseudo = it.pseudo) }

    /**
     * 1.1: the reply language of a question ([q]; [router]: a router matched it in its French or English words), once per turn
     * (core ReplyLang): the setting, else what LangId hears when it is sure, else the last turn's for a short line, else the
     * menus'. Nothing heard (a routine's Run now): the setting, else the menus'.
     */
    private fun decideLangs(q: String?, router: Lang? = null): Langs {
        val base = screenLangs()
        val now = System.currentTimeMillis()
        val setting = VoiceHub.settings.replyLang
        val say = if (q == null) ReplyLang.unheard(base.ui, setting) else {
            val guess = q.takeIf { it.isNotBlank() }?.let { try { ConversationEngine.langId(VoiceHub.app).detect(it) } catch (_: Throwable) { null } }
            ReplyLang.decide(guess, router, lastReply, now, base.ui, setting)
        }
        if (q != null) lastReply = ReplyLang.Last(say, now)
        return base.copy(say = say)
    }

    /** Before any question in this panel: the last turn's language for a while, else the menus' (a missed question's "Sorry"). */
    private fun idleLangs(): Langs = screenLangs().let { b ->
        b.copy(say = ReplyLang.decide(null, false, lastReply, System.currentTimeMillis(), b.ui, VoiceHub.settings.replyLang))
    }

    /** Every helper's name, as the router hears it (on or off: an off one is recognised so Robot can say so). */
    private fun names(): List<HelperName> = HelperName.all(VoiceHub.settings)

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
        mediaPause.start(scope)
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
        if (!speaking) cardVoice.speakingStopped()
        if (!speaking && listenAfterSpeech) {
            listenAfterSpeech = false
            // "What should the text to Mom say?" was said aloud: the answer comes by voice too.
            if (shown && (askingTextFor != null || PanelSkills.waitsForWords))
                VoiceHub.main.post { if (shown && (askingTextFor != null || PanelSkills.waitsForWords) && _state.value.phase == Phase.IDLE) startListening() }
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
        // FX1 (finding 3): with travel mode hiding drawers, a conversation holding Robot Files' answers is never continued.
        val stale = System.currentTimeMillis() - lastActivity > CONTINUE_MS ||
            PanelFiles.travelHides(VoiceHub.app, chat)
        travelSeen = PanelFiles.hidingNow(VoiceHub.app)
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
        screenShare.show(ignore = source == RobotVoiceService.SRC_APP || returning, returning = returning)
        if (!returning) {
            claudePieces = null
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
                showId = it.showId + 1, claudeScreen = false, lockedTools = s.lockedTools, tips = if (returning) it.tips else tips(s, locked))
        }
        BrainKeeper.prewarm(VoiceHub.app)
        HelperLink.warm(VoiceHub.app, s)
        // 1.1: the reply language before the first question; the language table loads off the main thread (once per process).
        if (!slot.running) turnLangs = idleLangs()
        scope.launch(Dispatchers.IO) { try { ConversationEngine.langId(VoiceHub.app) } catch (t: Throwable) { RLog.w("langid_load", t) } }
        PanelMessages.onShow()
        // The voice models load on first use (VoiceHub's memory policy): speech recognition when the mic or the wake
        // word starts a question, the voice when a question is sent with spoken replies on. Nothing loads just for a show.
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        shownAtE = SystemClock.elapsedRealtime()
        val from = showSource(source, showFlags)
        VoiceLog.add(VoiceEvent.SESSION_SHOWN, from)
        screenShare.start(from, showFlags)
        // The question first: once it is attached, the capture mode can't switch under it ("Always echo cancellation").
        if (source == RobotVoiceService.SRC_WAKE && handsFree == null) startListening()
        // 0.8: opened by Settings' Run now or a Ready card's tap.
        PanelRoutines.onShow(args)?.let { (label, work) -> routineTurn(label, work) }
        // 1.1: a skill's "Continue" notification tapped.
        PanelSkills.onShow(args)?.let { (label, work) -> routineTurn(label) { id, _ -> work(id, skillHost) } }
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
        cardVoice.stop()
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
        mediaPause.hidden()
        VoiceHub.updateCapture()
        // A helper's conversation ends when the panel closes (not when it hides for the camera or an app a helper opened).
        if (hidingFor == null) endHelperMode()
        // Screen content lives only while the panel is open, and so do offers.
        screenShare.hide()
        claudePieces = null
        offers.drop()
        if (!forCapture) {
            draftJpeg = null
            lastImage = null
            PanelFiles.forget(VoiceHub.app)
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
        // One call per visible activity ([index] of [count]): the screen's text is all of them together (PanelScreenShare).
        if (!screenShare.onText(pieces, pkg, index, count)) return
        if (index == 0) claudePieces = null
        // Only the Claude app's own window, and only when an answer can be found in it (a secure window gives no text).
        if (pkg == ClaudeAppLink.PACKAGE && ClaudeAppAnswer.extract(pieces, lastToApp) != null) claudePieces = pieces
        _state.update { it.copy(claudeScreen = claudePieces != null) }
        screenShare.refresh()
    }

    /**
     * Android called without the screen's text: "Use text from screen" is off, or the screen is private. Noted even
     * before the panel shows (a reused session gets it ahead of the show), so the chip can say why at once.
     */
    fun onAssistNone() = screenShare.onNoText()

    /** Null: Android called without a picture. As with [onAssistNone], a "no" counts before the show; a picture doesn't. */
    fun onScreenshot(bmp: Bitmap?) = screenShare.onShot(bmp)

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
        PanelFiles.forget(VoiceHub.app)
        chat.clear()
        MemoryPanel.newConversation()
        PanelSkills.newConversation()
        host?.secure(false)
        webSearch.forgetReads(allKnown = true)
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
        PanelSkills.newConversation()
        host?.secure(false)
        // What its earlier read tools returned isn't saved: any word not yours may be from it (QueryCheck).
        webSearch.forgetReads(allKnown = false)
        PanelFileTools.forget()
        // FX1 (files-1.4-review.md finding 3): travel mode on, Robot Files' answers come back as History shows them, to the
        // screen and the model alike; the stored ones are put back when the conversation is saved again (PanelFiles.saved).
        val shown = PanelFiles.opened(VoiceHub.app, c, turnLangs)
        chat += shown.turns
        // The groups its questions and actions used: continuing it describes the same tools.
        groups = ToolRouter.used(c.turns)
        closeConvAfterTurn = false
        startedLocked = false
        // Opening it from the history keeps saving it, but a screen question in it still needs "Keep" (only the
        // person's tap sets keptByUser).
        keptByUser = false
        lastActivity = System.currentTimeMillis()
        _state.update {
            PanelState(c.id, turns = shown.turns.map { t -> t.toUi() }, keep = true, sttAvailable = it.sttAvailable, micHint = it.micHint,
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
        offers.drop()
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

    /** FX3: what travel mode hid when last looked at (PanelFiles.hidingNow), at a show or a Cmd.FILES. */
    private var travelSeen: Set<String> = emptySet()

    /**
     * FX3 (Cmd.FILES): Robot Files or its settings changed. When travel mode starts hiding more (turned on, or another
     * drawer hidden), a conversation here holding Robot Files' answer is put away at once, not only at the next show
     * (onShow's own check): nothing a hidden drawer gave stays on screen. Only then: a file coming in while travel mode is
     * on leaves the conversation you're in alone. Its turns were saved as each reply ended; History shows them as
     * placeholders until travel mode is off.
     */
    fun filesChanged() {
        val now = PanelFiles.hidingNow(VoiceHub.app)
        val more = !travelSeen.containsAll(now)
        travelSeen = now
        if (more && PanelFiles.travelHides(VoiceHub.app, chat)) newConversation()
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
        val c = StoredConversation(st.convId, chat.first().time, System.currentTimeMillis(), PanelContinuity.stored(PanelFiles.saved(chat.toList()), startedLocked))
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
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockOpenApp()))
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

    /** 1.1: during a phone lock (the screen unlocked), Robot's app is covered: say so rather than "unlock first". */
    private fun unlockFirst(p: Phrase): Phrase = if (app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)) app.heyrobot.core.i18n.LockStrings.closedNow() else p

    /** A notice in Robot's screens' language (the menus'). */
    private fun notify(p: Phrase) = notify(p.render(screenLangs()))

    /** "Open Model" under a chat-only model's answer to an action: Settings › Model, where you choose (0.4.3). */
    fun openModelSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockOpenPage("page" to UiStrings.inSettings(UiStrings.model))))
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.MODEL))
    }

    /**
     * 0.7.0, the header's Settings button (the tester's 0.6.0 report: "add a setting button to the robot chat page"):
     * Robot's Settings tab over the panel, as Open Robot does; on the lock screen it asks you to unlock first.
     */
    fun openSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockTapAgain("button" to UiLabel(UiStrings.settings))))
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.SETTINGS))
    }

    /** "Allow" on a card that needs a permission: the main app's permission screen (it asks; the panel never does). */
    fun openPermissions() {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockOpenApp()))
        val h = host ?: return
        leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.PERMISSIONS))
    }

    // ------------------------------------------------------------------ attachments

    /** "Ask about this screen": the screen goes with the next question, or the panel says why it can't. */
    fun attachScreen() = screenShare.attach()

    /** "Open settings" under that notice: Settings › Apps › Default apps › Digital assistant app (voice input settings first). */
    fun openAssistSettings() {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockOpenSettings("settings" to AndroidPath.SETTINGS)))
        val h = host ?: return
        val i = Assistant.settingsIntent(VoiceHub.app)
            ?: return notify(PanelStrings.settingsDidntOpenGoTo("settings" to AndroidPath.SETTINGS, "path" to AndroidPath.DIGITAL_ASSISTANT))
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
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockForCamera()))
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
            notify(PanelStrings.cameraDidntOpen())
        }
    }

    /**
     * From MediaDeleteActivity (same process, 0.4.0): Android's confirmation for a picked file answered. The card that
     * waited for it (and the saved conversation) says whether it was deleted.
     */
    fun mediaDeleteAnswered(deleted: Boolean) {
        // 1.1: the card waits in its turn's language, and says what happened in it (core FreeUp.isDeleteWaiting).
        fun settle(tool: ToolName?, detail: String?) = tool == ToolName.FREE_STORAGE && FreeUp.isDeleteWaiting(detail)
        val status = if (deleted) ActionStatus.DONE else ActionStatus.DECLINED
        fun detail(waiting: String?) = sameLanguage(waiting, InfoStrings.deleteWaiting(), if (deleted) InfoStrings.deleted() else InfoStrings.notDeleted())
        _state.update { st ->
            st.copy(turns = st.turns.map { t -> t.copy(actions = t.actions.map { if (settle(it.tool, it.detail)) it.copy(status = status, detail = detail(it.detail)) else it }) })
        }
        chat.replaceAll { t -> t.copy(actions = t.actions.map { if (settle(it.tool, it.detail)) it.copy(status = status, detail = detail(it.detail)) else it }) }
        persist()
    }

    /** [then] in the language [line] (a card's line, [was] in one of Robot's languages) is in; English when it is none of them. */
    private fun sameLanguage(line: String?, was: Phrase, then: Phrase): String =
        Lang.entries.firstOrNull { l -> was.render(turnLangs.copy(say = l)) == line || was.render(Langs.of(l)) == line }
            ?.let { l -> then.render(turnLangs.copy(say = l)) } ?: then.en()

    /** From CaptureActivity (same process): the picture to send with the next question; [original], its own bytes for "file this". */
    fun onImagePicked(jpeg: ByteArray, thumb: Bitmap, original: ByteArray? = null, tooBig: Boolean = false) {
        draftJpeg = jpeg
        PanelFiles.picked(original, tooBig)
        _state.update { it.copy(draft = Draft(Attachment.PHOTO, thumb), notice = null, focusRequest = it.focusRequest + 1) }
    }

    // ------------------------------------------------------------------ voice input

    fun micPressed() {
        // A card waits: the mic answers it ("yes", "no"), with "Answer cards by voice" on (off: a tap or a typed answer).
        if (_state.value.phase == Phase.CONFIRMING) {
            if (!VoiceHub.settings.voiceCards) return notify(PanelStrings.cardTypeHint())
            VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
            return cardVoice.micPressed()
        }
        if (_state.value.busy || slot.running) return
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        if (VoiceHub.settings.speakReplies == SpeakReplies.VOICE) _state.update { it.copy(speak = true) }
        startListening()
    }

    fun micReleased() = VoiceHub.stt.stop()

    /** The mic button is held past a tap (push-to-talk): only its release ends the question. */
    fun micHeld() = VoiceHub.stt.holdOpen()

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
        notify(PanelStrings.sorry().render(turnLangs))
        cue(Cue.SORRY)
    }

    /** "Sorry, I didn't catch that." spoken when replies are spoken, otherwise a short chime. */
    private fun cue(c: Cue) {
        if (c != Cue.CHIME && _state.value.speak) {
            VoiceLog.add(VoiceEvent.CUE, c)
            speakRobot((if (c == Cue.SORRY_NO_ANSWER) PanelStrings.sorryNoAnswer() else PanelStrings.sorry()).said)
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
        if (st.phase == Phase.CONFIRMING && st.draft == null && cardVoice.answer(q, fromVoice)) return true
        // 0.7: a reply card waiting: "yes" (where allowed), "no", or "stop" in its 3 seconds.
        if (st.draft == null && PanelMessages.answerCard(q, fromVoice)) return true
        // 0.8: "next", "skip", "repeat" or "stop" while a routine or the brief speaks (or just paused for "hey robot").
        if (q.isNotEmpty() && st.draft == null && RoutineVoice.control(q)) return true
        if (st.busy || slot.running) {
            if (st.phase == Phase.CONFIRMING) {
                notify(if (fromVoice && !VoiceHub.settings.voiceCards) PanelStrings.cardTypeHint() else PanelStrings.cardHint())
                if (fromVoice) cue(Cue.CHIME)
            } else if (fromVoice) {
                notify(PanelStrings.stillOnLastAskAgain())
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
        if (st.draft == null && HelperRouter.isYes(q, names)) offers.waiting { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.let {
            acceptOffer(it, tapped = false)
            return true
        }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        spokenTurn = fromVoice
        turnSaid = q
        val textingFor = askingTextFor
        askingTextFor = null
        helperMode.expire()
        // Conversation mode's commands first (0.5.0, docs/conversation-mode.md 5.4): "transcribe this conversation",
        // and while one runs "stop transcribing", "pause", "resume". Never the model's to decide.
        // 0.7, your messages (PanelMessages, core MessagesRouter) before anything else: "read my messages", "reply …"; and a
        // message's own words heard back while Robot read it are never a request (not even "remember that …" or "report a bug").
        val msgCmd = if (q.isNotEmpty() && st.draft == null) PanelMessages.route(q, fromVoice) else null
        val convCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null) ConversationRouter.route(q, ConversationSession.running) else null
        // 0.7: "report a bug", "I have an idea", « signaler un bogue »: Send feedback opens in the app; the panel sends nothing.
        // After the messages router and its echo check (a message read aloud saying "report a bug: …" is the message's), before the model.
        val fbCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null) FeedbackRouter.route(q) else null
        // 1.1: Phone lock's phrases ("lock the phone until 7", "how long is left", "end the lock"), only from your own words.
        val lockCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode &&
            !TurnRules.untrusted(chat)) PanelLock.route(q) else null
        // 0.8: routines' phrases next ("every weekday at 7, tell me the weather", "brief me", "remind me in the morning to …"),
        // outside a helper's conversation. A routine said in a conversation holding screen text needs a tap.
        val routineCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode)
            PanelRoutines.route(q, TurnRules.untrusted(chat), fresh = chat.none { it.role == TurnRole.USER }) else null
        // 1.1: skills' phrases next (assistant/skills PanelSkills), under the routines' conditions.
        val skillCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            !_state.value.inHelperMode) PanelSkills.route(q) else null
        // D8: what waited for your next words (the steps, a new name) is dropped once they went elsewhere.
        if (skillCmd == null) PanelSkills.drop()
        // 1.4: "file this", « classe ça » (PanelFiles, D15: after skills, before hands-free), with what the panel holds; F8: Robot
        // Files' other words (core FilesRouter: a rule, move, find, Inbox, delete as a tap-only card).
        val fileCmd = if (q.isNotEmpty() && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null && skillCmd == null &&
            !_state.value.inHelperMode) PanelFiles.route(q) else null
        // 0.8: then headset and car mode's phrases, and "call …" (the call tool's own words).
        val handsFreeCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && routineCmd == null && skillCmd == null &&
            fileCmd == null && !_state.value.inHelperMode) PanelHandsFree.route(q) else null
        val routineOrHandsFree = routineCmd != null || skillCmd != null || handsFreeCmd != null || fileCmd != null
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
        // 1.1: this question's reply language, once (core ReplyLang): a router's own French or English match, else what LangId hears.
        turnLangs = decideLangs(q, router = when {
            convCmd != null -> ConversationRouter.lang(q, ConversationSession.running)
            lockCmd != null -> lockCmd.lang
            routineCmd != null -> RoutineRouter.lang(q, routineCmd)
            skillCmd != null -> PanelSkills.lang(q, skillCmd)
            memRoute != null -> MemoryPanel.lang(memRoute)
            fileCmd != null -> fileCmd.lang
            else -> freeAsk?.let { if (it) Lang.FR else Lang.EN }
        })
        offers.drop()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val fileWhat = fileCmd?.let { PanelFiles.what(attachment, screenShare.shot) }
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> screenShare.shot?.let { Images.jpeg(it) }
            Attachment.NONE -> null
        }
        // Only a photo: "ask Jarvis about this photo" must never pick up a screenshot.
        if (attachment == Attachment.PHOTO && image != null) {
            lastImage = image
            lastThumb = st.draft?.thumb
        }
        val thumb = st.draft?.thumb
        val sText = if (attachment == Attachment.SCREEN) screenShare.text else null
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
        // PanelHelperTurn.run says what's missing.
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
                helperStatus = if (api) words(turnHelper!!).thinkingText.render(screenLangs()) else null,
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
                convCmd != null -> conversationMode.run(replyId, convCmd)
                fbCmd != null -> feedback.run(replyId, fbCmd)
                lockCmd != null -> PanelLock.handle(replyId, lockCmd, routineHost, said = q)
                routineCmd != null -> PanelRoutines.handle(replyId, routineCmd, routineHost, said = q)
                skillCmd != null -> {
                    PanelSkills.handle(replyId, skillCmd, skillHost, said = q)
                    // D8: Robot asked for the steps or a name; asked by voice, the answer comes by voice too.
                    if (fromVoice && PanelSkills.waitsForWords) { if (_state.value.speak) listenAfterSpeech = true else if (shown) startListening() }
                }
                fileCmd != null -> withContext(Dispatchers.IO) { PanelFiles.file(VoiceHub.app, fileCmd, fileWhat!!, turnLangs, spoken = fromVoice) }.let { r ->
                    PanelFileAnswer.markFiles(replyId)
                    localReply(replyId, r.text, r.spoken)
                    // After the reply ends: finishReply settles a card still waiting as declined, so a delete card shown
                    // before it would lose its buttons.
                    r.cards.forEach { replies.upsertAction(replyId, it) }
                }
                handsFreeCmd != null -> PanelHandsFree.handle(replyId, handsFreeCmd, routineHost)
                webQuery != null -> webSearch.direct(replyId, webQuery, user.text, history, untrusted)
                // 0.9: "run backup", your own script named in your own words: termux_script without the model.
                termux != null -> termuxScript.run(replyId, termux, untrusted)
                memRoute != null -> memoryCommand.run(replyId, memRoute, fromVoice)
                freeAsk != null -> freeMemory.run(replyId)
                cmd == HelperCommand.Leave -> {
                    endHelperMode()
                    localReply(replyId, app.heyrobot.core.i18n.HelperStrings.backToRobot())
                }
                cmd == HelperCommand.ReadAnswer -> if (claudePieces != null) {
                    finishOrDrop(replyId)
                    readBack()
                } else {
                    localReply(replyId, app.heyrobot.core.i18n.HelperStrings.swipeToRead())
                }
                way == FastPath.Way.HELPER -> helperTurn.run(replyId, cmd, turnHelper, user.text, attachment, sText, image, thumb, history, fromVoice)
                // A plain request to text someone (or the answer to "what should it say?") goes straight to send_text.
                way == FastPath.Way.TEXT && texting.direct(replyId, q, textingFor, fromVoice, untrusted) -> {}
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
            _state.value.turns.filter { it.streaming }.forEach { failReply(it.id, PanelStrings.wentWrongTryAgain().render(turnLangs)) }
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
        // For escalation trigger a (PanelEscalation.beforeModel): with the weather tool on, a weather question is Robot's to
        // answer (0.4.0). Locked, the tool can't run: as before, the question may go to the helper when it is allowed on the
        // lock screen. Current information goes by itself only to a helper that can search (0.6.0).
        val localWeather = s.approval(ToolName.WEATHER) != Approval.OFF && (!Assistant.locked(VoiceHub.app) || ToolName.WEATHER in s.lockedTools)
        // 0.9: with web search on (and "Robot searches first"), current information is Robot's to look up, as the weather.
        val web = withContext(Dispatchers.IO) { WebStore.load(VoiceHub.app) to WebAnswers.load(VoiceHub.app) }
        val webOn = WebConsent.on(web.first, web.second)
        val localWeb = webOn && web.first.currentInfo == CurrentInfo.ROBOT_FIRST && s.approval(ToolName.WEB_SEARCH) != Approval.OFF &&
            (!Assistant.locked(VoiceHub.app) || ToolName.WEB_SEARCH in s.lockedTools)
        // 1.4: Robot Files' two reads only while its files can be read (on, unlocked, no phone lock: PanelFileTools.open).
        val filesOpen = withContext(Dispatchers.IO) { PanelFileTools.open(VoiceHub.app) }
        // Strong signals go to the helper before Gemma spends a prefill (Automatic only); null: the helper took the question.
        val before = escalation.beforeModel(replyId, question, history, attachment, voiceAsked, guard, s, target, localWeather, localWeb) ?: return
        val c = BrainKeeper.ensureLoaded(VoiceHub.app)
        if (c == null) {
            val b = BrainKeeper.state.value
            VoiceLog.add(VoiceEvent.BRAIN_FAILED, if (b.readiness == BrainKeeper.Readiness.NO_MODEL) BrainFail.NO_MODEL else BrainFail.LOAD_FAILED)
            return failReply(replyId, b.problem ?: PanelStrings.modelNotReady().render(turnLangs), if (b.readiness == BrainKeeper.Readiness.NO_MODEL) NoticeAction.OPEN_APP else NoticeAction.NONE)
        }
        client = c
        val loaded = BrainKeeper.state.value
        // 0.4.3, a short-context model (chat only): a request clearly worded as an action gets Robot's own answer, and an
        // offer to open Settings › Model, never a switch made for you. (Texting and the helpers' phrases went their own way
        // before the model was ever asked, as always.)
        val chatOnly = loaded.chatOnly
        if (chatOnly) ChatOnly.actionAsked(question)?.let { asked ->
            RLog.i("chat_only_action", "tool" to RLog.Code(asked.id))
            localReply(replyId, ChatOnly.reply.render(turnLangs),
                spoken = if (turnLangs.say == Lang.EN) ChatOnly.reply.render(turnLangs).replace(" › ", ", ") else ChatOnly.reply.render(turnLangs.voice))
            _state.update { it.copy(notice = Notice(ChatOnly.notice.render(screenLangs()), NoticeAction.OPEN_MODEL)) }
            return
        }
        // The model loaded without its vision encoder: a screen question still has the screen's text.
        val vision = loaded.vision
        // A model that reads text only (your own GGUF file) says so, for a photo and for the screen.
        val textOnly = loaded.textOnly
        if (image != null && !vision && attachment == Attachment.PHOTO) {
            return failReply(replyId, when {
                textOnly -> PanelStrings.textOnlyPhoto("page" to UiStrings.inSettings(UiStrings.model)).render(turnLangs)
                loaded.fileVision -> BrainError.NO_VISION.textPhrase().render(turnLangs)
                else -> PanelStrings.noVisionInFile("page" to UiStrings.inSettings(UiStrings.model)).render(turnLangs)
            })
        }
        if (textOnly && attachment == Attachment.SCREEN) notify(PanelStrings.textOnlyScreen())
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
        // 1.2 (design Step 3): on a slow path (the CPU), a reading budget for a rebuild and the compact prompt (core PrefillBudget).
        val reading = withContext(Dispatchers.IO) { BrainKeeper.prefill(VoiceHub.app) }
        val mem = withContext(Dispatchers.IO) { MemoryPanel.forTurn(question, locked0, given, maxTokens, reading.compact) }
        turnMemContent = mem.content
        // The question keeps the items retrieved for it (their notes come back in a rebuilt history). 1.1: the reply keeps
        // every item this turn had, the pinned ones too (MemoryPanel.mark), so a reply that repeats one keeps the conversation
        // personal for helpers (core PackedMemory.personal), and an item unpinned later never comes back as a note.
        if (mem.packed.retrievedIds.isNotEmpty()) chat.indexOfLast { it.role == TurnRole.USER }.takeIf { it >= 0 }?.let { i ->
            chat[i] = chat[i].copy(memoryIds = mem.packed.retrievedIds)
        }
        MemoryPanel.mark(replyId, mem.recall.ids, secret = mem.recall.secret)
        // A private item went in: the live conversation goes after this answer, so no later answer can repeat it (spoken, or
        // kept in History); rebuilt, it holds History's line instead, and naming the item again gives it again.
        if (mem.recall.secret) {
            closeConvAfterTurn = true
            host?.secure(true)
        }
        if (mem.quiet) {
            voice.mute()
            notify(PanelStrings.notReadAloud())
        }
        // 1.1, Skills phase B: up to 3 of your skills described for your words (assistant/skills PanelSkills); never in a
        // helper's conversation, on a locked phone or to a chat-only model.
        val skillTools = if (chatOnly || locked0 || _state.value.inHelperMode) emptyList() else withContext(Dispatchers.IO) { PanelSkills.forModel(question) }
        // One run of the model on this question into the reply, with or without ask_claude (the second one, without it,
        // is Robot's own answer after the model handed over: PanelEscalation.afterLocal). [skills]: false after "Did you mean" answered no.
        suspend fun gemma(askHelper: Boolean, skills: Boolean = true): TurnResult {
            val specs = if (skills) skillTools.map { it.spec } else emptyList()
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
                    browser = BrowserRouter.named(question), files = filesOpen && !locked)
            // 1.1: "Robot answers in" fixed to a language tells the model which (core ReplyLang.answerIn); the default adds nothing.
            val answerIn = ReplyLang.answerIn(s.replyLang, turnLangs.say)
            fun systemFor(tools: List<ToolName>, sk: Boolean = false): String = app.heyrobot.core.memory.MemoryPrompt.system(if (chatOnly) Prompt.chatSystem(now, direct = s.directAnswers, answerIn = answerIn)
            else Prompt.system(now, locked, askHelper, texting = ToolName.SEND_TEXT in tools,
                weather = ToolName.WEATHER in tools, phone = ToolName.PHONE_STATUS in tools, direct = s.directAnswers, lockedTools = s.lockedTools,
                helper = target?.name, helperSearches = target?.searching ?: true, helperHome = target?.home == true, driving = driving,
                web = ToolName.WEB_SEARCH in tools, termux = termuxLine?.takeIf { tools.any { it.termux } }, browser = ToolName.BROWSER_SEARCH in tools, skills = sk,
                compact = reading.compact, answerIn = answerIn),
                tools = ToolName.REMEMBER in tools, pinned = mem.packed.systemLine)
            // 0.9: with every group this conversation called for, the new turn may not fit: the groups this question doesn't call
            // for give way first, then the others, until it keeps its room (core ToolBudget).
            val sk = if (specs.isEmpty()) emptySet() else setOf(ToolGroup.SKILLS)
            val wanted = (if (termuxTools.isEmpty()) groups - ToolGroup.TERMUX else groups) + sk
            val described = if (chatOnly) wanted else ToolBudget.fit(wanted, ToolRouter.route(question) + sk, maxTokens) { g ->
                toolsFor(g).let { Prompt.fixedTokens(systemFor(it, ToolGroup.SKILLS in g), it, if (ToolGroup.SKILLS in g) specs else emptyList(), reading.compact) }
            }
            if (described != wanted) RLog.i("tool_budget", "dropped" to (wanted - described).size, "kept" to described.size)
            val tools = toolsFor(described)
            val skillSpecs = if (ToolGroup.SKILLS in described) specs else emptyList()
            val system = systemFor(tools, skillSpecs.isNotEmpty())
            // The time only for a question that needs it, after the question: the model copied a leading time into its replies.
            val clock = if (Prompt.needsClock(question, groups)) Prompt.clock(now) else ""
            // The new turn always fits the model's context: cut (and say so) rather than overflow. Memory's notes go before it only
            // when the question still fits whole with them.
            val notes = mem.packed.notesBlock?.takeIf {
                !Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools, skillSpecs, reading.compact) + mem.packed.notesTokens, clock, maxTokens).shortened
            }
            val fitted = Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools, skillSpecs, reading.compact) + (if (notes != null) mem.packed.notesTokens else 0),
                clock, maxTokens).let { if (notes != null) it.copy(text = notes + it.text) else it }
            if (fitted.shortened && !cutSaid) {
                cutSaid = true
                notify(if (fitted.questionCut) PanelStrings.messageCut() else PanelStrings.screenCut())
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
                skills = skillSpecs,
                compact = reading.compact,
                readBudget = reading.tokens,
            )
            // About what the context has left for a tool's answer beside the reply (as :brain will fit the history): the
            // weather gives fewer days when it is short.
            val fixedTokens = Prompt.fixedTokens(system, tools, skillSpecs, reading.compact)
            val budget = reading.history(fixedTokens, TokenEstimate.turn(PromptTurn(PromptRole.USER, fitted.text, img != null)), maxTokens)
            toolRoom = budget - ContextWindow.fit(gen.history, budget).sumOf(TokenEstimate::turn) - TOOL_CALL_TOKENS
            // Chat only: whatever the model emits, no call reaches the approval gate (core ChatOnlyTurn; :brain drops them too).
            val model = stream.turn(c, convId, replyId, gen, img).let { if (chatOnly) ChatOnlyTurn(it) else it }
            controlRefused = null
            // 0.7: notes kept from content (not your own words) make the turn content too (MemoryTurn.content).
            // 1.1, Skills phase B: a skill the model calls runs through PanelSkills (its card when the rule wants one, then each
            // step through its own gate and card).
            val actions = object : TurnActions by PanelActions {
                override suspend fun runSkill(call: app.heyrobot.core.skills.SkillCall.Ok, untrusted: Boolean) =
                    PanelSkills.runForModel(replyId, call, untrusted, question, loaded.model, skillHost)
            }
            return TurnRunner(model, actions, PanelView(replyId), slot.confirmations, untrusted || mem.content,
                asked = askedNow, messages = PanelMessages.taint(chat), langs = turnLangs,
                skills = PanelSkills.menu(if (skillSpecs.isEmpty()) emptyList() else skillTools), newId = ::newId,
                // 1.1: set_alarm only for a time your words gave, on the day it rings (core AlarmWords).
                heard = app.heyrobot.core.TurnWords.of(question, java.time.ZonedDateTime.now(), turnLangs.say)).run().also { leaveOutRefused(replyId, history) }
        }
        // ask_claude is a tool: a chat-only model gets none (the escalation triggers outside the model still apply).
        // "Did you mean" answered no (D18): the model answers again, without skills.
        val result = gemma(askHelper = leftOut == null && !chatOnly).let { if (it.withoutSkills) gemma(askHelper = leftOut == null && !chatOnly, skills = false) else it }
        if (mem.packed.retrievedIds.isNotEmpty()) scope.launch(Dispatchers.IO) { MemoryPanel.used(mem.packed.retrievedIds) }
        result.failure?.let { return failReply(replyId, it) }
        // 0.9: Gemma called web_search and it ran: its turn ended there, and a one-shot answers from what was found.
        result.web?.let { return webSearch.answer(replyId, question, history, it) }
        // 1.4: Gemma called read_file and it found its file: the same, a one-shot answers from the file's text.
        result.file?.let { return fileAnswer.answer(replyId, it) }
        // Escalation trigger b: Gemma called ask_claude. Its turn ended there; the helper answers (or is offered).
        if (result.escalate) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.LOCAL_TOOL)
            return escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.LOCAL_TOOL,
                ownAnswer = { gemma(askHelper = false) })
        }
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) return leaveForApp()
        // Escalation trigger c: the reply hedged ("I don't know", "I can't browse", nothing at all). One that says it has no
        // current information goes by itself, like current information, only to a helper that can search (0.6.0).
        val reply = chat.lastOrNull { it.id == replyId }
        if (result.reason == DoneReason.END && reply != null && reply.actions.isEmpty() && Hedge.detect(reply.text)) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.HEDGE)
            return escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.HEDGE,
                needsSearch = Hedge.needsSearch(reply.text))
        }
        before.offerAfter?.let { escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, it, offerOnly = true,
            needsSearch = before.needsSearch) }
    }

    /** The personal content in the conversation now, the reply [replyId] included while it is still on screen only. */
    private fun personalNow(replyId: String?): Set<Personal> {
        val ui = replyId?.takeIf { id -> chat.none { it.id == id } }?.let { id -> _state.value.turns.firstOrNull { it.id == id } }
        // A Files router answer is personal from its first spoken sentence, before finishReply adds it to the chat.
        val reply = ui?.let { listOf(ChatTurn(it.id, TurnRole.ASSISTANT, it.text, 0, actions = records(it.actions), files = PanelFileAnswer.isFiles(it.id))) }.orEmpty()
        return HelperContext.personal(chat + reply)
    }

    /** Robot's own words into its empty reply [replyId], spoken in Robot's voice when replies are spoken. */
    private fun sayInto(replyId: String, text: String) {
        replies.setText(replyId, text)
        voice.feed(replyId, text, _state.value.speak).forEach { speakSentence(it) }
    }

    /** The same for a sentence of Robot's own, in the turn's reply language (1.1): shown, and said in its voice's form. */
    private fun sayInto(replyId: String, text: Phrase) {
        replies.setText(replyId, text.render(turnLangs))
        voice.feed(replyId, text.said, _state.value.speak).forEach { speakSentence(it) }
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
     * The offer under [replyId] was accepted (PanelOffers): [tapped] on its Send button, or a yes, which sends only what
     * HelperTrust lets a yes send.
     */
    fun acceptOffer(replyId: String, tapped: Boolean = true) = offers.accept(replyId, tapped)

    fun dismissOffer(replyId: String) = offers.dismiss(replyId)

    /**
     * Helper [id]'s key was saved or deleted (or the helper removed): without a key it needs, its conversation and its API
     * offers end (the old client closes with them).
     */
    fun helperKeyChanged(id: String) {
        val h = VoiceHub.settings.helper(id)
        if (h != null && (!h.presetOf.keyRequired || keySaved(h))) return
        offers.dropIf { it.api && it.helper == id }
        if (_state.value.helperMode == id) endHelperMode()
    }

    /** Settings changed in the main app: the offers and the conversation of a helper turned off or removed end. */
    fun settingsChanged() {
        val s = VoiceHub.settings
        fun gone(id: String) = s.helper(id)?.on != true
        offers.dropIf { gone(it.helper) }
        helperMode.settingsChanged(s)
    }

    /**
     * A helper's turn (PanelHelperTurn): [rounds] of helper [h]'s on reply [replyId], with [s] as the turn began; what a read
     * finds goes to it only after "Share with …" unless HelperTrust says so, and it runs only the tools [session] offered.
     */
    private suspend fun helperRun(replyId: String, rounds: TurnModel, h: HelperConfig, s: RobotSettings, asked: Set<ToolName>, session: HelperSession): TurnResult =
        TurnRunner(rounds, PanelActions, PanelView(replyId, h.name), slot.confirmations, untrusted = true, remote = true, asked = asked,
            trusted = HelperTrust.reads(s, h), offered = session.offered, messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()

    /** The app route's hand-off (PanelHelperTurn): [scripted]'s one ask_claude call, your question, as a turn on reply [replyId]. */
    private suspend fun handOffRun(replyId: String, scripted: TurnModel): TurnResult =
        TurnRunner(scripted, PanelActions, PanelView(replyId), slot.confirmations, untrusted = false, messages = PanelMessages.taint(chat),
            langs = turnLangs, newId = ::newId).runHandOff()

    /** "Remember that …" after a message (PanelMemoryCommand): the quarantine's remember [call], your own words', on reply [replyId]. */
    private suspend fun memoryCardTurn(replyId: String, call: RawToolCall): TurnResult =
        TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted = true,
            asked = setOf(ToolName.REMEMBER), messages = PanelMessages.taint(chat), origin = ActionOrigin.USER_WORDS, langs = turnLangs, newId = ::newId).run()

    /** Settings › Memory changed something (Cmd.MEMORY_CHANGED): what the panel holds goes, and the live conversation starts again. */
    fun memoryChanged() {
        MemoryPanel.changedElsewhere()
        if (slot.running) closeConvAfterTurn = true else closeLiveConversation()
    }

    /** "Free up memory"'s card (PanelFreeMemory): free_memory as the panel's own call on reply [replyId]. */
    private suspend fun freeMemoryTurn(replyId: String): TurnResult =
        TurnRunner(DirectCall(RawToolCall(ToolName.FREE_MEMORY.id, emptyMap())), PanelActions, PanelView(replyId), slot.confirmations,
            untrusted = TurnRules.untrusted(chat), asked = setOf(ToolName.FREE_MEMORY), messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()

    /**
     * Robot's own words as the reply (no model), in Robot's bubble and voice; [spoken] is the same as the voice says it,
     * when it differs (no "›").
     */
    private fun localReply(replyId: String, text: String, spoken: String = text) {
        replies.markHelper(replyId, null)
        _state.update { it.copy(helperStatus = null) }
        replies.setText(replyId, text)
        voice.feed(replyId, spoken, _state.value.speak).forEach { speakSentence(it) }
        finishReply(replyId, DoneReason.END)
    }

    /** The same in the turn's reply language (1.1): [text] shown, [spoken] (or [text]'s voice form) said. */
    private fun localReply(replyId: String, text: Phrase, spoken: Phrase = text) = localReply(replyId, text.render(turnLangs), spoken.said)

    /** What Robot says of [this] in the turn's reply language: its voice's form in French, the screen's in English (1.0's words). */
    private val Phrase.said: String get() = if (turnLangs.say == Lang.EN) render(turnLangs) else render(turnLangs.voice)

    /** Robot's own words aloud: its French voice when the turn's reply language is French (1.1), its own otherwise. */
    private fun speakRobot(text: String) {
        val fr = if (turnLangs.say == Lang.FR) MessageVoices.french(VoiceHub.settings.voiceChoice) else null
        if (fr != null) VoiceHub.tts.speakOther(text, fr.first, fr.second) else VoiceHub.tts.speak(text)
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
        replies.markHelper(replyId, null)
        _state.update { it.copy(helperStatus = null) }
        replies.setText(replyId, text)
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

    /** "Back to Robot" on the chip, "thanks Jarvis", the panel closing, 10 minutes without a turn, or the lock screen. */
    fun endHelperMode() = helperMode.end()

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
        val pieces = claudePieces ?: return notify(app.heyrobot.core.i18n.HelperStrings.swipeToRead())
        val answer = ClaudeAppAnswer.extract(pieces, lastToApp)
        RLog.i("claude_read_back", "found" to (answer != null), "chars" to (answer?.length ?: 0))
        if (answer == null) {
            notify(PanelStrings.claudeAnswerNotFound())
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

    /** Texting without the model (PanelTexting): send_text's [call], your own words', as a turn on reply [replyId]. */
    private suspend fun textTurn(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult =
        // Your own words asked for this text (or answered "what should it say?").
        TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOf(ToolName.SEND_TEXT),
            messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()

    // ------------------------------------------------------------------ web search (0.9, core web; design Part A)

    /** "Search the web for …" as a turn (PanelWebSearch.direct): your own words' call on reply [replyId]. */
    private suspend fun webDirectTurn(replyId: String, call: RawToolCall, untrusted: Boolean): TurnResult =
        // 0.7's quarantine: after a message, even your own search is a card only a tap answers (core MessageQuarantine).
        TurnRunner(DirectCall(call), PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOfNotNull(ToolName.of(call.name)),
            messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()

    /**
     * A web answer's source tapped: it opens in the browser (only the card's own links, or Wikipedia's licence), as an app
     * opens; on the lock screen, unlock first.
     */
    fun openSource(replyId: String, url: String) {
        val card = _state.value.turns.firstOrNull { it.id == replyId }?.web ?: return
        if (url !in (card.cited + card.alsoFound).map { it.url } + WikipediaWire.LICENCE_URL) return
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(PanelStrings.unlockOpenWebPage()))
        val h = host ?: return
        VoiceHub.stopVoice(TtsStop.OTHER)
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (h.launch(i)) {
            hidingFor = "action"
            h.hidePanel()
        } else notify(PanelStrings.noBrowser())
    }

    /** "Run backup" (PanelTermuxScript): [model]'s one termux_script call, your script named in your own words, as a turn on reply [replyId]. */
    private suspend fun termuxTurn(replyId: String, model: TurnModel, untrusted: Boolean): TurnResult =
        // 0.7's quarantine: after a message, even your own script is a card only a tap answers (core MessageQuarantine).
        TurnRunner(model, PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOf(ToolName.TERMUX_SCRIPT),
            messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()

    // 0.9, Termux cards (TermuxPanel): found by id, changed in place, drawn again when a run's state changes.
    internal fun cardById(id: String): UiAction? = replies.cardById(id)

    internal fun updateCard(id: String, change: (UiAction) -> UiAction) = replies.updateCard(id, change)

    internal fun termuxChanged(key: String) = replies.updateCards({ it.termux?.key == key }, TermuxPanel::redress)

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
        // 1.4: a Robot Files card's Move: the card asks which drawer, and your next words answer (PanelFiles).
        if (PanelFiles.isMove(pkg)) {
            val turn = _state.value.turns.first { t -> t.actions.any { it.id == actionId } }
            PanelFiles.startMove(pkg)?.let { ask -> replies.upsertAction(turn.id, a.copy(detail = ask.render(turnLangs), handOff = null)) }
            if (shown && _state.value.speak && _state.value.phase == Phase.IDLE) startListening()
            return
        }
        // 1.4 F10: a reminder card's Add to calendar: add_event's own card, in a turn of its own.
        if (PanelFiles.isCalendar(pkg)) return filesCalendar(pkg)
        val page = SettingsPage.isPage(pkg)
        // 1.4 F8: Show in Files (a find, Inbox, several matches): the Files tab.
        if (PanelFiles.isOpen(pkg) && Assistant.locked(VoiceHub.app)) return notify(app.heyrobot.core.i18n.FilesStrings.locked())
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(if (page) PanelStrings.unlockOpenSettings("settings" to AndroidPath.SETTINGS) else PanelStrings.unlockOpenCheogram()))
        val h = host ?: return
        val tries = if (PanelFiles.isOpen(pkg)) listOf(PanelFiles.openIntent(VoiceHub.app, pkg)) else if (page) SettingsIntents.of(VoiceHub.app, pkg)
        else listOfNotNull(VoiceHub.app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (tries.isEmpty()) return notify(if (page) PanelStrings.settingsDidntOpen("settings" to AndroidPath.SETTINGS) else PanelStrings.cheogramNotInstalled())
        stopGenerating(TtsStop.OTHER)
        if (tries.any { h.launch(it) }) {
            hidingFor = "action"
            h.hidePanel()
        } else if (page) {
            notify(PanelStrings.settingsDidntOpen("settings" to AndroidPath.SETTINGS))
        }
    }

    /**
     * The phone side of a turn: the approval inputs and ToolExecutor (contacts, calendar and files off the main thread).
     * An online tool (the weather) runs through [OnlineRun]: Stop ends it at once, and so does its deadline.
     */
    private object PanelActions : TurnActions {
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override val phoneLock: Boolean get() = app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)
        override val settings: RobotSettings get() = VoiceHub.settings
        override suspend fun prepare(action: Action): Prepared = withContext(Dispatchers.IO) { MemoryPanel.prepare(action, locked) ?: executor().prepare(action) }
        // 1.0 (injection-termux-policy-unwired): a Termux call is prepared by TermuxTool (TermuxPolicy, the lock, the signer, the
        // permission), into its card or a refusal; everything else as above.
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            TermuxTool.prepareTurn(VoiceHub.app, action, untrusted, remote, turnSaid) { prepare(it) }
        // 1.0 (injection-querycheck-notes-personal): what a read tool returned to the model (a note's text, not just its title)
        // is kept for QueryCheck's personal words while this conversation lives.
        override suspend fun run(action: Action): Executed = runAction(action).also(webSearch::noteRead)

        private suspend fun runAction(action: Action): Executed {
            // 0.7: remember and forget (core memory/); what the conversation holds decides where a remembered item came from.
            if (action.tool.group == ToolGroup.MEMORY) return withContext(Dispatchers.IO) {
                // (The last: a message is in the conversation, so only your own "remember that …" got here, after its tap.)
                MemoryPanel.run(action, spokenTurn, app.heyrobot.core.memory.MemoryTaint.fromContent(TurnRules.untrusted(chat),
                    _state.value.turns.lastOrNull()?.actions?.any { it.content } == true, turnMemContent), PanelMessages.taint(chat).active)
            } ?: Executed(ActionStatus.FAILED, PanelStrings.wentWrong())
            // 0.9: a Termux run waits for its result (Stop ends its process tree), off the main thread.
            if (action.tool.termux) return withContext(Dispatchers.IO) { TermuxTool.run(VoiceHub.app, action) { i -> host?.launch(i) ?: false } }
            if (!action.online) return withContext(Dispatchers.IO) { executor().run(action) }
            if (action is Action.WebSearch) return webSearch.run(action)
            val weather = action.tool == ToolName.WEATHER
            val deadline = if (weather) WeatherAsk.DEADLINE_MS else Nearby.DEADLINE_MS
            val abort = Abort(deadline)
            return try {
                OnlineRun.run(abort, deadline) { a -> executor(a).run(action) } ?: if (weather) {
                    Executed(ActionStatus.FAILED, WeatherFail.TIMEOUT.text).also { VoiceLog.add(VoiceEvent.WEATHER_FAILED, WeatherFail.TIMEOUT) }
                } else if (!abort.claim()) {
                    // The lookup got to open Organic Maps just as its time ran out: that one stays, nothing opens twice.
                    Executed(ActionStatus.DONE, PanelStrings.openedOrganicMaps(), launchedApp = true)
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

    private fun executor(abort: Abort = Abort()) = ToolExecutor(VoiceHub.app, VoiceHub.settings, abort, toolRoom, turnSaid, webSearch::context, webSearch::step, turnLangs.say) { i -> host?.launch(i) ?: false }

    /** What you typed or said this turn (never screen text): "the nearest" in your own words counts for the maps tools. */
    @Volatile private var turnSaid: String? = null

    /** This turn went with a memory item from content (MemoryPanel.forTurn's content): what it remembers is content too. */
    @Volatile private var turnMemContent = false

    /** The room for a tool's answer in the turn under way (runTurn's estimate); no limit outside one. */
    @Volatile private var toolRoom = Int.MAX_VALUE

    /** The model's tool call itself, about. */
    private const val TOOL_CALL_TOKENS = 40

    /**
     * What the turn shows: the reply bubble [replyId], its cards and the panel's phase. [helper]: the helper whose turn it
     * is, by name, for "Share with Claude" (Claude: the name used before 0.6.0).
     */
    private class PanelView(private val replyId: String, private val helper: String = "Claude") : TurnView {
        override fun phase(p: TurnPhase) {
            if (!current(replyId)) return
            _state.update { it.copy(phase = if (p == TurnPhase.CONFIRMING) Phase.CONFIRMING else Phase.THINKING) }
            if (p == TurnPhase.CONFIRMING) cardVoice.waiting() else cardVoice.stop()
        }

        override fun card(c: Card) {
            val icon = (c.action as? Action.OpenApp)?.key?.let { key -> icons.getOrPut(key) { executor().appIcon(key) } }
            // What a read tool finds goes to the helper only after this tap: the button says so.
            val confirm = c.confirm ?: if (c.remote && c.tool?.reads == true) PanelStrings.shareWith("name" to helper).render(buttonLangs)
                else c.action?.confirmText?.render(buttonLangs)
            replies.upsertAction(replyId, TermuxPanel.dress(UiAction(c.id, c.tool, c.summary, c.status, c.detail, icon, c.body, c.choices.map { it.label }, confirm, c.undo,
                c.content, c.more, c.cancel, c.handOff, remember = c.remember, allowContacts = c.allowContacts,
                // What it finds goes to the helper: a read, or an online lookup around where you are (the nearest place).
                personal = c.remote && (c.tool?.reads == true || c.action?.online == true), tapFor = c.tapFor, chips = c.chips), c))
            // 1.1: what this conversation did, for "save that as a skill" (D12).
            PanelSkills.seen(c)
        }

        override val text: String get() = replies.text(replyId).orEmpty()
        override fun replaceText(text: String) = replies.setText(replyId, text)
        override fun paragraphBreak() = paragraphBreak(replyId)
        override fun say(sentence: String) {
            voice.say(replyId, sentence, _state.value.speak)?.let(::speakSentence)
        }
    }

    /** The model's text after a tool round starts a new paragraph (and a new sentence for speech). */
    private fun paragraphBreak(replyId: String) {
        if (replies.paragraphBreak(replyId)) voice.feed(replyId, "\n", _state.value.speak).forEach(::speakSentence)
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
        else speakRobot(clean)
    }

    private suspend fun leaveForApp() {
        hidingFor = "action"
        delay(900)
        if (shown) host?.hidePanel()
    }

    /** The person tapped "Do it" (true) or "Cancel" on a confirmation card. */
    fun confirm(actionId: String, yes: Boolean) {
        // 1.4 F8: a Robot Files delete card's Delete or Keep (PanelFiles). Tap-only (AskWhy.DELETE): a spoken yes never comes here.
        if (PanelFiles.isDelete(actionId)) return filesDeleteTapped(actionId, yes)
        // 1.4 F10: a reminder card's Add to my brief or Not now (PanelFiles): a brief reminder only, never the calendar.
        if (PanelFiles.isRemind(actionId)) return filesRemindTapped(actionId, yes)
        slot.confirmations.answer(actionId, yes)
    }

    private fun filesRemindTapped(actionId: String, yes: Boolean) {
        val turn = _state.value.turns.firstOrNull { t -> t.actions.any { it.id == actionId } } ?: return
        scope.launch {
            val (status, line) = withContext(Dispatchers.IO) { PanelFiles.remindTapped(VoiceHub.app, actionId, yes, turnLangs) }
            _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.let { replies.upsertAction(turn.id, it.copy(status = status, detail = line)) }
        }
    }

    /**
     * 1.4 F10: a reminder card's Add to calendar. A turn of its own runs add_event as the panel's call with AskWhy.FROM_CONTENT:
     * its card always shows, whatever Settings › Actions and Safety cards say, and only a tap adds the event (a file's words
     * chose its title and date, files-1.4-plan.md D11). Nothing here writes the calendar.
     */
    private fun filesCalendar(handOff: String) {
        if (Assistant.locked(VoiceHub.app) || app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)) return notify(app.heyrobot.core.i18n.FilesStrings.locked())
        routineTurn(app.heyrobot.core.i18n.FilesStrings.addToCalendar().render(turnLangs)) { replyId, _ ->
            // FX3: its card names the file's title and date: Robot Files' answer, for travel mode's placeholder (ChatTurn.files).
            PanelFileAnswer.markFiles(replyId)
            val ask = withContext(Dispatchers.IO) { PanelFiles.event(VoiceHub.app, handOff, turnLangs) }
            if (ask == null) {
                localReply(replyId, app.heyrobot.core.i18n.FilesStrings.datePassed().render(turnLangs))
                return@routineTurn
            }
            replies.setText(replyId, app.heyrobot.core.i18n.FilesStrings.calendarCard().render(turnLangs))
            // 1.1: with an alert (a dated reminder's, EventAlert); FromAFile's FROM_CONTENT keeps it tap only.
            val raw = RawToolCall(ToolName.ADD_EVENT.id, mapOf("title" to ask.title, "when" to ask.day, "alert" to true))
            val r = TurnRunner(DirectCall(raw), FromAFile, PanelView(replyId), slot.confirmations, untrusted = true, asked = setOf(ToolName.ADD_EVENT),
                messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()
            r.failure?.let { replies.appendText(replyId, it) }
            finishReply(replyId, DoneReason.END)
        }
    }

    /** The panel's actions for a call a file's date made (F10's Add to calendar): every card says it came from content and takes a tap. */
    private object FromAFile : TurnActions by PanelActions {
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            when (val p = PanelActions.prepare(action, untrusted, remote)) {
                is Prepared.Ready -> p.copy(why = p.why + AskWhy.FROM_CONTENT)
                else -> p
            }
    }

    private fun filesDeleteTapped(actionId: String, yes: Boolean) {
        val turn = _state.value.turns.firstOrNull { t -> t.actions.any { it.id == actionId } } ?: return
        val langs = turnLangs
        // FX2 (K1): Delete gives the card Undo for 10 seconds; when the delete goes through, the card's Undo goes.
        fun ended(ok: Boolean) = scope.launch {
            _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.takeIf { it.undo != null }?.let {
                replies.upsertAction(turn.id, it.copy(undo = null, status = if (ok) ActionStatus.DONE else ActionStatus.FAILED,
                    detail = (if (ok) app.heyrobot.core.i18n.FilesStrings.deleted() else app.heyrobot.core.i18n.FilesStrings.notDeleted()).render(langs)))
            }
        }
        scope.launch {
            val t = withContext(Dispatchers.IO) { PanelFiles.deleteTapped(VoiceHub.app, actionId, yes, langs, ended = { ok -> ended(ok) }) }
            _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.let { replies.upsertAction(turn.id, it.copy(status = t.status, detail = t.line, undo = t.undo)) }
        }
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
        // 1.4: a Robot Files card's Undo (PanelFiles): back in Inbox, or a move put back; the card says which.
        if (PanelFiles.isUndo(key)) {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null, handOff = null))
            scope.launch {
                val line = withContext(Dispatchers.IO) { PanelFiles.undo(VoiceHub.app, key, turnLangs) }
                _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.let { replies.upsertAction(turn.id, it.copy(detail = line)) }
            }
            return
        }
        if (MemoryPanel.isUndo(key)) {
            // 0.7: a memory card's Undo (an item saved, or items forgotten); the card says what it did.
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val line = withContext(Dispatchers.IO) { MemoryPanel.undo(key, turnLangs) }
                _state.value.turns.firstOrNull { it.id == turn.id }?.actions?.firstOrNull { it.id == actionId }?.let { replies.upsertAction(turn.id, it.copy(detail = line)) }
            }
        } else {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = PanelStrings.removedFromNotes().render(turnLangs), undo = null))
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

    // ------------------------------------------------------------------ ending a reply

    /** Stop button: stops the reply (or the model load) and its voice; a waiting card is declined. */
    fun stopGenerating(why: TtsStop = TtsStop.BUTTON) {
        cardVoice.stop()
        msgListenAfterSpeech = false
        PanelMessages.stop()
        PanelSkills.drop()
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

    /** 1.0.2: what llama.cpp refused for a control token's text in this turn's request (core ControlRefusal), until it is left out. */
    @Volatile private var controlRefused: ControlRefusal.Found? = null

    /**
     * After such a refusal: the turns that held the text (by [sent], the history the request was made from) are never given
     * to the model again, so the conversation goes on; the reply's failure says so. The live conversation is rebuilt. Not
     * once the conversation moved on ([replyId] isn't this conversation's any more).
     */
    private fun leaveOutRefused(replyId: String, sent: List<ChatTurn>) {
        val f = controlRefused ?: return
        controlRefused = null
        if (!current(replyId)) return
        val next = ControlRefusal.recover(chat, sent, f)
        RLog.i("control_text_left_out", "question" to f.question, "turns" to next.count { it.leftOut } - chat.count { it.leftOut })
        chat.clear()
        chat += next
        closeConvAfterTurn = true
    }

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
        if (reason == DoneReason.GARBAGE) text = listOf(text.take(200), PanelStrings.answerWentWrong().render(turnLangs)).joinToString("\n\n").trim()
        val actions = settled(ui?.actions.orEmpty())
        // The model finished without a word or an action: say so rather than leave an empty bubble.
        if (text.isEmpty() && actions.isEmpty() && !stopped) {
            text = PanelStrings.noAnswer().render(turnLangs)
            VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.EMPTY)
            if (spokenTurn) cue(Cue.SORRY_NO_ANSWER)
        }
        // 0.7: a turn with messages in it, or after one, is saved as a placeholder (core ChatTurn.messages); memory's History
        // record (its item ids, a line for a private answer) keeps that placeholder (MemoryPanel.forHistory copies the turn).
        val kept = PanelMessages.kept(replyId) ?: if (chat.any { it.messages }) app.heyrobot.core.messages.MessageReading.ANSWER_NOT_KEPT else null
        val quoted = synchronized(quotedReplies) { quotedReplies.remove(replyId) }
        // 1.4: an answer from a private file is kept as a placeholder (PanelFileAnswer), here and in History.
        chat += PanelFileAnswer.forHistory(MemoryPanel.forHistory(ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(),
            actions = records(actions) + if (quoted) listOf(Quoted.record(turnLangs)) else emptyList(), stopped = stopped,
            fromHelper = byHelper, sources = ui?.sources.orEmpty(), costMicros = costMicros,
            helper = ui?.helper.takeIf { byHelper }, by = ui?.by.takeIf { byHelper }, home = byHelper && ui?.home == true, messages = kept != null, kept = kept)), turnLangs)
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
            // 1.1: the card's line in its own language (a French card says « Environ 2 Go libérés », core FreeUp.isUnloading).
            val freed = FreeUp.freedText(before, after)
            fun line(was: String?) = sameLanguage(was, InfoStrings.unloading(), freed)
            _state.update { st ->
                st.copy(turns = st.turns.map { t ->
                    if (t.id != replyId) t else t.copy(actions = t.actions.map { if (it.tool == ToolName.FREE_MEMORY && FreeUp.isUnloading(it.detail)) it.copy(detail = line(it.detail)) else it })
                })
            }
            chat.replaceAll { t ->
                if (t.id != replyId) t else t.copy(actions = t.actions.map { if (it.tool == ToolName.FREE_MEMORY && FreeUp.isUnloading(it.detail)) it.copy(detail = line(it.detail)) else it })
            }
            persist()
        }
    }

    private fun failReply(replyId: String, message: String, action: NoticeAction = NoticeAction.NONE, noReply: Boolean = true) {
        if (!current(replyId)) return
        voice.finish(replyId, cancelled = true, speak = false)
        if (noReply) VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.FAILED)
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
        /** 1.1: the turn's languages, decided once with the routine router's match (or the setting, for Run now). */
        override fun langs(said: String?, router: Lang?): Langs = turnLangs
        override fun text(replyId: String, text: String) = replies.setText(replyId, text)
        override fun append(replyId: String, text: String) {
            paragraphBreak(replyId)
            replies.appendText(replyId, text)
        }
        override fun say(sentence: String, personal: Boolean) { if (_state.value.speak) speakSentence(sentence, personal = personal) }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean = pick(replyId, card, tapOnly) >= 0
        override suspend fun pick(replyId: String, card: Card, tapOnly: String?): Int {
            val view = PanelView(replyId)
            routineTapOnly = tapOnly?.let { card.id to it }
            view.card(card)
            view.phase(TurnPhase.CONFIRMING)
            val pick = try { slot.confirmations.ask(card.id) } finally { routineTapOnly = null }
            view.phase(TurnPhase.THINKING)
            return pick
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
                messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()
            r.failure?.let { replies.appendText(replyId, it) }
            return ran
        }
        override fun finish(replyId: String) = finishReply(replyId, DoneReason.END)
        override fun quoted(replyId: String) { synchronized(quotedReplies) { quotedReplies += replyId } }
        override suspend fun leave() = leaveForApp()
        override fun launch(intent: android.content.Intent): Boolean =
            intent.resolveActivity(VoiceHub.app.packageManager) != null && (host?.launch(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) ?: false)
    }

    /**
     * 1.1: what a skill turn does in the panel (assistant/skills PanelSkills): a routine turn's bubble, voice and cards, and the
     * steps' ports, a typed command's, with this conversation's real content and messages (D5; no memory goes with a skill).
     */
    private val skillHost = object : PanelSkills.Host, PanelRoutines.Host by routineHost {
        override fun ports(replyId: String) = PanelSkills.SkillPorts(PanelActions, PanelView(replyId), slot.confirmations, PanelMessages.taint(chat), turnLangs, ::newId)
        override val untrusted: Boolean get() = TurnRules.untrusted(chat)
        override val phoneLock: Boolean get() = app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)
        override val messages get() = PanelMessages.taint(chat)
    }

    /** Replies that read out your calendar or reminders as they are (core Quoted): their saved turn carries its content record. */
    private val quotedReplies = HashSet<String>()

    /** Settings' Run now or a Ready card's tap: a turn of its own, as if you had asked. */
    private fun routineTurn(label: String, work: suspend (String, PanelRoutines.Host) -> Unit) {
        if (_state.value.busy || slot.running) return notify(PanelStrings.stillOnLast())
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        // 1.1: nothing was heard: the setting's language, else the menus'.
        turnLangs = decideLangs(null)
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
}
