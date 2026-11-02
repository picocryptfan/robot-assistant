// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.skills.SkillSpecs

// Pure. 1 Nov (the spoken run: "How do I free up storage?" got "using the `free_storage` tool"): the person never hears or
// reads a tool's name. The model's words are put right here, for the bubble at the end of a round and for each sentence
// before it's read out; a reply that is a call written as text stays as it is, for ToolCalls.

object ReplyWords {
    private val SNAKE = ToolName.entries.map { it.id }.filter { '_' in it }
    private val SINGLE = ToolName.entries.map { it.id }.filter { '_' !in it }
    private val SKILL = Regex.escape(SkillSpecs.PREFIX) + """[\p{L}0-9_]+"""

    /** A tool's name as a name: any of them in backticks, the ones no one would say otherwise (set_timer) bare too. */
    private val NAME = "(?:`(?:" + (SNAKE + SINGLE).joinToString("|", transform = Regex::escape) + "|" + SKILL + """)(?:\(\))?`|\b(?:""" +
        SNAKE.joinToString("|", transform = Regex::escape) + "|" + SKILL + """)\b(?:\(\))?)"""

    /** ... or a word made a tool by what follows it: "the weather tool", "the free_up_space function". */
    private val WITH_WORD = """(?:$NAME|\b(?:""" + SINGLE.joinToString("|", transform = Regex::escape) + """|\p{L}+(?:_\p{L}+)+)\b(?=\s+(?:tool|function)\b)|`?\p{L}+(?:_\p{L}+)+`?(?=\s+(?:tool|function)\b))"""
    private val ANY = """$WITH_WORD(?:\s+(?:tool|function))?"""
    private val OUTIL = """(?:l['’]outil\s+|la\s+fonction\s+)?"""
    private val ART = """(?:the\s+|my\s+)?"""

    private val opts = setOf(RegexOption.IGNORE_CASE)
    /** "use the `x` tool to free up space": "free up space". */
    private val USE_TO = Regex("""\b(?:use|using|call|calling|run|running)\s+$ART$ANY\s+to\s+""", opts)
    private val USE_TO_FR = Regex("""\b(?:utilise|utiliser|utilisant|utilises)\s+$OUTIL$ANY\s+pour\s+""", opts)
    /** ", using the `x` tool": nothing. */
    private val USING = Regex("""[,\s]*\b(?:by\s+|just\s+)?(?:using|with|via|through|calling|running)\s+$ART$ANY""", opts)
    private val USING_FR = Regex("""[,\s]*(?:\ben\s+utilisant|\bavec|\bvia|\bgr[aâ]ce\s+[aà]|[aà]\s+l['’]aide\s+de)\s+$OUTIL$ANY""", opts)
    /** Anything else: "that", « ça ». */
    private val REST = Regex("""(?:\bthe\s+|$OUTIL)$ANY|$ANY""", opts)

    /** [text] with no tool named in it, in [say]'s words where one is needed. */
    fun scrub(text: String, say: Lang = Lang.EN): String {
        if (ToolCalls.parseText(text).isNotEmpty() || text.contains("<start_function_call>")) return text
        val a = USE_TO_FR.replace(USE_TO.replace(text, ""), "")
        val b = USING_FR.replace(USING.replace(a, ""), "")
        val word = if (say == Lang.FR) "ça" else "that"
        val c = REST.replace(b) { m ->
            val before = b.substring(0, m.range.first).trimEnd()
            if (before.isEmpty() || before.last() in ".!?…\n") word.replaceFirstChar { it.uppercase() } else word
        }
        if (c == text) return text
        return c.replace(Regex(""" +([.,;!?])"""), "$1").replace(Regex(""" {2,}"""), " ").trim()
            .let { s -> if (s.isNotEmpty() && text.first().isUpperCase()) s.replaceFirstChar { it.uppercase() } else s }
    }
}
