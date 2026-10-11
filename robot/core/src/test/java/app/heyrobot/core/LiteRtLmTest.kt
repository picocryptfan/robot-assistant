package app.heyrobot.core

import app.heyrobot.core.FakeLiteRtLm.LLM_METADATA
import app.heyrobot.core.FakeLiteRtLm.SP_TOKENIZER
import app.heyrobot.core.FakeLiteRtLm.Section
import app.heyrobot.core.FakeLiteRtLm.TFLITE
import app.heyrobot.core.FakeLiteRtLm.op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random

/**
 * The content check (0.4.1, your own model file) on fake headers shaped like the files the research parsed: Google's E2B
 * Tensor G5 build, Google's E4B CPU file, the renamed "Tensor G5" CPU export, NPU builds for other chips, and broken files.
 */
class LiteRtLmTest {
    private val dispatchOps = listOf(op("DISPATCH_OP"))
    private val cpuOps = listOf(op(builtin = 9), op(builtin = 22), op(builtin = 150), op(builtin = 159))
    private val g5Stamp = mapOf("LiteRtStamp" to FakeLiteRtLm.stamp("Google", "Tensor_G5"), "min_runtime_version" to "2.21.0".toByteArray())

    private fun tfl(type: String, data: ByteArray) = Section(type, TFLITE, data)
    private fun small(type: String) = tfl(type, FakeLiteRtLm.tflite(listOf(op(builtin = 0))))
    private fun meta(modelType: ByteArray = FakeLiteRtLm.gemma4(), max: Int = 0) = Section(null, LLM_METADATA, FakeLiteRtLm.llmMetadata(modelType, max))

