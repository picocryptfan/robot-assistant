// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The audit's F18: a checked model outside Robot's own storage waits for room, says so, and a download reserves that room. */
class ModelNeedsMoveTest {
    private val fast = BrainModel.FAST
    private val smarter = BrainModel.SMARTER

    @Test fun aChosenModelWaitingToMoveFallsBackToFastAndSaysWhy() {
        val d = ModelChoice.decide(smarter, ModelAvailability.NEEDS_MOVE, ModelAvailability.READY, false)
        assertEquals(ModelChoice.Decision.Load(fast, smarter, FallbackWhy.NEEDS_MOVE), d)
        val said = ModelChoice.fallbackPhrase(smarter, FallbackWhy.NEEDS_MOVE).en()
        assertTrue(said, said.contains("Robot's own storage"))
        assertTrue(said, said.contains(ModelLogic.gbText(ModelMove.spaceToMove(smarter.bytes)).en()))
        assertTrue(said, said.contains("open the Robot app"))
    }

    @Test fun fastWaitingToMoveIsNotCalledMissing() {
        val d = ModelChoice.decide(fast, ModelAvailability.NEEDS_MOVE, ModelAvailability.NEEDS_MOVE, false) as ModelChoice.Decision.NoLoad
        assertTrue(d.openApp)
        assertTrue(d.problem, d.problem.contains("its own storage"))
        assertTrue(d.problem, !d.problem.contains("isn't on this phone"))
    }

    @Test fun yourOwnFileWaitingToMoveHasNoMadeUpSize() {
        val said = ModelChoice.fallbackPhrase(BrainModel.OWN, FallbackWhy.NEEDS_MOVE).en()
        assertTrue(said, said.contains("room for the file"))
    }

    @Test fun aDownloadReservesRoomForTheCopyIn() {
        val m = BrainModel.GENERAL
        val need = ModelFiles.downloadNeedBytes(m)
        assertTrue(need >= m.bytes * 2 + ModelMove.MARGIN)
        assertTrue(need >= ModelFiles.needBytes(m))
        assertNull(ModelLogic.refuseDownloadText(m, ModelStatus.MISSING, need, 0))
        val refused = ModelLogic.refuseDownloadText(m, ModelStatus.MISSING, need - 1, 0)
        assertNotNull(refused)
        // After such a download, there is room for its check to copy it in.
        assertTrue(ModelMove.room(m.bytes, need - m.bytes))
    }

    @Test fun anImportNeedsNoRoomForACopy() {
        val m = BrainModel.GENERAL
        assertNull(ModelLogic.refuseImportText(m, ModelStatus.MISSING, m.bytes, ModelFiles.needBytes(m), 0))
    }

    @Test fun theSpaceNeededSaysSoWhenTheCopyMakesIt() {
        val t = ModelLogic.refuseDownloadText(fast, ModelStatus.MISSING, 0, 0)!!.en()
        assertTrue(t, t.contains("twice"))
    }
}
