package app.heyrobot.core

import app.heyrobot.core.FakeGguf.Arr
import app.heyrobot.core.FakeGguf.Tensor
import app.heyrobot.core.FakeGguf.Typed
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
 * The GGUF content check (0.4.5, your own model file run by llama.cpp) on fake files shaped like unsloth's MedGemma 1.5 4B
 * and Qwen's Qwen3 1.7B, refusals, damaged and hostile headers, your own file's record, and :brain's load check.
 */
class GgufTest {
    private fun check(b: ByteArray, ram: Long = 0) = GgufCheck.check(ArrayByteSource(b), ram)
    private val gb16 = 15_300_000_000L

    @Test fun aMedgemmaLikeFileIsReadAndRunsOnTheCpu() {
        val r = check(FakeGguf.medgemma(), gb16)
        assertTrue(r.toString(), r.readable)
        assertEquals(3, r.version)
        assertEquals("gemma3", r.architecture)
        assertEquals("Medgemma-1.5-4B-It", r.name)
        assertEquals("4B", r.sizeLabel)
        assertEquals("other", r.licence)
        assertEquals("Q4_K_M", r.quantisation)
        assertEquals(131_072L, r.contextLength)
        assertEquals(34, r.blocks)
        // 34 layers × 4 KV heads × (256 + 256) × 2 bytes: about 570 MB for Robot's 4,096 tokens.
        assertEquals(34L * 4 * 512 * 2, r.kvBytesPerToken)
        assertEquals(256L * 64 + 2 * (256L * 256 + 256 * 128 + 256 + 256 * 512) + 256, r.parameters)
        assertEquals("llama", r.tokenizer)
        assertTrue(r.chatTemplate)
        assertFalse(r.templateThinking || r.templateThinkTags || r.thinks)
        assertEquals(FakeGguf.tensors().size, r.tensors)
        assertNull(r.unusable)
        val lines = r.lines()
        assertTrue(lines.toString(), lines[0].startsWith("GGUF file, version 3: “Medgemma-1.5-4B-It” (architecture gemma3"))
        assertTrue(lines.contains("Quantisation: Q4_K_M."))
        assertTrue(lines.contains("Context: 131,072 tokens (Robot uses 4,096)."))
        assertTrue(lines.any { it.startsWith("Runs on the CPU with llama.cpp") })
        assertTrue(lines.any { it.startsWith("Can see photos: no.") })
        assertTrue(lines.any { it.contains("Robot's text format") && it.contains("may not work") })
        assertTrue(lines.any { it.contains("Licence the file names: “other”") })
        assertFalse(lines.any { it.contains("think") })
    }

    @Test fun aQwen3FileCanThinkAndRobotSaysSo() {
        val r = check(FakeGguf.qwen3(), gb16)
        assertNull(r.unusable)
        assertEquals("qwen3", r.architecture)
        assertEquals("Q8_0", r.quantisation)
        assertEquals(28L * 8 * 256 * 2, r.kvBytesPerToken)
        assertTrue(r.templateThinking && r.templateThinkTags && r.thinks)
        assertTrue(r.lines().any { it.contains("Let the model think first") })
        // A Qwen3 file whose template doesn't take enable_thinking still thinks (and takes /no_think).
        val plain = check(FakeGguf.qwen3(template = FakeGguf.GEMMA3_TEMPLATE))
        assertFalse(plain.templateThinking)
        assertTrue(plain.thinks)
    }

