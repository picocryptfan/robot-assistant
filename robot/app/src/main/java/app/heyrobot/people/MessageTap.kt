// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.messages.ActionSnapshot
import app.heyrobot.core.messages.HiddenCode
import app.heyrobot.core.messages.ListenerEvent
import app.heyrobot.core.messages.MarkKind
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageBuffer
import app.heyrobot.core.messages.MessageSigners
import app.heyrobot.core.messages.MessageSnapshot
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MsgAppCode
import app.heyrobot.core.messages.NotificationParsing
import app.heyrobot.core.messages.NotificationSnapshot
import app.heyrobot.core.messages.Parsed
import app.heyrobot.core.messages.PinVerdict
import app.heyrobot.core.messages.PersonSnapshot
import app.heyrobot.core.messages.RemoteInputSnapshot
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.core.messages.ReplyTarget
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.Paths

// MAIN PROCESS. 0.7, "Read my messages": the part of the existing notification listener (CheogramListener,
// same component, so the grant you gave for name learning holds) that reads messages. Off until you turn it on in
// Settings › Messages, and then only the apps you turned on there: every other package is dropped at the first line, with
// nothing read. An allowed app's MessagingStyle notification is copied field by field into core's plain snapshot
// (NotificationParsing decides everything else) and kept in a RAM buffer only, until its notification goes (or "Keep
// messages for" says): never a file, a log line or a crash report (codes and counts only). The PendingIntents stay
// here: a reply or a mark fires only from this process, after core ReplySend re-read the notification (MessagesBridge).
// A package is read only while it is signed as when you turned its app on (core MessageSigners, MessageAppSigners): a
// look-alike app under the same package name, or a build installed later, is ignored. What it holds goes at once when
// "Read my messages" is turned off or notification access is taken back.

object MessageTap {
    /** The conversations held now (RAM only; process death or a reboot empties it, the listener's connection rebuilds it). */
    val buffer = MessageBuffer()

    /** The listener while Android has it bound. */
    @Volatile private var listener: CheogramListener? = null

    val connected: Boolean get() = listener != null

    @Volatile private var listening = false

    private fun file(ctx: Context): MessagesFile = MessagesStore.current(ctx)

    /** "Read my messages" is on with at least one app. */
    fun on(ctx: Context): Boolean = file(ctx).settings.let { it.on && it.allowlist.apps.isNotEmpty() }

    /** The settings the buffer was last filled under. */
    @Volatile private var under: app.heyrobot.core.messages.MessageSettings? = null

    /** Settings › Messages changed here (the main process): what isn't allowed any more goes, and the shade is read again. */
    private fun changed(ctx: Context, f: MessagesFile) {
        // A person's "without a card" or "always in French" changes nothing here.
        if (f.settings == under) return
        under = f.settings
        buffer.keep = f.settings.keep
        if (!f.settings.on) {
            drop(ctx)
            listener?.checkIdle()
            return
        }
        // Simplest and safe: start over from what the shade shows now, under the new allowlist.
        buffer.clear()
        listener?.let { rebuild(ctx, it) }
    }

    /** Settings › Messages changes reach [changed] from the first time the tap is used in this process. */
    private fun listen(ctx: Context) {
        if (listening) return
        listening = true
        val app = ctx.applicationContext
        MessagesStore.listen { f -> app.mainExecutor.execute { changed(app, f) } }
    }

