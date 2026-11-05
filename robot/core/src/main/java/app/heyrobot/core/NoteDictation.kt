// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.MemoryText

// Pure. everyday-1.2.2-a bug 6: "take a note that the plumber comes Thursday E122" was saved as "Plumber Appointment":
// "Plumber comes Thursday at E122", the model's words and a title it made up. A note you dictate is kept in your words
// ([said]); a title stays only when its words are yours. A note the model writes from something else ("save the recipe on
// my screen as a note", "jot down what you just said") is its text, so add_note's text is replaced only when it is close
// to the words you dictated.
object NoteDictation {
    // l10n: not text (words Robot hears)
    private val LEAD = listOf("hey robot", "ok robot", "okay robot", "robot", "please", "can you", "could you", "would you", "will you", // l10n: not text (words Robot hears)
        "s'il te plaît", "s'il te plait", "peux-tu", "peux tu", "tu peux", "pourrais-tu") // l10n: not text (words Robot hears)
    private val ASKS = listOf("take a note that", "take a note saying", "take a note:", "take a note", "take note that", "take note:", "make a note that", // l10n: not text (words Robot hears)
        "make a note saying", "make a note:", "make a note", "add a note that", "add a note saying", "add a note:", "save a note that", // l10n: not text (words Robot hears)
        "save a note saying", "save a note:", "note that", "note down", "jot down", "write down", // l10n: not text (words Robot hears)
        "prends en note que", "prends note que", "prends une note que", "prends en note", "prends note", "note que", "écris que", // l10n: not text (words Robot hears)
        "ajoute une note que", "fais une note que", "fais une note :", "fais une note") // l10n: not text (words Robot hears)
    /** At least this share of the model's words must be in what you dictated for your words to replace its text. */
    private const val CLOSE = 0.6

    /** The words you dictated in [words] ("take a note that X": X, as said), or null when they don't dictate a note. */ // l10n: not text (words Robot hears)
    fun said(words: String): String? {
        var t = words.replace('’', '\'').trim()
        var changed = true
        while (changed) {
            changed = false
            for (l in LEAD) if (starts(t, l)) { t = t.substring(l.length).trimStart(',', ' '); changed = true }
        }
        val ask = ASKS.firstOrNull { starts(t, it) } ?: return null
        val rest = t.substring(ask.length).trimStart(':', ',', ' ').trimEnd().trimEnd('.').trimEnd()
        return rest.ifEmpty { null }
    }

    // fix-123-d (Test Bench bug-everyday-b-5 on 1.2.3): "take a note that the plumber comes Thursday", as the second note in a
    // conversation, got an answer and no add_note call (brain_reply calls=0), so nothing was saved. A note you dictate is
    // add_note's call in your words, run without the model ([direct], [Direct]). Words that point at something else ("jot
    // down what you just said", "the recipe on my screen", "write this down") stay the model's to write.
    private val REFERS = Regex("""^(?:what|whatever|which|this|these|those|that|it|its|everything|all|your|ce|ca|cela|ceci|tout|ton|ta|tes)\b""") // l10n: not text (a pattern)
    private val SEEN = Regex("""\b(?:on (?:my |the |this )?screen|you (?:just )?(?:said|wrote|told me|found|read)|above|sur (?:mon |l'|cet? )?ecran|tu (?:viens de |as )?(?:dire|dit|ecrit|lu))\b""") // l10n: not text (a pattern)

    /** add_note's call for the note you dictate in [heard] (this turn's words), or null: not a dictated note, or the model's to write. */
    fun direct(heard: String): RawToolCall? {
        if (heard.trimEnd().endsWith('?')) return null
        val mine = said(heard) ?: return null
        val folded = MemoryText.fold(mine).replace('’', '\'')
        if (words(mine).size < 2 || REFERS.containsMatchIn(folded) || SEEN.containsMatchIn(folded)) return null
        return RawToolCall(ToolName.ADD_NOTE.id, mapOf("text" to mine))
    }

    /** The turn's model for [direct]'s [call]: no model is asked, and Robot's line from the card is the reply (ActionOutcome.confirms). */
    class Direct(private val call: RawToolCall) : TurnModel {
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(call))
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
        override fun close() {}
    }

    /** [a] in your words when they dictated it ([heard]: this turn's words) and the model's text is close to them; else [a]. */
    fun keep(a: Action.AddNote, heard: String): Action.AddNote {
        val mine = said(heard) ?: return a
        val said = words(mine)
        val yours = said.toSet()
        val its = words(a.text)
        // fix-123-b: "take a note that the gate code changed" came back padded, "The gate code has changed. Please update all
        // necessary access codes." (4 of its 11 words yours): every word you dictated in its text is your note reworded too.
        val covers = its.toSet().let { t -> yours.all { it in t } }
        if (its.isEmpty() || (!covers && its.count { it in yours }.toDouble() / its.size < CLOSE)) return a
        // A title only as you said it, words in a row ("Plumber"); "Bank Call" for "call the bank" is made up.
        val title = a.title?.takeIf { t -> words(t).let { w -> w.isNotEmpty() && java.util.Collections.indexOfSubList(said, w) >= 0 } }
        return Action.AddNote(mine, title)
    }

    private fun starts(t: String, p: String) = t.startsWith(p, ignoreCase = true) && (t.length == p.length || !t[p.length].isLetterOrDigit())

    private fun words(s: String): List<String> = MemoryText.fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() } // l10n: not text (a pattern)
}
