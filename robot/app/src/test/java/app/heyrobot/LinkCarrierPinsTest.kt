// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 1.5, Robot to Robot's carriers (job L3): Tor 0.4.9.14, the UnifiedPush connector 3.3.5 and Tink 1.23.0 come from Maven
 * Central through a local, offline copy, and every copy of their hashes says the same: third_party/link-carriers.sha256 (the
 * one list the build's torBinary task, verify_apk.sh and tools/fetch_link_deps.sh read), Gradle's verification metadata, the
 * files in third_party/maven-link, Tor's executable inside its jar, and the README's table. The local repository serves
 * exactly those three modules, and they come from nowhere else; jtorctl, onionwrapper and protobuf are taken from nowhere.
 * L-bridges: and lyrebird 0.9.0 (Tor's bridges), built from source, not a module: its executable pinned the same way.
 */
class LinkCarrierPinsTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val pinsFile = File(root, "third_party/link-carriers.sha256")
    private val repo = File(root, "third_party/maven-link")

    private val pins: Map<String, String> by lazy {
        pinsFile.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.associate { line ->
            val m = Regex("""([0-9a-f]{64}) {2}(\S+)""").matchEntire(line) ?: throw AssertionError("not a pin line: $line")
            m.groupValues[2] to m.groupValues[1]
        }
    }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun theEightPins() {
        val expected = listOf(
            "org/briarproject/tor-android/0.4.9.14/tor-android-0.4.9.14.jar", "org/briarproject/tor-android/0.4.9.14/tor-android-0.4.9.14.pom",
            "org/unifiedpush/android/connector/3.3.5/connector-3.3.5.aar", "org/unifiedpush/android/connector/3.3.5/connector-3.3.5.pom",
            "org/unifiedpush/android/connector/3.3.5/connector-3.3.5.module", "com/google/crypto/tink/tink/1.23.0/tink-1.23.0.jar",
            "lib/arm64-v8a/libtor.so", "lib/arm64-v8a/liblyrebird.so")
        assertEquals(expected.sorted(), pins.keys.sorted())
        // R0's verified hashes (robot-to-robot-r0.md §4.1, §5.1): Tor's jar and executable, the connector's AAR.
        assertEquals("3c282777bdcd3b55dcc8c8d5294af753dd8ed32b832f12e82128eed6f481d2ed", pins.getValue(expected[0]))
        assertEquals("fee641c956e85c40552b6005a6f88deb157d84e217307214a846ba0640ec44c3", pins.getValue("lib/arm64-v8a/libtor.so"))
        assertEquals("fa017cdfabbdc9af021e1f8c8eff2c8098e701d7a2c599937dce0b4ecf1e929b", pins.getValue(expected[2]))
        // L-bridges: lyrebird's arm64 executable, as tools/build_lyrebird.sh builds it.
        assertEquals("de94bedd76f43dc495a1e53dd3fc7d886495ac43d48d7864aba08ae4ad416845", pins.getValue("lib/arm64-v8a/liblyrebird.so"))
    }

    /** Gradle's dependency verification pins exactly those files, with the same hashes. */
    @Test fun theVerificationMetadataSaysTheSame() {
        val xml = File(root, "gradle/verification-metadata.xml").readText()
        val pinned = HashMap<String, String>()
        for ((group, name) in listOf("org.briarproject" to "tor-android", "org.unifiedpush.android" to "connector", "com.google.crypto.tink" to "tink")) {
            val comps = Regex("""<component group="${Regex.escape(group)}" name="$name" version="([^"]+)">(.*?)</component>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
            val ours = comps.filter { it.groupValues[1] in setOf("0.4.9.14", "3.3.5", "1.23.0") }
            assertEquals(name, 1, ours.size)
            val c = ours.single()
            for (a in Regex("""<artifact name="([^"]+)">\s*<sha256 value="([0-9a-f]{64})"""").findAll(c.groupValues[2]))
                pinned["${group.replace('.', '/')}/$name/${c.groupValues[1]}/${a.groupValues[1]}"] = a.groupValues[2]
        }
        assertEquals(pins.filterKeys { !it.startsWith("lib/") }, pinned)
        // Nothing of what R0 also fetched and Robot doesn't use.
        for (absent in listOf("name=\"jtorctl\"", "name=\"onionwrapper", "name=\"dont-kill-me-lib\"", "protobuf-java\" version=\"4.33.6\"")) assertTrue(absent, !xml.contains(absent))
    }

    @Test fun theLocalRepositoryHoldsThePinnedFiles() {
        assertTrue("run tools/fetch_link_deps.sh (robot/README.md, \"Getting Tor and the push connector\")", repo.isDirectory)
        for ((path, sha) in pins.filterKeys { !it.startsWith("lib/") }) assertEquals(path, sha, sha256(File(repo, path).readBytes()))
        val jar = File(repo, "org/briarproject/tor-android/0.4.9.14/tor-android-0.4.9.14.jar")
        ZipFile(jar).use { z -> assertEquals(pins.getValue("lib/arm64-v8a/libtor.so"), sha256(z.getInputStream(z.getEntry("arm64-v8a/libtor.so")).readBytes())) }
        // Only those three modules in it (Briar's lyrebird jar, in a copy from before tools/build_lyrebird.sh, is ignored and unused).
        val files = repo.walkTopDown().filter { it.isFile }.map { it.relativeTo(repo).path }.filterNot { it.startsWith("org/briarproject/lyrebird-android/") }.toSet()
        assertEquals(pins.keys.filter { !it.startsWith("lib/") }.toSet(), files)
    }

    /** F124: lyrebird comes from Robot's own build, every part of it pinned; F53, F54, F125: the bundle, the repro check and CI take what it needs. */
    @Test fun lyrebirdIsBuiltFromPinnedSource() {
        val build = File(root, "tools/build_lyrebird.sh").readText()
        for (s in listOf("GO_VERSION=1.27.3", "GO_SHA256=65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40", "LYREBIRD_VERSION=0.9.0",
            "LYREBIRD_COMMIT=89d54872b176f936232ff39f7a8189b77d83d65e", "NDK_VERSION=27.1.12297006", "-trimpath", "-buildvcs=false", "-buildid=", "max-page-size=16384", "link-carriers.sha256"))
            assertTrue(s, build.contains(s))
        val patch = File(root, "third_party/patches/lyrebird-0.9.0-deps.patch").readText()
        // Only go.mod and go.sum: no line of lyrebird's own code changes.
        assertEquals(listOf("go.mod", "go.sum"), Regex("""^diff --git a/(\S+) """, RegexOption.MULTILINE).findAll(patch).map { it.groupValues[1] }.toList())
        for (s in listOf("+\tgolang.org/x/net v0.60.0", "github.com/pion/stun/v3 v3.1.5", "github.com/pion/dtls/v3 v3.1.4")) assertTrue(s, patch.contains(s))
        val gradle = File(root, "link/build.gradle").readText()
        assertTrue(gradle.contains("third_party/lyrebird/liblyrebird.so"))
        assertTrue(!gradle.contains("lyrebirdAndroid"))
        assertTrue(!File(root, "gradle/verification-metadata.xml").readText().contains("lyrebird-android"))
        val bundle = File(root, "tools/make_source_bundle.sh").readText()
        for (s in listOf("liblyrebird.so|GPL-3.0-or-later", "libsignal_jni.so|", "tools/build_lyrebird.sh")) assertTrue(s, bundle.contains(s))
        val licence = "GPL-3.0-or-later AND BSD-2-Clause AND BSD-3-Clause"
        assertTrue(File(root, "THIRD_PARTY.md").readText().contains("| 0.9.0 (built from source) | $licence |"))
        assertTrue(File(root, "app/src/main/assets/licenses/components.json").readText().contains("\"licence\": \"$licence\""))
        val repro = File(root, "tools/repro_check.sh").readText()
        for (s in listOf("third_party/maven-local", "third_party/maven-link", "third_party/lyrebird")) assertTrue(s, repro.contains(s))
        val ci = File(root.parentFile, ".github/workflows/tests.yml").readText()
        for (s in listOf("robot/tools/fetch_libsignal.sh", "robot/tools/fetch_link_deps.sh")) assertTrue(s, ci.contains(s))
    }

    /** fb19: the archive "builds on its own": every Robot file the lyrebird script reads is in it, and a cold run leaves no Go tarball in /tmp. */
    @Test fun theBundlesLyrebirdScriptHasWhatItReads() {
        val script = File(root, "tools/build_lyrebird.sh").readText()
        val carried = Regex("""ROBOT_FILES="([^"]*)"""").find(File(root, "tools/make_source_bundle.sh").readText())!!.groupValues[1].split(' ')
        assertTrue("build.gradle" in carried)
        for (m in Regex("""^[A-Z_]+=.*"${'$'}ROBOT/([^"$]+)"""", RegexOption.MULTILINE).findAll(script)) {
            val path = m.groupValues[1].substringBefore(' ')
            if (path.startsWith("third_party/lyrebird/")) continue // what it builds
            assertTrue(path, carried.any { path == it || path.startsWith("$it/") })
        }
        assertTrue(script.substringAfter("tar -xzf \"\$tgz\"").lineSequence().drop(1).first().trim().startsWith("rm -f \"\$tgz\""))
    }

    /** settings.gradle: the local repository in both lists, for exactly these modules; it's git-ignored; every reader uses the one pin file. */
    @Test fun theRepositoryIsLocalExclusiveAndIgnored() {
        val settings = File(root, "settings.gradle").readText()
        val block = """        exclusiveContent {
            forRepository {
                maven {
                    name = 'linkLocal'
                    url = uri("${'$'}{settingsDir}/third_party/maven-link")
                    metadataSources { gradleMetadata(); mavenPom(); artifact() }
                }
            }
            filter {
                includeModule 'org.briarproject', 'tor-android'
                includeModule 'org.unifiedpush.android', 'connector'
                includeVersion 'com.google.crypto.tink', 'tink', '1.23.0'
            }
        }"""
        assertEquals(2, settings.split(block).size - 1)
        val code = settings.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertEquals(2, Regex("""maven-link""").findAll(code).count())
        assertEquals(2, Regex("""org\.unifiedpush""").findAll(code).count())
        assertEquals(0, Regex("""lyrebird-android""").findAll(code).count())
        assertTrue(File(root.parentFile, ".gitignore").readLines().contains("robot/third_party/maven-link/"))
        for (f in listOf("link/build.gradle", "tools/verify_apk.sh", "tools/fetch_link_deps.sh")) assertTrue(f, File(root, f).readText().contains("link-carriers.sha256"))
        val readme = File(root, "README.md").readText()
        for ((path, sha) in pins) assertTrue(path, readme.contains("| `${path.substringAfterLast('/')}` | `$sha` |") || path.startsWith("lib/") && readme.contains(sha))
    }
}
