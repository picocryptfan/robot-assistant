// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.SearchModel
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// Pure. Search by meaning (docs/plans/meaning-search-plan.md): one search for memory, notes, Robot Files and the
// Logbook. Each source keeps its own [MeaningIndex] beside its data, with the same protection (the app seals it where the
// source is sealed); this file is the maths, the plan of what to embed, and the file format. Word matching (BM25) is the
// fallback when there is no model or no query vector, and the boost for names and numbers the vectors blur.

/** Where a document comes from; [code] goes in its key and in the voice log. */
enum class MeaningSource(val code: String) { MEMORY("m"), NOTES("n"), FILES("f"), LOGBOOK("l") }

/** One searchable text: a memory item, a note, a file's searchable words, a Logbook entry. */
data class SearchDoc(val source: MeaningSource, val id: String, val text: String) {
    val key: String get() = "${source.code}:$id"
}

/** How a document was found, as [RecallBy]. */
data class Found(val doc: SearchDoc, val score: Double, val by: RecallBy)

/** A text's fingerprint (64-bit FNV-1a over UTF-8): an edited text gets a new vector. Not a secret, not a check. */
object MeaningText {
    fun hash(text: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (b in text.toByteArray(Charsets.UTF_8)) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h
    }
}

/**
 * One source's vectors, by document id: each with the hash of the text it was made from. Made by the model [modelSha]
 * at [dim] dimensions; a file from another model or size is refused ([VectorCodec.decode]), so two spaces never mix.
 */
class MeaningIndex(val modelSha: String = SearchModel.SHA256, val dim: Int = SearchModel.DIM) {
    class Entry(val hash: Long, val vector: FloatArray)

    private val entries = LinkedHashMap<String, Entry>()

    val size: Int get() = entries.size
    val ids: Set<String> get() = entries.keys

    /** Keeps [v] for [id] (normalised, cut to [dim]); false when it is too short or all zero. */
    fun put(id: String, text: String, v: FloatArray): Boolean = putHashed(id, MeaningText.hash(text), v)

    internal fun putHashed(id: String, hash: Long, v: FloatArray): Boolean {
        val n = Vectors.matryoshka(v, dim) ?: return false
        entries[id] = Entry(hash, n)
        return true
    }

    fun vector(id: String): FloatArray? = entries[id]?.vector
    internal fun hashOf(id: String): Long = entries[id]?.hash ?: 0L
    fun remove(id: String) { entries.remove(id) }
    fun clear() = entries.clear()

    /** What [docs] need: the ones without a vector or edited since, and the ids whose document is gone. */
    fun plan(docs: List<SearchDoc>): Plan {
        val live = docs.associateBy { it.id }
        val embed = docs.filter { d -> entries[d.id]?.hash != MeaningText.hash(d.text) }
        return Plan(embed, entries.keys.filter { it !in live }.toSet())
    }

    data class Plan(val embed: List<SearchDoc>, val drop: Set<String>) {
        val nothing: Boolean get() = embed.isEmpty() && drop.isEmpty()
    }
}

/**
 * Brings [index] level with [docs]: drops the gone, then embeds the rest [batch] texts at a time (document prefix).
 * Stops at the first null from [embedder] (the model gone or busy: words still work). Returns how many it embedded.
 */
object MeaningIndexer {
    const val BATCH = 16

    suspend fun refresh(index: MeaningIndex, docs: List<SearchDoc>, embedder: TextEmbedder, batch: Int = BATCH): Int {
        val plan = index.plan(docs)
        plan.drop.forEach(index::remove)
        var done = 0
        for (chunk in plan.embed.chunked(batch.coerceAtLeast(1))) {
            val vs = embedder.embed(chunk.map { it.text }, EmbedRole.DOCUMENT) ?: break
            if (vs.size != chunk.size) break
            chunk.forEachIndexed { i, d -> if (index.put(d.id, d.text, vs[i])) done++ }
        }
        return done
    }
}

