// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.FreeUp
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.files.FilesCommand
import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.web.BrowserRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * E2's own phrases (docs/plans/everyday-set-plan.md §4.3, §4.6, Files' F10 method): routing/corpus-e2.txt holds the sums
 * SumsRouter is for, in English and Québec French, then their misfires (doses, timers, a list, spending, the calendar, a
 * call, letters inside a number, the web, currency, words that aren't sums). routing/baseline-e2.tsv is what every router
 * made of each at E0's tip (92f805fe), before SumsRouter took anything, in EverydayCorpusTest's columns and order (written by
 * this test at E2 with SumsRouter's column emptied and ToolRouter's SUMS group taken out: the only two readings E2 changes,
 * as this test checks for every other phrase of the other corpora). Every
 * router still reads each phrase exactly so, except that SumsRouter takes exactly [taken] (none of which any other router
 * took, before it or after it in the panel) and ToolRouter adds its SUMS group (calculate) to exactly [SUMS_GROUP] phrases.
 */
class SumsCorpusTest {
    private val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())
    private val helpers = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))
    private val scripts = listOf(TermuxScriptInfo("backup", "Copies my notes to the server", listOf("sauvegarde", "save notes")), TermuxScriptInfo("hello", "Says hello"))

    private fun norm(x: Any?): String = (x?.toString() ?: "-").replace(Regex("@[0-9a-f]{4,}"), "@").replace('\t', ' ').replace('\n', ' ')

    private fun files(t: String): String = when (val c = FilesRouter.route(t)) {
        null -> "-"
        is FilesCommand.File -> c.heard.ask.name
        else -> c.javaClass.simpleName
    }

    /** EverydayCorpusTest's row: every router's reading of [t], in the panel's order, with car mode's hands-free last. */
    private fun row(t: String): List<String> = listOf(
        norm(MessagesRouter.route(t)),
        norm(ConversationRouter.route(t, running = false)),
        norm(ConversationRouter.route(t, running = true)),
        norm(FeedbackRouter.route(t)),
        norm(LockRouter.route(t, now, listOf("Bedtime"))),
        norm(RoutineRouter.route(t, now, listOf("Evening routine"), id = "r")),
        norm(SkillRouter.route(t, skills, people = listOf("Marie", "Sam"))),
        files(t),
        norm(MusicRouter.route(t)),
        norm(SumsRouter.route(t, now)),
        norm(OnlineRouter.route(t)),
        norm(HandsFreeRouter.route(t, carMode = false)),
        norm(BrowserRouter.route(t)),
        norm(ScriptRegistry.match(t, scripts, typed = true)),
        norm(MemoryRouting.route(t, MemorySettings(), locked = false)),
        norm(FreeUp.asked(t)),
        norm(HelperRouter.route(t, helpers)),
        norm(ToolRouter.route(t).sorted()),
        norm(HandsFreeRouter.route(t, carMode = true)),
    )

    private val sumsCol = 10
    private val toolsCol = 18

    /** The phrases of corpus-e2.txt SumsRouter takes: its first 54, the sums; none of the 34 misfires after them. */
    private val taken: Set<String> = javaClass.getResourceAsStream("/routing/corpus-e2.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.take(54).toSet()

    /** The phrases of corpus-e2.txt for which ToolRouter also describes the SUMS group (calculate, §4.4). */
    private val SUMS_GROUP = 36

    private fun withoutSums(groups: String): String = groups.replace(", SUMS]", "]").replace("[SUMS, ", "[").replace(", SUMS, ", ", ").replace("[SUMS]", "[]")

    @Test fun everyRouterReadsE2sPhrasesAsBefore() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-e2.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-e2.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-e2.tsv").writeText(lines.joinToString("\n") + "\n")
            fail("routing/baseline-e2.tsv is missing: written to core/build/routing/ (commit it before SumsRouter takes anything)")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = LinkedHashSet<String>()
        var sumsGroup = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.size, aa.size)
            val t = aa[0]
            for (i in bb.indices) if (i != sumsCol && i != toolsCol) assertEquals("$t, column $i", bb[i], aa[i])
            assertEquals(t, bb[toolsCol], withoutSums(aa[toolsCol]))
            if (bb[toolsCol] != aa[toolsCol]) sumsGroup++
            if (bb[sumsCol] != aa[sumsCol]) changed += t
        }
        assertEquals(taken.sorted(), changed.sorted())
        assertEquals(SUMS_GROUP, sumsGroup)
        // A phrase SumsRouter takes went to no router before, in front of it or behind it (it stops there in the panel, so a
        // router after it would lose the phrase unseen): only ToolRouter's groups, which describe tools, may say anything.
        for (t in taken) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            for (i in 1 until b.size) if (i != toolsCol && i != 3) assertEquals("$t, column $i", "-", b[i])
        }
    }
}
