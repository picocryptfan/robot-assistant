// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.HelperContext
import app.heyrobot.core.LocationCode
import app.heyrobot.core.Personal
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.StrictJson
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.output.Places
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A skill's `travel_time` step (plan §3.4; C6): refused before its card in Robot's words (the place not saved, location
 * not allowed, where you are over plain http to anything but loopback or Tailscale), its card (where it goes, that where
 * you are goes there to about 1 km, never coordinates), the first use's tap-only card saying so, and what the answer gave
 * the rest of the skill and the conversation (Personal.LOCATION).
 */
class TravelStepTest {
    private val now = 1_761_100_000_000L
    private val home = SavedPlace.of("Home", 45.50884, -73.58781)!!
    private val work = SavedPlace.of("Office", 45.47312, -73.60219)!!
    private val origin = "http://100.101.102.20:5000"
    private val osrm = ConnFixtures.routing().copy(tested = ConnTested(now - 1000), answers = mapOf(origin to ConsentAnswer.ALLOW))

    private fun travel(to: String = "home", conn: String? = null) =
        ConnStepAction(ConnStepCall(StepKind.TRAVEL_TIME, conn, null, mapOf("to" to StepArg.Fixed(to)), "Heading home", 1))

    private fun x(c: Connection?, places: Places = Places(home, null), located: Boolean = true, network: Boolean = true, net: NetNow? = null) =
        StepContext(c, network, net, true, emptyMap(), LimitState(), now, places, located)

    private fun en(p: Prepared): String = when (p) {
        is Prepared.Refused -> p.text!!.render(Langs.EN)
        is Prepared.Ready -> p.noteText?.render(Langs.EN).orEmpty()
    }

    private fun ready(p: Prepared): Prepared.Ready = p as? Prepared.Ready ?: throw AssertionError(en(p))

    @Test fun refusedBeforeItsCard() {
        assertEquals("This connection is gone. Check the skill's steps in Settings › Skills › Connections.", en(ConnStep.prepare(travel(), x(null))))
        // A step naming another routing server than the one found.
        assertEquals(ActionStatus.FAILED, (ConnStep.prepare(travel(conn = "c-other1"), x(osrm)) as Prepared.Refused).status)
        assertEquals("Save your home in Settings › Car mode first.", en(ConnStep.prepare(travel(), x(osrm, Places()))))
        assertEquals("Save your work address in Settings › Car mode first.", en(ConnStep.prepare(travel("work"), x(osrm))))
        assertEquals("Robot isn't allowed to see where you are. Allow location for Robot in Android's settings.",
            en(ConnStep.prepare(travel(), x(osrm, located = false))))
        assertEquals("Robot's network access is off in Android's settings.", en(ConnStep.prepare(travel(), x(osrm, network = false))))
        assertEquals("Robot can't go online right now.", en(ConnStep.prepare(travel(), x(osrm, net = NetNow.NONE))))
        assertEquals(ActionStatus.FAILED, (ConnStep.prepare(travel("school"), x(osrm)) as Prepared.Refused).status)
    }

