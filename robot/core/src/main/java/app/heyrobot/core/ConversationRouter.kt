// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang

// Pure. Conversation mode's voice commands (docs/conversation-mode.md 5.4), decided by code before anything reaches
// Gemma, like HelperRouter: "transcribe this conversation", "translate this conversation", "be my interpreter", and
// while a session runs "stop transcribing", "pause", "resume", "summarize this conversation", in English and French.
// A command is an imperative at the start of what was said, with only filler before it ("hey robot", "please", "can
// you") and only polite words after it. "How do I transcribe a song?" and "Can you translate 'merci'?" are questions,
// not sessions. 1.1: [ConversationRouter.lang] says which language's phrase matched, for the turn's reply language
// (core ReplyLang: a router's match is sure).

/** A conversation session's face (docs/conversation-mode.md 5). */
enum class ConvMode(override val id: Int, override val title: String) : LogCode {
    /** The live transcript, for hours, screen on or off. */
    TRANSCRIBE(1, "transcribe"),
    /** Face to face: each side's speech translated and spoken for the other. */
    INTERPRET(2, "interpreter"),
}

sealed class ConversationCommand {
    /** Start a session (or, while one runs, switch it to [mode]). */
    data class Start(val mode: ConvMode) : ConversationCommand()

    data object Stop : ConversationCommand()

    data object Pause : ConversationCommand()

    data object Resume : ConversationCommand()

    /** End the session and summarize it (needs the phone unlocked, docs/conversation-mode.md 1.7). */
    data object Summarize : ConversationCommand()
}

object ConversationRouter {
    /** Words allowed before a command (English and French), as their plain forms ([HelperRouter.plain]). */
    private val FILLER: List<List<String>> = listOf(
        listOf("hey", "robot"), listOf("hi", "robot"), listOf("ok", "robot"), listOf("okay", "robot"), listOf("he", "robot"),
        listOf("dis", "robot"), listOf("robot"), listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"),
        listOf("will", "you"), listOf("okay"), listOf("ok"), listOf("so"), listOf("um"), listOf("uh"), listOf("hey"), listOf("now"),
        listOf("sil", "te", "plait"), listOf("sil", "vous", "plait"), listOf("peux", "tu"), listOf("pourrais", "tu"), listOf("pouvez", "vous"),
        listOf("est", "ce", "que", "tu", "peux"), listOf("dac"), listOf("daccord"), listOf("alors"), listOf("bon"),
    )
    /** Words allowed after a command. */
    private val TAIL = setOf("please", "now", "for", "me", "us", "thanks", "thank", "you", "sil", "te", "vous", "plait", "maintenant", "pour",
        "moi", "nous", "svp", "ok", "okay")

    /** A pattern: one set of words per place, "a|b" for either, "?a|b" for an optional place (its set holds ""). */
    private fun p(vararg parts: String): List<Set<String>> = parts.map { part ->
        part.removePrefix("?").split('|').toSet().let { if (part.startsWith("?")) it + "" else it }
    }

    /** A command's phrases in English and in French. */
    private class Phrases(val en: List<List<Set<String>>>, val fr: List<List<Set<String>>>)

