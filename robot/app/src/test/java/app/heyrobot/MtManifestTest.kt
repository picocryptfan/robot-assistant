// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.core.MtBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fast translation engine's pins exist more than once: the model files in the app (core MtBundle.kt), in
 * tools/build_robotmt.sh (what the build machine checked) and in THIRD_PARTY.md; the slimt sources in the script and
 * THIRD_PARTY.md; the JNI functions in Slimt.kt, robotmt_jni.cpp and the script's export check. This keeps them in step.
 */
class MtManifestTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val script by lazy { File(root, "tools/build_robotmt.sh").readText() }
    private val thirdParty by lazy { File(root, "THIRD_PARTY.md").readText() }

    private fun heredoc(name: String): List<List<String>> =
        Regex("""$name=\$\(cat <<'LIST'\n(.*?)\nLIST""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
            .lines().filter { it.isNotBlank() }.map { it.trim().split(Regex("\\s+")) }

    @Test fun theScriptChecksTheFilesTheAppDownloads() {
        val expected = MtBundle.FR_EN.files.map { listOf(it.name, it.path, it.gzBytes.toString(), it.gzSha256, it.bytes.toString(), it.sha256) }
        assertEquals(expected, heredoc("MODELS"))
        assertEquals(MtBundle.BASE_URL, Regex("""MODEL_BASE=(\S+)""").find(script)!!.groupValues[1])
    }

    @Test fun thirdPartyListsEveryPin() {
        for (f in MtBundle.FR_EN.files) {
            assertTrue(f.name, thirdParty.contains(f.sha256) && thirdParty.contains(f.gzSha256))
        }
        val sources = heredoc("SOURCES")
        assertEquals(listOf("slimt", "ruy", "cpuinfo", "sentencepiece"), sources.map { it[0] })
        // The fork and commit the plan named (docs/translation-plan.md 1.4).
        assertEquals(listOf("slimt", "https://github.com/DavidVentura/slimt", "2beb403abfff5b2f4c165a6d51362c973d037e97"), sources[0].take(3))
        for (s in sources) {
            assertTrue(s[0], Regex("[0-9a-f]{40}").matches(s[2]) && Regex("[0-9a-f]{64}").matches(s[3]))
            assertTrue("${s[0]} commit in THIRD_PARTY.md", thirdParty.contains(s[2]))
            assertTrue("${s[0]} sha256 in THIRD_PARTY.md", thirdParty.contains(s[3]))
        }
        for (l in listOf("GPL-2.0-or-later", "MPL-2.0", "Apache-2.0", "BSD-2-Clause", "librobotmt.so")) assertTrue(l, thirdParty.contains(l))
        // The toolchain the script pins is the one THIRD_PARTY.md names.
        val ndk = Regex("""NDK_VERSION=(\S+)""").find(script)!!.groupValues[1]
        assertEquals("27.1.12297006", ndk)
        assertTrue(thirdParty.contains(ndk))
    }

    @Test fun theJniFunctionsMatchOnBothSides() {
        val kt = File(root, "mt/src/main/java/app/heyrobot/mt/Slimt.kt").readText()
        val cpp = File(root, "mt/src/main/cpp/robotmt_jni.cpp").readText()
        val externals = Regex("""external fun (\w+)\(""").findAll(kt).map { it.groupValues[1] }.toList().sorted()
        assertEquals(listOf("nativeClose", "nativeOpen", "nativeTranslate"), externals)
        val defined = Regex("""JNICALL (Java_\w+)\(""").findAll(cpp).map { it.groupValues[1] }.toList().sorted()
        assertEquals(externals.map { "Java_app_heyrobot_mt_Slimt_$it" }, defined)
        // The script refuses a library that exports anything else.
        assertTrue(script.contains("[ \"\$exports\" = \"${defined.joinToString(" ")} \" ]"))
    }
}
