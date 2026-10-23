// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileMoveCode
import app.heyrobot.core.files.FilingOutcome
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.FilesSettingsStore
import java.security.SecureRandom

// VOICE PROCESS. Robot Files' notifications (files-1.4-plan.md §4.1's table, §5.3; job F6), after a batch FilingWork ran in
// the background: "Robot filed 3 things: 2 receipts and 1 bill." with Undo, and, for kinds at Ask me first, the batch card
// "6 new files: 4 receipts, 1 bill and 1 photo. File them?" with File them. Never on the lock screen (VISIBILITY_SECRET, a
// public version that says "Robot" only), and both actions need the phone unlocked (setAuthenticationRequired). A tap
// opens the Files tab, your Inbox at its top (F7). What an action applies is held here in :voice's memory under a
// random token, for a day; the action reaches :voice as a Cmd (Robot's own broadcast, under its signature permission).
// Nothing of a file is in a log: codes and counts only.

object FilingNotices {
    private const val CHANNEL = "files"
    /** Notification ids, and their PendingIntents' request codes: away from routines', skills' and Ready cards'. */
    private const val FILED_ID = 8_760
    private const val ASK_ID = 8_761
    private const val TTL_MS = 24 * 3_600_000L

    private class Held(val ids: List<FileId>, val until: Long)
    private val held = HashMap<String, Held>()
    private val random = SecureRandom()

    /** After a batch: the filed line with Undo, and the Ask me first card with File them. */
    fun batch(ctx: Context, outcomes: List<FilingOutcome>) {
        // F12: what went into a drawer travel mode hides isn't announced (it stays out of sight).
        val travel = FilesSettingsStore.current(ctx)
        val filed = outcomes.filterIsInstance<FilingOutcome.Filed>().filter { !app.heyrobot.core.files.TravelMode.hidden(it.placement.drawer, travel) }
        val asking = outcomes.filterIsInstance<FilingOutcome.Asked>().filter { it.why == FilingWhy.FILE_THEM }
        if (filed.isEmpty() && asking.isEmpty()) return
        if (!FilesSettingsStore.current(ctx).on || !allowed(ctx)) return
        val l = AppLanguage.langs(ctx)
        if (filed.isNotEmpty()) {
            val title = FilesStrings.filedThings(filed.size).render(l)
            val text = FilesStrings.kinds(filed.map { it.placement.kind }).render(l)
            post(ctx, FILED_ID, title, text, FilesStrings.undo().render(l), Cmd.FILES_UNDO, filed.map { it.entry.id })
        }
        if (asking.isNotEmpty()) {
            val title = FilesStrings.newFiles(asking.size, "kinds" to app.heyrobot.core.i18n.Arg.Sub(FilesStrings.kinds(asking.map { it.entry.kind }))).render(l)
            post(ctx, ASK_ID, title, null, FilesStrings.fileThem().render(l), Cmd.FILES_FILE, asking.map { it.entry.id })
        }
    }

    private fun allowed(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channel(ctx)
        Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) && nm.areNotificationsEnabled() &&
            nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    } catch (t: Throwable) {
        RLog.w("filing_notice_allowed", t)
        false
    }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, FilesStrings.channel().render(AppLanguage.langs(ctx)), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }

    private fun post(ctx: Context, id: Int, title: String, text: String?, action: String, op: String, ids: List<FileId>) {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } // l10n: not text (a random token)
        val now = System.currentTimeMillis()
        synchronized(held) {
            held.entries.removeAll { it.value.until <= now }
            held[token] = Held(ids, now + TTL_MS)
        }
        val act = PendingIntent.getBroadcast(ctx, id, Intent(Cmd.ACTION).setPackage(ctx.packageName).putExtra("op", op).putExtra(Cmd.EXTRA_KEY, token), // l10n: not text (an extra's key)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(ctx, id + 100, Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
            .putExtra(AppPages.EXTRA, AppPages.FILES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val l = AppLanguage.langs(ctx)
        val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(FilesStrings.notePublic().render(l)).build()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TTL_MS)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(public)
            .addAction(Notification.Action.Builder(null, action, act).setAuthenticationRequired(true).build())
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (t: Throwable) { RLog.w("filing_notice_post", t) }
    }

    /**
     * An action tapped (Cmd.FILES_UNDO, Cmd.FILES_FILE with the notification's token), on the filing thread: Undo puts
     * what Robot filed back in Inbox; File them files what waited at Ask me first, as Robot would have. Never while locked.
     */
    fun act(ctx: Context, op: String, token: String?) {
        val h = synchronized(held) { token?.let { held.remove(it) } } ?: return
        if (h.until < System.currentTimeMillis()) return
        val l = AppLanguage.langs(ctx)
        var n = 0
        when (op) {
            Cmd.FILES_UNDO -> {
                for (id in h.ids) if (FilingWork.change(ctx) { idx -> app.heyrobot.core.files.FilingRun.undo(idx, id) }) n++
                VoiceLog.add(VoiceEvent.FILE_MOVE, FileMoveCode.UNDO.id, n)
                cancel(ctx, FILED_ID)
            }
            Cmd.FILES_FILE -> {
                val settings = FilesSettingsStore.current(ctx)
                val words = app.heyrobot.core.files.NameWords(FilesStrings.nameDue().render(l))
                // F8: your rules, read once before the changes (memory's lock is never taken inside the index's).
                val rules = FilingWork.rules(ctx)
                for (id in h.ids) if (FilingWork.change(ctx) { idx -> app.heyrobot.core.files.FilingRun.fileThem(idx, id, settings, rules, words, System.currentTimeMillis()) }) n++
                VoiceLog.add(VoiceEvent.FILE_MOVE, FileMoveCode.FILE_THEM.id, n)
                cancel(ctx, ASK_ID)
            }
        }
    }

    private fun cancel(ctx: Context, id: Int) = try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Throwable) {}
}
