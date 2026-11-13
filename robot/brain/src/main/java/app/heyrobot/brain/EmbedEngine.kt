// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.os.Handler
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.RLog
import app.heyrobot.core.SearchModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File

// :BRAIN PROCESS ONLY. Search by meaning (docs/plans/meaning-search-plan.md): REQ_EMBED turns a batch of texts into
// vectors with LiteRT-LM's EmbeddingEngine and the search model (EmbeddingGemma 2 Text 270M, the CPU file), at
// SearchModel.DIM dimensions, normalised. On the work thread like every other LiteRT call (never two threads in LiteRT
// at once); that thread serves replies too, so a request is at most BrainMsg.Embed.MAX_TEXTS (8) texts, and a client's
// time limit stops its next request, not only its wait. Loaded on the first request, kept while requests come, closed after [IDLE_MS] without one (about 300 MB).
// The client sends only the path SearchModelStore checked (size and SHA-256); the file is checked for its size here too.
// Nothing here logs a text or a vector.

class EmbedEngine(private val handler: Handler, private val cacheDir: File) {
    private var engine: EmbeddingEngine? = null
    private var loaded: Pair<String, Int>? = null
    private val idle = Runnable { close("idle") }

    /** The vectors of [m]'s texts, in order, or an error when the model can't load or run. */
    fun embed(m: BrainMsg.Embed): BrainMsg {
        handler.removeCallbacks(idle)
        try {
            val e = engineFor(m.path, m.maxInput) ?: return BrainMsg.Vectors(emptyList(), ok = false)
            val t0 = System.nanoTime()
            val out = e.computeEmbeddingBatch(m.texts.map { listOf(InputData.Text(it)) }, EmbeddingOptions(true, true, SearchModel.DIM))
                .map { it.embedding }
            RLog.i("brain_embed", "n" to m.texts.size, "ms" to (System.nanoTime() - t0) / 1_000_000)
            return BrainMsg.Vectors(out, ok = out.size == m.texts.size)
        } catch (t: Throwable) {
            RLog.w("brain_embed", t)
            close("error")
            return BrainMsg.Vectors(emptyList(), ok = false)
        } finally {
            handler.postDelayed(idle, IDLE_MS)
        }
    }

    private fun engineFor(path: String, maxInput: Int): EmbeddingEngine? {
        val want = path to maxInput
        engine?.let { if (loaded == want) return it }
        close("switch")
        val f = File(path)
        if (!f.isFile || f.length() != SearchModel.BYTES || f.name != SearchModel.FILE) return null
        cacheDir.mkdirs()
        val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cacheDir.absolutePath, maxInputLength = maxInput))
        e.initialize()
        engine = e
        loaded = want
        RLog.i("brain_embed", "op" to RLog.Code("load"))
        return e
    }

    fun close(why: String) {
        handler.removeCallbacks(idle)
        val e = engine ?: return
        engine = null
        loaded = null
        try { e.close() } catch (t: Throwable) { RLog.w("brain_embed", t) }
        RLog.i("brain_embed", "op" to RLog.Code("close"), "why" to RLog.Code(why))
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
    }
}
