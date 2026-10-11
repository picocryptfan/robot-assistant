package app.heyrobot.core

// Pure. Settings › Model › "Let the model think first" (0.4.4, off by default): some models can reason in a hidden block
// before they answer (Qwen3, MiniCPM 5, the GGUF files whose chat template takes enable_thinking). Off, Robot tells them to
// answer directly; on, they may think, which takes longer (the thinking is read out by nobody). Either way the thinking
// never reaches the chat bubble or the voice: an engine that doesn't route it to a channel of its own has it removed here
// (ThinkFilter). Robot's built-in Gemma 4 models are left exactly as they were.

/**
 * What Robot does about one model's thinking. [systemSuffix]: added to the end of the system prompt (Qwen3's " /no_think");
 * [enableThinking]: the chat template's enable_thinking (null: the model's own default); [stripThinking]: remove
 * <think>…</think> from the reply.
 */
data class ThinkingPlan(val systemSuffix: String = "", val enableThinking: Boolean? = null, val stripThinking: Boolean = false) {
    /** The system prompt as the model gets it. */
    fun system(system: String): String = system + systemSuffix
}

object Thinking {
    /** Qwen3's soft switch: in the system prompt, it answers without a thinking block. */
    const val NO_THINK = " /no_think"
    const val OPEN = "<think>"
    const val CLOSE = "</think>"

    /**
     * A LiteRT-LM model ([modelType]: its LlmMetadata type, your own file only). Qwen3 takes "/no_think"; MiniCPM 5's
     * template takes enable_thinking (LiteRT-LM 0.18: ThinkingConfig and the template's extra context). Built-in models:
     * nothing changes.
     */
    fun liteRt(model: BrainModel, modelType: String?, think: Boolean): ThinkingPlan = when {
        model != BrainModel.OWN -> ThinkingPlan()
        modelType == "qwen3" -> ThinkingPlan(if (think) "" else NO_THINK, null, stripThinking = true)
        modelType == "minicpm5" -> ThinkingPlan("", think, stripThinking = true)
        else -> ThinkingPlan(stripThinking = true)
    }

    /**
     * A GGUF model, by its check: a template that reads enable_thinking gets it; a Qwen3 file whose template doesn't
     * takes "/no_think" instead; anything else is left to its template. The reply is always cleaned of a <think> block.
     */
    fun gguf(report: GgufReport?, think: Boolean): ThinkingPlan = when {
        report == null -> ThinkingPlan(stripThinking = true)
        report.templateThinking -> ThinkingPlan("", think, stripThinking = true)
        GgufCheck.qwen3(report.architecture) -> ThinkingPlan(if (think) "" else NO_THINK, null, stripThinking = true)
        else -> ThinkingPlan(stripThinking = true)
    }
}

/**
 * Streamed reply text without its thinking: drops everything from "<think>" to "</think>" (and the blank lines after it),
 * holding back the end of a chunk while it could still be the start of a tag, so no part of a tag or of the thinking is
 * ever shown or spoken. [startsInside]: the prompt already opened the block (some templates do), so the reply starts in
 * it. A block that never closes hides the rest of the reply: nothing of it is shown.
 */
class ThinkFilter(startsInside: Boolean = false) {
    private var inside = startsInside
    private val held = StringBuilder()
    /** After a closed block: the whitespace the model writes before its answer is dropped. */
    private var trimNext = false
    /** Thinking was seen (the panel can say the model is thinking). */
    var thought = startsInside
        private set

    /** What to show and speak of [chunk] now (possibly nothing yet). */
    fun feed(chunk: String): String {
        held.append(chunk)
        val out = StringBuilder()
        while (held.isNotEmpty()) {
            if (inside) {
                val end = held.indexOf(Thinking.CLOSE)
                if (end < 0) {
                    // Keep only what could be the start of "</think>"; the rest is thinking.
                    val keep = partialTag(held, Thinking.CLOSE)
                    held.delete(0, held.length - keep)
                    break
                }
                held.delete(0, end + Thinking.CLOSE.length)
                inside = false
                trimNext = true
            } else {
                val open = held.indexOf(Thinking.OPEN)
                val close = held.indexOf(Thinking.CLOSE)
                if (close >= 0 && (open < 0 || close < open)) {
                    // A stray closing tag (its block opened before Robot could see it): the tag itself never shows.
                    emit(out, held.substring(0, close))
                    held.delete(0, close + Thinking.CLOSE.length)
                    trimNext = true
                    continue
                }
                if (open >= 0) {
                    emit(out, held.substring(0, open))
                    held.delete(0, open + Thinking.OPEN.length)
                    inside = true
                    thought = true
                    continue
                }
                val keep = maxOf(partialTag(held, Thinking.OPEN), partialTag(held, Thinking.CLOSE))
                emit(out, held.substring(0, held.length - keep))
                held.delete(0, held.length - keep)
                break
            }
        }
        return out.toString()
    }

    /** The reply ended: what is still held back (never a part of thinking). */
    fun flush(): String {
        if (inside) {
            held.setLength(0)
            return ""
        }
        val out = StringBuilder()
        emit(out, held.toString())
        held.setLength(0)
        return out.toString()
    }

    private fun emit(out: StringBuilder, text: String) {
        var t = text
        if (trimNext) {
            t = t.trimStart()
            if (t.isEmpty()) return
            trimNext = false
        }
        out.append(t)
    }

    /** The length of the longest end of [s] that is a beginning of [tag] (shorter than the whole tag). */
    private fun partialTag(s: CharSequence, tag: String): Int {
        for (k in minOf(tag.length - 1, s.length) downTo 1) {
            var match = true
            for (i in 0 until k) if (s[s.length - k + i] != tag[i]) { match = false; break }
            if (match) return k
        }
        return 0
    }

    companion object {
        /** A whole reply without its thinking. */
        fun strip(text: String, startsInside: Boolean = false): String = ThinkFilter(startsInside).let { it.feed(text) + it.flush() }
    }
}
