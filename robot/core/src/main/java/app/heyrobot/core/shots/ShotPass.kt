// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.json.JSONArray
import org.json.JSONObject

// Pure. The charging pass's choices (screenshots-plan.md, "What's new" 1 and 3; questions 2 and 3's defaults): only
// screenshots in a screenshots folder, never other photos even with full access; only those added after you switched it
// on (MediaStore's generation then, and the time); with Selected photos only the picked ones, each whatever its date, since
// picking it is the yes (STATUS question 96's pick); at most [CAP] a night.
// Every entry whose screenshot is gone, in the trash or no longer picked is dropped.

/** The feature's own file (shots/state.json, not settings.json): plain, and holds no screenshot's content. */
data class ShotsState(
    val on: Boolean = false,
    /** When it was switched on (ms): nothing added before is read. */
    val since: Long = 0,
    /** MediaStore's generation past which screenshots are new: at switch-on, then the last pass's. */
    val generation: Long = 0,
    val lastPass: Long = 0,
    /** Screenshots read since switch-on (a count for the settings page). */
    val read: Int = 0,
    /** The MediaStore ids the index holds (numbers only), so a pass can drop gone ones while the index stays sealed. */
    val indexed: Set<Long> = emptySet(),
    /** The next batch's number. */
    val next: Long = 1,
    /** A random number per switch-on: a pass whose epoch is no longer the store's (deleted, off) writes nothing. */
    val epoch: Long = 0,
) {
    fun toJson(): String = JSONObject().put("on", on).put("since", since).put("generation", generation).put("lastPass", lastPass)
        .put("read", read).put("indexed", JSONArray(indexed.toList())).put("next", next).put("epoch", epoch).toString()

    companion object {
        fun fromJson(s: String?): ShotsState = try {
            val o = JSONObject(s ?: "{}")
            val ids = o.optJSONArray("indexed") ?: JSONArray()
            ShotsState(o.optBoolean("on"), o.optLong("since"), o.optLong("generation"), o.optLong("lastPass"), o.optInt("read"),
                (0 until ids.length()).map { ids.getLong(it) }.toSet(), o.optLong("next", 1).coerceAtLeast(1), o.optLong("epoch"))
        } catch (_: Exception) {
            ShotsState()
        }
    }
}

/** A MediaStore row: [path] its RELATIVE_PATH, [added] DATE_ADDED in ms, [generation] GENERATION_ADDED. */
data class ShotRow(val id: Long, val generation: Long, val path: String?, val added: Long, val trashed: Boolean = false, val pending: Boolean = false)

data class ShotPlan(val read: List<ShotRow>, val drop: Set<Long>, val capped: Boolean)

object ShotPass {
    val FOLDERS = listOf("Pictures/Screenshots/", "DCIM/Screenshots/") // l10n: not text (MediaStore paths)
    const val CAP = 50

    fun inFolder(path: String?): Boolean = path != null && FOLDERS.any { path.startsWith(it, ignoreCase = true) }

    /** What a pass reads and drops. [picked]: with Selected photos, the ids Android lets Robot see (null: all photos). */
    fun plan(state: ShotsState, rows: List<ShotRow>, picked: Set<Long>?, cap: Int = CAP): ShotPlan {
        if (!state.on) return ShotPlan(emptyList(), state.indexed, false)
        val visible = rows.filter { !it.trashed && inFolder(it.path) && (picked == null || it.id in picked) }
        val present = visible.map { it.id }.toSet()
        val fresh = visible.filter { !it.pending && it.id !in state.indexed && (picked != null || (it.generation > state.generation && it.added >= state.since)) }
            .sortedBy { it.generation }
        return ShotPlan(fresh.take(cap), state.indexed - present, fresh.size > cap)
    }

    /** The state after a pass that went through [done] of [plan]'s screenshots (in order), the model reading [readIds]
     *  of them into [entries], at [now]. */
    fun after(state: ShotsState, plan: ShotPlan, done: Int, entries: List<ShotEntry>, rows: List<ShotRow>, now: Long,
              readIds: Collection<Long> = plan.read.take(done).map { it.id }): ShotsState {
        val all = done == plan.read.size && !plan.capped
        val gen = if (all) maxOf(state.generation, rows.maxOfOrNull { it.generation } ?: 0)
            else plan.read.getOrNull(done - 1)?.generation?.let { maxOf(it, state.generation) } ?: state.generation
        return state.copy(generation = gen, lastPass = now, read = state.read + entries.size,
            // A screenshot read to nothing counts as read too, so with Selected photos a blank pick isn't read every pass;
            // one that couldn't be opened doesn't, so it's tried again (review-last-fixes-14 Low 3).
            indexed = state.indexed - plan.drop + readIds)
    }
}
