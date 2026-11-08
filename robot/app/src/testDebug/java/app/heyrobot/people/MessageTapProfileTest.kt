// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.messages.AppPin
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.SignerNow
import app.heyrobot.sys.MessagesStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNotificationListenerService

/**
 * fix-what-did-i-miss-c: a work profile's notification is that profile's. The same allowed, trusted app's conversation
 * posted under user 10 and under Robot's own user goes through each way in (the listener connecting and reading the
 * shade, a notification posted, one read again by its key): only Robot's own user's copy is ever kept or returned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageTapProfileTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val t0 = 1_760_191_320_000L
    private val pkg = "com.cheogram.android"

    /** User 10, the usual work profile: the same app's uid there (UserHandle.of is hidden from apps). */
    private val workUid = 10 * 100_000 + Process.myUid() % 100_000
    private val work: UserHandle = UserHandle.getUserHandleForUid(workUid)

    private val fake = object : SignerSource {
        override fun now(pkg: String) = if (pkg == this@MessageTapProfileTest.pkg) SignerNow(setOf("c".repeat(64)), "org.fdroid.fdroid") else null
        override fun stillSigns(pkg: String, pin: AppPin) = false
        override fun version(pkg: String) = if (pkg == this@MessageTapProfileTest.pkg) 1L else null
    }

    private var listener: CheogramListener? = null

    @Before fun fresh() {
        MessageTap.buffer.clear()
        MessagesStore.file(ctx).delete()
        MessagesStore.reload(ctx)
        MessageAppSigners.source = { fake }
        VoiceLog.resetForTests()
        // Cheogram on and pinned, as Settings › Messages turns it on.
        val apps = setOf(MessageApp.CHEOGRAM)
        MessagesStore.save(ctx, MessagesFile().turnedOn(apps, apps.flatMap { MessageAppSigners.pinsNow(ctx, it).entries }.associate { it.toPair() }))
    }

    @After fun phone() {
        listener?.let { MessageTap.disconnected(it) }
        MessageTap.buffer.clear()
        MessageAppSigners.source = { AndroidSigners(it.applicationContext) }
    }

    /** Cheogram's 1:1 chat, posted under [user] (its uid there). */
    private fun chat(user: UserHandle, uid: Int): StatusBarNotification {
        val me = Person.Builder().setName("Me").setKey("sam@cheogram.com").build()
        val mom = Person.Builder().setName("Mom Martin").setKey("+15145550101@cheogram.com").build()
        val style = Notification.MessagingStyle(me).addMessage(Notification.MessagingStyle.Message("Tu viens souper?", t0, mom))
        val n = Notification.Builder(ctx, "messages").setSmallIcon(android.R.drawable.sym_def_app_icon).setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE).setShortcutId("sam@cheogram.com#+15145550101@cheogram.com").build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(pkg, pkg, 1001, null, uid, 0, 0, n, user, t0 + 60_000L)
    }

    private fun workChat() = chat(work, workUid)
    private fun ownChat() = chat(Process.myUserHandle(), Process.myUid())

    /** The listener bound, with [active] in the shade. */
    private fun bound(vararg active: StatusBarNotification): CheogramListener {
        val l = Robolectric.buildService(CheogramListener::class.java).get()
        val shade = Shadow.extract<ShadowNotificationListenerService>(l)
        active.forEach { shade.addActiveNotification(it) }
        listener = l
        return l
    }

    @Test fun postedAWorkProfilesCopyIsNeverKept() {
        MessageTap.posted(ctx, workChat())
        assertEquals(0, MessageTap.buffer.size)
        MessageTap.posted(ctx, ownChat())
        assertEquals(1, MessageTap.buffer.size)
    }

    @Test fun connectedTheShadeIsReadWithoutTheWorkProfilesCopy() {
        val own = ownChat()
        // The work copy last: it would take the conversation's place if it were read.
        MessageTap.connected(bound(own, workChat()))
        assertEquals(1, MessageTap.buffer.size)
        assertEquals(own.key, MessageTap.buffer.threads(t0 + 60_000L).single().notificationKey)
    }

    @Test fun connectedWithOnlyTheWorkProfilesCopyNothingIsKept() {
        MessageTap.connected(bound(workChat()))
        assertEquals(0, MessageTap.buffer.size)
    }

    @Test fun readAgainAWorkProfilesCopyIsNotReturned() {
        val w = workChat()
        val own = ownChat()
        MessageTap.connected(bound(w, own))
        assertNull(MessageTap.fresh(ctx, w.key))
        assertNotNull(MessageTap.fresh(ctx, own.key))
    }
}
