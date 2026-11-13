// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. HS2 (home-screens plan, "What's new" 5): Quick access's order, by drag or by voice ("put car mode first",
// « mets le mode voiture en premier »), on TodayArrange's rules: unknown and repeated names dropped, a missing button
// added in the plan's order, whole utterances only. Not wired to a page yet (HS3): there, a phrase acts on the home you
// picked, since "my lists" is both a Today part and a button.

object QuickOrder {
    /** [keys] as an order: unknown and repeated names dropped, any button missing added in [QuickItem.DEFAULT]'s order. */
    fun of(keys: List<String>): List<QuickItem> {
        val known = keys.mapNotNull(QuickItem::of).distinct()
        return known + QuickItem.DEFAULT.filter { it !in known }
    }

    /** [order] with [item] moved to [to] (clamped). */
    fun dragged(order: List<QuickItem>, item: QuickItem, to: Int): List<QuickItem> {
        val rest = of(order.map { it.key }).filter { it != item }
        return rest.toMutableList().apply { add(to.coerceIn(0, rest.size), item) }
    }

    /** [order] with [item] dragged to [to] among the [shown] buttons; a button not shown keeps its place among the rest. */
    fun placed(order: List<QuickItem>, shown: List<QuickItem>, item: QuickItem, to: Int): List<QuickItem> {
        val full = of(order.map { it.key })
        val others = shown.filter { it != item }
        val rest = full.filter { it != item }
        return dragged(full, item, if (to >= others.size) rest.size else rest.indexOf(others[to.coerceAtLeast(0)]))
    }

    /** [order] after "put car mode first" and its kin; UP and DOWN step past buttons this phone doesn't show. */
    fun moved(order: List<QuickItem>, item: QuickItem, move: TodayMove, shown: List<QuickItem> = QuickItem.DEFAULT): List<QuickItem> {
        val vis = of(order.map { it.key }).filter { it in shown || it == item }
        val at = vis.indexOf(item)
        return placed(order, vis, item, when (move) { TodayMove.FIRST -> 0; TodayMove.LAST -> vis.size; TodayMove.UP -> at - 1; TodayMove.DOWN -> at + 1 })
    }
}

/** The spoken and typed phrases, as RoutineRouter's words write them (lower case, no accents, an apostrophe a space). */
object QuickArrange {
    private val NOUNS_EN = mapOf(
        QuickItem.TALK to listOf("talk to robot", "talk"), QuickItem.CAR to listOf("car mode", "the car mode"),
        QuickItem.LINK to listOf("robot to robot"), QuickItem.SKILLS to listOf("skills", "my skills"),
        QuickItem.LISTS to listOf("lists", "my lists", "the lists", "my list", "my lists and reminders", "my reminders"), QuickItem.NOTES to listOf("notes", "my notes", "the notes"),
        QuickItem.FILES to listOf("files", "my files", "the files"), QuickItem.CALENDAR to listOf("calendar", "my calendar", "the calendar", "my agenda", "my events", "my schedule"),
        QuickItem.HISTORY to listOf("history", "my history", "the history"), QuickItem.TRANSCRIBE to listOf("transcribe"),
        QuickItem.INTERPRETER to listOf("interpreter", "the interpreter"), QuickItem.ROUTINES to listOf("routines", "my routines", "the routines"),
    )
    // "le mode voiture" is what people say (the brief's phrase); the glossary's word, shown on screen, is « mode auto ».
    private val NOUNS_FR = mapOf(
        QuickItem.TALK to listOf("parler a robot"), QuickItem.CAR to listOf("le mode voiture", "le mode auto", "mode auto"),
        QuickItem.LINK to listOf("robot a robot"), QuickItem.SKILLS to listOf("les competences", "mes competences"),
        QuickItem.LISTS to listOf("mes listes", "les listes", "ma liste", "mes listes et rappels", "mes rappels"), QuickItem.NOTES to listOf("mes notes", "les notes"),
        QuickItem.FILES to listOf("mes fichiers", "les fichiers"), QuickItem.CALENDAR to listOf("mon agenda", "l agenda", "le calendrier", "agenda", "mon calendrier", "mes evenements"),
        QuickItem.HISTORY to listOf("l historique", "mon historique"), QuickItem.TRANSCRIBE to listOf("transcrire", "la transcription"),
        QuickItem.INTERPRETER to listOf("l interprete", "interprete"), QuickItem.ROUTINES to listOf("les routines", "mes routines"),
    )
    private val MOVES_EN = mapOf(
        TodayMove.FIRST to listOf("put # first", "move # first", "make # first", "put # at the top", "move # to the top", "put # on top"),
        TodayMove.LAST to listOf("put # last", "move # last", "put # at the bottom", "move # to the bottom"),
        TodayMove.UP to listOf("move # up", "put # higher", "move # higher"),
        TodayMove.DOWN to listOf("move # down", "put # lower", "move # lower"),
    )
    private val MOVES_FR = mapOf(
        TodayMove.FIRST to listOf("mets # en premier", "place # en premier", "mets # en haut", "monte # en haut"),
        TodayMove.LAST to listOf("mets # en dernier", "place # en dernier", "mets # en bas", "descends # en bas"),
        TodayMove.UP to listOf("monte #", "remonte #", "monte # d un cran"),
        TodayMove.DOWN to listOf("descends #", "baisse #", "descends # d un cran"),
    )
    private val WHERE_EN = listOf("", " on quick access", " in quick access", " on my home page")
    private val WHERE_FR = listOf("", " sur l acces rapide", " dans l acces rapide", " sur ma page")

