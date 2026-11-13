// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.HelperCost
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFormat
import app.heyrobot.core.helpers.HelperPrice
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.helpers.HelperTrust
import app.heyrobot.core.helpers.HelperUsage
import app.heyrobot.core.helpers.Pricing
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Pure. Skills 1.3, planning and drafting with a helper (design §9 "Planning with a helper", §10.1; S3.9): a plan or a
// draft too big for the phone's model can go to a helper you added, and only after its card showed exactly what would be
// sent, to whom, and what it costs against today's budget. Nothing goes before a tap on Send. What goes is one fixed
// instruction (the tools that are on, as Robot describes them to its own model; the date, time and language) and your
// words this turn: never the conversation, nothing Robot read, no other skill's steps. In car mode the card waits until
// you've parked (car mode ends: Robot never tells parked from moving, so car mode on counts as driving).

/** What the helper writes: a plan (make_plan's JSON) or a draft (make_skill's). */
enum class HelpJob { PLAN, DRAFT, SUMMARY }

/** Exactly what would go to a helper: its instruction ([system]) and one message, your [words]. No history, no tools, no search. */
data class HelpRequest(val job: HelpJob, val system: String, val words: String) {
    val chars: Int get() = system.length + words.length

    /** The card's body: the instruction, then your words, as they would be sent. */
    fun text(): String = system + "\n\n" + words
}

/** The helper's answer: its text (the JSON, read by SkillPlans or SkillDrafter), or why there was none. */
sealed class HelpReply {
    data class Text(val text: String) : HelpReply()
    data class Failed(val why: Phrase) : HelpReply()
}

/** The panel's side of a helper's step (PanelHelp gives it): the card, the lock, car mode, today's spend and the request. */
interface HelpPort {
    /** The phone is locked now (read live: it may lock while a card waits). */
    val locked: Boolean
    /** Car mode is on now. */
    val driving: Boolean
    /** Returns once car mode is off. */
    suspend fun untilParked()
    /** How long a card waits for you to park before it gives up ([SkillHelp.PARK_WAIT_MS]; a test's port sets less). */
    val parkWaitMs: Long get() = SkillHelp.PARK_WAIT_MS
    fun newId(): String
    /** Shows [card] and waits for its answer ([Confirmations.YES], a chip, or below 0 for Cancel); [tapOnly]: what a spoken yes gets. */
    suspend fun pick(card: Card, tapOnly: Phrase?): Int
    fun show(card: Card)
    /** One line into the reply, shown and said. */
    fun reply(p: Phrase)
    /** Said only (the card shows the rest). */
    fun say(p: Phrase)
    /** The reply's progress line ("Asking Claude…"); null clears it. */
    fun status(p: Phrase?)
    /** Today's spend on helpers. */
    suspend fun spend(): HelperSpend
    /** Sends [r] to [h]; only [SkillHelp.send] calls it, after the tap. */
    suspend fun ask(h: HelperConfig, r: HelpRequest): HelpReply
}

object SkillHelp {
    /** The helper's room for a plan or a draft: a few hundred tokens of JSON, with room for its thinking. */
    const val MAX_TOKENS = 4_000L

    /** A card waiting for you to park gives up after this (S3.11's review: never a request made hours earlier when you park). EST */
    const val PARK_WAIT_MS = 30 * 60_000L
    /** What the card's estimate counts for the answer. */
    const val OUTPUT_ESTIMATE = 1_000L

    /** The plan card offers "Let <helper> plan it" for a Careful or Risky plan (the phone's model is a small one). */
    fun offersPlan(tier: SkillTier): Boolean = tier != SkillTier.SIMPLE

    /** After the phone's model couldn't plan: a helper may do better, except where a plan's own rule refused it. */
    fun offersAfter(why: PlanRefusal): Boolean = why != PlanRefusal.APP_NOT_LAST && why != PlanRefusal.TOO_MANY

    /**
     * The helper Robot offers: your "When Robot needs help" one, else the first in your order, that Robot can ask itself (on,
     * its API, a key when it needs one) and whose budget (all helpers', or its own cap) isn't used up today. Null: none.
     */
    fun helper(s: RobotSettings, keySaved: (HelperConfig) -> Boolean, spend: HelperSpend, today: LocalDate): HelperConfig? {
        fun ok(h: HelperConfig) = HelperSettings.apiReady(h, keySaved(h)) && !spend.reached(today, s.helperBudgetCents, h.id, h.budgetCents)
        return HelperSettings.target(s, keySaved)?.takeIf(::ok) ?: s.helpers.firstOrNull(::ok)
    }

    private val CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") // l10n: not text (a format for the model)

    private fun tail(json: String, now: ZonedDateTime, lang: Lang): String =
        "\nYou have no tools here: write only the JSON, $json, and nothing else." + // l10n: not text (a prompt for a helper)
            "\nNow: ${now.format(CLOCK)}. The person speaks ${if (lang == Lang.FR) "French" else "English"}." // l10n: not text (a prompt for a helper)

    /** A plan: the PLAN one-shot's own instruction (the tools that are on, the cap), the date, time and language, and [words]. */
    fun plan(words: String, tools: List<ToolName>, cap: Int, now: ZonedDateTime, lang: Lang): HelpRequest =
        HelpRequest(HelpJob.PLAN, SkillPlans.system(tools, cap) + tail("{\"steps\": [...]}", now, lang), words) // l10n: not text (a prompt for a helper)

    /** A draft: the DRAFT one-shot's own instruction with the [ops] [opsFor] lets go, the date, time and language, and [words]. */
    fun draft(words: String, tools: List<ToolName>, ops: List<DraftOp>, now: ZonedDateTime, lang: Lang): HelpRequest =
        HelpRequest(HelpJob.DRAFT, SkillDrafter.system(tools, ops) + tail("{\"name\": ..., \"steps\": [...]}", now, lang), words) // l10n: not text (a prompt for a helper)

