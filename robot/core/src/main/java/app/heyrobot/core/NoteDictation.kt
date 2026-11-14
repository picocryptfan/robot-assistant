// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
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
    private val ASKS = listOf("take a note of", "make a note of", "take note of", "take a note that", "take a note saying", "take a note:", "take a note", "take note that", "take note:", "make a note that", // l10n: not text (words Robot hears)
        "make a note saying", "make a note:", "make a note", "add a note that", "add a note saying", "add a note:", "save a note that", // l10n: not text (words Robot hears)
        "save a note saying", "save a note:", "note that", "note down", "jot down", "write down", // l10n: not text (words Robot hears)
        "prends note de", "prends note d'", "prends une note de", "prends une note d'", "fais une note de", "fais une note d'", // l10n: not text (words Robot hears)
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
    // review-fix-jot-down-after-note L3: voice has no "?": a question word asking ("how to", "where is", « comment faire », « où est »)
    // is the model's to answer; "jot down where I parked: level 3" tells, and stays a note.
    private val ASKING = Regex("""^(?:(?:how|where|when|who|whom|whose|why)(?:'s|\s+(?:to|is|are|was|were|am|do|does|did|can|could|should|would|will|has|have|had)\b)|(?:comment|ou|quand|qui|pourquoi)\s+(?:est|sont|etait|faut|fait|faire|dire|aller|va|vont|peut|peux|puis|dois|doit|on)\b)""") // l10n: not text (a pattern)
    // fix-note-answer (phone-142-rc1 J3): "write down how to say thank you in French" went to the model, which saved the question
    // as the note. A note verb with a question ([ASKING], or [WHAT]: "what is", « qu'est-ce que », « comment dit-on ») is Robot's
    // to answer, then save, question and answer ([question], [Answered]); a colon tells ("jot down when it is: Tuesday").
    private val WHAT = Regex("""^(?:what(?:'s|\s+(?:is|are|was|were|does|do|did)\b)|which\s+(?:is|are)\b|comment\s+(?:dit|dit-on|dire|se\s+dit|on\s+dit)\b|qu'?\s*est[- ]ce\s+qu|que\s+(?:veut\s+dire|signifie)\b|quel(?:le)?s?\s+(?:est|sont)\b)""") // l10n: not text (a pattern)
    private val SEEN = Regex("""\b(?:on (?:my |the |this )?screen|you (?:just )?(?:said|wrote|told me|found|read)|above|sur (?:mon |l'|cet? )?ecran|tu (?:viens de |as )?(?:dire|dit|ecrit|lu))\b""") // l10n: not text (a pattern)

    /** add_note's call for the note you dictate in [heard] (this turn's words), or null: not a dictated note, or the model's to write. */
    fun direct(heard: String): RawToolCall? {
        if (heard.trimEnd().endsWith('?')) return null
        val mine = said(heard) ?: return null
        val folded = MemoryText.fold(mine).replace('’', '\'')
        // review-fix-note-answer M1: a question with a colon tells (« comment dit-on merci en espagnol : gracias », "what is the
        // door code: 4521"): your words, as said, never the model's, in French and in English alike. L-d: and an asking one ("how to
        // reset the router: hold the button", « où est la clé : sous le tapis »).
        val told = ':' in mine && (WHAT.containsMatchIn(folded) || ASKING.containsMatchIn(folded))
        if (words(mine).size < 2 || (REFERS.containsMatchIn(folded) && !told) || (ASKING.containsMatchIn(folded) && !told) || (WHAT.containsMatchIn(folded) && !told) || SEEN.containsMatchIn(folded)) return null
        return RawToolCall(ToolName.ADD_NOTE.id, mapOf("text" to mine))
    }

    /** The question you asked to have noted in [heard] ("write down how to say thank you in French": its question), or null. */
    fun question(heard: String): String? {
        val mine = said(heard)?.trimEnd('?', ' ') ?: return null
        val folded = MemoryText.fold(mine).replace('’', '\'')
        if (':' in mine || words(mine).size < 3 || SEEN.containsMatchIn(folded) || !(ASKING.containsMatchIn(folded) || WHAT.containsMatchIn(folded))) return null
        return mine
    }

    /** [question] for a turn of the panel's: as [forTurn], but a conversation with content in it still asks (its card asks first). */
    fun questionForTurn(heard: String, way: FastPath.Way, cmd: HelperCommand?, attachment: Attachment, inHelperMode: Boolean): String? =
        if (way != FastPath.Way.HELPER && cmd == null && attachment == Attachment.NONE && !inHelperMode) question(heard) else null

    /** The note for [question] answered by [answer] ("How to say thank you in French: Merci."; in French " : "), or null for no answer. */
    fun noteOf(question: String, answer: String, lang: Lang = Lang.EN): RawToolCall? = answer.trim().takeIf { it.isNotEmpty() }?.let {
        RawToolCall(ToolName.ADD_NOTE.id, mapOf("text" to question.trim().replaceFirstChar(Char::uppercaseChar) + (if (lang == Lang.FR) " : " else ": ") + it))
    }

    /**
     * fix-note-answer: the model answers [question] with no tools ([inner]: the raw turn); its answer ([reply], the reply's
     * text once it ends) and the question are then add_note's call, joined in [lang]. review-fix-note-answer M2, L1: no answer
     * (nothing, a hedge, a cut reply, a failure, a call, or calls written as text) saves [question] in your words, as rc1 did,
     * and [show] clears the reply; [none] goes in its place only once add_note's result says the note was saved
     * (review-fix-note-answer-c L-a: declined or failed, the card's line says what happened). Calls written as text are
     * :brain's text-call form of a reply ([ToolCalls.wholeReplyCalls], L-b); a hedge in the answer to a question that
     * holds one ("how to say I don't know in Spanish") is the answer (L-c), and only those words or their twin in the
     * other language (fix-142-lows: [Hedge.detect] besides the question): nothing lost, nothing wrong saved. A stopped reply stays as it is.
     */
    class Answered(private val inner: TurnModel, private val question: String, private val reply: () -> String, private val none: String,
            private val lang: Lang = Lang.EN, private val show: (String) -> Unit = {}) : TurnModel {
        override suspend fun ask(): ModelOutcome = when (val o = inner.ask()) {
            is ModelOutcome.Failed -> unanswered()
            is ModelOutcome.Calls -> { inner.close(); unanswered() }
            is ModelOutcome.Done -> if (o.reason == DoneReason.CANCELLED) o
                else reply().takeIf { o.reason == DoneReason.END && ToolCalls.wholeReplyCalls(it).isEmpty() && !Hedge.detect(it, besides = question) }?.let { noteOf(question, it, lang) }
                    ?.let { ModelOutcome.Calls(listOf(it)) } ?: unanswered()
        }
        private var unsaid = false
        private fun unanswered(): ModelOutcome {
            show("")
            unsaid = true
            return ModelOutcome.Calls(listOf(RawToolCall(ToolName.ADD_NOTE.id, mapOf("text" to question))))
        }
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { kept(results); return ModelOutcome.Done(DoneReason.END) }
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) { kept(results); close() }
        /** A result not done carries ActionOutcome.forModel's "outcome": then the card's own line is the reply. */
        private fun kept(results: List<Pair<String, Map<String, Any?>>>) { if (unsaid && results.isNotEmpty() && results.none { "outcome" in it.second }) show(none) }
        override fun close() = inner.close()
    }

    /**
     * fix-jot-down-after-note: [direct] for a turn of the panel's, or null when it isn't Robot's to write alone: a helper's
     * turn ([way] HELPER, [inHelperMode]), a helper phrase ([cmd]), something attached, or content Robot didn't write in the
     * conversation ([untrusted]). A plain ask is FastPath's TEXT (nothing named, nothing attached), never MODEL: the panel
     * asked for MODEL from fix-123-d on, so every dictated note went to the model, which after a first note in the same
     * conversation sometimes answered "Nothing was done." and saved nothing (phone-141-followups 1).
     */
    fun forTurn(heard: String, way: FastPath.Way, cmd: HelperCommand?, attachment: Attachment, untrusted: Boolean, inHelperMode: Boolean): RawToolCall? =
        if (way != FastPath.Way.HELPER && cmd == null && attachment == Attachment.NONE && !untrusted && !inHelperMode) direct(heard) else null

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

    private fun starts(t: String, p: String) = t.startsWith(p, ignoreCase = true) && (t.length == p.length || p.last() == '\'' || !t[p.length].isLetterOrDigit())

    private fun words(s: String): List<String> = MemoryText.fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() } // l10n: not text (a pattern)
}
