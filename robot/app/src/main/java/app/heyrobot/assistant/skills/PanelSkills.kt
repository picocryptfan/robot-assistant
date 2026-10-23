// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.skills

import android.os.Bundle
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.assistant.routines.RoutineRunner
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.Confirmations
import app.heyrobot.core.RLog
import app.heyrobot.core.TextCommand
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.NameContext
import app.heyrobot.core.skills.RecentAction
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCards
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillCommand
import app.heyrobot.core.skills.SkillDraft
import app.heyrobot.core.skills.SkillEditCode
import app.heyrobot.core.skills.SkillFromActions
import app.heyrobot.core.skills.SkillGo
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillNames
import app.heyrobot.core.skills.SkillPolicy
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.skills.SkillRunCode
import app.heyrobot.core.skills.SkillTeach
import app.heyrobot.core.skills.FillModel
import app.heyrobot.core.skills.FillSource
import app.heyrobot.core.skills.ModelGo
import app.heyrobot.core.skills.SkillCall
import app.heyrobot.core.skills.SkillFill
import app.heyrobot.core.skills.SkillFills
import app.heyrobot.core.skills.SkillMenu
import app.heyrobot.core.skills.SkillPickCode
import app.heyrobot.core.skills.SkillRan
import app.heyrobot.core.skills.SkillRisk
import app.heyrobot.core.skills.SkillSelect
import app.heyrobot.core.skills.SkillShelf
import app.heyrobot.core.skills.SkillSpecs
import app.heyrobot.core.skills.SkillStats
import app.heyrobot.core.skills.SkillTier
import app.heyrobot.core.skills.SkillTool
import app.heyrobot.core.skills.SkillWarnCode
import app.heyrobot.core.BrainModel
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.SkillContext
import app.heyrobot.sys.SkillFiles
import app.heyrobot.sys.SkillIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.ZonedDateTime

// VOICE PROCESS (1.1, Skills phase A: docs/plans/skills-1.1-plan.md §5 to §7). Skills in the panel, decided by code
// before the model (core SkillRouter, English and Québec French): teaching one ("teach a skill called heading home: text
// Sam I'm on my way, then open the route home"), "save that as a skill", running one by its name, asking what one does,
// listing them, deleting one (always a tap). Each step runs through the conversation's own ports (SkillRunner), with its
// gate and its card, as a typed command would. PanelController keeps the turn and its bubble; this decides what goes in
// them, through [Host]. Names, values and steps are never logged.

object PanelSkills {
    /** What a skill turn may do in the panel: a routine turn's (bubble, voice, cards), and the conversation's ports. */
    interface Host : PanelRoutines.Host {
        /** The ports a step goes through: the panel's actions and view, its cards' answers, this conversation's messages. */
        fun ports(replyId: String): SkillPorts
        /** The conversation holds content Robot didn't write (TurnRules.untrusted, D5). */
        val untrusted: Boolean
        /** A phone lock is on (its own line instead of "Unlock first"). */
        val phoneLock: Boolean
        /** The messages other people wrote that are in this conversation (MessageQuarantine). */
        val messages: MessageTaint
    }

    /** The ports of [Host.ports] (core SkillRun's). */
    class SkillPorts(val actions: TurnActions, val view: TurnView, val confirmations: Confirmations, val messages: MessageTaint, val langs: Langs,
        val newId: () -> String)

    /** What [route] found: a command, the steps after a pause (D8), or a new name for a card waiting. */
    sealed class Routed {
        abstract val lang: Lang?
        data class Command(val cmd: SkillCommand) : Routed() { override val lang get() = cmd.lang }
        data class Steps(val name: String, val steps: String, override val lang: Lang) : Routed()
        data class Rename(val draft: SkillDraft, val name: String) : Routed() { override val lang get() = draft.lang }
        /** Phase B: your words after "Change: {ask}" on a model fill's card: the new value. */
        data class Value(val edit: Edit, val words: String) : Routed() { override val lang get() = edit.lang }
    }

    /**
     * Phase B: a model fill's card waiting for a value you change ([param]): the skill as it was ([skillId], [rev]), the
     * values, the questions the model's call gave ([given]), the model ([model], null: none, the card came from your
     * words), whether your words named it ([named], D18).
     */
    class Edit(val skillId: String, val rev: Int, val values: Map<String, String>, val given: Set<String>, val param: String, val model: FillModel?,
        val named: Boolean, val lang: Lang)

    private val app get() = VoiceHub.app
    private fun store() = SkillFiles.store(app)

    /** Your skills as last read (the router reads them at every question); null: read again. */
    @Volatile private var cache: List<Skill>? = null

