// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreResultTest {
    // seal-notes-c 5: Restore with a newer Robot's note keeps its reason, not just Failed.
    @Test fun notesTooNewKeepsItsReason() {
        val r = RestoreResult(1_760_270_000_000L, mine = true, outcomes = linkedMapOf(BackupCategory.NOTES to CategoryOutcome.NotesTooNew), keptSafer = 0, madeBy = "1.2.0")
        assertEquals(CategoryOutcome.NotesTooNew, RestoreResult.fromJson(r.toJson())!!.outcomes[BackupCategory.NOTES])
    }

    // seal-people: Restore over a People file this Robot can't read keeps its reason, not just Failed.
    @Test fun peopleTooNewKeepsItsReason() {
        val r = RestoreResult(1_760_270_000_000L, mine = true, outcomes = linkedMapOf(BackupCategory.PEOPLE to CategoryOutcome.PeopleTooNew), keptSafer = 0, madeBy = "1.2.0")
        assertEquals(CategoryOutcome.PeopleTooNew, RestoreResult.fromJson(r.toJson())!!.outcomes[BackupCategory.PEOPLE])
    }

    @Test fun roundTrip() {
        val r = RestoreResult(1_760_270_000_000L, mine = false, outcomes = linkedMapOf(
            BackupCategory.PEOPLE to CategoryOutcome.Done,
            BackupCategory.KEYS to CategoryOutcome.Skipped(PlanSkip.NOT_YOURS),
            BackupCategory.MEMORY to CategoryOutcome.Locked,
            BackupCategory.NOTES to CategoryOutcome.Failed,
            // FX1: Robot Files' commit waiting for the next unlock: not done yet.
            BackupCategory.FILES to CategoryOutcome.AfterUnlock,
            // seal-history-fix-c 1: History that can't be opened whole keeps its reason.
            BackupCategory.HISTORY to CategoryOutcome.HistoryUnreadable(tooNew = true),
            BackupCategory.TRANSCRIPTS to CategoryOutcome.HistoryUnreadable(tooNew = false),
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

    @Test fun keysSayHowManyWereNotSaved() {
        // F46: a key kept on purpose isn't missed; none saved at all is a failure.
        assertEquals(CategoryOutcome.Done, CategoryOutcome.keys(0, 3))
        assertEquals(CategoryOutcome.KeysMissed(1), CategoryOutcome.keys(1, 3))
        assertEquals(CategoryOutcome.Failed, CategoryOutcome.keys(3, 3))
        val r = RestoreResult(1, true, mapOf(BackupCategory.KEYS to CategoryOutcome.KeysMissed(2)))
        val back = RestoreResult.fromJson(r.toJson())!!
        assertEquals(CategoryOutcome.KeysMissed(2), back.outcomes[BackupCategory.KEYS])
        assertEquals(emptySet<BackupCategory>(), back.done)
    }

    /** R2: keys that had nothing here to go with aren't "restored". */
    @Test fun keysWithNothingHereAreNotRestored() {
        assertEquals(CategoryOutcome.Done, CategoryOutcome.keys(0, 0))
        assertEquals(CategoryOutcome.Done, CategoryOutcome.keys(0, 3))
        assertEquals(CategoryOutcome.KeysNoPlace(2), CategoryOutcome.keys(0, 0, noPlace = 2))
        assertEquals(CategoryOutcome.Failed, CategoryOutcome.keys(2, 2))
        assertEquals(CategoryOutcome.Failed, CategoryOutcome.keys(2, 2, noPlace = 1))
        assertEquals(CategoryOutcome.KeysMissed(1), CategoryOutcome.keys(0, 2, noPlace = 1))
        assertEquals(CategoryOutcome.KeysMissed(2), CategoryOutcome.keys(1, 2, noPlace = 1))
        // Memory's private codes came, the helper keys didn't.
        assertEquals(CategoryOutcome.KeysMissed(2), CategoryOutcome.keys(0, 0, noPlace = 2, alsoCame = true))
        for (o in listOf(CategoryOutcome.KeysNoPlace(2), CategoryOutcome.Cut, CategoryOutcome.Refused)) {
            val r = RestoreResult(1, true, mapOf(BackupCategory.KEYS to o), cut = true, pid = 9, filesLeftOut = 3)
            assertEquals(r, RestoreResult.fromJson(r.toJson()))
            assertEquals(emptySet<BackupCategory>(), r.done)
        }
    }

    /** R2's three steps, through the planner: nothing here to go with counts; a key kept on purpose doesn't. */
    @Test fun keysPlanCountsWhatHadNoPlace() {
        val helpers = RestoreMerge.keys(listOf("helper/a", "helper/b"), emptySet(), { false }, mine = true)
        assertEquals(emptyList<String>(), helpers.save)
        assertEquals(2, helpers.noPlace)
        assertEquals(0, RestoreMerge.keys(listOf("helper/a"), setOf("a"), { true }, mine = true).noPlace)
        assertEquals(1, RestoreMerge.connectionKeys(mapOf("c-ha0001" to emptySet()), null, emptyList(), { false }, mine = true).noPlace)
    }

}
