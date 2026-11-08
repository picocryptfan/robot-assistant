// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.Card
import app.heyrobot.core.RLog
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.HelpPort
import app.heyrobot.core.skills.HelpReply
import app.heyrobot.core.skills.HelpRequest
import app.heyrobot.core.skills.SkillHelp
import java.time.Instant
import java.time.LocalDate

/**
 * 1.3 (S3.9): the helpers a plan or a draft may go to, as the panel gives them ([PanelSkills.Host.helpers]); skills code
 * never names the helpers' clients. [ask] sends one request and nothing else; only core SkillHelp.send calls it, after the card.
 */
interface SkillHelpers {
    fun keySaved(h: HelperConfig): Boolean
    suspend fun spend(): HelperSpend
    /** Car mode is on. */
    val driving: Boolean
    /** Returns once car mode is off. */
    suspend fun untilParked()
    suspend fun ask(h: HelperConfig, r: HelpRequest): HelpReply
}

/**
 * Skills 1.3, a plan or a draft by a helper (design §9 "Planning with a helper", §10.1; S3.9): which helper Robot offers,
 * and the helper's step through core SkillHelp.send (the card with exactly what would go, its cost against today's budget,
 * Send only by a tap; in car mode it waits until you've parked).
 */
object PanelHelp {
    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)
    private fun buttons(l: Langs) = Langs(l.ui, l.ui, l.system, pseudo = l.pseudo)

    /** The helper to offer now ([SkillHelp.helper]), or null: none, or the phone is locked. */
    suspend fun offered(replyId: String, host: PanelSkills.Host): HelperConfig? {
        val hs = host.helpers ?: return null
        if (host.locked) return null
        val spend = try { hs.spend() } catch (t: Throwable) { RLog.w("help_spend", t); return null }
        return SkillHelp.helper(host.ports(replyId).actions.settings, hs::keySaved, spend, LocalDate.now())
    }

    /** [r] to [h] after its card; the helper's text, or null (the reply says why). */
    suspend fun send(replyId: String, host: PanelSkills.Host, h: HelperConfig, r: HelpRequest, l: Langs): String? {
        val hs = host.helpers ?: return null
        val ports = host.ports(replyId)
        val port = object : HelpPort {
            override val locked get() = host.locked
            override val driving get() = hs.driving
            override suspend fun untilParked() = hs.untilParked()
            override fun newId() = ports.newId()
            override suspend fun pick(card: Card, tapOnly: Phrase?) = host.pick(replyId, card, tapOnly?.said(l))
            override fun show(card: Card) = host.show(replyId, card)
            override fun reply(p: Phrase) { host.append(replyId, p.render(l)); host.say(p.said(l)) }
            override fun say(p: Phrase) = host.say(p.said(l))
            // A line in the reply ("Asking Claude…"), which stays: the reply's text holds the lines before it.
            override fun status(p: Phrase?) { if (p != null) host.append(replyId, p.render(l)) }
            override suspend fun spend() = hs.spend()
            override suspend fun ask(h: HelperConfig, r: HelpRequest) = hs.ask(h, r)
        }
        return SkillHelp.send(port, h, r, ports.actions.settings, l, buttons(l), Instant::now, LocalDate::now)
    }
}
