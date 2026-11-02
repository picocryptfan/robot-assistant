// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** F51: the settings stores read for a write inside the write's try, by the read that throws: a failed read writes nothing (core readTextForUpdate). */
class ReadBeforeWriteTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    @Test fun aSettingsWriteNeverBuildsOnALenientRead() {
        for ((name, writes) in mapOf("EverydaySettingsStore" to 1, "FilesVault" to 2, "LinkSettingsStore" to 2, "ListsVault" to 2)) {
            val code = File(root, "app/src/main/java/app/heyrobot/sys/$name.kt").readLines()
            assertFalse(name, code.any { "readOrNull(" in it && "return@withFileLock false" in it })
            assertEquals(name, writes, code.count { "readTextForUpdate(" in it && "return@withFileLock false" in it })
        }
    }
}
