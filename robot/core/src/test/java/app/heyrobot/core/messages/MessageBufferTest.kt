package app.heyrobot.core.messages

import app.heyrobot.core.messages.Snapshots.MIN
import app.heyrobot.core.messages.Snapshots.T
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBufferTest {
    private val now = T + 60 * MIN

    @Test fun goneWhenTheNotificationGoesByDefault() {
        val b = MessageBuffer()
        val mom = thread(Snapshots.cheogram1to1("Are you coming?"))
        b.posted(mom)
        b.posted(thread(Snapshots.signal))
        assertEquals(2, b.size)
        assertEquals(listOf(MessageApp.SIGNAL, MessageApp.CHEOGRAM), b.threads(now).map { it.app })
        b.removed(mom.notificationKey, now)
        assertEquals(listOf(MessageApp.SIGNAL), b.threads(now).map { it.app })
        b.removed("no such key", now)
        assertEquals(1, b.size)
    }

    @Test fun keptForAnHourWhenAsked() {
        val b = MessageBuffer(KeepMessages.ONE_HOUR)
        val mom = thread(Snapshots.cheogram1to1("Are you coming?"))
        b.posted(mom)
        b.removed(mom.notificationKey, now)
        assertEquals(1, b.threads(now + 59 * MIN).size)
        assertEquals(0, b.threads(now + 60 * MIN).size)
        // Switching back to "until gone" lets what has gone go.
        b.posted(mom)
        b.removed(mom.notificationKey, now)
        b.keep = KeepMessages.UNTIL_GONE
        assertEquals(0, b.threads(now).size)
    }

    @Test fun aRepostReplacesAndKeepsWhatWasRead() {
        val b = MessageBuffer()
        val first = thread(Snapshots.cheogram1to1("Are you coming?"))
        b.posted(first)
        b.markReadByRobot(first.messages.map { it.id })
        assertTrue(b.unread(now).isEmpty())
        // Mom writes again: the app posts the conversation with both messages.
        b.posted(thread(Snapshots.cheogram1to1("Are you coming?", "Hello??")))
        val t = b.thread(first.key)!!
        assertEquals(2, t.messages.size)
        assertEquals(listOf(true, false), t.messages.map { it.readByRobot })
        assertEquals(listOf("Hello??"), b.unread(now).single().unread.map { it.text })
        assertEquals(1, b.size)
    }

    @Test fun rebuiltFromTheShadeOnConnect() {
        val b = MessageBuffer()
        val stale = thread(Snapshots.cheogram1to1("old"))
        b.posted(stale)
        b.connected(listOf(thread(Snapshots.signal), thread(Snapshots.messaging)), now)
        assertNull(b.thread(stale.key))
        assertEquals(2, b.size)
    }

    @Test fun cappedAndCleared() {
        val b = MessageBuffer(maxThreads = 3)
        for (i in 1..5) b.posted(thread(Snapshots.signal.copy(shortcutId = "c$i", key = "k$i")))
        assertEquals(3, b.size)
        assertNull(b.thread("SIGNAL:c1"))
        assertTrue(b.thread("SIGNAL:c5") != null)
        b.clear()
        assertEquals(0, b.size)
        assertTrue(b.unread(now).isEmpty())
    }

    @Test fun findingAConversationByName() {
        val b = MessageBuffer()
        b.posted(thread(Snapshots.cheogram1to1("Are you coming?")))
        b.posted(thread(Snapshots.cheogramGroup))
        b.posted(thread(Snapshots.signal))
        assertEquals(listOf(MessageApp.CHEOGRAM), b.named("mom", now).map { it.app })
        assertEquals(listOf(MessageApp.CHEOGRAM), b.named("Mom Martin", now).map { it.app })
        assertEquals(listOf("Famille"), b.named("julie", now).map { it.name })
        assertEquals(listOf("Pierre"), b.named("PIERRE", now).map { it.name })
        assertTrue(b.named("Mo", now).isEmpty())
        assertTrue(b.named("  ", now).isEmpty())
        assertEquals(listOf("Mom Martin"), b.withSenderKeys(setOf("+15145550101@Cheogram.com"), now).map { it.name })
    }

    @Test fun hiddenConversationsStillCountAsNew() {
        val b = MessageBuffer()
        b.posted(thread(Snapshots.signalNameOnly))
        assertEquals(1, b.unread(now).size)
        assertFalse(b.toString().contains("Pierre"))
    }
}
