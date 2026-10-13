package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/** Robot's People: the file, the changes the app makes to it, and how People join the phone's contacts for a name. */
class PeopleStoreTest {
    private fun tmp(): File = Files.createTempDirectory("people").toFile().apply { deleteOnExit() }
    private var n = 0
    private val ids: () -> String = { "id${++n}" }

    private val mom = Person("p1", "Mom Martin", aliases = listOf("Mom"), xmpp = listOf(XmppAddress("+15145550101@cheogram.com", "me@jmp.chat")),
        source = PersonSource.CHEOGRAM_PICK, updatedAt = 1)
    private val bob = Person("p2", "Bob Tremblay", phones = listOf(ContactPhone("+1 514 555 0199", PhoneKind.MOBILE, "Mobile")),
        emails = listOf("bob@example.org", "bob@work.example.com"), source = PersonSource.VCARD, updatedAt = 2)

    // ------------------------------------------------------------------ the file

    @Test fun onlyAnAddressPickedInCheogramOpensInItsAccount() {
        // Review 0.4.0 #4: Cheogram opens a chat in a named account only for a contact of that account (its list);
        // anyone else goes by the plain link.
        val card = ContactCard("r:x", "Zoé", xmpp = listOf(XmppAddress("zoe@example.org", "me@jmp.chat", picked = true), XmppAddress("z2@example.org", "me@jmp.chat")))
        assertEquals(listOf("me@jmp.chat", null), Recipients.forText(listOf(card), xmppOk = true).map { it.account })
        // A pick refreshes the account and marks it picked; accounts are kept in lower case, as Cheogram compares them.
        val old = listOf(Person("z", "Zoé", xmpp = listOf(XmppAddress("zoe@example.org", "old@example.org"))))
        val (_, p) = PeopleEdit.rememberPick(old, "zoé", CheogramPick("Zoé", "zoe@example.org", "me@jmp.chat"), 1, ids)
        assertEquals(XmppAddress("zoe@example.org", "me@jmp.chat", picked = true), p.xmpp.single())
        assertEquals("me@jmp.chat", People.clean(Person("y", "Y", xmpp = listOf(XmppAddress("y@example.org", " Me@JMP.chat ", picked = true)))).xmpp.single().account)
        // No account, nothing picked.
        assertEquals(XmppAddress("y@example.org"), People.clean(Person("y", "Y", xmpp = listOf(XmppAddress("y@example.org", " ", picked = true)))).xmpp.single())
        val back = PeopleCodec.fromJson(PeopleCodec.toJson(listOf(p)))
        assertTrue(back.single().xmpp.single().picked)
    }

    @Test fun theFileRoundTripsAndToleratesDamage() {
        val learned = Person("p3", "Zoé", xmpp = listOf(XmppAddress("zoe@example.org", "me@example.org")), source = PersonSource.NOTIFICATION,
            confirmed = false, updatedAt = 3)
        val linked = Person("p4", "Hélène Côté", aliases = listOf("maman"), phones = listOf(ContactPhone("514 555 0123", PhoneKind.HOME, "Home")),
            preferredText = "514 555 0123", preferredEmail = "h@example.org", source = PersonSource.REMEMBERED, systemLookupKey = "0r12-ABC", updatedAt = 4)
        val all = listOf(mom, bob, learned, linked)
        assertEquals(all, PeopleCodec.fromJson(PeopleCodec.toJson(all)))
        assertEquals(emptyList<Person>(), PeopleCodec.fromJson(null))
        assertEquals(emptyList<Person>(), PeopleCodec.fromJson("not json"))
        // A bad entry goes; the rest stays.
        val text = """{"v":1,"people":[{"id":"../x","name":"Bad"},{"id":"ok","name":"Good","phones":[{"number":"5145550101","kind":"WEIRD"}]},7]}"""
        val read = PeopleCodec.fromJson(text)
        assertEquals(listOf("ok"), read.map { it.id })
        assertEquals(ContactPhone("5145550101", PhoneKind.OTHER, "Other"), read[0].phones.single())
        assertTrue(read[0].confirmed)
    }

    @Test fun theStoreChangesUnderItsLockAndWritesOnlyWhatChanged() {
        val dir = tmp()
        val s = PeopleStore(dir)
        assertEquals(emptyList<Person>(), s.list())
        assertTrue(s.save(mom))
        assertTrue(s.save(bob))
        assertEquals(listOf(mom, bob), s.list())
        assertEquals(bob, s.get("p2"))
        val f = File(dir, "people.json")
        val stamp = f.readText()
        // Returning the same list writes nothing.
        s.update { it }
        assertEquals(stamp, f.readText())
        assertTrue(s.delete("p1"))
        assertEquals(listOf(bob), s.list())
        assertTrue(s.deleteAll())
        assertEquals(emptyList<Person>(), s.list())
        // Two writers at once (the app and the assistant): no change is lost.
        val threads = (1..8).map { t -> thread { repeat(10) { i -> s.update { l -> l + Person("t$t-$i", "Person $t $i", emails = listOf("p$t$i@example.org")) } } } }
        threads.forEach { it.join() }
        assertEquals(80, s.list().size)
    }

