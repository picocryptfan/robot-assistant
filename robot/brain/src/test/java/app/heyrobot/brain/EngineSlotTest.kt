// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.app.Application
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** One model in memory in :brain at a time, and the marker that tells the app which one it is (the app never deletes a model in use). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineSlotTest {
    private class Holder(override var destroyedAt: Long = 0) : SlotHolder
    private val app: Application = RuntimeEnvironment.getApplication()
    private val one = Holder()
    private val two = Holder()
    private val marker get() = File(File(app.filesDir, "robot"), ModelFiles.LOADED)
    private val fast = BrainMsg.Load("/x", BrainModel.FAST, ModelBackend.TPU, 4096, ModelFormat.LITERT_LM)
    private val own = BrainMsg.Load("/y", BrainModel.OWN, ModelBackend.CPU, 4096, ModelFormat.GGUF)

    private fun markedModel() = BrainModel.byId(marker.readText().split(' ')[0])

    @Before fun records() { File(app.filesDir, "robot").mkdirs() }

    @After fun free() {
        EngineSlot.release(one, app)
        EngineSlot.release(two, app)
    }

    @Test fun theHolderIsMarkedWithItsModelBackendAndProcess() {
        assertTrue(EngineSlot.claim(one, app, fast))
        assertEquals(BrainLoaded(BrainModel.FAST, ModelBackend.TPU, android.os.Process.myPid()).encode(), marker.readText())
    }

    @Test fun aSecondEngineWaitsAndTheMarkerStays() {
        assertTrue(EngineSlot.claim(one, app, fast))
        assertFalse(EngineSlot.claim(two, app, own))
        assertEquals(BrainModel.FAST, markedModel())
    }

    @Test fun onlyTheHolderLetsGo() {
        assertTrue(EngineSlot.claim(one, app, fast))
        EngineSlot.release(two, app)
        assertTrue(marker.exists())
        assertFalse(EngineSlot.claim(two, app, own))
        EngineSlot.release(one, app)
        assertFalse(marker.exists())
        assertTrue(EngineSlot.claim(two, app, own))
        assertEquals(BrainModel.OWN, markedModel())
    }

    @Test fun theHolderMayChangeItsOwnMarker() {
        assertTrue(EngineSlot.claim(one, app, fast))
        assertTrue(EngineSlot.claim(one, app, own))
        assertEquals(BrainModel.OWN, markedModel())
    }

    @Test fun howLongAgoTheHoldersServiceWentIsKnownOnlyToOthers() {
        assertEquals("nobody holds it", -1L, EngineSlot.holderDestroyedMs(two))
        assertTrue(EngineSlot.claim(one, app, fast))
        assertEquals("it lives", -1L, EngineSlot.holderDestroyedMs(two))
        one.destroyedAt = 1
        assertTrue(EngineSlot.holderDestroyedMs(two) >= 0)
        assertEquals("never of itself", -1L, EngineSlot.holderDestroyedMs(one))
    }
}
