package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Pure. The phone_status tool (0.4.0): storage, battery and memory as the app process reads them on the phone (StatFs,
// BatteryManager, ActivityManager.MemoryInfo; the apps using the most space and the media sizes only with Usage access),
// written as a few short lines the model gives advice from. Kept small for the 4,096-token context. Nothing leaves the
// phone and nothing is logged.

/** What the person asked about. */
enum class PhonePart {
    STORAGE, BATTERY, MEMORY, ALL;

    companion object {
        /** "storage", "space", "battery", "RAM", "mémoire"…; anything else (or nothing) is all of it. */
        fun of(text: String?): PhonePart {
            val t = Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            fun any(vararg w: String) = w.any { Regex("\\b$it").containsMatchIn(t) }
            val parts = buildSet {
                if (any("storage", "space", "disk", "stockage", "espace", "apps", "files", "photos")) add(STORAGE)
                if (any("battery", "batterie", "charg", "power")) add(BATTERY)
                if (any("memory", "ram", "memoire")) add(MEMORY)
            }
            return parts.singleOrNull() ?: ALL
        }
    }
}

/** The data partition (StatFs on /data): what the phone's own storage settings call internal storage. */
data class StorageFacts(val total: Long, val free: Long) {
    val usedPercent: Int get() = if (total <= 0) 0 else ((total - free).coerceAtLeast(0) * 100.0 / total).roundToInt()
}

/** [minutesToFull]: Android's estimate while charging (null: none). [tempC]: the battery's temperature. */
data class BatteryFacts(val percent: Int, val charging: Boolean, val full: Boolean, val tempC: Double?, val minutesToFull: Int?)

/** ActivityManager.MemoryInfo: [available] and [total] bytes; [low]: Android considers memory low right now. */
data class MemoryFacts(val total: Long, val available: Long, val low: Boolean)

/** One app's storage: the app and its data ([bytes], cache included) and the part that is cache. */
data class AppSize(val label: String, val bytes: Long, val cacheBytes: Long)

/** Photos, videos and audio in shared storage (StorageStatsManager, with Usage access). */
data class MediaFacts(val images: Long, val videos: Long, val audio: Long)

/**
 * What phone_status read. Null parts weren't asked for or couldn't be read. [apps] and [media] need Usage access
 * ([usageAccess]); without it they are null and the answer says how to allow it.
 */
data class PhoneFacts(
    val storage: StorageFacts? = null,
    val battery: BatteryFacts? = null,
    val memory: MemoryFacts? = null,
    val apps: List<AppSize>? = null,
    val media: MediaFacts? = null,
    val usageAccess: Boolean = false,
    /** Robot's own model is loaded in memory now (about [ROBOT_MODEL_BYTES]); null: not known. */
    val robotModelLoaded: Boolean? = null,
)

/** The pages of Android's settings a card can open (Executed.handOff), with the card button's words. */
object SettingsPage {
    const val STORAGE = "settings:storage"
    const val USAGE_ACCESS = "settings:usage_access"

    /** The button for [key]; null for an app's package (the card names that app). */
    fun label(key: String?): String? = when (key) {
        STORAGE -> "Open storage settings"
        USAGE_ACCESS -> "Turn on Usage access"
        else -> null
    }

    fun isPage(key: String?): Boolean = key?.startsWith("settings:") == true
}

object PhoneStatus {
    /** Apps listed at most (about 10 tokens each). */
    const val TOP_APPS = 5
    /** Gemma 4 E2B in memory, about. */
    const val ROBOT_MODEL_BYTES = 2_000_000_000L

    /** Decimal units, as Android's own storage settings: "57 GB", "1.2 GB", "340 MB", "12 KB". */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        fun fmt(v: Double, unit: String) = (if (v < 10) String.format(Locale.US, "%.1f", v).removeSuffix(".0") else v.roundToLong().toString()) + " $unit"
        return when {
            b >= 1e9 -> fmt(b / 1e9, "GB")
            b >= 1e6 -> fmt(b / 1e6, "MB")
            b >= 1e3 -> fmt(b / 1e3, "KB")
            else -> "${b.roundToLong()} bytes"
        }
    }

    /**
     * The tool's answer for the model: one entry per thing read, each a short line. Storage comes with the apps using
     * the most space and the media sizes when Usage access is on, or a note that it is off.
     */
    fun results(f: PhoneFacts, part: PhonePart, units: WeatherUnits = WeatherUnits.METRIC): List<Map<String, Any?>> {
        val m = linkedMapOf<String, Any?>()
        val storage = part == PhonePart.STORAGE || part == PhonePart.ALL
        if (storage) f.storage?.let { m["storage"] = storageLine(it) }
        if (part == PhonePart.BATTERY || part == PhonePart.ALL) f.battery?.let { m["battery"] = batteryLine(it, units) }
        if (part == PhonePart.MEMORY || part == PhonePart.ALL) f.memory?.let { m["memory"] = memoryLine(it, f.robotModelLoaded) }
        if (storage) {
            if (f.usageAccess) {
                f.apps?.takeIf { it.isNotEmpty() }?.let { m["largest_apps"] = appsLine(it) }
                f.media?.let { m["media"] = mediaLine(it) }
            } else {
                m["largest_apps"] = "unknown: Robot needs Usage access to see which apps use the most space (the card has a button for it)"
            }
        }
        return if (m.isEmpty()) emptyList() else listOf(m)
    }

    /** The card's lines (and what text-call mode reads out): one per entry, in words. */
    fun lines(results: List<Map<String, Any?>>): String = results.flatMap { r ->
        r.map { (k, v) -> "${TITLES[k] ?: k}: $v" }
    }.joinToString("\n")

    private val TITLES = mapOf("storage" to "Storage", "battery" to "Battery", "memory" to "Memory", "largest_apps" to "Largest apps", "media" to "Photos, videos and audio")

    /** "57 GB free of 238 GB (76% used)". */
    fun storageLine(s: StorageFacts): String = "${size(s.free)} free of ${size(s.total)} (${s.usedPercent}% used)"

    /** "64%, charging, full in about 50 min, 31°C". */
    fun batteryLine(b: BatteryFacts, units: WeatherUnits = WeatherUnits.METRIC): String = buildList {
        add("${b.percent}%")
        add(when { b.full -> "full"; b.charging -> "charging"; else -> "not charging" })
        b.minutesToFull?.takeIf { b.charging && !b.full && it > 0 }?.let { add("full in about " + if (it < 90) "$it min" else "${(it / 60.0).roundToInt()} h") }
        b.tempC?.let { add(if (units == WeatherUnits.IMPERIAL) "${(it * 9 / 5 + 32).roundToInt()}°F" else "${it.roundToInt()}°C") }
    }.joinToString(", ")

    /** "3.1 GB available of 16 GB", ", low", and whether Robot's own model holds about 2 GB of it. */
    fun memoryLine(m: MemoryFacts, robotModel: Boolean?): String =
        "${size(m.available)} available of ${size(m.total)}" + (if (m.low) " (Android says memory is low)" else "") +
            when (robotModel) {
                true -> "; Robot's own model holds about ${size(ROBOT_MODEL_BYTES)} while loaded"
                else -> ""
            }

    /** "Signal 12 GB (cache 1.2 GB), Organic Maps 4.1 GB": the biggest [TOP_APPS], cache shown from 100 MB. */
    fun appsLine(apps: List<AppSize>): String = top(apps).joinToString(", ") { a ->
        "${NoteSearch.cut(a.label.replace(Regex("\\s+"), " ").trim(), 30)} ${size(a.bytes)}" + if (a.cacheBytes >= 100_000_000) " (cache ${size(a.cacheBytes)})" else ""
    }

    fun top(apps: List<AppSize>, n: Int = TOP_APPS): List<AppSize> = apps.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(n)

    /** "photos 10 GB, videos 21 GB, audio 1.2 GB". */
    fun mediaLine(m: MediaFacts): String = "photos ${size(m.images)}, videos ${size(m.videos)}, audio ${size(m.audio)}"

    /** The card's summary for [part]. */
    fun summary(part: PhonePart): String = when (part) {
        PhonePart.STORAGE -> "Check your phone's storage"
        PhonePart.BATTERY -> "Check your phone's battery"
        PhonePart.MEMORY -> "Check your phone's memory"
        PhonePart.ALL -> "Check your phone's storage, battery and memory"
    }
}
