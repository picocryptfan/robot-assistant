// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.Moment
import app.heyrobot.core.MomentReminder
import app.heyrobot.core.MomentReminders
import app.heyrobot.core.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * moments-backup (review-develop-14 L3): moments.json in its own MOMENTS category, appended last and shown in Routines' row.
 * Not in ROUTINE_FILES: 1.3 refuses a known category whole when it holds a name it doesn't know, and skips an unknown
 * category by name when each entry is under 1 GiB.
 */
class MomentsBackupTest {
    private val emptySkills = app.heyrobot.core.skills.SkillCodec.encode(emptyList())
    private val car = Moment.entries.first()
    private val other = Moment.entries.last()
    private val milk = MomentReminder("m1", "buy milk", car, 1000L, heldUntil = 99L)
    private val keys = MomentReminder("m2", "take the keys", other, 2000L)
    private val list = listOf(milk, keys)

    private fun backup(moments: String? = MomentReminders.encode(list)): ByteArray {
        val r = RawRecords().manifest().entry("skills", SkillStore.SKILLS, BackupKit.text(emptySkills))
        if (moments != null) r.entry("moments", BackupCategory.MOMENTS_FILE, BackupKit.text(moments), v = 1)
        return BackupKit.encrypt(r.end().bytes())
    }

    /** Pins the category list's end: appended after HEADSUP, since the voice log's category bits follow this order. */
    @Test fun appendedLastWithOneSmallEntryInRoutinesRow() {
        // Its own place (bit 24), so a category appended later never moves this pin (LOGBOOK's: BackupCategoryBitsTest).
        assertEquals(24, BackupCategory.MOMENTS.ordinal)
        assertEquals(BackupCategory.HEADSUP.ordinal + 1, BackupCategory.MOMENTS.ordinal)
        assertEquals(BackupCategory.MOMENTS, BackupCategory.of("moments"))
        assertEquals("moments.json", BackupCategory.MOMENTS_FILE)
        assertTrue(BackupCategory.MOMENTS.maxBytes(BackupCategory.MOMENTS_FILE)!! < BackupCaps.UNKNOWN_ENTRY)
        // Routines keep their 3 files (1.3 would refuse a fourth), and the row covers both.
        assertFalse(BackupCategory.ROUTINES.validName(BackupCategory.MOMENTS_FILE))
        assertEquals(BackupCategory.ROUTINES, BackupCategory.coveredBy(BackupCategory.MOMENTS))
        assertTrue(BackupCategory.MOMENTS in BackupCategory.covers(BackupCategory.ROUTINES))
        assertFalse(BackupCategory.MOMENTS in BackupCategory.ROWS)
        // Someone else's reminders never speak on this phone, as the brief's reminders don't.
        assertTrue(BackupCategory.MOMENTS in BackupCategory.ONLY_YOURS)
    }

    @Test fun aBackupWithMomentsRestoresThem() {
        val v = BackupKit.verify(backup())
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(list, v.moments)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertTrue(plan.of(BackupCategory.MOMENTS).restores)
        assertEquals(RestoreMode.FRESH, plan.of(BackupCategory.MOMENTS).mode)
        // A car-off wait held on the old phone starts over here.
        assertEquals(listOf(milk.copy(heldUntil = null), keys), RestoreMerge.moments(emptyList(), v.moments!!, RestoreMode.FRESH))
    }

    @Test fun someoneElsesBackupLeavesThemOut() {
        val v = BackupKit.verify(backup())
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false)
        assertEquals(PlanSkip.NOT_YOURS, plan.of(BackupCategory.MOMENTS).skip)
    }

    @Test fun addKeepsThisPhonesAndAddsTheRestOnce() {
        val here = MomentReminder("m1", "Buy milk", car, 500L)
        val mine = MomentReminder("h1", "water the plants", other, 600L)
        val out = RestoreMerge.moments(listOf(here, mine), list, RestoreMode.ADD)
        assertEquals(listOf(here, mine, keys), out)
        assertEquals(list.map { it.copy(heldUntil = null) }, RestoreMerge.moments(listOf(mine), list, RestoreMode.REPLACE))
        val many = (1..30).map { MomentReminder("x$it", "thing $it", car, it.toLong()) }
        assertEquals(MomentReminders.MAX, RestoreMerge.moments(emptyList(), many, RestoreMode.ADD).size)
        // An id already here gets a new one rather than overwriting this phone's reminder.
        val clash = RestoreMerge.moments(listOf(mine), listOf(MomentReminder("h1", "feed the cat", car, 1L)), RestoreMode.ADD)
        assertEquals(2, clash.size)
        assertEquals(2, clash.map { it.id }.toSet().size)
    }

    @Test fun aBackupWithoutMomentsRestoresAsBefore() {
        val v = BackupKit.verify(backup(moments = null))
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertNull(v.moments)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(PlanSkip.NOT_IN_BACKUP, plan.of(BackupCategory.MOMENTS).skip)
    }

    /** release/1.3's reader: a category it doesn't know is skipped by name when its entries are under 1 GiB; the rest restores. */
    @Test fun release13SkipsTheCategoryByName() {
        val f = backup()
        assertTrue(BackupCategory.MOMENTS.maxBytes(BackupCategory.MOMENTS_FILE)!! < 1L shl 30)
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.MOMENTS } }
        assertEquals(listOf(CategorySkip("moments", null, SkipWhy.UNKNOWN)), old.skipped)
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(listOf("moments"), plan.skippedByName.map { it.name })
    }

    @Test fun aNewerOrUnversionedFileIsNotRead() {
        val newer = BackupKit.verify(backup("{\"v\":2,\"moments\":[]}"))
        assertNull(newer.moments)
        assertEquals(SkipWhy.NEWER, newer.skipped.single().why)
        assertNull(BackupKit.verify(backup("{\"moments\":[]}")).moments)
        assertNull(BackupKit.verify(backup("not json")).moments)
    }

    /** moments-backup-c (L4): one damaged entry refuses the file whole, with the note other refused categories get. */
    @Test fun aDamagedEntryRefusesTheWholeFile() {
        val bads = listOf<Any>(
            org.json.JSONObject().put("id", "m3").put("text", "x").put("moment", "no-such-moment"),
            org.json.JSONObject().put("id", "m4").put("moment", car.id),
            org.json.JSONObject().put("id", "m5").put("text", "y").put("moment", car.id).put("from", "25:99").put("to", "04:00"),
            "not an entry")
        for (bad in bads) {
            val f = org.json.JSONObject(MomentReminders.encode(list)).also { it.getJSONArray("moments").put(bad) }.toString()
            val v = BackupKit.verify(backup(f))
            assertNull(v.moments)
            assertEquals(SkipWhy.REFUSED, v.skipped.single().why)
        }
    }
}
