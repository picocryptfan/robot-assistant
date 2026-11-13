// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.steps.StepAsk
import app.heyrobot.core.steps.StepTime
import app.heyrobot.core.steps.StepWord
import app.heyrobot.core.steps.StepWords

/** Step by step on what Read it to me reads: [what] is [ReadCommand.Shared], a [ReadCommand.Note] or a [ReadCommand.File]. */
data class StepsOf(val what: ReadCommand) : ReadCommand()

/** While the steps are open, from the panel: one of the words ([word]) or a question the page answers ([question]). */
data class StepSay(val word: StepWord?, val question: String?) : ReadCommand()

// Starting step by step (docs/plans/step-by-step-plan.md), matched whole after ReadRouter.fold:
//   Shared  "walk me through this", "step by step"; « guide-moi étape par étape », « étape par étape »
//   Note    "walk me through my note about pancakes"; « guide-moi dans ma note sur les crêpes »
//   File    "walk me through the PDF called setup"; « guide-moi dans le PDF de l'installation »
object StepRouter {
    fun route(text: String): ReadCommand? {
        val t = ReadRouter.fold(text)
        if (t.isEmpty()) return null
        if (t in SHARED) return StepsOf(ReadCommand.Shared)
        NOTE.matchEntire(t)?.let { return StepsOf(ReadCommand.Note(it.groupValues[3].trim())) }
        FILE.matchEntire(t)?.let { m -> return m.groupValues[3].trim().takeIf { it.isNotEmpty() }?.let { StepsOf(ReadCommand.File(it)) } }
        return null
    }

    /** While the steps are open, what the panel heard: a step word, or a question ([StepAsk.asks]) a line of [page]
     *  answers ([shown]: the step on screen); else null, and it goes where it would without the steps: Sums and the timer
     *  first ("how many grams in a cup", "how long is left on my timer"), "set a timer for 25 minutes", "how much vanilla"
     *  on a page that never names it (no other route takes it: [silent], after them all), a call. */
    fun during(q: String, page: String, shown: String?): StepSay? {
        StepWords.of(q)?.let { return StepSay(it, null) }
        if (!StepAsk.asks(q) || StepAsk.elsewhere(q)) return null
        val line = StepAsk.answer(page, q, shown) ?: return null
        return if (leavesThePage(q, line)) null else StepSay(null, q)
    }

    /** fix-routing-1.4-b (review-fix-routing-1.4 M1): [routedElsewhere] is [silent]'s, where the page says nothing; with a
     *  [line] that answers, a light, a place or "until" on that line is the page's ("how much light brown sugar", "bake it
     *  until golden", "where is the reset button", « où est la vis B »). A trip, "how long until …" or « d'ici », and the
     *  maps, an app or the flashlight by words the line lacks ("where is the nearest hardware store") still leave. */
    private fun leavesThePage(q: String, line: String): Boolean {
        val t = " ${ReadRouter.fold(q)} "
        if (TRAVEL.any { t.contains(" $it ") }) return true
        // fix-routing-1.4-c (review L2): "how long until golden" is the page's when its line names a duration.
        if (NOW_ASKED.any { t.contains(" $it ") } && StepTime.seconds(line) == null) return true
        if (app.heyrobot.core.ToolRouter.asked(q).none { it in PLACES }) return false
        if (placeOnTheLine(t, line)) return true
        val on = ReadRouter.fold(line).split(' ').map(::stem).toSet()
        val rest = t.trim().split(' ').filter { it.isNotEmpty() && stem(it) !in on && it !in SHAPES }
        return rest.isNotEmpty() && app.heyrobot.core.ToolRouter.asked(rest.joinToString(" ")).any { it in PLACES }
    }

