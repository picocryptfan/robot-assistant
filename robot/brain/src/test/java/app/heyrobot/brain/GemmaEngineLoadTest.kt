// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.app.Application
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import app.heyrobot.core.ArrayByteSource
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainScheduler
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.FakeGguf
import app.heyrobot.core.FakeLiteRtLm
import app.heyrobot.core.FakeLiteRtLm.LLM_METADATA
import app.heyrobot.core.FakeLiteRtLm.SP_TOKENIZER
import app.heyrobot.core.FakeLiteRtLm.Section
import app.heyrobot.core.FakeLiteRtLm.TFLITE
import app.heyrobot.core.FakeLiteRtLm.op
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.LiteRtLmCheck
import app.heyrobot.core.LoadCheck
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Priority
import app.heyrobot.core.i18n.ModelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.VoiceStrings
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What GemmaEngine decides about a file before LiteRT-LM ever sees it, and how it answers with nothing loaded; no model and no LiteRT library here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GemmaEngineLoadTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var work: HandlerThread
    private lateinit var handler: Handler
    private lateinit var engine: GemmaEngine
    private val holder = object : SlotHolder { override val destroyedAt = 0L }
    private val records get() = File(app.filesDir, "robot").apply { mkdirs() }
    private val marker get() = File(records, ModelFiles.LOADED)

    @Before fun start() {
        work = HandlerThread("test-gemma").apply { start() }
        handler = Handler(work.looper)
        engine = GemmaEngine(app, handler)
    }

    @After fun stop() {
        work.quitSafely()
        work.join(5_000)
        EngineSlot.release(holder, app)
        EngineSlot.parked.set(0)
    }

    private fun <T> onWork(block: () -> T): T {
        val done = CountDownLatch(1)
        var out: Result<T>? = null
        handler.post {
            out = runCatching(block)
            done.countDown()
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        return out!!.getOrThrow()
    }

    private fun req(f: File, model: BrainModel = BrainModel.OWN, backend: ModelBackend = ModelBackend.CPU, maxTokens: Int = 4096,
        format: ModelFormat = ModelFormat.LITERT_LM) = BrainMsg.Load(f.path, model, backend, maxTokens, format)

    private fun load(r: BrainMsg.Load) = onWork { engine.load(r) }

    private fun refused(l: BrainMsg.Loaded, failure: LoadFailure, text: Phrase? = null) {
        assertFalse(l.ok)
        assertEquals(failure, l.failure)
        assertEquals(ModelFormat.LITERT_LM, l.format)
        text?.let { assertEquals(it.en(), l.error) }
        assertFalse("nothing is marked as in memory", marker.exists())
    }

    private fun file(m: BrainModel, bytes: ByteArray = ByteArray(1024) { 7 }) = File(app.filesDir, m.file).apply { writeBytes(bytes) }
    private fun fingerprint(f: File) = ModelFiles.fingerprint(f.length(), f.lastModified())
    private fun mark(m: BrainModel, f: File) = File(records, ModelFiles.verifiedMark(m)).writeText("${m.sha256}|${fingerprint(f)}")

    private fun section(type: String, data: ByteArray) = Section(type, TFLITE, data)
    private fun small(type: String) = section(type, FakeLiteRtLm.tflite(listOf(op(builtin = 0))))
    /** A LiteRT-LM container that passes the strict check for the CPU, with no weights. */
    private fun cpuFile(): ByteArray {
        val ops = listOf(op(builtin = 9), op(builtin = 22), op(builtin = 150), op(builtin = 159))
        return FakeLiteRtLm.litertlm(listOf(
            Section(null, LLM_METADATA, FakeLiteRtLm.llmMetadata(FakeLiteRtLm.gemma4(), 0)),
            Section(null, SP_TOKENIZER, ByteArray(32)),
            small("tf_lite_embedder"),
            section("tf_lite_vision_encoder", FakeLiteRtLm.tflite(ops)),
            section("tf_lite_prefill_decode", FakeLiteRtLm.tflite(ops, mapOf("min_runtime_version" to "2.17.0".toByteArray()))),
            small("tf_lite_mtp_drafter"),
        ))
    }

    private fun ownRecord(f: File, sha: String = "ab".repeat(32)) {
        val report = LiteRtLmCheck.check(f, strict = true)
        File(records, ModelFiles.OWN_RECORD).writeText(OwnModel(f.name, f.length(), sha, fingerprint(f), report).toJson().toString())
    }

    // ---------------------------------------------------------------- refused before LiteRT-LM reads anything

    @Test fun aGgufFileIsNeverParsedByLiteRt() {
        refused(load(req(file(BrainModel.OWN, FakeGguf.medgemma()), format = ModelFormat.GGUF)), LoadFailure.NOT_ALLOWED, VoiceStrings.engineLiteRtOnly())
    }

    @Test fun aMissingFileOrAnotherNameIsNoFile() {
        refused(load(req(File(app.filesDir, BrainModel.OWN.file))), LoadFailure.NO_FILE, VoiceStrings.engineFileMissing())
        refused(load(req(File(app.filesDir, "other.litertlm").apply { writeBytes(cpuFile()) })), LoadFailure.NO_FILE, VoiceStrings.engineFileMissing())
    }

    @Test fun aFileOutsideRobotsStorageOrLinkedThereIsNoFile() {
        val out = Files.createTempDirectory("outside").toFile()
        val there = File(out, BrainModel.OWN.file).apply { writeBytes(cpuFile()) }
        refused(load(req(there)), LoadFailure.NO_FILE)
        Files.createSymbolicLink(File(app.filesDir, BrainModel.OWN.file).toPath(), there.toPath())
        refused(load(req(File(app.filesDir, BrainModel.OWN.file))), LoadFailure.NO_FILE)
    }

    @Test fun aFileInExternalStorageMustMoveFirst() {
        val f = File(app.getExternalFilesDir(null)!!, BrainModel.OWN.file).apply { writeBytes(cpuFile()) }
        ownRecord(f)
        refused(load(req(f)), LoadFailure.FILE_CHANGED, LoadCheck.outsideText(BrainModel.OWN))
    }

    @Test fun aBuiltInFileNeedsItsOwnMark() {
        val m = BrainModel.GENERAL
        val f = file(m)
        val r = req(f, m, maxTokens = m.contextTokens)
        refused(load(r), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
        File(records, ModelFiles.verifiedMark(m)).writeText("deadbeef|${fingerprint(f)}")
        refused(load(r), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
        mark(m, f)
        f.setLastModified(f.lastModified() - 60_000)
        refused(load(r), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
    }

    @Test fun aBuiltInFileWithItsMarkIsStillReadBeforeItLoads() {
        val m = BrainModel.GENERAL
        val f = file(m)
        mark(m, f)
        refused(load(req(f, m, maxTokens = m.contextTokens)), LoadFailure.NOT_ALLOWED)
    }

    @Test fun theTpuIsRefusedOnAPhoneWithout() {
        val m = BrainModel.FAST
        val f = file(m)
        mark(m, f)
        refused(load(req(f, m, ModelBackend.TPU, m.contextTokens)), LoadFailure.NOT_ALLOWED, LoadCheck.noTpuText)
    }

    @Test fun aBuiltInModelOnlyOnItsContext() {
        val m = BrainModel.GENERAL
        val f = file(m)
        mark(m, f)
        refused(load(req(f, m, maxTokens = m.contextTokens + 1)), LoadFailure.NOT_ALLOWED, LoadCheck.wrongContextText(m.contextTokens))
    }

    @Test fun yourOwnFileWithNoStaleOrBrokenRecordIsAChangedFile() {
        val f = file(BrainModel.OWN, cpuFile())
        refused(load(req(f)), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
        ownRecord(f)
        f.setLastModified(f.lastModified() - 60_000)
        refused(load(req(f)), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
        File(records, ModelFiles.OWN_RECORD).writeText("{not json")
        refused(load(req(f)), LoadFailure.FILE_CHANGED, LoadCheck.changedText)
    }

    @Test fun aRecordOfAGgufFileIsNotALiteRtFile() {
        val f = file(BrainModel.OWN, cpuFile())
        val gguf = OwnModel(f.name, f.length(), "ab".repeat(32), fingerprint(f), OwnModel.NOT_LITERT_LM,
            gguf = GgufCheck.check(ArrayByteSource(FakeGguf.medgemma()), 0))
        File(records, ModelFiles.OWN_RECORD).writeText(gguf.toJson().toString())
        refused(load(req(f)), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
    }

    @Test fun whatTheRecordSaidIsReadAgainFromTheFile() {
        val f = file(BrainModel.OWN, cpuFile())
        ownRecord(f)
        val real = LiteRtLmCheck.check(f, strict = true).contextTokens
        val at = f.lastModified()
        f.writeBytes(ByteArray(f.length().toInt()) { 7 })
        f.setLastModified(at)
        refused(load(req(f, maxTokens = real)), LoadFailure.NOT_ALLOWED)
    }

    @Test fun onlyTheContextTheFileWasBuiltWith() {
        val f = file(BrainModel.OWN, cpuFile())
        ownRecord(f)
        val real = LiteRtLmCheck.check(f, strict = true).contextTokens
        refused(load(req(f, maxTokens = real + 1)), LoadFailure.NOT_ALLOWED, LoadCheck.wrongContextText(real))
    }

    @Test fun neverBesideAParkedOrAHoldingEngine() {
        val f = file(BrainModel.OWN, cpuFile())
        ownRecord(f)
        val real = LiteRtLmCheck.check(f, strict = true).contextTokens
        EngineSlot.parked.set(1)
        refused(load(req(f, maxTokens = real)), LoadFailure.STOPPING, ModelStrings.stopping())
        EngineSlot.parked.set(0)
        assertTrue(EngineSlot.claim(holder, app, req(f, maxTokens = real)))
        val l = load(req(f, maxTokens = real))
        assertFalse(l.ok)
        assertEquals(LoadFailure.STOPPING, l.failure)
        assertTrue("the holder's marker stays", marker.exists())
    }

    // ---------------------------------------------------------------- past the checks, with no LiteRT library

    @Test fun whateverLiteRtDoesTheSlotIsFreeAfterAFailedLoad() {
        val f = file(BrainModel.OWN, cpuFile())
        ownRecord(f)
        val r = req(f, maxTokens = LiteRtLmCheck.check(f, strict = true).contextTokens)
        val outcome = runCatching { load(r) }
        outcome.getOrNull()?.let { assertFalse(it.ok) }
        assertFalse("the marker goes with the model", marker.exists())
        assertFalse(onWork { engine.status() }.loaded)
        assertTrue("another engine may load", EngineSlot.claim(holder, app, r))
    }

    // ---------------------------------------------------------------- nothing loaded

    @Test fun nothingLoadedAnswersNotLoadedEveryWay() {
        val errors = ArrayList<BrainMsg.Error>()
        val sink = BrainEngine.Sink { what, m ->
            assertEquals(BrainProtocol.EV_ERROR, what)
            errors += m as BrainMsg.Error
        }
        val k = BrainScheduler.Key(Binder(), 1)
        onWork {
            engine.generate(k, BrainMsg.Generate("c", "S", emptyList(), 0, "q", false), null, sink)
            engine.oneShot(k, BrainMsg.OneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, "T", "q", 10), null, sink)
            engine.toolResult(k, BrainMsg.ToolResult("c", emptyList()), sink)
            engine.cancel(k)
            engine.closeConv("c")
            engine.unload("nothing to let go")
        }
        assertEquals(listOf(BrainError.NOT_LOADED, BrainError.NOT_LOADED, BrainError.NOT_LOADED), errors.map { it.code })
        val s = onWork { engine.status() }
        assertFalse(s.loaded)
        assertEquals(null, s.model)
    }

    @Test fun shuttingDownWithNothingLoadedQuitsTheThread() {
        val steps = ArrayList<EngineTeardown.Step>()
        onWork { engine.shutdown { steps += it } }
        assertEquals(listOf(EngineTeardown.Step.QUIT_THREAD), steps)
    }
}
