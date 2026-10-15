// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import java.util.EnumSet

// Pure. A small bash lexer for the Termux agent, good enough to classify a
// command before anything runs it: words with quotes and escapes, pipes, &&, ||, ;, &, redirections, here-docs,
// subshells, { } groups, $( ), backticks, ${ }, $(( )), process substitution, globs, assignments, comments, keywords and
// function definitions. It never runs or expands anything. Whatever it can't follow is a [ShellProblem], and the command
// is then "complex" (CommandAnalyzer: always a card answered by a tap, its warnings still shown). Every character is read
// a bounded number of times (nesting is capped at [ShellLexer.MAX_DEPTH]) and no regular expression runs on the input,
// so a long or hostile command costs linear time.

/** A word as bash reads it, before any expansion. */
class ShellWord internal constructor(
    /** The source text. */
    val raw: String,
    /** Quotes removed and escapes resolved ($'…' decoded); expansions ($X, $(…), `…`) kept as their source text. */
    val value: String,
    /** Some part was quoted or escaped. */
    val quoted: Boolean,
    /** Parameters it expands: the name of a plain $NAME or ${NAME}, "?" for anything else ($1, $@, ${x:-y}, ${#x}). */
    val params: List<String>,
    /** It holds $(…), `…`, $((…)) or <(…): its value is only known by running something. */
    val substitutes: Boolean,
    /** An unquoted *, ? or […] (pathname expansion) or {a,b} / {1..3} (brace expansion). */
    val glob: Boolean,
    /** It starts with an unquoted ~. */
    val tilde: Boolean,
    /** It uses $'…' quoting: [value] is decoded, but the card shows escapes such as \x72. */
    val ansiC: Boolean,
) {
    val expands: Boolean get() = params.isNotEmpty() || substitutes
    /** Nothing in it is expanded or globbed: the program gets exactly [value] (a leading ~ aside). */
    val literal: Boolean get() = !expands && !glob
    override fun toString(): String = raw
}

/** A redirection: [op] is one of < > >> >| <> <& >& &> &>> << <<- <<<, [fd] the number written before it. */
class ShellRedirect internal constructor(val op: String, val fd: String?, val target: ShellWord?) {
    /** A here-document's lines (<<, <<-) once read; null for other operators, or one never closed. */
    var heredoc: String? = null
        internal set
    /** The here-document's delimiter was quoted: its lines are not expanded. */
    var heredocQuoted: Boolean = false
        internal set

    private val toFd: Boolean get() = target != null && (target.value == "-" || (target.value.isNotEmpty() && target.value.all { it in '0'..'9' }))

    /** It writes to a file named by [target] (not a descriptor copy such as 2>&1). */
    val writesFile: Boolean get() = when (op) {
        ">", ">>", ">|", "&>", "&>>", "<>" -> true
        ">&" -> !toFd
        else -> false
    }

    /** It empties the file before writing (>, >|, &>, >&file), so what was in it is lost. */
    val truncates: Boolean get() = when (op) {
        ">", ">|", "&>" -> true
        ">&" -> !toFd
        else -> false
    }

    /** It reads a file named by [target]. */
    val readsFile: Boolean get() = op == "<" || op == "<>" || (op == "<&" && !toFd)
}

/** Where a command sits besides its own pipeline. */
enum class ShellContext { SUBSHELL, GROUP, SUBSTITUTION, PROCESS_SUBSTITUTION, HEREDOC, CONDITION, FUNCTION, CASE }

/** One simple command: assignments, words (the first is the program) and redirections. */
class ShellCommand internal constructor(
    /** Unique within its [ShellParse], in the order commands start. */
    val id: Int,
    /** NAME=value words before the program. */
    val assignments: List<ShellWord>,
    val words: List<ShellWord>,
    val redirects: List<ShellRedirect>,
    /** Its pipeline's id; commands joined by | share one. */
    val pipeline: Int,
    /** Its place in the pipeline (0: the first), and the pipeline's length (a subshell or { } group counts as one). */
    val stage: Int,
    val stages: Int,
    /** Its pipeline ends with &. */
    val background: Boolean,
    /** The command whose word holds this one ($(…), `…`, <(…), a here-document), or null. */
    val parent: Int?,
    val contexts: Set<ShellContext>,
    /** The function whose body it is in, or null. */
    val function: String?,
) {
    val name: ShellWord? get() = words.firstOrNull()
    val args: List<ShellWord> get() = if (words.isEmpty()) emptyList() else words.subList(1, words.size)
}

/** A loop whose condition the analyzer checks ("while true", "until false", "for ((;;))"). */
class ShellLoop internal constructor(val kind: Kind, /** The condition command's id. */ val condition: Int?, /** An arithmetic condition or for (( )) header. */ val arithmetic: String?) {
    enum class Kind { WHILE, UNTIL, FOR_ARITHMETIC }
}

enum class ShellFeature {
    PIPE, AND_OR, SEQUENCE, BACKGROUND, REDIRECT, HEREDOC, HERESTRING, SUBSHELL, GROUP, COMMAND_SUBSTITUTION, BACKTICK,
    PROCESS_SUBSTITUTION, ARITHMETIC, PARAMETER, GLOB, TILDE, ASSIGNMENT, ARRAY, ANSI_C, COMMENT, KEYWORD, FUNCTION, CONDITION,
}

