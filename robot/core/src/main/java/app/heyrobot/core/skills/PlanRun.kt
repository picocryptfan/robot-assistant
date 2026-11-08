// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.messages.MessageTaint

// Pure (coroutines). Skills 1.3, running a plan (design §9, "Running it"). Each step goes through SkillRun, so through
// the same path as a typed command (the quarantine, the gate with its level, the lock, the tool's prepare, its card).
// 1. Reads first, at once: the reads before any write run before the card, and what they found is said ([reads]).
// 2. Writes wait for the plan card. Do the rest ([rest]) with Approve a plan's actions at All at once answers the cards
//    that show only for the level (Ask first): never one with a reason of its own (not sure who, going online the first
//    time), one only a tap answers (a message's quarantine), one with a list to choose from, a step your words didn't
//    ask for, or one that moves money, touches security or messages someone (question 6). Step by step, or the setting's Step by step, shows every write's own card.
// 3. Stop on failure: the first step that fails, is declined, needs an unlock or fails its check ends the plan, and the
//    line says which steps didn't run ([line]). Robot's line comes from the real statuses, never from a model.

/**
 * A plan's run. [asked]: the actions your words asked for (PlanRouter.asked). [say]: shown and spoken (what a read
 * found, a say step's words). [approve]: Settings › Skills › Approve a plan's actions.
 */
class PlanRun(
    private val plan: SkillPlan,
    private val asked: Set<ToolName>,
    private val actions: TurnActions,
    private val view: TurnView,
    private val confirmations: Confirmations,
    private val messages: MessageTaint,
    private val langs: Langs,
    private val newId: () -> String,
    private val say: (String) -> Unit,
    private val approve: PlanApprove,
    private val links: SkillLinks = SkillLinks.NONE,
) {
    private val skill = plan.skill

    /** Every step's tool was among your words: the gate counts each as asked (one that wasn't keeps its own card). */
    private val named: Boolean = plan.steps.all { it.kind.tool in asked }

    /** The results so far, by step (from 0). */
    private val results = LinkedHashMap<Int, StepResult>()

    /** The conversation's taint after the steps so far. */
    private var taint = false

    /** The reads before any write, at once: null when the plan starts with a write. What each found is said. */
    suspend fun reads(untrusted: Boolean): SkillOutcome? {
        val k = plan.readsFirst
        if (k == 0) return null
        val part = skill.copy(steps = skill.steps.take(k))
        return SkillRun(part, emptyMap(), named, reading(actions), view, confirmations, untrusted, messages, langs, newId, canWait = false, say = say,
            links = links).run(0).also(::keep)
    }

    /** The rest, after the card's Do the rest ([stepByStep] false) or Step by step. Stops at the first step that didn't happen. */
    suspend fun rest(stepByStep: Boolean, untrusted: Boolean): SkillOutcome {
        val k = plan.readsFirst
        val auto = !stepByStep && approve == PlanApprove.ALL_AT_ONCE
        val why = HashMap<Action, Set<AskWhy>>()
        val a = object : TurnActions by actions {
            override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
                actions.prepare(action, untrusted, remote).also { p -> if (p is Prepared.Ready) why[p.action] = p.reasons }
            override suspend fun run(action: Action): Executed = reading(actions).run(action)
        }
        val v = object : TurnView by view {
            override fun card(c: Card) {
                // Answered by Do the rest: not shown waiting; its result card follows as any card's does.
                if (auto && answerable(c, why[c.action])) return confirmations.answerFirst(c.id)
                view.card(c)
            }
        }
        val done = results.values.count { it.happened }
        return SkillRun(skill, emptyMap(), named, a, v, confirmations, untrusted || taint, messages, langs, newId, canWait = false, say = say,
            done = done, links = links).run(k).also(::keep)
    }

    /** A card Do the rest may answer: waiting, a yes may answer it, nothing to pick, no reason but the level, and asked for. */
    private fun answerable(c: Card, reasons: Set<AskWhy>?): Boolean = c.status == ActionStatus.PENDING && c.tapFor == null && c.choices.isEmpty() &&
        !c.allowContacts && c.remember == null && reasons != null && reasons.isEmpty() && c.tool != null && c.tool in asked &&
        plan.steps.any { it.kind.tool == c.tool } && !ownCard(c)

    /**
     * Your answer of 4 Nov 2026 (plan §6, question 6): money, security and messages still get their own card, even one with
     * no reason but the level (a text to an exact contact). Any step of the card's tool that does one of them keeps it.
     */
    private fun ownCard(c: Card): Boolean = plan.steps.any { it.kind.tool == c.tool && SkillEffects.step(skill, it, links).any(OWN_CARD::contains) }

    private companion object {
        val OWN_CARD = setOf(SkillEffect.MONEY, SkillEffect.SECURITY, SkillEffect.MESSAGES_SOMEONE)
    }

    /** A read's findings are said as it ends (design §9: "their results show on the card"). */
    private fun reading(ports: TurnActions): TurnActions = object : TurnActions by ports {
        override suspend fun run(action: Action): Executed = ports.run(action).also { e ->
            if (action.tool.reads && e.status.succeeded) (e.spoken ?: e.detail).takeIf { it.isNotBlank() }?.let(say)
        }
    }

    private fun keep(o: SkillOutcome) {
        for (r in o.results) results[r.index] = r
        taint = taint || o.untrusted
    }

    /** It stopped: a step didn't happen (and didn't go on). */
    val stopped: Boolean get() = results.values.any { !it.happened && !it.wentOn }

    /** Every step happened. */
    val finished: Boolean get() = plan.steps.indices.all { results[it]?.happened == true }

    /** The card's lines: each step with what it does or did, then the tier and who planned it ([model]). */
    fun lines(model: Phrase): List<Phrase> = plan.steps.mapIndexed { i, st ->
        val args = arrayOf("n" to (i + 1).toString(), "step" to SkillCards.stepText(skill, st, links = links))
        val r = results[i]
        when {
            r?.happened == true -> SkillStrings.planStepDone(*args)
            r != null || stopped -> SkillStrings.planStepNotRun(*args)
            i < plan.readsFirst -> SkillStrings.planStepNow(*args)
            st.kind.tool !in asked -> SkillStrings.planStepUnasked(*args)
            else -> SkillStrings.planStepWaits(*args)
        }
    } + when (plan.tier) {
        SkillTier.SIMPLE -> SkillStrings.planSimple("model" to model)
        SkillTier.CAREFUL -> SkillStrings.planCareful("model" to model)
        SkillTier.RISKY -> SkillStrings.planRisky("model" to model)
    }

    /** Robot's line once the plan ended: done, or where it stopped and which steps didn't run. */
    fun line(): Phrase {
        val total = plan.steps.size
        val stop = results.values.sortedBy { it.index }.firstOrNull { !it.happened && !it.wentOn }
            ?: return SkillStrings.planDone("done" to results.values.count { it.happened }.toString(), "total" to total.toString())
        val why = stop.why?.trim()?.trimEnd('.', '!', '?')?.takeIf { it.isNotEmpty() } ?: stop.status.text.render(langs)
        val left = (stop.index + 1 until total).map { SkillCards.stepText(skill, plan.steps[it], links = links) }
        val n = (stop.index + 1).toString()
        return if (left.isEmpty()) SkillStrings.planStoppedLast("n" to n, "why" to why)
            else SkillStrings.planStopped("n" to n, "why" to why, "left" to Phrase.ListOf(left))
    }
}
