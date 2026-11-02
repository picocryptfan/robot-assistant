// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.LinkCommand
import app.heyrobot.core.link.LinkIpcProtocol
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.LinkClient
import app.heyrobot.sys.LinkSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The way in to Robot to Robot from the panel and the notification: the lock, a file ask, a closed client, a failed tap. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class LinkDoorTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        PanelLinkAsk.forget()
        synchronized(LinkNotices.held) { LinkNotices.held.clear() }
    }

    @After fun off() {
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        LinkSettingsStore.update(ctx) { it.copy(on = false) }
        PanelLinkAsk.forget()
    }

    /** F16: on the keyguard the words are ordinary talk, whoever is named: nothing says who is paired. */
    @Test fun aLockedRouteIsNull() {
        LinkSettingsStore.update(ctx) { it.copy(on = true) }
        PanelLinkAsk.known = PanelLinkAsk.Known(System.currentTimeMillis(), emptyList(), emptyList())
        val q = "ask Zed's robot when Zed is free Friday"
        assertEquals(LinkCommand.NotPaired("Zed", Lang.EN), PanelLinkAsk.route(ctx, q))
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertNull(PanelLinkAsk.route(ctx, q))
        assertNull(PanelLinkAsk.route(ctx, "stop answering Zed on your own"))
    }

    /** F163: "Stop answering" reads who's paired afresh: the minute-old copy is never what it acts on. */
    @Test fun stopAnsweringNeverUsesTheCopy() {
        shadowOf(app).declareComponentUnbindable(ComponentName(ctx.packageName, LinkIpcProtocol.SERVICE_CLASS))
        PanelLinkAsk.known = PanelLinkAsk.Known(System.currentTimeMillis(), emptyList(), emptyList())
        val said = PanelLinkAsk.stopAlone(ctx, LinkCommand.StopAlone("Zed", Lang.EN))
        assertNull(PanelLinkAsk.known)
        val l = AppLanguage.langs(ctx)
        assertEquals(LinkStrings.linkOff().render(l), said.render(l))
    }

    /** F28: "Send a file" has to pick a file, so neither the panel nor the notification answers the card by itself. */
    @Test fun aFileAskGoesToRequests() {
        assertTrue(PanelLink.opensRequests(DeskYes.SEND_FILE))
        assertTrue(PanelLink.opensRequests(null))
        assertFalse(PanelLink.opensRequests(DeskYes.ACCEPT))
        assertNull(LinkNotices.buttonYes(DeskYes.SEND_FILE))
        assertEquals(DeskYes.ACCEPT, LinkNotices.buttonYes(DeskYes.ACCEPT))
    }

    /** F29: a late call on a closed client binds nothing nobody would close. */
    @Test fun aClosedClientDoesNotBindAgain() {
        val c = LinkClient(ctx)
        c.close()
        assertFalse(c.connect(1))
        assertNull(c.status())
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    /** F162: a failed tap says so and leaves the button working; only an answer that went spends it. */
    @Test fun aFailedTapSaysSoAndASecondTapStillWorks() {
        synchronized(LinkNotices.held) { LinkNotices.held["t"] = LinkNotices.Held("card", DeskChoice.DECLINE, System.currentTimeMillis() + 60_000) }
        assertFalse(LinkNotices.tap(ctx, "t") { _, _ -> false })
        assertTrue(synchronized(LinkNotices.held) { "t" in LinkNotices.held })
        val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
        val posted = assertNotNull_(nm.allNotifications.singleOrNull())
        assertEquals(LinkStrings.couldntAnswer().render(AppLanguage.langs(ctx)), posted.extras.getString(Notification.EXTRA_TITLE))
        var asked: Pair<String, DeskChoice>? = null
        assertTrue(LinkNotices.tap(ctx, "t") { card, choice -> asked = card to choice; true })
        assertEquals("card" to DeskChoice.DECLINE, asked)
        assertFalse(synchronized(LinkNotices.held) { "t" in LinkNotices.held })
        assertTrue(nm.allNotifications.isEmpty())
        assertFalse(LinkNotices.tap(ctx, "t") { _, _ -> true })
    }

    private fun <T : Any> assertNotNull_(v: T?): T { assertNotNull(v); return v!! }
}
