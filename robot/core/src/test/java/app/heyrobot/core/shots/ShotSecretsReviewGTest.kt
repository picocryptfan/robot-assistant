// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// fix-screenshots-g: fix-screenshots-f's three skip Lows (logs/fix-screenshots-g/cases.tsv, its ids): a lone "Pin" or
// "Pins" line, a receipt's "VERIFIED BY PIN", and a list of years on lines of their own read as a card; and their near
// misses, which still skip.
class ShotSecretsReviewGTest {
    private fun skipped(t: String) = ShotRead.entry(1, 2, t)?.skipped == true
    private fun allRead(vararg t: String) = t.filter { skipped(it) }.let { assertTrue("skipped: $it", it.isEmpty()) }
    private fun allSkipped(vararg t: String) = t.filterNot { skipped(it) }.let { assertTrue("read: $it", it.isEmpty()) }

    @Test fun g1ALonePinLineWithNoValueReads() {
        allRead("Reply\nForward\nCopy\nPin\nDelete", "Home\nSearch\nPins\nProfile", "Saved\nPins\nBoards", "Message\nPin",
            "PINS\nBoards\nProfile", "Pins\n12 saved")
        allSkipped("PIN\n4821", "Pin\n4821", "pin\n4821", "Pins\n\n4821", "PIN\n48 21", "Card / PIN\n4821", "NIP\n4821",
            "Pin: 4821", "PIN: 4821", "Your PIN 4821", "Enter your PIN\n4821", "Pin code 4821", "P1N\n4821")
        // review-last-fixes-14's S5: no label's form, so the line hiding takes the number.
        assertTrue("4821" !in (ShotRead.entry(1, 2, "Your Pin is 4821")?.text ?: "4821"))
    }

    @Test fun g2AReceiptsVerifiedByPinReads() {
        allRead("SOBEYS 123\nTOTAL \$54.21\nINTERAC\nVERIFIED BY PIN\nAPPROVED", "VISA DEBIT\nVerified by PIN\n00 APPROVED - THANK YOU 027",
            "INTERAC\nPIN VERIFIED\nAUTH 012345", "INTERAC\nVÉRIFIÉ PAR NIP\nAPPROUVÉE", "NIP VERIFIE")
        allSkipped("VERIFIED BY PIN 4821", "PIN VERIFIED: 4821", "VERIFIED BY PIN\nPIN: 4821", "VERIFIED BY PIN\nPIN\n4821")
    }

    // Years on lines of their own, 1,000 lists a kind and length (seed 7): before, a run of 4-digit groups joined into
    // a "card" whenever four of them passed Luhn.
    private val r = java.util.Random(7)
    private fun years(kind: String, k: Int): String {
        val ys = when (kind) {
            "consecutive" -> (1950 + r.nextInt(70)).let { s -> (s until s + k).toList() }
            "descending" -> (1960 + r.nextInt(66)).let { s -> (s downTo s - k + 1).toList() }
            else -> (1900..2026).shuffled(r).take(k).sorted()
        }
        return ys.joinToString("\n")
    }

    @Test fun g3AListOfYearsIsNoCard() {
        val rates = listOf("consecutive", "descending", "random").flatMap { kind ->
            listOf(4, 5, 8, 12).map { k -> "$kind x$k: " + (1..1000).count { ShotSecrets.skips(years(kind, k)) } / 10.0 + "%" }
        }
        println("review-g years: $rates")
        assertTrue(rates.toString(), rates.all { it.endsWith(" 0.0%") })
        allRead("2001\n2002\n2003\n2004\n2005\n2006", "Seasons\n2019\n2020\n2021\n2022\n2023")
        // A card on lines of its own, or beside a year list, still skips.
        allSkipped("4111\n1111\n1111\n1111", "Card\n4111 1111\n1111 1111", "2019\n2020\n2021\n4111 1111 1111 1111",
            "2024\n4111\n1111\n1111\n1111", "4111\n1111\n1111\n1111\n2019\n2020", "2221\n0000\n0000\n0009")
    }
}
