package app.heyrobot.core.car

import app.heyrobot.core.output.AnswerCode
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.output.OutputLines
import app.heyrobot.core.output.ReplyWords
import app.heyrobot.core.output.SpokenCard
import app.heyrobot.core.output.SpokenCards
import app.heyrobot.core.output.SpokenChoice
import app.heyrobot.core.output.SpokenLang

// Pure. The car's multi-step flows as state machines (design §4.5): a call (choices, read-back, yes) and a message
// (announce, read, reply, read-back, send). CarModeController keeps one flow at a time, gives it each thing heard (the
// transcript, "" for silence) and carries out what it returns: say a line and listen again, place the call, send the
// reply, or keep something for parking. Robot's own last line is kept in the state, so the microphone hearing it back
// is never taken for an answer.

/** What a flow asks the controller to do. */
sealed class FlowEffect {
    /** Say [text], then listen for the answer when [listen]. */
    data class Say(val text: String, val listen: Boolean) : FlowEffect()
    /** ACTION_DIAL or ACTION_CALL (after the spoken yes) with [number]. */
    data class Place(val number: String, val way: CallWay) : FlowEffect()
    /** Hand exactly [text] to the conversation's Reply action (ReplyChannel); say CarMessages.result after. */
    data class Send(val conversationKey: String, val text: String) : FlowEffect()
    /** Keep it for parking ([summary]: the card's words, personal, never logged). */
    data class Park(val code: ParkCode, val summary: String) : FlowEffect()
}

/** A call in progress: the current plan (choices or read-back), unusable answers so far, re-reads, and Robot's last line. */
data class CallFlowState(val plan: CallPlan, val tries: Int = 0, val rereads: Int = 0, val said: String = "", val done: Boolean = false)

object CallFlow {
    /** Start from CallTarget.plan: ask, or say why not (and keep it for parking when that is why). */
    fun start(plan: CallPlan, asked: String, lang: SpokenLang): Pair<CallFlowState, List<FlowEffect>> {
        val st = CallFlowState(plan)
        return when (plan) {
            is CallPlan.Confirm -> ask(st, plan.card, lang)
            is CallPlan.Choose -> ask(st, plan.card, lang)
            is CallPlan.Emergency -> st.copy(done = true) to listOf(FlowEffect.Place(plan.number, CallWay.DIAL), FlowEffect.Say(plan.say, false))
            is CallPlan.Hold -> st.copy(done = true) to listOf(FlowEffect.Say(plan.say, false), FlowEffect.Park(if (plan.why == CallHold.LEARNED) ParkCode.LEARNED_NAME else ParkCode.TOO_MANY, call(asked, lang)))
            is CallPlan.Refuse -> st.copy(done = true) to listOf(FlowEffect.Say(plan.say, false))
        }
    }

    private fun call(asked: String, lang: SpokenLang) = (if (lang == SpokenLang.FR) "Appeler " else "Call ") + asked.trim()

    private fun ask(st: CallFlowState, card: SpokenCard, lang: SpokenLang): Pair<CallFlowState, List<FlowEffect>> {
        val p = SpokenCards.prompt(card, lang)
        return st.copy(said = p) to listOf(FlowEffect.Say(p, true))
    }

    /** [heard] (a transcript; "" for silence) in answer to the current card. */
    fun on(st: CallFlowState, heard: String, lang: SpokenLang, wakePhrases: List<String> = emptyList()): Pair<CallFlowState, List<FlowEffect>> {
        if (st.done) return st to emptyList()
        val card = when (val p = st.plan) {
            is CallPlan.Confirm -> p.card
            is CallPlan.Choose -> p.card
            else -> return st.copy(done = true) to emptyList()
        }
        val a = SpokenChoice.answer(heard, card, st.said, wakePhrases)
        return when (val next = CallTarget.answer(st.plan, a, st.tries, st.rereads, lang)) {
            is CallNext.Place -> st.copy(done = true) to listOf(FlowEffect.Place(next.number, next.way))
            is CallNext.Ask -> ask(CallFlowState(next.plan), next.plan.card, lang)
            is CallNext.Cancelled -> st.copy(done = true) to listOf(FlowEffect.Say(next.say, false))
            CallNext.Reread -> st.copy(rereads = st.rereads + 1) to listOf(FlowEffect.Say(st.said, true))
            is CallNext.AskAgain -> st.copy(tries = st.tries + 1, said = next.say) to listOf(FlowEffect.Say(next.say, true))
            is CallNext.Held -> st.copy(done = true) to listOf(FlowEffect.Say(next.say, false), FlowEffect.Park(ParkCode.NO_ANSWER, card.action))
        }
    }
}

