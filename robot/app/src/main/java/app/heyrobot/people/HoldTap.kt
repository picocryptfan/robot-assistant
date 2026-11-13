// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.heyrobot.R
import app.heyrobot.core.Person
import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.messages.HoldAsk
import app.heyrobot.core.messages.HoldBook
import app.heyrobot.core.messages.HoldCandidate
import app.heyrobot.core.messages.HoldCode
import app.heyrobot.core.messages.HeldCount
import app.heyrobot.core.messages.HoldLine
import app.heyrobot.core.messages.HoldPlan
import app.heyrobot.core.messages.HoldResult
import app.heyrobot.core.messages.HoldResume
import app.heyrobot.core.messages.HoldResumeRule
import app.heyrobot.core.messages.HoldRule
import app.heyrobot.core.messages.HoldSaved
import app.heyrobot.core.messages.HoldTime
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.HoldStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.sys.Paths
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.lang.ref.WeakReference
import java.time.ZonedDateTime

// MAIN PROCESS. 1.3, notifications held until a time (docs/plans/notify-hold-plan.md): the part of the notification
// listener (CheogramListener, the same component and grant) that snoozes, while a hold runs, the notifications of the
// apps you picked in Settings › Messages (core HoldRule says which never are). Each is a snoozeNotification for the time
// left, so Android brings it back on time even if Robot is stopped. What it keeps is counts by app name (core HoldBook),
// with a copy in hold_now.json (core HoldSaved) so a restart or an update carries the hold on; its end is an
// allow-while-idle alarm (HoldEnd), exact with USE_EXACT_ALARM, so it comes on time while the phone sleeps. At the end,
// one line as Robot's own quiet notification, never a ring. Never a title, a text or a log line.

object HoldTap {
    val book = HoldBook()

    @Volatile private var listener: WeakReference<NotificationListenerService>? = null
    private val known = HoldPeople(PEOPLE_MS)

    private const val CHANNEL = "hold"
    /** The end line's notification id: away from Files', routines', skills' and pairings' (8,790 was a pairing's RAISED). */
    private const val ID = 8_771
    /** The hold-over line's total, so it can be posted again with counts only (L1). */
    private const val EXTRA_TOTAL = "app.heyrobot.hold_total"
    private const val PEOPLE_MS = 60_000L

    /** On with at least one app: the listener stays bound for it. */
    fun on(ctx: Context): Boolean = HoldStore.current(ctx).let { it.on && it.apps.isNotEmpty() }

    fun connected(l: NotificationListenerService) {
        listener = WeakReference(l)
        resume(l.applicationContext)
    }

    fun disconnected(l: NotificationListenerService) {
        if (listener?.get() === l) listener = null
    }

    /** Notification access taken back: the hold and its counts go (what is snoozed comes back at its time). */
    @Synchronized fun drop(ctx: Context) {
        book.clear()
        file(ctx).delete()
        cancel(ctx)
    }

    private fun file(ctx: Context) = File(Paths.dir(ctx), HoldSaved.FILE)

    private fun save(ctx: Context) {
        try {
            book.saved()?.let { writeAtomic(file(ctx), it.write()) } ?: file(ctx).delete()
        } catch (_: Throwable) {}
    }

