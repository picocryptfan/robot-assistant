// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.web.BrowserRouter
import app.heyrobot.core.ConversationRouter
import app.heyrobot.core.FreeUp
import app.heyrobot.core.HelperName
import app.heyrobot.core.HelperRouter
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.feedback.FeedbackRouter
import app.heyrobot.core.lock.LockRouter
import app.heyrobot.core.memory.MemoryRouting
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.output.HandsFreeRouter
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.TermuxScriptInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * 1.4 F8, the before-and-after proof for the panel's routers (files-1.4-plan.md §9, D15). routing/corpus.txt holds every
 * phrase the other routers' tests use (1,689, pulled from their sources at c330d47f) and 83 everyday sentences in both
 * languages; routing/baseline.tsv is what each router made of each phrase at c330d47f, before FilesRouter existed. Each
 * router still makes exactly that of every phrase, and FilesRouter takes only the phrases F5's "file this" took then:
 * since it sits after skills and before hands-free, the routers after it see the same words as before. F5's misfire list
 * (ShareRulesTest) held two of F8's own phrases, "Esso receipts go under Car" and "move the Costco receipt to Taxes": no
 * router took them at c330d47f, and FilesRouter takes them now, as it takes two of the everyday sentences.
 */
class RoutingCorpusTest {
    private val now = ZonedDateTime.parse("2026-10-22T10:00:00-04:00[America/Toronto]")
    private val skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta())
    private val helpers = listOf(HelperName.CLAUDE, HelperName("jarvis", listOf(listOf("jarvis")), phonetic = true))
    private val scripts = listOf(TermuxScriptInfo("backup", "Copies my notes to the server", listOf("sauvegarde", "save notes")), TermuxScriptInfo("hello", "Says hello"))

    private fun norm(x: Any?): String = C1_DEFAULTS.fold((x?.toString() ?: "-").replace(Regex("@[0-9a-f]{4,}"), "@").replace('\t', ' ').replace('\n', ' ')) { t, d ->
        t.replace(d, "")
    }

    /**
     * Skills connections C1 gave Skill, SkillParam and SkillStep new fields (skills-connections-plan.md §5.1); a 1.1 skill
     * holds their defaults (and a routine auto-open's empty `opens`), left out here so the baseline, written before them, still compares every router's reading.
     */
    private val C1_DEFAULTS = listOf(", options=[], min=null, max=null, unit=null", ", id=null, conn=null, op=null, cond=null, check=null, onFail=STOP",
        ", perHour=null", ", opens=[]")

    /** Each router's reading of [t], in the panel's order (files apart, in [files]). */
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
    )

    /** What the files router takes of [t]: "file this" as F5's FileThis named it (FILE, ANYWAY), F8's commands by name, or "-". */
    private fun files(t: String): String = when (val c = FilesRouter.route(t)) {
        null -> "-"
        is FilesCommand.File -> c.heard.ask.name
        else -> c.javaClass.simpleName
    }

    /**
     * The corpus's phrases F8 takes on purpose: the everyday sentences added for it, which no router took at c330d47f (they
     * went to the model). Every other phrase routes exactly as before.
     */
    private val taken = setOf("where's my lease", "what's in my inbox", "Esso receipts go under Car", "move the Costco receipt to Taxes",
        // F10: the spending and due-date sentences F8 added for it, unclaimed until now.
        "how much did I spend on gas in September", "what bills are due this week", "combien j'ai dépensé en essence en septembre",
        "quelles factures sont dues cette semaine")

    /** 1.4 F9: the corpus's phrases whose words name your files, for which ToolRouter now also describes the FILES group. */
    private val FILES_GROUP = 25

    /**
     * The everyday set (E2, everyday-set-plan.md §4.4): the corpus's phrases for which ToolRouter also describes the SUMS group
     * (calculate), per file, as F9 counted FILES. It only describes a tool, so a phrase too many costs only its tokens; each is
     * a number beside an operator or a sum's own word ("what's 15 % of 80", "calculate my taxes for 2026", "2 h 05 ago" is not).
     */
    /** corpus.txt: "what's 15 % of 80" and "answer this: what is 2 plus 2". */
    private val SUMS_GROUP = 2
    private val SUMS_F10 = 0
    /** corpus-f12.txt: "calculate my taxes for 2026" (the word "calculate"; the model still answers it, the tool only described). */
    private val SUMS_F12 = 1
    private val SUMS_F13 = 0
    private val SUMS_FX2 = 0

    /** [row] with ToolRouter's column ([tools]) read without E2's SUMS group; [found] for each row that had it. */
    private fun sumsFree(row: List<String>, tools: Int, found: () -> Unit): List<String> {
        val g = row[tools]
        val without = g.replace(", SUMS]", "]").replace("[SUMS, ", "[").replace(", SUMS, ", ", ").replace("[SUMS]", "[]")
        if (without == g) return row
        found()
        return row.toMutableList().also { it[tools] = without }
    }

    private fun corpus(): List<String> = javaClass.getResourceAsStream("/routing/corpus.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    /**
     * As on the phone after FL4 and FL5: the panel just filed a file ("move it" means it) and your drawers have everyday
     * names. FilesRouter still takes only the same phrases, and of those, moves only the one that names its file.
     */
    @Test fun withDrawersOfEverydayNamesAndAFileJustFiled() {
        val yours = listOf("Car", "House", "Work", "Kitchen", "Home", "Auto", "Maison", "Travail", "Cuisine", "Left", "Gauche")
        val taken = corpus().mapNotNull { t -> FilesRouter.route(t, recent = true, yours = yours)?.let { t to it } }
            .filter { it.second !is FilesCommand.File }.map { it.first }
        assertEquals(this.taken.sorted(), taken.sorted())
    }

    /**
     * 1.4 F10's phrases (spending, due dates, expiry, returns, warranties, subscriptions, in English and Québec French) and
     * their misfires (prices on the web, the calendar, "remind me …", a subscription to cancel): routing/corpus-f10.txt, and
     * routing/baseline-f10.tsv, what every router made of each at d715e79e (F9, before F10 changed any router). Every other
     * router still reads each phrase exactly so, and FilesRouter takes only [takenF10], none of which another router took.
     */
    @Test fun everyRouterReadsF10sPhrasesAsBefore() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-f10.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-f10.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-f10.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = ArrayList<String>()
        var sums = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa0 = a.split('\t')
            // The everyday set (E2): ToolRouter's SUMS group (calculate) is the only other change, counted per file.
            val aa = sumsFree(aa0, bb.size - 2) { sums++ }
            assertEquals(bb.dropLast(1), aa.dropLast(1))
            if (bb.last() != aa.last()) changed += aa.first()
        }
        assertEquals(takenF10.sorted(), changed.sorted())
        assertEquals(SUMS_F10, sums)
        for (t in takenF10) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(b.size - 3) { "-" }, b.subList(1, b.size - 2))
        }
    }

    /** F10's phrases FilesRouter takes: the first 26 of corpus-f10.txt; none of the 16 misfires after them. */
    private val takenF10: Set<String> = javaClass.getResourceAsStream("/routing/corpus-f10.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.take(26).toSet()

    /**
     * 1.4 F12's phrases (a tax pack and an expense report, in English and Québec French) and their misfires (doing your
     * taxes, a deadline, "remind me", the web): routing/corpus-f12.txt, and routing/baseline-f12.tsv, what every router made
     * of each at d53e008c (before F12 changed any router). Every other router reads each phrase exactly so, and FilesRouter
     * takes only [takenF12], none of which another router took.
     */
    @Test fun everyRouterReadsF12sPhrasesAsBefore() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-f12.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-f12.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-f12.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = ArrayList<String>()
        var sums = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa0 = a.split('\t')
            // The everyday set (E2): ToolRouter's SUMS group (calculate) is the only other change, counted per file.
            val aa = sumsFree(aa0, bb.size - 2) { sums++ }
            assertEquals(bb.dropLast(1), aa.dropLast(1))
            if (bb.last() != aa.last()) changed += aa.first()
        }
        assertEquals(takenF12.sorted(), changed.sorted())
        assertEquals(SUMS_F12, sums)
        for (t in takenF12) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(b.size - 3) { "-" }, b.subList(1, b.size - 2))
        }
    }

    /** F12's phrases FilesRouter takes: the first 14 of corpus-f12.txt; none of the 15 misfires after them. */
    private val takenF12: Set<String> = javaClass.getResourceAsStream("/routing/corpus-f12.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.take(14).toSet()

    /**
     * 1.4 F13's phrases: everyday Québec French for find, file this, move, a rule, the tax pack, spending and what's due
     * (« sors-moi mon reçu de Costco », « il est où mon bail », « sauve ça dans mes fichiers », « j'ai-tu des factures à
     * payer cette semaine »), then their misfires (the tax return itself, natural gas, « combien j'ai mis de temps », the
     * phone, the web, the weather, notes, songs, accounts, « mets-le dans le coffre »): routing/corpus-f13.txt, and
     * routing/baseline-f13.tsv, what every router made of each at 3b57a8f4 (F12's tip, before F13 changed any router).
     * Every other router reads each phrase exactly so, and FilesRouter takes only [takenF13], none of which another router
     * took.
     */
    @Test fun everyRouterReadsF13sPhrasesAsBefore() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-f13.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-f13.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-f13.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = ArrayList<String>()
        var sums = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa0 = a.split('\t')
            // The everyday set (E2): ToolRouter's SUMS group (calculate) is the only other change, counted per file.
            val aa = sumsFree(aa0, bb.size - 2) { sums++ }
            assertEquals(bb.dropLast(1), aa.dropLast(1))
            if (bb.last() != aa.last()) changed += aa.first()
        }
        assertEquals(takenF13.sorted(), changed.sorted())
        assertEquals(SUMS_F13, sums)
        for (t in takenF13) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(b.size - 3) { "-" }, b.subList(1, b.size - 2))
        }
    }

    /** F13's phrases FilesRouter takes: the first 26 of corpus-f13.txt; none of the 17 phrases after them (misfires, and a spending question that stays one). */
    private val takenF13: Set<String> = javaClass.getResourceAsStream("/routing/corpus-f13.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }.take(26).toSet()

    /**
     * FX2's phrases (files-1.4-review.md R1, R2, R5): a question about where something goes (« c'est dans quel tiroir? »,
     * "go in which drawer") or a place that isn't a drawer (« c'est dans la boîte à gants ») is no longer a rule; « combien
     * j'ai mis en REER » is no longer spending; « classe moi ca » (no accent, no hyphen) and « toutes les » are heard. Then
     * phrases that must read as before (a rule to a drawer that exists or the « vont dans » form, spending on a category, at
     * a merchant or in all). routing/corpus-fx2.txt, and routing/baseline-fx2.tsv, what every router made of each at 8f2c5d3c
     * (FX1's tip, before FX2 changed any router). Every other router reads each phrase exactly so; FilesRouter's reading
     * changes only for [changedFX2], to the reading given there, both ways (FX2 drops phrases as well as taking them).
     */
    @Test fun everyRouterReadsFX2sPhrasesAsBefore() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-fx2.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-fx2.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-fx2.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = LinkedHashMap<String, String>()
        var sums = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa0 = a.split('\t')
            // The everyday set (E2): ToolRouter's SUMS group (calculate) is the only other change, counted per file.
            val aa = sumsFree(aa0, bb.size - 2) { sums++ }
            assertEquals(bb.dropLast(1), aa.dropLast(1))
            if (bb.last() != aa.last()) changed[aa.first()] = aa.last()
        }
        assertEquals(changedFX2, changed.toSortedMap())
        assertEquals(SUMS_FX2, sums)
    }

    /** FX2's changes to FilesRouter's reading of corpus-fx2.txt: each phrase and what FilesRouter makes of it now ("-": nothing). */
    private val changedFX2: Map<String, String> = sortedMapOf(
        // R1 (b): a drawer named by a question word, in every form.
        "les factures d'Hydro, c'est dans quel tiroir?" to "-", "les factures de Bell, ça va dans quel tiroir" to "-",
        "les reçus d'Esso, ça s'en va dans lequel" to "-", "Esso receipts go in which drawer" to "-", "put Esso receipts in what folder" to "-",
        "mets les reçus d'Esso dans quel tiroir" to "-", "les reçus d'Esso vont dans quelle boîte" to "-",
        // R1 (a): « c'est dans » names a drawer that exists, as a move does.
        "les reçus de Costco, c'est dans la boîte à gants" to "-",
        // R2: « mis en » only before a known category.
        "combien j'ai mis en REER cette année" to "-", "combien j'ai mis en banque" to "-", "combien j'ai mis en épargne ce mois-ci" to "-",
        "combien j'ai mis en temps" to "-",
        // R5: "file this" folded (accents, hyphens); « toutes les » in a rule.
        "classe moi ca" to "FILE", "range ca" to "FILE", "Classe-moi ca s'il te plait" to "FILE",
        "mets toutes les factures de Bell dans Maison" to "Rule", "toutes les factures d'Hydro vont dans Maison" to "Rule",
    )

    @Test fun everyRouterReadsTheCorpusAsBefore() {
        val lines = corpus().map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = ArrayList<String>()
        val filesGroup = ArrayList<String>()
        var sums = 0
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = sumsFree(a.split('\t'), b.split('\t').size - 2) { sums++ }
            // Every other router reads it as before.
            assertEquals(bb.dropLast(2), aa.dropLast(2))
            // 1.4 F9: ToolRouter adds its new FILES group (find_files, read_file) for your files' words (files-1.4-plan.md §5.1),
            // and nothing else of its reading changes. The tools are only described, so a word too many costs only tokens.
            val tools = bb.size - 2
            assertEquals(aa.first(), bb[tools], aa[tools].replace(", FILES]", "]"))
            if (bb[tools] != aa[tools]) filesGroup += aa.first()
            if (bb.last() != aa.last()) changed += aa.first() + " -> " + aa.last()
        }
        // Never for free_storage's files ("delete big files", « libère de l'espace »): FreeUp's own phrases keep their groups.
        assertEquals(emptyList<String>(), filesGroup.filter { FreeUp.asked(it) != null })
        assertEquals(filesGroup.toString(), FILES_GROUP, filesGroup.size)
        assertEquals(SUMS_GROUP, sums)
        // FilesRouter takes what "file this" took, and only the phrases listed, which went to the model before (no router's).
        assertEquals(taken.sorted(), changed.map { it.substringBefore(" -> ") }.sorted())
        for (t in taken) {
            val b = before.first { it.startsWith("$t\t") }.split('\t')
            assertEquals(t, List(b.size - 3) { "-" }, b.subList(1, b.size - 2))
        }
    }

    /** The columns of a corpus line after the phrase, in [row]'s order, then [files]. */
    private val COLUMNS = listOf("messages", "conversation", "conversation running", "feedback", "lock", "routines", "skills", "hands-free",
        "browser", "termux", "memory", "free up", "helpers", "tools", "files")

    /**
     * 1.1's phone fixes (22 Oct): « Ajoute un film ce soir de 22 h à 1 h » set a 1-minute timer, and "Remind me about it"
     * saved a note. routing/corpus-phonefix.txt holds those phrases (typed without accents, with them, and as the
     * transcriber writes them), their neighbours in both languages, and timers, alarms and reminders that must read as
     * before; routing/baseline-phonefix.tsv is what every router made of each at edda31f3 ("Robot 1.1.0"), before the fix.
     * Every router still reads every phrase so, but for [changedPhoneFix], column by column.
     */
    @Test fun everyRouterReadsThePhoneFixPhrasesAsRecorded() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-phonefix.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-phonefix.tsv")
        if (base == null) {
            File("build/routing").mkdirs()
            File("build/routing/baseline-phonefix.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = sortedMapOf<String, String>()
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.size, aa.size)
            assertEquals(bb.first(), aa.first())
            for (c in 1 until aa.size) if (bb[c] != aa[c]) changed[aa.first() + " | " + COLUMNS[c - 1]] = bb[c] + " -> " + aa[c]
        }
        assertEquals(changedPhoneFix, changed)
    }

    /**
     * 1.3, "What did I miss?" (what-did-i-miss-plan.md): routing/corpus-missed.txt holds its phrases in both languages and
     * their misfires ("… in the meeting", "did I miss the bus", « j'ai manqué l'autobus »); routing/baseline-missed.tsv is
     * what every router made of each before MessagesRouter knew them. Only the messages column changes, for [changedMissed].
     */
    @Test fun everyRouterReadsTheMissedPhrasesAsRecorded() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-missed.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-missed.tsv")
        if (base == null) {
            // build/reports: a remote build (beelink-gradle) brings it back.
            File("build/reports/routing").mkdirs()
            File("build/reports/routing/baseline-missed.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = sortedMapOf<String, String>()
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.first(), aa.first())
            for (c in 1 until aa.size) if (bb[c] != aa[c]) changed[aa.first() + " | " + COLUMNS[c - 1]] = bb[c] + " -> " + aa[c]
        }
        assertEquals(changedMissed, changed)
    }

    private val changedMissed: Map<String, String> = sortedMapOf<String, String>().apply {
        val en = listOf("What did I miss?", "what did I miss", "What have I missed?", "what'd I miss", "Did I miss anything?", "Have I missed anything?",
            "did I miss something", "what did I miss on my phone", "What did I miss while I was away?", "Robot, what did I miss?", "what did I miss please",
            // fix-what-did-i-miss, finding 5: a time tail, notifications by name, « qu'ai-je manqué ».
            "did I miss any notifications", "Any notifications?", "anything I missed")
        val fr = listOf("Qu'est-ce que j'ai manqué?", "Qu’est-ce que j’ai manqué ?", "j'ai-tu manqué quelque chose?", "J'ai manqué quoi?",
            "ai-je manqué quelque chose", "qu'est-ce que j'ai manqué pendant que j'étais parti", "quoi de neuf sur mon téléphone", "qu'est-ce que j'ai raté",
            "Qu'ai-je manqué ?")
        for (t in en) put("$t | messages", "- -> Missed(lang=EN)")
        for (t in fr) put("$t | messages", "- -> Missed(lang=FR)")
    }

    /**
     * 1.3, notifications held until a time (notify-hold-plan.md): routing/corpus-hold.txt holds its phrases in both
     * languages and their misfires ("hold on", "hold the door", "put a hold on my library book"); routing/baseline-hold.tsv
     * is what every router made of each before MessagesRouter knew them. Only the messages column changes, for [changedHold].
     */
    @Test fun everyRouterReadsTheHoldPhrasesAsRecorded() {
        val phrases = javaClass.getResourceAsStream("/routing/corpus-hold.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        val lines = phrases.map { t -> (listOf(t) + row(t) + files(t)).joinToString("\t") }
        val base = javaClass.getResourceAsStream("/routing/baseline-hold.tsv")
        if (base == null) {
            File("build/reports/routing").mkdirs()
            File("build/reports/routing/baseline-hold.tsv").writeText(lines.joinToString("\n") + "\n")
            return
        }
        val before = base.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(before.size, lines.size)
        val changed = sortedMapOf<String, String>()
        for ((b, a) in before.zip(lines)) {
            val bb = b.split('\t')
            val aa = a.split('\t')
            assertEquals(bb.first(), aa.first())
            for (c in 1 until aa.size) if (bb[c] != aa[c]) changed[aa.first() + " | " + COLUMNS[c - 1]] = bb[c] + " -> " + aa[c]
        }
        assertEquals(changedHold.keys, changed.keys)
        for ((k, v) in changed) assertTrue(k, v.startsWith("- -> Hold(") && v.endsWith(changedHold[k]!!))
    }

    private val HOLD_EN = listOf("Hold my shopping and news apps till noon.", "hold my notifications until noon", "pause my apps for an hour", "snooze my news notifications until 3:30 pm", "I'm off my phone till three", "I'm away from my phone for 2 hours", "Robot, hold my apps till 6", "hold all my notifications for 30 minutes")
    private val HOLD_FR = listOf("Mets mes notifications en attente jusqu'à midi.", "mets mes applis de magasinage et de nouvelles en attente jusqu'à 18 h", "suspends mes notifications pendant une heure", "je suis loin de mon téléphone jusqu'à trois heures", "mets mes notifs en pause jusqu’à 14 h 30")

    /** Each phrase now Robot's, "phrase | messages" to the end of its new reading (its language). */
    private val changedHold: Map<String, String> = sortedMapOf<String, String>().apply {
        for (t in HOLD_EN) put("$t | messages", "lang=EN)")
        for (t in HOLD_FR) put("$t | messages", "lang=FR)")
    }

    /** What the phone fixes changed in corpus-phonefix.txt's reading: "phrase | column" to "before -> after". */
    private val changedPhoneFix: Map<String, String> = sortedMapOf(
        // « ce soir », « 22 h », « minuit », « fin de semaine »: the calendar's tools are described (ToolRouter.route).
        "Ajoute un film ce soir de 22 h a 1 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "Ajoute un film ce soir de 22 h à 1 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "Ajoute un film de 22 h à 1 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "Ajoute un souper ce soir à 18 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "ajoute un brunch en fin de semaine | tools" to "[CORE] -> [CORE, CALENDAR]",
        "ajoute un film ce soir de vingt-deux heures à une heure | tools" to "[CORE] -> [CORE, CALENDAR]",
        "annule mon alarme de 6 h 30 | tools" to "[CORE] -> [CORE, CALENDAR]",
        "mets un film ce soir de 22 h 30 à minuit | tools" to "[CORE] -> [CORE, CALENDAR]",
        "mets une minuterie de 1 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "minuterie de 1 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        "planifie un appel ce soir à 20 h | tools" to "[CORE] -> [CORE, CALENDAR]",
        // Nothing to remind and no time: Robot asks what and when (RoutineCommand.RemindWhat); an alarm or a timer to cancel:
        // Robot says it can't and offers the clock app (RoutineCommand.CantCancel).
        "Cancel my 6:30 alarm | routines" to "- -> CantCancel(timer=false, french=false)",
        "Rappelle-moi ça | routines" to "- -> RemindWhat",
        "Rappelle-moi-le | routines" to "- -> RemindWhat",
        "Remind me about it | routines" to "- -> RemindWhat",
        "Remind me about this. | routines" to "- -> RemindWhat",
        "Remind me | routines" to "- -> RemindWhat",
        "annule mon alarme de 6 h 30 demain | routines" to "- -> CantCancel(timer=false, french=true)",
        "annule mon alarme de 6 h 30 | routines" to "- -> CantCancel(timer=false, french=true)",
        "arrête la minuterie | routines" to "- -> CantCancel(timer=true, french=true)",
        "cancel the timer | routines" to "- -> CantCancel(timer=true, french=false)",
        "delete the 7 am alarm | routines" to "- -> CantCancel(timer=false, french=false)",
        "désactive mon réveil | routines" to "- -> CantCancel(timer=false, french=true)",
        "fais-moi penser à ça | routines" to "- -> RemindWhat",
        "rappelle moi ca | routines" to "- -> RemindWhat",
        "remind me about that | routines" to "- -> RemindWhat",
        "remind me of it | routines" to "- -> RemindWhat",
        "stop the alarm | routines" to "- -> CantCancel(timer=false, french=false)",
        "turn off my alarm | routines" to "- -> CantCancel(timer=false, french=false)",
    )
}
