// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.everyday

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
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.sums.SumsRouter
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.web.BrowserRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * The everyday set's before-and-after proof for the panel's routers (docs/plans/everyday-set-plan.md §2.3, Files' method
 * in core files RoutingCorpusTest). routing/baseline-everyday.tsv is what every router made of every phrase of the routing
 * corpus (corpus.txt and the F10, F12, F13 and FX2 files: 1,914 phrases) at E0, before MusicRouter, SumsRouter and
 * OnlineRouter took anything, in the panel's order: messages, conversation mode (off and on), feedback, Phone lock,
 * routines, skills, Files, the three new routers, hands-free with car mode off, web search in your browser, Termux,
 * memory, free up, the helpers, ToolRouter's groups, and last hands-free with car mode on (today's other column runs it
 * with car mode off only, so it can't see car mode's music phrases leave hands-free).
 *
 * Each later job changes one line here: its router's call and its `taken` set. Then every router reads every phrase
 * exactly as before, except that:
 * - each new router takes exactly its own `taken` phrases, none of which an earlier router took (they'd never reach it);
 * - hands-free with car mode on gives up exactly [carMusicMoved] (E1: car mode's music phrases move to MusicRouter);
 * - ToolRouter may add E2's SUMS group to exactly [SUMS_GROUP] phrases, and nothing else of its reading changes.
 *
 * Lists' and Robot to Robot's routers and corpus phrases join at the merge of the chains: their columns go after Files,
 * and this baseline is written again then, before any of the three routers takes anything.
 */
class EverydayCorpusTest {
    private val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())
    private val helpers = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))
    private val scripts = listOf(TermuxScriptInfo("backup", "Copies my notes to the server", listOf("sauvegarde", "save notes")), TermuxScriptInfo("hello", "Says hello"))

    /** Auto-open gave Routine `opens` (no app ticked by default); left out when empty, so the baseline, written before it, still compares. */
    private fun norm(x: Any?): String = (x?.toString() ?: "-").replace(Regex("@[0-9a-f]{4,}"), "@").replace('\t', ' ').replace('\n', ' ')
        .replace(", opens=[]", "")

    /** What Files takes, as RoutingCorpusTest reads it. */
    private fun files(t: String): String = when (val c = FilesRouter.route(t)) {
        null -> "-"
        is FilesCommand.File -> c.heard.ask.name
        else -> c.javaClass.simpleName
    }

    // E1, E2 and E3: each changes its own line, and only that.
    // E1: once your music app is picked, the widest net "play X" casts (D2); nothing playing, no pause of Robot's, car mode off.
    private fun music(t: String): String = norm(MusicRouter.route(t, MusicContext(appPicked = true)))
    private fun sums(t: String): String = norm(SumsRouter.route(t, now))
    private fun online(t: String): String = norm(OnlineRouter.route(t))

    /** Every router's reading of [t], in the panel's order, with car mode's hands-free last. */
    private fun row(t: String): List<String> = listOf(
        norm(MessagesRouter.route(t)),
        norm(ConversationRouter.route(t, running = false)),
        norm(ConversationRouter.route(t, running = true)),
        norm(FeedbackRouter.route(t)),
        norm(LockRouter.route(t, now, listOf("Bedtime"))),
        norm(RoutineRouter.route(t, now, listOf("Evening routine"), id = "r")),
        norm(SkillRouter.route(t, skills, people = listOf("Marie", "Sam"))),
        files(t),
        music(t),
        sums(t),
        online(t),
        norm(HandsFreeRouter.route(t, carMode = false)),
        norm(BrowserRouter.route(t)),
        norm(ScriptRegistry.match(t, scripts, typed = true)),
        norm(MemoryRouting.route(t, MemorySettings(), locked = false)),
        norm(FreeUp.asked(t)),
        norm(HelperRouter.route(t, helpers)),
        norm(ToolRouter.route(t).sorted()),
        norm(HandsFreeRouter.route(t, carMode = true)),
    )

    // Column numbers in [row] (0 is the phrase itself in the file).
    private val musicCol = 9
    private val sumsCol = 10
    private val onlineCol = 11
    private val toolsCol = 18
    private val carCol = 19

    /** E1: the corpus's phrases MusicRouter takes (§3.2), none of them taken by an earlier router. */
    private val takenMusic: Set<String> = setOf(
        "chanson précédente", "mets de la musique", "next song", "pause the music", "play music", "play my morning playlist",
        "skip this song", "toune suivante",
    )

    /** E2: the corpus's phrases SumsRouter takes (§4.3): the one sum among them; E2's own are in core sums SumsCorpusTest. */
    private val takenSums: Set<String> = setOf("what's 15 % of 80")

    /** E3: the corpus's phrases OnlineRouter takes (§5.6). */
    private val takenOnline: Set<String> = setOf()

    /** E1: the phrases hands-free gives up with car mode on, once MusicRouter hears car mode's music phrases (§3.2, D2). */
    private val carMusicMoved: Set<String> = setOf(
        "chanson précédente", "mets de la musique", "next song", "pause the music", "play music", "skip this song",
        "toune suivante",
    )

    /** E2: the corpus's phrases for which ToolRouter also describes the SUMS group (calculate, §4.4): "what's 15 % of 80", "answer this: what is 2 plus 2" and "calculate my taxes for 2026" (as RoutingCorpusTest counts them). */
    private val SUMS_GROUP = 3

    private fun corpus(): List<String> = listOf("corpus.txt", "corpus-f10.txt", "corpus-f12.txt", "corpus-f13.txt", "corpus-fx2.txt").flatMap { name ->
        javaClass.getResourceAsStream("/routing/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }
    }

    /** ToolRouter's groups without E2's SUMS. */
    private fun withoutSums(groups: String): String = groups.replace(", SUMS]", "]").replace("[SUMS, ", "[").replace(", SUMS, ", ", ").replace("[SUMS]", "[]")

    /**
     * E1's own corpus (routing/corpus-e1.txt): §3.2's music phrases and §3.6's misfires in both languages, with what every
     * router made of them at E0 (routing/baseline-e1.tsv, written at 92f805fe before MusicRouter took anything). The same
     * rule as the whole corpus: only the music column and car mode's hands-free column move.
     */
    private val takenMusicE1: Set<String> = setOf(
        "arrête la musique", "c'est quoi cette toune", "chanson précédente", "chanson suivante", "coupe la musique", "fais jouer Radiohead",
        "fais jouer la chanson Creep de Radiohead", "go back a song", "hey robot, play the album Kid A please", "joue l'album Kid A", "last song", "mets de la musique",
        "mets des chansons de Céline Dion", "mets du jazz", "mets la musique sur pause", "mets ma liste de lecture d'entraînement", "next song",
        "next track", "partir la musique", "passe la toune", "pause music", "pause the music", "play OK Computer by Radiohead",
        "play Radiohead", "play Stand by Me", "play Stand by Me by Ben E. King", "play a song", "play music", "play my music",
        "play my running mix", "play my running playlist", "play my workout playlist", "play some jazz", "play some music", "play something",
        "play songs by Radiohead", "play the album Kid A", "previous song", "prochaine toune", "put on some jazz", "qui chante ça",
        "remets la musique", "reprends la musique", "resume the music", "skip this song", "start the music", "stop the music",
        "toune précédente", "unpause", "what song is this", "what's playing", "who sings this",
    )

    /** E1: the phrases of corpus-e1.txt that hands-free gives up with car mode on. */
    private val carMusicMovedE1: Set<String> = setOf(
        "arrête la musique", "chanson précédente", "chanson suivante", "coupe la musique", "go back a song", "last song",
        "mets de la musique", "mets la musique sur pause", "next song", "next track", "partir la musique", "passe la toune",
        "pause", "pause music", "pause the music", "play music", "play my music", "play some music",
        "previous song", "prochaine toune", "remets la musique", "reprends la musique", "resume the music", "skip this song",
        "start the music", "stop the music", "toune précédente", "unpause",
    )

    @Test fun musicTakesOnlyItsOwnPhrasesInItsCorpus() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-e1.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-e1.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-e1.tsv").writeText(lines.joinToString("\n") + "\n")
            fail("routing/baseline-e1.tsv is missing: written to core/build/routing/ (commit it before MusicRouter changes)")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val music = LinkedHashSet<String>()
        val car = LinkedHashSet<String>()
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.size, aa.size)
            val t = aa[0]
            for (i in bb.indices) if (i != musicCol && i != carCol) assertEquals("$t, column $i", bb[i], aa[i])
            if (bb[musicCol] != aa[musicCol]) music += t
            if (bb[carCol] != aa[carCol]) car += t
        }
        assertEquals(takenMusicE1.sorted(), music.sorted())
        assertEquals(carMusicMovedE1.sorted(), car.sorted())
        for (t in takenMusicE1) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(musicCol - 2) { "-" }, b.subList(1, musicCol).filterIndexed { i, _ -> i + 1 != 3 })
            assertTrue(t, b[musicCol] == "-")
        }
    }

    @Test fun everyRouterReadsTheCorpusAsBeforeTheEverydaySet() {
        val phrases = corpus()
        assertEquals(1_914, phrases.size)
        val lines = phrases.map { t -> (listOf(t) + row(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-everyday.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-everyday.tsv").writeText(lines.joinToString("\n") + "\n")
            fail("routing/baseline-everyday.tsv is missing: written to core/build/routing/ (commit it before any router changes)")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = mapOf(musicCol to LinkedHashSet<String>(), sumsCol to LinkedHashSet(), onlineCol to LinkedHashSet(), carCol to LinkedHashSet())
        var sumsGroup = 0
        val loose = changed.keys + toolsCol
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.size, aa.size)
            val t = aa[0]
            // Every other router reads it as before.
            for (i in bb.indices) if (i !in loose) assertEquals("$t, column $i", bb[i], aa[i])
            assertEquals(t, bb[toolsCol], withoutSums(aa[toolsCol]))
            if (bb[toolsCol] != aa[toolsCol]) sumsGroup++
            for ((col, set) in changed) if (bb[col] != aa[col]) set += t
        }
        assertEquals(takenMusic.sorted(), changed.getValue(musicCol).sorted())
        assertEquals(takenSums.sorted(), changed.getValue(sumsCol).sorted())
        assertEquals(takenOnline.sorted(), changed.getValue(onlineCol).sorted())
        assertEquals(carMusicMoved.sorted(), changed.getValue(carCol).sorted())
        assertEquals(SUMS_GROUP, sumsGroup)
        // A phrase a new router takes went to nothing before it: no earlier router took it, and its own column was empty.
        // Conversation mode while it transcribes (column 3) is left out: it hears its own words first, as the panel does
        // ("pause" while it transcribes stays its, §3.6).
        for ((col, taken) in listOf(musicCol to takenMusic, sumsCol to takenSums, onlineCol to takenOnline)) for (t in taken) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(col - 2) { "-" }, b.subList(1, col).filterIndexed { i, _ -> i + 1 != 3 })
            assertTrue(t, b[col] == "-")
        }
        // Each router after the three reads the same words as before: a taken phrase stops at its router in the panel.
        assertEquals(emptySet<String>(), takenMusic intersect takenSums)
        assertEquals(emptySet<String>(), (takenMusic + takenSums) intersect takenOnline)
    }
}
