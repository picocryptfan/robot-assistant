// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-people-fix 4: People's damaged and Start over texts name both kept-aside files and a newer Robot's file; the panel reads People off its main thread. */
class PeopleWordingTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun string(folder: String, file: String, name: String): String =
        checkNotNull(Regex("""name="$name">([^<]*)<""").find(File(root, "app/src/main/res/$folder/$file.xml").readText())).groupValues[1]

    @Test fun theTextsSayWhatIsTrue() {
        for (f in listOf("values", "values-fr-rCA")) {
            assertTrue(f, "people.json.bad" in string(f, "strings_people", "people_start_over_text"))
            assertTrue(f, "people.json.bad" in string(f, "strings_people", "people_damaged_detail"))
        }
        assertTrue("A newer Robot saved it" in string("values", "strings_people", "people_damaged_detail"))
        assertTrue("(a newer Robot saved it, or it is damaged)" in string("values", "strings_backup", "backup_restore_result_people_too_new"))
    }

    @Test fun thePanelOpensPeopleOffItsMainThread() {
        val src = File(root, "app/src/main/java/app/heyrobot/assistant/handsfree/PanelHandsFree.kt").readText()
        assertTrue(src.contains("withContext(Dispatchers.IO) { PhoneLockNow.calls(app) }"))
        assertFalse(src.contains("if (phoneLock) PhoneLockNow.callKeys(app)"))
    }
}
