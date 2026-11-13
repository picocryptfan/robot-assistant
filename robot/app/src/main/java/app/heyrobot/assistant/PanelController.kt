// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.NoteDelete
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.lock.PhoneLockNow
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import app.heyrobot.assistant.ask.AskPanel
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.assistant.conversation.SummaryTodoPanel
import app.heyrobot.assistant.handsfree.HandsFreeHub
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
import app.heyrobot.core.i18n.ReadStrings
import app.heyrobot.core.read.ReadAloud
import app.heyrobot.core.i18n.SkillStrings
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
import app.heyrobot.core.web.WebRoute
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import app.heyrobot.core.i18n.CorrectStrings

// VOICE PROCESS. The assistant panel's state and logic, kept per process (not per session) so a
// conversation survives the panel hiding for the camera or a new session object. All state changes
// happen on the main thread. Nothing here is logged except counts and outcomes.

object PanelController {
    /** A panel reopened within this time continues the same conversation. */
    private const val CONTINUE_MS = 5 * 60_000L
    private val scope = MainScope()
    private const val LOCK_LEAVE_MS = 2_000L
    private const val LOCK_STOP_MS = 500L
    /** The one turn that may run at a time, and its confirmation cards. */
    private val slot = TurnSlot(scope)
    private val _state = MutableStateFlow(PanelState(newId()))
    val state: StateFlow<PanelState> = _state
    /** The turn surface: every write to a reply's text, cards and owner goes through it (PanelReplies). */
    private val replies = PanelReplies(_state)
    @Volatile var shown = false; private set
    /** Which router took the last question (the class's simple name), or "model". Never logged or saved; a test build reports it. */
    @Volatile var lastRouter = "model"; private set // l10n: not text

    private var host: PanelHost? = null
    private val chat = mutableListOf<ChatTurn>()
    private var draftJpeg: ByteArray? = null
    /** E7: this turn's question carries what another app shared (framed as shared text, not the screen). */
    private var turnShared = false
    private var lastActivity = 0L
    private var startedLocked = false
    private var keptByUser = false
    private var hidingFor: String? = null
    /** 0.8: what a headset's button or car mode asked of this show (PanelHandsFree), or null. */
    private var handsFree: PanelHandsFree.Show? = null
    /** fix-1.2.1-b: this show came from the wake word (a hands-free voice session; PanelRoutines.Host.handsFree). */
    private var wokeBy = false
    private var reqId = 0
    private var client: BrainClient? = null
    /** What of the streaming reply is read aloud (muted by "stop", silent once cancelled). */
    private val voice = ReplyVoice()
    private val shots = PanelShots(replies, voice, { _state.value.speak }, { speakSentence(it) }, { id, r -> finishReply(id, r) })
    private val wiki = PanelWiki(replies, voice, { _state.value.speak }, { speakSentence(it) }, { id, r -> finishReply(id, r) })
    /** The model stream (PanelStream): the request under way, a refused control text, the languages and the voice stay here. */
    private val streamPort = object : PanelStream.Port {
        override var reqId: Int
            get() = this@PanelController.reqId
            set(v) { this@PanelController.reqId = v }
        override val langs: Langs get() = turnLangs
        override fun refused(f: ControlRefusal.Found) { controlRefused = f }
        override fun speak(replyId: String, text: String) = voice.feed(replyId, text, _state.value.speak).forEach(::speakSentence)
    }
    private val stream = PanelStream(scope, replies, streamPort)
    private val icons = HashMap<String, Bitmap?>()
    private var initialized = false
    /** The tool groups this conversation has used: only ever added to (ToolRouter), reset with the conversation. */
    private var groups: Set<ToolGroup> = setOf(ToolGroup.CORE)
    /** Undo changed what the live conversation believes: close it once the turn under way (if any) has ended. */
    private var closeConvAfterTurn = false
    /** For the voice log: when the panel was shown, and whether the turn under way began as a spoken question. */
    private var shownAtE = 0L
    private var spokenTurn = false
    private val quiet = PanelQuiet(_state) { notify(it) }
    /** Robot asked what a text should say ("text mom"): the name, until the next question answers it. */
    private var askingTextFor: String? = null
    // Idea 7: after the answer to a spoken question, a beep and a few seconds' listening for the next (Settings › Voice).
    private val keepListening = PanelKeepListening(object : PanelKeepListening.Host {
        override val idle get() = shown && _state.value.phase.let { it == Phase.IDLE || it == Phase.SPEAKING }
        override val speaking get() = VoiceHub.tts.isSpeaking
        override val hearing get() = VoiceHub.stt.hearing
        override val listening get() = _state.value.phase == Phase.LISTENING
        override fun now() = SystemClock.elapsedRealtime()
        override fun post(delayMs: Long, run: () -> Unit) { VoiceHub.main.postDelayed({ run() }, delayMs) }
        override fun beep() = Cues.listening(false)
        override fun listen() = startListening()
        override fun stopQuietly() = stopListeningQuietly()
        override val inCall get() = HandsFreeHub.callActive(unknownIsCall = true)
    })

    /** VoiceHub.stopVoice, every stop of the voice: a stopped answer opens no window after it. */
    fun voiceStopped(why: TtsStop) = keepListening.voiceStopped(PanelKeepListening.byYou(why))
    /** That question was asked aloud: listen for the answer once Robot has said it. */
    private var listenAfterSpeech = false
    /** fix-car-stay-b: a listen is posted for 350 ms after Robot stopped talking (onSpeakingChanged). */
    private var listenDue = false
    /** fix-car-stay-b: Robot will listen for an answer once it stops talking, or in a moment (CarModeController.quietCheck). */
    val listenComing: Boolean get() = listenAfterSpeech || msgListenAfterSpeech || listenDue
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
    /** VoiceHub.stopVoice while Robot talks: a card's read-out under way ends unread (fix-car-long-card-b). */
    internal fun voiceStopped() = cardVoice.voiceStopped()

