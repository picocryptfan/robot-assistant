// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.os.Build
import app.heyrobot.core.BrainModel
import app.heyrobot.core.CheckKey
import app.heyrobot.core.CheckLog
import app.heyrobot.core.CheckRecord
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.GpuVerdict
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.SpeedLog
import app.heyrobot.core.TpuFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.2 (design Step 2): the GPU and TPU failure records on disk against the phone's Android build. Every reader ignores a
 * record from another build (a system update gives the GPU another chance); the voice process writes the build into a
 * record from 1.1 or 0.8 the first time it sees it, and deletes one from another build; "Try the GPU again" deletes the
 * check's record too, so the next load checks again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProblemsBuildTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val dir = Paths.dir(ctx)
    private val gpu = File(dir, GpuFailure.FILE)
    private val tpu = File(dir, TpuFailure.FILE)
    private val checks = File(dir, CheckLog.FILE)
    private val here = SpeedLog.cleanBuild(Build.FINGERPRINT)!!
    private val other = "google/tegu/tegu:16/BP4A.251105.001/14100000:user/release-keys"

    @After fun clean() { dir.deleteRecursively() }

    @Test fun aNewFailureIsWrittenWithThisBuild() {
        Problems.gpuFailed(ctx, GpuFailure(BrainModel.GENERAL, 1_760_000_000_000L, ProblemStage.LOADING, LoadFailure.ENGINE))
        assertEquals(here, GpuFailure.decode(gpu.readText())!!.build)
        assertNotNull(Problems.gpuFailure(ctx))
        Problems.tpuFailed(ctx, TpuFailure(BrainModel.FAST_G6, 1_760_000_000_000L, null, check = GpuVerdict.GARBAGE))
        assertEquals(here, TpuFailure.decode(tpu.readText())!!.build)
        assertEquals(GpuVerdict.GARBAGE, Problems.tpuFailure(ctx)!!.check)
    }

    /**
     * Only a fault in the check moves the model off the device. A record that names a miss (a wrong or slow answer, no
     * timer call: none is written now) reads as no failure on disk too, so the GPU or the TPU is used.
     */
    @Test fun aRecordOfAMissReadsAsNoFailure() {
        gpu.writeText("e2b 1760000000000 1 0 $here ${GpuVerdict.WRONG.id}")
        tpu.writeText("e2b_tpu_g6 1760000000000 0 $here ${GpuVerdict.NO_CALL.id}")
        assertNull(Problems.gpuFailure(ctx))
        assertNull(Problems.tpuFailure(ctx))
        gpu.writeText("e2b 1760000000000 1 0 $here ${GpuVerdict.STALLED.id}")
        assertEquals(GpuVerdict.STALLED, Problems.gpuFailure(ctx)!!.check)
    }

    /** 1.1's record (four fields): in force, then stamped with this build once, so the next system update drops it. */
    @Test fun anOldRecordIsKeptAndStampedWithThisBuild() {
        gpu.writeText("e2b 1760000000000 1 4")
        tpu.writeText("e2b_tpu_g6 1760000000000 4")
        assertNotNull(Problems.gpuFailure(ctx))
        assertNotNull(Problems.tpuFailure(ctx))
        assertEquals(1, Problems.fingerprintFailures(ctx))
        assertEquals(here, GpuFailure.decode(gpu.readText())!!.build)
        assertEquals(here, TpuFailure.decode(tpu.readText())!!.build)
        assertNotNull(Problems.gpuFailure(ctx))
        // Nothing more to do the next time.
        assertEquals(0, Problems.fingerprintFailures(ctx))
    }

    /** After a system update: no reader sees the old build's failure, and the voice process deletes it, once. */
    @Test fun anOsUpdateResetsTheRecord() {
        Problems.gpuFailed(ctx, GpuFailure(BrainModel.GENERAL, 1L, ProblemStage.WRITING, null, other, GpuVerdict.GARBAGE))
        Problems.tpuFailed(ctx, TpuFailure(BrainModel.FAST_G6, 1L, LoadFailure.ENGINE, other))
        assertTrue(gpu.isFile)
        assertNull(Problems.gpuFailure(ctx))
        assertNull(Problems.tpuFailure(ctx))
        assertEquals(2, Problems.fingerprintFailures(ctx))
        assertFalse(gpu.exists())
        assertFalse(tpu.exists())
        assertEquals(0, Problems.fingerprintFailures(ctx))
    }

    @Test fun tryingAgainForgetsTheCheckToo() {
        val r = CheckRecord(CheckKey.of(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256, Build.FINGERPRINT)!!, GpuVerdict.STOPPED, 2,
            1_760_000_000_000L, 40_000, -1.0, -1.0)
        Problems.checked(ctx, r)
        assertEquals(listOf(r), Problems.checks(ctx))
        Problems.gpuFailed(ctx, GpuFailure(BrainModel.GENERAL, 1L, ProblemStage.WRITING, null, check = GpuVerdict.STOPPED))
        Problems.forgetGpuFailure(ctx)
        assertNull(Problems.gpuFailure(ctx))
        assertEquals(emptyList<CheckRecord>(), Problems.checks(ctx))
        Problems.checked(ctx, r)
        Problems.forgetTpuFailure(ctx)
        assertFalse(checks.exists())
    }
}
