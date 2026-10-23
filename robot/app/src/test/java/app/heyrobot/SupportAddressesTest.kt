// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import app.heyrobot.core.QrCode
import app.heyrobot.ui.settings.SupportKind
import app.heyrobot.ui.settings.SupportLinks
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The donation links (1.0's README "Support Robot", the fastlane description, 1.1's Settings › About › Support Robot) match
 * support-addresses.txt character for character: Ko-fi first, the bc1q address before the sp1 one, and no other address
 * in the README or the app.
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

    /** 1.1: the app's page shows every line of the file, and nothing else, each value exactly as the file has it. */
    @Test fun theAppListsEveryOneExactly() {
        val kind = mapOf("Ko-fi" to SupportKind.KOFI, "BTC" to SupportKind.BITCOIN, "BTC Lightning" to SupportKind.LIGHTNING,
            "XMR" to SupportKind.MONERO, "ETH" to SupportKind.ETHEREUM)
        val app = SupportLinks.ALL.map { it.kind to it.value }
        assertEquals(entries.map { (what, v) -> kind.getValue(what) to v }.sortedBy { it.second }, app.sortedBy { it.second })
        assertEquals(app.size, app.map { it.second }.toSet().size)
    }

    /** The page's order (the user, 14 Oct 2026): Ko-fi, the bc1q address, the sp1 one, Lightning, Monero, Ethereum. */
    @Test fun theAppShowsKoFiFirstAndBc1qBeforeSp1() {
        assertEquals(listOf(SupportKind.KOFI, SupportKind.BITCOIN, SupportKind.BITCOIN, SupportKind.LIGHTNING, SupportKind.MONERO, SupportKind.ETHEREUM),
            SupportLinks.ALL.map { it.kind })
        assertTrue(SupportLinks.ALL[1].value.startsWith("bc1q"))
        assertTrue(SupportLinks.ALL[2].value.startsWith("sp1"))
        assertEquals(SupportLinks.KOFI, SupportLinks.ALL.first())
    }

    /** No other address-shaped value in the page's source: an address there comes from the file, or the test above fails. */
    @Test fun noOtherAddressInTheApp() {
        val known = entries.map { it.second }.toSet()
        val shaped = Regex("""\b(bc1[0-9a-z]{20,}|sp1[0-9a-z]{20,}|0x[0-9a-fA-F]{40}|4[0-9A-Za-z]{94}|[\w.]+@[\w.]+\.cash|https://ko-fi\.com/\w+)\b""")
        val page = File(root, "app/src/main/java/app/heyrobot/ui/settings/SupportSettings.kt").readText()
        assertEquals(emptyList<String>(), shaped.findAll(page).map { it.value }.filter { it !in known }.toList())
    }

    /**
     * The Bitcoin addresses' own checksums hold (BIP-173 bech32 for bc1q, BIP-352's bech32m for sp1), so a copy that lost
     * or changed a character upstream would show here too.
     */
    @Test fun theBitcoinChecksumsHold() {
        val btc = entries.filter { it.first == "BTC" }.map { it.second }
        assertEquals(BECH32, bech32Polymod(btc.single { it.startsWith("bc1q") }))
        assertEquals(BECH32M, bech32Polymod(btc.single { it.startsWith("sp1") }))
    }

    private fun bech32Polymod(address: String): Int {
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        val hrp = address.substringBeforeLast('1')
        val data = address.substringAfterLast('1').map { c -> charset.indexOf(c).also { assertTrue("$c is not bech32", it >= 0) } }
        var chk = 1
        for (v in hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 } + data) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0 until 5) if ((b shr i) and 1 == 1) chk = chk xor gen[i]
        }
        return chk
    }

    /**
     * Each address's QR code (core QrCode, which the page draws from the same value) is the one zbarimg 0.23.93 read back
     * to exactly that address on 19 Oct 2026: SHA-256 of its rows ("1" dark, "0" light, one line each), in the file's order.
     * A changed address fails here until its new code is checked the same way.
     */
    @Test fun theQrCodesAreTheOnesZbarimgRead() {
        fun sha(q: QrCode): String {
            val rows = (0 until q.size).joinToString("\n") { y -> (0 until q.size).joinToString("") { x -> if (q.dark(x, y)) "1" else "0" } }
            return MessageDigest.getInstance("SHA-256").digest(rows.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        val codes = entries.filter { it.first != "Ko-fi" }.map { (what, v) -> what to sha(QrCode.encode(v)!!) }
        assertEquals(listOf(
            "ETH" to "6839037b28e9144bf62d6ba0ef60accca545b00d5496efad14b4235392c35b48",
            "BTC" to "e5727afd9a743e7dd9ea7e0820c990a3ad4bc5ed12701805c9def761995c3430",
            "BTC" to "8a50b6309813570a50c1d6a63602e088eae2aea4ce4edb78c3dd235745a90806",
            "BTC Lightning" to "a7ffa8de433cfa32e6b7cd7b1e7172b038d4c892fe18a554d50d3172c0a69ee5",
            "XMR" to "5b9e9a84654da4007e02a9b0a6a434f544b09fab23c9cc76a7bbab471b7cda01",
        ), codes)
    }

    @Test fun theStoreDescriptionUsesTheSameKoFi() {
        val text = File(root.parentFile, "fastlane/metadata/android/en-US/full_description.txt").readText()
        assertTrue(text.contains(entries.single { it.first == "Ko-fi" }.second))
    }

    private companion object {
        const val BECH32 = 1
        const val BECH32M = 0x2bc830a3
    }
}
