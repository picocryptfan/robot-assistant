// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** F22: a lock file that can't be read is never taken for "no lock", in any process, and nothing is written over it from nothing. */
class LockStateFileTest {
    // review-lock 9: "the emergency dialer is open" outlives a restart of :lock, and only for as long as it was set for.
    @Test fun anOpenEmergencyDialerIsKeptInTheFile() {
        val s = LockState(emergencyUntil = 5_000, dialerUntil = 6_000)
        val back = LockCodec.decodeState(LockCodec.encodeState(s))!!
        assertEquals(5_000L, back.emergencyUntil)
        assertEquals(6_000L, back.dialerUntil)
        assertTrue(LockState.emergencyHeld(5_000, 4_999))
        assertFalse(LockState.emergencyHeld(5_000, 5_000))
        assertFalse(LockState.emergencyHeld(0, 1_000))
        // The clock set back doesn't stretch it.
        assertFalse(LockState.emergencyHeld(LockState.EMERGENCY_OPEN_MS + 2, 1))
        // None open: the file holds no such key, so an older Robot still reads it.
        assertFalse(LockCodec.encodeState(LockState()).contains("emergencyUntil"))
    }

    @get:Rule val tmp = TemporaryFolder()
    private val f by lazy { File(tmp.root, "state.json") }
    private val voice = ActiveLock(ActiveLock.VOICE, 1_000, 5_000)
    private val on = LockState(active = listOf(voice), codeSet = true)

    private fun damage() = f.writeText("{ not state")
    /** A read error: there is something by that name, and it won't read. */
    private fun unreadable() { f.delete(); f.mkdir() }

    @Test fun aFreshFileIsNoLocks() {
        val s = LockStateFile(f)
        assertEquals(LockState(), s.stateOrNull())
        assertEquals(on, s.edit { on })
        assertEquals(on, LockStateFile(f).stateOrNull())
    }

    @Test fun aDamagedFileWithAKnownStateKeepsItAndPutsItBack() {
        val s = LockStateFile(f)
        s.edit { on }
        damage()
        assertEquals(on, s.state())
        assertNull(s.stateOrNull())
        val edited = s.edit { it.copy(edit = 9) }
        assertEquals(on.copy(edit = 9), edited)
        assertEquals(edited, LockCodec.decodeState(f.readText()))
    }

    @Test fun aDamagedFileIsWrittenBackEvenWhenTheEditChangesNothing() {
        val s = LockStateFile(f)
        s.edit { on }
        damage()
        s.edit { it }
        assertEquals(on, LockCodec.decodeState(f.readText()))
    }

    @Test fun anotherProcessSeesUnreadableAndAsksForTheCode() {
        LockStateFile(f).edit { on }
        damage()
        val other = LockStateFile(f)
        assertNull(other.stateOrNull())
        assertTrue(other.state().codeSet)
        assertTrue(other.state().active.isEmpty())
    }

    @Test fun aReadErrorInAProcessThatKnowsNothingWritesNothing() {
        unreadable()
        val s = LockStateFile(f)
        var asked = false
        assertThrows(LockStateFile.Unreadable::class.java) { s.edit { asked = true; on } }
        assertFalse(asked)
        assertTrue(f.isDirectory)
        // The read works again: nothing was lost.
        f.delete()
        LockStateFile(f).edit { on }
        assertEquals(on, s.edit { it })
    }

    @Test fun aReadErrorWithAKnownStateWorksFromIt() {
        val s = LockStateFile(f)
        s.edit { on }
        f.delete(); f.mkdir()
        assertEquals(on, s.state())
        // What other processes go by stays "can't be read", so they fail closed.
        assertNull(s.stateOrNull())
        assertEquals(on, s.edit { it })
    }

    @Test fun damageNobodyCanReadIsKeptAsideAndCountsAsACodeSet() {
        damage()
        val s = LockStateFile(f)
        val next = s.edit { it }
        assertTrue(next.codeSet)
        assertEquals("{ not state", File(tmp.root, "state.json.bad").readText())
        assertEquals(next, LockCodec.decodeState(f.readText()))
    }

