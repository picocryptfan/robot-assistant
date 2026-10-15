// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.VoiceBundle
import app.heyrobot.voice.EspeakData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The voice model manifest exists three times: in the app (core VoiceModels.kt, what the phone downloads and checks),
 * in tools/fetch_voice_models.sh (what the build machine checked) and in THIRD_PARTY.md. Same for the espeak-ng
 * subset (EspeakData.kt, tools/fetch_third_party.sh). This keeps them identical, and the built-in benchmark clip and
 * sentences in step with the tools that made and measure them.
 */
class VoiceManifestTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    private fun tool(name: String) = File(root, "tools/$name").readText()

    private fun heredoc(script: String, name: String): List<List<String>> =
        Regex("""$name=\$\(cat <<'LIST'\n(.*?)\nLIST""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
            .lines().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }

    @Test fun fetchScriptMatchesTheAppsManifest() {
        val rows = heredoc(tool("fetch_voice_models.sh"), "MANIFEST")
        val expected = VoiceBundle.entries.flatMap { b -> b.files.map { f -> listOf(b.id, b.repo, b.commit, f.name, f.bytes.toString(), f.sha256) } }
        assertEquals(expected, rows)
    }

    @Test fun thirdPartyListsEveryPinnedFile() {
        val tp = File(root, "THIRD_PARTY.md").readText()
        for (b in VoiceBundle.entries) {
            assertTrue(b.commit, tp.contains(b.commit))
            for (f in b.files) assertTrue("${b.id}/${f.name}", tp.contains(f.sha256))
        }
        for (f in EspeakData.FILES) assertTrue(f.name, tp.contains(f.sha256))
        // The sherpa-onnx Kotlin files added for speaker labels (0.5.0) are the v1.13.8 ones, byte for byte.
        for (f in listOf("Speaker.kt", "SpeakerEmbeddingExtractorConfig.kt")) {
            val sha = MessageDigest.getInstance("SHA-256").digest(File(root, "voice/src/main/java/com/k2fsa/sherpa/onnx/$f").readBytes()).joinToString("") { "%02x".format(it) }
            assertTrue(f, tp.contains("`$f`") && tp.contains(sha))
        }
        assertTrue(tp.contains("CC-BY-4.0") && tp.contains("GPL-3.0-or-later"))
    }

    @Test fun espeakSubsetMatchesTheFetchScriptAndTheAssets() {
        val rows = heredoc(tool("fetch_third_party.sh"), "ESPEAK_FILES")
        assertEquals(EspeakData.FILES.map { listOf(it.name, it.sha256) }, rows)
        assertEquals(880_227L, EspeakData.FILES.sumOf { it.bytes })
        val dir = File(root, "voice/src/main/assets/espeak-ng-data")
        for (f in EspeakData.FILES) {
            val a = File(dir, f.name)
            assertTrue("${f.name} missing: run tools/fetch_third_party.sh --install", a.isFile)
            assertEquals(f.name, f.bytes, a.length())
            assertEquals(f.name, f.sha256, MessageDigest.getInstance("SHA-256").digest(a.readBytes()).joinToString("") { "%02x".format(it) })
        }
        assertEquals(EspeakData.FILES.size, dir.walkTopDown().count { it.isFile })
    }

    @Test fun benchmarkClipAndSentencesStayInStep() {
        val clip = File(root, "voice/src/main/assets/bench/test-clip.s16")
        assertTrue(clip.length() in 100_000..200_000 && clip.length() % 2 == 0L)
        val expected = File(root, "voice/src/main/assets/bench/test-clip.txt").readText().trim()
        assertTrue(tool("make_test_clip.py").contains("SENTENCE = \"$expected\""))
        val bench = File(root, "voice/src/main/java/app/heyrobot/voice/VoiceBenchmark.kt").readText()
        val py = tool("voice_bench.py")
        for (k in listOf("FIRST_CHUNK", "SENTENCE")) {
            val kt = Regex("""const val $k = "([^"]+)"""").find(bench)!!.groupValues[1]
            assertTrue(k, py.contains("$k = \"$kt\""))
        }
    }
}
