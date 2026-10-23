// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.memory.Bm25Index
import app.heyrobot.core.memory.MemoryText
import kotlin.math.ln

// Pure. Phase B (plan §8): which of your skills, at most 3, are described to Robot's model for what you just said. Word
// search (memory's BM25, accents and case folded, English and French stop words dropped, plurals reduced) over one
// document per skill: its name three times, its other names twice, and its steps' kinds by their titles in English and
// French ("Timers", « Minuteries »). Not the steps' own words: a fixed value ("12 minutes", "Marie") matched every request
// that named a number or that person. Your skills that are Off are never described.
//
// BM25's idf depends on how many documents there are: with one skill, every word it holds scores 0.29 at most; with 20,
// a rare word scores 2.6. So the index always holds the same background beside your skills (every tool Robot has, its
// description and its titles in English and French), which keeps the words every tool shares light, and a score is
// scaled to what it would be among [REFERENCE] skills: the threshold, measured on the labelled set (SkillSelectTest, 20
// skills, 200 lines), then means the same with 1 skill or 100.
//
// One word of a two-word name is weak evidence ("what time is it" and "Tea time", "a good book" and "Good night"): a skill
// none of whose names your words hold whole scores [PARTIAL] of its score.

object SkillSelect {
    /** Skills described to the model at once, at most (the user, 15 Oct). */
    const val MAX = 3

    /**
     * The least score a skill needs. The plan's estimate (1.2 times the median non-zero score of the irrelevant requests)
     * gave 2.1 here, which described a skill for 10 of the 60 irrelevant requests; 3.0 is where the labelled set keeps
     * recall at 3 at 96% (135 of 140; 137 since « thé » stopped folding into the stop word "the") and stays quiet for 92%
     * (55 of 60). SK14 measures it on the phone before 1.2.
     */
    const val MIN_SCORE = 3.0

    /** What a skill scores when your words don't hold any of its names whole. */
    const val PARTIAL = 0.6

    /** The labelled set's size: scores are scaled to what they would be in a library of this many skills. */
    const val REFERENCE = 20

    /** A skill scoring under this share of the best one isn't described with it. */
    const val WITHIN = 0.5

    private const val BACKGROUND = "~" // l10n: not text (the background documents' id prefix: never a skill id, which starts with "s-")

    /** A skill's document: its name 3 times, its other names twice, its steps' kinds by their titles in English and French. */
    fun document(s: Skill): String = buildList {
        repeat(3) { add(s.name) }
        (s.namesEn + s.namesFr).forEach { n -> repeat(2) { add(n) } }
        for (step in s.steps) step.kind.tool?.let { t -> for (l in Lang.entries) add(t.titleText.render(Langs.of(l))) }
    }.joinToString(" . ")

    /** Robot's tools, each its description and its titles in English and French: the same background for every library. */
    private val background: List<Pair<String, String>> by lazy {
        ToolName.entries.filter { it.forModel }.mapIndexed { i, t ->
            val spec = ToolSpecs.ALL.firstOrNull { it.tool == t }?.description.orEmpty()
            "$BACKGROUND$i" to (listOf(spec) + Lang.entries.map { t.titleText.render(Langs.of(it)) }).joinToString(" . ")
        }
    }

    /** Your words hold every search word of one of [s]'s names. */
    private fun saysAName(s: Skill, words: Set<String>): Boolean = s.allNames.any { n ->
        val t = MemoryText.tokens(n).toSet()
        t.isNotEmpty() && words.containsAll(t)
    }

    /** An index over [skills] (Off ones left out), rebuilt when they change. */
    class Index(skills: List<Skill>) {
        private val byId = skills.filter { it.level != SkillLevel.OFF }.associateBy { it.id }
        private val bm25 = Bm25Index.build(byId.values.map { it.id to document(it) } + background)

        /** A word only one document holds weighs ln(1 + (N - 0.5) / 1.5): scaled to its weight among [REFERENCE] skills. */
        private val scale = unique(REFERENCE + background.size) / unique(byId.size + background.size)

        private fun unique(docs: Int): Double = ln(1.0 + (docs - 0.5) / 1.5)

        /** The skills scoring for [query], best first, with their scores (no threshold): for the tests and SK14. */
        fun scores(query: String): List<Pair<Skill, Double>> {
            val words = MemoryText.tokens(query).toSet()
            return bm25.search(query, limit = byId.size + background.size).mapNotNull { h ->
                byId[h.id]?.let { it to scale * if (saysAName(it, words)) h.score else h.score * PARTIAL }
            }.sortedByDescending { it.second }
        }

        /**
         * The skills to describe for [query]: those scoring at least [MIN_SCORE] and within [WITHIN] of the best, at most
         * [MAX]. [named]: a skill your words named but whose words the router couldn't read (D10): always among them, first.
         */
        fun pick(query: String, named: Skill? = null): List<Skill> {
            val hits = scores(query).filter { it.second >= MIN_SCORE }
            val best = hits.firstOrNull()?.second ?: 0.0
            val found = hits.filter { it.second >= best * WITHIN }.map { it.first }
            val forced = named?.takeIf { it.level != SkillLevel.OFF }
            return (listOfNotNull(forced) + found).distinctBy { it.id }.take(MAX)
        }
    }
}

/**
 * The skills a conversation describes (plan §8): at most [SkillSelect.MAX], kept from turn to turn so the conversation is
 * rebuilt only when a new one comes in; a fourth replaces the one used least recently. Reset with the conversation.
 */
class SkillShelf {
    private val ids = ArrayList<String>()

    /** The ids kept, the most recently used last. */
    val kept: List<String> get() = synchronized(ids) { ids.toList() }

    /** [picked] this turn (the first is the best) become the most recent; what no longer exists in [have] goes. */
    fun use(picked: List<String>, have: Set<String>): List<String> = synchronized(ids) {
        ids.retainAll(have)
        for (id in picked.reversed()) {
            ids.remove(id)
            ids.add(id)
        }
        while (ids.size > SkillSelect.MAX) ids.removeAt(0)
        ids.toList()
    }

    fun clear() = synchronized(ids) { ids.clear() }
}
