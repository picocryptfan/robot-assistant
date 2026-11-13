// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// Pure. named-timers (idea 8, docs/plans/named-timers-plan.md): opt-in timers Robot keeps itself, on the exact alarm its
// routines use, so they can be named, asked about, cancelled and stopped, which a clock app's timers can't be (Android lets
// no app cancel or read them). A missed ring is the worst failure: the list is on disk, nothing is only in memory, and a
// timer whose end passed while the phone was off or Robot's process was gone still rings when the alarm comes back
// (within LATE_MS; older, a quiet notice that it was missed).

/**
 * A timer Robot keeps: [name] as said ("pasta"; "" unnamed), [seconds] long, set at [set] (ms since the epoch).
 * [rang]: when it rang (0: not yet); [stopped]: stopped, cancelled while ringing, or only told as missed.
 * [setEl]: [set] in elapsed time (SystemClock.elapsedRealtime) during boot [boot] (Settings.Global.BOOT_COUNT; -1 unknown).
 */
data class OwnTimer(val id: Int, val name: String, val seconds: Long, val set: Long, val rang: Long = 0, val stopped: Boolean = false,
    val setEl: Long = 0, val boot: Int = -1) {
    val ends: Long get() = set + seconds * 1000
}

object OwnTimers {
    const val MAX = 10
    /** A ring stops by itself after this long, as a clock app's does. */
    const val RING_MS = 10 * 60_000L
    /** A timer that ended this long ago or less, while Robot couldn't ring, rings late; older, it is told as missed. */
    const val LATE_MS = 60 * 60_000L
    const val MAX_SECONDS = 24 * 3_600L

    fun encode(timers: List<OwnTimer>): String = JSONArray().also { a ->
        timers.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("s", it.seconds).put("t", it.set).put("r", it.rang).put("x", it.stopped).put("e", it.setEl).put("b", it.boot)) }
    }.toString()

    fun decode(json: String?): List<OwnTimer> = try {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            OwnTimer(o.getInt("i"), o.optString("n", ""), o.getLong("s"), o.getLong("t"), o.optLong("r", 0L), o.optBoolean("x", false),
                o.optLong("e", 0L), o.optInt("b", -1))
                .takeIf { it.seconds in 1..MAX_SECONDS }
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** [timers] still worth keeping at [now]: not rung yet (even long overdue: it still has to ring or be told), or ringing. */
    fun kept(timers: List<OwnTimer>, now: Long): List<OwnTimer> = timers.filter { it.rang == 0L || (!it.stopped && now - it.rang < RING_MS) }

    /**
     * [timers] in [now]'s wall-clock frame. One set during this boot ([boot], when known) keeps its end in elapsed time, so a
     * manual clock change doesn't move it: its start is worked out again from [el]. After a reboot, or with no boot count,
     * the wall-clock end stands, and the timer is stamped for this boot from it.
     */
    fun rebase(timers: List<OwnTimer>, now: Long, el: Long, boot: Int): List<OwnTimer> = timers.map { t ->
        when {
            boot < 0 -> t.copy(boot = -1)
            t.boot == boot && t.setEl <= el -> t.copy(set = now - (el - t.setEl))
            else -> t.copy(setEl = el - (now - t.set), boot = boot)
        }
    }

    fun pending(timers: List<OwnTimer>): List<OwnTimer> = timers.filter { it.rang == 0L }.sortedBy { it.ends }

    /** When the alarm must go off: the soonest end not rung yet (in the past after a restart: at once), or null. */
    fun nextAt(timers: List<OwnTimer>): Long? = pending(timers).firstOrNull()?.ends

    fun running(timers: List<OwnTimer>, now: Long): List<OwnTimer> = pending(timers).filter { it.ends > now }
    fun due(timers: List<OwnTimer>, now: Long): List<OwnTimer> = pending(timers).filter { it.ends <= now }
    fun ringing(timers: List<OwnTimer>, now: Long): List<OwnTimer> = timers.filter { it.rang > 0 && !it.stopped && now - it.rang < RING_MS }

    /** Whether due [t] rings at [now] rather than being told as missed. */
    fun ringsAt(t: OwnTimer, now: Long): Boolean = now - t.ends <= LATE_MS

    sealed class Added {
        data class Ok(val timers: List<OwnTimer>, val timer: OwnTimer) : Added()
        object Full : Added()
        object TooLong : Added()
    }

    /** [timers] with one more, [name] (a second "pasta" is "pasta 2"), [seconds] long, set at [now]. */
    fun add(timers: List<OwnTimer>, name: String, seconds: Long, now: Long): Added {
        if (seconds !in 1..MAX_SECONDS) return Added.TooLong
        val k = kept(timers, now)
        if (k.size >= MAX) return Added.Full
        val base = name.trim().replace(Regex("""\s+"""), " ").take(40)
        val taken = k.map { key(it.name) }.toSet()
        val unique = if (base.isEmpty() || key(base) !in taken) base else (2..MAX + 1).map { "$base $it" }.first { key(it) !in taken } // l10n: not text (a name's number)
        val id = (1..999).first { i -> k.none { it.id == i } }
        val t = OwnTimer(id, unique, seconds, now)
        return Added.Ok(k + t, t)
    }

    fun rang(timers: List<OwnTimer>, ids: Set<Int>, now: Long, missed: Set<Int> = emptySet()): List<OwnTimer> =
        timers.map { if (it.id in ids) it.copy(rang = now, stopped = it.id in missed) else it }
    fun stopped(timers: List<OwnTimer>, ids: Set<Int>): List<OwnTimer> = timers.map { if (it.id in ids) it.copy(stopped = true) else it }
    fun cancelled(timers: List<OwnTimer>, ids: Set<Int>): List<OwnTimer> = timers.filterNot { it.id in ids }

    private val FILLER = setOf("the", "my", "a", "an", "timer", "timers", "le", "la", "les", "l", "de", "des", "du", "d", "ma", "mon", "mes", // l10n: not text (the words a name is matched without)
        "minuterie", "minuteries", "minuteur", "minuteurs", "pour", "for", "on", "of", "aux", "au")

    /** The words [s] is matched by: lower case, no accents or punctuation, no articles or timer words. */
    fun words(s: String): List<String> = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ").trim().split(' ').filter { it.isNotEmpty() }

    fun key(name: String): String = words(name).filter { it !in FILLER }.map { singular(it) }.joinToString(" ")

    private fun singular(w: String): String = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

    /** The kept timers [said] names: the same words first, else every timer whose words hold [said]'s (or the reverse). */
    fun find(timers: List<OwnTimer>, said: String): List<OwnTimer> {
        val k = key(said)
        if (k.isEmpty()) return emptyList()
        timers.filter { key(it.name) == k }.takeIf { it.isNotEmpty() }?.let { return it }
        val ws = k.split(' ').toSet()
        return timers.filter { t -> key(t.name).split(' ').toSet().let { it.isNotEmpty() && key(t.name).isNotEmpty() && (it.containsAll(ws) || ws.containsAll(it)) } }
    }
}