    @Test fun aFullDiskStillKeepsTheNewState() {
        val s = LockStateFile(f)
        s.edit { on }
        f.delete(); f.mkdir() // Neither reads nor takes a write.
        val next = s.edit { it.copy(edit = 3) }
        assertEquals(on.copy(edit = 3), next)
        assertTrue(s.unsavedState)
        assertEquals(next, s.stateOrNull())
    }

    // ------------------------------------------------------------------------------------------------ LockView

    private fun LockView.on(ms: Long) = on(Clocks(java.time.Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC), 0, null))

    @Test fun theOtherProcessesEndALockAsLockDoes() {
        // F23: a lock by voice that ends on the elapsed-time clock. The wall clock moved forward past its end.
        val a = ActiveLock(ActiveLock.VOICE, 1_000, 5_000, endElapsed = 700, boot = 3)
        val v = LockView.of(LockState(active = listOf(a)), LockFileRead.Ok(locks), 100)
        val past = java.time.Instant.ofEpochMilli(6_000).atZone(ZoneOffset.UTC)
        assertTrue(v.on(Clocks(past, 600, 3)))
        assertFalse(v.on(Clocks(past, 700, 3)))
        // After a restart the elapsed time means nothing: the wall clock decides.
        assertFalse(v.on(Clocks(past, 600, 4)))
        assertFalse(v.on(Clocks(past, 600, null)))
    }

    private val locks = LockFile(LockSettings(on = true))
    private val hour = 3_600_000L

    @Test fun whatTheOtherProcessesSee() {
        // Both read: as they say, and kept.
        val v = LockView.of(on, LockFileRead.Ok(locks), 100)
        assertTrue(v.exact)
        assertTrue(v.on(4_000))
        assertFalse(v.on(5_000))
        assertFalse(v.someoneElse)
        // Phone lock off: no lock, whatever state.json says.
        // fb11: car mode your car started covers nothing during your own lock, and only your own, and only while it lasts.
        assertEquals(false, v.carOwn(4_000))
        val car = on.copy(car = 1_000)
        val mine = locks.copy(settings = locks.settings.copy(forWho = LockFor.ME))
        assertEquals(true, LockView.of(car, LockFileRead.Ok(mine), 100).carOwn(4_000))
        assertEquals(false, LockView.of(car, LockFileRead.Ok(mine), 100).carOwn(1_000 + LockState.CAR_MAX_MS))
        assertEquals(false, LockView.of(car, LockFileRead.Ok(mine.copy(settings = mine.settings.copy(forWho = LockFor.SOMEONE_ELSE))), 100).carOwn(4_000))
        assertEquals(false, LockView.of(null, LockFileRead.Ok(mine), 100).carOwn(4_000))
        val off = LockView.of(on, LockFileRead.Ok(LockFile()), 100)
        assertTrue(off.exact)
        assertFalse(off.on(4_000))
        assertFalse(LockView.of(null, LockFileRead.Ok(LockFile()), 100).on(4_000))
    }

    @Test fun stateUnreadableIsOnAndNeverKept() {
        val v = LockView.of(null, LockFileRead.Ok(locks), 100)
        assertFalse(v.exact)
        assertTrue(v.on(100 + 23 * hour))
        assertTrue(v.someoneElse)
        assertNull(v.file)
        // Nothing it could hold lasts longer than the cap, so damage never locks the panel for good.
        assertFalse(v.on(100 + 24 * hour))
    }

    @Test fun locksUnreadableCountsTheLocksInState() {
        val v = LockView.of(on, LockFileRead.Damaged, 100)
        assertFalse(v.exact)
        assertTrue(v.on(4_000))
        assertFalse(v.on(5_000))
        assertTrue(v.someoneElse)
        assertTrue(v.apps.isEmpty())
        assertFalse(v.maps)
        assertFalse(LockView.of(LockState(), LockFileRead.Damaged, 100).on(4_000))
        assertTrue(LockView.of(on, LockFileRead.TooNew(99), 100).on(4_000))
    }

    @Test fun nothingReadIsOnOnlyWhenThereIsAFile() {
        assertTrue(LockView.unknown(100).on(200))
        assertFalse(LockView.unknown(0).on(200))
    }

    // ------------------------------------------------------------------------------------------------ the maps app

    @Test fun theMapsAppIsSettledAsTheFirstLockStartsAndKeptUntilTheLastEnds() {
        // F24.
        var default: String? = "org.example.maps"
        val s = LockStateFile(f) { default }
        assertNull(s.edit { it.copy(edit = 1) }.mapsApp)
        assertEquals("org.example.maps", s.edit { it.copy(active = listOf(voice)) }.mapsApp)
        default = "org.example.other"
        assertEquals("org.example.maps", s.edit { it.copy(edit = 2) }.mapsApp)
        assertEquals("org.example.maps", s.edit { it.copy(active = it.active + ActiveLock("lk_9f2c", 1, 2)) }.mapsApp)
        assertEquals("org.example.maps", LockCodec.decodeState(f.readText())?.mapsApp)
        assertEquals("org.example.maps", s.edit { it.copy(active = listOf(voice)) }.mapsApp)
        assertNull(s.edit { it.copy(active = emptyList()) }.mapsApp)
        assertEquals("org.example.other", s.edit { it.copy(active = listOf(voice)) }.mapsApp)
    }

    @Test fun theOtherProcessesOpenTheAppSettledNotTheDefaultNow() {
        val l = PhoneLock("lk_9f2c", "Reading", LockRule.Time(app.heyrobot.core.TimeWindow(java.time.LocalTime.NOON, java.time.LocalTime.MIDNIGHT)), extras = LockExtras(maps = true))
        val st = LockState(active = listOf(ActiveLock("lk_9f2c", 1_000, 5_000)), mapsApp = "org.example.maps")
        val v = LockView.of(st, LockFileRead.Ok(LockFile(LockSettings(on = true), listOf(l))), 100)
        assertEquals(setOf("org.example.maps"), v.apps)
        assertTrue(v.maps)
    }

    @Test fun anUnknownPackageInStateDamagesIt() {
        assertNull(LockCodec.decodeState(LockCodec.encodeState(LockState()).replace(Regex(""""mapsApp":null"""), """"mapsApp":"not a package"""")))
    }

    // ------------------------------------------------------------------------------------------------ :lock's refresh

    private val t0 = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, ZoneOffset.UTC)
    private fun clocks(ms: Long) = Clocks(t0.plusNanos(ms * 1_000_000), ms, 1)
    private val ms0 = t0.toInstant().toEpochMilli()

    @Test fun locksUnreadableHoldsWhatIsOnUntilItsEnd() {
        val a = ActiveLock("lk_9f2c", ms0, ms0 + hour)
        val st = LockState(active = listOf(a))
        val held = LockSchedule.refresh(null, st, clocks(1_000), hold = true)
        assertEquals(listOf(a), held.state.active)
        assertTrue(held.ended.isEmpty() && held.started.isEmpty())
        val over = LockSchedule.refresh(null, st, clocks(hour), hold = true)
        assertEquals(listOf(a), over.ended)
        // Phone lock really off: every lock goes at once, as it always did.
        assertTrue(LockSchedule.refresh(null, st, clocks(1_000)).state.active.isEmpty())
    }

    @Test fun aHeldLockAlwaysHasAWayOut() {
        assertEquals(EndEarly.CODE, LockSchedule.heldSettings(LockState(codeSet = true)).endEarly)
        assertEquals(EndEarly.WAIT, LockSchedule.heldSettings(LockState()).endEarly)
    }
}
