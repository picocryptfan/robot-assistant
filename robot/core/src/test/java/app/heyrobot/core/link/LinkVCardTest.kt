// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.CardOffer
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * robot-to-robot-plan.md §9.1's VCardTest (job L4), for Robot to Robot's contact cards (LinkVCard, not People's lenient
 * contacts-file reader): good, bad, overlong, mixed scripts. One spelling only: a card reads when it is exactly what the
 * writer makes of it; anything else (another version, a parameter, a property outside the subset, a photo, a URL, folding,
 * line feeds alone) refuses the whole card. Damaged and random cards never throw.
 */
class LinkVCardTest {
    private val sam = ContactCard("Sam Tremblay", phones = listOf("+1 514 555-0101", "(514) 555.0102"), emails = listOf("sam@example.org"),
        xmpp = listOf("sam@xmpp.example.org"))
    private val samText = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Sam Tremblay\r\nTEL;VALUE=text:+1 514 555-0101\r\nTEL;VALUE=text:(514) 555.0102\r\n" +
        "EMAIL:sam@example.org\r\nIMPP:xmpp:sam@xmpp.example.org\r\nEND:VCARD\r\n"

    private fun card(name: String = "Sam", phones: List<String> = listOf("5145550101"), emails: List<String> = emptyList(), xmpp: List<String> = emptyList()) =
        runCatching { ContactCard(name, phones, emails, xmpp) }.getOrNull()

    @Test fun theOneSpellingReadsBack() {
        assertEquals(samText, LinkVCard.write(sam))
        assertEquals(sam, LinkVCard.read(samText))
        val back = LinkVCard.read(samText)!!
        assertEquals(listOf("+1 514 555-0101", "(514) 555.0102"), back.phones)
        assertEquals(listOf("sam@example.org"), back.emails)
        assertEquals(listOf("sam@xmpp.example.org"), back.xmpp)
        // Names with the three escaped characters, accents, emoji and other scripts.
        for (name in listOf("Tremblay, Sam; \\ Jr", "Zoë Côté-Lévesque", "Sam 🤖", "山田たろう", "Ivan Иванов", "Νίκος", "Sam O'Brien (work)")) {
            val c = ContactCard(name, emails = listOf("a@example.org"))
            val text = LinkVCard.write(c)
            assertEquals(name, c, LinkVCard.read(text))
            assertTrue(name, CardOffer.shapeOk(text))
        }
        assertTrue(LinkVCard.write(ContactCard("Tremblay, Sam;", phones = listOf("123"))).contains("FN:Tremblay\\, Sam\\;\r\n"))
        // As a sender offers it: the canonical card, which L1's codec carries and :linkparse reads.
        val offer = CardOffer.of(sam)
        assertEquals(samText, offer.vcard)
        val r = LinkRequest.of(offer, 1_790_000_000L, "en")
        val read = LinkRequests.decode(LinkRequests.encode(r)!!, LinkRequests.LIVE) as RequestRead.Ok
        assertEquals(sam, LinkVCard.read((read.request.body as CardOffer).vcard))
    }

    @Test fun theWorstCaseFitsTwoKiB() {
        val e4 = "🤖"
        val worst = ContactCard(
            e4.repeat(ContactCard.NAME),
            phones = (1..ContactCard.PHONES).map { "+1 (514) 555-01${it}0 000 000 00" .take(ContactCard.PHONE_CHARS) },
            emails = (1..ContactCard.EMAILS).map { i -> "a$i." + "b".repeat(50) + "@" + "c".repeat(60) + ".org" },
            xmpp = (1..ContactCard.XMPP).map { i -> "x$i." + "y".repeat(50) + "@" + "z".repeat(60) + ".org" },
        )
        val text = LinkVCard.write(worst)
        assertTrue(text.toByteArray().size.toString(), text.toByteArray().size <= LinkVCard.MAX_BYTES)
        assertEquals(worst, LinkVCard.read(text))
        assertTrue(CardOffer.shapeOk(text))
        // A name of commas: each written as two characters, still within the cap.
        val commas = ContactCard("a" + ",".repeat(ContactCard.NAME - 1), phones = listOf("5145550101"))
        assertEquals(commas, LinkVCard.read(LinkVCard.write(commas)))
    }