/** Where a message flow is. */
enum class MessageStep { ANNOUNCED, OFFERED, DICTATING, READ_BACK, DONE }

data class MessageFlowState(
    val notice: MessageNotice,
    val step: MessageStep,
    val draft: ReplyDraft? = null,
    val tries: Int = 0,
    val rereads: Int = 0,
    val said: String = "",
)

/** What a message flow needs from settings and the moment. */
data class MessageFlowContext(val settings: CarSettings, val safety: HandsFreeSafety, val locked: Boolean, val trusted: Boolean, val lang: SpokenLang)

object MessageFlow {
    /** A new message ([senderAllowed]: "Announce from: people you choose"): announced, read at once, or nothing. */
    fun start(n: MessageNotice, c: MessageFlowContext, senderAllowed: Boolean): Pair<MessageFlowState?, List<FlowEffect>> =
        when (val a = CarMessages.announce(n, c.settings, c.locked, c.trusted, senderAllowed, c.lang)) {
            is CarAnnouncement.Skip -> null to emptyList()
            is CarAnnouncement.Announce -> if (n.hidden) null to listOf(FlowEffect.Say(a.say, false))
                else MessageFlowState(n, MessageStep.ANNOUNCED, said = a.say) to listOf(FlowEffect.Say(a.say, true))
            is CarAnnouncement.ReadNow -> afterReading(MessageFlowState(n, MessageStep.ANNOUNCED), a.say, c)
        }

    /** "Read my messages" with [n] the newest: read it, then offer a reply. */
    fun read(n: MessageNotice, c: MessageFlowContext): Pair<MessageFlowState, List<FlowEffect>> =
        afterReading(MessageFlowState(n, MessageStep.ANNOUNCED), CarMessages.says(n, c.lang), c)

    /** "Reply …" said right away (HandsFreeRouter.Reply) for the conversation last read. */
    fun reply(n: MessageNotice, dictated: String, c: MessageFlowContext): Pair<MessageFlowState, List<FlowEffect>> =
        startReply(MessageFlowState(n, MessageStep.DICTATING), dictated, c)

    private fun afterReading(st: MessageFlowState, reading: String, c: MessageFlowContext): Pair<MessageFlowState, List<FlowEffect>> {
        if (!CarMessages.offersReply(st.notice, c.settings, c.safety)) return st.copy(step = MessageStep.DONE) to listOf(FlowEffect.Say(reading, false))
        val ask = CarMessages.ASK_REPLY.get(c.lang)
        return st.copy(step = MessageStep.OFFERED, said = ask, tries = 0, rereads = 0) to listOf(FlowEffect.Say(reading, false), FlowEffect.Say(ask, true))
    }