/**
 * The shared search. With a query [vector]: cosine over each source's index, at least [minScore], best first, plus the
 * word hits that hold a name or a number from the question and that the vectors missed. Without one: words only, at
 * least [wordFloor]. [indexes] by source; a document without a vector is found by words only.
 */
object MeaningSearch {
    fun search(
        query: String,
        vector: FloatArray?,
        docs: List<SearchDoc>,
        indexes: Map<MeaningSource, MeaningIndex>,
        limit: Int,
        minScore: Double = SearchModel.MIN_SCORE,
        wordFloor: Double = Bm25Retriever.MIN_SCORE,
    ): List<Found> {
        if (limit <= 0 || docs.isEmpty()) return emptyList()
        val byKey = docs.associateBy { it.key }
        val words = Bm25Index.build(docs.map { it.key to it.text }, minDocs = Bm25Retriever.MIN_DOCS)
        if (vector == null) {
            return words.search(query, limit, wordFloor).mapNotNull { h -> byKey[h.id]?.let { Found(it, h.score, RecallBy.WORDS) } }
        }
        val found = docs.mapNotNull { d ->
            val idx = indexes[d.source] ?: return@mapNotNull null
            val q = Vectors.matryoshka(vector, idx.dim) ?: return@mapNotNull null
            val v = idx.vector(d.id) ?: return@mapNotNull null
            val s = Vectors.dot(q, v)
            if (s >= minScore) Found(d, s, RecallBy.EMBEDDING) else null
        }.sortedByDescending { it.score }.take(limit).toMutableList()
        val strong = HybridRetriever.strongTokens(query)
        if (strong.isEmpty()) return found
        for (h in words.search(query, limit)) {
            if (h.matched.none { it in strong }) continue
            val i = found.indexOfFirst { it.doc.key == h.id }
            if (i >= 0) found[i] = found[i].copy(by = RecallBy.BOTH)
            else byKey[h.id]?.let { found += Found(it, h.score, RecallBy.WORDS) }
        }
        return found.take(limit)
    }
}

/**
 * index.enc's plain bytes (sealed by the app where the source is): "RVEC", version, the model's SHA-256, the dimensions,
 * then each id, its text hash and its vector. [decode] gives null for another model, another size, or damage: the
 * index is then made again from the source, never trusted.
 */
object VectorCodec {
    private const val MAGIC = 0x52564543 // "RVEC"
    const val VERSION = 1
    private const val MAX_ID = 512

    fun encode(index: MeaningIndex): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeUTF(index.modelSha); o.writeInt(index.dim); o.writeInt(index.size)
            for (id in index.ids) {
                val e = index.vector(id)!!
                o.writeUTF(id); o.writeLong(index.hashOf(id)); for (x in e) o.writeFloat(x)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray?, modelSha: String = SearchModel.SHA256, dim: Int = SearchModel.DIM): MeaningIndex? = try {
        if (bytes == null) null else DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            if (i.readInt() != MAGIC || i.readInt() != VERSION || i.readUTF() != modelSha || i.readInt() != dim) return null
            val n = i.readInt()
            if (n < 0 || n.toLong() * (dim * 4 + 10) > bytes.size) return null
            val idx = MeaningIndex(modelSha, dim)
            repeat(n) {
                val id = i.readUTF()
                if (id.isEmpty() || id.length > MAX_ID) return null
                val h = i.readLong()
                val v = FloatArray(dim) { i.readFloat() }
                if (!idx.putHashed(id, h, v)) return null
            }
            if (i.read() != -1) return null
            idx
        }
    } catch (_: Exception) { null }
}

/** meaning-search.json (settings stay at schema 13): may the model download use mobile data (off: Wi-Fi only). */
data class MeaningSettings(val anyNetwork: Boolean = false) {
    fun toJson(): String = JSONObject().put("v", 1).put("anyNetwork", anyNetwork).toString()

    companion object {
        const val FILE = "meaning-search.json"
        fun fromJson(s: String?): MeaningSettings = try {
            if (s == null) MeaningSettings() else MeaningSettings(JSONObject(s).optBoolean("anyNetwork", false))
        } catch (_: Exception) { MeaningSettings() }
    }
}
