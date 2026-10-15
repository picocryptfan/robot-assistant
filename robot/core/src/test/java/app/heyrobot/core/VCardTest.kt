// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contacts files: what Robot reads from vCard 2.1, 3.0 and 4.0, and what it writes. */
class VCardTest {
    private fun one(text: String): VCardEntry = VCard.parse(text).entries.single()

    /** A file as Proton Mail's web app exports it (Contacts › select all › Export selection as .VCF): vCard 4.0, item groups. */
    private val proton = """
        BEGIN:VCARD
        VERSION:4.0
        PRODID;VALUE=TEXT:-//ProtonMail//ProtonMail vCard 1.0.0//EN
        FN;PREF=1:Zoé Lefèvre
        UID:proton-web-3b5f6c1e-0d1a-4c2b-9f5e-7a1b2c3d4e5f
        N:Lefèvre;Zoé;;;
        item1.EMAIL;PREF=1:zoe@example.com
        item1.CATEGORIES:Famille
        item2.EMAIL;PREF=2:zoe.lefevre@example.fr
        TEL;PREF=1;TYPE=cell:+33 6 12 34 56 78
        ADR;TYPE=home:;;12 rue de l'Église;Montréal;QC;H2X 1Y4;Canada
        BDAY:19900214
        NOTE:Allergique aux noix\, voir avec elle
        END:VCARD
        BEGIN:VCARD
        VERSION:4.0
        PRODID;VALUE=TEXT:-//ProtonMail//ProtonMail vCard 1.0.0//EN
        FN;PREF=1:Bob Tremblay
        UID:proton-web-11111111-2222-3333-4444-555555555555
        item1.EMAIL;PREF=1:bob@example.org
        item2.EMAIL;PREF=2:bob@work.example.com
        item3.EMAIL;PREF=3;TYPE=work:BOB@work.example.com
        END:VCARD
        BEGIN:VCARD
        VERSION:4.0
        FN;PREF=1:Only A Name
        END:VCARD
    """.trimIndent().replace("\n", "\r\n")

    @Test fun protonsExportKeepsNamesWithAccentsAndEveryEmail() {
        val f = VCard.parse(proton)
        assertEquals(2, f.entries.size)
        assertEquals(1, f.skipped)
        val z = f.entries[0]
        assertEquals("Zoé Lefèvre", z.name)
        assertEquals(listOf("zoe@example.com", "zoe.lefevre@example.fr"), z.emails)
        assertEquals(listOf(ContactPhone("+33 6 12 34 56 78", PhoneKind.MOBILE, "Mobile")), z.phones)
        // Addresses, birthdays, notes and categories are not kept.
        assertEquals(emptyList<String>(), z.nicknames)
        val b = f.entries[1]
        assertEquals(listOf("bob@example.org", "bob@work.example.com"), b.emails)
        assertEquals(4, f.emails)
        assertEquals(1, f.phones)
        // "email Zoé" then finds her address through People.
        val book = AddressBook(emptyList(), f.entries.mapIndexed { i, e -> VCard.person(e, "v$i", 1) })
        val m = book.best("zoe")
        assertEquals("Zoé Lefèvre", m.single().name)
        assertEquals(listOf("zoe@example.com", "zoe.lefevre@example.fr"), Recipients.forEmail(book.cards(m) { emptyList() }).map { it.address })
    }

    @Test fun vCard21WithQuotedPrintableAndSoftBreaks() {
        // As the phone's Contacts app exports: 2.1, UTF-8 quoted-printable, a soft line break, bare type parameters.
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:C=C3=B4t=C3=A9;H=C3=A9l=C3=A8ne;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:H=C3=A9l=C3=A8ne =\r\nC=C3=B4t=C3=A9\r\n" +
            "TEL;CELL;PREF:+1-514-555-0101\r\n" +
            "TEL;HOME:514 555 0199\r\n" +
            "TEL;WORK;VOICE:(514) 555-0177 ext. 22\r\n" +
            "EMAIL;HOME:helene@example.org\r\n" +
            "X-JABBER;HOME:helene@jabber.example.org\r\n" +
            "X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;Maman;1;;;;;;;;;;;;;\r\n" +
            "NOTE;ENCODING=QUOTED-PRINTABLE:Tr=C3=A8s =\r\nlong\r\n" +
            "PHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQ\r\n AAAQABAAD\r\n\r\n" +
            "END:VCARD\r\n"
        val e = one(text)
        assertEquals("Hélène Côté", e.name)
        assertEquals(listOf("Maman"), e.nicknames)
        assertEquals(listOf(PhoneKind.MOBILE, PhoneKind.HOME, PhoneKind.WORK), e.phones.map { it.kind })
        assertEquals("(514) 555-0177 ext. 22", e.phones[2].number)
        assertEquals(listOf("helene@example.org"), e.emails)
        assertEquals(listOf(XmppAddress("helene@jabber.example.org")), e.xmpp)
    }