    private fun skills(): List<Skill> = cache ?: store().skills().also { cache = it }

    /** Cmd.SKILLS (Settings › Skills, a restore) or a change made here: skills.json is read again. */
    fun reload() {
        cache = null
        index = null
    }
    private val random = SecureRandom()

    // ------------------------------------------------------------------ what waits for your next words (D8)

    private enum class Waits { STEPS, NAME, VALUE }
    private class Pending(val waits: Waits, val name: String, val lang: Lang, val draft: SkillDraft?, val at: Long, val edit: Edit? = null)

    /** "Teach a skill called heading home" and a pause, or "Change the name": your next words, within 2 minutes. */
    @Volatile private var pending: Pending? = null
    private const val PENDING_MS = 2 * 60_000L

    /** Robot asked for the steps or a new name and waits for your next words (the panel listens again when you spoke). */
    val waitsForWords: Boolean get() = pending?.let { System.currentTimeMillis() - it.at < PENDING_MS } == true

    /** Stop, a new conversation, the lock: what waited for your words is dropped. */
    fun drop() { pending = null }

    // ------------------------------------------------------------------ this conversation's actions (D12)

    private val recent = LinkedHashMap<String, RecentAction>()

    /** A card of this conversation as it is now (PanelView.card): an action that ended is kept for "save that". */
    fun seen(c: Card) {
        val a = c.action ?: return
        if (c.status == ActionStatus.PENDING) return
        synchronized(recent) { recent[c.id] = RecentAction(a, c.status, helper = c.remote) }
    }

    fun newConversation() {
        synchronized(recent) { recent.clear() }
        pending = null
        shelf.clear()
    }

    private fun recent(): List<RecentAction> = synchronized(recent) { recent.values.toList() }

    // ------------------------------------------------------------------ routing

    /** The skill phrase [q] is, or null (the rest of the panel answers). */
    fun route(q: String): Routed? = try {
        val p = pending
        pending = null
        if (p != null && System.currentTimeMillis() - p.at < PENDING_MS) {
            // Your words without "hey robot", "please" and the like in front, as the router reads them.
            val w = SkillRouter.withoutLead(q)
            when (p.waits) {
                Waits.STEPS -> if (SkillTeach.anyStep(w)) return Routed.Steps(p.name, w, p.lang)
                // A name: 1 to 40 characters that you could say, 1 to 5 words; anything else goes on as usual.
                Waits.NAME -> if (CardAnswer.of(q) == null && SkillCheck.validName(SkillDraft.displayName(w)) && SkillDraft.otherName(w) != null)
                    return Routed.Rename(p.draft!!, w)
                // Phase B: the value you change on a model fill's card: any words but a yes or a no.
                Waits.VALUE -> if (CardAnswer.of(q) == null && w.isNotBlank()) return Routed.Value(p.edit!!, w)
            }
        }
        SkillRouter.route(q, skills(), people(), emptyList())?.let { Routed.Command(it) }
    } catch (t: Throwable) {
        RLog.w("skill_route", t)
        null
    }

    /** The language [r], routed from [q], was surely said in (core ReplyLang's router match), or null. */
    fun lang(q: String, r: Routed): Lang? = when (r) {
        is Routed.Command -> SkillRouter.lang(q, r.cmd)
        else -> r.lang
    }

    /** People's names, aliases and nicknames, and the kin words, for a person's value after a skill's name. */
    private fun people(): List<String> = try {
        PeopleFiles.store(app).list().flatMap { listOf(it.name) + it.aliases + it.nicknames } + TextCommand.KIN
    } catch (_: Throwable) { TextCommand.KIN.toList() }

    // ------------------------------------------------------------------ handling

    private fun Phrase.shown(l: Langs) = render(l)
    private fun Phrase.said(l: Langs) = RoutineRunner.said(this, l)
    private fun Phrase.button(l: Langs) = render(Langs(l.ui, l.ui, l.system, pseudo = l.pseudo))

    private fun reply(replyId: String, host: Host, p: Phrase, l: Langs) {
        host.text(replyId, p.shown(l))
        host.say(p.said(l))
    }

    /** [r] as routed from [said], your words this turn. */
    suspend fun handle(replyId: String, r: Routed, host: Host, said: String) {
        val l = host.langs(said, lang(said, r))
        try {
            when (r) {
                is Routed.Steps -> if (lockedOut(replyId, host, l, change = true)) Unit
                    else save(replyId, SkillTeach.teach(r.name, r.steps, r.lang, newSkillId(), System.currentTimeMillis(), host.untrusted, paused = true), host, l)
                is Routed.Rename -> if (lockedOut(replyId, host, l, change = true)) Unit else save(replyId, r.draft.renamed(r.name), host, l)
                is Routed.Value -> edited(replyId, r, host, l)
                is Routed.Command -> command(replyId, r.cmd, host, l)
            }
        } finally {
            host.finish(replyId)
        }
    }

