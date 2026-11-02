// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.json.JSONObject

// Pure (org.json). A restore's outcome, by category: files/robot/restore/result.json, written by
// :voice when its part is done and read by Settings › Back up and restore after the restart, until you close it. Codes
// only: never a name, a count of anyone's items by name, or a word of content.

/** How one category ended: restored, left out (and why), or not restored with nothing of it changed. */
sealed class CategoryOutcome {
    data object Done : CategoryOutcome()
    data class Skipped(val why: PlanSkip) : CategoryOutcome()
    data object Failed : CategoryOutcome()
    /** The phone locked while it was written: nothing of it changed ("Unlock and restore again"). */
    data object Locked : CategoryOutcome()
    /** FX1: written, but Robot Files' last step waits for the phone's next unlock ("finishes after you unlock"). */
    data object AfterUnlock : CategoryOutcome()
    /** FX3: written, but Robot Files' last step waits for free space or a storage error to clear; tried again at each unlock. */
    data object WaitsForRoom : CategoryOutcome()
    /** FX3: not restored, nothing of it changed: an earlier restore's Robot Files step still waits for free space or the storage. */
    data object EarlierWaits : CategoryOutcome()
    /** F46: Keys restored, except [missed] that couldn't be saved here (a key kept on purpose isn't counted). */
    data class KeysMissed(val missed: Int) : CategoryOutcome()
    /** R2: Keys, none came: all [count] have no helper or connection here to go with. Nothing changed. */
    data class KeysNoPlace(val count: Int) : CategoryOutcome()
    /** R1: the restore stopped before this one was written, or while it was and it was undone. Nothing of it changed. */
    data object Cut : CategoryOutcome()
    /** R3: the main app couldn't write it over this phone's file (unreadable, a newer Robot's, a failed write). Nothing of it changed. */
    data object Refused : CategoryOutcome()

    companion object {
        /**
         * F46: how Keys ended, [missed] of [total] not saved. R2: [noPlace] more had nothing here to go with (a key kept on
         * purpose isn't one); [alsoCame]: something else of this category came back (Memory's private codes).
         */
        fun keys(missed: Int, total: Int, noPlace: Int = 0, alsoCame: Boolean = false): CategoryOutcome = when {
            missed <= 0 && noPlace <= 0 -> Done
            !alsoCame && missed >= total -> if (noPlace > 0 && missed <= 0) KeysNoPlace(noPlace) else Failed
            else -> KeysMissed(missed + noPlace)
        }
    }
}

data class RestoreResult(
    val at: Long,
    val mine: Boolean,
    val outcomes: Map<BackupCategory, CategoryOutcome>,
    /** How many guarded settings kept this phone's safer value. */
    val keptSafer: Int = 0,
    /** The Robot version that made the backup, for "needs Robot 1.3 or later". */
    val madeBy: String = "",
    /** R1: the restore stopped part way: what it finished stays, the rest is as it was. */
    val cut: Boolean = false,
    /** R1: while it runs, the process writing it ([RestoreProgress]). */
    val pid: Int = 0,
    /** R4: how many of Robot Files' files were left out of the backup itself, because they couldn't be read when it was made. */
    val filesLeftOut: Int = 0,
) {
    /**
     * R1, R3: what recovery said of each category ([lines]: finished, or not). Finished turns a line the restore didn't reach the
     * end of into restored; not finished turns a line that said restored into refused. Any other line stays.
     */
    fun settled(lines: Map<BackupCategory, Boolean>): RestoreResult = copy(outcomes = outcomes.mapValuesTo(LinkedHashMap()) { (c, o) ->
        when {
            lines[c] == true && o == CategoryOutcome.Cut -> CategoryOutcome.Done
            lines[c] == false && o == CategoryOutcome.Done -> CategoryOutcome.Refused
            else -> o
        }
    })

    fun toJson(): String = JSONObject().put("v", VERSION).put("at", at).put("mine", mine).put("kept", keptSafer).put("madeBy", madeBy)
        .apply { if (cut) put("cut", true); if (pid != 0) put("pid", pid); if (filesLeftOut > 0) put("filesLeft", filesLeftOut) }
        .put("c", JSONObject().apply {
            for ((c, o) in outcomes) put(c.id, when (o) {
                CategoryOutcome.Done -> "done"
                is CategoryOutcome.Skipped -> "skip:" + o.why.name
                CategoryOutcome.Failed -> "failed"
                CategoryOutcome.Locked -> "locked"
                CategoryOutcome.AfterUnlock -> "unlock"
                CategoryOutcome.WaitsForRoom -> "room"
                CategoryOutcome.EarlierWaits -> "earlier"
                is CategoryOutcome.KeysMissed -> "missed:" + o.missed
                is CategoryOutcome.KeysNoPlace -> "noplace:" + o.count
                CategoryOutcome.Cut -> "cut"
                CategoryOutcome.Refused -> "refused"
            })
        }).toString()

    val done: Set<BackupCategory> get() = outcomes.filterValues { it == CategoryOutcome.Done }.keys

    companion object {
        const val FILE = "result.json"
        const val VERSION = 1

        fun fromJson(text: String?): RestoreResult? = try {
            val o = JSONObject(text ?: return null)
            val c = o.optJSONObject("c") ?: JSONObject()
            val out = LinkedHashMap<BackupCategory, CategoryOutcome>()
            for (cat in BackupCategory.entries) {
                val s = c.opt(cat.id) as? String ?: continue
                out[cat] = when {
                    s == "done" -> CategoryOutcome.Done
                    s == "failed" -> CategoryOutcome.Failed
                    s == "locked" -> CategoryOutcome.Locked
                    s == "unlock" -> CategoryOutcome.AfterUnlock
                    s == "room" -> CategoryOutcome.WaitsForRoom
                    s == "earlier" -> CategoryOutcome.EarlierWaits
                    s == "cut" -> CategoryOutcome.Cut
                    s == "refused" -> CategoryOutcome.Refused
                    s.startsWith("noplace:") -> s.removePrefix("noplace:").toIntOrNull()?.takeIf { it > 0 }?.let { CategoryOutcome.KeysNoPlace(it) } ?: continue
                    s.startsWith("missed:") -> s.removePrefix("missed:").toIntOrNull()?.takeIf { it > 0 }?.let { CategoryOutcome.KeysMissed(it) } ?: continue
                    s.startsWith("skip:") -> PlanSkip.entries.firstOrNull { it.name == s.removePrefix("skip:") }?.let { CategoryOutcome.Skipped(it) } ?: continue
                    else -> continue
                }
            }
            RestoreResult((o.opt("at") as? Number)?.toLong() ?: 0, o.opt("mine") as? Boolean ?: false, out,
                (o.opt("kept") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0, (o.opt("madeBy") as? String).orEmpty().take(32),
                o.opt("cut") as? Boolean ?: false, (o.opt("pid") as? Number)?.toInt() ?: 0, (o.opt("filesLeft") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0)
        } catch (_: Exception) { null }
    }
}