    @Test fun latin1QuotedPrintableAndNWithoutFn() {
        val text = "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:Fran=E7ois;Andr=E9;;Dr;\nTEL:5145550101\nEND:VCARD\n"
        assertEquals("André François", one(text).name)
    }

    @Test fun vCard30FoldingEscapesGroupsAndLabels() {
        val text = """
            BEGIN:VCARD
            VERSION:3.0
            FN:Martin\, Alice \; the
              second
            NICKNAME:Al,Ali\,ce
            item1.TEL;type=pref:+1 (613) 555-0123
            item1.X-ABLabel:Maman
            item2.TEL:+1 613 555 0124
            item2.X-ABLabel:_${'$'}!<Mobile>!${'$'}_
            item3.EMAIL;type=INTERNET;type=pref:alice@example.org
            IMPP;X-SERVICE-TYPE=Jabber;type=pref:xmpp:alice@jabber.example.org
            IMPP:sip:alice@voip.example.org
            END:VCARD
        """.trimIndent()
        val e = one(text)
        assertEquals("Martin, Alice ; the second", e.name)
        assertEquals(listOf("Al", "Ali,ce"), e.nicknames)
        assertEquals(listOf("Maman", "Mobile"), e.phones.map { it.label })
        // Apple's "Mobile" label makes it a mobile number (offered first for texts).
        assertEquals(listOf(PhoneKind.OTHER, PhoneKind.MOBILE), e.phones.map { it.kind })
        assertEquals(listOf("alice@example.org"), e.emails)
        // Only xmpp: in IMPP; SIP and others aren't kept.
        assertEquals(listOf(XmppAddress("alice@jabber.example.org")), e.xmpp)
    }

