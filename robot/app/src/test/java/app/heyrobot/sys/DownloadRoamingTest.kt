// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix-roaming-download (release-1.4-review L3): no download of Robot's goes over roaming, even with "Download on mobile
 * data" on. Each request handed to Android's download manager says setAllowedOverRoaming(false) (its default is true),
 * and no other module or file makes one this test doesn't know about. fix-mt-metered: each also says
 * setAllowedOverMetered from the person's choice (its default is true, any network), never a literal true.
 */
class DownloadRoamingTest {
    private val root = File("..")

    private fun chains(path: String): List<String> {
        val src = File(root, path).readText()
        return Regex("""DownloadManager\.Request\(""").findAll(src).map { m ->
            val end = src.indexOf("enqueue(", m.range.last)
            assertTrue("$path: a request that is never enqueued", end > 0)
            src.substring(m.range.first, end)
        }.toList()
    }

    private fun neverRoaming(path: String, call: String = ".setAllowedOverRoaming(false)") {
        val c = chains(path)
        assertTrue("$path makes no download request", c.isNotEmpty())
        c.forEach { assertTrue("$path: a request without $call", it.contains(call)) }
        c.forEach { followsMetered(path, it) }
    }

    private fun followsMetered(path: String, chain: String) {
        assertTrue("$path: a request without setAllowedOverMetered", chain.contains(".setAllowedOverMetered("))
        assertTrue("$path: a request allowed on mobile data whatever the person chose", !chain.contains(".setAllowedOverMetered(true)"))
    }

    @Test fun languageModelNeverRoams() = neverRoaming("app/src/main/java/app/heyrobot/sys/ModelStore.kt")
    @Test fun voiceModelsNeverRoam() = neverRoaming("app/src/main/java/app/heyrobot/sys/VoiceModelStore.kt")
    @Test fun translationModelsNeverRoam() = neverRoaming("app/src/main/java/app/heyrobot/sys/MtModelStore.kt")
    @Test fun searchModelNeverRoams() = neverRoaming("app/src/main/java/app/heyrobot/sys/SearchModelStore.kt")
    // Core's WikiDownload.request says overRoaming = false (WikiDownloadTest, WikiStoreWifiTest).
    @Test fun wikipediaNeverRoams() = neverRoaming("app/src/main/java/app/heyrobot/sys/WikiStore.kt", ".setAllowedOverRoaming(r.overRoaming)")

    /** Every request in every module, listed above or not: a new one without both fails here. */
    @Test fun everyRequestSetsRoamingAndMetered() {
        val files = root.listFiles().orEmpty().map { File(it, "src/main") }.filter { it.isDirectory }
            .flatMap { d -> d.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("DownloadManager.Request(") }.toList() }
        assertTrue(files.isNotEmpty())
        files.forEach { f ->
            val p = f.relativeTo(root).path
            chains(p).forEach { c ->
                assertTrue("$p: a request without setAllowedOverRoaming", c.contains(".setAllowedOverRoaming("))
                followsMetered(p, c)
            }
        }
    }

    /** The translation download asks about mobile data as the voices do (MainActivity's askNetwork), so the choice reaches it. */
    @Test fun translationDownloadGoesThroughAskNetwork() {
        val src = File(root, "app/src/main/java/app/heyrobot/ui/MainActivity.kt").readText()
        assertTrue(Regex("""mtDownload = \{ askNetwork\(MtModelStore\.bytesToFetch\([^)]*\)\) \{ any -> MtModelStore\.startDownload\([^)]*, any\)""").containsMatchIn(src))
    }

    @Test fun noOtherDownloadRequests() {
        val found = root.listFiles().orEmpty().map { File(it, "src/main") }.filter { it.isDirectory }
            .flatMap { d -> d.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("DownloadManager.Request(") }.toList() }
            .map { it.name }.sorted()
        assertEquals(listOf("ModelStore.kt", "MtModelStore.kt", "SearchModelStore.kt", "VoiceModelStore.kt", "WikiStore.kt"), found)
    }
}
