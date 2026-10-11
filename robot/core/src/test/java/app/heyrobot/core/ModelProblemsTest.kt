package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 0.4.7: the GPU failure Robot remembers, the problem note, and the problem report you may copy or share. */
class ModelProblemsTest {
    private val utc = ZoneId.of("UTC")
    private val t = 1_791_000_000_000L

    @Test fun gpuFailureAndTryingRoundTrip() {
        val f = GpuFailure(BrainModel.GENERAL, t, ProblemStage.LOADING, LoadFailure.ENGINE)
        assertEquals(f, GpuFailure.decode(f.encode()))
        val stopped = GpuFailure(BrainModel.GENERAL, t, ProblemStage.WRITING, null)
        assertEquals(stopped, GpuFailure.decode(stopped.encode()))
        assertEquals("didn't load (engine error)", f.what)
        assertEquals("stopped while writing the answer", stopped.what)
        assertEquals("failed while reading the prompt (engine error)", GpuFailure(BrainModel.GENERAL, t, ProblemStage.READING, LoadFailure.ENGINE).what)
        for (bad in listOf(null, "", "e2b", "e2b x 1 0", "nope $t 1 0", "e2b $t 9 0", "e2b $t 1 99", "e2b 0 1 0")) assertNull(bad, GpuFailure.decode(bad))
        val trying = GpuTrying(BrainModel.GENERAL, t, 4242)
        assertEquals(trying, GpuTrying.decode(trying.encode()))
        assertNull(GpuTrying.decode("e2b $t 0"))
    }

    @Test fun problemNoteRoundTripsAndSaysWhatHappened() {
        val p = ModelProblem(t, BrainModel.GENERAL, ModelBackend.GPU, ProblemStage.WRITING, null, BrainModel.GENERAL, ModelBackend.CPU,
            2_100_000_000L, 7_500_000_000L, true, 12345)
        assertEquals(p, ModelProblem.decode(p.encode()))
        assertEquals("Fast stopped on the GPU, so Robot switched to the CPU.", p.headline())
        val load = p.copy(failure = LoadFailure.ENGINE, stage = ProblemStage.LOADING)
        assertEquals(load, ModelProblem.decode(load.encode()))
        assertEquals("Fast didn't load on the GPU, so Robot switched to the CPU.", load.headline())
        // The engine failed a reply on the GPU (an OpenCL error, not a crash).
        assertEquals("Fast failed on the GPU, so Robot switched to the CPU.", p.copy(failure = LoadFailure.ENGINE).headline())
        val e4b = ModelProblem(t, BrainModel.SMARTER, ModelBackend.CPU, ProblemStage.READING, null, BrainModel.GENERAL, ModelBackend.GPU)
        assertEquals("Smarter, slower stopped on the CPU, so Robot is using Fast on the GPU.", e4b.headline())
        val alone = ModelProblem(t, BrainModel.FAST, ModelBackend.TPU, ProblemStage.LOADING, LoadFailure.TIMEOUT, null, null)
        assertEquals(alone, ModelProblem.decode(alone.encode()))
        assertEquals("Fast didn't load on the TPU.", alone.headline())
        for (bad in listOf(null, "", "p1 1", "p3 " + p.encode().substringAfter(' '), p.encode().replace(" gpu ", " npx "),
            p.encode().removeSuffix(" 1") + " 9", p.encode().replace(" litertlm ", " onnx "))) assertNull(bad, ModelProblem.decode(bad))
        // Numbers and fixed ids only.
        assertTrue(p.encode(), Regex("^[a-z0-9_ -]+$").matches(p.encode()))
    }

    /**
     * 0.6.0: where each request to the model is, from :brain's events, a one-shot's as much as a question's: on the model
     * from EV_QUEUED 0, writing from its first chunk, off it while it waits (also after giving way), after its tool calls
     * and at its end. The model is at the furthest stage of any request.
     */
    @Test fun requestStagesFollowTheQueueOneShotsToo() {
        val P = BrainProtocol
        assertEquals(listOf(P.REQ_GENERATE, P.REQ_TOOL_RESULT, P.REQ_ONE_SHOT), P.REQUESTS.filter(RequestStages::tracked).sorted())
        // A translation waits behind the panel's question, starts, writes, gives way, starts over and ends.
        var s: ProblemStage? = null
        s = RequestStages.after(s, P.EV_QUEUED, 2); assertNull(s)
        s = RequestStages.after(s, P.EV_QUEUED, 0); assertEquals(ProblemStage.READING, s)
        s = RequestStages.after(s, P.EV_CHUNK); assertEquals(ProblemStage.WRITING, s)
        s = RequestStages.after(s, P.EV_QUEUED, 1); assertNull(s)
        s = RequestStages.after(s, P.EV_QUEUED, 0); assertEquals(ProblemStage.READING, s)
        s = RequestStages.after(s, P.EV_CHUNK); assertEquals(ProblemStage.WRITING, s)
        for (end in P.TERMINAL) assertNull(RequestStages.after(ProblemStage.WRITING, end))
        // A question that asks for tools is off the model until its results go back (a request of its own).
        assertNull(RequestStages.after(ProblemStage.WRITING, P.EV_TOOL_CALLS))
        // Anything else leaves it where it was.
        assertEquals(ProblemStage.READING, RequestStages.after(ProblemStage.READING, 999))
        // One writes while another reads or waits: writing; reading alone: reading; none on the model: between answers.
        assertEquals(ProblemStage.WRITING, RequestStages.now(listOf(ProblemStage.READING, ProblemStage.WRITING)))
        assertEquals(ProblemStage.READING, RequestStages.now(listOf(ProblemStage.READING)))
        assertNull(RequestStages.now(emptyList()))
    }