enum class ShellProblem { UNTERMINATED_QUOTE, UNTERMINATED_SUBSTITUTION, UNTERMINATED_HEREDOC, UNBALANCED, SYNTAX, TOO_DEEP, NUL }

/** What [ShellLexer.parse] read: every simple command (nested ones too), and how they are put together. */
class ShellParse internal constructor(
    val source: String,
    /** In the order they start; nested commands ($(…), subshells, here-documents) included, with their [ShellCommand.parent]. */
    val commands: List<ShellCommand>,
    val features: Set<ShellFeature>,
    val problems: Set<ShellProblem>,
    val functions: Set<String>,
    val loops: List<ShellLoop>,
) {
    private val byId: Map<Int, ShellCommand> = commands.associateBy { it.id }

    /** Read fully, with nothing Robot couldn't follow. */
    val readable: Boolean get() = problems.isEmpty()

    fun command(id: Int?): ShellCommand? = if (id == null) null else byId[id]
}

object ShellLexer {
    /** Nesting deeper than this ($( $( … ) ), subshells, backticks) is not followed: [ShellProblem.TOO_DEEP]. */
    const val MAX_DEPTH = 12

    fun parse(source: String): ShellParse {
        val sink = Sink()
        if (source.indexOf('\u0000') >= 0) sink.problems += ShellProblem.NUL
        Lexer(source, sink).parseList(Ctx(emptySet(), null, null, 0), closing = false)
        return ShellParse(source, sink.commands.sortedBy { it.id }, sink.features.toSet(), sink.problems.toSet(), sink.functions.toSet(), sink.loops.toList())
    }

    /**
     * Your script's arguments, split as a shell would split words but never run through one (B7.1 rule 6): blanks
     * separate words, '…' and "…" quote, a backslash escapes; ; | & $ ` ( ) < > are ordinary characters, so
     * `one 'two words' three;rm` is three arguments, the last "three;rm". Null when a quote isn't closed, or the text
     * holds a control character (a newline, a carriage return, NUL), or more than [maxArgs] arguments or one longer than
     * [maxChars].
     */
    fun argv(text: String, maxArgs: Int = 32, maxChars: Int = 1024): List<String>? {
        if (text.any { (it < ' ' && it != '\t') || it == '\u007f' }) return null
        val out = ArrayList<String>()
        var i = 0
        val n = text.length
        while (true) {
            while (i < n && (text[i] == ' ' || text[i] == '\t')) i++
            if (i >= n) break
            val sb = StringBuilder()
            while (i < n && text[i] != ' ' && text[i] != '\t') {
                when (val c = text[i]) {
                    '\'' -> {
                        val e = text.indexOf('\'', i + 1)
                        if (e < 0) return null
                        sb.append(text, i + 1, e)
                        i = e + 1
                    }
                    '"' -> {
                        i++
                        var closed = false
                        while (i < n) {
                            val d = text[i]
                            if (d == '"') { closed = true; i++; break }
                            if (d == '\\' && i + 1 < n && text[i + 1] in "\"\\$`") { sb.append(text[i + 1]); i += 2 } else { sb.append(d); i++ }
                        }
                        if (!closed) return null
                    }
                    '\\' -> {
                        if (i + 1 >= n) return null
                        sb.append(text[i + 1])
                        i += 2
                    }
                    else -> { sb.append(c); i++ }
                }
                if (sb.length > maxChars) return null
            }
            out += sb.toString()
            if (out.size > maxArgs) return null
        }
        return out
    }
}

private class Sink {
    val commands = ArrayList<ShellCommand>()
    val features: EnumSet<ShellFeature> = EnumSet.noneOf(ShellFeature::class.java)
    val problems: EnumSet<ShellProblem> = EnumSet.noneOf(ShellProblem::class.java)
    val functions = LinkedHashSet<String>()
    val loops = ArrayList<ShellLoop>()
    var nextCommand = 0
    var nextPipeline = 0
}

/** Where the commands being read sit: contexts, the command holding them, the function body, the nesting depth. */
private class Ctx(val contexts: Set<ShellContext>, val parent: Int?, val function: String?, val depth: Int) {
    fun nested(add: ShellContext, owner: Int?, function: String? = this.function): Ctx = Ctx(contexts + add, owner ?: parent, function, depth + 1)
}

private class WordState {
    var quoted = false
    val params = ArrayList<String>()
    var substitutes = false
    var glob = false
    var tilde = false
    var ansiC = false
    var bracket = false
    var braceDepth = 0
    var braceList = false
}

private class CmdBuilder(val id: Int, val pipeline: Int, val stage: Int, val contexts: Set<ShellContext>, val parent: Int?, val function: String?) {
    val assignments = ArrayList<ShellWord>()
    val words = ArrayList<ShellWord>()
    val redirects = ArrayList<ShellRedirect>()
    val empty: Boolean get() = assignments.isEmpty() && words.isEmpty() && redirects.isEmpty()
}

private enum class Conn { SEMI, NEWLINE, AND, OR, AMP }

private enum class CaseMode { SUBJECT, PATTERN, BODY }

private class Lexer(val src: String, val sink: Sink) {
    var i = 0
    val n = src.length
    private val heredocs = ArrayList<Pending>()

