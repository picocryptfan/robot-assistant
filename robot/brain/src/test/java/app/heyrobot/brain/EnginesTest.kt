// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainScheduler
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFormat
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which engine BrainService's request reaches: by the kind of file asked for, the other one letting go first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [StatShadow::class])
internal class EnginesTest : LlamaBench() {
    private val engines by lazy { Engines(app, handler) }
    private val noLiteRt get() = BrainMsg.Load(File(app.filesDir, BrainModel.OWN.file).path, BrainModel.OWN, ModelBackend.CPU, 4096, ModelFormat.LITERT_LM)

    /** The slot is the process's: an engine still holding it would keep every later test's load waiting. */
    @After fun letGo() = onWork { engines.unload("test") }

    @Test fun aGgufFileGoesToLlamaCppAndAnythingElseToLiteRt() {
        own()
        assertEquals(ModelFormat.LITERT_LM, onWork { engines.load(noLiteRt) }.format)
        assertEquals(0, fake.hellos.get())
        val l = onWork { engines.load(req()) }
        assertTrue(l.error, l.ok)
        assertEquals(ModelFormat.GGUF, l.format)
        assertEquals(1, fake.hellos.get())
    }

    @Test fun theStatusIsTheEngineLastAskedToLoad() {
        assertFalse(onWork { engines.status() }.loaded)
        own()
        onWork { engines.load(req()) }
        val s = onWork { engines.status() }
        assertTrue(s.loaded)
        assertEquals(ModelFormat.GGUF, s.format)
    }

    @Test fun switchingKindEndsTheOtherEnginesModelFirst() {
        own()
        onWork { engines.load(req()) }
        assertEquals(LoadFailure.NO_FILE, onWork { engines.load(noLiteRt) }.failure)
        assertEquals(1, fake.exits.get())
        settle()
        assertFalse(onWork { engines.status() }.loaded)
        assertFalse(markerFile.exists())
    }

    @Test fun requestsReachTheActiveEngine() {
        val out = ArrayList<BrainMsg>()
        val client = android.os.Binder()
        own()
        onWork { engines.load(req()) }
        fake.generate = { g -> g.piece("Hi"); LlamaIpc.GEN_END }
        onWork { engines.generate(BrainScheduler.Key(client, 1), BrainMsg.Generate("c", "S", emptyList(), 0, "q", false), null, sink) }
        do out += next().second while (out.last() !is BrainMsg.Done)
        assertEquals(1, fake.runs.size)
        onWork { engines.unload("asked") }
        onWork { engines.generate(BrainScheduler.Key(client, 2), BrainMsg.Generate("c", "S", emptyList(), 0, "q", false), null, sink) }
        val e = next()
        assertEquals(BrainProtocol.EV_ERROR, e.first)
        assertEquals(BrainError.NOT_LOADED, (e.second as BrainMsg.Error).code)
    }

    @Test fun shutdownSaysOnceWhenBothEnginesLetGo() {
        own()
        onWork { engines.load(req()) }
        val steps = ArrayList<EngineTeardown.Step>()
        onWork { engines.destroyed(); engines.shutdown { steps += it } }
        settle()
        assertEquals(listOf(EngineTeardown.Step.QUIT_THREAD), steps)
        assertEquals(1, fake.exits.get())
    }

    @Test fun shutdownWithNoLlamaEngineEverMadeOnlyAsksLiteRt() {
        val steps = ArrayList<EngineTeardown.Step>()
        onWork { engines.shutdown { steps += it } }
        settle()
        assertEquals(listOf(EngineTeardown.Step.QUIT_THREAD), steps)
        assertNull("no slot was taken", markerFile.takeIf { it.exists() })
    }
}
