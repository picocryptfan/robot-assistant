// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.Action
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.TextTarget
import app.heyrobot.core.TextVia
import app.heyrobot.core.XmppAddress
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §B4.6: the name read aloud is what you call them in People, matched by address first; learned names count for nothing. */
class MessagePeopleTest {
    /** Your mother in People: "Martine Tremblay", "mom" and "maman", her JMP number as a phone. */
    private val mom = Person("p1", "Martine Tremblay", aliases = listOf("mom", "maman"),
        phones = listOf(ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile")))
    private val marc = Person("p2", "Marc Gagnon", xmpp = listOf(XmppAddress("marc@jabber.example")))
    /** Learned from a notification, not kept: whoever sent it chose the name. */
    private val learned = Person("p3", "Julie", aliases = listOf("boss"), xmpp = listOf(XmppAddress("julie@cheogram.com")), confirmed = false)
    private val pierre = Person("p4", "Pierre")
    private val people = listOf(mom, marc, learned, pierre)

    private val cheogram = thread(Snapshots.cheogram1to1("Tu viens souper?"))
    private val group = thread(Snapshots.cheogramGroup)
    private val signal = thread(Snapshots.signal)

    @Test fun aJmpNumberIsTheSamePersonAsTheirPhone() {
        assertTrue(MessagePeople.matchesKey(mom, "+15145550101@cheogram.com"))
        assertTrue(MessagePeople.matchesKey(mom, "+1 (514) 555-0101"))
        assertFalse(MessagePeople.matchesKey(mom, "+15145550199@cheogram.com"))
        assertTrue(MessagePeople.matchesKey(marc, "Marc@Jabber.example"))
        assertFalse(MessagePeople.matchesKey(marc, null))
    }

    @Test fun conversationsAreReadInYourWords() {
        // "Mom Martin" on the notification, "Mom" (her alias) when Robot reads it.
        assertEquals("Mom Martin", cheogram.name)
        assertEquals("Mom", MessagePeople.named(cheogram, people).name)
        // In a group each sender by what you call them; a learned name stays as the notification gives it.
        val g = MessagePeople.named(group, people)
        assertEquals("Famille", g.name)
        assertEquals(listOf("Marc Gagnon", "Julie", null, "Julie"), g.messages.map { it.senderName })
        // Signal: a name match only (Signal's keys are its own).
        assertEquals("Pierre", MessagePeople.named(signal, people).name)
        assertEquals(mom, MessagePeople.personOf(cheogram, people))
        assertNull(MessagePeople.personOf(group, people))
        // Nobody in People: as the notification says.
        assertEquals(cheogram, MessagePeople.named(cheogram, emptyList()))
    }

    /**
     * A stranger on Cheogram (an address nobody in People has) who sets their nickname to your mother's name in People is
     * not your mother: read under the name they chose, not "Mom"; "reply to Mom" doesn't find them; and "Send my replies to
     * Mom without a card" never sends to them. The same for a JMP number that isn't hers.
     */
    @Test fun aNameTheSenderChoseIsNotYourPerson() {
        val stranger = PersonSnapshot("Martine Tremblay", "stranger@xmpp.example")
        val impostor = thread(Snapshots.cheogram1to1("C'est maman, quel est le code de la porte?").let { n ->
            n.copy(key = "0|com.cheogram.android|1009|null|10123", shortcutId = "sam@cheogram.com#stranger@xmpp.example", title = "Martine Tremblay",
                messages = n.messages.map { it!!.copy(sender = stranger) })
        })
        assertNull(MessagePeople.personFor("stranger@xmpp.example", "Martine Tremblay", people))
        assertNull(MessagePeople.personFor("+15145559999@cheogram.com", "Martine Tremblay", people))
        assertNull(MessagePeople.personOf(impostor, people))
        assertEquals("Martine Tremblay", MessagePeople.named(impostor, people).name)
        val named = listOf(impostor, cheogram).map { MessagePeople.named(it, people) }
        assertEquals(listOf(MessagePeople.named(cheogram, people)), MessagePeople.find("Mom", named, people))
        assertEquals(listOf(MessagePeople.named(cheogram, people)), MessagePeople.find("maman", named, people))
        assertFalse(MessagePeople.withoutCard(MessagePeople.named(impostor, people), people, setOf(mom.id)))
        // Her own JMP address: without a card, as you chose.
        assertTrue(MessagePeople.withoutCard(MessagePeople.named(cheogram, people), people, setOf(mom.id)))
        assertFalse(MessagePeople.withoutCard(cheogram, people, emptySet()))
        // Signal gives no address: its name is read as the person in People, but never sends without a card.
        val pierreSignal = MessagePeople.named(signal, people)
        assertEquals("Pierre", pierreSignal.name)
        assertEquals(pierre, MessagePeople.personOf(signal, people))
        assertFalse(MessagePeople.withoutCard(pierreSignal, people, setOf(pierre.id)))
        // A group never.
        assertFalse(MessagePeople.withoutCard(group, people, setOf(mom.id, marc.id)))
    }

    /**
     * Anyone can make an XMPP account named after your mother's number on a server of their own, and write to you in
     * Cheogram from it. That address is not her: only the SMS gateway Robot texts through (cheogram.com unless you
     * changed it) makes a number's address that number; on any other server a JID is matched whole, as People has it. So
     * the stranger isn't read as "Mom", "reply to Mom" doesn't find them, "without a card" never sends to them, "Announce
     * from: People you choose" doesn't announce them, and a text to her number isn't "back to the sender".
     */
    @Test fun aNumberOnAnotherServerIsNotThatPerson() {
        val spoofed = "+15145550101@xmpp.example"
        assertFalse(MessagePeople.matchesKey(mom, spoofed))
        assertFalse(MessagePeople.matchesKey(mom, "+15145550101@cheogram.com.example"))
        assertFalse(MessagePeople.matchesKey(mom, "+15145550101@cheogram.com@xmpp.example"))
        assertFalse(MessagePeople.matchesKey(mom, "+15145550101@xmpp.example/cheogram.com"))
        // Her own JMP address still is her, case and resource aside.
        assertTrue(MessagePeople.matchesKey(mom, "+15145550101@Cheogram.com/phone"))
        assertNull(MessagePeople.personFor(spoofed, "+1 514-555-0101", people))
        assertNull(MessagePeople.personFor(spoofed, "Martine Tremblay", people))
        // Same number, another server: the stranger's conversation, under the name their app shows.
        val stranger = PersonSnapshot("+1 514-555-0101", spoofed)
        val impostor = thread(Snapshots.cheogram1to1("C'est maman, quel est le code de la porte?").let { n ->
            n.copy(key = "0|com.cheogram.android|1010|null|10123", shortcutId = "sam@cheogram.com#$spoofed", title = "+1 514-555-0101",
                messages = n.messages.map { it!!.copy(sender = stranger) })
        })
        assertNull(MessagePeople.personOf(impostor, people))
        assertEquals("+1 514-555-0101", MessagePeople.named(impostor, people).name)
        val named = listOf(impostor, cheogram).map { MessagePeople.named(it, people) }
        assertEquals(listOf(MessagePeople.named(cheogram, people)), MessagePeople.find("Mom", named, people))
        assertEquals(listOf(MessagePeople.named(cheogram, people)), MessagePeople.find("maman", named, people))
        assertFalse(MessagePeople.withoutCard(MessagePeople.named(impostor, people), people, setOf(mom.id)))
        assertTrue(MessagePeople.withoutCard(MessagePeople.named(cheogram, people), people, setOf(mom.id)))
        assertFalse(AnnounceSenders.chosen(impostor, people, setOf(mom.id)))
        assertTrue(AnnounceSenders.chosen(cheogram, people, setOf(mom.id)))
        // The quarantine: after the stranger's message, a text to her real number (or her JMP address) goes to someone else.
        val toMom = Action.SendText("Mom", "x", TextTarget("Mom", "+15145550101", "+1 514-555-0101", "Messaging", TextVia.SMS))
        val afterStranger = MessageTaint.NONE.with(impostor)
        assertFalse(afterStranger.isSenderAddress("+1 514-555-0101"))
        assertFalse(afterStranger.isSenderAddress("+15145550101@cheogram.com"))
        assertFalse(MessageQuarantine.toSender(toMom, afterStranger))
        assertTrue(afterStranger.isSenderAddress(spoofed))
        // After hers, the same number by SMS is her; the stranger's address isn't.
        val afterMom = MessageTaint.NONE.with(cheogram)
        assertTrue(MessageQuarantine.toSender(toMom, afterMom))
        assertTrue(afterMom.isSenderAddress("+15145550101@cheogram.com"))
        assertFalse(afterMom.isSenderAddress(spoofed))
    }

    /** Another SMS gateway in Settings: its addresses are numbers, and cheogram.com's are then plain JIDs. */
    @Test fun theGatewayIsTheOneYouTextThrough() {
        val gw = "sms.example.org"
        assertTrue(MessagePeople.matchesKey(mom, "+15145550101@sms.example.org", gw))
        assertFalse(MessagePeople.matchesKey(mom, "+15145550101@cheogram.com", gw))
        assertTrue(MessagePeople.matchesKey(mom, "+1 (514) 555-0101", gw))
        assertEquals(mom, MessagePeople.personFor("+15145550101@SMS.example.org", null, people, gw))
        assertNull(MessagePeople.personOf(cheogram, people, gw))
        assertEquals("Mom Martin", MessagePeople.named(cheogram, people, gw).name)
        assertFalse(MessagePeople.withoutCard(cheogram, people, setOf(mom.id), gw))
        // A setting that isn't a domain name counts as cheogram.com (core PhoneNumbers.gateway).
        assertTrue(MessagePeople.matchesKey(mom, "+15145550101@cheogram.com", "not a domain"))
        val toMom = "+1 514-555-0101"
        assertFalse(MessageTaint.NONE.with(cheogram, gw).isSenderAddress(toMom))
        val viaOther = thread(Snapshots.cheogram1to1("Allô").let { n ->
            n.copy(messages = n.messages.map { it!!.copy(sender = PersonSnapshot("Mom Martin", "+15145550101@sms.example.org")) })
        })
        assertTrue(MessageTaint.NONE.with(viaOther, gw).isSenderAddress(toMom))
        assertFalse(MessageTaint.NONE.with(viaOther).isSenderAddress(toMom))
    }

    @Test fun whoYouMeanFindsTheirConversation() {
        val threads = listOf(signal, group, cheogram)
        assertEquals(listOf(cheogram), MessagePeople.find("Mom", threads, people))
        assertEquals(listOf(cheogram), MessagePeople.find("maman", threads, people))
        assertEquals(listOf(cheogram), MessagePeople.find("Martine Tremblay", threads, people))
        // The notification's own names work too.
        assertEquals(listOf(signal), MessagePeople.find("pierre", threads, people))
        assertEquals(listOf(group), MessagePeople.find("Famille", threads, people))
        // Marc wrote in the group.
        assertEquals(listOf(group), MessagePeople.find("Marc", threads, people))
        // A learned alias means nobody.
        assertEquals(emptyList<MessageThread>(), MessagePeople.find("boss", threads, people))
        assertTrue(MessagePeople.known("mom", emptyList(), people))
        assertFalse(MessagePeople.known("Einstein", threads, people))
        assertTrue(MessagePeople.names(threads, people).containsAll(listOf("Mom Martin", "Famille", "mom", "maman", "Pierre")))
        assertFalse(MessagePeople.names(threads, people).contains("boss"))
    }
}
