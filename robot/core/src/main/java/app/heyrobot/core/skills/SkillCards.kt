// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.EventText
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.i18n.SettingsStrings
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure. What a skill's cards and answers say (plan §6, §7), as words without a language yet: the save card (the name,
// the steps numbered in their own cards' words with a chip for each value that can become a question, the level, the
// lines that apply), the run card, the delete card, and "what does it do" and "what skills do I have".

/** The save card: [chips] in order are the slots', then the level's (when it may change), then "Change the name". */
data class SaveCard(val title: Phrase, val body: List<Phrase>, val chips: List<Phrase>, val lines: List<Phrase>, val footer: Phrase?,
    val levelChip: Boolean, val nameProblem: Phrase?)

object SkillCards {
    /** A level's words (Settings › Actions' own). */
    fun level(l: SkillLevel): Phrase = when (l) {
        SkillLevel.JUST_DO_IT -> SettingsStrings.approvalAuto()
        SkillLevel.ASK_FIRST -> SettingsStrings.approvalAsk()
        SkillLevel.OFF -> SettingsStrings.approvalOff()
    }

    /**
     * Step [step] of [s] in its card's words, with [values] for its questions (the defaults for the rest). A connection's step
     * whose link is known ([links], C4, plan §5.4): your operation's title, your connection's name, its host and what it
     * does ("Turn off a light · your Home Assistant (home.example.org) · changes your home"): shown, never logged or told to a model.
     */
    fun stepText(s: Skill, step: SkillStep, values: Map<String, String> = emptyMap(), links: SkillLinks = SkillLinks.NONE): Phrase {
        val op = if (step.kind == StepKind.CONN && step.conn != null && step.op != null) links.op(step.conn, step.op) else null
        val title = op?.title
        if (op != null && title != null) {
            val effects = app.heyrobot.core.connect.ConnStep.chips(SkillEffects.step(s, step, links), op.host.orEmpty())
            return app.heyrobot.core.i18n.ConnStrings.skillStep("title" to title, "name" to op.name.orEmpty(), "host" to op.host.orEmpty(),
                "effects" to Phrase.ListOf(effects))
        }
        if (step.kind.tool == null) return kindTitle(step)
        return SkillCheck.action(s, step, values)?.summary() ?: kindTitle(step)
    }

    /**
     * What kind of step [step] is, without its values: its tool's title, "Say …", or one of the connections plan's steps
     * (its operation's and connection's names come with C5 to C9; never an address, an entity or a topic).
     */
    fun kindTitle(step: SkillStep): Phrase = when (step.kind) {
        StepKind.SAY -> SkillStrings.stepSay("text" to (step.args["text"] as? String).orEmpty())
        StepKind.CONN -> SkillStrings.stepConnection()
        StepKind.APP -> SkillStrings.stepHandOff()
        StepKind.TRAVEL_TIME -> SkillStrings.stepTravel()
        StepKind.TERMUX_SCRIPT -> SkillStrings.stepScript("name" to (step.args["script"] as? String).orEmpty())
        else -> step.kind.tool!!.titleText
    }

    /** "1. Text **Sam**", "2. Open the route home in Organic Maps". */
    fun steps(s: Skill, values: Map<String, String> = emptyMap(), links: SkillLinks = SkillLinks.NONE): List<Phrase> =
        s.steps.mapIndexed { i, st -> SkillStrings.stepLine("i" to (i + 1).toString(), "step" to stepText(s, st, values, links)) }

