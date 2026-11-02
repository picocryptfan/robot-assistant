// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.connect.ConnKeyRead
import app.heyrobot.assistant.connect.ConnKeys
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.PlanSkip
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.core.backup.RestoreResult
import app.heyrobot.core.backup.VerifiedBackup
import app.heyrobot.core.connect.AppPinState
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnSetup
import app.heyrobot.core.connect.ConnState
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.HandOffApp
import app.heyrobot.sys.BackupFiles
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.sys.Paths
import app.heyrobot.sys.RestoreFiles
import app.heyrobot.sys.SecretKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.heyrobot.core.connect.ConnectionCodec
import org.junit.Assert.assertThrows
import app.heyrobot.core.backup.CantFinish
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Skills connections in a backup on the app's side (connections plan D7, §5.7; C10), Robolectric: Back up now carries
 * connections.json and, with Keys and private codes ticked, each connection's key decrypted in :voice straight into the
 * backup; a restore from your own backup brings both back, each key under a fresh Keystore key; from someone else's, the
 * connections arrive untrusted (no first-use answers, no spoken yes, "Test it again", a hand-off to pick again) and no key
 * comes; Replace deletes this phone's key where a connection goes or now reaches another address before writing the file.
 * Nothing of a key, an address or a name is logged. The Android Keystore isn't in Robolectric, so ordinary AES keys stand in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupConnectionsRoundTripTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pass = "correct-horse-battery-staple-orbit"
    private val t0 = 1_760_270_000_000L
    private val before = KeystoreSecret.keys
    private val logged = mutableListOf<String>()

    private class FakeKeystore : SecretKeys {
        val held = HashMap<String, SecretKey>()
        override fun get(alias: String): SecretKey? = held[alias]
        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }
        override fun delete(alias: String) {
            held.remove(alias)
        }
    }

    private var keystore = FakeKeystore()

    private val ha = Connection("c-ha1234", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.tail1234.ts.net")!!,
        auth = ConnAuth.Bearer, where = ConnWhere.HOME, answers = mapOf("https://home.tail1234.ts.net" to ConsentAnswer.ALLOW),
        tested = ConnTested(t0, "2026.10.1"))
    private val ntfy = Connection("c-ntfy01", ConnKind.NTFY, "Jarvis's ntfy", ConnUrl.parse("https://ntfy.example.org")!!, auth = ConnAuth.Bearer,
        perMinute = 60)
    private val signal = Connection("c-app001", ConnKind.HAND_OFF, "Signal", handOff = HandOffApp("org.thoughtcrime.securesms", "a".repeat(64)))
    private val haKey = ConnSecrets(token = "zq9marker-ha-token-" + "Ab1_".repeat(8))
    private val ntfyKey = ConnSecrets(token = "tk_zq9markerntfytoken01", topic = "zq9marker_family_AB12")

    @Before fun up() {
        VoiceLog.resetForTests()
        KeystoreSecret.keys = keystore
        RLog.sink = { synchronized(logged) { logged += it } }
        ShadowStatFs.registerStats(ctx.filesDir, 1_000_000, 1_000_000, 1_000_000)
        File(Paths.dir(ctx), "settings.json").writeText(RobotSettings().toJson().toString())
    }

    @After fun down() {
        KeystoreSecret.keys = before
        RLog.sink = null
        ctx.filesDir.deleteRecursively()
    }

    private fun haveConnections(vararg cs: Connection) {
        for (c in cs) assertTrue(c.id, ConnFiles.store(ctx).save(c))
    }

    private fun backup(chosen: Set<BackupCategory>): File {
        val out = File(ctx.cacheDir, "connections.robotbackup")
        val r = BackupJob(ctx, chosen, recordings = false, pictures = true).run(Uri.fromFile(out), pass) {}
        assertTrue(r.toString(), r is BackupJob.Outcome.Done)
        return out
    }

    /** A new phone: no connections, no key files, and a Keystore of its own. */
    private fun newPhone() {
        File(Paths.dir(ctx), "connections.json").delete()
        File(Paths.dir(ctx), "connections").deleteRecursively()
        keystore = FakeKeystore()
        KeystoreSecret.keys = keystore
    }

    private fun restore(file: File, mine: Boolean, keys: Boolean, replace: Boolean = false): Pair<RestoreResult, VerifiedBackup> {
        val s = RestoreSession(ctx)
        assertNull(s.copyIn(Uri.fromFile(file)) {})
        assertNull(s.unlock(pass))
        assertNull(s.verify {})
        val v = s.verified!!
        val tick = if (keys) BackupCategory.covers(BackupCategory.KEYS).associateWith { CategoryChoice() } else emptyMap()
        val plan = RestorePlanner.plan(v, s.phone(), RestorePlanner.defaults(v) + tick + (BackupCategory.CONNECTIONS to CategoryChoice(replace = replace)), mine)
        val r = s.apply(plan) {}
        s.close()
        return r to v
    }

    private fun key(id: String): ConnSecrets? = (ConnKeys.read(ctx, id) as? ConnKeyRead.Present)?.secrets

    private fun noSecretLogged() {
        val all = synchronized(logged) { logged.joinToString("\n") }
        for (s in listOf("zq9marker", "tail1234", "ntfy.example.org", "Jarvis", "thoughtcrime")) assertFalse(s, all.contains(s))
    }

    @Test fun yourConnectionsAndTheirKeysComeBackOnANewPhone() {
        haveConnections(ha, ntfy, signal)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        assertTrue(ConnKeys.save(ctx, ntfy.id, ntfyKey))
        // Back up now's Keys row names them; Connection keys goes in with its tick.
        val survey = BackupSources.survey(ctx, RobotSettings())
        assertEquals(listOf("Home Assistant", "Jarvis's ntfy"), survey.getValue(BackupCategory.KEYS).names)
        assertEquals(3, survey.getValue(BackupCategory.CONNECTIONS).count)
        assertEquals(2, survey.getValue(BackupCategory.CONN_KEYS).count)
        val file = backup(setOf(BackupCategory.CONNECTIONS) + BackupCategory.covers(BackupCategory.KEYS))
        assertTrue(BackupFiles.state(ctx).lastKeys)
        // The file is encrypted whole: nothing of a key or an address in its bytes.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        for (s in listOf("zq9marker", "tail1234", "ntfy.example.org")) assertFalse(s, raw.contains(s))

        newPhone()
        val (r, v) = restore(file, mine = true, keys = true)
        assertEquals(setOf(BackupCategory.CONNECTIONS, BackupCategory.CONN_KEYS), v.found.keys)
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.CONNECTIONS])
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.CONN_KEYS])
        // As saved: answers, Test, pins, switches.
        assertEquals(listOf(ha, ntfy, signal), ConnFiles.store(ctx).connections())
        // Each key back, under a fresh key of this phone's Keystore.
        assertEquals(haKey, key(ha.id))
        assertEquals(ntfyKey, key(ntfy.id))
        assertEquals(setOf("robot_conn_c-ha1234", "robot_conn_c-ntfy01"), keystore.held.keys)
        assertEquals(mapOf(app.heyrobot.core.connect.SecretPart.TOKEN to haKey.masks().getValue(app.heyrobot.core.connect.SecretPart.TOKEN)),
            ConnKeyFiles.masks(ctx, ha.id))
        assertTrue(RestoreFiles.journal(ctx).read().isEmpty())
        // A second restore adds nothing and replaces no key.
        val (again, _) = restore(file, mine = true, keys = true)
        assertEquals(CategoryOutcome.Done, again.outcomes[BackupCategory.CONNECTIONS])
        assertEquals(listOf(ha, ntfy, signal), ConnFiles.store(ctx).connections())
        assertEquals(haKey, key(ha.id))
        noSecretLogged()
    }

    @Test fun someoneElsesConnectionsArriveUntrustedAndWithoutKeys() {
        haveConnections(ha, ntfy, signal)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        val file = backup(setOf(BackupCategory.CONNECTIONS) + BackupCategory.covers(BackupCategory.KEYS))
        newPhone()
        val (r, _) = restore(file, mine = false, keys = true)
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.CONNECTIONS])
        assertEquals(CategoryOutcome.Skipped(PlanSkip.NOT_YOURS), r.outcomes[BackupCategory.CONN_KEYS])
        assertTrue(r.keptSafer > 0)
        val got = ConnFiles.store(ctx).connections()
        assertEquals(listOf(ha.id, ntfy.id, signal.id), got.map { it.id })
        for (c in got) {
            assertTrue(c.id, c.answers.isEmpty())
            assertFalse(c.id, c.spokenYes)
            assertNull(c.id, c.tested)
            assertNull(c.id, c.where)
            assertTrue(c.id, c.perMinute <= Connection.DEFAULT_PER_MINUTE)
            assertFalse(c.id, ConnKeyFiles.saved(ctx, c.id))
        }
        // Its address and what it does come as saved; Settings says what each needs.
        assertEquals(ha.address, got[0].address)
        assertEquals(ConnState.NEEDS_KEY, ConnSetup.state(got[0], keySaved = false))
        val hand = got.single { it.id == signal.id }
        assertNull(hand.handOff!!.signer)
        assertEquals(AppPinState.UNPINNED, app.heyrobot.sys.HandOffPicks.state(ctx, hand))
        assertEquals(ConnState.PICK_AGAIN, ConnSetup.state(hand, true, AppPinState.UNPINNED))
        assertTrue(keystore.held.isEmpty())
        noSecretLogged()
    }

    @Test fun replaceDropsThisPhonesKeyWhereTheAddressChangedAndTakesYourBackupsKey() {
        haveConnections(ha, ntfy)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        assertTrue(ConnKeys.save(ctx, ntfy.id, ntfyKey))
        val file = backup(setOf(BackupCategory.CONNECTIONS) + BackupCategory.covers(BackupCategory.KEYS))
        // Since: Home Assistant moved, with another key, and a webhook was added with a key of its own.
        val moved = ha.copy(address = ConnUrl.parse("https://ha.elsewhere.example")!!)
        val movedKey = ConnSecrets(token = "zq9marker-moved-key-" + "Cd2_".repeat(8))
        val hook = Connection("c-hook01", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!, auth = ConnAuth.Bearer)
        assertTrue(ConnFiles.store(ctx).edit { listOf(moved, ntfy, hook) } != null)
        assertTrue(ConnKeys.save(ctx, moved.id, movedKey))
        assertTrue(ConnKeys.save(ctx, hook.id, ConnSecrets(token = "zq9marker-hook-key-0001")))
        // Replace, keys ticked, your own: the file's list; the moved one's key here is gone and the backup's goes in its
        // place; ntfy (same address) keeps its key; the webhook and its key go.
        val (r, _) = restore(file, mine = true, keys = true, replace = true)
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.CONNECTIONS])
        assertEquals(listOf(ha, ntfy), ConnFiles.store(ctx).connections())
        assertEquals(haKey, key(ha.id))
        assertEquals(ntfyKey, key(ntfy.id))
        assertFalse(ConnKeyFiles.saved(ctx, hook.id))
        assertFalse(File(Paths.dir(ctx), "connections/${hook.id}").exists())
        assertFalse(keystore.held.containsKey("robot_conn_c-hook01"))
        noSecretLogged()
    }

    @Test fun addNeverGivesAKeyToAConnectionThatMoved() {
        haveConnections(ha)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        val file = backup(setOf(BackupCategory.CONNECTIONS) + BackupCategory.covers(BackupCategory.KEYS))
        // This phone's Home Assistant now reaches another address, and has no key.
        val moved = ha.copy(address = ConnUrl.parse("https://ha.elsewhere.example")!!)
        assertTrue(ConnFiles.store(ctx).edit { listOf(moved) } != null)
        ConnKeyFiles.delete(ctx, ha.id)
        val (r, _) = restore(file, mine = true, keys = true)
        // Add: yours stays as it is, and the backup's key, saved for the old address, never goes to the new one.
        // R2: so the line doesn't say restored.
        assertEquals(CategoryOutcome.KeysNoPlace(1), r.outcomes[BackupCategory.CONN_KEYS])
        assertEquals(listOf(moved), ConnFiles.store(ctx).connections())
        assertFalse(ConnKeyFiles.saved(ctx, ha.id))
    }

    /**
     * C12's L1: a key folder left without a connection here (one connections.json dropped on read, say) never becomes the
     * key of a connection a restore brings under the same id: it goes before the connections arrive, in every mode.
     */
    @Test fun aStrayKeyNeverReachesSomeoneElsesConnection() {
        haveConnections(ha)
        val file = backup(setOf(BackupCategory.CONNECTIONS))
        for (replace in listOf(false, true)) {
            newPhone()
            assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = "zq9marker-stray-" + "Ef3_".repeat(8))))
            assertTrue(ConnFiles.store(ctx).connections().isEmpty())
            val (r, _) = restore(file, mine = false, keys = false, replace = replace)
            assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.CONNECTIONS])
            assertEquals(listOf(ha.id), ConnFiles.store(ctx).connections().map { it.id })
            assertFalse("replace=$replace", ConnKeyFiles.saved(ctx, ha.id))
            assertFalse(keystore.held.containsKey("robot_conn_c-ha1234"))
        }
        noSecretLogged()
    }

    @Test fun aDamagedConnectionsFileHereIsNeverWrittenOver() {
        haveConnections(ha)
        val file = backup(setOf(BackupCategory.CONNECTIONS))
        val here = File(Paths.dir(ctx), "connections.json").apply { writeText("{\"v\":1,") }
        // Back up now leaves it out rather than carry a file no restore would read.
        assertEquals(-1, BackupSources.survey(ctx, RobotSettings()).getValue(BackupCategory.CONNECTIONS).count)
        val (r, _) = restore(file, mine = true, keys = false)
        assertEquals(CategoryOutcome.Skipped(PlanSkip.CONNECTIONS_UNREADABLE), r.outcomes[BackupCategory.CONNECTIONS])
        assertEquals("{\"v\":1,", here.readText())
    }

    /** F88: a key the Keystore wouldn't save is counted, not reported as Done. */
    @Test fun aFailedConnectionKeySaveIsCounted() {
        haveConnections(ha, ntfy)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        assertTrue(ConnKeys.save(ctx, ntfy.id, ntfyKey))
        val file = backup(setOf(BackupCategory.CONNECTIONS) + BackupCategory.covers(BackupCategory.KEYS))
        fun failing(allowed: Int) = object : SecretKeys {
            val inner = FakeKeystore()
            var left = allowed
            override fun get(alias: String): SecretKey? = inner.get(alias)
            override fun create(alias: String): SecretKey = if (left-- > 0) inner.create(alias) else throw java.security.KeyStoreException("no")
            override fun delete(alias: String) = inner.delete(alias)
        }
        newPhone()
        KeystoreSecret.keys = failing(1)
        val some = restore(file, mine = true, keys = true).first.outcomes[BackupCategory.CONN_KEYS]
        assertEquals(1, (some as app.heyrobot.core.backup.CategoryOutcome.KeysMissed).missed)
        newPhone()
        KeystoreSecret.keys = failing(0)
        assertEquals(app.heyrobot.core.backup.CategoryOutcome.Failed, restore(file, mine = true, keys = true).first.outcomes[BackupCategory.CONN_KEYS])
    }

    /** F89: a list that can't be written fails Connections, with this phone's key still here. */
    @Test fun aConnectionListThatCantBeWrittenKeepsTheKeysAndFails() {
        haveConnections(ha)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        val st = File(ctx.cacheDir, "conn-staged").apply { mkdirs() }
        File(st, BackupCategory.CONNECTIONS_FILE).writeText(ConnectionCodec.encode(listOf(ntfy)))
        File(st, RestoreSession.DROP_KEYS).writeText("[\"${ha.id}\"]")
        val list = File(Paths.dir(ctx), "connections.json")
        val good = list.readText()
        list.writeText("damaged")
        assertThrows(CantFinish::class.java) { RestoreSession.finishVoice(ctx, BackupCategory.CONNECTIONS, st) }
        assertTrue(ConnKeyFiles.saved(ctx, ha.id))
        // A staged list that doesn't read: the same.
        list.writeText(good)
        val staged = File(st, BackupCategory.CONNECTIONS_FILE)
        val whole = staged.readText()
        staged.writeText("damaged")
        assertThrows(CantFinish::class.java) { RestoreSession.finishVoice(ctx, BackupCategory.CONNECTIONS, st) }
        assertTrue(ConnKeyFiles.saved(ctx, ha.id))
        // Written: the list changes and the key goes.
        staged.writeText(whole)
        RestoreSession.finishVoice(ctx, BackupCategory.CONNECTIONS, st)
        assertEquals(listOf(ntfy), ConnFiles.store(ctx).connections())
        assertFalse(ConnKeyFiles.saved(ctx, ha.id))
    }

    @Test fun aCutRunDeletesTheConnectionKeysItSaved() {
        haveConnections(ha)
        assertTrue(ConnKeys.save(ctx, ha.id, haKey))
        val j = RestoreFiles.journal(ctx)
        j.begin(BackupCategory.CONN_KEYS, app.heyrobot.core.backup.RestoreProcess.VOICE)
        j.keySaving(BackupCategory.connKey(ha.id), BackupCategory.CONN_KEYS)
        RestoreSession.recover(ctx)
        assertFalse(ConnKeyFiles.saved(ctx, ha.id))
        assertTrue(j.read().isEmpty())
        // The connection itself stays: only the key this run saved goes.
        assertEquals(listOf(ha), ConnFiles.store(ctx).connections())
    }
}
