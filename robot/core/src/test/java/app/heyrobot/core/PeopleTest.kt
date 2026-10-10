package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    // ------------------------------------------------------------------ E.164 and the gateway address

    /** A stand-in for PhoneNumberUtils.formatNumberToE164: knows two numbers, refuses the rest (as it does invalid ones). */
    private val platform: (String, String) -> String? = { n, region ->
        when (PhoneNumbers.digits(n)) {
            "5145550101" -> if (region == "CA" || region == "US") "+15145550101" else null
            "15145550101" -> "+15145550101"
            "33612345678" -> "+33612345678"
            "0612345678" -> if (region == "FR") "+33612345678" else null
            else -> null
        }
    }

    @Test fun numbersBecomeE164WithTheRegion() {
        assertEquals("+15145550101", PhoneNumbers.e164("(514) 555-0101", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("514.555.0101", "ca", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("+1 514-555-0101", "FR", platform))
        assertEquals("+33612345678", PhoneNumbers.e164("06 12 34 56 78", "FR", platform))
        // "00" is the international prefix.
        assertEquals("+33612345678", PhoneNumbers.e164("0033 6 12 34 56 78", "CA", platform))
        // An unknown or bad region is the default, CA.
        assertEquals("+15145550101", PhoneNumbers.e164("514 555 0101", "Canada", platform))
    }

    @Test fun withoutThePlatformOnlySafeReadingsRemain() {
        // PhoneNumberUtils said no: a number with its country code, or 10 NANP digits, is still read; nothing else is guessed.
        assertEquals("+442079460000", PhoneNumbers.e164("+44 20 7946 0000", "CA", platform))
        assertEquals("+16135550123", PhoneNumbers.e164("613-555-0123", "CA", platform))
        assertEquals("+16135550123", PhoneNumbers.e164("1 613 555 0123", "US", platform))
        assertNull(PhoneNumbers.e164("613-555-0123", "FR", platform))
        assertNull(PhoneNumbers.e164("0123 456", "CA", platform))
        assertNull(PhoneNumbers.e164("Mom", "CA", platform))
        assertNull(PhoneNumbers.e164("555-0101 ext 4", "CA", platform))
        // A platform answer that isn't E.164 is not trusted.
        assertEquals("+16135550123", PhoneNumbers.e164("613 555 0123", "CA") { _, _ -> "613-555-0123" })
    }

    @Test fun jidsGoThroughTheGateway() {
        assertEquals("+15145550101@cheogram.com", PhoneNumbers.jid("+15145550101", "cheogram.com"))
        assertEquals("+15145550101@sms.example.org", PhoneNumbers.jid("+15145550101", " SMS.Example.org "))
        // A setting that isn't a domain falls back to JMP's gateway.
        assertEquals("+15145550101@cheogram.com", PhoneNumbers.jid("+15145550101", "not a domain"))
        assertNull(PhoneNumbers.jid("5145550101", "cheogram.com"))
        assertNull(PhoneNumbers.jid("+0123", "cheogram.com"))
        assertEquals("cheogram.com", PhoneNumbers.gateway("https://cheogram.com"))
        assertEquals("FR", PhoneNumbers.region("fr"))
        assertEquals("CA", PhoneNumbers.region(null))
    }

    @Test fun addressesAndNumbersAreToldApartFromNames() {
        assertTrue(Addresses.isAddress("mom@example.org"))
        assertTrue(Addresses.isAddress("first.last+tag@mail.example.co.uk"))
        assertTrue(Addresses.isAddress("user@jabber.ccc.de"))
        assertFalse(Addresses.isAddress("mom"))
        assertFalse(Addresses.isAddress("mom@home"))
        assertFalse(Addresses.isAddress("two words@example.org"))
        assertTrue(Addresses.isPhone("+1 (514) 555-0101"))
        assertTrue(Addresses.isPhone("514.555.0101"))
        assertFalse(Addresses.isPhone("Mom"))
        assertFalse(Addresses.isPhone("Mom 2"))
        assertFalse(Addresses.isPhone("12"))
    }

    // ------------------------------------------------------------------ contact ranking

    private val contacts = listOf(
        ContactName(1, "Mom", listOf("Maman")),
        ContactName(2, "Momo Takeda"),
        ContactName(3, "Alex Martin"),
        ContactName(4, "Alexandra Roy", listOf("Sasha")),
        ContactName(5, "Hélène Côté"),
        ContactName(6, "John Smith"),
        ContactName(7, "Dr. Alex Chen"),
    )

    private fun best(q: String) = ContactMatcher.best(q, contacts).map { it.id }

    @Test fun exactBeatsPrefixBeatsWordBeatsFuzzy() {
        assertEquals(NameMatch.EXACT, ContactMatcher.kind("mom", "Mom"))
        assertEquals(NameMatch.PREFIX, ContactMatcher.kind("Mom", "Momo Takeda"))
        assertEquals(NameMatch.WORD, ContactMatcher.kind("Roy", "Alexandra Roy"))
        assertEquals(NameMatch.WORD, ContactMatcher.kind("Alex", "Dr. Alex Chen"))
        assertEquals(NameMatch.FUZZY, ContactMatcher.kind("Jon", "John"))
        assertNull(ContactMatcher.kind("Bob", "Alex Martin"))
        // The best kind only: "Mom" is Mom, not Momo.
        assertEquals(listOf(1L), best("Mom"))
        assertEquals(listOf(1L), best("my mom"))
        val ranked = ContactMatcher.rank("Mom", contacts)
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
        assertEquals(listOf(NameMatch.EXACT, NameMatch.PREFIX), ranked.map { it.kind })
    }

    @Test fun caseAccentsAndNicknamesDontMatter() {
        assertEquals(listOf(5L), best("helene cote"))
        assertEquals(listOf(5L), best("HÉLÈNE"))
        assertEquals(listOf(1L), best("maman"))
        assertEquals(listOf(4L), best("Sasha"))
    }

    @Test fun severalPeopleWithTheNameAreAllOffered() {
        // Two prefix matches for "Alex": the card asks which (shorter names first); Dr. Alex Chen is only a word match.
        assertEquals(listOf(3L, 4L), best("Alex"))
        assertEquals(listOf(7L), best("Alex Chen"))
        assertEquals(listOf(3L), best("alex martin"))
    }

    @Test fun fuzzyOnlyWhenNothingBetterAndNeverForTinyNames() {
        assertEquals(listOf(6L), best("Jon Smith"))
        assertEquals(listOf(6L), best("Smyth"))
        assertEquals(NameMatch.FUZZY, ContactMatcher.kind("Smyth", "John Smith"))
        // Two letters: a prefix still counts (the card offers both), but never a fuzzy guess.
        assertEquals(listOf(3L, 4L), best("Al"))
        assertNull(ContactMatcher.kind("Jo", "Bo"))
        assertEquals(emptyList<Long>(), best("Zed"))
        assertEquals(emptyList<Long>(), best(""))
    }

    // ------------------------------------------------------------------ where a text or an email goes

    private val mom = ContactCard(1, "Mom",
        phones = listOf(
            ContactPhone("514 555 0199", PhoneKind.HOME, "Home"),
            ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile"),
            ContactPhone("(514) 555-0101", PhoneKind.MOBILE, "Mobile"),
        ),
        emails = listOf("mom@example.org", "MOM@example.org", "not an address"))
    private val alex = ContactCard(3, "Alex Martin", phones = listOf(ContactPhone("613 555 0123", PhoneKind.WORK, "Work")), xmpp = listOf("alex@jabber.example.org"))

    @Test fun mobileNumbersComeFirstForTexts() {
        val r = Recipients.forText(listOf(mom), xmppOk = true)
        assertEquals(listOf("+1 514-555-0101", "514 555 0199"), r.map { it.address })
        assertEquals(listOf("Mobile", "Home"), r.map { it.label })
    }

    @Test fun anXmppAddressIsTextedDirectlyByCheogram() {
        assertEquals(listOf(Reach(3, "Alex Martin", "alex@jabber.example.org", "XMPP", xmpp = true)), Recipients.forText(listOf(alex), xmppOk = true))
        // The SMS app can't: the number then.
        assertEquals(listOf("613 555 0123"), Recipients.forText(listOf(alex), xmppOk = false).map { it.address })
        assertEquals(3, Recipients.forText(listOf(mom, alex), xmppOk = true).size)
    }

    @Test fun emailsAreValidAndOnce() {
        assertEquals(listOf("mom@example.org"), Recipients.forEmail(listOf(mom)).map { it.address })
        assertEquals(emptyList<Reach>(), Recipients.forEmail(listOf(alex)))
    }

    @Test fun lookupTellsTheModelAtMostThreePeople() {
        val many = (1..5).map { ContactCard(it.toLong(), "Person $it", phones = (1..5).map { n -> ContactPhone("514 555 010$n", PhoneKind.OTHER, "Other") },
            emails = listOf("a$it@example.org", "b$it@example.org", "c$it@example.org")) }
        val r = Recipients.lookup(many)
        assertEquals(3, r.size)
        assertEquals(3, (r[0]["numbers"] as List<*>).size)
        assertEquals(2, (r[0]["emails"] as List<*>).size)
        val m = Recipients.lookup(listOf(mom, alex)) { "+$it" }
        assertEquals(listOf("mobile: ++1 514-555-0101", "home: +514 555 0199"), m[0]["numbers"])
        assertEquals("alex@jabber.example.org", m[1]["xmpp"])
        assertFalse(m[0].containsKey("xmpp"))
    }
}
