package app.heyrobot.brain

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.RLog

// :BRAIN PROCESS ONLY (android:process=":brain"). A native crash in LiteRT-LM or the TPU stack kills
// this process alone; the assistant in :voice sees the binder die and reloads on the next question.
// No LiteRT import here: GemmaEngine.kt is the only file that touches it.

class BrainService : Service() {
    private lateinit var work: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private lateinit var engine: GemmaEngine

    override fun onCreate() {
        super.onCreate()
        work = HandlerThread("robot-brain").apply { start() }
        handler = Handler(work.looper) { m -> handle(m); true }
        messenger = Messenger(handler)
        engine = GemmaEngine(this, handler)
        RLog.i("brain_service", "op" to RLog.Code("create"))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** Every client left: free the model's memory (about 2 GB) right away; onDestroy follows. */
    override fun onUnbind(intent: Intent?): Boolean {
        handler.post { engine.unload("unbound") }
        return false
    }

    private fun send(to: Messenger, what: Int, reqId: Int, payload: BrainMsg) {
        try { to.send(BrainWire.message(what, reqId, payload)) } catch (_: RemoteException) {}
    }

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        // Not exported, so only Robot's own processes can bind; checked again here (sendingUid is set by Messenger).
        if (msg.sendingUid != -1 && msg.sendingUid != Process.myUid()) return
        val reqId = msg.arg1
        val payload = BrainWire.decode(msg)
        if (payload == null || msg.what !in BrainProtocol.REQUESTS) {
            send(reply, BrainProtocol.EV_ERROR, reqId, BrainMsg.Error(BrainError.BAD_REQUEST, null))
            return
        }
        val sink = GemmaEngine.Sink { what, m -> send(reply, what, reqId, m) }
        try {
            when (msg.what) {
                BrainProtocol.REQ_STATUS -> sink.emit(BrainProtocol.EV_STATUS, engine.status())
                BrainProtocol.REQ_LOAD -> sink.emit(BrainProtocol.EV_LOADED, engine.load((payload as BrainMsg.Load).path))
                BrainProtocol.REQ_UNLOAD -> { engine.unload("asked"); sink.emit(BrainProtocol.EV_UNLOADED, BrainMsg.Empty) }
                BrainProtocol.REQ_GENERATE -> engine.generate(reqId, payload as BrainMsg.Generate, BrainWire.takeImage(msg), sink)
                BrainProtocol.REQ_TOOL_RESULT -> engine.toolResult(reqId, payload as BrainMsg.ToolResult, sink)
                BrainProtocol.REQ_CANCEL -> engine.cancel((payload as BrainMsg.Cancel).target)
                BrainProtocol.REQ_CLOSE_CONV -> engine.closeConv((payload as BrainMsg.CloseConv).convId)
            }
        } catch (t: Throwable) {
            RLog.w("brain_request", t, "what" to msg.what)
            sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.ENGINE, t.javaClass.simpleName))
        }
    }

    override fun onDestroy() {
        // On the work thread like every other engine call (never two threads in LiteRT at once); quitSafely
        // runs what is already queued first.
        handler.post { try { engine.unload("destroy") } catch (_: Throwable) {} }
        work.quitSafely()
        RLog.i("brain_service", "op" to RLog.Code("destroy"))
        super.onDestroy()
    }
}
