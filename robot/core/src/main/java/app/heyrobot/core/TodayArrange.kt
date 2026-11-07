// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. Today H4 (home-design §3): the page's parts in the order you put them, by voice ("put my calendar first",
// « mets mon agenda en premier ») or by press-and-hold drag. The right-now line stays on top and the foot at the foot;
// only the parts between move. Phrases are whole utterances (RoutineRouter.whole), so a calendar title, a note or a web
// page that says "put my calendar first" in passing never reorders anything.

/** A part of the Today page that you can move. [key]: its name in home_settings.json. */
enum class TodayPart(val key: String) {
    CALENDAR("calendar"), LISTS("lists"), NOTE("note");

    companion object {
        /** The order H2 drew, and a new page's. */
        val DEFAULT: List<TodayPart> = listOf(CALENDAR, LISTS, NOTE)
        fun of(key: String): TodayPart? = entries.firstOrNull { it.key == key }
    }
}

/** Where a spoken move puts a part. */
enum class TodayMove { FIRST, LAST, UP, DOWN }

object TodayOrder {
    /** [keys] as an order: unknown and repeated names dropped, any part missing added in [TodayPart.DEFAULT]'s order. */
    fun of(keys: List<String>): List<TodayPart> {
        val known = keys.mapNotNull(TodayPart::of).distinct()
        return known + TodayPart.DEFAULT.filter { it !in known }
    }

    /** [order] with [part] moved to [to] (clamped). */
    fun dragged(order: List<TodayPart>, part: TodayPart, to: Int): List<TodayPart> {
        val rest = of(order.map { it.key }).filter { it != part }
        return rest.toMutableList().apply { add(to.coerceIn(0, rest.size), part) }
    }

    /** [order] with [part] dragged to [to] among the [shown] parts; a part not shown keeps its place among the rest. */
    fun placed(order: List<TodayPart>, shown: List<TodayPart>, part: TodayPart, to: Int): List<TodayPart> {
        val full = of(order.map { it.key })
        val others = shown.filter { it != part }
        val rest = full.filter { it != part }
        return dragged(full, part, if (to >= others.size) rest.size else rest.indexOf(others[to.coerceAtLeast(0)]))
    }

    /** [order] after "put [part] first" and its kin. */
    fun moved(order: List<TodayPart>, part: TodayPart, move: TodayMove): List<TodayPart> {
        val o = of(order.map { it.key })
        val at = o.indexOf(part)
        return dragged(o, part, when (move) { TodayMove.FIRST -> 0; TodayMove.LAST -> o.size; TodayMove.UP -> at - 1; TodayMove.DOWN -> at + 1 })
    }
}

/** The spoken and typed phrases, as RoutineRouter's words write them (lower case, no accents, an apostrophe a space). */
object TodayArrange {
    private val NOUNS_EN = mapOf(
        TodayPart.CALENDAR to listOf("my calendar", "the calendar", "calendar", "my agenda", "my events", "my schedule"),
        TodayPart.LISTS to listOf("my lists", "the lists", "lists", "my list", "my lists and reminders", "my reminders"),
        TodayPart.NOTE to listOf("my note", "the note", "note", "my pinned note", "my notes"),
    )
    private val NOUNS_FR = mapOf(
        TodayPart.CALENDAR to listOf("mon agenda", "l agenda", "agenda", "mon calendrier", "le calendrier", "mes evenements"),
        TodayPart.LISTS to listOf("mes listes", "les listes", "ma liste", "mes listes et rappels", "mes rappels"),
        TodayPart.NOTE to listOf("ma note", "la note", "ma note epinglee", "mes notes"),
    )
    private val MOVES_EN = mapOf(
        TodayMove.FIRST to listOf("put # first", "move # first", "make # first", "put # at the top", "move # to the top", "put # on top"),
        TodayMove.LAST to listOf("put # last", "move # last", "put # at the bottom", "move # to the bottom"),
        TodayMove.UP to listOf("move # up", "put # higher", "move # higher"),
        TodayMove.DOWN to listOf("move # down", "put # lower", "move # lower"),
    )
    private val MOVES_FR = mapOf(
        TodayMove.FIRST to listOf("mets # en premier", "place # en premier", "mets # en haut", "monte # en haut", "mets # en premier sur ma page"),
        TodayMove.LAST to listOf("mets # en dernier", "place # en dernier", "mets # en bas", "descends # en bas"),
        TodayMove.UP to listOf("monte #", "remonte #", "monte # d un cran"),
        TodayMove.DOWN to listOf("descends #", "baisse #", "descends # d un cran"),
    )
    private val WHERE_EN = listOf("", " on my day", " on today", " on my page", " on my today page", " on my home page")
    private val WHERE_FR = listOf("", " sur ma page", " dans ma journee", " sur ma page aujourd hui", " sur aujourd hui")

    private fun table(moves: Map<TodayMove, List<String>>, nouns: Map<TodayPart, List<String>>, where: List<String>) =
        moves.flatMap { (m, forms) -> nouns.flatMap { (p, ns) -> forms.flatMap { f -> ns.flatMap { n -> where.map { w -> f.replace("#", n) + w to (p to m) } } } } }

    /** Every phrase, longest first (so "move my calendar to the top" isn't read as a shorter one). */
    val PHRASES: List<Pair<String, Pair<TodayPart, TodayMove>>> =
        (table(MOVES_EN, NOUNS_EN, WHERE_EN) + table(MOVES_FR, NOUNS_FR, WHERE_FR)).distinctBy { it.first }.sortedByDescending { it.first.length }

    /** What Robot says once [part] has moved. */
    fun reply(part: TodayPart, move: TodayMove): app.heyrobot.core.i18n.Phrase = with(app.heyrobot.core.i18n.RoutineStrings) {
        val m = when (move) {
            TodayMove.FIRST -> when (part) { TodayPart.CALENDAR -> arrangedCalendarFirst; TodayPart.LISTS -> arrangedListsFirst; TodayPart.NOTE -> arrangedNoteFirst }
            TodayMove.LAST -> when (part) { TodayPart.CALENDAR -> arrangedCalendarLast; TodayPart.LISTS -> arrangedListsLast; TodayPart.NOTE -> arrangedNoteLast }
            TodayMove.UP -> when (part) { TodayPart.CALENDAR -> arrangedCalendarUp; TodayPart.LISTS -> arrangedListsUp; TodayPart.NOTE -> arrangedNoteUp }
            TodayMove.DOWN -> when (part) { TodayPart.CALENDAR -> arrangedCalendarDown; TodayPart.LISTS -> arrangedListsDown; TodayPart.NOTE -> arrangedNoteDown }
        }
        m()
    }

    /** The French verbs these phrases start with (RoutineRouter.french: the reply is French). */
    val FRENCH_LEAD = listOf("mets", "place", "monte", "remonte", "descends", "baisse")
}
