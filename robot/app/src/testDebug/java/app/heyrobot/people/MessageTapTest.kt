package app.heyrobot.people

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.NotificationParsing
import app.heyrobot.core.messages.Parsed
import app.heyrobot.core.messages.ReplyCheck
import app.heyrobot.core.messages.ReplyOutcome
import app.heyrobot.core.messages.ReplyRequest
import app.heyrobot.core.messages.ReplySend
import app.heyrobot.sys.MessagesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 0.7, the main process's copy of a notification (design §B2–§B5) with Android's own classes: a Cheogram-shaped
 * MessagingStyle notification becomes core's snapshot field by field and parses into the conversation; an app that isn't
 * turned on, or "Read my messages" off, leaves nothing in RAM; and a reply fires the app's own action with your words under
 * its RemoteInput key, marked as typed, after core ReplySend's check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageTapTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val t0 = 1_760_191_320_000L

    @Before fun fresh() {
        MessageTap.buffer.clear()
        MessagesStore.file(ctx).delete()
        MessagesStore.reload(ctx)
    }

    private fun turnOn(apps: Set<MessageApp> = setOf(MessageApp.CHEOGRAM)) =
        MessagesStore.save(ctx, MessagesFile(MessageSettings(on = true, allowlist = MessageAllowlist(apps))))

    private fun replyIntent(): PendingIntent = PendingIntent.getService(ctx, 1, Intent("eu.siacs.conversations.action.REPLY_TO_CONVERSATION")
        .setClassName(ctx.packageName, "example.CheogramService"), PendingIntent.FLAG_MUTABLE)

    /** As Cheogram posts a 1:1 chat: MessagingStyle with you as the user, its reply action (no UI) and mark-as-read. */
    private fun cheogram(pkg: String = "com.cheogram.android", vararg texts: String = arrayOf("Tu viens souper?")): StatusBarNotification {
        val me = Person.Builder().setName("Me").setKey("sam@cheogram.com").build()
        val mom = Person.Builder().setName("Mom Martin").setKey("+15145550101@cheogram.com").build()
        val style = Notification.MessagingStyle(me)
        texts.forEachIndexed { i, s -> style.addMessage(Notification.MessagingStyle.Message(s, t0 + i * 60_000L, mom)) }
        val reply = Notification.Action.Builder(null, "Reply", replyIntent())
            .addRemoteInput(RemoteInput.Builder("text_reply").setLabel("Reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build()
        reply.extras.putBoolean("android.support.action.showsUserInterface", false)
        val read = Notification.Action.Builder(null, "Mark as read", replyIntent()).setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
        val n = Notification.Builder(ctx, "messages").setSmallIcon(android.R.drawable.sym_def_app_icon).setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE).setShortcutId("sam@cheogram.com#+15145550101@cheogram.com")
            .addAction(reply).addAction(read).build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(pkg, pkg, 1001, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), t0 + texts.size * 60_000L)
    }

    @Test fun androidsNotificationBecomesTheSnapshotAndTheConversation() {
        val snap = MessageTap.snapshot(cheogram())
        assertEquals("com.cheogram.android", snap.packageName)
        assertEquals(Notification.CATEGORY_MESSAGE, snap.category)
        assertEquals("sam@cheogram.com#+15145550101@cheogram.com", snap.shortcutId)
        assertEquals("sam@cheogram.com", snap.user?.key)
        assertEquals("Tu viens souper?", snap.messages.single()?.text)
        assertEquals("+15145550101@cheogram.com", snap.messages.single()?.sender?.key)
        assertEquals(listOf("Reply", "Mark as read"), snap.actions.map { it?.title })
        assertEquals("text_reply", snap.actions[0]!!.remoteInputs.single().resultKey)
        assertFalse(snap.actions[0]!!.showsUserInterface)
        val t = (NotificationParsing.parse(snap, MessageAllowlist(setOf(MessageApp.CHEOGRAM))) as Parsed.Thread).thread
        assertEquals("Mom Martin", t.name)
        assertEquals(0, t.reply!!.index)
        assertEquals(1, t.markRead!!.index)
        // Nothing of it in the snapshot's own words.
        assertFalse(snap.toString().contains("souper"))
    }

    @Test fun onlyAnAppYouTurnedOnAndOnlyWhileOn() {
        MessageTap.posted(ctx, cheogram())
        assertEquals(0, MessageTap.buffer.size)
        turnOn(setOf(MessageApp.SIGNAL))
        MessageTap.posted(ctx, cheogram())
        assertEquals(0, MessageTap.buffer.size)
        turnOn()
        MessageTap.posted(ctx, cheogram("com.example.lookalike"))
        assertEquals(0, MessageTap.buffer.size)
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        // Its notification goes: so does the conversation (Keep messages for: until the notification goes).
        MessageTap.removed(cheogram())
        assertEquals(0, MessageTap.buffer.size)
    }

    @Test fun aReplyFiresTheAppsOwnActionWithYourWords() {
        turnOn()
        val sbn = cheogram()
        MessageTap.posted(ctx, sbn)
        val held = MessageTap.buffer.threads(System.currentTimeMillis()).single()
        val fresh = (NotificationParsing.parse(MessageTap.snapshot(sbn), MessageAllowlist(setOf(MessageApp.CHEOGRAM))) as Parsed.Thread).thread
        val check = ReplySend.check(held, fresh, ReplyRequest(held.key, "J'arrive!", held.newestIncoming), MessagesStore.current(ctx).settings, locked = false)
        val fire = check as ReplyCheck.Fire
        assertEquals(ReplyOutcome.SENT, MessageTap.fire(ctx, sbn, fire.target, fire.text))
        val sent = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService
        assertEquals("eu.siacs.conversations.action.REPLY_TO_CONVERSATION", sent.action)
        assertEquals("J'arrive!", RemoteInput.getResultsFromIntent(sent)?.getCharSequence("text_reply")?.toString())
        assertEquals(RemoteInput.SOURCE_FREE_FORM_INPUT, RemoteInput.getResultsSource(sent))
        // An action that isn't there any more: never fired, the chat opens instead.
        assertEquals(ReplyOutcome.GONE, MessageTap.fire(ctx, sbn, fire.target.copy(action = fire.target.action.copy(index = 5)), "x"))
        assertNull(shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedService)
        assertTrue(MessageTap.buffer.size == 1)
    }
}
