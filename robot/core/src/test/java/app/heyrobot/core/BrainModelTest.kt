package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model catalogue (0.4.1) and which model the assistant loads. */
class BrainModelTest {
    private val sha = Regex("^[0-9a-f]{64}$")
    private val commit = Regex("^[0-9a-f]{40}$")

    /** Fast is 0.4.0's model, byte for byte: the same file, URL, size, hash and TPU. */
    @Test fun fastIsTheModelOfEarlierVersions() {
        val f = BrainModel.FAST
        assertEquals("gemma-4-E2B-it_Google_Tensor_G5.litertlm", f.file)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it_Google_Tensor_G5.litertlm", f.url)
        assertEquals(3_113_545_589L, f.bytes)
        assertEquals("af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc", f.sha256)
        assertEquals(ModelBackend.TPU, f.backend)
        assertEquals("npu", f.backend.id)
        assertEquals(4096, f.contextTokens)
        assertEquals("Gemma 4 E2B (Tensor G5)", f.modelName)
    }

    /**
     * 0.4.7: the general E2B, pinned as the Hugging Face API showed it (b3ca0d2f, Apache-2.0, not gated): the same repository
     * and commit as Fast, the file LiteRT-LM runs on the CPU or the GPU, with figures that fit an 8 GB phone.
     */
    @Test fun theGeneralE2bIsPinned() {
        val g = BrainModel.GENERAL
        assertEquals("e2b", g.id)
        assertEquals("Fast", g.title)
        assertEquals("Gemma 4 E2B", g.modelName)
        assertEquals("gemma-4-E2B-it.litertlm", g.file)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm", g.url)
        assertEquals(2_588_147_712L, g.bytes)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", g.sha256)
        assertEquals(BrainModel.FAST.repo, g.repo)
        assertEquals(BrainModel.FAST.commit, g.commit)
        assertEquals(ModelBackend.CPU, g.backend)
        assertTrue(g.gpu && g.fromGoogle)
        assertEquals("Apache-2.0", g.licence)
        assertEquals(4096, g.contextTokens)
        assertTrue(g.memoryBytes in 2_000_000_000L..3_000_000_000L)
        assertTrue(g.cacheBytes in ModelFiles.GPU_CACHE_BYTES..g.bytes)
        assertEquals("model_verified_e2b.txt", ModelFiles.verifiedMark(g))
        assertEquals("litert_cache/e2b", ModelFiles.cacheDir(g, ModelBackend.GPU))
        assertEquals("litert_cache/e2b", ModelFiles.cacheDir(g, ModelBackend.CPU))
        assertNull(ModelFiles.cacheDir(BrainModel.FAST))
        assertEquals(ModelFiles.GPU_CACHE_BYTES, ModelFiles.cacheBytes(g, ModelBackend.GPU))
        assertEquals(g.cacheBytes, ModelFiles.cacheBytes(g, ModelBackend.CPU))
        assertEquals(0L, ModelFiles.cacheBytes(BrainModel.FAST, ModelBackend.TPU))
        // Fast's name and model card text never say "this phone's TPU" (a Pixel 9a has none Robot can use).
        assertFalse(g.about.contains("this phone's TPU") || g.speed.contains("this phone's TPU") || BrainModel.OWN.speed.contains("this phone's TPU"))
        assertEquals(listOf(BrainModel.GENERAL), BrainModel.entries.filter { it.gpu })
    }

