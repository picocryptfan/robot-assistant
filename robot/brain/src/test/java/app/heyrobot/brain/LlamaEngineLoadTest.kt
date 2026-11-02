// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.content.ComponentName
import android.os.Parcel
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.FakeGguf
import app.heyrobot.core.LoadCheck
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelMove
import app.heyrobot.core.OwnModel
import app.heyrobot.core.i18n.ModelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.VoiceStrings
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** What LlamaEngine decides about your own GGUF file before and while its isolated process loads it; no model is ever read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [StatShadow::class])
internal class LlamaEngineLoadTest : LlamaBench() {
    private val holder = object : SlotHolder { override val destroyedAt = 0L }

    @After fun freeSlot() = EngineSlot.release(holder, app)

    private fun refused(l: BrainMsg.Loaded, failure: LoadFailure, text: Phrase? = null) {
        assertFalse(l.ok)
        assertEquals(failure, l.failure)
        assertEquals(ModelFormat.GGUF, l.format)
        text?.let { assertEquals(it.en(), l.error) }
    }

    private fun neverStarted() {
        assertEquals(0, fake.hellos.get())
        assertFalse("the file was marked as in memory", markerFile.exists())
    }

    // ---------------------------------------------------------------- before any process starts

    @Test fun aMissingFileIsNoFile() {
        refused(load(), LoadFailure.NO_FILE, VoiceStrings.engineFileMissing())
        neverStarted()
    }

    @Test fun aFileOutsideRobotsStorageIsNoFileEvenWithAFairName() {
        val out = Files.createTempDirectory("outside").toFile()
        own(at = File(out, ModelFiles.OWN_GGUF))
        refused(load(req(File(out, ModelFiles.OWN_GGUF))), LoadFailure.NO_FILE)
        neverStarted()
    }

    @Test fun aPathOrALinkLeadingOutOfRobotsStorageIsNoFile() {
        val out = Files.createTempDirectory("outside").toFile()
        own(at = File(out, ModelFiles.OWN_GGUF))
        refused(load(req(File(app.filesDir, "../" + out.name + "/" + ModelFiles.OWN_GGUF))), LoadFailure.NO_FILE)
        Files.createSymbolicLink(ggufFile.toPath(), File(out, ModelFiles.OWN_GGUF).toPath())
        refused(load(), LoadFailure.NO_FILE)
        neverStarted()
    }

    @Test fun onlyTheFixedNameYourOwnModelAndTheGgufFormatLoad() {
        own()
        val other = File(app.filesDir, "other.gguf").apply { writeBytes(ggufFile.readBytes()) }
        refused(load(req(other)), LoadFailure.NO_FILE)
        refused(load(req(model = BrainModel.FAST)), LoadFailure.NO_FILE)
        refused(load(req(format = ModelFormat.LITERT_LM)), LoadFailure.NO_FILE)
        refused(load(req(backend = ModelBackend.TPU)), LoadFailure.NOT_ALLOWED, VoiceStrings.ggufCpuOnly())
        refused(load(req(backend = ModelBackend.GPU)), LoadFailure.NOT_ALLOWED, VoiceStrings.ggufCpuOnly())
        neverStarted()
    }

    @Test fun noRecordOrAStaleOneIsAChangedFile() {
        ggufFile.writeBytes(FakeGguf.medgemma())
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        own()
        ggufFile.setLastModified(ggufFile.lastModified() - 60_000)
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
    }

    @Test fun aBrokenRecordIsAChangedFile() {
        own()
        File(File(app.filesDir, "robot"), ModelFiles.OWN_RECORD).writeText("{not json")
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
    }

    @Test fun aRecordOfTheOtherKindOfFileIsAChangedFile() {
        own()
        record(OwnModel("x", ggufFile.length(), "ab".repeat(32), ModelFiles.fingerprint(ggufFile.length(), ggufFile.lastModified()), OwnModel.NOT_LITERT_LM))
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
    }

    @Test fun aFileSwappedAtThePathSinceItWasOpenedIsRefused() {
        own()
        StatShadow.pathIno = 2
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
    }

