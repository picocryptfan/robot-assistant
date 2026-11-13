// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scam texts (idea 17): the review's hard cases (review-scam-texts, 10 Nov 2026), made-up Canadian-style texts picked to
 * probe the edges. Every ordinary text gets no line; every scam gets one. Prints each text's result.
 */
class ScamCorpusTest {
    private val corpus = listOf(
        // branch's own tests (validate emulation)
                "ARC : un remboursement de 380 \$ t'attend. Réclame-le sur arc-remboursement.com" to true,
        "Banque Nationale : confirme ton identité sur bnc-verif.com" to true,
        "Suivi : purolatr.com/colis" to true,
        "Your bill: bell-billing.com" to true,
        "Interac e-Transfer from Jean: deposit at www.rbc.com/deposit" to false,
        "Meet at 6 a.m., e.g. at the café. It was \$1.99." to false,
        "Email me at sam@cp-redelivery.top" to false,
        "The interactive map: interactive-maps.com" to false,
        "We booked bell-hotel.com" to false,
        "Fred Rogers quote: example.org/quote" to false,
        // real senders, own links and codes
        "Canada Post: Duty and taxes of \$14.23 are owing on your parcel. Pay at canadapost-postescanada.ca/pay" to false,
        "Your RBC verification code is 482913. Do not share it. RBC will never call you to ask for it." to false,
        "482913 is your Interac verification code. @etransfer.interac.ca #482913" to false,
        "Desjardins : Un virement Interac de 50,00 \$ a été déposé. Détails : desjardins.com" to false,
        "Rogers Bank: your statement is ready. View it at rogersbank.com" to false,
        "Bell MTS: your bill of \$82.10 is ready at bellmts.ca" to false,
        "Tangerine: your Interac e-Transfer of \$50.00 to Sam was deposited. Details at tangerine.ca" to false,
        "UPS: Duties and taxes of \$12.34 are due on your package from Best Buy. Pay at https://www.ups.com/ca/en/pay" to false,
        "Your order of \$45.99 has shipped! Track your package: https://www.amazon.ca/gp/your-orders" to false,
        "Telus: your bill is ready. Pay \$95.00 by Nov 20 at telus.com/mybill" to false,
        // friends
        "Sent you \$20 by Interac for dinner. Here's the menu: https://www.skipthedishes.com/the-burger-joint" to false,
        "Canada Post lost my package again lol https://www.reddit.com/r/canada/comments/abc" to false,
        "Switched from TD to EQ Bank, way better rates: eqbank.ca" to false,
        "What a TD! Replay here: tsn.ca/video/123" to false,
        "Finally filed with CRA, used wealthsimple.com/tax" to false,
        "J'ai payé les frais de douane pour ton colis, voici le suivi : https://www.ups.com/track?tracknum=1Z" to false,
        "Call me right away.my phone is dying" to false,
        "That was hilarious.lol" to false,
        "ok.so I'll pay you back for the parcel tomorrow" to false,
        "Mon colis est arrivé.Merci pour le cadeau de 20 \$!" to false,
        "Envoie-moi ça par Interac stp, « dimanche » chez Marie : https://www.google.com/maps/abc" to false,
        // scams
        "Canada Post: Your package is held due to an incomplete address. Update at https://canadapost-redelivery.com" to true,
        "CRA: You are eligible for a tax refund of \$420.50. Accept at https://cra-refund.ca" to true,
        "INTERAC: You have received \$300.00 from Sam. Accept: https://interac-etransfer.xyz/d/7" to true,
        "407 ETR: You have an unpaid toll balance of \$6.99. Pay within 24 hours to avoid a late fee: https://407etr-pay.com" to true,
        "407 ETR: Your account has an outstanding toll balance of \$4.35. Settle it now at 407etr-billing.com" to true,
        "CRA: A warrant has been issued for unpaid taxes. Call 1-888-555-0199 immediately." to true,
        "Canada Post: parcel held, pay \$1.99 at canadapost-fee.club" to true,
        "Interac: you received \$250. Deposit at interac-etransfer.fun" to true,
        "Interac: You received \$500.00. Accept at etransferdeposit.ca before it expires" to true,
        "Virement Interac : Vous avez reçu 420,00 \$. Acceptez le dépôt ici : etransfert-depot.com" to true,
        "Postes Canada : Votre colis n'a pas pu être livré. Frais de 1,99 \$ : https://bit.ly/3abc" to true,
        "Netflix: your payment failed. Update your card at netflix-billing.com" to true,
        "Your account will be closed. Verify at https://rbcroyaIbank.com" to true,        // review-scam-texts-b: its 20 new edge texts and four it quoted
        "Your Uber Eats order is on its way! Track it here: ubereats.com/orders" to false,
        "Rappel : ton rendez-vous chez le dentiste est demain à 14 h. Pour annuler, appelle le 418-555-0142." to false,
        "Desjardins : Un virement Interac de 125,00 \$ a été déposé dans ton compte. desjardins.com" to false,
        "Come right away, we're at the Tim Hortons on Main: maps.app.goo.gl/Xy12Ab" to false,
        "Amazon.ca: Your refund of \$34.99 has been issued. You'll receive it in 3-5 business days. amzn.to/3kXyZ9" to false,
        "Salut! J'ai reçu ton colis.ca coûte 12 \$ de frais de douane, tu me rembourses?" to false,
        "Rappel de l'école : la sortie au musée est demain. Paiement de 15 \$ sur schoolcashonline.com d'ici 24 h." to false,
        "Your Instacart order will arrive within 2 hours. Track it at instacart.ca" to false,
        "RBC: Did you attempt a purchase of \$412.50 at an electronics store? Reply YES or NO." to false,
        "Bell : ta facture de 89,50 \$ est prête. Consulte-la sur bell.ca/mabell" to false,
        "CRA: Your tax refund of \$489.20 is ready. Claim it at cra-refund.us" to true,
        "Canada Post: your parcel is on hold, a \$1.99 redelivery fee is due: canadapost-fee.lol" to true,
        "TD: Unusual sign-in detected. Verify your identity at td-verify.me" to true,
        "Costco: Congratulations! You've been selected for a \$500 gift card. Claim your reward: costco-rewards.shop" to true,
        "Péage 407 ETR : solde impayé de 7,85 \$. Évite des frais supplémentaires : 407etr-paiement.com" to true,
        "Your Rogers account has been suspended due to a billing error. Restore service: rogers-secure-login.com" to true,
        "ARC : Ton remboursement d'impôt de 612 \$ est en attente. Réclame-le ici : https://arc-remboursement.gc-ca.info" to true,
        "lol the CRA called me about a warrant, number was 1-888-555-0199, total scam right?" to false,
        "Our hockey pool payment is due Friday, \$20 each, details on pool-payment-portal.ca" to false,
        // review-scam-texts-c: its probes
        "Hi, this is the dentist's office, your appointment is tomorrow at 2:30. Confirm at https://bayviewdental.janeapp.com/c/abc" to false,
        "It's the Canada Post strike again, so your card will be late. https://www.cbc.ca/news/canada-post-strike" to false,
        "It's Telus again, the whole east coast is down: downdetector.ca/status/telus" to false,
        "C'est Desjardins qui m'a appelé finalement, regarde : https://www.lapresse.ca/affaires/desjardins" to false,
        "C'est la Banque Nationale qui a le meilleur taux, regarde : https://www.ratehub.ca/taux" to false,
        "It's TD Bank's new fee, read this: https://www.reddit.com/r/PersonalFinanceCanada/comments/xyz" to false,
        "It's Interac or cash for the bake sale, details: https://schoolcashonline.com" to false,
        "This is Scotiabank's new branch on Quinpool, opening Monday: https://www.halifaxexaminer.ca/story" to false,
        "This is the CRA thing I told you about: https://www.cbc.ca/news/cra-scam" to false,
        "Hey it's Koodo, your plan renews on the 15th. Manage it at koodomobile.com" to false,
        "Hi Sam, a reminder of your appointment at Bayview Physio Thu at 2:30pm. Please confirm within 24 hours: https://bayviewphysio.janeapp.com/confirm/abc" to false,
        "Report cards are posted. Log in within 7 days to see them: https://hrce.edsby.com" to false,
        "lol look at this https://t.co/AbC123" to false,
        "Your prescription is ready for pickup at Lawtons Drugs. Details: https://bit.ly/lawtons-rx" to false,
        "Our new menu is up: thepizzaplace.fun" to false,
        "Come right away.my mom is here" to false,
        "Great party.fun times with you guys" to false,
        "Done for the day.work was nuts" to false,
        "Thanks for dinner.date night again soon?" to false,
        "Your new library card is ready, pick it up within 7 days. Hours: https://www.halifaxpubliclibraries.ca" to false,
        "Gift cards are 20% off today only at Kent! https://www.kent.ca/deals" to false,
        "Netflix: your payment failed. Update it at netflix-billing-help.net" to true,
        "Your Netflix subscription is on hold. Update your payment: netflix-billing-help.net" to true,
        "Your Netflix account has a billing problem. Fix it at netflix-billing-help.net" to true,
        "Congrats on the new job! Dinner at Bluenose II rewards night? bluenose-rewards.ca" to false,
        // review-scam-texts-c: its 20 fresh texts, written before it read the code
        "Hi, this is the dentist's office at Bayview Dental. Your appointment is tomorrow at 2:30 pm. Reply C to confirm or call 902-555-0142 to reschedule." to false,
        "Your package from Amazon was delivered. Details: https://amzn.to/3xYz12" to false,
        "RBC: Your Visa ending 4412 was charged \$54.20 at SOBEYS #612 on Nov 9. If this wasn't you, call the number on the back of your card." to false,
        "Hey it's Mike from work, my phone died so texting from my wife's. Can you cover my shift Saturday? Let me know asap" to false,
        "Reminder from HRCE: school is closed today due to the storm. Details at https://hrce.ca/closures" to false,
        "Your Google verification code is 482913. Don't share it with anyone." to false,
        "Running late, be there in 15. Grab us a table? Here's the place: https://maps.app.goo.gl/AbC123" to false,
        "This is Sarah, the new receptionist at Dr. Patel's. We need to update your health card number before your visit Thursday, please call us back at 902-555-0177." to false,
        "Your Rogers bill of \$87.45 is ready. View it in the MyRogers app. Payment is due Nov 24." to false,
        "Hi! Your order #4417 from Pizza Delight is out for delivery and will arrive in about 20 min. Track: https://bit.ly/pd4417" to false,
        "Canada Post: Your package is on hold due to an incomplete address. Update within 24h: https://canadapost-delivery.top/ca" to true,
        "CRA: You are eligible for a tax refund of \$468.50. Claim it now at https://cra-refund-gc.com/claim" to true,
        "TD Alert: Unusual sign-in detected on your account. Your access has been suspended. Verify your identity at td-secure-verify.com" to true,
        "407 ETR: You have an unpaid toll balance of \$6.99. Pay by Nov 12 to avoid a \$50 late fee: https://407etr-pay.info" to true,
        "Your Netflix subscription has been paused due to a payment issue. Update your billing details: netflix-billing-help.net" to true,
        // review-scam-texts-c: its 30 fresh texts (the 4 no rule can tell are in outside)
        "omg this was our first dance song 😭 https://spotify.link/Qm3vR8xT2Kb" to false,
        "TELUS: Your order has shipped! Track your new phone: https://www.purolator.com/en/shipping/tracker?pin=335912048771" to false,
        "Canada Post: Your parcel 7023210144825591 is out for delivery today. Track: https://www.canadapost-postescanada.ca/track-reperage/en#/details/7023210144825591" to false,
        "RBC Royal Bank: Did you attempt a \$1,249.99 purchase at BEST BUY #912 on Nov 9? Reply YES or NO. Msg&data rates may apply." to false,
        "Rogers: Welcome to the U.S.! Roam Like Home is \$16/day, charged only when you use your phone. Details: rogers.com/roaming" to false,
        "Desjardins : Ton code de sécurité est 482913. Ne le partage avec personne, même pas avec nous." to false,
        "Salut! Pour le gâteau de fête de Mia, la boulangerie prend les commandes ici : belle-et-bonne.ca/commandes" to false,
        "Here's the passport photo place at Costco, it's \$14.99: https://www.costco.ca/photo-centre" to false,
        "INTERAC e-Transfer: KAREN MACLEOD sent you \$45.00 (CAD). To deposit the money, go to: https://etransfer.interac.ca/RFnD8xk2Lq" to false,
        "this is the air fryer we got, it's on sale till Sunday https://a.co/d/9fJk2Lp" to false,
        "Koodo: You've used 90% of your 20 GB. Add data in the app or at koodomobile.com/add-ons" to false,
        "Bell: Crave is now included with your Fibe TV plan. Activate it at crave.ca/bell" to false,
        "Vidéotron : Ta facture de novembre (92,40 \$) est prête. Consulte-la dans ton Espace client sur videotron.com/client" to false,
        "Coucou! Le spectacle de danse de Léa est jeudi à 19 h. Les billets sont ici : https://lepointdevente.com/billets/lea2026" to false,
        "Rogers Place tix for Saturday 🏒 accept the transfer here before Friday: https://am.ticketmaster.com/oilers/t/AbC123" to false,
        "Canada Post: Your parcel is held at our depot because of an unpaid customs fee of \$3.49. Pay within 24 hours: https://canadapost.ca-redelivery.top/fee" to true,
        "Canada Revenue Agency (CRA): FINAL NOTICE. A warrant has been issued for your arrest over unpaid taxes of \$4,812. Call 1-844-555-0187 today." to true,
        "ARC : Tu as droit à un remboursement de 486,25 \$. Réclame-le avant ce soir : https://arc-remboursement.ca/depot" to true,
        "RCMP: A warrant has been issued under your SIN for money laundering. Call Constable Roy at 613-555-0142 within 2 hours or you will be arrested." to true,
        "Service Canada : Ton NAS a été suspendu à la suite d'une activité suspecte. Appelle le 1-866-555-0119 aujourd'hui pour éviter des poursuites judiciaires." to true,
        "TD Fraud Alert: unusual sign-in from a new device. If this wasn't you, secure your account now: https://td-secureverify.com/login" to true,
        "TD Bank - Your online access has been put on hold. Restore it today: td-restore.com" to true,
        "You've received an Interac e-Transfer of \$350.00 from ANDREW P. Accept your deposit: interac-depot.ca/accept" to true,
        "Desjardins : Une connexion inhabituelle a été détectée sur ton compte AccèsD. Confirme ton identité : https://desjardins-securite.info/accesd" to true,
        "407 ETR: Your account has an unpaid toll balance of \$7.85. Pay today to avoid a \$35.00 late fee: https://407etr.ca-tolls.icu/pay" to true,
        "Ton identifiant Apple a été verrouillé pour des raisons de sécurité. Déverrouille-le ici : https://appleid-verrouillage.com" to true,
        // review-scam-texts-d: 30 fresh texts and 12 probes (lookalikes run together, lock words, a CRA mention)
        "RBC: Your verification code is 482913. Don't share it with anyone. RBC will never ask you for this code." to false,
        "TD Alert: A purchase of \$54.21 at COSTCO WHOLESALE was made on your credit card ending in 1234. Reply STOP to opt out." to false,
        "Desjardins : Votre code de sécurité est 593021. Ne le communiquez à personne, même pas à un employé de Desjardins." to false,
        "Rogers: Your bill of \$87.50 is ready. It's due Nov 28. View it in the MyRogers app or at rogers.com/mybill" to false,
        "Bell: You've used 90% of your data for this billing cycle. Add more at bell.ca/addons or reply DATA." to false,
        "Postes Canada : Votre colis 7012345678901234 sera livré aujourd'hui. Suivez-le sur postescanada.ca/suivi" to false,
        "Purolator: Your package 335123456789 is out for delivery today. Track it at https://www.purolator.com/en/shipping/tracker" to false,
        "Amazon.ca: Your package with 2 items will arrive today by 9 PM. Track: https://www.amazon.ca/progress-tracker/package" to false,
        "Bonjour aux parents! Rappel : journée pédagogique vendredi le 14, pas de classe. Le service de garde est ouvert. École St-Jean" to false,
        "Shoppers Drug Mart: Your prescription is ready for pickup at 123 Main St. Questions? Call your pharmacy. Reply HELP for help." to false,
        "G-583920 is your Google verification code." to false,
        "lol you have to see this https://www.youtube.com/watch?v=dQw4w9WgXcQ" to false,
        "Salut! Voici la recette dont je te parlais https://www.ricardocuisine.com/recettes/8312-pate-chinois" to false,
        "INTERAC e-Transfer: JOHN SMITH sent you \$40.00 (CAD) and the money has been automatically deposited into your bank account." to false,
        "Hydro-Québec : Votre facture de 142,35 \$ est prête. Date d'échéance : 20 novembre. Consultez-la dans votre Espace client." to false,
        "Canada Post: Your parcel is on hold due to an incomplete address. Update within 24 hours: https://canadapost-redelivery.info/ca" to true,
        "Postes Canada : Votre colis ne peut pas être livré. Des frais de 2,99 \$ sont requis. Payez ici : https://postescanada-livraison.top" to true,
        "INTERAC: You have received \$350.00. Click to accept your deposit: https://interac-deposit.net/accept" to true,
        "CRA: You are eligible for a tax refund of \$468.20. Claim it before it expires: https://cra-refund-canada.com" to true,
        "ARC : Votre remboursement d'impôt de 512,00 \$ est en attente. Confirmez vos informations : arc-remboursement.ca" to true,
        "RBC Alert: Unusual sign-in detected on your account. If this wasn't you, verify now: rbc-secure-login.com" to true,
        "Desjardins : Votre accès a été suspendu. Réactivez-le immédiatement : desjardins-acces.co" to true,
        "407 ETR: You have an unpaid toll balance of \$6.85. Pay now to avoid a late fee of \$50: https://407etr-pay.xyz" to true,
        "Netflix: Your payment was declined. Update your billing details to keep watching: netflix-billing.xyz" to true,
        "Bell: Your account has a credit of \$72.40 from an overbilling. Claim your refund within 48h: bell-refund.info" to true,
        "Canada Post: Your parcel is held. Pay the \$1.99 fee at canadapostredelivery.com" to true,
        "Interac: You received \$500.00. Accept your deposit at interacdeposit.com" to true,
        "Tangerine: Your account is locked. Unlock it at tangerine-unlock.com" to true,
        "Apple: Your Apple ID was locked. Unlock it at apple-verrouillage.com" to true,
        "Netflix: Your payment was declined. Update it at netflixbilling.com" to true,
        "Rogers: Your service will be suspended. Restore it at rogers-restore.com" to true,
        "My accountant re CRA: the court date moved to Dec 3. Call her at 902-555-0141." to false,
        "RBC: Your card is locked. Verify at rbcverify.com" to true,
        "Tickets for Saturday at the Rogers Centre: rogerscentre.com/events" to false,
        "Postes Canada : Votre colis est en attente. Payez ici : postescanadalivraison.com" to true,
        "CRA: Your refund of \$468.20 is ready at crarefund.ca" to true,
        "Desjardins : Accès bloqué. Déverrouillez votre compte : desjardins-deverrouillage.com" to true,
    )