    @Test fun whereYouAreNeverGoesOverPlainHttpToYourNetwork() {
        // On someone else's Wi-Fi a private address can be anyone's machine: refused before the card, in Robot's words.
        for (a in listOf("http://192.168.1.20:5000", "http://10.0.0.5:5000", "http://[fd00::20]:8989", "http://169.254.1.1", "http://osrm.lan:5000",
            "http://pc.tail1234.ts.net:5000")) {
            val c = osrm.copy(address = ConnFixtures.url(a))
            assertEquals(a, ConnFailure.LOCATION_OVER_HTTP, ConnRules.refusal(c))
            assertTrue(a, ConnProblem.LOCATION_OVER_HTTP in ConnCheck.problems(c))
            assertEquals(a, "Robot won't send where you are to your My routing server over plain http: use an https address, or your server's Tailscale address.",
                en(ConnStep.prepare(travel(), x(c))))
            // Test sends points near your Home: refused the same way, before any request is built.
            assertEquals(a, Built.Refused(ConnFailure.LOCATION_OVER_HTTP), RoutingPreset.test(c, home))
        }
        // Loopback (a server on the phone, or the PC through adb reverse) and Tailscale's 100.64/10; https anywhere.
        for (a in listOf("http://127.0.0.1:5000", "http://[::1]:5000", "http://100.64.0.1:5000", "http://100.127.255.254", "http://[::ffff:100.101.1.2]:5000",
            "https://192.168.1.20:5000", "https://osrm.lan", "https://route.example.org")) {
            val c = osrm.copy(address = ConnFixtures.url(a), answers = mapOf(ConnFixtures.url(a).origin to ConsentAnswer.ALLOW))
            assertNull(a, ConnRules.refusal(c))
            assertFalse(a, ConnProblem.LOCATION_OVER_HTTP in ConnCheck.problems(c))
            assertTrue(a, ConnStep.prepare(travel(), x(c)) is Prepared.Ready)
        }
        // 100.128.0.1 is outside Tailscale's range: a public address, which plain http never reaches anyway.
        assertEquals(ConnFailure.MOVED, ConnRules.refusal(osrm.copy(address = ConnFixtures.url("http://100.128.0.1"))))
        // Only routing sends where you are: a webhook over http at home is as before.
        assertNull(ConnRules.refusal(ConnFixtures.webhook().copy(address = ConnFixtures.url("http://192.168.1.9"))))
        // The socket check's own rule.
        assertTrue(ConnRules.nearOnly(byteArrayOf(127, 0, 0, 1)))
        assertTrue(ConnRules.nearOnly(byteArrayOf(100, 64, 0, 1)))
        assertFalse(ConnRules.nearOnly(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)))
        assertFalse(ConnRules.nearOnly(byteArrayOf(100, 128.toByte(), 0, 1)))
        assertFalse(ConnRules.nearOnly(ByteArray(3)))
    }

    @Test fun itsCardSaysWhatLeavesNeverCoordinates() {
        val r = ready(ConnStep.prepare(travel(), x(osrm, Places(home, work))))
        val a = r.action as ConnStepAction
        val p = a.prepared!!
        assertEquals("Travel time to home · your My routing server", p.summary.render(Langs.EN))
        val body = p.body.render(Langs.EN)
        assertEquals("Goes to 100.101.102.20 (your My routing server)\n" +
            "Sends where you are, rounded to about 1 km, and your home's place as saved in Car mode.\n" +
            "What it does: sends where you are, to about 1 km and reads from 100.101.102.20", body)
        assertFalse(body.contains("45.5") || body.contains("73.5"))
        assertEquals(setOf(SkillEffect.READS_ONLINE), p.effects)
        assertTrue(p.read && p.spokenYes)
        assertTrue(r.why.isEmpty())
        assertNull(a.tapOnly)
        assertEquals("Travel time to work · your My routing server", (ready(ConnStep.prepare(travel("work"), x(osrm, Places(home, work)))).action as ConnStepAction)
            .prepared!!.summary.render(Langs.EN))
        // A key shows where it goes, never itself.
        val keyed = osrm.copy(address = ConnFixtures.url("https://route.example.org"), auth = ConnAuth.Bearer, answers = mapOf("https://route.example.org" to ConsentAnswer.ALLOW))
        val k = (ready(ConnStep.prepare(travel(), StepContext(keyed, true, null, true, mapOf(SecretPart.TOKEN to "…AB12"), LimitState(), now, Places(home, null), true)))
            .action as ConnStepAction).prepared!!.body.render(Langs.EN)
        assertTrue(k, k.endsWith("Authorization: your My routing server token"))
        // D14's switch off: a spoken yes no longer answers it.
        assertFalse((ready(ConnStep.prepare(travel(), x(osrm.copy(spokenYes = false)))).action as ConnStepAction).prepared!!.spokenYes)
    }

    @Test fun theFirstUseSaysWhereYouAreGoes() {
        val first = osrm.copy(answers = emptyMap())
        val r = ready(ConnStep.prepare(travel(), x(first)))
        assertEquals(setOf(AskWhy.CONNECTION_FIRST), r.why)
        assertEquals(listOf(ConsentAnswer.ALLOW, ConsentAnswer.ONCE), r.choices.map { (it.action as ConnStepAction).consent })
        assertEquals("“Heading home” wants to send to 100.101.102.20 (your My routing server): Travel time to home · your My routing server. " +
            "It sends where you are, to about 1 km, to your server.", en(r))
    }

    @Test fun whatTheAnswerGave() {
        val a = ready(ConnStep.prepare(travel(), x(osrm))).action as ConnStepAction
        val reply = StrictJson.parse("""{"code":"Ok","routes":[{"duration":1514.3,"distance":17532.8}]}""")!!.value
        val done = TravelStep.result(a, osrm, CallEnd.Answered(ReplyRead.Ok(200, reply), 80), null)
        assertEquals(ActionStatus.DONE, done.status)
        assertEquals("Your My routing server: about 25 min (17.5 km).", done.text!!.render(Langs.EN))
        val ran = done.conn!!
        assertEquals(25L, ran.outputs.getValue("minutes").value)
        assertEquals(ParamType.NUMBER, ran.outputs.getValue("km").type)
        assertTrue(ran.outputs.values.all { it.sources == setOf(ValueSource.ONLINE) })
        assertTrue(ran.content && ran.located && ran.sent)
        assertTrue(done.content)
        // No route in the answer: unreadable, nothing kept, nothing said that didn't come.
        val none = TravelStep.result(a, osrm, CallEnd.Answered(ReplyRead.Ok(200, StrictJson.parse("""{"code":"Ok","routes":[]}""")!!.value)), null)
        assertEquals(ActionStatus.FAILED, none.status)
        assertEquals("Your My routing server's answer was too long or unreadable.", none.text!!.render(Langs.EN))
        assertTrue(none.conn!!.outputs.isEmpty() && !none.conn!!.located)
        // Failed and stopped calls: a read, so nothing about "may have reached"; away on mobile data for a home server.
        val away = TravelStep.result(a, osrm.copy(where = ConnWhere.HOME), CallEnd.Failed(ConnFailure.UNREACHABLE, false), NetNow.AWAY)
        assertEquals("Robot can't reach your My routing server off your home network.", away.text!!.render(Langs.EN))
        assertEquals(ActionStatus.DECLINED, TravelStep.result(a, osrm, CallEnd.Stopped(false), null).status)
        assertEquals("Your My routing server said no (error 400).",
            TravelStep.result(a, osrm, CallEnd.Answered(ReplyRead.Failed(ConnFailure.SAID_NO, 400)), null).text!!.render(Langs.EN))
    }

    @Test fun noPositionSaysWhy() {
        assertEquals(ConnFailure.LOCATION_OFF, TravelStep.noFix(LocationCode.OFF))
        assertEquals(ConnFailure.LOCATION_BACKGROUND, TravelStep.noFix(LocationCode.BACKGROUND))
        assertEquals(ConnFailure.LOCATION_NOT_ALLOWED, TravelStep.noFix(LocationCode.NOT_ALLOWED))
        for (c in listOf(LocationCode.NONE, LocationCode.TOO_OLD, LocationCode.STOPPED)) assertEquals(ConnFailure.NO_LOCATION, TravelStep.noFix(c))
        assertEquals("Location is off on this phone. Turn it on to get a travel time.", ConnFailure.LOCATION_OFF.text("x").render(Langs.EN))
        // Codes stay unique (the voice log keeps them).
        assertEquals(ConnFailure.entries.size, ConnFailure.entries.map { it.code }.toSet().size)
        assertTrue(TravelStep.FIX_MS + 10_000L < 18_000L)
    }

    @Test fun theConversationHoldsWhereYouAreForHelpers() {
        val rec = ActionRecord(ToolName.CONNECTION, "Travel time to home · your My routing server", ActionStatus.DONE, content = true, located = true)
        val t = ChatTurn("t1", TurnRole.ASSISTANT, "About 25 min.", now, actions = listOf(rec))
        assertEquals(setOf(Personal.LOCATION), HelperContext.personal(listOf(t)))
        // A Home Assistant read stays "what your connections answered".
        val ha = t.copy(actions = listOf(rec.copy(located = false)))
        assertEquals(setOf(Personal.HOME), HelperContext.personal(listOf(ha)))
        // Kept when the conversation is saved and read back.
        fun round(turn: ChatTurn) = ConversationCodec.fromJson(ConversationCodec.toJson(StoredConversation("c1", now, now, listOf(turn))))!!.turns.single()
        assertTrue(round(t).actions.first().located)
        assertEquals(setOf(Personal.LOCATION), HelperContext.personal(listOf(round(t))))
        assertFalse(round(ha).actions.first().located)
    }
}
