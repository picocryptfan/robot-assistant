// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.skills.SkillHelpers
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperEffort
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.RLog
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.skills.HelpReply
import app.heyrobot.core.skills.HelpRequest
import app.heyrobot.core.skills.SkillHelp
import app.heyrobot.helpers.HelperOutcome
import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.sys.HelperSpendStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// VOICE PROCESS. 1.3 (S3.9): the skills' way to a helper for a plan or a draft (PanelSkills.Host.helpers): one request with
// its own instruction, no tools, no web search and no history (HelperLink.skillSession), sent only by core SkillHelp.send
// after your Send on its card. Its cost counts in today's budget for helpers. Nothing here logs content.
object SkillHelperLink : SkillHelpers {
    /** How often a card waiting for you to park looks at car mode again. */
    private const val PARK_POLL_MS = 2_000L

    private val ctx get() = VoiceHub.app

    override fun keySaved(h: HelperConfig): Boolean = HelperLink.keySaved(ctx, h)

    override suspend fun spend(): HelperSpend = HelperSpendStore.cached() ?: withContext(Dispatchers.IO) { HelperSpendStore.current(ctx) }

    override val driving: Boolean get() = CarModeController.on

    override suspend fun untilParked() {
        while (CarModeController.on) delay(PARK_POLL_MS)
    }

    override suspend fun ask(h: HelperConfig, r: HelpRequest): HelpReply {
        if (!HelperLink.networkAllowed(ctx)) return HelpReply.Failed(HelperFailure.NO_INTERNET.sayText(HelperWho.of(h)))
        val session = withContext(Dispatchers.IO) { HelperLink.skillSession(ctx, h, r.system) }
            ?: return HelpReply.Failed(HelperFailure.NO_KEY.sayText(HelperWho.of(h)))
        val text = StringBuilder()
        try {
            val out = session.ask(listOf(HelperBlock.Text(r.words)), HelperPlan(HelperEffort.LOW, SkillHelp.MAX_TOKENS), object : HelperTurnListener {
                override fun text(delta: String) { text.append(delta) }

                override fun declined() { text.setLength(0) }

                override fun spent(dollars: Double) {
                    try { HelperSpendStore.add(ctx, h.id, dollars) } catch (t: Throwable) { RLog.w("help_spend", t) }
                }
            })
            RLog.i("help_ask", "job" to r.job.ordinal, "ok" to (out is HelperOutcome.Done))
            return when (out) {
                is HelperOutcome.Done -> if (text.isBlank()) HelpReply.Failed(ConversationStrings.helperSentNothing("name" to h.name)) else HelpReply.Text(text.toString())
                HelperOutcome.Declined -> HelpReply.Failed(ConversationStrings.helperDeclined("name" to h.name))
                is HelperOutcome.Failed -> HelpReply.Failed(out.failure.sayText(HelperWho.of(h)))
                // No tools were offered: a call can't come; if it did, nothing runs.
                is HelperOutcome.Calls -> HelpReply.Failed(ConversationStrings.helperUnusable("name" to h.name))
            }
        } finally {
            HelperLink.done(session)
        }
    }
}
