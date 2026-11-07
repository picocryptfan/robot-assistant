package app.heyrobot.sys

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-today-review 3: Robot's own changes redraw the home-screen widget at once (through TodayRefresh, in main): a timer
 * set or stopped by voice and an event Robot adds. Read from the source, as ProcessIsolationTest does (no device here).
 */
class TodayRedrawTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun src(path: String) = File(root, "app/src/main/java/app/heyrobot/$path").readText()

    /** The text of the function that starts at [head], to its closing brace at the same indent. */
    private fun body(text: String, head: String): String {
        val at = text.indexOf(head)
        assertTrue(head, at >= 0)
        val indent = text.lastIndexOf('\n', at).let { at - it - 1 }
        val end = text.indexOf("\n" + " ".repeat(indent) + "}", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    @Test fun aTimerSetOrStoppedRedrawsTheWidget() {
        val s = src("sys/TodaySource.kt")
        assertTrue(body(s, "fun timerSet(").contains("changed(ctx)"))
        assertTrue(body(s, "fun timersStopped(").contains("changed(ctx)"))
        assertTrue(body(s, "fun changed(").contains("LockSignals.TODAY_REFRESH"))
    }

    @Test fun anEventRobotAddsRedrawsTheWidget() {
        val t = src("assistant/ToolExecutor.kt")
        val insert = t.indexOf("Calendars.insert(ctx, a.title, p)")
        assertTrue(insert >= 0)
        val after = t.substring(insert, t.indexOf("return Executed(ActionStatus.DONE, ToolStrings.addedToCalendar()", insert))
        assertTrue(after, after.contains("TodaySource.changed(ctx)"))
    }
}