    private class Pending(val redirect: ShellRedirect, val delimiter: String, val quoted: Boolean, val stripTabs: Boolean, val ctx: Ctx, val owner: Int)

    private fun peek(k: Int): Char? = if (i + k < n) src[i + k] else null

    private fun skipBlanks() {
        while (i < n) {
            val c = src[i]
            if (c == ' ' || c == '\t') i++
            else if (c == '\\' && peek(1) == '\n') i += 2
            else break
        }
    }

    /** Reads commands to the end, or (with [closing]) to the ')' that closes this level, which it consumes. */
    fun parseList(ctx: Ctx, closing: Boolean) {
        if (ctx.depth > ShellLexer.MAX_DEPTH) {
            sink.problems += ShellProblem.TOO_DEEP
            i = n
            return
        }
        val st = ListState(ctx)
        while (true) {
            skipBlanks()
            if (i >= n) break
            val c = src[i]
            if (st.cond && c in "&|<>()") {
                // Inside [[ ]] these are the test's own operators, not the shell's.
                st.condOperator()
                continue
            }
            when {
                c == '#' -> {
                    sink.features += ShellFeature.COMMENT
                    while (i < n && src[i] != '\n') i++
                }
                c == '\n' -> {
                    i++
                    if (!st.pendingOperand) st.endPipeline(Conn.NEWLINE)
                    readHeredocs()
                }
                c == ')' -> when {
                    st.cases.lastOrNull() == CaseMode.PATTERN -> { i++; st.cases[st.cases.size - 1] = CaseMode.BODY }
                    closing -> { i++; st.finish(); return }
                    else -> { sink.problems += ShellProblem.UNBALANCED; i++; st.endPipeline(Conn.SEMI) }
                }
                c == '(' -> st.openParen()
                c == ';' -> st.semicolons()
                c == '|' -> when {
                    st.cases.lastOrNull() == CaseMode.PATTERN -> i++
                    peek(1) == '|' -> { i += 2; st.endPipeline(Conn.OR) }
                    else -> { i += if (peek(1) == '&') 2 else 1; st.pipeTo() }
                }
                c == '&' -> when {
                    peek(1) == '&' -> { i += 2; st.endPipeline(Conn.AND) }
                    peek(1) == '>' -> st.redirect(null)
                    else -> {
                        i++
                        sink.features += ShellFeature.BACKGROUND
                        st.endPipeline(Conn.AMP)
                    }
                }
                (c == '<' || c == '>') && peek(1) == '(' -> st.word()
                c == '<' || c == '>' -> st.redirect(null)
                c in '0'..'9' -> {
                    var j = i
                    while (j < n && src[j] in '0'..'9') j++
                    if (j < n && (src[j] == '<' || src[j] == '>') && (j + 1 >= n || src[j + 1] != '(')) {
                        val fd = src.substring(i, j)
                        i = j
                        st.redirect(fd)
                    } else st.word()
                }
                else -> st.word()
            }
        }
        if (closing) sink.problems += ShellProblem.UNTERMINATED_SUBSTITUTION
        st.finish()
    }

    /** The lines of the here-documents opened on the line just ended, in order. */
    private fun readHeredocs() {
        if (heredocs.isEmpty()) return
        val list = heredocs.toList()
        heredocs.clear()
        for (p in list) {
            val body = StringBuilder()
            var closed = false
            while (i < n) {
                val end = src.indexOf('\n', i).let { if (it < 0) n else it }
                val line = src.substring(i, end)
                i = if (end < n) end + 1 else n
                val cmp = if (p.stripTabs) line.trimStart('\t') else line
                if (cmp == p.delimiter) { closed = true; break }
                body.append(cmp).append('\n')
            }
            if (!closed) sink.problems += ShellProblem.UNTERMINATED_HEREDOC
            p.redirect.heredoc = body.toString()
            p.redirect.heredocQuoted = p.quoted
            // An unquoted delimiter: $( ), backticks and ${ } in the lines run when the command does.
            if (!p.quoted) Lexer(body.toString(), sink).scanExpansions(p.ctx.nested(ShellContext.HEREDOC, p.owner), p.owner)
        }
    }

    /** Text read like a here-document's lines or a double-quoted string without its quotes: only its expansions matter. */
    fun scanExpansions(ctx: Ctx, owner: Int?) {
        if (ctx.depth > ShellLexer.MAX_DEPTH) { sink.problems += ShellProblem.TOO_DEEP; i = n; return }
        readDouble(StringBuilder(), WordState(), ctx, owner, terminator = false)
    }

