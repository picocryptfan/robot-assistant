package app.heyrobot.core.memory

import kotlin.math.sqrt

// Pure. Which remembered items a question needs (design §A6). Two ways, behind one interface: BM25 over the words
// (always there), and the on-device embedding model when you download it (better search: paraphrase and the other
// language). Here the embedding side is the maths only, cosine over FloatArray vectors; computing a vector is :brain's
// job later (the LiteRT-LM embedding engine behind a REQ_EMBED request), so [TextEmbedder] is only the seam. The
// retrievers rank; what goes into a prompt (no secrets unless named, no expired items, nothing already given, the
// token budget) is PromptPacking's.

/** A question to search with: its words, and its vector when the embedding model is there (null: words only). */
class RecallQuery(val text: String, val vector: FloatArray? = null)

/** How an item was found (the voice log's retriever code later: 1 words, 2 embedding, 3 both). */
enum class RecallBy(val code: Int) { WORDS(1), EMBEDDING(2), BOTH(3) }

data class Recalled(val item: MemoryItem, val score: Double, val by: RecallBy)

interface Retriever {
    /** The items of [items] that [query] may need, best first, at most [limit]. */
    fun retrieve(query: RecallQuery, items: List<MemoryItem>, limit: Int): List<Recalled>
}

/**
 * Words only (BM25). Without the embedding model Robot sends at most [maxHits] items scoring at least [minScore], so a
 * question that shares only a common word with an item ("what time is it") doesn't drag notes in.
 */
class Bm25Retriever(private val minScore: Double = MIN_SCORE, private val maxHits: Int = MAX_HITS) : Retriever {
    override fun retrieve(query: RecallQuery, items: List<MemoryItem>, limit: Int): List<Recalled> {
        if (items.isEmpty() || limit <= 0) return emptyList()
        val byId = items.associateBy { it.id }
        val index = Bm25Index.build(items.sortedByDescending { it.createdAt }.map { it.id to it.text })
        return index.search(query.text, minOf(limit, maxHits), minScore).mapNotNull { h -> byId[h.id]?.let { Recalled(it, h.score, RecallBy.WORDS) } }
    }

    /**
     * The raw hits with the terms each matched, for [HybridRetriever]'s exact-token boost: no floor there, since a hit
     * must hold a name or a number from the question to count.
     */
    fun hits(query: String, items: List<MemoryItem>, limit: Int, floor: Double = 0.0): List<Bm25Index.Hit> =
        Bm25Index.build(items.sortedByDescending { it.createdAt }.map { it.id to it.text }).search(query, limit, floor)

    companion object {
        /** BM25 scores under this share only weak words with the question. */
        const val MIN_SCORE = 1.0
        const val MAX_HITS = 4
    }
}

/**
 * The item vectors in RAM (index.enc, decrypted with the same key as memory.enc): one per item, from the model [model]
 * at [dim] dimensions. A vector of another size is refused, so a model change can't mix two spaces; the vault rebuilds
 * the index when the model's hash changes.
 */
class EmbeddingIndex(val model: String, val dim: Int = SearchModel.DIM) {
    private val vectors = LinkedHashMap<String, FloatArray>()

    val size: Int get() = vectors.size

    /** Keeps [v] (normalised, cut to [dim]); false when it is shorter than [dim] or all zero. */
    fun put(id: String, v: FloatArray): Boolean {
        val n = Vectors.matryoshka(v, dim) ?: return false
        vectors[id] = n
        return true
    }

    fun get(id: String): FloatArray? = vectors[id]
    fun remove(id: String) { vectors.remove(id) }

    /** Drops the vectors of items that are gone. */
    fun retain(ids: Set<String>) { vectors.keys.retainAll(ids) }

    /** The items still without a vector (new, or edited), to embed in one batch. */
    fun missing(items: List<MemoryItem>): List<MemoryItem> = items.filter { it.id !in vectors }
}

/** Cosine over the item vectors; items without one are not found this way (BM25 still finds them). */
class EmbeddingRetriever(private val index: EmbeddingIndex, private val minScore: Double = SearchModel.MIN_SCORE) : Retriever {
    override fun retrieve(query: RecallQuery, items: List<MemoryItem>, limit: Int): List<Recalled> {
        val q = query.vector?.let { Vectors.matryoshka(it, index.dim) } ?: return emptyList()
        if (limit <= 0) return emptyList()
        return items.mapNotNull { item ->
            val v = index.get(item.id) ?: return@mapNotNull null
            val s = Vectors.dot(q, v)
            if (s >= minScore) Recalled(item, s, RecallBy.EMBEDDING) else null
        }.sortedByDescending { it.score }.take(limit)
    }
}

