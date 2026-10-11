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
        assertEquals(listOf(XmppAddress("+15145550101@cheogram.com", "me@jmp.chat")), p1.xmpp)
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

    @Test fun importsMergeByNumberEmailAndAddress() {
        val incoming = listOf(
            // Bob again, with a new number: merged, his name kept and the file's name an alias.
            Person("i1", "Robert Tremblay", phones = listOf(ContactPhone("514-555-0199", PhoneKind.MOBILE, "Mobile"), ContactPhone("514 555 0177", PhoneKind.WORK, "Work"))),
            // Mom's number at the gateway is the number in the file.
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
        assertEquals(listOf("Robert Tremblay"), b.aliases)
        assertEquals(2, b.phones.size)
        val mm = m.people.first { it.id == "p1" }
        assertEquals("Mom Martin", mm.name)
        assertTrue(mm.aliases.containsAll(listOf("Mom", "Maman")))
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

    @Test fun aLearnedNameNeverStandsBesideSomeoneConfirmed() {
        val spoof = Person("s", "Mom", xmpp = listOf(XmppAddress("spoof@example.org", "me@example.org")), source = PersonSource.NOTIFICATION, confirmed = false)
        // The phone has a Mom: the learned "Mom" is left out.
        val book = AddressBook(phone, listOf(spoof))
        assertEquals(listOf(ContactIds.system(1)), book.best("mom").map { it.id })
        // Only the learned one: found, but never sure, and its card says so.
        val alone = AddressBook(emptyList(), listOf(spoof))
        val m = alone.best("mom")
        assertEquals(listOf(true), m.map { it.learned })
        assertFalse(ContactMatcher.sure(m, 1))
        val reach = Recipients.forText(alone.cards(m) { emptyList() }, xmppOk = true).single()
        assertTrue(reach.learned)
        assertEquals("me@example.org", reach.account)
        val a = Action.SendText("mom", "hi")
        val t = a.copy(target = TextTarget(reach.name, reach.address, reach.address, "Cheogram", TextVia.CHEOGRAM, from = reach))
        val p = Recipients.prepare(a, m, listOf(Recipients.Option(reach.name, "XMPP", reach.address, t, learned = true)), 0) { a } as Prepared.Ready
        assertTrue(p.mustAsk)
        assertEquals("Text **Mom** (spoof@example.org) in Cheogram · name seen in Cheogram", (p.action as Action.SendText).describe())
        assertEquals("me@example.org", (p.action as Action.SendText).target!!.account)
        // A learned name that matches better than anyone confirmed is still offered (on a card).
        val book2 = AddressBook(listOf(ContactName(ContactIds.system(9), "Momo Diallo")), listOf(spoof))
        assertEquals(listOf(ContactIds.people("s")), book2.best("mom").map { it.id })
        // Using (or editing) them confirms them: from then on they count like anyone else.
        val c = PeopleEdit.confirm(listOf(spoof), "s", 40)
        assertTrue(c.single().confirmed)
        assertSame(c, PeopleEdit.confirm(c, "s", 41))
        assertTrue(ContactMatcher.sure(AddressBook(emptyList(), c).best("mom"), 1))
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
}