    private inner class ListState(val ctx: Ctx) {
        var cur: CmdBuilder? = null
        val pipe = ArrayList<CmdBuilder>()
        var pipeline = sink.nextPipeline++
        /** Stages started in this pipeline (commands and compound commands). */
        var stageCount = 0
        /** A compound command ({ }, ( ), if … fi) ended and is this pipeline's current stage. */
        var compound = false
        /** A |, && or || waits for the command after it. */
        var pendingOperand = false
        /** What ended the previous pipeline at this level (null: none yet). */
        var connector: Conn? = null
        /** Open { } groups, each with the function it is the body of (or null). */
        val groups = ArrayList<String?>()
        /** After name() or `function name`: the next group or subshell is its body. */
        var pendingFunction: String? = null
        /** The next command is this loop's condition. */
        var loopNext: ShellLoop.Kind? = null
        /** Inside for/select's header (up to ; or do): its words aren't commands. */
        var header = false
        val cases = ArrayList<CaseMode>()
        /** Inside [[ ]]. */
        var cond = false
        /** Keywords that close what is open: fi, done, esac. */
        val closers = ArrayList<String>()

        val function: String? get() = groups.lastOrNull { it != null } ?: ctx.function

        fun current(): CmdBuilder {
            cur?.let { return it }
            when (connector) {
                Conn.SEMI, Conn.NEWLINE -> if (pipe.isEmpty() && stageCount == 0) sink.features += ShellFeature.SEQUENCE
                Conn.AND, Conn.OR -> sink.features += ShellFeature.AND_OR
                Conn.AMP -> sink.features += ShellFeature.SEQUENCE
                null -> {}
            }
            val contexts = HashSet(ctx.contexts)
            if (groups.isNotEmpty()) contexts += ShellContext.GROUP
            if (function != null) contexts += ShellContext.FUNCTION
            if (cond) contexts += ShellContext.CONDITION
            if (cases.isNotEmpty()) contexts += ShellContext.CASE
            val b = CmdBuilder(sink.nextCommand++, pipeline, stageCount, contexts, ctx.parent, function)
            stageCount++
            loopNext?.let { sink.loops += ShellLoop(it, b.id, null); loopNext = null }
            cur = b
            pendingOperand = false
            compound = false
            return b
        }

        fun endCommand() {
            val b = cur ?: return
            if (!b.empty) pipe += b
            cur = null
        }

        fun pipeTo() {
            sink.features += ShellFeature.PIPE
            val had = cur != null || compound
            endCommand()
            if (!had || pendingOperand) sink.problems += ShellProblem.SYNTAX
            compound = false
            pendingOperand = true
        }

        fun endPipeline(conn: Conn) {
            endCommand()
            val empty = pipe.isEmpty() && stageCount == 0
            if (pendingOperand) sink.problems += ShellProblem.SYNTAX
            if (empty && (conn == Conn.AND || conn == Conn.OR || conn == Conn.AMP) && !compound) sink.problems += ShellProblem.SYNTAX
            for (b in pipe) {
                sink.commands += ShellCommand(b.id, b.assignments.toList(), b.words.toList(), b.redirects.toList(), b.pipeline, b.stage, stageCount,
                    conn == Conn.AMP, b.parent, b.contexts, b.function)
            }
            pipe.clear()
            if (!empty || compound) connector = conn
            pipeline = sink.nextPipeline++
            stageCount = 0
            compound = false
            pendingOperand = conn == Conn.AND || conn == Conn.OR
            if (header && (conn == Conn.SEMI || conn == Conn.NEWLINE)) header = false
        }

        fun finish() {
            val open = frames.isNotEmpty()
            while (frames.isNotEmpty()) compoundEnd()
            endPipeline(Conn.SEMI)
            if (open || groups.isNotEmpty() || cases.isNotEmpty() || cond || closers.isNotEmpty() || header || loopNext != null) sink.problems += ShellProblem.UNBALANCED
            if (pendingFunction != null) sink.problems += ShellProblem.SYNTAX
        }

        /** The pipeline a compound command ({ }, if, while, for, case) is a stage of, set aside while its own commands are read. */
        private inner class Frame(val pipeline: Int, val stageCount: Int, val pipe: List<CmdBuilder>, val connector: Conn?)

        private val frames = ArrayList<Frame>()

        /** A compound command starts: it is the next stage of this pipeline, and its commands make pipelines of their own. */
        fun compoundStart() {
            endCommand()
            frames += Frame(pipeline, stageCount + 1, pipe.toList(), connector)
            pipe.clear()
            pipeline = sink.nextPipeline++
            stageCount = 0
            pendingOperand = false
            compound = false
        }

        /** A compound command ends: back to the pipeline it is a stage of. */
        fun compoundEnd() {
            endCommand()
            if (frames.isEmpty()) {
                compound = true
                pendingOperand = false
                return
            }
            if (pipe.isNotEmpty() || stageCount > 0) endPipeline(Conn.SEMI)
            val f = frames.removeAt(frames.size - 1)
            pipeline = f.pipeline
            stageCount = f.stageCount
            pipe.clear()
            pipe.addAll(f.pipe)
            connector = f.connector
            compound = true
            pendingOperand = false
        }

        fun semicolons() {
            when {
                src.startsWith(";;&", i) || src.startsWith(";;", i) || src.startsWith(";&", i) -> {
                    i += if (src.startsWith(";;&", i)) 3 else 2
                    if (cases.lastOrNull() == CaseMode.BODY) {
                        endCommand()
                        val empty = pipe.isEmpty() && stageCount == 0
                        if (!empty) endPipeline(Conn.SEMI) else { compound = false; pendingOperand = false }
                        cases[cases.size - 1] = CaseMode.PATTERN
                    } else sink.problems += ShellProblem.SYNTAX
                }
                else -> { i++; endPipeline(Conn.SEMI) }
            }
        }

        fun openParen() {
            val b = cur
            when {
                // NAME ( ) — a function definition.
                b != null && b.words.size == 1 && b.assignments.isEmpty() && b.redirects.isEmpty() && b.words[0].literal && !b.words[0].quoted &&
                    closesEmpty() -> {
                    val name = b.words[0].value
                    cur = null
                    stageCount = maxOf(0, stageCount - 1)
                    sink.features += ShellFeature.FUNCTION
                    sink.functions += name
                    pendingFunction = name
                }
                b == null && cases.lastOrNull() == CaseMode.PATTERN -> i++
                b == null && src.startsWith("((", i) -> arithmeticCommand()
                b == null -> subshell()
                else -> {
                    sink.problems += ShellProblem.SYNTAX
                    i++
                }
            }
        }

        /** At "(", followed by blanks and ")": consumes both. */
        private fun closesEmpty(): Boolean {
            var j = i + 1
            while (j < n && (src[j] == ' ' || src[j] == '\t')) j++
            if (j < n && src[j] == ')') { i = j + 1; return true }
            return false
        }

        private fun subshell() {
            compoundStart()
            sink.features += ShellFeature.SUBSHELL
            i++
            val fn = pendingFunction
            pendingFunction = null
            parseList(ctx.nested(ShellContext.SUBSHELL, null, fn ?: function), closing = true)
            compoundEnd()
        }

        private fun arithmeticCommand() {
            val start = i
            i += 2
            val (inner, closed) = scanArith()
            if (!closed) {
                // "((" that isn't arithmetic: a subshell inside a subshell, as bash reads it too.
                i = start
                subshell()
                return
            }
            compoundStart()
            sink.features += ShellFeature.ARITHMETIC
            sink.features += ShellFeature.KEYWORD
            loopNext?.let { sink.loops += ShellLoop(it, null, inner); loopNext = null }
            Lexer(inner, sink).scanExpansions(ctx.nested(ShellContext.SUBSHELL, null), null)
            compoundEnd()
        }

        fun redirect(fd: String?) {
            val op = when {
                src.startsWith("&>>", i) -> "&>>"
                src.startsWith("&>", i) -> "&>"
                src.startsWith("<<<", i) -> "<<<"
                src.startsWith("<<-", i) -> "<<-"
                src.startsWith("<<", i) -> "<<"
                src.startsWith("<>", i) -> "<>"
                src.startsWith("<&", i) -> "<&"
                src.startsWith(">>", i) -> ">>"
                src.startsWith(">|", i) -> ">|"
                src.startsWith(">&", i) -> ">&"
                src[i] == '<' -> "<"
                else -> ">"
            }
            i += op.length
            val b = current()
            skipBlanks()
            val target = readWord(ctx, b.id)
            if (target == null) {
                sink.problems += ShellProblem.SYNTAX
                return
            }
            val r = ShellRedirect(op, fd, target)
            b.redirects += r
            when (op) {
                "<<", "<<-" -> {
                    sink.features += ShellFeature.HEREDOC
                    heredocs += Pending(r, target.value, target.quoted, op == "<<-", ctx, b.id)
                }
                "<<<" -> sink.features += ShellFeature.HERESTRING
                else -> sink.features += ShellFeature.REDIRECT
            }
        }

        /** An operator inside [[ ]], kept as a word of the test. */
        fun condOperator() {
            val start = i
            i += if (i + 1 < n && src[i + 1] == src[i] && src[i] in "&|") 2 else 1
            val op = src.substring(start, i)
            current().words += ShellWord(op, op, false, emptyList(), false, false, false, false)
        }

        fun word() {
            if (cur == null) {
                val k = peekKeyword()
                if (k != null && keywordApplies(k)) {
                    i += k.length
                    keyword(k)
                    sink.features += ShellFeature.KEYWORD
                    return
                }
            }
            val mode = cases.lastOrNull()
            if (mode == CaseMode.SUBJECT || mode == CaseMode.PATTERN || header) {
                // case's subject and patterns, for/select's names and list: words, not commands.
                val w = readWord(ctx, null) ?: return syntaxSkip()
                if (mode == CaseMode.SUBJECT && w.value == "in" && !w.quoted) cases[cases.size - 1] = CaseMode.PATTERN
                return
            }
            val b = current()
            val w = readWord(ctx, b.id) ?: return syntaxSkip()
            if (b.words.isEmpty() && assignment(w)) {
                b.assignments += w
                sink.features += ShellFeature.ASSIGNMENT
                return
            }
            b.words += w
            if (cond && w.value == "]]" && !w.quoted) cond = false
        }

        /** A character no word can start with here: say so and move on. */
        private fun syntaxSkip() {
            sink.problems += ShellProblem.SYNTAX
            i++
        }

        private fun keywordApplies(k: String): Boolean = when {
            cases.lastOrNull() == CaseMode.PATTERN -> k == "esac"
            cases.lastOrNull() == CaseMode.SUBJECT -> false
            header -> k == "do"
            cond -> false
            else -> true
        }

        /** A reserved word where a command starts, read as is (reserved words are never quoted or expanded). */
        private fun keyword(v: String) {
            if (header && v == "do") {
                header = false
                return
            }
            if (cases.lastOrNull() == CaseMode.PATTERN && v == "esac") {
                closeWith("esac")
                cases.removeAt(cases.size - 1)
                compoundEnd()
                return
            }
            when (v) {
                "if" -> { compoundStart(); closers += "fi" }
                "then", "else", "elif", "!" -> endCommand()
                "do" -> { endCommand(); loopNext = null }
                "time" -> {
                    endCommand()
                    skipBlanks()
                    if (src.startsWith("-p", i) && (i + 2 >= n || src[i + 2] == ' ' || src[i + 2] == '\t')) i += 2
                }
                "fi" -> { closeWith("fi"); compoundEnd() }
                "done" -> { closeWith("done"); compoundEnd() }
                "while" -> { compoundStart(); closers += "done"; loopNext = ShellLoop.Kind.WHILE }
                "until" -> { compoundStart(); closers += "done"; loopNext = ShellLoop.Kind.UNTIL }
                "for", "select" -> {
                    compoundStart()
                    closers += "done"
                    skipBlanks()
                    if (src.startsWith("((", i)) {
                        i += 2
                        val (inner, closed) = scanArith()
                        if (!closed) sink.problems += ShellProblem.SYNTAX
                        sink.features += ShellFeature.ARITHMETIC
                        sink.loops += ShellLoop(ShellLoop.Kind.FOR_ARITHMETIC, null, inner)
                        Lexer(inner, sink).scanExpansions(ctx.nested(ShellContext.SUBSHELL, null), null)
                    } else header = true
                }
                "case" -> { compoundStart(); closers += "esac"; cases += CaseMode.SUBJECT }
                "esac" -> {
                    closeWith("esac")
                    if (cases.isNotEmpty()) cases.removeAt(cases.size - 1)
                    compoundEnd()
                }
                "{" -> {
                    compoundStart()
                    sink.features += ShellFeature.GROUP
                    groups += pendingFunction
                    pendingFunction = null
                }
                "}" -> {
                    if (groups.isEmpty()) {
                        sink.problems += ShellProblem.UNBALANCED
                    } else groups.removeAt(groups.size - 1)
                    compoundEnd()
                }
                "[[" -> {
                    sink.features += ShellFeature.CONDITION
                    current().words += ShellWord("[[", "[[", false, emptyList(), false, false, false, false)
                    cond = true
                }
                "function" -> {
                    sink.features += ShellFeature.FUNCTION
                    skipBlanks()
                    val name = readWord(ctx, null)
                    if (name == null || !name.literal) {
                        sink.problems += ShellProblem.SYNTAX
                    } else {
                        sink.functions += name.value
                        pendingFunction = name.value
                        skipBlanks()
                        if (i < n && src[i] == '(') closesEmpty()
                    }
                }
                "coproc" -> { endCommand(); sink.features += ShellFeature.BACKGROUND }
            }
        }

        /** The reserved word that starts here, followed by a blank, an operator or the end; or null. */
        private fun peekKeyword(): String? {
            var j = i
            while (j < n && src[j] !in " \t\n;&|()<>") j++
            val k = src.substring(i, j)
            return if (k in KEYWORDS) k else null
        }

        private fun closeWith(k: String) {
            if (closers.lastOrNull() == k) closers.removeAt(closers.size - 1) else sink.problems += ShellProblem.UNBALANCED
        }
    }