    @Test fun aHeaderThatDoesNotMatchItsRecordIsAChangedFile() {
        own(FakeGguf.medgemma(tokens = 64))
        val recorded = File(File(app.filesDir, "robot"), ModelFiles.OWN_RECORD).readText()
        ggufFile.writeBytes(FakeGguf.medgemma(tokens = 128))
        // The same size and time as recorded would be a forgery: the header read through the open file still decides.
        val forged = OwnModel.fromJson(recorded)!!.copy(bytes = ggufFile.length(), fingerprint = ModelFiles.fingerprint(ggufFile.length(), ggufFile.lastModified()))
        record(forged)
        refused(load(), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
    }

    @Test fun garbageInTheFileIsRefusedInTheChecksOwnWords() {
        own()
        ggufFile.writeBytes(ByteArray(4096) { 7 })
        val o = OwnModel.fromJson(File(File(app.filesDir, "robot"), ModelFiles.OWN_RECORD).readText())!!
        record(o.copy(bytes = ggufFile.length(), fingerprint = ModelFiles.fingerprint(ggufFile.length(), ggufFile.lastModified())))
        val l = load()
        refused(l, LoadFailure.NOT_ALLOWED)
        assertFalse(l.error, l.error.isNullOrBlank())
        neverStarted()
    }

    @Test fun anEmptyFileIsNeverLoaded() {
        ggufFile.writeBytes(ByteArray(0))
        refused(load(), LoadFailure.NOT_ALLOWED)
        neverStarted()
    }

    @Test fun aContextOtherThanTheFilesIsRefused() {
        own()
        refused(load(req(maxTokens = 2048)), LoadFailure.NOT_ALLOWED)
        neverStarted()
    }

    @Test fun aBuildWithoutTheLibrarySaysSoAndStartsNothing() {
        own()
        File(app.applicationInfo.nativeLibraryDir, "librobot_llama.so").delete()
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaNotInBuild())
        neverStarted()
    }

    @Test fun neverBesideAParkedEngine() {
        own()
        EngineSlot.parked.set(1)
        refused(load(), LoadFailure.STOPPING, ModelStrings.stopping())
        neverStarted()
    }

    @Test fun neverBesideAnEngineHoldingTheSlot() {
        own()
        assertTrue(EngineSlot.claim(holder, app, req()))
        refused(load(), LoadFailure.STOPPING, ModelStrings.stopping())
        assertEquals(0, fake.hellos.get())
    }

    @Test fun aFileOutsideInternalStorageMustMatchItsWholeHash() {
        val ext = app.getExternalFilesDir(null)!!
        val f = File(ext, ModelFiles.OWN_GGUF)
        own(at = f, sha = "ab".repeat(32))
        refused(load(req(f)), LoadFailure.NOT_ALLOWED, LoadCheck.changedText)
        neverStarted()
        val bytes = f.readBytes()
        own(bytes, at = f, sha = f.inputStream().channel.use { ModelMove.sha256(it) })
        assertTrue(load(req(f)).ok)
    }

    // ---------------------------------------------------------------- with its isolated process

    @Test fun aGoodFileLoadsThroughTheProcessAndMarksItself() {
        own()
        val l = load()
        assertTrue(l.error, l.ok)
        assertEquals(ModelFormat.GGUF, l.format)
        assertEquals(true, fake.proofAccepted)
        assertEquals(1, fake.loads.get())
        assertTrue(markerFile.exists())
        assertTrue(onWork { engine.status() }.loaded)
        assertFalse(onWork { engine.status() }.vision)
    }

    @Test fun theSameLoadAgainStartsNothingNew() {
        own()
        assertTrue(load().ok)
        assertTrue(load().ok)
        assertEquals(1, fake.hellos.get())
    }

    @Test fun anUnloadEndsTheProcessAndFreesTheSlot() {
        own()
        assertTrue(load().ok)
        onWork { engine.unload("asked") }
        assertEquals(1, fake.exits.get())
        settle()
        assertFalse(markerFile.exists())
        assertFalse(onWork { engine.status() }.loaded)
    }

    @Test fun aProcessThatCannotProveAnIsolatedUidNeverGetsTheFile() {
        own()
        ShadowBinder.setCallingUid(10_123)
        refused(load(), LoadFailure.NOT_ALLOWED, VoiceStrings.llamaNotIsolated())
        assertEquals(false, fake.proofAccepted)
        assertEquals(0, fake.loads.get())
        assertEquals(1, fake.exits.get())
        assertFalse(markerFile.exists())
    }

