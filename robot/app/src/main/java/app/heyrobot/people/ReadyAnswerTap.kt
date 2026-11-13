// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Langs
import app.heyrobot.sys.AppLanguage
import app.heyrobot.core.i18n.ReadyStrings
import app.heyrobot.core.messages.ChatLinks
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessagePeople
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.ReadyAnswer
import app.heyrobot.core.messages.ReadyAnswers
import app.heyrobot.core.messages.ReadyGate
import app.heyrobot.core.messages.ReadyQuestion
import app.heyrobot.core.messages.ReadyQuestions
import app.heyrobot.core.messages.Confirm
import app.heyrobot.core.messages.Recheck
import app.heyrobot.core.messages.ReplyGuard
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.core.messages.ReplyPlan
import app.heyrobot.core.messages.ReplyTarget
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.CheogramIntents
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.LockShade
import app.heyrobot.sys.PeopleFiles
import app.heyrobot.sys.ReadyAnswerStore
import app.heyrobot.sys.RobotFiles
import app.heyrobot.sys.SettingsStore
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [MAIN] Ready answers (idea 18): a question from someone in People that your calendar or Files answers gets a quiet notice
 * with a draft; Send fires the conversation's own reply action, Edit opens the messaging app with the draft. Off by default
 * (ready-answers.json); unlocked only; nothing is sent without your tap. The question and the draft stay in RAM, by token,
 * until the notice goes (or [TTL]); a log line carries codes only.
 */
object ReadyAnswerTap {
    private const val CHANNEL = "ready_answers" // l10n: not text (a channel id)
    private const val ACTION = "app.heyrobot.ready.ACT" // l10n: not text (an action)
    private const val EXTRA_TOKEN = "t" // l10n: not text (an extra's key)
    private const val EXTRA_OP = "op" // l10n: not text (an extra's key)
    private const val EXTRA_ID = "id" // l10n: not text (an extra's key)
    private const val OP_SEND = "send" // l10n: not text (an op)
    private const val OP_DROP = "drop" // l10n: not text (an op)
    const val TTL = 30 * 60_000L

    /**
     * [target] and [newestSeen]: the conversation as it was asked in, which Send checks again (ReplyGuard.recheck). Low 8: its
     * key and the draft only, never the thread's messages; gone at [TTL] by a timer (a notice's timeout sends no delete intent).
     */
    private data class Held(val id: Int, val target: ReplyTarget, val newestSeen: Long?, val answer: ReadyAnswer, val at: Long)

    private val held = ConcurrentHashMap<String, Held>()
    /** Messages already looked at (thread key and message id), so an updated notice isn't answered twice. */
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var registered = false
    @Volatile private var next = 0
    /** Low D: post, Send and clear() take it, so a lock for someone else can't start between a check and a notice. */
    private val gate = Any()

    /** From MessageTap.posted, on the main thread: the newest message's question is looked at once, off the main thread. */
    fun posted(ctx: Context, t: MessageThread) {
        val msg = t.unread.lastOrNull() ?: return
        val app = ctx.applicationContext
        val mark = "${t.key}|${msg.id}"
        // Low 11: the switch is a file, read on the worker, never on the main thread.
        run(Runnable {
            try {
                if (!ReadyAnswerStore.settings(app).on) return@Runnable
                synchronized(seen) {
                    if (!seen.add(mark)) return@Runnable
                    if (seen.size > 200) seen.remove(seen.first())
                }
                work(app, t, msg.text)
            } catch (_: Throwable) { RLog.w("ready_answer") }
        })
    }

    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ready-answers").apply { isDaemon = true } } } // l10n: not text (a thread name)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** Where [posted]'s look runs, one at a time (a test holds it). */
    internal var run: (Runnable) -> Unit = { worker.execute(it) }

    /** How many drafts' Sends are held in RAM (a test reads it). */
    internal fun heldCount(): Int = held.size

    private fun work(ctx: Context, t: MessageThread, text: String) {
        val people = PeopleFiles.store(ctx).list()
        val person = ReadyGate.person(t, people, SettingsStore.load(ctx).smsGateway)
        val skip = ReadyGate.skip(ReadyAnswerStore.settings(ctx).on, Assistant.locked(ctx), t, person)
        if (skip != null) return RLog.i("ready_answer", "skip" to RLog.Code(skip.name.lowercase()))
        val q = ReadyQuestions.read(text) ?: return
        if (q !is ReadyQuestion.Ask) return RLog.i("ready_answer", "skip" to RLog.Code("refused"))
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val events = if (Calendars.readGranted(ctx)) Calendars.events(ctx, ReadyAnswers.window(now, zone)) else emptyList()
        // As travel mode shows them: a hidden drawer's file never names itself in a draft.
        val files = { RobotFiles.shown(ctx)?.entries.orEmpty() }
        val a = ReadyAnswers.answer(q, events, files, now, zone) ?: return RLog.i("ready_answer", "skip" to RLog.Code("no_match"))
        // Locked while it was read: nothing on the lock screen.
        if (Assistant.locked(ctx)) return
        post(ctx, t, person?.let { MessagePeople.callName(it) } ?: t.name, text, a, now)
        RLog.i("ready_answer", "offered" to RLog.Code(a.source.name.lowercase()))
    }