    @Test fun anythingButTheOneSpellingIsRefused() {
        fun bad(v: String) = assertNull(v.replace("\r\n", "⏎"), LinkVCard.read(v))
        // Another version, line feeds alone, lower case, a missing or extra line break, data after the end.
        bad(samText.replace("VERSION:4.0", "VERSION:3.0"))
        bad(samText.replace("\r\n", "\n"))
        bad(samText.replace("BEGIN:VCARD", "begin:vcard"))
        bad(samText.replace("FN:", "fn:"))
        bad(samText.removeSuffix("\r\n"))
        bad(samText + "\r\n")
        bad(samText + "BEGIN:VCARD\r\n")
        bad(samText.replace("FN:Sam Tremblay\r\n", "FN:Sam Tremblay\r\n\r\n"))
        // Properties outside the subset: a photo, an address to fetch, a note, N, a key, Robot's own parameters elsewhere.
        for (extra in listOf("PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRg==", "PHOTO:https://example.org/sam.jpg", "URL:https://example.org", "N:Tremblay;Sam;;;",
            "NOTE:hi", "KEY:https://example.org/key", "LOGO:https://example.org/l.png", "SOUND:https://example.org/s.ogg", "X-SOCIALPROFILE:x", "ORG:Robot",
            "ADR:;;1 Main St;Montréal;QC;;Canada", "BDAY:19900101", "UID:urn:uuid:1", "PRODID:-//Robot//EN", "REV:20261023T000000Z"))
            bad(samText.replace("END:VCARD", "$extra\r\nEND:VCARD"))
        // Parameters, groups, folding, another way to write a phone or an address.
        bad(samText.replace("FN:", "FN;CHARSET=UTF-8:"))
        bad(samText.replace("TEL;VALUE=text:", "TEL:"))
        bad(samText.replace("TEL;VALUE=text:", "TEL;TYPE=cell;VALUE=text:"))
        bad(samText.replace("TEL;VALUE=text:+1 514 555-0101", "TEL;VALUE=uri:tel:+1-514-555-0101"))
        bad(samText.replace("EMAIL:", "item1.EMAIL:"))
        bad(samText.replace("EMAIL:", "EMAIL;TYPE=home:"))
        bad(samText.replace("IMPP:xmpp:", "IMPP:sip:"))
        bad(samText.replace("IMPP:xmpp:", "X-JABBER:"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam\r\n  Tremblay"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam\r\n Tremblay"))
        // The writer's order: phones, then emails, then XMPP; FN once and first.
        bad(samText.replace("EMAIL:sam@example.org\r\nIMPP:xmpp:sam@xmpp.example.org\r\n", "IMPP:xmpp:sam@xmpp.example.org\r\nEMAIL:sam@example.org\r\n"))
        bad(samText.replace("TEL;VALUE=text:+1 514 555-0101\r\n", "").replace("EMAIL:sam@example.org\r\n", "EMAIL:sam@example.org\r\nTEL;VALUE=text:+1 514 555-0101\r\n"))
        bad(samText.replace("FN:Sam Tremblay\r\n", "FN:Sam Tremblay\r\nFN:Sam\r\n"))
        bad(samText.replace("FN:Sam Tremblay\r\n", ""))
        bad(samText.replace("FN:Sam Tremblay\r\nTEL", "TEL").replace("END:VCARD", "FN:Sam Tremblay\r\nEND:VCARD"))
        // Escapes: only \\ \, \; and only where a name needs them; a bare comma or semicolon isn't a name.
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam\\nTremblay"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam\\ Tremblay"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam, Tremblay"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam; Tremblay"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam Tremblay\\"))
        bad(samText.replace("FN:Sam Tremblay", "FN:Sam \\Tremblay"))
        // Nothing to reach Sam by, a blank name, the same phone twice, one too many.
        bad("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Sam\r\nEND:VCARD\r\n")
        bad(samText.replace("FN:Sam Tremblay", "FN: "))
        bad(samText.replace("FN:Sam Tremblay", "FN:"))
        bad(samText.replace("(514) 555.0102", "+1 514 555-0101"))
        bad(LinkVCard.write(ContactCard("Sam", phones = (1..5).map { "514555010$it" })).replace("END:VCARD", "TEL;VALUE=text:5145550109\r\nEND:VCARD"))
        // Control and invisible characters, bidi overrides, a lone surrogate, a byte-order mark.
        for (c in listOf("\u0000", "\u0007", "\u001B", "\u0085", "‮", "⁦", "‏", "﻿", "\uD800", " ", "󠁁"))
            bad(samText.replace("Sam Tremblay", "Sam${c}Tremblay"))
        // Not NFC.
        bad(samText.replace("Sam Tremblay", "Zoë"))
    }

    @Test fun overlongIsRefused() {
        assertNull(card(name = "a".repeat(ContactCard.NAME + 1)))
        assertNotNull(card(name = "a".repeat(ContactCard.NAME)))
        assertNull(card(phones = listOf("1".repeat(ContactCard.PHONE_CHARS + 1))))
        assertNotNull(card(phones = listOf("1".repeat(ContactCard.PHONE_CHARS))))
        val long = "a".repeat(64) + "@" + "b".repeat(50) + ".org"
        assertNull(card(phones = emptyList(), emails = listOf(long + "x".repeat(ContactCard.ADDRESS_CHARS - long.length + 1))))
        assertNull(card(phones = emptyList(), emails = listOf("a".repeat(65) + "@example.org")))
        assertNull(card(phones = emptyList(), emails = listOf("a@" + "b".repeat(64) + ".org")))
        assertNull(card(phones = (1..ContactCard.PHONES + 1).map { "51455501$it$it" }))
        assertNull(card(phones = emptyList(), emails = (1..ContactCard.EMAILS + 1).map { "a$it@example.org" }))
        assertNull(card(phones = emptyList(), xmpp = (1..ContactCard.XMPP + 1).map { "a$it@example.org" }))
        // Past 2 KiB on the wire, refused before anything else.
        assertNull(LinkVCard.read(samText.replace("END:VCARD", "NOTE:" + "x".repeat(2_048) + "\r\nEND:VCARD")))
    }

