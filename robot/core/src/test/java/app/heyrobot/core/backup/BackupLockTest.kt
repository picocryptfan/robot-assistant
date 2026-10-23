// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.TimeWindow
import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 1.1, Phone lock's backup category (docs/plans/phone-lock-design.md §12, §15): locks.json only; restored locks arrive
 * off, yours come back as you left them from a backup marked as your own, someone else's always wait for a new code; Phone
 * lock's own switch keeps this phone's value; with a code on this phone, restoring the category needs it.
 */
class BackupLockTest {
    private val v by lazy { BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords())) }
    private fun lock(id: String, on: Boolean = true) = PhoneLock(id, id, LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0))), on)
    private val mine = LockFile(LockSettings(on = true), listOf(lock("lk_bed0"), lock("lk_nap0", on = false)))
    private val child = LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE, endEarly = EndEarly.CODE), listOf(lock("lk_bed0")))

    @Test fun locksJsonGoesAndComesBackWhole() {
        assertEquals("Bedtime", v.phoneLock!!.locks.single().name)
        assertEquals(1L, v.found.getValue(BackupCategory.PHONE_LOCK).entries)
        // A locks.json Phone lock can't read whole refuses its category.
        val bad = BackupKit.content().toMutableMap().apply { put(BackupCategory.PHONE_LOCK, mapOf(BackupCategory.LOCK_FILE to "{\"v\":1}".toByteArray())) }
        val r = BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords(bad)))
        assertNull(r.phoneLock)
        assertEquals(SkipWhy.REFUSED, r.skipped.single { it.category == BackupCategory.PHONE_LOCK }.why)
        // One from a newer Robot waits for it.
        val newer = BackupKit.content().toMutableMap().apply {
            put(BackupCategory.PHONE_LOCK, mapOf(BackupCategory.LOCK_FILE to LockCodec.encode(mine).replace("\"v\":1", "\"v\":2").toByteArray()))
        }
        assertEquals(SkipWhy.NEWER, BackupKit.verify(BackupKit.encrypt(BackupKit.contentRecords(newer))).skipped.single { it.category == BackupCategory.PHONE_LOCK }.why)
    }

    @Test fun withALockCodeHereRestoringNeedsIt() {
        val asks = RestorePlanner.plan(v, PhoneHas(emptyMap(), lockCode = true), RestorePlanner.defaults(v), mine = true)
        assertEquals(PlanSkip.NEEDS_CODE, asks.of(BackupCategory.PHONE_LOCK).skip)
        val ok = RestorePlanner.plan(v, PhoneHas(emptyMap(), lockCode = true), RestorePlanner.defaults(v) + (BackupCategory.PHONE_LOCK to CategoryChoice(codeOk = true)), mine = true)
        assertTrue(ok.of(BackupCategory.PHONE_LOCK).restores)
        assertTrue(RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false).of(BackupCategory.PHONE_LOCK).restores)
        assertTrue(BackupWords.skip(asks.of(BackupCategory.PHONE_LOCK), "1.1.0")!!.en().contains("lock code"))
    }

    @Test fun restoredLocksArriveOff() {
        // Not marked as yours: every lock off, and Phone lock keeps this phone's value (off here).
        val r = RestoreSafety.phoneLock(null, mine, mine = false, phoneCode = false, add = false)
        assertTrue(r.locks.none { it.on })
        assertFalse(r.settings.on)
        // Yours: your own locks as you left them; Phone lock still this phone's value.
        val own = RestoreSafety.phoneLock(LockFile(LockSettings(on = true)), mine, mine = true, phoneCode = false, add = false)
        assertEquals(listOf(true, false), own.locks.map { it.on })
        assertTrue(own.settings.on)
    }

    @Test fun locksForSomeoneElseWaitForANewCode() {
        val r = RestoreSafety.phoneLock(null, child, mine = true, phoneCode = false, add = false)
        assertTrue(r.locks.none { it.on })
        // No code on this phone: back to yourself, End early without the code, as removing the code does.
        assertEquals(LockFor.ME, r.settings.forWho)
        assertEquals(EndEarly.OFF, r.settings.endEarly)
        val coded = RestoreSafety.phoneLock(null, child, mine = true, phoneCode = true, add = false)
        assertEquals(LockFor.SOMEONE_ELSE, coded.settings.forWho)
        assertTrue(coded.locks.none { it.on })
    }

    @Test fun addingKeepsThisPhonesRowsAndLocks() {
        val here = LockFile(LockSettings(on = true, waitMin = 30), listOf(lock("lk_here")))
        val r = RestoreSafety.phoneLock(here, mine, mine = false, phoneCode = false, add = true)
        assertEquals(30, r.settings.waitMin)
        assertEquals(listOf("lk_here", "lk_bed0", "lk_nap0"), r.locks.map { it.id })
        assertTrue(r.locks.first().on && r.locks.drop(1).none { it.on })
        // Never more than ten.
        val full = LockFile(LockSettings(), (0 until 10).map { lock("lk_f00$it") })
        assertEquals(10, RestoreSafety.phoneLock(full, mine, mine = true, phoneCode = false, add = true).locks.size)
    }
}
