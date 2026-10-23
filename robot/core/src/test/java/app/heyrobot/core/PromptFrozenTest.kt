// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryPrompt
import app.heyrobot.core.memory.MemoryRecall
import app.heyrobot.core.memory.PromptPacking
import app.heyrobot.core.memory.Recalled
import app.heyrobot.core.memory.RecallBy
import app.heyrobot.core.skills.SkillSpecs
import app.heyrobot.core.skills.SkillToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.2 (job 4, the reading budget): every prompt Robot builds for its model today, written out and compared with a frozen
 * record (test resource `prompts/full.txt`, first written on feature/1.2-selftest at 4cb885c5, before job 4 changed any
 * code). The record is the proof that the compact prompt changes nothing where the model reads fast (the TPU, the GPU):
 * the system prompt for each combination of its switches, each tool's description (as the JVM's org.json writes it: its
 * keys may come in another order than Android's, so this proves job 4 changed nothing, not the device's exact bytes), the
 * text-call hint, the fixed part's estimate, memory's budget and what it packs, and the history a rebuilt conversation keeps, in
 * English and French.
 *
 * Every prompt here is built through job 4's code (the compact switch, ToolSpecs.json, PrefillBudget's history, memory's
 * budget) with what a fast path gets ([PrefillBudget.of] on the GPU or the TPU, even after a slow measurement there).
 *
 * Never re-record this file to make a change pass: a difference here is a change in what the model reads, and needs a
 * reason in the commit that makes it, and a line here.
 *
 * - 1.4 F9 (Robot Files' two reads): find_files' and read_file's descriptions, their two examples in the text-call hint, and
 *   every "every group" estimate (329 more, ToolBudget giving way as before). Nothing else of any prompt changed.
 */
class PromptFrozenTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 14, 5, 0, 0, ZoneId.of("America/Halifax"))
    private val termuxLine = TermuxCalls.promptLine(listOf("backup", "sync"), commands = true)
    private val pinned = "About the user (they told you this, in their words; use it when it helps): “Call me Sam.”"
    private val skill = SkillToolSpec("skill_morning", "Your morning routine: weather, then the day's events.", listOf("city" to "Which city?"))

    /**
     * 1.2: the budget of a fast path, as PrefillBudget gives it on the GPU even when it measured a slow GPU, and on the TPU:
     * every prompt below goes through job 4's code with it, and must come out as frozen before job 4.
     */
    private val gpu = PrefillBudget.of(ModelBackend.GPU, measuredTps = 20.0, estimateTps = 20.0)
    private val tpu = PrefillBudget.of(ModelBackend.TPU, measuredTps = 20.0, estimateTps = 500.0)
    private val fast = gpu.also { assertEquals(PrefillBudget.NONE, it); assertEquals(PrefillBudget.NONE, tpu) }
    private val c = fast.compact

    /** The system prompt's switches, each on alone, and the combinations a phone meets. */
    private class Sys(val name: String, val build: (ZonedDateTime) -> String)

    private val systems: List<Sys> = listOf(
        Sys("plain") { Prompt.system(it, locked = false, compact = c) },
        Sys("direct") { Prompt.system(it, locked = false, direct = true, compact = c) },
        Sys("askHelper") { Prompt.system(it, locked = false, askHelper = true, compact = c) },
        Sys("askHelper direct") { Prompt.system(it, locked = false, askHelper = true, direct = true, compact = c) },
        Sys("askHelper other home") { Prompt.system(it, locked = false, askHelper = true, helper = "Qwen", helperHome = true, helperSearches = false, compact = c) },
        Sys("askHelper other online web weather") { Prompt.system(it, locked = false, askHelper = true, helper = "Mistral", web = true, weather = true, compact = c) },
        Sys("texting") { Prompt.system(it, locked = false, texting = true, compact = c) },
        Sys("weather") { Prompt.system(it, locked = false, weather = true, compact = c) },
        Sys("phone") { Prompt.system(it, locked = false, phone = true, compact = c) },
        Sys("web") { Prompt.system(it, locked = false, web = true, compact = c) },
        Sys("web weather") { Prompt.system(it, locked = false, web = true, weather = true, compact = c) },
        Sys("browser") { Prompt.system(it, locked = false, browser = true, compact = c) },
        Sys("termux") { Prompt.system(it, locked = false, termux = termuxLine, compact = c) },
        Sys("skills") { Prompt.system(it, locked = false, skills = true, compact = c) },
        Sys("driving") { Prompt.system(it, locked = false, driving = true, compact = c) },
        Sys("locked default") { Prompt.system(it, locked = true, compact = c) },
        Sys("locked none") { Prompt.system(it, locked = true, lockedTools = emptySet(), compact = c) },
        Sys("locked some") { Prompt.system(it, locked = true, lockedTools = setOf(ToolName.SET_TIMER, ToolName.FLASHLIGHT, ToolName.ADD_NOTE), compact = c) },
        Sys("locked driving direct") { Prompt.system(it, locked = true, direct = true, driving = true, compact = c) },
        Sys("everything") {
            Prompt.system(it, locked = true, askHelper = true, texting = true, weather = true, phone = true, direct = true,
                lockedTools = setOf(ToolName.SET_TIMER), helper = "Claude", driving = true, web = true, termux = termuxLine, browser = true, skills = true,
                compact = c)
        },
        Sys("memory tools pinned direct") { MemoryPrompt.system(Prompt.system(it, locked = false, direct = true, compact = c), tools = true, pinned = pinned) },
        Sys("memory pinned only") { MemoryPrompt.system(Prompt.system(it, locked = false, compact = c), tools = false, pinned = pinned) },
        Sys("chat only") { Prompt.chatSystem(it) },
        Sys("chat only direct") { Prompt.chatSystem(it, direct = true) },
    )

    private val toolSets: List<Pair<String, List<ToolName>>> = listOf(
        "core" to Prompt.CORE_TOOLS,
        "people" to ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.PEOPLE), RobotSettings()),
        "every group" to ToolName.entries.filter { it.forModel && !it.termux },
        "none" to emptyList(),
    )

    private fun ex(q: String, a: String) = listOf(PromptTurn(PromptRole.USER, q), PromptTurn(PromptRole.MODEL, a))

    private val english: List<PromptTurn> = (1..30).flatMap { i ->
        ex("Question $i: what should I cook tonight with rice, beans and a few peppers?",
            "Answer $i: a quick rice and bean skillet with peppers, cumin and lime works well; it takes about 25 minutes.")
    }
    private val french: List<PromptTurn> = (1..30).flatMap { i ->
        ex("Question $i : qu'est-ce que je peux cuisiner ce soir avec du riz, des fèves et quelques poivrons?",
            "Réponse $i : une poêlée de riz et de fèves aux poivrons, au cumin et à la lime, prête en à peu près 25 minutes.")
    }

    private fun one(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")

    private fun record(): String = buildString {
        append("# The prompts Robot builds for its model (PromptFrozenTest). Frozen: never re-record to make a change pass.\n")
        append("\n# System prompts.\n")
        for (s in systems) {
            val text = s.build(now)
            append("== ").append(s.name).append('\n')
            append(one(text)).append('\n')
            append("tokens ").append(TokenEstimate.text(text))
            for ((n, t) in toolSets) append(" · fixed ").append(n).append(' ').append(Prompt.fixedTokens(text, t, compact = c))
            append(" · fixed core+skill ").append(Prompt.fixedTokens(text, Prompt.CORE_TOOLS, listOf(skill)))
            append('\n')
        }
        append("\n# Tools as LiteRT-LM gets them.\n")
        for (t in ToolName.entries.filter { it.forModel }) {
            val json = ToolSpecs.json(t, c)
            append(json).append(" · tokens ").append(TokenEstimate.text(json)).append('\n')
        }
        append(skill.openApiJson()).append('\n')
        append("\n# The text-call hint.\n")
        append(one(Prompt.textCallHint(ToolName.entries.filter { it.forModel }, listOf(skill)))).append('\n')
        append(one(Prompt.textCallHint(Prompt.CORE_TOOLS))).append('\n')
        append(one(SkillSpecs.SYSTEM_LINE)).append('\n')
        append("\n# Memory.\n")
        for (max in listOf(4096, 3000, 2048)) append("budget ").append(max).append(' ').append(MemoryRecall.budget(max, c)).append('\n')
        val t0 = 1_760_000_000_000L
        val items = listOf(
            MemoryItem("p1", "Call me Sam", pinned = true, createdAt = t0),
            MemoryItem("p2", "I prefer metric units", pinned = true, createdAt = t0 + 1),
            MemoryItem("p3", "My partner is Alex and we live in Halifax near the harbour, on the third floor of a brick building", pinned = true, createdAt = t0 + 2),
        ) + (1..8).map { MemoryItem("n$it", "Note $it about rice, beans, peppers and the dinner plan for the family on Sunday", createdAt = t0 + 10 + it) }
        val ranked = items.filter { !it.pinned }.map { Recalled(it, 1.0, RecallBy.WORDS) }
        for (max in listOf(4096, 2048)) {
            val p = PromptPacking.pack("what about rice and beans for dinner", items, ranked, t0 + 100, unlocked = true,
                budget = MemoryRecall.budget(max, c), zone = ZoneId.of("UTC"))
            append("packed ").append(max).append(": pinned ").append(p.pinnedIds).append(" retrieved ").append(p.retrievedIds)
                .append(" tokens ").append(p.pinnedTokens).append('/').append(p.notesTokens).append('\n')
            append(one(p.systemLine ?: "-")).append('\n').append(one(p.notesBlock ?: "-")).append('\n')
        }
        append("\n# A rebuilt conversation's history.\n")
        for ((lang, history) in listOf("en" to english, "fr" to french)) {
            for ((n, sys) in listOf("plain" to Prompt.system(now, locked = false, direct = true, compact = c), "everything" to systems.first { it.name == "everything" }.build(now))) {
                for ((tn, tools) in toolSets.take(3)) {
                    val fixed = Prompt.fixedTokens(sys, tools, compact = c)
                    val turn = TokenEstimate.turn(PromptTurn(PromptRole.USER, "And for tomorrow night?"))
                    val budget = fast.history(fixed, turn)
                    assertEquals(ContextWindow.historyBudget(fixed, turn), budget)
                    val kept = ContextWindow.fit(history, budget)
                    append(lang).append(' ').append(n).append(' ').append(tn).append(": fixed ").append(fixed).append(" · newTurnLimit ")
                        .append(ContextWindow.newTurnLimit(fixed)).append(" · budget ").append(budget).append(" · kept ").append(kept.size)
                        .append(" turns, ").append(kept.sumOf(TokenEstimate::turn)).append(" tokens, from ").append(one(kept.firstOrNull()?.text ?: "-").take(14)).append('\n')
                }
            }
        }
    }

    /**
     * 1.4 (Robot Files): the two file one-shots, FILE_READ and FILE_ANSWER (core files FilePrompt, FileQuote), written out
     * as :brain gets them, in their own record (test resource `prompts/files.txt`, first written by job F4): the fixed
     * prompts, the frame for every kind and source, and the requests for a short receipt and a long one at 4,096 and 2,048
     * tokens of context. The record above doesn't change with them: no prompt the panel builds holds a file's prompt.
     * Never re-record this file to make a change pass either.
     */
    private fun fileRecord(): String = buildString {
        val fp = app.heyrobot.core.files.FilePrompt
        val fq = app.heyrobot.core.files.FileQuote
        append("# The file one-shots Robot builds for its model (PromptFrozenTest, 1.4). Frozen: never re-record to make a change pass.\n")
        append("\n# System prompts.\n")
        for ((n, t) in listOf("read picture" to fp.READ_PICTURE, "read text" to fp.READ_TEXT, "answer" to fp.ANSWER))
            append("== ").append(n).append('\n').append(one(t)).append('\n').append("tokens ").append(TokenEstimate.text(t)).append('\n')
        append("\n# Frames.\n")
        for (k in app.heyrobot.core.files.FileKind.entries) append(k).append(": ").append(fq.frame(k, app.heyrobot.core.files.FileSource.SHARE) ?: "-").append('\n')
        for (src in app.heyrobot.core.files.FileSource.entries) append(src).append(": ").append(fq.frame(app.heyrobot.core.files.FileKind.RECEIPT, src)).append('\n')
        append(fq.frame(app.heyrobot.core.files.FileKind.UNKNOWN, app.heyrobot.core.files.FileSource.SCAN, picture = true)).append('\n')
        val short = "HARBOURSIDE GROCER\n2026-08-14 16:48\nSUBTOTAL 63.74\nHST 13% 2.57\nTOTAL 66.31 CAD"
        val long = (1..300).joinToString("\n") { "ITEM $it 4471-2290-3318 ${it * 7}.${it % 100} T" }
        fun req(name: String, r: app.heyrobot.core.files.FilePrompt.Request?, max: Int) {
            append("== ").append(name).append(" @").append(max).append('\n')
            if (r == null) { append("-\n"); return }
            val o = r.request
            append(o.kind).append(' ').append(o.priority).append(" maxChunks ").append(OneShotRules.maxChunks(o, max)).append(" hasImage ").append(o.hasImage)
                .append(" cut ").append(r.cut).append(" tokens ").append(OneShotRules.tokens(o)).append(" fits ").append(OneShotRules.fits(o, max))
                .append(" system ").append(if (o.system == fp.READ_PICTURE) "read picture" else if (o.system == fp.READ_TEXT) "read text" else "answer").append('\n')
            val text = one(o.text)
            append(if (text.length > 600) text.take(300) + " [" + (text.length - 600) + " more] " + text.takeLast(300) else text).append('\n')
        }
        append("\n# Requests.\n")
        for (max in listOf(4096, 2048)) {
            req("read picture", fp.readPicture(app.heyrobot.core.files.FileSource.SHARE), max)
            req("read text short", fp.readText(short, app.heyrobot.core.files.FileSource.SHARE, maxTokens = max), max)
            req("read text long", fp.readText(long, app.heyrobot.core.files.FileSource.SCAN, maxTokens = max), max)
            for ((lang, spoken) in listOf(app.heyrobot.core.i18n.Lang.EN to true, app.heyrobot.core.i18n.Lang.FR to false, null to true)) {
                val q = if (lang == app.heyrobot.core.i18n.Lang.FR) "C'était combien en tout?" else "What was the total?"
                req("answer short $lang ${if (spoken) "spoken" else "typed"}",
                    fp.answer(q, short, app.heyrobot.core.files.FileKind.RECEIPT, app.heyrobot.core.files.FileSource.SHARE, lang, now, spoken, max), max)
            }
            req("answer long", fp.answer("What was item 250?", long, app.heyrobot.core.files.FileKind.RECEIPT, app.heyrobot.core.files.FileSource.SHARE,
                app.heyrobot.core.i18n.Lang.EN, now, spoken = false, maxTokens = max), max)
        }
    }

    @Test fun theFilePromptsAreAsFrozen() {
        val actual = fileRecord()
        val expected = javaClass.getResourceAsStream("/prompts/files.txt")?.bufferedReader()?.readText()
        if (expected == null) {
            val out = File("build/prompts-files.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            fail("No frozen record yet: wrote ${out.absolutePath}")
        }
        if (expected != actual) {
            val e = expected!!.lines()
            val a = actual.lines()
            val i = (0 until minOf(e.size, a.size)).firstOrNull { e[it] != a[it] } ?: minOf(e.size, a.size)
            File("build/prompts-files.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            assertEquals("first difference at line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
            assertEquals(expected, actual)
        }
    }

    @Test fun theFastPathsPromptsAreAsFrozen() {
        val actual = record()
        val expected = javaClass.getResourceAsStream("/prompts/full.txt")?.bufferedReader()?.readText()
        if (expected == null) {
            val out = File("build/prompts-full.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            fail("No frozen record yet: wrote ${out.absolutePath}")
        }
        if (expected != actual) {
            val e = expected!!.lines()
            val a = actual.lines()
            val i = (0 until minOf(e.size, a.size)).firstOrNull { e[it] != a[it] } ?: minOf(e.size, a.size)
            File("build/prompts-full.actual.txt").apply { parentFile.mkdirs(); writeText(actual) }
            assertEquals("first difference at line ${i + 1}", e.getOrNull(i), a.getOrNull(i))
            assertEquals(expected, actual)
        }
    }
}
