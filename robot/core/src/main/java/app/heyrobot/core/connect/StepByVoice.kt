// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.memory.MemoryText
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillStep
import java.text.Normalizer
import java.util.Locale

// Pure. "Add to <skill>: <what a connection does>" (1.4): which of your connections' operations the words name, the values
// Add a step's form leaves for you (asked one at a time), and whether the step touches a lock, a door or an alarm, which
// is never added by voice alone. There is no spoken connection request outside a skill, so the words are matched the way
// the skill index reads a linked step (SkillSelect): word tokens, never a host, a key or an entity.

object StepByVoice {
    sealed interface Found {
        /** [shown]: its title as said back, with its connection's name when another connection has that title too (review Low 2). */
        class One(val conn: Connection, val op: ConnOp, val shown: String = op.title) : Found {
            override fun toString(): String = "One(…)" // l10n: not text (toString, for the logs)
        }
        /** More than one fits as well: their titles with their connections' names ("Porch light on (Cabin)"), at most 3. */
        class Several(val titles: List<String>) : Found {
            override fun toString(): String = "Several(…)" // l10n: not text (toString, for the logs)
        }
        data object None : Found
    }

    /**
     * The operation of your Home Assistant, ntfy and webhook connections (Add a step's own list) whose title your words
     * hold, every word of it (never part of it: "close the living room blinds" holds most of "Open the living room
     * blinds"), and don't contradict ([OPPOSITES]: "turn off the porch light" against "Porch light on", where "on" is no
     * search word); the one holding the most words, or [Found.Several] on a tie: a question, never a guess. On a tie, the
     * connection your words name (every word of its name: "porch light on the Cabin") tells the same title apart (Low 5).
     */
    fun find(what: String, connections: List<Connection>): Found {
        val all = MemoryText.tokens(what)
        val words = all.toSet()
        if (words.isEmpty()) return Found.None
        val ops = SkillEditor.offers(connections, emptyList()).filterIsInstance<StepOffer.Operation>()
        val said = plain(what)
        val scored = ops.mapNotNull { o ->
            val tt = MemoryText.tokens(o.op.title)
            val t = tt.toSet()
            if (t.isEmpty() || !words.containsAll(t) || contradicts(said, plain(o.op.title))) null
            else o to t.size * 2 + (if (named(all, o.conn, tt)) 1 else 0)
        }
        if (scored.isEmpty()) return Found.None
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        if (top.size == 1) {
            val o = top[0].first
            val twice = ops.any { it.conn.id != o.conn.id && it.op.title.equals(o.op.title, ignoreCase = true) }
            return Found.One(o.conn, o.op, if (twice) "${o.op.title} (${o.conn.name})" else o.op.title) // l10n: not text (a title and a name you gave)
        }
        return Found.Several(top.take(3).map { (o, _) -> "${o.op.title} (${o.conn.name})" }) // l10n: not text (a title and a name you gave)
    }

    /**
     * Your words hold each search word of [c]'s name more times than the [title] does (review-c Low 1, counted: "porch
     * light on the porch" names Porch, "porch light on" doesn't).
     */
    private fun named(words: List<String>, c: Connection, title: List<String>): Boolean {
        val name = MemoryText.tokens(c.name)
        return name.isNotEmpty() && name.distinct().all { w -> words.count { it == w } >= title.count { it == w } + name.count { it == w } }
    }

    /**
     * Words that undo each other in a title (English, and French as said in Quebec, where "ferme" and "ouvre" also turn a
     * light off and on). Matched on whole lowercase words, stop words included.
     */
    private val OPPOSITES: List<Pair<Set<String>, Set<String>>> = listOf(
        setOf("open", "opens", "opened", "ouvre", "ouvrir", "ouvert", "ouverte", "ouverts", "ouvertes", "ouvrez") to // l10n: not text (matched words)
            setOf("close", "closes", "closed", "shut", "ferme", "fermer", "fermé", "fermée", "fermés", "fermées", "fermez"), // l10n: not text (matched words)
        setOf("on", "allume", "allumer", "allumé", "allumée", "allumés", "allumées", "allumez", "ouvre") to // l10n: not text (matched words)
            setOf("off", "éteins", "éteindre", "éteint", "éteinte", "éteints", "éteintes", "éteignez", "ferme"), // l10n: not text (matched words)
        setOf("lock", "locks", "locked", "verrouille", "verrouiller", "verrouillé", "verrouillée", "verrouillez", "barre", "barrer") to // l10n: not text (matched words)
            setOf("unlock", "unlocks", "unlocked", "déverrouille", "déverrouiller", "déverrouillé", "déverrouillée", "déverrouillez", "débarre", "débarrer"), // l10n: not text (matched words)
        setOf("up", "raise", "raises", "monte", "monter", "lève", "lever", "haut") to // l10n: not text (matched words)
            setOf("down", "lower", "lowers", "descends", "descendre", "baisse", "baisser", "bas"), // l10n: not text (matched words)
        setOf("arm", "arms", "armed", "arme", "armer") to setOf("disarm", "disarms", "disarmed", "désarme", "désarmer"), // l10n: not text (matched words)
        setOf("start", "starts", "démarre", "démarrer") to setOf("stop", "stops", "arrête", "arrêter"), // l10n: not text (matched words)
    )