    @Test fun whatRobotCantRunIsRefusedWithAReason() {
        fun why(b: ByteArray, ram: Long = 0) = check(b, ram).unusable
        assertEquals("It isn't a GGUF model file.", why("not a model, just some text that goes on and on".toByteArray()))
        assertEquals("It isn't a GGUF model file.", why(FakeGguf.gguf(emptyList(), emptyList(), magic = "GGML")))
        assertTrue(why(FakeGguf.gguf(emptyList(), emptyList(), version = 1))!!.contains("version 1"))
        assertTrue(why(FakeGguf.gguf(emptyList(), emptyList(), version = 4))!!.contains("version 4"))
        // A big-endian file reads its version 3 as 0x03000000.
        assertTrue(why(FakeGguf.gguf(emptyList(), emptyList(), version = 0x03000000))!!.contains("big-endian"))
        assertEquals("It was trained for only 2047 tokens of context; Robot needs at least 2048 to chat (4096 for actions).",
            why(FakeGguf.medgemma(context = 2047)))
        assertNull(why(FakeGguf.medgemma(context = 2048)))
        assertNull(why(FakeGguf.medgemma(context = 4096)))
        assertTrue(why(FakeGguf.medgemma(template = null))!!.contains("no chat template"))
        assertTrue(why(FakeGguf.medgemma(template = "  "))!!.contains("no chat template"))
        assertTrue(why(FakeGguf.medgemma(tokenizer = null))!!.contains("no tokenizer"))
        assertEquals("Its architecture “gemma9” isn't one that llama.cpp b11200 knows.", why(FakeGguf.medgemma(arch = "gemma9")))
        for (a in listOf("clip", "bert", "t5encoder", "qwen3tts", "eagle3", "llada")) assertTrue(a, why(FakeGguf.medgemma(arch = a))!!.contains("doesn't write answers"))
        assertTrue(why(FakeGguf.gguf(listOf("tokenizer.ggml.model" to "llama"), FakeGguf.tensors()))!!.contains("general.architecture"))
        // One part of a split model (llama.cpp would look for the other parts by a name Robot never gives the file).
        val part = why(FakeGguf.medgemma(extra = listOf("split.no" to Typed(2, 0), "split.count" to Typed(2, 3))))!!
        assertTrue(part, part.startsWith("It is one part of a model split into 3 files") && part.contains("llama-gguf-split --merge"))
        assertNull(why(FakeGguf.medgemma(extra = listOf("split.no" to Typed(2, 0), "split.count" to Typed(2, 1)))))
        // Without a context length (general.architecture names the key).
        val noContext = FakeGguf.gguf(listOf("general.architecture" to "llama", "tokenizer.ggml.model" to "llama", "tokenizer.chat_template" to "x"), FakeGguf.tensors())
        assertEquals("It doesn't say its context length (llama.context_length).", why(noContext))
    }

    /** Memory: the file, the cache for 4,096 tokens and work space, beside the voice (1.3 GB) and Android (4 GB). */
    @Test fun aFileTooBigForThisPhonesMemoryIsRefused() {
        val r = check(FakeGguf.medgemma(), gb16)
        val need = r.fileBytes + 34L * 4 * 512 * 2 * 4096 + GgufCheck.WORK_BYTES
        assertEquals(need, r.memoryBytes)
        assertNull(r.memoryProblem)
        // A phone with too little: refused, with the numbers.
        val small = check(FakeGguf.medgemma(), need + GgufCheck.VOICE_BYTES + GgufCheck.SYSTEM_BYTES - 1)
        assertTrue(small.unusable!!, small.unusable!!.startsWith("It needs about 0.97 GB of memory"))
        assertNull(check(FakeGguf.medgemma(), need + GgufCheck.VOICE_BYTES + GgufCheck.SYSTEM_BYTES).memoryProblem)
        // Unknown memory: no memory check.
        assertNull(check(FakeGguf.medgemma(), 0).memoryProblem)
        // Real numbers: a 27B Q4_K_M (16.5 GB) can't run on the Pixel 10 Pro; MedGemma's 2.49 GB file can.
        assertNotNull(r.copy(fileBytes = 16_500_000_000).memoryProblem)
        assertNull(r.copy(fileBytes = 2_489_894_976).memoryProblem)
        assertEquals("3.46 GB", ModelLogic.gb(r.copy(fileBytes = 2_489_894_976).memoryBytes))
    }

