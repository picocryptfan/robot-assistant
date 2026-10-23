// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). Back up and restore's two small files in files/robot/, apart from settings.json (which
// stays at schema 13), one writer each, neither holding where a backup went or anything of its content:
//
//   backup.json        [BackupPrefs], the main app's: the monthly reminder's switch and when it last showed
//   backup_state.json  [BackupState], :voice's (where the backup window runs): the last backup (when, its categories,
//                      its size, keys or not, checked or not) and the switches chosen for the next one
//
// Read forward-compatibly: a missing or damaged file gives the defaults, unknown keys and a known key of the wrong type
// are ignored.

data class BackupPrefs(
    /** "Remind me to back up every month": off. */
    val remind: Boolean = false,
    /** When the switch was turned on (wall clock ms), the reminder's start when there was no backup since. */
    val remindSince: Long = 0,
    /** When the last reminder showed; null: never. */
    val lastShown: Long? = null,
) {
    fun toJson(): String = JSONObject().put("v", VERSION).put("remind", remind).put("remindSince", remindSince)
        .apply { lastShown?.let { put("lastShown", it) } }.toString()

    companion object {
        const val FILE = "backup.json"
        const val VERSION = 1

        fun fromJson(text: String?): BackupPrefs {
            val o = obj(text) ?: return BackupPrefs()
            return BackupPrefs(
                remind = o.opt("remind") as? Boolean ?: false,
                remindSince = (o.opt("remindSince") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                lastShown = (o.opt("lastShown") as? Number)?.toLong()?.takeIf { it > 0 },
            )
        }
    }
}

/**
 * The last backup ([lastAt] null: none yet) and what Back up now offers next time ([chosen], [recordings], [pictures],
 * [videos]: the switches you chose last; the defaults until then, and for a category added since).
 */
data class BackupState(
    val lastAt: Long? = null,
    val lastCategories: Set<BackupCategory> = emptySet(),
    val lastBytes: Long = 0,
    val lastKeys: Boolean = false,
    /** The saved file read back and every chunk checked ("Backup saved and checked"). */
    val lastChecked: Boolean = false,
    val chosen: Set<BackupCategory> = BackupCategory.entries.filter { it.defaultOn }.toSet(),
    /** Transcripts' recordings (audio.ogg): off. */
    val recordings: Boolean = false,
    /** History's kept screenshots and photos: on. */
    val pictures: Boolean = true,
    /** 1.4: Robot Files' videos: on (the one backup carries everything unless you leave them out). */
    val videos: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("v", VERSION).apply {
        lastAt?.let { put("lastAt", it) }
        put("lastCategories", JSONArray(lastCategories.map { it.id }))
        put("lastBytes", lastBytes)
        put("lastKeys", lastKeys)
        put("lastChecked", lastChecked)
        put("chosen", JSONArray(chosen.map { it.id }))
        // 1.4: the categories Back up now offered when this was saved, so one added since starts at its default.
        put("offered", JSONArray(BackupCategory.entries.map { it.id }))
        put("recordings", recordings)
        put("pictures", pictures)
        put("videos", videos)
    }.toString()

    /** After a backup saved at [at] with [categories], [bytes] long, [checked] or not. */
    fun after(at: Long, categories: Set<BackupCategory>, bytes: Long, checked: Boolean): BackupState =
        copy(lastAt = at, lastCategories = categories, lastBytes = bytes, lastKeys = BackupCategory.KEYS in categories, lastChecked = checked)

    companion object {
        const val FILE = "backup_state.json"
        const val VERSION = 1

        fun fromJson(text: String?): BackupState {
            val o = obj(text) ?: return BackupState()
            val d = BackupState()
            fun cats(k: String): Set<BackupCategory>? = (o.opt(k) as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { BackupCategory.of(a.opt(it) as? String) }.toSet() }
            // A category Back up now didn't offer when your choices were saved (1.1's file has no "offered": it offered all
            // but Files) starts at its default, rather than as one you left out.
            val offered = cats("offered") ?: (BackupCategory.entries.toSet() - BackupCategory.FILES)
            val chosen = cats("chosen")?.let { saved -> saved + BackupCategory.entries.filter { it.defaultOn && it !in offered } } ?: d.chosen
            return BackupState(
                lastAt = (o.opt("lastAt") as? Number)?.toLong()?.takeIf { it > 0 },
                lastCategories = cats("lastCategories") ?: d.lastCategories,
                lastBytes = (o.opt("lastBytes") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0,
                lastKeys = o.opt("lastKeys") as? Boolean ?: false,
                lastChecked = o.opt("lastChecked") as? Boolean ?: false,
                chosen = chosen,
                recordings = o.opt("recordings") as? Boolean ?: d.recordings,
                pictures = o.opt("pictures") as? Boolean ?: d.pictures,
                videos = o.opt("videos") as? Boolean ?: d.videos,
            )
        }
    }
}

private fun obj(text: String?): JSONObject? = try { text?.let(::JSONObject) } catch (_: Exception) { null }