    @Test fun vCard40TelUrisQuotedTypesAndTheSameAddressTwice() {
        val text = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Sam Côté
            TEL;VALUE=uri;TYPE="voice,cell";PREF=1:tel:+1-514-555-0111;ext=5
            TEL;VALUE=uri;TYPE=work:tel:%2B15145550112
            EMAIL:mailto:sam@example.org
            IMPP;PREF=1:xmpp:sam@example.org
            X-JABBER:SAM@example.org
            END:VCARD
        """.trimIndent()
        val e = one(text)
        assertEquals(listOf("+1-514-555-0111", "+15145550112"), e.phones.map { it.number })
        assertEquals(listOf(PhoneKind.MOBILE, PhoneKind.WORK), e.phones.map { it.kind })
        assertEquals(listOf("sam@example.org"), e.emails)
        assertEquals(1, e.xmpp.size)
    }

    @Test fun severalCardsNestedAgentsAndJunk() {
        val text = """
            junk before
            BEGIN:VCARD
            VERSION:2.1
            FN:Parent
            TEL:5145550101
            AGENT:
            BEGIN:VCARD
            VERSION:2.1
            FN:Assistant
            TEL:5145550999
            END:VCARD
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            EMAIL:noname@example.org
            END:VCARD
            begin:vcard
            version:3.0
            fn:lower case
            email:lower@example.org
            end:vcard
        """.trimIndent()
        val f = VCard.parse(text)
        assertEquals(listOf("Parent", "noname@example.org", "lower case"), f.entries.map { it.name })
        assertEquals(listOf("5145550101"), f.entries[0].phones.map { it.number })
    }

    @Test fun bytesAreUtf8OrWindows1252OrUtf16() {
        val utf8 = "FN:Zoé".toByteArray(Charsets.UTF_8)
        assertEquals("FN:Zoé", VCard.decode(utf8))
        assertEquals("FN:Zoé", VCard.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8))
        assertEquals("FN:Zoé", VCard.decode("FN:Zoé".toByteArray(charset("windows-1252"))))
        // Review 0.4.0 #16: UTF-16 files (some Windows and Outlook exports), with or without a byte-order mark.
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Zoé\r\nEMAIL:zoe@example.org\r\nEND:VCARD\r\n"
        for (b in listOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + card.toByteArray(Charsets.UTF_16LE), byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            card.toByteArray(Charsets.UTF_16BE), card.toByteArray(Charsets.UTF_16LE), card.toByteArray(Charsets.UTF_16BE))) {
            assertEquals("Zoé", VCard.parse(VCard.decode(b)).entries.single().name)
        }
    }

    @Test fun aSoftBreakBeforeAnIndentedLineAndAnUnendedLastCard() {
        // Review 0.4.0 #16: a quoted-printable soft break whose next line is also folded left "Hélène=Dupont".
        val qp = "BEGIN:VCARD\r\nVERSION:2.1\r\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:H=C3=A9l=C3=A8ne =\r\n Dupont\r\nTEL:5145550101\r\nEND:VCARD\r\n"
        assertEquals("Hélène Dupont", one(qp).name)
        // The file's last card has no END:VCARD: kept.
        val f = VCard.parse("BEGIN:VCARD\nVERSION:3.0\nFN:Ann\nEMAIL:ann@example.org\nEND:VCARD\nBEGIN:VCARD\nVERSION:3.0\nFN:Bea\nEMAIL:bea@example.org\n")
        assertEquals(listOf("Ann", "Bea"), f.entries.map { it.name })
        // ...unless there's nothing to reach them by.
        assertEquals(1, VCard.parse("BEGIN:VCARD\nFN:Cy\n").skipped)
    }

    @Test fun longPicturesAreSkippedInOnePass() {
        // Review 0.4.0 #5: a folded base64 PHOTO took seconds (each folded line copied the whole value again).
        val photo = (1..20_000).joinToString("\r\n ") { "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo0NTY3ODkwYWJjZGVmZ2hpamtsbW5vcHFy" }
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Pic\r\nPHOTO;ENCODING=b;TYPE=JPEG:$photo\r\nLOGO;VALUE=uri:data:x\r\nEMAIL:pic@example.org\r\nEND:VCARD\r\n"
        val t0 = System.nanoTime()
        val e = one(text)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        assertEquals(listOf("pic@example.org"), e.emails)
    }

    @Test fun anExportReadsBackTheSame() {
        val people = listOf(
            Person("a", "Hélène Côté-Desrosiers de la Montagne, avec un nom très long pour plier la ligne", aliases = listOf("maman", "mom; ma"),
                phones = listOf(ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile"), ContactPhone("514 555 0199", PhoneKind.OTHER, "Other")),
                emails = listOf("helene@example.org"), xmpp = listOf(XmppAddress("+15145550101@cheogram.com", "me@example.net"))),
            Person("b", "Bob", emails = listOf("bob@example.org")),
        )
        val text = VCard.write(people)
        // CRLF, and no line over 75 bytes.
        assertTrue(text.endsWith("END:VCARD\r\n"))
        assertTrue(text.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertTrue(text.contains("X-JABBER:+15145550101@cheogram.com"))
        val back = VCard.parse(text)
        assertEquals(0, back.skipped)
        val a = back.entries[0]
        assertEquals(people[0].name, a.name)
        assertEquals(listOf("maman", "mom; ma"), a.nicknames)
        assertEquals(listOf(PhoneKind.MOBILE, PhoneKind.OTHER), a.phones.map { it.kind })
        assertEquals(listOf("helene@example.org"), a.emails)
        // The Cheogram account survives the round trip (Robot's own IMPP parameter).
        assertEquals(listOf(XmppAddress("+15145550101@cheogram.com", "me@example.net")), a.xmpp)
        assertEquals("Bob", back.entries[1].name)
        // A person imported back merges with themselves: nothing new.
        val m = PeopleEdit.merge(people, back.entries.mapIndexed { i, e -> VCard.person(e, "n$i", 2) }, 3)
        assertEquals(0, m.added)
    }
}
