package app.heyrobot.core.messages

import app.heyrobot.core.messages.Snapshots.MIN
import app.heyrobot.core.messages.Snapshots.T
import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design §B4–§B5 across the bridge: conversations cross Binder whole and come back the same; a reply names a
 * conversation and carries words, and the main process sends it only to that conversation's own notification, read
 * again just before (the guard), never to anything the voice process says, and never with "Open the chat; I press Send".
 */
class MessageWireTest {
    private val on = MessageSettings(on = true, allowlist = Snapshots.ALL_APPS)
    private val mom = thread(Snapshots.cheogram1to1("Tu viens souper?"))
    private val group = thread(Snapshots.cheogramGroup)

    @Test fun conversationsRoundTrip() {
        val all = listOf(mom, group, thread(Snapshots.signal), thread(Snapshots.messaging), thread(Snapshots.signalNameOnly))
        val status = MessagesStatus(on = true, access = true, connected = true, locked = false, apps = 3)
        val (s, back) = MessageWire.list(MessageWire.list(status, all))
        assertEquals(status, s)
        assertEquals(all, back)
        // Damaged: nothing, never something else.
        assertEquals(MessagesStatus() to emptyList<MessageThread>(), MessageWire.list("{"))
        assertEquals(MessagesStatus() to emptyList<MessageThread>(), MessageWire.list(null))
        assertNull(MessageWire.thread(org.json.JSONObject().put("app", "NOT_AN_APP").put("conv", "x").put("nkey", "y")))
        assertEquals(listOf("a", "b"), MessageWire.ids(MessageWire.ids(listOf("a", "b"))))
        assertEquals("k", MessageWire.keyOf(MessageWire.key("k")))
        assertTrue(MessageWire.ok(MessageWire.ok(true)))
        assertEquals("p1" to Lang.FR, MessageWire.replyLang(MessageWire.replyLang("p1", Lang.FR)))
        assertEquals("p1" to null, MessageWire.replyLang(MessageWire.replyLang("p1", null)))
        assertNull(MessageWire.replyLang("{}"))
        val req = ReplyRequest(mom.key, "J'arrive!", mom.newestIncoming)
        assertEquals(req, MessageWire.request(MessageWire.request(req)))
        assertNull(MessageWire.request("{\"text\":\"no key\"}"))
        val a = ReplyAnswer(ReplyOutcome.NEW_MESSAGES, mom)
        assertEquals(a, MessageWire.answer(MessageWire.answer(a)))
        assertEquals(ReplyAnswer(ReplyOutcome.FAILED), MessageWire.answer("nonsense"))
        // A reply request's toString has no words.
        assertFalse(req.toString().contains("arrive"))
    }

    @Test fun theRecipientIsTheHeldNotificationsOwn() {
        val req = ReplyRequest(mom.key, "J'arrive", mom.newestIncoming)
        val r = ReplySend.check(mom, mom, req, on, locked = false) as ReplyCheck.Fire
        assertEquals(mom.notificationKey, r.target.notificationKey)
        assertEquals(MessageApp.CHEOGRAM, r.target.app)
        assertEquals("text_reply", r.target.action.resultKey)
        assertEquals(0, r.target.action.index)
        assertEquals("J'arrive", r.text)
    }

    @Test fun goneChangedNewerLockedOrOffNeverSends() {
        val req = ReplyRequest(mom.key, "J'arrive", mom.newestIncoming)
        fun outcome(c: ReplyCheck) = (c as ReplyCheck.Stop).answer.outcome
        // Nothing held under that key, or the notification went.
        assertEquals(ReplyOutcome.GONE, outcome(ReplySend.check(null, mom, req, on, false)))
        assertEquals(ReplyOutcome.GONE, outcome(ReplySend.check(mom, null, req, on, false)))
        // The reply action went.
        assertEquals(ReplyOutcome.GONE, outcome(ReplySend.check(mom, mom.copy(reply = null), req, on, false)))
        // Another conversation under that notification key now.
        assertEquals(ReplyOutcome.CHANGED, outcome(ReplySend.check(mom, group.copy(notificationKey = mom.notificationKey), req, on, false)))
        // Mom wrote again: the card shows it, nothing sent.
        val newer = thread(Snapshots.cheogram1to1("Tu viens souper?", "Allô?"))
        val stop = ReplySend.check(mom, newer, req, on, false) as ReplyCheck.Stop
        assertEquals(ReplyOutcome.NEW_MESSAGES, stop.answer.outcome)
        assertEquals("Allô?", stop.answer.thread!!.incoming.last().text)
        // Locked: only with "Read and reply", and never an action that needs the phone unlocked.
        assertEquals(ReplyOutcome.LOCKED, outcome(ReplySend.check(mom, mom, req, on, locked = true)))
        val lockedOk = on.copy(locked = MessagesLocked.READ_AND_REPLY)
        assertTrue(ReplySend.check(mom, mom, req, lockedOk, locked = true) is ReplyCheck.Fire)
        val auth = mom.copy(reply = mom.reply!!.copy(authRequired = true))
        assertEquals(ReplyOutcome.AUTH_REQUIRED, outcome(ReplySend.check(auth, auth, req, lockedOk, locked = true)))
        // Off, and "Open the chat; I press Send": the main process never sends.
        assertEquals(ReplyOutcome.OFF, outcome(ReplySend.check(mom, mom, req, on.copy(on = false), false)))
        assertEquals(ReplyOutcome.OPEN_CHAT, outcome(ReplySend.check(mom, mom, req, on.copy(replyRoute = ReplyRoute.OPEN_CHAT), false)))
        // Too long, or empty.
        assertEquals(ReplyOutcome.FAILED, outcome(ReplySend.check(mom, mom, req.copy(text = "x".repeat(ReplyPlanner.MAX_CHARS + 1)), on, false)))
        assertEquals(ReplyOutcome.FAILED, outcome(ReplySend.check(mom, mom, req.copy(text = "  "), on, false)))
        // Every outcome a reply can end with has a log code, but off and open-chat (nothing was tried).
        assertEquals(listOf(ReplyOutcome.OFF, ReplyOutcome.OPEN_CHAT), ReplyOutcome.entries.filter { it.end == null })
        assertTrue(T + MIN > 0)
    }
}
