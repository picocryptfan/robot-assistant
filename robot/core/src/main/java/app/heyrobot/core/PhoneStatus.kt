// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
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

/** One app's storage: the app and its data ([bytes], cache included) and the part that is cache; [pkg] for its info page. */
data class AppSize(val label: String, val bytes: Long, val cacheBytes: Long, val pkg: String = "",
    /** 1.1: [label] as words without a language yet, for Robot's own row ([InfoStrings.robotModels]); null: [label] is the app's own name. */
    val labelText: Phrase? = null) {
    /** The name in a line or on a button: Robot's own row in the reply language, another app's label as it is (cut at 30). */
    val nameText: Phrase get() = labelText ?: Phrase.plain(NoteSearch.cut(label.replace(Regex("\\s+"), " ").trim(), 30))
}

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
    /** Robot's own model is loaded in memory now (about [robotModelBytes]); null: not known. */
    val robotModelLoaded: Boolean? = null,
    /** What the loaded model holds, about (0.4.1: 2 GB for Fast, 4 GB for the E4B models). */
    val robotModelBytes: Long = PhoneStatus.ROBOT_MODEL_BYTES,
)

/** The pages of Android's settings a card can open (Executed.handOff), with the card button's words. */
object SettingsPage {
    const val STORAGE = "settings:storage"
    const val USAGE_ACCESS = "settings:usage_access"
    /** Android's own "Free up space" screen (free_storage). */
    const val FREE_UP = "settings:free_up"
    /** 0.7: Robot's own Settings › Memory (a memory card's "Open Memory"). */
    const val MEMORY = "settings:memory"

    /** The button for [key]; null for an app's package (the card names that app). */
    fun label(key: String?): String? = labelText(key)?.en()

    /** [label] in words without a language yet (1.1: a button, in the menus' language). */
    fun labelText(key: String?): Phrase? = when (key) {
        STORAGE -> InfoStrings.openStorageSettings()
        USAGE_ACCESS -> InfoStrings.turnOnUsageAccess()
        FREE_UP -> InfoStrings.openFreeUpSpace()
        MEMORY -> InfoStrings.openPage("page" to UiLabel(UiStrings.memory))
        else -> null
    }

    fun isPage(key: String?): Boolean = key?.startsWith("settings:") == true
}

object PhoneStatus {
    /** Apps listed at most (about 10 tokens each). */
    const val TOP_APPS = 5
    /** Gemma 4 E2B in memory, about (BrainModel.FAST.memoryBytes; the default when the loaded model isn't known). */
    const val ROBOT_MODEL_BYTES = 2_000_000_000L

