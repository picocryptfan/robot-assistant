// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Escalation
import app.heyrobot.core.EscalationDecision
import app.heyrobot.core.EscalationGuard
import app.heyrobot.core.EscalationPolicy
import app.heyrobot.core.EscalationTrigger
import app.heyrobot.core.Hedge
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.Personal
import app.heyrobot.core.PreRouter
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Shared
import app.heyrobot.core.TurnResult
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.helpers.HelperChoice
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.QuestionKind
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.memory.MemoryChoice
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.sys.HandOffIntents
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

// VOICE PROCESS. When Robot brings a helper in by itself (docs/ask-claude.md "When Robot needs help"): the pre-router's
// strong signals before Robot's own model runs, the model handing over (ask_claude) with Robot's own answer from what the
// phone holds first, and a reply that hedged; then the helper asked now, an offer under the reply, the unlock line, or
// nothing. The policy is core EscalationPolicy's, what may go HelperContext's and HelperTrust's; this is the panel's side
// of it. The helper's turn is PanelHelperTurn's and the offer PanelOffers'; Robot's model, the reply's end, the voice
// and "not Jarvis" stay in PanelController, through [PanelEscalation.Port]. Its callers run on the main thread, in the
// panel's scope, as before.

/** Escalation over PanelController's [state] and [chat] (the same list, read live); [port]: what it needs of PanelController. */
internal class PanelEscalation(private val state: MutableStateFlow<PanelState>, private val chat: List<ChatTurn>, private val port: Port) {
    /** What escalation needs of PanelController. */
    interface Port {
        /** The voice process's context (VoiceHub.app). */
        val ctx: Context
        /** The settings now (VoiceHub.settings). */
        val settings: RobotSettings
        /** The turn's languages, read live. */
        val turnLangs: Langs
        /** What Robot says of [p] in the turn's reply language. */
        fun said(p: Phrase): String
        /** The phone is locked (Assistant.locked). */
        fun locked(): Boolean
        /** Whether helper [h]'s key is saved. */
        fun keySaved(h: HelperConfig?): Boolean
        /** Today's budget for all helpers, or [h]'s own, is used up (HelperLink.budgetReached, with [s]). */
        suspend fun budgetReached(s: RobotSettings, h: HelperConfig?): Boolean
        /** You said "not Jarvis" in this conversation (send's router decides it). */
        val helperDeclined: Boolean
        /** Your region for a helper's first message. */
        val userRegion: String
        /** The personal content in the conversation now, the reply [replyId] included while it is still on screen only. */
        fun personalNow(replyId: String?): Set<Personal>
        /** A notice as it was rendered. */
        fun notify(text: String)
        /** Robot's own words aloud, in its voice for the turn's reply language. */
        fun speakRobot(text: String)
        /** Robot's own sentence into its empty reply, shown and said in its voice. */
        fun sayInto(replyId: String, text: Phrase)
        fun finishReply(replyId: String, reason: DoneReason)
        /** Finishes Robot's reply [replyId]; an empty one with no card is dropped instead. */
        fun finishOrDrop(replyId: String)
        fun failReply(replyId: String, message: String)
        /** The turn opened an app: the panel goes. */
        suspend fun leaveForApp()
        /** Robot answers from what its own web search found (PanelWebSearch.answer). */
        suspend fun webAnswer(replyId: String, question: String, history: List<ChatTurn>, found: WebOutcome)
        /** The offer [o] under reply [replyId] (PanelOffers.show). */
        fun offer(replyId: String, o: PanelOffers.Pending)
        /** Robot checks with helper [h] with exactly [blocks] (PanelHelperTurn.escalate). */
        suspend fun escalate(replyId: String?, question: String, blocks: List<HelperBlock>, voiceAsked: Boolean, reuseBubble: Boolean, h: HelperConfig,
            fallBack: Boolean): Boolean
        /** Your remembered choice for [kind], the one Robot may follow now (Memory on, and readable with the phone as it is). */
        suspend fun rememberedChoice(kind: QuestionKind): MemoryChoice?
        /** "Try it here or ask": what was picked; null: nothing, in time (PanelHelperTurn.askChoice). */
        suspend fun askChoice(replyId: String, h: HelperConfig): HelperChoice.Picked?
        /** Opens helper [h]'s app with [question] (PanelHelperTurn.handOff): the turn ends there. */
        suspend fun handOff(replyId: String, question: String, h: HelperConfig)
        /** Offers to remember [k] under reply [replyId]. */
        fun keepOffer(replyId: String, k: KeepChoice)
    }

