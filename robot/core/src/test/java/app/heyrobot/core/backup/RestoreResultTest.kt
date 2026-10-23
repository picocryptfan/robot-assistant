// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreResultTest {
    @Test fun roundTrip() {
        val r = RestoreResult(1_760_270_000_000L, mine = false, outcomes = linkedMapOf(
            BackupCategory.PEOPLE to CategoryOutcome.Done,
            BackupCategory.KEYS to CategoryOutcome.Skipped(PlanSkip.NOT_YOURS),
            BackupCategory.MEMORY to CategoryOutcome.Locked,
            BackupCategory.NOTES to CategoryOutcome.Failed,
            // FX1: Robot Files' commit waiting for the next unlock: not done yet.
            BackupCategory.FILES to CategoryOutcome.AfterUnlock,
        ), keptSafer = 4, madeBy = "1.1.0")
        assertEquals(r, RestoreResult.fromJson(r.toJson()))
        assertEquals(setOf(BackupCategory.PEOPLE), r.done)
    }

    /** FX3: Robot Files' commit waiting for free space or the storage, and a restore an earlier such one held back. */
    @Test fun waitingForRoomRoundTrips() {
        for (o in listOf(CategoryOutcome.WaitsForRoom, CategoryOutcome.EarlierWaits)) {
            val r = RestoreResult(1, mine = true, outcomes = mapOf(BackupCategory.FILES to o))
            assertEquals(r, RestoreResult.fromJson(r.toJson()))
            assertEquals(emptySet<BackupCategory>(), r.done)
        }
    }

    @Test fun codesOnlyAndForgiving() {
        assertNull(RestoreResult.fromJson(null))
        assertNull(RestoreResult.fromJson("not json"))
        val r = RestoreResult.fromJson("""{"v":1,"at":5,"mine":true,"c":{"people":"done","nope":"done","notes":"skip:WHATEVER"}}""")!!
        assertEquals(mapOf(BackupCategory.PEOPLE to CategoryOutcome.Done), r.outcomes)
        assertFalse(RestoreResult(0, false, mapOf(BackupCategory.NOTES to CategoryOutcome.Done)).toJson().contains("note-"))
    }
}
