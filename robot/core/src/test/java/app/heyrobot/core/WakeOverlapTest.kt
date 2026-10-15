// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wake phrases inside other wake phrases (0.3.0 on the phone: with "robot" on, turning "hey robot" off didn't stop
 * "hey robot" from waking Robot, because the keyword model fires "robot" at the end of it).
 */
class WakeOverlapTest {
    private val heyRobot = WakePhrase(1, "hey robot")
    private val robot = WakePhrase(2, "robot", WakeSensitivity.LOW)
    private val heyComputer = WakePhrase(3, "hey computer")

    @Test fun aPhraseInsideAnotherIsFound() {
        assertEquals(listOf(robot to heyRobot), WakePhrases.inside(listOf(heyRobot, robot, heyComputer)))
        // The longer one off: it still counts, that is the whole point.
        val off = heyRobot.copy(enabled = false)
        assertEquals(listOf(robot to off), WakePhrases.inside(listOf(off, robot, heyComputer)))
        // The inner one off: saying the longer one can't fire it.
        assertEquals(emptyList<Pair<WakePhrase, WakePhrase>>(), WakePhrases.inside(listOf(heyRobot, robot.copy(enabled = false))))
    }

    @Test fun onlyWholeWordsInARowCount() {
        // "hey computer" and "hey robot" share a word, but neither is inside the other.
        assertEquals(emptyList<Pair<WakePhrase, WakePhrase>>(), WakePhrases.inside(listOf(heyRobot, heyComputer)))
        // Part of a word isn't a word: "rob" in "hey robot", "robot" in "hey robots".
        assertEquals(emptyList<Pair<WakePhrase, WakePhrase>>(), WakePhrases.inside(listOf(heyRobot, WakePhrase(4, "rob"))))
        assertEquals(emptyList<Pair<WakePhrase, WakePhrase>>(), WakePhrases.inside(listOf(WakePhrase(5, "hey robots"), robot)))
        // Not in a row: "ok robot" in "ok hey robot".
        assertEquals(emptyList<Pair<WakePhrase, WakePhrase>>(), WakePhrases.inside(listOf(WakePhrase(6, "ok hey robot"), WakePhrase(7, "ok robot"))))
        // At the start or in the middle counts too (a pause after it is enough), and case and apostrophes don't matter.
        val inner = WakePhrase(8, "Hey Robot")
        val outer = WakePhrase(9, "hey robot please")
        assertEquals(listOf(inner to outer), WakePhrases.inside(listOf(inner, outer)))
        assertEquals(listOf(WakePhrase(10, "robot's") to WakePhrase(11, "hey robot’s turn")),
            WakePhrases.inside(listOf(WakePhrase(10, "robot's"), WakePhrase(11, "hey robot’s turn"))))
        // A phrase inside two others.
        val ok = WakePhrase(12, "ok robot")
        assertEquals(listOf(robot to heyRobot, robot to ok), WakePhrases.inside(listOf(heyRobot, robot, ok)))
    }

    @Test fun theCardsSaySo() {
        // Both on is fine: saying "hey robot" fires "hey robot" itself. No warning, only a plain note on the shorter one.
        val pairs = WakePhrases.inside(listOf(heyRobot, robot, heyComputer))
        assertNull(WakePhrases.insideWarning(heyRobot, pairs))
        assertEquals("Also hears “hey robot”: while this is on, saying it wakes Robot even with it off.", WakePhrases.insideNote(robot, pairs))
        // The longer one off while the shorter one is on: it doesn't do what its switch says.
        val off = heyRobot.copy(enabled = false)
        val offPairs = WakePhrases.inside(listOf(off, robot))
        assertEquals("“hey robot” is off, but “robot” is on and still hears it. Turn off “robot” too.", WakePhrases.insideWarning(off, offPairs))
        assertEquals("Also hears “hey robot”: while this is on, saying it wakes Robot even with it off.", WakePhrases.insideNote(robot, offPairs))
        assertNull(WakePhrases.insideWarning(robot, pairs))
        assertNull(WakePhrases.insideWarning(heyComputer, pairs))
        assertNull(WakePhrases.insideNote(heyRobot, pairs))
        val outer = WakePhrase(1, "hey there robot", enabled = false)
        val two = WakePhrases.inside(listOf(outer, WakePhrase(2, "robot"), WakePhrase(3, "there robot")))
        assertEquals("“hey there robot” is off, but “robot” and “there robot” are on and still hear it. Turn them off too.", WakePhrases.insideWarning(outer, two))
        assertEquals("Also hears “hey there robot” and “there robot”: while this is on, saying them wakes Robot even with them off.",
            WakePhrases.insideNote(WakePhrase(2, "robot"), two))
    }

    /**
     * Words stand for tokens: in the keyword model's own spellings (sherpa-onnx's text2token, the test resource), one
     * phrase's words come inside another's exactly when its tokens do, aligned on whole words.
     */
    @Test fun wordsInsideMeansTokensInside() {
        val text = javaClass.classLoader!!.getResourceAsStream("kws/text2token-reference.tsv")!!.readBytes().toString(Charsets.UTF_8)
        val ref = text.lines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { l -> l.split('\t').let { (p, t) -> p to t.split(' ') } }
        // A phrase's tokens split into words at each "▁".
        fun byWord(t: List<String>): List<List<String>> = t.fold(mutableListOf<MutableList<String>>()) { acc, x ->
            if (x.startsWith("▁") || acc.isEmpty()) acc += mutableListOf(x) else acc.last() += x
            acc
        }
        val phrases = ref.mapIndexed { i, (p, _) -> WakePhrase(i + 1, p) }
        val found = WakePhrases.inside(phrases).map { it.first.id to it.second.id }.toSet()
        var pairs = 0
        for ((i, a) in ref.withIndex()) for ((j, b) in ref.withIndex()) {
            if (i == j) continue
            val aw = byWord(a.second)
            val bw = byWord(b.second)
            val tokensInside = bw.size > aw.size && bw.windowed(aw.size).any { it == aw }
            assertEquals("${a.first} in ${b.first}", tokensInside, (i + 1 to j + 1) in found)
            if (tokensInside) pairs++
        }
        // The reference has such pairs ("ROBOT" in "HEY ROBOT"), so the check checks something.
        assertTrue("$pairs pairs", pairs >= 5)
    }
}
