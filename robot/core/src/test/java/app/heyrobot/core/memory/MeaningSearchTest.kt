// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.SearchModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningSearchTest {
    private class Question(val kind: String, val gold: List<String>, val text: String)

    /** The design's bilingual set (memory-messages-design.md §A6.2): 40 items, 48 answerable questions, 20 across languages. */
    private val set: Pair<List<SearchDoc>, List<Question>> by lazy {
        val lines = javaClass.classLoader!!.getResourceAsStream("memory/bilingual-set.tsv")!!.readBytes().toString(Charsets.UTF_8).lines()
        val items = lines.filter { it.startsWith("item\t") }.map { it.split('\t').let { p -> SearchDoc(MeaningSource.MEMORY, p[1], p[2]) } }
        val qs = lines.filter { it.startsWith("question\t") }.map { it.split('\t').let { p -> Question(p[1], p[2].split(',').filter { g -> g.isNotEmpty() }, p[3]) } }
        items to qs
    }

    @Test fun pinnedSource() {
        assertEquals("https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/" +
            "9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm", SearchModel.URL)
        assertEquals(40, SearchModel.REVISION.length)
        assertEquals(64, SearchModel.SHA256.length)
    }

    /** No model on this PC (and none is ever committed): the 48 questions and the cross-language 20 run on words alone. */
    @Test fun bilingualSetWithWordsAlone() {
        val (docs, qs) = set
        assertEquals(40, docs.size)
        val answerable = qs.filter { it.gold.isNotEmpty() }
        assertEquals(48, answerable.size)
        fun top(q: Question) = MeaningSearch.search(q.text, null, docs, emptyMap(), 3, wordFloor = 0.0)
        fun at1(q: Question) = top(q).firstOrNull()?.doc?.id in q.gold
        val first = answerable.count(::at1)
        val kw = answerable.filter { it.kind == "kw" }
        val xl = answerable.filter { it.kind == "xl" }
        assertTrue("right first $first/48", first >= 26)
        assertEquals("key word", 15, kw.count(::at1))
        assertEquals(20, xl.size)
        assertTrue("other language ${xl.count(::at1)}/20", xl.count(::at1) >= 6)
        assertTrue(top(answerable[0]).all { it.by == RecallBy.WORDS })
        // The floor Robot searches with: a question with no answer finds nothing.
        assertTrue(MeaningSearch.search("Is there anything about the dog?", null, docs, emptyMap(), 6).isEmpty())
    }

    private fun doc(s: MeaningSource, id: String, text: String) = SearchDoc(s, id, text)

    @Test fun vectorsRankAcrossSourcesAndWordsAddNamesAndNumbers() {
        val pho = doc(MeaningSource.MEMORY, "1", "My favourite food is pho")
        val car = doc(MeaningSource.NOTES, "a", "Parked on level P3")
        val julie = doc(MeaningSource.FILES, "x", "Julie birthday card receipt")
        val mem = MeaningIndex(dim = 2).apply { put(pho.id, pho.text, floatArrayOf(1f, 0f)) }
        val notes = MeaningIndex(dim = 2).apply { put(car.id, car.text, floatArrayOf(0f, 1f)) }
        val files = MeaningIndex(dim = 2).apply { put(julie.id, julie.text, floatArrayOf(-1f, 0f)) }
        val idx = mapOf(MeaningSource.MEMORY to mem, MeaningSource.NOTES to notes, MeaningSource.FILES to files)
        val docs = listOf(pho, car, julie)
        val r = MeaningSearch.search("what do I like to eat?", floatArrayOf(0.9f, 0.1f), docs, idx, 6)
        assertEquals(listOf(pho), r.map { it.doc })
        assertEquals(RecallBy.EMBEDDING, r[0].by)
        // "Julie" is a name: the word hit is added though its vector points away.
        val j = MeaningSearch.search("what did I buy for Julie?", floatArrayOf(0.9f, 0.1f), docs, idx, 6)
        assertEquals(listOf(pho, julie), j.map { it.doc })
        assertEquals(RecallBy.WORDS, j[1].by)
        // A source without an index is found by words only.
        assertTrue(MeaningSearch.search("level", floatArrayOf(0f, 1f), docs, idx - MeaningSource.NOTES, 6).isEmpty())
        assertTrue(MeaningSearch.search("x", null, emptyList(), idx, 6).isEmpty())
        assertEquals("n:a", car.key)
    }

    @Test fun planEmbedsNewAndEditedAndDropsGone() {
        val idx = MeaningIndex(dim = 2)
        idx.put("1", "old text", floatArrayOf(1f, 0f))
        idx.put("2", "kept", floatArrayOf(0f, 1f))
        idx.put("3", "gone", floatArrayOf(0f, 1f))
        val docs = listOf(doc(MeaningSource.NOTES, "1", "new text"), doc(MeaningSource.NOTES, "2", "kept"), doc(MeaningSource.NOTES, "4", "fresh"))
        val p = idx.plan(docs)
        assertEquals(listOf("1", "4"), p.embed.map { it.id })
        assertEquals(setOf("3"), p.drop)
        assertFalse(p.nothing)
        assertFalse(idx.put("5", "zero", floatArrayOf(0f, 0f)))
        assertFalse(idx.put("5", "short", floatArrayOf(1f)))
    }

    @Test fun indexerBatchesAndStopsWhenTheModelGoes() = runBlocking {
        val idx = MeaningIndex(dim = 2)
        val docs = (1..5).map { doc(MeaningSource.LOGBOOK, "$it", "entry $it") }
        val calls = mutableListOf<Pair<Int, EmbedRole>>()
        var left = 2
        val embedder = object : TextEmbedder {
            override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray>? {
                calls += texts.size to role
                return if (left-- > 0) texts.map { floatArrayOf(1f, it.length.toFloat()) } else null
            }
        }
        assertEquals(4, MeaningIndexer.refresh(idx, docs, embedder, batch = 2))
        assertEquals(listOf(2 to EmbedRole.DOCUMENT, 2 to EmbedRole.DOCUMENT, 1 to EmbedRole.DOCUMENT), calls)
        assertEquals(listOf("5"), idx.plan(docs).embed.map { it.id })
        left = 1
        assertEquals(1, MeaningIndexer.refresh(idx, docs, embedder))
        assertTrue(idx.plan(docs).nothing)
        assertEquals(0, MeaningIndexer.refresh(idx, docs.drop(1), embedder))
        assertEquals(4, idx.size)
    }

    @Test fun codecRoundTripsAndRefusesOtherModelsAndDamage() {
        val idx = MeaningIndex("abc", 2)
        idx.put("one", "text one", floatArrayOf(3f, 4f))
        idx.put("two", "text two", floatArrayOf(0f, 2f))
        val bytes = VectorCodec.encode(idx)
        val back = VectorCodec.decode(bytes, "abc", 2)!!
        assertEquals(setOf("one", "two"), back.ids)
        assertEquals(0.6f, back.vector("one")!![0], 1e-6f)
        assertTrue(back.plan(listOf(doc(MeaningSource.MEMORY, "one", "text one"), doc(MeaningSource.MEMORY, "two", "text two"))).nothing)
        assertNull(VectorCodec.decode(bytes, "other", 2))
        assertNull(VectorCodec.decode(bytes, "abc", 3))
        assertNull(VectorCodec.decode(bytes.copyOf(bytes.size - 1), "abc", 2))
        assertNull(VectorCodec.decode(bytes + 0, "abc", 2))
        assertNull(VectorCodec.decode(ByteArray(3), "abc", 2))
        assertNull(VectorCodec.decode(null))
        assertEquals(0, VectorCodec.decode(VectorCodec.encode(MeaningIndex()))!!.size)
    }

    @Test fun settingsDefaultToWiFiOnly() {
        assertFalse(MeaningSettings().anyNetwork)
        assertFalse(MeaningSettings.fromJson(null).anyNetwork)
        assertFalse(MeaningSettings.fromJson("not json").anyNetwork)
        assertTrue(MeaningSettings.fromJson(MeaningSettings(anyNetwork = true).toJson()).anyNetwork)
        assertEquals("meaning-search.json", MeaningSettings.FILE)
    }
}
