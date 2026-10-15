// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretDetectorTest {
    private fun kinds(s: String) = SecretDetector.find(s)

    @Test fun pinsAndNips() {
        assertTrue(SecretKind.PIN in kinds("My bank PIN is 4512"))
        assertTrue(SecretKind.PIN in kinds("my pin is 0000"))
        assertTrue(SecretKind.PIN in kinds("Mon NIP est 1234"))
        assertTrue(SecretKind.PIN in kinds("mon nip de carte de débit c'est 9876"))
        assertTrue(SecretKind.PIN in kinds("The SIM PIN is in the drawer"))
        assertTrue(SecretKind.PIN in kinds("PIN for the bike lock: 3-1-4"))
    }

    @Test fun passwordsCodesAndKeys() {
        assertTrue(SecretKind.PASSWORD in kinds("The Wi-Fi password is hunter2"))
        assertTrue(SecretKind.PASSWORD in kinds("Mon mot de passe Proton est Tr0ub4dor&3"))
        assertTrue(SecretKind.PASSWORD in kinds("le mdp du wifi c'est maison123"))
        assertTrue(SecretKind.PASSWORD in kinds("The wifi key is on the router"))
        assertTrue(SecretKind.CODE in kinds("The door code at the cottage is 4512#"))
        assertTrue(SecretKind.CODE in kinds("Le code de la porte, c'est 4512"))
        assertTrue(SecretKind.CODE in kinds("Le code d'accès de l'immeuble est 2580"))
        assertTrue(SecretKind.CODE in kinds("The gate code is the year Mom was born"))
        assertTrue(SecretKind.CODE in kinds("The code for the locker is A4512"))
        assertTrue(SecretKind.KEY in kinds("My seed phrase starts with apple banana"))
        assertTrue(SecretKind.KEY in kinds("The API key is sk-ant-api03-AbCdEf1234567890xyzQ"))
        assertTrue(SecretKind.KEY in kinds("backup codes are in the blue folder"))
    }

    @Test fun cardsIdsAndAccounts() {
        assertTrue(SecretKind.CARD in kinds("My Visa is 4111 1111 1111 1111"))
        assertTrue(SecretKind.CARD in kinds("card 4111-1111-1111-1111 exp 08/29"))
        assertTrue(SecretKind.CARD in kinds("5555555555554444"))
        assertTrue(SecretKind.CARD in kinds("the CVV is 123"))
        assertTrue(SecretKind.ID_NUMBER in kinds("My SIN is 046 454 286"))
        assertTrue(SecretKind.ID_NUMBER in kinds("Mon NAS : 046-454-286"))
        assertTrue(SecretKind.ID_NUMBER in kinds("numéro d'assurance sociale dans le classeur"))
        assertTrue(SecretKind.ID_NUMBER in kinds("SSN 123-45-6789"))
        assertTrue(SecretKind.ID_NUMBER in kinds("Passport number AB123456"))
        assertTrue(SecretKind.ID_NUMBER in kinds("Mon numéro de passeport est GA123456"))
        assertTrue(SecretKind.ID_NUMBER in kinds("Ma carte soleil c'est TREM 1234 5678"))
        assertTrue(SecretKind.ACCOUNT in kinds("Account number 0012-3456789"))
        assertTrue(SecretKind.ACCOUNT in kinds("Transit 12345, folio 1234567"))
        assertTrue(SecretKind.ACCOUNT in kinds("IBAN FR76 3000 6000 0112 3456 7890 189"))
    }

    @Test fun longDigitRunsThatArentPhoneNumbers() {
        assertTrue(SecretKind.LONG_NUMBER in kinds("the number on the back is 123456789012"))
        assertTrue(SecretKind.LONG_NUMBER in kinds("reference 9876 5432 1098"))
        // A long run is a secret even without a word around it.
        assertTrue(SecretDetector.isSecret("0123456789"))
    }

    @Test fun ordinaryNumbersAreNotSecrets() {
        for (s in listOf(
            "Room 1203 at the Hilton", "Bus 165 leaves at 7:45", "I parked on level P3", "My sister Julie's birthday is May 3",
            "Mom's number is 514-555-0101", "Mom's cell is (514) 555-0101", "call +1 514 555 0101", "+33 1 23 45 67 89",
            "5145550101", "The meeting is on 2026-10-14 at 14:05", "Léo has hockey Tuesdays and Thursdays",
            "The area code is 514", "Our postal code is H2X 1Y4", "Le code postal du chalet est J0E 1A0", "The dress code is formal",
            "I paid $1,250.00 for the couch", "Flight AC 871 lands at 14:05", "I like to pin photos on the fridge",
            "My order number is 12345", "J'habite au 4512 rue Saint-Denis", "My shoe size is 10.5", "My passport expires in March 2028",
            "The car is a grey 2019 Honda Civic", "Notre anniversaire de mariage est le 14 août", "Population 1.8 million in 2026",
            "I take my blood pressure pill every morning at 8", "",
        )) {
            assertEquals(s, emptySet<SecretKind>(), kinds(s))
        }
    }

    @Test fun luhnAndPhones() {
        assertTrue(SecretDetector.luhn("4111111111111111"))
        assertTrue(SecretDetector.luhn("046454286"))
        assertFalse(SecretDetector.luhn("4111111111111112"))
        assertFalse(SecretDetector.luhn("12a4"))
        assertFalse(SecretDetector.luhn("7"))
        assertTrue(SecretDetector.phoneShaped("514-555-0101"))
        assertTrue(SecretDetector.phoneShaped("1 514 555 0101"))
        assertTrue(SecretDetector.phoneShaped("+44 20 7946 0958"))
        assertFalse(SecretDetector.phoneShaped("123 456 7890"))
        assertFalse(SecretDetector.phoneShaped("123456789012"))
    }

    @Test fun whatRobotSaysInstead() {
        assertEquals("[a code, kept in Memory]", SecretDetector.placeholder(setOf(SecretKind.CODE)))
        assertEquals("[a password, kept in Memory]", SecretDetector.placeholder(setOf(SecretKind.CODE, SecretKind.PASSWORD)))
        assertEquals("[un NIP, gardé en mémoire]", SecretDetector.placeholder(setOf(SecretKind.PIN), fr = true))
        assertEquals("[a code, kept in Memory]", SecretDetector.placeholder(emptySet()))
    }
}