    @Test fun cleaningKeepsEachPlaceOnceAndNamesOnOneLine() {
        val p = People.clean(Person("x", "  Alice\n Martin ", aliases = listOf("alice martin", "Al", "al", " "),
            phones = listOf(ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile"), ContactPhone("(514) 555-0101", PhoneKind.HOME, "Home")),
            emails = listOf("a@x.org", "A@X.org"), xmpp = listOf(XmppAddress("a@x.org"), XmppAddress("A@x.org", "me@x.org"))))
        assertEquals("Alice Martin", p.name)
        // An alias that is the name, or another alias, case and accents aside, goes.
        assertEquals(listOf("Al"), p.aliases)
        assertEquals(1, p.phones.size)
        assertEquals(listOf("a@x.org"), p.emails)
        assertEquals(1, p.xmpp.size)
        assertTrue(People.sameName("Hélène", "helene"))
        assertTrue(People.matches(bob, "tremb"))
        assertTrue(People.matches(bob, "5550199"))
        assertTrue(People.matches(mom, "mom"))
        assertFalse(People.matches(bob, "zoe"))
    }

    // ------------------------------------------------------------------ Cheogram pick

    @Test fun aPickInCheogramIsRememberedUnderTheNameYouSaid() {
        val pick = CheogramPick("Maman Martin", "+15145550101@cheogram.com", "me@jmp.chat")
        val (l1, p1) = PeopleEdit.rememberPick(emptyList(), "mom", pick, 10, ids)
        assertEquals(listOf("mom"), p1.aliases)
        assertEquals("Maman Martin", p1.name)
        assertEquals(listOf(XmppAddress("+15145550101@cheogram.com", "me@jmp.chat", picked = true)), p1.xmpp)
        assertEquals(PersonSource.CHEOGRAM_PICK, p1.source)
        assertTrue(p1.confirmed)
        assertEquals(listOf(p1), l1)
        // The same person picked again for "maman": one more alias, not a second person.
        val (l2, p2) = PeopleEdit.rememberPick(l1, "maman", pick, 11, ids)
        assertEquals(1, l2.size)
        assertEquals(listOf("mom", "maman"), p2.aliases)
        // Said as Cheogram names them: no alias needed.
        val (_, p3) = PeopleEdit.rememberPick(emptyList(), "maman martin", pick, 12, ids)
        assertEquals(emptyList<String>(), p3.aliases)
        // A learned person with that address takes Cheogram's name and is confirmed; an account missing is added.
        val learned = Person("L", "Spoofed Name", xmpp = listOf(XmppAddress("+15145550101@cheogram.com")), source = PersonSource.NOTIFICATION, confirmed = false)
        val (_, p4) = PeopleEdit.rememberPick(listOf(learned), "mom", pick, 13, ids)
        assertEquals("Maman Martin", p4.name)
        assertTrue(p4.confirmed)
        assertEquals("me@jmp.chat", p4.xmpp.single().account)
        // An alias moves: "mom" now means only the one you picked.
        val other = Person("o", "Old Mom", aliases = listOf("Mom"), phones = listOf(ContactPhone("5145550000", PhoneKind.MOBILE, "Mobile")))
        val (l5, _) = PeopleEdit.rememberPick(listOf(other), "mom", pick, 14, ids)
        assertEquals(emptyList<String>(), l5.first { it.id == "o" }.aliases)
    }

    // ------------------------------------------------------------------ "Always use this for mom"

    @Test fun aRememberedChoiceSetsTheAddressAndTheAlias() {
        // A person in People.
        val r = Reach(ContactIds.people("p2"), "Bob Tremblay", "bob@work.example.com", null)
        val l = PeopleEdit.rememberChoice(listOf(mom, bob), "bobby", r, email = true, lookupKey = null, now = 20, newId = ids)
        val b = l.first { it.id == "p2" }
        assertEquals("bob@work.example.com", b.preferredEmail)
        assertNull(b.preferredText)
        assertEquals(listOf("bobby"), b.aliases)
        // One of the phone's contacts: an entry that adds to it, keeping the number for when the contact can't be read.
        val sys = Reach(ContactIds.system(42), "Hélène Côté", "(514) 555-0123", "Work")
        val l2 = PeopleEdit.rememberChoice(l, "maman", sys, email = false, lookupKey = "0r42", now = 21, newId = ids)
        val h = l2.first { it.systemLookupKey == "0r42" }
        assertEquals(PersonSource.REMEMBERED, h.source)
        assertEquals("(514) 555-0123", h.preferredText)
        assertEquals(listOf(ContactPhone("(514) 555-0123", PhoneKind.OTHER, "Work")), h.phones)
        assertEquals(listOf("maman"), h.aliases)
        // Again for the same contact, another number: the same entry changes.
        val l3 = PeopleEdit.rememberChoice(l2, "maman", sys.copy(address = "+1 514 555 0124", label = "Mobile"), email = false, lookupKey = "0r42", now = 22, newId = ids)
        assertEquals(l2.size, l3.size)
        assertEquals("+1 514 555 0124", l3.first { it.systemLookupKey == "0r42" }.preferredText)
        // A person gone meanwhile: nothing changes.
        val gone = Reach(ContactIds.people("nobody"), "X", "x@example.org", null)
        assertSame(l3, PeopleEdit.rememberChoice(l3, "x", gone, email = true, lookupKey = null, now = 23, newId = ids))
    }

    // ------------------------------------------------------------------ learning from notifications

    @Test fun notificationsTeachNamesButNeverRenameSomeoneYouConfirmed() {
        val l1 = PeopleEdit.learn(emptyList(), "Zoé Lefèvre", "zoe@example.org", "me@example.org", 30, ids)
        val z = l1.single()
        assertFalse(z.confirmed)
        assertEquals(PersonSource.NOTIFICATION, z.source)
        assertEquals(listOf(XmppAddress("zoe@example.org", "me@example.org")), z.xmpp)
        // The same again: nothing to write.
        assertSame(l1, PeopleEdit.learn(l1, "Zoé Lefèvre", "ZOE@example.org", "me@example.org", 31, ids))
        // A learned person takes the newest name; a confirmed one keeps theirs, and only gets a missing account.
        assertEquals("Zoé L.", PeopleEdit.learn(l1, "Zoé L.", "zoe@example.org", "me@example.org", 32, ids).single().name)
        val confirmed = listOf(Person("c", "Mom", xmpp = listOf(XmppAddress("mom@example.org"))))
        val l2 = PeopleEdit.learn(confirmed, "Someone Else", "mom@example.org", "me@example.org", 33, ids)
        assertEquals("Mom", l2.single().name)
        assertEquals("me@example.org", l2.single().xmpp.single().account)
        // Review 0.4.0 #4: a newer notification's account replaces an older one (even one picked), in lower case.
        val picked = listOf(Person("c", "Mom", xmpp = listOf(XmppAddress("mom@example.org", "old@example.org", picked = true))))
        val moved = PeopleEdit.learn(picked, "Mom", "mom@example.org", "New@Example.org", 37, ids).single().xmpp.single()
        assertEquals(XmppAddress("mom@example.org", "new@example.org", picked = false), moved)
        assertSame(l2, PeopleEdit.learn(l2, "Someone Else", "mom@example.org", "me@example.org", 34, ids))
        // Nothing for a name-less or unusable sender.
        assertSame(confirmed, PeopleEdit.learn(confirmed, " ", "x@example.org", "me@example.org", 35, ids))
        assertSame(confirmed, PeopleEdit.learn(confirmed, "X", "a?b@example.org", "me@example.org", 36, ids))
        // A flood of senders keeps the newest learned people only; confirmed people are never dropped.
        var l: List<Person> = confirmed
        for (i in 1..(People.MAX_LEARNED + 5)) l = PeopleEdit.learn(l, "Sender $i", "s$i@example.org", "me@example.org", 100L + i, ids)
        assertEquals(People.MAX_LEARNED + 1, l.size)
        assertTrue(l.any { it.id == "c" })
        assertFalse(l.any { it.name == "Sender 1" })
        assertTrue(l.any { it.name == "Sender ${People.MAX_LEARNED + 5}" })
    }

    // ------------------------------------------------------------------ importing

    @Test fun importsMergeOnlyTheSamePerson() {
        val incoming = listOf(
            // Bob again: the same name in another form, and a number already his: merged, the file's name a nickname.
            Person("i1", "Bob Tremblay", nicknames = listOf("Robert"), phones = listOf(ContactPhone("514-555-0199", PhoneKind.MOBILE, "Mobile"),
                ContactPhone("514 555 0177", PhoneKind.WORK, "Work"))),
            // Mom's number at the gateway is the number in the file, and it is all the file has: merged.
            Person("i2", "Maman", phones = listOf(ContactPhone("(514) 555-0101", PhoneKind.MOBILE, "Mobile"))),
            // Someone new.
            Person("i3", "Zoé Lefèvre", emails = listOf("zoe@example.org")),
            // Nothing to reach them by.
            Person("i4", "Nobody"),
        )
        val m = PeopleEdit.merge(listOf(mom, bob), incoming, 50)
        assertEquals(1, m.added)
        assertEquals(2, m.updated)
        assertEquals(1, m.skipped)
        assertEquals(listOf("p2", "p1", "i3", null), m.landed)
        val b = m.people.first { it.id == "p2" }
        assertEquals("Bob Tremblay", b.name)
        assertEquals(listOf("Robert"), b.nicknames)
        assertEquals(emptyList<String>(), b.aliases)
        assertEquals(2, b.phones.size)
        val mm = m.people.first { it.id == "p1" }
        assertEquals("Mom Martin", mm.name)
        assertEquals(listOf("Mom"), mm.aliases)
        assertEquals(listOf("Maman"), mm.nicknames)
        assertEquals("Zoé Lefèvre", m.people.last().name)
        // The same file twice changes nothing more.
        val again = PeopleEdit.merge(m.people, incoming, 51)
        assertEquals(0, again.added)
        assertEquals(0, again.updated)
        // A learned person matched by the file takes the file's name and is confirmed.
        val learned = Person("L", "Spoof", xmpp = listOf(XmppAddress("zoe@example.org")), source = PersonSource.NOTIFICATION, confirmed = false)
        val m2 = PeopleEdit.merge(listOf(learned), listOf(Person("i", "Zoé Lefèvre", xmpp = listOf(XmppAddress("ZOE@example.org", "me@example.org")))), 52)
        assertEquals("Zoé Lefèvre", m2.people.single().name)
        assertTrue(m2.people.single().confirmed)
    }

    @Test fun aSharedLandlineOrEmailNeverMergesTwoPeople() {
        // Review 0.4.0 #3: Mom has the home number; the file's Dad has it too, with his own mobile and email.
        val home = Person("m", "Mom", phones = listOf(ContactPhone("514 555 0100", PhoneKind.HOME, "Home")), emails = listOf("family@example.org"))
        val dad = Person("d", "Dad", phones = listOf(ContactPhone("514 555 0100", PhoneKind.HOME, "Home"), ContactPhone("514 555 0177", PhoneKind.MOBILE, "Mobile")),
            emails = listOf("dad@example.org", "family@example.org"))
        val m = PeopleEdit.merge(listOf(home), listOf(dad), 1)
        assertEquals(1, m.added)
        assertEquals(0, m.updated)
        assertEquals(home, m.people.first { it.id == "m" })
        assertEquals(listOf("514 555 0100", "514 555 0177"), m.people.first { it.id == "d" }.phones.map { it.number })
        // "email mom" still has one address, and "text dad" finds Dad (not Mom under another name).
        assertEquals(listOf("family@example.org"), m.people.first { it.id == "m" }.emails)
        assertEquals(listOf(ContactIds.people("d")), AddressBook(emptyList(), m.people).best("dad").map { it.id })
        // A name that is a nickname of someone already there is theirs.
        val m2 = PeopleEdit.merge(listOf(home.copy(nicknames = listOf("Maman"))), listOf(Person("x", "maman", phones = listOf(ContactPhone("514 555 0188", PhoneKind.MOBILE, "Mobile")),
            emails = listOf("family@example.org"))), 2)
        assertEquals(1, m2.updated)
        assertEquals(1, m2.people.size)
    }

    @Test fun aBigFileMergesInOnePass() {
        val base = (0 until 2500).map { Person("b$it", "Person $it", phones = listOf(ContactPhone("+1 514 55${"%05d".format(it)}", PhoneKind.MOBILE, "Mobile"))) }
        val file = (0 until 2500).map { Person("f$it", "Person ${it + 1250}", emails = listOf("p$it@example.org"),
            phones = listOf(ContactPhone("+1 514 55${"%05d".format(it + 1250)}", PhoneKind.MOBILE, "Mobile"))) }
        val m = PeopleEdit.merge(base, file, 1)
        assertEquals(1250, m.updated)
        assertEquals(1250, m.added)
        assertEquals(3750, m.people.size)
    }

    @Test fun anImportIsWrittenAsMergedUnlessTheListChanged() {
        val s = PeopleStore(tmp())
        s.save(mom)
        val before = s.list()
        val incoming = listOf(Person("z", "Zoé", emails = listOf("zoe@example.org")))
        val merged = PeopleEdit.merge(before, incoming, 1)
        var again = 0
        s.commit(before, merged.people) { again++; PeopleEdit.merge(it, incoming, 2).people }
        assertEquals(0, again)
        assertEquals(2, s.list().size)
        // Something changed meanwhile (the assistant remembered someone): merged again with it, nothing lost.
        val before2 = s.list()
        val merged2 = PeopleEdit.merge(before2, listOf(Person("y", "Yves", emails = listOf("yves@example.org"))), 3)
        s.save(bob)
        s.commit(before2, merged2.people) { again++; PeopleEdit.merge(it, listOf(Person("y", "Yves", emails = listOf("yves@example.org"))), 4).people }
        assertEquals(1, again)
        assertEquals(setOf("p1", "z", "p2", "y"), s.list().map { it.id }.toSet())
    }

    // ------------------------------------------------------------------ the address book

    private val phone = listOf(
        ContactName(ContactIds.system(1), "Mom", lookupKey = "k1"),
        ContactName(ContactIds.system(2), "Alex Martin", lookupKey = "k2"),
        ContactName(ContactIds.system(3), "Alexandra Roy", lookupKey = "k3"),
    )

    @Test fun aliasesBeatNamesAndJoinThePhonesContact() {
        val link = Person("lk", "Alexandra Roy", aliases = listOf("sasha"), phones = listOf(ContactPhone("514 555 0300", PhoneKind.OTHER, "Work")),
            preferredText = "514 555 0300", source = PersonSource.REMEMBERED, systemLookupKey = "k3")
        val book = AddressBook(phone, listOf(mom, link))
        // "Mom" is a phone contact's exact name, but you told Robot "Mom" means Mom Martin.
        val m = book.best("mom")
        assertEquals(listOf(ContactIds.people("p1")), m.map { it.id })
        assertEquals(NameMatch.ALIAS, m.single().kind)
        assertTrue(ContactMatcher.sure(m, 1))
        // The alias of an entry joined to a phone contact finds that contact (one person, not two).
        assertEquals(listOf(ContactIds.system(3)), book.best("Sasha").map { it.id })
        assertEquals(link, book.person(ContactIds.system(3)))
        assertEquals(mom, book.person(ContactIds.people("p1")))
        // Its remembered number joins the contact's card read from the phone.
        val cards = book.cards(book.best("sasha")) { ms -> ms.map { ContactCard(it.id, it.name, phones = listOf(
            ContactPhone("514 555 0301", PhoneKind.MOBILE, "Mobile"), ContactPhone("514-555-0300", PhoneKind.WORK, "Work"))) } }
        assertEquals("514 555 0300", cards.single().preferredText)
        assertEquals(listOf("514-555-0300"), Recipients.forText(cards, xmppOk = true).map { it.address })
        // Without the phone's contacts (not allowed), the entry stands on its own with what it kept.
        val alone = AddressBook(emptyList(), listOf(link))
        assertEquals(listOf(ContactIds.people("lk")), alone.best("sasha").map { it.id })
        assertEquals(listOf("514 555 0300"), Recipients.forText(alone.cards(alone.best("sasha")) { emptyList() }, xmppOk = false).map { it.address })
        // The phone's contacts are read once, for the phone's matches only.
        var calls = 0
        book.cards(book.best("alex")) { ms -> calls++; assertTrue(ms.all { ContactIds.systemId(it.id) != null }); emptyList() }
        assertEquals(1, calls)
        book.cards(book.best("mom")) { calls++; emptyList() }
        assertEquals(1, calls)
    }

    @Test fun anAliasWinsOnlyForSomeoneTheToolCanReach() {
        // Review 0.4.0 #2A: you picked "Bob Jones" in Cheogram for "bob" (XMPP only); the phone's Bob has an email.
        val jones = Person("j", "Bob Jones", aliases = listOf("bob"), xmpp = listOf(XmppAddress("bob@jabber.example.org")))
        val phoneBob = listOf(ContactName(ContactIds.system(3), "Bob"))
        val book = AddressBook(phoneBob, listOf(jones))
        // For a text (Cheogram), Bob Jones can be reached: the alias wins.
        assertEquals(listOf(ContactIds.people("j") to NameMatch.ALIAS), book.best("bob", reach = Reachable.text(xmppOk = true)).map { it.id to it.kind })
        // For an email he can't: the alias counts like any name, so the phone's Bob is found too.
        val mail = book.best("bob", reach = Reachable.email)
        assertEquals(setOf(ContactIds.system(3), ContactIds.people("j")), mail.map { it.id }.toSet())
        assertTrue(mail.all { it.kind == NameMatch.EXACT })
        // Nor by the SMS app (it can't text an XMPP address).
        assertTrue(book.best("bob", reach = Reachable.text(xmppOk = false)).none { it.kind == NameMatch.ALIAS })
        // An email-only person with an alias doesn't hide the phone's numbers from "text bob".
        val proton = AddressBook(phoneBob, listOf(Person("p", "Robert Smith", aliases = listOf("Bob"), emails = listOf("bob@proton.me"))))
        assertEquals(2, proton.best("bob", reach = Reachable.text(xmppOk = true)).size)
        assertEquals(listOf(ContactIds.people("p")), proton.best("bob", reach = Reachable.email).map { it.id })
    }

    @Test fun importedNicknamesMatchAtTheirUsualRank() {
        // Review 0.4.0 #2B: a file's NICKNAME is not a name you gave: the phone contact named exactly "Bob" isn't hidden.
        val e = VCard.parse("BEGIN:VCARD\nVERSION:3.0\nFN:Robert Smith\nNICKNAME:Bob\nTEL;TYPE=CELL:+15145550101\nEND:VCARD\n").entries.single()
        val robert = VCard.person(e, "r", 1)
        assertEquals(listOf("Bob"), robert.nicknames)
        assertEquals(emptyList<String>(), robert.aliases)
        val book = AddressBook(listOf(ContactName(ContactIds.system(3), "Bob")), listOf(robert))
        val m = book.best("bob", reach = Reachable.text(xmppOk = true))
        assertEquals(setOf(ContactIds.system(3), ContactIds.people("r")), m.map { it.id }.toSet())
        assertFalse(ContactMatcher.sure(m, 1))
        // A name kept from a merge is a nickname too.
        val merged = PeopleEdit.merge(listOf(robert), listOf(Person("x", "Bobby Smith", phones = listOf(ContactPhone("514 555 0101", PhoneKind.MOBILE, "Mobile")))), 2)
        assertTrue(merged.people.single().aliases.isEmpty())
    }

    @Test fun possessivesAreLeftOutOfAliases() {
        // Review 0.4.0 #10: the name asked may be "my mom" (the model's, or Claude's).
        assertEquals("mom", People.aliasName(" my  mom "))
        assertEquals("mère", People.aliasName("Ma mère"))
        assertEquals("parents", People.aliasName("mes parents"))
        assertEquals("ma", People.aliasName("ma"))
        assertEquals("Always use this for “mom”", Recipients.rememberLabel("my mom"))
        val pick = CheogramPick("Maman Martin", "+15145550101@cheogram.com", "me@jmp.chat")
        val (_, p) = PeopleEdit.rememberPick(emptyList(), "my mom", pick, 1, ids)
        assertEquals(listOf("mom"), p.aliases)
        val book = AddressBook(emptyList(), listOf(p.copy(aliases = listOf("mom", "mère"))))
        for (asked in listOf("mom", "my mom", "Mom", "ma mère", "MÈRE")) assertEquals(asked, NameMatch.ALIAS, book.best(asked).single().kind)
        // Kept without them when edited, too.
        assertEquals(listOf("mom"), People.clean(Person("x", "Maman Martin", aliases = listOf("my mom", "mom"))).aliases)
    }

    @Test fun aLearnedNameNeverDisplacesSomeoneConfirmed() {
        val spoof = Person("s", "Mom", xmpp = listOf(XmppAddress("spoof@example.org", "me@example.org")), source = PersonSource.NOTIFICATION, confirmed = false)
        // The phone has a Mom: the learned "Mom" follows only as one more choice, and nothing is sure.
        val book = AddressBook(phone, listOf(spoof))
        assertEquals(listOf(ContactIds.system(1), ContactIds.people("s")), book.best("mom").map { it.id })
        assertFalse(ContactMatcher.sure(book.best("mom"), 1))
        // Review 0.4.0 #1: the phone's "Mom Smith" only starts with "mom" and a sender called themselves exactly "Mom". Mom
        // Smith still comes first; the learned one is only offered after them.
        val smith = AddressBook(listOf(ContactName(ContactIds.system(5), "Mom Smith")), listOf(spoof))
        assertEquals(listOf(ContactIds.system(5), ContactIds.people("s")), smith.best("mom").map { it.id })
        val maman = AddressBook(listOf(ContactName(ContactIds.system(6), "Maman Dupont")), listOf(spoof.copy(name = "Maman")))
        assertEquals(listOf(ContactIds.system(6), ContactIds.people("s")), maman.best("maman").map { it.id })
        // A learned name that matches worse than someone confirmed isn't offered at all.
        val worse = AddressBook(listOf(ContactName(ContactIds.system(7), "Mom")), listOf(spoof.copy(name = "Mommy Dearest")))
        assertEquals(listOf(ContactIds.system(7)), worse.best("mom").map { it.id })
        // Someone confirmed matched but can't be reached this way: the learned one is never left alone on the card.
        val a = Action.SendText("mom", "hi")
        val learnedOnly = listOf(Recipients.Option("Mom", "XMPP · seen in a notification", "spoof@example.org", a, learned = true))
        assertEquals(emptyList<Recipients.Option>(), Recipients.trusted(smith.best("mom"), learnedOnly))
        // Only the learned one: found, never sure, and offered as a choice to tap, with the box that keeps them, never as a
        // button already filled in.
        val alone = AddressBook(emptyList(), listOf(spoof))
        val m = alone.best("mom")
        assertEquals(listOf(true), m.map { it.learned })
        assertFalse(ContactMatcher.sure(m, 1))
        assertEquals(learnedOnly, Recipients.trusted(m, learnedOnly))
        val reach = Recipients.forText(alone.cards(m) { emptyList() }, xmppOk = true).single()
        assertTrue(reach.learned)
        // Its account was only seen in a notification: the chat opens by the xmpp: link, which needs no roster entry.
        assertNull(reach.account)
        val t = TextTarget(reach.name, reach.address, reach.address, "Cheogram", TextVia.CHEOGRAM, from = reach)
        val p = Recipients.prepare(a, m, listOf(Recipients.Option(reach.name, "XMPP · seen in a notification", reach.address, a.copy(target = t), learned = true,
            remember = a.copy(target = t.copy(remember = true)))), 0, Recipients.rememberLabel("mom")) { a } as Prepared.Ready
        assertTrue(p.mustAsk)
        assertEquals(listOf("XMPP · seen in a notification · spoof@example.org"), p.choices.map { it.label })
        assertEquals(Recipients.LEARNED_NOTE, p.note)
        assertEquals("Always use this for “mom”", p.remember)
        assertEquals("Text **Mom** (spoof@example.org) in Cheogram · name seen in a notification", (p.action as Action.SendText).describe())
        assertNull((p.action as Action.SendText).target!!.account)
        // Keep (or an edit, or the ticked box) confirms them: from then on they count like anyone else.
        val c = PeopleEdit.confirm(listOf(spoof), "s", 40)
        assertTrue(c.single().confirmed)
        assertSame(c, PeopleEdit.confirm(c, "s", 41))
        assertTrue(ContactMatcher.sure(AddressBook(emptyList(), c).best("mom"), 1))
        val ticked = PeopleEdit.rememberChoice(listOf(spoof), "mom", reach, email = false, lookupKey = null, now = 42)
        assertTrue(ticked.single().confirmed)
    }

    @Test fun aRememberedChoiceIsTheOnlyPlaceOfferedWhileItExists() {
        val card = ContactCard("s:1", "Mom", phones = listOf(ContactPhone("514 555 0199", PhoneKind.HOME, "Home"), ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile")),
            xmpp = listOf(XmppAddress("mom@example.org")), preferredText = "514 555 0199")
        assertEquals(listOf("514 555 0199"), Recipients.forText(listOf(card), xmppOk = true).map { it.address })
        assertEquals(listOf("mom@example.org"), Recipients.forText(listOf(card.copy(preferredText = "MOM@example.org")), xmppOk = true).map { it.address })
        // A gateway address names its number.
        assertEquals(listOf("+1 514-555-0101"), Recipients.forText(listOf(card.copy(preferredText = "+15145550101@cheogram.com")), xmppOk = false).map { it.address })
        // Gone from the contact: every place again.
        assertEquals(3, Recipients.forText(listOf(card.copy(preferredText = "514 555 0000")), xmppOk = true).size)
        val mail = ContactCard("s:2", "Bob", emails = listOf("bob@example.org", "bob@work.example.com"), preferredEmail = "BOB@work.example.com")
        assertEquals(listOf("bob@work.example.com"), Recipients.forEmail(listOf(mail)).map { it.address })
        assertTrue(Preferred.same("+1 (514) 555-0101", "5145550101"))
        assertFalse(Preferred.same("a@example.org", "b@example.org"))
        assertFalse(Preferred.same("", ""))
    }

    @Test fun idsSayWhereAContactComesFrom() {
        assertEquals("s:12", ContactIds.system(12))
        assertEquals(12L, ContactIds.systemId("s:12"))
        assertNull(ContactIds.systemId("r:12"))
        assertEquals("abc", ContactIds.peopleId("r:abc"))
        assertNull(ContactIds.peopleId("s:abc"))
        assertNull(ContactIds.peopleId("r:"))
        assertNotNull(PeopleStore.newId())
        assertTrue(PeopleStore.validId(PeopleStore.newId()))
    }

    // ------------------------------------------------------------------ review 0.4.0, the lows

    @Test fun aDamagedFileIsNeverWrittenOver() {
        // Review 0.4.0 #9: a file cut short read as nobody, and the next write kept only the new entry.
        val dir = tmp()
        val store = PeopleStore(dir)
        assertTrue(store.save(mom) && store.save(bob))
        val f = File(dir, "people.json")
        val cut = f.readText().dropLast(20)
        f.writeText(cut)
        assertTrue(store.damaged())
        assertEquals(emptyList<Person>(), store.list())
        // Nothing changes it: a notification, a save, Delete all.
        assertFalse(store.learn(CheogramSender("Zoé", "zoe@example.org", "me@example.org"), 5))
        assertFalse(store.save(Person("p9", "New")))
        assertNull(store.update { it + Person("p9", "New") })
        assertFalse(store.deleteAll())
        assertEquals(cut, f.readText())
        // Start over: the file is kept aside, and People works again.
        assertTrue(store.reset())
        assertEquals(cut, File(dir, "people.json.bad").readText())
        assertFalse(store.damaged())
        assertTrue(store.save(bob))
        assertEquals(listOf(bob), store.list())
        // A good file is never set aside.
        assertTrue(store.reset())
        assertEquals(listOf(bob), store.list())
        assertEquals(cut, File(dir, "people.json.bad").readText())
    }

    @Test fun someoneRemovedIsNotLearnedAgain() {
        // Review 0.4.0 #7: Remove on a learned person lasted only until their next message.
        val store = PeopleStore(tmp())
        val zoe = CheogramSender("Zoé", "Zoe@Example.org", "me@example.org")
        assertTrue(store.learn(zoe, 1))
        val learned = store.list().single()
        assertFalse(learned.confirmed)
        assertTrue(store.remove(learned.id, ignore = true))
        assertEquals(setOf("zoe@example.org"), store.ignored())
        assertTrue(store.learn(zoe.copy(name = "Zoé B."), 2))
        assertEquals(emptyList<Person>(), store.list())
        // The list survives other changes, and a confirmed person removed without it isn't ignored.
        assertTrue(store.save(mom))
        assertTrue(store.remove(mom.id, ignore = false))
        assertEquals(setOf("zoe@example.org"), store.ignored())
        // Delete all forgets it too.
        assertTrue(store.deleteAll())
        assertEquals(emptySet<String>(), store.ignored())
        assertTrue(store.learn(zoe, 3))
        assertEquals("Zoé", store.list().single().name)
        // It is kept in the file.
        val text = PeopleCodec.toJson(listOf(mom), setOf("a@example.org"))
        assertEquals(PeopleFile(listOf(mom), setOf("a@example.org")), PeopleCodec.read(text))
        assertNull(PeopleCodec.read("{\"v\":1}"))
        assertEquals(PeopleFile(), PeopleCodec.read(""))
    }

    @Test fun theEditorAppliesOnlyWhatWasChanged() {
        // Review 0.4.0 #8: Save replaced the whole entry with the one loaded when the screen opened.
        val loaded = Person("p1", "Mom Martin", phones = listOf(ContactPhone("+1 514 555 0101", PhoneKind.MOBILE, "Mobile")),
            xmpp = listOf(XmppAddress("mom@example.org", "me@example.org")), updatedAt = 1)
        // Meanwhile: "Always use this for mom" on a card, and a notification moved the chat to another account.
        val now = loaded.copy(aliases = listOf("mom"), preferredText = "mom@example.org",
            xmpp = listOf(XmppAddress("mom@example.org", "other@example.org")), updatedAt = 2)
        // The editor changed the name and added an email.
        val edited = loaded.copy(name = "Marie Martin", emails = listOf("marie@example.org"))
        val (list, p) = PeopleEdit.applyEdit(listOf(now, bob), loaded, edited, 9)!!
        assertEquals("Marie Martin", p.name)
        assertEquals(listOf("mom"), p.aliases)
        assertEquals("mom@example.org", p.preferredText)
        assertEquals(listOf(XmppAddress("mom@example.org", "other@example.org")), p.xmpp)
        assertEquals(listOf("marie@example.org"), p.emails)
        assertEquals(listOf(p, bob), list)
        assertTrue(p.confirmed)
        assertEquals(9L, p.updatedAt)
        // Removing the remembered place forgets the choice; a number added meanwhile stays.
        val more = now.copy(phones = now.phones + ContactPhone("+1 514 555 0202", PhoneKind.WORK, "Work"))
        val (_, q) = PeopleEdit.applyEdit(listOf(more), loaded, loaded.copy(xmpp = emptyList()), 9)!!
        assertEquals(emptyList<XmppAddress>(), q.xmpp)
        assertNull(q.preferredText)
        assertEquals(listOf("+1 514 555 0101", "+1 514 555 0202"), q.phones.map { it.number })
        // An edited row wins over the stored one with the same address.
        val (_, r) = PeopleEdit.applyEdit(listOf(now), loaded, loaded.copy(xmpp = listOf(XmppAddress("mom@example.org", "mine@example.org"))), 9)!!
        assertEquals(listOf(XmppAddress("mom@example.org", "mine@example.org")), r.xmpp)
        // Saving someone learned confirms them.
        val learned = loaded.copy(source = PersonSource.NOTIFICATION, confirmed = false)
        assertTrue(PeopleEdit.applyEdit(listOf(learned), learned, learned, 9)!!.second.confirmed)
    }

    @Test fun theEditorNeverBringsBackSomeoneDeletedAndSavesANewPersonOnce() {
        val loaded = bob
        assertNull(PeopleEdit.applyEdit(listOf(mom), loaded, loaded.copy(name = "Robert"), 9))
        val store = PeopleStore(tmp())
        assertTrue(store.save(mom))
        // A new person: the editor keeps one id, so a second Save (a double tap) changes the same person.
        val fresh = Person("new1", "Zoé", emails = listOf("zoe@example.org"))
        repeat(2) { assertNotNull(store.update { l -> PeopleEdit.applyEdit(l, null, fresh, 9)!!.first }) }
        assertEquals(listOf("p1", "new1"), store.list().map { it.id })
        // Forget on a remembered choice is a change of its own.
        val withChoice = PeopleEdit.upsert(store.list(), bob.copy(preferredText = "+1 514 555 0199", preferredEmail = "bob@example.org"))
        val forgot = PeopleEdit.forgetChoice(withChoice, bob.id, email = false, now = 9).first { it.id == bob.id }
        assertNull(forgot.preferredText)
        assertEquals("bob@example.org", forgot.preferredEmail)
        assertSame(withChoice, PeopleEdit.forgetChoice(withChoice, mom.id, email = true, now = 9))
    }
}
