// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix-126-review 4: with "Use mobile data for news" on, nothing says the daily fetch is on Wi-Fi only. */
class TodayNewsMobileTextTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val src = File(root, "app/src/main/java/app/heyrobot/ui/today/TodayNews.kt").readText()

    @Test fun theDailyTextsHaveAMobileDataForm() {
        for (name in listOf("today_news_mode_daily", "today_news_card_daily")) {
            // Each use of the Wi-Fi form sits beside its mobile data form.
            val uses = Regex("R\\.string\\.$name\\b(?!_)").findAll(src).count()
            val mobile = Regex("R\\.string\\.${name}_mobile\\b").findAll(src).count()
            assertTrue(name, uses > 0)
            assertEquals(name, uses, mobile)
            for (dir in listOf("values", "values-fr-rCA"))
                assertTrue("$dir $name", "name=\"${name}_mobile\"" in File(root, "app/src/main/res/$dir/strings_today.xml").readText())
        }
    }
}
