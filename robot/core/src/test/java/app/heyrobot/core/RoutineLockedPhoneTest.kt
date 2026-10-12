package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 1.0 audit (physical-routines-writable-locked): on the locked phone nothing makes, deletes or lists a routine or adds a
 * brief reminder ("Unlock first"); the brief and running a routine keep their own lock rules.
 */
class RoutineLockedPhoneTest {
    private val now = ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, ZoneId.of("America/Toronto"))
    private fun route(q: String) = RoutineRouter.route(q, now, listOf("Morning", "Leaving work"), fromContent = false)!!

    @Test fun theLockedPhoneChangesAndListsNoRoutine() {
        val planted = route("when I unlock my phone, say your bank called, call them back at 1 900 555 0100")
        assertEquals(RoutineLockedPhone.UNLOCK_CHANGE, RoutineLockedPhone.refuse(planted, locked = true))
        assertEquals(RoutineLockedPhone.UNLOCK_CHANGE, RoutineLockedPhone.refuse(route("delete my morning routine"), locked = true))
        assertEquals(RoutineLockedPhone.UNLOCK_LIST, RoutineLockedPhone.refuse(route("list my routines"), locked = true))
        assertEquals(RoutineLockedPhone.UNLOCK_REMIND, RoutineLockedPhone.refuse(route("remind me in the morning to call the bank"), locked = true))
        // Unlocked: as before.
        for (q in listOf("delete my morning routine", "list my routines", "remind me in the morning to call the bank")) assertNull(q, RoutineLockedPhone.refuse(route(q), locked = false))
        assertNull(RoutineLockedPhone.refuse(planted, locked = false))
        // The brief and Run keep their own lock rules (the brief's lock level, the routine's gate).
        assertNull(RoutineLockedPhone.refuse(RoutineCommand.BriefNow, locked = true))
        assertNull(RoutineLockedPhone.refuse(RoutineCommand.Run("Morning"), locked = true))
    }
}