    private fun alarm(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, HoldEnd::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The end's alarm: exact when Robot may (USE_EXACT_ALARM), else Android's inexact allow-while-idle; either wakes the phone, neither rings. */
    private fun arm(ctx: Context, end: Long) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, alarm(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, alarm(ctx))
        } catch (_: Throwable) {}
    }

    private fun cancel(ctx: Context) {
        try { ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx)) } catch (_: Throwable) {}
    }

    /** [MAIN] After a restart or an update (the listener back, or the alarm): a saved hold carries on, or ends now with its line. */
    @Synchronized fun resume(ctx: Context) {
        if (book.endsAt() > 0L) return
        val s = HoldSaved.parse(try { readOrNull(file(ctx)) } catch (_: Throwable) { null })
        when (HoldResumeRule.at(s, System.currentTimeMillis())) {
            HoldResume.NONE -> {}
            HoldResume.CARRY_ON -> { book.restore(s!!); arm(ctx, s.end) }
            HoldResume.END -> { book.restore(s!!); over(ctx, s.end) }
        }
    }

    /** [MAIN] The end's alarm went off: the hold (read back if Robot was restarted) ends, or, if a newer one runs, its own alarm is set. */
    @Synchronized fun alarmed(ctx: Context) {
        resume(ctx)
        val end = book.endsAt()
        if (end <= 0L) return
        if (System.currentTimeMillis() >= end - 2_000L) over(ctx, end) else arm(ctx, end)
    }

    /** [MAIN] A hold asked for (MessagesBridge): planned against your settings, started, and what is in the shade held now. */
    fun start(ctx: Context, ask: HoldAsk, someoneElse: Boolean): HoldResult {
        val l = listener?.get()
        val now = ZonedDateTime.now()
        val (r, pkgs) = HoldPlan.plan(ask, HoldStore.current(ctx), l != null && NotificationAccess.granted(ctx), someoneElse, now) { MissedTap.label(ctx, it) }
        if (r.code != HoldCode.HELD || l == null) return r
        val end = HoldTime.end(ask.until, now).toInstant().toEpochMilli()
        synchronized(this) {
            book.start(pkgs, end)
            save(ctx.applicationContext)
            arm(ctx.applicationContext, end)
        }
        val active = try { l.activeNotifications.orEmpty() } catch (_: Throwable) { emptyArray() }
        val ranking = try { l.currentRanking } catch (_: Throwable) { null }
        for (sbn in active) posted(l, sbn, ranking)
        return r
    }

    /** [MAIN] onNotificationPosted: true when [sbn] was held (snoozed until the hold's end), so nothing else reads it. */
    fun posted(l: NotificationListenerService, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): Boolean {
        val pkg = sbn.packageName ?: return false
        val now = System.currentTimeMillis()
        val holding = book.holding(now)
        if (pkg !in holding) return false
        val ctx = l.applicationContext
        val n = sbn.notification ?: return false
        val c = try {
            val r = NotificationListenerService.Ranking().takeIf { ranking?.getRanking(sbn.key, it) == true }
            HoldCandidate(pkg, n.category,
                ongoing = sbn.isOngoing || n.flags and (Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR) != 0,
                conversation = r?.isConversation == true || n.extras.containsKey(Notification.EXTRA_MESSAGES) || n.shortcutId != null,
                media = n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION),
                fullScreen = n.fullScreenIntent != null,
                ownProfile = sbn.user == android.os.Process.myUserHandle(),
                title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                alerting = r?.let { it.importance >= NotificationManager.IMPORTANCE_DEFAULT } ?: true)
        } catch (_: Throwable) {
            return false
        }
        if (!HoldRule.mayHold(c, HoldStore.current(ctx), holding, ctx.packageName) { knows(ctx, it) }) return false
        // A group's summary goes with its group but isn't counted twice.
        val ms = if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) book.endsAt() - now else book.held(pkg, MissedTap.label(ctx, pkg), now)
        if (ms <= 0) return false
        save(ctx)
        return try {
            l.snoozeNotification(sbn.key, ms)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Someone in People by name or alias: never held. People is read at most once a minute; locked, the last unlocked read (HoldPeople). */
    private fun knows(ctx: Context, title: String): Boolean = known.knows(title, System.currentTimeMillis()) {
        PeopleFiles.store(ctx).let { s -> s.list().takeIf { it.isNotEmpty() || !s.notNow() } }
    }

    /** fix-seal-14 M2: People changed in this process (Delete all: empty): what [knows] matches on, at once. */
    fun peopleChanged(people: List<Person>) = known.remember(people, System.currentTimeMillis())

    /** The hold that ended at [end] (unless a newer one replaced it): its one line, shown; said once where OutputGuard lets it (HoldVoice). */
    @Synchronized private fun over(ctx: Context, end: Long) {
        if (book.endsAt() != end) return
        val counts = book.take()
        file(ctx).delete()
        cancel(ctx)
        val countsOnly = app.heyrobot.lock.PhoneLockNow.someoneElse(ctx)
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !nm.areNotificationsEnabled()) return
            val l = AppLanguage.langs(ctx)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL, MessageStrings.holdChannel().render(l), NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
            val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(HoldLine.overPublic().render(l)).build()
            val line = HoldLine.over(counts, countsOnly).render(l)
            nm.notify(ID, Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(HoldLine.overPublic().render(l))
                .setContentText(line).setStyle(Notification.BigTextStyle().bigText(line)).setPublicVersion(public)
                .addExtras(android.os.Bundle().apply { putInt(EXTRA_TOTAL, counts.filter { it.n > 0 }.sumOf { it.n }) })
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
            say(ctx, line, countsOnly)
        } catch (_: Throwable) {}
    }

    /**
     * review-develop-14 L1: someone else's Phone lock started. The hold-over line still in the shade is posted again with
     * counts only, as it would be posted then (a line from before its total was kept: the generic title only).
     */
    fun countsOnly(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val n = nm.activeNotifications.firstOrNull { it.id == ID }?.notification ?: return
            val l = AppLanguage.langs(ctx)
            val line = n.extras.getInt(EXTRA_TOTAL, -1).takeIf { it >= 0 }?.let { HoldLine.over(listOf(HeldCount("", it)), countsOnly = true).render(l) }
                ?: HoldLine.overPublic().render(l)
            nm.notify(ID, Notification.Builder.recoverBuilder(ctx, n).setContentText(line).setStyle(Notification.BigTextStyle().bigText(line)).build())
        } catch (t: Throwable) {
            app.heyrobot.core.RLog.w("hold_counts", t)
        }
    }

    /** Robot's voice is the :voice process's: the line goes there by class name, and RoutineVoice.holdOver decides (HoldVoice). */
    private fun say(ctx: Context, line: String, someoneElse: Boolean) {
        try {
            ctx.sendBroadcast(android.content.Intent(app.heyrobot.sys.RoutineIntents.ACTION_HOLD_OVER).setClassName(ctx.packageName, app.heyrobot.sys.RoutineIntents.RECEIVER)
                .putExtra(app.heyrobot.sys.RoutineIntents.EXTRA_LINE, line).putExtra(app.heyrobot.sys.RoutineIntents.EXTRA_SOMEONE_ELSE, someoneElse))
        } catch (_: Throwable) {}
    }
}

/** The end of a hold, from AlarmManager (HoldTap.arm), so it comes on time while the phone sleeps. Main process, not exported, no filter. */
class HoldEnd : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) = HoldTap.alarmed(ctx.applicationContext)
}
