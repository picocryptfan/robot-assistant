// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.io.File
import java.time.ZonedDateTime

// Pure. today-fix-b (review finding 4): Today and the Calendar tab read the phone (the calendar provider, the private events'
// and the lists' vaults through the Keystore, the notes, Files' sealed list, Robot to Robot over :link) when the page opens
// and when something they read has changed: a file, a setting, the lock, the date. Once a minute only the clock moves: the
// right-now and leave-by lines are made again from what was read.

/** A page's reads, kept for one open while its stamp stays the same. */
class PageRefresh<S, R : Any> {
    private var opened = -1
    private var stamp: S? = null
    private var last: R? = null

    /**
     * The read for page open [open] with [now] its stamp: [read] runs only on a new open or a changed stamp. [read] gets the
     * last read ([last], null at first) and whether this is a new open ([fresh]); otherwise the last read is given back.
     */
    @Synchronized
    fun get(open: Int, now: S, read: (last: R?, fresh: Boolean) -> R): R {
        val kept = last
        if (kept != null && open == opened && now == stamp) return kept
        return read(kept, open != opened).also { last = it; opened = open; stamp = now }
    }
}

/** What a page read stands on, without opening anything: each file's, and each folder's files', last change and size. */
object PageStamp {
    fun files(vararg fs: File): List<Long> = fs.flatMap { f ->
        if (f.isDirectory) listOf(f.lastModified()) + f.listFiles().orEmpty().sortedBy { it.name }.flatMap { listOf(it.name.hashCode().toLong(), it.lastModified(), it.length()) }
        else listOf(f.lastModified(), f.length())
    }
}

/** Today's page as read ([TodaySource.read] in app): [view] at any minute from it, nothing read again. */
class TodayRead(
    val input: DayInput,
    val week: List<CalendarEvent>?,
    val lists: List<ListGlance>,
    val looseEnds: Int,
    val fromRobots: Int,
    val note: NoteGlance?,
    /** The notes the page offers to pin: id and title. */
    val choices: List<Pair<String, String>> = emptyList(),
    /** H6: the weather line at a minute, from the forecast read with the page; null while it is off. */
    val weather: ((ZonedDateTime) -> WeatherGlance)? = null,
) {
    fun view(now: ZonedDateTime): TodayView =
        TodayPage.view(TodayInput(Today.picture(input.copy(now = now)), week, lists, looseEnds, fromRobots, note, weather?.invoke(now)))
}
