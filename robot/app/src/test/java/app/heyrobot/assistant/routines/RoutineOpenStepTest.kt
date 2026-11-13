// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-auto-open 4 and auto-open-g: a routine's open step reads the lock, the screen and the switch again just before it opens. */
class RoutineOpenStepTest {
    private val src = File("src/main/java/app/heyrobot/assistant/routines/RoutineRunner.kt").readText()

    @Test fun theOpenStepChecksTheLockAndTheScreenAgainJustBeforeItRuns() {
        val tool = src.substringAfter("private fun tool(ctx: Context")
        val check = tool.indexOf("if (open != null && (Assistant.keyguard(ctx) || !screenOn(ctx))) return null")
        assertTrue("no second check", check >= 0)
        assertTrue("checked after the run", check < tool.indexOf("ex.run("))
    }

    @Test fun theSwitchOffMeansNoTickedApp() {
        assertTrue(src.contains("opens = if (app.heyrobot.sys.OpenAppsStore.named(ctx)) r.opens else emptySet()"))
    }
}
