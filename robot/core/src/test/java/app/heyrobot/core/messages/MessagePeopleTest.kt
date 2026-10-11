package app.heyrobot.core.messages

import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
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
