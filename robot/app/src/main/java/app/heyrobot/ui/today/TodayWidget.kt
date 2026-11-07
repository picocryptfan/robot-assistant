// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.widget.RemoteViews
import app.heyrobot.R
import app.heyrobot.core.BriefComposer
import app.heyrobot.core.GlanceLine
import app.heyrobot.core.GlanceNow
import app.heyrobot.core.GlanceView
import app.heyrobot.core.RLog
import app.heyrobot.core.Today
import app.heyrobot.core.TodayGlance
import app.heyrobot.core.i18n.Lang
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.sys.TodaySource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// MAIN PROCESS. Today outside the app (home-design.md §6.2, H9): the home-screen widget "Robot today" and the lock screen
// summary. Framework RemoteViews and a notice only. Core's TodayGlance decides what each says; this file reads the
// phone's state for it, draws and posts.
//
// - TodayWidgetProvider (exported, as a widget provider must be, the APPWIDGET_UPDATE filter alone, and only the system's
//   broadcasts: BIND_APPWIDGET, today-fix-c 8) reads nothing from its intent: the widgets the system lists are redrawn and
//   the summary posted again.
// - TodayRefresh (not exported, no filter): the inexact alarm (no exact-alarm permission) set for when the day next changes
//   (an event starts or ends, a timer ends, midnight); it only redraws.
// - Redraws: at the process's start, screen-on, screen-off and unlock, the date, time or zone changing, a Today setting
//   changed, a phone lock's start or end (LockEngine, through TodayRefresh). No periodic update: nothing here wakes the
//   phone (today-fix-c 6). With Today off, screen events do nothing once it is drawn off (7). Each draw reads its state.
// - The summary (off until you turn it on in Settings › Display): a silent notice, counts and times only on the lock
//   screen and in the shade alike, so its public version is itself; gone with Today off or under a phone lock.
//
// Nothing here logs a title or a count of anything yours: codes only.

/** The widget's provider: whatever it is sent, everything is drawn again (it reads nothing from its intent). */
class TodayWidgetProvider : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        TodayGlances.changed(ctx.applicationContext, goAsync())
    }
}

/** The inexact alarm for the day's next change (not exported: only Robot's own PendingIntent reaches it). */
class TodayRefresh : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        TodayGlances.changed(ctx.applicationContext, goAsync())
    }
}

object TodayGlances {
    const val CHANNEL = "today_lock" // l10n: not text (a channel id)
    const val NOTICE_ID = 4209

    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-today-glance").apply { isDaemon = true } } }
    private val pending = AtomicBoolean(false)
    private val held = java.util.concurrent.ConcurrentLinkedQueue<BroadcastReceiver.PendingResult>()
    @Volatile private var started = false

    private fun provider(ctx: Context) = ComponentName(ctx, TodayWidgetProvider::class.java)

    fun ids(ctx: Context): IntArray = try {
        AppWidgetManager.getInstance(ctx)?.getAppWidgetIds(provider(ctx)) ?: IntArray(0)
    } catch (t: Exception) {
        RLog.w("today_widget_ids", t)
        IntArray(0)
    }

    /** [MAIN] At the process's start: drawn now, and again at screen-on, screen-off and unlock. */
    fun start(app: Context) {
        if (ListsFiles.process() != "main" || started) return
        started = true
        try {
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { screen(app) }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_DATE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
        } catch (t: Throwable) { RLog.w("today_glance_hooks", t) }
        changed(app)
    }

    private const val NOT_RESTING = Long.MIN_VALUE
    /** The home settings' file as last drawn with Today off ([stamp]), or [NOT_RESTING]. */
    @Volatile private var restingAt = NOT_RESTING

    /** The home settings' file as it is: a change to it is a new stamp (time and size; a stat, not a read). */
    private fun stamp(ctx: Context): Long = try {
        HomeSettingsStore.file(ctx).let { it.lastModified() xor (it.length() shl 40) }
    } catch (_: Exception) { 0L }

    /**
     * today-fix-c 7: a screen, unlock or date event. With Today off and everything drawn and cancelled for these settings,
     * nothing is done (false); else the widgets and the summary are drawn again.
     */
    fun screen(app: Context): Boolean {
        val r = restingAt
        if (r != NOT_RESTING && r == stamp(app)) return false
        changed(app)
        return true
    }

