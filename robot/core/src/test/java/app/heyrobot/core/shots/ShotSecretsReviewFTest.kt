// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// review-last-fixes-14's cases (logs/review-last-fixes-14/cases.tsv, its ids) through the whole entry: lists of numbers
// read as cards (M1), a login's password kept (M2), "Log in" or "Pass" ending a line skipping the shot (M3), and its Lows.
class ShotSecretsReviewFTest {
    private fun skipped(t: String) = ShotRead.entry(1, 2, t)?.skipped == true
    private fun indexed(t: String) = ShotRead.entry(1, 2, t)?.takeUnless { it.skipped }?.text ?: ""
    private fun allRead(vararg t: String) = t.filter { skipped(it) }.let { assertTrue("skipped: $it", it.isEmpty()) }
    private fun allSkipped(vararg t: String) = t.filterNot { skipped(it) }.let { assertTrue("read: $it", it.isEmpty()) }

    // The review's simulation (Shot.java's gen, seed 14): two to ten lines of a kind, 1,000 lists each. It measured,
    // over 4,000: phone numbers 27% (two) to 77% (five) skipped, ISO dates 66% (five), prices 54% (five); before, 0%.
    private val r = java.util.Random(14)
    private fun d(n: Int) = (1..n).joinToString("") { r.nextInt(10).toString() }
    private fun npa() = "${2 + r.nextInt(8)}${d(2)}"
    private fun list(kind: String, k: Int): String {
        val day = java.time.LocalDate.of(2026, 1, 1).plusDays(r.nextInt(700).toLong())
        return (0 until k).joinToString("\n") { i ->
            when (kind) {
                "phone" -> "${npa()}-${npa()}-${d(4)}"
                "phone()" -> "(${npa()}) ${npa()}-${d(4)}"
                "isodate" -> day.plusDays(i.toLong()).toString()
                else -> "\$" + (100 + r.nextInt(900)) + "." + d(2)
            }
        }
    }

    @Test fun m1ListsOfNumbersAreNoCard() {
        val rates = listOf("phone", "phone()", "isodate", "price").flatMap { kind ->
            listOf(2, 3, 5, 10).map { k -> "$kind x$k: " + (1..1000).count { ShotSecrets.skips(list(kind, k)) } / 10.0 + "%" }
        }
        println("review-f M1: $rates")
        assertTrue(rates.toString(), rates.all { it.endsWith(" 0.0%") })
        allRead("416-555-0148\n647-555-0199", "416-555-0148\n647-555-0199\n416-555-0123", "(416) 555-0148\n(647) 555-0199\n(905) 555-0172",
            "Call 416-555-0148 / 647-555-0199", "2026-11-11\n2026-11-12\n2026-11-13\n2026-11-14\n2026-11-15",
            "\$129.99\n\$249.99\n\$349.99\n\$199.99\n\$459.00", "Hours\n9:00 - 5:00\nPhone 902-555-0148\n902-555-0199")
        // The split cards review-c found still skip, and an Amex's 4-6-5 across wide gaps.
        allSkipped("Card 4111  1111  1111  1111", "Card\n4111 1111\n1111 1111", "4111 - 1111 - 1111 - 1111", "4111/1111/1111/1111",
            "Amex 3782  822463  10005", "Card 4111 / 1111 / 1111 / 1111")
    }

    @Test fun m2ALoginsPasswordSkips() {
        allSkipped("Netflix login: me@example.com / sunshine", "Router login: admin / letmein", "Netflix log in: me@example.com sunshine")
        allRead("Last login: Nov 9, 2026", "Previous login: Nov 2, 2026")
    }

    @Test fun m3LogInOrPassEndingALineIsNoLabel() {
        allRead("The Chronicle Herald\nSubscribe | Log in\nHalifax council votes on bike lanes",
            "Log In\nSign Up\nr/halifax\nWhere to get good donairs?", "Pass\nRaise\nFold")
        allSkipped("Login:\nmaple", "Gmail\nLogin\nme@example.com\nsunshine", "PIN: 4821", "pin\n4821")
    }

    @Test fun low1AKeyOrAnAnswerOnlyAsASecretsLabel() {
        allRead("Key: Bus stop, Ferry terminal", "Wonderwall\nKey: F#m  Capo: 2", "Issue key: ROB-142\nStatus: In progress",
            "Clé USB : 32 Go", "Question: Can I bring my dog?\nAnswer: Yes, on a leash.")
        allSkipped("Network security key: correcthorsebattery", "Wi-Fi BELL-5G-22, key: maple-otter", "Wi-Fi BELL-5G-22\nKey: maple-otter",
            "Private key: 5Kb8kLf9", "API key: sk_live_51Hx9QaZ2", "Clé de sécurité : soleil", "Clé privée\nabcdef",
            "Security key\ncorrecthorsebattery", "Security question: first pet\nAnswer: Biscuit")
    }

    @Test fun low2ACardAfterALabelIsStillACard() {
        allSkipped("Ticket 4111 1111 1111 1111", "Order #\n4111 1111 1111 1111", "Booking\n4111 1111 1111 1111")
        for (t in listOf("Ticket 4111 1111 1111 1111", "Order #\n4111 1111 1111 1111"))
            assertTrue(t, "4111" !in ShotSecrets.hide(t).text)
        // A number its label names stays.
        assertTrue("112-4829137-5520313" in indexed("Your order\nOrder # 112-4829137-5520313"))
        assertTrue("7023 4829 1137 5520 311" in indexed("Tracking number\n7023 4829 1137 5520 311"))
    }
}