    /**
     * The save card of [d]. [nameProblem]: why its name can't be used (SkillNames), or null. [paused]: D8, the steps came
     * after a pause (the card suggests a longer wait).
     */
    fun save(d: SkillDraft, nameProblem: Phrase?): SaveCard {
        val s = d.skill()
        val title = (if (d.made == SkillMade.DEMO) SkillStrings.cardSave else SkillStrings.cardNew)("name" to s.name)
        val chips = d.slots.map { slot ->
            SkillStrings.stepLine("i" to (slot.step + 1).toString(), "step" to slot.chip(slot in d.asked))
        }.toMutableList()
        // D13: made from content, it stays at Ask first; otherwise Just do it takes a tap (D14).
        val levelChip = !d.fromContent
        if (levelChip) chips += SkillStrings.level("level" to level(d.level))
        chips += SkillStrings.rename()
        val lines = buildList {
            for (slot in d.asked) {
                val q = s.params.firstOrNull { p -> s.steps[slot.step].args[slot.arg] == SkillTemplate.of(p.name) }
                if (q != null) add(SkillStrings.askedLine("ask" to SkillDraft.ASK.getValue(q.type)(), "value" to slot.shown))
            }
            for (u in d.unparsed) add(SkillStrings.notUnderstood("words" to u))
            addAll(d.cantDo)
            addAll(d.leftOut)
            addAll(d.notes)
            addAll(d.problems)
            nameProblem?.let(::add)
            if (SkillNames.common(s.name)) add(SkillStrings.commonWord("name" to ((s.namesEn + s.namesFr).firstOrNull() ?: s.name)))
            if (s.steps.dropLast(1).any { SkillRun.mayOpenApp(it.kind) }) add(SkillStrings.moreApps())
            if (d.paused) add(SkillStrings.waitTip("page" to UiStrings.inSettings(UiStrings.voice)))
        }
        val footer = when {
            d.problems.isNotEmpty() -> null
            d.fromContent -> SkillStrings.tapToSave("save" to UiLabel(UiStrings.save))
            else -> SkillStrings.sayYesOrSave("save" to UiLabel(UiStrings.save))
        }
        return SaveCard(title, steps(s), chips, lines, footer, levelChip, nameProblem)
    }

    /** The run card's body: the steps with the values this run uses, a connection's step named as [links] knows it (C4). */
    fun run(s: Skill, values: Map<String, String>, links: SkillLinks = SkillLinks.NONE): List<Phrase> = steps(s, values, links)

    /** "Today 17:32 · 2 of 2 done", "Never run": a skill's last run as Settings › Skills shows it. */
    fun lastRun(r: SkillRunRecord?, now: ZonedDateTime): Phrase {
        r ?: return SkillStrings.neverRun()
        val at = Instant.ofEpochMilli(r.at).atZone(now.zone)
        val day: Any = when (at.toLocalDate()) {
            now.toLocalDate() -> RoutineStrings.today()
            now.toLocalDate().minusDays(1) -> RoutineStrings.yesterday()
            else -> at.toLocalDate()
        }
        return SkillStrings.lastRun("day" to day, "time" to EventText.clock(at.hour, at.minute), "done" to r.done.toString(), "total" to r.total.toString())
    }

    /** A skill's row in Settings › Skills: its level and its last run. */
    fun row(s: Skill, r: SkillRunRecord?, now: ZonedDateTime): Phrase = SkillStrings.levelRow("level" to level(s.level), "run" to lastRun(r, now))

    /** The "Made" row: how and when, and when last edited. */
    fun made(s: Skill, zone: ZoneId): Phrase {
        val created = Instant.ofEpochMilli(s.provenance.created).atZone(zone).toLocalDate()
        val edited = Instant.ofEpochMilli(s.provenance.edited).atZone(zone).toLocalDate()
        val how = when {
            s.provenance.fromContent -> SkillStrings.madeContent()
            s.provenance.made == SkillMade.VOICE -> SkillStrings.madeVoice("date" to created)
            s.provenance.made == SkillMade.DEMO -> SkillStrings.madeDemo("date" to created)
            else -> SkillStrings.madeSettings("date" to created)
        }
        return if (edited != created) Phrase.Join(listOf(how, SkillStrings.edited("date" to edited)), " · ") else how
    }

    /** "“Heading home”: text Sam, then open the route home." */
    fun about(s: Skill): Phrase = SkillStrings.about("name" to s.name, "steps" to Phrase.ListOf(s.steps.map { stepText(s, it) }))

    /** "Your skills: “Heading home” and “Pasta”." */
    fun list(skills: List<Skill>): Phrase = if (skills.isEmpty()) SkillStrings.none()
        else SkillStrings.list("names" to Phrase.ListOf(skills.map { SkillStrings.quoted("name" to it.name) }))
}
