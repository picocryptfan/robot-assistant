// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.EventKind
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.lists.SoftCipher
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

// H3: private events (the Calendar tab): the file, its edits, a restore's merge, the sealed envelope, the agenda's days,
// which events Robot may change, leave-by's place, and their backup category.
class PrivateEventsTest {
    private val zone = ZoneId.of("America/Halifax")
    private val day = LocalDate.of(2026, 11, 5)
    private fun at(h: Int, d: LocalDate = day) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: String, title: String = "Dentist", h: Int = 9, changed: Long = 1, place: String? = null) =
        PrivateEvent(id.padEnd(32, '0'), title, at(h), at(h + 1), false, place, changed)

    @Test fun roundTripsAndKeepsWhatANewerRobotAdded() {
        val f = PrivateEventsFile(listOf(ev("a", place = "12 Main St"), ev("b", "Lunch", 12)))
        val r = PrivateEventsCodec.decode(PrivateEventsCodec.encode(f)) as PrivateRead.Ok
        assertEquals(f.events, r.file.events)
        val withExtra = PrivateEventsCodec.encode(f).replaceFirst("{", "{\"later\":7,")
        val again = (PrivateEventsCodec.decode(withExtra) as PrivateRead.Ok).file
        assertTrue(PrivateEventsCodec.encode(again).contains("\"later\":7"))
    }

    @Test fun refusesNewerAndDamagedFiles() {
        assertEquals(PrivateRead.TooNew(2), PrivateEventsCodec.decode("{\"v\":2,\"events\":[]}"))
        assertEquals(PrivateRead.Damaged, PrivateEventsCodec.decode("not json"))
        assertEquals(PrivateRead.Damaged, PrivateEventsCodec.decode(null))
    }

    @Test fun editsRefuseWhatCantBeKept() {
        val f = PrivateEventsFile()
        assertEquals(PrivateRefusal.NO_TITLE, PrivateEdits.save(f, ev("a", "   "), 5).refused)
        assertEquals(PrivateRefusal.ENDS_BEFORE, PrivateEdits.save(f, ev("a").copy(end = at(8)), 5).refused)
        val saved = PrivateEdits.save(f, ev("a", "  Dentist \n visit ", place = "  "), 5)
        assertNull(saved.refused)
        assertEquals("Dentist visit", saved.file.events.single().title)
        assertNull(saved.file.events.single().place)
        assertEquals(5L, saved.file.events.single().changed)
        val changed = PrivateEdits.save(saved.file, ev("a", "Dentist, moved", 14), 6).file
        assertEquals(listOf("Dentist, moved"), changed.events.map { it.title })
        assertEquals(PrivateRefusal.NOT_FOUND, PrivateEdits.delete(changed, "f".repeat(32)).refused)
        assertTrue(PrivateEdits.delete(changed, changed.events.single().id).file.events.isEmpty())
        val full = PrivateEventsFile(List(PrivateEventsCodec.MAX_EVENTS) { ev(it.toString(16)) })
        assertEquals(PrivateRefusal.FULL, PrivateEdits.save(full, ev("new"), 9).refused)
        assertTrue(PrivateEventsCodec.validId(PrivateEdits.newId()))
    }

    @Test fun addKeepsBothAndTheNewerChangeReplaceTakesTheBackups() {
        val have = PrivateEventsFile(listOf(ev("a", "Old", changed = 1), ev("b", "Mine only")))
        val backup = PrivateEventsFile(listOf(ev("a", "New", changed = 2), ev("c", "From the backup")))
        assertEquals(setOf("New", "Mine only", "From the backup"), PrivateMerge.merge(have, backup, replace = false).events.map { it.title }.toSet())
        assertEquals("Old", PrivateMerge.merge(have, backup.copy(events = listOf(ev("a", "Older", changed = 0))), false).events.first { it.id.startsWith("a") }.title)
        assertEquals(setOf("New", "From the backup"), PrivateMerge.merge(have, backup, replace = true).events.map { it.title }.toSet())
    }

    @Test fun theEnvelopeOpensOnlyWithItsKeyAndUnchanged() {
        val c = SoftCipher(PrivateEnvelope.ALIAS)
        val plain = "private".toByteArray()
        val sealed = PrivateEnvelope.seal(plain, c)
        assertEquals(PrivateEnvelope.ALIAS, PrivateEnvelope.alias(sealed))
        assertArrayEquals(plain, PrivateEnvelope.open(sealed) { c })
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("private"))
        for (bad in listOf(sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, PrivateEnvelope.seal(plain, SoftCipher("robot_lists_v1")))) {
            try { PrivateEnvelope.open(bad) { c }; fail() } catch (_: Exception) {}
        }
        try { PrivateEnvelope.open(sealed) { null }; fail() } catch (e: MemoryKeyException) { assertEquals(KeyProblem.GONE, e.problem) }
    }

    @Test fun agendaDaysPutAllDayFirstAndJoinPrivateEvents() {
        val phone = listOf(CalendarEvent(at(10), at(11), false, "Call", null, id = 4), CalendarEvent(at(0), at(0, day.plusDays(1)), true, "Holiday", null, id = 5))
        val joined = CalendarAgenda.join(phone, listOf(ev("a", "Dentist", 9)))
        val days = CalendarAgenda.days(joined, day, zone, 3)
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(2)), days.map { it.date })
        assertEquals(listOf("Holiday", "Dentist", "Call"), days[0].events.map { it.title })
        assertTrue(days[2].events.isEmpty())
        assertEquals(EventKind.PRIVATE, joined.first { it.title == "Dentist" }.kind)
        assertEquals(listOf("Dentist"), CalendarAgenda.join(null, listOf(ev("a"))).map { it.title })
    }

    @Test fun robotChangesOnlyPrivateEventsAndTheOnesItAdded() {
        val private = ev("a").asEvent()
        val added = CalendarEvent(at(10), at(11), false, "Added", null, id = 7)
        val other = CalendarEvent(at(10), at(11), false, "Someone's", null, id = 8)
        assertTrue(CalendarAgenda.editable(private, emptySet()))
        assertTrue(CalendarAgenda.editable(added, setOf(7L)))
        assertFalse(CalendarAgenda.editable(other, setOf(7L)))
        assertFalse(CalendarAgenda.editable(CalendarEvent(at(10), at(11), false, "No id", null), setOf(0L)))
    }

    @Test fun leaveByIsThePlaceAndAMapsLinkNothingComputed() {
        assertEquals("12 Main St, Halifax", LeaveBy.place(CalendarEvent(at(9), at(10), false, "x", "  12 Main St,\n Halifax ")))
        assertNull(LeaveBy.place(CalendarEvent(at(9), at(10), false, "x", "  ")))
        assertNull(LeaveBy.place(null))
        assertEquals("geo:0,0?q=12%20Main%20St%2C%20Halifax", LeaveBy.geo("12 Main St, Halifax"))
    }

    @Test fun backupCategoryIsYoursOnlyAndTakesOnlyItsFile() {
        val c = BackupCategory.CALENDAR
        // Appended after Robot to Robot's; H5's Today page comes after it.
        assertEquals(listOf(BackupCategory.LINKS, c, BackupCategory.HOME), BackupCategory.entries.takeLast(3))
        assertTrue(c in BackupCategory.ONLY_YOURS)
        assertFalse(c in BackupCategory.SENSITIVE)
        assertTrue(c.validName(PrivateEventsCodec.FILE))
        assertFalse(c.validName("events.enc"))
        assertEquals(PrivateEventsCodec.MAX_FILE, c.maxBytes(PrivateEventsCodec.FILE))
    }
}