    /**
     * What the pre-router left for after Robot's own answer: an offer ([offerAfter]), and whether it [needsSearch]; [helper]: the
     * helper it's for (your remembered one), null for the usual; [hold]: this question stays with Robot unless you tap;
     * [keep]: you picked "try it here", so offer to remember that.
     */
    class Before(val offerAfter: EscalationTrigger?, val needsSearch: Boolean, val helper: HelperConfig? = null, val hold: Boolean = false, val keep: KeepChoice? = null)

    /** This conversation already said no helper is set up. */
    private var noHelperSaid = false

    /** A new or loaded conversation. */
    fun newConversation() {
        noHelperSaid = false
    }

    /** The day the "budget used up" sentence was said (once a day). */
    private var budgetSaid: String? = null

    /** The reply whose "try it here or ask" nobody answered: the card has gone, so the offer under it says it was a lot. */
    private var unanswered: String? = null

    private fun words(h: HelperConfig) = HelperWords(HelperWho.of(h))

    /** The helper Robot checks with when it needs help ("When Robot needs help › Ask:"), or none. */
    private fun target(s: RobotSettings): HelperConfig? = HelperSettings.target(s) { port.keySaved(it) }

    /**
     * Escalation trigger a, before Robot's model runs on [question] ([s] and [target]: as runTurn began): strong signals go
     * to the helper before Gemma spends a prefill (Automatic only); with Ask first, Gemma answers and the offer follows its
     * reply. [localWeather], [localWeb]: the weather, and current information, are Robot's own to look up. Null: the helper
     * took the question.
     */
    suspend fun beforeModel(replyId: String, question: String, history: List<ChatTurn>, attachment: Attachment, voiceAsked: Boolean, guard: EscalationGuard,
        s: RobotSettings, target: HelperConfig?, localWeather: Boolean, localWeb: Boolean): Before? {
        unanswered = null
        // Not after "not Jarvis": that conversation stays with Robot. A screen or a photo only a tap sends: nothing here.
        if (attachment != Attachment.NONE || port.helperDeclined) return Before(null, false)
        val signal = PreRouter.signal(question, localWeather, localWeb)
        val kind = HelperChoice.kindOf(question, localWeather, localWeb)
        if (signal == null && kind == null) return Before(null, false)
        // F18: a saved route covers its kind even when the question holds an action word ("add a column in SQL"), which has
        // no kind of its own. Such a question opens no card by itself: under Ask first it's only offered (remembered, below).
        val saved = (kind ?: signal?.let(QuestionKind::of))?.let { port.rememberedChoice(it) }
        // F19: a route to a helper you've turned off or removed since is no route: what you set for everything else applies.
        val choice = saved?.takeIf { c -> c.robot || c.helper == null || s.helper(c.helper)?.on == true }
        val asked = choice?.helper?.let { id -> s.helper(id) } ?: target
        val needsSearch = signal == PreRouter.Signal.CURRENT_INFO
        val decision = if (asked == null) EscalationDecision.NONE else
            decide(attachment, guardFree = !guard.used, needsSearch = needsSearch, asked = asked, remembered = choice?.robot == false && kind != null)
        val step = HelperChoice.step(signal != null, kind, choice?.robot, decision, helperAround = asked != null)
        RLog.i("helper_trigger", "kind" to EscalationTrigger.PRE_ROUTER, "signal" to signal, "step" to step, "remembered" to (choice != null))
        return when (step) {
            HelperChoice.Step.NOTHING -> Before(null, false)
            HelperChoice.Step.ROBOT -> Before(null, false, hold = true)
            HelperChoice.Step.SAY_NO_HELPER -> {
                // Only with no helper on at all, and not with "When Robot needs help" off: that asks for no such line.
                if (!noHelperSaid && s.help != Escalation.OFF && s.helpers.none { it.on }) {
                    noHelperSaid = true
                    port.notify(HelperStrings.choiceNoHelper().render(port.turnLangs))
                    if (state.value.speak) port.speakRobot(port.said(HelperStrings.choiceNoHelper()))
                }
                Before(null, false)
            }
            HelperChoice.Step.GO -> {
                asked!!
                guard.take(fromHelper = false)
                val personal = port.personalNow(null)
                // F21: a saved route promises your question and nothing else (HelperStrings' route dialog).
                val blocks = if (choice?.robot == false) HelperContext.oneShot(question, ZonedDateTime.now(), port.userRegion, app.heyrobot.core.Shared())
                else HelperContext.escalation(question, history, ZonedDateTime.now(), port.userRegion, personal = personal,
                    includePersonal = HelperTrust.conversation(s, asked, personal, MemoryPanel.toTrusted()))
                // A helper that can't be reached (a server that's off, no connection): Robot answers itself instead.
                if (!port.escalate(replyId, question, blocks, voiceAsked, reuseBubble = true, h = asked, fallBack = true)) return null
                RLog.i("helper_fallback", "kind" to asked.kind)
                Before(null, false)
            }
            HelperChoice.Step.OFFER_AFTER -> Before(EscalationTrigger.PRE_ROUTER, needsSearch, helper = asked, hold = choice?.robot == false)
            HelperChoice.Step.CHOICE -> choose(replyId, question, voiceAsked, guard, s, asked!!, kind!!, needsSearch)
        }
    }