    /**
     * 0.6.0: a note names the process that failed: :brain for LiteRT-LM (Fast, on the GPU too), :llama for your GGUF file,
     * whose crash is never Fast's or the GPU's. 0.4.7's notes ("p1", 12 fields) still read, as :brain's.
     */
    @Test fun notesSayWhichProcessFailedAndOldOnesStillRead() {
        assertEquals(ModelProcess.LLAMA, ModelProcess.of(ModelFormat.GGUF))
        assertEquals(ModelProcess.BRAIN, ModelProcess.of(ModelFormat.LITERT_LM))
        val gpu = ModelProblem(t, BrainModel.GENERAL, ModelBackend.GPU, ProblemStage.WRITING, null, BrainModel.GENERAL, ModelBackend.CPU,
            2_100_000_000L, 7_500_000_000L, false, 12345)
        assertEquals(ModelProcess.BRAIN, gpu.process)
        assertTrue(gpu.encode(), gpu.encode().startsWith("p2 ") && gpu.encode().endsWith(" litertlm 1"))
        val p047 = "p1 $t e2b gpu 3 0 e2b cpu 2100000000 7500000000 0 12345"
        assertEquals(gpu, ModelProblem.decode(p047))
        // llama.cpp's process stopped under your GGUF file's reply: its own process, no :brain pid, Fast on the GPU after it.
        val gguf = ModelProblem(t, BrainModel.OWN, ModelBackend.CPU, ProblemStage.WRITING, null, BrainModel.GENERAL, ModelBackend.GPU,
            format = ModelFormat.GGUF)
        assertEquals(ModelProcess.LLAMA, gguf.process)
        assertEquals(gguf, ModelProblem.decode(gguf.encode()))
        // :brain itself stopped while a GGUF file ran in :llama: :brain's, and the note keeps both.
        val brainUnderGguf = gguf.copy(process = ModelProcess.BRAIN, brainPid = 4321)
        assertEquals(brainUnderGguf, ModelProblem.decode(brainUnderGguf.encode()))
        assertTrue(Regex("^[a-z0-9_ -]+$").matches(gguf.encode()))
        val facts = ReportFacts("0.6.0", 60, Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_500_000_000L), "17 (SDK 37)", "fp", 1_234_567,
            emptyList(), emptyList())
        val llama = ProblemReport.text(gguf, facts, t, utc)
        assertTrue(llama, llama.contains("Ran on: CPU · llama.cpp in :llama, an isolated process (your GGUF file, never Fast) · stage: writing the answer"))
        assertTrue(llama, llama.contains("failure: the model process stopped (:llama)") && llama.contains("Android's records of :llama"))
        assertTrue(llama, llama.contains("your own file (name and hash left out)"))
        val brain = ProblemReport.text(gpu, facts, t, utc)
        assertTrue(brain, brain.contains("Ran on: GPU · LiteRT-LM in :brain · stage: writing the answer") &&
            brain.contains("failure: the model process stopped (:brain)") && brain.contains("Android's records of :brain"))
        assertFalse(brain.contains(":llama"))
        assertTrue(ProblemReport.text(brainUnderGguf, facts, t, utc).contains("llama.cpp in :llama, an isolated process (your GGUF file, never Fast) · stage: writing the answer · failure: the model process stopped (:brain)"))
    }

    /** Paths in Android's free text never name a file of yours: only libraries and Robot's own model files keep a name. */
    @Test fun scrubKeepsNoPathOfYours() {
        assertEquals("crash in …/liblitertlm_jni.so", ProblemReport.scrub("crash in /data/app/~~x==/app.heyrobot-y==/lib/arm64/liblitertlm_jni.so"))
        assertEquals("open …/gemma-4-E2B-it.litertlm failed", ProblemReport.scrub("open /storage/emulated/0/Android/data/app.heyrobot/files/gemma-4-E2B-it.litertlm failed"))
        // A name with spaces in shared storage: the rest of the text goes with it.
        assertEquals("open …", ProblemReport.scrub("open /storage/emulated/0/Download/Anna's tax return.pdf failed"))
        assertEquals("…", ProblemReport.scrub("/sdcard/DCIM/Camera/IMG_2026.jpg"))
        assertNull(ProblemReport.scrub("  "))
        assertEquals(ProblemReport.MAX_DESCRIPTION, ProblemReport.scrub("x".repeat(500))!!.length)
        assertEquals("a b", ProblemReport.scrub("a\u0000 b"))
    }

    /**
     * A report built from a voice log and settings full of personal things holds none of them: wake phrases by number,
     * no question or answer (the log never had them), your own file neither by name nor hash, no path of yours.
     */
    @Test fun theReportHoldsNothingPersonal() {
        val personal = RobotSettings(wakePhrases = listOf(WakePhrase(7, "hey jennifer smith", WakeSensitivity.MEDIUM, true)),
            weatherHome = WeatherPlace("12 Rue Secrète, Montréal", 45.5, -73.6))
        val log = listOf(
            VoiceLogEntry(t, VoiceEvent.WAKE, 7, 88, WakeAction.OPENED.id),
            VoiceLogEntry(t + 1_000, VoiceEvent.STT_DONE, 42, 3_000, 400),
            VoiceLogEntry(t + 2_000, VoiceEvent.BRAIN_SENT, 0, 1),
            VoiceLogEntry(t + 3_000, VoiceEvent.MODEL_LOADED, ModelCode.GENERAL.id, BackendCode.GPU.id, 4_100),
            VoiceLogEntry(t + 4_000, VoiceEvent.BRAIN_DIED),
            VoiceLogEntry(t + 4_100, VoiceEvent.GPU_FALLBACK, ModelCode.GENERAL.id, ProblemStage.WRITING.id, 0),
        ).reversed()
        val p = ModelProblem(t + 4_000, BrainModel.GENERAL, ModelBackend.GPU, ProblemStage.WRITING, null, BrainModel.GENERAL, ModelBackend.CPU,
            2_100_000_000L, 7_500_000_000L, false, 12345)
        val exit = ExitFacts(t + 4_000, 12345, 5, "CRASH_NATIVE", 11, "crash in /data/app/~~a==/app.heyrobot-b==/lib/arm64/liblitertlm_jni.so " +
            "reading /storage/emulated/0/Documents/jennifer-smith-diary.txt", 100, 1_900_000, 2_400_000)
        val facts = ReportFacts("0.4.7", 47, Phone("Google", "Pixel 9a", "Google", "Tensor G4", 7_500_000_000L), "17 (SDK 37)",
            "google/tegu/tegu:17/CP3A.261005.005/2026100201:user/release-keys", 2_588_147_712L, listOf(exit), log)
        val text = ProblemReport.text(p, facts, t + 60_000, utc)
        for (w in listOf("Fast stopped on the GPU, so Robot switched to the CPU.", "0.4.7 (47)", "Pixel 9a", "Tensor G4", "17 (SDK 37)",
            "google/tegu/tegu:17/CP3A.261005.005/2026100201:user/release-keys", "gemma-4-E2B-it.litertlm", "SHA-256 18193810…",
            "2,588,147,712 bytes", "Ran on: GPU", "writing the answer", "the model process stopped", "on the CPU", "2100 MB free of 7500 MB",
            "pid 12345 (the one that failed)", "CRASH_NATIVE (5)", "status 11", "…/liblitertlm_jni.so", "Wake word #7",
            "Model loaded: Fast (Gemma 4 E2B, general build) on the GPU", "The GPU failed for Fast", "Nothing here was sent anywhere")) {
            assertTrue(w, text.contains(w))
        }
        for (w in listOf("jennifer", "Jennifer", "Secrète", "Montréal", "diary", "/storage", "/data/app", "app.heyrobot-b")) assertFalse(w, text.contains(w))
        assertFalse(personal.wakePhrases.any { text.contains(it.text) })
        // Your own file: neither its name nor its hash.
        val own = ProblemReport.text(p.copy(model = BrainModel.OWN, backend = ModelBackend.CPU, then = BrainModel.GENERAL), facts.copy(fileBytes = 1_234_567), t, utc)
        assertTrue(own.contains("your own file (name and hash left out)") && !own.contains("own_model"))
        // At most the last 50 voice log events.
        val many = facts.copy(voiceLog = List(80) { VoiceLogEntry(t - it, VoiceEvent.SCREEN, 1) })
        assertEquals(50, ProblemReport.text(p, many, t, utc).lines().count { it.contains("Screen on") })
    }
}
