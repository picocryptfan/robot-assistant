package app.heyrobot.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix-today-review 4: no Today screen saves its settings on the main thread; every save goes through TodaySave (IO). */
class TodaySaveTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val dir = File(root, "app/src/main/java/app/heyrobot/ui/today")

    @Test fun todaysSettingsAreSavedOffTheMainThread() {
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }!!.toList()
        assertTrue(files.size > 3)
        val direct = files.filter { it.name != "TodaySave.kt" && "HomeSettingsStore.update(" in it.readText() }.map { it.name }
        assertEquals(emptyList<String>(), direct)
        assertTrue("own.async(io)" in File(dir, "TodaySave.kt").readText())
    }
}
