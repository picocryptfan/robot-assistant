// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review M3 (report-ways-in): a hung test stops the build instead of holding it. Robolectric's TimeLimitedStatement only
 * interrupts the test thread, so a busy loop or a swallowed interrupt still hangs: every module's Test task has a Gradle
 * timeout as the backstop, and the panel's report Compose tests keep their own per-test limit.
 */
class TestTimeoutsTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    @Test fun everyTestTaskHasAGradleTimeout() {
        val build = File(root, "build.gradle").readText()
        val tests = build.substringAfter("subprojects {").substringAfter("tasks.withType(Test).configureEach { t ->")
        assertTrue("build.gradle: no Test timeout", Regex("""t\.timeout = java\.time\.Duration\.ofMinutes\(\d+\)""").containsMatchIn(tests.substringBefore("\n    }\n}")))
    }

    @Test fun thePanelsReportComposeTestsHaveAPerTestLimit() {
        for (name in listOf("PanelReportWaysTest", "PanelHeaderFitTest")) {
            val src = File(root, "app/src/testDebug/java/app/heyrobot/assistant/$name.kt").readText()
            assertTrue("$name: no per-test limit", src.contains("@get:Rule(order = -2) val timeout = TestRule { base, _ -> TimeLimitedStatement("))
        }
    }
}
