package app.heyrobot.core.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RetrievalTest {
    private class Question(val kind: String, val gold: List<String>, val text: String)

    /** The design's bilingual set (§A6.2), in core/src/test/resources/memory. */
    private val set: Pair<List<Pair<String, String>>, List<Question>> by lazy {
        val lines = javaClass.classLoader!!.getResourceAsStream("memory/bilingual-set.tsv")!!.readBytes().toString(Charsets.UTF_8).lines()
        val items = lines.filter { it.startsWith("item\t") }.map { it.split('\t').let { p -> p[1] to p[2] } }
        val qs = lines.filter { it.startsWith("question\t") }.map { it.split('\t').let { p -> Question(p[1], p[2].split(',').filter { g -> g.isNotEmpty() }, p[3]) } }
        items to qs
    }

    @Test fun foldingAndTokens() {
        assertEquals("helene's oeuvre", MemoryText.fold("Hélène’s Œuvre"))
        assertEquals(MemoryText.tokens("Hélène"), MemoryText.tokens("HELENE"))
        // French elisions and English possessives.
        assertEquals(MemoryText.tokens("anniversaire"), MemoryText.tokens("l'anniversaire"))
        assertEquals(MemoryText.tokens("Hélène"), MemoryText.tokens("d’Hélène"))
        assertEquals(MemoryText.tokens("Julie"), MemoryText.tokens("Julie's"))
        assertEquals(emptyList<String>(), MemoryText.tokens("qu'il"))
        assertEquals(listOf("aujourdhui"), MemoryText.tokens("aujourd'hui"))
        // Stop words in both languages.
        assertEquals(emptyList<String>(), MemoryText.tokens("What is the, and est-ce que c'est le"))
        // "May" the month is kept.
        assertTrue(MemoryText.tokens("May 3").containsAll(listOf("may", "3")))
    }

    @Test fun lightStemming() {
        fun same(a: String, b: String) = assertEquals("$a / $b", MemoryText.tokens(a), MemoryText.tokens(b))
        same("birthdays", "birthday")
        same("journaux", "journal")
        same("chevaux", "cheval")
        same("gâteaux", "gâteau")
        same("jeux", "jeu")
        same("dimanches", "dimanche")
        same("watches", "watch")
        same("allergies", "allergy")
        same("allergie", "allergy")
        same("jeudis", "jeudi")
        same("amie", "ami")
        assertEquals(listOf("boss"), MemoryText.tokens("boss"))
        assertEquals(listOf("bus"), MemoryText.tokens("bus"))
        assertEquals(listOf("p3"), MemoryText.tokens("P3"))
        assertEquals(listOf("10", "5"), MemoryText.tokens("10.5"))
    }

    private val notes = listOf(
        "a" to "My sister Julie's birthday is May 3",
        "b" to "Léo has hockey on Tuesdays and Thursdays",
        "c" to "Marc is allergic to peanuts",
        "d" to "Le code postal du chalet est J0E 1A0",
        "e" to "Hélène aime le café noir",
    )

    @Test fun bm25RanksSharedWords() {
        val idx = Bm25Index.build(notes)
        assertEquals(5, idx.size)
        assertEquals("a", idx.search("When is Julie's birthday?").first().id)
        assertEquals("b", idx.search("hockey schedule").first().id)
        assertEquals("e", idx.search("est-ce que helene aime le cafe?").first().id)
        assertEquals("a", idx.search("Quand est la fête de Julie?").first().id)
        assertEquals("d", idx.search("what is the postal code at the chalet").first().id)
        assertEquals(setOf("chalet"), idx.search("chalet").first().matched)
        // Nothing shared: nothing found. And the other language without a shared word is a known miss (the
        // embedding model covers it).
        assertTrue(idx.search("anything about the dog?").isEmpty())
        assertTrue(idx.search("what time is it").isEmpty())
        assertTrue(idx.search("C'est quand la fête de ma sœur?").none { it.id == "a" })
        assertTrue(idx.search("").isEmpty())
        assertTrue(Bm25Index.build(emptyList()).search("Julie").isEmpty())
        // Scores are positive, best first, and the floor filters.
        val hits = idx.search("Julie hockey")
        assertTrue(hits.all { it.score > 0 })
        assertEquals(hits.sortedByDescending { it.score }, hits)
        assertTrue(idx.search("Julie", minScore = 100.0).isEmpty())
    }

    @Test fun tiesKeepTheGivenOrder() {
        val idx = Bm25Index.build(listOf("new" to "the cottage key", "old" to "the cottage key"))
        assertEquals(listOf("new", "old"), idx.search("cottage").map { it.id })
    }

    /**
     * The design's bilingual set: BM25 alone finds the right item first for every question that shares a key word,
     * and seldom across languages (design §A6.2: 26/48 right first with the x86 script's tokenizer, 6/20 across
     * languages). This tokenizer finds 28/48 first (15/15 key word, 6/20 other language); the floors catch a regression.
     */
    @Test fun goldenRanksOnTheBilingualSet() {
        val (items, qs) = set
        assertEquals(40, items.size)
        assertEquals(50, qs.size)
        val idx = Bm25Index.build(items)
        val answerable = qs.filter { it.gold.isNotEmpty() }
        fun at1(q: Question) = idx.search(q.text, 3).firstOrNull()?.id in q.gold
        fun at3(q: Question) = idx.search(q.text, 3).any { it.id in q.gold }
        val first = answerable.count(::at1)
        val top3 = answerable.count(::at3)
        val kw = answerable.filter { it.kind == "kw" }
        val xl = answerable.filter { it.kind == "xl" }
        assertEquals(48, answerable.size)
        assertTrue("right first $first/48", first >= 28)
        assertTrue("top 3 $top3/48", top3 >= first)
        assertEquals(15, kw.size)
        assertEquals("key word", 15, kw.count(::at1))
        assertEquals(20, xl.size)
        assertTrue("other language ${xl.count(::at1)}/20", xl.count(::at1) >= 6)
        // The two questions with no answer find nothing through the floor Robot sends with.
        val r = Bm25Retriever()
        val memory = items.map { (id, text) -> MemoryItem(id, text, createdAt = 0) }
        assertTrue(r.retrieve(RecallQuery("Is there anything about the dog?"), memory, 6).isEmpty())
    }

    private fun mem(id: String, text: String, at: Long = 0) = MemoryItem(id, text, createdAt = at)

    @Test fun bm25RetrieverCapsAndFloors() {
        val items = (1..10).map { mem("i$it", "Julie note $it", it.toLong()) } + (11..40).map { mem("o$it", "Other thing number $it", it.toLong()) }
        val r = Bm25Retriever()
        val got = r.retrieve(RecallQuery("Julie"), items, 6)
        assertEquals(Bm25Retriever.MAX_HITS, got.size)
        assertTrue(got.all { it.by == RecallBy.WORDS })
        // Newest first among equals.
        assertEquals("i10", got.first().item.id)
        assertTrue(r.retrieve(RecallQuery("Julie"), emptyList(), 6).isEmpty())
        assertTrue(r.retrieve(RecallQuery("Julie"), items, 0).isEmpty())
    }

    @Test fun vectors() {
        val v = Vectors.matryoshka(floatArrayOf(3f, 4f, 100f), 2)!!
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
        assertNull(Vectors.matryoshka(floatArrayOf(1f), 2))
        assertNull(Vectors.matryoshka(floatArrayOf(0f, 0f, 1f), 2))
        assertNull(Vectors.matryoshka(floatArrayOf(Float.NaN, 1f), 2))
        assertEquals(0.0, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-9)
        assertEquals(1.0, Vectors.cosine(floatArrayOf(2f, 0f), floatArrayOf(5f, 0f)), 1e-9)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 1e-9)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 1e-9)
        assertTrue(abs(Vectors.dot(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)) - 11.0) < 1e-9)
    }

    /** A fake model: each text maps to a direction; similar meanings share most of it. */
    private fun vec(vararg x: Float) = x

    @Test fun embeddingRetrieverUsesCosineAndAFloor() {
        val index = EmbeddingIndex("test-model", dim = 3)
        val birthday = mem("b", "My sister's birthday is May 3")
        val peanuts = mem("p", "Mom is allergic to peanuts")
        val parking = mem("k", "I parked on P3")
        assertTrue(index.put("b", vec(1f, 0.1f, 0f)))
        assertTrue(index.put("p", vec(0f, 1f, 0.1f)))
        assertFalse(index.put("short", vec(1f, 0f)))
        assertEquals(2, index.size)
        assertEquals(listOf(parking), index.missing(listOf(birthday, peanuts, parking)))
        val r = EmbeddingRetriever(index)
        // "C'est quand la fête de ma sœur?": close to the birthday item, in another language.
        val got = r.retrieve(RecallQuery("C'est quand la fête de ma sœur?", vec(0.9f, 0.2f, 0f)), listOf(birthday, peanuts, parking), 6)
        assertEquals(listOf("b"), got.map { it.item.id })
        assertEquals(RecallBy.EMBEDDING, got[0].by)
        assertTrue(got[0].score >= SearchModel.MIN_SCORE)
        // No vector (the model isn't downloaded): nothing from this side.
        assertTrue(r.retrieve(RecallQuery("anything"), listOf(birthday), 6).isEmpty())
        index.retain(setOf("p"))
        assertNull(index.get("b"))
    }

    @Test fun hybridAddsNamesAndNumbersTheEmbeddingMissed() {
        val index = EmbeddingIndex("test-model", dim = 2)
        val parking = mem("k", "I parked on level P3 at the Complexe Desjardins")
        val car = mem("c", "The car is a grey Honda Civic")
        index.put("c", vec(1f, 0f))
        index.put("k", vec(0f, 1f))
        val hybrid = HybridRetriever(EmbeddingRetriever(index))
        // The question's vector points at the car; "P3" is a number the embedding blurs: BM25 adds the parking item.
        val got = hybrid.retrieve(RecallQuery("Is the car still on P3?", vec(1f, 0f)), listOf(parking, car), 6)
        assertEquals(listOf("c", "k"), got.map { it.item.id })
        assertEquals(RecallBy.WORDS, got[1].by)
        // Both found it: marked both.
        val both = hybrid.retrieve(RecallQuery("Where is the Honda Civic?", vec(1f, 0f)), listOf(parking, car), 6)
        assertEquals(RecallBy.BOTH, both.first { it.item.id == "c" }.by)
        // Without a vector, words only.
        assertTrue(hybrid.retrieve(RecallQuery("P3"), listOf(parking, car), 6).all { it.by == RecallBy.WORDS })
    }

    @Test fun strongTokensAreNamesAndNumbers() {
        assertTrue("juli" in HybridRetriever.strongTokens("When is Julie's birthday?") || "julie" in HybridRetriever.strongTokens("When is Julie's birthday?"))
        assertEquals(setOf("p3"), HybridRetriever.strongTokens("Where did I park? On P3"))
        assertTrue(HybridRetriever.strongTokens("When is it? Where is it?").isEmpty())
        assertTrue(HybridRetriever.strongTokens("I think so").isEmpty())
    }

    @Test fun embedderSeamAndPrefixes() {
        assertEquals("task: search result | query: où est l'auto?", EmbedRole.QUERY.input("où est l'auto?"))
        assertEquals("title: none | text: I parked on P3", EmbedRole.DOCUMENT.input("I parked on P3"))
        val fake = object : TextEmbedder {
            override suspend fun embed(texts: List<String>, role: EmbedRole) = texts.map { floatArrayOf(it.length.toFloat(), 1f) }
        }
        assertEquals(2, runBlocking { fake.embed(listOf("a", "bb"), EmbedRole.DOCUMENT) }!!.size)
        assertEquals(256, SearchModel.DIM)
        assertEquals(64, SearchModel.SHA256.length)
    }
}
