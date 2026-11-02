// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.StepArg
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnOp
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.NetNow
import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.connect.OpSpec
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.StepKind
import app.heyrobot.helpers.connect.ConnCall
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.sys.SecretKeys
import app.heyrobot.sys.SkillFiles
import java.io.File
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * A skill's connection step in the panel (connections plan §5.4, §5.5; C4), with a fake client in place of ConnHttp: what
 * prepare reads (the connection, its key saved, the Network toggle, the limits), and the run's order: the Network toggle
 * first, the connection still the one its card showed, the first-use answer it carries (none: nothing sent) and kept as it
 * goes, the key read just before and each way it can't be read said, the limits counted, then the call and its outputs;
 * the voice log keeps numbers only. CN4's seam: never asks Android without ACCESS_NETWORK_STATE (unknown on this branch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnRunnerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val logged = mutableListOf<String>()
    private val before = KeystoreSecret.keys
    private val token = "zq9TOKEN" + "Ab1_".repeat(10) + "X9z7"

    private val keys = object : SecretKeys {
        val held = HashMap<String, SecretKey>()
        var failure: Throwable? = null
        override fun get(alias: String): SecretKey? = failure?.let { throw it } ?: held[alias]
        override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { held[alias] = it }
        override fun delete(alias: String) { held.remove(alias) }
    }

    private val origin = "https://zq9home.example.org"
    private val light = ConnOp("light_off", "Turn off a light", OpSpec.HaCall("light", "turn_off"),
        mapOf("entity" to OpParam(ParamType.CHOICE, listOf("light.zq9marker2"), default = "light.zq9marker2")))
    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "zq9 Home Assistant", ConnUrl.parse(origin)!!, auth = ConnAuth.Bearer,
        where = ConnWhere.HOME, ops = listOf(light), answers = mapOf(origin to ConsentAnswer.ALLOW), tested = ConnTested(1L))

    private val sent = mutableListOf<Triple<Connection, HttpRequest, ConnSecrets>>()
    private var answer: ConnCall = ConnCall.Answered(ReplyRead.Ok(200, listOf(mapOf("entity_id" to "light.zq9marker2", "state" to "off"))), ConnWhere.HOME, 2, 40)

    private fun step() = ConnStepAction(ConnStepCall(StepKind.CONN, ha.id, "light_off", mapOf("entity" to StepArg.Fixed("light.zq9marker2")), "Good night", 1))

    private fun prepared(): ConnStepAction = (ConnRunner.prepare(ctx, step()) as Prepared.Ready).let { r -> (r.choices.firstOrNull()?.action ?: r.action) as ConnStepAction }

    private fun en(p: Prepared): String = (p as Prepared.Refused).text!!.render(Langs.EN)

    @Before fun up() {
        KeystoreSecret.keys = keys
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.INTERNET)
        RLog.sink = { synchronized(logged) { logged += it } }
        ConnRunner.send = { c, r, s, _ -> sent += Triple(c, r, s); answer }
        ConnRunner.net = NetSource { null }
        assertTrue(ConnFiles.store(ctx).save(ha))
    }

    @After fun down() {
        RLog.sink = null
        ConnRunner.send = null
        ConnRunner.net = ConnNetwork
        ConnKeyFiles.removeAll(ctx, ha.id)
        File(ctx.filesDir, "robot/connections.json").delete()
        File(ctx.filesDir, "robot/skills_state.json").delete()
        KeystoreSecret.keys = before
        for (l in logged) assertFalse(l, l.contains("zq9") || l.contains("example.org") || l.contains("/api"))
    }

    @Test fun aPreparedStepRunsWithItsKeyAndCountsTheCall() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        assertEquals("Turn off a light · your zq9 Home Assistant", a.summary().render(Langs.EN))
        // The card's preview shows the key's mask, never the key.
        assertFalse(a.bodyText()!!.render(Langs.EN).contains(token))
        val e = ConnRunner.run(ctx, a)
        assertEquals(ActionStatus.DONE, e.status)
        val (_, req, secrets) = sent.single()
        assertEquals("/api/services/light/turn_off", req.tail)
        assertEquals(ConnSecrets(token = token), secrets)
        assertEquals(1, SkillFiles.store(ctx).state().limits.calls.getValue(ha.id).size)
        val call = VoiceLog.entries().first { it.event == VoiceEvent.CONN_CALL }
        assertEquals(listOf(1, 0, 11), listOf(call.a, call.b, call.c))
    }

    /** C5: an entity Home Assistant's answer didn't list is read back once, after the wait, and judged by the domain table. */
    @Test fun whatTheAnswerDidntListIsReadBack() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        var state = "on"
        ConnRunner.send = { c, r, s, _ ->
            sent += Triple(c, r, s)
            if (r.method == app.heyrobot.core.connect.HttpMethod.GET) ConnCall.Answered(ReplyRead.Ok(200, mapOf("entity_id" to "light.zq9marker2", "state" to state)), ConnWhere.HOME, 60, 30)
            else ConnCall.Answered(ReplyRead.Ok(200, listOf<Any>()), ConnWhere.HOME, 2, 40)
        }
        val calls0 = VoiceLog.entries().count { it.event == VoiceEvent.CONN_CALL }
        val t0 = System.nanoTime()
        val e = ConnRunner.run(ctx, prepared())
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= app.heyrobot.core.connect.HaCheck.READ_BACK_WAIT_MS)
        assertEquals(listOf("/api/services/light/turn_off", "/api/states/light.zq9marker2"), sent.map { it.second.tail })
        assertEquals(ConnSecrets(token = token), sent[1].third)
        assertEquals(ActionStatus.FAILED, e.status)
        assertEquals(app.heyrobot.core.connect.CheckOutcome.FAILED, e.conn!!.check)
        assertEquals("the zq9marker2 light didn't turn off", e.conn!!.what!!.render(Langs.EN))
        // Both calls in the voice log, as numbers; only the step counted toward the limits.
        assertEquals(calls0 + 2, VoiceLog.entries().count { it.event == VoiceEvent.CONN_CALL })
        assertEquals(1, SkillFiles.store(ctx).state().limits.calls.getValue(ha.id).size)
        // Off when read back: passed.
        sent.clear()
        state = "off"
        assertEquals(app.heyrobot.core.connect.CheckOutcome.PASSED, ConnRunner.run(ctx, prepared()).conn!!.check)
    }

    @Test fun theNetworkToggleComesFirstAndNothingIsSent() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.INTERNET)
        assertEquals("Robot's network access is off in Android's settings.", en(ConnRunner.prepare(ctx, step())))
        assertEquals("Robot's network access is off in Android's settings.", ConnRunner.run(ctx, a).text!!.render(Langs.EN))
        assertTrue(sent.isEmpty())
    }

    @Test fun eachWayTheKeyCantBeReadIsSaidAndNothingIsSent() = runBlocking {
        assertEquals("Your zq9 Home Assistant needs its key. Paste it in Settings › Skills › Connections.", en(ConnRunner.prepare(ctx, step())))
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        keys.failure = KeyStoreException("busy")
        assertEquals("Robot couldn't read your zq9 Home Assistant key just now. Try again in a moment.", ConnRunner.run(ctx, a).text!!.render(Langs.EN))
        assertTrue("kept for the next try", ConnKeyFiles.saved(ctx, ha.id))
        keys.failure = null
        keys.held.clear()
        assertEquals("Robot couldn't read your zq9 Home Assistant key anymore, so it removed it. Paste it again in Settings › Skills › Connections.",
            ConnRunner.run(ctx, a).text!!.render(Langs.EN))
        assertTrue(sent.isEmpty())
        // Nothing was counted toward the limits: nothing went out.
        assertNull(SkillFiles.store(ctx).state().limits.calls[ha.id])
    }

    @Test fun theFirstUseAnswerGoesWithTheRunAndIsKept() = runBlocking {
        assertTrue(ConnFiles.store(ctx).save(ha.copy(answers = emptyMap())))
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val r = ConnRunner.prepare(ctx, step()) as Prepared.Ready
        assertEquals(setOf(AskWhy.CONNECTION_FIRST), r.why)
        // Without an answer (a card nobody answered), nothing is sent.
        assertEquals(ActionStatus.FAILED, ConnRunner.run(ctx, r.action).status)
        assertTrue(sent.isEmpty())
        assertNull(ConnFiles.store(ctx).get(ha.id)!!.answers[origin])
        // "Allow for this connection": kept as it goes, and the next step asks nothing.
        assertEquals(ActionStatus.DONE, ConnRunner.run(ctx, r.choices[0].action).status)
        assertEquals(ConsentAnswer.ALLOW, ConnFiles.store(ctx).get(ha.id)!!.answers[origin])
        assertEquals(emptySet<AskWhy>(), (ConnRunner.prepare(ctx, step()) as Prepared.Ready).why)
    }

    @Test fun aChangedAddressSinceTheCardIsNeverSentTo() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        // Changed and tested again since the card: still never sent to (the card showed the old address).
        assertTrue(ConnFiles.store(ctx).save(app.heyrobot.core.connect.ConnConsent.withAddress(ha, ConnUrl.parse("https://zq9other.example.org")!!)
            .copy(tested = ConnTested(2L))))
        assertEquals("Test your zq9 Home Assistant first in Settings › Skills › Connections.", ConnRunner.run(ctx, a).text!!.render(Langs.EN))
        assertTrue(sent.isEmpty())
    }

    /**
     * C12's L3: the step runs what its card showed, or nothing. An operation changed in connections.json while its card
     * waited (another entity, another service, a lock where a light was) is never sent: "ask again".
     */
    @Test fun anOperationChangedAfterItsCardIsNotSent() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        // The step names no entity: the operation's own (its default) is what the card showed.
        val bare = ConnStepAction(ConnStepCall(StepKind.CONN, ha.id, "light_off", emptyMap(), "Good night", 1))
        fun ready() = (ConnRunner.prepare(ctx, bare) as Prepared.Ready).action as ConnStepAction
        // C13: its own line, not "This connection is gone" (the connection is there; what it does changed).
        val gone = "This step changed after its card showed, so Robot didn't run it. Ask again."
        val other = light.copy(params = mapOf("entity" to OpParam(ParamType.CHOICE, listOf("light.zq9other"), default = "light.zq9other")))
        val service = light.copy(spec = OpSpec.HaCall("light", "turn_on"))
        val unlock = light.copy(spec = OpSpec.HaCall("lock", "unlock"), params = mapOf("entity" to OpParam(ParamType.CHOICE, listOf("lock.zq9door"), default = "lock.zq9door")))
        for (changed in listOf(other, service, unlock)) {
            assertTrue(ConnFiles.store(ctx).save(ha))
            val a = ready()
            assertTrue(ConnFiles.store(ctx).save(ha.copy(ops = listOf(changed))))
            val e = ConnRunner.run(ctx, a)
            assertEquals(changed.spec.toString(), ActionStatus.FAILED, e.status)
            assertEquals(gone, e.text!!.render(Langs.EN))
            assertTrue(sent.isEmpty())
        }
        // As its card showed it: sent.
        assertTrue(ConnFiles.store(ctx).save(ha))
        assertEquals(ActionStatus.DONE, ConnRunner.run(ctx, ready()).status)
        assertEquals("/api/services/light/turn_off", sent.single().second.tail)
    }

    @Test fun theLimitIsCheckedAgainAtTheRun() = runBlocking {
        assertTrue(ConnFiles.store(ctx).save(ha.copy(perMinute = 1)))
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        val b = prepared()
        assertEquals(ActionStatus.DONE, ConnRunner.run(ctx, a).status)
        assertEquals("Robot's limit for your zq9 Home Assistant was reached: 1 a minute.", ConnRunner.run(ctx, b).text!!.render(Langs.EN))
        assertEquals(1, sent.size)
        val limit = VoiceLog.entries().first { it.event == VoiceEvent.SKILL_LIMIT }
        assertEquals(listOf(2, 1), listOf(limit.a, limit.b))
    }

    @Test fun aWriteThatMayHaveGoneOutSaysSo() = runBlocking {
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        answer = ConnCall.Failed(app.heyrobot.core.connect.ConnFailure.TIMEOUT, sent = true, ms = 10_000)
        val e = ConnRunner.run(ctx, prepared())
        assertEquals("Your zq9 Home Assistant didn't answer in time. It may have reached your zq9 Home Assistant, so Robot won't send it again by itself.",
            e.text!!.render(Langs.EN))
        assertEquals(1, sent.size)
    }

    @Test fun aNetworkAndroidWontDescribeStopsNoCall() = runBlocking {
        // conn-vpn: null from getActiveNetwork means no default network or a blocked one; a call might still work.
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        ConnRunner.net = ConnNetwork
        shadowOf(cm).setDefaultNetworkActive(false)
        assertNull(cm.activeNetwork)
        assertNull(ConnNetwork.now(ctx))
        assertTrue(ConnKeys.save(ctx, ha.id, ConnSecrets(token = token)))
        val a = prepared()
        assertEquals(0, sent.size)
        // Tried, and a failure says "can't reach".
        answer = ConnCall.Failed(ConnFailure.UNREACHABLE, false, 2)
        val done = ConnRunner.run(ctx, a)
        assertEquals(1, sent.size)
        assertEquals(ActionStatus.FAILED, done.status)
        assertEquals("Robot can't reach your zq9 Home Assistant right now.", done.text!!.render(Langs.EN))
        // A network whose capabilities Android won't give: unknown too.
        shadowOf(cm).setDefaultNetworkActive(true)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork!!, null)
        assertNull(ConnNetwork.now(ctx))
    }

    @Test fun whereTheCapabilitiesPutYou() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        fun on(vararg transports: Int): NetNow? {
            val caps = ShadowNetworkCapabilities.newInstance()
            transports.forEach { shadowOf(caps).addTransportType(it) }
            shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
            return ConnNetwork.now(ctx)
        }
        assertEquals(NetNow.LOCAL, on(NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(NetNow.AWAY, on(NetworkCapabilities.TRANSPORT_CELLULAR))
        // Android can't say whether a VPN that's up goes anywhere: on mobile data it still reads as your own network.
        assertEquals(NetNow.LOCAL, on(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN))
    }

    @Test fun androidIsAskedOnlyWithThePermission() {
        // Not declared on this branch: never granted, so unknown, and nothing of the network is read.
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        assertNull(ConnNetwork.now(ctx))
        assertEquals(NetNow.LOCAL, ConnNetwork.of(wifi = true, ethernet = false, vpn = false))
        assertEquals(NetNow.LOCAL, ConnNetwork.of(wifi = false, ethernet = false, vpn = true))
        assertEquals(NetNow.AWAY, ConnNetwork.of(wifi = false, ethernet = false, vpn = false))
        // Away from your network, an http home address isn't tried.
        val home = Connection("c-home01", ConnKind.WEBHOOK, "zq9 Lamp", ConnUrl.parse("http://192.168.1.9")!!, where = ConnWhere.HOME,
            ops = listOf(ConnOp("lamp_on", "Lamp on", OpSpec.Webhook(app.heyrobot.core.connect.HttpMethod.POST, "/on"))),
            answers = mapOf("http://192.168.1.9" to ConsentAnswer.ALLOW), tested = ConnTested(1L))
        assertTrue(ConnFiles.store(ctx).save(home))
        ConnRunner.net = NetSource { NetNow.AWAY }
        val away = ConnStepAction(ConnStepCall(StepKind.CONN, home.id, "lamp_on", emptyMap(), "Good night", 1))
        assertEquals("Robot can't reach your zq9 Lamp off your home network.", en(ConnRunner.prepare(ctx, away)))
        ConnRunner.net = NetSource { NetNow.NONE }
        assertEquals("Robot can't go online right now.", en(ConnRunner.prepare(ctx, away)))
        ConnKeyFiles.removeAll(ctx, home.id)
    }
}
