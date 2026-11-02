// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnWindow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The connection window (connections plan §4.3, §5.6, §6.3; C3): opened by the class name the main app uses, it shows
 * only for a connection that exists and has something to paste (or, for Test, a request of its kind), is FLAG_SECURE and
 * out of autofill, and closes while the phone is locked, as it opens and when it comes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionWindowTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer)
    private val hook = Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("http://192.168.1.5:5678")!!)

    @Before fun up() {
        assertTrue(ConnFiles.store(ctx).save(ha) && ConnFiles.store(ctx).save(hook))
    }

    @After fun down() {
        File(ctx.filesDir, "robot/connections.json").delete()
        shadowOf(ctx.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @Test fun onlyARoutingServersTestWaitsForYourTap() {
        assertTrue(ConnTest.asksFirst(Connection("c-rt0001", ConnKind.ROUTING, "OSRM", ConnUrl.parse("https://osrm.example.org")!!, routing = app.heyrobot.core.connect.RoutingKind.OSRM)))
        assertFalse(ConnTest.asksFirst(ha))
        assertFalse(ConnTest.asksFirst(hook))
    }

    private fun open(id: String?, mode: String) = Robolectric.buildActivity(ConnectionWindow::class.java,
        Intent().setClassName(ctx, ConnWindow.CLASS).putExtra(ConnWindow.EXTRA_CONN, id).putExtra(ConnWindow.EXTRA_MODE, mode)).setup()

    @Test fun theMainAppsClassNameIsTheWindow() {
        assertEquals(ConnectionWindow::class.java.name, ConnWindow.CLASS)
    }

    @Test fun itShowsForAKeyToPasteSecurelyAndOutOfAutofill() {
        val w = open(ha.id, ConnWindow.MODE_KEY).get()
        assertFalse(w.isFinishing)
        assertTrue(w.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, w.window.decorView.importantForAutofill)
    }

    @Test fun itClosesWhenThereIsNothingToDo() {
        // No such connection, or none named.
        assertTrue(open("c-zzzzzz", ConnWindow.MODE_KEY).get().isFinishing)
        assertTrue(open(null, ConnWindow.MODE_TEST).get().isFinishing)
        // A connection with no secret has no key to paste; a hand-off has no Test here (C7 checks its app).
        assertTrue(open(hook.id, ConnWindow.MODE_KEY).get().isFinishing)
        val handOff = Connection("c-app001", ConnKind.HAND_OFF, "Signal", handOff = app.heyrobot.core.connect.HandOffApp("org.example.app", "a".repeat(64)))
        assertTrue(ConnFiles.store(ctx).save(handOff))
        assertTrue(open(handOff.id, ConnWindow.MODE_TEST).get().isFinishing)
    }

    /** §5.6: keys are never pasted while the phone is locked: it closes as it opens, and when it comes back locked. */
    @Test fun itClosesWhileLocked() {
        val km = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        assertTrue(open(ha.id, ConnWindow.MODE_KEY).get().isFinishing)
        km.setKeyguardLocked(false)
        val c = open(ha.id, ConnWindow.MODE_KEY)
        assertFalse(c.get().isFinishing)
        km.setKeyguardLocked(true)
        c.pause().resume()
        assertTrue(c.get().isFinishing)
    }
}
