// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.NoteDictation
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.MultiAsk
import app.heyrobot.core.skills.SkillPolicy

/**
 * Idea 6 (docs/plans/multi-request-plan.md): several requests in one sentence, each one Robot does without a model (core
 * MultiAsk). One card, one line per request in your words, one yes (or Do all); a line's Remove chip takes it off and the
 * card shows again; Cancel or a no ends it with nothing done. Then each request runs as a typed command would (its gate,
 * the lock, its tool's checks): a card the yes already answered isn't shown again, one with a reason of its own (a
 * reminder's calendar card, a tap the gate wants, a step set to Ask first in Settings › Actions) still shows, and a no there drops only that request. Robot's line
 * counts what really happened.
 */
object PanelMulti {
    private fun Phrase.shown(l: Langs) = render(l)
    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)
    private fun Phrase.button(l: Langs) = render(Langs(l.ui, l.ui, l.system, pseudo = l.pseudo))

    private fun reply(replyId: String, host: PanelSkills.Host, p: Phrase, l: Langs) {
        host.append(replyId, p.shown(l))
        host.say(p.said(l))
    }

    suspend fun handle(replyId: String, parts: List<MultiAsk.Part>, host: PanelSkills.Host, l: Langs) {
        if (host.locked) return reply(replyId, host, SkillPolicy.lockedLine(false, host.phoneLock), l)
        val ports = host.ports(replyId)
        val id = ports.newId()
        val go = SkillStrings.multiDoAll()
        // Tap only with content Robot didn't write, or other people's messages, in this conversation (as a plan's card).
        val tapOnly = host.untrusted || ports.messages != MessageTaint.NONE
        val footer = if (tapOnly) SkillStrings.planTap("go" to go.button(l)) else SkillStrings.multiSayYes("go" to go.button(l))
        var left = parts
        var spoken = false
        while (true) {
            val card = Card(id, null, SkillStrings.multiTitle("n" to left.size.toString()).shown(l), ActionStatus.PENDING, footer.shown(l),
                body = left.mapIndexed { i, p -> SkillStrings.multiLine("n" to (i + 1).toString(), "step" to Phrase.plain(p.said)).shown(l) }.joinToString("\n"),
                confirm = go.button(l), cancel = UiStrings.cancel().button(l),
                chips = if (left.size > 1) left.indices.map { SkillStrings.multiRemove("n" to (it + 1).toString()).button(l) } else emptyList())
            if (!spoken) { host.say(footer.said(l)); spoken = true }
            val pick = host.pick(replyId, card, if (tapOnly) footer.said(l) else null)
            val chip = pick - Card.CHIP
            if (pick >= Card.CHIP && chip in left.indices && left.size > 1) {
                left = left.filterIndexed { i, _ -> i != chip }
                continue
            }
            if (pick != Confirmations.YES) {
                host.show(replyId, card.copy(status = ActionStatus.DECLINED, detail = null))
                return reply(replyId, host, SkillStrings.multiCancelled(), l)
            }
            host.show(replyId, card.copy(status = ActionStatus.DONE, detail = null))
            break
        }
        RLog.i("multi_run", "n" to left.size, "of" to parts.size)
        val done = left.count { run(it, ports, host.untrusted) }
        reply(replyId, host, SkillStrings.multiDone("done" to done.toString(), "total" to left.size.toString()), l)
    }

    /** One request, as a typed command: the card's yes answers its card only when nothing but the level asks for it. */
    private suspend fun run(p: MultiAsk.Part, ports: PanelSkills.SkillPorts, untrusted: Boolean): Boolean {
        var ran: Executed? = null
        val why = HashMap<Action, Set<AskWhy>>()
        val a = object : TurnActions by ports.actions {
            override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
                ports.actions.prepare(action, untrusted, remote).also { r -> if (r is Prepared.Ready) why[r.action] = r.reasons }
            override suspend fun run(action: Action): Executed = ports.actions.run(action).also { ran = it }
        }
        // Settings › Actions: a step whose level is Ask first shows its own card, as when it's asked alone (Off and the lock
        // are the gate's, in TurnRunner, before any card).
        val level = ports.actions.settings.approval(p.kind.tool)
        val v = object : TurnView by ports.view {
            override fun card(c: Card) {
                val reasons = why[c.action]
                if (c.status == ActionStatus.PENDING && c.tapFor == null && c.choices.isEmpty() && !c.allowContacts && c.remember == null &&
                    reasons != null && reasons.isEmpty() && c.tool == p.kind.tool && level != Approval.ASK) return ports.confirmations.answerFirst(c.id)
                ports.view.card(c)
            }
        }
        TurnRunner(NoteDictation.Direct(p.call), a, v, ports.confirmations, untrusted, asked = setOf(p.kind.tool), messages = ports.messages,
            langs = ports.langs, newId = ports.newId).run()
        return ran?.status?.succeeded == true
    }
}
