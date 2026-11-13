// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// review-screenshots-c's 20 cases (S, E) and its 30 probes (P, K), logs/review-screenshots-c, through the whole entry,
// and fix-screenshots-e's rows for each rule's shape. A secret's case: its screenshot is skipped (P2 and P20, a bare
// "code", are read with the value hidden). An everyday one: read, not skipped. E8 stays skipped: an IMEI passes Luhn
// and no label says what it is (counted for screenshots-plan question 96).
class ShotSecretsReviewCTest {
    private class Case(val id: String, val secret: String?, val text: String)

    private val cases = listOf(
        Case("S1", "Otter", "Home Wi-Fi\nNetwork  BELL-5G-22\nPassw0rd\nmaple-Otter-77"),
        Case("S2", "482913", "Bank app\nYour sign-in C0de is 482913. It expires in 10 minutes."),
        Case("S3", "731", "Desjardins\nVotre code de v\u00e9rification\u00a0: 731\u00a0204\nNe le partagez avec personne."),
        Case("S4", "Brume", "Mot de passe\u00a0:\nSoleil!Brume2024"),
        Case("S5", "1111", "Carte enregistr\u00e9e\n4111\u00a01111\u00a01111\u00a01111\nExp. 08/29"),
        Case("S6", "822463", "Card number\n3782-822463-10005\nCID 4411"),
        Case("S7", "5531", "Ta nouvelle carte\nNIP\u00a0: 5531"),
        Case("S8", "918", "One-time passcode\n918 273\nDon't share this with anyone"),
        Case("S9", "29qa", "Backup codes\n4f7k-29qa\n8m2p-x6tn\n77hb-q3we"),
        Case("S10", "Biscuit", "Virement Interac\nQuestion de s\u00e9curit\u00e9\u202f? Nom de mon premier chien\nR\u00e9ponse\u00a0: Biscuit2019"),
        Case("S11", "5555", "Paid with 5555.5555.5555.4444 on Nov 3"),
        Case("S12", "714", "Code de s\u00e9curit\u00e9 (CVC)\u00a0: 714"),
        Case("E1", null, "SOBEYS QUINPOOL\nMILK 2L 5.49\nBREAD 3.99\nSUBTOTAL 9.48\nHST 15% 1.42\nTOTAL 10.90\nVISA ************1111\nAUTH CODE: 08812C\nAPPROVED - THANK YOU"),
        Case("E2", null, "Carte d'embarquement / Boarding pass\nHalifax YHZ \u2192 Montr\u00e9al YUL\nVol AC 665  Si\u00e8ge 14C\nPorte 22  Embarquement 06\u00a0h\u00a040\nCode de r\u00e9servation\u00a0: X7KQ2P\nNo de billet 0142345678901"),
        Case("E3", null, "Canada Post\nTracking number\n7023210544321987\nDelivered to community mailbox\nOrder # 701-4829137-5520311"),
        Case("E4", null, "Tarte aux bleuets\n2\u00a0tasses de bleuets sauvages\nCuire 45\u00a0min \u00e0 190\u00a0\u00b0C\nCode promo\u00a0: BLEUETS15 sur ta prochaine commande"),
        Case("E5", null, "Livraison\n1234, rue Robie\nHalifax (N.-\u00c9.)\nCode postal\u00a0: B3H\u00a03C3\nT\u00e9l.\u00a0: 902-555-0147"),
        Case("E6", null, "Foundation gala\nSaturday, November 15, 7 p.m.\nDress code: black tie\nShow this QR code at the door"),
        Case("E7", null, "Couldn't sync\nError code: 0x80070005\nTry again later"),
        Case("E8", null, "About phone\nModel Pixel 8\nAndroid version 16\nIMEI 356938035643809"),
        Case("P1", "maple", "Passw0rd: maple-Otter"),
        Case("P2", "482913", "C0de: 482913"),
        Case("P3", "Biscuit", "Virement Interac\nQuestion de s\u00e9curit\u00e9\u202f? Nom de mon premier chien\nR\u00e9ponse\u00a0: Biscuit"),
        Case("P4", "Biscuit", "Security question: first pet\nAnswer: Biscuit"),
        Case("P5", "correcthorse", "Network security key: correcthorsebattery"),
        Case("P6", "correcthorse", "Security key\ncorrecthorsebattery"),
        Case("P7", "maple", "Wi-Fi BELL-5G-22, key: maple-otter"),
        Case("P8", "5Kb8", "Private key: 5Kb8kLf9zgWQnogidDA76MzPL6TsZZY36hWXMssSzNydYXYB9KF"),
        Case("P9", "sk_live", "API key: sk_live_51Hx9QaZ2kLmN8pR"),
        Case("P10", "1111  1111", "Card 4111  1111  1111  1111"),
        Case("P11", "1111 1111", "Card\n4111 1111\n1111 1111"),
        Case("P12", "1111 - 1111", "4111 - 1111 - 1111 - 1111"),
        Case("P13", null, "Roll the dough out with a rolling pin to 3 mm."),
        Case("P14", null, "Jump to Recipe \u00b7 Pin Recipe \u00b7 Print Recipe\nMaple butter tarts"),
        Case("P15", null, "One-time purchase \$24.99 or Subscribe & Save"),
        Case("P16", null, "Season pass: Wentworth 2026-27"),
        Case("P17", null, "Last login: Nov 9, 2026"),
        Case("P18", null, "Your order\nOrder # 112-4829137-5520313"),
        Case("P19", null, "SACS \u00c0 USAGE UNIQUE 0,10\u00a0\$"),
        Case("P20", "4821", "Code secret\u00a0: 4821"),
        Case("P21", "maplehouse", "Wireless key: maplehouse"),
        Case("P22", "maplehouse", "Pre-shared key: maplehouse"),
        Case("P23", "soleil", "Mot-de-passe\u00a0: soleil"),
        Case("P24", "hunter", "Pass word: hunter"),
        Case("P25", "4111/1111", "4111/1111/1111/1111"),
        Case("P26", null, "Bobby pins, hair ties, sunscreen"),
        Case("K1", "hunter2", "Wi-Fi password: hunter2"),
        Case("K2", null, "Coupon code: SPRING25"),
        Case("K3", null, "Order 4111111111111112"),
        Case("K4", "4111", "Paid 4111111111111112"),
    )
    // Read, the value hidden by the line layer: a bare "code" (P2, P20); 16 digits that fail Luhn after "Paid" (K4).
    private val bareCode = setOf("P2", "P20", "K4")
    private val stillSkipped = setOf("E8")

