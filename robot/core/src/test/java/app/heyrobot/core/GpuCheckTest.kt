// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 (design Step 2): the GPU self-test's turns, its verdicts, its record, and the failure records tied to the OS build. */
class GpuCheckTest {
    private val sha = BrainModel.GENERAL.sha256!!
    private val build = "google/shiba/shiba:16/BP4A.251005.001/14000000:user/release-keys"
    private val updated = "google/shiba/shiba:16/BP4A.251105.001/14100000:user/release-keys"
    private val key = CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, sha, build)!!

    private fun ok(text: String, ms: Long = 900, rebuilt: Boolean = false, read: Int = 30, readTps: Double = 400.0, written: Int = 3, writeTps: Double = 15.0) =
        CheckTurn.Done(DoneReason.END, text, emptyList(), ms, readTokens = read, readTps = readTps, writtenTokens = written, writeTps = writeTps, rebuilt = rebuilt)

    private fun timer(ms: Long = 1_500, written: Int = 14, writeTps: Double = 14.0) = CheckTurn.Done(DoneReason.TOOL_CALLS, "",
        listOf(RawToolCall("set_timer", mapOf("seconds" to 300))), ms, readTokens = 20, readTps = 300.0, writtenTokens = written, writeTps = writeTps)

    /** A good run: turn 1 builds the conversation (about 300 tokens of prompt and tool), turns 2 and 3 go on in it. */
    private val good = listOf(ok("Ready.", rebuilt = true, read = 320, readTps = 480.0), ok("5"), timer())

    private fun runWith(turns: List<CheckTurn>): Pair<CheckResult, List<BrainMsg.Generate>> {
        val sent = ArrayList<BrainMsg.Generate>()
        val r = runBlocking { GpuCheck.run { i, g -> sent += g; turns[i] } }
        return r to sent
    }

    // ------------------------------------------------------------------ the turns: fixed, local, one conversation

    @Test fun theTurnsAreFixedPromptsInOneConversationOfTheirOwn() {
        val (r, sent) = runWith(good)
        assertEquals(GpuVerdict.PASSED, r.verdict)
        assertEquals(GpuCheck.TURNS, sent.map { it.text })
        assertEquals(listOf("Reply with the word ready.", "What is 2 plus 3? Answer with a number.", "Set a timer for 5 minutes."), sent.map { it.text })
        for (g in sent) {
            assertEquals(GpuCheck.CONV_ID, g.convId)
            assertEquals(GpuCheck.SYSTEM, g.system)
            assertEquals(listOf("set_timer"), g.tools)
            assertFalse(g.think)
            assertFalse(g.hasImage)
            assertEquals(emptyList<Any>(), g.skills)
        }
        // History counts 0, 2, 4: :brain goes on in the conversation turn 1 built (BrainPolicy.canReuse), so a GPU that
        // fails after a turn or two fails here.
        assertEquals(listOf(0, 2, 4), sent.map { it.historyCount })
        assertEquals(listOf(PromptTurn(PromptRole.USER, GpuCheck.TURNS[0]), PromptTurn(PromptRole.MODEL, "Ready."),
            PromptTurn(PromptRole.USER, GpuCheck.TURNS[1]), PromptTurn(PromptRole.MODEL, "5")), sent[2].history)
        assertTrue(r.reused)
    }

    @Test fun theSystemPromptHoldsNothingThatChanges() {
        // No date, no clock, no name, no setting: the same words on every phone, every day.
        assertFalse(GpuCheck.SYSTEM.contains(Regex("""\d""")))
        assertEquals(GpuCheck.request(1, listOf("ready")), GpuCheck.request(1, listOf("ready")))
        assertTrue(GpuCheck.SYSTEM.contains("set_timer"))
        // What crosses to :brain is a plain Generate, through the same JSON as the panel's.
        val g = GpuCheck.request(2, listOf("ready", "5"))
        assertEquals(g, BrainMsg.decode(BrainProtocol.REQ_GENERATE, g.toJson().toString()))
    }

    // ------------------------------------------------------------------ verdicts

    @Test fun eachTurnFailsTheWayTheDesignSays() {
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(0, ok("ready")))
        assertEquals(GpuVerdict.EMPTY, GpuCheck.judgeTurn(0, ok("  ")))
        assertEquals(GpuVerdict.WRONG, GpuCheck.judgeTurn(0, timer()))
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(1, ok("5")))
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(1, ok("2 plus 3 is 5.")))
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(1, ok("Five.")))
        for (no5 in listOf("6", "55", "15", "2.5", "", "ready")) assertEquals(no5, if (no5.isBlank()) GpuVerdict.EMPTY else GpuVerdict.WRONG, GpuCheck.judgeTurn(1, ok(no5)))
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(2, timer()))
        assertEquals(GpuVerdict.NO_CALL, GpuCheck.judgeTurn(2, ok("Sure, setting a timer.")))
        assertEquals(GpuVerdict.NO_CALL, GpuCheck.judgeTurn(2, CheckTurn.Done(DoneReason.TOOL_CALLS, "", listOf(RawToolCall("set_alarm", emptyMap())), 900)))
        // Text-call mode (a build that refused the tool API): a reply that is nothing but the call counts.
        val text = "call:set_timer{minutes:5}"
        assertEquals(GpuVerdict.PASSED, GpuCheck.judgeTurn(2, CheckTurn.Done(DoneReason.END, text, emptyList(), 900, textCalls = true)))
        assertEquals(GpuVerdict.NO_CALL, GpuCheck.judgeTurn(2, CheckTurn.Done(DoneReason.END, text, emptyList(), 900, textCalls = false)))
        // On any turn: garbage (or a three-word answer run to the cap), an error, a crash, a stall, a turn over the limit.
        for (turn in 0..2) {
            assertEquals(GpuVerdict.GARBAGE, GpuCheck.judgeTurn(turn, CheckTurn.Done(DoneReason.GARBAGE, "aaaaaaa", emptyList(), 900)))
            assertEquals(GpuVerdict.GARBAGE, GpuCheck.judgeTurn(turn, CheckTurn.Done(DoneReason.LENGTH, "ready ready", emptyList(), 900)))
            assertEquals(GpuVerdict.ERROR, GpuCheck.judgeTurn(turn, CheckTurn.Done(DoneReason.CONTEXT_FULL, "", emptyList(), 900)))
            assertEquals(GpuVerdict.ERROR, GpuCheck.judgeTurn(turn, CheckTurn.Failed(stopped = false)))
            assertEquals(GpuVerdict.STOPPED, GpuCheck.judgeTurn(turn, CheckTurn.Failed(stopped = true)))
            assertEquals(GpuVerdict.STALLED, GpuCheck.judgeTurn(turn, CheckTurn.Stalled))
            assertEquals(GpuVerdict.STALLED, GpuCheck.judgeTurn(turn, ok("5", ms = GpuCheck.TURN_MS + 1)))
        }
    }

    /**
     * Only the device misbehaving moves the model off it, an empty answer included (nothing else catches a device whose
     * output shows as nothing); how the model answered is noted and the device stays.
     */
    @Test fun onlyAFaultMovesTheModelOffTheDevice() {
        assertEquals(setOf(GpuVerdict.NOT_LOADED, GpuVerdict.ERROR, GpuVerdict.STOPPED, GpuVerdict.STALLED, GpuVerdict.GARBAGE, GpuVerdict.EMPTY),
            GpuVerdict.entries.filter { it.fault }.toSet())
        assertEquals(setOf(GpuVerdict.WRONG, GpuVerdict.NO_CALL, GpuVerdict.SLOW), GpuVerdict.entries.filter { it.miss }.toSet())
        assertFalse(GpuVerdict.PASSED.fault)
        assertFalse(GpuVerdict.PASSED.miss)
    }

    @Test fun theCheckStopsAtTheFirstFault() {
        val (garbled, sent) = runWith(listOf(ok("ready", rebuilt = true), CheckTurn.Done(DoneReason.GARBAGE, "5555555", emptyList(), 900), timer()))
        assertEquals(GpuVerdict.GARBAGE to 2, garbled.verdict to garbled.turn)
        assertEquals(2, sent.size)
        val (stalled, _) = runWith(listOf(ok("ready", rebuilt = true), ok("5"), CheckTurn.Stalled))
        assertEquals(GpuVerdict.STALLED to 3, stalled.verdict to stalled.turn)
        val (error, errorSent) = runWith(listOf(CheckTurn.Failed(stopped = false), ok("5"), timer()))
        assertEquals(GpuVerdict.ERROR to 1, error.verdict to error.turn)
        assertEquals(1, errorSent.size)
        // An empty answer is a fault: the check ends there, on turn 1 or turn 2.
        val (empty, emptySent) = runWith(listOf(ok(" ", rebuilt = true), ok("5", rebuilt = true), timer()))
        assertEquals(GpuVerdict.EMPTY to 1, empty.verdict to empty.turn)
        assertEquals(1, emptySent.size)
        val (empty2, empty2Sent) = runWith(listOf(ok("ready", rebuilt = true), ok(""), timer()))
        assertEquals(GpuVerdict.EMPTY to 2, empty2.verdict to empty2.turn)
        assertEquals(2, empty2Sent.size)
    }

    /**
     * A miss (a wrong or slow answer, no call) is noted, and the check goes on, so the turns after it still try the device:
     * a fault there wins, and with none the first miss is the verdict.
     */
    @Test fun aMissIsNotedAndTheCheckGoesOn() {
        val (wrong, sent) = runWith(listOf(ok("ready", rebuilt = true), ok("7"), timer()))
        assertEquals(GpuVerdict.WRONG to 2, wrong.verdict to wrong.turn)
        assertEquals(3, sent.size)
        // The model's own words go on in the conversation, as after a pass: :brain keeps the conversation turn 1 built.
        assertEquals(listOf(PromptTurn(PromptRole.USER, GpuCheck.TURNS[0]), PromptTurn(PromptRole.MODEL, "ready"),
            PromptTurn(PromptRole.USER, GpuCheck.TURNS[1]), PromptTurn(PromptRole.MODEL, "7")), sent[2].history)
        assertEquals(4, sent[2].historyCount)
        // A fault after a miss is the verdict: the GPU broke on its third turn.
        val (broke, _) = runWith(listOf(ok("ready", rebuilt = true), ok("7"), CheckTurn.Failed(stopped = true)))
        assertEquals(GpuVerdict.STOPPED to 3, broke.verdict to broke.turn)
        val (garbledLater, _) = runWith(listOf(ok("ready", rebuilt = true), ok("7"), CheckTurn.Done(DoneReason.GARBAGE, "<pad>", emptyList(), 900)))
        assertEquals(GpuVerdict.GARBAGE to 3, garbledLater.verdict to garbledLater.turn)
        val (noCall, _) = runWith(listOf(ok("ready", rebuilt = true), ok("5"), ok("OK.")))
        assertEquals(GpuVerdict.NO_CALL to 3, noCall.verdict to noCall.turn)
        // The first miss is the one kept.
        val (two, _) = runWith(listOf(ok("ready", rebuilt = true), ok("7"), ok("OK.")))
        assertEquals(GpuVerdict.WRONG to 2, two.verdict to two.turn)
        // A miss before a slow run is the one kept; SLOW only when every answer was right.
        val slowAndWrong = GpuCheck.judge(listOf(ok("ready", rebuilt = true, written = 2, writeTps = 1.0), ok("7", written = 2, writeTps = 1.0),
            timer(written = 14, writeTps = 1.5)))
        assertEquals(GpuVerdict.WRONG to 2, slowAndWrong.verdict to slowAndWrong.turn)
    }

    /**
     * A turn that left no words (a call where words were asked for: a miss) is left out of the next turn's history with its
     * question: no empty answer reaches the engine (the panel never sends one either), and :brain builds the conversation
     * afresh from what is left (history count 0, then 2). An empty answer never gets here: it is a fault, and ends the check.
     */
    @Test fun aTurnWithoutWordsIsLeftOutOfTheHistory() {
        // Turn 1 answered with a call: WRONG, and its conversation (waiting for the call's result) is built again for turn 2.
        val called = CheckTurn.Done(DoneReason.TOOL_CALLS, "", listOf(RawToolCall("set_timer", mapOf("seconds" to 300))), 900, readTokens = 320,
            readTps = 480.0, writtenTokens = 12, writeTps = 14.0, rebuilt = true)
        val (wrong, sent) = runWith(listOf(called, ok("5", rebuilt = true), timer()))
        assertEquals(GpuVerdict.WRONG to 1, wrong.verdict to wrong.turn)
        assertEquals(listOf(0, 0, 2), sent.map { it.historyCount })
        assertEquals(emptyList<PromptTurn>(), sent[1].history)
        assertEquals(listOf(PromptTurn(PromptRole.USER, GpuCheck.TURNS[1]), PromptTurn(PromptRole.MODEL, "5")), sent[2].history)
        assertFalse(wrong.reused)
        // Turn 2 answered with a call too: turn 3 goes with turn 1 alone.
        val (wrong2, sent2) = runWith(listOf(ok("ready", rebuilt = true), called.copy(rebuilt = false), timer()))
        assertEquals(GpuVerdict.WRONG to 2, wrong2.verdict to wrong2.turn)
        assertEquals(listOf(0, 2, 2), sent2.map { it.historyCount })
        assertEquals(listOf(PromptTurn(PromptRole.USER, GpuCheck.TURNS[0]), PromptTurn(PromptRole.MODEL, "ready")), sent2[2].history)
        // No request ever carries an empty answer.
        for (g in sent + sent2) assertTrue(g.history.none { it.role == PromptRole.MODEL && it.text.isBlank() })
    }

    /** :brain dying under turn 2 (its binding gone): the check's failure, STOPPED, at that turn; turn 3 never sent. */
    @Test fun aCrashOfTheModelProcessMidTestFailsAtThatTurn() {
        val (r, sent) = runWith(listOf(ok("ready", rebuilt = true), CheckTurn.Failed(stopped = true), timer()))
        assertEquals(GpuVerdict.STOPPED, r.verdict)
        assertEquals(2, r.turn)
        assertEquals(2, sent.size)
    }

    @Test fun theSpeedFloorNeedsEnoughTokensWritten() {
        // Three turns of a token or two at a crawl: too few tokens to time, so only the time limit judges them.
        val short = listOf(ok("ready", rebuilt = true, written = 2, writeTps = 0.5), ok("5", written = 1, writeTps = 0.5),
            timer(written = 3, writeTps = 0.5))
        assertEquals(GpuVerdict.PASSED, GpuCheck.judge(short).verdict)
        // Enough tokens, under 2 a second over the three turns: too slow.
        val slow = listOf(ok("ready", rebuilt = true, written = 2, writeTps = 1.0), ok("5", written = 2, writeTps = 1.0), timer(written = 14, writeTps = 1.5))
        val r = GpuCheck.judge(slow)
        assertEquals(GpuVerdict.SLOW to 3, r.verdict to r.turn)
        // Just over the floor passes.
        assertEquals(GpuVerdict.PASSED, GpuCheck.judge(listOf(ok("ready", rebuilt = true, written = 2, writeTps = 2.5), ok("5", written = 2, writeTps = 2.5),
            timer(written = 14, writeTps = 2.5))).verdict)
        // Unknown numbers (an older :brain) never fail it.
        assertEquals(GpuVerdict.PASSED, GpuCheck.judge(listOf(ok("ready", written = -1, writeTps = -1.0), ok("5", written = -1, writeTps = -1.0),
            timer(written = -1, writeTps = -1.0))).verdict)
    }

    @Test fun itsNumbersAreTheReadingOfTheLongestPromptAndTheWritingOverAllTurns() {
        val r = GpuCheck.judge(good)
        // Turn 1 read 320 tokens at 480 a second; turns 2 and 3 read too few to time.
        assertEquals(480.0, r.readTps, 0.0)
        // 3 + 3 + 14 tokens in 0.2 + 0.2 + 1.0 s.
        assertEquals(14.3, r.writeTps, 0.0)
        // Under SpeedLog's floors: unknown.
        val few = GpuCheck.judge(listOf(ok("ready", read = 50), ok("5"), timer(written = 4)))
        assertEquals(-1.0, few.readTps, 0.0)
        assertEquals(-1.0, few.writeTps, 0.0)
        // A turn that rebuilt the conversation after turn 1 is noted: the turns didn't share a conversation.
        assertFalse(GpuCheck.judge(listOf(ok("ready", rebuilt = true), ok("5", rebuilt = true), timer())).reused)
    }

    // ------------------------------------------------------------------ when it runs

    @Test fun onlyTheGpuAndABetaTpuBuildAreChecked() {
        assertTrue(GpuCheck.applies(BrainModel.GENERAL, ModelBackend.GPU))
        assertTrue(GpuCheck.applies(BrainModel.FAST_G6, ModelBackend.TPU))
        // Never the CPU, never the Tensor G5's TPU build (Pixel 10: as fast as before), never another model.
        assertFalse(GpuCheck.applies(BrainModel.GENERAL, ModelBackend.CPU))
        assertFalse(GpuCheck.applies(BrainModel.FAST, ModelBackend.TPU))
        for (m in listOf(BrainModel.SMARTER, BrainModel.UNCENSORED, BrainModel.OWN)) for (b in ModelBackend.entries) assertFalse("$m $b", GpuCheck.applies(m, b))
        assertFalse(GpuCheck.needed(emptyList(), CheckKey.of(BrainModel.FAST, ModelBackend.TPU, BrainModel.FAST.sha256, build)))
    }

    @Test fun itRunsOncePerBuildFileAndEngine() {
        assertTrue(GpuCheck.needed(emptyList(), key))
        assertFalse(GpuCheck.needed(emptyList(), null))
        val passed = CheckRecord(key, GpuVerdict.PASSED, 0, 1_760_000_000_000L, 41_000, 480.0, 14.3)
        assertFalse(GpuCheck.needed(listOf(passed), key))
        assertEquals(passed, GpuCheck.of(listOf(passed), key))
        // A failed check doesn't run again by itself either: the GPU failure it wrote stands until you try again.
        assertFalse(GpuCheck.needed(listOf(passed.copy(verdict = GpuVerdict.GARBAGE, turn = 2)), key))
        // An OS update, another file or another LiteRT-LM: checked again.
        assertTrue(GpuCheck.needed(listOf(passed), CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, sha, updated)))
        assertTrue(GpuCheck.needed(listOf(passed), CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, "ab".repeat(32), build)))
        assertTrue(GpuCheck.needed(listOf(passed), CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, sha, build, engine = "0.19.0")))
        // No build or no hash known: nothing to key it by, so it never runs (the reactive fallback stays).
        assertNull(CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, sha, ""))
        assertNull(CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, null, build))
    }

    // ------------------------------------------------------------------ its record

    @Test fun theCheckLogKeepsTheLastCheckOfEachModelAndBackend() {
        val r = CheckRecord(key, GpuVerdict.PASSED, 0, 1_760_000_000_000L, 41_000, 480.0, 14.3)
        val text = CheckLog.add(null, r)
        assertEquals(listOf(r), CheckLog.decode(text))
        assertTrue(text.startsWith("robot-model-check 1\n"))
        // Codes and numbers only.
        assertEquals("e2b gpu ${sha.take(12)} 0.18.0 $build 0 0 1760000000000 41000 480.0 14.3", text.lines()[1])
        val failed = r.copy(key = CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, sha, updated)!!, verdict = GpuVerdict.STOPPED, turn = 2, readTps = -1.0, writeTps = -1.0)
        val both = CheckLog.add(text, failed)
        assertEquals(listOf(failed), CheckLog.decode(both))
        val tpu = CheckRecord(CheckKey.of(BrainModel.FAST_G6, ModelBackend.TPU, BrainModel.FAST_G6.sha256, build)!!, GpuVerdict.NOT_LOADED, 0, 1_760_000_000_001L, 9_000, -1.0, -1.0)
        assertEquals(listOf(failed, tpu), CheckLog.decode(CheckLog.add(both, tpu)))
        // A damaged or foreign file reads as no check (so it runs).
        for (bad in listOf("", "hello", "robot-model-check 1\ne2b gpu x 0.18.0 b 0 0 1 1 1 1", "robot-model-check 1\ne2b gpu ${sha.take(12)} 0.18.0 b 0 2 1 1 1 1",
            "robot-model-check 1\ne2b gpu ${sha.take(12)} 0.18.0 b 77 1 1 1 1 1", "robot-model-check 1\ne2b gpu ${sha.take(12)} 0.18.0 b 5 0 1 1 1 1"))
            assertEquals(bad, emptyList<CheckRecord>(), CheckLog.decode(bad))
        assertEquals(emptyList<CheckRecord>(), CheckLog.decode("robot-model-check 1\n" + "x".repeat(CheckLog.MAX_CHARS)))
    }

    // ------------------------------------------------------------------ the failure records and the OS build

    @Test fun aGpuFailureHoldsOnItsBuildOnly() {
        val t = 1_760_000_000_000L
        val f = GpuFailure(BrainModel.GENERAL, t, ProblemStage.WRITING, LoadFailure.ENGINE, SpeedLog.cleanBuild(build), GpuVerdict.GARBAGE)
        assertEquals(f, GpuFailure.decode(f.encode()))
        assertTrue(f.inForce(build))
        assertFalse(f.inForce(updated))
        assertEquals("The GPU failed Robot's check (garbled answer)", f.titleText.en())
        // An OS update gives the GPU another chance.
        val fromCheck = GpuFailure(BrainModel.GENERAL, t, ProblemStage.WRITING, null, SpeedLog.cleanBuild(build), GpuVerdict.STOPPED)
        assertEquals(fromCheck, GpuFailure.decode(fromCheck.encode()))
        assertFalse(fromCheck.inForce(updated))
    }

    /** 1.1's record has no build: it stays in force, takes the build it is first seen on, and the next update drops it. */
    @Test fun anOldRecordWithoutAFingerprintIsKeptUntilTheBuildChanges() {
        val old = GpuFailure.decode("e2b 1760000000000 1 4")!!
        assertNull(old.build)
        assertNull(old.check)
        assertTrue(old.inForce(build))
        assertTrue(old.inForce(updated))
        val stamped = old.stamped(build)
        assertEquals(SpeedLog.cleanBuild(build), stamped.build)
        assertEquals(stamped, GpuFailure.decode(stamped.encode()))
        assertTrue(stamped.inForce(build))
        assertFalse(stamped.inForce(updated))
        // A stamped record isn't stamped again.
        assertEquals(stamped, stamped.stamped(updated))
        // The same for the TPU (0.8's three fields).
        val tpu = TpuFailure.decode("e2b_tpu_g6 1760000000000 4")!!
        assertNull(tpu.build)
        assertTrue(tpu.inForce(updated))
        val tpuStamped = tpu.stamped(build)
        assertEquals(tpuStamped, TpuFailure.decode(tpuStamped.encode()))
        assertFalse(tpuStamped.inForce(updated))
        val tpuCheck = TpuFailure(BrainModel.FAST_G6, 1L, LoadFailure.TIMEOUT, SpeedLog.cleanBuild(build), GpuVerdict.STALLED)
        assertEquals(tpuCheck, TpuFailure.decode(tpuCheck.encode()))
        assertEquals("The TPU failed Robot's check (no answer in time)", tpuCheck.titleText.en())
    }

    @Test fun damagedFailureRecordsReadAsNone() {
        for (bad in listOf("e2b 1 1 4 -", "e2b 1 1 4 - 0 x", "e2b 1 1 4 a\tb 0", "e2b 1 1 4 - 99", "e2b 1 1 4 - 0.5"))
            assertNull(bad, GpuFailure.decode(bad))
        // A check field of 0 is "not the check"; PASSED is never a failure.
        assertNull(GpuFailure.decode("e2b 1 1 4 - 0")!!.check)
        for (bad in listOf("e2b_tpu_g6 1 4 -", "e2b_tpu_g6 1 4 - 99", "e2b 1 4 - 0")) assertNull(bad, TpuFailure.decode(bad))
        // Nor is a miss (none is written): a record that names one reads as no failure, so the device is used.
        for (v in GpuVerdict.entries.filter { it.miss }) {
            assertNull("$v", GpuFailure.decode("e2b 1 1 4 - ${v.id}"))
            assertNull("$v", TpuFailure.decode("e2b_tpu_g6 1 4 - ${v.id}"))
        }
        for (v in GpuVerdict.entries.filter { it.fault }) {
            assertEquals(v, GpuFailure.decode("e2b 1 1 4 - ${v.id}")!!.check)
            assertEquals(v, TpuFailure.decode("e2b_tpu_g6 1 4 - ${v.id}")!!.check)
        }
    }
}