    /** An assignment word: NAME=… or NAME+=…, the name unquoted. */
    private fun assignment(w: ShellWord): Boolean {
        val r = w.raw
        if (r.isEmpty() || !(r[0].isAsciiLetter() || r[0] == '_')) return false
        var j = 1
        while (j < r.length && (r[j].isAsciiLetter() || r[j] in '0'..'9' || r[j] == '_')) j++
        return j < r.length && (r[j] == '=' || (r[j] == '+' && j + 1 < r.length && r[j + 1] == '='))
    }

    /** A word from here, or null when none starts here. [owner]: the command it belongs to (for nested commands). */
    fun readWord(ctx: Ctx, owner: Int?): ShellWord? {
        val start = i
        val v = StringBuilder()
        val w = WordState()
        if (i < n && src[i] == '~') {
            w.tilde = true
            sink.features += ShellFeature.TILDE
        }
        loop@ while (i < n) {
            val c = src[i]
            when (c) {
                ' ', '\t', '\n', ';', '&', '|', ')' -> break@loop
                '(' -> {
                    if (isArrayStart(start)) readArray(v, w, ctx, owner) else break@loop
                }
                '<', '>' -> {
                    if (peek(1) == '(') processSubstitution(v, w, ctx, owner) else break@loop
                }
                '\\' -> {
                    when {
                        i + 1 >= n -> { v.append('\\'); i++ }
                        src[i + 1] == '\n' -> i += 2
                        else -> { v.append(src[i + 1]); w.quoted = true; i += 2 }
                    }
                }
                '\'' -> {
                    w.quoted = true
                    val e = src.indexOf('\'', i + 1)
                    if (e < 0) {
                        sink.problems += ShellProblem.UNTERMINATED_QUOTE
                        v.append(src, i + 1, n)
                        i = n
                    } else {
                        v.append(src, i + 1, e)
                        i = e + 1
                    }
                }
                '"' -> {
                    w.quoted = true
                    i++
                    readDouble(v, w, ctx, owner, terminator = true)
                }
                '$' -> dollar(v, w, ctx, owner, inDouble = false)
                '`' -> backtick(v, w, ctx, owner)
                '*', '?' -> { w.glob = true; v.append(c); i++ }
                '[' -> { w.bracket = true; v.append(c); i++ }
                ']' -> { if (w.bracket) w.glob = true; v.append(c); i++ }
                '{' -> { w.braceDepth++; v.append(c); i++ }
                ',' -> { if (w.braceDepth > 0) w.braceList = true; v.append(c); i++ }
                '.' -> { if (w.braceDepth > 0 && peek(1) == '.') w.braceList = true; v.append(c); i++ }
                '}' -> {
                    if (w.braceDepth > 0) {
                        w.braceDepth--
                        if (w.braceList) w.glob = true
                    }
                    v.append(c)
                    i++
                }
                else -> { v.append(c); i++ }
            }
        }
        if (i == start) return null
        if (w.glob) sink.features += ShellFeature.GLOB
        if (w.params.isNotEmpty()) sink.features += ShellFeature.PARAMETER
        return ShellWord(src.substring(start, i), v.toString(), w.quoted, w.params.toList(), w.substitutes, w.glob, w.tilde, w.ansiC)
    }

