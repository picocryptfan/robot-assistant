// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// fix-screenshots-i: review-screenshots-h's findings on fix-screenshots-h. A lone PIN label looked only three lines
// down, so a PIN above it or four lines down read in clear (High 1); a receipt with its amount on the VERIFIED BY PIN
// line skipped (Medium 1); a PIN's spelled-out name was no label (Low 3).
class ShotSecretsReviewITest {
    private fun skipped(t: String) = ShotRead.entry(1, 2, t)?.skipped == true
    private fun allSkipped(vararg t: String) = t.filterNot { skipped(it) }.let { assertTrue("read: $it", it.isEmpty()) }
    private fun allRead(vararg t: String) = t.filter { skipped(it) }.let { assertTrue("skipped: $it", it.isEmpty()) }

    @Test fun h1APinAboveItsLabelOrFourLinesDownSkips() {
        allSkipped("4821\nPIN", "Bank app, card details\nJane Doe, Visa Debit\n4821\nPIN", "4821\n« NIP »",
            "PIN\nVisa Debit\nJane Doe\nTap to hide\n4821", "NIP\nCarte Visa\nJeanne Roy\nToucher pour masquer\n4821",
            "Pin\n\nShow\n\nHide\n\nCopy\n4821")
    }

    @Test fun m1AnAmountOnTheVerifiedByPinLineReads() {
        allRead("Debit purchase of \$54.21, verified by PIN", "TOTAL \$54.21 VERIFIED BY PIN",
            "Paid with Visa Debit ending 1234, verified by PIN", "Achat de 54,21 \$, vérifié par NIP",
            "TOTAL 54,21 \$ VÉRIFIÉ PAR NIP")
        // A lone number with the phrase is still a PIN beside its label.
        allSkipped("4821 VERIFIED BY PIN", "4821 VÉRIFIÉ PAR NIP")
    }

    @Test fun low3APinsSpelledOutNameIsALabel() {
        allSkipped("Personal identification number\n4821", "Numéro d'identification personnel\n4821",
            "Numéro d’identification personnel\n4821")
    }
}
