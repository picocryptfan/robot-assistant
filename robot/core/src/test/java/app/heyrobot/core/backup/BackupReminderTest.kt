// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §9.5: the monthly reminder's timing, and the two small files' codecs. */
class BackupReminderTest {
    private val day = BackupReminder.DAY_MS
    private val t0 = 1_760_000_000_000L

    @Test fun thirtyDaysThenAtMostOnceAWeek() {
        // Off: never.
        assertFalse(BackupReminder.due(t0 + 100 * day, lastBackup = t0, onSince = t0, lastShown = null, on = false))
        // 30 days after the last backup.
        assertFalse(BackupReminder.due(t0 + 29 * day, lastBackup = t0, onSince = t0, lastShown = null, on = true))
        assertTrue(BackupReminder.due(t0 + 30 * day, lastBackup = t0, onSince = t0, lastShown = null, on = true))
        // Not again within 7 days of the last reminder.
        assertFalse(BackupReminder.due(t0 + 36 * day, lastBackup = t0, onSince = t0, lastShown = t0 + 30 * day, on = true))
        assertTrue(BackupReminder.due(t0 + 37 * day, lastBackup = t0, onSince = t0, lastShown = t0 + 30 * day, on = true))
        // A backup made since: counted from it.
        assertFalse(BackupReminder.due(t0 + 37 * day, lastBackup = t0 + 20 * day, onSince = t0, lastShown = null, on = true))
        // Never backed up: counted from when the switch was turned on.
        assertFalse(BackupReminder.due(t0 + 29 * day, lastBackup = null, onSince = t0, lastShown = null, on = true))
        assertTrue(BackupReminder.due(t0 + 30 * day, lastBackup = null, onSince = t0, lastShown = null, on = true))
        // Turned on after an old backup: 30 days from turning it on.
        assertFalse(BackupReminder.due(t0 + 40 * day, lastBackup = t0 - 100 * day, onSince = t0 + 20 * day, lastShown = null, on = true))
        // A clock set back reminds nothing.
        assertFalse(BackupReminder.due(t0, lastBackup = t0 + day, onSince = 0, lastShown = null, on = true))
        assertEquals(34, BackupReminder.daysSince(t0 + 34 * day + 5, t0))
        assertNull(BackupReminder.daysSince(t0, null))
    }

    @Test fun theFilesReadForwardCompatibly() {
        val p = BackupPrefs(remind = true, remindSince = t0, lastShown = t0 + day)
        assertEquals(p, BackupPrefs.fromJson(p.toJson()))
        assertEquals(BackupPrefs(), BackupPrefs.fromJson(null))
        assertEquals(BackupPrefs(), BackupPrefs.fromJson("not json"))
        assertEquals(BackupPrefs(remind = false), BackupPrefs.fromJson("{\"remind\":\"yes\",\"other\":1}"))

        val s = BackupState().after(t0, setOf(BackupCategory.SETTINGS, BackupCategory.KEYS), 40_000_000, checked = true)
        assertTrue(s.lastKeys)
        assertEquals(s, BackupState.fromJson(s.toJson()))
        // The defaults: Keys and recordings off, pictures on.
        val d = BackupState()
        assertEquals(BackupCategory.entries.toSet() - BackupCategory.SENSITIVE, d.chosen)
        assertFalse(d.recordings)
        assertTrue(d.pictures)
        assertEquals(d, BackupState.fromJson("{\"v\":9,\"chosen\":\"all\",\"lastAt\":-5}"))
        // 1.4: a 1.1 file never offered Files, so it starts at its default (on) rather than as one you left out.
        assertEquals(setOf(BackupCategory.NOTES, BackupCategory.FILES), BackupState.fromJson("{\"chosen\":[\"notes\",\"plans\"]}").chosen)
        // Once offered, unticking it sticks; videos are on until you leave them out.
        val noFiles = BackupState(chosen = setOf(BackupCategory.NOTES), videos = false)
        assertEquals(noFiles, BackupState.fromJson(noFiles.toJson()))
        assertTrue(d.videos)
        // Neither file holds where a backup went.
        assertFalse(s.toJson().contains("uri") || s.toJson().contains("content:"))
    }
}