    @Test fun phonesAndAddressesHaveTheirGrammar() {
        for (p in listOf("5145550101", "+15145550101", "+1 514 555-0101", "(514) 555-0101", "514.555.0101", "911", "+44 (0)20 7946 0018"))
            assertTrue(p, LinkVCard.phoneOk(p))
        for (p in listOf("", "12", "+", "++1514", "1+514", "514 555 01a1", "514  555", " 5145550101", "5145550101 ", "514-", "-514", "514,555", "514;555",
            "tel:5145550101", "５１４５５５０１０１", "514 555 0101", "(51)"))
            assertFalse(p, LinkVCard.phoneOk(p))
        for (a in listOf("sam@example.org", "sam.tremblay+robot@mail.example.ca", "a_b%c-d@x-y.example.org", "s@xn--bcher-kva.example"))
            assertTrue(a, LinkVCard.addressOk(a))
        for (a in listOf("", "sam", "@example.org", "sam@", "sam@example", "sam@@example.org", "sam@a@example.org", ".sam@example.org", "sam.@example.org",
            "s..m@example.org", "sam@-example.org", "sam@example-.org", "sam@exa_mple.org", "sam@example..org", "sam@.example.org", "sam@example.org.",
            "säm@example.org", "sam@exämple.org", "sam smith@example.org", "sam,x@example.org", "sam;x@example.org", "sam\\x@example.org",
            "\"sam\"@example.org", "sam@example.123", "sam@[127.0.0.1]", "sam@example.org/res"))
            assertFalse(a, LinkVCard.addressOk(a))
    }

    /** §9.1: mixed scripts. A word mixing Latin, Cyrillic or Greek letters is refused (refused, not flagged: the card never lands). */
    @Test fun aWordMixingLookalikeAlphabetsIsRefused() {
        val cyrillicA = "а"
        val greekO = "ο"
        for (n in listOf("P${cyrillicA}ypal", "Sam Tr${cyrillicA}mblay", "R${greekO}bot", "Иван${greekO}", "Ζoë"))
            assertTrue(n, LinkVCard.mixedScripts(n) && card(name = n) == null)
        for (n in listOf("Sam Tremblay", "Ivan Иванов", "Νίκος Papadopoulos", "山田たろう", "Kim 김민준", "José-María", "R2-D2", "Sam 🤖"))
            assertTrue(n, !LinkVCard.mixedScripts(n) && card(name = n) != null)
        // The same from the wire: the card is refused whole.
        assertNull(LinkVCard.read(samText.replace("Sam Tremblay", "Sam Tr${cyrillicA}mblay")))
    }

    @Test fun aCardOffersCardIsItsVCards() {
        // :link's rule (CardOffer): a card travels with exactly its own vCard, or not at all.
        assertTrue(runCatching { CardOffer(samText, ContactCard("Sam", phones = listOf("5145550101"))) }.isFailure)
        assertEquals(sam, CardOffer(samText, sam).card)
        assertEquals(CardOffer(samText), CardOffer(samText, sam))
        assertEquals("ContactCard(2, 1, 1)", sam.toString())
        assertFalse(CardOffer.of(sam).toString().contains("Sam"))
    }

    /** Damaged and random cards: never an exception; whatever reads is exactly what the writer makes of it. */
    @Test fun damagedAndRandomCardsNeverThrow() {
        val r = Random(1504)
        val goods = listOf(samText, LinkVCard.write(ContactCard("Tremblay, Sam; \\", phones = listOf("911"))), LinkVCard.write(ContactCard("山田たろう", xmpp = listOf("t@x.jp"))))
        val alphabet = "BEGINVCARDFNTELEMAILIMPPxmpp:;,\\\r\n .@+-()0123456789aé🤖"
        var accepted = 0
        for (g in goods) {
            repeat(2_000) {
                val sb = StringBuilder(g)
                repeat(1 + r.nextInt(3)) {
                    val i = r.nextInt(sb.length)
                    when (r.nextInt(4)) {
                        0 -> sb.deleteCharAt(i)
                        1 -> sb.insert(i, alphabet[r.nextInt(alphabet.length)])
                        2 -> sb.setCharAt(i, (r.nextInt(0x3000)).toChar())
                        else -> sb.setCharAt(i, alphabet[r.nextInt(alphabet.length)])
                    }
                }
                val v = sb.toString()
                val c = LinkVCard.read(v)
                if (c != null) { accepted++; assertEquals(v, LinkVCard.write(c)) }
            }
        }
        repeat(2_000) {
            val v = String(CharArray(r.nextInt(300)) { alphabet[r.nextInt(alphabet.length)] })
            LinkVCard.read(v)
            LinkVCard.read("BEGIN:VCARD\r\nVERSION:4.0\r\n$v")
        }
        // Some damage still spells a card (a letter of the name changed): each of those is its own one spelling.
        assertTrue(accepted > 0)
    }
}
