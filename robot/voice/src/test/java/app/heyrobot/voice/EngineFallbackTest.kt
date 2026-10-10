package app.heyrobot.voice

import app.heyrobot.core.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The engine fallback state machine: a model that kills :voice while loading must not loop (the wake word lives there). */
class EngineFallbackTest {
    private val CRASH_NATIVE = 5
    private val EXIT_SELF = 1
    private val LOW_MEMORY = 3

    @Test fun twoDeathsInARowWhileLoadingMarkTheEngineBroken() {
        var r = GuardRecord()
        // Process 1 starts loading and dies (no afterLoad).
        r = GuardLogic.beforeLoad(r)
        // Process 2 finds the note: one death. Not broken yet, so it tries again, and dies too.
        r = GuardLogic.atProcessStart(r, died = true)
        assertEquals(1, r.deaths)
        assertFalse(GuardLogic.broken(r))
        r = GuardLogic.beforeLoad(r)
        // Process 3: broken; the placeholder runs.
        r = GuardLogic.atProcessStart(r, died = true)
        assertTrue(GuardLogic.broken(r))
        assertEquals(EngineState.BROKEN, EngineFallback.state(verified = true, broken = true, failed = false, loading = false, loaded = false))
        // "Try again" clears it.
        assertFalse(GuardLogic.broken(GuardRecord()))
    }

    @Test fun aSuccessfulLoadClearsTheCountAndAKillForMemoryIsNoDeath() {
        var r = GuardLogic.atProcessStart(GuardLogic.beforeLoad(GuardRecord()), died = true)
        assertEquals(1, r.deaths)
        r = GuardLogic.afterLoad(GuardLogic.beforeLoad(r), ok = true)
        assertEquals(GuardRecord(), r)
        // Killed for memory while loading: not counted.
        r = GuardLogic.atProcessStart(GuardLogic.beforeLoad(r), died = GuardLogic.countsAsDeath(LOW_MEMORY))
        assertEquals(0, r.deaths)
        assertFalse(r.loading)
        // A Java exception: the process lives, the note is cleared, the count stays.
        r = GuardLogic.afterLoad(GuardLogic.beforeLoad(GuardRecord(deaths = 1)), ok = false)
        assertEquals(GuardRecord(deaths = 1), r)
        assertEquals(r, GuardLogic.atProcessStart(r, died = true))
    }

    @Test fun exitReasons() {
        assertTrue(GuardLogic.countsAsDeath(CRASH_NATIVE))
        assertTrue(GuardLogic.countsAsDeath(EXIT_SELF)) // sherpa-onnx's exit(-1) on a bad config
        assertTrue(GuardLogic.countsAsDeath(4))
        assertTrue(GuardLogic.countsAsDeath(null))
        for (r in listOf(3, 10, 11, 15, 16)) assertFalse("$r", GuardLogic.countsAsDeath(r))
    }

    @Test fun recordsSurviveGarbage() {
        assertEquals(GuardRecord(2, true), GuardRecord.decode(GuardRecord(2, true).encode()))
        assertEquals(GuardRecord(), GuardRecord.decode("nonsense"))
        assertEquals(GuardRecord(), GuardRecord.decode(null))
        assertEquals(GuardRecord(0, false), GuardRecord.decode("deaths=-4"))
    }

    @Test fun stateFollowsTheFacts() {
        fun st(v: Boolean = true, b: Boolean = false, f: Boolean = false, l: Boolean = false, r: Boolean = false) = EngineFallback.state(v, b, f, l, r)
        assertEquals(EngineState.MISSING, st(v = false, b = true))
        assertEquals(EngineState.IDLE, st())
        assertEquals(EngineState.LOADING, st(l = true))
        assertEquals(EngineState.READY, st(r = true))
        assertEquals(EngineState.FAILED, st(f = true))
        assertFalse(st(f = true).usable)
        assertFalse(st(b = true).usable)
        assertTrue(st(l = true).usable)
    }

    /** The file-backed guard across simulated :voice processes (a new EngineGuard = a new process). */
    @Test fun guardFileAcrossProcesses() {
        val dir = Files.createTempDirectory("guard").toFile()
        try {
            val f = File(dir, "guard-x.txt")
            fun process(reason: Int? = CRASH_NATIVE) = EngineGuard("x", f) { reason }
            process().beforeLoad() // dies
            val p2 = process()
            assertFalse(p2.broken)
            p2.beforeLoad() // dies again
            val p3 = process()
            assertTrue(p3.broken)
            p3.reset()
            assertFalse(process().broken)
            val p5 = process()
            p5.beforeLoad()
            p5.afterLoad(true)
            p5.beforeLoad() // killed for memory
            assertFalse(process(reason = LOW_MEMORY).broken)
        } finally {
            dir.deleteRecursively()
        }
    }
}
