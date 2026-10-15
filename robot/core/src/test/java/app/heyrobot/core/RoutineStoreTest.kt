// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** files/robot/'s routine files (0.8): atomic writes, a damaged file kept aside, and two processes writing under one lock. */
class RoutineStoreTest {
    private val dir: File = Files.createTempDirectory("routines").toFile()
    private val store = RoutineStore(dir)

    @After fun clean() { dir.deleteRecursively() }

    private fun routine(id: String = "r-1", name: String = "Morning", steps: List<RoutineStep> = listOf(RoutineStep.Brief)) =
        Routine(id, name, RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), steps, created = 1_000L)

    @Test fun routinesRoundTripAndTheBriefIsBuiltIn() {
        assertEquals(RoutineFile.Ok(emptyList()), store.file())
        assertTrue(store.save(routine()))
        assertTrue(store.save(routine("r-2", "Evening")))
        assertEquals(listOf("r-1", "r-2"), store.routines().map { it.id })
        // Replaced in place, not added twice.
        assertTrue(store.save(routine(name = "Early")))
        assertEquals(listOf("Early", "Evening"), store.routines().map { it.name })
        // The brief is always there for the triggers, off until you turn it on, and never written to routines.json.
        assertEquals(listOf("r-1", "r-2", BriefRoutine.ID), store.all().map { it.id })
        assertFalse(store.get(BriefRoutine.ID)!!.enabled)
        assertFalse(store.save(BriefRoutine.of(RoutineSettings(briefEnabled = true))))
        assertTrue(store.setEnabled(BriefRoutine.ID, true, 5L))
        assertTrue(store.settings().briefEnabled && store.get(BriefRoutine.ID)!!.enabled)
        assertFalse(File(dir, RoutineStore.ROUTINES).readText().contains(BriefRoutine.ID))
        assertTrue(store.setEnabled("r-2", false, 9L))
        assertEquals(false to 9L, store.get("r-2")!!.let { it.enabled to it.edited })
        assertTrue(store.delete("r-1"))
        assertEquals(listOf("r-2"), store.routines().map { it.id })
        // Nothing half-written is left behind (writeAtomic renames a finished file into place).
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test fun aRoutineThatCantBeSavedIsRefused() {
        assertFalse(store.save(routine(steps = emptyList())))
        assertFalse(store.save(routine(steps = listOf(RoutineStep.Say("")))))
        assertEquals(emptyList<Routine>(), store.routines())
        // At most 50 routines.
        for (i in 1..RoutineCheck.MAX_ROUTINES) assertTrue(store.save(routine("r-$i")))
        assertFalse(store.save(routine("r-51")))
        assertEquals(RoutineCheck.MAX_ROUTINES, store.routines().size)
    }

    @Test fun aDamagedFileIsNeverWrittenOverAndStartingOverKeepsItAside() {
        val f = File(dir, RoutineStore.ROUTINES)
        dir.mkdirs()
        f.writeText("{\"v\":1,\"routines\":[{\"id\":")
        assertEquals(RoutineFile.Damaged, store.file())
        assertEquals(emptyList<Routine>(), store.routines())
        assertFalse(store.save(routine()))
        assertNull(store.edit { it + routine() })
        assertEquals("{\"v\":1,\"routines\":[{\"id\":", f.readText())
        assertTrue(store.reset())
        assertEquals("{\"v\":1,\"routines\":[{\"id\":", File(dir, "routines.json.bad").readText())
        assertFalse(f.exists())
        assertTrue(store.save(routine()))
        assertEquals(1, store.routines().size)
    }

    @Test fun aNewerFileIsLeftAlone() {
        dir.mkdirs()
        val f = File(dir, RoutineStore.ROUTINES)
        f.writeText("{\"v\":2,\"routines\":[]}")
        assertEquals(RoutineFile.TooNew(2), store.file())
        assertFalse(store.save(routine()))
        // Start over doesn't touch a newer file either.
        assertTrue(store.reset())
        assertEquals("{\"v\":2,\"routines\":[]}", f.readText())
    }

    @Test fun aRoutineThatCantBeReadIsKeptInACopyBeforeTheFirstWrite() {
        assertTrue(store.save(routine()))
        val f = File(dir, RoutineStore.ROUTINES)
        // A second routine whose step a later Robot would have to read: dropped whole, never run in part.
        val text = f.readText().replace("]}", ",{\"id\":\"r-9\",\"name\":\"X\",\"trigger\":{\"type\":\"asked\"},\"steps\":[{\"type\":\"teleport\"}],\"created\":1}]}")
        f.writeText(text)
        assertEquals(1, (store.file() as RoutineFile.Ok).dropped)
        assertTrue(store.save(routine("r-2", "Evening")))
        assertEquals(text, File(dir, "routines.json.bad").readText())
        assertEquals(listOf("r-1", "r-2"), store.routines().map { it.id })
    }

    @Test fun theOtherFilesRoundTrip() {
        val rs = RoutineSettings(runLocked = false, online = false, briefEnabled = true, briefItems = listOf(BriefItem.GREETING, BriefItem.CALENDAR),
            briefLock = BriefLock.NOTHING, finishOnUnlock = false, briefLang = BriefLang.FR, briefPrepare = false, clockSilenceMinutes = 15,
            alarmWindow = TimeWindow(LocalTime.of(5, 0), LocalTime.of(11, 0)),
            briefTrigger = RoutineTrigger.At(LocalTime.of(6, 30), setOf(DayOfWeek.MONDAY)), briefOutput = RoutineOutput.HEADSET_ONLY, briefOnline = true)
        assertEquals(rs, store.editSettings { rs })
        assertEquals(rs, store.settings())
        val day = LocalDate.of(2026, 10, 12)
        val card = ReadyCard("r-1", "Morning", RoutineStep.Brief, 7L, day, missed = LocalTime.of(7, 0))
        assertEquals(listOf(card), store.editReady { ReadyCards.add(it, card, day) })
        assertEquals(listOf(card), store.ready())
        assertEquals(1, store.editReminders { BriefReminders.add(it, "bring the passport", day) }!!.size)
        assertEquals("bring the passport", store.reminders().single().text)
        val st = RoutineState(RunLedger().record("r-1", "t:2026-10-12T07:00", day.atTime(7, 0).atZone(java.time.ZoneId.of("America/Toronto"))),
            AlarmWatchState(AlarmPhase.SNOOZED, 10L, 5L, 20L), checked = 99L, runs = mapOf("r-1" to RunRecord(100L, TriggerKind.TIME, 3, 1, 0)))
        assertEquals(st, store.editState { st })
        assertEquals(st, store.state())
        // Delete all: the files go; the settings stay (they are Settings › Routines' own switches).
        assertTrue(store.save(routine()))
        assertTrue(store.deleteAll())
        assertEquals(emptyList<Routine>(), store.routines())
        assertEquals(emptyList<ReadyCard>(), store.ready())
        assertEquals(RoutineState(), store.state())
        assertEquals(rs, store.settings())
    }

    @Test fun routineSettingsReadAsTheirDefaultsWhenAnythingIsMissingOrWrong() {
        assertEquals(RoutineSettings(), RoutineSettingsCodec.decode(null))
        assertEquals(RoutineSettings(), RoutineSettingsCodec.decode("{oops"))
        assertEquals(RoutineSettings(), RoutineSettingsCodec.decode("{}"))
        // Defaults: routines run locked and go online as their cards said; the brief is off, after the alarm, time and weather locked.
        val d = RoutineSettings()
        assertTrue(d.runLocked && d.online && !d.briefEnabled && d.briefTrigger == RoutineTrigger.AlarmDismissed() && d.briefLock == BriefLock.TIME_WEATHER)
        assertTrue(d.finishOnUnlock && d.briefPrepare && d.briefLang == null && d.clockSilenceMinutes == 10 && !d.briefOnline)
        // A device or charging trigger isn't one the brief takes; a window that starts and ends together isn't kept.
        val odd = """{"v":1,"clock_silence_minutes":500,"alarm_window":{"from":"04:00","to":"04:00"},"brief":{"trigger":{"type":"charging","start":true},
            |"items":["weather","nope","weather"],"lock":"sideways","output":"loud","lang":"de"}}""".trimMargin()
        val r = RoutineSettingsCodec.decode(odd)
        assertEquals(d.briefTrigger, r.briefTrigger)
        assertEquals(10, r.clockSilenceMinutes)
        assertEquals(TimeWindow.ALARM, r.alarmWindow)
        assertEquals(listOf(BriefItem.WEATHER), r.briefItems)
        assertEquals(BriefLock.TIME_WEATHER, r.briefLock)
        assertEquals(RoutineOutput.HEADSET_OR_SPEAKER, r.briefOutput)
        assertNull(r.briefLang)
        for (t in listOf(RoutineTrigger.AlarmDismissed(), RoutineTrigger.Unlock(), RoutineTrigger.Asked, RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.ALL)))
            assertTrue(t.toString(), RoutineSettingsCodec.briefTriggerOk(t))
        for (t in listOf(RoutineTrigger.Charging(), RoutineTrigger.Device(DeviceKind.CAR), RoutineTrigger.At(LocalTime.of(7, 0), emptySet())))
            assertFalse(t.toString(), RoutineSettingsCodec.briefTriggerOk(t))
    }

    /** Many writers in this process: every change lands (each one reads the file again under the lock). */
    @Test fun threadsWritingTogetherLoseNothing() {
        val day = LocalDate.of(2026, 10, 12)
        val threads = (1..4).map { t ->
            Thread {
                val s = RoutineStore(dir)
                repeat(5) { i -> s.editReminders { BriefReminders.add(it, "thing $t.$i", day) } }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(20, store.reminders().size)
    }

    /**
     * Two processes, as the main app and :voice are: another JVM adds routines while this one does, both through the lock
     * file. Every routine of both is in the file at the end.
     */
    @Test fun twoProcessesWritingTogetherLoseNothing() {
        val java = System.getProperty("java.home")?.let { File(it, "bin/java") }?.takeIf { it.canExecute() }?.path
        val cp = System.getProperty("java.class.path").orEmpty()
        assumeTrue("needs a java binary and a plain class path", java != null && cp.contains("json"))
        val other = ProcessBuilder(java, "-cp", cp, RoutineStoreWriter::class.java.name, dir.path, "b", "10").redirectErrorStream(true).start()
        repeat(10) { i -> assertTrue(store.save(routine("a-$i", "A $i"))) }
        assertTrue(other.waitFor(60, TimeUnit.SECONDS))
        val out = other.inputStream.bufferedReader().readText()
        assertEquals(out, 0, other.exitValue())
        val ids = store.routines().map { it.id }.toSet()
        assertEquals((0 until 10).map { "a-$it" }.toSet() + (0 until 10).map { "b-$it" }.toSet(), ids)
    }
}

/** The other process of [RoutineStoreTest.twoProcessesWritingTogetherLoseNothing]: saves [n] routines named [prefix]-i in [dir]. */
object RoutineStoreWriter {
    @JvmStatic fun main(args: Array<String>) {
        val store = RoutineStore(File(args[0]))
        repeat(args[2].toInt()) { i ->
            val ok = store.save(Routine("${args[1]}-$i", "${args[1]} $i", RoutineTrigger.Asked, listOf(RoutineStep.Brief), created = 1L))
            if (!ok) { System.err.println("save $i failed"); kotlin.system.exitProcess(1) }
        }
    }
}
