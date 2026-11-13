// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

// Step by step (docs/plans/step-by-step-plan.md): a page's steps, taken from its own numbering ("1." "2)" "Step 3:"
// « Étape 3 : »), else its bullets, else its lines; none when the page has only one line. Each step is the page's own
// text, its number or bullet taken off and its spaces tidied: never reworded.
object StepSplit {
    const val MAX_STEPS = 80

    private val NUMBERED = Regex("""^\s*(\d{1,2})\s*[.):](?:\s+|$)(.*)$""")
    private val NAMED = Regex("""^\s*(?:step|[ée]tape)\s+(\d{1,2})\s*[.):\-–—]?\s*(.*)$""", RegexOption.IGNORE_CASE) // l10n: not text (words on a page)
    private val BULLET = Regex("""^\s*[-*•·▪–—]\s+(.*)$""")

    fun split(text: String): List<String> {
        val lines = text.replace("\r", "").split('\n')
        numbered(lines).takeIf { it.size >= 2 }?.let { return it.take(MAX_STEPS) }
        bullets(lines).takeIf { it.size >= 2 }?.let { return it.take(MAX_STEPS) }
        val plain = lines.map(::tidy).filter { it.isNotEmpty() }
        return if (plain.size >= 2) plain.take(MAX_STEPS) else emptyList()
    }

    private fun number(line: String): Pair<Int, String>? =
        (NAMED.matchEntire(line) ?: NUMBERED.matchEntire(line))?.let { it.groupValues[1].toInt() to it.groupValues[2] }

    /** Runs of numbered lines, a run starting again at 1; the longest wins, the last on a tie (the method after a numbered list of what you need). */
    private fun numbered(lines: List<String>): List<String> {
        val runs = ArrayList<MutableList<String>>()
        var cur: MutableList<String>? = null
        var open = false
        var last = 0
        for (l in lines) {
            val n = number(l)
            when {
                n != null && (cur == null || n.first == 1 || n.first != last + 1) && n.first <= 1 -> {
                    cur = mutableListOf(n.second); runs += cur; last = n.first; open = true
                }
                n != null && cur != null && n.first == last + 1 -> { cur += n.second; last = n.first; open = true }
                n != null -> { cur = null; open = false }
                l.isBlank() -> open = false
                open && cur != null -> cur[cur.size - 1] = cur.last() + " " + l.trim()
            }
        }
        return runs.map { r -> r.map(::tidy).filter { it.isNotEmpty() } }.lastOrNull { r -> runs.all { it.size <= r.size } } ?: emptyList()
    }

    private fun bullets(lines: List<String>): List<String> {
        val runs = ArrayList<MutableList<String>>()
        var cur: MutableList<String>? = null
        for (l in lines) {
            val b = BULLET.matchEntire(l)
            if (b != null) { if (cur == null) { cur = mutableListOf(); runs += cur }; cur += tidy(b.groupValues[1]) }
            else if (l.isNotBlank()) cur = null
        }
        return runs.lastOrNull { r -> runs.all { it.size <= r.size } } ?: emptyList()
    }

    private fun tidy(s: String): String = s.trim().replace(Regex("""\s+"""), " ")
}
