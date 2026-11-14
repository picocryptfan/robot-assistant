// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

/**
 * fix-calendar-lows: the writes that leave an occurrence out of a series with no _SYNC_ID, in the order of the one
 * applyBatch that sends them all (app/sys/Calendars). The provider runs a batch in one transaction (SQLiteContentProvider,
 * from memory), and each update expects one row: a write that fails undoes the others, so no copy is left beside the
 * occurrence and no copy lacks the series' alerts.
 */
object SplitBatch {
    sealed interface Step {
        /** The occurrence as an event of its own (a split-off only). */
        data object AddEvent : Step
        /** One of the series' alerts, for the event added at step [eventAt]. */
        data class AddAlert(val minutes: Int, val method: Int, val eventAt: Int) : Step
        /** The series' EXDATE ([index] 0), then each exception sent its own time columns again ([index] 1 on), so it stays shown. */
        data class Exclude(val index: Int) : Step
    }

    /** A split-off: the event, its alerts, then [excludes] series writes. */
    fun split(alerts: List<Pair<Int, Int>>, excludes: Int): List<Step> =
        listOf(Step.AddEvent) + alerts.map { (m, k) -> Step.AddAlert(m, k, 0) } + delete(excludes)

    /** A deleted occurrence: the series writes alone. */
    fun delete(excludes: Int): List<Step> = (0 until excludes).map { Step.Exclude(it) }
}

/**
 * fix-calendar-lows: each event Robot split off a series, with that series' id, so a whole-series delete Robot makes takes
 * them too, as the provider takes a series' exceptions. One "split series" pair per line.
 */
object SeriesSplits {
    const val MAX = 2_000

    fun decode(text: String?): Map<Long, Long> = text.orEmpty().lines().mapNotNull { l ->
        l.trim().split(' ').takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { x -> b.toLongOrNull()?.let { y -> x to y } } }
    }.toMap()

    fun encode(splits: Map<Long, Long>): String = splits.entries.joinToString("\n") { "${it.key} ${it.value}" }

    fun add(splits: Map<Long, Long>, split: Long, series: Long): Map<Long, Long> =
        (splits - split + (split to series)).entries.toList().takeLast(MAX).associate { it.key to it.value }

    /** The split-offs of [series] that are still Robot's ([added]): the ones its delete takes. */
    fun of(splits: Map<Long, Long>, series: Long, added: Set<Long>): List<Long> = splits.filter { it.value == series && it.key in added }.keys.sorted()
}