    @Test fun whatTheProcessSaysOfItsLibraryIsSaidInRobotsWords() {
        own()
        fake.answerHello = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(1); r.writeString(LlamaIpc.NOT_IN_BUILD) }
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaNotInBuild())
        assertEquals(0, fake.loads.get())
    }

    @Test fun wordsOfTheProcessOwnBreakTheLinkAndAreNeverShown() {
        own()
        fake.answerHello = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(1); r.writeString("Type your password here") }
        val l = load()
        refused(l, LoadFailure.PROCESS, VoiceStrings.llamaStopped())
        assertEquals(1, fake.exits.get())
        assertFalse(markerFile.exists())
    }

    @Test fun anyOtherBrokenHelloEndsTheProcess() {
        own()
        val broken = listOf<(Parcel) -> Unit>(
            { r -> r.writeInt(-129) },
            { r -> r.writeInt(LlamaIpc.REPLY_OK) },
            { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(2); r.writeString(null) },
            { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(1); r.writeString(null); r.writeInt(9) },
        )
        for (b in broken) {
            val f = FakeLlama()
            f.answerHello = b
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app.packageName, LlamaIpc.SERVICE_CLASS), f)
            refused(load(), LoadFailure.PROCESS, VoiceStrings.llamaStopped())
            assertEquals(1, f.exits.get())
            assertEquals(0, f.loads.get())
        }
    }

    @Test fun whatLlamaCppCouldNotLoadIsNamed() {
        own()
        fun answer(why: Int) { fake.answerLoad = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeLong(0); r.writeInt(why); r.writeInt(0); r.writeInt(-1); r.writeInt(-1) } }
        answer(LlamaIpc.LOAD_CONTEXT)
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaNoContext())
        answer(LlamaIpc.LOAD_TEMPLATE)
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaNoTemplate())
        answer(LlamaIpc.LOAD_MODEL)
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaCouldntLoad())
        assertFalse(onWork { engine.status() }.loaded)
        assertFalse(markerFile.exists())
    }

    @Test fun aHandleWithoutItsSpecialTokensIsNotALoad() {
        own()
        fake.control = null
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaCouldntLoad())
        assertFalse(onWork { engine.status() }.loaded)
    }

    @Test fun tooManySpecialTokensBreakTheLink() {
        own()
        fake.answerLoad = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeLong(1); r.writeInt(0); r.writeInt(131_072); r.writeInt(LlamaIpc.MAX_SPECIALS + 1) }
        refused(load(), LoadFailure.PROCESS, VoiceStrings.llamaStopped())
    }

    @Test fun twoParsersReadingTheContextDifferentlyIsRefused() {
        own()
        fake.train = 8192
        refused(load(), LoadFailure.NOT_ALLOWED, VoiceStrings.llamaDifferent())
        assertFalse(onWork { engine.status() }.loaded)
        assertFalse(markerFile.exists())
    }

    @Test fun aProcessDyingInTheLoadIsASkippedFileNotACrash() {
        own()
        fake.answerLoad = { fake.crash() }
        refused(load(), LoadFailure.PROCESS, VoiceStrings.llamaStopped())
        assertFalse(markerFile.exists())
    }

    @Test fun aProcessThatAnswersButNeverGoesKeepsTheSlotParkedUntilItDoes() {
        own()
        fake.exitEnds = false
        fake.answerLoad = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeLong(0); r.writeInt(LlamaIpc.LOAD_MODEL); r.writeInt(0); r.writeInt(-1); r.writeInt(-1) }
        refused(load(), LoadFailure.ENGINE, VoiceStrings.llamaCouldntLoad())
        assertEquals(1, EngineSlot.parked.get())
        assertTrue(markerFile.exists())
        // Nothing beside it, until it is gone.
        refused(load(), LoadFailure.STOPPING, ModelStrings.stopping())
        fake.die()
        settle()
        assertEquals(0, EngineSlot.parked.get())
        assertFalse(markerFile.exists())
    }
}