    private fun isArrayStart(start: Int): Boolean {
        if (i - start < 2 || src[i - 1] != '=') return false
        var j = start
        if (!(src[j].isAsciiLetter() || src[j] == '_')) return false
        j++
        while (j < i - 1 && (src[j].isAsciiLetter() || src[j] in '0'..'9' || src[j] == '_')) j++
        return j == i - 1 || (j == i - 2 && src[j] == '+')
    }

    /** NAME=( … ): the elements are words. */
    private fun readArray(v: StringBuilder, w: WordState, ctx: Ctx, owner: Int?) {
        sink.features += ShellFeature.ARRAY
        val s = i
        i++
        while (true) {
            while (i < n && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n')) i++
            if (i >= n) { sink.problems += ShellProblem.UNBALANCED; break }
            if (src[i] == ')') { i++; break }
            val e = readWord(ctx, owner)
            if (e == null) { sink.problems += ShellProblem.SYNTAX; break }
            if (e.expands) w.substitutes = w.substitutes || e.substitutes
            if (e.params.isNotEmpty()) w.params += e.params
        }
        v.append(src, s, i)
    }

    private fun processSubstitution(v: StringBuilder, w: WordState, ctx: Ctx, owner: Int?) {
        val s = i
        sink.features += ShellFeature.PROCESS_SUBSTITUTION
        w.substitutes = true
        i += 2
        parseList(ctx.nested(ShellContext.PROCESS_SUBSTITUTION, owner), closing = true)
        v.append(src, s, i)
    }

    /** Inside "…" (or a here-document's lines when not [terminator]): only \, $ and ` are special. */
    private fun readDouble(v: StringBuilder, w: WordState, ctx: Ctx, owner: Int?, terminator: Boolean) {
        while (i < n) {
            val c = src[i]
            when (c) {
                '"' -> if (terminator) { i++; return } else { v.append(c); i++ }
                '\\' -> {
                    val d = peek(1)
                    when {
                        d == null -> { v.append('\\'); i++ }
                        d == '\n' -> i += 2
                        d == '$' || d == '`' || d == '\\' || (d == '"' && terminator) -> { v.append(d); i += 2 }
                        else -> { v.append('\\').append(d); i += 2 }
                    }
                }
                '$' -> dollar(v, w, ctx, owner, inDouble = true)
                '`' -> backtick(v, w, ctx, owner)
                else -> { v.append(c); i++ }
            }
        }
        if (terminator) sink.problems += ShellProblem.UNTERMINATED_QUOTE
    }

    private fun dollar(v: StringBuilder, w: WordState, ctx: Ctx, owner: Int?, inDouble: Boolean) {
        val s = i
        val next = peek(1)
        when {
            next == '(' && peek(2) == '(' -> {
                i += 3
                val (inner, closed) = scanArith()
                if (closed) {
                    sink.features += ShellFeature.ARITHMETIC
                    w.substitutes = true
                    Lexer(inner, sink).scanExpansions(ctx.nested(ShellContext.SUBSTITUTION, owner), owner)
                } else {
                    // $( (…) …): a command substitution that starts with a subshell.
                    i = s + 2
                    commandSubstitution(ctx, owner, w)
                }
            }
            next == '(' -> {
                i += 2
                commandSubstitution(ctx, owner, w)
            }
            next == '{' -> {
                i += 2
                val inner = scanBraced()
                w.params += if (isName(inner)) inner else "?"
                if (!isName(inner)) Lexer(inner, sink).scanExpansions(ctx.nested(ShellContext.SUBSTITUTION, owner), owner)
            }
            next == '\'' && !inDouble -> {
                i += 2
                ansiC(v, w)
                return
            }
            next == '"' && !inDouble -> {
                i += 2
                w.quoted = true
                readDouble(v, w, ctx, owner, terminator = true)
                return
            }
            next != null && (next.isAsciiLetter() || next == '_') -> {
                var j = i + 1
                while (j < n && (src[j].isAsciiLetter() || src[j] in '0'..'9' || src[j] == '_')) j++
                w.params += src.substring(i + 1, j)
                i = j
            }
            next != null && (next in '0'..'9' || next in "@*#?$!-") -> {
                w.params += "?"
                i += 2
            }
            else -> {
                v.append('$')
                i++
                return
            }
        }
        v.append(src, s, i)
    }

    private fun commandSubstitution(ctx: Ctx, owner: Int?, w: WordState) {
        sink.features += ShellFeature.COMMAND_SUBSTITUTION
        w.substitutes = true
        parseList(ctx.nested(ShellContext.SUBSTITUTION, owner), closing = true)
    }

    private fun backtick(v: StringBuilder, w: WordState, ctx: Ctx, owner: Int?) {
        val s = i
        sink.features += ShellFeature.BACKTICK
        w.substitutes = true
        i++
        val inner = StringBuilder()
        var closed = false
        while (i < n) {
            val c = src[i]
            if (c == '\\' && i + 1 < n && src[i + 1] in "`\\$") { inner.append(src[i + 1]); i += 2; continue }
            if (c == '`') { closed = true; i++; break }
            inner.append(c)
            i++
        }
        if (!closed) sink.problems += ShellProblem.UNTERMINATED_SUBSTITUTION
        v.append(src, s, i)
        if (ctx.depth + 1 > ShellLexer.MAX_DEPTH) { sink.problems += ShellProblem.TOO_DEEP; return }
        Lexer(inner.toString(), sink).parseList(ctx.nested(ShellContext.SUBSTITUTION, owner), closing = false)
    }

    /** After "((" or "$((": the text up to the matching "))", and whether "))" closed it (else a lone ")" did, or nothing). */
    private fun scanArith(): Pair<String, Boolean> {
        val s = i
        var d = 0
        while (i < n) {
            when (src[i]) {
                '(' -> d++
                ')' -> {
                    if (d == 0) {
                        val inner = src.substring(s, i)
                        return if (peek(1) == ')') { i += 2; inner to true } else { i++; inner to false }
                    }
                    d--
                }
                '\\' -> i++
                '\'' -> { val e = src.indexOf('\'', i + 1); i = if (e < 0) n - 1 else e }
                '"' -> {
                    i++
                    while (i < n && src[i] != '"') { if (src[i] == '\\') i++; i++ }
                }
            }
            i++
        }
        sink.problems += ShellProblem.UNTERMINATED_SUBSTITUTION
        i = n
        return src.substring(s, n) to false
    }

    /** After "${": the text up to the matching "}". */
    private fun scanBraced(): String {
        val s = i
        var d = 1
        while (i < n) {
            when (src[i]) {
                '{' -> d++
                '}' -> {
                    d--
                    if (d == 0) { val inner = src.substring(s, i); i++; return inner }
                }
                '\\' -> i++
                '\'' -> { val e = src.indexOf('\'', i + 1); i = if (e < 0) n - 1 else e }
                '"' -> {
                    i++
                    while (i < n && src[i] != '"') { if (src[i] == '\\') i++; i++ }
                }
            }
            i++
        }
        sink.problems += ShellProblem.UNTERMINATED_SUBSTITUTION
        i = n
        return src.substring(s, n)
    }

    /** $'…': decoded as bash does (\n, \t, \e, \xHH, \uHHHH, \nnn octal…). */
    private fun ansiC(v: StringBuilder, w: WordState) {
        sink.features += ShellFeature.ANSI_C
        w.ansiC = true
        w.quoted = true
        while (i < n) {
            val c = src[i]
            if (c == '\'') { i++; return }
            if (c != '\\' || i + 1 >= n) { v.append(c); i++; continue }
            val e = src[i + 1]
            i += 2
            when (e) {
                'a' -> v.append('\u0007')
                'b' -> v.append('\b')
                'e', 'E' -> v.append('\u001b')
                'f' -> v.append('\u000c')
                'n' -> v.append('\n')
                'r' -> v.append('\r')
                't' -> v.append('\t')
                'v' -> v.append('\u000b')
                'x' -> v.appendCodePoint(hex(2) ?: 'x'.code)
                'u' -> v.appendCodePoint(hex(4) ?: 'u'.code)
                'U' -> v.appendCodePoint(hex(8)?.takeIf { it <= Character.MAX_CODE_POINT } ?: 'U'.code)
                'c' -> if (i < n) { v.append((src[i].code and 0x1f).toChar()); i++ }
                in '0'..'7' -> {
                    var value = e - '0'
                    var k = 0
                    while (k < 2 && i < n && src[i] in '0'..'7') { value = value * 8 + (src[i] - '0'); i++; k++ }
                    v.append((value and 0xff).toChar())
                }
                else -> v.append(e)
            }
        }
        sink.problems += ShellProblem.UNTERMINATED_QUOTE
    }

    /** Up to [max] hex digits from here, or null when there are none. */
    private fun hex(max: Int): Int? {
        var value = 0
        var k = 0
        while (k < max && i < n && src[i].let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            value = value * 16 + Character.digit(src[i], 16)
            i++
            k++
        }
        return if (k == 0) null else value
    }

    private fun isName(s: String): Boolean =
        s.isNotEmpty() && (s[0].isAsciiLetter() || s[0] == '_') && s.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** bash's reserved words that matter here (recognized only where a command starts, unquoted). */
private val KEYWORDS = setOf("if", "then", "else", "elif", "fi", "do", "done", "while", "until", "for", "select", "case", "esac", "{", "}", "!",
    "[[", "function", "time", "coproc")
