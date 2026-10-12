package app.heyrobot

import app.heyrobot.core.LicenceBook
import app.heyrobot.core.LicenceWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings › About › Licences (0.4.5) is built from assets/licenses/components.json. This keeps that list complete: every
 * component THIRD_PARTY.md names is in it (its table row by row, its headings, its Maven artifacts), every library the
 * release build packs belongs to an entry, every text and notice it names ships, and the copies of Robot's own licence
 * texts are the repository's.
 */
class LicencesTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val assets = File(root, "app/src/main/assets")
    /** Where :brain's aarNotices task puts the licence files it copies out of the LiteRT AARs (Gradle runs it first). */
    private val brainAssets = File(root, "brain/build/generated/assets/aarNotices")
    private val book by lazy { LicenceBook.parse(File(assets, LicenceBook.FILE).readText()) }
    private val thirdParty by lazy { File(root, "THIRD_PARTY.md").readText() }

    private fun asset(path: String): File? = listOf(File(assets, path), File(brainAssets, path)).firstOrNull { it.isFile }

    @Test fun theListReadsWithoutProblems() {
        assertEquals(emptyList<String>(), book.problems())
        assertTrue(book.components.size >= 40)
        for (w in listOf(LicenceWhere.APP, LicenceWhere.DOWNLOAD, LicenceWhere.SERVICE, LicenceWhere.BUILD)) assertTrue(w.id, book.section(w).isNotEmpty())
        assertEquals("GPL-3.0-only", book.robot.licence)
    }

    @Test fun everyTextAndNoticeShips() {
        for (f in book.files()) {
            val file = asset(f)
            assertTrue("$f is missing (assets/licenses, or :brain's aarNotices)", file != null && file.length() > 200)
        }
        // Robot's GPL is the repository's LICENSE, word for word; the shared Apache text and the Silero and llama.cpp texts are
        // the ones in third_party/.
        assertEquals(File(root.parentFile, "LICENSE").readText(), File(assets, "licenses/GPL-3.0.txt").readText())
        assertEquals(File(root, "third_party/LICENSE-sherpa-onnx.txt").readText(), File(assets, "licenses/Apache-2.0.txt").readText())
        assertEquals(File(root, "third_party/LICENSE-silero-vad.txt").readText(), File(assets, "licenses/MIT-silero-vad.txt").readText())
        assertEquals(File(root, "third_party/LICENSE-llama.cpp.txt").readText(), File(assets, "licenses/MIT-llama.cpp.txt").readText())
        // An MIT or BSD part ships its own text, whose copyright line is the one the screen shows.
        for (c in book.components.filter { it.where == LicenceWhere.APP && (it.licence == "MIT" || it.licence.startsWith("BSD")) }) {
            val text = asset(c.text ?: error("${c.id} needs its own ${c.licence} text"))!!.readText()
            val holder = c.copyright.substringAfter("Copyright").replace(Regex("\\(c\\)|©|[0-9,-]+|present"), "").trim().split(';', ',').first().trim().split(' ').first()
            assertTrue("${c.id}: its text doesn't name $holder", text.contains(holder))
        }
        // LiteRT-LM's large notice file, copied from the AAR at build time, and every file in assets/licenses is on the list.
        assertTrue(asset("licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt")!!.length() > 1_000_000)
        val shipped = File(assets, "licenses").walkTopDown().filter { it.isFile && it.name != "components.json" }
            .map { "licenses/" + it.relativeTo(File(assets, "licenses")).path }.toSet()
        assertEquals(emptySet<String>(), shipped - book.files())
    }

    /** THIRD_PARTY.md's table ("| Component | Version | Licence | Where |"): the same parts, licences and places, both ways. */
    @Test fun thirdPartyTableMatchesTheList() {
        val table = thirdParty.substringAfter("| Component | Version | Licence | Where |").lineSequence().drop(2).takeWhile { it.startsWith("|") }
            .map { row -> row.trim('|').split(" | ").map { it.trim() } }.toList()
        assertTrue(table.size >= 40)
        val where = mapOf("in the APK" to LicenceWhere.APP, "downloaded when you ask" to LicenceWhere.DOWNLOAD,
            "online service" to LicenceWhere.SERVICE, "build and tests only" to LicenceWhere.BUILD)
        for ((name, version, licence, place) in table) {
            val c = book.components.firstOrNull { it.name == name }
            assertTrue("THIRD_PARTY.md names $name, which the Licences screen lacks", c != null)
            assertEquals(name, c!!.version, version)
            assertEquals(name, c.licence, licence)
            assertEquals(name, c.where, where[place])
        }
        assertEquals(book.components.map { it.name }.sorted(), table.map { it[0] }.sorted())
    }

    /** Every section heading of THIRD_PARTY.md names a part on the list (or says nothing is bundled). */
    @Test fun everyThirdPartyHeadingIsOnTheList() {
        val headings = thirdParty.lineSequence().filter { it.startsWith("### ") }.map { it.removePrefix("### ") }.toList()
        assertTrue(headings.size >= 10)
        for (h in headings.filterNot { it.contains("not bundled") }) {
            assertTrue("THIRD_PARTY.md's \"$h\" isn't on the Licences screen",
                book.components.any { c -> (listOf(c.name.substringBefore(" (")) + c.match).any { h.contains(it, ignoreCase = true) } })
        }
    }

    /** Every Maven artifact THIRD_PARTY.md names, and every library the release build packs, belongs to an entry. */
    @Test fun everyLibraryIsOnTheList() {
        val named = Regex("`([a-z0-9.-]+):([A-Za-z0-9_.-]+)(?::[^`]*)?`").findAll(thirdParty).map { it.groupValues[1] to it.groupValues[2] }.toSet()
        assertTrue(named.size >= 25)
        for ((g, a) in named) assertTrue("THIRD_PARTY.md names $g:$a, which no Licences entry covers", book.forArtifact(g, a) != null)
        val list = File(root, "app/build/robot/release-runtime-artifacts.txt")
        assertTrue("run through Gradle (releaseRuntimeArtifacts)", list.isFile)
        val packed = list.readLines().filter { it.isNotBlank() }.map { it.split(':') }
        assertTrue(packed.size >= 50)
        for ((g, a) in packed) {
            val c = book.forArtifact(g, a)
            assertTrue("the release build packs $g:$a, which no Licences entry covers", c != null && c.where == LicenceWhere.APP)
        }
        // The LiteRT and Anthropic artifacts are where they should be.
        assertEquals("litert-lm", book.forArtifact("com.google.ai.edge.litertlm", "litertlm-android")?.id)
        assertEquals("anthropic-sdk-java", book.forArtifact("com.anthropic", "anthropic-java-core")?.id)
    }

    /** The icons in Icons.kt are Material Symbols at the commit tools/symbols.py pins, and the list names that commit. */
    @Test fun theIconsAreOnTheListAtTheirCommit() {
        val commit = Regex("""COMMIT = "([0-9a-f]{40})"""").find(File(root, "tools/symbols.py").readText())!!.groupValues[1]
        val symbols = book.byId("material-symbols")!!
        assertEquals(LicenceWhere.APP, symbols.where)
        assertTrue(symbols.version, symbols.version.endsWith(" at " + commit.take(8)))
        assertTrue(thirdParty.contains(commit))
    }

    /**
     * Robot's llama.cpp library (0.4.5, tools/build_llama.sh): llama.cpp and KleidiAI are on the list at the versions the
     * script pins, the libc++ entry names the NDK it builds with, and THIRD_PARTY.md names both commits.
     */
    @Test fun theLlamaLibrarysPartsAreOnTheListAtTheirPins() {
        val script = File(root, "tools/build_llama.sh").readText()
        fun pin(name: String) = Regex("""(?m)^$name=(\S+)$""").find(script)!!.groupValues[1]
        assertEquals("${pin("LLAMA_TAG")} (${pin("LLAMA_COMMIT").take(8)})", book.byId("llama-cpp")!!.version)
        assertEquals(pin("KLEIDIAI_TAG"), "v" + book.byId("kleidiai")!!.version)
        assertTrue(script.contains("/ndk/" + book.byId("llvm-libcxx")!!.version.removePrefix("Android NDK ")))
        for (id in listOf("llama-cpp", "kleidiai", "llvm-libcxx", "nlohmann-json")) assertEquals(id, LicenceWhere.APP, book.byId(id)!!.where)
        assertTrue(thirdParty.contains(pin("LLAMA_COMMIT")) && thirdParty.contains(pin("KLEIDIAI_COMMIT")))
    }

    /** The GPL's notices: free software, no warranty, the licence a tap away, the source, and the GPL parts' source. */
    @Test fun robotsNoticeAndSourceLinks() {
        assertEquals("licenses/GPL-3.0.txt", book.robot.text)
        assertTrue(book.robot.bundleAt("1.2.3").endsWith("/releases/download/v1.2.3/robot-1.2.3-third-party-sources.tar.gz"))
        assertTrue(book.robot.sourceAt("1.2.3").endsWith("/tree/v1.2.3"))
        // The script that makes that bundle names its file the same way.
        val script = File(root, "tools/make_source_bundle.sh").readText()
        assertTrue(script.contains("NAME=\"robot-\${VERSION}-third-party-sources\"") && script.contains("TGZ=\"\$OUT/\$NAME.tar.gz\""))
        // 1.0: it also takes the sources of the two libraries Robot builds (slimt's and llama.cpp's), from the pins of the
        // very scripts that build them, and Robot's own native sources.
        assertTrue(script.contains("\"\$HERE/build_robotmt.sh\"") && script.contains("\"\$HERE/build_llama.sh\""))
        for (part in listOf("mt/src/main/cpp", "brain/src/main/cpp", "--without-llama", "--list")) assertTrue(part, script.contains(part))
        // espeak-ng is GPL-3.0-or-later and says where its source is.
        val espeak = book.byId("espeak-ng")!!
        assertEquals("GPL-3.0-or-later", espeak.licence)
        assertTrue(espeak.note!!.contains("source bundle"))
        // Parakeet's CC BY 4.0 attribution: creator, link, licence and its link, the change, the disclaimer.
        val parakeet = book.byId("parakeet")!!.note!!
        for (part in listOf("NVIDIA", "huggingface.co/nvidia/parakeet-tdt-0.6b-v3", "Attribution 4.0", "creativecommons.org/licenses/by/4.0", "Changed:", "without warranties")) {
            assertTrue(part, parakeet.contains(part))
        }
    }
}