    /** [MAIN] The listener connected: read the shade (getActiveNotifications) into the buffer. */
    fun connected(l: CheogramListener) {
        listener = l
        val ctx = l.applicationContext
        listen(ctx)
        buffer.keep = file(ctx).settings.keep
        under = file(ctx).settings
        // An app on before the pins (messages.json version 1) is pinned at its first notification: only its builds
        // installed now may be (one installed later waits for you to turn the app on again).
        if (file(ctx).firstUse.isNotEmpty()) {
            val src = MessageAppSigners.source(ctx)
            MessagesStore.update(ctx) { it.firstUseInstalled { pkg -> src.now(pkg) != null } }
        }
        if (on(ctx)) rebuild(ctx, l) else buffer.clear()
        log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.CONNECTED.id, buffer.size)
    }

    fun disconnected(l: CheogramListener) {
        if (listener === l) listener = null
        val ctx = l.applicationContext
        log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.DISCONNECTED.id, buffer.size)
        // Notification access taken back: nothing Robot read stays in memory.
        if (!app.heyrobot.sys.NotificationAccess.granted(ctx)) drop(ctx)
    }

    /** Every message held goes now ("Read my messages" off, notification access taken back). */
    fun drop(ctx: Context) {
        val n = buffer.size
        buffer.clear()
        trust.clear()
        if (n > 0) log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.DROPPED.id, n)
    }

    private fun rebuild(ctx: Context, l: CheogramListener) {
        val allow = file(ctx).settings.allowlist
        val active = try { l.activeNotifications.orEmpty() } catch (_: Throwable) { emptyArray() }
        val threads = active.filter { allow.allows(it.packageName) != null }.mapNotNull { parse(ctx, it) }
        buffer.connected(threads, System.currentTimeMillis())
    }

    /**
     * [MAIN] onNotificationPosted, on the main thread: an allowed app's conversation goes into RAM; anything else, nothing.
     * 0.8: with "Announce new messages" on, a new message from someone else tells the voice process which conversation
     * (its key in RAM) and whether Do Not Disturb lets it through, never its sender or words; :voice decides whether it is
     * announced (private headphones, or the car in car mode) and asks the bridge for it as for any read.
     */
    fun posted(ctx: Context, sbn: StatusBarNotification) {
        listen(ctx)
        val s = file(ctx).settings
        if (!s.on || s.allowlist.allows(sbn.packageName) == null) return
        val t = parse(ctx, sbn) ?: return
        val before = buffer.thread(t.key)
        buffer.posted(t)
        t.hidden?.let { log(ctx, VoiceEvent.MSG_HIDDEN, MsgAppCode.of(t.app).id, HiddenCode.of(it).id) }
        if (s.announce && app.heyrobot.core.messages.MessageArrival.isNew(before, t)) {
            Cmd.send(ctx, Cmd.MESSAGE_NEW, android.os.Bundle().apply {
                putString(Cmd.EXTRA_KEY, t.key)
                putBoolean(Cmd.EXTRA_DND, dndAllows(sbn))
            })
        }
    }

    /**
     * Do Not Disturb lets [sbn] through now (Ranking.matchesInterruptionFilter). Fails closed (core MessageDnd): when the
     * listener can't say, the message isn't announced (it stays in its app, as Do Not Disturb would keep it).
     */
    private fun dndAllows(sbn: StatusBarNotification): Boolean = try {
        val r = android.service.notification.NotificationListenerService.Ranking()
        val found = listener?.currentRanking?.getRanking(sbn.key, r)
        app.heyrobot.core.messages.MessageDnd.allows(found, found == true && r.matchesInterruptionFilter())
    } catch (_: Throwable) {
        false
    }

    /** [MAIN] onNotificationRemoved: its conversation goes (or is kept for "Keep messages for"). */
    fun removed(sbn: StatusBarNotification) {
        buffer.removed(sbn.key ?: return, System.currentTimeMillis())
    }

    /** The last verdict per package, for the package as installed then and the pin kept then (MessageSigners). */
    private val trust = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

    /**
     * [pkg] is signed as when you turned its app on, or, for an app on before the pins, is pinned now (its first
     * notification). Worked out again only when the package is updated or reinstalled, or its pin changes.
     */
    private fun trusted(ctx: Context, pkg: String): Boolean {
        val f = file(ctx)
        val src = MessageAppSigners.source(ctx)
        val key = "${src.version(pkg)}:${f.pins[pkg]?.hashCode()}:${pkg in f.firstUse}"
        trust[pkg]?.let { (k, ok) -> if (k == key) return ok }
        val appCode = MsgAppCode.idOf(listOfNotNull(MessageApp.of(pkg)))
        val ok = when (MessageAppSigners.verdict(ctx, pkg, f)) {
            PinVerdict.TRUST -> true
            PinVerdict.PIN_NOW -> {
                val pin = MessageSigners.pinOf(src.now(pkg))
                if (pin != null) {
                    MessagesStore.update(ctx) { it.pinnedAtFirstUse(pkg, pin) }
                    log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.PINNED_FIRST_USE.id, appCode)
                }
                pin != null
            }
            PinVerdict.CHANGED -> { log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.SIGNER_CHANGED.id, appCode); false }
            PinVerdict.NOT_PINNED, null -> { log(ctx, VoiceEvent.MSG_LISTENER, ListenerEvent.NOT_PINNED.id, appCode); false }
        }
        // A pin kept just now changes the key: the next notification works it out once more, then this holds.
        trust[pkg] = key to ok
        return ok
    }

    private fun parse(ctx: Context, sbn: StatusBarNotification): MessageThread? = try {
        val allow = file(ctx).settings.allowlist
        if (allow.allows(sbn.packageName) == null || !trusted(ctx, sbn.packageName)) null
        else (NotificationParsing.parse(snapshot(sbn), allow) as? Parsed.Thread)?.thread
    } catch (_: Throwable) {
        // Never the notification in a log: only that one couldn't be read.
        RLog.w("message_parse")
        null
    }

    /**
     * The notification [key] as it is now, read again from the shade (getActiveNotifications([key])), with its
     * conversation; null when it has gone or isn't an allowed conversation any more.
     */
    fun fresh(ctx: Context, key: String): Pair<StatusBarNotification, MessageThread>? {
        val l = listener ?: return null
        val sbn = try { l.getActiveNotifications(arrayOf(key))?.firstOrNull() } catch (_: Throwable) { null } ?: return null
        return parse(ctx, sbn)?.let { sbn to it }
    }

    /**
     * Fires [target]'s reply action of the notification [sbn] (just read again) with [text], as Android Auto and watches
     * do: the results under the RemoteInput's key, marked free-form input. The PendingIntent carries the allowances
     * Android gave the posting app's notification.
     */
    fun fire(ctx: Context, sbn: StatusBarNotification, target: ReplyTarget, text: String): ReplyOutcome {
        val action = sbn.notification.actions?.getOrNull(target.action.index) ?: return ReplyOutcome.GONE
        val inputs = action.remoteInputs?.takeIf { it.isNotEmpty() } ?: return ReplyOutcome.GONE
        val key = target.action.resultKey ?: return ReplyOutcome.GONE
        if (inputs.none { it.resultKey == key }) return ReplyOutcome.CHANGED
        return try {
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { putCharSequence(key, text) })
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(ctx, 0, intent)
            ReplyOutcome.SENT
        } catch (_: PendingIntent.CanceledException) {
            ReplyOutcome.CANCELED_PI
        } catch (t: Throwable) {
            RLog.w("message_reply")
            ReplyOutcome.FAILED
        }
    }

    /** Fires the conversation's mark-as-read action: the sender may see you read it (Cheogram's marker, Signal's receipts). */
    fun markRead(ctx: Context, conversation: String): Boolean {
        val held = buffer.thread(conversation) ?: return false
        val (sbn, t) = fresh(ctx, held.notificationKey) ?: return false
        val ref = t.markRead ?: return false
        if (!app.heyrobot.core.messages.ReplyGuard.markable(ref, app.heyrobot.sys.Assistant.locked(ctx))) return false
        val action = sbn.notification.actions?.getOrNull(ref.index) ?: return false
        return try {
            action.actionIntent.send()
            log(ctx, VoiceEvent.MSG_MARK, MarkKind.READ.id, MsgAppCode.of(t.app).id)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Dismisses the conversation's notification; the chat stays unread in the app. */
    fun clear(ctx: Context, conversation: String): Boolean {
        val held = buffer.thread(conversation) ?: return false
        val l = listener ?: return false
        return try {
            l.cancelNotification(held.notificationKey)
            log(ctx, VoiceEvent.MSG_MARK, MarkKind.CLEAR.id, MsgAppCode.of(held.app).id)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ the copy, field by field

    /** Notification.MessagingStyle's sender key in a message bundle (API 28+), and AndroidX's "shows a screen" flag. */
    private const val SHOWS_UI = "android.support.action.showsUserInterface"

    /** [sbn] as core's plain snapshot: what Android gives, nothing decided here. */
    fun snapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val x = n.extras ?: Bundle()
        return NotificationSnapshot(
            packageName = sbn.packageName,
            key = sbn.key,
            postTime = sbn.postTime,
            category = n.category,
            template = x.getString(Notification.EXTRA_TEMPLATE),
            shortcutId = n.shortcutId,
            channelId = n.channelId,
            title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            conversationTitle = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = if (x.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else null,
            user = person(x.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)),
            messages = messages(x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)),
            historicMessages = messages(x.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES, Parcelable::class.java)),
            actions = n.actions.orEmpty().map { action(it) },
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            ongoing = sbn.isOngoing,
        )
    }

    private fun person(p: android.app.Person?): PersonSnapshot? = p?.let { PersonSnapshot(it.name?.toString(), it.key, it.uri) }

    /** Each bundle on its own, so one Android can't read stays a gap (null) and the rest are kept. */
    private fun messages(arr: Array<Parcelable>?): List<MessageSnapshot?> = arr.orEmpty().map { b ->
        try {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(arrayOf(b)).firstOrNull()?.let { m ->
                MessageSnapshot(m.text?.toString(), m.timestamp, person(m.senderPerson), m.dataMimeType)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun action(a: Notification.Action?): ActionSnapshot? = a?.let {
        ActionSnapshot(
            title = it.title?.toString(),
            semanticAction = it.semanticAction,
            remoteInputs = it.remoteInputs.orEmpty().map { r ->
                RemoteInputSnapshot(r.resultKey, r.label?.toString(), r.allowFreeFormInput, r.choices.orEmpty().map { c -> c.toString() })
            },
            showsUserInterface = it.extras?.getBoolean(SHOWS_UI, true) ?: true,
            contextual = it.isContextual,
            authenticationRequired = it.isAuthenticationRequired,
        )
    }

    /** The main process's own small log (the voice log screen shows both). Codes and counts only. */
    private fun log(ctx: Context, e: VoiceEvent, a: Int, b: Int = 0) {
        try {
            if (!Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
            VoiceLog.add(e, a, b)
        } catch (_: Throwable) {}
    }
}