    private val LETTERS = Regex("\\p{L}+")

    private fun plain(s: String): Set<String> =
        LETTERS.findAll(Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)).map { it.value }.toSet()

    /** The title says one side of a pair and your words the other (words holding both sides contradict too). */
    private fun contradicts(said: Set<String>, title: Set<String>): Boolean = OPPOSITES.any { (a, b) ->
        val ta = title.any { it in a }
        val tb = title.any { it in b }
        (ta && !tb && said.any { it in b }) || (tb && !ta && said.any { it in a })
    }

    /** The values the form leaves for you: required ones the new [step] has no value for, and a choice among several (Low 6). */
    fun missing(step: SkillStep, conn: Connection, op: ConnOp): List<ArgSlot> =
        SkillEditor.slots(conn, op).filter { it.required && (step.args[it.name] == null || several(it)) }

    /**
     * What Robot asks for [slot] of [step] (its title): a choice among several (the entities you picked) by its values,
     * else the value by its name.
     */
    fun ask(slot: ArgSlot, step: String): Phrase = if (several(slot)) SkillStrings.addWhich("step" to step, "list" to slot.options.joinToString(", ") { shown(slot, it) })
    else SkillStrings.addValue("value" to (slot.label ?: slot.name), "step" to step)

    /**
     * The value of [slot] your [words] name: a choice whose words they hold, every word ("the kitchen ceiling" for
     * light.kitchen_ceiling), the one holding the most, never a tie; else the words as said, for the form to check.
     */
    fun which(slot: ArgSlot, words: String): String {
        val t = words.trim()
        if (slot.type != ParamType.CHOICE) return t
        slot.options.firstOrNull { it.equals(t, ignoreCase = true) }?.let { return it }
        val said = MemoryText.tokens(t).toSet()
        val fit = slot.options.mapNotNull { o -> MemoryText.tokens(shown(slot, o)).toSet().takeIf { it.isNotEmpty() && said.containsAll(it) }?.let { o to it.size } }
        val best = fit.maxOfOrNull { it.second } ?: return inPart(slot, said).singleOrNull() ?: t
        return fit.filter { it.second == best }.singleOrNull()?.first ?: t
    }

    /** Review-c Low 2: the options your answer shares a word with ("kitchen" for kitchen ceiling). */
    private fun inPart(slot: ArgSlot, said: Set<String>): List<String> =
        if (said.isEmpty()) emptyList() else slot.options.filter { o -> MemoryText.tokens(shown(slot, o)).any { it in said } }

    /** Asked again when [slot]'s answer didn't fit: a choice by its question (part of several fits), else the value. */
    fun again(slot: ArgSlot, step: String): Phrase = if (several(slot)) ask(slot, step) else SkillStrings.addBadValue("value" to (slot.label ?: slot.name))

    /**
     * Review Low 5: [words] answer [slot]'s question. A choice's answer names one of its options; other words end the wait
     * and go on as usual (a command said meanwhile isn't lost); part of
     * an option fits (review-c Low 2). Any words for a value by its name.
     */
    fun fits(slot: ArgSlot, words: String): Boolean = slot.type != ParamType.CHOICE || which(slot, words) in slot.options ||
        inPart(slot, MemoryText.tokens(words.trim()).toSet()).isNotEmpty()

    /** What a value said for step [i] of [s] does: fills it, asked again, or (review Low 1) refused there. */
    sealed interface Value {
        class Filled(val skill: Skill) : Value
        /** Not a value the form takes: Robot asks again. */
        data object Bad : Value
        /** The step it makes is one a spoken yes never approves (a garage door picked among blinds): refused at once. */
        class Refused(val why: ScreenOnly, val conn: String) : Value
    }

    /** [words] as the value of [slot] in step [i] of [s]; the step is checked again with the value, before the say-back. */
    fun value(s: Skill, i: Int, slot: ArgSlot, words: String, connections: List<Connection>): Value {
        val set = SkillEditor.setConstant(s, i, slot, which(slot, words)) ?: return Value.Bad
        val step = set.steps[i]
        screenOnly(set, step, connections)?.let { why -> return Value.Refused(why, connections.firstOrNull { it.id == step.conn }?.name.orEmpty()) }
        return Value.Filled(set)
    }

    /** [title] with the entity [step] acts on, when it holds one of several you picked ("Lights on (porch)"). */
    fun named(title: String, step: SkillStep): String = (step.args[ConnOp.ENTITY] as? String)?.let { e ->
        // Review-c Low 4: one parenthesis, the connection's name and the entity ("Porch light on (Home Assistant, porch)").
        if (title.endsWith(")") && title.contains(" (")) "${title.dropLast(1)}, ${entity(e)})" else "$title (${entity(e)})" // l10n: not text (a title and an entity of yours)
    } ?: title

    /** A choice among several: the form's first is a guess, so it is asked. */
    private fun several(slot: ArgSlot): Boolean = slot.type == ParamType.CHOICE && slot.options.size > 1

    private fun shown(slot: ArgSlot, o: String): String = if (slot.name == ConnOp.ENTITY) entity(o) else o

    /** An entity as said: its object id's words ("kitchen ceiling" for light.kitchen_ceiling). */
    private fun entity(id: String): String = id.substringAfter('.').replace('_', ' ')

    /** [step] touches a lock, a door or an alarm (its effects as the skill's page shows them): added on the screen only. */
    fun security(s: Skill, step: SkillStep, connections: List<Connection>): Boolean = screenOnly(s, step, connections) == ScreenOnly.SECURITY

    /** Why a step is added on the screen only: what a spoken yes never approves (plan D14). */
    enum class ScreenOnly { SECURITY, RISKY, CODE, FIRST_USE, TAP }

    /**
     * Null when a spoken yes may add [step]. Else why not: a lock, a door or an alarm; a delete or money; a Home Assistant
     * scene, script or group (it reaches entities Robot can't see, a lock's or an alarm's among them); or a connection
     * whose first-use card you haven't answered with Allow (never a spoken yes); a step whose run card wants a tap (its connection's
     * "A spoken yes may approve its changes" off, or an effect outside [ConnStep.SPOKEN_YES]). Fails closed: no connection, first use.
     */
    fun screenOnly(s: Skill, step: SkillStep, connections: List<Connection>): ScreenOnly? {
        val e = SkillEditor.effects(s.copy(steps = listOf(step), params = emptyList()), ConnLinks(connections))
        return when {
            SkillEffect.SECURITY in e -> ScreenOnly.SECURITY
            SkillEffect.DELETES in e || SkillEffect.MONEY in e -> ScreenOnly.RISKY
            SkillEffect.RUNS_CODE in e -> ScreenOnly.CODE
            connections.firstOrNull { it.id == step.conn }?.let(ConnConsent::asks) != false -> ScreenOnly.FIRST_USE
            // Review Low 6: the run-time rule (ConnStep), not the blocklist alone: its switch on and every effect one a spoken yes may approve.
            connections.firstOrNull { it.id == step.conn }?.let { c -> c.spokenYes && e.all { it in ConnStep.SPOKEN_YES } } != true -> ScreenOnly.TAP
            else -> null
        }
    }

    /** What Robot says when [why] keeps a step off the voice path, and whether it opens the skill's page. */
    class Refusal(val line: Phrase, val opens: Boolean)

    /** On yes: the skill to save, or why nothing is saved. */
    sealed interface OnYes {
        class Save(val skill: Skill) : OnYes
        class Not(val line: Phrase) : OnYes
    }

    /**
     * The yes on Add's card (review M1): step [i] of [snapshot] (the copy taken when the command came) goes on [stored], the
     * skill as saved now, so a touch edit made while the card waited stays; checked again with the [connections] of now.
     */
    fun onYes(stored: Skill?, snapshot: Skill, i: Int, title: String, connections: List<Connection>): OnYes {
        if (stored == null) return OnYes.Not(SkillStrings.addGone("name" to snapshot.name))
        val step = snapshot.steps[i]
        val conn = connections.firstOrNull { it.id == step.conn }
        if (conn == null || conn.ops.none { it.id == step.op }) return OnYes.Not(SkillStrings.addConnGone("step" to title))
        if (screenOnly(stored, step, connections) != null) return OnYes.Not(SkillStrings.addNowScreen("step" to title))
        val added = SkillEditor.add(stored, step)
            ?: return OnYes.Not(SkillStrings.addFull("name" to stored.name, "max" to SkillCheck.MAX_STEPS.toString()))
        return OnYes.Save(added)
    }

    /** In Car mode ([driving]) nothing opens and nothing waits to open: you add it on the screen once parked. */
    fun refusal(why: ScreenOnly, step: String, conn: String, driving: Boolean): Refusal = if (driving) Refusal(SkillStrings.addPark("step" to step), false)
    else Refusal(when (why) {
        ScreenOnly.SECURITY -> SkillStrings.addSecurity("step" to step)
        ScreenOnly.RISKY -> SkillStrings.addRisky("step" to step)
        ScreenOnly.CODE -> SkillStrings.addCode("step" to step, "name" to conn)
        ScreenOnly.FIRST_USE -> SkillStrings.addFirst("step" to step, "name" to conn)
        ScreenOnly.TAP -> SkillStrings.addTap("step" to step)
    }, true)
}
