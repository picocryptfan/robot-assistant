// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** FA9: the places that keep lock state and clocks honest, in the app code the JVM tests can't run (core has the rules). */
class LockStateRulesTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/$path").readText(), keepStrings = false)

    @Test fun refreshHoldsLocksWhenLocksJsonCantBeRead() {
        // F22: unreadable isn't off.
        val engine = code("lock/LockEngine.kt")
        assertTrue(engine.contains("val hold = read.ok == null"))
        assertTrue(engine.contains("LockSchedule.refresh(file, st, c, hold)"))
        assertEquals(0, Regex("""LockSchedule\.refresh\(file, [a-zA-Z()]+, c\)""").findAll(engine).count())
    }

    @Test fun theMapsAppIsAskedOnlyAsALockStarts() {
        // F24: nothing asks Android which app opens places but the state's one writer.
        val users = File(root, "app/src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { Source.strip(it.readText(), keepStrings = false).contains("LockMaps.app(") }.map { it.name }.toList()
        assertEquals(listOf("LockStore.kt"), users)
    }

    @Test fun everyWayIntoATurnChecksForALockThatCameOn() {
        // F27: both ways a turn starts in the panel.
        val panel = code("assistant/PanelController.kt")
        assertEquals(3, Regex("""\bnewLockedTurn\(\)""").findAll(panel).count())
        assertTrue(Regex("""fun send\(text: String, fromVoice: Boolean = false\): Boolean \{\s+newLockedTurn\(\)""").containsMatchIn(panel))
    }

    @Test fun theCodePadUsesBothClocks() {
        // F25.
        val pad = code("lock/LockCodeActivity.kt")
        assertTrue(pad.contains("pad.check(code, c.nowMs, c.elapsed, c.boot)"))
        assertTrue(pad.contains("LockCodeRules.nextTry(t, now, c.elapsed, c.boot)"))
        assertTrue(code("lock/LockEngine.kt").contains("LockCodeRules.wrong(st.code, now, c.elapsed, c.boot)"))
    }
}
