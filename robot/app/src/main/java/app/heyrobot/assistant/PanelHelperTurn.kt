// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.graphics.Bitmap
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AppHandOff
import app.heyrobot.core.AskRoute
import app.heyrobot.core.Attachment
import app.heyrobot.core.Card
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ClockEcho
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperCommand
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperFail
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReplyVoice
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Shared
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TtsStop
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.HelperTurn
import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.helpers.anthropic.AnthropicSession
import app.heyrobot.sys.HelperSpendStore
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. A helper's turn in the panel (docs/ask-claude.md; any helper since 0.6.0): "ask Jarvis …", "go get
// Jarvis" and every question in its conversation, Robot checking with a helper by itself or after an offer's tap, the
// cards that ask before your screen, a photo or the conversation go, the hand-off to a helper's app, and the helper's
// answer streamed into its bubble in its voice. What may go to a helper is core HelperContext's and HelperTrust's; this is
// the panel's side of it. The helper's TurnRunner, its cards' view, the reply's end and the voice stay in PanelController,
// through [PanelHelperTurn.Port]; the screen comes from PanelScreenShare and the helper's conversation from
// PanelHelperMode. Its callers run on the main thread, in the panel's scope, as before.

/**
 * Helper turns over PanelController's [state]; [voice]: what of the reply is read aloud; [helperMode]: a helper's
 * conversation; [screenShare]: the screen's text and picture; [cardVoice]: a card answered by voice; [port]: what it
 * needs of PanelController.
 */
