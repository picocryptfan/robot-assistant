// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

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
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.web.BrowserRouter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * Lists LS3, the before-and-after proof for the panel's routers (lists-plan.md §3.4): every phrase of the six routing
 * corpora (corpus.txt, F10's, F12's, F13's, FX2's, and corpus-lists.txt with §3.3's phrases and §3.4's misfires) and what
 * every router before and after Lists made of it at d1857ece, before ListsRouter existed: routing/baseline-lists.tsv
 * (committed alone, first). Re-recorded after release/1.1's merge (LS8) in two columns only, the 44 phrases release/1.1
 * re-recorded in its own baselines (6dbdf14b): ToolRouter's calendar words (41 rows) and RoutineRouter's "remind me" and
 * "stop the timer" (4 rows). LS8 added its Québec phrasings and their misfires to corpus-lists.txt, and 1.1's phone-fix
 * corpus (corpus-phonefix.txt, 52 phrases) after it, their rows recorded as every other router reads them. Every one of those routers still reads every phrase exactly so, and ListsRouter takes only the
 * phrases in [taken], none of which another router took (only ToolRouter's column, the tools described to the model, may
 * name something). Since ListsRouter sits after Files and before hands-free, the routers after it see the same words as
 * before. ChatOnly.kt and Groups.kt still send list phrases to add_note (LS4 moves them): ToolRouter's column is unchanged.
 */
