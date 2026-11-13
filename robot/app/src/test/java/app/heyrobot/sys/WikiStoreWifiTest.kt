// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.wiki.WikiDownload
import app.heyrobot.core.wiki.WikiSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wikipedia P3: the copies download on Wi-Fi only (core's request says so, and WikiStore hands the download manager just that). */
class WikiStoreWifiTest {
    private val src = File("src/main/java/app/heyrobot/sys/WikiStore.kt").readText()

    @Test fun downloadManagerGetsTheWifiOnlyRequest() {
        assertTrue(WikiSet.entries.none { WikiDownload.request(it).overMetered || WikiDownload.request(it).overRoaming })
        assertEquals(1, Regex("""\.setAllowedOverMetered\(r\.overMetered\)""").findAll(src).count())
        assertEquals(1, Regex("""\.setAllowedOverRoaming\(r\.overRoaming\)""").findAll(src).count())
        assertEquals(1, Regex("""DownloadManager\.Request\(""").findAll(src).count())
        assertFalse("never asks about mobile data", src.contains("DownloadPolicy.asksFirst") || src.contains("anyNetwork"))
    }

    @Test fun startsOnlyFromTheSettingsPage() {
        val callers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "WikiStore.kt" }
            .filter { it.readText().contains("WikiStore.start(") }.map { it.name }.toList()
        assertEquals(listOf("WikipediaSettings.kt"), callers)
    }
}
