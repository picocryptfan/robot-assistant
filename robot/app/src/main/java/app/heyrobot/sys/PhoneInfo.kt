package app.heyrobot.sys

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.BatteryManager
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import app.heyrobot.core.AppSize
import app.heyrobot.core.BatteryFacts
import app.heyrobot.core.FreeUp
import app.heyrobot.core.MediaFile
import app.heyrobot.core.MediaFacts
import app.heyrobot.core.MemoryFacts
import app.heyrobot.core.PhoneFacts
import app.heyrobot.core.PhoneStatus
import app.heyrobot.core.PhonePart
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.StorageFacts
import java.io.File

// Main and voice processes. What the phone says about itself for phone_status (0.4.0): StatFs on the data partition,
// BatteryManager and its sticky broadcast, ActivityManager.MemoryInfo, and, only with Usage access (PACKAGE_USAGE_STATS,
// which you turn on in Android's settings), StorageStatsManager for the apps with a launcher icon (the ones Robot may see
// without QUERY_ALL_PACKAGES) and for photos, videos and audio. For free_memory and free_storage: the apps used lately
// (Usage access), the largest photos and videos (the media permission, MediaStore) and Robot's own cache. Nothing is
// logged, no file name included; the caller runs it off the main thread.

object PhoneInfo {
    /** Usage access (Settings › Apps › Special app access › Usage access › Robot). */
    fun usageAccess(ctx: Context): Boolean = try {
        val mode = ctx.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) Assistant.granted(ctx, Manifest.permission.PACKAGE_USAGE_STATS) else mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }

    /** What [part] needs, each part on its own (one that can't be read is left out). */
    fun read(ctx: Context, part: PhonePart, robotModelLoaded: Boolean? = null, robotModelBytes: Long = PhoneStatus.ROBOT_MODEL_BYTES): PhoneFacts {
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
            robotModelBytes = robotModelBytes,
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
                // Robot itself: most of it is its models, which clearing its storage would delete.
                AppSize(if (pkg == ctx.packageName) FreeUp.ROBOT_LABEL else label(ctx, pkg), st.appBytes + st.dataBytes, st.cacheBytes, pkg)
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

    fun label(ctx: Context, pkg: String): String = ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }

    private fun launcherPackages(ctx: Context): Set<String> =
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }.toSet()

    /**
     * The apps (with a launcher icon) used in the last [withinMs], most recent first, that free_memory may name as ones
     * you could stop: never the app you are in, Robot, the keyboard, the home screen, messaging apps or clock apps (stopping
     * one can cancel its alarms). Labels only. Needs Usage access; empty without it.
     */
    fun recentApps(ctx: Context, withinMs: Long = 2 * 3_600_000L): List<String> {
        if (!usageAccess(ctx)) return emptyList()
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val skip = buildSet {
            add(ctx.packageName)
            addAll(FreeUp.MESSAGING)
            foreground(usm, now)?.let(::add)
            safe { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) }?.let { ComponentName.unflattenFromString(it)?.packageName }?.let(::add)
            safe { ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY) }
                ?.activityInfo?.packageName?.let(::add)
            safe { Telephony.Sms.getDefaultSmsPackage(ctx) }?.let(::add)
            safe { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }?.let(::add)
            safe { ctx.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_ALL) }.orEmpty().forEach { add(it.activityInfo.packageName) }
            // A VPN, and anything with a foreground service running (music, navigation, a call, a download).
            safe { ctx.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), PackageManager.MATCH_ALL) }.orEmpty().forEach { add(it.serviceInfo.packageName) }
            addAll(withForegroundService(usm, now))
        }
        val launcher = launcherPackages(ctx)
        val used = safe { usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - withinMs, now) }.orEmpty()
            .filter { it.lastTimeUsed >= now - withinMs && it.packageName in launcher && it.packageName !in skip }
            .groupBy { it.packageName }.mapValues { (_, v) -> v.maxOf { it.lastTimeUsed } }
        return used.entries.sortedByDescending { it.value }.take(FreeUp.RECENT_APPS).mapNotNull { safe { label(ctx, it.key) } }
    }

    /** Apps whose foreground service started in the last few hours and hasn't stopped (playing, navigating, recording). */
    private fun withForegroundService(usm: UsageStatsManager, now: Long): Set<String> = safe {
        val events = usm.queryEvents(now - 6 * 3_600_000L, now)
        val e = UsageEvents.Event()
        val running = HashMap<String, Int>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.FOREGROUND_SERVICE_START -> running[pkg] = (running[pkg] ?: 0) + 1
                UsageEvents.Event.FOREGROUND_SERVICE_STOP -> running[pkg] = ((running[pkg] ?: 0) - 1).coerceAtLeast(0)
            }
        }
        running.filterValues { it > 0 }.keys
    }.orEmpty()

    /** The app on screen under the panel: the last one that came to the front, other than Robot. */
    private fun foreground(usm: UsageStatsManager, now: Long): String? = safe {
        val events = usm.queryEvents(now - 30 * 60_000L, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != null) last = e.packageName
        }
        last
    }

    /** Photos and videos Robot may read: all of them, or only those you picked ("Select photos"). */
    fun mediaGranted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) ||
        Assistant.granted(ctx, Manifest.permission.READ_MEDIA_VIDEO) || mediaPartial(ctx)

    /** Only the photos and videos you picked (Android 14's partial access). */
    fun mediaPartial(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
        !Assistant.granted(ctx, Manifest.permission.READ_MEDIA_IMAGES) && !Assistant.granted(ctx, Manifest.permission.READ_MEDIA_VIDEO)

    /** The largest photos and videos Robot may see, biggest first. Trashed and pending ones aren't listed (MediaStore's default). */
    fun largeMedia(ctx: Context, n: Int = FreeUp.LARGE_FILES): List<MediaFile> {
        val out = mutableListOf<MediaFile>()
        for ((uri, video) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)) {
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED)
            safe {
                ctx.contentResolver.query(uri, cols, null, null, "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
                    var taken = 0
                    while (taken < n && c.moveToNext()) {
                        val date = (if (c.isNull(3)) null else c.getLong(3)) ?: (if (c.isNull(4)) null else c.getLong(4) * 1000)
                        out += MediaFile(ContentUris.withAppendedId(uri, c.getLong(0)).toString(), c.getString(1).orEmpty(), c.getLong(2), date, video)
                        taken++
                    }
                }
            }
        }
        return out.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(n)
    }

    /** Robot's own cache (the cache folders; never its models, notes, history or settings), in bytes. */
    fun robotCache(ctx: Context): Long = cacheDirs(ctx).sumOf { d -> d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Empties Robot's cache folders; the bytes it removed. */
    fun clearRobotCache(ctx: Context): Long {
        var freed = 0L
        for (d in cacheDirs(ctx)) d.listFiles()?.forEach { f ->
            val size = f.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (f.deleteRecursively()) freed += size
        }
        return freed
    }

    private fun cacheDirs(ctx: Context): List<File> = listOfNotNull(ctx.cacheDir, ctx.externalCacheDir).filter { it.isDirectory }
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
        // Android's own "Free up space" (whichever app handles it on this phone), else the storage settings.
        SettingsPage.FREE_UP -> listOf(Intent(StorageManager.ACTION_MANAGE_STORAGE), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        // 0.7: Robot's own Settings › Memory, in the main app (AppPages).
        SettingsPage.MEMORY -> listOf(HandOffApps.robotPage(ctx, AppPages.MEMORY))
        else -> emptyList()
    }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    /** An app's info page (Force stop, Storage and cache). */
    fun appInfo(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