class ListsRoutingCorpusTest {
    private val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())
    private val helpers = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))
    private val scripts = listOf(TermuxScriptInfo("backup", "Copies my notes to the server", listOf("sauvegarde", "save notes")), TermuxScriptInfo("hello", "Says hello"))

    private fun norm(x: Any?): String = (x?.toString() ?: "-").replace(Regex("@[0-9a-f]{4,}"), "@").replace('\t', ' ').replace('\n', ' ')

    /** Each router's reading of [t], in the panel's order, Files last (RoutingCorpusTest's columns and its files column). */
    private fun row(t: String): List<String> = listOf(
        norm(MessagesRouter.route(t)),
        norm(ConversationRouter.route(t, running = false)),
        norm(ConversationRouter.route(t, running = true)),
        norm(FeedbackRouter.route(t)),
        norm(LockRouter.route(t, now, listOf("Bedtime"))),
        norm(RoutineRouter.route(t, now, listOf("Evening routine"), id = "r")),
        norm(SkillRouter.route(t, skills, people = listOf("Marie", "Sam"))),
        norm(HandsFreeRouter.route(t, carMode = false)),
        norm(BrowserRouter.route(t)),
        norm(ScriptRegistry.match(t, scripts, typed = true)),
        norm(MemoryRouting.route(t, MemorySettings(), locked = false)),
        norm(FreeUp.asked(t)),
        norm(HelperRouter.route(t, helpers)),
        norm(ToolRouter.route(t).sorted()),
        files(t),
    )

    /** ToolRouter's column in [row]: the tools described to the model, not a router that takes the phrase. */
    private val TOOLS = 13
    /** The columns of the routers the panel asks before Lists: messages, conversation mode (twice), feedback, the phone lock, routines, skills, Files. */
    private val FIRST = setOf(0, 1, 2, 3, 4, 5, 6, 14)

    private fun files(t: String): String = when (val c = FilesRouter.route(t)) {
        null -> "-"
        is FilesCommand.File -> c.heard.ask.name
        else -> c.javaClass.simpleName
    }

    /** What ListsRouter takes of [t]: its command's kind, or "-". */
    private fun lists(t: String, heard: ListsHeard): String = ListsRouter.route(t, heard)?.javaClass?.simpleName ?: "-"

    private fun lines(name: String): List<String> = javaClass.getResourceAsStream("/routing/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun corpus(): List<String> = listOf("corpus.txt", "corpus-f10.txt", "corpus-f12.txt", "corpus-f13.txt", "corpus-fx2.txt", "corpus-lists.txt",
        "corpus-phonefix.txt").flatMap(::lines)

    /**
     * corpus-lists.txt: its first 64 phrases are §3.3's and LT2, LT9, LT10's (taken as they are), the next 12 the list in
     * focus's follow-ups, then §3.4's misfires (to line 126). From line 127, LS8's: 23 Québec phrasings taken as they are,
     * 2 follow-ups (« ôte les œufs »), then 26 misfires, each beside the phrasing it could be mistaken for. From line 178,
     * LS10's 8 misfires (L2): « la liste des X » questions, « liste de lecture » and "put me on the guest list" (their rows
     * recorded as every other router reads them, as LS8's were).
     */
    private val ours = lines("corpus-lists.txt")

    /** What ListsRouter takes with no list in focus: corpus-lists.txt's first 64 and LS8's 23, and corpus.txt's "add milk to my list" (which went to the model's add_note). */
    private val taken: Set<String> = ours.take(64).toSet() + ours.subList(126, 149) + "add milk to my list"

    /** With a list in focus that holds eggs, bread (ticked), sunscreen, pain (ticked)…, and "undo that" possible: the 12 follow-ups and LS8's 2 too. */
    private val takenInFocus: Set<String> = taken + ours.subList(64, 76) + ours.subList(149, 151)

    private fun before(): List<String>? = javaClass.getResourceAsStream("/routing/baseline-lists.tsv")?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }

    @Test fun everyRouterReadsTheCorporaAsBeforeAndListsTakesOnlyItsOwn() {
        val phrases = corpus()
        val rows = phrases.map { t -> (listOf(t) + row(t)).joinToString("\t") }
        val base = before()
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-lists.tsv").writeText(rows.joinToString("\n") + "\n")
            return
        }
        assertEquals(base.size, rows.size)
        for ((b, a) in base.zip(rows)) assertEquals(b, a)
        check(phrases, base, ListsHeard(), taken)
    }

    /**
     * As on the phone in a conversation that just used Shopping: everyday names among your lists (so "add X to Work" would be
     * a list's), a list in focus whose items Robot saw, and an Undo waiting. Lists takes the follow-ups too, and nothing more.
     */
    @Test fun withYourListsNamesAndAListInFocus() {
        val base = before() ?: return
        val shopping = ListsList(id(1), role = ListRole.SHOPPING, made = 0, items = listOf(item(2, "eggs"), item(3, "bread", done = true), item(4, "oat milk"),
            item(5, "pain", done = true), item(6, "œufs"), item(7, "crème solaire")))
        val packing = ListsList(id(8), name = "Packing", made = 0, items = listOf(item(9, "sunscreen")))
        val names = listOf("Packing", "Work", "Home", "Car", "Kids", "Sam", "Maison", "Travail")
        val file = ListsFile(listOf(shopping, packing) + names.drop(1).mapIndexed { i, n -> ListsList(id(20 + i), name = n, made = 0) })
        check(corpus(), base, ListsHeard(names = names, focus = true, undo = true, seen = ListsSeen.of(file, shopping.id)), takenInFocus,
            // With a list called Maison, Files' rule « mets toutes les factures de Bell dans Maison » reads as an add too; Files
            // is asked first (D9), so Lists never hears it.
            first = setOf("mets toutes les factures de Bell dans Maison"))
    }

    /**
     * Lists takes exactly [want] of [phrases], each of which no other router took at the baseline. ListsRouter alone would
     * also take [first], which a router asked before it takes: never Lists' in the panel.
     */
    private fun check(phrases: List<String>, base: List<String>, heard: ListsHeard, want: Set<String>, first: Set<String> = emptySet()) {
        val raw = phrases.zip(base).filter { lists(it.first, heard) != "-" }
        val earlier = raw.filter { (_, b) -> b.split('\t').drop(1).withIndex().any { (i, c) -> i in FIRST && c != "-" } }.map { it.first }
        assertEquals(first.sorted(), earlier.sorted())
        val took = raw.map { it.first } - earlier.toSet()
        assertEquals(want.sorted(), took.sorted())
        for ((t, b) in phrases.zip(base)) {
            if (t !in want) continue
            val cols = b.split('\t').drop(1)
            assertEquals(t, List(cols.size - 1) { "-" }, cols.filterIndexed { i, _ -> i != TOOLS })
        }
    }

    private fun id(n: Int): ListsId = ListsId.of("%032x".format(n))!!
    private fun item(n: Int, text: String, done: Boolean = false) = ListItem(id(100 + n), text, done = done, pos = n * 1000L, at = 0, upd = 0, by = ItemBy.YOU, how = ItemHow.VOICE)
}