    /** True when the phone is locked: what Robot says instead was said. */
    private fun lockedOut(replyId: String, host: Host, l: Langs, change: Boolean): Boolean {
        if (!host.locked) return false
        reply(replyId, host, SkillPolicy.lockedLine(change, host.phoneLock), l)
        return true
    }

    private suspend fun command(replyId: String, c: SkillCommand, host: Host, l: Langs) {
        when (c) {
            is SkillCommand.Teach -> {
                if (lockedOut(replyId, host, l, change = true)) return
                val steps = c.steps
                if (steps == null) {
                    // D8: Robot asks for the steps; your next words, within 2 minutes, are read as them if one parses.
                    pending = Pending(Waits.STEPS, c.name, c.lang, null, System.currentTimeMillis())
                    reply(replyId, host, SkillStrings.whichSteps("name" to SkillDraft.displayName(c.name)), l)
                    return
                }
                save(replyId, SkillTeach.teach(c.name, steps, c.lang, newSkillId(), System.currentTimeMillis(), host.untrusted), host, l)
            }
            is SkillCommand.SaveThat -> {
                if (lockedOut(replyId, host, l, change = true)) return
                val taken = store().skills().flatMap { it.allNames }.toSet()
                val d = SkillFromActions.draft(recent(), c.name, c.lang, newSkillId(), System.currentTimeMillis(), host.untrusted, taken)
                    ?: return reply(replyId, host, SkillStrings.nothingToSave(), l)
                if (save(replyId, d, host, l)) synchronized(recent) { recent.clear() }
            }
            is SkillCommand.Run -> run(replyId, c.skill, c.values, host, l)
            is SkillCommand.About -> {
                if (lockedOut(replyId, host, l, change = false)) return
                reply(replyId, host, c.skill?.let { SkillCards.about(it) } ?: SkillStrings.noSuch("name" to c.said), l)
            }
            is SkillCommand.ListAll -> {
                if (lockedOut(replyId, host, l, change = false)) return
                reply(replyId, host, SkillCards.list(store().skills()), l)
            }
            is SkillCommand.Delete -> {
                if (lockedOut(replyId, host, l, change = true)) return
                delete(replyId, c.skill, c.said, host, l)
            }
        }
    }

    /** A new skill's id: "s-" and 6 lowercase letters or digits, not one of yours. */
    private fun newSkillId(): String {
        val have = store().skills().map { it.id }.toSet()
        val abc = "abcdefghijklmnopqrstuvwxyz0123456789"
        while (true) {
            val id = "s-" + (1..6).map { abc[random.nextInt(abc.length)] }.joinToString("")
            if (id !in have) return id
        }
    }

    /** What the name is checked against on this phone now (D15). */
    fun nameContext(): NameContext = SkillContext.of(app, store().skills())

    // ------------------------------------------------------------------ the save card

