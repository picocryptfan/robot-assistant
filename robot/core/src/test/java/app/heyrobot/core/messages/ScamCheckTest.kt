// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scam texts (idea 17): made-up texts only (no real person, number or account); each rule alone; a sender's own link. */
class ScamCheckTest {
    private val en = Langs.of(Lang.EN)
    private val fr = Langs.of(Lang.FR)
    private fun why(text: String) = ScamCheck.of(text)?.why

    @Test fun theHeadlineExampleNamesCanadaPost() {
        val hit = ScamCheck.of("Canada Post: parcel held, pay \$1.99 at canadapost-redelivery.top")!!
        assertEquals(ScamWhy.WRONG_SENDER, hit.why)
        assertEquals("canadapost", hit.sender.id)
        assertEquals("This looks like a scam: that link isn't one of Canada Post's addresses.", hit.text().render(en))
        assertTrue(hit.text().render(fr), hit.text().render(fr).contains("pas une des adresses de Postes Canada"))
    }

    @Test fun madeUpScamsInEnglishAndFrench() {
        val set = mapOf(
            "CRA: You have a refund of \$512.40 waiting. Claim it at cra-refund-portal.com" to "cra",
            "RBC Royal Bank: unusual sign-in. Verify at https://rbc-secure-login.com/verify" to "rbc",
            "INTERAC e-Transfer: you received \$250.00. Deposit at interac-deposit.net" to "interac",
            "Rogers: your bill is overdue, pay now at rogers-billing.top/pay" to "rogers",
            "TELUS: your refund is ready at paypal-refund-center.com" to "telus",
            "ARC : un remboursement de 380 \$ t'attend. Réclame-le sur arc-remboursement.com" to "cra",
            "Desjardins : ton accès sera suspendu. Confirme sur desjardins-securite.info" to "desjardins",
            "Banque Nationale : confirme ton identité sur bnc-verif.com" to "nationalbank",
        )
        for ((text, id) in set) {
            val hit = ScamCheck.of(text)
            assertEquals(text, ScamWhy.WRONG_SENDER, hit?.why)
            assertEquals(text, id, hit!!.sender.id)
        }
        assertTrue(ScamCheck.of("ARC : réclame sur arc-remboursement.com")!!.text().render(fr).contains("pas une des adresses de l’ARC"))
        assertEquals("This looks like a scam: that link isn't one of the CRA's addresses.",
            ScamCheck.of("CRA: claim at cra-refund-portal.com")!!.text().render(en))
    }

    @Test fun eachRuleAlone() {
        assertEquals(ScamWhy.LOOKALIKE, why("Track it here: canadapots.ca/t/88"))
        assertEquals("canadapost", ScamCheck.of("Track it here: canadapots.ca/t/88")!!.sender.id)
        assertEquals("interac", ScamCheck.of("Your money is waiting: interac-deposit.com")!!.sender.id)
        assertEquals("purolator", ScamCheck.of("Suivi : purolatr.com/colis")!!.sender.id)
        assertEquals("bell", ScamCheck.of("Your bill: bell-billing.com")!!.sender.id)
    }

    @Test fun rule1ALookalikeAddressWarnsWhateverTheWordsAndANewsLinkNeverDoes() {
        for ((text, id) in listOf("Your Netflix subscription is on hold. Update your payment: netflix-billing-help.net" to "netflix",
            "Ton abonnement Netflix est en pause. Mets à jour ton paiement : netflix-paiement.com" to "netflix",
            "Your PayPal account is limited: paypal-resolution.com" to "paypal", "Account notice: secure-rbconline.help" to "rbc",
            "Votre accès : desjardins-connexion.info" to "desjardins",
            "TD Bank - Your online access has been put on hold. Restore it today: td-restore.com" to "td",
            "Ton identifiant Apple est verrouillé : appleid-verrouillage.com" to "apple", "Sign in to continue: rbc.secure-login.com" to "rbc")) {
            val hit = ScamCheck.of(text)
            assertEquals(text, ScamWhy.LOOKALIKE, hit?.why)
            assertEquals(text, id, hit!!.sender.id)
        }
        assertEquals("This looks like a scam: that link's address imitates Netflix.", ScamCheck.of("netflix-billing.com")!!.text().render(en))
        listOf("Netflix is putting prices up again https://www.cnn.com/2026/netflix-prices",
            "Netflix : les prix montent encore, lis ça https://www.lapresse.ca/affaires/netflix",
            "Rogers Park photos are up: rogers-park-photos.com", "Desjardins Street sale this weekend: desjardins-street-sale.ca",
            "Apple picking Saturday! apple-orchard-tours.ca", "Your Amazon order is out for delivery: amzn.to/3xYz",
            "PayPal me the \$20? paypal.me/sam", "omg our first dance song https://spotify.link/Qm3vR8xT2Kb",
            "My old email was at telus.net", "rbcbank.com", "Got it on amazon.co.uk/dp/B0C123", "Sign in at microsoftonline.com",
            "La boulangerie prend les commandes ici : belle-et-bonne.ca/commandes", "La garderie : arc-en-ciel-garderie.ca").forEach { assertNull(it, ScamCheck.of(it)) }
    }