    /** Robot may post the summary: the permission granted and its notifications on (today-fix-c 3). */
    fun canNotify(ctx: Context): Boolean = try {
        Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) && ctx.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    } catch (_: Exception) { false }

    /** Something changed: the widgets and the summary drawn again soon (one redraw for a burst); [done] finished after it. */
    fun changed(ctx: Context, done: BroadcastReceiver.PendingResult? = null) {
        val app = ctx.applicationContext
        if (done != null) held.add(done)
        if (!pending.compareAndSet(false, true)) return
        worker.execute {
            pending.set(false)
            val waiting = generateSequence { held.poll() }.toList()
            try { redrawNow(app) } catch (t: Throwable) { RLog.w("today_glance", t) } finally { waiting.forEach { it.finish() } }
        }
    }

    /** The phone's state for [TodayGlance], for a widget on a lock-screen host or not. */
    fun now(ctx: Context, keyguardHost: Boolean = false): GlanceNow {
        val home = HomeSettingsStore.current(ctx)
        val interactive = try { ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: false } catch (_: Exception) { false }
        val phoneLock = try { PhoneLockNow.on(ctx) } catch (_: Exception) { true }
        val someoneElse = try { PhoneLockNow.someoneElse(ctx) } catch (_: Exception) { true }
        val locked = try { ListsFiles.locked(ctx) } catch (_: Exception) { true }
        return GlanceNow(home.today, home.lockSummary, interactive, locked, phoneLock, someoneElse, keyguardHost)
    }

    private fun lang(ctx: Context): Lang = AppLanguage.langs(ctx).ui

    /** Every widget and the summary as things are now (this process's worker; tests call it directly). */
    fun redrawNow(ctx: Context) {
        val stamp = stamp(ctx)
        val lang = lang(ctx)
        var read: app.heyrobot.core.DayInput? = null
        val day = { read ?: TodaySource.input(ctx).also { read = it } }
        val m = AppWidgetManager.getInstance(ctx)
        val ids = ids(ctx)
        for (id in ids) {
            try {
                val host = try {
                    val cat = m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                    cat and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD != 0
                } catch (_: Exception) {
                    true
                }
                val height = try { m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) } catch (_: Exception) { 0 }
                m.updateAppWidget(id, views(ctx, TodayGlance.widget(now(ctx, host), lang, day), lang, height))
            } catch (t: Exception) {
                RLog.w("today_widget_draw", t)
            }
        }
        val notice = TodayGlance.notice(now(ctx), lang, day)
        post(ctx, notice?.let { BriefComposer.shown(it, lang) })
        // The next change, only while something shows the day.
        val next = read?.let { TodayGlance.nextChange(Today.picture(it)) }
        schedule(ctx, if (ids.isNotEmpty() || notice != null) next else null)
        restingAt = if (HomeSettingsStore.current(ctx).today) NOT_RESTING else stamp
        RLog.i("today_glance_drawn", "n" to ids.size, "notice" to (if (notice != null) 1 else 0))
    }

    /** [heightDp]: the widget's height as its host says (0: not known); the day's lines fit it at your font size (10). */
    fun views(ctx: Context, v: GlanceView, lang: Lang, heightDp: Int = 0): RemoteViews {
        val r = RemoteViews(ctx.packageName, R.layout.today_widget)
        val text = when (v) {
            is GlanceView.Day -> BriefComposer.shown(v.text, lang)
            is GlanceView.Summary -> BriefComposer.shown(v.text, lang)
            is GlanceView.Line -> ctx.getString(when (v.line) {
                GlanceLine.OFF -> R.string.today_widget_off
                GlanceLine.ROBOT_LOCKED -> R.string.today_widget_robot_locked
                GlanceLine.UNLOCK -> R.string.today_widget_unlock
            })
        }
        r.setTextViewText(R.id.today_widget_line, text)
        r.setInt(R.id.today_widget_line, "setMaxLines", TodayGlance.lines(heightDp, ctx.resources.configuration.fontScale)) // l10n: not text (a method name)
        r.setOnClickPendingIntent(R.id.today_widget_root, open(ctx))
        return r
    }

    /** A tap opens Robot (MainActivity, covered by Phone lock). */
    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), // l10n: not text (a class name)
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The summary posted ([text]: counts and times only), or taken away (null). */
    private fun post(ctx: Context, text: String?) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (text == null) {
            try { nm.cancel(NOTICE_ID) } catch (t: Throwable) { RLog.w("today_notice_cancel", t) }
            return
        }
        try {
            if (!canNotify(ctx)) return
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.today_lock_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
            val title = ctx.getString(R.string.today_widget_label)
            val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(title).setContentText(text).build()
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open(ctx))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPublicVersion(public)
                .build()
            nm.notify(NOTICE_ID, n)
        } catch (t: Throwable) {
            RLog.w("today_notice_post", t)
        }
    }

    private fun refresh(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, TodayRefresh::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** An inexact, non-waking alarm at [at] (ms), or none. */
    private fun schedule(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        try {
            if (at == null) am.cancel(refresh(ctx)) else am.set(AlarmManager.RTC, at, refresh(ctx))
        } catch (t: Throwable) {
            RLog.w("today_glance_alarm", t)
        }
    }
}
