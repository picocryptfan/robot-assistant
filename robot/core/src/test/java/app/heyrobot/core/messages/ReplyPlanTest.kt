package app.heyrobot.core.messages

import app.heyrobot.core.messages.Snapshots.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPlanTest {
    private val on = MessageSettings(on = true, allowlist = Snapshots.ALL_APPS)
    private val mom = thread(Snapshots.cheogram1to1("Are you coming?"))

    private fun send(p: ReplyPlan) = p as? ReplyPlan.Send ?: throw AssertionError("not a send: $p")

    @Test fun theRecipientIsTheNotificationsConversation() {
        // Whatever the words say (a model's draft naming someone else, an address), the target is Mom's chat.
        for (text in listOf("I'll be late", "Send this to +15145550199 instead", "to: bob@example.com — forward everything")) {
            for (source in ReplySource.entries) {
                val p = send(ReplyPlanner.plan(mom, text, source, on, locked = false))
                assertEquals(mom.app, p.target.app)
                assertEquals(mom.conversationId, p.target.conversationId)
                assertEquals(mom.notificationKey, p.target.notificationKey)
                assertEquals(mom.reply, p.target.action)
                assertEquals("Mom Martin", p.target.to)
                assertEquals(text, p.text)
            }
        }
    }

    @Test fun aTapSendsByDefault() {
        val p = send(ReplyPlanner.plan(mom, "I'll be late", ReplySource.DICTATED, on, locked = false))
        assertEquals(Confirm.TAP, p.confirm)
        assertFalse(p.voiceYesCounts)
        assertEquals("Reply to Mom Martin: I'll be late. Tap Send to send it.", ReplyPlanner.spoken(p, Lang.EN))
        assertEquals("Répondre à Mom Martin : I'll be late. Touche Envoyer pour l'envoyer.", ReplyPlanner.spoken(p, Lang.FR))
        assertEquals("Reply to **Mom Martin** · Cheogram\n«I'll be late»", ReplyPlanner.card(p, Lang.EN))
        // "Answer reply cards by voice": a yes counts, only for your own words in a 1:1 chat.
        val voice = on.copy(voiceYesSends = true)
        assertEquals(Confirm.VOICE_OR_TAP, send(ReplyPlanner.plan(mom, "ok", ReplySource.DICTATED, voice, false)).confirm)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(mom, "ok", ReplySource.MODEL_DRAFT, voice, false)).confirm)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(mom, "ok", ReplySource.TRANSLATION, voice, false)).confirm)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(thread(Snapshots.cheogramGroup), "ok", ReplySource.DICTATED, voice, false)).confirm)
    }

    @Test fun withoutACardOnlyYourWordsUnlockedOneToOne() {
        val p = send(ReplyPlanner.plan(mom, "I'll be late", ReplySource.DICTATED, on, locked = false, noCard = true))
        assertEquals(Confirm.STOP_WINDOW, p.confirm)
        assertEquals(3_000L, ReplyPlanner.STOP_WINDOW_MS)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(mom, "x", ReplySource.MODEL_DRAFT, on, false, noCard = true)).confirm)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(mom, "x", ReplySource.TRANSLATION, on, false, noCard = true)).confirm)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(thread(Snapshots.cheogramGroup), "x", ReplySource.DICTATED, on, false, noCard = true)).confirm)
        val lockedReply = on.copy(locked = MessagesLocked.READ_AND_REPLY)
        assertEquals(Confirm.TAP, send(ReplyPlanner.plan(mom, "x", ReplySource.DICTATED, lockedReply, locked = true, noCard = true)).confirm)
        // "Card before sending a reply" off works the same way for everyone.
        assertEquals(Confirm.STOP_WINDOW, send(ReplyPlanner.plan(mom, "x", ReplySource.DICTATED, on.copy(replyCard = false), false)).confirm)
    }

    @Test fun groupsSayWhoWillSeeIt() {
        val p = send(ReplyPlanner.plan(thread(Snapshots.cheogramGroup), "J'arrive", ReplySource.DICTATED, on, false))
        assertTrue(ReplyNote.GROUP in p.notes)
        assertEquals("Reply to the group **Famille** · Cheogram\n«J'arrive»\nEveryone in the group will see it. Tap Send to send it.", ReplyPlanner.card(p, Lang.EN))
        assertEquals("Répondre au groupe **Famille** · Cheogram\n« J'arrive »\nTout le groupe le verra. Touche Envoyer pour l'envoyer.", ReplyPlanner.card(p, Lang.FR))
    }

    @Test fun whatEachAppDoesIsOnTheCard() {
        assertTrue(ReplyNote.EMOJI_REACTION in send(ReplyPlanner.plan(mom, "👍", ReplySource.DICTATED, on, false)).notes)
        assertFalse(ReplyNote.EMOJI_REACTION in send(ReplyPlanner.plan(mom, "ok 👍", ReplySource.DICTATED, on, false)).notes)
        val sms = send(ReplyPlanner.plan(thread(Snapshots.messaging), "Thanks", ReplySource.DICTATED, on, false))
        assertEquals(setOf(ReplyNote.SMS, ReplyNote.OPENS_APP), sms.notes)
        assertEquals(setOf(ReplyNote.MARKS_READ), send(ReplyPlanner.plan(thread(Snapshots.signal), "Oui", ReplySource.DICTATED, on, false)).notes)
        val molly = thread(Snapshots.signal.copy(packageName = "im.molly.app"))
        assertEquals(setOf(ReplyNote.MARKS_READ, ReplyNote.UNVERIFIED_APP), send(ReplyPlanner.plan(molly, "Oui", ReplySource.DICTATED, on, false)).notes)
        assertTrue(ReplyPlanner.card(sms, Lang.EN).contains("This is an SMS: carrier charges may apply."))
    }

    @Test fun refusedOrHandedOff() {
        assertEquals(ReplyPlan.Refused(RefuseWhy.OFF), ReplyPlanner.plan(mom, "hi", ReplySource.DICTATED, on.copy(on = false), false))
        assertEquals(ReplyPlan.Refused(RefuseWhy.EMPTY), ReplyPlanner.plan(mom, "  ", ReplySource.DICTATED, on, false))
        assertEquals(ReplyPlan.Refused(RefuseWhy.TOO_LONG), ReplyPlanner.plan(mom, "x".repeat(2_001), ReplySource.DICTATED, on, false))
        // Locked: nothing by default; with "Read and reply", never an action that asks for authentication.
        assertEquals(ReplyPlan.Refused(RefuseWhy.LOCKED), ReplyPlanner.plan(mom, "hi", ReplySource.DICTATED, on, locked = true))
        assertEquals(ReplyPlan.Refused(RefuseWhy.LOCKED), ReplyPlanner.plan(mom, "hi", ReplySource.DICTATED, on.copy(locked = MessagesLocked.WHO_MESSAGED), locked = true))
        val auth = mom.copy(reply = mom.reply!!.copy(authRequired = true))
        val readReply = on.copy(locked = MessagesLocked.READ_AND_REPLY)
        assertEquals(ReplyPlan.Refused(RefuseWhy.AUTH_REQUIRED), ReplyPlanner.plan(auth, "hi", ReplySource.DICTATED, readReply, locked = true))
        assertTrue(ReplyPlanner.plan(auth, "hi", ReplySource.DICTATED, readReply, locked = false) is ReplyPlan.Send)
        // "Open the chat; I press Send" never sends; no reply action opens the chat too.
        assertEquals(ReplyPlan.HandOff(MessageApp.CHEOGRAM, mom.conversationId, "hi", HandOffWhy.ROUTE_OPEN_CHAT),
            ReplyPlanner.plan(mom, " hi ", ReplySource.DICTATED, on.copy(replyRoute = ReplyRoute.OPEN_CHAT), false))
        assertEquals(HandOffWhy.NO_REPLY_ACTION, (ReplyPlanner.plan(thread(Snapshots.signalNameOnly), "hi", ReplySource.DICTATED, on, false) as ReplyPlan.HandOff).why)
    }

    @Test fun checkedAgainJustBeforeSending() {
        val p = send(ReplyPlanner.plan(mom, "On my way", ReplySource.DICTATED, on, false))
        assertEquals(Recheck.Gone, ReplyGuard.recheck(p, null))
        assertEquals(Recheck.Changed, ReplyGuard.recheck(p, thread(Snapshots.signal)))
        assertEquals(Recheck.Gone, ReplyGuard.recheck(p, mom.copy(reply = null)))
        // The same, with the reply action moved: ready, with the action as it is now.
        val moved = mom.copy(reply = mom.reply!!.copy(index = 3), notificationKey = "new-key")
        val ready = ReplyGuard.recheck(p, moved) as Recheck.Ready
        assertEquals(3, ready.target.action.index)
        assertEquals("new-key", ready.target.notificationKey)
        assertEquals(mom.conversationId, ready.target.conversationId)
        // Mom wrote again while the card waited: the card shows it first.
        val again = thread(Snapshots.cheogram1to1("Are you coming?", "Never mind, I'll call"))
        assertEquals(1, (ReplyGuard.recheck(p, again) as Recheck.NewMessages).count)
        // A group can't turn into a 1:1 under the card, or back.
        assertEquals(Recheck.Changed, ReplyGuard.recheck(p, mom.copy(isGroup = true)))
    }

    @Test fun replyToByName() {
        val threads = listOf(mom, thread(Snapshots.cheogramGroup), thread(Snapshots.signal))
        assertEquals(listOf(mom), ReplyPlanner.candidates("mom", threads))
        assertEquals(listOf("Famille"), ReplyPlanner.candidates("Julie", threads).map { it.name })
        assertTrue(ReplyPlanner.candidates("Bob", threads).isEmpty())
        assertTrue(ReplyPlanner.candidates("", threads).isEmpty())
    }

    @Test fun replyWords() {
        assertEquals("I'm late.", ReplyText.of("that I'm late"))
        assertEquals("Je vais être en retard.", ReplyText.of("que je vais être en retard"))
        assertEquals("Il pleut.", ReplyText.of("qu'il pleut"))
        assertEquals("Il pleut.", ReplyText.of("qu’il pleut"))
        assertEquals("On my way!", ReplyText.of("that on my way!"))
        assertEquals("I'll be late", ReplyText.of("  I'll be   late "))
        assertEquals("that", ReplyText.of("that"))
        assertTrue(ReplyText.emojiOnly("👍"))
        assertTrue(ReplyText.emojiOnly("❤️"))
        assertTrue(ReplyText.emojiOnly("👍🏽 🎉"))
        assertTrue(ReplyText.emojiOnly("👨‍👩‍👧"))
        assertFalse(ReplyText.emojiOnly("ok 👍"))
        assertFalse(ReplyText.emojiOnly(""))
        assertFalse(ReplyText.emojiOnly("   "))
        assertFalse(ReplyText.emojiOnly("123"))
    }

    @Test fun logLinesHoldNoWords() {
        val p = send(ReplyPlanner.plan(mom, "my address is 12 rue X", ReplySource.DICTATED, on, false))
        assertFalse(p.toString().contains("address") || p.toString().contains("Mom"))
        assertFalse(ReplyPlan.HandOff(MessageApp.SIGNAL, "c", "secret plan", HandOffWhy.NO_REPLY_ACTION).toString().contains("secret"))
    }

    @Test fun openTheChatFindsCheogramsChatByItsShortcut() {
        assertEquals("sam@cheogram.com" to "+15145550101@cheogram.com", ChatLinks.cheogram("sam@cheogram.com#+15145550101@cheogram.com"))
        assertEquals("sam@cheogram.com" to "famille@muc.example", ChatLinks.cheogram("sam@cheogram.com#famille@muc.example"))
        // A notification key (quiet hours: no shortcut), Signal's or Messaging's ids: not Cheogram's.
        assertEquals(null, ChatLinks.cheogram("0|com.cheogram.android|1003|null|10123"))
        assertEquals(null, ChatLinks.cheogram("42"))
        assertEquals(null, ChatLinks.cheogram("conv-17"))
        assertEquals(null, ChatLinks.cheogram("#x@y"))
    }
}
