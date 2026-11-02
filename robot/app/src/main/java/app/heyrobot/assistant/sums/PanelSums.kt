// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.sums

import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.sums.Calculate
import app.heyrobot.core.sums.Sums
import app.heyrobot.core.sums.SumsAnswer
import app.heyrobot.core.sums.SumsCommand
import app.heyrobot.core.sums.SumsRouter
import java.time.ZonedDateTime
import java.util.UUID

/**
 * VOICE PROCESS. The panel's sums slot (docs/plans/everyday-set-plan.md §4, D1, D6): after music and before What went
 * online (PanelController.send). Your words as a sum (core SumsRouter), worked out and answered by code (core Sums) in the
 * turn's language, on screen and aloud; nothing goes online, and it works locked and during a phone lock (it reads nothing of
 * yours and changes nothing, §4.7). "$80 plus HST" asks which rate, with the provinces' rates on chips. The voice log keeps
 * the sum's kind and how it ended, never its numbers.
 */
object PanelSums {
    fun route(q: String): SumsCommand? = SumsRouter.route(q, ZonedDateTime.now())

    suspend fun handle(replyId: String, cmd: SumsCommand, host: PanelRoutines.Host) {
        val l = host.langs(null, cmd.lang)
        try {
            var a = Sums.answer(cmd)
            reply(replyId, a, host, l)
            if (cmd is SumsCommand.Tax && a.rates.isNotEmpty()) {
                // "Which rate?": a chip per province's rate; a tap answers with the sum at that rate.
                val id = UUID.randomUUID().toString()
                val chips = a.rates.map { Sums.pct(it).render(l) }
                val pick = host.pick(replyId, Card(id, null, a.text.render(l), ActionStatus.PENDING, null, chips = chips, cancel = UiStrings.cancel().render(l)))
                if (pick >= Card.CHIP && pick - Card.CHIP < a.rates.size) {
                    host.show(replyId, Card(id, null, chips[pick - Card.CHIP], ActionStatus.DONE, null))
                    a = Sums.withRate(cmd, a.rates[pick - Card.CHIP])
                    reply(replyId, a, host, l)
                } else {
                    host.show(replyId, Card(id, null, a.text.render(l), ActionStatus.DECLINED, null))
                }
            }
            VoiceLog.add(VoiceEvent.SUMS, cmd.kind.id, a.result.id)
        } finally {
            host.finish(replyId)
        }
    }

    private fun reply(replyId: String, a: SumsAnswer, host: PanelRoutines.Host, l: Langs) {
        host.text(replyId, a.text.render(l))
        host.say(a.text.render(l.voice))
    }

    /**
     * The model's calculate (§4.4, D8): code works out the expression and its reply is the answer; the model's turn ends
     * with it (Executed.calc), so the model never copies the number.
     */
    fun calculate(action: Action.Calculate): Executed {
        val a = Calculate.run(action.ask)
        VoiceLog.add(VoiceEvent.CALC_TOOL, a.result.id)
        return Executed(ActionStatus.DONE, a.text, forModel = EverydayStrings.calculated().en(), calc = a.text)
    }
}