    /** fix-routing-1.4-c (review-fix-routing-1.4-b M1): a where/où ask about words the line has after at/to/from or
     *  à/au/chez ("Buy screws at the hardware store", « Achète la levure à l'épicerie ») asks for a real place: maps.
     *  review-batch-17-18 M1: only "at", and à/au/aux/chez, the accent read on the unfolded word ("Connect the cable to
     *  the port", « Le boîtier a un port USB » are the page's). review-batch-19 M1: à/au/aux also mean "to" and "in"
     *  (« Branche le câble au port USB », « Range la boîte au garage »), so only before a name (« au Canadian Tire ») or a
     *  shop's kind (« à l'épicerie »); chez always. Unclear stays on the page: reading the line back beats leaving for maps. */
    private fun placeOnTheLine(t: String, line: String): Boolean {
        val fr = t.contains(" ou est ")
        if (!fr && WHERE.none { t.contains(" $it ") }) return false
        val asked = t.trim().split(' ').filter { it.isNotEmpty() && it !in SHAPES && it !in ARTICLES }.map(::stem).toSet()
        val cased = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFC).split(WORD_GAP).filter { it.isNotEmpty() }
        val raw = cased.map { it.lowercase(java.util.Locale.ROOT) }
        val w = raw.map { ReadRouter.fold(it) }
        val at = if (fr) AT_FR else AT_EN
        for (i in w.indices) {
            if (stem(w[i]) !in asked) continue
            var j = i - 1
            while (j >= 0 && raw[j] !in at && (w[j] in ARTICLES || stem(w[j]) in asked)) j--
            if (j >= 0 && raw[j] in at && (raw[j] !in TO_FR || namesAPlace(cased, w, j + 1))) return true
        }
        return false
    }
    private val WHERE = listOf("where is", "where s", "wheres", "where are") // l10n: not text (words Robot hears)
    private val AT_EN = setOf("at") // l10n: not text (words on a page)
    private val AT_FR = setOf("à", "au", "aux", "chez") // l10n: not text (words on a page)
    private val TO_FR = setOf("à", "au", "aux") // l10n: not text (words on a page)
    /** review-batch-19 M1: after à/au/aux, a place is a name (a capital, then lower case: « Canadian Tire », not « USB ») or
     *  a shop's kind; the first word past the articles decides. */
    private fun namesAPlace(cased: List<String>, w: List<String>, from: Int): Boolean {
        var k = from
        while (k < w.size && w[k] in ARTICLES) k++
        if (k >= w.size) return false
        val c = cased[k]
        return w[k] in SHOPS_FR || c.length > 1 && c[0].isUpperCase() && c[1].isLowerCase()
    }
    private val SHOPS_FR = setOf("epicerie", "pharmacie", "quincaillerie", "depanneur", "magasin", "supermarche", "marche", // l10n: not text (words on a page)
        "boulangerie", "boucherie", "fruiterie", "banque", "caisse", "clinique", "hopital", "bibliotheque", "librairie", "ecocentre") // l10n: not text (words on a page)
    private val WORD_GAP = Regex("""[-‐‑–—'’`«»"“”.,!?;:()\u00A0\u202F\s]+""")
    private val ARTICLES = setOf("the", "a", "an", "my", "your", "le", "la", "les", "l", "un", "une", "des", "du", "ma", "mon", "ta", "ton") // l10n: not text (words on a page)

    private fun stem(w: String) = if (w.length > 3) w.removeSuffix("s") else w
    /** A question's shape, not a place: "where is …", "which direction …", « où est … » ([leavesThePage]). */
    private val SHAPES = setOf("where", "wheres", "s", "is", "which", "direction", "ou", "est") // l10n: not text (words Robot hears)
    private val NOW_ASKED = listOf("how long until", "how long till", "how long til", "how long since", "how much longer until", "d ici") // l10n: not text (words Robot hears)

    /** fix-train-14 (phone-train-14, step-by-step 4): a question shaped like one for the page that no line answers and
     *  no other route took ("how much vanilla" on a page that never names it): "The page doesn't say", never the model's
     *  guess. Asked after every router, before the model; a question for one of the model's tools (the battery, the
     *  weather, your calendar) or about a time from now ("how long until Christmas") keeps its route. */
    fun silent(q: String): Boolean {
        if (StepWords.of(q) != null || !StepAsk.asks(q) || StepAsk.elsewhere(q) || routedElsewhere(q)) return false
        return app.heyrobot.core.ToolRouter.route(q) == setOf(app.heyrobot.core.ToolGroup.CORE)
    }

    /** fix-routing-1.4 (review-routing-1.4 L1): what [during] and [silent] both leave to other routes, written once: a
     *  time from now ("how long until the store opens"), a trip, the maps, an app or the flashlight. */
    private fun routedElsewhere(q: String): Boolean {
        val t = " ${ReadRouter.fold(q)} "
        if (FROM_NOW.any { t.contains(" $it ") }) return true
        // review-batch-14-rest's M3: CORE holds the maps, open app and the flashlight; a trip is no page question. Only
        // with a destination (review-batch-14-rest-b): "which way does the blade go" and "drive the screw in" are steps'.
        return TRAVEL.any { t.contains(" $it ") } || app.heyrobot.core.ToolRouter.asked(q).any { it in PLACES }
    }

    private val PLACES = setOf(app.heyrobot.core.ToolName.MAPS_SEARCH, app.heyrobot.core.ToolName.MAPS_NAVIGATE,
        app.heyrobot.core.ToolName.OPEN_APP, app.heyrobot.core.ToolName.FLASHLIGHT)
    private val TRAVEL = listOf("drive to", "driving to", "drive me", "walk to", "which way to", "how far is it to", "conduire", "en voiture", "a pied", "trajet", // l10n: not text (words Robot hears)
        "aller a", "quel chemin") // l10n: not text (words Robot hears)

    private val FROM_NOW = listOf("until", "till", "til", "since", "left until", "jusqu", "jusqu a", "depuis", "d ici") // l10n: not text (words Robot hears)

    private val SHARED = setOf(
        "walk me through this", "walk me through it", "walk me through this recipe", "walk me through the recipe", // l10n: not text (words Robot hears)
        "walk me through this page", "walk me through these steps", "walk me through the steps", "take me through this", // l10n: not text (words Robot hears)
        "step by step", "go step by step", "walk me through this step by step", "read this step by step", "read me this step by step", // l10n: not text (words Robot hears)
        "guide moi", "guide moi etape par etape", "etape par etape", "guide moi dans cette recette", "guide moi dans ces etapes", // l10n: not text (words Robot hears)
        "explique moi ca etape par etape", "lis moi ca etape par etape", "accompagne moi etape par etape", // l10n: not text (words Robot hears)
    )
    private const val LEAD = """(walk me through|take me through|guide moi dans|guide moi a travers|accompagne moi dans)""" // l10n: not text (words Robot hears)
    private val NOTE = Regex("""$LEAD (my|the|ma|la) note(?: (?:about|on|called|titled|for|sur|a propos de|qui s appelle|pour|de|du|des) (.+))?()""") // l10n: not text (words Robot hears)
    private val FILE = Regex("""$LEAD (my|the|le|mon) (?:pdf|document|file|fichier)(?: (?:about|on|called|titled|named|sur|qui s appelle|intitule|de|du|des))?( .+)""") // l10n: not text (words Robot hears)
}
