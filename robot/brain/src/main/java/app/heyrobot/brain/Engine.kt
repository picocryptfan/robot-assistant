// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainScheduler
import app.heyrobot.core.EngineKind
import app.heyrobot.core.EngineSwitch
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.RLog
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// :BRAIN PROCESS ONLY. 0.4.5: two engines behind one interface, LiteRT-LM (GemmaEngine: Robot's models and your own
// .litertlm) and llama.cpp (LlamaEngine: your own .gguf, run in its own isolated process), and still one model at a
// time: the 0.4.1 slot rule, now shared by both (LlamaEngine holds it until its isolated process is gone), and the other
// engine unloads before one loads (core EngineSwitch). 0.6.0: each engine serves its requests through conversation mode's
// priority queue (core BrainScheduler), REQ_ONE_SHOT included, so the panel, the interpreter, transcript translations and
// summaries take their turns the same way whichever kind of file is loaded (for a GGUF file, the queue is :brain's; the
// isolated process only runs the one request :brain gives it).

/**
 * What BrainService asks of an engine; every call on its work thread. A request is known by its [BrainScheduler.Key]
 * (its client and the client's own number), and waits its turn in the engine's queue.
 */
interface BrainEngine {
    fun interface Sink { fun emit(what: Int, msg: BrainMsg) }

    fun status(): BrainMsg.Status
    fun load(req: BrainMsg.Load): BrainMsg.Loaded
    fun unload(why: String)
    fun generate(key: BrainScheduler.Key, req: BrainMsg.Generate, image: ByteArray?, sink: Sink)
    fun toolResult(key: BrainScheduler.Key, req: BrainMsg.ToolResult, sink: Sink)
    /** REQ_ONE_SHOT (0.5.0): one message in a conversation of its own, at its priority (core OneShotRules). */
    fun oneShot(key: BrainScheduler.Key, req: BrainMsg.OneShot, sink: Sink)
    fun cancel(key: BrainScheduler.Key)
    fun closeConv(convId: String)
    /** BrainService.onDestroy (main thread), before the work thread gets to [shutdown]. */
    fun destroyed()
    /** BrainService was destroyed (work thread): unload, then tell [step] once when the work thread may stop. */
    fun shutdown(step: (EngineTeardown.Step) -> Unit)
}

/** An engine that can hold the process's one slot; [destroyedAt]: when its BrainService was destroyed (elapsed ms; 0: it lives). */
interface SlotHolder {
    val destroyedAt: Long
}

/**
 * The process's one engine slot (0.4.1): the engine whose model is loading, loaded, being let go of or parked, whichever
 * BrainService made it and (0.4.5) whichever kind it is. Its holder also writes filesDir/robot/brain_loaded.txt, so the
 * main app never deletes a model in use.
 */
object EngineSlot {
    private val lock = Any()
    private var holder: SlotHolder? = null
    /** Engines parked in this process, by any engine of any service (a new service may be created before the old one's is closed). */
    val parked = AtomicInteger()

    /** Takes the slot for [who] and marks [req]'s model as the one in memory; false while another engine holds it. */
    fun claim(who: SlotHolder, ctx: Context, req: BrainMsg.Load): Boolean = synchronized(lock) {
        val h = holder
        if (h != null && h !== who) return false
        holder = who
        try { writeAtomic(loadedFile(ctx), BrainLoaded(req.model, req.backend, android.os.Process.myPid()).encode()) } catch (t: Throwable) { RLog.w("brain_loaded_mark", t) }
        true
    }

    /** How long ago the BrainService of the engine holding the slot (another than [who]) was destroyed; -1: it lives, or nobody holds it. */
    fun holderDestroyedMs(who: SlotHolder): Long = synchronized(lock) {
        val at = holder?.takeIf { it !== who }?.destroyedAt ?: 0L
        if (at > 0) SystemClock.elapsedRealtime() - at else -1L
    }

    /** [who]'s model is gone (closed, or never made): the slot is free and the marker goes. */
    fun release(who: SlotHolder, ctx: Context) = synchronized(lock) {
        if (holder === who) {
            holder = null
            try { loadedFile(ctx).delete() } catch (_: Throwable) {}
        }
    }

    private fun loadedFile(ctx: Context) = File(File(ctx.filesDir, "robot"), ModelFiles.LOADED)
}

/**
 * BrainService's engine: LiteRT-LM or llama.cpp by the kind of file asked for. The llama.cpp engine is made only for a
 * GGUF load, and its library is loaded only in the isolated process it starts. Before one engine loads, the other one lets its model go; if it can't yet (a reply it is still
 * leaving), the slot keeps the new load waiting ("still stopping"), exactly as between two services in 0.4.1.
 */
class Engines(private val ctx: Context, private val handler: Handler) : BrainEngine {
    private val gemma = GemmaEngine(ctx, handler)
    private var llama: LlamaEngine? = null
    /** The engine last asked to load (its status, replies and cancels are the service's). */
    private var active: BrainEngine = gemma
    private var activeKind: EngineKind? = null

    private fun of(kind: EngineKind): BrainEngine = when (kind) {
        EngineKind.LITERT_LM -> gemma
        EngineKind.LLAMA_CPP -> llama ?: LlamaEngine(ctx, handler).also { llama = it }
    }

    override fun load(req: BrainMsg.Load): BrainMsg.Loaded {
        val kind = EngineKind.of(req.format)
        if (EngineSwitch.step(activeKind, kind) == EngineSwitch.Step.UNLOAD_OTHER_FIRST) {
            RLog.i("brain_engine_switch", "to" to kind)
            active.unload("engine_switch")
        }
        active = of(kind)
        activeKind = kind
        return active.load(req)
    }

    override fun status(): BrainMsg.Status = active.status()

    override fun unload(why: String) {
        gemma.unload(why)
        llama?.unload(why)
    }

    override fun generate(key: BrainScheduler.Key, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink) = active.generate(key, req, image, sink)
    override fun toolResult(key: BrainScheduler.Key, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) = active.toolResult(key, req, sink)
    override fun oneShot(key: BrainScheduler.Key, req: BrainMsg.OneShot, sink: BrainEngine.Sink) = active.oneShot(key, req, sink)
    override fun cancel(key: BrainScheduler.Key) = active.cancel(key)
    override fun closeConv(convId: String) = active.closeConv(convId)

    override fun destroyed() {
        gemma.destroyed()
        llama?.destroyed()
    }

    /** Both engines let go; the work thread may stop once both say so, and the process ends if either can't. */
    override fun shutdown(step: (EngineTeardown.Step) -> Unit) {
        val engines = listOfNotNull(gemma, llama)
        var left = engines.size
        var told = false
        for (e in engines) e.shutdown { s ->
            left--
            if (!told && (s == EngineTeardown.Step.END_PROCESS || left == 0)) {
                told = true
                step(s)
            }
        }
    }
}
