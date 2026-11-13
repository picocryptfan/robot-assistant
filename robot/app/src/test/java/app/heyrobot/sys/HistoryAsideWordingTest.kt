// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** small-14-c: History's kept-aside copies are sealed (HistoryVault.sealAside, fix-seal-14-e); its card says so. */
class HistoryAsideWordingTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun detail(values: String): String =
        checkNotNull(Regex("""name="home_history_aside_detail">([^<]*)<""").find(File(root, "app/src/main/res/$values/strings_home.xml").readText())).groupValues[1]

    @Test fun theKeptAsideCopyIsSaidToBeSealed() {
        val en = detail("values")
        assertFalse(en, en.contains("without a lock"))
        assertTrue(en, en.contains("a whole copy here, as it was, sealed on this phone"))
        val fr = detail("values-fr-rCA")
        assertFalse(fr, fr.contains("sans verrou"))
        assertTrue(fr, fr.contains("une copie complète, telle quelle, scellée sur ce téléphone"))
    }
}