internal class PanelHelperTurn(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PanelState>,
    private val replies: PanelReplies,
    private val voice: ReplyVoice,
    private val helperMode: PanelHelperMode,
    private val screenShare: PanelScreenShare,
    private val cardVoice: PanelCardVoice,
    private val port: Port,
) {
    /** What a helper's turn needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The settings now (VoiceHub.settings); each turn keeps the ones it began with. */
        val settings: RobotSettings
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** A button's words: the menus' language, whatever the reply language. */
        val buttonLangs: Langs
        /** Robot's screens' languages now (the menus', the phone's): a status line. */
        fun screenLangs(): Langs
        /** The turn slot's confirmations: a share card waits on them. */
        val confirmations: Confirmations
        fun newId(): String
        /** The phone is locked (Assistant.locked). */
        fun locked(): Boolean
        /** 1.1: a phone lock is on now (PhoneLockNow.on). */
        fun phoneLockOn(): Boolean
        /** 1.1: a phone lock for someone else is on now (PhoneLockNow.someoneElse). */
        fun someoneElse(): Boolean
        /** GrapheneOS's Network toggle is on for Robot (HelperLink.networkAllowed). */
        fun networkAllowed(): Boolean
        /** Whether helper [h]'s key is saved. */
        fun keySaved(h: HelperConfig?): Boolean
        /** A session with [h] made with [s] (HelperLink.session), or why not; called off the main thread. */
        fun session(s: RobotSettings, h: HelperConfig, conversation: Boolean): HelperLink.Made
        /** Your region for a helper's first message. */
        val userRegion: String
        /** The last photo this conversation had, and its thumbnail ("ask Jarvis about this photo"). */
        val lastImage: ByteArray?
        val lastThumb: Bitmap?
        /** The question Robot handed to a helper's app ([text]; null: none to find an answer after). */
        fun handedToApp(text: String?)
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** A notice in Robot's screens' language. */
        fun notify(p: Phrase)
        /** Robot's own words aloud, in its voice for the turn's reply language. */
        fun speakRobot(text: String)
        /** One sentence aloud: Robot's voice, or a helper's ([byHelper], in its [voice]). */
        fun speakSentence(s: String, byHelper: Boolean, voice: String?)
        fun stopVoice(why: TtsStop)
        /** Robot's own sentence into its empty reply, shown and said in its voice. */
        fun sayInto(replyId: String, text: Phrase)
        /** Robot's own words as the reply, which ends it. */
        fun localReply(replyId: String, text: Phrase, spoken: Phrase)
        /** [costMicros]: a helper's reply's estimated price, kept with it in the history. */
        fun finishReply(replyId: String, reason: DoneReason, costMicros: Long)
        /** [noReply]: the voice log counts it as no reply. */
        fun failReply(replyId: String, message: String, noReply: Boolean)
        /** The helper's answer joins Robot's own conversation: :brain rebuilds it from the history next time. */
        fun closeLiveConversation()
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
        /** The turn's view of reply [replyId] (its cards and the panel's phase), as Robot's. */
        fun view(replyId: String): TurnView
        /** The same for helper [helper]'s turn, by name ("Share with Jarvis"). */
        fun view(replyId: String, helper: String): TurnView
        /** [rounds] of helper [h]'s as a turn on reply [replyId] ([s]: the settings it began with; [session]: what it offered). */
        suspend fun helperRun(replyId: String, rounds: TurnModel, h: HelperConfig, s: RobotSettings, asked: Set<ToolName>, session: HelperSession): TurnResult
        /** The app route's [model] (its one ask_claude call) as a turn on reply [replyId]. */
        suspend fun handOffRun(replyId: String, model: TurnModel): TurnResult
    }

    private fun who(h: HelperConfig) = HelperWho.of(h)
    private fun words(h: HelperConfig) = HelperWords(HelperWho.of(h))

    /** A new, empty reply bubble of [h]'s at the end of the conversation. */
    fun bubble(h: HelperConfig): String {
        val id = port.newId()
        state.update {
            it.copy(turns = it.turns + UiTurn(id, TurnRole.ASSISTANT, "", streaming = true, fromHelper = true, helper = h.id, by = h.name, home = h.home),
                phase = Phase.THINKING, helperStatus = words(h).checkingText.render(port.screenLangs()))
        }
        voice.start(id)
        return id
    }

    /**
     * Robot checks with helper [h] (automatically, or after your tap): "Checking with Jarvis…" in Robot's voice, then the
     * answer in the helper's. [blocks]: exactly what goes (HelperContext.escalation, or an offer's frozen payload).
     * [fallBack] (Automatic, before Robot's own model ran): a helper that can't be reached before it said anything gives
     * the question back; then true is returned, the reply is Robot's again, and Robot answers itself.
     */
    suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean,
        h: HelperConfig, fallBack: Boolean = false): Boolean {
        val s = port.settings
        val w = words(h)
        val id = if (reuseBubble && replyId != null) replyId.also { r ->
            replies.markHelper(r, h)
            state.update { it.copy(helperStatus = w.checkingText.render(port.screenLangs())) }
        } else bubble(h)
        if (state.value.speak) port.speakRobot(port.said(w.checkingSaid))
        if (port.locked() && (!h.locked || port.someoneElse())) {
            // 1.1: during a phone lock, unlocking doesn't open it.
            if (port.phoneLockOn()) port.localReply(id, LockStrings.closedNow(), LockStrings.closedNow()) else port.localReply(id, w.lockedText, w.lockedText)
            return false
        }
        if (!port.networkAllowed()) {
            networkCard(id)
            return false
        }
        // The key is decrypted and the client made off the main thread.
        val session = when (val made = withContext(Dispatchers.IO) { port.session(s, h, conversation = false) }) {
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
        replies.markHelper(replyId, null)
        state.update { st -> st.copy(helperStatus = null, phase = Phase.THINKING, turns = st.turns.map { if (it.id == replyId) it.copy(text = "", actions = emptyList()) else it }) }
        voice.start(replyId)
        port.notify(PanelStrings.answeredInstead("why" to failure.sayText(who(h))))
        RLog.i("helper_fallback", "kind" to h.kind, "failure" to failure)
        return true
    }

    /** The app's name for the Send card ("a screenshot of Signal"), or null when it isn't known or visible to Robot. */
    private fun appLabel(pkg: String?): String? = pkg?.let {
        try {
            val pm = port.ctx.packageManager
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
            failure == HelperFailure.NO_KEY && HelperLink.unavailable(h.id) -> words(h).keyUnavailableText
            failure == HelperFailure.NO_KEY && HelperLink.unreadable(h.id) -> words(h).keyUnreadableText
            else -> failure.sayText(who(h))
        }.render(port.turnLangs)
        // 1.0.2: the voice log says why the helper wasn't asked ("key not sent over http"), not that the model failed.
        VoiceLog.add(VoiceEvent.CLAUDE_FAILED, HelperFail.of(failure).id, h.kind.id)
        port.failReply(replyId, text, noReply = false)
    }

    /** A question for helper [h] ("ask ChatGPT …", "go get Jarvis", or any question in a helper's conversation). */
    suspend fun run(replyId: String, cmd: HelperCommand?, h: HelperConfig?, said: String, attachment: Attachment, sText: String?, image: ByteArray?,
        thumb: Bitmap?, history: List<ChatTurn>, voiceAsked: Boolean) {
        val s = port.settings
        if (h == null) {
            helperMode.end()
            val any = (cmd as? HelperCommand.Ask)?.helper == HelperName.ANY_ID || (cmd as? HelperCommand.Join)?.helper == HelperName.ANY_ID
            // "Ask the helper …" with helpers on but none ready: what the one you'd get still needs (a key, or its app).
            val waiting = s.helper(s.helpDefault)?.takeIf { it.on } ?: s.helpers.firstOrNull { it.on }
            val p = when {
                !any -> PanelStrings.helperGone()
                s.helpers.isEmpty() -> PanelStrings.noHelper()
                waiting == null -> PanelStrings.noHelperOn()
                else -> words(waiting).notReadyText
            }
            return port.localReply(replyId, p, p)
        }
        val w = words(h)
        val keySaved = port.keySaved(h)
        if (!h.on) return port.localReply(replyId, w.offText, w.offText)
        val question = when (cmd) {
            is HelperCommand.Ask -> cmd.question
            is HelperCommand.Join -> cmd.question
            else -> said
        }
        if (HelperSettings.route(h, keySaved) == AskRoute.APP) {
            // Its app (the Claude app, ChatGPT, …): the question, or for "go get …" a short summary of the conversation so far.
            return handOff(replyId, question ?: AppHandOff.summary(history).orEmpty(), h)
        }
        if (h.presetOf.keyRequired && !keySaved) return HelperFailure.NO_KEY.sayText(who(h)).let { port.localReply(replyId, it, it) }
        if (port.locked() && (!h.locked || port.someoneElse())) {
            helperMode.end()
            if (port.phoneLockOn()) return port.localReply(replyId, LockStrings.closedNow(), LockStrings.closedNow())
            return port.localReply(replyId, w.lockedText, w.lockedText)
        }
        if (!port.networkAllowed()) return networkCard(replyId)
        if (cmd is HelperCommand.Join) {
            helperMode.start(h, joinedAt = history.size, misheard = cmd.misheard)
            // Nothing goes to the helper until there is a question.
            if (question == null) return port.localReply(replyId, w.joinedText, w.joinedText)
        }
        val mode = state.value.helperMode == h.id
        val deep = (cmd as? HelperCommand.Ask)?.deep ?: HelperRouter.deep(said)
        // The screen or a photo goes only after a tap on its card ("Send your screen's text to Jarvis?").
        val extra = if (attachment != Attachment.NONE) attachment else (cmd as? HelperCommand.Ask)?.extra ?: Attachment.NONE
        var shared = Shared()
        if (extra != Attachment.NONE) {
            val screen = if (extra == Attachment.SCREEN) sText ?: screenShare.text else null
            val photo = extra == Attachment.PHOTO
            val img = if (photo) (if (attachment == Attachment.PHOTO) image else port.lastImage) else screenShare.shot?.let { Images.jpeg(it) }
            if (screen == null && img == null) {
                return PanelStrings.noScreenOrPhoto("name" to h.name).let { port.localReply(replyId, it, it) }
            }
            // The card says exactly what goes and shows the picture: the photo, or a screenshot of the app on screen.
            val pic = if (photo) (if (attachment == Attachment.PHOTO) thumb else port.lastThumb) else screenShare.shot?.let { Images.scaled(it, Images.THUMB_EDGE) }
            val appName = if (!photo && img != null) withContext(Dispatchers.IO) { appLabel(screenShare.pkg) } else null
            val what = if (photo) PanelStrings.thePhoto() else Phrase.ListOf(listOfNotNull(screen?.let { PanelStrings.screenText() },
                img?.let { appName?.let { a -> PanelStrings.aScreenshotOf("appName" to a) } ?: PanelStrings.aScreenshot() }))
            if (!askToShare(replyId, PanelStrings.sendTo("what" to what, "name" to h.name), screen?.let { ToolCalls.safeTake(it, 600) }, PanelStrings.send(),
                    picture = pic, name = h.name)) {
                return port.localReply(replyId, w.nothingSentText, w.nothingSentText)
            }
            shared = Shared(screenText = screen, image = img)
        }
        // The key is decrypted and the client made off the main thread.
        val made = if (mode) helperMode.session?.let { HelperLink.Made.Ok(it) }
            ?: withContext(Dispatchers.IO) { port.session(s, h, conversation = true) }.also { if (it is HelperLink.Made.Ok) helperMode.session = it.session }
            else withContext(Dispatchers.IO) { port.session(s, h, conversation = false) }
        val session = when (made) {
            is HelperLink.Made.Ok -> made.session
            is HelperLink.Made.Not -> return noKey(replyId, h, made.failure)
        }
        val now = ZonedDateTime.now()
        try {
            val blocks = when {
                !mode -> HelperContext.oneShot(question ?: said, now, port.userRegion, shared)
                session.fresh -> {
                    // The helper joins the conversation: a personal one only after "Include" ("Start fresh" sends none of it),
                    // or by itself to a helper you trust that you called by its name (never with a screen or a photo in it).
                    val before = history.take(helperMode.joinedAt.coerceIn(0, history.size))
                    val personal = HelperContext.personal(before)
                    // A conversation from a word that only sounded like the name asks too, personal or not.
                    val include = HelperTrust.joinWithoutCard(h, personal, helperMode.misheard, MemoryPanel.toTrusted()) ||
                        HelperContext.includeQuestion(before, helperMode.misheard)?.let { q ->
                            askToShare(replyId, Phrase.plain(q), null, PanelStrings.include(), PanelStrings.startFresh(),
                                detail = PanelStrings.startFreshDetail("name" to h.name), personal = personal.isNotEmpty(), name = h.name,
                                // 1.4: what Robot read from your files goes only on a tap, never a spoken yes (HelperTrust).
                                tapFor = if (app.heyrobot.core.Personal.FILES in personal) app.heyrobot.core.AskWhy.FILES else null)
                        } ?: false
                    HelperContext.join(before, question ?: said, now, port.userRegion, shared, include, helperMode.misheard)
                }
                else -> HelperContext.next(question ?: said, shared)
            }
            runHelper(replyId, h, session, blocks, HelperPlan.of(h.effort, voiceAsked, deep), ToolRouter.asked(said))
        } finally {
            if (!mode) HelperLink.done(session)
        }
        if (mode) helperMode.touch()
    }

    /**
     * One turn of helper [h]'s into the reply [replyId]: streamed into the bubble and spoken in the helper's voice, its
     * calls as cards (always), then the cost line, today's spend, and Robot's own conversation rebuilt with it.
     * [fallBack]: see [escalate]; true when the question went back to Robot.
     */
    private suspend fun runHelper(replyId: String, h: HelperConfig, session: HelperSession, blocks: List<HelperBlock>, plan: HelperPlan, asked: Set<ToolName>,
        fallBack: Boolean = false): Boolean {
        val w = words(h)
        val s = port.settings
        replies.markHelper(replyId, h)
        state.update { st -> st.copy(helperStatus = st.helperStatus ?: w.thinkingText.render(port.screenLangs())) }
        val t0 = System.currentTimeMillis()
        var first = -1L
        // As for Robot's replies: each round's start is checked for a copied clock ("[Now 14:05]"), never shown or said.
        var echo = ClockEcho()
        fun stream(text: String) {
            if (text.isEmpty()) return
            replies.appendText(replyId, text)
            voice.feed(replyId, text, state.value.speak).forEach { port.speakSentence(it, byHelper = true, voice = h.voice) }
        }
        val heard = object : HelperTurnListener {
            override fun text(delta: String) {
                if (first < 0) first = System.currentTimeMillis() - t0
                stream(echo.feed(delta))
            }

            override fun declined() {
                // Robot's own words, in Robot's voice and Robot's bubble, in place of what was shown.
                voice.mute()
                port.stopVoice(TtsStop.OTHER)
                replies.setText(replyId, w.declinedText.render(port.turnLangs))
                replies.markHelper(replyId, null)
                if (state.value.speak) port.speakRobot(port.said(w.declinedText))
            }

            override fun thinking() {
                state.update { st -> if (st.phase == Phase.THINKING) st.copy(helperStatus = w.thinkingText.render(port.screenLangs())) else st }
            }

            override fun loading() {
                // Your server is loading the model (nothing for a few seconds): said on screen, so the wait makes sense.
                state.update { st -> if (st.phase == Phase.THINKING) st.copy(helperStatus = w.loadingText.render(port.screenLangs())) else st }
            }

            override fun spent(dollars: Double) {
                // Into today's spend as soon as it is known, so a crash mid-answer loses none of it.
                scope.launch(Dispatchers.IO) { HelperSpendStore.add(port.ctx, h.id, dollars) }
            }
        }
        val turn = HelperTurn(session, blocks, plan, heard) { it.sayText(who(h)).render(port.turnLangs) }
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
            port.helperRun(replyId, rounds, h, s, asked, session)
        } finally {
            val r = session.lastReport
            RLog.i("helper_turn", "kind" to h.kind, "model" to ((session as? AnthropicSession)?.model ?: RLog.Code("other")), "effort" to plan.effort,
                "firstMs" to first, "ms" to (System.currentTimeMillis() - t0), "requests" to r.requests, "searches" to r.searches,
                "searchErrors" to r.searchErrors, "cache_read_input_tokens" to r.usage.cacheRead)
        }
        state.update { it.copy(helperStatus = null) }
        val report = session.lastReport
        result.failure?.let {
            // A server that can't be reached, before it said or did anything: Robot answers itself (Automatic only).
            val f = turn.failure
            if (fallBack && f != null && f in FALLS_BACK && first < 0 && state.value.turns.firstOrNull { t -> t.id == replyId }?.actions.isNullOrEmpty()) {
                return fellBack(replyId, h, f)
            }
            // Robot's copy of the conversation can't make a valid request any more: the helper's conversation starts over.
            if (f == HelperFailure.RESET) helperMode.end()
            // Said aloud, in Robot's voice ("No internet", "Claude is busy, try again in a minute").
            if (state.value.speak) port.speakRobot(it)
            port.failReply(replyId, it, noReply = true)
            return false
        }
        val cost = when {
            !s.helperShowCost || report.requests == 0 -> null
            !report.costKnown -> PanelStrings.costUnknown().render(port.screenLangs())
            else -> Pricing.lineText(report.dollars, free = h.price is HelperPrice.Free)?.render(port.turnLangs)
        }
        state.update { st ->
            st.copy(turns = st.turns.map {
                if (it.id == replyId) it.copy(sources = report.domains.takeIf { report.searches > 0 } ?: emptyList(), cost = cost) else it
            })
        }
        // Cut off before it said anything (its thinking used the room): say so rather than leave an empty bubble.
        if (result.reason == DoneReason.LENGTH && state.value.turns.firstOrNull { it.id == replyId }?.let { it.text.isBlank() && it.actions.isEmpty() } == true) {
            replies.markHelper(replyId, null)
            port.sayInto(replyId, w.cutEmptyText)
        }
        port.finishReply(replyId, result.reason ?: DoneReason.END, costMicros = if (report.costKnown) Pricing.micros(report.dollars) else 0)
        // The helper's answer joins Robot's own conversation: :brain rebuilds it from the history next time.
        port.closeLiveConversation()
        if (result.launchedApp) port.leaveForApp()
        return false
    }

    /**
     * "Send …to Jarvis?" with Send and Cancel (or Include and Start fresh): true only after the tap. Said too when replies
     * are spoken, so you know a tap is needed. [name]: the helper's.
     */
    private suspend fun askToShare(replyId: String, summaryText: Phrase, body: String?, confirmText: Phrase, cancelText: Phrase = PanelStrings.cancel(),
        detail: Phrase? = null, picture: Bitmap? = null, personal: Boolean = true, name: String = "Claude", tapFor: app.heyrobot.core.AskWhy? = null): Boolean {
        val id = port.newId()
        val view = port.view(replyId, name)
        // 1.1: the card's words in the reply language, its buttons in the menus' (and named so in what is said).
        val summary = summaryText.render(port.turnLangs)
        val confirm = confirmText.render(port.buttonLangs)
        val cancel = cancelText.render(port.buttonLangs)
        replies.upsertAction(replyId, UiAction(id, ToolName.ASK_HELPER, summary, ActionStatus.PENDING,
            (detail ?: PanelStrings.nothingGoesUntil("name" to name, "button" to confirm)).render(port.turnLangs), body = body,
            confirm = confirm, cancel = cancel, picture = picture, personal = personal, tapFor = tapFor))
        if (state.value.speak) {
            val s = port.settings
            // A spoken yes sends personal content only with "Voice yes can send personal content" on; a no always works.
            port.speakRobot(port.said(when {
                !s.voiceCards -> PanelStrings.shareTapOrCancel("summary" to summaryText, "confirm" to confirm, "cancel" to cancel)
                tapFor == null && (!personal || s.voiceYesPersonal) -> PanelStrings.shareSayYesOrNo("summary" to summaryText)
                else -> PanelStrings.shareTapOrNo("summary" to summaryText, "confirm" to confirm)
            }))
            cardVoice.saidPrompt()
        }
        view.phase(TurnPhase.CONFIRMING)
        val pick = try {
            port.confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, ToolName.ASK_HELPER, summary, ActionStatus.DECLINED, null))
            throw e
        }
        view.card(Card(id, ToolName.ASK_HELPER, summary, if (pick >= 0) ActionStatus.DONE else ActionStatus.DECLINED,
            (if (pick >= 0) PanelStrings.sharedWith("name" to name) else PanelStrings.notShared()).render(port.turnLangs)))
        view.phase(TurnPhase.THINKING)
        return pick >= 0
    }

    /** GrapheneOS's Network toggle is off for Robot: a card that says so (no helper can be reached at all), said too. */
    private fun networkCard(replyId: String) {
        port.view(replyId).card(Card(port.newId(), ToolName.ASK_HELPER, PanelStrings.noNetwork().render(port.turnLangs), ActionStatus.FAILED,
            HelperFailure.NO_NETWORK_PERMISSION.sayText(HelperWho.CLAUDE).render(port.turnLangs)))
        if (state.value.speak) port.speakRobot(port.said(HelperFailure.NO_NETWORK_PERMISSION.sayText(HelperWho.CLAUDE)))
        state.update { it.copy(helperStatus = null) }
        replies.markHelper(replyId, null)
        port.finishReply(replyId, DoneReason.END, costMicros = 0)
    }

    /**
     * The app route (any helper with an app, 1.0.0): the card "Open the ChatGPT app with: «…»", then [h]'s app opens with
     * the question and the panel steps aside (the launchedApp path). Robot never hears the answer; with the Claude app you
     * can read it back with the corner swipe.
     */
    private suspend fun handOff(replyId: String, text: String, h: HelperConfig) {
        port.handedToApp(text.takeIf { it.isNotBlank() })
        replies.markHelper(replyId, null)
        val scripted = object : TurnModel {
            override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(RawToolCall(ToolName.ASK_HELPER.id, mapOf("question" to text, "helper" to h.id))))
            override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
            override fun close() {}
        }
        val r = port.handOffRun(replyId, scripted)
        port.finishReply(replyId, DoneReason.END, costMicros = 0)
        if (r.launchedApp) port.leaveForApp()
    }

    private companion object {
        /**
         * Failures that mean the helper can't be reached right now (your server is off, no connection, it timed out): a
         * question Robot sent by itself, before its own model ran, comes back to Robot instead.
         */
        val FALLS_BACK = setOf(HelperFailure.NO_INTERNET, HelperFailure.UNREACHABLE, HelperFailure.TIMEOUT, HelperFailure.SERVER,
            HelperFailure.OVERLOADED, HelperFailure.NO_ADDRESS, HelperFailure.NOT_LOCAL, HelperFailure.CERTIFICATE)
    }
}
