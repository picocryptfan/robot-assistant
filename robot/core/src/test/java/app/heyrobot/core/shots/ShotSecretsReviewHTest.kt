// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// fix-screenshots-h: review-screenshots-g's findings on fix-screenshots-g. A lone PIN line looked only one line down,
// for the skip and for the line hiding, so a PIN two lines down was kept in clear (High 1); "PIN*" or « NIP » never
// reached the digit check (Medium 1); a receipt's "VERIFIED BY PIN" beside a lone number read (Low 1); a PIN split over
// two lines read in clear (Low 2).
class ShotSecretsReviewHTest {
    private fun skipped(t: String) = ShotRead.entry(1, 2, t)?.skipped == true
    private fun allSkipped(vararg t: String) = t.filterNot { skipped(it) }.let { assertTrue("read: $it", it.isEmpty()) }
    private fun allRead(vararg t: String) = t.filter { skipped(it) }.let { assertTrue("skipped: $it", it.isEmpty()) }
    /** The line hiding alone, the second layer: none of [values] left in clear. */
    private fun allHidden(values: List<String>, vararg t: String) = t.filter { s ->
        val out = ShotSecrets.hide(s).text
        values.any { Regex("""(?<!\d)$it(?!\d)""").containsMatchIn(out) }
    }.let { assertTrue("in clear: $it", it.isEmpty()) }

    @Test fun h1APinTwoOrThreeLinesDownIsSkippedAndHidden() {
        val rows = arrayOf("PIN\nVisa Debit\n4821", "Card details\nPIN\nJane Doe\n4821", "Pin\nTap to reveal\n4821",
            "NIP\nCarte Visa\n4821", "PIN\nКод\n4821", "PIN\n\n\nName\n4821", "PIN\nVisa\nDebit\n4821")
        allSkipped(*rows)
        allHidden(listOf("4821"), *rows)
    }

    @Test fun h2APinLabelWithMarksReachesTheDigitCheck() {
        val rows = arrayOf("PIN*\n4821", "PIN -\n4821", "« NIP »\n4821", "«NIP»\n4821", "PIN*\nJane Doe\n4821",
            "« NIP »\nCarte\n4821")
        allSkipped(*rows)
        allHidden(listOf("4821"), *rows)
    }

    @Test fun h3VerifiedByPinBesideALoneNumberSkips() {
        allSkipped("VERIFIED BY PIN\n4821", "4821 VERIFIED BY PIN", "PIN VERIFIED\n4821", "4821\nVERIFIED BY PIN",
            "VÉRIFIÉ PAR NIP\n4821")
        // No label's form: read, and the line hiding takes the number.
        allHidden(listOf("4821"), "VERIFIED BY PIN\n4821", "4821 VERIFIED BY PIN", "PIN VERIFIED\n4821",
            "Pin 4821\nVERIFIED BY PIN")
    }

    @Test fun h4APinSplitOverLinesIsSkippedAndHiddenWhole() {
        allSkipped("PIN\n48\n21", "Pin\n4\n8\n2", "NIP\n48\n21")
        allHidden(listOf("48", "21"), "PIN\n48\n21", "NIP\n48\n21", "PIN\n48 21")
    }

    @Test fun h5NearMissesStillRead() {
        allRead("Reply\nForward\nCopy\nPin\nDelete", "Home\nSearch\nPins\nProfile", "Saved\nPins\nBoards", "Message\nPin",
            "PINS\nBoards\nProfile", "Pins\n12 saved", "SOBEYS 123\nTOTAL \$54.21\nINTERAC\nVERIFIED BY PIN\nAPPROVED",
            "VISA DEBIT\nVerified by PIN\n00 APPROVED - THANK YOU 027", "INTERAC\nPIN VERIFIED\nAUTH 012345",
            "INTERAC\nVÉRIFIÉ PAR NIP\nAPPROUVÉE", "NIP VERIFIE", "TOTAL\n\$54.21\nVERIFIED BY PIN")
        // Recorded as they come out: three digits within three lines of a lone pin line is a value, the safe side.
        allSkipped("Pins\n124 saved")
    }

    /** review-screenshots-h Low 2: a clock time or a date near a lone pin line isn't its value; a number beside them still is. */
    @Test fun low2TimesAndDatesArentAValue() {
        allRead("Reply\nForward\nCopy\nPin\nDelete\n10:42", "Message\nPin\nCopy text\nForward\nSent 10:42 AM",
            "TOTAL\n\$54.21\nVISA DEBIT\n************1234\nVERIFIED BY PIN\n2026-11-11\n14:32:05",
            "Reçu\nTOTAL 54,21 \$\nINTERAC\nVÉRIFIÉ PAR NIP\nAPPROUVÉE\n2026-11-11")
        allSkipped("PIN\n4821\n10:42", "Pin\n10:42\n4821", "VERIFIED BY PIN\n2026-11-11\n4821", "NIP\n2026-11-11\n48 21",
            "PIN\n10:42 4821")
    }
}
