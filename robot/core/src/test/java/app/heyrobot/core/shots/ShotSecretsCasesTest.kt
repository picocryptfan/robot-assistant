// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertTrue
import org.junit.Test

// review-screenshots-b's 63 cases (logs/review-screenshots-b/all.tsv), through the whole entry (skip, then the line
// hiding): H, the secret is gone; K, it stays. Two differ from the review's file, as fix-screenshots-d asks: X11 holds a
// verification code, so the whole screenshot is skipped (S) and its booking code with it; B7's 16 digits fail Luhn on an
// order line, so they stay (K).
class ShotSecretsCasesTest {
    private class Case(val id: String, val expect: Char, val text: String, val secret: String)

    private val cases = listOf(
        Case("O1", 'H', "FB-12345 is your Facebook confirmation code", "12345"),
        Case("O2", 'H', "Your Instagram confirmation code is 739104", "739104"),
        Case("O3", 'H', "Your password is: hunter2", "hunter2"),
        Case("O4", 'H', "Use 482913 to verify your account", "482913"),
        Case("O5", 'H', "Your verification code is 482 913", "913"),
        Case("O6", 'H', "Steam Guard code: F4K2P", "F4K2P"),
        Case("O7", 'H', "Ton NIP : 4821", "4821"),
        Case("O8", 'H', "Mot de passe à usage unique : 739104", "739104"),
        Case("O9", 'H', "Mot de passe temporaire : Xy7pq2", "Xy7pq2"),
        Case("O10", 'H', "Card number 4111 1111 1111 1112 exp 09/28", "1112"),
        Case("O11", 'H', "Code de sécurité : 123", "123"),
        Case("O12", 'H', "Wi-Fi password\nCafe2024!", "Cafe2024"),
        Case("O13", 'H', "Password: correct horse battery staple", "horse"),
        Case("O14", 'H', "Your recovery key: ABCD-1234-EFGH-5678-IJKL", "EFGH"),
        Case("O15", 'H', "Enter 482913 to log in", "482913"),
        Case("O16", 'K', "Code de réservation : HX4R2913", "HX4R2913"),
        Case("O17", 'K', "Booking confirmation: HX4R2913", "HX4R2913"),
        Case("O18", 'H', "Votre code de vérification est 482913", "482913"),
        Case("O19", 'H', "Mot de passe : Soleil2024", "Soleil2024"),
        Case("O20", 'H', "4111111111111111", "4111"),
        Case("O21", 'H', "VOTRE CODE DE VÉRIFICATION : 482913", "482913"),
        Case("O22", 'K', "Boarding pass: Gate 12, seat 14C", "14C"),
        Case("F1", 'H', "**Verification code:** 739104", "739104"),
        Case("F2", 'H', "Use 739104 to sign in to your account", "739104"),
        Case("F3", 'H', "Card number: 4111 1111 1111 1111", "4111"),
        Case("F4", 'H', "Exp 08/29 · CVC 491", "491"),
        Case("F5", 'H', "Recovery codes:\nabcd-efgh-ijkl\nmnop-qrst-uvwx", "efgh"),
        Case("F6", 'H', "Wi-Fi key: SunnyDay!2024", "SunnyDay"),
        Case("F7", 'H', "Ton code d'accès temporaire est le 58 29 13", "29 13"),
        Case("F8", 'H', "Numéro de carte : 4520-8811-2233-4455", "8811"),
        Case("F9", 'H', "Phrase de passe : cheval correct pile agrafe", "cheval"),
        Case("F10", 'H', "G-582913 is your Google verification code.", "582913"),
        Case("K1", 'K', "Numéro de suivi : 7023456789012345", "7023456789012345"),
        Case("K2", 'K', "Error code 0x80070005 when updating", "0x80070005"),
        Case("K3", 'K', "Code postal : B3H 4R2", "B3H 4R2"),
        Case("K4", 'K', "Coupon code: SPRING25", "SPRING25"),
        Case("K5", 'K', "Amazon order #112-3456789-1234567", "3456789"),
        Case("X1", 'H', "**Password:**\nhunter2", "hunter2"),
        Case("X2", 'H', "**Mot de passe :**\nsoleil", "soleil"),
        Case("X3", 'H', "Your code: 7 3 9 1 0 4", "7 3 9"),
        Case("X4", 'H', "Numéro de carte : 4520\u00a08811\u00a02233\u00a04455", "8811"),
        Case("X5", 'H', "Card: 4520\u202f8811\u202f2233\u202f4455", "8811"),
        Case("X6", 'H', "Recovery phrase:\napple banana cherry dog eagle fox grape hill ice jam kite lemon", "banana"),
        Case("X7", 'H', "Secret Key: A3-ASWWYB-798JRY-LJVD4-23DC2-86TVM-H43EB", "798JRY"),
        Case("X8", 'K', "USPS tracking: 9400 1000 0000 0000 0000 00", "9400"),
        Case("X9", 'H', "Google Authenticator\nGoogle (alex)\n482 913\nGitHub\n193 027", "193 027"),
        Case("X10", 'H', "PW: hunter2", "hunter2"),
        Case("X11", 'S', "Your verification code is 482913\nBooking code: HX4R2913", "482913"),
        Case("X12", 'H', "Login: alex / hunter2", "hunter2"),
        Case("X13", 'H', "Password (case-sensitive):\nhunter2", "hunter2"),
        Case("X14", 'K', "Area code 902", "902"),
        Case("X15", 'H', "WPA2 key: Sunny2024!", "Sunny2024"),
        Case("X16", 'K', "Call 902-555-0142 about the security deposit", "0142"),
        Case("X17", 'H', "Backup codes: 8472 1938 2049 5831", "2049"),
        Case("X18", 'H', "Recovery codes:\nabcd-efgh-ijkl", "efgh"),
        Case("B1", 'K', "Delta: trip to Halifax, confirmation code HX4R29, Nov 12", "HX4R29"),
        Case("B2", 'K', "Code de confirmation du vol : HX4R29", "HX4R29"),
        Case("B3", 'K', "FedEx tracking 7489 1234 5678 901", "7489"),
        Case("B4", 'K', "Ticket number: 0142345678901", "0142345678901"),
        Case("B5", 'K', "Your Air Canada booking HX4R29. Sign in to manage it.", "HX4R29"),
        Case("B6", 'K', "Order 112-3456789-1234567 shipped", "3456789"),
        Case("B7", 'K', "Order 4111111111111112", "1112"),
        Case("B8", 'H', "Your confirmation code: A1B2C3D4", "A1B2C3D4"),
    )

    @Test fun theReviewsCasesHoldBothWays() {
        val kept = ArrayList<String>()
        val over = ArrayList<String>()
        for (c in cases) {
            val e = ShotRead.entry(1, 2, c.text)
            val has = e != null && c.secret in e.text
            if (c.expect == 'K' && !has) over += c.id
            if (c.expect != 'K' && has) kept += c.id
        }
        val read = cases.filter { it.expect != 'K' && ShotRead.entry(1, 2, it.text)?.skipped != true }.map { it.id }
        val skip = cases.filter { it.expect == 'K' && ShotRead.entry(1, 2, it.text)?.skipped == true }.map { it.id }
        println("review-b: secret kept ${kept.size} $kept; read ${read.size} $read; skipped needlessly ${skip.size} $skip")
        assertTrue("kept a secret ${kept.size} $kept; hid needlessly ${over.size} $over", kept.isEmpty() && over.isEmpty())
    }
}