    /** Decimal units, as Android's own storage settings: "57 GB", "1.2 GB", "340 MB", "12 KB". */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0).toDouble()
        fun fmt(v: Double, unit: String) = (if (v < 10) String.format(Locale.US, "%.1f", v).removeSuffix(".0") else v.roundToLong().toString()) + " $unit"
        return when {
            b >= 1e9 -> fmt(b / 1e9, "GB")
            b >= 1e6 -> fmt(b / 1e6, "MB")
            b >= 1e3 -> fmt(b / 1e3, "KB")
            else -> "${b.roundToLong()} bytes" // l10n: not text (sizeText's English)
        }
    }

    /** [size] in words without a language yet: "1.2 GB", « 1,2 Go » (« 1,2 gigaoctet » aloud). 1,000 GB and more as [size] writes it. */
    fun sizeText(bytes: Long): Phrase {
        val b = bytes.coerceAtLeast(0).toDouble()
        val (v, unit) = when {
            b >= 1e9 -> b / 1e9 to InfoStrings.sizeGb
            b >= 1e6 -> b / 1e6 to InfoStrings.sizeMb
            b >= 1e3 -> b / 1e3 to InfoStrings.sizeKb
            else -> return InfoStrings.sizeBytes(b.roundToLong())
        }
        if (v >= 10 && v.roundToLong() >= 1000) return Phrase.plain(size(bytes))
        return unit("n" to Arg.Num(v, if (v < 10) 1 else 0))
    }

    /**
     * The tool's answer for the model: one entry per thing read, each a short line. Storage comes with the apps using
     * the most space and the media sizes when Usage access is on, or a note that it is off.
     */
    fun results(f: PhoneFacts, part: PhonePart, units: WeatherUnits = WeatherUnits.METRIC): List<Map<String, Any?>> =
        entries(f, part, units).let { e -> if (e.isEmpty()) emptyList() else listOf(e.mapValues { it.value.en() }) }

    /** What [results] holds, in words without a language yet. */
    private fun entries(f: PhoneFacts, part: PhonePart, units: WeatherUnits): Map<String, Phrase> {
        val m = linkedMapOf<String, Phrase>()
        val storage = part == PhonePart.STORAGE || part == PhonePart.ALL
        if (storage) f.storage?.let { m["storage"] = storageText(it) }
        if (part == PhonePart.BATTERY || part == PhonePart.ALL) f.battery?.let { m["battery"] = batteryText(it, units) }
        if (part == PhonePart.MEMORY || part == PhonePart.ALL) f.memory?.let { m["memory"] = memoryText(it, f.robotModelLoaded, f.robotModelBytes) }
        if (storage) {
            if (f.usageAccess) {
                f.apps?.takeIf { it.isNotEmpty() }?.let { m["largest_apps"] = appsText(it) }
                f.media?.let { m["media"] = mediaText(it) }
            } else {
                m["largest_apps"] = InfoStrings.appsUnknown()
            }
        }
        return m
    }

    /** The card's lines (and what text-call mode reads out): one per entry, in words. */
    fun lines(results: List<Map<String, Any?>>): String = results.flatMap { r ->
        r.map { (k, v) -> "${TITLES[k]?.let { it().en() } ?: k}: $v" }
    }.joinToString("\n")

    /** [lines] of what was read, in words without a language yet (the card's body; [sep] ". " for the voice). */
    fun linesText(f: PhoneFacts, part: PhonePart, units: WeatherUnits = WeatherUnits.METRIC, sep: String = "\n"): Phrase =
        Phrase.Join(entries(f, part, units).map { (k, v) -> InfoStrings.statusLine("title" to (TITLES[k]?.invoke() ?: Phrase.plain(k)), "value" to v) }, sep)

    private val TITLES = mapOf("storage" to InfoStrings.storage, "battery" to InfoStrings.battery, "memory" to InfoStrings.memory,
        "largest_apps" to InfoStrings.largestApps, "media" to InfoStrings.media)

    /** "57 GB free of 238 GB (76% used)". */
    fun storageLine(s: StorageFacts): String = storageText(s).en()

    private fun storageText(s: StorageFacts): Phrase =
        InfoStrings.storageFree("free" to sizeText(s.free), "total" to sizeText(s.total), "used" to s.usedPercent.toString())

    /** "64%, charging, full in about 50 min, 31°C". */
    fun batteryLine(b: BatteryFacts, units: WeatherUnits = WeatherUnits.METRIC): String = batteryText(b, units).en()

    private fun batteryText(b: BatteryFacts, units: WeatherUnits): Phrase = Phrase.Join(buildList {
        add(InfoStrings.percent("n" to b.percent.toString()))
        add(when { b.full -> InfoStrings.batteryFull(); b.charging -> InfoStrings.batteryCharging(); else -> InfoStrings.batteryNotCharging() })
        b.minutesToFull?.takeIf { b.charging && !b.full && it > 0 }?.let {
            add(if (it < 90) InfoStrings.fullInMinutes("n" to it.toString()) else InfoStrings.fullInHours("n" to (it / 60.0).roundToInt().toString()))
        }
        b.tempC?.let { add(if (units == WeatherUnits.IMPERIAL) InfoStrings.fahrenheit("n" to (it * 9 / 5 + 32).roundToInt().toString()) else InfoStrings.celsius("n" to it.roundToInt().toString())) }
    }, ", ")

    /** "3.1 GB available of 16 GB", ", low", and whether Robot's own model holds about [modelBytes] (2 GB for Fast) of it. */
    fun memoryLine(m: MemoryFacts, robotModel: Boolean?, modelBytes: Long = ROBOT_MODEL_BYTES): String = memoryText(m, robotModel, modelBytes).en()

    /** [memoryLine] in words without a language yet. */
    fun memoryText(m: MemoryFacts, robotModel: Boolean?, modelBytes: Long = ROBOT_MODEL_BYTES): Phrase {
        val free = (if (m.low) InfoStrings.memoryAvailableLow else InfoStrings.memoryAvailable)("available" to sizeText(m.available), "total" to sizeText(m.total))
        return if (robotModel == true) InfoStrings.memoryModel("memory" to free, "size" to sizeText(modelBytes)) else free
    }

    /** "Signal 12 GB (cache 1.2 GB), Organic Maps 4.1 GB": the biggest [TOP_APPS], cache shown from 100 MB. */
    fun appsLine(apps: List<AppSize>): String = appsText(apps).en()

    private fun appsText(apps: List<AppSize>): Phrase = Phrase.Join(top(apps).map { a ->
        val name = a.nameText
        if (a.cacheBytes >= 100_000_000) InfoStrings.appSizeCache("appName" to name, "size" to sizeText(a.bytes), "cache" to sizeText(a.cacheBytes))
        else InfoStrings.appSize("appName" to name, "size" to sizeText(a.bytes))
    }, ", ")

    fun top(apps: List<AppSize>, n: Int = TOP_APPS): List<AppSize> = apps.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(n)

    /** "photos 10 GB, videos 21 GB, audio 1.2 GB". */
    fun mediaLine(m: MediaFacts): String = mediaText(m).en()

    private fun mediaText(m: MediaFacts): Phrase = InfoStrings.mediaSizes("photos" to sizeText(m.images), "videos" to sizeText(m.videos), "audio" to sizeText(m.audio))

    /** The card's summary for [part]. */
    fun summary(part: PhonePart): String = summaryText(part).en()

    /** [summary] in words without a language yet. */
    fun summaryText(part: PhonePart): Phrase = when (part) {
        PhonePart.STORAGE -> InfoStrings.checkStorage()
        PhonePart.BATTERY -> InfoStrings.checkBattery()
        PhonePart.MEMORY -> InfoStrings.checkMemory()
        PhonePart.ALL -> InfoStrings.checkAll()
    }
}