    @Test fun rule2ATaggedSendersLinkMustBeItsOwn() {
        for ((text, id) in listOf("Interac: You received \$500.00. Accept at interac-depot.ca before it expires" to "interac",
            "[Canada Post] We could not deliver your item. Reschedule at canadapost-reschedule.info" to "canadapost",
            "RBC-Alert: unusual activity. Review at secure-rbc-access.com" to "rbc")) {
            val hit = ScamCheck.of(text)
            assertEquals(text, ScamWhy.WRONG_SENDER, hit?.why)
            assertEquals(text, id, hit!!.sender.id)
        }
        listOf("Canada Post strike: https://www.cbc.ca/news/canada-post-strike", "Postes Canada en grève : https://www.lapresse.ca/actualites/greve",
            "It's the Canada Post strike again https://www.cbc.ca/news/strike", "C'est Desjardins qui a le meilleur taux : https://www.ratehub.ca/taux",
            "TD and RBC rates compared: https://www.ratehub.ca/compare",
            "TELUS: Your order has shipped! Track: https://www.purolator.com/en/shipping/tracker?pin=335912048771",
            "Bell: Crave is now included with your Fibe TV plan. Activate it at crave.ca/bell",
            "TELUS: get the My TELUS app at https://apps.apple.com/ca/app/my-telus/id123").forEach { assertNull(it, ScamCheck.of(it)) }
    }

    @Test fun rule2AShortLinkUnderATagSaysItHidesWhereItGoes() {
        val hit = ScamCheck.of("Postes Canada : ton colis n'a pas pu être livré. Frais de 1,99 \$ : https://bit.ly/3abc")!!
        assertEquals(ScamWhy.SHORT_LINK, hit.why)
        assertEquals("canadapost", hit.sender.id)
        assertEquals("This looks like a scam: a short link hides where it goes.", ScamCheck.of("RBC: confirm at bit.ly/3abc")!!.text().render(en))
        assertTrue(hit.text().render(fr), hit.text().render(fr).contains("un lien court cache"))
        assertEquals(ScamWhy.WRONG_SENDER, ScamCheck.of("RBC: confirm at bit.ly/3abc or rbc-verify.com")?.why)
    }

    @Test fun rule3TheGovernmentNeverTextsAnArrestThreatWithANumber() {
        for ((text, id) in listOf("RCMP: A warrant is out for your arrest. Call 1-888-555-0199." to "rcmp",
            "GRC : un mandat d'arrestation est émis contre toi. Appelle le 1-888-555-0199." to "rcmp",
            "ARC : une poursuite judiciaire est engagée. Appelle le 1-844-555-0172." to "cra",
            "Canada Revenue Agency (CRA): FINAL NOTICE. A warrant has been issued for your arrest. Call 1-844-555-0187 today." to "cra",
            "CRA FINAL NOTICE: legal action is being taken against you. Call 1-844-555-0187." to "cra",
            "URGENT RCMP: you will be arrested today. Call 613-555-0142." to "rcmp",
            "Agence du revenu du Canada (ARC) : DERNIER AVIS. Un mandat d'arrestation est émis contre toi. Appelle le 1-844-555-0187." to "cra")) {
            val hit = ScamCheck.of(text)
            assertEquals(text, ScamWhy.CALL_BACK, hit?.why)
            assertEquals(text, id, hit!!.sender.id)
        }
        assertTrue(ScamCheck.of("GRC : un mandat contre toi. Appelle le 1-888-555-0199.")!!.text().render(fr).contains("la GRC"))
        listOf("CRA: your notice of assessment is ready. Questions? Call 1-800-555-0110.",
            "ARC : ton avis de cotisation est prêt. Questions : 1-800-555-0110",
            "lol the CRA called me about a warrant, number was 1-888-555-0199",
            "Service Canada: your SIN renewal is due. Call 1-800-555-0123.").forEach { assertNull(it, ScamCheck.of(it)) }
    }

