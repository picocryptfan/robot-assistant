// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.RLog
import app.heyrobot.core.memory.Bm25Retriever
import app.heyrobot.core.memory.EmbedRole
import app.heyrobot.core.memory.EmbeddingIndex
import app.heyrobot.core.memory.EmbeddingRetriever
import app.heyrobot.core.memory.HybridRetriever
import app.heyrobot.core.memory.MeaningIndex
import app.heyrobot.core.memory.MeaningIndexer
import app.heyrobot.core.memory.MeaningSource
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.Retriever
import app.heyrobot.core.memory.SearchDoc
import app.heyrobot.core.SearchModel
import app.heyrobot.core.memory.TextEmbedder
import app.heyrobot.sys.MemoryFiles
import app.heyrobot.sys.SearchModelStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

// Voice process (the panel's memory turn). Search by meaning for memory (docs/plans/meaning-search-plan.md): with the
// search model checked (SearchModelStore.verifiedPath), a question gets one query vector from :brain (REQ_EMBED) and
// memory's vectors (index.enc, sealed with memory's key) rank the items, word hits with a name or a number added; without
// the model, or when anything fails or is slow, words only, as before. After each question the vectors catch up with
// the items in the background (new and edited ones embedded, deleted ones dropped). Private items get no vector: they
// are found by their words, as before. Nothing here logs a text or a vector.

object MeaningMemory {
    /** The query vector waits at most this long (one short text, ~50 ms on the CPU); past it, words only. */
    private const val QUERY_MS = 1_500L
    private val WORDS: Pair<Retriever, FloatArray?> = Bm25Retriever() to null

    @Volatile private var app: Context? = null
    @Volatile private var cached: Pair<Long, MeaningIndex>? = null
    private val busy = AtomicBoolean(false)
    private val embedder by lazy { app?.let(::BrainEmbedder) }

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** The retriever and the query vector for [question] (words only, null vector, without the model or its vectors). */
    fun forQuestion(question: String, items: List<MemoryItem>): Pair<Retriever, FloatArray?> {
        val ctx = app ?: return WORDS
        if (SearchModelStore.verifiedPath(ctx) == null) return WORDS
        val idx = index(ctx)
        val e = embedder
        // The question's vector first, then the catch-up (which holds :brain's embedder for its batches).
        val q = if (idx == null || idx.size == 0 || e == null) null else try {
            runBlocking { withTimeoutOrNull(QUERY_MS) { e.embed(listOf(question), EmbedRole.QUERY) } }?.singleOrNull()
        } catch (t: Throwable) { RLog.w("meaning_query", t); null }
        catchUp(ctx, items)
        if (idx == null || q == null) return WORDS
        val vectors = EmbeddingIndex(SearchModel.SHA256).apply { idx.ids.forEach { id -> idx.vector(id)?.let { put(id, it) } } }
        return HybridRetriever(EmbeddingRetriever(vectors)) to q
    }

    /** The documents memory's vectors are made from: every item but the private ones. */
    fun docs(items: List<MemoryItem>): List<SearchDoc> = items.filter { !it.secret }.map { SearchDoc(MeaningSource.MEMORY, it.id, it.text) }

    private fun index(ctx: Context): MeaningIndex? {
        val vault = MemoryFiles.vault(ctx)
        val stamp = vault.stamp() * 31 + MeaningVectorsStamp.of(ctx)
        cached?.let { (s, i) -> if (s == stamp) return i }
        return vault.readIndex()?.also { cached = stamp to it }
    }

    private fun catchUp(ctx: Context, items: List<MemoryItem>) {
        val docs = docs(items)
        val idx = index(ctx) ?: MeaningIndex()
        if (idx.plan(docs).nothing || !busy.compareAndSet(false, true)) return
        Thread({
            try {
                val e = embedder ?: return@Thread
                val n = runBlocking { MeaningIndexer.refresh(idx, docs, e) }
                val ok = MemoryFiles.vault(ctx).writeIndex(idx)
                cached = null
                RLog.i("meaning_index", "source" to RLog.Code(MeaningSource.MEMORY.code), "embedded" to n, "size" to idx.size, "ok" to ok)
            } catch (t: Throwable) {
                RLog.w("meaning_index", t)
            } finally {
                busy.set(false)
            }
        }, "robot-meaning-index").apply { priority = Thread.MIN_PRIORITY }.start()
    }
}

/** index.enc's own change stamp, so a cached index is read again once the background writer has saved it. */
private object MeaningVectorsStamp {
    fun of(ctx: Context): Long = app.heyrobot.sys.MeaningVectors.memory(ctx).let { if (it.isFile) it.lastModified() * 31 + it.length() else 0L }
}

/** [TextEmbedder] over :brain (REQ_EMBED), with the checked model's path; null when the model or :brain isn't there. */
class BrainEmbedder(private val ctx: Context) : TextEmbedder {
    private val lock = Mutex()
    private var client: BrainClient? = null

    override suspend fun embed(texts: List<String>, role: EmbedRole): List<FloatArray>? = lock.withLock {
        val path = SearchModelStore.verifiedPath(ctx) ?: return@withLock null
        val c = client ?: BrainClient(ctx, { client = null }).also { client = it }
        if (!c.connect()) { client = null; return@withLock null }
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(BrainMsg.Embed.MAX_TEXTS)) {
            val r = c.call(BrainProtocol.REQ_EMBED, BrainMsg.Embed(path, chunk.map { role.input(it).take(BrainMsg.Embed.MAX_CHARS) }), CALL_MS)
            val v = r as? BrainMsg.Vectors
            if (v == null || !v.ok || v.vectors.size != chunk.size) return@withLock null
            out += v.vectors
        }
        out
    }

    companion object {
        private const val CALL_MS = 60_000L
    }
}
