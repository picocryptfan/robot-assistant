// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// review-screenshots' 22 formats (H1, M2) and ten more, EN and fr: a secret's value never stays (an over-hide is a Low, a
// kept secret a High), and a booking's or a boarding pass's own code stays when its line names it.
class ShotSecretsRowsTest {
    /** A line, the values that must be gone, the text that must stay. */
    private class Row(val text: String, val gone: List<String>, val stays: List<String> = emptyList())

    private val rows = listOf(
        Row("FB-12345 is your Facebook confirmation code", listOf("12345")),
        Row("Your Instagram confirmation code is 739104", listOf("739104")),
        Row("Your password is: hunter2", listOf("hunter2")),
        Row("Use 482913 to verify your account", listOf("482913")),
        Row("Your verification code is 482 913", listOf("482", "913")),
        Row("Steam Guard code: F4K2P", listOf("F4K2P")),
        Row("Ton NIP : 4821", listOf("4821")),
        Row("Mot de passe à usage unique : 739104", listOf("739104")),
        Row("Mot de passe temporaire : Xy7pq2", listOf("Xy7pq2")),
        Row("Card number 4111 1111 1111 1112 exp 09/28", listOf("4111", "1112")),
        Row("Code de sécurité : 123", listOf("123")),
        Row("Wi-Fi password\nCafe2024!", listOf("Cafe2024")),
        Row("Password: correct horse battery staple", listOf("correct", "horse", "staple")),
        Row("Your recovery key: ABCD-1234-EFGH-5678-IJKL", listOf("1234", "EFGH", "5678", "IJKL")),
        Row("Enter 482913 to log in", listOf("482913")),
        Row("Code de réservation : HX4R2913", emptyList(), listOf("HX4R2913")),
        Row("Booking confirmation: HX4R2913", emptyList(), listOf("HX4R2913")),
        Row("Votre code de vérification est 482913", listOf("482913")),
        Row("Mot de passe : Soleil2024", listOf("Soleil2024")),
        Row("4111111111111111", listOf("4111111111111111")),
        Row("VOTRE CODE DE VÉRIFICATION : 482913", listOf("482913")),
        Row("Boarding pass: Gate 12, seat 14C", emptyList(), listOf("Gate 12, seat 14C")),
        // Ten more.
        Row("Your Uber code: 4821. Never share this code.", listOf("4821")),
        Row("Microsoft account security code: 7392 0461", listOf("7392", "0461")),
        Row("Ton code d'authentification\n582193", listOf("582193")),
        Row("Your PIN is 0472", listOf("0472")),
        Row("WhatsApp code 271-904", listOf("271", "904")),
        Row("Network key: Maple!Leaf88", listOf("Maple", "Leaf88")),
        Row("MDP : Hiver#2025", listOf("Hiver")),
        Row("Carte 4520 1234 5678 9012", listOf("4520", "9012")),
        Row("Sign in with this code\nAB12-CD34", listOf("AB12")),
        Row("Ton code de sécurité Desjardins est 845 210", listOf("845", "210")),
        Row("Ton code de réservation est QX12345", emptyList(), listOf("QX12345")),
    )

    @Test fun noFormatKeepsItsSecret() {
        for (r in rows) {
            val h = ShotSecrets.hide(r.text).text
            for (g in r.gone) assertFalse("${r.text} -> $h", g in h)
            for (s in r.stays) assertTrue("${r.text} -> $h", s in h)
            if (r.gone.isNotEmpty()) assertTrue("${r.text} -> $h", ShotSecrets.hide(r.text).kinds.isNotEmpty())
        }
    }
}