    @Test fun aSendersOwnLinkAndOrdinaryTextsAreNotFlagged() {
        listOf(
            "Canada Post: your parcel 7023 is out for delivery today. Track at canadapost-postescanada.ca/track",
            "RBC: A purchase of \$52.10 was made on your card. Not you? Visit rbc.com/fraud",
            "TD: Your card is locked. Call 1-800-555-0123.",
            "INTERAC e-Transfer: Jean sent you \$40.00. Deposit at etransfer.interac.ca/abc",
            "Interac e-Transfer from Jean: deposit at www.rbc.com/deposit",
            "CRA: your notice of assessment is ready in My Account at canada.ca/my-cra-account",
            "Postes Canada : ton colis 4411 sera livré aujourd'hui. Suivi : postescanada.ca/suivi",
            "Desjardins : un paiement de 120 \$ a été fait. Détails sur desjardins.com",
            "Dinner at 6? Bring \$20 for pizza",
            "Meet at 6 a.m., e.g. at the café. It was \$1.99.",
            "Email me at sam@cp-redelivery.top",
            "Nova Scotia trip pics: photos.example.com/abc",
            "The interactive map: interactive-maps.com",
            "We booked bell-hotel.com",
            "Fred Rogers quote: example.org/quote",
        ).forEach { assertNull(it, ScamCheck.of(it)) }
    }

    @Test fun aLookalikeIsFlaggedAndTheRealDomainIsNot() {
        assertEquals(ScamWhy.LOOKALIKE, why("See desjardnis.com/login"))
        assertNull(ScamCheck.of("See desjardins.com/login"))
        assertNull(ScamCheck.lookalike("purolator.com"))
        assertEquals("canadapost", ScamCheck.lookalike("canadapost-tracking.info")!!.id)
    }

    @Test fun aNameRunTogetherWithPaddingCountsAlone() {
        for ((host, id) in listOf("canadapostredelivery.com" to "canadapost", "interacdeposit.com" to "interac", "netflixbilling.com" to "netflix",
            "rbcverify.com" to "rbc", "crarefund.ca" to "cra", "postescanadalivraison.com" to "canadapost", "rogersbilling.com" to "rogers"))
            assertEquals(host, id, ScamCheck.lookalike(host)?.id)
        // Under four letters, a word or a surname needs four letters of padding; a company's own address is its own.
        listOf("rbcbank.com", "microsoftonline.com", "rogerscentre.com", "bellca.com", "applepay.com", "mytd.com").forEach { assertNull(it, ScamCheck.lookalike(it)) }
    }

    @Test fun lockAndRestoreWordsArePaddingBesideANameThatIsAlsoAWord() {
        for ((host, id) in listOf("desjardins-acces.co" to "desjardins", "desjardins-deverrouillage.com" to "desjardins", "tangerine-unlock.com" to "tangerine",
            "tangerine-access.ca" to "tangerine", "rogers-restore.com" to "rogers", "apple-verrouillage.com" to "apple", "apple-recovery.com" to "apple"))
            assertEquals(host, id, ScamCheck.lookalike(host)?.id)
    }

    @Test fun aGovernmentNameLaterInTheFirstLineIsNoTag() {
        assertNull(ScamCheck.of("My accountant re CRA: the court date moved to Dec 3. Call her at 902-555-0141."))
    }

    @Test fun aNameOfThreePartsCounts() {
        assertEquals("bmo", ScamCheck.lookalike("bank-of-montreal-secure.com")?.id)
    }

    @Test fun virementInteracIsASenderTag() {
        val hit = ScamCheck.of("Virement Interac : Tu as reçu 250,00 \$. Accepte-le ici : bit.ly/3vKq")
        assertEquals(ScamWhy.SHORT_LINK, hit?.why)
        assertEquals("interac", hit!!.sender.id)
    }