    private fun skipped(t: String) = ShotRead.entry(1, 2, t)?.skipped == true
    private fun indexed(t: String) = ShotRead.entry(1, 2, t)?.takeUnless { it.skipped }?.text ?: ""

    @Test fun theReviewsCasesHold() {
        val kept = cases.filter { c -> c.secret != null && c.secret in indexed(c.text) }.map { it.id }
        val read = cases.filter { c -> c.secret != null && !skipped(c.text) }.map { it.id }
        val skip = cases.filter { c -> c.secret == null && skipped(c.text) }.map { it.id }
        val msg = "secret kept ${kept.size} $kept; read ${read.size} $read; skipped needlessly ${skip.size} $skip"
        println("review-c: " + msg)
        assertTrue(msg, kept.isEmpty() && read.toSet() == bareCode && skip.toSet() == stillSkipped)
    }

    // Item 1: labels on folded text (OCR slips, any space kind), a card across any short gap.
    @Test fun ocrSlipsAndGapsDontHideASecret() {
        for (t in listOf("P@ssw0rd: tulip", "Pa\$\$word\ntulip", "P1N: 4821", "Card 4111 / 1111 / 1111 / 1111", "4111 _ 1111 _ 1111 _ 1111"))
            assertTrue(t, skipped(t))
        assertTrue("482913" !in indexed("C0de: 482913"))
    }

    // Item 2: a label ending in "key" or starting with "clé", whatever its value; an answer only near a question.
    @Test fun aKeyOrAnAnswerUnderAQuestionSkips() {
        for (t in listOf("Clé de sécurité\u00a0: soleil", "Clé privée\nabcdef", "Wireless network key\nmaplehouse", "Security question\n\nAnswer: tulip"))
            assertTrue(t, skipped(t))
        assertTrue("Biscuit" in indexed("Quiz night\nAnswer: Biscuit the dog"))
        assertTrue("continue" in indexed("Press any key\nto continue"))
    }

    // Item 3: pin, pass, login, one-time and usage unique count only as a label.
    @Test fun aShortWordCountsOnlyAsALabel() {
        for (t in listOf("PIN: 4821", "Login code: 482913", "Your PIN is 4821", "Code NIP\u00a0: 4821", "Login:\nmaple", "pin\n4821", "Code à usage unique\u00a0: 731204"))
            assertTrue(t, skipped(t))
        for (t in listOf("Last login: Nov 9, 2026", "Season pass: Wentworth 2026-27", "Roll it with a rolling pin", "One-time purchase \$24.99", "Sacs à usage unique 0,10\u00a0\$"))
            assertTrue(t, !skipped(t))
    }

    // Item 4: a number right after its label (on its line or the line above) stays in both layers, even passing Luhn.
    @Test fun aNumberUnderItsLabelStays() {
        assertTrue("7023210544321987" in indexed("Canada Post\nTracking number\n7023210544321987"))
        assertTrue("112-4829137-5520313" in indexed("Your order\nOrder # 112-4829137-5520313"))
        assertTrue("4111111111111111" in indexed("E-ticket number: 4111111111111111"))
        // A card after the order's own number still skips.
        assertTrue(skipped("Order # 701-4829137-5520311\n4111 1111 1111 1111"))
    }
}
