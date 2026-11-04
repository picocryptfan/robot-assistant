// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Test Bench v70-mem-remember / bug-memory-recall (1.2.2): "remember my sister's birthday is May 3" was saved, but in a
 * new conversation "when is my sister's birthday?" got no note, so the model said to check the calendar. With one item in
 * memory every word's BM25 weight was ln(4/3), the two shared words scored 0.58, under Bm25Retriever's floor of 1.0.
 * Every earlier recall test had four items or more.
 */
class SmallMemoryRecallTest {
    private val utc = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 11, 4).atStartOfDay(utc).toInstant().toEpochMilli()
    private val on = MemorySettings()

    /** What "remember …" saves, through the same parser the panel uses. */
    private fun saved(said: String, id: String): MemoryItem {
        val c = MemoryCommands.parse(said) as MemoryCommand.Remember
        return MemoryItem(id, c.text, c.kind, createdAt = now - 60_000)
    }

    private fun notes(question: String, vararg items: MemoryItem): String? =
        MemoryRecall.forTurn(question, MemoryFile(items.toList()), on, locked = false, now = now, zone = utc).packed.notesBlock

    @Test fun oneSavedFactIsFoundInANewConversation() {
        val sister = saved("remember my sister's birthday is May 3", "m_fact00000001")
        for (q in listOf("When is my sister's birthday?", "what's my sister's birthday", "when's my sister's birthday")) {
            val block = notes(q, sister)
            assertNotNull(q, block)
            assertTrue(q, block!!.contains(sister.text))
        }
    }

    @Test fun oneSavedFactIsFoundInFrench() {
        val soeur = saved("souviens-toi que l'anniversaire de ma sœur est le 3 mai", "m_fact00000002")
        assertTrue(notes("C'est quand l'anniversaire de ma sœur ?", soeur)!!.contains(soeur.text))
        val julie = saved("souviens-toi que Julie aime les tulipes", "m_fact00000003")
        assertTrue(notes("Qu'est-ce que Julie aime ?", julie)!!.contains(julie.text))
    }

    @Test fun twoOrThreeItemsToo() {
        val sister = saved("remember my sister's birthday is May 3", "m_fact00000001")
        val julie = saved("souviens-toi que Julie aime les tulipes", "m_fact00000003")
        val car = saved("remember my car is parked on level 2", "m_fact00000004")
        val block = notes("When is my sister's birthday?", sister, julie, car)!!
        assertTrue(block.contains(sister.text))
        assertTrue(!block.contains(car.text) && !block.contains(julie.text))
    }

    @Test fun aQuestionSharingNoWordStillBringsNothing() {
        val sister = saved("remember my sister's birthday is May 3", "m_fact00000001")
        for (q in listOf("what time is it", "Tell me a joke", "Is there anything about the dog?", "quelle heure est-il")) assertNull(q, notes(q, sister))
    }

    @Test fun fromTwentyItemsOnTheWeightsAreThePlainOnes() {
        val docs = (1..25).map { "d$it" to "note number $it about Julie" }
        val a = Bm25Index.build(docs).search("Julie")
        val b = Bm25Index.build(docs, minDocs = Bm25Retriever.MIN_DOCS).search("Julie")
        assertEquals(a, b)
    }
}
