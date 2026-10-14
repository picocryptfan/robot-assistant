package app.heyrobot.core

import org.json.JSONObject

// Pure. Settings › Display (the 0.4.x UI redesign): how Robot looks. Kept in its own small file, look.json, not in
// RobotSettings: the main app writes it, and the voice process reads it again each time the panel opens.

/**
 * [wallpaperColors]: Material You colours from the wallpaper (the default), or Robot's own blue scheme when off.
 * 1.0 dropped the Classic look switch (the panel is always the classic one): an older file's "classic" key is ignored.
 */
data class LookSettings(val wallpaperColors: Boolean = true) {
    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("wallpaperColors", wallpaperColors)
        .toString()

    companion object {
        const val FILE = "look.json"
        const val VERSION = 1

        /** Missing, empty or damaged text gives the defaults; unknown keys and a wrong type for a known key are ignored. */
        fun fromJson(text: String?): LookSettings {
            val o = try { JSONObject(text ?: return LookSettings()) } catch (_: Exception) { return LookSettings() }
            val d = LookSettings()
            fun flag(key: String, default: Boolean): Boolean = when (val v = o.opt(key)) {
                is Boolean -> v
                else -> default
            }
            return LookSettings(wallpaperColors = flag("wallpaperColors", d.wallpaperColors))
        }
    }
}
