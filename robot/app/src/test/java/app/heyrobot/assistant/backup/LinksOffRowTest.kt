// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** With Robot to Robot off, its settings file stays out of the backup with the pairings: the row says both, in both languages. */
class LinksOffRowTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun row(folder: String): String =
        checkNotNull(Regex("""name="backup_links_off">([^<]*)<""").find(File(root, "app/src/main/res/$folder/strings_backup.xml").readText())).groupValues[1]

    @Test fun theRowNamesThePairingsAndTheSettings() {
        assertTrue(row("values").let { "pairings" in it && "its settings" in it })
        assertTrue(row("values-fr-rCA").let { "personnes jumelées" in it && "ses réglages" in it })
    }
}
