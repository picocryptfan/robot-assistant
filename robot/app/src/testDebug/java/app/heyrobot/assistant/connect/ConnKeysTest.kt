// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.TermuxSecrets
import app.heyrobot.core.RLog
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.sys.SecretKeys
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A connection's key file (connections plan §3, §4.3; C3): every secret part in one file under its own Keystore key
 * (robot_conn_<id>), in files/robot/connections/<id>/, with only masks beside it; read back whole; a file whose plain
 * form no Robot can read is removed as one the Keystore can never read is, one from a newer Robot kept and never used, one
 * the Keystore can't read just now kept. Termux refuses a command holding any part. Nothing is logged but codes. The
 * Android Keystore isn't in Robolectric, so ordinary AES keys stand in for its keys ([KeystoreSecret.keys]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnKeysTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private class FakeKeystore : SecretKeys {
        val held = HashMap<String, SecretKey>()
        var failure: Throwable? = null

        override fun get(alias: String): SecretKey? {
            failure?.let { throw it }
            return held[alias]
        }

        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }

        override fun delete(alias: String) {
            held.remove(alias)
        }
    }

    private val keystore = FakeKeystore()
    private val before = KeystoreSecret.keys
    private val logged = mutableListOf<String>()

    private val token = "zq9TOKEN" + "Ab1_".repeat(10) + "X9z7"
    private val topic = "zq9_family_topic_42"
    private val hook = "https://hooks.example.org/services/T0ZQ9/B0ZQ9/zq9SECRETpathAB12"

    @Before fun up() {
        KeystoreSecret.keys = keystore
        RLog.sink = { synchronized(logged) { logged += it } }
    }

    @After fun down() {
        RLog.sink = null
        for (id in listOf("c-ha0001", "c-nt0001", "c-wh0001")) ConnKeyFiles.removeAll(ctx, id)
        File(ctx.filesDir, "robot/connections.json").delete()
        File(ctx.filesDir, "robot/connections.json.bad").delete()
        KeystoreSecret.keys = before
        for (l in logged) assertFalse(l, l.contains("zq9") || l.contains("hooks.example"))
    }

    @Test fun theSecretsAreSavedEncryptedWithTheirMasksAndReadBackWhole() {
        val s = ConnSecrets(token = token, topic = topic)
        assertTrue(ConnKeys.save(ctx, "c-nt0001", s))
        val dir = File(ctx.filesDir, "robot/connections/c-nt0001")
        assertEquals(dir, ConnKeyFiles.dir(ctx, "c-nt0001"))
        assertTrue(File(dir, "key.bin").isFile && File(dir, "key.json").isFile)
        assertEquals(setOf("robot_conn_c-nt0001"), keystore.held.keys)
        // Neither file holds a secret in the clear; key.json only the masks.
        for (f in dir.listFiles()!!) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.name, text.contains(token) || text.contains(topic) || text.contains("Ab1_Ab1_"))
        }
        assertEquals(mapOf(SecretPart.TOKEN to "…X9z7", SecretPart.TOPIC to "…c_42"), ConnKeyFiles.masks(ctx, "c-nt0001"))
        assertEquals(s, (ConnKeys.read(ctx, "c-nt0001") as ConnKeyRead.Present).secrets)
        assertEquals("Present(…)", ConnKeys.read(ctx, "c-nt0001").toString())
        // None saved: absent, no masks.
        assertEquals(ConnKeyRead.Absent, ConnKeys.read(ctx, "c-ha0001"))
        assertEquals(emptyMap<SecretPart, String>(), ConnKeyFiles.masks(ctx, "c-ha0001"))
    }

    @Test fun onlyValidSecretsAreSaved() {
        assertFalse(ConnKeys.save(ctx, "c-nt0001", ConnSecrets()))
        assertFalse(ConnKeys.save(ctx, "c-nt0001", ConnSecrets(topic = "not a topic")))
        assertFalse(ConnKeys.save(ctx, "c-wh0001", ConnSecrets(address = "http://hooks.example.org/x")))
        assertFalse(ConnKeyFiles.saved(ctx, "c-nt0001") || ConnKeyFiles.saved(ctx, "c-wh0001"))
        // A connection id is checked before any path is made.
        for (bad in listOf("../x", "c-ABCDEF", "helpers", "c-12345")) {
            assertTrue(bad, runCatching { ConnKeyFiles.dir(ctx, bad) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { ConnKeyFiles.alias(bad) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    /** Decrypted, but its plain form no Robot can read: removed, as one the Keystore can never read is, and Settings says so. */
    @Test fun aDamagedPlainFormIsRemoved() {
        assertTrue(ConnKeyFiles.store("c-ha0001").save(ctx, "{\"v\":1,\"token\":", "…"))
        assertEquals(ConnKeyRead.Invalidated("damaged"), ConnKeys.read(ctx, "c-ha0001"))
        assertFalse(ConnKeyFiles.saved(ctx, "c-ha0001"))
        assertTrue(ConnKeyFiles.unreadable(ctx, "c-ha0001"))
        assertFalse("its Keystore key is gone too", "robot_conn_c-ha0001" in keystore.held)
    }

    /**
     * C11, Settings' Start over: a damaged connections.json goes aside and every key it leaves without a connection goes,
     * file and Keystore key; a newer Robot's file stays, with its keys.
     */
    @Test fun startOverDeletesTheKeysLeftWithoutAConnection() {
        assertTrue(ConnKeys.save(ctx, "c-ha0001", ConnSecrets(token = token)))
        assertTrue(ConnKeys.save(ctx, "c-nt0001", ConnSecrets(token = token, topic = topic)))
        assertEquals(setOf("c-ha0001", "c-nt0001"), ConnKeyFiles.ids(ctx))
        val file = File(ctx.filesDir, "robot/connections.json")
        file.writeText("{\"v\":2,\"connections\":[]}")
        assertFalse(app.heyrobot.sys.ConnFiles.startOver(ctx))
        assertTrue(ConnKeyFiles.saved(ctx, "c-ha0001") && ConnKeyFiles.saved(ctx, "c-nt0001"))
        file.writeText("{\"v\":1,\"connections\":[")
        assertTrue(app.heyrobot.sys.ConnFiles.startOver(ctx))
        assertFalse(file.exists())
        assertTrue(File(ctx.filesDir, "robot/connections.json.bad").isFile)
        assertEquals(emptySet<String>(), ConnKeyFiles.ids(ctx))
        assertFalse(ConnKeyFiles.saved(ctx, "c-ha0001") || ConnKeyFiles.saved(ctx, "c-nt0001"))
        assertEquals(emptySet<String>(), keystore.held.keys)
    }

    /** From a newer Robot: kept as it is, never used or removed. */
    @Test fun aNewerKeyFileIsKeptAndNeverUsed() {
        assertTrue(ConnKeyFiles.store("c-ha0001").save(ctx, "{\"v\":2,\"token\":\"x\",\"passkey\":\"y\"}", "…"))
        assertEquals(ConnKeyRead.TooNew(2), ConnKeys.read(ctx, "c-ha0001"))
        assertTrue(ConnKeyFiles.saved(ctx, "c-ha0001"))
        assertEquals(ConnKeyRead.TooNew(2), ConnKeys.read(ctx, "c-ha0001"))
    }

    /** C12's L6: a newer Robot's key file can't go in a backup, and Back up now names its connection as left out. */
    @Test fun aNewerKeyIsNamedInTheBackup() {
        val c = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "zq9 Home Assistant", ConnUrl.parse("https://zq9home.example.org")!!, auth = ConnAuth.Bearer)
        assertTrue(ConnFiles.store(ctx).save(c))
        assertTrue(ConnKeyFiles.store(c.id).save(ctx, "{\"v\":2,\"token\":\"x\",\"passkey\":\"y\"}", "…"))
        assertEquals(ConnKeyBackup.Unavailable, ConnKeys.backup(ctx, c.id))
        val keys = app.heyrobot.assistant.backup.BackupSecrets.connectionKeys(ctx)
        assertTrue(keys.secrets.isEmpty())
        assertEquals(listOf("zq9 Home Assistant"), keys.unreadable)
        // Kept as it is.
        assertTrue(ConnKeyFiles.saved(ctx, c.id))
    }

    /** The Keystore busy: kept, file and key, and read on the next try; its key gone for good: removed. */
    @Test fun aKeystoreFailureKeepsOrRemovesAsHelperKeysDo() {
        assertTrue(ConnKeys.save(ctx, "c-ha0001", ConnSecrets(token = token)))
        keystore.failure = KeyStoreException("busy")
        assertTrue(ConnKeys.read(ctx, "c-ha0001") is ConnKeyRead.Unavailable)
        assertTrue(ConnKeyFiles.saved(ctx, "c-ha0001"))
        keystore.failure = null
        assertEquals(ConnSecrets(token = token), (ConnKeys.read(ctx, "c-ha0001") as ConnKeyRead.Present).secrets)
        keystore.held.clear()
        assertTrue(ConnKeys.read(ctx, "c-ha0001") is ConnKeyRead.Invalidated)
        assertFalse(ConnKeyFiles.saved(ctx, "c-ha0001"))
    }

    /** Remove connection: the key file, its Keystore key and the folder go. */
    @Test fun removingAConnectionShredsItsKey() {
        assertTrue(ConnKeys.save(ctx, "c-ha0001", ConnSecrets(token = token)))
        ConnKeyFiles.removeAll(ctx, "c-ha0001")
        assertFalse(ConnKeyFiles.dir(ctx, "c-ha0001").exists())
        assertTrue(keystore.held.isEmpty())
        assertEquals(ConnKeyRead.Absent, ConnKeys.read(ctx, "c-ha0001"))
    }

    /** A Termux command holding any part of a connection's key is refused: the token, the topic, a secret address and its path. */
    @Test fun termuxKnowsEveryPartOfEveryConnectionsKey() {
        val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer)
        val ntfy = Connection("c-nt0001", ConnKind.NTFY, "ntfy", ConnUrl.parse("https://ntfy.example.org")!!)
        val webhook = Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://hooks.example.org")!!, secretAddress = true)
        for (c in listOf(ha, ntfy, webhook)) assertTrue(ConnFiles.store(ctx).save(c))
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        assertTrue(ConnKeys.save(ctx, ntfy.id, ConnSecrets(topic = topic)))
        assertTrue(ConnKeys.save(ctx, webhook.id, ConnSecrets(address = hook)))
        val own = TermuxSecrets.own(ctx)
        for (v in listOf(token, topic, hook, "/services/T0ZQ9/B0ZQ9/zq9SECRETpathAB12")) assertTrue(v, v in own)
        // A key that can't be read just now is left out (it is still refused by its shape).
        keystore.failure = KeyStoreException("busy")
        assertFalse(token in TermuxSecrets.own(ctx))
    }
}
