// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B's selection (plan §8, §11): the labelled set's recall and quiet, the cap of 3, the same rule for 1 skill or 20. */
class SkillSelectTest {
    private val lib = SkillLibrary.skills
    private val index = SkillSelect.Index(lib.values.toList())

    @Test fun theSetHas200LinesAnd20Skills() {
        assertEquals(20, lib.size)
        assertEquals(200, SkillLibrary.lines.size)
        assertEquals(140, SkillLibrary.lines.count { it.second != null })
        lib.values.forEach { assertTrue(it.name, SkillCheck.valid(it)) }
        SkillLibrary.lines.mapNotNull { it.second }.forEach { assertTrue(it, it in lib) }
    }

    @Test fun recallAtThreeIsAtLeast95Percent() {
        val about = SkillLibrary.lines.filter { it.second != null }
        val missed = about.filter { (q, key) -> lib.getValue(key!!).id !in index.pick(q).map { it.id } }
        val recall = 1.0 - missed.size.toDouble() / about.size
        assertTrue("recall@3 ${"%.3f".format(recall)}, missed: ${missed.map { it.first }}", recall >= 0.95)
    }

    @Test fun noSkillForAtLeast90PercentOfTheRest() {
        val rest = SkillLibrary.lines.filter { it.second == null }
        val noisy = rest.filter { (q, _) -> index.pick(q).isNotEmpty() }
        val quiet = 1.0 - noisy.size.toDouble() / rest.size
        assertTrue("quiet ${"%.3f".format(quiet)}, found a skill for: ${noisy.map { it.first }}", quiet >= 0.90)
    }

    @Test fun neverMoreThanThree() {
        for ((q, _) in SkillLibrary.lines) assertTrue(q, index.pick(q).size <= SkillSelect.MAX)
        // Every skill named at once still gives 3.
        assertEquals(3, index.pick(lib.values.joinToString(" ") { it.name }).size)
    }

    @Test fun theSameRuleWithOneSkillOrThree() {
        // BM25's idf alone would make one skill's words score 0.29: the background keeps the threshold's meaning.
        val home = lib.getValue("home")
        assertEquals(listOf(home.id), SkillSelect.Index(listOf(home)).pick("I'm going home").map { it.id })
        assertEquals(emptyList<Skill>(), SkillSelect.Index(listOf(home)).pick("What's the capital of Peru?"))
        val three = listOf(home, lib.getValue("pasta"), lib.getValue("team"))
        assertEquals(lib.getValue("team").id, SkillSelect.Index(three).pick("tell the team standup moved").first().id)
        assertEquals(lib.getValue("pasta").id, SkillSelect.Index(three).pick("je fais des pâtes").first().id)
        // And with 100: the 20, and 80 more one-step timers named after places.
        val more = (1..80).map { SkillFixtures.simple("s-more%02d".format(it), "Zone $it") }
        val hundred = SkillSelect.Index(lib.values.toList() + more)
        assertEquals(home.id, hundred.pick("I'm going home").first().id)
        assertEquals(emptyList<Skill>(), hundred.pick("What's the capital of Peru?"))
    }

    @Test fun offSkillsAreNeverDescribed() {
        val off = lib.getValue("pasta").copy(level = SkillLevel.OFF)
        assertEquals(emptyList<Skill>(), SkillSelect.Index(listOf(off)).pick("start the pasta"))
        assertEquals(emptyList<Skill>(), SkillSelect.Index(listOf(off)).pick("start the pasta", named = off))
    }

    @Test fun aNamedSkillTheRouterCouldntReadIsAlwaysFirst() {
        val tea = lib.getValue("tea")
        val picked = index.pick("pasta for eight and a half minutes please", named = tea)
        assertEquals(tea.id, picked.first().id)
        assertTrue(picked.size <= SkillSelect.MAX)
    }

    /** « thé » folds to "the", an English stop word: a skill named « Thé » is still found, and "the" never finds it. */
    @Test fun aSkillNamedTheIsFoundAndTheEnglishWordIsNot() {
        val the = SkillFixtures.simple("s-the", "Thé")
        val one = SkillSelect.Index(listOf(the))
        assertEquals(listOf(the.id), one.pick("thé").map { it.id })
        assertEquals(listOf(the.id), one.pick("je me fais un thé").map { it.id })
        val all = SkillSelect.Index(lib.values.toList() + the)
        assertTrue(the.id in all.pick("je me fais un thé").map { it.id })
        for (q in listOf("turn off the lights", "what's the time", "the", "steep the tea")) {
            assertTrue(q, the.id !in one.pick(q).map { it.id })
            assertTrue(q, the.id !in all.pick(q).map { it.id })
        }
    }

    /** SK12's lines: "I'm going home now" finds Heading home among 20 skills; Peru finds none. */
    @Test fun sk12() {
        assertEquals(lib.getValue("home").id, index.pick("I'm going home now").first().id)
        assertEquals(emptyList<Skill>(), index.pick("What's the capital of Peru?"))
    }
}