    /**
     * Scams no rule here can tell (the main session's ruling, 10 Nov 2026: three rules, nothing else warns): no sender
     * tag, no borrowed name in the address, or a threat that isn't arrest or court. The user guide says no warning
     * doesn't mean safe.
     */
    private val outside = listOf(
        // review-scam-texts-c: no tag and no name in the address, or a brand not on the list (Hydro-Québec, PC Optimum)
        "Ton colis Postes Canada est retenu à l'entrepôt. Mets à jour ton adresse ici : https://colis-retenu.top/ca",
        "Hi Mom, it's me. I dropped my phone in the lake 😩 this is my new number. Can you send \$800 by e-transfer for rent? I'll pay you back Friday",
        "Hydro-Québec : Ton service sera interrompu ce soir à 20 h pour un solde impayé de 187,30 \$. Paie maintenant : https://hydroquebec-paiement.com",
        "PC Optimum: Your 48,000 points (\$48) expire tonight. Redeem them before midnight: https://pcoptimum-redeem.com",
        // fix-scam-texts-d: a sender tag with an address naming no one (rule 2 warns only on a lookalike or a short link)
        "[Canada Post] Your package could not be delivered due to an incomplete address. Update it here: https://cpost-ca.info/u",
        "Interac : Tu as reçu un virement de 250,00 \$. Pour le déposer : https://depot-virement.ca/r",
        "RBC-Alert: your online banking is on hold. Confirm at secure-access-portal.com",
        "Canada Post: parcel held, pay \$1.99 at cp-redelivery.top",
        "INTERAC e-Transfer: You have received \$250.00. Click to accept deposit: http://etransfer-accept.xyz/d?id=8812",
        "Purolator: we could not deliver. Book at puro-ship.com",
        "Postes Canada : ton colis est retenu. Payez 2,99 \$ sur postes-livraison.top",
        "[Canada Post] We could not deliver your item. Reschedule at cpost-reschedule.info",
        "RBC-Alert: unusual activity. Review at secure-access-portal.com",
        // fix-scam-texts-d: a brand's name alone with an ending is no lookalike (spotify.link, telus.net)
        "netflix.xyz/login",
        "Dernier avis : ton compte sera bloqué d'ici 24 heures. Connecte-toi sur verif-compte.com",
        "New pics are up at mybandsite.xyz",
        "Service Canada: your SIN has been suspended. Call 1-855-555-0123 now.",
        "Your package could not be delivered. Pay the \$2.99 redelivery fee: cp-ship.win/pay",
        "This is the Canada Revenue Agency. A warrant has been issued in your name for unpaid taxes. Call 1-888-555-0187 immediately.",
        "Hi, this is TD Fraud Dept. Your card was used for \$899. If not you, visit secure-verify-acct.com",
        "Your parcel is held. Pay the \$2.99 fee at parcelhold.ca today",
        "Hi Mom, I dropped my phone in the toilet, this is my new number. Can you text me on WhatsApp? I need help paying a bill urgently.",
        "Congratulations! You've won a \$1000 Walmart gift card. Claim here: bit.ly/wm-win1000 Reply STOP to opt out",
        "This is the RCMP. A warrant has been issued in your name for unpaid taxes. Call 1-888-555-0199 immediately to avoid arrest.",
        "Hello, I'm a recruiter from Amazon. Earn \$300-\$500/day working from home part time. Contact me on WhatsApp +1 555 0123",
        // review-scam-texts-d: job offers, Hi Mom, an Amazon call-back, an address naming no one
        "Hi, I'm Sarah from a recruiting agency. We have a remote part-time job paying \$300-\$500/day. Interested? Reply YES or message me on WhatsApp.",
        "Bonjour! Emploi à domicile, 2 h par jour, 400 \$ par jour. Écrivez-nous sur WhatsApp : https://wa.me/15815550132",
        "Hi Mom, I dropped my phone in the toilet, this is my new number. Can you send me an e-transfer of \$800? I'll pay you back tomorrow.",
        "Amazon: A charge of \$849.99 for an iPhone 16 Pro was placed on your account. If this wasn't you, call 1-855-555-0142 right away.",
    )

    @Test fun scamsOutsideTheThreeRulesGetNoLine() {
        for (text in outside) assertNull(text, ScamCheck.of(text))
    }

    @Test fun everyOrdinaryTextPassesAndEveryScamIsWarned() {
        val wrong = corpus.mapNotNull { (text, scam) ->
            val hit = ScamCheck.of(text)
            println("${if ((hit != null) == scam) "ok  " else "MISS"} ${if (scam) "scam" else "ok  "} ${hit?.why ?: "-"} | $text") // l10n: not text (test output)
            text.takeIf { (hit != null) != scam }
        }
        assertEquals("texts judged wrong", emptyList<String>(), wrong) // l10n: not text (test message)
    }
}
