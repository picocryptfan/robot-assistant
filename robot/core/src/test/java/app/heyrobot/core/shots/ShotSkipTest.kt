// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ShotStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-screenshots-d item 1: a screenshot with a secret's label anywhere (any case, accents or spaces) or a card's number
// (however grouped) is never indexed as text, only as there; the bare word "code" isn't a label.
class ShotSkipTest {
    private fun indexed(text: String) = ShotRead.entry(1, 2, text)!!.text

    /** The text, and a word in it that must be gone (not a secret: nothing of the screenshot stays). */
    private val skipped = listOf(
        "Wi-Fi\n**Password:**\nhunter2" to "Wi-Fi",
        "Recovery phrase:\napple banana cherry dog" to "banana",
        "Secret Key: A3-ASWWYB-798JRY" to "ASWWYB",
        "Google Authenticator\nGitHub\n193 027" to "GitHub",
        "Login: alex / hunter2" to "hunter2",
        "MOT\u00a0DE\u202fPASSE : soleil" to "soleil",
        "Phrase de récupération : cheval correct pile" to "cheval",
        "CLE SECRETE : ABCD-EFGH" to "EFGH",
        "Codes de secours\nmaison jardin" to "jardin",
        "Seed words: apple banana" to "apple",
        "Your PIN was changed at Sobeys" to "Sobeys",
        "Visa 4111\u00a01111\u202f1111-1111 exp 09/29" to "09/29",
        "Paid with 4111111111111111 at Sobeys" to "Sobeys",
        "OTP 4821 for Sobeys" to "Sobeys",
        "Ton code de vérification : 482913, Sobeys" to "Sobeys",
        "Backup codes\nalpha bravo" to "bravo",
        "Ton code d’accès : 4821 chez Sobeys" to "Sobeys",
    )

    /** No label and no card: read, the line hiding on top. */
    private val read = listOf(
        "Coupon code: SPRING25" to "SPRING25",
        "Code postal : B3H 4R2" to "B3H 4R2",
        "FedEx tracking 7489 1234 5678 901" to "7489 1234 5678 901",
        "Call 902-555-0142 about the security deposit" to "902-555-0142",
        "Dropped pin near Halifax Citadel" to "Citadel",
        "Boarding pass: Gate 12, seat 14C" to "Gate 12",
    )

    @Test fun aScreenshotWithASecretLabelOrACardIsNotReadAtAll() {
        for ((t, w) in skipped) assertFalse(t, w in indexed(t))
    }

    @Test fun noLabelAndNoCardIsRead() {
        for ((t, w) in read) assertTrue(t, w in indexed(t))
    }

    @Test fun aSkippedScreenshotIsThereAndCounted() {
        val e = ShotRead.entry(7, 2, "Wi-Fi password: hunter2")!!
        assertTrue(e.skipped)
        assertEquals(ShotRead.SKIPPED, e.text)
        assertEquals(e, ShotBatch.fromJson(ShotBatch(listOf(e)).toJson()).add.single())
        assertFalse(ShotRead.entry(8, 2, "Coupon code: SPRING25")!!.skipped)
        assertEquals(listOf(e), ShotIndex.search(listOf(e), "Do I have a screenshot of my Wi-Fi password?"))
        val en = Langs(Lang.EN, Lang.EN, Lang.EN)
        val said = ShotAsk.none(listOf(e, e.copy(id = 8)), 0).render(en)
        assertTrue(said, "2 more" in said && "password" in said)
        assertEquals(ShotStrings.nothing().render(en), ShotAsk.none(emptyList(), 0).render(en))
    }
}
