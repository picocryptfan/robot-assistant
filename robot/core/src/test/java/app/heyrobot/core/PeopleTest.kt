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
        assertEquals(NameMatch.FUZZY, ContactMatcher.kind("Johm", "John"))
        // Under 4 letters a letter off is a guess (review A M5): "Mom" is not Tom, "Dan" is not Don.
        assertNull(ContactMatcher.kind("Jon", "John"))
        assertNull(ContactMatcher.kind("Mom", "Tom Smith"))
        assertNull(ContactMatcher.kind("Dan", "Don Lee"))
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

    @Test fun onlyOneExactPersonWithOneDestinationGoesWithoutACard() {
        // Review A M5: a single fuzzy or prefix match, or several people, ran without a card under "Just do it".
        fun m(id: Long, kind: NameMatch) = ContactMatch(id, "x$id", kind)
        assertTrue(ContactMatcher.sure(listOf(m(1, NameMatch.EXACT)), 1))
        assertFalse(ContactMatcher.sure(listOf(m(1, NameMatch.EXACT)), 2))
        assertFalse(ContactMatcher.sure(listOf(m(1, NameMatch.PREFIX)), 1))
        assertFalse(ContactMatcher.sure(listOf(m(1, NameMatch.WORD)), 1))
        assertFalse(ContactMatcher.sure(listOf(m(1, NameMatch.FUZZY)), 1))
        assertFalse(ContactMatcher.sure(listOf(m(1, NameMatch.EXACT), m(2, NameMatch.EXACT)), 1))
        assertFalse(ContactMatcher.sure(emptyList(), 1))
        // The review's cases.
        val tomMaman = listOf(ContactName(1, "Tom Smith"), ContactName(2, "Maman"))
        assertEquals(emptyList<ContactMatch>(), ContactMatcher.best("Mom", tomMaman))
        val momo = ContactMatcher.best("Mom", listOf(ContactName(1, "Tom Smith"), ContactName(3, "Momo Diallo")))
        assertEquals(listOf(3L), momo.map { it.id })
        assertFalse(ContactMatcher.sure(momo, 1))
        // Five letters may still be one off, but such a match always shows the card.
        val salah = ContactMatcher.best("Sarah", listOf(ContactName(19, "Mo Salah")))
        assertEquals(listOf(NameMatch.FUZZY), salah.map { it.kind })
        assertFalse(ContactMatcher.sure(salah, 1))
        assertFalse(ContactMatcher.sure(ContactMatcher.best("Alexandre", listOf(ContactName(3, "Alexandra Chen"))), 1))
        assertTrue(ContactMatcher.sure(ContactMatcher.best("maman", listOf(ContactName(6, "Jane Doe", listOf("Maman")))), 1))
    }

    @Test fun theCardRuleForTextsAndEmails() {
        // Review A M5 (a, b) and minor 16, through the helper ToolExecutor uses.
        val asked = Action.SendText("Alex", "hi")
        fun opt(name: String, kind: String?, n: String) = Recipients.Option(name, kind, n,
            asked.copy(target = TextTarget(name, "$n@cheogram.com", n, "Cheogram", TextVia.CHEOGRAM)))
        val exact = listOf(ContactMatch(1, "Alex", NameMatch.EXACT))
        val prefix = listOf(ContactMatch(2, "Alexandra Chen", NameMatch.PREFIX))
        // One exact name, one place: ready, and the gate decides (no forced card).
        assertEquals(false, (Recipients.prepare(asked, exact, listOf(opt("Alex", "Mobile", "+1")), 0) { asked.copy(to = it) } as Prepared.Ready).mustAsk)
        // A prefix match, or several people of whom only one has a number, or people left out: the card shows.
        assertEquals(true, (Recipients.prepare(asked, prefix, listOf(opt("Alexandra Chen", "Mobile", "+1")), 0) { asked } as Prepared.Ready).mustAsk)
        val two = exact + ContactMatch(3, "Alex", NameMatch.EXACT)
        assertEquals(true, (Recipients.prepare(asked, two, listOf(opt("Alex", "Mobile", "+1")), 0) { asked } as Prepared.Ready).mustAsk)
        assertEquals(true, (Recipients.prepare(asked, exact, listOf(opt("Alex", "Mobile", "+1")), 4) { asked } as Prepared.Ready).mustAsk)
        // Several places for one person: their kinds and numbers, the waiting card named after them.
        val one = Recipients.prepare(asked, exact, listOf(opt("Alex", "Mobile", "+1 1"), opt("Alex", "Home", "+1 2")), 0) { asked.copy(to = it) } as Prepared.Ready
        assertEquals(listOf("Mobile · +1 1", "Home · +1 2"), one.choices.map { it.label })
        assertEquals("Alex", (one.action as Action.SendText).to)
        assertEquals(0, one.more)
        // Several people, more than fit: at most six choices, and the rest (and the people not looked at) counted.
        val many = (1..9).map { opt("Person $it", "Mobile", "+1 $it") }
        val capped = Recipients.prepare(asked, prefix, many, 2) { asked } as Prepared.Ready
        assertEquals(Recipients.MAX_CHOICES, capped.choices.size)
        assertEquals("Person 1 · Mobile · +1 1", capped.choices[0].label)
        assertEquals(3 + 2, capped.more)
        assertEquals(asked, capped.action)
    }

    @Test fun noContactWithCheogramOffersToChooseInCheogram() {
        // Owner report (0.2.2): people who exist only in Cheogram's roster were "not found".
        val asked = Action.SendText("Bob", "See you at 8")
        for (reason in listOf("No contact matches “Bob”.", "No matching contact has a phone number or XMPP address.")) {
            val pick = Recipients.notFound(asked, reason, TextVia.CHEOGRAM) as Prepared.Ready
            // Always a card (even under "Just do it"), with one button and no choices: the person is picked in Cheogram.
            assertTrue(pick.mustAsk)
            assertTrue(pick.choices.isEmpty())
            val a = pick.action as Action.SendText
            assertEquals(TextTarget(null, "", reason, "Cheogram", TextVia.CHEOGRAM_PICK), a.target)
            assertEquals("Bob", a.to)
            assertEquals("See you at 8", a.body())
            assertEquals("Choose in Cheogram", a.confirmLabel)
            // The card still says why no contact fitted.
            assertEquals("$reason Choose who to text in Cheogram", a.describe())
        }
        // The SMS app has no list of its own: not found, as before.
        assertEquals(Prepared.Refused(ActionStatus.NOT_FOUND, "No contact matches “Bob”."), Recipients.notFound(asked, "No contact matches “Bob”.", TextVia.SMS))
        // A normal Cheogram hand-off keeps its button.
        val mom = asked.copy(target = TextTarget("Mom", "+15145550101@cheogram.com", "+1 514-555-0101", "Cheogram", TextVia.CHEOGRAM))
        assertEquals("Open in Cheogram for Mom", mom.confirmLabel)
    }

    @Test fun mobileNumbersComeFirstForTexts() {
        val r = Recipients.forText(listOf(mom), xmppOk = true)
        assertEquals(listOf("+1 514-555-0101", "514 555 0199"), r.map { it.address })
        assertEquals(listOf("Mobile", "Home"), r.map { it.label })
    }

    @Test fun anXmppAddressComesFirstButNumbersAreStillOffered() {
        // Review A minor 16: a stale Jabber address hid the mobile number.
        assertEquals(listOf(Reach(3, "Alex Martin", "alex@jabber.example.org", "XMPP", xmpp = true), Reach(3, "Alex Martin", "613 555 0123", "Work")),
            Recipients.forText(listOf(alex), xmppOk = true))
        // The SMS app can't text an address: the number only.
        assertEquals(listOf("613 555 0123"), Recipients.forText(listOf(alex), xmppOk = false).map { it.address })
        assertEquals(4, Recipients.forText(listOf(mom, alex), xmppOk = true).size)
        // An address Cheogram would cut short is never offered.
        val odd = ContactCard(9, "Odd", xmpp = listOf("a?b@x.org", "c/d@x.org", "ok@x.org"))
        assertEquals(listOf("ok@x.org"), Recipients.forText(listOf(odd), xmppOk = true).map { it.address })
    }

    @Test fun jidsWithCharactersCheogramCutsAreRefused() {
        // Review A minor 4: "a?b@x.org" opened the chat with "a".
        assertTrue(Addresses.isJid("mom@example.org"))
        assertTrue(Addresses.isJid("first.last+tag@jabber.example.org"))
        for (j in listOf("a?b@x.org", "a#b@x.org", "a/b@x.org", "a:b@x.org", "a&b@x.org", "a'b@x.org", "a%b@x.org", "mom")) assertFalse(j, Addresses.isJid(j))
    }

    @Test fun extensionsAndBidiMarksAreCleanedAndPlusOneNeedsTenDigits() {
        // Review A minor 3.
        assertNull(PhoneNumbers.e164("+1 514 555 0101 22", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("+1 (514) 555-0101 / 0102", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("514-555-0101 x23", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("514-555-0101 ext. 23", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("514-555-0101;23", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("514-555-0101,23", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("\u202A+1 514 555 0101\u202C", "CA", platform))
        assertEquals("+15145550101", PhoneNumbers.e164("+1\u00A0514\u00A0555\u00A00101", "CA", platform))
        assertTrue(Addresses.isPhone("514-555-0101 x23"))
        assertEquals("+16135550199", PhoneNumbers.e164("+1 613 555 0199", "FR") { _, _ -> null })
        assertNull(PhoneNumbers.e164("+1 013 555 0199", "FR") { _, _ -> null })
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
