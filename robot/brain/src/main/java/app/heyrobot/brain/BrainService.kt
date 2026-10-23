// SPDX-License-Identifier: GPL-3.0-only
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
import app.heyrobot.core.BrainScheduler
import app.heyrobot.core.BrainWire
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.RLog

// :BRAIN PROCESS ONLY (android:process=":brain"). A native crash in LiteRT-LM or the TPU stack kills
// this process alone; the assistant in :voice sees the binder die and reloads on the next question (or, when the model
// you chose is the one that keeps failing, falls back to Fast: BrainKeeper).
// No LiteRT import here: GemmaEngine.kt is the only file that touches it. llama.cpp (0.4.5, your own GGUF file) never
// runs in this process: LlamaEngine runs it in LlamaService's isolated process (":llama"). Engines picks one of the two
// for each load; one engine holds a model at a time.

class BrainService : Service() {
    private lateinit var work: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private lateinit var engine: Engines

    override fun onCreate() {
        super.onCreate()
        work = HandlerThread("robot-brain").apply { start() }
        handler = Handler(work.looper) { m -> handle(m); true }
        messenger = Messenger(handler)
        engine = Engines(this, handler)
        RLog.i("brain_service", "op" to RLog.Code("create"))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** Every client left: free the model's memory (about 2 GB for Fast, 4 GB for an E4B) right away; onDestroy follows. */
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
        val sink = BrainEngine.Sink { what, m -> send(reply, what, reqId, m) }
        // A request is known by its client (the reply binder) and the client's own number: two clients' numbers never mix.
        val key = BrainScheduler.Key(reply.binder, reqId)
        try {
            when (msg.what) {
                BrainProtocol.REQ_STATUS -> sink.emit(BrainProtocol.EV_STATUS, engine.status())
                BrainProtocol.REQ_LOAD -> sink.emit(BrainProtocol.EV_LOADED, engine.load(payload as BrainMsg.Load))
                BrainProtocol.REQ_UNLOAD -> { engine.unload("asked"); sink.emit(BrainProtocol.EV_UNLOADED, BrainMsg.Empty) }
                BrainProtocol.REQ_GENERATE -> engine.generate(key, payload as BrainMsg.Generate, BrainWire.takeImage(msg), sink)
                BrainProtocol.REQ_TOOL_RESULT -> engine.toolResult(key, payload as BrainMsg.ToolResult, sink)
                BrainProtocol.REQ_ONE_SHOT -> engine.oneShot(key, payload as BrainMsg.OneShot, BrainWire.takeImage(msg), sink)
                BrainProtocol.REQ_CANCEL -> engine.cancel(BrainScheduler.Key(reply.binder, (payload as BrainMsg.Cancel).target))
                BrainProtocol.REQ_CLOSE_CONV -> engine.closeConv((payload as BrainMsg.CloseConv).convId)
            }
        } catch (t: Throwable) {
            RLog.w("brain_request", t, "what" to msg.what)
            sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.ENGINE, t.javaClass.simpleName))
        }
    }

    override fun onDestroy() {
        // Known at once, even while the work thread is still busy: a new service waits for this one's engine to go.
        engine.destroyed()
        // On the work thread like every other engine call (never two threads in LiteRT at once). The thread stays
        // while an engine is parked: LiteRT posts the callback that closes it there (quitting at once, as 0.1.2 did,
        // dropped that callback and kept the model's memory and the TPU until :brain died). If LiteRT never lets go, :brain ends.
        handler.post {
            try {
                engine.shutdown { step ->
                    if (step == EngineTeardown.Step.END_PROCESS) Process.killProcess(Process.myPid()) else work.quitSafely()
                }
            } catch (t: Throwable) {
                RLog.w("brain_teardown", t)
                work.quitSafely()
            }
        }
        RLog.i("brain_service", "op" to RLog.Code("destroy"))
        super.onDestroy()
    }
}
