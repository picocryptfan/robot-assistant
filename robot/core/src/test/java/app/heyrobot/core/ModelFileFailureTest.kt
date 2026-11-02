// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit's F57: a file that changed or moved under a load is never recorded against the GPU or the beta TPU build. F58:
 * the first-load space check counts only the cache of the backend about to load.
 */
class ModelFileFailureTest {
    @Test fun aChangedFileIsNotTheDevicesFailing() {
        assertFalse(ModelChoice.deviceFailure(LoadFailure.FILE_CHANGED))
        assertFalse(ModelChoice.deviceFailure(LoadFailure.NO_FILE))
        assertFalse(ModelChoice.deviceFailure(LoadFailure.NO_SPACE))
        assertFalse(ModelChoice.deviceFailure(LoadFailure.STOPPING))
    }

    @Test fun theDevicesOwnFailuresStillCount() {
        for (f in listOf(LoadFailure.ENGINE, LoadFailure.TIMEOUT, LoadFailure.PROCESS, LoadFailure.NOT_ALLOWED)) assertTrue(f.name, ModelChoice.deviceFailure(f))
    }

    @Test fun aMarkForAnotherCopyIsAChangedFile() {
        val m = BrainModel.GENERAL
        val fp = ModelFiles.fingerprint(m.bytes, 1000)
        val other = ModelFiles.fingerprint(m.bytes, 2000)
        assertFalse(LoadCheck.fileChanged(m, fp, "${m.sha256}|$fp\n", null))
        // The move finished: the mark names the inside copy, the plan the outside one.
        assertTrue(LoadCheck.fileChanged(m, other, "${m.sha256}|$fp", null))
        assertTrue(LoadCheck.fileChanged(m, fp, null, null))
    }

    private val f = BrainModel.GENERAL.file
    private val cpu = "${f}_8c1f.xnnpack_cache" to 2_000_000_000L
    private val cpuVision = "${f}_8c1f.vision_encoder.xnnpack_cache" to 100_000_000L
    private val gpuWeights = "${f}_8c1f_mldrift_weight_cache.bin" to 745_000_000L
    private val gpuPrograms = "${f}_8c1f_mldrift_compiled_cache.bin" to 11_000_000L

    @Test fun theGpusCachesAreNotTheCpus() {
        val all = listOf(cpu, cpuVision, gpuWeights, gpuPrograms)
        assertEquals(2_100_000_000L, ModelFiles.cachedBytes(ModelBackend.CPU, all))
        assertEquals(756_000_000L, ModelFiles.cachedBytes(ModelBackend.GPU, all))
        // The general build's GPU caches alone: the CPU still has its whole cache to write.
        val need = ModelFiles.cacheBytes(BrainModel.GENERAL, ModelBackend.CPU) - ModelFiles.cachedBytes(ModelBackend.CPU, listOf(gpuWeights, gpuPrograms))
        assertEquals(BrainModel.GENERAL.cacheBytes, need)
    }

    @Test fun aNameRobotDoesntKnowCountsForBoth() {
        val other = "something_else" to 5L
        assertEquals(5L, ModelFiles.cachedBytes(ModelBackend.CPU, listOf(other)))
        assertEquals(5L, ModelFiles.cachedBytes(ModelBackend.GPU, listOf(other)))
    }

    @Test fun yourOwnFileWithoutItsRecordIsAChangedFile() {
        assertTrue(LoadCheck.fileChanged(BrainModel.OWN, "1|2", null, ownRecord = null))
    }
}
