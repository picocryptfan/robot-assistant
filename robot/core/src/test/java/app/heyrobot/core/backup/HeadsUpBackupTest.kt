// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.headsup.HeadsUpSettings
import app.heyrobot.core.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** earbud-headsup-c: headsup.json in its own HEADSUP category, appended last, skipped by name by a Robot without it. */
class HeadsUpBackupTest {
    private val emptySkills = app.heyrobot.core.skills.SkillCodec.encode(emptyList())
    private val on = HeadsUpSettings(on = true, minutes = 10)

    private fun backup(headsUp: String? = on.encode()): ByteArray {
        val r = RawRecords().manifest().entry("skills", SkillStore.SKILLS, BackupKit.text(emptySkills))
        if (headsUp != null) r.entry("headsup", BackupCategory.HEADSUP_FILE, BackupKit.text(headsUp), v = 1)
        return BackupKit.encrypt(r.end().bytes())
    }

    /** Pins the category list's end: appended after auto-open's SKILL_OPENS (develop/1.4), since the voice log's category bits follow this order. */
    @Test fun appendedLastWithOneSmallEntry() {
        // Its own place (bit 23), so a category appended later never moves this pin.
        assertEquals(23, BackupCategory.HEADSUP.ordinal)
        assertEquals(BackupCategory.SKILL_OPENS.ordinal + 1, BackupCategory.HEADSUP.ordinal)
        assertEquals(BackupCategory.HEADSUP, BackupCategory.of("headsup"))
        assertEquals("headsup.json", BackupCategory.HEADSUP_FILE)
        assertTrue(BackupCategory.HEADSUP.maxBytes(BackupCategory.HEADSUP_FILE)!! < BackupCaps.UNKNOWN_ENTRY)
        // The events already handled stay on this phone, and Settings keeps its 8 files.
        assertFalse(BackupCategory.HEADSUP.validName("headsup-said.json"))
        assertFalse(BackupCategory.SETTINGS.validName(BackupCategory.HEADSUP_FILE))
        assertFalse(BackupCategory.HEADSUP in BackupCategory.ONLY_YOURS)
    }

    @Test fun theHeadsUpComesBackFromYourOwnBackup() {
        val v = BackupKit.verify(backup())
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(on, v.headsUp)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertTrue(plan.of(BackupCategory.HEADSUP).restores)
        val g = RestoreSafety.headsUp(HeadsUpSettings(), v.headsUp!!, mine = true)
        assertEquals(on, g.value)
        assertEquals(emptyList<SaferField>(), g.kept)
    }

    /** Someone else's backup brings the minutes and quiet hours, never a heads-up on. */
    @Test fun someoneElsesBackupComesBackWithTheHeadsUpOff() {
        val v = BackupKit.verify(backup())
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false)
        assertTrue(plan.of(BackupCategory.HEADSUP).restores)
        val g = RestoreSafety.headsUp(HeadsUpSettings(), v.headsUp!!, mine = false)
        assertFalse(g.value.on)
        assertEquals(10, g.value.minutes)
        assertEquals(listOf(SaferField.HEADSUP_ON), g.kept)
    }

    @Test fun anOlderBackupWithoutItRestoresAsBefore() {
        val v = BackupKit.verify(backup(headsUp = null))
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertNull(v.headsUp)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(PlanSkip.NOT_IN_BACKUP, plan.of(BackupCategory.HEADSUP).skip)
    }

    /** A Robot without the category (an unknown category is skipped by name) restores the rest. */
    @Test fun aRobotWithoutTheCategorySkipsItByName() {
        val f = backup()
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.HEADSUP } }
        assertEquals(listOf(CategorySkip("headsup", null, SkipWhy.UNKNOWN)), old.skipped)
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(listOf("headsup"), plan.skippedByName.map { it.name })
    }

    @Test fun aNewerOrUnversionedFileIsNotRead() {
        assertNull(BackupKit.verify(backup("{\"v\":2,\"on\":true}")).headsUp)
        assertNull(BackupKit.verify(backup("{\"on\":true}")).headsUp)
    }
}
