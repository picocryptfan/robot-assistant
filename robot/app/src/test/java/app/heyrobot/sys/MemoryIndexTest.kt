// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.assistant.MeaningMemory
import app.heyrobot.core.memory.Bm25Retriever
import app.heyrobot.core.memory.MeaningIndex
import app.heyrobot.core.memory.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Search by meaning's vectors for memory: sealed with memory's key, unreadable otherwise, deleted with memory. */
class MemoryIndexTest {
    private val dir: File = Files.createTempDirectory("memory-index").toFile()
    private val vault = FileMemoryVault(dir, SoftMemoryKeys())
    private fun index() = MeaningIndex(dim = 2).apply { put("1", "my favourite food is pho", floatArrayOf(1f, 0f)) }

    @Test fun sealedWithMemorysKeyAndDeletedWithIt() {
        // No memory yet: nothing to seal with, so nothing is written.
        assertFalse(vault.writeIndex(index()))
        assertNull(vault.readIndex())
        vault.update { f -> f.copy(items = f.items + MemoryItem("1", "my favourite food is pho", createdAt = 0)) }
        val idx = MeaningIndex().apply { put("1", "my favourite food is pho", FloatArray(300) { if (it == 0) 1f else 0f }) }
        assertTrue(vault.writeIndex(idx))
        val f = File(dir, FileMemoryVault.INDEX)
        assertTrue(f.isFile)
        // Sealed: the id isn't in the file as plain text.
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("RVEC"))
        assertEquals(setOf("1"), vault.readIndex()!!.ids)
        // Damaged: not trusted (made again from memory).
        f.writeBytes(f.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(vault.readIndex())
        assertTrue(vault.writeIndex(idx))
        assertTrue(vault.deleteAll())
        assertFalse(f.exists())
        assertNull(vault.readIndex())
    }

    @Test fun privateItemsGetNoVectorAndNoModelMeansWords() {
        val items = listOf(MemoryItem("1", "pho", createdAt = 0), MemoryItem("2", "PIN 1234", createdAt = 0, secret = true))
        assertEquals(listOf("1"), MeaningMemory.docs(items).map { it.id })
        val (r, v) = MeaningMemory.forQuestion("what do I like to eat?", items)
        assertTrue(r is Bm25Retriever)
        assertNull(v)
    }
}
