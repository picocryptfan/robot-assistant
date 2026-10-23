// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * files-1.4-plan.md §4.5, job F8: a filing rule never goes into a prompt. Not retrieved for a question that names its
 * words, not pinned even when a file says it is, not found by "what do you remember", "what do you know about me" or the
 * model's forget (each puts an item's words in a reply the model reads next), and not in a rebuilt conversation's notes.
 */
class FilingRuleRecallTest {
    private val t = 1_760_184_000_000L
    private val rule = MemoryItem("m_00mgk5x9crule01", "Esso receipts go in Car", source = MemorySource.SAID, createdAt = t,
        filing = MemoryFiling(kind = "RECEIPT", merchant = "Esso", drawer = "Car"))
    /** As if a file said so: pinned, though the codec and MemoryEdits never let a rule be. */
    private val pinnedRule = rule.copy(id = "m_00mgk5x9crule02", text = "Costco receipts go in Groceries", pinned = true,
        filing = MemoryFiling(kind = "RECEIPT", merchant = "Costco", drawer = "Groceries"))
    private val fact = MemoryItem("m_00mgk5x9cfact01", "I fill up at Esso on the way to work", createdAt = t)
    private val file = MemoryFile(listOf(rule, pinnedRule, fact))
    private val ruleIds = setOf(rule.id, pinnedRule.id)

    @Test fun aQuestionNeverGetsARule() {
        for (q in listOf("where do Esso receipts go?", "Esso receipts", "what about Costco receipts in Groceries", "Esso Car receipts go in")) {
            val r = MemoryRecall.forTurn(q, file, MemorySettings(), locked = false, now = t + 1)
            assertTrue(q, r.ids.none { it in ruleIds })
            assertFalse(q, (r.packed.systemLine ?: "").contains("Costco"))
            assertFalse(q, (r.packed.notesBlock ?: "").contains("Car"))
        }
        // The fact that shares the words still comes.
        assertTrue(fact.id in MemoryRecall.forTurn("where do I fill up on the way to work", file, MemorySettings(), locked = false, now = t + 1).ids)
        // Only rules: nothing at all.
        assertEquals(PackedMemory.NONE, MemoryRecall.forTurn("Esso receipts", MemoryFile(listOf(rule, pinnedRule)), MemorySettings(), false, t + 1).packed)
    }

    @Test fun neverPinnedNeverANote() {
        assertNull(PromptPacking.pinnedLine(listOf(pinnedRule), t + 1).first)
        assertNull(PromptPacking.notesFor(listOf(rule.id, pinnedRule.id), file.items, t + 1))
        val ranked = listOf(Recalled(rule, 9.0, RecallBy.WORDS), Recalled(pinnedRule, 9.0, RecallBy.WORDS))
        val packed = PromptPacking.pack("Esso receipts", file.items, ranked, t + 1, unlocked = true)
        assertTrue(packed.retrievedIds.isEmpty() && packed.pinnedIds.isEmpty())
    }

    @Test fun memorysCommandsNeverFindARule() {
        assertTrue(MemorySearch.find("Esso receipts", file.items, t + 1).none { it.id in ruleIds })
        assertTrue(MemorySearch.clear("Esso receipts go in Car", file.items, t + 1).none { it.id in ruleIds })
        assertTrue(MemorySearch.clear("Costco receipts", file.items, t + 1).isEmpty())
        val used = file.copy(items = file.items.map { if (it.id in ruleIds) it.copy(uses = 99) else it })
        assertEquals(listOf(fact.id), MemorySearch.examples(used.items, t + 1).map { it.id })
        assertEquals(listOf(fact), MemoryEdits.forQuestions(file.items, t + 1))
    }

    /** Adding: a fact, never pinned; the newer rule with the same match replaces the older one, and Undo brings it back. */
    @Test fun addingARule() {
        val a = MemoryEdits.addRule(MemoryFile(listOf(fact)), rule.copy(kind = MemoryKind.PREFERENCE, pinned = true))
        assertEquals(AddOutcome.SAVED, a.outcome)
        assertEquals(MemoryKind.FACT, a.item!!.kind)
        assertFalse(a.item!!.pinned)
        // The same rule again (other words for it): already kept.
        assertEquals(AddOutcome.ALREADY_KEPT, MemoryEdits.addRule(a.file, rule.copy(id = "m_00mgk5x9crule09", text = "ESSO receipts go in Car")).outcome)
        // Another drawer for the same match replaces it; Undo puts the old one back.
        val travel = rule.copy(id = "m_00mgk5x9crule03", text = "Esso receipts go in Travel", createdAt = t + 5,
            filing = MemoryFiling(kind = "RECEIPT", merchant = "ESSO", drawer = "Travel"))
        val b = MemoryEdits.addRule(a.file, travel)
        assertEquals(listOf(rule.id), b.replaced.map { it.id })
        assertEquals(listOf(fact.id, travel.id), b.file.items.map { it.id })
        assertEquals(setOf(fact.id, rule.id), MemoryEdits.undoRule(b.file, travel.id, b.replaced).items.map { it.id }.toSet())
        // A tag rule for the same match sits beside the drawer rule; the same tags again are already kept.
        val tag = rule.copy(id = "m_00mgk5x9crule04", text = "Esso receipts get the tag car", filing = MemoryFiling(kind = "RECEIPT", merchant = "Esso", tags = listOf("car"), learned = true))
        val c = MemoryEdits.addRule(b.file, tag)
        assertEquals(AddOutcome.SAVED, c.outcome)
        assertTrue(c.replaced.isEmpty())
        assertEquals(AddOutcome.ALREADY_KEPT, MemoryEdits.addRule(c.file, tag.copy(id = "m_00mgk5x9crule05")).outcome)
        // A rule from content (an import) is never replaced by yours, nor taken for it.
        val theirs = rule.copy(id = "m_00mgk5x9crule06", imported = MemoryImported.CONTENT)
        assertEquals(AddOutcome.SAVED, MemoryEdits.addRule(MemoryFile(listOf(theirs)), rule).outcome)
        // Full memory: refused, nothing changed.
        val full = MemoryFile((0 until MemoryLimits.MAX_ITEMS).map { MemoryItem("m_" + it.toString(36).padStart(9, '0') + "aaaaaa", "fact $it", createdAt = t) })
        val f = MemoryEdits.addRule(full, rule)
        assertEquals(AddOutcome.FULL, f.outcome)
        assertEquals(full, f.file)
        // A rule with nothing to match or nothing to do isn't one.
        assertEquals(AddOutcome.EMPTY, MemoryEdits.addRule(MemoryFile(), rule.copy(filing = MemoryFiling(drawer = "Car"))).outcome)
    }
}
