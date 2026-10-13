package app.heyrobot.helpers

import app.heyrobot.core.DoneReason
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.TurnModel

/**
 * A helper as a [TurnModel] (every helper since 0.6.0; Claude only before): the turn runner shows the helper's calls as cards
 * (remote = true) and sends their results back. [first]: the question's blocks.
 */
class HelperTurn(
    private val session: HelperSession,
    private val first: List<HelperBlock>,
    private val plan: HelperPlan,
    private val listener: HelperTurnListener,
    /** How a failure is said, in the helper's name (HelperWho; the Claude helper's words by default). */
    private val say: (HelperFailure) -> String = { it.text },
) : TurnModel {
    /** Why the last request failed, if it did (a server that can't be reached may give the question back to Robot). */
    var failure: HelperFailure? = null
        private set

    override suspend fun ask(): ModelOutcome = outcome(session.ask(first, plan, listener))

    override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = outcome(session.answer(results.map { it.second }, listener))

    override fun close() = session.abandon()

    override fun abandon(results: List<Pair<String, Map<String, Any?>>>) = session.abandon(results.map { it.second })

    override fun stopped(results: List<Pair<String, Map<String, Any?>>>) = session.abandon(results.map { it.second }, stopped = true)

    private fun outcome(o: HelperOutcome): ModelOutcome = when (o) {
        is HelperOutcome.Done -> ModelOutcome.Done(if (o.cut) DoneReason.LENGTH else DoneReason.END)
        is HelperOutcome.Calls -> ModelOutcome.Calls(o.calls)
        // The listener already put "Jarvis declined that one." in place of the reply.
        HelperOutcome.Declined -> ModelOutcome.Done(DoneReason.END)
        is HelperOutcome.Failed -> {
            failure = o.failure
            ModelOutcome.Failed(say(o.failure))
        }
    }
}
