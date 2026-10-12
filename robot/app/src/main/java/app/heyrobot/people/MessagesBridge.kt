package app.heyrobot.people

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessageWire
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.MessagesProtocol
import app.heyrobot.core.messages.MessagesStatus
import app.heyrobot.core.messages.MsgAppCode
import app.heyrobot.core.messages.ReplyAnswer
import app.heyrobot.core.messages.ReplyCheck
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.core.messages.ReplyRouteCode
import app.heyrobot.core.messages.ReplySend
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.sys.Paths

// MAIN PROCESS. 0.7: how the voice process (the panel) asks for your messages and sends a reply (design §B4, core
// MessagesProtocol): a bound service, not exported, guarded by Robot's signature permission, answering only Robot's own
// uid. It hands over what MessageTap holds in RAM, as the main process's own settings and lock allow (nothing while
// locked unless "Messages on the lock screen" says so; names only for "Who messaged"); and for a reply it reads the
// notification again and lets core ReplySend decide, so the recipient is always that notification's conversation and
// "Open the chat; I press Send" never sends. Message text crosses Binder in RAM; nothing here writes or logs it.

class MessagesBridge : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("robot-messages-bridge").apply { start() }
        messenger = Messenger(Handler(thread.looper) { m -> handle(m); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(m: Message) {
        val to = m.replyTo ?: return
        // Only Robot's own processes (the signature permission guards the binding; this guards each request too).
        if (m.sendingUid != Process.myUid()) return
        val req = m.data?.getString(MessagesProtocol.KEY)
        val out = try {
            when (m.what) {
                MessagesProtocol.REQ_LIST -> list()
                MessagesProtocol.REQ_MARK_ROBOT -> { MessageTap.buffer.markReadByRobot(MessageWire.ids(req)); MessageWire.ok(true) }
                MessagesProtocol.REQ_REPLY -> MessageWire.answer(reply(req))
                MessagesProtocol.REQ_MARK_READ -> MessageWire.ok(allowedNow() && MessageWire.keyOf(req)?.let { MessageTap.markRead(applicationContext, it) } == true)
                MessagesProtocol.REQ_CLEAR -> MessageWire.ok(allowedNow() && MessageWire.keyOf(req)?.let { MessageTap.clear(applicationContext, it) } == true)
                MessagesProtocol.REQ_REPLY_LANG -> MessageWire.ok(MessageWire.replyLang(req)?.let { (id, lang) -> replyLang(id, lang); true } == true)
                else -> return
            }
        } catch (t: Throwable) {
            RLog.w("messages_bridge")
            if (m.what == MessagesProtocol.REQ_REPLY) MessageWire.answer(ReplyAnswer(ReplyOutcome.FAILED)) else MessageWire.ok(false)
        }
        try {
            to.send(Message.obtain(null, MessagesProtocol.ANSWER, m.arg1, 0).apply { data = Bundle().apply { putString(MessagesProtocol.KEY, out) } })
        } catch (_: RemoteException) {}
    }

    private fun locked(): Boolean = Assistant.locked(applicationContext)

    private fun replyLang(person: String, lang: app.heyrobot.core.messages.Lang?) = setReplyLang(applicationContext, person, lang)

    /** On, and either unlocked or "Read and reply" on the lock screen. */
    private fun allowedNow(): Boolean {
        val s = MessagesStore.current(applicationContext).settings
        return s.on && (!locked() || s.locked == MessagesLocked.READ_AND_REPLY)
    }

    /** The conversations, as the settings and the lock allow, with the listener's state. */
    private fun list(): String {
        val ctx = applicationContext
        val s = MessagesStore.current(ctx).settings
        val locked = locked()
        val access = NotificationAccess.granted(ctx)
        // Access taken back while the listener was still bound: nothing it read stays.
        if (!access) MessageTap.drop(ctx)
        val status = MessagesStatus(on = s.on, access = access, connected = MessageTap.connected, locked = locked,
            apps = s.allowlist.apps.size)
        val all = if (s.on) MessageTap.buffer.threads(System.currentTimeMillis()) else emptyList()
        val shown: List<MessageThread> = when {
            !locked || s.locked == MessagesLocked.READ_AND_REPLY -> all
            s.locked == MessagesLocked.WHO_MESSAGED -> all.map { t -> t.copy(messages = t.messages.map { it.copy(text = "") }) }
            else -> emptyList()
        }
        return MessageWire.list(status, shown)
    }

    private fun reply(json: String?): ReplyAnswer {
        val ctx = applicationContext
        val req = MessageWire.request(json) ?: return ReplyAnswer(ReplyOutcome.FAILED)
        val s = MessagesStore.current(ctx).settings
        val locked = locked()
        val held = MessageTap.buffer.thread(req.key)
        val fresh = held?.let { MessageTap.fresh(ctx, it.notificationKey) }
        val answer = when (val c = ReplySend.check(held, fresh?.second, req, s, locked)) {
            is ReplyCheck.Stop -> c.answer
            is ReplyCheck.Fire -> ReplyAnswer(MessageTap.fire(ctx, fresh!!.first, c.target, c.text))
        }
        held?.let { h -> answer.outcome.end?.let { log(VoiceEvent.MSG_REPLY, MsgAppCode.of(h.app).id, ReplyRouteCode.REMOTE_INPUT.id, it.id) } }
        return answer
    }

    companion object {
        /**
         * "Always for Mom" on a reply card: the main process stays messages.json's only writer, through the same locked
         * change as Settings (MessagesStore.update), so neither loses the other's. A People id and a language, nothing else.
         */
        fun setReplyLang(ctx: android.content.Context, person: String, lang: app.heyrobot.core.messages.Lang?) =
            MessagesStore.update(ctx) { f -> f.copy(replyLang = if (lang == null) f.replyLang - person else f.replyLang + (person to lang)) }
    }

    private fun log(e: VoiceEvent, a: Int, b: Int, c: Int) {
        try {
            if (!Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(applicationContext))
            VoiceLog.add(e, a, b, c)
        } catch (_: Throwable) {}
    }
}
