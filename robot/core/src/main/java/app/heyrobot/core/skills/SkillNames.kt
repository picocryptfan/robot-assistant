// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.FreeUp
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.helpers.HelperNames
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.web.BrowserRouter
import java.time.ZonedDateTime

// Pure. A skill's name and other names checked against everything else that listens before the model (plan D15): the
// routers in the panel's order (messages, conversation, feedback, the phone lock, routines, hands-free, web, memory,
// free-up, helpers) on every form that names a skill ("run <name>", "<name> for 5 minutes", "what does <name> do",
// "delete the skill <name>"), your routines', locks', helpers' and Termux scripts' names, your wake phrases, the helpers'
// command words, and your other skills. A name that is taken is refused with the reason; one taken later (a routine or a
// lock you add afterwards) is noted on the skill's page ([shadows]).

/** What a name is checked against: what is on this phone now. */
data class NameContext(
    val skills: List<Skill> = emptyList(),
    val routines: List<String> = emptyList(),
    val locks: List<String> = emptyList(),
    /** Your helpers as the router hears them, and their names as shown. */
    val helpers: List<HelperName> = listOf(HelperName.CLAUDE),
    val helperNames: List<String> = emptyList(),
    val wake: List<String> = emptyList(),
    val scripts: List<String> = emptyList(),
)

object SkillNames {
    /** Why [name] can't be a name of skill [selfId] (null: a new skill), or null when it may. */
    fun check(name: String, selfId: String?, ctx: NameContext, now: ZonedDateTime, other: Boolean = false): Phrase? {
        val n = name.trim()
        if (if (other) !SkillCheck.validOtherName(n) else !SkillCheck.validName(n)) return if (other) SkillStrings.otherNameRules() else SkillStrings.nameRules()
        val key = SkillKey.of(n)
        val words = key.split(' ')
        if (words.size == 1 && words[0] in HelperNames.commandWords) return SkillStrings.nameCommand("word" to n)
        for (w in ctx.wake) {
            val wk = SkillKey.of(w)
            if (wk.isNotEmpty() && (key == wk || " $key ".contains(" $wk "))) return SkillStrings.nameWake("name" to n)
        }
        if (ctx.skills.any { it.id != selfId && key in it.keys }) return SkillStrings.nameSkill("name" to n)
        if (RoutineRouter.find(n, ctx.routines) { it } != null || ctx.routines.any { SkillKey.of(it) == key }) return SkillStrings.nameRoutine("name" to n)
        if (ctx.locks.any { SkillKey.of(it) == key }) return SkillStrings.nameLock("name" to n)
        if (ctx.helperNames.any { SkillKey.of(it) == key } || ctx.helpers.any { h -> h.forms.any { it.joinToString(" ") == key } }) return SkillStrings.nameHelper("name" to n)
        if (ctx.scripts.any { SkillKey.of(it) == key }) return SkillStrings.nameScript("name" to n)
        for (form in forms(n)) claimedBy(form, ctx, now)?.let { return SkillStrings.nameRobot("name" to n, "what" to it) }
        return null
    }

    /** Every way a skill is said with [name] in it (plan D15): alone, run, with a value, asked about, deleted. */
    fun forms(name: String): List<String> = SkillWords.NAME_FORMS.map { it.replace(SkillWords.NAME_SLOT, name) }

    /** Which of Robot's own features hears [text] before skills would, in words for "Robot already uses … for …". */
    fun claimedBy(text: String, ctx: NameContext, now: ZonedDateTime): Phrase? = when {
        MessagesRouter.route(text) != null -> SkillStrings.whatMessages()
        ConversationRouter.route(text, running = false) != null || ConversationRouter.route(text, running = true) != null -> SkillStrings.whatConversation()
        FeedbackRouter.route(text) != null -> SkillStrings.whatFeedback()
        LockRouter.route(text, now, ctx.locks, phoneLock = true, lockOn = false) != null ||
            LockRouter.route(text, now, ctx.locks, phoneLock = true, lockOn = true) != null -> SkillStrings.whatLock()
        RoutineRouter.route(text, now, ctx.routines) != null -> SkillStrings.whatRoutines()
        HandsFreeRouter.route(text, carMode = false, wakePhrases = ctx.wake) != null ||
            HandsFreeRouter.route(text, carMode = true, wakePhrases = ctx.wake) != null -> SkillStrings.whatHandsFree()
        BrowserRouter.route(text) != null -> SkillStrings.whatWeb()
        MemoryRouting.route(text, MemorySettings(), locked = false) != null -> SkillStrings.whatMemory()
        FreeUp.asked(text) != null -> SkillStrings.whatFreeUp()
        HelperRouter.route(text, ctx.helpers) != null -> SkillStrings.whatHelpers()
        else -> null
    }

    /** What runs instead of [s] when its name is said: a routine or a phone lock added later; and the Termux script it shadows. */
    fun shadows(s: Skill, ctx: NameContext): List<Phrase> = buildList {
        val keys = s.keys
        if (ctx.routines.any { SkillKey.of(it) in keys } || keys.any { k -> RoutineRouter.find(k, ctx.routines) { it } != null }) add(SkillStrings.shadowsRoutine())
        if (ctx.locks.any { SkillKey.of(it) in keys }) add(SkillStrings.shadowsLock())
        if (ctx.scripts.any { SkillKey.of(it) in keys }) add(SkillStrings.shadowsScript())
    }

    /** A one-word name: said alone in passing, it runs the skill (the save card says so). */
    fun common(name: String): Boolean = SkillKey.words(name).size == 1
}