/**
 * The embedding order, plus the BM25 hits that hold a name or a number from the question ("Julie", "P3", "1203") and
 * that the embedding missed: embeddings blur rare exact tokens (design §A6.1: fusion only as a boost). Without a
 * query vector, words only.
 */
class HybridRetriever(private val embedding: EmbeddingRetriever, private val words: Bm25Retriever = Bm25Retriever()) : Retriever {
    override fun retrieve(query: RecallQuery, items: List<MemoryItem>, limit: Int): List<Recalled> {
        if (query.vector == null) return words.retrieve(query, items, limit)
        val found = embedding.retrieve(query, items, limit).toMutableList()
        val strong = strongTokens(query.text)
        if (strong.isEmpty()) return found
        val byId = items.associateBy { it.id }
        for (h in words.hits(query.text, items, limit)) {
            if (h.matched.none { it in strong }) continue
            val i = found.indexOfFirst { it.item.id == h.id }
            if (i >= 0) found[i] = found[i].copy(by = RecallBy.BOTH)
            else byId[h.id]?.let { found += Recalled(it, h.score, RecallBy.WORDS) }
        }
        return found.take(limit)
    }

    companion object {
        /**
         * The search terms of [question] that are names or numbers: a word with a digit, or a capitalised word that
         * doesn't start the sentence ("I" aside).
         */
        fun strongTokens(question: String): Set<String> {
            val out = HashSet<String>()
            var sentenceStart = true
            for (m in Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*|[.!?]").findAll(question)) {
                val w = m.value
                if (w in setOf(".", "!", "?")) { sentenceStart = true; continue }
                val strong = w.any { it.isDigit() } || (!sentenceStart && w[0].isUpperCase() && w != "I")
                if (strong) out += MemoryText.tokens(w)
                sentenceStart = false
            }
            return out
        }
    }
}

object Vectors {
    fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in 0 until minOf(a.size, b.size)) s += a[i].toDouble() * b[i]
        return s
    }

    /** Cosine similarity; 0 when either vector is all zero or the sizes differ. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * The first [dim] values of [v], normalised to length 1 (Matryoshka: EmbeddingGemma's shorter vectors are the first
     * n values, normalised again). Null when [v] is shorter than [dim] or all zero (or not a number).
     */
    fun matryoshka(v: FloatArray, dim: Int): FloatArray? {
        if (dim <= 0 || v.size < dim) return null
        var n = 0.0
        for (i in 0 until dim) n += v[i].toDouble() * v[i]
        if (n == 0.0 || n.isNaN() || n.isInfinite()) return null
        val len = sqrt(n)
        return FloatArray(dim) { (v[it] / len).toFloat() }
    }
}

/** Which side of a search a text is on: EmbeddingGemma wants a prefix for each (the model card's, design §A6.2). */
enum class EmbedRole(val prefix: String) {
    QUERY("task: search result | query: "),
    DOCUMENT("title: none | text: ");

    fun input(text: String): String = prefix + text
}

/**
 * The seam to :brain (later): one batch of texts to vectors, or null when the model isn't loaded or failed. The
 * :voice side implements it over the brain binding (REQ_EMBED); tests use a fake.
 */
interface TextEmbedder {
    suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray>?
}

/**
 * Settings › Memory › "Better search": EmbeddingGemma 2 Text 270M (Apache-2.0), the CPU file, pinned by size and SHA-256
 * like the other models; checked before it is ever loaded. Assistant setup offers it once ([MemorySettings.searchModelOffer]).
 */
object SearchModel {
    const val FILE = "embeddinggemma-2-text-270m.litertlm"
    const val BYTES = 164_626_432L
    const val SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
    /** The size Robot asks the engine for (outputSize, normalize = true). */
    const val DIM = 256
    /** maxInputLength for an item; transcript chunks take [MAX_INPUT_CHUNK]. */
    const val MAX_INPUT = 512
    const val MAX_INPUT_CHUNK = 1_024
    /** The least cosine for an item to go into a prompt (no threshold separates "no answer" cleanly: the prompt says "only if they help"). */
    const val MIN_SCORE = 0.60
    const val TITLE = "Download the search model (165 MB, Google's EmbeddingGemma 2, Apache-2.0)"
}
