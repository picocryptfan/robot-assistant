// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.Priority
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.TurnRules
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.skills.SkillHelp
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.PlanApprove
import app.heyrobot.core.skills.PlanRead
import app.heyrobot.core.skills.PlanRefusal
import app.heyrobot.core.skills.PlanRouter
import app.heyrobot.core.skills.PlanRun
import app.heyrobot.core.skills.SkillPlan
import app.heyrobot.core.skills.SkillPlans
import app.heyrobot.core.skills.SkillPolicy
import app.heyrobot.core.skills.SkillTier
import app.heyrobot.sys.SkillFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Skills 1.3, plans on the phone (design §9, S3.7): your words asked for two or more actions (core PlanRouter), so the
 * PLAN one-shot writes them as a plan with its one tool, make_plan; core SkillPlans checks it strictly (one bad step
 * refuses it whole); its reads run at once and are said, and its writes wait on the plan card (Do the rest, Step by
 * step, Cancel). A step that fails stops the plan, and Robot says which steps didn't run.
 */
object PanelPlans {
    /** The PLAN one-shot's time: a few hundred tokens of JSON. */
    private const val PLAN_MS = 45_000L
    private const val PLAN_CHUNKS = 160

    private val ctx get() = VoiceHub.app

    private fun Phrase.shown(l: Langs) = render(l)
    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)
    private fun Phrase.button(l: Langs) = render(Langs(l.ui, l.ui, l.system, pseudo = l.pseudo))

    private fun reply(replyId: String, host: PanelSkills.Host, p: Phrase, l: Langs) {
        host.append(replyId, p.shown(l))
        host.say(p.said(l))
    }

    /** The tools a plan may use now: the plannable ones not turned Off. */
    private fun on(host: PanelSkills.Host, replyId: String): Set<ToolName> {
        val s = host.ports(replyId).actions.settings
        return SkillPlans.PLANNABLE.filter { s.approval(it) != Approval.OFF }.toSet()
    }

    private fun settings() = try { SkillFiles.store(ctx).settings() } catch (t: Throwable) { RLog.w("plan_settings", t); null }

    /** "Plan this: …", or your words with two actions joined: the plan made, checked, then run. */
    suspend fun handle(replyId: String, ask: PlanRouter.Ask, host: PanelSkills.Host, said: String, l: Langs) {
        if (host.locked) return reply(replyId, host, SkillPolicy.lockedLine(false, host.phoneLock), l)
        val cap = settings()?.planCap ?: SkillPlans.CAP
        val on = on(host, replyId)
        host.text(replyId, SkillStrings.planMaking().shown(l))
        val req = BrainMsg.OneShot(OneShotKind.PLAN, Priority.PANEL, SkillPlans.system(on.sorted(), cap), ask.words, PLAN_CHUNKS)
        val r = try { host.oneShots.run(req, OneShotBudget(startWithinMs = null, totalMs = PLAN_MS)) } catch (t: Throwable) { RLog.w("plan_one_shot", t); null }
        val read = when (r) {
            is OneShotResult.Done -> withContext(Dispatchers.IO) {
                SkillPlans.read(r.calls, r.text, on, cap, ask.lang ?: l.ui, System.currentTimeMillis(), host.untrusted, PanelSkills.links())
            }
            else -> PlanRead.Refused(PlanRefusal.NO_PLAN)
        }
        RLog.i("plan_read", "ok" to (read is PlanRead.Ok), "why" to ((read as? PlanRead.Refused)?.why?.id ?: 0))
        host.text(replyId, "")
        when (read) {
            is PlanRead.Refused -> {
                // S3.9: too much for the phone's model, so a helper you added may plan it, after its card.
                val h = if (SkillHelp.offersAfter(read.why)) PanelHelp.offered(replyId, host) else null
                if (h == null) return reply(replyId, host, if (read.why == PlanRefusal.APP_NOT_LAST) SkillStrings.planAppLast() else SkillStrings.planFailed(), l)
                reply(replyId, host, SkillStrings.helpPlanOffer("name" to h.name), l)
                helperPlan(replyId, ask.words, ask.lang ?: l.ui, ToolRouter.asked(said), h, host, l)
            }
            is PlanRead.Ok -> run(replyId, read.plan, ToolRouter.asked(said), host, l, ask.words, ask.lang ?: l.ui)
        }
    }

    /**
     * The model's turn asked for more calls than a turn runs ([TurnResult.skipped], TurnRules.MAX_CALLS): "Make that a
     * plan?" Make a plan shows the calls that didn't run as a plan (checked as one, no model), with its own card.
     */
    suspend fun offer(replyId: String, skipped: List<RawToolCall>, said: String, host: PanelSkills.Host, lang: Lang?) {
        if (skipped.isEmpty() || host.locked) return
        val l = host.langs(said, lang)
        val cap = settings()?.planCap ?: SkillPlans.CAP
        val read = withContext(Dispatchers.IO) {
            SkillPlans.check(skipped, on(host, replyId), cap, lang ?: l.ui, System.currentTimeMillis(), host.untrusted, PanelSkills.links())
        }
        val plan = (read as? PlanRead.Ok)?.plan ?: return
        val ports = host.ports(replyId)
        val title = SkillStrings.planOffer()
        val body = SkillStrings.planOfferBody("n" to TurnRules.MAX_CALLS.toString())
        reply(replyId, host, title, l)
        val pick = host.pick(replyId, Card(ports.newId(), null, title.shown(l), ActionStatus.PENDING, body.shown(l),
            confirm = SkillStrings.planMake().button(l), cancel = UiStrings.cancel().button(l)))
        if (pick == Confirmations.YES) run(replyId, plan, ToolRouter.asked(said), host, l, said, lang ?: l.ui)
    }

    /**
     * S3.9: "Let <helper> plan it": [words] to helper [h] after the card that shows exactly what would go (PanelHelp); its
     * plan is checked as the phone's is, and shown on the plan card, tap only.
     */
    private suspend fun helperPlan(replyId: String, words: String, lang: Lang, asked: Set<ToolName>, h: HelperConfig, host: PanelSkills.Host, l: Langs) {
        val cap = settings()?.planCap ?: SkillPlans.CAP
        val on = on(host, replyId)
        val text = PanelHelp.send(replyId, host, h, SkillHelp.plan(words, on.sorted(), cap, ZonedDateTime.now(), lang), l) ?: return
        val read = withContext(Dispatchers.IO) {
            SkillPlans.read(emptyList(), text, on, cap, lang, System.currentTimeMillis(), host.untrusted, PanelSkills.links())
        }
        RLog.i("plan_help_read", "ok" to (read is PlanRead.Ok), "why" to ((read as? PlanRead.Refused)?.why?.id ?: 0))
        when (read) {
            is PlanRead.Refused -> reply(replyId, host, SkillStrings.helpPlanFailed("name" to h.name), l)
            is PlanRead.Ok -> run(replyId, read.plan, asked, host, l, words, lang, by = h)
        }
    }

    /**
     * The plan's reads at once and said, then its card; Do the rest (or Step by step) runs what's left. [by]: the helper
     * that wrote it (null: the phone's model, whose Careful or Risky plan offers "Let <helper> plan it" with [words]).
     */
    private suspend fun run(replyId: String, plan: SkillPlan, asked: Set<ToolName>, host: PanelSkills.Host, l: Langs, words: String, lang: Lang,
        by: HelperConfig? = null) {
        val ports = host.ports(replyId)
        val approve = settings()?.planApprove ?: PlanApprove.ALL_AT_ONCE
        val untrusted = host.untrusted || plan.skill.provenance.fromContent
        val run = PlanRun(plan, asked, ports.actions, ports.view, ports.confirmations, ports.messages, ports.langs, ports.newId,
            say = { t -> host.append(replyId, t); host.say(t, personal = true) }, approve = approve, links = PanelSkills.links())
        run.reads(untrusted)
        if (run.stopped || run.finished) return reply(replyId, host, run.line(), l)
        val id = ports.newId()
        val title = SkillStrings.planTitle("n" to plan.steps.size.toString()).shown(l)
        val body = run.lines(by?.let { Phrase.plain(it.name) } ?: SkillStrings.planModel()).joinToString("\n") { it.shown(l) }
        val helper = if (by == null && SkillHelp.offersPlan(plan.tier)) PanelHelp.offered(replyId, host) else null
        // Tap only: a Risky plan, or one made from content Robot didn't write (D5) or with other people's messages here.
        // S3.9: and one a helper wrote.
        val tapOnly = by != null || plan.tier == SkillTier.RISKY || untrusted || ports.messages != MessageTaint.NONE
        val go = SkillStrings.planDoRest()
        val footer = (if (tapOnly) SkillStrings.planTap("go" to go.button(l)) else SkillStrings.planSayYes("go" to go.button(l)))
        host.say(footer.said(l))
        val card = Card(id, null, title, ActionStatus.PENDING, footer.shown(l), body = body, confirm = go.button(l),
            cancel = UiStrings.cancel().button(l), chips = listOfNotNull(SkillStrings.planStepByStep().button(l),
                helper?.let { SkillStrings.helpPlanChip("name" to it.name).button(l) }))
        val pick = host.pick(replyId, card, if (tapOnly) footer.said(l) else null)
        if (helper != null && pick == Card.CHIP + 1) {
            host.show(replyId, card.copy(status = ActionStatus.DECLINED, detail = SkillStrings.helpPlanInstead("name" to helper.name).shown(l)))
            return helperPlan(replyId, words, lang, asked, helper, host, l)
        }
        if (pick != Confirmations.YES && pick != Card.CHIP) {
            host.show(replyId, card.copy(status = ActionStatus.DECLINED, detail = null))
            return reply(replyId, host, SkillStrings.planCancelled(), l)
        }
        host.show(replyId, card.copy(status = ActionStatus.DONE, detail = null))
        run.rest(stepByStep = pick == Card.CHIP, untrusted = untrusted)
        reply(replyId, host, run.line(), l)
    }
}
