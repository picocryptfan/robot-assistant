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
import org.junit.Assert.fail
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

    /**
     * 0.4.3: 2,048 to 4,095 tokens is chat only (loaded with its own context, no actions), 4,096 and more (or none stated)
     * runs as before with 4,096, and under 2,048 is still refused.
     */
    @Test fun shortContextsAreChatOnlyAndUnder2048IsStillRefused() {
        val short = "Short context (2048 tokens): chat only — no actions, shorter memory."
        for (max in listOf(2048, 3000, 4095)) {
            val r = check(e4bCpu(max = max))
            assertTrue(r.shortContext)
            assertNull(r.unusable)
            assertEquals(ModelBackend.CPU, r.backend)
            assertEquals(max, r.contextTokens)
            assertTrue(r.lines().toString(), r.lines().contains("Short context ($max tokens): chat only — no actions, shorter memory."))
            // No promise about actions, whatever the metadata says.
            assertFalse(r.lines().any { it.contains("actions should work") || it.contains("may not work") })
            assertTrue(r.lines().contains("Gemma 4."))
        }
        assertTrue(check(e4bCpu(max = 2048)).lines().contains(short))
        for (max in listOf(4096, 32_000)) {
            val r = check(e4bCpu(max = max))
            assertFalse(r.shortContext)
            assertEquals(4096, r.contextTokens)
            assertTrue(r.lines().contains("Context: $max tokens (Robot uses 4096)."))
            assertTrue(r.lines().contains("Gemma 4, with its tool-calling metadata: actions should work."))
        }
        // None stated (Google's own files): 4,096, as before.
        assertEquals(4096, check(e4bCpu(max = 0)).contextTokens)
        assertFalse(check(e4bCpu(max = 0)).lines().any { it.startsWith("Context:") || it.startsWith("Short context") })
        for (max in listOf(1, 1024, 2047)) {
            val r = check(e4bCpu(max = max))
            assertNull(r.backend)
            assertEquals(0, r.contextTokens)
            assertEquals("It was built for only $max tokens of context; Robot needs at least 2048 to chat (4096 for actions).", r.unusable)
            assertFalse(r.lines().any { it.startsWith("Short context") })
        }
    }

    /** Shaped like MedGemma 1.5 4B's LiteRT-LM files (their headers, 0.4.3): gemma3n metadata, 2,048 tokens, no tool tokens. */
    private fun medgemma(vision: Boolean) = FakeLiteRtLm.litertlm(listOfNotNull(
        meta(FakeLiteRtLm.gemma3n(), max = 2048),
        Section(null, 6, ByteArray(32)),
        tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(cpuOps, mapOf("min_runtime_version" to "2.17.0".toByteArray()))),
        small("tf_lite_embedder"),
        if (vision) tfl("tf_lite_vision_encoder", FakeLiteRtLm.tflite(cpuOps)) else null,
        if (vision) small("tf_lite_vision_adapter") else null,
    ))

    @Test fun medGemmaShapedFilesAreChatOnlyOnTheCpu() {
        for (vision in listOf(true, false)) {
            val r = check(medgemma(vision))
            assertTrue(r.readable && r.decoder && r.shortContext)
            assertEquals("gemma3n", r.modelType)
            assertFalse(r.gemma4 || r.toolTokens || r.tensorG5Build)
            assertEquals(ModelBackend.CPU, r.backend)
            assertEquals(2048, r.contextTokens)
            assertEquals(vision, r.vision)
            assertEquals(listOf(
                "LiteRT-LM file, format 1.5.0, ${r.sections.size} sections.",
                "Compiled for this phone's TPU: no. It runs on the CPU.",
                "Model type “gemma3n”, not Gemma 4: Robot is written for Gemma 4, so answers may be odd.",
                if (vision) "Can see photos: yes." else "Can see photos: no (questions about a photo won't work).",
                "Short context (2048 tokens): chat only — no actions, shorter memory.",
            ), r.lines())
            // Imported and checked like any other own file: ready to choose, and its record keeps all of it.
            val own = OwnModel("medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm", 3_023_069_488, sha, "3023069488|1", r)
            assertEquals(ModelAvailability.READY, own.availability("3023069488|1"))
            assertEquals(ModelBackend.CPU, own.backend)
            val back = OwnModel.fromJson(own.toJson().toString())!!
            assertEquals(own, back)
            assertEquals(2048, back.report.contextTokens)
        }
    }

    /** A record can't make a short file long, nor a refused one usable: the context comes from the checked numbers. */
    @Test fun aRecordsContextComesFromTheCheckedHeader() {
        val r = check(e4bCpu(max = 1024))
        val forged = OwnModel("x", 10, sha, "10|1", r).toJson().apply { getJSONObject("report").put("maxTokens", 1024) }.toString()
        assertNull(OwnModel.fromJson(forged)!!.backend)
        assertEquals(ModelAvailability.UNUSABLE, OwnModel.fromJson(forged)!!.availability("10|1"))
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

    /** Positions and offsets come from the file: one near Long.MAX_VALUE is outside the file, never an overflow past the check. */
    @Test fun hugePositionsAreOutsideTheFile() {
        val src = ArrayByteSource(ByteArray(64))
        for (pos in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 2, Long.MAX_VALUE - 63, -1L, 60L)) {
            try { src.read(pos, 8); fail("read at $pos") } catch (_: LiteRtLmFormat) {}
        }
        assertEquals(8, src.read(56, 8).size)
        val dir = Files.createTempDirectory("litertlm").toFile()
        try {
            FileByteSource(File(dir, "f").apply { writeBytes(ByteArray(64)) }).use { f ->
                for (pos in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 2, 60L)) {
                    try { f.read(pos, 8); fail("read at $pos") } catch (_: LiteRtLmFormat) {}
                }
                assertEquals(8, f.read(56, 8).size)
            }
        } finally { dir.deleteRecursively() }
        // A decoder whose stamp buffer says its bytes are at an offset near Long.MAX_VALUE: unreadable, never the TPU.
        val decoder = FakeLiteRtLm.flatbuffer(FakeLiteRtLm.Obj(FakeLiteRtLm.U32(3), FakeLiteRtLm.Ref(FakeLiteRtLm.Vec(dispatchOps)), null, null,
            FakeLiteRtLm.Ref(FakeLiteRtLm.Vec(listOf(FakeLiteRtLm.Obj(), FakeLiteRtLm.Obj(null, FakeLiteRtLm.U64(Long.MAX_VALUE - 4), FakeLiteRtLm.U64(16))))), null,
            FakeLiteRtLm.Ref(FakeLiteRtLm.Vec(listOf(FakeLiteRtLm.Obj(FakeLiteRtLm.Ref(FakeLiteRtLm.Str("LiteRtStamp")), FakeLiteRtLm.U32(1)))))), ident = "TFL3")
        val r = check(FakeLiteRtLm.litertlm(listOf(tfl("tf_lite_prefill_decode", decoder), small("tf_lite_aux"), meta())))
        assertFalse(r.toString(), r.readable)
        assertNull(r.backend)
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

    /**
     * :brain's own check before a load (LoadCheck): whatever the assistant asks, a built-in model only from the file the app
     * checked by SHA-256 and only where it runs; your own file only as recorded after its check, unchanged since, and on
     * the TPU only once you allowed it.
     */
    @Test fun brainLoadsOnlyWhatTheAppChecked() {
        val fp = ModelFiles.fingerprint(3_113_545_589, 1_760_000_000_000)
        assertEquals("3113545589|1760000000000", fp)
        val fast = BrainModel.FAST
        val mark = "${fast.sha256}|$fp"
        assertNull(LoadCheck.refuse(fast, ModelBackend.TPU, fp, mark, null))
        assertNull(LoadCheck.refuse(fast, ModelBackend.TPU, fp, "$mark\n", null))
        // No mark, a mark for another file (changed since: size or time), another model's hash, or the wrong backend.
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(fast, ModelBackend.TPU, fp, null, null))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(fast, ModelBackend.TPU, ModelFiles.fingerprint(3_113_545_589, 1_760_000_000_001), mark, null))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(fast, ModelBackend.TPU, fp, "${BrainModel.SMARTER.sha256}|$fp", null))
        assertNotNull(LoadCheck.refuse(fast, ModelBackend.CPU, fp, mark, null))
        assertNotNull(LoadCheck.refuse(BrainModel.SMARTER, ModelBackend.TPU, fp, "${BrainModel.SMARTER.sha256}|$fp", null))
        assertNull(LoadCheck.refuse(BrainModel.UNCENSORED, ModelBackend.CPU, fp, "${BrainModel.UNCENSORED.sha256}|$fp", null))

        val own = BrainModel.OWN
        val ownFp = "3000000000|1"
        val tpu = OwnModel("mine", 3_000_000_000, sha, ownFp, check(e2bG5()))
        // A Tensor G5 build: never on the TPU until you allowed it, nor on the CPU at all.
        assertEquals("Your model file waits for you to allow the TPU in the Robot app › Model.",
            LoadCheck.refuse(own, ModelBackend.TPU, ownFp, null, tpu.toJson().toString()))
        assertNotNull(LoadCheck.refuse(own, ModelBackend.CPU, ownFp, null, tpu.toJson().toString()))
        val allowed = tpu.copy(tpuAllowed = true).toJson().toString()
        assertNull(LoadCheck.refuse(own, ModelBackend.TPU, ownFp, null, allowed))
        // Swapped or touched after the check (another size or time), or no record: checked again first.
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(own, ModelBackend.TPU, "3000000000|2", null, allowed))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(own, ModelBackend.TPU, ownFp, null, null))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(own, ModelBackend.TPU, ownFp, null, "garbage"))
        // A record claiming the TPU for a CPU file reads as the CPU.
        val forged = OwnModel("x", 10, sha, "10|1", check(e4bCpu())).toJson().put("tpuAllowed", true).toString()
        assertNotNull(LoadCheck.refuse(own, ModelBackend.TPU, "10|1", null, forged))
        assertNull(LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, forged))
        // A file Robot can't run: refused with the check's own reason.
        val bad = OwnModel("x", 10, sha, "10|1", check(ByteArray(10))).toJson().toString()
        for (b in ModelBackend.entries) assertNotNull(LoadCheck.refuse(own, b, "10|1", null, bad))
    }

    /**
     * 0.4.3: and only with the context Robot's prompts are made for with that file: 4,096 for a built-in model and for
     * your own file that states none or more; a short file's own (2,048 for MedGemma), never 4,096 KV on it, nor its
     * short context on another file. Under 2,048 nothing loads, whatever context is asked for.
     */
    @Test fun brainLoadsOnlyWithTheContextOfTheFileItChecked() {
        val fp = ModelFiles.fingerprint(3_113_545_589, 1_760_000_000_000)
        val mark = "${BrainModel.FAST.sha256}|$fp"
        assertNull(LoadCheck.refuse(BrainModel.FAST, ModelBackend.TPU, fp, mark, null, 4096))
        for (n in listOf(2048, 4095, 8192, 0, -1)) {
            assertEquals(LoadCheck.wrongContext(4096), LoadCheck.refuse(BrainModel.FAST, ModelBackend.TPU, fp, mark, null, n))
        }
        val smarter = "${BrainModel.SMARTER.sha256}|$fp"
        assertEquals(LoadCheck.wrongContext(4096), LoadCheck.refuse(BrainModel.SMARTER, ModelBackend.CPU, fp, smarter, null, 2048))

        val own = BrainModel.OWN
        val short = OwnModel("medgemma.litertlm", 10, sha, "10|1", check(e4bCpu(max = 2048))).toJson().toString()
        assertNull(LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, short, 2048))
        assertEquals(LoadCheck.wrongContext(2048), LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, short, 4096))
        assertEquals(LoadCheck.wrongContext(2048), LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, short, 2047))
        assertEquals(LoadCheck.wrongContext(2048), LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, short, 4095))
        // Changed since, or on the TPU: the same refusals as for any file.
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(own, ModelBackend.CPU, "10|2", null, short, 2048))
        assertNotNull(LoadCheck.refuse(own, ModelBackend.TPU, "10|1", null, short, 2048))
        val odd = OwnModel("x", 10, sha, "10|1", check(e4bCpu(max = 3000))).toJson().toString()
        assertNull(LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, odd, 3000))
        assertNotNull(LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, odd, 2048))
        // A long file never loads with a short context, nor a refused one with any.
        val long = OwnModel("x", 10, sha, "10|1", check(e4bCpu(max = 32_000))).toJson().toString()
        assertNull(LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, long, 4096))
        assertEquals(LoadCheck.wrongContext(4096), LoadCheck.refuse(own, ModelBackend.CPU, "10|1", null, long, 2048))
        val tiny = OwnModel("x", 10, sha, "10|1", check(e4bCpu(max = 1024))).toJson().toString()
        for (n in listOf(1024, 2048, 4096)) for (b in ModelBackend.entries) assertNotNull(LoadCheck.refuse(own, b, "10|1", null, tiny, n))
        // A short Tensor G5 build you allowed: the TPU, with its own context.
        val g5 = OwnModel("x", 10, sha, "10|1", check(FakeLiteRtLm.litertlm(listOf(
            tfl("tf_lite_prefill_decode", FakeLiteRtLm.tflite(dispatchOps, g5Stamp)), small("tf_lite_aux"), meta(max = 2048),
        ))), tpuAllowed = true).toJson().toString()
        assertNull(LoadCheck.refuse(own, ModelBackend.TPU, "10|1", null, g5, 2048))
        assertNotNull(LoadCheck.refuse(own, ModelBackend.TPU, "10|1", null, g5, 4096))
    }

    /** The one line under a model whose name says MedGemma (the screen only: never a reason to trust or allow anything). */
    @Test fun aMedGemmaNameGetsTheNoteAndNothingElseDoes() {
        for (n in listOf("medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm", "MedGemma 1.5 4B", "Med-Gemma.litertlm", "my_med_gemma.litertlm")) {
            assertEquals(n, ModelNotes.MEDICAL, ModelNotes.of(n))
        }
        for (n in listOf("gemma-4-E4B-it.litertlm", "medical.litertlm", "gemma-med.litertlm", "", null)) assertNull(n, ModelNotes.of(n))
        assertEquals("Not a medical device: check health information with a professional.", ModelNotes.MEDICAL)
        // No built-in model is one.
        assertTrue(BrainModel.entries.none { ModelNotes.medical(it.modelName) || ModelNotes.medical(it.file) })
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
