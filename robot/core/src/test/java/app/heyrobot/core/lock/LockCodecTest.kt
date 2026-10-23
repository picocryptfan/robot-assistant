// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.AlarmPhase
import app.heyrobot.core.AlarmWatchState
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.backup.BackupCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class LockCodecTest {
    private val bedtime = PhoneLock("lk_9f2c", "Bedtime", LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)),
        setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)),
        extras = LockExtras(apps = listOf("org.example.reader")))
    private val wake = PhoneLock("lk_31ab", "Wake up", LockRule.Alarm(minutes = 60, days = LockRule.WEEKDAYS), extras = LockExtras(people = listOf("p_mom")))
    private val reading = PhoneLock("lk_77d0", "Reading first", LockRule.App(AppMode.FIRST, "org.example.reader"))
    private val limit = PhoneLock("lk_aa01", "Evenings", LockRule.App(AppMode.LIMIT, "org.example.reader", limitMin = 90, until = LocalTime.of(6, 0)))
    private val alarmStart = PhoneLock("lk_aa02", "After the alarm", LockRule.App(AppMode.FIRST, "org.example.reader", AppStart.Alarm), on = false,
        extras = LockExtras(maps = true, music = "org.example.music"))
    private val file = LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE, endEarly = EndEarly.WAIT, waitMin = 15, warnMin = 10, showLeft = false),
        listOf(bedtime, wake, reading, limit, alarmStart))

    @Test fun roundTrip() {
        assertEquals(LockFileRead.Ok(file), LockCodec.decode(LockCodec.encode(file)))
        assertEquals(LockFileRead.Ok(LockFile()), LockCodec.decode(LockCodec.encode(LockFile())))
    }

    @Test fun theDesignsExampleReads() {
        val text = """{ "v": 1, "on": true, "for": "me", "endEarly": "off", "waitMin": 10, "warnMin": 5, "showLeft": true,
          "locks": [ { "id": "lk_9f2c", "name": "Bedtime", "on": true, "kind": "time", "from": "22:00", "to": "07:00",
              "days": ["SUN","MON","TUE","WED","THU"], "extras": { "people": [], "maps": false, "music": null, "apps": [] } } ] }"""
        val f = LockCodec.decode(text).ok!!
        assertEquals(LockSettings(on = true), f.settings)
        assertEquals(bedtime.copy(extras = LockExtras.NONE), f.locks.single())
    }

    private fun mutate(change: (String) -> String) = LockCodec.decode(change(LockCodec.encode(file)))

    @Test fun strictParsingAndUnknownFieldsAreRefused() {
        assertEquals(LockFileRead.Damaged, mutate { it.replaceFirst("{", "{\"extra\":1,") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"maps\":false", "\"maps\":false,\"web\":true") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"waitMin\":15", "\"waitMin\":7") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"22:00\"", "\"22:00:30\"") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"SUN\"", "\"SUNDAY\"") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"lk_9f2c\"", "\"bedtime\"") })
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"limitMin\":90", "\"limitMin\":90,\"minutes\":20") })
        // A single quote, a comment, a key twice: org.json would read them, strict JSON doesn't.
        assertEquals(LockFileRead.Damaged, mutate { it.replace("\"Bedtime\"", "'Bedtime'") })
        assertEquals(LockFileRead.Damaged, mutate { it.replaceFirst("{", "{\"on\":false,") })
        assertEquals(LockFileRead.Damaged, LockCodec.decode("{\"v\":1} // x"))
        assertEquals(LockFileRead.Damaged, LockCodec.decode(null))
        assertEquals(LockFileRead.Damaged, LockCodec.decode("not json"))
        assertEquals(LockFileRead.TooNew(2), mutate { it.replace("\"v\":1", "\"v\":2") })
    }

    @Test fun capsAndChecks() {
        val many = LockFile(LockSettings(), (1..11).map { bedtime.copy(id = "lk_%04d".format(it)) })
        assertEquals(LockFileRead.Damaged, LockCodec.decode(LockCodec.encode(many)))
        assertEquals(LockFileRead.Damaged, LockCodec.decode(LockCodec.encode(LockFile(LockSettings(), listOf(bedtime, bedtime)))))
        assertEquals(LockFileRead.Damaged, LockCodec.decode(" ".repeat(LockCodec.MAX_BYTES + 1)))
        assertEquals(LockProblem.SAME_TIMES, LockLimits.problem(bedtime.copy(rule = LockRule.Time(TimeWindow(LocalTime.NOON, LocalTime.NOON)))))
        assertEquals(LockProblem.NO_DAYS, LockLimits.problem(bedtime.copy(rule = LockRule.Time(TimeWindow(LocalTime.NOON, LocalTime.MIDNIGHT), emptySet()))))
        assertEquals(LockProblem.NAME, LockLimits.problem(bedtime.copy(name = "x".repeat(41))))
        assertEquals(LockProblem.NAME, LockLimits.problem(bedtime.copy(name = "two\nlines")))
        assertEquals(LockProblem.APPS, LockLimits.problem(bedtime.copy(extras = LockExtras(apps = (1..6).map { "org.example.a$it" }))))
        assertEquals(LockProblem.APPS, LockLimits.problem(bedtime.copy(extras = LockExtras(apps = listOf("not a package")))))
        assertEquals(LockProblem.LENGTH, LockLimits.problem(wake.copy(rule = LockRule.Alarm(minutes = 61))))
        assertNull(LockLimits.problem(bedtime))
        assertEquals("Bedtime", LockLimits.name("  Bedtime "))
        assertNull(LockLimits.name("   "))
    }

    @Test fun stateRoundTripAndAVoiceLock() {
        val s = LockState(
            active = listOf(ActiveLock("lk_31ab", 1_792_134_000_000, 1_792_137_600_000, 98_000_000, 41), ActiveLock(ActiveLock.VOICE, 1_792_140_000_000,
                1_792_161_600_000, 104_000_000, 41, waitFrom = 1_792_140_100_000)),
            skip = mapOf("lk_9f2c" to LocalDate.of(2026, 10, 17)), edit = 5, codeSet = true, code = CodeTries(2, 3),
            alarm = AlarmWatchState(AlarmPhase.SNOOZED, 1, 2, 3), last = LastSeen(4, 5),
            record = listOf(RecordLine(1, RecordEvent.STARTED, "lk_31ab"), RecordLine(2, RecordEvent.GAP, null, 12)),
        )
        assertEquals(s, LockCodec.decodeState(LockCodec.encodeState(s)))
        assertEquals(LockState(), LockCodec.decodeState(LockCodec.encodeState(LockState())))
        assertNull(LockCodec.decodeState("{}"))
        assertNull(LockCodec.decodeState(LockCodec.encodeState(s).replace("\"codeSet\":true", "\"codeSet\":true,\"x\":1")))
        // A record line from a newer Robot is left out, not the whole state.
        val newer = LockCodec.encodeState(s).replace("\"e\":\"gap\"", "\"e\":\"later\"")
        assertEquals(1, LockCodec.decodeState(newer)!!.record.size)
    }

    @Test fun theRecordKeepsThirtyDaysAndTwoHundredLines() {
        var s = LockState()
        for (i in 0 until 250) s = s.recording(RecordLine(i.toLong(), RecordEvent.STARTED))
        assertEquals(LockState.RECORD_MAX, s.record.size)
        assertEquals(50L, s.record.first().t)
        s = s.recording(RecordLine(LockState.RECORD_DAYS_MS + 100, RecordEvent.ENDED))
        assertTrue(s.record.all { it.t > 100 })
    }

    /**
     * §7, §12: files/robot/lock/'s state.json and code.json are in no backup category; phase B puts locks.json in its own,
     * Phone lock, on purpose, and nothing else of that folder.
     */
    @Test fun noBackupCategoryTakesLockFiles() {
        for (c in BackupCategory.entries) for (n in listOf("lock/code.json", "lock/state.json", "lock/lock.lock", "lock/locks.json.bad"))
            assertTrue("$c takes $n", !c.validName(n))
        assertEquals(listOf(BackupCategory.PHONE_LOCK), BackupCategory.entries.filter { it.validName("lock/locks.json") })
        assertEquals(LockCodec.MAX_BYTES.toLong(), BackupCategory.PHONE_LOCK.maxBytes("lock/locks.json"))
        // In restore order right after Routines, on by default, written by the main app.
        assertEquals(BackupCategory.ROUTINES.ordinal + 1, BackupCategory.PHONE_LOCK.ordinal)
        assertTrue(BackupCategory.PHONE_LOCK.defaultOn)
    }
}