    /**
     * Your connections' operations, by their titles, are yours: they go to [h] only as HelperTrust lets what Robot reads for
     * a helper go (a helper you trust, or "Send personal content automatically"), and never for a draft made after content
     * Robot didn't write ([quarantined], design §10.3). Without them, the helper's draft can't use them.
     */
    fun opsFor(s: RobotSettings, h: HelperConfig, ops: List<DraftOp>, quarantined: Boolean): List<DraftOp> =
        if (!quarantined && HelperTrust.reads(s, h)) ops else emptyList()

    /** The estimate in dollars for [r] to [h], or null when its price isn't known. */
    fun estimate(h: HelperConfig, r: HelpRequest, at: Instant): Double? =
        Pricing.dollars(h.price, HelperUsage(input = HelperCost.tokens(HelperCost.charsOf(r.system) + HelperCost.charsOf(r.words)), output = OUTPUT_ESTIMATE) /* fix-batch-15 L5 */, at)

    /** "≈ 1¢ · today: $0.12 of $1 on helpers", in [lang]'s amounts; a home server's "No cost". */
    fun costLine(h: HelperConfig, r: HelpRequest, at: Instant, spend: HelperSpend, today: LocalDate, budgetCents: Int, lang: Lang): Phrase {
        val cost = Pricing.lineText(estimate(h, r, at), free = h.price is HelperPrice.Free) ?: SkillStrings.helpFree()
        val spent = HelperFormat.dollars(spend.on(today).total, lang)
        return if (budgetCents < 0) SkillStrings.helpCostNoBudget("cost" to cost)
        else SkillStrings.helpCost("cost" to cost, "spent" to spent, "budget" to HelperFormat.budget(budgetCents * 10_000L, lang))
    }

    /**
     * The helper's step: in car mode, a card that waits until you've parked (Cancel ends it); then the card with exactly
     * what would go ([r]'s text), to [h], and the cost line; only a tap on Send sends it (a spoken yes is told to tap).
     * The lock and the budget are checked again after the tap. Returns the helper's text, or null when nothing came back
     * (the reply already says why).
     */
    suspend fun send(port: HelpPort, h: HelperConfig, r: HelpRequest, s: RobotSettings, l: Langs, buttons: Langs, at: () -> Instant,
        today: () -> LocalDate): String? {
        val name = "name" to h.name
        if (port.driving && !parked(port, h, l, buttons)) return null
        if (port.locked) return null.also { port.reply(TurnStrings.unlockThenAsk()) }
        val spend = port.spend()
        if (spend.reached(today(), s.helperBudgetCents, h.id, h.budgetCents)) return null.also { port.reply(SkillStrings.helpBudget(name)) }
        val send = UiStrings.send().render(buttons)
        val tap = SkillStrings.helpTap("send" to send)
        val footer = Phrase.join(tap, costLine(h, r, at(), spend, today(), s.helperBudgetCents, l.say))
        val card = Card(port.newId(), null, SkillStrings.helpTitle(name).render(l), ActionStatus.PENDING, footer.render(l), body = r.text(),
            confirm = send, cancel = UiStrings.cancel().render(buttons))
        port.say(SkillStrings.helpSay(name, "send" to send))
        val pick = port.pick(card, tap)
        if (pick != Confirmations.YES) {
            port.show(card.copy(status = ActionStatus.DECLINED, detail = SkillStrings.helpNotSent(name).render(l)))
            return null.also { port.reply(SkillStrings.helpNotSent(name)) }
        }
        // The phone may have locked, or the budget gone, while the card waited.
        if (port.locked) {
            port.show(card.copy(status = ActionStatus.NEEDS_UNLOCK, detail = SkillStrings.helpNotSent(name).render(l)))
            return null.also { port.reply(TurnStrings.unlockThenAsk()) }
        }
        if (port.spend().reached(today(), s.helperBudgetCents, h.id, h.budgetCents)) {
            port.show(card.copy(status = ActionStatus.DECLINED, detail = SkillStrings.helpNotSent(name).render(l)))
            return null.also { port.reply(SkillStrings.helpBudget(name)) }
        }
        port.show(card.copy(status = ActionStatus.DONE, detail = null))
        port.status(SkillStrings.helpAsking(name))
        val reply = try { port.ask(h, r) } finally { port.status(null) }
        return when (reply) {
            is HelpReply.Text -> reply.text
            is HelpReply.Failed -> null.also { port.reply(reply.why) }
        }
    }

    /** Car mode is on: a card waits until it ends (true), or until you cancel it or [HelpPort.parkWaitMs] passes (false). */
    private suspend fun parked(port: HelpPort, h: HelperConfig, l: Langs, buttons: Langs): Boolean {
        val line = SkillStrings.helpPark("name" to h.name)
        val card = Card(port.newId(), null, SkillStrings.helpParkTitle().render(l), ActionStatus.PENDING, line.render(l),
            cancel = UiStrings.cancel().render(buttons))
        port.say(line)
        val parked = coroutineScope {
            val pick = async { port.pick(card, line) }
            val wait = async { withTimeoutOrNull(port.parkWaitMs) { port.untilParked() } != null }
            select {
                pick.onAwait { wait.cancel(); false }
                wait.onAwait { parked -> pick.cancel(); parked }
            }
        }
        port.show(card.copy(status = if (parked) ActionStatus.DONE else ActionStatus.DECLINED,
            detail = if (parked) null else SkillStrings.helpNotSent("name" to h.name).render(l)))
        if (!parked) port.reply(SkillStrings.helpNotSent("name" to h.name))
        return parked
    }
}
