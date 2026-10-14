package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The donation links (1.0's README "Support Robot", the fastlane description) match support-addresses.txt character for
 * character: Ko-fi first, the bc1q address before the sp1 one, and no other address in the README.
 */
class SupportAddressesTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val entries by lazy {
        File(root, "support-addresses.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBeforeLast(' ') to it.substringAfterLast(' ') }
    }
    private val readme by lazy { File(root.parentFile, "README.md").readText() }
    private val support by lazy { readme.substringAfter("## Support Robot\n").substringBefore("\n## ") }

    @Test fun theListIsComplete() {
        assertEquals(listOf("ETH", "BTC", "BTC", "BTC Lightning", "XMR", "Ko-fi"), entries.map { it.first })
        assertTrue(entries.all { it.second.length >= 20 && it.second.none(Char::isWhitespace) })
    }

    @Test fun theReadmeListsEveryOneExactly() {
        for ((what, value) in entries) {
            val line = if (what == "Ko-fi") "- Ko-fi: $value\n" else "`$value`\n"
            assertTrue("$what is not in the README's Support Robot section as $value", support.contains(line))
        }
    }

    @Test fun koFiFirstAndBc1qBeforeSp1() {
        val at = entries.map { (_, v) -> support.indexOf(v) }
        val kofi = support.indexOf(entries.single { it.first == "Ko-fi" }.second)
        assertTrue(at.all { it >= kofi })
        val btc = entries.filter { it.first == "BTC" }.map { it.second }
        assertTrue(support.indexOf(btc.single { it.startsWith("bc1q") }) < support.indexOf(btc.single { it.startsWith("sp1") }))
    }

    @Test fun noOtherAddressInTheReadme() {
        val known = entries.map { it.second }.toSet()
        val shaped = Regex("""\b(bc1[0-9a-z]{20,}|sp1[0-9a-z]{20,}|0x[0-9a-fA-F]{40}|4[0-9A-Za-z]{94})\b""")
        assertEquals(emptyList<String>(), shaped.findAll(readme).map { it.value }.filter { it !in known }.toList())
    }

    @Test fun theStoreDescriptionUsesTheSameKoFi() {
        val text = File(root.parentFile, "fastlane/metadata/android/en-US/full_description.txt").readText()
        assertTrue(text.contains(entries.single { it.first == "Ko-fi" }.second))
    }
}
