// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fastlane/metadata (IzzyOnDroid, F-Droid) stays inside the stores' limits, has a changelog for the current versionCode, a
 * 512×512 icon, and names no AI product.
 */
class FastlaneMetadataTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val dir = File(root.parentFile, "fastlane/metadata/android/en-US")
    private fun text(name: String) = File(dir, name).readText().trimEnd('\n')

    @Test fun withinTheLimits() {
        assertTrue(text("title.txt").length in 1..50)
        assertTrue(text("short_description.txt").length in 1..80)
        assertTrue(text("full_description.txt").length in 1..4000)
    }

    @Test fun aChangelogForTheCurrentVersion() {
        val code = Regex("""versionCode\s+(\d+)""").find(File(root, "app/build.gradle").readText())!!.groupValues[1]
        assertTrue(text("changelogs/$code.txt").length in 1..500)
    }

    @Test fun theIconIs512Square() {
        val b = File(dir, "images/icon.png").readBytes()
        fun int(at: Int) = (0..3).fold(0) { acc, i -> (acc shl 8) or (b[at + i].toInt() and 0xff) }
        assertEquals(listOf(512, 512), listOf(int(16), int(20)))
    }

    @Test fun noAiProductNames() {
        val all = dir.walk().filter { it.extension == "txt" }.joinToString("\n") { it.readText() }
        val names = listOf("Claude", "Anthropic", "ChatGPT", "OpenAI", "GPT", "Gemini", "Grok", "Mistral", "Le Chat", "DeepSeek", "Copilot")
        assertEquals(emptyList<String>(), names.filter { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(all) })
    }
}