    /**
     * "Try it here or ask your helper." The helper: your question and nothing else goes, as for "ask the helper …". Here: Robot's
     * model, no offer on top. Nothing: Robot tries, and the offer stays under its answer for a tap or a yes. Null: the helper took it.
     */
    private suspend fun choose(replyId: String, question: String, voiceAsked: Boolean, guard: EscalationGuard, s: RobotSettings, h: HelperConfig, kind: QuestionKind,
        needsSearch: Boolean): Before? {
        val api = HelperSettings.apiReady(h, port.keySaved(h))
        val noAnswer = Before(EscalationTrigger.PRE_ROUTER, needsSearch, helper = h, hold = true)
        if (!api && h.presetOf.app?.let { pkg -> withContext(Dispatchers.IO) { HandOffIntents.installed(port.ctx, pkg) } } != true) return noAnswer
        return when (port.askChoice(replyId, h)) {
            null -> noAnswer.also { unanswered = replyId }
            HelperChoice.Picked.HERE -> Before(null, false, hold = true, keep = KeepChoice(kind, robot = true))
            HelperChoice.Picked.HELPER -> {
                guard.take(fromHelper = false)
                // Asked now, and the panel goes: nothing stays on screen to answer an offer, so saying "always use it" is the way to keep this one.
                if (!api) {
                    port.handOff(replyId, question, h)
                    return null
                }
                val blocks = HelperContext.oneShot(question, ZonedDateTime.now(), port.userRegion, app.heyrobot.core.Shared())
                if (port.escalate(replyId, question, blocks, voiceAsked, reuseBubble = true, h = h, fallBack = true)) {
                    RLog.i("helper_fallback", "kind" to h.kind)
                    return Before(null, false)
                }
                if (state.value.turns.firstOrNull { it.id == replyId }?.text?.isNotBlank() == true) port.keepOffer(replyId, KeepChoice(kind, robot = false, helper = h.id, name = h.name, tapOnly = true))
                null
            }
        }
    }

    /**
     * The escalation policy for this question now ([attachment]: its screen or photo, which only a tap may send, unless
     * "Send personal content to helpers automatically" is on).
     */
    private suspend fun decide(attachment: Attachment, guardFree: Boolean, needsSearch: Boolean = false, asked: HelperConfig? = null, remembered: Boolean = false): EscalationDecision {
        val s = port.settings
        val target = asked ?: target(s)
        return EscalationPolicy.decide(s, target, port.keySaved(target), attachment == Attachment.FILE || attachment != Attachment.NONE && !HelperTrust.attachments(s, port.personalNow(null)), port.locked(),
            port.budgetReached(s, target), !guardFree, declined = port.helperDeclined, priceKnown = target?.price?.known ?: false, needsSearch = needsSearch, remembered = remembered)
    }

