// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ContactName
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.Priority
import app.heyrobot.core.RLog
import app.heyrobot.core.ToolName
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.SkillHelp
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.DraftOp
import app.heyrobot.core.skills.DraftRead
import app.heyrobot.core.skills.DraftRefusal
import app.heyrobot.core.skills.DraftSource
import app.heyrobot.core.skills.SkillDrafter
import app.heyrobot.core.skills.SkillPlans
import app.heyrobot.core.skills.SkillPolicy
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Skills 1.3, a skill drafted from what you describe (design §10, S3.8): "make a skill that …" (core SkillDrafter.route),
 * so the DRAFT one-shot writes it with its one tool, make_skill, from your words and the step kinds you have; core
 * SkillDrafter reads it (a step that fails §3.8 is left out and named; a person shown as found or missing), and it goes to
 * the save card (PanelSkills.save): Ask first, and only a tap saves it. After content, its banner says where from.
 */
object PanelDrafts {
    /** The DRAFT one-shot's time: a few hundred tokens of JSON. */
    private const val DRAFT_MS = 45_000L
    private const val DRAFT_CHUNKS = 200
    private const val HOUR_MS = 60 * 60_000L

    private val ctx get() = VoiceHub.app

    /** When the drafts of the last hour were asked (design §4.7, 10 an hour); this process's, counts only. */
    private val asked = ArrayDeque<Long>()

    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)

    private fun reply(replyId: String, host: PanelSkills.Host, p: Phrase, l: Langs) {
        host.text(replyId, p.render(l))
        host.say(p.said(l))
    }

    /** True when another draft may be asked now (and counts it). */
    internal fun allow(now: Long): Boolean = synchronized(asked) {
        while (asked.isNotEmpty() && now - asked.first() >= HOUR_MS) asked.removeFirst()
        if (asked.size >= SkillDrafter.PER_HOUR) false else { asked.addLast(now); true }
    }

    private fun ops(): List<DraftOp> = try { SkillDrafter.ops(ConnFiles.store(ctx).connections()) } catch (t: Throwable) { RLog.w("draft_ops", t); emptyList() }

    private fun contacts(): List<ContactName> = try { if (Contacts.granted(ctx)) Contacts.names(ctx) else emptyList() } catch (t: Throwable) { RLog.w("draft_contacts", t); emptyList() }

    /** "Make a skill that …": the draft made, read, then its save card. */
    suspend fun handle(replyId: String, ask: SkillDrafter.Ask, host: PanelSkills.Host, l: Langs, id: String) {
        if (host.locked) return reply(replyId, host, SkillPolicy.lockedLine(true, host.phoneLock), l)
        val now = System.currentTimeMillis()
        if (!allow(now)) return reply(replyId, host, SkillStrings.draftLimit("n" to SkillDrafter.PER_HOUR.toString()), l)
        val s = host.ports(replyId).actions.settings
        val on = SkillPlans.PLANNABLE.filter { s.approval(it) != Approval.OFF }.toSet()
        val ops = withContext(Dispatchers.IO) { ops() }
        host.text(replyId, SkillStrings.draftMaking().render(l))
        val req = BrainMsg.OneShot(OneShotKind.DRAFT, Priority.PANEL, SkillDrafter.system(on.sorted(), ops), ask.words, DRAFT_CHUNKS)
        val r = try { host.oneShots.run(req, OneShotBudget(startWithinMs = null, totalMs = DRAFT_MS)) } catch (t: Throwable) { RLog.w("draft_one_shot", t); null }
        // Content in the conversation (D5, design §10.3): the draft is quarantined, and its banner says where from.
        val source = host.source ?: if (host.untrusted) DraftSource.CONTENT else null
        val read = when (r) {
            is OneShotResult.Done -> withContext(Dispatchers.IO) {
                SkillDrafter.read(r.calls, r.text, ask, id, System.currentTimeMillis(), on, ops, contacts(), source, host.messages, PanelSkills.links())
            }
            else -> DraftRead.Refused(DraftRefusal.NO_DRAFT)
        }
        RLog.i("draft_read", "ok" to (read is DraftRead.Ok), "why" to ((read as? DraftRead.Refused)?.why?.id ?: 0))
        host.text(replyId, "")
        when (read) {
            is DraftRead.Refused -> {
                // S3.9: too much for the phone's model, so a helper you added may draft it, after its card.
                val h = PanelHelp.offered(replyId, host) ?: return reply(replyId, host, SkillStrings.draftFailed(), l)
                reply(replyId, host, SkillStrings.helpDraftOffer("name" to h.name), l)
                helperDraft(replyId, ask, host, l, id, h, on, ops, source)
            }
            is DraftRead.Ok -> {
                // S3.9: "Draft with <helper>" on the draft card.
                val alt = PanelHelp.offered(replyId, host)?.let { h ->
                    PanelSkills.Alt(SkillStrings.helpDraftChip("name" to h.name), SkillStrings.helpDraftInstead("name" to h.name)) {
                        helperDraft(replyId, ask, host, l, id, h, on, ops, source)
                    }
                }
                PanelSkills.saveDraft(replyId, read.draft, host, l, alt)
            }
        }
    }

    /**
     * S3.9: the draft by helper [h], after the card that shows exactly what would go (PanelHelp): your words and the step
     * kinds; your connections' operations only as core SkillHelp.opsFor lets them go (never after content). Its draft is
     * read as the phone's is, made by a helper, and only a tap saves it.
     */
    private suspend fun helperDraft(replyId: String, ask: SkillDrafter.Ask, host: PanelSkills.Host, l: Langs, id: String, h: HelperConfig,
        on: Set<ToolName>, ops: List<DraftOp>, source: DraftSource?) {
        val s = host.ports(replyId).actions.settings
        val sent = SkillHelp.opsFor(s, h, ops, quarantined = source != null || host.messages != MessageTaint.NONE)
        val text = PanelHelp.send(replyId, host, h, SkillHelp.draft(ask.words, on.sorted(), sent, ZonedDateTime.now(), ask.lang), l) ?: return
        val read = withContext(Dispatchers.IO) {
            SkillDrafter.read(emptyList(), text, ask, id, System.currentTimeMillis(), on, sent, contacts(), source, host.messages, PanelSkills.links(),
                made = SkillMade.DRAFT_HELPER)
        }
        RLog.i("draft_help_read", "ok" to (read is DraftRead.Ok), "why" to ((read as? DraftRead.Refused)?.why?.id ?: 0))
        when (read) {
            is DraftRead.Refused -> reply(replyId, host, SkillStrings.helpDraftFailed("name" to h.name), l)
            is DraftRead.Ok -> PanelSkills.saveDraft(replyId, read.draft, host, l)
        }
    }
}