    private fun table(moves: Map<TodayMove, List<String>>, nouns: Map<QuickItem, List<String>>, where: List<String>) =
        moves.flatMap { (m, forms) -> nouns.flatMap { (p, ns) -> forms.flatMap { f -> ns.flatMap { n -> where.map { w -> f.replace("#", n) + w to (p to m) } } } } }

    /** Every phrase, longest first, as TodayArrange.PHRASES (for RoutineRouter's whole-utterance match in HS3). */
    val PHRASES: List<Pair<String, Pair<QuickItem, TodayMove>>> =
        (table(MOVES_EN, NOUNS_EN, WHERE_EN) + table(MOVES_FR, NOUNS_FR, WHERE_FR)).distinctBy { it.first }.sortedByDescending { it.first.length }

    private val BY_TEXT = PHRASES.toMap()

    /** HS3: the button's name, as the page shows it and Robot says it. */
    fun name(item: QuickItem): app.heyrobot.core.i18n.Phrase = with(app.heyrobot.core.i18n.RoutineStrings) {
        when (item) {
            QuickItem.TALK -> quickTalk; QuickItem.CAR -> quickCar; QuickItem.LINK -> quickLink; QuickItem.SKILLS -> quickSkills
            QuickItem.LISTS -> quickLists; QuickItem.NOTES -> quickNotes; QuickItem.FILES -> quickFiles; QuickItem.CALENDAR -> quickCalendar
            QuickItem.HISTORY -> quickHistory; QuickItem.TRANSCRIBE -> quickTranscribe; QuickItem.INTERPRETER -> quickInterpreter
            QuickItem.ROUTINES -> quickRoutines
        }()
    }

    /** HS3: what Robot says once [item] has moved. */
    fun reply(item: QuickItem, move: TodayMove): app.heyrobot.core.i18n.Phrase = with(app.heyrobot.core.i18n.RoutineStrings) {
        when (move) { TodayMove.FIRST -> quickFirst; TodayMove.LAST -> quickLast; TodayMove.UP -> quickUp; TodayMove.DOWN -> quickDown }("name" to name(item))
    }

    /**
     * HS3: "put my lists first" (a Today part and a button both) with Quick access as your home moves its button; on the
     * other homes, for the pinned note ("my note": no button) or when you name Today ("… on today"), Today's order changes
     * as before. By the words you said (Arrange.quick), not the part: "my notes" is the Notes button. Null: Today's.
     */
    fun onQuick(cmd: RoutineCommand.Arrange, home: HomeChoice): Pair<QuickItem, TodayMove>? =
        if (home == HomeChoice.QUICK) cmd.quick?.let { it to cmd.move } else null

    /** The button [phrase] (one of TodayArrange.PHRASES, as matched) names when said on Quick access, or null. */
    fun named(phrase: String): QuickItem? = BY_TEXT[phrase]?.first

    /** [text] as typed or heard, when it is one of the phrases and nothing more; else null. */
    fun match(text: String): Pair<QuickItem, TodayMove>? =
        BY_TEXT[StepPhrases.Words(text).t.map { it.w }.filter { it != "," }.joinToString(" ")]
}