    /** Like gemma-4-E2B-it_Google_Tensor_G5.litertlm: every heavy section a DISPATCH_OP stamped Tensor_G5, an aux section, Gemma 4 metadata. */
    private fun e2bG5(stamp: Map<String, ByteArray> = g5Stamp, aux: Boolean = true, metadata: ByteArray = FakeLiteRtLm.gemma4(), outside: Boolean = false) =
        FakeLiteRtLm.litertlm(listOfNotNull(
            tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(dispatchOps, stamp, outside)),
            small("tf_lite_embedder"),
            if (aux) small("tf_lite_aux") else null,
            small("tf_lite_per_layer_embedder"),
            tfl("tf_lite_vision_encoder", FakeLiteRtLm.tflite(dispatchOps, stamp)),
            Section(null, SP_TOKENIZER, ByteArray(64) { it.toByte() }),
            meta(metadata),
        ))

    /** Like Google's gemma-4-E4B-it.litertlm: plain TFLite, Gemma 4 metadata with tool tokens, vision, no aux. */
    private fun e4bCpu(metadata: ByteArray = FakeLiteRtLm.gemma4(), max: Int = 0) = FakeLiteRtLm.litertlm(listOf(
        meta(metadata, max),
        Section(null, SP_TOKENIZER, ByteArray(32)),
        small("tf_lite_embedder"),
        tfl("tf_lite_vision_encoder", FakeLiteRtLm.tflite(cpuOps)),
        tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(cpuOps, mapOf("min_runtime_version" to "2.17.0".toByteArray()))),
        small("tf_lite_mtp_drafter"),
    ))

    private fun check(b: ByteArray) = LiteRtLmCheck.check(ArrayByteSource(b))

    @Test fun googlesTensorG5BuildIsATpuBuild() {
        val r = check(e2bG5())
        assertTrue(r.toString(), r.readable && r.decoder && r.aux && r.dispatchOp && r.tensorG5Stamp && r.gemma4 && r.toolTokens && r.vision)
        assertEquals("1.5.0", r.format)
        assertEquals("Google Tensor_G5", r.stamp)
        assertTrue(r.tensorG5Build)
        assertEquals(ModelBackend.TPU, r.backend)
        assertNull(r.unusable)
        assertTrue(r.lines().any { it.startsWith("Compiled for this phone's TPU (Tensor G5): yes") })
        // The stamp in a buffer after the flatbuffer (offset and size), as a "buffers outside" export keeps it, reads the same.
        assertEquals(ModelBackend.TPU, check(e2bG5(outside = true)).backend)
    }

    @Test fun googlesCpuFileRunsOnTheCpu() {
        val r = check(e4bCpu())
        assertTrue(r.readable && r.decoder && r.gemma4 && r.toolTokens && r.vision)
        assertFalse(r.aux || r.dispatchOp || r.tensorG5Build)
        assertNull(r.stamp)
        assertEquals(ModelBackend.CPU, r.backend)
        assertTrue(r.lines().contains("Gemma 4, with its tool-calling metadata: actions should work."))
    }

    /**
     * safecircleai/horizon-edge-4b's "…_Google_Tensor_G5.litertlm" is the generic CPU export renamed: no aux, no
     * DISPATCH_OP, no stamp, generic_model metadata. It must never get the TPU, whatever its name says (the check has no
     * name to go by at all), and Robot says tools may not work.
     */
    @Test fun aRenamedCpuExportIsNotATpuBuild() {
        val horizon = FakeLiteRtLm.litertlm(listOf(
            meta(FakeLiteRtLm.GENERIC, max = 4096),
            Section(null, 6, ByteArray(16)),
            tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(cpuOps, mapOf("min_runtime_version" to "2.17.0".toByteArray()))),
            small("tf_lite_embedder"), small("tf_lite_per_layer_embedder"),
        ))
        val r = check(horizon)
        assertTrue(r.readable)
        assertFalse(r.tensorG5Build)
        assertEquals(ModelBackend.CPU, r.backend)
        assertEquals("generic_model", r.modelType)
        assertFalse(r.toolTokens || r.vision)
        assertEquals(4096, r.maxTokens)
        assertTrue(r.lines().any { it.contains("not Gemma 4") && it.contains("actions may not work") })
    }

    /** A stamp alone proves nothing: without DISPATCH_OP and the aux section the file is ordinary TFLite (the CPU). */
    @Test fun aStampWithoutTpuCodeIsNotATpuBuild() {
        val spoof = FakeLiteRtLm.litertlm(listOf(
            tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(cpuOps, g5Stamp)), meta(),
        ))
        val r = check(spoof)
        assertTrue(r.tensorG5Stamp)
        assertFalse(r.tensorG5Build)
        assertEquals(ModelBackend.CPU, r.backend)
    }

    /** TPU code that isn't a complete Tensor G5 build can run nowhere: not on the TPU (unproven), not on the CPU (NPU code). */
    @Test fun incompleteOrForeignNpuBuildsAreRefused() {
        val noAux = check(e2bG5(aux = false))
        assertNull(noAux.backend)
        assertTrue(noAux.unusable!!, noAux.unusable!!.contains("the aux section"))
        val generic = check(e2bG5(metadata = FakeLiteRtLm.GENERIC))
        assertNull(generic.backend)
        assertTrue(generic.unusable!!.contains("Gemma 4 metadata"))
        // assassinshadow's Snapdragon 8 Gen 1 build of E4B, and Google's own E2B build for the G6.
        for (s in listOf(FakeLiteRtLm.stamp("Qualcomm", "SM8450"), FakeLiteRtLm.stamp("Google", "Tensor_G6"))) {
            val r = check(e2bG5(stamp = mapOf("LiteRtStamp" to s)))
            assertFalse(r.tensorG5Stamp || r.tensorG5Build)
            assertNull(r.backend)
            assertTrue(r.unusable!!, r.unusable!!.contains("another chip's NPU"))
        }
        // DISPATCH_OP without any stamp.
        assertNull(check(e2bG5(stamp = emptyMap())).backend)
    }

    @Test fun gemma4WithoutToolTokensWarnsAboutActions() {
        val r = check(e4bCpu(FakeLiteRtLm.gemma4(tools = false)))
        assertTrue(r.gemma4)
        assertFalse(r.toolTokens)
        assertEquals(ModelBackend.CPU, r.backend)
        assertTrue(r.lines().any { it.contains("without its tool-calling metadata") && it.contains("may not work") })
        // A Gemma 4 TPU build without them still isn't refused for that, only warned about.
        assertEquals(ModelBackend.TPU, check(e2bG5(metadata = FakeLiteRtLm.gemma4(tools = false))).backend)
    }

    @Test fun aContextShorterThanRobotsIsRefused() {
        val r = check(e4bCpu(max = 1024))
        assertNull(r.backend)
        assertTrue(r.unusable!!, r.unusable!!.contains("only 1024 tokens"))
        assertEquals(ModelBackend.CPU, check(e4bCpu(max = 32_000)).backend)
    }

    @Test fun aFileWithoutALanguageModelIsRefused() {
        val r = check(FakeLiteRtLm.litertlm(listOf(small("tf_lite_embedder"), meta())))
        assertTrue(r.readable)
        assertFalse(r.decoder)
        assertNull(r.backend)
        assertTrue(r.unusable!!.contains("no prefill_decode"))
    }

    @Test fun otherFilesAreUnreadableNeverAnError() {
        assertFalse(check(ByteArray(0)).readable)
        assertFalse(check("not a model at all, just some text that goes on".toByteArray()).readable)
        val wrongMagic = check(FakeLiteRtLm.litertlm(listOf(small("tf_lite_prefill_decode")), magic = "TFLITE00"))
        assertFalse(wrongMagic.readable)
        assertEquals("It isn't a LiteRT-LM model file (.litertlm).", wrongMagic.unusable)
        // Cut short: the header promises more than the file has.
        val good = e2bG5()
        for (n in listOf(8, 31, 40, 200, good.size / 2)) assertFalse("cut at $n", check(good.copyOf(n)).readable)
        // A header end past the file, or absurdly large.
        val far = good.copyOf().also { it[24] = 0xff.toByte(); it[25] = 0xff.toByte(); it[26] = 0xff.toByte() }
        assertFalse(check(far).readable)
        assertNull(check(far).backend)
    }

    /** The file is untrusted: random damage anywhere ends in a report (readable or not), never an exception or a hang. */
    @Test fun damagedFilesNeverThrow() {
        val rnd = Random(41)
        for (base in listOf(e2bG5(), e4bCpu(), e2bG5(outside = true))) {
            repeat(400) {
                val b = base.copyOf()
                repeat(1 + rnd.nextInt(6)) { b[rnd.nextInt(minOf(b.size, 4096))] = rnd.nextInt(256).toByte() }
                val r = check(b)
                // Whatever it found, the TPU needs the whole proof.
                if (r.backend == ModelBackend.TPU) assertTrue(r.tensorG5Build)
            }
        }
    }

    @Test fun aRealFileOnDiskIsReadTheSameWay() {
        val dir = Files.createTempDirectory("litertlm").toFile()
        try {
            val f = File(dir, "renamed_Google_Tensor_G5.litertlm").apply { writeBytes(e4bCpu()) }
            assertEquals(ModelBackend.CPU, LiteRtLmCheck.check(f).backend)
            val g = File(dir, "plain.litertlm").apply { writeBytes(e2bG5()) }
            assertEquals(ModelBackend.TPU, LiteRtLmCheck.check(g).backend)
            assertFalse(LiteRtLmCheck.check(File(dir, "missing.litertlm")).readable)
        } finally { dir.deleteRecursively() }
    }

    @Test fun reportsRoundTripAndNeverGainTheTpu() {
        for (r in listOf(check(e2bG5()), check(e4bCpu()), check(ByteArray(3)), check(e2bG5(aux = false)))) {
            assertEquals(r, LiteRtLmReport.fromJson(r.toJson()))
        }
        assertNull(LiteRtLmReport.fromJson(null))
    }

    // ------------------------------------------------------------------ your own file's record

    private val sha = "ab".repeat(32)

    @Test fun ownModelNeedsYourOkForTheTpu() {
        val tpu = OwnModel("mine.litertlm", 3_000_000_000, sha, "3000000000|1", check(e2bG5()))
        assertEquals(ModelAvailability.NEEDS_TPU_OK, tpu.availability("3000000000|1"))
        assertNull(tpu.backend)
        val allowed = tpu.copy(tpuAllowed = true)
        assertEquals(ModelAvailability.READY, allowed.availability("3000000000|1"))
        assertEquals(ModelBackend.TPU, allowed.backend)
        // Changed or gone: checked again first.
        assertEquals(ModelAvailability.NOT_CHECKED, allowed.availability("3000000000|2"))
        assertEquals(ModelAvailability.NOT_DOWNLOADED, allowed.availability(null))
        val cpu = OwnModel("x", 10, sha, "10|1", check(e4bCpu()))
        assertEquals(ModelAvailability.READY, cpu.availability("10|1"))
        assertEquals(ModelBackend.CPU, cpu.backend)
        val bad = OwnModel("x", 10, sha, "10|1", check(ByteArray(10)))
        assertEquals(ModelAvailability.UNUSABLE, bad.availability("10|1"))
    }

    @Test fun ownModelRecordRoundTripsAndNeverAllowsTheTpuForACpuFile() {
        val r = OwnModel("my model “v2”.litertlm", 3_659_530_240, sha, "3659530240|1760000000000", check(e2bG5()), tpuAllowed = true, checkedAt = 5)
        assertEquals(r, OwnModel.fromJson(r.toJson().toString()))
        // A record that claims the TPU for a file the check didn't prove: read as not allowed.
        val forged = OwnModel("x", 10, sha, "10|1", check(e4bCpu())).toJson().put("tpuAllowed", true).toString()
        assertFalse(OwnModel.fromJson(forged)!!.tpuAllowed)
        assertEquals(ModelBackend.CPU, OwnModel.fromJson(forged)!!.backend)
        assertNull(OwnModel.fromJson("{\"sha256\":\"nothex\"}"))
        assertNull(OwnModel.fromJson("garbage"))
        assertNull(OwnModel.fromJson(null))
        assertNotNull(OwnModel.fromJson(OwnModel("x", 10, sha, "10|1", check(e4bCpu())).toJson().toString()))
    }
}