    private val TRANSCRIBE = Phrases(
        listOf(
            p("transcribe", "?this|the|our|everything", "?conversation|discussion|meeting|call|talk"),
            p("start", "transcribing|transcription", "?this|the|our", "?conversation|discussion|meeting"),
            p("start", "a|the", "transcript|transcription"),
        ),
        listOf(
            p("transcris|transcrivez|transcrire", "?cette|la|notre|ca|tout", "?conversation|discussion|reunion"),
            p("commence|commencez|demarre|demarrez|lance|lancez", "a|la|une", "transcrire|transcription"),
        ),
    )
    private val INTERPRET = Phrases(
        listOf(
            p("translate|interpret", "this|the|our", "conversation|discussion|meeting"),
            p("be", "my|our", "interpreter|translator"),
            p("interpret", "for", "me|us"),
            p("start", "translating|interpreting"),
            p("interpreter", "mode"),
        ),
        listOf(
            p("traduis|traduisez|traduire", "cette|la|notre", "conversation|discussion|reunion"),
            p("sois|soyez", "mon|notre", "interprete|traducteur|traductrice"),
            p("fais|faites", "linterprete"),
            p("commence|commencez", "a", "traduire"),
            p("mode", "interprete"),
        ),
    )
    private val STOP = Phrases(
        listOf(
            p("stop|end|finish", "transcribing|translating|interpreting|recording"),
            p("stop|end|finish", "the|this", "transcript|transcription|translation|conversation|session|recording"),
        ),
        listOf(
            p("arrete|arretez|termine|terminez", "de", "transcrire|traduire"),
            p("arrete|arretez|termine|terminez", "la|cette", "transcription|traduction|conversation|session"),
        ),
    )
    private val PAUSE = Phrases(
        listOf(
            p("pause", "?the|this", "?transcript|transcription|transcribing|recording|translation|session"),
        ),
        listOf(
            p("pause", "la|cette", "transcription|traduction|session"),
            p("mets|mettez", "en", "pause"),
            p("mets|mettez", "la|cette", "transcription|traduction|session", "en", "pause"),
        ),
    )
    private val RESUME = Phrases(
        listOf(
            p("resume|continue|unpause", "?the|this", "?transcript|transcription|transcribing|recording|translation|translating|session"),
            p("keep", "transcribing|translating|going"),
        ),
        listOf(
            p("reprends|reprenez|continue|continuez", "?la|a", "?transcription|transcrire|traduction|traduire"),
        ),
    )
    private val SUMMARIZE = Phrases(
        listOf(
            p("summarize|summarise", "this|the|our", "conversation|discussion|meeting|transcript"),
        ),
        listOf(
            p("resume|resumez", "cette|la|notre", "conversation|discussion|reunion|transcription"),
            p("fais|faites", "un|le", "resume", "?de", "?cette|la|notre", "?conversation|discussion|reunion|transcription"),
        ),
    )

    private fun words(text: String): List<String> =
        HelperRouter.plain(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun head(ws: List<String>): Int {
        var i = 0
        while (true) {
            val f = FILLER.firstOrNull { f -> f.indices.all { k -> ws.getOrNull(i + k) == f[k] } } ?: break
            i += f.size
        }
        return i
    }

    /** Matches [pattern] at [at] and only tail words after it; optional words are skipped when they don't fit. */
    private fun whole(ws: List<String>, at: Int, pattern: List<Set<String>>): Boolean {
        fun go(i: Int, k: Int): Boolean {
            if (k == pattern.size) return (i until ws.size).all { ws[it] in TAIL }
            val set = pattern[k]
            if (i < ws.size && ws[i] in set && go(i + 1, k + 1)) return true
            return "" in set && go(i, k + 1)
        }
        return go(at, 0)
    }

    /**
     * The command at the start of [text], or null (ordinary talk for Robot). [running]: a session runs or is paused;
     * only then are stop, pause, resume and summarize commands (outside one, "stop transcribing" is just words).
     */
    fun route(text: String, running: Boolean): ConversationCommand? = match(text, running)?.first

    /**
     * The language of the phrase [route] matched in [text] (English or French), or null when it matched none, when the
     * words fit a phrase of each language, or when they are one or two words ("pause", "continue"): the turn's reply
     * language takes a router's answer as sure (core ReplyLang.decide), and a short line isn't. Only the command's words
     * decide, never the filler before it ("dis robot").
     */
    fun lang(text: String, running: Boolean): Lang? = match(text, running)?.second

    private fun match(text: String, running: Boolean): Pair<ConversationCommand, Lang?>? {
        val ws = words(text)
        val i = head(ws)
        if (i >= ws.size) return null
        val short = ws.size - i <= 2
        // Null: not this command; else the command's language (null inside: not sure).
        fun any(c: Phrases): Array<Lang?>? {
            val en = c.en.any { whole(ws, i, it) }
            val fr = c.fr.any { whole(ws, i, it) }
            return if (!en && !fr) null else arrayOf(if (short || en == fr) null else if (en) Lang.EN else Lang.FR)
        }
        if (running) {
            any(STOP)?.let { return ConversationCommand.Stop to it[0] }
            any(SUMMARIZE)?.let { return ConversationCommand.Summarize to it[0] }
            any(PAUSE)?.let { return ConversationCommand.Pause to it[0] }
            any(RESUME)?.let { return ConversationCommand.Resume to it[0] }
        }
        any(INTERPRET)?.let { return ConversationCommand.Start(ConvMode.INTERPRET) to it[0] }
        any(TRANSCRIBE)?.let { return ConversationCommand.Start(ConvMode.TRANSCRIBE) to it[0] }
        return null
    }
}
