// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 1.5, Robot to Robot (robot-to-robot-r0.md §2.6, job L2): libsignal 0.105.2 comes from Signal's own repository through a
 * local, offline copy, and every copy of its hashes says the same: third_party/libsignal-0.105.2.sha256 (the one list the
 * build's strip task, verify_apk.sh and tools/fetch_libsignal.sh read), Gradle's verification metadata, the files in
 * third_party/maven-local, Signal's published .sha256 files beside them, the hashes inside the module files, and the
 * README's table. The local repository serves org.signal alone, and org.signal comes from nowhere else.
 */
class LibsignalPinsTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val pinsFile = File(root, "third_party/libsignal-0.105.2.sha256")
    private val repo = File(root, "third_party/maven-local")

    /** "path" to sha256, from the pin file's hash lines. */
    private val pins: Map<String, String> by lazy {
        pinsFile.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.associate { line ->
            val m = Regex("""([0-9a-f]{64}) {2}(\S+)""").matchEntire(line) ?: throw AssertionError("not a pin line: $line")
            m.groupValues[2] to m.groupValues[1]
        }
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun theSevenPins() {
        val v = "0.105.2"
        val expected = listOf("aar", "pom", "module").map { "org/signal/libsignal-android/$v/libsignal-android-$v.$it" } +
            listOf("jar", "pom", "module").map { "org/signal/libsignal-client/$v/libsignal-client-$v.$it" } + "lib/arm64-v8a/libsignal_jni.so"
        assertEquals(expected.sorted(), pins.keys.sorted())
        // R0's verified hashes (robot-to-robot-r0.md §2.1 and §2.5): the AAR, the jar, the stripped library.
        assertEquals("37447ab37d7861a0bcc177302b364709cd49f395e17274ef2c521ed727908ac8", pins.getValue(expected[0]))
        assertEquals("4d1801ef6f63c3f2656ff987bd13486f4c26caa53b62ec358b936649097e9a1b", pins.getValue(expected[3]))
        assertEquals("9e20c7dddfc35b7a354031a1f497d7ee008835e864de1496473af0f9f83a4a6c", pins.getValue("lib/arm64-v8a/libsignal_jni.so"))
    }

    /** Gradle's dependency verification pins exactly the six files, with the same hashes. */
    @Test fun theVerificationMetadataSaysTheSame() {
        val xml = File(root, "gradle/verification-metadata.xml").readText()
        val signal = Regex("""<component group="org\.signal" name="([^"]+)" version="([^"]+)">(.*?)</component>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        assertEquals(listOf("libsignal-android", "libsignal-client"), signal.map { it.groupValues[1] }.sorted())
        val pinned = signal.flatMap { c ->
            Regex("""<artifact name="([^"]+)">\s*<sha256 value="([0-9a-f]{64})"""").findAll(c.groupValues[3])
                .map { "org/signal/${c.groupValues[1]}/${c.groupValues[2]}/${it.groupValues[1]}" to it.groupValues[2] }.toList()
        }.toMap()
        assertEquals(pins.filterKeys { it.startsWith("org/") }, pinned)
    }

    /** The files Gradle reads match, and so do Signal's own .sha256 files and the hashes inside the module files. */
    @Test fun theLocalRepositoryHoldsThePinnedFiles() {
        assertTrue("run tools/fetch_libsignal.sh (robot/README.md, \"Getting libsignal\")", repo.isDirectory)
        for ((path, sha) in pins.filterKeys { it.startsWith("org/") }) {
            val f = File(repo, path)
            assertEquals(path, sha, sha256(f))
            if (path.endsWith(".aar") || path.endsWith(".jar")) assertEquals("Signal's $path.sha256", sha, File(repo, "$path.sha256").readText().trim())
            if (path.endsWith(".module")) {
                val files = JSONObject(f.readText()).getJSONArray("variants").let { a -> (0 until a.length()).flatMap { i ->
                    a.getJSONObject(i).optJSONArray("files")?.let { fs -> (0 until fs.length()).map { fs.getJSONObject(it) } }.orEmpty() } }
                val main = path.removeSuffix(".module") + if (path.contains("android")) ".aar" else ".jar"
                val inModule = files.filter { it.getString("name") == main.substringAfterLast('/') }.map { it.getString("sha256") }.toSet()
                assertEquals(path, setOf(pins.getValue(main)), inModule)
            }
        }
        // Nothing but org.signal in it.
        assertEquals(listOf("org"), repo.list()!!.toList())
        assertEquals(listOf("signal"), File(repo, "org").list()!!.toList())
    }

    /** settings.gradle: the local repository in both lists, org.signal alone, exclusively; it's git-ignored. */
    @Test fun theRepositoryIsLocalExclusiveAndIgnored() {
        val settings = File(root, "settings.gradle").readText()
        val block = """exclusiveContent {
            forRepository { maven { name = 'libsignalLocal'; url = uri("${'$'}{settingsDir}/third_party/maven-local") } }
            filter { includeGroup 'org.signal' }
        }"""
        assertEquals(2, settings.split(block).size - 1)
        assertEquals(2, Regex("""org\.signal""").findAll(settings.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")).count())
        assertEquals(2, Regex("""maven-local""").findAll(settings.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")).count())
        assertTrue(File(root.parentFile, ".gitignore").readLines().contains("robot/third_party/maven-local/"))
        // Every reader of the pins reads the one file.
        for (f in listOf("link/build.gradle", "tools/verify_apk.sh", "tools/fetch_libsignal.sh"))
            assertTrue(f, File(root, f).readText().contains("libsignal-0.105.2.sha256") || File(root, f).readText().contains("libsignal-\${libsignalVersion}.sha256"))
        // The README's table says the same as the pins.
        val readme = File(root, "README.md").readText()
        for ((path, sha) in pins) assertTrue(path, readme.contains("| `${path.substringAfterLast('/')}` | `$sha` |") || path.startsWith("lib/") && readme.contains(sha))
    }
}
