// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.sys.SecretKeys
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C12's L1, the connection window's half: a key is saved only for the connection the window showed, read again. One
 * removed or given another address in Settings while the window was open gets none, and a removed webhook is never
 * added back by the address pasted for it. The Android Keystore isn't in Robolectric, so ordinary AES keys stand in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionWindowSaveTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val before = KeystoreSecret.keys

    private val keystore = object : SecretKeys {
        val held = HashMap<String, SecretKey>()
        override fun get(alias: String): SecretKey? = held[alias]
        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }
        override fun delete(alias: String) { held.remove(alias) }
    }

    private val token = "zq9TOKEN" + "Ab1_".repeat(10) + "X9z7"
    private val address = "https://hooks.example.org/services/T0ZQ9/B0ZQ9/zq9SECRETpathAB12"
    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "zq9 Home Assistant", ConnUrl.parse("https://zq9home.example.org")!!, auth = ConnAuth.Bearer)
    private val hook = Connection("c-wh0001", ConnKind.WEBHOOK, "zq9 Hook", ConnUrl.parse("https://hooks.example.org")!!, secretAddress = true)

    @Before fun up() {
        KeystoreSecret.keys = keystore
    }

    @After fun down() {
        for (id in listOf(ha.id, hook.id)) ConnKeyFiles.removeAll(ctx, id)
        File(ctx.filesDir, "robot/connections.json").delete()
        KeystoreSecret.keys = before
    }

    @Test fun theKeyGoesToTheConnectionTheWindowShowed() {
        assertTrue(ConnFiles.store(ctx).save(ha))
        assertEquals(ha, ConnectionWindow.saveKey(ctx, ha, mapOf(SecretPart.TOKEN to token), newHook = false))
        assertTrue(ConnKeyFiles.saved(ctx, ha.id))
    }

    @Test fun aConnectionRemovedWhileTheWindowWasOpenGetsNoKey() {
        assertNull(ConnectionWindow.saveKey(ctx, ha, mapOf(SecretPart.TOKEN to token), newHook = false))
        assertFalse(ConnKeyFiles.saved(ctx, ha.id))
        // A webhook whose address is the key: the pasted address never brings it back.
        assertNull(ConnectionWindow.saveKey(ctx, hook, mapOf(SecretPart.ADDRESS to address), newHook = false))
        assertTrue(ConnFiles.store(ctx).connections().isEmpty())
        assertFalse(ConnKeyFiles.saved(ctx, hook.id))
        assertTrue(keystore.held.isEmpty())
    }

    @Test fun aConnectionReaddressedWhileTheWindowWasOpenGetsNoKey() {
        assertTrue(ConnFiles.store(ctx).save(ha.copy(address = ConnUrl.parse("https://zq9elsewhere.example.org")!!)))
        assertNull(ConnectionWindow.saveKey(ctx, ha, mapOf(SecretPart.TOKEN to token), newHook = false))
        assertFalse(ConnKeyFiles.saved(ctx, ha.id))
        assertTrue(keystore.held.isEmpty())
    }

    @Test fun aNewWebhookKeepsItsAddressesMoneyHintNotItsPath() {
        val new = Connection(hook.id, ConnKind.WEBHOOK, "zq9 Hook", secretAddress = true)
        val typed = "https://hooks.example.org/pay/abcdefghijklmnop1234"
        val saved = ConnectionWindow.saveKey(ctx, new, mapOf(SecretPart.ADDRESS to typed), newHook = true)!!
        assertEquals(setOf(app.heyrobot.core.skills.SkillEffect.MONEY), saved.addressMarks)
        assertEquals("https://hooks.example.org", ConnFiles.store(ctx).get(new.id)!!.address!!.text)
        assertEquals(saved.addressMarks, ConnFiles.store(ctx).get(new.id)!!.addressMarks)
    }

    @Test fun aNewWebhookJoinsWithItsAddressesOrigin() {
        val new = Connection(hook.id, ConnKind.WEBHOOK, "zq9 Hook", secretAddress = true)
        val saved = ConnectionWindow.saveKey(ctx, new, mapOf(SecretPart.ADDRESS to address), newHook = true)!!
        assertEquals("https://hooks.example.org", saved.address!!.text)
        assertEquals(listOf(saved), ConnFiles.store(ctx).connections())
        assertTrue(ConnKeyFiles.saved(ctx, new.id))
    }
}
