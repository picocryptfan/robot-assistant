// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase

/** Why the steps ended. */
enum class StepEnd(val id: Int) { STOP(1), LEFT(2), IDLE(3), CALL(4), CAR(5) }

/** What a [StepSession] asks the app to do. */
sealed class StepAct {
    /** Show step [index] (0-based) of [total] in large text; [offerS]: a timer is offered for this many seconds. */
    data class Show(val index: Int, val total: Int, val text: String, val offerS: Long?) : StepAct()
    data class Say(val line: Phrase) : StepAct()
    /** You said yes to the offer: set a timer for [seconds]. Never sent without a yes. */
    data class SetTimer(val seconds: Long) : StepAct()
    data class End(val why: StepEnd) : StepAct()
}

// Step by step (docs/plans/step-by-step-plan.md): where you are in a page's steps, the timer offer and the ends (stop,
// leaving, [idleMs] with no word). Every word, tap or question counts as a word for the idle end. Pure: the app
// (assistant/steps/StepVoice) does what the acts say.
class StepSession(val steps: List<String>, private val page: String, now: Long, private val idleMs: Long = IDLE_MS) {
    var at = 0; private set
    /** The timer on offer for the step shown, in seconds, until the next word. */
    var offer: Long? = null; private set
    private var offerAt = 0L
    var ended: StepEnd? = null; private set
    private var lastWord = now

    fun start(now: Long): List<StepAct> { lastWord = now; return show(now) }

    fun word(w: StepWord, now: Long): List<StepAct> {
        if (ended != null) return emptyList()
        lastWord = now
        val o = offer
        offer = null
        return when (w) {
            // The timer button goes with the offer (a yes or a no).
            StepWord.YES -> (if (o != null && now - offerAt < OFFER_MS) listOf(StepAct.SetTimer(o)) else emptyList()) + listOfNotNull(o?.let { hide() })
            StepWord.NO -> listOfNotNull(o?.let { hide() })
            StepWord.NEXT -> if (at + 1 < steps.size) { at++; show(now) } else listOf(StepAct.Say(HandsFreeStrings.stepsLast()))
            StepWord.BACK -> if (at > 0) { at--; show(now) } else listOf(StepAct.Say(HandsFreeStrings.stepsFirst())) + show(now)
            StepWord.AGAIN -> show(now)
            StepWord.STOP -> end(StepEnd.STOP)
        }
    }

    /** "How much butter?": the page's own line, or that the page doesn't say. */
    fun ask(question: String, now: Long): List<StepAct> {
        if (ended != null) return emptyList()
        lastWord = now
        val line = StepAsk.answer(page, question, steps[at])
        return listOf(StepAct.Say(if (line != null) Phrase.Plain(line) else HandsFreeStrings.stepsPageSilent()))
    }

    /** Called now and then: [idleMs] with no word ends the steps; the timer offer closes [OFFER_MS] after it's made. */
    fun tick(now: Long): List<StepAct> {
        if (ended != null) return emptyList()
        if (now - lastWord >= idleMs) return end(StepEnd.IDLE)
        if (offer != null && now - offerAt >= OFFER_MS) { offer = null; return listOf(StepAct.Show(at, steps.size, steps[at], null)) }
        return emptyList()
    }

    /** You left the steps' window. */
    fun left(): List<StepAct> = if (ended == null) end(StepEnd.LEFT) else emptyList()

    /** A call, a silenced microphone or Car mode: the steps end with nothing said. */
    fun cut(why: StepEnd): List<StepAct> = if (ended == null) end(why) else emptyList()

    private fun end(why: StepEnd): List<StepAct> {
        ended = why
        offer = null
        val line = when (why) {
            StepEnd.IDLE -> HandsFreeStrings.stepsIdle()
            StepEnd.STOP -> HandsFreeStrings.stepsEnded()
            StepEnd.LEFT, StepEnd.CALL, StepEnd.CAR -> null
        }
        return listOfNotNull(line?.let { StepAct.Say(it) }, StepAct.End(why))
    }

    private fun hide() = StepAct.Show(at, steps.size, steps[at], null)

    private fun show(now: Long): List<StepAct> {
        val text = steps[at]
        val o = StepTime.seconds(text)
        offer = o
        offerAt = now
        val said = HandsFreeStrings.stepLine("n" to (at + 1).toLong(), "total" to steps.size.toLong(), "text" to text)
        return listOfNotNull(StepAct.Show(at, steps.size, text, o), StepAct.Say(said),
            o?.let { StepAct.Say(HandsFreeStrings.stepsTimerOffer("duration" to Arg.Seconds(it))) })
    }

    companion object {
        /** No word for this long ends the steps (the plan's default, 30 minutes). */
        const val IDLE_MS = 30 * 60_000L
        /** The timer offer closes this long after it's made (or at the next word). */
        const val OFFER_MS = 30_000L
    }
}