    private fun channel(ctx: Context, nm: NotificationManager, l: Langs) {
        // Low: shown in the shade, never a sound or a heads-up.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ReadyStrings.channel().render(l), NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setShowBadge(false)
        })
    }

    private fun register(ctx: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = act(c.applicationContext, i)
            }, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    /** At the main process's start (RobotApp): drafts from before have nothing in RAM behind their Send, so they go; Send's receiver is ready. */
    fun start(ctx: Context) {
        try { clear(ctx); register(ctx.applicationContext) } catch (e: Throwable) { RLog.w("ready_answer", e) }
    }

    internal fun post(ctx: Context, t: MessageThread, who: String, question: String, a: ReadyAnswer, now: Long) {
        synchronized(gate) {
            // None during a lock for someone else (LockShade.hide cleared the shade as it started).
            if (app.heyrobot.lock.PhoneLockNow.someoneElse(ctx)) return
            register(ctx)
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            // Low 9: what Robot says to you is in the app's language (its channel's name too); the draft is in the question's.
            val l = AppLanguage.langs(ctx)
            channel(ctx, nm, l)
            val token = UUID.randomUUID().toString()
            // Notification ids are LockShade's, so a lock for someone else clears them; their PendingIntents' codes are id * 10 + 1..3.
            val id = LockShade.READY_ANSWER_BASE + (next++ % LockShade.READY_ANSWER_SLOTS)
            held.values.removeIf { it.id == id || now - it.at > TTL }
            val target = ReplyTarget(t.app, t.conversationId, t.notificationKey, t.reply ?: return, who, t.isGroup)
            held[token] = Held(id, target, t.incoming.maxOfOrNull { it.time }, a, now)
            main.postDelayed({ synchronized(gate) { held.remove(token) } }, TTL)
            nm.notify(id, build(ctx, id, token, target, a, ReadyStrings.asks("who" to who, "question" to question).render(l), a.note, send = true))
        }
    }

    /** Low D: every draft, its notice and what its Send holds, gone under the gate (LockShade.hide; Low C: the process starting). */
    fun clear(ctx: Context) {
        synchronized(gate) {
            held.clear()
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            for (slot in 0 until LockShade.READY_ANSWER_SLOTS) nm.cancel(LockShade.READY_ANSWER_BASE + slot)
        }
    }

    private fun build(ctx: Context, id: Int, token: String, t: ReplyTarget, a: ReadyAnswer, title: String, note: String, send: Boolean): Notification {
        val l = AppLanguage.langs(ctx)
        fun broadcast(op: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ACTION).setPackage(ctx.packageName)
            .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_OP, op).putExtra(EXTRA_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = cover(ctx, l)
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(a.draft)
            .setStyle(Notification.BigTextStyle().bigText(a.draft + "\n" + note))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            // Low 12: never bridged to a paired watch, where the draft and its Send would show past the phone's lock.
            .setLocalOnly(true)
            .setTimeoutAfter(TTL)
            .setDeleteIntent(broadcast(OP_DROP, id * 10 + 2))
        val icon = Icon.createWithResource(ctx, R.drawable.ic_robot)
        if (send) b.addAction(Notification.Action.Builder(icon, ReadyStrings.send().render(l), broadcast(OP_SEND, id * 10 + 1))
            .setAuthenticationRequired(true).build())
        // Edit: straight to the messaging app (an activity, never a trampoline), the draft in its box; nothing sent.
        chatIntent(ctx, t, a.draft)?.let { i ->
            b.addAction(Notification.Action.Builder(icon, ReadyStrings.edit().render(l),
                PendingIntent.getActivity(ctx, id * 10 + 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAuthenticationRequired(true).build())
        }
        return b.build()
    }

    /** What the lock screen may show: no name, no question, no draft; kept on the phone too. */
    private fun cover(ctx: Context, l: Langs): Notification = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot)
        .setContentTitle(ReadyStrings.lockedPublic().render(l)).setLocalOnly(true).build()

    /** As PanelMessages.chatIntent: Cheogram's chat with the draft, else the app's share with it. */
    private fun chatIntent(ctx: Context, t: ReplyTarget, text: String): Intent? {
        if (t.app == MessageApp.CHEOGRAM) {
            val pkg = HandOffApps.cheogram(ctx) ?: return null
            return ChatLinks.cheogram(t.conversationId)?.let { (account, jid) -> CheogramIntents.chat(pkg, jid, account, text) } ?: CheogramIntents.share(pkg, text)
        }
        val pkg = t.app.packages.firstOrNull { HandOffApps.installed(ctx, it) } ?: return null
        return Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * [MAIN] Send or the notice dismissed. Send: unlocked, on, in RAM and in time, the conversation's notice still there; else
     * nothing goes and the notice says why (Low C), except during a lock for someone else, when nothing shows (Low D).
     */
    internal fun act(ctx: Context, i: Intent) {
        synchronized(gate) {
            val token = i.getStringExtra(EXTRA_TOKEN) ?: return
            val h = held.remove(token)
            if (i.getStringExtra(EXTRA_OP) != OP_SEND) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            // The notice's id rides in the intent, so a Send whose draft left RAM (its time, its slot, a restart) still says why.
            val id = h?.id ?: i.getIntExtra(EXTRA_ID, -1).takeIf { it - LockShade.READY_ANSWER_BASE in 0 until LockShade.READY_ANSWER_SLOTS } ?: return
            nm.cancel(id)
            // Low D: checked here too, under the gate: during a lock for someone else nothing goes and nothing shows.
            if (app.heyrobot.lock.PhoneLockNow.someoneElse(ctx)) return RLog.i("ready_answer", "send" to RLog.Code("someone_else"))
            val l = AppLanguage.langs(ctx)
            fun stop(code: String, why: String) {
                RLog.i("ready_answer", "send" to RLog.Code(code))
                channel(ctx, nm, l)
                nm.notify(id, reason(ctx, l, why))
            }
            if (h == null || System.currentTimeMillis() - h.at > TTL) return stop("expired", ReadyStrings.notSentExpired().render(l)) // l10n: not text (a log code)
            if (Assistant.locked(ctx)) return stop("locked", ReadyStrings.notSentLocked().render(l)) // l10n: not text (a log code)
            if (!ReadyAnswerStore.settings(ctx).on) return stop("off", ReadyStrings.notSentOff().render(l)) // l10n: not text (a log code)
            // Read again: sent only to the same one-to-one conversation, with nothing new in it since the question.
            val fresh = MessageTap.fresh(ctx, h.target.notificationKey)
            val check = ReplyGuard.recheck(ReplyPlan.Send(h.target, h.answer.draft, Confirm.TAP, emptySet(), h.newestSeen), fresh?.second)
            val outcome = when (check) {
                is Recheck.Ready -> fresh?.let { (sbn, _) -> MessageTap.fire(ctx, sbn, check.target, h.answer.draft) }
                else -> null
            }
            val code = outcome?.name?.lowercase() ?: when (check) { // l10n: not text (log codes)
                is Recheck.NewMessages -> "new_messages"
                Recheck.Changed -> "changed"
                else -> "gone"
            }
            RLog.i("ready_answer", "send" to RLog.Code(code))
            if (outcome == ReplyOutcome.SENT) return
            // Not sent: say why, with Edit still there (the draft opens in the asker's chat, nothing sent).
            val why = whyNot(check, outcome, h.target.to, h.target.app.titleText, l)
            nm.notify(h.id, build(ctx, h.id, token, h.target, h.answer, ReadyStrings.channel().render(l), why, send = false))
        }
    }

    /** Low C: why a Send that reached the recheck or the app's reply didn't go, as it is. */
    internal fun whyNot(check: Recheck?, outcome: ReplyOutcome?, who: String, app: Any, l: Langs): String = when {
        check is Recheck.NewMessages || outcome == ReplyOutcome.NEW_MESSAGES -> ReadyStrings.notSentNew("who" to who)
        check == Recheck.Changed || outcome == ReplyOutcome.CHANGED -> ReadyStrings.notSentChanged()
        outcome == ReplyOutcome.FAILED -> ReadyStrings.notSentFailed("app" to app)
        outcome == ReplyOutcome.LOCKED || outcome == ReplyOutcome.AUTH_REQUIRED -> ReadyStrings.notSentLocked()
        outcome == ReplyOutcome.OFF || outcome == ReplyOutcome.OPEN_CHAT -> ReadyStrings.notSentReplies()
        else -> ReadyStrings.notSent("app" to app)
    }.render(l)

    /** A notice with only why nothing went: no draft, no Send, no Edit. */
    private fun reason(ctx: Context, l: Langs, why: String): Notification = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.ic_robot)
        .setContentTitle(ReadyStrings.channel().render(l))
        .setContentText(why)
        .setStyle(Notification.BigTextStyle().bigText(why))
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setPublicVersion(cover(ctx, l))
        .setLocalOnly(true)
        .setTimeoutAfter(TTL)
        .build()
}
