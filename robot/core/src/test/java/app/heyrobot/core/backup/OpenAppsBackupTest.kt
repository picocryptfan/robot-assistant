// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.OpenAppsFile
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * auto-open-plan §4: open_apps.json is a category of its own ("open_apps", appended last, shown in Settings' row), never a
 * ninth Settings file: a 1.3 or older reader turns down the whole Settings category at a name it doesn't know
 * (RestoreReader's maxBytes null), and skips a category it doesn't know by name.
 */
class OpenAppsBackupTest {
    private val cat = BackupCategory.OPEN_APPS

    private fun guard(phone: Boolean, file: Boolean, mine: Boolean) = RestoreSafety.openApps(OpenAppsFile(phone), OpenAppsFile(file), mine)

    @Test fun theSwitchIsItsOwnCategoryAndSettingsStaysAtEight() {
        assertFalse(OpenAppsFile.NAME in BackupCategory.SETTINGS_FILES)
        // A phone with every Settings file backs up within SETTINGS' maxEntries (BackupContainer's "entries").
        assertTrue(BackupCategory.SETTINGS_FILES.size <= BackupCategory.SETTINGS.maxEntries)
        assertEquals(8, BackupCategory.SETTINGS.maxEntries)
        // Appended last: the voice log's category bits follow the order.
        assertEquals(BackupCategory.SKILL_TRUST.ordinal + 1, cat.ordinal)
        assertEquals(1, cat.maxEntries)
        assertEquals(RestoreProcess.MAIN, cat.writer)
        assertTrue(cat.defaultOn)
        assertEquals(BackupCategory.SETTINGS, BackupCategory.coveredBy(cat))
        assertTrue(cat in BackupCategory.covers(BackupCategory.SETTINGS))
        assertTrue(cat.maxBytes(OpenAppsFile.NAME) != null && cat.maxBytes("look.json") == null)
        assertFalse(cat in BackupCategory.ONLY_YOURS)
    }

    @Test fun yourBackupBringsTheSwitchBackEitherWay() {
        assertFalse(guard(phone = true, file = false, mine = true).value.named)
        assertTrue(guard(phone = false, file = true, mine = true).value.named)
    }

    @Test fun someoneElsesNeverTurnsItOn() {
        val g = guard(phone = false, file = true, mine = false)
        assertFalse(g.value.named)
        assertEquals(listOf(SaferField.OPEN_APPS_NAMED), g.kept)
        assertFalse(guard(phone = true, file = false, mine = false).value.named)
    }

    private fun backup(): ByteArray = BackupKit.encrypt(RawRecords().manifest()
        .entry("settings", "look.json", BackupKit.text("{\"v\":1,\"classic\":true}"), v = 1)
        .entry("open_apps", OpenAppsFile.NAME, BackupKit.text(OpenAppsFile.write(OpenAppsFile(named = false))), v = 1)
        .end().bytes())

    /** A 1.3 reader (this one less the category: format 1 and every record rule unchanged) skips it by name, restores Settings. */
    @Test fun anOlderReaderSkipsItByNameAndRestoresTheRest() {
        val f = backup()
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it.id != "open_apps" } }
        assertEquals(listOf(CategorySkip("open_apps", null, SkipWhy.UNKNOWN)), old.skipped)
        assertEquals(setOf(BackupCategory.SETTINGS), old.found.keys)
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
        assertEquals(listOf(BackupCategory.SETTINGS), plan.restoring)
        assertEquals(listOf("open_apps"), plan.skippedByName.map { it.name })
        assertTrue(old.settings.containsKey("look.json"))
        // This Robot: both, the switch as written.
        val now = BackupKit.verify(f)
        assertEquals(emptyList<CategorySkip>(), now.skipped)
        assertEquals(setOf(BackupCategory.SETTINGS, cat), now.found.keys)
        assertEquals(OpenAppsFile(named = false), now.openApps)
        assertEquals(listOf(BackupCategory.SETTINGS, cat), RestorePlanner.plan(now, PhoneHas(emptyMap()), RestorePlanner.defaults(now), mine = true).restoring)
    }

    @Test fun aBackupFromBeforeTicksNothing() {
        // The kit's routines.json is from before "opens" (skills: SkillOpensCodecTest): every open step asks, as before.
        val routines = (RoutineCodec.decode(String(BackupKit.content().getValue(BackupCategory.ROUTINES).getValue("routines.json"))) as RoutineFile.Ok).routines
        assertTrue(routines.isNotEmpty() && routines.all { it.opens.isEmpty() })
    }
}