    /**
     * The save card of [draft0] until Save or Cancel: a tap on a value's chip makes it asked each time (or fixed again), the
     * level's chip offers Just do it, "Change the name" waits for your next words. A spoken yes saves, except a skill made
     * from content (D13). True when it was saved.
     */
    private suspend fun save(replyId: String, draft0: SkillDraft, host: Host, l: Langs): Boolean {
        var draft = draft0
        val id = "skill-card-" + random.nextLong().toString(16)
        val ctx = nameContext()
        while (true) {
            val skill = draft.skill()
            val nameProblem = SkillNames.check(skill.name, null, ctx, ZonedDateTime.now()) ?: skill.namesEn.plus(skill.namesFr)
                .firstNotNullOfOrNull { SkillNames.check(it, null, ctx, ZonedDateTime.now(), other = true) }
            val c = SkillCards.save(draft, nameProblem)
            val body = (c.body + c.lines).joinToString("\n") { it.shown(l) }
            if (draft.problems.isNotEmpty()) {
                host.text(replyId, SkillStrings.couldntSave().shown(l))
                host.say(SkillStrings.couldntSave().said(l))
                host.show(replyId, Card(id, null, c.title.shown(l), ActionStatus.INVALID, draft.problems.first().shown(l), body = body))
                VoiceLog.add(VoiceEvent.SKILL_EDIT, SkillEditCode.NOT_SAVED.id, skill.steps.size, skill.params.size)
                return false
            }
            val footer = nameProblem ?: c.footer
            host.text(replyId, (footer ?: c.title).shown(l))
            if (draft === draft0) host.say((footer ?: c.title).said(l))
            val confirm = if (nameProblem != null) SkillStrings.rename().button(l) else UiStrings.save().button(l)
            val tapOnly = if (draft.fromContent) SkillStrings.tapToSave("save" to UiLabel(UiStrings.save)).said(l) else null
            val pick = host.pick(replyId, Card(id, null, c.title.shown(l), ActionStatus.PENDING, footer?.shown(l), body = body, confirm = confirm,
                cancel = UiStrings.cancel().button(l), chips = c.chips.map { it.button(l) }), tapOnly)
            when {
                pick < 0 -> {
                    host.show(replyId, Card(id, null, c.title.shown(l), ActionStatus.DECLINED, SkillStrings.notSaved().shown(l), body = body))
                    VoiceLog.add(VoiceEvent.SKILL_EDIT, SkillEditCode.NOT_SAVED.id, skill.steps.size, skill.params.size)
                    return false
                }
                pick == Confirmations.YES && nameProblem == null -> break
                pick == Confirmations.YES -> return rename(replyId, id, draft, c.title, host, l)
                pick >= Card.CHIP -> {
                    val i = pick - Card.CHIP
                    draft = when {
                        i < draft.slots.size -> draft.toggle(draft.slots[i])
                        c.levelChip && i == draft.slots.size -> draft.copy(level = if (draft.level == SkillLevel.JUST_DO_IT) SkillLevel.ASK_FIRST else SkillLevel.JUST_DO_IT)
                        else -> return rename(replyId, id, draft, c.title, host, l)
                    }
                }
            }
        }
        // The phone may have locked while the card waited.
        val skill = draft.skill()
        val title = SkillStrings.cardNew("name" to skill.name).shown(l)
        if (host.locked) {
            host.show(replyId, Card(id, null, title, ActionStatus.NEEDS_UNLOCK, SkillPolicy.lockedLine(true, host.phoneLock).shown(l)))
            return false
        }
        if (store().skills().size >= SkillCheck.MAX_SKILLS) {
            host.show(replyId, Card(id, null, title, ActionStatus.FAILED, SkillStrings.tooMany("max" to SkillCheck.MAX_SKILLS.toString()).shown(l)))
            return false
        }
        val ok = withContext(Dispatchers.IO) { store().save(skill) }
        if (!ok) {
            host.show(replyId, Card(id, null, title, ActionStatus.FAILED, SkillStrings.couldntSave().shown(l)))
            return false
        }
        reload()
        SkillFiles.changed(app)
        VoiceLog.add(VoiceEvent.SKILL_EDIT, (if (draft.made == SkillMade.DEMO) SkillEditCode.SAVED_THAT else SkillEditCode.TAUGHT).id,
            skill.steps.size, skill.params.size)
        val saved = SkillStrings.saved("name" to skill.name, "key" to (skill.namesEn + skill.namesFr).firstOrNull().orEmpty().ifEmpty { skill.name })
        host.show(replyId, Card(id, null, title, ActionStatus.DONE, SkillCards.level(skill.level).shown(l), body = SkillCards.steps(skill).joinToString("\n") { it.shown(l) }))
        reply(replyId, host, saved, l)
        return true
    }

    /** "Change the name": Robot asks, and your next words (within 2 minutes) are the name; the card shows again then. */
    private fun rename(replyId: String, id: String, draft: SkillDraft, title: Phrase, host: Host, l: Langs): Boolean {
        pending = Pending(Waits.NAME, draft.name, draft.lang, draft, System.currentTimeMillis())
        host.show(replyId, Card(id, null, title.shown(l), ActionStatus.DECLINED, SkillStrings.renameAsk().shown(l)))
        reply(replyId, host, SkillStrings.renameAsk(), l)
        return false
    }

    // ------------------------------------------------------------------ running