    /** 0.8, beta: Google's Tensor G6 build, pinned at Fast's commit, by its size and SHA-256. */
    @Test fun theTensorG6BuildIsPinned() {
        val g6 = BrainModel.FAST_G6
        assertEquals("e2b_tpu_g6", g6.id)
        assertEquals("gemma-4-E2B-it_Google_Tensor_G6.litertlm", g6.file)
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/" +
            "gemma-4-E2B-it_Google_Tensor_G6.litertlm", g6.url)
        assertEquals(3_313_938_293L, g6.bytes)
        assertEquals("f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7", g6.sha256)
        assertEquals(BrainModel.FAST.repo, g6.repo)
        assertEquals(BrainModel.FAST.commit, g6.commit)
        assertEquals(ModelBackend.TPU, g6.backend)
        assertEquals(4096, g6.contextTokens)
        assertEquals(0L, g6.cacheBytes)
        assertEquals("Apache-2.0", g6.licence)
        assertTrue(g6.fromGoogle && g6.beta && g6.isFast && !g6.gpu)
        assertFalse(BrainModel.FAST.beta)
        assertTrue(g6.title.contains("beta") && g6.speed.startsWith("Beta") && g6.speed.contains("not measured"))
        assertEquals("model_verified_e2b_tpu_g6.txt", ModelFiles.verifiedMark(g6))
        assertNull(ModelFiles.cacheDir(g6))
        assertEquals(BrainModel.FAST_G6, BrainModel.of("e2b_tpu_g6"))
    }

    @Test fun theDefaultStaysFast() {
        assertEquals(BrainModel.FAST, BrainModel.DEFAULT)
        assertEquals(BrainModel.FAST, RobotSettings().brainModelChoice)
        assertEquals(BrainModel.FAST.id, RobotSettings().brainModel)
        for (id in listOf(null, "", "nope", "E4B_CPU", "horizon-edge-4b")) assertEquals(id, BrainModel.FAST, BrainModel.of(id))
        assertNull(BrainModel.byId("nope"))
    }

    @Test fun theE4bEntriesArePinnedAsTheResearchFoundThem() {
        val s = BrainModel.SMARTER
        assertEquals("https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm", s.url)
        assertEquals(3_659_530_240L, s.bytes)
        assertEquals("0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", s.sha256)
        assertTrue(s.fromGoogle)
        val u = BrainModel.UNCENSORED
        assertEquals("https://huggingface.co/olekk/gemma-4-E4B-it-abliterated-litert-lm/resolve/a4eecccd3b0ba1777660180cda60a396eedcb8aa/" +
            "gemma-4-E4B-it-abliterated.litertlm", u.url)
        assertEquals("gemma-4-E4B-it-abliterated.litertlm", u.file)
        assertEquals(3_659_530_240L, u.bytes)
        assertEquals("f5a6625e0368e853743c91b39fa6ac6a8add1fc4825b18f98f518966610f2a88", u.sha256)
        assertFalse(u.fromGoogle)
        assertTrue(u.publisher.contains("not from Google"))
        assertTrue(u.about.contains("refusals removed") && u.about.contains("still ask you first"))
        for (m in listOf(s, u)) {
            assertEquals(ModelBackend.CPU, m.backend)
            assertEquals("Apache-2.0", m.licence)
            assertTrue(m.cacheBytes > 2_500_000_000L)
            assertTrue(m.memoryBytes in 4_000_000_000L..4_500_000_000L)
        }
    }

    /** Every download is pinned: HTTPS to Hugging Face, the repo at a full commit, the file's own name, a SHA-256. */
    @Test fun everyDownloadIsPinnedByCommitAndHash() {
        for (m in BrainModel.entries.filter { it.builtIn }) {
            assertEquals(m.name, "https://huggingface.co/${m.repo}/resolve/${m.commit}/${m.file}", m.url)
            assertTrue(m.name, commit.matches(m.commit!!))
            assertTrue(m.name, sha.matches(m.sha256!!))
            assertTrue(m.name, m.bytes > 1_000_000_000L)
        }
        assertEquals(listOf(BrainModel.FAST, BrainModel.FAST_G6, BrainModel.GENERAL, BrainModel.SMARTER, BrainModel.UNCENSORED), BrainModel.entries.filter { it.builtIn })
        val own = BrainModel.OWN
        assertNull(own.url)
        assertNull(own.sha256)
        assertEquals(-1L, own.bytes)
        assertEquals(BrainModel.entries.size, BrainModel.entries.map { it.id }.toSet().size)
        assertEquals(listOf("e2b_tpu", "e2b_tpu_g6", "e2b", "e4b_cpu", "e4b_cpu_uncensored", "own"), BrainModel.entries.map { it.id })
    }

    /**
     * No Gemma 4 E4B build for the Tensor TPU exists: only Fast's E2B builds are on the TPU (the Tensor G5's, 0.8: the
     * Tensor G6's), each for its own chip, and nothing from horizon-edge is offered.
     */
    @Test fun noE4bOnTheTpuAndNoHorizonEdge() {
        assertEquals(listOf(BrainModel.FAST, BrainModel.FAST_G6), BrainModel.entries.filter { it.backend == ModelBackend.TPU })
        assertEquals(listOf(Chip.TENSOR_G5, Chip.TENSOR_G6), BrainModel.entries.mapNotNull { it.tpuChip })
        assertEquals(BrainModel.entries.filter { it.backend == ModelBackend.TPU }, BrainModel.entries.filter { it.tpuChip != null })
        for (m in BrainModel.entries) {
            val all = listOfNotNull(m.title, m.modelName, m.file, m.url, m.repo, m.publisher, m.about, m.speed).joinToString(" ").lowercase()
            assertFalse(m.name, all.contains("horizon") || all.contains("safecircle"))
            if (all.contains("e4b")) assertEquals(m.name, ModelBackend.CPU, m.backend)
            assertFalse(m.name, all.contains("owner"))
        }
    }

    /** The prompt budget is 4,096 tokens for every model (ContextWindow), and each entry asks :brain for exactly that. */
    @Test fun everyModelUsesRobotsContext() {
        for (m in BrainModel.entries) assertEquals(m.name, ContextWindow.MAX_TOKENS, m.contextTokens)
    }

    @Test fun honestNumbersAreShown() {
        assertTrue(BrainModel.FAST.speed.contains("17 tokens"))
        assertTrue(BrainModel.SMARTER.speed.contains("8 tokens") && BrainModel.SMARTER.speed.contains("21 s") && BrainModel.SMARTER.speed.contains("4 GB"))
        assertTrue(BrainModel.SMARTER.speed.contains("battery"))
    }

    // ------------------------------------------------------------------ which model loads

    private val ready = ModelAvailability.READY

    @Test fun theChosenModelLoadsWhenReady() {
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST), ModelChoice.decide(BrainModel.FAST, ready, ready, false))
        assertEquals(ModelChoice.Decision.Load(BrainModel.SMARTER), ModelChoice.decide(BrainModel.SMARTER, ready, ready, false))
        // Fast alone is tried every time, as before 0.4.1.
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST), ModelChoice.decide(BrainModel.FAST, ready, ready, true))
        val none = ModelChoice.decide(BrainModel.FAST, ModelAvailability.NOT_DOWNLOADED, ModelAvailability.NOT_DOWNLOADED, false) as ModelChoice.Decision.NoLoad
        assertEquals(ModelChoice.NO_MODEL, none.problem)
        assertTrue(none.openApp)
    }

    /** A chosen model that isn't ready, or failed to load earlier, falls back to Fast with a message that says why. */
    @Test fun anythingElseFallsBackToFast() {
        val cases = mapOf(
            ModelAvailability.NOT_DOWNLOADED to FallbackWhy.NOT_DOWNLOADED, ModelAvailability.NOT_CHECKED to FallbackWhy.NOT_CHECKED,
            ModelAvailability.NEEDS_TPU_OK to FallbackWhy.NEEDS_TPU_OK, ModelAvailability.UNUSABLE to FallbackWhy.UNUSABLE,
        )
        for ((state, why) in cases) {
            assertEquals(state.name, ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.OWN, why), ModelChoice.decide(BrainModel.OWN, state, ready, false))
        }
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.SMARTER, FallbackWhy.FAILED_EARLIER),
            ModelChoice.decide(BrainModel.SMARTER, ready, ready, chosenFailedBefore = true))
        // Neither: nothing loads, and the message names both.
        val none = ModelChoice.decide(BrainModel.UNCENSORED, ModelAvailability.NOT_DOWNLOADED, ModelAvailability.NOT_DOWNLOADED, false) as ModelChoice.Decision.NoLoad
        assertEquals("“Uncensored, slower” isn't on this phone yet, and Fast isn't on this phone either. Open the Robot app › Model.", none.problem)
        assertTrue(none.openApp)
    }

    @Test fun aFailedLoadFallsBackToFastOnce() {
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.SMARTER, FallbackWhy.LOAD_FAILED),
            ModelChoice.afterFailure(BrainModel.SMARTER, LoadFailure.ENGINE, ready))
        // Fast itself failing doesn't loop.
        val fast = ModelChoice.afterFailure(BrainModel.FAST, LoadFailure.ENGINE, ready, "The model didn't load (LiteRtLmJniException).") as ModelChoice.Decision.NoLoad
        assertEquals("The model didn't load (LiteRtLmJniException).", fast.problem)
        assertFalse(fast.openApp)
        // The last engine still stopping is no reason to give up on the model, nor to remember a failure.
        assertEquals(ModelChoice.Decision.NoLoad(ModelChoice.STOPPING, false), ModelChoice.afterFailure(BrainModel.SMARTER, LoadFailure.STOPPING, ready))
        assertFalse(ModelChoice.remember(LoadFailure.STOPPING))
        for (f in LoadFailure.entries.filter { it != LoadFailure.STOPPING }) assertTrue(ModelChoice.remember(f))
        assertTrue((ModelChoice.afterFailure(BrainModel.OWN, LoadFailure.PROCESS, ModelAvailability.NOT_DOWNLOADED) as ModelChoice.Decision.NoLoad).openApp)
    }

    /**
     * A chosen model whose model process stopped while it was in memory (a native crash while it answered, or Android
     * reclaiming 4 GB) is skipped at the next question, so it isn't loaded again and again until the crash lockout (three
     * stops an hour) stops Fast too.
     */
    @Test fun aModelThatStoppedTheProcessIsSkipped() {
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST, BrainModel.UNCENSORED, FallbackWhy.STOPPED),
            ModelChoice.decide(BrainModel.UNCENSORED, ready, ready, chosenFailedBefore = true, earlier = FallbackWhy.STOPPED))
        assertEquals("“Uncensored, slower” stopped unexpectedly, so Robot is using Fast. Tap it again in the Robot app › Model to retry.",
            ModelChoice.fallbackText(BrainModel.UNCENSORED, FallbackWhy.STOPPED))
        val alone = ModelChoice.decide(BrainModel.SMARTER, ready, ModelAvailability.NOT_DOWNLOADED, true, FallbackWhy.STOPPED) as ModelChoice.Decision.NoLoad
        assertEquals("“Smarter, slower” stopped unexpectedly, and Fast isn't on this phone either. Open the Robot app › Model.", alone.problem)
        // Fast itself is tried every time, as before.
        assertEquals(ModelChoice.Decision.Load(BrainModel.FAST), ModelChoice.decide(BrainModel.FAST, ready, ready, true, FallbackWhy.STOPPED))
        assertEquals("Used Fast (Gemma 4 E2B, TPU) instead of Smarter (Gemma 4 E4B, CPU): stopped unexpectedly",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.MODEL_FALLBACK, ModelCode.SMARTER.id, FallbackWhy.STOPPED.id, ModelCode.FAST.id)))
    }

    @Test fun fallbackMessagesArePlain() {
        assertEquals("“Smarter, slower” isn't on this phone yet, so Robot is using Fast. Download it in the Robot app › Model.",
            ModelChoice.fallbackText(BrainModel.SMARTER, FallbackWhy.NOT_DOWNLOADED))
        assertEquals("“Your own model file” isn't on this phone yet, so Robot is using Fast. Import it in the Robot app › Model.",
            ModelChoice.fallbackText(BrainModel.OWN, FallbackWhy.NOT_DOWNLOADED))
        assertEquals("“Your own model file” didn't load (the file is missing), so Robot is using Fast.",
            ModelChoice.fallbackText(BrainModel.OWN, FallbackWhy.LOAD_FAILED, "the file is missing"))
        for (m in BrainModel.entries) for (w in FallbackWhy.entries) {
            val t = ModelChoice.fallbackText(m, w)
            assertTrue(t, t.contains(m.title) && !t.contains("owner"))
        }
    }

    /**
     * The approval gate decides the same whichever model you chose (the uncensored one included): the model only proposes
     * an action, and Robot's cards and switches decide.
     */
    @Test fun theGateIgnoresTheModel() {
        val bools = listOf(false, true)
        for (base in listOf(RobotSettings(), RobotSettings(safetyCards = SafetyCards.AFTER_READING, whoCards = false))) {
            for (t in ToolName.entries) for (locked in bools) for (content in bools) for (asked in bools) for (remote in bools) {
                val why = setOf(AskWhy.WHO)
                val fast = ApprovalGate.decide(t, locked, base, why, content, asked, remote)
                for (m in BrainModel.entries) {
                    assertEquals("$t $m", fast, ApprovalGate.decide(t, locked, base.copy(brainModel = m.id), why, content, asked, remote))
                }
            }
        }
    }

    /**
     * Tidying and deleting a model's files outside take the file and what LiteRT-LM left beside it, never another model's
     * file whose name begins the same way (the 1.0.1 review).
     */
    @Test fun onlyTheModelsOwnFilesAreBesideIt() {
        val f = BrainModel.FAST.file
        assertTrue(ModelFiles.besideFile(f, f))
        assertTrue(ModelFiles.besideFile("${f}_1791942466_3113545589.vision_adapter.xnnpack_cache", f))
        assertTrue(ModelFiles.besideFile("$f.xnnpack_cache", f))
        assertFalse(ModelFiles.besideFile(f.removeSuffix(".litertlm") + "-v2.litertlm", f))
        assertFalse(ModelFiles.besideFile("${f}x", f))
        assertFalse(ModelFiles.besideFile(BrainModel.UNCENSORED.file, BrainModel.SMARTER.file))
        for (a in BrainModel.entries) for (b in BrainModel.entries) if (a != b) assertFalse("${a.file} beside ${b.file}", ModelFiles.besideFile(a.file, b.file))
    }
}
