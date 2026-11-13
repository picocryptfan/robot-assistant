// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.heyrobot.R
import app.heyrobot.core.OwnTimer
import app.heyrobot.core.OwnTimers
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.TimerStrings
import app.heyrobot.lock.PhoneLockNow
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * named-timers: the timers' channel. Its sound is the phone's alarm sound on the alarm stream (usage alarm), and each ring is
 * category alarm, so Do Not Disturb lets it through exactly when it lets alarms through, and the alarm volume sets it.
 */
object OwnTimerChannel {
    /** A missed timer's notice: no sound. */
    const val MISSED = "robot_own_timers_missed"

    fun make(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(MISSED) == null) nm.createNotificationChannel(NotificationChannel(MISSED,
            TimerStrings.noteMissed().render(AppLanguage.langs(ctx)), NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        if (nm.getNotificationChannel(OwnTimerIntents.CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(OwnTimerIntents.CHANNEL, TimerStrings.channel().render(AppLanguage.langs(ctx)),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
}

/**
 * named-timers: the ring. The alarm (OwnTimerAlarms) reaches the routines' receiver, which calls [receive]: each timer whose
 * end has come rings as an insistent notification (its sound repeats until Stop, a tap, a swipe, a spoken "stop", or 10
 * minutes); one that ended more than an hour before Robot could ring (the phone was off) is told as missed, silently.
 * Then the alarm is set for the next one. Boot, an update and a time change do the same, so a timer that ended meanwhile
 * still rings.
 */
object OwnTimerRing {
    /** Robot's own timer intents and the system's boot and clock broadcasts; true when [intent] was only the timers'. */
    fun receive(ctx: Context, intent: Intent): Boolean = try {
        when (intent.action) {
            OwnTimerIntents.ACTION_RING -> { onFire(ctx); true }
            OwnTimerIntents.ACTION_STOP -> { stop(ctx, setOf(intent.getIntExtra(OwnTimerIntents.EXTRA_ID, -1))); true }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> { onFire(ctx); false }
            else -> false
        }
    } catch (t: Throwable) {
        RLog.w("own_timer_receive", t)
        intent.action == OwnTimerIntents.ACTION_RING || intent.action == OwnTimerIntents.ACTION_STOP
    }

    fun onFire(ctx: Context, now: Long = System.currentTimeMillis()) {
        // Under the store's lock: only what this change marked as rung is posted, so a cancel lands wholly before (nothing
        // due) or after (its notification taken down).
        OwnTimerStore.locked {
            var due = emptyList<OwnTimer>()
            var missed = emptySet<Int>()
            OwnTimerStore.update(ctx, now) { l ->
                due = OwnTimers.due(l, now)
                missed = due.filterNot { OwnTimers.ringsAt(it, now) }.map { it.id }.toSet()
                OwnTimers.rang(l, due.map { it.id }.toSet(), now, missed)
            }
            if (due.isNotEmpty()) {
                due.forEach { post(ctx, it, now, it.id in missed) }
                RLog.i("own_timer_ring", "due" to due.size, "missed" to missed.size)
            }
        }
        OwnTimerAlarms.arm(ctx, now)
    }

    /** Stops the ring of [ids]. */
    fun stop(ctx: Context, ids: Set<Int>) = OwnTimerStore.locked {
        OwnTimerStore.update(ctx) { OwnTimers.stopped(it, ids) }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ids.forEach { nm.cancel(OwnTimerIntents.NOTE_BASE + it) }
    }

    /** Cancels [ids], ringing or not, and sets the alarm for what is left. */
    fun cancel(ctx: Context, ids: Set<Int>) {
        OwnTimerStore.locked {
            OwnTimerStore.update(ctx) { OwnTimers.cancelled(it, ids) }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            ids.forEach { nm.cancel(OwnTimerIntents.NOTE_BASE + it) }
        }
        OwnTimerAlarms.arm(ctx)
    }

    // The name stays off the lock screen whatever Android's lock-screen setting ("show all content" shows a private
    // notification whole): a ring posted while the phone is locked leaves it out, and while a named ring is up, the screen
    // going off and the unlock post it again (no new sound) without the name or with it.
    private var screenHook: BroadcastReceiver? = null

    private fun locked(ctx: Context): Boolean = PhoneLockNow.on(ctx) ||
        try { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { true }

    private fun watchScreen(ctx: Context): Unit = synchronized(this) {
        if (screenHook != null) return@synchronized
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { onScreen(app, i.action == Intent.ACTION_SCREEN_OFF) }
        }
        try {
            ProtectedBroadcasts.register(app, r, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_OFF))
            screenHook = r
        } catch (t: Throwable) {
            RLog.w("own_timer_screen_hook", t)
        }
    }

    /** The screen went off ([off]) or the phone was unlocked: each named ring still up is posted again, its name as the lock allows. */
    internal fun onScreen(ctx: Context, off: Boolean, now: Long = System.currentTimeMillis()) {
        try {
            OwnTimerStore.locked {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                val up = nm.activeNotifications.map { it.id }.toSet()
                val named = OwnTimers.ringing(OwnTimerStore.load(ctx, now), now).filter { it.name.isNotEmpty() && OwnTimerIntents.NOTE_BASE + it.id in up }
                named.forEach { post(ctx, it, it.rang, false, again = now, hide = off) }
                if (named.isEmpty()) synchronized(this) {
                    screenHook?.let { r -> try { ctx.applicationContext.unregisterReceiver(r) } catch (_: Throwable) {} }
                    screenHook = null
                }
            }
        } catch (t: Throwable) {
            RLog.w("own_timer_screen", t)
        }
    }

    private fun clock(ms: Long) = Arg.Clock(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().truncatedTo(ChronoUnit.MINUTES))

    /** [again]: when a ring already up is posted again (no new sound, the 10 minutes kept); [hide]: leave the name out. */
    private fun post(ctx: Context, t: OwnTimer, now: Long, missed: Boolean, again: Long = 0, hide: Boolean = false) {
        try {
            OwnTimerChannel.make(ctx)
            val l = AppLanguage.langs(ctx)
            // Behind Robot's phone lock or Android's, the name stays off the notification.
            val name = t.name.takeUnless { hide || locked(ctx) }.orEmpty()
            val title = when {
                missed -> TimerStrings.noteMissed()
                name.isEmpty() -> TimerStrings.noteTitle()
                else -> TimerStrings.noteTitleNamed("name" to Arg.Plain(name))
            }.render(l)
            val text = when {
                missed -> TimerStrings.noteMissedText("time" to clock(t.ends))
                now - t.ends > 60_000 -> TimerStrings.noteLate("time" to clock(t.ends))
                else -> TimerStrings.noteText("duration" to Arg.Seconds(t.seconds))
            }.render(l)
            val stop = PendingIntent.getBroadcast(ctx, OwnTimerIntents.NOTE_BASE + t.id,
                Intent(OwnTimerIntents.ACTION_STOP).setClassName(ctx.packageName, RoutineIntents.RECEIVER).putExtra(OwnTimerIntents.EXTRA_ID, t.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val channel = if (missed) OwnTimerChannel.MISSED else OwnTimerIntents.CHANNEL
            val public = Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_robot)
                .setContentTitle((if (missed) TimerStrings.noteMissed() else TimerStrings.noteTitle()).render(l)).build()
            val b = Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(t.ends)
                .setShowWhen(true)
                .setContentIntent(stop)
                .setDeleteIntent(stop)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
            if (again > 0) b.setOnlyAlertOnce(true)
            if (!missed) b.setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
                .setTimeoutAfter(if (again > 0) (OwnTimers.RING_MS - (again - t.rang)).coerceAtLeast(1) else OwnTimers.RING_MS)
                .addAction(Notification.Action.Builder(null, TimerStrings.noteStop().render(l), stop).build())
            val n = b.build()
            if (!missed) n.flags = n.flags or Notification.FLAG_INSISTENT
            ctx.getSystemService(NotificationManager::class.java).notify(OwnTimerIntents.NOTE_BASE + t.id, n)
            if (!missed && t.name.isNotEmpty()) watchScreen(ctx)
        } catch (t: Throwable) {
            RLog.w("own_timer_post", t)
        }
    }
}
