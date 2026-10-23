// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.files.DateKind
import app.heyrobot.core.files.FileDate
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FilesBrief
import app.heyrobot.core.files.Provenance
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * files-1.4-plan.md §7.2 and §5.3 (F10): the brief's files part (BriefItem.FILES), said after your reminders and before the
 * closing, shown without a merchant, and never on a locked phone whatever "On the lock screen" allows, not even held.
 */
class BriefFilesTest {
    private val now = ZonedDateTime.parse("2026-10-22T07:00:00-04:00[America/Toronto]")
    private val hydro = FileEntry(FileId.of("1".repeat(32))!!, "2026-09 Hydro-Québec 142.00 CAD", FileKind.BILL, FileState.FILED,
        Provenance(FileSource.SHARE, 1L, 10L), drawer = "bills",
        fields = mapOf("merchant" to "Hydro-Québec", "total" to "142.00", "currency" to "CAD", "due" to "2026-10-24").mapValues { FileField(it.value, FieldBy.READ) })
    private val files = FilesBrief(listOf(FileKind.RECEIPT, FileKind.RECEIPT, FileKind.BILL), listOf(FileDate(hydro, DateKind.DUE, LocalDate.of(2026, 10, 24))))
    private val items = BriefItem.DEFAULTS + BriefItem.FILES

    private fun input(lang: Lang = Lang.EN) = BriefInput(now, lang, items, events = emptyList(), reminders = listOf("bring the passport"), files = files)

    @Test fun saidAfterYourRemindersAndBeforeTheClosing() {
        val b = BriefComposer.compose(input())
        val order = b.spoken.mapNotNull { it.item }.distinct()
        assertEquals(listOf(BriefItem.GREETING, BriefItem.WEATHER, BriefItem.CALENDAR, BriefItem.REMINDERS, BriefItem.FILES, BriefItem.CLOSING), order)
        val lines = b.spoken.filter { it.item == BriefItem.FILES }
        assertEquals(listOf("Robot filed 3 things yesterday: 2 receipts and 1 bill.", "A bill is due Saturday October 24: \$142.00."), lines.map { it.shown })
        assertEquals("Your Hydro-Québec bill is due Saturday October 24: \$142.00.", lines[1].text)
        assertTrue(lines.all { it.personal })
        // Settings › Routines' own parts don't hold it.
        assertFalse(BriefItem.FILES in BriefItem.DEFAULTS)
        assertEquals(BriefItem.FILES, BriefItem.of("files"))
    }

    @Test fun neverOnALockedPhoneWhateverTheLevel() {
        for (level in BriefLock.entries) {
            val b = BriefComposer.compose(input(), locked = true, level = level)
            assertTrue(level.name, (b.spoken + b.held).none { it.item == BriefItem.FILES })
            assertTrue(level.name, (b.spoken + b.held).none { it.text.contains("Hydro") || it.shown.contains("bill is due") })
            assertFalse(level.name, BriefPrivacy.plan(items, locked = true, level = level).let { it.spoken + it.held }.contains(BriefItem.FILES))
        }
        // Unlocked: said.
        assertTrue(BriefComposer.compose(input(), locked = false).spoken.any { it.item == BriefItem.FILES })
    }

    @Test fun inQuebecFrench() {
        val lines = BriefComposer.compose(input(Lang.FR)).spoken.filter { it.item == BriefItem.FILES }
        assertEquals("Robot a classé 3 fichiers hier : 2 reçus et 1 facture.", lines[0].shown)
        assertEquals("Ta facture d’Hydro-Québec est due le samedi 24 octobre: 142 dollars.", lines[1].text)
    }
}
