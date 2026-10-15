// SPDX-License-Identifier: GPL-3.0-only
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
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.ListenerEvent
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
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.sys.MessagesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
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

    /** What "Android" says of each package: its signer, and its last update time. */
    private val signers = HashMap<String, String>()
    private val updated = HashMap<String, Long>()
    private val fdroid = "c".repeat(64)
    private val lookalike = "e".repeat(64)

    private val fake = object : SignerSource {
        override fun now(pkg: String) = signers[pkg]?.let { SignerNow(setOf(it), "org.fdroid.fdroid") }
        override fun stillSigns(pkg: String, pin: AppPin) = false
        override fun version(pkg: String) = updated[pkg] ?: signers[pkg]?.let { 1L }
    }

    @Before fun fresh() {
        MessageTap.buffer.clear()
        MessagesStore.file(ctx).delete()
        MessagesStore.reload(ctx)
        signers.clear()
        updated.clear()
        signers["com.cheogram.android"] = fdroid
        MessageAppSigners.source = { fake }
        // A voice log of this test's own: no other test's events in it.
        VoiceLog.resetForTests()
    }

    @After fun phone() {
        MessageAppSigners.source = { AndroidSigners(it.applicationContext) }
    }

    /** As Settings › Messages turns them on: each app's installed packages pinned to their signers now. */
    private fun turnOn(apps: Set<MessageApp> = setOf(MessageApp.CHEOGRAM)) =
        MessagesStore.save(ctx, MessagesFile().turnedOn(apps, apps.flatMap { MessageAppSigners.pinsNow(ctx, it).entries }.associate { it.toPair() }))

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

    /**
     * 0.7 after the review: a package is read only while it is signed as when you turned its app on. A look-alike under
     * Cheogram's package name, signed by someone else, leaves nothing in RAM; so does a build installed after you turned
     * the app on (Cheogram's Play build here) until you turn it on again.
     */
    @Test fun onlyThePackageSignedAsWhenYouTurnedItOn() {
        turnOn()
        assertEquals(setOf("com.cheogram.android"), MessagesStore.current(ctx).pins.keys)
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        MessageTap.removed(cheogram())
        // Uninstalled, and something else installed under the same name: another key.
        signers["com.cheogram.android"] = lookalike
        updated["com.cheogram.android"] = 2L
        MessageTap.posted(ctx, cheogram())
        assertEquals(0, MessageTap.buffer.size)
        assertTrue(MessageAppSigners.problems(ctx, MessagesStore.current(ctx)).single().startsWith("Cheogram changed its signing key"))
        // The Play build, installed after Cheogram was turned on: not pinned, not read.
        signers["com.cheogram.android.playstore"] = fdroid
        MessageTap.posted(ctx, cheogram("com.cheogram.android.playstore"))
        assertEquals(0, MessageTap.buffer.size)
        // Turned off and on again: what is installed now is trusted, each build pinned apart.
        MessagesStore.update(ctx) { it.withApp(MessageApp.CHEOGRAM, false) }
        turnOn()
        assertEquals(setOf("com.cheogram.android", "com.cheogram.android.playstore"), MessagesStore.current(ctx).pins.keys)
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        MessageTap.removed(cheogram())
        MessageTap.posted(ctx, cheogram("com.cheogram.android.playstore"))
        assertTrue(MessageTap.buffer.threads(System.currentTimeMillis()).single().notificationKey.contains("com.cheogram.android.playstore"))
        assertTrue(MessageAppSigners.problems(ctx, MessagesStore.current(ctx)).isEmpty())
    }

    /** A messages.json from before the pins: an app that was on is pinned at its first notification, with a code in the voice log. */
    @Test fun anAppOnBeforeThePinsIsPinnedAtFirstUse() {
        MessagesStore.file(ctx).apply { parentFile?.mkdirs(); writeText("""{"v":1,"on":true,"apps":["CHEOGRAM"]}""") }
        MessagesStore.reload(ctx)
        val before = VoiceLog.total
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        val f = MessagesStore.current(ctx)
        assertEquals(setOf(fdroid), f.pins["com.cheogram.android"]?.digests)
        assertFalse("com.cheogram.android" in f.firstUse)
        assertTrue(VoiceLog.entries().take((VoiceLog.total - before).toInt()).any { it.event == VoiceEvent.MSG_LISTENER && it.a == ListenerEvent.PINNED_FIRST_USE.id })
        MessagesStore.awaitWrites()
        assertEquals(f.pins, MessagesFile.fromJson(MessagesStore.file(ctx).readText()).pins)
        // From then on, another key is refused.
        MessageTap.removed(cheogram())
        signers["com.cheogram.android"] = lookalike
        updated["com.cheogram.android"] = 2L
        MessageTap.posted(ctx, cheogram())
        assertEquals(0, MessageTap.buffer.size)
    }

    /** Turned off, or notification access taken back: nothing held stays in the main process. */
    @Test fun turningItOffOrLosingAccessDropsEverything() {
        turnOn()
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        MessagesStore.update(ctx) { it.copy(settings = it.settings.copy(on = false)) }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(0, MessageTap.buffer.size)
        turnOn()
        MessageTap.posted(ctx, cheogram())
        assertEquals(1, MessageTap.buffer.size)
        // The listener unbound with access gone (Robolectric's NotificationManager grants none).
        val l = org.robolectric.Robolectric.buildService(CheogramListener::class.java).get()
        MessageTap.disconnected(l)
        assertEquals(0, MessageTap.buffer.size)
    }
}
