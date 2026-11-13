// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-ready-answers: what the tap reads and when it sends, read from its code (it needs a phone to run). */
class ReadyAnswerTapTest {
    private fun src(path: String) = File("src/main/java/app/heyrobot/$path").readText()
    private val tap by lazy { src("people/ReadyAnswerTap.kt") }
    private fun body(s: String, head: String) = s.substringAfter(head).substringBefore("\n    }\n")

    /** High 1: Files as travel mode shows them, so a file in a hidden drawer never names itself in a draft. */
    @Test fun filesAsTravelModeShowsThem() {
        assertTrue("RobotFiles.shown(ctx)" in tap)
        assertFalse("RobotFiles.vault" in tap)
    }
    /** High 2: a lock for someone else clears every draft from the shade, and none is posted during one. */
    @Test fun aLockForSomeoneElseClearsTheDrafts() {
        val hide = body(src("sys/LockShade.kt"), "fun hide(")
        assertTrue(hide, "for (slot in 0 until READY_ANSWER_SLOTS) nm.cancel(READY_ANSWER_BASE + slot)" in hide)
        assertTrue("PhoneLockNow.someoneElse(ctx)" in body(tap, "fun post("))
    }
    /** High 3: Send fires only when the conversation, read again, is still the one asked in, with nothing new since. */
    @Test fun sendOnlyWhenTheRecheckIsReady() {
        val act = body(tap, "fun act(")
        assertTrue(act, "ReplyGuard.recheck(" in act)
        assertEquals(1, Regex("""MessageTap\.fire\(""").findAll(act).count())
        assertTrue(act.substringAfter("is Recheck.Ready ->").substringBefore("\n").contains("MessageTap.fire("))
    }
    /** Medium 7: the asker is found by address only (ReadyGate.person), never by a display name a stranger can take. */
    @Test fun theAskerByAddressOnly() {
        assertTrue("ReadyGate.person(" in tap)
        assertFalse("MessagePeople.personOf(" in tap)
    }
}