    /** [s] named in your words, with [values] (plan §7.3): its level, the lock, its card, then its steps. */
    private suspend fun run(replyId: String, s: Skill, values: Map<String, String>, host: Host, l: Langs) {
        when (val go = SkillPolicy.decide(s, host.locked, host.phoneLock, host.messages.active)) {
            is SkillGo.Refused -> {
                VoiceLog.add(VoiceEvent.SKILL_RUN, (if (s.level == SkillLevel.OFF) SkillRunCode.OFF else SkillRunCode.LOCKED).id, 0, s.steps.size)
                return reply(replyId, host, go.why, l)
            }
            is SkillGo.Card -> {
                val id = "skill-run-" + random.nextLong().toString(16)
                val title = SkillStrings.runTitle("name" to s.name).shown(l)
                val run = UiLabel(UiStrings.run)
                val footer = when {
                    s.provenance.fromContent -> SkillStrings.runTapContent("run" to run)
                    go.tapOnly -> SkillStrings.runTapMessage("run" to run)
                    else -> SkillStrings.runSayYes("run" to run)
                }
                val body = SkillCards.run(s, values).joinToString("\n") { it.shown(l) }
                host.text(replyId, footer.shown(l))
                host.say(footer.said(l))
                val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, footer.shown(l), body = body, confirm = UiStrings.run().button(l),
                    cancel = UiStrings.cancel().button(l)), tapOnly = if (go.tapOnly) footer.said(l) else null)
                if (!yes) {
                    host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, SkillStrings.notRun("name" to s.name).shown(l), body = body))
                    VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.CARD_NO.id, 0, s.steps.size)
                    return
                }
                host.show(replyId, Card(id, null, title, ActionStatus.DONE, null, body = body))
                // The phone may have locked while the card waited: each step's gate says so again.
            }
            SkillGo.Run -> Unit
        }
        SkillRunner.run(replyId, s, values, named = true, host = host, l = l)
    }

    // ------------------------------------------------------------------ deleting (always a tap)

    private suspend fun delete(replyId: String, s: Skill?, said: String, host: Host, l: Langs) {
        if (s == null) return reply(replyId, host, SkillStrings.noSuch("name" to said), l)
        val id = "skill-delete-" + random.nextLong().toString(16)
        val delete = UiLabel(UiStrings.delete)
        val title = SkillStrings.deleteCard("name" to s.name).shown(l)
        val body = SkillCards.steps(s).joinToString("\n") { it.shown(l) }
        host.text(replyId, SkillStrings.tapDelete("delete" to delete).shown(l))
        host.say(SkillStrings.tapDelete("delete" to delete).said(l))
        val yes = host.ask(replyId, Card(id, null, title, ActionStatus.PENDING, null, body = body, confirm = UiStrings.delete().button(l),
            cancel = UiStrings.cancel().button(l)), tapOnly = SkillStrings.tapDelete("delete" to delete).said(l))
        if (!yes) {
            host.show(replyId, Card(id, null, title, ActionStatus.DECLINED, SkillStrings.kept("name" to s.name).shown(l)))
            VoiceLog.add(VoiceEvent.SKILL_EDIT, SkillEditCode.KEPT.id, s.steps.size, s.params.size)
            return
        }
        if (host.locked) {
            host.show(replyId, Card(id, null, title, ActionStatus.NEEDS_UNLOCK, SkillPolicy.lockedLine(true, host.phoneLock).shown(l)))
            return
        }
        val ok = withContext(Dispatchers.IO) { store().delete(s.id) }
        reload()
        if (ok) SkillFiles.changed(app)
        VoiceLog.add(VoiceEvent.SKILL_EDIT, if (ok) SkillEditCode.DELETED.id else SkillEditCode.KEPT.id, s.steps.size, s.params.size)
        host.show(replyId, Card(id, null, title, if (ok) ActionStatus.DONE else ActionStatus.FAILED,
            (if (ok) SkillStrings.deleted("name" to s.name) else SkillStrings.couldntChange()).shown(l)))
        reply(replyId, host, if (ok) SkillStrings.deleted("name" to s.name) else SkillStrings.couldntChange(), l)
    }

    // ------------------------------------------------------------------ phase B: the model (plan §8)

    /** The word index over your skills (SkillSelect), rebuilt when they change. */
    @Volatile private var index: SkillSelect.Index? = null
    private fun index(): SkillSelect.Index = index ?: SkillSelect.Index(skills()).also { index = it }

    /** The skills this conversation describes, at most 3 (the one used least recently goes first). */
    private val shelf = SkillShelf()

    /**
     * The skills to describe to the model for [question] (your own words this turn), as tools: none when Settings ›
     * Skills' "Let Robot's model pick skills" is off; the caller leaves them out in a helper's conversation, on a locked
     * phone and for a chat-only model. A skill your words named but whose values the router couldn't read is always among
     * them (D10). Read off the main thread.
     */
    fun forModel(question: String): List<SkillTool> = try {
        if (!store().settings().modelPicks) emptyList() else {
            val all = skills()
            val named = SkillRouter.namedWithWords(question, all)
            val picked = index().pick(question, named)
            val live = all.filter { it.level != SkillLevel.OFF }.associateBy { it.id }
            val kept = shelf.use(picked.map { it.id }, live.keys).mapNotNull { live[it] }
            if (picked.isNotEmpty()) VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.DESCRIBED.id, kept.size, if (named != null) 1 else 0)
            SkillSpecs.describe(kept, all)
        }
    } catch (t: Throwable) {
        RLog.w("skill_select", t)
        emptyList()
    }

    /** [tools] as TurnRunner reads a call against them: each skill as it is now (a changed rev is refused). */
    fun menu(tools: List<SkillTool>): SkillMenu = if (tools.isEmpty()) SkillMenu.NONE else SkillMenu(tools) { id -> skills().firstOrNull { it.id == id } }

    /** People's names and aliases, one list per person (a person's value is yours when you said any of them). */
    private fun persons(): List<List<String>> = try {
        PeopleFiles.store(app).list().map { listOf(it.name) + it.aliases + it.nicknames }
    } catch (_: Throwable) { emptyList() }

    private suspend fun sample(s: Skill, model: FillModel, right: Boolean) = withContext(Dispatchers.IO) {
        try { store().editState { SkillStats.record(it, s, model, right) } } catch (t: Throwable) { RLog.w("skill_state", t) }
    }

    private fun tierCode(t: SkillTier?): Int = t?.let { it.ordinal + 1 } ?: 0

    /**
     * TurnActions.runSkill's port (plan §8): a skill the model called in your turn ([said]: your words). Its values (SkillFill:
     * yours or the model's), its tier (SkillRisk), the rule with this model's samples (SkillStats, [model]), then its card
     * when the rule wants one ("Did you mean" when your words didn't name it, D18), then its steps, each through its own gate
     * and card. A fill shown on a card is a sample: Run unchanged is right, "Did you mean" answered no and a refused value are
     * wrong, Cancel adds nothing; "Change" waits for your next words (the turn ends here).
     */
    suspend fun runForModel(replyId: String, call: SkillCall.Ok, untrusted: Boolean, said: String, loaded: BrainModel?, host: Host): SkillRan {
        val l = host.langs(said, null)
        val s = call.skill
        val model = FillModel.of(loaded ?: BrainModel.FAST)
        val named = SkillRouter.saysName(said, s)
        val fill = withContext(Dispatchers.IO) { SkillFills.of(s, call.values, said, persons()) }
        if (fill is SkillFill.Refused) {
            sample(s, model, right = false)
            VoiceLog.add(VoiceEvent.SKILL_WARN, SkillWarnCode.BAD_VALUE.id, 0, 0)
            return SkillRan(ActionStatus.INVALID, ActionStatus.INVALID.toolResponse(fill.why.en()), text = fill.why)
        }
        fill as SkillFill.Ok
        val given = fill.sources.keys
        val tier = SkillRisk.tier(s, given)
        val trusted = fill.fromModel && withContext(Dispatchers.IO) { SkillStats.trusted(store().state(), s, model) }
        VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.CALLED.id, tierCode(tier), if (named) 1 else 0)
        when (val go = SkillRisk.decide(s, named, fill.fromModel, tier, trusted, host.locked, host.phoneLock, host.messages.active)) {
            is ModelGo.Refused -> {
                host.append(replyId, go.why.shown(l))
                host.say(go.why.said(l))
                val status = if (s.level == SkillLevel.OFF) ActionStatus.DISABLED else ActionStatus.NEEDS_UNLOCK
                return SkillRan(status, status.toolResponse(go.why.en()), asked = named, text = go.why)
            }
            is ModelGo.Card -> {
                val edit = Edit(s.id, s.rev, fill.values, given, "", model.takeIf { fill.fromModel }, named, l.say)
                val answer = card(replyId, s, fill.values, fill.sources, edit, go.didYouMean, go.tapOnly, host, l)
                when {
                    answer == Confirmations.YES -> {
                        if (fill.fromModel) sample(s, model, right = true)
                        VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.RIGHT.id, tierCode(tier), if (named) 1 else 0)
                    }
                    answer >= Card.CHIP -> {
                        // "Change: …": your next words are the value; nothing runs in this turn.
                        val p = s.params.filter { it.name in given }.let { it.getOrNull(answer - Card.CHIP) ?: it.first() }
                        return waitForValue(replyId, s, Edit(s.id, s.rev, fill.values, given, p.name, edit.model, named, l.say), host, l)
                    }
                    go.didYouMean -> {
                        // D18: no goes back to the model without skills for this turn; a wrong pick, whatever was filled.
                        sample(s, model, right = false)
                        VoiceLog.add(VoiceEvent.SKILL_WARN, SkillWarnCode.DECLINED.id, tierCode(tier), 0)
                        return SkillRan(ActionStatus.DECLINED, ActionStatus.DECLINED.toolResponse(null), withoutSkills = true)
                    }
                    else -> {
                        VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.CANCELLED.id, tierCode(tier), if (named) 1 else 0)
                        return SkillRan(ActionStatus.DECLINED, ActionStatus.DECLINED.toolResponse(null), asked = named,
                            text = SkillStrings.notRun("name" to s.name))
                    }
                }
            }
            is ModelGo.Run -> {
                VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.NO_CARD.id, tierCode(tier), if (named) 1 else 0)
                if (go.trusted) {
                    VoiceLog.add(VoiceEvent.SKILL_WARN, SkillWarnCode.TRUSTED.id, tierCode(tier), 0)
                    val line = SkillStrings.ranFilled("name" to s.name, "model" to model.title)
                    host.append(replyId, line.shown(l))
                    host.say(line.said(l))
                }
            }
        }
        val out = SkillRunner.inTurn(replyId, s, fill.values, named, host, l, untrusted)
        return SkillRan(SkillRan.statusOf(out), SkillRan.forModel(out), launched = out.launched,
            content = out.results.any { it.content } || s.provenance.fromContent, asked = named, text = out.line)
    }

    /**
     * The card of a model's pick or fill: "Did you mean “Heading home”?" ([didYouMean]) with **Yes, run it** and **No**, or
     * the run card with **Run** and **Cancel**; the steps with their values, a chip "written by Robot's model" beside each
     * value it wrote, "Filled by Fast", and "Change: …" for each value the call gave. The answer: Confirmations.YES, NO or
     * Card.CHIP + the index among [edit]'s given questions.
     */
    private suspend fun card(replyId: String, s: Skill, values: Map<String, String>, sources: Map<String, FillSource>, edit: Edit, didYouMean: Boolean,
        tapOnly: Boolean, host: Host, l: Langs): Int {
        val id = "skill-pick-" + random.nextLong().toString(16)
        val title = (if (didYouMean) SkillStrings.didYouMean("name" to s.name) else SkillStrings.runTitle("name" to s.name)).shown(l)
        val yes = if (didYouMean) SkillStrings.yesRun else UiStrings.run
        val footer = when {
            !tapOnly && didYouMean -> SkillStrings.didYouMeanSay("yes" to UiLabel(yes))
            !tapOnly -> SkillStrings.runSayYes("run" to UiLabel(yes))
            didYouMean && !s.provenance.fromContent && !host.messages.active -> SkillStrings.didYouMeanTap("yes" to UiLabel(yes))
            s.provenance.fromContent -> SkillStrings.runTapContent("run" to UiLabel(yes))
            host.messages.active -> SkillStrings.runTapMessage("run" to UiLabel(yes))
            else -> SkillStrings.runTapModel("run" to UiLabel(yes))
        }
        val lines = buildList {
            addAll(SkillCards.run(s, values))
            for (p in s.params) if (sources[p.name] == FillSource.MODEL) add(SkillStrings.valueChip("value" to values.getValue(p.name), "state" to SkillStrings.modelText()))
            edit.model?.let { add(SkillStrings.filledBy("model" to it.title)) }
        }
        val body = lines.joinToString("\n") { it.shown(l) }
        val asks = s.params.filter { it.name in edit.given }.map { p -> SkillStrings.changeValue("ask" to (if (l.ui == Lang.FR) p.askFr else p.askEn)) }
        host.append(replyId, footer.shown(l))
        host.say(footer.said(l))
        val pick = host.pick(replyId, Card(id, null, title, ActionStatus.PENDING, footer.shown(l), body = body, confirm = yes().button(l),
            cancel = (if (didYouMean) SkillStrings.no() else UiStrings.cancel()).button(l), chips = asks.map { it.button(l) }),
            tapOnly = if (tapOnly) footer.said(l) else null)
        val status = if (pick == Confirmations.YES) ActionStatus.DONE else ActionStatus.DECLINED
        val line = if (pick == Confirmations.YES || pick >= Card.CHIP) null else SkillStrings.notRun("name" to s.name).shown(l)
        host.show(replyId, Card(id, null, title, status, line, body = body))
        return pick
    }

    /** "Change: …": Robot asks the question, and your next words (within 2 minutes) are the value. */
    private fun waitForValue(replyId: String, s: Skill, edit: Edit, host: Host, l: Langs): SkillRan {
        val p = s.param(edit.param)!!
        pending = Pending(Waits.VALUE, s.name, edit.lang, null, System.currentTimeMillis(), edit)
        val ask = Phrase.plain(if (l.say == Lang.FR) p.askFr else p.askEn)
        host.append(replyId, ask.shown(l))
        host.say(ask.said(l))
        return SkillRan(ActionStatus.DECLINED, ActionStatus.DECLINED.toolResponse(null), endTurn = true, text = ask)
    }

    /**
     * The value you said after "Change: …": the card again with it (yours now), then Run, which counts the model's fill as
     * wrong (D16), or Cancel, which counts nothing. Each step still goes through its own gate and card.
     */
    private suspend fun edited(replyId: String, r: Routed.Value, host: Host, l: Langs) {
        val e = r.edit
        val s = store().get(e.skillId)?.takeIf { it.rev == e.rev } ?: return reply(replyId, host, SkillStrings.changed(), l)
        val p = s.param(e.param) ?: return reply(replyId, host, SkillStrings.changed(), l)
        val v = SkillFills.read(p.type, r.words) ?: return reply(replyId, host, SkillStrings.fillRefused("ask" to (if (l.say == Lang.FR) p.askFr else p.askEn)), l)
        val values = e.values + (p.name to v)
        val sources = e.given.associateWith { if (it == p.name) FillSource.YOURS else FillSource.MODEL }
        if (host.locked) return reply(replyId, host, if (host.phoneLock) LockStrings.closedNow() else TurnStrings.unlockThenAsk(), l)
        if (s.level == SkillLevel.OFF) return reply(replyId, host, SkillStrings.isOff("name" to s.name, "page" to UiStrings.inSettings(UiStrings.skills)), l)
        val tier = SkillRisk.tier(s, e.given)
        val tap = s.provenance.fromContent || host.messages.active || tier == SkillTier.RISKY
        val edit = Edit(s.id, s.rev, values, e.given, "", e.model, e.named, e.lang)
        when (val answer = card(replyId, s, values, sources, edit, didYouMean = false, tapOnly = tap, host = host, l = l)) {
            Confirmations.YES -> {
                // D16: a value changed, then Run: the model's fill was wrong.
                e.model?.let { sample(s, it, right = false) }
                VoiceLog.add(VoiceEvent.SKILL_WARN, SkillWarnCode.EDITED.id, tierCode(tier), 0)
                VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.WRONG.id, tierCode(tier), if (e.named) 1 else 0)
                SkillRunner.run(replyId, s, values, e.named, host, l)
            }
            else -> if (answer >= Card.CHIP) {
                val q = s.params.filter { it.name in e.given }.getOrNull(answer - Card.CHIP) ?: p
                waitForValue(replyId, s, Edit(s.id, s.rev, values, e.given, q.name, e.model, e.named, e.lang), host, l)
            } else VoiceLog.add(VoiceEvent.SKILL_PICK, SkillPickCode.CANCELLED.id, tierCode(tier), if (e.named) 1 else 0)
        }
    }

    // ------------------------------------------------------------------ the continuation (D7)

    /**
     * The panel opened by a "Continue “Heading home”" tap: what the turn says it is for, and the work (the rest of the
     * steps, each through its gate again); null when the show isn't one.
     */
    fun onShow(args: Bundle?): Pair<String, suspend (String, Host) -> Unit>? {
        val token = args?.getString(SkillIntents.ARG_SKILL) ?: return null
        val l = PanelRoutines.decideLangs(null, null)
        val w = SkillContinue.peek(token)
        val s = w?.let { store().get(it.skillId) }?.takeIf { it.rev == w.rev }
        if (s == null) return SkillStrings.continueAny().shown(l) to { replyId, host ->
            SkillContinue.take(app, token)
            VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.EXPIRED.id, 0, 0)
            reply(replyId, host, SkillStrings.expired(), l)
            host.finish(replyId)
        }
        return SkillStrings.continueTitle("name" to s.name).shown(l) to { replyId, host ->
            try {
                when {
                    // Locked: nothing runs, and the notification stays until it expires.
                    host.locked -> reply(replyId, host, if (host.phoneLock) LockStrings.closedNow() else TurnStrings.unlockThenAsk(), l)
                    s.level == SkillLevel.OFF -> {
                        SkillContinue.take(app, token)
                        reply(replyId, host, SkillStrings.isOff("name" to s.name, "page" to UiStrings.inSettings(UiStrings.skills)), l)
                    }
                    else -> {
                        val c = SkillContinue.take(app, token)
                        if (c == null) reply(replyId, host, SkillStrings.expired(), l) else {
                            VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.CONTINUED.id, c.done, s.steps.size)
                            SkillRunner.run(replyId, s, c.values, c.named, host, l, from = c.from, done = c.done, untrusted = c.untrusted)
                        }
                    }
                }
            } finally {
                host.finish(replyId)
            }
        }
    }
}
