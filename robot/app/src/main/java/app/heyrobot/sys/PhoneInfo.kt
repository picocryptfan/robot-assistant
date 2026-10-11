package app.heyrobot.sys

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import app.heyrobot.core.AppSize
import app.heyrobot.core.BatteryFacts
import app.heyrobot.core.MediaFacts
import app.heyrobot.core.MemoryFacts
import app.heyrobot.core.PhoneFacts
import app.heyrobot.core.PhonePart
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.StorageFacts

// Main and voice processes. What the phone says about itself for phone_status (0.4.0): StatFs on the data partition,
// BatteryManager and its sticky broadcast, ActivityManager.MemoryInfo, and, only with Usage access (PACKAGE_USAGE_STATS,
// which you turn on in Android's settings), StorageStatsManager for the apps with a launcher icon (the ones Robot may see
// without QUERY_ALL_PACKAGES) and for photos, videos and audio. Nothing is logged; the caller runs it off the main thread.

object PhoneInfo {
    /** Usage access (Settings › Apps › Special app access › Usage access › Robot). */
    fun usageAccess(ctx: Context): Boolean = try {
        val mode = ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) Assistant.granted(ctx, Manifest.permission.PACKAGE_USAGE_STATS) else mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /** What [part] needs, each part on its own (one that can't be read is left out). */
    fun read(ctx: Context, part: PhonePart, robotModelLoaded: Boolean? = null): PhoneFacts {
        val all = part == PhonePart.ALL
        val usage = usageAccess(ctx)
        val storage = all || part == PhonePart.STORAGE
        return PhoneFacts(
            storage = if (storage) safe { storage() } else null,
            battery = if (all || part == PhonePart.BATTERY) safe { battery(ctx) } else null,
            memory = if (all || part == PhonePart.MEMORY) safe { memory(ctx) } else null,
            apps = if (storage && usage) safe { apps(ctx) } else null,
            media = if (storage && usage) safe { media(ctx) } else null,
            usageAccess = usage,
            robotModelLoaded = robotModelLoaded,
        )
    }

    fun storage(): StorageFacts = StatFs(Environment.getDataDirectory().path).let { StorageFacts(it.totalBytes, it.availableBytes) }

    fun battery(ctx: Context): BatteryFacts {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        // The sticky broadcast: no receiver is registered, Android only hands back the last one.
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val charging = full || status == BatteryManager.BATTERY_STATUS_CHARGING || bm.isCharging
        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val left = try { bm.computeChargeTimeRemaining() } catch (_: Exception) { -1L }
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
            ?: sticky?.let { s -> s.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / s.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
        return BatteryFacts(percent, charging, full, temp, if (left > 0) (left / 60_000).toInt() else null)
    }

    fun memory(ctx: Context): MemoryFacts {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return MemoryFacts(mi.totalMem, mi.availMem, mi.lowMemory)
    }

    /** Every app with a launcher icon: the app and its data, and its cache. Needs Usage access. */
    fun apps(ctx: Context): List<AppSize> {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val packages = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.distinct()
        return packages.mapNotNull { pkg ->
            try {
                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                AppSize(label, st.appBytes + st.dataBytes, st.cacheBytes)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Photos, videos and audio in shared storage. Needs Usage access. */
    fun media(ctx: Context): MediaFacts {
        val st = ctx.getSystemService(StorageStatsManager::class.java).queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
        return MediaFacts(st.imageBytes, st.videoBytes, st.audioBytes)
    }

    private inline fun <T> safe(f: () -> T): T? = try { f() } catch (_: Exception) { null }
}

/**
 * The settings pages a card's button (or Assistant setup) opens, by [SettingsPage] key: the intents to try in order (the
 * Settings app may not be visible to Robot's package queries, so the caller just starts each until one opens). Empty for
 * a key that isn't a page.
 */
object SettingsIntents {
    fun of(ctx: Context, key: String): List<Intent> = when (key) {
        SettingsPage.STORAGE -> listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        // Robot's own switch where Android supports it, else the list.
        SettingsPage.USAGE_ACCESS -> listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        else -> emptyList()
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
