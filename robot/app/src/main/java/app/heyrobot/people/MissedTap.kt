// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.heyrobot.core.messages.MissedNotice
import app.heyrobot.core.messages.MissedNotices
import app.heyrobot.core.messages.MissedSettings
import app.heyrobot.core.messages.SenderKind
import app.heyrobot.sys.MissedStore
import java.util.concurrent.ConcurrentHashMap

// MAIN PROCESS. 1.3, "What did I miss?": the part of the notification listener (CheogramListener, the same component
// and grant) that notes, for the apps you picked in Settings › Messages, each alerting notification's app, kind of
// sender and title (core MissedNotices: RAM only, cut as the brief cuts). Never its text, never a file or a log line.
// Off, or an app not picked: dropped at the first line. Switch off or access taken back: everything held goes.

object MissedTap {
    val held = MissedNotices()

    @Volatile private var listening = false
    private val labels = ConcurrentHashMap<String, String>()
    private val targets = ConcurrentHashMap<String, Int>()

    private fun listen() {
        if (listening) return
        listening = true
        MissedStore.listen { s -> held.settings(s) }
    }

    /** On with at least one app: the listener stays bound for it. */
    fun on(ctx: Context): Boolean = MissedStore.current(ctx).let { it.on && it.apps.isNotEmpty() }

    /** [MAIN] The listener connected: the shade as it is now, from picked apps. */
    fun connected(l: NotificationListenerService) {
        listen()
        val ctx = l.applicationContext
        val s = MissedStore.current(ctx)
        if (!s.on) return held.clear()
        val active = try { l.activeNotifications.orEmpty() } catch (_: Throwable) { emptyArray() }
        val ranking = try { l.currentRanking } catch (_: Throwable) { null }
        // Dismissed while unbound: no longer counted or named.
        held.keep(active.mapNotNull { it.key }.toSet())
        for (sbn in active) posted(ctx, sbn, ranking, s)
    }

    /** [MAIN] onNotificationPosted: a picked app's alerting notification is noted; anything else, nothing. */
    fun posted(ctx: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?, s: MissedSettings = MissedStore.current(ctx)) {
        listen()
        val pkg = sbn.packageName ?: return
        if (!s.picked(pkg) || pkg == ctx.packageName) return
        // A work profile's copy of a picked app is that profile's, never counted or named here.
        if (sbn.user != android.os.Process.myUserHandle()) return
        held.heard(pkg, s)
        // Heard once, heard after a restart too (missed.json): a quiet chat app is "Nothing new", not "isn't a messaging app".
        if (pkg !in s.heard) MissedStore.update(ctx) { if (it.picked(pkg) && pkg !in it.heard) it.copy(heard = it.heard + pkg) else it }
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || !alerting(sbn, ranking)) return held.removed(sbn.key)
        held.posted(sbn.key, MissedNotice(pkg, label(ctx, pkg), kind(n, target(ctx, pkg)), title(n), sbn.postTime), s)
    }

    fun removed(sbn: StatusBarNotification) {
        sbn.key?.let(held::removed)
    }

    /** Notification access taken back: nothing held stays. */
    fun drop() {
        held.clear()
        labels.clear()
        targets.clear()
    }

    /** Silent and minimized notifications aren't "missed"; with no ranking to tell, the notification counts. */
    private fun alerting(sbn: StatusBarNotification, map: NotificationListenerService.RankingMap?): Boolean = try {
        val r = NotificationListenerService.Ranking()
        if (map?.getRanking(sbn.key, r) == true) r.importance >= NotificationManager.IMPORTANCE_DEFAULT else true
    } catch (_: Throwable) {
        true
    }

    private fun kind(n: Notification, target: Int): SenderKind = SenderKind.of(
        messages = n.extras.containsKey(Notification.EXTRA_MESSAGES),
        group = n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
        title = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null, targetSdk = target)

    /** [pkg]'s target SDK; unknown: current, so a title alone never makes a group. */
    private fun target(ctx: Context, pkg: String): Int = targets.getOrPut(pkg) {
        try {
            ctx.packageManager.getApplicationInfo(pkg, 0).targetSdkVersion
        } catch (_: Exception) {
            Int.MAX_VALUE
        }
    }

    /** The conversation's title or the notification's title; never its text. */
    private fun title(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()

    /** [pkg]'s name; "" when it isn't installed (not kept, so a reinstalled app gets its name back). */
    fun label(ctx: Context, pkg: String): String = labels[pkg] ?: (try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            ""
        }).also { if (it.isNotEmpty()) labels[pkg] = it }

    /** [pkg] is installed now: a picked app since uninstalled is left out of "nothing has come from". */
    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }
}