    @Test fun aContactsTextIsReadWithTheWarningFirst() {
        // Mom Martin is in People: her phone could be stolen, so the rules still apply.
        val t = thread(Snapshots.cheogram1to1("Canada Post: parcel held, pay \$1.99 at canadapost-redelivery.top"))
        val p = MessageReader.thread(t, Lang.EN, more = false)
        assertEquals("This looks like a scam: that link isn't one of Canada Post's addresses.", p.spoken[1].text)
        assertEquals("Canada Post: parcel held, pay \$1.99 at canadapost-redelivery.top", p.spoken[2].text)
        assertTrue(p.shown.contains("\nThis looks like a scam: that link isn't one of Canada Post's addresses.\n- Canada Post"))
        assertFalse(p.kept.contains("scam"))
        val f = MessageReader.thread(t, Lang.FR, more = false)
        assertTrue(f.spoken[1].text, f.spoken[1].text.contains("arnaque") && f.spoken[1].text.contains("Postes Canada"))
        val plain = MessageReader.thread(thread(Snapshots.cheogram1to1("Bring bread")), Lang.EN, more = false)
        assertFalse(plain.shown.contains("scam"))
    }

    @Test fun lockedSaysHowManyAndNothingMore() {
        val scam = thread(Snapshots.cheogram1to1("Canada Post: parcel held, pay \$1.99 at canadapost-redelivery.top"))
        val other = thread(Snapshots.signal)
        val p = MessageReader.start(listOf(scam, other), Lang.EN, namesOnly = true)
        assertTrue(p.shown, p.spoken.map { it.text }.contains("1 conversation looks like a scam."))
        for (secret in listOf("Canada Post", "canadapost-redelivery", "1.99", "parcel")) assertFalse(secret, p.shown.contains(secret))
        assertTrue(p.ids.isEmpty())
        val none = MessageReader.start(listOf(other), Lang.EN, namesOnly = true)
        assertFalse(none.shown.contains("scam"))
    }

    @Test fun itNeverSaysSafe() {
        val hits = listOf("CRA: a warrant is out for you. Call 1-888-555-0199 now.", "Canada Post: pay at canadapost-redelivery.top", "RBC: confirm at bit.ly/3abc", "See desjardnis.com")
            .map { ScamCheck.of(it)!! }
        assertEquals(ScamWhy.values().size, hits.map { it.why }.toSet().size)
        val lines = hits.flatMap { listOf(it.text().render(en), it.text().render(fr)) } +
            listOf(1, 2).flatMap { n -> listOf(app.heyrobot.core.i18n.ScamStrings.locked(n).render(en), app.heyrobot.core.i18n.ScamStrings.locked(n).render(fr)) }
        for (l in lines) for (w in listOf("safe", "sûr", "sécuritaire", "sans danger", "legit")) assertFalse(l, l.lowercase().contains(w))
    }

    @Test fun aFrenchTagWithANoBreakSpaceStillWarns() {
        for (sp in listOf("\u00A0", "\u202F")) {
            assertEquals(ScamWhy.SHORT_LINK, ScamCheck.of("Postes Canada${sp}: ton colis n'a pas pu être livré. Frais : https://bit.ly/3abc")?.why)
            assertEquals(ScamWhy.WRONG_SENDER, ScamCheck.of("Virement Interac${sp}: tu as reçu 250,00 \$. Dépose-le à interac-depot.top")?.why)
            assertEquals(ScamWhy.CALL_BACK, ScamCheck.of("ARC : un mandat d'arrestation est émis. Appelle le 1${sp}888${sp}555${sp}0123")?.why)
            assertNull(ScamCheck.of("Mon${sp}comptable${sp}re${sp}ARC: la date du tribunal a changé. Appelle-la au 902-555-0141"))
        }
    }

    @Test fun anETransferAddressNamesInterac() {
        for (host in listOf("etransfert-depot.com", "etransfer-deposit.top", "e-transfer-secure.com", "etransfertdepot.com"))
            assertEquals(host, "interac", ScamCheck.of("Tu as reçu 300,00 \$. Accepte-le à $host")?.sender?.id)
        for (host in listOf("etransfer.ca", "e-transfer.com", "transfer.ca", "transfert.fr", "etransfer.mycu.ca"))
            assertNull(host, ScamCheck.of("Details at $host"))
    }
}