    /**
     * Robot's reply [replyId] is in (or Gemma handed over with ask_claude): bring the helper in now, offer it, or say why not.
     * At most one escalation per turn ([guard]); [offerOnly]: the pre-router's offer, after Gemma's answer. [screen] and
     * [image]: this question's screen text and picture, which only an offer's tap may send. [ownAnswer]: the model once
     * more on the same question without ask_claude, for when it handed over without a word (EscalationPolicy.ownAnswerFirst):
     * its answer comes first, any offer goes under it, and with Automatic the helper is asked only when that answer is
     * missing or unsure (EscalationPolicy.afterOwnAnswer). [needsSearch]: the
     * question needs today's sources (the pre-router's current information, or a reply that said it has none): it goes by
     * itself only to a helper that can search, as the pre-router decided (0.6.0). [helper]: your remembered helper for it, for the
     * usual one. [hold]: you kept this with Robot, or didn't choose: the helper is only offered, even with Automatic.
     */
    suspend fun afterLocal(replyId: String, question: String, history: List<ChatTurn>, attachment: Attachment, screen: String?, image: ByteArray?,
        voiceAsked: Boolean, guard: EscalationGuard, trigger: EscalationTrigger, offerOnly: Boolean = false, ownAnswer: (suspend () -> TurnResult)? = null,
        needsSearch: Boolean = false, helper: HelperConfig? = null, hold: Boolean = false) {
        // A turn that already escalated decides NONE here.
        var decision = decide(attachment, guardFree = !guard.used, needsSearch = needsSearch, asked = helper).held(hold)
        guard.take(fromHelper = false)
        val s = port.settings
        val target = helper ?: target(s)
        val keySaved = port.keySaved(target)
        // Never only "I'd rather check that with Jarvis.": the model's own answer first, in the same reply (and turn).
        var ranOwn = false
        var ownFailure: String? = null
        var asWell = false
        // 1.1: with Automatic too, Robot's own answer from what the phone holds comes first ("own notes first then helper").
        if (ownAnswer != null && EscalationPolicy.ownAnswerFirst(trigger == EscalationTrigger.LOCAL_TOOL, said = !saidNothing(replyId))) {
            ranOwn = true
            val r = ownAnswer()
            ownFailure = r.failure
            // Stopped meanwhile: the turn ends as stopped, with no offer.
            currentCoroutineContext().ensureActive()
            val answered = !saidNothing(replyId)
            val own = state.value.turns.firstOrNull { it.id == replyId }
            val hedged = EscalationPolicy.ownAnswerHedged(own?.text.orEmpty(), acted = own?.actions?.isNotEmpty() == true)
            VoiceLog.add(VoiceEvent.OWN_ANSWER, if (answered) 1 else 0)
            RLog.i("helper_own_answer", "answered" to answered, "decision" to decision, "failed" to (r.failure != null), "again" to r.escalate, "hedged" to hedged)
            if (r.launchedApp) {
                port.finishReply(replyId, DoneReason.END)
                return port.leaveForApp()
            }
            if (r.reason == DoneReason.CANCELLED) return port.finishReply(replyId, DoneReason.CANCELLED)
            // Automatic, and the model looked it up on the web itself (Robot searches first): Robot answers from what it found.
            if (decision == EscalationDecision.AUTO) r.web?.let { found ->
                RLog.i("helper_escalation", "trigger" to trigger, "decision" to EscalationDecision.NONE)
                return port.webAnswer(replyId, question, history, found)
            }
            if (answered) {
                port.finishReply(replyId, r.reason ?: DoneReason.END)
                asWell = true
            }
            // Automatic: an answer that stands is the answer. A missing or unsure one goes to the helper as a hedge would: by
            // itself only to a helper that can search when it needs today's sources, otherwise offered under it.
            val after = EscalationPolicy.afterOwnAnswer(decision, answered, hedged)
            decision = if (after == EscalationDecision.AUTO && answered && Hedge.needsSearch(own?.text.orEmpty()))
                decide(attachment, guardFree = true, needsSearch = true, asked = helper).held(hold) else after
        }
        // The budget is used up for today: Automatic asks first, and Robot says so once a day.
        if (decision == EscalationDecision.OFFER && target != null && HelperSettings.apiReady(target, keySaved) &&
            HelperSettings.escalation(s, target, keySaved) == Escalation.AUTO && port.budgetReached(s, target)) {
            val day = LocalDate.now().toString()
            if (budgetSaid != day) {
                budgetSaid = day
                port.notify(words(target).budgetText.render(port.turnLangs))
                if (state.value.speak) port.speakRobot(port.said(words(target).budgetText))
            }
        }
        // A personal conversation (this reply's read included): Robot sends only the question.
        val personal = port.personalNow(replyId)
        when (decision) {
            EscalationDecision.AUTO -> if (offerOnly || target == null) port.finishOrDrop(replyId) else {
                // A screen or photo question gets here only with personal content allowed to go by itself: it goes along.
                val shared = if (attachment != Attachment.NONE && attachment != Attachment.FILE && HelperTrust.attachments(s, personal)) Shared(screenText = screen, image = image) else Shared()
                val blocks = HelperContext.escalation(question, history, ZonedDateTime.now(), port.userRegion, shared, personal = personal,
                    includePersonal = HelperTrust.conversation(s, target, personal, MemoryPanel.toTrusted()))
                port.finishOrDrop(replyId)
                port.escalate(null, question, blocks, voiceAsked, reuseBubble = false, h = target, fallBack = false)
            }
            EscalationDecision.OFFER -> {
                target!!
                val api = HelperSettings.apiReady(target, keySaved)
                // No API to ask and no app to open: nothing to offer.
                if (!api && target.presetOf.app?.let { pkg -> withContext(Dispatchers.IO) { HandOffIntents.installed(port.ctx, pkg) } } != true) {
                    if (saidNothing(replyId)) port.sayInto(replyId, PanelStrings.notSure())
                    if (ranOwn) port.finishReply(replyId, DoneReason.END) else port.finishOrDrop(replyId)
                    return RLog.i("helper_escalation", "trigger" to trigger, "decision" to EscalationDecision.NONE)
                }
                if (chat.none { it.id == replyId }) {
                    // Gemma said nothing before asking for Claude, not even when asked again: the offer stands on its own.
                    if (state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) port.sayInto(replyId, if (api) words(target).ratherText else PanelStrings.helperCouldHelp("name" to target.name))
                    port.finishReply(replyId, DoneReason.END)
                }
                // What the tap sends is fixed now: this question's screen or picture, never one that arrives later.
                val payload = if (!api) null else HelperContext.payload(question, history, ZonedDateTime.now(), port.userRegion, personal, attachment,
                    Shared(screenText = screen, image = image), includePersonal = HelperTrust.conversation(s, target, personal, MemoryPanel.toTrusted()))
                port.offer(replyId, PanelOffers.Pending(api, question, payload, voiceAsked, asWell, target.id, beyond = unanswered == replyId))
            }
            EscalationDecision.NEEDS_UNLOCK -> {
                if (chat.none { it.id == replyId }) {
                    if (state.value.turns.firstOrNull { it.id == replyId }?.text.isNullOrBlank()) port.sayInto(replyId, target?.let { words(it).unlockToCheckText } ?: PanelStrings.unlockFirst())
                    port.finishReply(replyId, DoneReason.END)
                }
            }
            // Asked again and still nothing: why, or "Robot had no answer", rather than no reply at all.
            EscalationDecision.NONE -> when {
                !ranOwn -> port.finishOrDrop(replyId)
                ownFailure != null && saidNothing(replyId) -> port.failReply(replyId, ownFailure)
                else -> port.finishReply(replyId, DoneReason.END)
            }
        }
        RLog.i("helper_escalation", "trigger" to trigger, "decision" to decision)
    }

    private fun EscalationDecision.held(hold: Boolean) = if (hold && this == EscalationDecision.AUTO) EscalationDecision.OFFER else this

    /** Robot's reply [replyId] is still open and holds neither a word nor a card (Gemma handed straight over). */
    private fun saidNothing(replyId: String): Boolean = chat.none { it.id == replyId } &&
        state.value.turns.firstOrNull { it.id == replyId }?.let { it.text.isBlank() && it.actions.isEmpty() } == true
}
