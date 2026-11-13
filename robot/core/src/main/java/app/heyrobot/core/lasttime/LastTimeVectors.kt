// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lasttime

import app.heyrobot.core.SearchModel
import app.heyrobot.core.memory.EmbedRole
import app.heyrobot.core.memory.MeaningIndex
import app.heyrobot.core.memory.MeaningText
import app.heyrobot.core.memory.TextEmbedder

// "When did I last …" by meaning over every candidate, not only the newest (review-last-time H1, M4): each candidate's
// vector is made once, kept by its key and its text's hash (an edited event or note is embedded again, a deleted one
// dropped at the next refresh), and a question embeds only itself. Kept in memory only, never written: receipts' and
// notes' own files are sealed, and their vectors stay as private as they are. Thread-safe: the asking thread reads
// while the background refresh writes. Nothing here logs a text or a vector.

class LastTimeVectors(modelSha: String = SearchModel.SHA256, dim: Int = SearchModel.DIM) {
    private val index = MeaningIndex(modelSha, dim)

    val size: Int @Synchronized get() = index.size

    /** The vectors held for [cs] as they read now (a candidate whose text changed has none until it's embedded again). */
    @Synchronized fun known(cs: List<LastCandidate>): Map<String, FloatArray> = cs.mapNotNull { c ->
        index.vector(c.key)?.takeIf { index.hashOf(c.key) == MeaningText.hash(text(c)) }?.let { c.key to it }
    }.toMap()

    /** Drops what's gone from [cs]; the rest still to embed, newest first. */
    @Synchronized fun plan(cs: List<LastCandidate>): List<LastCandidate> {
        val live = cs.associateBy { it.key }
        index.ids.filter { it !in live }.forEach(index::remove)
        return live.values.filter { index.hashOf(it.key) != MeaningText.hash(text(it)) || index.vector(it.key) == null }.sortedByDescending { it.day }
    }

    @Synchronized private fun put(c: LastCandidate, v: FloatArray): Boolean = index.put(c.key, text(c), v)

    /**
     * Brings the vectors level with [cs], [batch] texts per request so :brain's thread is never held long, at most
     * [maxBatches] requests a call (one question's share), and stops between batches once [more] says so (or at the
     * first null: the model gone or busy). How many it embedded.
     */
    suspend fun refresh(cs: List<LastCandidate>, embedder: TextEmbedder, batch: Int = BATCH, maxBatches: Int = MAX_BATCHES, more: () -> Boolean = { true }): Int {
        var done = 0
        for (chunk in plan(cs).chunked(batch.coerceAtLeast(1)).take(maxBatches.coerceAtLeast(0))) {
            if (!more()) break
            val vs = embedder.embed(chunk.map(::text), EmbedRole.DOCUMENT) ?: break
            if (vs.size != chunk.size) break
            chunk.forEachIndexed { i, c -> if (put(c, vs[i])) done++ }
        }
        return done
    }

    @Synchronized fun clear() = index.clear()

    companion object {
        /** Texts per :brain request (BrainMsg.Embed.MAX_TEXTS). */
        const val BATCH = 8
        /** :brain requests the rebuild makes per question (review-last-time-b M1); the next question carries on. */
        const val MAX_BATCHES = 8
        const val TEXT_CHARS = 300
        fun text(c: LastCandidate): String = c.searchable.take(TEXT_CHARS)
    }
}
