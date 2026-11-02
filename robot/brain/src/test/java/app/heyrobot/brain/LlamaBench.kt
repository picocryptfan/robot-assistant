// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.app.Application
import android.content.ComponentName
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.system.StructStat
import app.heyrobot.core.ArrayByteSource
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.FakeGguf
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.OwnModel
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBinder

/** Os.fstat and Os.stat of the open file and the path, which LlamaEngine compares: the same file unless a test swaps the path's. */
@Implements(Os::class)
class StatShadow {
    companion object {
        @Volatile @JvmField var pathIno = 1L
        private fun stat(ino: Long, size: Long) = StructStat(1, ino, 0, 1, 0, 0, 0, size, 0, 0, 0, 4096, 1)
        @JvmStatic @Implementation fun fstat(fd: FileDescriptor): StructStat = stat(1, java.io.FileInputStream(fd).channel.size())
        @JvmStatic @Implementation fun stat(path: String): StructStat = stat(pathIno, File(path).length())
    }
}

/** A LlamaEngine on its own work thread, as BrainService runs it, with [fake] as its isolated process and your own GGUF file in Robot's storage. */
internal abstract class LlamaBench {
    protected val app: Application = RuntimeEnvironment.getApplication()
    protected lateinit var work: HandlerThread
    protected lateinit var handler: Handler
    protected lateinit var engine: LlamaEngine
    protected val fake = FakeLlama()
    protected val isolatedUid = 99_123
    protected val events = LinkedBlockingQueue<Pair<Int, BrainMsg>>()
    protected val sink = BrainEngine.Sink { what, m -> events.put(what to m) }

    @Before fun bench() {
        ShadowBinder.setCallingUid(isolatedUid)
        StatShadow.pathIno = 1
        work = HandlerThread("test-brain").apply { start() }
        handler = Handler(work.looper)
        engine = LlamaEngine(app, handler)
        val libs = File(app.filesDir, "libs").apply { mkdirs() }
        File(libs, "librobot_llama.so").writeText("not a library")
        app.applicationInfo.nativeLibraryDir = libs.path
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app.packageName, LlamaIpc.SERVICE_CLASS), fake)
    }

    @After fun closeBench() {
        onWork { engine.unload("test") }
        work.quitSafely()
        work.join(5_000)
        EngineSlot.parked.set(0)
        ShadowBinder.reset()
    }

    /** Runs [block] on the work thread, as every engine call runs, and gives its answer. */
    protected fun <T> onWork(block: () -> T): T {
        val done = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post {
            out = runCatching(block)
            done.countDown()
        }
        assertTrue("the work thread didn't come back", done.await(30, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    /** Waits until the work thread has done everything posted so far. */
    protected fun settle() = onWork { }

    /** The next event to the client. */
    protected fun next(): Pair<Int, BrainMsg> = events.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("no event came")

    protected val ggufFile: File get() = File(app.filesDir, ModelFiles.OWN_GGUF)
    protected val markerFile: File get() = File(File(app.filesDir, "robot"), ModelFiles.LOADED)

    protected fun record(o: OwnModel) {
        File(app.filesDir, "robot").apply { mkdirs() }.resolve(ModelFiles.OWN_RECORD).writeText(o.toJson().toString())
    }

    /** [bytes] as your own file, with the app's record of it. */
    protected fun own(bytes: ByteArray = FakeGguf.medgemma(), at: File = ggufFile, sha: String = "ab".repeat(32)) {
        at.parentFile!!.mkdirs()
        at.writeBytes(bytes)
        record(OwnModel(at.name, bytes.size.toLong(), sha, ModelFiles.fingerprint(at.length(), at.lastModified()), OwnModel.NOT_LITERT_LM,
            gguf = GgufCheck.check(ArrayByteSource(bytes), 0)))
    }

    protected fun req(path: File = ggufFile, model: BrainModel = BrainModel.OWN, backend: ModelBackend = ModelBackend.CPU,
        maxTokens: Int = 4096, format: ModelFormat = ModelFormat.GGUF) = BrainMsg.Load(path.path, model, backend, maxTokens, format)

    protected fun load(r: BrainMsg.Load = req()): BrainMsg.Loaded = onWork { engine.load(r) }

    protected fun loaded(bytes: ByteArray = FakeGguf.medgemma(), maxTokens: Int = 4096) {
        own(bytes)
        val l = load(req(maxTokens = maxTokens))
        assertTrue(l.error, l.ok)
        assertEquals(ModelFormat.GGUF, l.format)
    }
}
