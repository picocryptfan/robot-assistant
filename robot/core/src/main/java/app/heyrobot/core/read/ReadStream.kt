// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReadStrings

/**
 * 1.3 (P2): a reading said sentence by sentence as the model writes it (the panel's PanelRead), in ReadAloud.spoken's
 * order: the kind once its line is whole; each finished sentence of the words in their own language, "[unclear]" as
 * "I can't read this part"; at the end the rest, then "I don't see any words in it." for none, or the offer of [you]r
 * language when they're in another. Pure: one per reading.
 */
class ReadStream(private val you: Lang) {
    private var started = false
    private var used = 0
    private var ended = false

    /** The reply so far, read: null until its first line is whole (or it ended). */
    private fun reading(soFar: String, done: Boolean): ReadAloud.Reading? =
        if (done || soFar.trimStart().contains('\n')) ReadAloud.parse(soFar) else null

    /** The words the card shows: none until the kind's line is whole, then the words without it. */
    fun shown(soFar: String, done: Boolean = false): String = reading(soFar, done)?.text.orEmpty()

    /** What to say now of the reply so far [soFar] ([done]: all of it): only what wasn't said before. */
    fun next(soFar: String, done: Boolean = false): List<ReadAloud.Said> {
        if (ended) return emptyList()
        val r = reading(soFar, done) ?: return emptyList()
        val out = ArrayList<ReadAloud.Said>()
        if (!started) {
            started = true
            r.kind?.phrase()?.let { out += ReadAloud.Said.Robot(it) }
        }
        val text = r.text
        val end = if (done) text.length else BOUNDARY.findAll(text, minOf(used, text.length)).lastOrNull()?.let { it.range.last + 1 } ?: used
        if (end > used) {
            text.substring(used, end).split(UNCLEAR).forEachIndexed { i, p ->
                if (i > 0) out += ReadAloud.Said.Robot(Phrase.M(ReadStrings.unclear))
                p.trim().takeIf { it.isNotEmpty() }?.let { out += ReadAloud.Said.Words(it, r.lang) }
            }
            used = end
        }
        if (done) {
            ended = true
            if (text.isBlank()) out += ReadAloud.Said.Robot(Phrase.M(ReadStrings.noWords))
            else if (r.lang != null && r.lang != you.id) out += ReadAloud.Said.Robot(Phrase.M(ReadStrings.offer))
        }
        return out
    }

    companion object {
        /** [text] cut into its sentences, as a reading is said (each trimmed, none empty). */
        fun sentences(text: String): List<String> {
            val out = ArrayList<String>()
            var from = 0
            for (m in BOUNDARY.findAll(text)) {
                text.substring(from, m.range.last + 1).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                from = m.range.last + 1
            }
            text.substring(from).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            return out
        }

        /** A sentence's end: its stop and closing marks, then a space or a line's end; or a line's end alone. */
        private val BOUNDARY = Regex("""[.!?…]+["'”»)\]]*\s+|\n+""")
        private val UNCLEAR = Regex("""\[\s*unclear\s*]""", RegexOption.IGNORE_CASE)
    }
}
