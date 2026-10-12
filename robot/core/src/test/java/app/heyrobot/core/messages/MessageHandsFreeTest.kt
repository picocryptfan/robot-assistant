package app.heyrobot.core.messages

import app.heyrobot.core.AppUnread
import app.heyrobot.core.ContactPhone
import app.heyrobot.core.Person
import app.heyrobot.core.PhoneKind
import app.heyrobot.core.UnreadCount
import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.ReplyEnd
import app.heyrobot.core.car.ReplyStart
import app.heyrobot.core.headset.AnnounceAnswer
import app.heyrobot.core.headset.AnnounceNext
import app.heyrobot.core.messages.Snapshots.thread
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.ReplyResult
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.output.handsFree
import app.heyrobot.core.output.notice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8: 0.7's messages in the brief, the headset and the car. */
class MessageHandsFreeTest {
    private val on = MessageSettings(on = true, allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM, MessageApp.SIGNAL)))
    private val mom = Person("p1", "Martine Tremblay", aliases = listOf("mom"), phones = listOf(ContactPhone("+1 514-555-0101", PhoneKind.MOBILE, "Mobile")))
    private val momThread = thread(Snapshots.cheogram1to1("T'es où?", "J'arrive bientôt"))
    private val pierre = thread(Snapshots.signal)

    @Test fun theBriefCountsByAppOnly() {
        val u = MessageUnread.count(listOf(momThread, pierre), on)!!
        assertEquals(UnreadCount(3, listOf(AppUnread("Cheogram", 2), AppUnread("Signal", 1))), u)
        // Never a name: the count crosses the bridge as numbers and app names, and reads back the same.
        val wire = MessageUnread.toJson(u)
        assertFalse(wire.contains("Mom") || wire.contains("Pierre") || wire.contains("arrive"))
        assertEquals(u, MessageUnread.fromJson(wire))
        // Read to you already: not counted. An app Settings › Messages doesn't allow: not counted.
        val read = momThread.copy(messages = momThread.messages.map { it.copy(readByRobot = true) })
        assertEquals(UnreadCount(1, listOf(AppUnread("Signal", 1))), MessageUnread.count(listOf(read, pierre), on))
        assertEquals(UnreadCount(2, listOf(AppUnread("Cheogram", 2))), MessageUnread.count(listOf(momThread, pierre), on.copy(allowlist = MessageAllowlist(setOf(MessageApp.CHEOGRAM)))))
        // Settings › Messages off: the brief says nothing about messages.
        assertNull(MessageUnread.count(listOf(momThread), on.copy(on = false)))
        assertNull(MessageUnread.fromJson(MessageUnread.toJson(null)))
        assertNull(MessageUnread.fromJson("garbage"))
        assertEquals(UnreadCount(0, emptyList()), MessageUnread.count(emptyList(), on))
    }

    @Test fun onlyANewIncomingMessageIsAnnounced() {
        val one = thread(Snapshots.cheogram1to1("T'es où?"))
        assertTrue(MessageArrival.isNew(null, one))
        assertTrue(MessageArrival.isNew(one, momThread))
        // The same notification posted again, or after Robot read it: not new.
        assertFalse(MessageArrival.isNew(momThread, momThread))
        assertFalse(MessageArrival.isNew(null, momThread.copy(messages = momThread.messages.map { it.copy(readByRobot = true) })))
        // Your own reply added to the conversation: not new.
        val mine = momThread.copy(messages = momThread.messages + momThread.messages.last().copy(id = "x", fromMe = true, time = momThread.messages.last().time + 60_000))
        assertFalse(MessageArrival.isNew(momThread, mine))
        // Earlier context only: not new.
        assertFalse(MessageArrival.isNew(null, one.copy(messages = one.messages.map { it.copy(historic = true) })))
    }

    /** Do Not Disturb holds an announcement unless its ranking was read and lets it through: anything unread fails closed. */
    @Test fun doNotDisturbFailsClosed() {
        assertTrue(MessageDnd.allows(found = true, matches = true))
        assertFalse(MessageDnd.allows(found = true, matches = false))
        assertFalse(MessageDnd.allows(found = false, matches = true))
        assertFalse(MessageDnd.allows(found = null, matches = true))
    }

    @Test fun peopleYouChooseAreYourConfirmedPeople() {
        assertTrue(AnnounceSenders.chosen(momThread, listOf(mom), setOf("p1")))
        assertFalse(AnnounceSenders.chosen(momThread, listOf(mom), emptySet()))
        assertFalse(AnnounceSenders.chosen(momThread, listOf(mom), setOf("p2")))
        // Learned, not confirmed: not chosen.
        assertFalse(AnnounceSenders.chosen(momThread, listOf(mom.copy(confirmed = false)), setOf("p1")))
        // Someone else: their name in the notification doesn't make them Mom.
        assertFalse(AnnounceSenders.chosen(pierre, listOf(mom), setOf("p1")))
    }

    @Test fun aReplyGoesByVoiceOnlyWhere07LetsAYesSendIt() {
        // 0.7's defaults: a card, sent by a tap. A spoken yes isn't enough, so the car keeps it for parking.
        assertEquals(VoiceReply.TAP_ONLY, HandsFreeReplies.of(momThread, on, locked = false, noCard = false))
        assertEquals(VoiceReply.BY_VOICE, HandsFreeReplies.of(momThread, on.copy(voiceYesSends = true), locked = false, noCard = false))
        assertEquals(VoiceReply.BY_VOICE, HandsFreeReplies.of(momThread, on, locked = false, noCard = true))
        // Locked: a tap only, whatever the switches (or not at all without "Read and reply").
        assertEquals(VoiceReply.REFUSED, HandsFreeReplies.of(momThread, on.copy(voiceYesSends = true), locked = true, noCard = false))
        assertEquals(VoiceReply.TAP_ONLY, HandsFreeReplies.of(momThread, on.copy(voiceYesSends = true, locked = MessagesLocked.READ_AND_REPLY), locked = true, noCard = false))
        // "Open the chat; I press Send": Robot sends nothing itself.
        assertEquals(VoiceReply.OPEN_CHAT, HandsFreeReplies.of(momThread, on.copy(replyRoute = ReplyRoute.OPEN_CHAT, voiceYesSends = true), locked = false, noCard = false))
        // A group always needs a tap.
        val group = momThread.copy(isGroup = true)
        assertEquals(VoiceReply.TAP_ONLY, HandsFreeReplies.of(group, on.copy(voiceYesSends = true), locked = false, noCard = true))
    }

    @Test fun inTheCarATapOnlyReplyIsKeptForParkingWithYourWords() {
        val n = momThread.notice()!!
        val s = CarSettings()
        val safety = HandsFreeSafety()
        val held = CarMessages.startReply(n, "tell her I'm 10 minutes away", s, safety, false, false, SpokenLang.EN, VoiceReply.TAP_ONLY) as ReplyStart.Hold
        assertEquals(ReplyEnd.NEEDS_TAP, held.why)
        assertEquals("I'm 10 minutes away", held.text)
        assertEquals("reply to “${n.sender}”: I'm 10 minutes away", CarMessages.parkedAsk(n, held.text, SpokenLang.EN))
        assertEquals(ReplyEnd.NEEDS_TAP, (CarMessages.startReply(n, "ok", s, safety, false, false, SpokenLang.EN, VoiceReply.OPEN_CHAT) as ReplyStart.Hold).why)
        assertEquals(ReplyEnd.LOCKED, (CarMessages.startReply(n, "ok", s, safety, false, false, SpokenLang.EN, VoiceReply.REFUSED) as ReplyStart.Hold).why)
        assertTrue(CarMessages.startReply(n, "ok", s, safety, false, false, SpokenLang.EN, VoiceReply.BY_VOICE) is ReplyStart.ReadBack)
        assertNull(CarMessages.parkedAsk(n, " ", SpokenLang.EN))
        // The main process's answers, as said in the car: "Handed to Cheogram", never "delivered".
        assertEquals(ReplyResult.HANDED_OVER, ReplyOutcome.SENT.handsFree())
        assertEquals(ReplyResult.NEW_MESSAGES, ReplyOutcome.NEW_MESSAGES.handsFree())
        assertEquals(ReplyResult.GONE, ReplyOutcome.CHANGED.handsFree())
        assertEquals(ReplyResult.LOCKED, ReplyOutcome.AUTH_REQUIRED.handsFree())
        assertTrue(CarMessages.result(ReplyResult.NEW_MESSAGES, "Cheogram", SpokenLang.EN).contains("park"))
        assertTrue(ReplyOutcome.entries.all { o -> CarMessages.result(o.handsFree(), "Cheogram", SpokenLang.FR).isNotBlank() })
    }

    @Test fun readItInTheHeadset() {
        val said = "Message from Mom. Read it?"
        assertEquals(AnnounceNext.Read, AnnounceAnswer.next("yes", said, 0, 0, SpokenLang.EN))
        assertEquals(AnnounceNext.Read, AnnounceAnswer.next("oui", "Message de Maman. Je te le lis?", 0, 0, SpokenLang.FR))
        assertEquals(AnnounceNext.Quiet, AnnounceAnswer.next("no", said, 0, 0, SpokenLang.EN))
        assertEquals(AnnounceNext.Ask(said), AnnounceAnswer.next("repeat", said, 0, 0, SpokenLang.EN))
        // Robot's own question heard back is not a yes; two unusable answers end it quietly.
        assertTrue(AnnounceAnswer.next(said, said, 0, 0, SpokenLang.EN) is AnnounceNext.Ask)
        assertEquals(AnnounceNext.Quiet, AnnounceAnswer.next("", said, 1, 0, SpokenLang.EN))
    }

    @Test fun peopleToAnnounceAreKeptInMessagesJson() {
        val f = MessagesFile(settings = on.copy(announce = true), announcePeople = setOf("p1", "p4"))
        val back = MessagesFile.fromJson(f.toJson())
        assertEquals(setOf("p1", "p4"), back.announcePeople)
        assertTrue(back.settings.announce)
        // Off by default, and an old file has nobody chosen.
        assertFalse(MessagesFile.fromJson(null).settings.announce)
        assertEquals(emptySet<String>(), MessagesFile.fromJson("""{"v":2,"on":true}""").announcePeople)
    }
}