    @Test fun cutShortOrMisplacedTensorsAreUnreadable() {
        val good = FakeGguf.medgemma()
        assertTrue(check(good).readable)
        for (n in listOf(4, 8, 23, 24, 100, 300, good.size / 2, good.size - 1)) assertFalse("cut at $n", check(good.copyOf(n)).readable)
        val cut = check(FakeGguf.gguf(listOf("general.architecture" to "llama"), FakeGguf.tensors(), cutData = 1))
        assertFalse(cut.readable)
        assertTrue(cut.problem!!, cut.problem!!.contains("cut short"))
        // Tensors in another order than their data, or overlapping.
        val t = FakeGguf.tensors()
        val moved = t.mapIndexed { i, x -> if (i == 1) x.copy(offset = 0) else x }
        assertFalse(check(FakeGguf.gguf(listOf("general.architecture" to "llama"), moved)).readable)
        // A row that doesn't fill its blocks, an unknown type, more than four dimensions, a name too long, the same name twice.
        fun readable(x: Tensor) = check(FakeGguf.gguf(listOf("general.architecture" to "llama"), listOf(x))).readable
        assertFalse(readable(Tensor("a", listOf(100, 2), FakeGguf.Q8_0)))
        assertFalse(readable(Tensor("a", listOf(32), 31)))
        assertFalse(readable(Tensor("a", listOf(32), 99)))
        assertFalse(readable(Tensor("a", listOf(1, 1, 1, 1, 32))))
        assertFalse(readable(Tensor("x".repeat(64), listOf(32))))
        assertTrue(readable(Tensor("x".repeat(63), listOf(32))))
        assertFalse(check(FakeGguf.gguf(listOf("general.architecture" to "llama"), listOf(Tensor("a", listOf(32)), Tensor("a", listOf(32))))).readable)
        // A file without tensors is read (it simply has nothing to run: no tokenizer, no template).
        assertTrue(check(FakeGguf.gguf(listOf("general.architecture" to "llama"), emptyList())).readable)
    }