    private val cardVoice = PanelCardVoice(_state, replies, screenShare, object : PanelCardVoice.Port {
        override val carQuiet get() = CarModeController.quietTurn
        override val carTrusted get() = CarModeController.trusted
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
        override fun reword(cardId: String, text: String) = texting.reword(cardId, text)
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
    private val attach by lazy { PanelAttach(_state, replies, voice, fileAnswer.port) }
    private val fileAnswer = PanelFileAnswer(_state, replies, voice, object : PanelFileAnswer.Port {
        override val ctx get() = VoiceHub.app
        override val turnLangs get() = this@PanelController.turnLangs
        override fun sayInto(replyId: String, text: Phrase) = this@PanelController.sayInto(replyId, text)
        override fun finishReply(replyId: String, reason: DoneReason) = this@PanelController.finishReply(replyId, reason)
        override fun failReply(replyId: String, message: String) = this@PanelController.failReply(replyId, message)
        override fun speakSentence(s: String) = this@PanelController.speakSentence(s)
        override fun noteRead(text: String) = webSearch.noteRead(Executed(ActionStatus.DONE, "", results = listOf(mapOf("answer" to text))))
        override fun heardPeer(pairing: String) = PanelMessages.withPairings(setOf(pairing))
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
        override suspend fun rememberedChoice(kind: app.heyrobot.core.helpers.QuestionKind) = withContext(Dispatchers.IO) { MemoryPanel.choiceFor(kind.name) }
        override suspend fun askChoice(replyId: String, h: HelperConfig) = helperTurn.askChoice(replyId, h)
        override suspend fun handOff(replyId: String, question: String, h: HelperConfig) = helperTurn.handOff(replyId, question, h)
        override fun keepOffer(replyId: String, k: KeepChoice) = offers.showKeep(replyId, k)
        override fun keepChoice(k: KeepChoice, spoken: Boolean) { scope.launch { helperChoice.save(k, spoken).let { p -> notify(p.render(turnLangs)); if (_state.value.speak) speakRobot(said(p)) } } }
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
        override fun lastTurn(replyId: String) = app.heyrobot.core.feedback.ReportTurn.from(chat, replyId, reportLangs.heardBefore, reportLangs.replyBefore, System.currentTimeMillis())?.copy(conv = _state.value.convId)
        override fun useScreenshot() = Assistant.screenSharing(VoiceHub.app).second
        override fun heldShot() = screenShare.shot?.let { it to screenShare.pkg }
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
    private val helperChoice = PanelHelperChoice(object : PanelHelperChoice.Port {
        override val settings get() = VoiceHub.settings
        override val turnLangs get() = this@PanelController.turnLangs
        override fun locked() = Assistant.locked(VoiceHub.app)
        override fun askTarget() = this@PanelController.askTarget()
        override suspend fun keepInMemory(c: app.heyrobot.core.memory.MemoryChoice, text: String, spoken: Boolean) = withContext(Dispatchers.IO) { MemoryPanel.keepChoice(c, text, spoken) }
        override suspend fun dropFromMemory(kind: String) = withContext(Dispatchers.IO) { MemoryPanel.dropChoice(kind) }
    })
    /**
     * 1.1: the languages of the turn under way (core i18n): what Robot says in its reply language
     * ([Langs.say], decided once per question by ReplyLang), Robot's screens named in it and the cards' buttons in the menus'
     * ([Langs.ui]), Android's screens in the phone's. The model is told English whatever they are.
     */
    @Volatile private var turnLangs: Langs = Langs.EN
    @Volatile private var claimsHeld = false
    @Volatile private var replyBefore: Lang? = null
    /** 1.4: the languages of the turn before this one (the report card's Language guess). */
    private val reportLangs = app.heyrobot.core.feedback.ReportLangs()
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

    /** A card's question and buttons: the conversation's language, as its title (fix-124c, CardWords). */
    private val buttonLangs: Langs get() = CardWords.langs(turnLangs)

    /**
     * 1.1: the reply language of a question ([q]; [router]: a router matched it in its French or English words), once per turn
     * (core ReplyLang): the setting, else what LangId hears when it is sure, else the last turn's for a short line, else the
     * menus'. Nothing heard (a routine's Run now): the setting, else the menus'.
     */
    private fun decideLangs(q: String?, router: Lang? = null): Langs {
        val base = screenLangs()
        val now = System.currentTimeMillis()
        val setting = VoiceHub.settings.replyLang
        val guess = q?.takeIf { it.isNotBlank() && !app.heyrobot.core.output.HandsFreeRouter.eitherLanguage(it) }?.let { try { ConversationEngine.langId(VoiceHub.app).detect(it) } catch (_: Throwable) { null } }
        val say = if (q == null) ReplyLang.unheard(base.ui, setting) else ReplyLang.decide(guess, router, lastReply, now, base.ui, setting)
        if (q != null) { reportLangs.asked(guess?.lang, lastReply?.lang?.id); replyBefore = lastReply?.lang; lastReply = ReplyLang.Last(say, now) }
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
        scope.launch { _state.map { it.phase.spokenReplyComing(it.speak) }.distinctUntilChanged().collect { VoiceHub.replyComing(it) } }
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
        if (!speaking) { cardVoice.speakingStopped(); keepListening.speakingStopped() }
        if (!speaking && listenAfterSpeech) {
            listenAfterSpeech = false
            // "What should the text to Mom say?" was said aloud: the answer comes by voice too.
            if (shown && (askingTextFor != null || PanelSkills.waitsForWords)) {
                listenDue = true
                VoiceHub.main.postDelayed({ listenDue = false; if (shown && !VoiceHub.tts.isSpeaking && (askingTextFor != null || PanelSkills.waitsForWords) && _state.value.phase == Phase.IDLE) startListening() }, 350)
            }
        }
        // 0.7: after a conversation read aloud ("Reply, next, or stop?") or a reply card, one listen; and what Robot read
        // of a message counts as heard back for 15 s from now.
        if (!speaking) PanelMessages.quiet()
        if (!speaking && msgListenAfterSpeech) {
            msgListenAfterSpeech = false
            listenDue = true
            VoiceHub.main.postDelayed({ listenDue = false; if (shown && !VoiceHub.tts.isSpeaking && _state.value.phase == Phase.IDLE) startListening() }, 350)
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
        app.heyrobot.assistant.steps.StepVoice.panelShown(true)
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
        // The everyday set's Ask Robot (E7, assistant/ask): what another app shared, taken once by its one-time id and never
        // over the lock screen or during a phone lock; it starts a conversation of its own, so it never joins an earlier one.
        val shared = AskPanel.take(source, args, locked)
        // 1.3 point and read (P3): a Files page to read aloud (an id and a page, core FileRead), from Robot's own show alone.
        val fileRead = PanelFileRead.take(source, args)
        if (shared != null || (fileRead != null && !locked)) newConversation(locked) else when (open) {
            PanelContinuity.Open.LOAD -> loadConversation(convArg!!)
            PanelContinuity.Open.NEW_LOCKED -> newConversation(locked = true)
            PanelContinuity.Open.NEW -> newConversation(locked)
            PanelContinuity.Open.CONTINUE -> {}
            PanelContinuity.Open.CONTINUE_QUOTED -> quoteLockedTurns()
        }
        // Continued unlocked: never lock-screen safe again (physical-locked-conversation-carryover).
        startedLocked = PanelContinuity.stillLocked(startedLocked, locked)
        quiet.listen(VoiceHub.app) { VoiceHub.stopVoice(TtsStop.MUTED) }
        val returning = back && (open == PanelContinuity.Open.CONTINUE || open == PanelContinuity.Open.CONTINUE_QUOTED)
        screenShare.show(ignore = source == RobotVoiceService.SRC_APP || source == RobotVoiceService.SRC_SHARE || returning, returning = returning)
        if (!returning) {
            claudePieces = null
            val speak = when (s.speakReplies) {
                SpeakReplies.ALWAYS -> true
                SpeakReplies.NEVER -> false
                SpeakReplies.VOICE -> source == RobotVoiceService.SRC_WAKE || shared != null
            }
            // focusRequest restarts at 0 so a new panel's first composition does not pop the keyboard.
            _state.update { it.copy(speak = speak, notice = null, draft = null, focusRequest = 0) }
            quiet.reset()
            // 0.8: a headset's button or car mode (assistant/handsfree PanelHandsFree): a session routes the microphone first.
            handsFree = PanelHandsFree.onShow(source, args, locked)?.also { hf -> _state.update { it.copy(speak = hf.speak) } }
            wokeBy = source == RobotVoiceService.SRC_WAKE
            draftJpeg = null
        }
        // 1.3 point and read: back from the camera opened for a reading, it starts (PanelRead); any other show drops the photo.
        PanelRead.taken { Phrase.M(ReadStrings.asked).render(screenLangs()) }?.let { (asked, bmp) -> if (returning) readPicture(asked, bmp) }
        // A helper's conversation never carries over onto the lock screen unless that helper is allowed there.
        if (locked && s.helper(_state.value.helperMode)?.locked != true) endHelperMode()
        _state.update {
            it.copy(locked = locked, screenChip = ScreenChip.HIDDEN, sttAvailable = VoiceHub.stt.available, micHint = VoiceHub.stt.hint, partial = "",
                showId = it.showId + 1, claudeScreen = false, lockedTools = s.lockedTools, tips = if (returning) it.tips else tips(s, locked))
        }
        shared?.let(::attachShared)
        fileRead?.let { readFile(it, locked) }
        BrainKeeper.prewarm(VoiceHub.app)
        HelperLink.warm(VoiceHub.app, s)
        // 1.1: the reply language before the first question; the language table loads off the main thread (once per process).
        if (!slot.running) turnLangs = idleLangs()
        scope.launch(Dispatchers.IO) { try { ConversationEngine.langId(VoiceHub.app) } catch (t: Throwable) { RLog.w("langid_load", t) } }
        PanelMessages.onShow()
        // 1.4 Lists: your lists' names and items for the router, read off the main thread (PanelLists.route never opens them).
        refreshLists()
        // 1.5 L6: requests from other Robots waiting for you, as cards (PanelLink), unlocked only.
        if (!locked) linkChanged()
        // 1.5 L7: who you're paired with, read in the background, so LinkRouter never waits on :link.
        if (!locked) PanelLinkAsk.refresh(VoiceHub.app)
        // Signal later: what came back while out of range, or a question back online that needs your OK (unlocked only).
        if (!locked) app.heyrobot.assistant.later.PanelLater.onShow(scope, ::routineTurn) { q -> send(q) }
        // The voice models load on first use (VoiceHub's memory policy): speech recognition when the mic or the wake
        // word starts a question, the voice when a question is sent with spoken replies on. Nothing loads just for a show.
        RLog.i("panel_show", "src" to source?.let { RLog.Code(it) }, "locked" to locked, "turns" to chat.size)
        shownAtE = SystemClock.elapsedRealtime()
        val from = showSource(source, showFlags)
        VoiceLog.add(VoiceEvent.SESSION_SHOWN, from)
        screenShare.start(from, showFlags)
        // The question first: once it is attached, the capture mode can't switch under it ("Always echo cancellation").
        if ((source == RobotVoiceService.SRC_WAKE && handsFree == null) || handsFree?.listenNow == true ||
            app.heyrobot.core.car.CarTurn.listensQuietly(CarModeController.quietTurn, handsFree != null, voice = source == null && !locked)) startListening()
        // E7 (Q4): with what was shared on screen, Robot listens right away; without the speech model, the keyboard comes up.
        else if (shared != null && handsFree == null) if (VoiceHub.stt.available) startListening() else _state.update { it.copy(focusRequest = it.focusRequest + 1) }
        // 0.8: opened by Settings' Run now or a Ready card's tap.
        PanelRoutines.onShow(args)?.let { (label, work) -> routineTurn(label, work) }
        // 1.1: a skill's "Continue" notification tapped.
        PanelSkills.onShow(args)?.let { (label, work) -> routineTurn(label) { id, _ -> work(id, skillHost) } }
        // summary-todos: a summary's Add to calendar (SummaryActivity), taken once by its one-time id.
        args?.getString(SummaryTodoPanel.ARG)?.let { id ->
            when (val t = SummaryTodoPanel.open(id, locked || app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app), SystemClock.elapsedRealtime())) {
                is SummaryTodoPanel.Taken.Ready -> summaryTodoCalendar(t.ask)
                SummaryTodoPanel.Taken.Locked -> notify(app.heyrobot.core.i18n.ConversationStrings.todoLocked())
                SummaryTodoPanel.Taken.Gone -> notify(app.heyrobot.core.i18n.ConversationStrings.todoGone())
            }
        }
        VoiceHub.updateCapture()
    }

    private fun showSource(src: String?, flags: Int): ShowSource = when (src) {
        RobotVoiceService.SRC_WAKE -> ShowSource.WAKE
        RobotVoiceService.SRC_KEYGUARD -> ShowSource.KEYGUARD
        RobotVoiceService.SRC_APP -> ShowSource.APP
        RobotVoiceService.SRC_CAPTURE -> ShowSource.CAPTURE
        RobotVoiceService.SRC_HEADSET -> ShowSource.HEADSET
        RobotVoiceService.SRC_SHARE -> ShowSource.SHARE
        // Another app's showAssist (its arguments dropped by ShowGate); the corner swipe (and the assist gesture) come
        // without Robot's own source.
        else -> if (flags and VoiceInteractionSession.SHOW_SOURCE_APPLICATION != 0) ShowSource.OTHER_APP else ShowSource.SWIPE
    }

    fun onHide() {
        shown = false
        quiet.unlisten(VoiceHub.app)
        // 0.7: what was decrypted of memory goes with the panel.
        MemoryPanel.drop()
        // 0.8: a headset or car session ends with the panel, and lets go of the headset; a message that came meanwhile is announced.
        if (hidingFor == null) PanelHandsFree.onHide()
        app.heyrobot.assistant.handsfree.HandsFreeMessages.panelHidden()
        ConversationSession.panel(false)
        app.heyrobot.assistant.steps.StepVoice.panelShown(false)
        val st0 = _state.value
        // fb14: words held for the text box don't wait for the next window, which may be on the lock screen.
        _state.update { it.copy(fillInput = null) }
        if (st0.phase == Phase.LISTENING) VoiceLog.add(VoiceEvent.NO_QUESTION, NoQuestion.CLOSED)
        if (st0.busy && spokenTurn && hidingFor == null) VoiceLog.add(VoiceEvent.NO_REPLY, NoReply.STOPPED)
        VoiceLog.add(VoiceEvent.SESSION_HIDDEN, ((SystemClock.elapsedRealtime() - shownAtE) / 1000).toInt())
        cardVoice.stop()
        VoiceHub.stt.cancel()
        VoiceHub.listener?.wakePaused = false
        listenAfterSpeech = false
        msgListenAfterSpeech = false
        keepListening.cancel()
        PanelMessages.onHide(forApp = hidingFor == "action")
        PanelLink.onHide()
        if (hidingFor == null) askingTextFor = null
        val forCapture = hidingFor == "capture"
        // A turn never goes on behind a hidden panel: closing stops it, and a card nobody can tap any more is declined.
        // fix-car-132: hidden for an app, a reply in its 3 seconds still goes (PanelMessages.stop(forApp)).
        if (hidingFor == null || slot.confirmations.waiting) stopGenerating(TtsStop.CLOSED, forApp = hidingFor == "action")
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
        // 1.4 Lists: what was seen of your items goes with the panel (the list in focus stays with the conversation).
        PanelLists.drop()
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

    /** E7: one of Ask Robot's buttons (Explain, Translate…): your request in the menus' words, as if said; the listening stops first. */
    fun askShared(request: Phrase) {
        if (_state.value.draft?.shared == null) return
        stopListeningQuietly()
        send(request.render(screenLangs()))
    }

    /** 1.3 point and read (P3): Read aloud on Ask Robot's "Shared photo" chip: the photo :files decoded, read as one held is. */
    fun readShared() {
        val item = _state.value.draft?.shared?.takeIf { it.kind == app.heyrobot.core.ask.SharedKind.PHOTO } ?: return
        // Ask Robot never shows over the lock screen (AskPanel.take); locked since, what was shared goes, unread.
        if (Assistant.locked(VoiceHub.app)) return removeDraft()
        stopListeningQuietly()
        val bmp = item.jpeg?.let { PanelRead.bitmap(it) } ?: return notify(PanelStrings.unreadablePicture())
        readPicture(Phrase.M(ReadStrings.asked).render(screenLangs()), bmp)
    }

    /** E7: what another app shared goes with the next question, riding SCREEN (text, a voice message's words) or PHOTO. */
    private fun attachShared(item: app.heyrobot.assistant.ask.SharedItem) {
        draftJpeg = item.jpeg
        // "File this" leaves it alone (Robot Files has a share entry of its own): no earlier photo's bytes stay either.
        PanelFiles.picked(null, false)
        val kind = if (item.kind == app.heyrobot.core.ask.SharedKind.PHOTO) Attachment.PHOTO else Attachment.SCREEN
        _state.update { it.copy(draft = Draft(kind, item.thumb, shared = item), notice = null) }
    }

    // ------------------------------------------------------------------ conversation

    private fun refreshLists() = scope.launch(Dispatchers.IO) { PanelLists.refresh(VoiceHub.app) }.also { scope.launch(Dispatchers.IO) { app.heyrobot.assistant.logbook.PanelLogbook.refresh(VoiceHub.app) } }

    /** 1.4 Lists (LS12): your lists changed (Cmd.LISTS) or a conversation began: while the panel shows, the router reads them again. */
    fun listsChanged() {
        if (shown) refreshLists()
    }

    fun newConversation(locked: Boolean = _state.value.locked) {
        stopGenerating(TtsStop.OTHER)
        VoiceHub.stopVoice(TtsStop.OTHER)
        askingTextFor = null
        endHelperMode()
        offers.clear()
        helperChoice.forget()
        PanelCorrect.forget() // review Medium 4: "undo that" never reaches into the last conversation
        escalation.newConversation()
        helperDeclined = false
        // A photo from the last conversation never goes with this one.
        lastImage = null
        lastThumb = null
        PanelFiles.forget(VoiceHub.app)
        PanelLists.forget()
        listsChanged()
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
        helperChoice.forget()
        PanelCorrect.forget() // review Medium 4: "undo that" never reaches into the last conversation
        escalation.newConversation()
        helperDeclined = false
        lastImage = null
        lastThumb = null
        PanelAttach.forget()
        chat.clear()
        PanelLists.forget()
        listsChanged()
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
        groups = ToolRouter.used(c.turns, PanelLists.on(VoiceHub.app))
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
        actions.map { UiAction(newId(), it.tool, it.summary, it.status, it.detail, content = it.content, located = it.located) }, stopped = stopped, error = error,
        fromHelper = fromHelper, readBack = readBack, sources = sources, cost = costMicros.takeIf { it > 0 && VoiceHub.settings.helperShowCost }?.let { CostText.line(it / 1e6) },
        helper = helper, by = by, home = home, locked = locked, shared = shared)

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

    private val historySaves = app.heyrobot.core.SaveWatch()

    private fun persist() {
        val st = _state.value
        if (!st.keep || !VoiceHub.settings.saveHistory || chat.isEmpty()) return
        // 1.0: a conversation begun on the lock screen is saved with its turns marked, so History's Continue quotes them.
        val c = StoredConversation(st.convId, chat.first().time, System.currentTimeMillis(), PanelContinuity.stored(PanelFiles.saved(chat.toList()), startedLocked))
        scope.launch(Dispatchers.IO) { if (historySaves.tell(ConversationStore.save(VoiceHub.app, c))) notify(PanelStrings.historyNotSaved()) }
    }

    fun toggleSpeak() {
        val on = !_state.value.speak
        if (!on) VoiceHub.stopVoice(TtsStop.MUTED)
        quiet.tapped(on)
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
     * nobody sees it, a "Just do it" action) behind the app. [keepVoice] (openSkill, review Low 8): the turn's last line is
     * said after the page opens, so neither stops. Whether the page launched.
     */
    private fun leaveForRobot(h: PanelHost, i: Intent, keepVoice: Boolean = false): Boolean {
        if (!keepVoice) {
            stopGenerating(TtsStop.OTHER, forApp = true)
            VoiceHub.stopVoice(TtsStop.OTHER)
        }
        if (!h.launch(i)) return false
        hidingFor = "action"
        h.hidePanel()
        return true
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

    /**
     * The skill [id]'s page in Settings › Skills (a lock, door or alarm step refused by voice). Null once it has launched;
     * else why not (locked, the Phone lock's closed line, no panel). Review Low 8: the refusal's line is said after this,
     * so neither the voice nor the turn is stopped (the panel hides for an action, which keeps the voice).
     */
    fun openSkill(id: String): Phrase? {
        if (Assistant.locked(VoiceHub.app)) return unlockFirst(PanelStrings.unlockOpenApp())
        val h = host ?: return SkillStrings.addNoPage()
        return if (leaveForRobot(h, HandOffApps.robotPage(VoiceHub.app, AppPages.skillRoute(id)), keepVoice = true)) null else SkillStrings.addNoPage()
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
        // The chip's file or photo goes with it, so "file this" has nothing left of it. The screen's chip holds neither (a file
        // already sent keeps its Save to Robot Files).
        if (_state.value.draft?.kind.let { it == Attachment.PHOTO || it == Attachment.FILE }) PanelFiles.picked(null, false)
        draftJpeg = null
        PanelAttach.forget()
        _state.update { it.copy(draft = null) }
    }

    fun openCapture(mode: String) {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(if (mode == CaptureActivity.MODE_FILE) PanelStrings.unlockForFile() else PanelStrings.unlockForCamera()))
        // Not while a turn runs: it would go on hidden behind the camera. Said so, as a stopped move ends its read-back first (review-f).
        if (_state.value.busy || slot.running) return notify(PanelStrings.stillOnLastAskAgain())
        // A reading on a loaded model that can't see pictures: said before the photo, not after it (review finding 7).
        if (mode == CaptureActivity.MODE_READ) BrainKeeper.state.value.let { st ->
            if (st.readiness == BrainKeeper.Readiness.READY && (!st.vision || st.textOnly)) return notify(Phrase.M(ReadStrings.needsVision))
        }
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

    /** 1.3 point and read, from CaptureActivity's MODE_READ (same process): the photo, read once the panel is back. */
    fun onReadPicked(bmp: Bitmap) = PanelRead.hold(bmp)

    private fun readTurn(q: String, ask: ReadAloud.Ask): Boolean {
        if (ask == ReadAloud.Ask.CAMERA) {
            PanelRead.asked = q
            openCapture(CaptureActivity.MODE_READ)
            return true
        }
        val bmp = draftJpeg?.let { PanelRead.bitmap(it) } ?: return false.also { notify(PanelStrings.unreadablePicture()) }
        readPicture(q, bmp)
        return true
    }

    /** 1.3 point and read (P3): a Files page, opened here behind Robot Files' gate; its text layer read as words, else its pixels. */
    private fun readFile(p: app.heyrobot.core.read.FileRead.Page, locked: Boolean) {
        if (locked) return notify(unlockFirst(PanelStrings.unlockForFile()))
        val ctx = VoiceHub.app
        val asked = Phrase.M(ReadStrings.asked).render(screenLangs())
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                PanelFileRead.load(ctx, p) { Assistant.locked(ctx) || app.heyrobot.lock.PhoneLockNow.on(ctx) }
            }
            when (got) {
                is PanelFileRead.Got.Words -> readPicture(asked, null, got.text, file = true, private = got.private)
                is PanelFileRead.Got.Picture -> readPicture(asked, got.bmp, file = true, private = got.private)
                PanelFileRead.Got.Locked -> notify(unlockFirst(PanelStrings.unlockForFile()))
                PanelFileRead.Got.Unreadable -> notify(PanelStrings.fileUnreadable())
            }
        }
    }

    /**
     * [asked] and the photo read aloud (or P3's text layer, [words], read without the model), a turn of its own under a screen
     * turn's rules: untrusted from here, saved only with Keep. A Files page ([file]) is Robot Files' answer, under read_file's
     * rules (PanelFileAnswer): marked for travel mode, and a [private] file's words shown only (privateAnswer).
     */
    private fun readPicture(asked: String, bmp: Bitmap?, words: String? = null, file: Boolean = false, private: Boolean = false) {
        if (_state.value.busy || slot.running) return
        // Review finding 2: asking for a reading is asking to hear it, a tap too (ReadAloud.speaks); a hands-free session keeps its own.
        if (handsFree == null) _state.update { it.copy(speak = ReadAloud.speaks(VoiceHub.settings.speakReplies, it.speak)) }
        turnLangs = decideLangs(asked, ReplyLang.commandLang(asked))
        val now = System.currentTimeMillis()
        val user = ChatTurn(newId(), TurnRole.USER, asked, now, ReadAloud.ATTACHMENT)
        chat += user
        val replyId = newId()
        _state.update {
            it.copy(turns = it.turns + UiTurn(user.id, TurnRole.USER, asked, ReadAloud.ATTACHMENT, bmp?.let { Images.scaled(it, Images.THUMB_EDGE) }) +
                UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true), phase = Phase.THINKING, draft = null, partial = "", notice = null,
                keep = if (keptByUser) it.keep else false, locked = Assistant.locked(VoiceHub.app))
        }
        // Review of P3, finding 1: History marks it (ChatTurn.files), so travel mode turned on later hides it and ends the
        // conversation; a private file's: never said, a placeholder in History and backups, the conversation closed after, no screenshot.
        if (file) PanelFileAnswer.markFiles(replyId)
        if (file && private) fileAnswer.port.privateAnswer(replyId)
        draftJpeg = null
        lastActivity = now
        voice.start(replyId)
        if (_state.value.speak) VoiceHub.tts.prewarm()
        startTurn(replyId) { if (bmp != null) PanelRead.run(replyId, bmp, reading) else PanelRead.runText(replyId, words.orEmpty(), reading) }
    }

    private val reading = object : PanelRead.Port {
        override val you: Lang get() = turnLangs.say
        override val tiles: Boolean get() = app.heyrobot.sys.EverydaySettingsStore.current(VoiceHub.app).readTiles
        override fun firstReading() = PanelRead.firstReading(VoiceHub.app)
        override fun show(replyId: String, text: String) = replies.setText(replyId, text)
        override fun say(replyId: String, s: ReadAloud.Said) {
            val (line, words) = when (s) {
                is ReadAloud.Said.Robot -> s.phrase.said to null
                is ReadAloud.Said.Words -> s.text to (s.lang ?: "")
            }
            voice.say(replyId, line, _state.value.speak && !PanelFileAnswer.isPrivate(replyId))?.let { speakSentence(it, personal = words != null, words = words) }
        }
        override fun caution(replyId: String, p: Phrase) { notify(p); say(replyId, ReadAloud.Said.Robot(p)) }
        override fun refuse(replyId: String, p: Phrase) = localReply(replyId, p)
        override fun finish(replyId: String, reason: DoneReason) = finishReply(replyId, reason)
        override fun fail(replyId: String) = failReply(replyId, PanelStrings.wentWrongTryAgain().render(turnLangs))
    }

    /** From CaptureActivity (same process): the picture to send with the next question; [original], its own bytes for "file this". */
    fun onImagePicked(jpeg: ByteArray, thumb: Bitmap, original: ByteArray? = null, tooBig: Boolean = false) {
        draftJpeg = jpeg
        PanelAttach.forget()
        PanelFiles.picked(original, tooBig)
        _state.update { it.copy(draft = Draft(Attachment.PHOTO, thumb), notice = null, focusRequest = it.focusRequest + 1) }
    }

    /** chat-files (PanelAttach.attach, on the main thread): a file for the next question, or the one line why not. */
    internal fun onFilePicked(p: PanelAttach.Picked) {
        val h = p.held ?: return notify(p.notice ?: PanelStrings.fileUnreadable())
        draftJpeg = h.jpeg
        PanelAttach.hold(h)
        PanelFiles.picked(p.original, p.tooBig, h.name)
        _state.update { it.copy(draft = Draft(Attachment.FILE, p.thumb, h.name), notice = p.notice?.let { n -> Notice(n.render(screenLangs())) }, focusRequest = it.focusRequest + 1) }
    }

    /** chat-files: a file came while the phone is locked. */
    internal fun fileLocked() = notify(unlockFirst(PanelStrings.unlockForFile()))

    // ------------------------------------------------------------------ voice input

    fun micPressed() {
        // A card waits: the mic answers it ("yes", "no"), with "Answer cards by voice" on (off: a tap or a typed answer).
        if (_state.value.phase == Phase.CONFIRMING) {
            if (!VoiceHub.settings.voiceCards) return notify(PanelStrings.cardTypeHint())
            VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
            return cardVoice.micPressed()
        }
        // review-f Low: a stopped move ends its read-back first (up to 5 s); the mic says to ask again, never nothing.
        if (_state.value.busy || slot.running) return notify(PanelStrings.stillOnLastAskAgain())
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
            @Volatile private var cut = false
            override fun onCapReached() { cut = true }
            override fun onPartial(text: String) { main.post { _state.update { if (it.phase == Phase.LISTENING) it.copy(partial = text) else it } } }
            override fun onEndOfSpeech() {
                main.post {
                    // The wake word may listen again while the words are written down.
                    VoiceHub.listener?.wakePaused = false
                    _state.update { if (it.phase == Phase.LISTENING) it.copy(phase = Phase.TRANSCRIBING) else it }
                }
            }
            override fun onSpeechBack() {
                main.post {
                    VoiceHub.listener?.wakePaused = true
                    _state.update { if (it.phase == Phase.TRANSCRIBING) it.copy(phase = Phase.LISTENING) else it }
                }
            }
            override fun onFinal(text: String) {
                main.post {
                    endListening()
                    // Idea 7: in the window after an answer, nothing said or "that's all" ends it, quietly.
                    if (keepListening.heard(text)) return@post
                    // Read first: hearing ends the session, and that may close the panel.
                    val box = CarModeController.boxStays(PanelHandsFree.active)
                    PanelHandsFree.heard(text.isBlank(), cut && text.isNotBlank())
                    // No words came back: say so rather than going quiet. Cut off at the cap: never acted on.
                    if (text.isBlank()) missedQuestion(NoQuestion.EMPTY) else if (cut) { if (shown) holdCutWords(text, box) } else sendSpoken(text)
                }
            }
            override fun onError(reason: SpeechToText.Reason, message: String) {
                main.post {
                    endListening()
                    if (keepListening.missed(reason == SpeechToText.Reason.NO_SPEECH)) return@post
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
    /** Speech ran into the 30 s cap: it's only part of what was said, so it waits in the text box, and Robot says why. */
    internal fun holdCutWords(text: String, box: Boolean) {
        // fb14: where no text box stays (CarTurnEnd.boxStays), words held for a later window could show on the lock screen. Nothing's kept.
        val words = if (box) PanelStrings.cutAtCap() else PanelStrings.cutAtCapNoBox()
        _state.update { if (box) it.copy(fillInput = text, notice = Notice(words.render(turnLangs)), focusRequest = it.focusRequest + 1) else it.copy(notice = Notice(words.render(turnLangs))) }
        cue(Cue.CHIME)
        if (_state.value.speak) speakRobot(words.said)
    }

    /** The text box has taken [PanelState.fillInput]. */
    fun fillTaken() = _state.update { it.copy(fillInput = null) }

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

    /**
     * :lock says a lock just started (Cmd.PHONE_LOCK), with the panel open: what was said before it can't be read during the
     * lock, so it goes now, not at the next turn or when the panel closes (review-lock 3). A turn under way stops first. A panel
     * that's leaving for an app (PanelLock's yes, its words still being said) gets the time to go; its next show starts afresh.
     * Car mode your own car started during your own lock covers nothing, and clears nothing.
     */
    fun lockStarted() {
        if (!shown) return
        scope.launch {
            if (hidingFor != null) { delay(LOCK_LEAVE_MS); if (!shown) return@launch }
            val app = VoiceHub.app
            val (on, car) = withContext(Dispatchers.IO) { Assistant.locked(app) to PhoneLockNow.carOwn(app) }
            if (car || !PanelContinuity.newLockedTurn(on, startedLocked, chat.isEmpty() && _state.value.turns.isEmpty())) return@launch
            if (_state.value.busy || slot.running) { stopGenerating(TtsStop.OTHER); delay(LOCK_STOP_MS) }
            newConversation(locked = true)
        }
    }

    /** A lock came on while the panel was open: what was said before it stays out of this turn (core PanelContinuity). */
    private fun newLockedTurn() {
        if (_state.value.busy || slot.running) return
        if (PanelContinuity.newLockedTurn(Assistant.locked(VoiceHub.app), startedLocked, chat.isEmpty())) newConversation(locked = true)
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
    /** fix-french-turn-c: which way [send] took a turn ([path]), with the words' language, never the words. */
    private fun sendPath(path: SendPath, q: String, fromVoice: Boolean) =
        RLog.i("send_path", "path" to path, "lang" to ReplyLang.commandLang(q), "voice" to fromVoice)

    /** fix-french-turn-c: the ways [send] can take or refuse a turn. */
    private enum class SendPath { EMPTY, CONFIRM_CARD, REPLY_CARD, ROUTINE_CONTROL, BUSY_CARD, BUSY, OFFER_YES, OFFER_HELPER, OFFER_HERE, SCREEN_QUESTION, TURN }

    fun send(text: String, fromVoice: Boolean = false): Boolean {
        newLockedTurn()
        keepListening.cancel()
        val st = _state.value
        val q = text.trim()
        if (q.isEmpty() && st.draft == null) { sendPath(SendPath.EMPTY, q, fromVoice); return false }
        // A typed "yes" to a card or an offer is a typed turn too (review-quiet-when-silent, 5); never during a running turn.
        if (!(st.busy || slot.running) || st.phase == Phase.CONFIRMING) quiet.turn(VoiceHub.app, fromVoice)
        // A card waits: a typed or spoken "yes" or "no" answers it (Settings › Safety & privacy).
        if (st.phase == Phase.CONFIRMING && st.draft == null && cardVoice.answer(q, fromVoice)) { sendPath(SendPath.CONFIRM_CARD, q, fromVoice); quiet.tell(); return true }
        // 0.7: a reply card waiting: "yes" (where allowed), "no", or "stop" in its 3 seconds.
        if (st.draft == null && PanelMessages.answerCard(q, fromVoice)) { sendPath(SendPath.REPLY_CARD, q, fromVoice); return true }
        // 0.8: "next", "skip", "repeat" or "stop" while a routine or the brief speaks (or just paused for "hey robot").
        if (q.isNotEmpty() && st.draft == null && RoutineVoice.control(q)) { sendPath(SendPath.ROUTINE_CONTROL, q, fromVoice); return true }
        if (st.busy || slot.running) {
            sendPath(if (st.phase == Phase.CONFIRMING) SendPath.BUSY_CARD else SendPath.BUSY, q, fromVoice)
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
        if (st.draft == null) offers.waiting { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.takeIf { HelperRouter.isYes(q, offers.namesFor(it, names)) }?.let {
            sendPath(SendPath.OFFER_YES, q, fromVoice)
            acceptOffer(it, tapped = false)
            quiet.tell()
            return true
        }
        // "The helper" or "here", when an offer waits: hands free, as a yes or its X.
        if (st.draft == null) offers.waiting { k -> st.turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.id == k }?.let { id ->
            when (app.heyrobot.core.helpers.HelperChoice.answer(q, names, offers.helperOf(id))) {
                app.heyrobot.core.helpers.HelperChoice.Picked.HELPER -> if (!offers.isKeep(id)) { sendPath(SendPath.OFFER_HELPER, q, fromVoice); acceptOffer(id, tapped = false); quiet.tell(); return true }
                app.heyrobot.core.helpers.HelperChoice.Picked.HERE -> { sendPath(SendPath.OFFER_HERE, q, fromVoice); offers.dismiss(id); return true }
                null -> {}
            }
        }
        VoiceHub.stopVoice(TtsStop.NEW_QUESTION)
        spokenTurn = fromVoice
        turnSaid = q
        val textingFor = askingTextFor
        askingTextFor = null
        helperMode.expire()
        // 1.3 point and read (PanelRead, core ReadAloud.ask): "read this", « lis ça » said alone reads the photo held aloud;
        // holding nothing, the camera opens for one (locked: openCapture's unlock line). Debug builds: the Test Bench's demo.
        PanelRead.demo(VoiceHub.app, q)?.let { readPicture(q, it); return true }
        // Conversation mode's commands first (0.5.0, docs/conversation-mode.md 5.4): "transcribe this conversation",
        // and while one runs "stop transcribing", "pause", "resume". Never the model's to decide.
        // 0.7, your messages (PanelMessages, core MessagesRouter) before anything else: "read my messages", "reply …"; and a
        // message's own words heard back while Robot read it are never a request (not even "remember that …" or "report a bug").
        val msgCmd = if (q.isNotEmpty() && st.draft == null) PanelMessages.route(q, fromVoice) else null
        val convCmd = if (q.isNotEmpty() && st.draft == null && msgCmd == null) ConversationRouter.route(q, ConversationSession.running) else null
        // E10: hands-free waits for your answer (a call's "Who?", a dictated reply): it's the answer, not a request for another router.
        // Messages' echo check and a running transcript's own words stay ahead of it; hands-free's own gate below takes the words.
        // F37: so does "What should the text to Mom say?": every router below leaves your answer to the text (PanelTexting).
        val textWaits = q.isNotEmpty() && st.draft == null && !_state.value.inHelperMode && textingFor != null
        val hfWaits = textWaits || (q.isNotEmpty() && st.draft == null && !_state.value.inHelperMode && PanelHandsFree.waitsForAnswer)
        // Point and read after them (review M3): a dictated "what does it say" is the answer; Car mode opens no camera.
        val readAsk = if (st.draft?.shared == null && textingFor == null && !_state.value.inHelperMode) ReadAloud.panelAsk(q, st.draft?.kind ?: Attachment.NONE,
            draftJpeg != null, taken = hfWaits || msgCmd != null || convCmd != null, car = CarModeController.on) else null
        if (readAsk != null) return readTurn(q, readAsk)
        // 0.7: "report a bug", "I have an idea", « signaler un bogue »: Send feedback opens in the app; the panel sends nothing.
        // After the messages router and its echo check (a message read aloud saying "report a bug: …" is the message's), before the model.
        val fbCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null) FeedbackRouter.route(q) else null
        // 1.1: Phone lock's phrases ("lock the phone until 7", "how long is left", "end the lock"), only from your own words.
        val lockCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode &&
            !TurnRules.untrusted(chat)) PanelLock.route(q) else null
        // 0.8: routines' phrases next ("every weekday at 7, tell me the weather", "brief me", "remind me in the morning to …"),
        // outside a helper's conversation. A routine said in a conversation holding screen text needs a tap.
        val routineCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !_state.value.inHelperMode)
            PanelRoutines.route(q, TurnRules.untrusted(chat), fresh = chat.none { it.role == TurnRole.USER }) else null
        // 1.1: skills' phrases next (assistant/skills PanelSkills), under the routines' conditions.
        val skillCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            !_state.value.inHelperMode) PanelSkills.route(q) else null
        // D8: what waited for your next words (the steps, a new name) is dropped once they went elsewhere.
        if (skillCmd == null) PanelSkills.drop()
        // 1.4: "file this", « classe ça » (PanelFiles, D15: after skills, before hands-free), with what the panel holds; F8: Robot
        // Files' other words (core FilesRouter: a rule, move, find, Inbox, delete as a tap-only card).
        val fileCmd = if (q.isNotEmpty() && !hfWaits && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null && skillCmd == null &&
            !_state.value.inHelperMode) PanelFiles.route(q) else null
        // Read it to me (read-it-to-me-plan.md §4): "read me my last note", "pause reading" (PanelRead, core ReadRouter; after Files, before Lists).
        val readCmd = if (q.isNotEmpty() && !hfWaits && (st.draft == null || app.heyrobot.assistant.read.PanelRead.sharedText(st.draft?.shared) != null) && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            skillCmd == null && fileCmd == null && !_state.value.inHelperMode) app.heyrobot.assistant.read.PanelRead.route(q) else null
        // 1.4 Lists: "add oat milk to the shopping list", "take eggs off" (PanelLists, core ListsRouter; D9: after Files, before
        // hands-free), your own words only: with an attachment, the model reads it (add_to_list, LS4).
        // recipe-list: a recipe ask may come with what another app shared (its text, never the screen's picture).
        val listCmd = if (q.isNotEmpty() && !hfWaits && (st.draft == null || st.draft.shared?.text != null && app.heyrobot.core.lists.RecipeList.ask(q) != null) && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            skillCmd == null && fileCmd == null && readCmd == null && !_state.value.inHelperMode) PanelLists.route(VoiceHub.app, q, st.draft?.shared?.text) else null
        // LS10: "undo that" only right after a list change; your words went elsewhere, so it's forgotten (a card's Undo stays).
        if (q.isNotEmpty() && listCmd == null) PanelLists.otherTurn()
        // correct-last: "undo that", "no, 6:45" about Robot's own last action (PanelCorrect; after Lists, which keeps its own).
        val fixCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            skillCmd == null && fileCmd == null && readCmd == null && listCmd == null && !_state.value.inHelperMode) PanelCorrect.route(q) else null
        // 1.5 L7: your words to another Robot (core LinkRouter, D15: after Robot Files and Lists, before the everyday set and hands-free).
        val linkCmd = if (q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null && routineCmd == null &&
            skillCmd == null && fileCmd == null && readCmd == null && listCmd == null && !_state.value.inHelperMode) PanelLinkAsk.route(VoiceHub.app, q) else null
        // The everyday set (everyday-set-plan.md §2.1, D1): music, sums and What went online, in that order, after Files and Lists
        // (Robot to Robot's LinkRouter goes between) and before hands-free, under Lists' conditions and without an attachment.
        val everydayOpen = q.isNotEmpty() && !hfWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && lockCmd == null &&
            routineCmd == null && skillCmd == null && fileCmd == null && readCmd == null && listCmd == null && linkCmd == null && !_state.value.inHelperMode
        val musicCmd = if (everydayOpen) app.heyrobot.assistant.music.PanelMusic.route(q) else null
        val sumsCmd = if (everydayOpen && musicCmd == null) app.heyrobot.assistant.sums.PanelSums.route(q) else null
        val onlineCmd = if (everydayOpen && musicCmd == null && sumsCmd == null) app.heyrobot.assistant.online.PanelOnline.route(q) else null
        // Spoken log: "log that …", "when did I last …" (PanelLogbook, core LogbookRouter): after the everyday set, before memory and the model.
        val logbookCmd = if (everydayOpen && musicCmd == null && sumsCmd == null && onlineCmd == null) app.heyrobot.assistant.logbook.PanelLogbook.route(VoiceHub.app, q) else null
        // "When did I last …" (PanelLastTime, core LastTime): Files' receipts, past calendar events and notes, after the everyday set and the Logbook.
        val lastCmd = if (everydayOpen && musicCmd == null && sumsCmd == null && onlineCmd == null && logbookCmd == null) PanelLastTime.route(q) else null
        val everydayCmd = musicCmd != null || sumsCmd != null || onlineCmd != null || logbookCmd != null || lastCmd != null
        // 0.8: then headset and car mode's phrases, and "call …" (the call tool's own words). A list's phrase is never theirs (the
        // routing corpus, ListsRoutingCorpusTest), and Lists' turn comes first below.
        val handsFreeCmd = if (q.isNotEmpty() && !textWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && routineCmd == null && skillCmd == null &&
            fileCmd == null && readCmd == null && linkCmd == null && !everydayCmd && !_state.value.inHelperMode) PanelHandsFree.route(q) else null
        val routineOrHandsFree = routineCmd != null || skillCmd != null || handsFreeCmd != null || fileCmd != null || readCmd != null || listCmd != null || fixCmd != null || linkCmd != null || everydayCmd
        // 0.9: "search the web for …" next: web_search with exactly your words, without the model (WebRouter); outside a
        // helper's conversation and without an attachment. 1.0: or a search in your browser ("search DuckDuckGo for …").
        // W4: "summarize this page" (a link in your words, on the screen, or the page of the last web answer) before them.
        // A link shared into Robot (a browser's Share) with nothing typed or "summarize" goes the same way.
        val pageAsk = if ((q.isNotEmpty() && st.draft == null || st.draft?.shared != null) && !textWaits && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree &&
            !_state.value.inHelperMode) PanelPageSummary.route(q, st.draft?.shared, PanelPageSummary::pagesOn) else null
        val webQuery = if (q.isNotEmpty() && !textWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree &&
            pageAsk == null && !_state.value.inHelperMode) BrowserRouter.route(q) else null
        // 0.9: then "run backup", your own Termux script named in your own words (TermuxTool.direct), the same way.
        val termux = if (q.isNotEmpty() && !textWaits && st.draft == null && msgCmd == null && convCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null &&
            !_state.value.inHelperMode) TermuxTool.direct(VoiceHub.app, q, typed = !fromVoice) else null
        // 0.7: memory's commands next ("remember that …", "forget that", "what do you know about me?"), Robot's own even in a
        // helper's conversation: what you ask Robot to remember never goes to a helper (core MemoryRouting).
        // "Always ask the helper for these", "ask the helper instead": before memory and the helpers' phrases, whose words they share.
        val choiceRoute = if (q.isNotEmpty() && st.draft == null && !hfWaits && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null &&
            termux == null) helperChoice.route(q, names) else null
        val memRoute = if (q.isNotEmpty() && !textWaits && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null &&
            termux == null && choiceRoute == null) MemoryPanel.route(q, Assistant.locked(VoiceHub.app)) else null
        // 0.7.0: "free up memory", "close apps", « libère de la mémoire »: Robot's own answer (what Android reports, what
        // unloading its model gives, the apps used lately) and a card to pick from (core FreeUp), not the model's guess.
        val freeAsk = if (q.isNotEmpty() && !textWaits && st.draft == null && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null &&
            termux == null && memRoute == null && choiceRoute == null) FreeUp.asked(q) else null
        // The helpers' phrases next ("ask ChatGPT …", "go get Jarvis"), then texting without the model, then the model (FastPath).
        var cmd = if (q.isNotEmpty() && !textWaits && convCmd == null && msgCmd == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null &&
            termux == null && memRoute == null && freeAsk == null && choiceRoute == null) HelperRouter.route(q, names) else null
        // "Thanks Jarvis" outside a helper's conversation is ordinary talk.
        if (cmd == HelperCommand.Leave && !_state.value.inHelperMode) cmd = null
        // "Not Jarvis", "you tell me": Robot answers, now and for the rest of the conversation (a helper's conversation ends too).
        // Only an explicit "ask …" or "go get …" brings a helper back.
        if (q.isNotEmpty() && !textWaits && memRoute == null && fbCmd == null && !routineOrHandsFree && webQuery == null && pageAsk == null && termux == null && freeAsk == null && choiceRoute == null &&
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
            listCmd != null -> listCmd.lang
            linkCmd != null -> linkCmd.lang
            fixCmd != null -> fixCmd.lang
            musicCmd != null -> musicCmd.lang
            sumsCmd != null -> sumsCmd.lang
            lastCmd != null -> lastCmd.lang
            logbookCmd != null -> logbookCmd.lang
            onlineCmd != null -> onlineCmd.lang
            else -> freeAsk?.let { if (it) Lang.FR else Lang.EN } ?: ReplyLang.commandLang(q)
        })
        lastRouter = listOf(msgCmd to "MessagesRouter", convCmd to "ConversationRouter", fbCmd to "FeedbackRouter", lockCmd to "LockRouter", // l10n: not text
            routineCmd to "RoutineRouter", skillCmd to if (skillCmd is PanelSkills.Routed.Plan) "PlanRouter" else "SkillRouter", fileCmd to "FilesRouter", readCmd to if (readCmd is app.heyrobot.core.read.StepsOf || readCmd is app.heyrobot.core.read.StepSay) "StepRouter" else "ReadRouter", listCmd to "ListsRouter", fixCmd to "CorrectRouter", linkCmd to "LinkRouter", musicCmd to "MusicRouter", // l10n: not text
            sumsCmd to "SumsRouter", lastCmd to "LastTimeRouter", onlineCmd to if (onlineCmd is app.heyrobot.core.online.LaterCommand) "LaterRouter" else "OnlineRouter", logbookCmd to "LogbookRouter", handsFreeCmd to "HandsFreeRouter", termux to "TermuxRouter", cmd to "HelperRouter", choiceRoute to "HelperRouter", // l10n: not text
            pageAsk to "PageSummaryRouter", webQuery to if (webQuery is WebRoute.Browser) "BrowserRouter" else "WebRouter").firstOrNull { it.first != null }?.second ?: "model" // l10n: not text
        // Your own words about the screen, with no other router's claim on them: the screen goes with them, on any tab (core ScreenQuestion).
        if (app.heyrobot.core.ScreenQuestion.asks(q) && st.draft == null && !_state.value.inHelperMode && textingFor == null && !PanelSkills.waitsForWords &&
            listOf(msgCmd, convCmd, fbCmd, lockCmd, routineCmd, skillCmd, fileCmd, readCmd, listCmd, fixCmd, linkCmd, musicCmd, sumsCmd, lastCmd, onlineCmd, logbookCmd, handsFreeCmd, termux, memRoute, freeAsk, cmd, choiceRoute, webQuery, pageAsk).all { it == null }) {
            sendPath(SendPath.SCREEN_QUESTION, q, fromVoice)
            if (screenShare.attachAsked()) return send(text, fromVoice)
            if (fromVoice) cue(Cue.CHIME)
            return false
        }
        offers.drop()
        val attachment = st.draft?.kind ?: Attachment.NONE
        val chatFile = if (attachment == Attachment.FILE) PanelAttach.take() else null
        // E7: what another app shared, riding the screen's or the photo's place (never the screen itself).
        val sharedNow = st.draft?.shared
        val fileWhat = fileCmd?.let { PanelFiles.what(attachment, screenShare.shot) }
        val image = when (attachment) {
            Attachment.PHOTO -> draftJpeg
            Attachment.SCREEN -> if (sharedNow != null) null else screenShare.shot?.let { Images.jpeg(it) }
            Attachment.FILE -> chatFile?.jpeg
            Attachment.NONE -> null
        }
        // Only a photo: "ask Jarvis about this photo" must never pick up a screenshot.
        if (attachment == Attachment.PHOTO && image != null) {
            lastImage = image
            lastThumb = st.draft?.thumb
        }
        val thumb = st.draft?.thumb
        val sText = if (attachment == Attachment.SCREEN) sharedNow?.text ?: screenShare.text else null
        val history = chat.toList()
        val now = System.currentTimeMillis()
        // "Ask Jarvis about my screen" said without attaching it is still a screen question: the conversation is
        // personal from here on, and it is saved only with Keep.
        val marked = if (attachment != Attachment.NONE) attachment else (cmd as? HelperCommand.Ask)?.extra ?: Attachment.NONE
        // "Keep screenshots and photos in history" (off by default): the thumbnail and the screen's text go into the saved
        // conversation, for History only (never replayed to the model, never sent to a helper).
        val kept = VoiceHub.settings.let { it.keepMedia && it.saveHistory } && attachment != Attachment.NONE && attachment != Attachment.FILE
        // 0.7: a message's words heard back while Robot read it are the message's, never your question: History keeps their
        // placeholder and the model is given it (core ChatTurn.messages), never the words.
        val heardBack = msgCmd is PanelMessages.Routed.Echo
        // 1.4 Lists (LS10): a secret a list refused is kept as memory's private placeholder (PanelLists.question).
        val user = ChatTurn(newId(), TurnRole.USER, memRoute?.let { MemoryPanel.question(it, q) } ?: listCmd?.let { PanelLists.question(it, q) } ?: q.ifEmpty { Prompt.defaultQuestion(attachment, sharedNow != null) }, now, marked,
            picture = if (kept) st.draft?.thumb?.let { b -> java.util.Base64.getEncoder().encodeToString(Images.jpeg(b, Images.THUMB_EDGE, 70)) } else null,
            screenText = if (kept) sText?.let(ScreenText::sanitize)?.takeIf { it.isNotBlank() }?.let { ToolCalls.safeTake(it, ConversationCodec.MAX_SCREEN_TEXT) } else null,
            shared = sharedNow != null, messages = heardBack, kept = if (heardBack) app.heyrobot.core.messages.MessageReading.HEARD_BACK else null, fileName = chatFile?.name)
        chat += user
        val way = if (memRoute != null || freeAsk != null || choiceRoute != null || attachment == Attachment.FILE) FastPath.Way.MODEL else FastPath.of(cmd, _state.value.inHelperMode && fbCmd == null, attachment)
        val toHelper = way == FastPath.Way.HELPER
        // Groups are only ever added: a question that needs a new one rebuilds the conversation once (:brain).
        if (cmd == null && !toHelper && convCmd == null && msgCmd == null && fbCmd == null && memRoute == null && freeAsk == null) groups = groups + ToolRouter.route(q, PanelLists.on(VoiceHub.app))
        val replyId = newId()
        // Decided per turn: a screen turn stops saving until the person taps Keep.
        // E7: so is a question about what another app shared, a photo included.
        // W4 (review L2): so is a page picked off the screen.
        val pagePick = pageAsk?.let { PanelPageSummary.pick(it, sharedNow, screenShare.text, _state.value.turns) }
        val keep = if ((marked == Attachment.SCREEN || sharedNow != null || pagePick?.from == app.heyrobot.core.web.PageSummaryRouter.From.SCREEN) && !keptByUser) false else st.keep
        // Screen text, a picture, a read tool's findings or a helper's words anywhere in this conversation: every action asks first.
        val untrusted = TurnRules.untrusted(chat)
        // fix-123-d: a note you dictate is add_note's call in your words, never left to the model to call (core NoteDictation.direct).
        val dictated = if (way == FastPath.Way.MODEL && cmd == null && attachment == Attachment.NONE && !untrusted && !_state.value.inHelperMode)
            app.heyrobot.core.NoteDictation.direct(q) else null
        // The helper this turn goes to: the one named, or the one whose conversation is on. 1.0.0: "ask the helper …" names
        // none (HelperName.ANY_ID): one that can take it now, the one you chose first (HelperSettings.askTarget); none, and
        // PanelHelperTurn.run says what's missing.
        val askedId = (cmd as? HelperCommand.Ask)?.helper ?: (cmd as? HelperCommand.Join)?.helper ?: _state.value.helperMode
        val turnHelper = if (!toHelper) null else if (askedId == HelperName.ANY_ID) askTarget() else VoiceHub.settings.helper(askedId)
        val api = turnHelper != null && turnHelper.on && HelperSettings.route(turnHelper, keySaved(turnHelper)) == AskRoute.API
        _state.update {
            it.copy(
                turns = it.turns + UiTurn(user.id, TurnRole.USER, user.text, marked, st.draft?.thumb, fileName = user.fileName, shared = sharedNow != null) +
                    UiTurn(replyId, TurnRole.ASSISTANT, "", streaming = true, fromHelper = api && !(cmd is HelperCommand.Join && cmd.question == null),
                        helper = turnHelper?.id, by = turnHelper?.name, home = turnHelper?.home == true),
                phase = Phase.THINKING, draft = null, partial = "", notice = null, keep = keep,
                locked = Assistant.locked(VoiceHub.app),
                helperStatus = if (api) words(turnHelper!!).thinkingText.render(screenLangs()) else null,
            )
        }
        quiet.tell()
        RLog.i("send_path", "path" to SendPath.TURN, "lang" to turnLangs.say, "voice" to fromVoice)
        draftJpeg = null
        if (attachment == Attachment.FILE) PanelFiles.sentWith(user.id)
        turnShared = sharedNow != null
        lastActivity = now
        voice.start(replyId)
        // The reply will be read aloud: the voice loads while the model thinks.
        if (st.speak) VoiceHub.tts.prewarm()
        val guard = EscalationGuard()
        // What "ask the helper instead" would send next: only a question that reaches a model, as it was asked.
        val asked = app.heyrobot.core.helpers.HelperChoice.Asked(user.text, replyId, null, marked != Attachment.NONE || sharedNow != null || chatFile != null, heardBack,
            Assistant.locked(VoiceHub.app))
        if (choiceRoute == null) helperChoice.forget()
        // correct-last (review High 2): another router may act where PanelCorrect can't see, so the older action is forgotten;
        // a model turn gets it back (its own actions replace it in ToolExecutor).
        val heldFix = PanelCorrect.held().also { if (fixCmd == null) PanelCorrect.forget() }
        startTurn(replyId) {
            when {
                choiceRoute != null -> choiceTurn(replyId, choiceRoute, history, untrusted, guard, fromVoice)
                msgCmd != null -> PanelMessages.run(replyId, msgCmd)
                convCmd != null -> conversationMode.run(replyId, convCmd)
                fbCmd != null -> feedback.run(replyId, fbCmd)
                lockCmd != null -> PanelLock.handle(replyId, lockCmd, routineHost, said = q)
                routineCmd != null -> PanelRoutines.handle(replyId, routineCmd, skillHost, said = q)
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
                chatFile != null && chatFile.text != null -> attach.answer(replyId, user.text, chatFile)
                readCmd != null -> localReply(replyId, app.heyrobot.assistant.read.PanelRead.handle(VoiceHub.app, readCmd, sharedNow))
                listCmd != null -> withContext(Dispatchers.IO) { PanelLists.handle(VoiceHub.app, replyId, listCmd, turnLangs, spoken = fromVoice) }.let { r ->
                    r.undid?.let(::listUndone)
                    localReply(replyId, r.text, r.text)
                    // After the reply ends: its end settles a card still waiting as declined, and Empty's waits for a tap.
                    r.card?.let { replies.upsertAction(replyId, it) }
                    r.list?.let { replies.setList(replyId, it) }
                }
                fixCmd != null -> withContext(Dispatchers.IO) {
                    // review-d Low: "stop" or the wake word ends the move, and its line is shown, not spoken (review-e: by startTurn's stop).
                    val job = coroutineContext[kotlinx.coroutines.Job]!!
                    PanelCorrect.handle(VoiceHub.app, fixCmd, object : PanelCorrect.Hands by executor() { override fun stopped() = !job.isActive }) { Assistant.locked(VoiceHub.app) || app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app) }
                        .let { PanelCorrect.hold(replyId, it) }
                }.let { r ->
                    PanelCorrect.taken(replyId)
                    // review Medium 4: the card's own line is the reply (its Undo may not have worked); a card gone is said so.
                    val card = r.card
                    val detail = { st: PanelState -> st.turns.firstNotNullOfOrNull { t -> t.actions.firstOrNull { it.id == card } }?.detail }
                    if (card == null) localReply(replyId, r.line, r.line)
                    else if (!undo(card)) localReply(replyId, CorrectStrings.goneSince(), CorrectStrings.goneSince())
                    else PanelCorrect.cardSaid(kotlinx.coroutines.withTimeoutOrNull(3_000) { _state.first { detail(it) != null } }?.let(detail), turnLangs).let { localReply(replyId, it, it) }
                }
                musicCmd != null -> app.heyrobot.assistant.music.PanelMusic.handle(replyId, musicCmd, routineHost)
                sumsCmd != null -> app.heyrobot.assistant.sums.PanelSums.handle(replyId, sumsCmd, routineHost)
                lastCmd != null -> PanelLastTime.handle(replyId, lastCmd, routineHost)
                onlineCmd != null -> app.heyrobot.assistant.online.PanelOnline.handle(replyId, onlineCmd, routineHost)
                logbookCmd != null -> app.heyrobot.assistant.logbook.PanelLogbook.handle(VoiceHub.app, replyId, logbookCmd, routineHost)
                linkCmd != null -> linkTurn(replyId, linkCmd)
                handsFreeCmd != null -> PanelHandsFree.handle(replyId, handsFreeCmd, routineHost)
                pageAsk != null -> PanelPageSummary.handle(replyId, pageAsk, skillHost, pagePick) { _state.update { s -> s.copy(turns = s.turns.map { if (it.id == replyId) it.copy(page = true) else it }) } }
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
                // E7: a conversation holding what another app shared sends it to a helper only on a tap (Personal.SHARED).
                way == FastPath.Way.HELPER -> {
                    helperChoice.asked(asked.copy(helper = turnHelper?.id))
                    helperTurn.run(replyId, cmd, turnHelper, user.text, attachment, sText, image, thumb, history, fromVoice,
                        fromAnotherApp = sharedNow != null || Personal.SHARED in HelperContext.personal(history))
                }
                // A plain request to text someone (or the answer to "what should it say?") goes straight to send_text.
                way == FastPath.Way.TEXT && texting.direct(replyId, q, textingFor, fromVoice, untrusted) -> {}
                dictated != null -> noteTurn(replyId, dictated)
                else -> {
                    PanelCorrect.keep(heldFix)
                    helperChoice.asked(asked)
                    // Wikipedia P4: the copy on this phone, after every command and memory, before the model alone (core WikiTurn).
                    // fix-train-14: the index's own answers don't keep a second screenshot question from it (ShotAsk.untrusted);
                    // with steps open, a question for the page it doesn't answer says so (StepVoice.silent, after Wikipedia), never the model.
                    if (!shots.run(replyId, q, turnLangs, attachment != Attachment.NONE || sText != null, _state.value.inHelperMode, app.heyrobot.core.shots.ShotAsk.untrusted(chat)) &&
                        !wiki.run(replyId, q, history, attachment != Attachment.NONE || sText != null, _state.value.inHelperMode, TurnRules.untrustedForCopy(chat)) &&
                        app.heyrobot.assistant.steps.StepVoice.silent(q, attachment != Attachment.NONE || sText != null || _state.value.inHelperMode)?.let { localReply(replyId, it); true } != true)
                        runTurn(replyId, user.text, attachment, sText, image, history, untrusted, guard, fromVoice)
                }
            }
        }
        return true
    }

    /**
     * "Always ask the helper for these", "ask the helper instead", "ask Robot instead". A re-send goes as "ask the helper …" does,
     * through the same checks (PanelHelperTurn.run), or to Robot's model with no helper brought back by itself ([guard] is used).
     */
    private suspend fun choiceTurn(replyId: String, route: PanelHelperChoice.Route, history: List<ChatTurn>, untrusted: Boolean, guard: EscalationGuard, fromVoice: Boolean) {
        val last = helperChoice.last
        val acted = last?.let { a -> replies.turn(a.replyId)?.actions?.isNotEmpty() } == true
        val r = helperChoice.turn(route, acted, untrusted, HelperContext.personal(history).isNotEmpty(), fromVoice)
        // RP3: "that's wrong": a Report it line for the answer it corrects, set before the re-send runs.
        helperChoice.reportNotice(route, turnLangs)?.let { n -> _state.update { it.copy(notice = n) } }
        when (r) {
            is PanelHelperChoice.Resend.Say -> localReply(replyId, r.text)
            is PanelHelperChoice.Resend.Keep -> {
                localReply(replyId, r.text)
                offers.showKeep(replyId, r.k)
            }
            is PanelHelperChoice.Resend.Robot -> {
                endHelperMode()
                guard.take(fromHelper = false)
                helperChoice.asked(last!!.copy(replyId = replyId, helper = null))
                runTurn(replyId, r.question, Attachment.NONE, null, null, history, untrusted, guard, fromVoice)
            }
            is PanelHelperChoice.Resend.To -> {
                endHelperMode()
                helperDeclined = false
                r.h?.takeIf { it.on }?.let { replies.markHelper(replyId, it) }
                helperChoice.asked(last!!.copy(replyId = replyId, helper = r.h?.id))
                helperTurn.run(replyId, HelperCommand.Ask(r.question, HelperRouter.deep(r.question), Attachment.NONE, r.named ?: HelperName.ANY_ID), r.h, r.question,
                    Attachment.NONE, null, null, null, history, fromVoice, fromAnotherApp = Personal.SHARED in HelperContext.personal(history))
            }
            // Your question alone, frozen now; only a tap or a yes sends it, and PanelOffers.accept checks the helper again.
            is PanelHelperChoice.Resend.Ask -> {
                val api = HelperSettings.apiReady(r.h, keySaved(r.h))
                val payload = if (!api) null else HelperContext.payload(r.question, emptyList(), java.time.ZonedDateTime.now(), userRegion, emptySet(), Attachment.NONE,
                    app.heyrobot.core.Shared(), includePersonal = false)
                sayInto(replyId, HelperWords(HelperWho.of(r.h)).ratherText)
                finishReply(replyId, DoneReason.END)
                offers.show(replyId, PanelOffers.Pending(api, r.question, payload, fromVoice, helper = r.h.id))
            }
        }
    }

    /**
     * Runs a turn in the slot; a stop finishes every reply still streaming as stopped, a crash fails it in words. A model
     * unload free_memory asked for is done before the turn completes (0.4.0), so the next question can't start meanwhile.
     */
    private fun startTurn(replyId: String, block: suspend () -> Unit) = slot.start {
        claimsHeld = false
        try {
            block()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                PanelCorrect.taken(replyId)?.let { replies.setText(replyId, it.render(turnLangs)) }
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
        // 1.4 Lists (LS4): add_to_list only while Lists is on, the phone unlocked and no phone lock (PanelLists.open, §5).
        val listsOpen = withContext(Dispatchers.IO) { PanelLists.open(VoiceHub.app) }
        // Strong signals go to the helper before Gemma spends a prefill (Automatic only); null: the helper took the question.
        val before = escalation.beforeModel(replyId, question, history, attachment, voiceAsked, guard, s, target, localWeather, localWeb) ?: return
        val c = BrainKeeper.ensureLoaded(VoiceHub.app)
        if (c == null) {
            // 1.1.2: no model, but the time and a timer are still answered, from your words (core ClockAsk).
            if (attachment == Attachment.NONE && clockTurn(replyId, question, untrusted)) return
            val b = BrainKeeper.state.value
            VoiceLog.add(VoiceEvent.BRAIN_FAILED, if (b.readiness == BrainKeeper.Readiness.NO_MODEL) BrainFail.NO_MODEL else BrainFail.LOAD_FAILED)
            return failReply(replyId, b.problem ?: PanelStrings.modelNotReady().render(turnLangs), if (b.readiness == BrainKeeper.Readiness.NO_MODEL) NoticeAction.OPEN_APP else NoticeAction.NONE)
        }
        client = c
        val loaded = BrainKeeper.state.value
        // 0.4.3, a short-context model (chat only): a request clearly worded as an action gets Robot's own answer, and an
        // offer to open Settings › Model, never a switch made for you. (Texting and the helpers' phrases went their own way
        // before the model was ever asked, as always.)
        // A question about the screen only wants an answer: no tools, so no card can come of it (core ScreenQuestion).
        val chatOnly = loaded.chatOnly || app.heyrobot.core.ScreenQuestion.answerOnly(question, attachment)
        // 1.4 Lists: while Lists is on, "add … to my list" is the list tool's (PanelLists.on), as for the full model.
        val listsOn = PanelLists.on(VoiceHub.app)
        if (loaded.chatOnly) ChatOnly.actionAsked(question, listsOn)?.let { asked ->
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
        if (image != null && !vision && (attachment == Attachment.PHOTO || attachment == Attachment.FILE)) {
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
        // fix-everyday-c: "and tomorrow?" also asks for what the turn before asked for (its read tools: core ToolRouter.asked).
        val askedNow = ToolRouter.asked(question, chat.filter { it.role == TurnRole.USER }.map { it.text }.let { u ->
            u.getOrNull(u.size - if (u.lastOrNull() == question) 2 else 1) }, listsOn)
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
                    browser = BrowserRouter.named(question), files = filesOpen && !locked, lists = listsOpen && !locked, link = !locked && PanelLinkAsk.described(VoiceHub.app))
            // 1.1: "Robot answers in" fixed to a language tells the model which (core ReplyLang.answerIn); so does a French turn.
            val answerIn = ReplyLang.answerIn(s.replyLang, turnLangs.say, replyBefore)
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
            val described = if (chatOnly) wanted else ToolBudget.fit(wanted, ToolRouter.route(question, listsOn) + sk, maxTokens) { g ->
                toolsFor(g).let { Prompt.fixedTokens(systemFor(it, ToolGroup.SKILLS in g), it, if (ToolGroup.SKILLS in g) specs else emptyList(), reading.compact) }
            }
            if (described != wanted) RLog.i("tool_budget", "dropped" to (wanted - described).size, "kept" to described.size)
            val tools = toolsFor(described)
            val skillSpecs = if (ToolGroup.SKILLS in described) specs else emptyList()
            val system = systemFor(tools, skillSpecs.isNotEmpty())
            // The time only for a question that needs it, after the question: the model copied a leading time into its replies.
            val clock = (if (Prompt.needsClock(question, groups)) Prompt.clock(now) else "") + Prompt.figureNote(question, history.size >= 2)
            // The new turn always fits the model's context: cut (and say so) rather than overflow. Memory's notes go before it only
            // when the question still fits whole with them.
            val notes = mem.packed.notesBlock?.takeIf {
                !Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools, skillSpecs, reading.compact) + mem.packed.notesTokens, clock, maxTokens,
                    shared = turnShared).shortened
            }
            val fitted = Prompt.fitTurn(question, screen, attachment, img != null, Prompt.fixedTokens(system, tools, skillSpecs, reading.compact) + (if (notes != null) mem.packed.notesTokens else 0),
                clock, maxTokens, shared = turnShared).let { if (notes != null) it.copy(text = notes + it.text) else it }
            if (fitted.shortened && !cutSaid) {
                cutSaid = true
                notify(if (fitted.questionCut) PanelStrings.messageCut() else if (turnShared) app.heyrobot.core.i18n.EverydayStrings.askContextCut() else PanelStrings.screenCut())
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
            // everyday-1.2.2-a bug 7: a question about you, nothing asked for: no "I saved that" read out (core ReplyVoice).
            if (app.heyrobot.core.ActionOutcome.recallOnly(askedNow, question)) voice.recallOnly(replyId)
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
        // The everyday set (E2): Gemma called calculate: code's reply is the answer, its own form on screen and aloud (core sums).
        result.calc?.let { p ->
            replies.setText(replyId, p.render(turnLangs))
            voice.feed(replyId, p.render(turnLangs.voice), _state.value.speak).forEach { speakSentence(it) }
            return finishReply(replyId, DoneReason.END)
        }
        // Escalation trigger b: Gemma called ask_claude. Its turn ended there; the helper answers (or is offered).
        if (result.escalate) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.LOCAL_TOOL)
            return escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.LOCAL_TOOL,
                ownAnswer = { gemma(askHelper = false) }, helper = before.helper, hold = before.hold)
        }
        // 1.3 (S3.7): calls past the turn's limit didn't run: "Make that a plan?"
        if (result.skipped.isNotEmpty() && !result.launchedApp) app.heyrobot.assistant.skills.PanelPlans.offer(replyId, result.skipped, question, skillHost, null)
        finishReply(replyId, result.reason ?: DoneReason.END)
        if (result.launchedApp) return leaveForApp()
        // Escalation trigger c: the reply hedged ("I don't know", "I can't browse", nothing at all). One that says it has no
        // current information goes by itself, like current information, only to a helper that can search (0.6.0).
        val reply = chat.lastOrNull { it.id == replyId }
        if (result.reason == DoneReason.END && reply != null && reply.actions.isEmpty() && Hedge.detect(reply.text)) {
            RLog.i("helper_trigger", "kind" to EscalationTrigger.HEDGE)
            return escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, EscalationTrigger.HEDGE,
                needsSearch = Hedge.needsSearch(reply.text), helper = before.helper, hold = before.hold)
        }
        before.offerAfter?.let { escalation.afterLocal(replyId, question, history, attachment, screen, image, voiceAsked, guard, it, offerOnly = !before.hold,
            needsSearch = before.needsSearch, helper = before.helper, hold = before.hold) }
        before.keep?.let { offers.showKeep(replyId, it) }
    }

    /** The personal content in the conversation now, the reply [replyId] included while it is still on screen only. */
    private fun personalNow(replyId: String?): Set<Personal> {
        val ui = replyId?.takeIf { id -> chat.none { it.id == id } }?.let { id -> replies.turn(id) }
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
        val ui = replies.turn(replyId) ?: return
        if (ui.text.isBlank() && ui.actions.isEmpty()) {
            voice.finish(replyId, cancelled = true, speak = false)
            replies.end(replyId, VoiceHub.tts.isSpeaking) { null }
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

    /**
     * 0.8: an announced message's session ended ("no", or nothing more to say): the panel it opened goes. fix-car-stay-e:
     * every close of a turn Robot makes on its own (a session's end, a quiet turn done, the car screen's talk) comes here,
     * and waits for Robot's words and for a reply in its 3 seconds or on its way (onHide would stop it unsent), this show only.
     * fix-car-stay-f: the car screen's talk ([words] false) stops Robot's words at once and waits only for a reply.
     */
    internal fun hideAfterAnnouncement(words: Boolean = true, held: Boolean = false) = hideWhenSaid(shownAtE, HIDE_TRIES, words, held)

    private fun hideWhenSaid(at: Long, tries: Int, words: Boolean, held: Boolean = false) {
        if (!shown || shownAtE != at) return
        val s = _state.value
        val sending = app.heyrobot.assistant.car.CarTurnLines.sending(s)
        // fix-car-stay-f: once a reply held it, a question said in its 3 seconds (the turn busy) is answered before the panel goes.
        if (app.heyrobot.core.car.CarTurnEnd.hideWaits(words, VoiceHub.tts.isSpeaking, sending, s.busy, held, tries)) {
            VoiceHub.main.postDelayed({ hideWhenSaid(at, tries - 1, words, held || sending) }, HIDE_STEP_MS)
        } else host?.hidePanel()
    }

    // fix-car-stay-e: 300 ms a poll, a minute at most, as CarModeController's own close.
    private const val HIDE_STEP_MS = 300L
    private const val HIDE_TRIES = 200

    /** Ends [replyId] with [text] shown; PanelMessages speaks it in each piece's own voice. */
    internal fun messagesReply(replyId: String, text: String) {
        replies.markHelper(replyId, null)
        _state.update { it.copy(helperStatus = null) }
        replies.setText(replyId, text)
        finishReply(replyId, DoneReason.END)
    }

    /** The reply card under [replyId] (null: none). */
    internal fun messagesCard(replyId: String, card: app.heyrobot.ui.common.ReplyCardView?) = replies.setReply(replyId, card)

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
        replies.setTexts(dropped)
    }

    /** "Open chat": the app opens over the panel with your reply as a draft. */
    internal fun messagesLaunch(i: Intent): Boolean {
        val ok = host?.launch(i) == true
        if (ok) scope.launch { leaveForApp() }
        return ok
    }

    /** A card button (PanelActions.reply). */
    fun reply(replyId: String, op: app.heyrobot.ui.common.ReplyOp) = PanelMessages.onCard(replyId, op)

    /** RP3: the bug button (null: the newest turn) or a reply pressed and held: the card for that turn (PanelFeedback.report). */
    fun report(replyId: String?) {
        val t = app.heyrobot.core.feedback.ReportTurn.of(chat, replyId, reportLangs.heardLast, lastReply?.lang?.id, System.currentTimeMillis())?.copy(conv = _state.value.convId) ?: return
        scope.launch { feedback.report(t) }
    }

    /** 1.4 Lists (LS5): a list card's tick or Undo (through the vault, off the main thread), or its Open. */
    fun listCard(replyId: String, op: app.heyrobot.ui.common.ListCardOp) {
        val v = replies.turn(replyId)?.list ?: return
        if (op == app.heyrobot.ui.common.ListCardOp.Open) return openList(v.list)
        scope.launch {
            val next = withContext(Dispatchers.IO) { PanelLists.cardTap(VoiceHub.app, replyId, v, op, turnLangs) }
            replies.setList(replyId, next)
        }
    }

    /** The list [id]'s page in the app, the panel put away; while locked, a line to unlock instead (§5). */
    private fun openList(id: String) {
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(app.heyrobot.core.i18n.ListsStrings.unlockToSee()))
        val h = host ?: return
        stopGenerating(TtsStop.OTHER, forApp = true)
        if (h.launch(PanelLists.openIntent(VoiceHub.app, id))) {
            hidingFor = "action"
            h.hidePanel()
        }
    }

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

    /** 1.1.2, without the model: "what time is it" said from the clock, "set a timer for 10 minutes" run as set_timer's call. */
    private suspend fun clockTurn(replyId: String, question: String, untrusted: Boolean): Boolean {
        when (val a = app.heyrobot.core.ClockAsk.route(question, ZonedDateTime.now())) {
            null -> return false
            is app.heyrobot.core.ClockAsk.Answer.Say -> localReply(replyId, a.text)
            is app.heyrobot.core.ClockAsk.Answer.Call -> {
                val r = TurnRunner(DirectCall(a.call), PanelActions, PanelView(replyId), slot.confirmations, untrusted, asked = setOf(ToolName.SET_TIMER),
                    messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()
                r.failure?.let { failReply(replyId, it) } ?: finishReply(replyId, r.reason ?: DoneReason.END)
                if (r.launchedApp) leaveForApp()
            }
        }
        return true
    }

    /** fix-123-d: a dictated note (core NoteDictation.direct) as add_note's call in your words, without the model. */
    private suspend fun noteTurn(replyId: String, call: RawToolCall) {
        val r = TurnRunner(app.heyrobot.core.NoteDictation.Direct(call), PanelActions, PanelView(replyId), slot.confirmations, false,
            asked = setOf(ToolName.ADD_NOTE), messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()
        r.failure?.let { failReply(replyId, it) } ?: finishReply(replyId, r.reason ?: DoneReason.END)
    }

    /**
     * A web answer's source tapped: it opens in the browser (only the card's own links, or Wikipedia's licence), as an app
     * opens; on the lock screen, unlock first. Wikipedia P4b: a copy's card, its articles' and the licence's (WikiCard.links).
     */
    fun openSource(replyId: String, url: String) {
        val turn = replies.turn(replyId) ?: return
        val links = turn.web?.let { card -> (card.cited + card.alsoFound).map { it.url } + WikipediaWire.LICENCE_URL } ?: turn.wiki?.links ?: return
        if (url !in links) return
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
        val a = replies.cardById(actionId) ?: return
        val pkg = a.handOff ?: return
        // 1.4: a Robot Files card's Move: the card asks which drawer, and your next words answer (PanelFiles).
        if (PanelFiles.isMove(pkg)) {
            val turn = replies.turnWithCard(actionId) ?: return
            PanelFiles.startMove(pkg)?.let { ask -> replies.upsertAction(turn.id, a.copy(detail = ask.render(turnLangs), handOff = null)) }
            if (shown && _state.value.speak && _state.value.phase == Phase.IDLE) startListening()
            return
        }
        // 1.4 F10: a reminder card's Add to calendar: add_event's own card, in a turn of its own.
        if (PanelFiles.isCalendar(pkg)) return filesCalendar(pkg)
        // 1.4 Lists (LS5): a list card's "Open Shopping": the list's page in the app.
        if (PanelLists.isOpen(pkg)) return PanelLists.openedList(pkg)?.let(::openList) ?: Unit
        val page = SettingsPage.isPage(pkg)
        // 1.4 F8: Show in Files (a find, Inbox, several matches): the Files tab.
        if (PanelFiles.isOpen(pkg) && Assistant.locked(VoiceHub.app)) return notify(app.heyrobot.core.i18n.FilesStrings.locked())
        if (Assistant.locked(VoiceHub.app)) return notify(unlockFirst(if (page) PanelStrings.unlockOpenSettings("settings" to AndroidPath.SETTINGS) else PanelStrings.unlockOpenCheogram()))
        val h = host ?: return
        val tries = if (PanelFiles.isOpen(pkg)) listOf(PanelFiles.openIntent(VoiceHub.app, pkg)) else if (page) SettingsIntents.of(VoiceHub.app, pkg)
        else listOfNotNull(VoiceHub.app.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (tries.isEmpty()) return notify(if (page) PanelStrings.settingsDidntOpen("settings" to AndroidPath.SETTINGS) else PanelStrings.cheogramNotInstalled())
        stopGenerating(TtsStop.OTHER, forApp = true)
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
        override val openNamed: Boolean get() = app.heyrobot.sys.OpenAppsStore.named(VoiceHub.app)
        override fun firstOpenWithoutCard(): Boolean = app.heyrobot.sys.OpenAppsStore.firstOpen(VoiceHub.app)
        override suspend fun prepare(action: Action): Prepared = withContext(Dispatchers.IO) {
            // Skills connections (C4): a skill's step that reaches a connection is ConnRunner's (assistant/connect).
            if (action.tool.group == ToolGroup.CONNECTIONS) app.heyrobot.assistant.connect.ConnRunner.prepare(VoiceHub.app, action)
            else MemoryPanel.prepare(action, locked) ?: executor().prepare(action)
        }
        // 1.0 (injection-termux-policy-unwired): a Termux call is prepared by TermuxTool (TermuxPolicy, the lock, the signer, the
        // permission), into its card or a refusal; everything else as above.
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            // 1.4 Lists (LS4, D6): add_to_list's card comes from your words and the conversation's content (PanelLists).
            if (action is Action.AddToList) withContext(Dispatchers.IO) { PanelLists.prepareModel(VoiceHub.app, action, untrusted || remote, turnSaid) }
            // W4: a helper's web search goes without your town, and only while "Let helpers use Robot's search" is on.
            else if (remote && action is Action.WebSearch) withContext(Dispatchers.IO) { executor().prepareForHelper(action) }
            else TermuxTool.prepareTurn(VoiceHub.app, action, untrusted, remote, turnSaid) { prepare(it) }
        // 1.0 (injection-querycheck-notes-personal): what a read tool returned to the model (a note's text, not just its title)
        // is kept for QueryCheck's personal words while this conversation lives.
        override suspend fun run(action: Action): Executed = runAction(action).also(webSearch::noteRead)
        override suspend fun emergencyNumber(to: String): Boolean = withContext(Dispatchers.IO) {
            app.heyrobot.assistant.car.CallPlacer.isEmergency(VoiceHub.app, app.heyrobot.core.PhoneNumbers.digits(to), VoiceHub.settings.regionFor(Phones.deviceRegion(VoiceHub.app)))
        }

        private suspend fun runAction(action: Action): Executed {
            // 0.7: remember and forget (core memory/); what the conversation holds decides where a remembered item came from.
            if (action.tool.group == ToolGroup.MEMORY) return withContext(Dispatchers.IO) {
                // (The last: a message is in the conversation, so only your own "remember that …" got here, after its tap.)
                MemoryPanel.run(action, spokenTurn, app.heyrobot.core.memory.MemoryTaint.fromContent(TurnRules.untrusted(chat),
                    _state.value.turns.lastOrNull()?.actions?.any { it.content } == true, turnMemContent), PanelMessages.taint(chat).active)
            } ?: Executed(ActionStatus.FAILED, PanelStrings.wentWrong())
            // 0.9: a Termux run waits for its result (Stop ends its process tree), off the main thread.
            if (action.tool.termux) return withContext(Dispatchers.IO) { TermuxTool.run(VoiceHub.app, action) { i -> host?.launch(i) ?: false } }
            if (action is Action.AddToList) return withContext(Dispatchers.IO) { PanelLists.runModel(VoiceHub.app, action) }
            // Skills connections (C4): Stop cancels a connection's call at once (ConnRunner's OnlineRun); C7, a hand-off opens its app from the panel.
            if (action.tool.group == ToolGroup.CONNECTIONS) return app.heyrobot.assistant.connect.ConnRunner.run(VoiceHub.app, action) { i -> host?.launch(i) ?: false }
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

    private fun executor(abort: Abort = Abort()) = ToolExecutor(VoiceHub.app, VoiceHub.settings, abort, toolRoom, turnSaid, webSearch::context, webSearch::step, turnLangs.say, records = true) { i -> host?.launch(i) ?: false }

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
            replies.thinking(p == TurnPhase.CONFIRMING)
            if (p == TurnPhase.CONFIRMING) cardVoice.waiting() else cardVoice.stop()
        }

        override fun card(c: Card) {
            val icon = (c.action as? Action.OpenApp)?.key?.let { key -> icons.getOrPut(key) { executor().appIcon(key) } }
            // What a read tool finds goes to the helper only after this tap: the button says so.
            val confirm = c.confirm ?: if (c.remote && c.tool?.reads == true) PanelStrings.shareWith("name" to helper).render(buttonLangs)
                else c.action?.confirmText?.render(buttonLangs)
            // 1.3: a dictated text's numbers and names to check (PanelTexting).
            val detail = PanelTexting.marks(replyId)?.takeIf { c.tool == ToolName.SEND_TEXT }?.let { m -> c.detail?.let { "$it\n$m" } ?: m } ?: c.detail
            replies.upsertAction(replyId, CardWords.dress(TermuxPanel.dress(UiAction(c.id, c.tool, c.summary, c.status, detail, icon, c.body, c.choices.map { it.label }, confirm, c.undo,
                c.content, c.more, c.cancel, c.handOff, remember = c.remember, allowContacts = c.allowContacts,
                // What it finds goes to the helper: a read, or an online lookup around where you are (the nearest place).
                personal = c.remote && (c.tool?.reads == true || c.action?.online == true), tapFor = c.tapFor, chips = c.chips, located = c.located), c), buttonLangs))
            // 1.1: what this conversation did, for "save that as a skill" (D12).
            PanelSkills.seen(c)
        }

        override val text: String get() = replies.text(replyId).orEmpty()
        override fun replaceText(text: String) = replies.setText(replyId, text)
        override fun paragraphBreak() = paragraphBreak(replyId)
        override fun holdClaims(on: Boolean) { claimsHeld = on }
        override fun say(sentence: String) {
            voice.say(replyId, sentence, _state.value.speak)?.let(::speakSentence)
        }
    }

    /** The model's text after a tool round starts a new paragraph (and a new sentence for speech). */
    private fun paragraphBreak(replyId: String) {
        if (replies.paragraphBreak(replyId)) streamPort.speak(replyId, "\n")
    }

    /**
     * One sentence aloud: Robot's voice, or a helper's ([byHelper], in its [voice]: another voice of the same model, so you
     * hear who talks).
     */
    private fun speakSentence(s: String, byHelper: Boolean = false, voice: String? = null, personal: Boolean = false, words: String? = null) {
        // Never read out a tool call the model wrote as text.
        // A reading's [words] are a picture's or a file's, never the model's own: no tools, nothing in them acted on (review finding 4).
        if (!byHelper && words == null && (s.contains("call:") || s.contains("<start_function_call>") || ToolCalls.parseText(s).isNotEmpty())) return
        val clean = SpeechText.clean(if (byHelper || words != null) s else app.heyrobot.core.ReplyWords.scrub(s, turnLangs.say))
        if (clean.isEmpty()) return
        // 1 Nov: nothing this turn has done, and the model says otherwise: not read out (TurnRunner writes what happened).
        if (!byHelper && words == null && claimsHeld && app.heyrobot.core.ActionOutcome.claimsAnything(clean)) return
        // 0.8: in a headset or car session, OutputGuard first (personal sentences only on a private device, the car's two).
        if (!PanelHandsFree.allow(clean) { personal || personalNow(_state.value.turns.lastOrNull()?.id).isNotEmpty() }) return
        // The helper's voice per sentence: Kokoro's French voice for a French one, its own otherwise (Claude's when the
        // helper has none, as before 0.6.0).
        if (byHelper) HelperVoices.forSentence(VoiceHub.settings.voiceChoice, voice ?: VoiceHub.settings.claude?.voice, clean).let { (sid, lang) -> VoiceHub.tts.speakOther(clean, sid, lang) }
        else if (words != null) speakWords(clean, words)
        else speakRobot(clean)
    }

    /** 1.3 point and read: a picture's words in their own language's voice ([lang] "fr": the French one), Robot's own otherwise. */
    private fun speakWords(text: String, lang: String) {
        val fr = if (lang == "fr") MessageVoices.french(VoiceHub.settings.voiceChoice) else null // l10n: not text (a language code)
        if (fr != null) VoiceHub.tts.speakOther(text, fr.first, fr.second) else VoiceHub.tts.speak(text)
    }

    private suspend fun leaveForApp() {
        hidingFor = "action"
        delay(900)
        if (shown) host?.hidePanel()
    }

    // ------------------------------------------------------------------ Robot to Robot (1.5 L6, PanelLink)

    /**
     * :link's desk changed, or the panel showed: the cards this conversation hasn't shown come as one turn of Robot's, content
     * (ChatTurn.messages, History keeps only its count), and the conversation's taint gains their pairings. Not while a turn
     * runs (the next change, or the next show, brings them).
     */
    fun linkChanged() {
        if (!shown) return
        val conv = _state.value.convId
        val langs = screenLangs()
        scope.launch {
            val f = withContext(Dispatchers.IO) { try { PanelLink.find(VoiceHub.app, conv, langs) } catch (t: Throwable) { RLog.w("link_panel", t); null } } ?: return@launch
            if (!shown || _state.value.convId != conv || _state.value.busy || slot.running) return@launch
            val t = ChatTurn(newId(), TurnRole.ASSISTANT, f.text, System.currentTimeMillis(), messages = true, kept = f.kept)
            chat += t
            _state.update { it.copy(turns = it.turns + UiTurn(t.id, TurnRole.ASSISTANT, f.text, actions = f.cards)) }
            PanelLink.shown(VoiceHub.app, f)
            PanelMessages.withPairings(f.pairings)
            lastActivity = System.currentTimeMillis()
            // L10, D16: their words in the other language, translated under the original once the turn shows.
            for (tr in f.translate) PanelLink.translated(tr, langs)?.let { body ->
                replies.card(t.id, tr.actionId)?.let { replies.upsertAction(t.id, it.copy(body = body)) }
            }
        }
    }

    /**
     * 1.5 L7: your words to another Robot (PanelLinkAsk): a request is one ask_robot call of your words through the turn runner
     * (its card, its level, the lock and the quarantine, as the model's); tightening a level is done at once.
     */
    private suspend fun linkTurn(replyId: String, c: app.heyrobot.core.link.LinkCommand) {
        when (c) {
            is app.heyrobot.core.link.LinkCommand.Ask -> {
                val raw = RawToolCall(ToolName.ASK_ROBOT.id, PanelLinkAsk.args(c))
                val r = TurnRunner(DirectCall(raw), PanelActions, PanelView(replyId), slot.confirmations, untrusted = TurnRules.untrusted(chat),
                    asked = setOf(ToolName.ASK_ROBOT), messages = PanelMessages.taint(chat), langs = turnLangs, newId = ::newId).run()
                r.failure?.let { replies.appendText(replyId, it) }
                finishReply(replyId, DoneReason.END)
            }
            is app.heyrobot.core.link.LinkCommand.StopAlone -> localReply(replyId, withContext(Dispatchers.IO) { PanelLinkAsk.stopAlone(VoiceHub.app, c) })
            is app.heyrobot.core.link.LinkCommand.NotYet -> localReply(replyId, app.heyrobot.core.i18n.LinkStrings.notYet())
            is app.heyrobot.core.link.LinkCommand.NotPaired -> localReply(replyId, withContext(Dispatchers.IO) { PanelLinkAsk.notPaired(VoiceHub.app, c) })
        }
    }

    /** A Robot to Robot card's Yes or Decline (or a proposal's Turn on or Not now): answered through :link, the card says what happened. */
    private fun linkTapped(actionId: String, yes: Boolean) {
        val turn = replies.turnWithCard(actionId) ?: return
        val langs = screenLangs()
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { PanelLink.tapped(VoiceHub.app, actionId, yes, langs) }) {
                PanelLink.Tapped.OpenRequests -> {
                    val h = host ?: return@launch
                    leaveForRobot(h, Intent().setClassName(VoiceHub.app.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
                        .putExtra(app.heyrobot.sys.AppPages.EXTRA, app.heyrobot.sys.AppPages.LINK_REQUESTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                is PanelLink.Tapped.Done -> replies.card(turn.id, actionId)
                    ?.let { replies.upsertAction(turn.id, it.copy(status = r.status, detail = r.line)) }
            }
        }
    }

    /**
     * fix-covered-partial M2: car mode's Yes was refused for another app's window over it: said aloud, then one listen.
     * fix-covered-partial-b M1: a reply's line follows PanelMessages' own spoken-yes gate, as an action card's follows its own.
     */
    fun coveredYes(actionId: String?, replyId: String?): app.heyrobot.assistant.car.CoveredLine {
        stopListeningQuietly() // fix-covered-partial-b M2: an open listen never takes Robot's own line as a question.
        if (actionId != null) return cardVoice.covered(actionId)
        if (replyId == null) return app.heyrobot.assistant.car.CoveredLine.TAP
        val line = PanelMessages.coveredVoice(replyId, VoiceHub.stt.available)
        speakRobot(line.phrase.said)
        if (line != app.heyrobot.assistant.car.CoveredLine.TAP) messagesListen(false)
        return line
    }

    /** The person tapped "Do it" (true) or "Cancel" on a confirmation card. */
    fun confirm(actionId: String, yes: Boolean) {
        // 1.5 L6: a Robot to Robot card (PanelLink): a spoken yes reaches here only for the cards §4.2 allows (no tapFor).
        if (PanelLink.isCard(actionId)) return linkTapped(actionId, yes)
        // 1.4 F8: a Robot Files delete card's Delete or Keep (PanelFiles). Tap-only (AskWhy.DELETE): a spoken yes never comes here.
        if (PanelFiles.isDelete(actionId)) return filesDeleteTapped(actionId, yes)
        // 1.4 Lists: an Empty card's Empty or Keep (PanelLists). Tap-only (AskWhy.DELETE): a spoken yes never empties a list.
        if (PanelLists.isEmpty(actionId)) return listsEmptyTapped(actionId, yes)
        // 1.4 F10: a reminder card's Add to my brief or Not now (PanelFiles): a brief reminder only, never the calendar.
        if (PanelFiles.isRemind(actionId)) return filesRemindTapped(actionId, yes)
        slot.confirmations.answer(actionId, yes)
    }

    private fun filesRemindTapped(actionId: String, yes: Boolean) {
        val turn = replies.turnWithCard(actionId) ?: return
        scope.launch {
            val (status, line) = withContext(Dispatchers.IO) { PanelFiles.remindTapped(VoiceHub.app, actionId, yes, turnLangs) }
            replies.card(turn.id, actionId)?.let { replies.upsertAction(turn.id, it.copy(status = status, detail = line)) }
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

    /** summary-todos: a summary's to-do as add_event's card, as F10's: FROM_CONTENT, so only a tap adds it (a summary chose its words). */
    private fun summaryTodoCalendar(ask: SummaryTodoPanel.Ask) {
        routineTurn(app.heyrobot.core.i18n.FilesStrings.addToCalendar().render(turnLangs)) { replyId, _ ->
            replies.setText(replyId, app.heyrobot.core.i18n.FilesStrings.calendarCard().render(turnLangs))
            val raw = RawToolCall(ToolName.ADD_EVENT.id, ask.args())
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
        val turn = replies.turnWithCard(actionId) ?: return
        val langs = turnLangs
        // FX2 (K1): Delete gives the card Undo for 10 seconds; when the delete goes through, the card's Undo goes.
        fun ended(ok: Boolean) = scope.launch {
            replies.card(turn.id, actionId)?.takeIf { it.undo != null }?.let {
                replies.upsertAction(turn.id, it.copy(undo = null, status = if (ok) ActionStatus.DONE else ActionStatus.FAILED,
                    detail = (if (ok) app.heyrobot.core.i18n.FilesStrings.deleted() else app.heyrobot.core.i18n.FilesStrings.notDeleted()).render(langs)))
            }
        }
        scope.launch {
            val t = withContext(Dispatchers.IO) { PanelFiles.deleteTapped(VoiceHub.app, actionId, yes, langs, ended = { ok -> ended(ok) }) }
            replies.card(turn.id, actionId)?.let { replies.upsertAction(turn.id, it.copy(status = t.status, detail = t.line, undo = t.undo)) }
        }
    }

    private fun listsEmptyTapped(actionId: String, yes: Boolean) {
        val turn = replies.turnWithCard(actionId) ?: return
        scope.launch {
            val t = withContext(Dispatchers.IO) { PanelLists.emptyTapped(VoiceHub.app, actionId, yes, turnLangs) }
            replies.card(turn.id, actionId)?.let { replies.upsertAction(turn.id, it.copy(status = t.status, detail = t.line, undo = t.undo)) }
        }
    }

    /** 1.4 Lists: "undo that" undid the change behind the card with Undo key [key]: that card says Undone. */
    private fun listUndone(key: String) {
        val turn = _state.value.turns.firstOrNull { t -> t.actions.any { it.undo == key } } ?: return
        turn.actions.filter { it.undo == key }.forEach { replies.upsertAction(turn.id, it.copy(status = ActionStatus.UNDONE, undo = null)) }
    }

    /** The person tapped one of the people or numbers a card listed; [remember]: with "Always use this for …" ticked. */
    fun choose(actionId: String, index: Int, remember: Boolean = false) {
        slot.confirmations.choose(actionId, index, remember)
    }

    /** Undo on a note Robot just saved: it leaves Robot Notes, the card says so, the history keeps it as undone. */
    /** A card's Undo; false when the card is gone or its Undo was used already (review Medium 4). */
    fun undo(actionId: String): Boolean {
        val st = _state.value
        val turn = st.turns.firstOrNull { t -> t.actions.any { it.id == actionId } } ?: return false
        val a = turn.actions.first { it.id == actionId }
        val key = a.undo ?: return false
        // 1.4: a Robot Files card's Undo (PanelFiles): back in Inbox, or a move put back; the card says which.
        if (PanelFiles.isUndo(key)) {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null, handOff = null))
            scope.launch {
                val line = withContext(Dispatchers.IO) { PanelFiles.undo(VoiceHub.app, key, turnLangs) }
                replies.card(turn.id, actionId)?.let { replies.upsertAction(turn.id, it.copy(detail = line)) }
            }
            return true
        }
        // fix-note-delete: a note deleted by voice comes back whole (PanelRoutines.deleteNote).
        if (NoteDelete.isUndo(key)) {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val (line, again) = withContext(Dispatchers.IO) { PanelRoutines.undoNoteDelete(key, turnLangs).let { it to PanelRoutines.canUndoNote(key) } }
                undoSaid(turn.id, actionId, line, if (again) a else null)
            }
            return true
        }
        // spoken-log-3: a log entry deleted by voice comes back whole (PanelLogbook.deleteLast).
        if (app.heyrobot.core.logbook.LogbookDelete.isUndo(key)) {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val r = withContext(Dispatchers.IO) { app.heyrobot.assistant.logbook.PanelLogbook.undoDelete(VoiceHub.app, key, turnLangs) }
                // spoken-log-4: an Undo that failed for now (locked, full) keeps its button
                replies.card(turn.id, actionId)
                    ?.let { replies.upsertAction(turn.id, it.copy(detail = r.line, undo = r.again, status = if (r.again != null) a.status else it.status)) }
            }
            return true
        }
        // 1.4 Lists: a list card's Undo (PanelLists): the change put back; the card says whether it could be.
        if (PanelLists.isUndo(key)) {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val (line, again) = withContext(Dispatchers.IO) { PanelLists.undo(VoiceHub.app, key, turnLangs).let { it to PanelLists.canUndo(key) } }
                undoSaid(turn.id, actionId, line, if (again) a else null)
            }
            return true
        }
        if (MemoryPanel.isUndo(key)) {
            // 0.7: a memory card's Undo (an item saved, or items forgotten); the card says what it did.
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = null, undo = null))
            scope.launch {
                val (line, again) = withContext(Dispatchers.IO) { MemoryPanel.undo(key, turnLangs).let { it to MemoryPanel.canUndo(key) } }
                undoSaid(turn.id, actionId, line, if (again) a else null)
                if (again) recordCards(turn.id)
            }
        } else {
            replies.upsertAction(turn.id, a.copy(status = ActionStatus.UNDONE, detail = PanelStrings.removedFromNotes().render(turnLangs), undo = null))
            scope.launch(Dispatchers.IO) { NotesFiles.store(VoiceHub.app).delete(key) }
        }
        // The live conversation still believes the note was saved: it is rebuilt from the history ("undone") next time.
        if (slot.running) closeConvAfterTurn = true else closeLiveConversation()
        recordCards(turn.id)
        return true
    }

    /** A finished reply's record changes with its cards (a reply still running records its cards when it ends). */
    private fun recordCards(turnId: String) {
        val i = chat.indexOfFirst { it.id == turnId }
        if (i >= 0) {
            val ui = _state.value.turns.first { it.id == turnId }
            chat[i] = chat[i].copy(actions = records(settled(ui.actions)))
            persist()
        }
    }

    /**
     * An Undo's [line] on its card. lists-undo-fix (as spoken-log-4's logbook): one that failed for now (locked, not written)
     * gets back [kept]'s state and button, so you can try again once you unlock.
     */
    private fun undoSaid(turnId: String, actionId: String, line: String?, kept: UiAction?) {
        replies.card(turnId, actionId)
            ?.let { replies.upsertAction(turnId, if (kept != null) it.copy(detail = line, undo = kept.undo, status = kept.status) else it.copy(detail = line)) }
    }

    private fun closeLiveConversation() {
        closeConvAfterTurn = false
        client?.post(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv(_state.value.convId))
    }

    // ------------------------------------------------------------------ ending a reply

    /**
     * Stop button: stops the reply (or the model load) and its voice; a waiting card is declined. [forApp]
     * (fix-car-stay-g): a tap that opens an app leaves a reply in its 3 seconds to send when they end.
     */
    fun stopGenerating(why: TtsStop = TtsStop.BUTTON, forApp: Boolean = false) {
        cardVoice.stop()
        msgListenAfterSpeech = false
        PanelMessages.stop(forApp)
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
        val ui = replies.turn(replyId)
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
        // 1.4: an answer from a private file is kept as a placeholder (PanelFileAnswer), here and in History; so is what was
        // playing (the everyday set's music, PanelMusic: the song's tags are content).
        chat += PanelShots.forHistory(app.heyrobot.assistant.music.PanelMusic.forHistory(PanelFileAnswer.forHistory(MemoryPanel.forHistory(ChatTurn(replyId, TurnRole.ASSISTANT, text, System.currentTimeMillis(),
            actions = records(actions) + if (quoted) listOf(Quoted.record(turnLangs)) else emptyList(), stopped = stopped,
            fromHelper = byHelper, sources = ui?.sources.orEmpty(), costMicros = costMicros,
            helper = ui?.helper.takeIf { byHelper }, by = ui?.by.takeIf { byHelper }, home = byHelper && ui?.home == true, content = wikiContent(ui), page = ui?.page == true, messages = kept != null, kept = kept)), turnLangs), turnLangs), turnLangs)
        // 1.4 Lists: a list read aloud carries its content record (PanelLists, core ListQuote): framed for the model, and saved
        // in History as its placeholder only (D17).
        chat[chat.lastIndex] = PanelLists.followUp(PanelLists.forHistory(chat.last(), turnLangs), chat.dropLast(1), turnLangs)
        // Today fix: private event titles the reply said are kept as "a private event" (PanelPrivateEvents).
        chat[chat.lastIndex] = PanelPrivateEvents.forHistory(chat.last(), turnLangs)
        replies.end(replyId, VoiceHub.tts.isSpeaking) { it.copy(text = text, streaming = false, stopped = stopped, actions = actions) }
        lastActivity = System.currentTimeMillis()
        persist()
        if (closeConvAfterTurn) closeLiveConversation()
        keepListening.answered(app.heyrobot.core.headset.PanelFollowUp.After(HandsFreeHub.headset.settings.panelFollowUp, spokenTurn, stopped, shown,
            PanelHandsFree.active || CarModeController.on, listenAfterSpeech || msgListenAfterSpeech || askingTextFor != null ||
                PanelSkills.waitsForWords || _state.value.phase == Phase.CONFIRMING), VoiceHub.tts.isSpeaking)
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
            replies.updateCardsIn(replyId, { it.tool == ToolName.FREE_MEMORY && FreeUp.isUnloading(it.detail) }) { it.copy(detail = line(it.detail)) }
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
        val actions = settled(replies.turn(replyId)?.actions.orEmpty())
        chat += ChatTurn(replyId, TurnRole.ASSISTANT, message, System.currentTimeMillis(), actions = records(actions), error = true)
        replies.end(replyId, VoiceHub.tts.isSpeaking, if (action != NoticeAction.NONE) Notice(message, action) else null) {
            it.copy(text = message, streaming = false, error = true, actions = actions, fromHelper = false)
        }
        persist()
        if (closeConvAfterTurn) closeLiveConversation()
    }

    /** A finished reply has no card left waiting: one still pending was not done (and is never saved as waiting). */
    private fun settled(actions: List<UiAction>) = actions.map { if (it.status == ActionStatus.PENDING) it.copy(status = ActionStatus.DECLINED) else it }

    private fun records(actions: List<UiAction>) = actions.mapNotNull { a -> a.tool?.let { ActionRecord(it, a.summary, a.status, a.detail, a.content, a.located) } }

    // ------------------------------------------------------------------ routines (0.8)

    /** The routine card waiting that only a tap answers, and what Robot says to a spoken yes. */
    private var routineTapOnly: Pair<String, String>? = null

    /** What a routine turn does in the panel (assistant/routines PanelRoutines): this turn's bubble, voice and cards. */
    private val routineHost = object : PanelRoutines.Host {
        override val speak: Boolean get() = _state.value.speak
        override val locked: Boolean get() = Assistant.locked(VoiceHub.app)
        override val handsFree: Boolean get() = wokeBy || PanelHandsFree.active || CarModeController.on
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
        override val oneShots: app.heyrobot.core.OneShotTransport get() = BrainOneShots
        override val source get() = app.heyrobot.core.skills.SkillDrafter.source(chat)
        override val helpers get() = SkillHelperLink
        override val onScreen: Boolean get() = shown
        override val phoneLock: Boolean get() = app.heyrobot.lock.PhoneLockNow.on(VoiceHub.app)
        override val messages get() = PanelMessages.taint(chat)
    }

    /** Replies that read out your calendar or reminders as they are (core Quoted): their saved turn carries its content record. */
    private val quotedReplies = HashSet<String>()

    /** Settings' Run now or a Ready card's tap: a turn of its own, as if you had asked. */
    private fun routineTurn(label: String, work: suspend (String, PanelRoutines.Host) -> Unit) {
        if (_state.value.busy || slot.running) return notify(PanelStrings.stillOnLast())
        newLockedTurn()
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
        startTurn(replyId) { work(replyId, skillHost) }
    }
}