    private fun startReply(st: MessageFlowState, dictated: String, c: MessageFlowContext): Pair<MessageFlowState, List<FlowEffect>> =
        when (val r = CarMessages.startReply(st.notice, dictated, c.settings, c.safety, c.locked, c.trusted, c.lang)) {
            is ReplyStart.ReadBack -> st.copy(step = MessageStep.READ_BACK, draft = r.draft, said = r.say, tries = 0, rereads = 0) to listOf(FlowEffect.Say(r.say, true))
            is ReplyStart.AskText -> if (st.step == MessageStep.DICTATING && st.tries + 1 >= SpokenChoice.MAX_TRIES) {
                st.copy(step = MessageStep.DONE) to listOf(FlowEffect.Say(OutputLines.KEEP_FOR_PARK.get(c.lang), false),
                    FlowEffect.Park(ParkCode.REPLY, replyTo(st.notice, c.lang)))
            } else st.copy(step = MessageStep.DICTATING, said = r.say, tries = if (st.step == MessageStep.DICTATING) st.tries + 1 else 0) to listOf(FlowEffect.Say(r.say, true))
            is ReplyStart.Hold -> st.copy(step = MessageStep.DONE) to listOf(FlowEffect.Say(r.say, false), FlowEffect.Park(ParkCode.REPLY, replyTo(st.notice, c.lang)))
        }

    private fun replyTo(n: MessageNotice, lang: SpokenLang) = (if (lang == SpokenLang.FR) "Répondre à " else "Reply to ") + n.sender

    /** [heard] ("" for silence) at the current step. */
    fun on(st: MessageFlowState, heard: String, c: MessageFlowContext, wakePhrases: List<String> = emptyList()): Pair<MessageFlowState, List<FlowEffect>> {
        val lang = c.lang
        return when (st.step) {
            MessageStep.DONE -> st to emptyList()
            MessageStep.ANNOUNCED, MessageStep.OFFERED -> {
                // "Read it?" / "Reply?": yes or no; at "Reply?" the reply itself may come straight away ("tell her I'm late").
                if (st.step == MessageStep.OFFERED && ReplyWords.hasLead(heard)) return startReply(st, heard, c)
                val a = SpokenChoice.answer(heard, SpokenCard(st.said.removeSuffix("?")), st.said, wakePhrases)
                when {
                    a.sure && a.kind == AnswerCode.YES -> if (st.step == MessageStep.ANNOUNCED) afterReading(st, CarMessages.says(st.notice, lang), c)
                        else st.copy(step = MessageStep.DICTATING, said = CarMessages.WHAT_REPLY.get(lang), tries = 0) to listOf(FlowEffect.Say(CarMessages.WHAT_REPLY.get(lang), true))
                    a.sure && a.kind == AnswerCode.NO -> st.copy(step = MessageStep.DONE) to emptyList()
                    a.kind == AnswerCode.REPEAT && st.rereads < SpokenChoice.MAX_REREADS -> st.copy(rereads = st.rereads + 1) to listOf(FlowEffect.Say(st.said, true))
                    // Not answered: the message stays in its app; nothing is kept.
                    st.tries + 1 >= SpokenChoice.MAX_TRIES -> st.copy(step = MessageStep.DONE) to emptyList()
                    else -> st.copy(tries = st.tries + 1) to listOf(FlowEffect.Say(SpokenCards.hint(0, lang), true))
                }
            }
            MessageStep.DICTATING -> startReply(st, heard, c)
            MessageStep.READ_BACK -> {
                val d = st.draft!!
                val a = SpokenChoice.answer(heard, CarMessages.card(d, lang), st.said, wakePhrases)
                when (val next = CarMessages.answer(d, a, st.tries, st.rereads, lang)) {
                    is ReplyNext.Send -> st.copy(step = MessageStep.DONE) to listOf(FlowEffect.Send(next.conversationKey, next.text))
                    is ReplyNext.Done -> st.copy(step = MessageStep.DONE) to listOfNotNull(FlowEffect.Say(next.say, false),
                        if (next.why == ReplyEnd.SILENCE) FlowEffect.Park(ParkCode.REPLY, replyTo(st.notice, lang)) else null)
                    ReplyNext.Reread -> st.copy(rereads = st.rereads + 1) to listOf(FlowEffect.Say(st.said, true))
                    is ReplyNext.AskAgain -> st.copy(tries = st.tries + 1) to listOf(FlowEffect.Say(next.say, true))
                }
            }
        }
    }
}
