package app.heyrobot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.MessageWire
import app.heyrobot.core.messages.MessagesProtocol
import app.heyrobot.core.messages.MessagesStatus
import app.heyrobot.core.messages.ReplyAnswer
import app.heyrobot.core.messages.ReplyRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// VOICE PROCESS. The panel's way to your messages (core MessagesProtocol): it binds the main process's MessagesBridge by
// class name and asks; the listener, the RAM buffer and every PendingIntent stay over there. What comes back is held in
// RAM for the turn that asked, never written. [close] unbinds (the panel hid).

class MessagesLink(ctx: Context) : AutoCloseable {
    private val app = ctx.applicationContext
    private val thread = HandlerThread("robot-messages-link").apply { start() }
    private val replies = Messenger(Handler(thread.looper) { m -> pending[m.arg1]?.complete(m.data?.getString(MessagesProtocol.KEY)); true })
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val ids = AtomicInteger(1)

    @Volatile private var service: Messenger? = null
    // Guarded by this.
    private var connecting: CompletableDeferred<Messenger?>? = null
    private var bound = false
    private var closed = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = binder?.let(::Messenger)
            service = m
            synchronized(this@MessagesLink) { connecting?.complete(m) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            for (d in pending.values) d.complete(null)
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(this@MessagesLink) { connecting?.complete(null) }
        }
    }

    private suspend fun messenger(): Messenger? {
        service?.let { return it }
        val wait = synchronized(this) {
            if (closed) return null
            connecting?.takeIf { !it.isCompleted } ?: CompletableDeferred<Messenger?>().also { d ->
                connecting = d
                if (!bound) {
                    bound = try {
                        app.bindService(Intent().setClassName(app.packageName, MessagesProtocol.SERVICE_CLASS), conn, Context.BIND_AUTO_CREATE)
                    } catch (_: Exception) { false }
                    if (!bound) d.complete(null)
                }
            }
        }
        service?.let { return it }
        return withTimeoutOrNull(MessagesProtocol.CONNECT_MS) { wait.await() }
    }

    /** One request and its answer's JSON; null when the bridge can't be reached or doesn't answer in time. */
    private suspend fun ask(what: Int, json: String?, timeoutMs: Long = MessagesProtocol.ANSWER_MS): String? {
        val to = messenger() ?: return null
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<String?>()
        pending[id] = d
        try {
            try {
                to.send(Message.obtain(null, what, id, 0).apply {
                    replyTo = replies
                    if (json != null) data = Bundle().apply { putString(MessagesProtocol.KEY, json) }
                })
            } catch (_: RemoteException) {
                return null
            }
            return withTimeoutOrNull(timeoutMs) { d.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** The conversations held now (newest first) and the listener's state; unreachable: off and none. */
    suspend fun list(): Pair<MessagesStatus, List<MessageThread>>? = ask(MessagesProtocol.REQ_LIST, null)?.let(MessageWire::list)

    suspend fun markReadByRobot(ids: Collection<String>) {
        if (ids.isNotEmpty()) ask(MessagesProtocol.REQ_MARK_ROBOT, MessageWire.ids(ids))
    }

    /** How the reply went; null when the bridge didn't answer (it may or may not have been sent). */
    suspend fun reply(r: ReplyRequest): ReplyAnswer? = ask(MessagesProtocol.REQ_REPLY, MessageWire.request(r))?.let(MessageWire::answer)

    /** A request is waiting for its answer (a reply on its way): not the moment to let the bridge go. */
    val busy: Boolean get() = pending.isNotEmpty()

    suspend fun markRead(conversation: String): Boolean = MessageWire.ok(ask(MessagesProtocol.REQ_MARK_READ, MessageWire.key(conversation)))

    suspend fun clear(conversation: String): Boolean = MessageWire.ok(ask(MessagesProtocol.REQ_CLEAR, MessageWire.key(conversation)))

    /** "Always for Mom": the main process keeps it in messages.json. */
    suspend fun replyLang(person: String, lang: app.heyrobot.core.messages.Lang?): Boolean =
        MessageWire.ok(ask(MessagesProtocol.REQ_REPLY_LANG, MessageWire.replyLang(person, lang)))

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            if (bound) try { app.unbindService(conn) } catch (_: Exception) {}
            bound = false
            connecting?.complete(null)
        }
        service = null
        for (d in pending.values) d.complete(null)
        thread.quitSafely()
    }
}