    @Test fun hostileHeadersEndInAReportNeverAnOverflowOrALargeRead() {
        fun unreadable(b: ByteArray) = assertFalse(check(b).readable)
        // Counts beyond the caps, and near Long.MAX_VALUE.
        unreadable(FakeGguf.gguf(emptyList(), emptyList(), tensorCount = GgufCheck.MAX_TENSORS + 1L))
        unreadable(FakeGguf.gguf(emptyList(), emptyList(), kvCount = GgufCheck.MAX_KV + 1L))
        unreadable(FakeGguf.gguf(emptyList(), emptyList(), tensorCount = -1))
        unreadable(FakeGguf.gguf(emptyList(), emptyList(), kvCount = Long.MAX_VALUE))
        // More entries promised than there are.
        unreadable(FakeGguf.gguf(listOf("general.architecture" to "llama"), emptyList(), kvCount = 3))
        // A string whose length is near Long.MAX_VALUE, or just past the cap; a key too long; an empty key; a key twice.
        val head = FakeGguf.Out().bytes("GGUF".toByteArray()).u32(3).u64(0).u64(1)
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).u64(Long.MAX_VALUE - 3).bytes("general.name".toByteArray()).toByteArray())
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).str("general.name").u32(8).u64(GgufCheck.MAX_STRING + 1L).bytes(ByteArray(64)).toByteArray())
        unreadable(FakeGguf.gguf(listOf("k".repeat(GgufCheck.MAX_KEY + 1) to 1), emptyList()))
        unreadable(FakeGguf.gguf(listOf("" to 1), emptyList()))
        unreadable(FakeGguf.gguf(listOf("general.name" to "a", "general.name" to "b"), emptyList()))
        // An array longer than the cap, of an unknown type, or of arrays; a value of an unknown type.
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).str("tokenizer.ggml.tokens").u32(9).u32(8).u64(GgufCheck.MAX_ARRAY + 1L).toByteArray())
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).str("x").u32(9).u32(13).u64(1).u32(0).toByteArray())
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).str("x").u32(9).u32(9).u64(1).u32(4).u64(1).u32(0).toByteArray())
        unreadable(FakeGguf.Out().bytes(head.toByteArray()).str("x").u32(13).u32(0).toByteArray())
        // Alignment: a u32 power of two, as llama.cpp requires.
        unreadable(FakeGguf.gguf(listOf("general.alignment" to 24), FakeGguf.tensors()))
        unreadable(FakeGguf.gguf(listOf("general.alignment" to 0), FakeGguf.tensors()))
        unreadable(FakeGguf.gguf(listOf("general.alignment" to Typed(10, 64L)), FakeGguf.tensors()))
        unreadable(FakeGguf.gguf(listOf("general.alignment" to "64"), FakeGguf.tensors()))
        unreadable(FakeGguf.gguf(listOf("general.alignment" to Arr(4, listOf(64))), FakeGguf.tensors()))
        assertTrue(check(FakeGguf.gguf(listOf("general.alignment" to 64), FakeGguf.tensors(), alignment = 64)).readable)
        // Dimensions whose product overflows, or negative.
        unreadable(FakeGguf.gguf(listOf("general.architecture" to "llama"), listOf(Tensor("a", listOf(1L shl 40, 1L shl 40)))))
        unreadable(FakeGguf.gguf(listOf("general.architecture" to "llama"), listOf(Tensor("a", listOf(-32L)))))
        // A tensor offset near Long.MAX_VALUE.
        unreadable(FakeGguf.gguf(listOf("general.architecture" to "llama"), listOf(Tensor("a", listOf(32), offset = Long.MAX_VALUE - 8))))
    }

    /** The file is untrusted: random damage anywhere ends in a report, never an exception or a hang. */
    @Test fun damagedFilesNeverThrow() {
        val rnd = Random(44)
        for (base in listOf(FakeGguf.medgemma(), FakeGguf.qwen3(), FakeGguf.medgemma(tokens = 2000))) {
            repeat(500) {
                val b = base.copyOf(if (rnd.nextInt(5) == 0) rnd.nextInt(base.size + 1) else base.size)
                repeat(1 + rnd.nextInt(6)) { if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                val r = check(b, gb16)
                // Whatever it found, a report that says it can run says why it is safe to try: read to the end, a known chat model.
                if (r.unusable == null) assertTrue(r.readable && r.architecture in GgufCheck.ARCHITECTURES && r.chatTemplate && r.contextLength >= 2048 &&
                    r.contextTokens in 2048..4096)
            }
        }
    }

    /** A real vocabulary (262,144 tokens, as Gemma 3's) is skipped string by string, quickly, and never kept. */
    @Test fun aLargeVocabularyIsSkippedQuickly() {
        val big = FakeGguf.medgemma(tokens = 262_144)
        val t0 = System.nanoTime()
        val r = check(big)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNull(r.unusable)
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun aRealFileOnDiskIsReadTheSameWay() {
        val dir = Files.createTempDirectory("gguf").toFile()
        try {
            val f = File(dir, "renamed.litertlm").apply { writeBytes(FakeGguf.qwen3()) }
            val r = GgufCheck.check(f, gb16)
            assertNull(r.unusable)
            assertEquals(f.length(), r.fileBytes)
            assertFalse(GgufCheck.check(File(dir, "missing.gguf")).readable)
            // :brain reads it through the file it has open (0.4.5): the same report, and no read past its end.
            java.io.RandomAccessFile(f, "r").use { raf ->
                val src = ChannelByteSource(raf.channel)
                assertEquals(r, GgufCheck.check(src, gb16))
                assertEquals(f.length(), src.size)
                assertEquals("GGUF", String(src.read(0, 4), Charsets.ISO_8859_1))
                for ((pos, n) in listOf(-1L to 1, 0L to -1, f.length() to 1, f.length() - 1 to 2, Long.MAX_VALUE to 1)) {
                    assertTrue("$pos $n", runCatching { src.read(pos, n) }.exceptionOrNull() is LiteRtLmFormat)
                }
                // Cut short after it was opened: a report that it can't be read, never a short header.
                java.io.RandomAccessFile(f, "rw").use { it.setLength(100) }
                assertFalse(GgufCheck.check(src, gb16).readable)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun reportsRoundTripAndAreCapped() {
        for (r in listOf(check(FakeGguf.medgemma(), gb16), check(FakeGguf.qwen3()), check(ByteArray(3)), check(FakeGguf.medgemma(context = 1024)))) {
            assertEquals(r, GgufReport.fromJson(r.toJson()))
        }
        assertNull(GgufReport.fromJson(null))
        assertNull(GgufReport.fromJson(org.json.JSONObject("{}")))
        // Text from the file is shown printable and short, even from a hand-edited record.
        val o = check(FakeGguf.medgemma()).toJson().put("name", "a\u202Eb" + "x".repeat(500)).put("architecture", "\u0000gemma3")
        val back = GgufReport.fromJson(o)!!
        assertEquals("ab" + "x".repeat(GgufCheck.MAX_SHOWN - 2), back.name)
        assertEquals("gemma3", back.architecture)
        // And from the file itself.
        val odd = check(FakeGguf.gguf(listOf("general.architecture" to "gemma3", "general.name" to "\u202Eevil\u0007"), emptyList()))
        assertEquals("evil", odd.name)
    }

    @Test fun theFileKindIsReadFromItsFirstBytes() {
        assertEquals(ModelFormat.GGUF, ModelFormat.sniff(FakeGguf.qwen3()))
        assertEquals(ModelFormat.LITERT_LM, ModelFormat.sniff("LITERTLM\u0001\u0000".toByteArray()))
        assertNull(ModelFormat.sniff("PK\u0003\u0004".toByteArray()))
        assertNull(ModelFormat.sniff(ByteArray(0)))
        assertEquals(ModelFormat.GGUF, ModelFormat.of("gguf"))
        assertNull(ModelFormat.of("onnx"))
        assertEquals("own_model.gguf", ModelFiles.ownFile(ModelFormat.GGUF))
        assertEquals("own_model.litertlm", ModelFiles.ownFile(ModelFormat.LITERT_LM))
        // No cache for a GGUF file: llama.cpp maps it as it is.
        assertEquals(2_000_000_000L + BrainModel.HEADROOM, ModelFiles.needBytes(BrainModel.OWN, 2_000_000_000L, gguf = true))
        assertEquals(2_000_000_000L + 1_500_000_000L + BrainModel.HEADROOM, ModelFiles.needBytes(BrainModel.OWN, 2_000_000_000L))
    }

    // ------------------------------------------------------------------ your own file's record, and :brain's check

    private val sha = "cd".repeat(32)
    private fun own(b: ByteArray, fp: String = "2489894976|1", ram: Long = gb16) =
        OwnModel("medgemma-1.5-4b-it-Q4_K_M.gguf", 2_489_894_976, sha, fp, OwnModel.NOT_LITERT_LM, gguf = check(b, ram))

    @Test fun aGgufRecordRunsOnTheCpuWhenThisBuildHasLlamaCpp() {
        val m = own(FakeGguf.medgemma())
        assertEquals(ModelFormat.GGUF, m.format)
        assertEquals(ModelBackend.CPU, m.runsOn)
        assertEquals(ModelBackend.CPU, m.backend)
        assertFalse(m.tensorG5Build)
        assertNull(m.unusable)
        assertEquals(ModelAvailability.READY, m.availability("2489894976|1"))
        assertEquals(ModelAvailability.UNUSABLE, m.availability("2489894976|1", ggufEngine = false))
        assertEquals(ModelAvailability.NOT_CHECKED, m.availability("2489894976|2"))
        assertEquals(ModelAvailability.NOT_DOWNLOADED, m.availability(null))
        assertEquals(m.gguf!!.lines(), m.lines())
        assertEquals(4096, m.contextTokens)
        assertFalse(m.shortContext || m.vision)
        val bad = own(FakeGguf.medgemma(context = 1024))
        assertNull(bad.backend)
        assertEquals(ModelAvailability.UNUSABLE, bad.availability("2489894976|1"))
        assertTrue(bad.unusable!!.contains("1024"))
        assertEquals(0, bad.contextTokens)
    }

    @Test fun aGgufRecordRoundTripsAndNeverGetsTheTpu() {
        val m = own(FakeGguf.qwen3())
        assertEquals(m, OwnModel.fromJson(m.toJson().toString()))
        // A record that claims the TPU for a GGUF file: read as not allowed, and it runs on the CPU.
        val forged = m.toJson().put("tpuAllowed", true).toString()
        assertFalse(OwnModel.fromJson(forged)!!.tpuAllowed)
        assertEquals(ModelBackend.CPU, OwnModel.fromJson(forged)!!.backend)
        // A damaged GGUF entry makes the whole record unreadable (checked again), never a LiteRT-LM record.
        assertNull(OwnModel.fromJson(m.toJson().put("gguf", "nope").toString()))
        // 0.4.1's records (no "gguf") are LiteRT-LM files, as before.
        val litert = OwnModel.fromJson(m.toJson().apply { remove("gguf") }.toString())!!
        assertEquals(ModelFormat.LITERT_LM, litert.format)
        assertNull(litert.backend)
    }

    /**
     * :brain's own check before a load (LoadCheck), 0.4.5: a GGUF file only as recorded after its check, unchanged since, on
     * the CPU, and only by llama.cpp (the request's kind of file must be the record's); a built-in model never as GGUF.
     */
    @Test fun brainLoadsAGgufFileOnlyAsTheAppCheckedIt() {
        val fp = "2489894976|1"
        val rec = own(FakeGguf.medgemma(), fp).toJson().toString()
        val o = BrainModel.OWN
        val gguf = ModelFormat.GGUF
        assertNull(LoadCheck.refuse(o, ModelBackend.CPU, fp, null, rec, format = gguf))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(o, ModelBackend.CPU, fp, null, rec, format = ModelFormat.LITERT_LM))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(o, ModelBackend.CPU, "2489894976|2", null, rec, format = gguf))
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(o, ModelBackend.CPU, fp, null, null, format = gguf))
        assertEquals("Your model file runs on the CPU, not the TPU.", LoadCheck.refuse(o, ModelBackend.TPU, fp, null, rec, format = gguf))
        // Only with the context Robot's prompts are made for with it: 4,096 for a file trained for more.
        assertEquals(LoadCheck.wrongContext(4096), LoadCheck.refuse(o, ModelBackend.CPU, fp, null, rec, 2048, gguf))
        assertEquals(LoadCheck.wrongContext(4096), LoadCheck.refuse(o, ModelBackend.CPU, fp, null, rec, 8192, gguf))
        // A LiteRT-LM record asked for as GGUF: checked again first.
        val litert = OwnModel("x", 10, sha, fp, LiteRtLmReport(readable = true, decoder = true, modelType = "gemma4")).toJson().toString()
        assertEquals(LoadCheck.CHANGED, LoadCheck.refuse(o, ModelBackend.CPU, fp, null, litert, format = gguf))
        assertNull(LoadCheck.refuse(o, ModelBackend.CPU, fp, null, litert, format = ModelFormat.LITERT_LM))
        // A GGUF file trained for 2,048 to 4,095 tokens: only with exactly its own context (chat only, as a short .litertlm).
        val short = own(FakeGguf.medgemma(context = 3000), fp).toJson().toString()
        assertNull(LoadCheck.refuse(o, ModelBackend.CPU, fp, null, short, 3000, gguf))
        for (n in listOf(2048, 2999, 3001, 4096)) assertEquals(LoadCheck.wrongContext(3000), LoadCheck.refuse(o, ModelBackend.CPU, fp, null, short, n, gguf))
        // A GGUF file Robot can't run: refused with the check's own reason.
        val tiny = own(FakeGguf.medgemma(context = 1024), fp).toJson().toString()
        for (n in listOf(1024, 2048, 4096)) assertTrue(LoadCheck.refuse(o, ModelBackend.CPU, fp, null, tiny, n, gguf)!!.contains("1024"))
        // Built-in models are LiteRT-LM files only.
        val mark = "${BrainModel.FAST.sha256}|$fp"
        assertNotNull(LoadCheck.refuse(BrainModel.FAST, ModelBackend.TPU, fp, mark, null, format = gguf))
        assertNull(LoadCheck.refuse(BrainModel.FAST, ModelBackend.TPU, fp, mark, null, format = ModelFormat.LITERT_LM))
    }

    /**
     * 0.4.5, as a short .litertlm (0.4.3): a GGUF file trained for 2,048 to 4,095 tokens runs with exactly its own context
     * as chat only; the screen says so, its memory is for that context, and its plan asks :brain for it.
     */
    @Test fun aShortContextGgufFileRunsAsChatOnlyWithItsOwnContext() {
        val r = check(FakeGguf.medgemma(context = 2048), gb16)
        assertNull(r.unusable)
        assertTrue(r.shortContext)
        assertEquals(2048, r.contextTokens)
        assertEquals(r.fileBytes + 34L * 4 * 512 * 2 * 2048 + GgufCheck.WORK_BYTES, r.memoryBytes)
        val lines = r.lines()
        assertTrue(lines.toString(), lines.contains("Short context (2,048 tokens): chat only — no actions, shorter memory."))
        assertFalse(lines.any { it.startsWith("Context:") || it.contains("Robot's text format") })
        assertTrue(lines.any { it.contains("cache for Robot's 2,048 tokens") })
        val m = own(FakeGguf.medgemma(context = 3500))
        assertEquals(3500, m.contextTokens)
        assertTrue(m.shortContext)
        assertFalse(m.vision)
        assertEquals(ModelAvailability.READY, m.availability("2489894976|1"))
        assertTrue(LoadPlan(BrainModel.OWN, "/x/own_model.gguf", ModelBackend.CPU, m.contextTokens, "1|1", m.vision, m.format).chatOnly)
        // At 4,096 or more: Robot's full context, actions in text format.
        for (c in listOf(4096L, 40_960L, 131_072L, Long.MAX_VALUE / 2)) {
            val big = check(FakeGguf.medgemma(context = c), gb16)
            assertEquals(4096, big.contextTokens)
            assertFalse(big.shortContext)
        }
        assertTrue(check(FakeGguf.medgemma(context = 131_072), gb16).lines().contains("Context: 131,072 tokens (Robot uses 4,096)."))
    }

    @Test fun theArchitectureListIsLlamaCppsAtThePinnedTag() {
        assertEquals("b11200", GgufCheck.LLAMA_TAG)
        assertEquals(152, GgufCheck.ARCHITECTURES.size)
        assertTrue(GgufCheck.ARCHITECTURES.containsAll(listOf("gemma3", "qwen3", "llama", "phi3", "minicpm", "smollm3", "lfm2", "gpt-oss")))
        assertTrue(GgufCheck.ARCHITECTURES.containsAll(GgufCheck.NOT_CHAT))
        assertEquals("Q4_K_M", GgufCheck.FILE_TYPES[15])
        assertEquals("Q8_0", GgufCheck.FILE_TYPES[7])
        // An array of per-layer head counts counts by its largest.
        val perLayer = check(FakeGguf.gguf(listOf("general.architecture" to "llama", "llama.block_count" to 2, "llama.attention.head_count" to 8,
            "llama.attention.head_count_kv" to Arr(5, listOf(2, 4)), "llama.embedding_length" to 512), emptyList()))
        assertEquals(2L * 4 * (64 + 64) * 2, perLayer.kvBytesPerToken)
    }
}
