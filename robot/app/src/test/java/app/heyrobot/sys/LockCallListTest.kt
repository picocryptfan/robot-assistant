// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.lock.LockExtras
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import org.junit.Assert.assertEquals
import org.junit.Test

/** seal-people-fix-b 6: Phone lock off leaves no chosen callers, so lock_calls.json is removed. */
class LockCallListTest {
    private val mom = "6f0c2a3e-0000-4000-8000-000000000001"
    private val lock = PhoneLock("lk_bed", "Bedtime", LockRule.Alarm(), extras = LockExtras(people = listOf(mom)))

    @Test fun phoneLockOffChoosesNobody() {
        assertEquals(setOf(mom), LockCallList.chosen(LockFile(LockSettings(on = true), listOf(lock))))
        assertEquals(emptySet<String>(), LockCallList.chosen(LockFile(LockSettings(on = false), listOf(lock))))
        assertEquals(emptySet<String>(), LockCallList.chosen(null))
    }
}
