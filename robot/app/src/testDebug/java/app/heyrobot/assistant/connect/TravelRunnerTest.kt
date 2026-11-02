// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.LocationCode
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.StepArg
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnTested
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.NetNow
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.connect.RoutingKind
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.output.Places
import app.heyrobot.core.skills.StepKind
import app.heyrobot.helpers.connect.ConnCall
import app.heyrobot.sys.CoarseLocation
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.HandsFreeFiles
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A skill's travel time step in the panel (connections plan §3.4; C6), with a fake client and a fake position: prepare
 * reads Home and Work from Car mode and whether location is allowed, without reading where the phone is; the run reads it
 * only after the card, rounds it to about 1 km before it goes, asks the routing server the step names or your only one,
 * and gives the minutes and kilometres. A position that couldn't be had says why and sends nothing. Logs: never a place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TravelRunnerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val logged = mutableListOf<String>()
    private val origin = "http://100.101.102.20:5000"
    private val osrm = Connection("c-osrm01", ConnKind.ROUTING, "zq9 OSRM", ConnUrl.parse(origin)!!, routing = RoutingKind.OSRM, where = ConnWhere.HOME,
        answers = mapOf(origin to ConsentAnswer.ALLOW), tested = ConnTested(1L))
    private val home = SavedPlace.of("Home", 45.50884, -73.58781)!!

    private val sent = mutableListOf<HttpRequest>()
    private var answer: ConnCall = ConnCall.Answered(ReplyRead.Ok(200, mapOf("code" to "Ok", "routes" to listOf(mapOf("duration" to 1514.3, "distance" to 17532.8)))),
        ConnWhere.HOME, 80, 40)
    private var located = 0
    private var found = CoarseLocation.Found(CoarseLocation.Fix(45.473129, -73.602191, fresh = true), LocationCode.FIX, 100, 900)

    private fun step(to: String = "home", conn: String? = null) =
        ConnStepAction(ConnStepCall(StepKind.TRAVEL_TIME, conn, null, mapOf("to" to StepArg.Fixed(to)), "Heading home", 1))

    private fun prepared(a: ConnStepAction = step()): ConnStepAction = (ConnRunner.prepare(ctx, a) as Prepared.Ready).let { r ->
        (r.choices.firstOrNull()?.action ?: r.action) as ConnStepAction }

    private fun en(p: Prepared): String = (p as Prepared.Refused).text!!.render(Langs.EN)

    @Before fun up() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.INTERNET, Manifest.permission.ACCESS_COARSE_LOCATION)
        RLog.sink = { synchronized(logged) { logged += it } }
        ConnRunner.send = { _, r, _, _ -> sent += r; answer }
        ConnRunner.net = NetSource { null }
        ConnRunner.locate = { _, _, _ -> located++; found }
        assertTrue(ConnFiles.store(ctx).save(osrm))
        HandsFreeFiles.store(ctx).editPlaces { Places(home, null) }
    }

    @After fun down() {
        RLog.sink = null
        ConnRunner.send = null
        ConnRunner.net = ConnNetwork
        ConnRunner.locate = { c, ms, a -> CoarseLocation.find(c, ms, a) }
        File(ctx.filesDir, "robot/connections.json").delete()
        File(ctx.filesDir, "robot/skills_state.json").delete()
        HandsFreeFiles.store(ctx).editPlaces { Places() }
        for (l in logged) assertFalse(l, l.contains("zq9") || l.contains("45.4") || l.contains("73.6") || l.contains("100.101"))
    }

    @Test fun itReadsWhereYouAreOnlyAfterItsCardAndSendsItRounded() = runBlocking {
        val a = prepared()
        assertEquals(0, located)
        assertTrue(sent.isEmpty())
        assertEquals("Travel time to home · your zq9 OSRM", a.summary().render(Langs.EN))
        val e = ConnRunner.run(ctx, a)
        assertEquals(1, located)
        assertEquals(ActionStatus.DONE, e.status)
        assertEquals("Your zq9 OSRM: about 25 min (17.5 km).", e.text!!.render(Langs.EN))
        assertEquals(25L, e.conn!!.outputs.getValue("minutes").value)
        assertTrue(e.conn!!.located && e.content)
        // Where the phone is, rounded to two decimals, then Home.
        assertEquals("/route/v1/driving/-73.60,45.47;-73.59,45.51?overview=false&steps=false&alternatives=false", sent.single().tail)
        // Counted toward the connection's limits, and the voice log keeps its kind (4: routing) and how it ended.
        assertEquals(1, ConnFilesLimits.calls(ctx, osrm.id))
        val log = VoiceLog.entries().first { it.event == VoiceEvent.CONN_CALL }
        assertEquals(listOf(4, 0), listOf(log.a, log.b))
    }

    @Test fun noPositionSaysWhyAndSendsNothing() = runBlocking {
        val a = prepared()
        for ((code, words) in listOf(LocationCode.OFF to ConnFailure.LOCATION_OFF, LocationCode.BACKGROUND to ConnFailure.LOCATION_BACKGROUND,
            LocationCode.NONE to ConnFailure.NO_LOCATION, LocationCode.TOO_OLD to ConnFailure.NO_LOCATION)) {
            found = CoarseLocation.Found(null, code, 100, 10)
            val e = ConnRunner.run(ctx, a)
            assertEquals(ActionStatus.FAILED, e.status)
            assertEquals(words.text("zq9 OSRM").render(Langs.EN), e.text!!.render(Langs.EN))
        }
        assertTrue(sent.isEmpty())
    }

    @Test fun refusedBeforeItsCard() {
        // No Work saved in Car mode.
        assertEquals("Save your work address in Settings › Car mode first.", en(ConnRunner.prepare(ctx, step("work"))))
        // A second routing server: a step that names none can't choose.
        assertTrue(ConnFiles.store(ctx).save(osrm.copy(id = "c-osrm02")))
        assertEquals(ActionStatus.FAILED, (ConnRunner.prepare(ctx, step()) as Prepared.Refused).status)
        ConnRunner.prepare(ctx, step(conn = "c-osrm02")).let { assertTrue((it as? Prepared.Refused)?.text?.render(Langs.EN).orEmpty(), it is Prepared.Ready) }
        // Where you are never goes over plain http to a LAN address: such a connection isn't even saved (and core's
        // TravelStepTest refuses it before the card, should one come from an older file).
        assertFalse(ConnFiles.store(ctx).save(osrm.copy(id = "c-osrm03", address = ConnUrl.parse("http://192.168.1.20:5000")!!)))
        // Offline.
        ConnRunner.net = NetSource { NetNow.NONE }
        assertEquals("Robot can't go online right now.", en(ConnRunner.prepare(ctx, step(conn = "c-osrm02"))))
        // Location not allowed: said before the card, and nothing is read.
        ConnRunner.net = NetSource { null }
        shadowOf(ctx as Application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals("Robot isn't allowed to see where you are. Allow location for Robot in Android's settings.", en(ConnRunner.prepare(ctx, step(conn = "c-osrm02"))))
        assertEquals(0, located)
        assertTrue(sent.isEmpty())
    }

    @Test fun aChangedAddressOrHomeIsNeverSentTo() = runBlocking {
        val a = prepared()
        // Home removed after the card: refused, nothing read or sent.
        HandsFreeFiles.store(ctx).editPlaces { Places() }
        assertEquals("Save your home in Settings › Car mode first.", ConnRunner.run(ctx, a).text!!.render(Langs.EN))
        HandsFreeFiles.store(ctx).editPlaces { Places(home, null) }
        // The address changed after the card.
        assertTrue(ConnFiles.store(ctx).save(osrm.copy(address = ConnUrl.parse("http://100.101.102.21:5000")!!)))
        assertEquals(ActionStatus.FAILED, ConnRunner.run(ctx, a).status)
        assertEquals(0, located)
        assertTrue(sent.isEmpty())
        // A server that answers without a route: unreadable, nothing kept.
        assertTrue(ConnFiles.store(ctx).save(osrm))
        answer = ConnCall.Answered(ReplyRead.Ok(200, mapOf("code" to "Ok", "routes" to emptyList<Any>())), ConnWhere.HOME, 20, 30)
        val e = ConnRunner.run(ctx, prepared())
        assertEquals("Your zq9 OSRM's answer was too long or unreadable.", e.text!!.render(Langs.EN))
        assertTrue(e.conn!!.outputs.isEmpty())
    }
}

/** The connection's recorded calls (D16), for the tests. */
private object ConnFilesLimits {
    fun calls(ctx: Context, id: String): Int = app.heyrobot.sys.SkillFiles.store(ctx).state().limits.calls[id].orEmpty().size
}
