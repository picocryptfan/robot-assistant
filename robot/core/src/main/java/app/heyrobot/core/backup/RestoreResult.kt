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
}

data class RestoreResult(
    val at: Long,
    val mine: Boolean,
    val outcomes: Map<BackupCategory, CategoryOutcome>,
    /** How many guarded settings kept this phone's safer value. */
    val keptSafer: Int = 0,
    /** The Robot version that made the backup, for "needs Robot 1.3 or later". */
    val madeBy: String = "",
) {
    fun toJson(): String = JSONObject().put("v", VERSION).put("at", at).put("mine", mine).put("kept", keptSafer).put("madeBy", madeBy)
        .put("c", JSONObject().apply {
            for ((c, o) in outcomes) put(c.id, when (o) {
                CategoryOutcome.Done -> "done"
                is CategoryOutcome.Skipped -> "skip:" + o.why.name
                CategoryOutcome.Failed -> "failed"
                CategoryOutcome.Locked -> "locked"
                CategoryOutcome.AfterUnlock -> "unlock"
                CategoryOutcome.WaitsForRoom -> "room"
                CategoryOutcome.EarlierWaits -> "earlier"
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
                    s.startsWith("skip:") -> PlanSkip.entries.firstOrNull { it.name == s.removePrefix("skip:") }?.let { CategoryOutcome.Skipped(it) } ?: continue
                    else -> continue
                }
            }
            RestoreResult((o.opt("at") as? Number)?.toLong() ?: 0, o.opt("mine") as? Boolean ?: false, out,
                (o.opt("kept") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0, (o.opt("madeBy") as? String).orEmpty().take(32))
        } catch (_: Exception) { null }
    }
}
