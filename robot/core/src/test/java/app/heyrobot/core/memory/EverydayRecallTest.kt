// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Test Bench everyday-1.2.2-a, bug 1: "what do I like to eat most" (saved: "my favourite food is pad thai") and "where's my
 * car" (saved: "I parked the car on level 3, row B") share no word, or only a weak one, with what was saved, so the word
 * lookup gave the model nothing and it said it had no access. While memory is small, a question about you takes it all.
 */
class EverydayRecallTest {
    private val utc = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 11, 4).atStartOfDay(utc).toInstant().toEpochMilli()
    private val on = MemorySettings()
    private var n = 0

    private fun saved(said: String): MemoryItem {
        val c = MemoryCommands.parse(said) as? MemoryCommand.Remember
        n++
        val text = c?.text ?: said.substringAfter("remember ").substringAfter("souviens-toi que ")
        return MemoryItem("m_fact%08d".format(n), text, c?.kind ?: MemoryKind.FACT, createdAt = now - 600_000 + n * 1000L)
    }

    private fun notes(question: String, items: List<MemoryItem>): String? =
        MemoryRecall.forTurn(question, MemoryFile(items), on, locked = false, now = now, zone = utc).packed.notesBlock

    private val everyday by lazy {
        listOf("remember Tom's birthday is June 12", "remember I parked the car on level 3, row B", "remember my favourite food is pad thai",
            "remember my doctor is Dr. Anika Patel", "remember my partner's favourite flower is the peony", "remember my mom's anniversary is August 20",
            "souviens-toi que mon dentiste s'appelle docteur Martin").map(::saved)
    }

    @Test fun otherWordsStillFindTheFactWhileMemoryIsSmall() {
        val food = saved("remember my favourite food is pad thai")
        val car = saved("remember I parked the car on level 3, row B")
        assertTrue(notes("what do I like to eat most", listOf(food))!!.contains("pad thai"))
        assertTrue(notes("where's my car", listOf(car))!!.contains("level 3"))
        notes("what do I like to eat most", everyday).let { assertTrue(it, it!!.contains("pad thai")) }
        assertTrue(notes("where's my car", everyday)!!.contains("level 3"))
        assertTrue(notes("qu'est-ce que je mange le plus", listOf(food))!!.contains("pad thai"))
    }

    @Test fun aQuestionNotAboutYouStillBringsNothing() {
        for (q in listOf("what time is it", "Tell me a joke", "quelle heure est-il", "what is the capital of Portugal", "how many people live there"))
            assertNull(q, notes(q, everyday))
    }

    @Test fun aBigMemoryStillGoesByWords() {
        val many = everyday + (1..20).map { saved("remember note number $it about the garden") }
        assertNull(notes("what do I like to eat most", many))
    }
}
