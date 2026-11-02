// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A skill's connection step (plan §5.4, §5.5, D13 to D16, D22; C4): what refuses it before its card, its reasons (the
 * first call to a host, a tap for what isn't yours or is risky, a card for a value the model picked), when a spoken yes may
 * answer it (D14), what its card shows (everything that will run, never a key), and what its call gave the rest of the skill.
 */
class ConnStepTest {
    private val now = 1_761_100_000_000L
    private val ha = ConnFixtures.homeAssistant()
    private val tested = ConnTested(now - 1000)
    private val ntfy = ConnFixtures.ntfy().copy(tested = tested, answers = mapOf("https://ntfy.example.org" to ConsentAnswer.ALLOW))
    private val n8n = ConnFixtures.webhook().copy(tested = tested, answers = mapOf("https://n8n.example.org" to ConsentAnswer.ALLOW))

    /** light_off's entity (it has two to pick from and no default). */
    private val downstairs = mapOf("entity" to StepArg.Fixed("light.downstairs"))

    private fun call(c: Connection, op: String, args: Map<String, StepArg> = emptyMap(), check: StepCheck.Equals? = null) =
        ConnStepAction(ConnStepCall(StepKind.CONN, c.id, op, args, "Good night", 1, check))

    private fun x(c: Connection?, network: Boolean = true, net: NetNow? = null, key: Boolean = true, limits: LimitState = LimitState(),
        masks: Map<SecretPart, String> = mapOf(SecretPart.TOKEN to "…AB12")) = StepContext(c, network, net, key, masks, limits, now)

    private fun en(p: Prepared): String = when (p) {
        is Prepared.Refused -> p.text!!.render(Langs.EN)
        is Prepared.Ready -> p.noteText?.render(Langs.EN).orEmpty()
    }

    private fun ready(p: Prepared): Prepared.Ready = p as? Prepared.Ready ?: throw AssertionError(en(p))

    private fun value(v: Any, t: ParamType, s: ValueSource) = StepArg.Value(SkillValue.of(v, t, s))

    @Test fun refusedBeforeItsCardInRobotsWords() {
        // Gone, its operation gone, not tested.
        assertEquals("This connection is gone. Check the skill's steps in Settings › Skills › Connections.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(null))))
        assertEquals(ConnFailure.GONE.text("Home Assistant").render(Langs.EN), en(ConnStep.prepare(call(ha, "nope"), x(ha))))
        assertEquals("Test your Home Assistant first in Settings › Skills › Connections.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha.copy(tested = null)))))
        // D22: the Network toggle, said as such; D2: a key over http; no key saved.
        assertEquals("Robot's network access is off in Android's settings.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, network = false))))
        val http = ha.copy(address = ConnFixtures.url("http://192.168.1.5:8123"))
        assertEquals(ConnFailure.KEY_OVER_HTTP.text("Home Assistant").render(Langs.EN), en(ConnStep.prepare(call(http, "light_off", downstairs), x(http))))
        assertEquals("Your Home Assistant needs its key. Paste it in Settings › Skills › Connections.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, key = false))))
        // Travel time (C6, TravelStepTest): a routing server not tested yet.
        val travel = ConnStepAction(ConnStepCall(StepKind.TRAVEL_TIME, null, null, mapOf("to" to StepArg.Fixed("home")), "Heading home", 1))
        assertEquals("Test your My routing server first in Settings › Skills › Connections.", en(ConnStep.prepare(travel, x(ConnFixtures.routing()))))
        for (p in listOf(ConnStep.prepare(call(ha, "light_off", downstairs), x(null)), ConnStep.prepare(travel, x(null))))
            assertEquals(ActionStatus.FAILED, (p as Prepared.Refused).status)
    }

    @Test fun offlineAndAwayWhenAndroidMaySay() {
        // CN4: unknown (the permission not granted) decides nothing; none is offline; away refuses an http home address only.
        assertTrue(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, net = null)) is Prepared.Ready)
        assertEquals("Robot can't go online right now.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, net = NetNow.NONE))))
        // https at home may be split DNS: tried away, and a failure to connect then says why.
        assertTrue(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, net = NetNow.AWAY)) is Prepared.Ready)
        val home = Connection("c-home01", ConnKind.WEBHOOK, "Lamp server", ConnFixtures.url("http://192.168.1.9"), where = ConnWhere.HOME, tested = tested,
            ops = listOf(ConnOp("on", "Lamp on", OpSpec.Webhook(HttpMethod.POST, "/on"))), answers = mapOf("http://192.168.1.9" to ConsentAnswer.ALLOW))
        assertEquals("Robot can't reach your Lamp server off your home network.", en(ConnStep.prepare(call(home, "on"), x(home, net = NetNow.AWAY))))
        assertTrue(ConnStep.prepare(call(home, "on"), x(home, net = NetNow.LOCAL)) is Prepared.Ready)
        assertEquals(ConnFailure.AWAY, ConnAway.after(ha, NetNow.AWAY, ConnFailure.UNREACHABLE))
        assertEquals(ConnFailure.AWAY, ConnAway.after(ha, NetNow.AWAY, ConnFailure.TIMEOUT))
        assertEquals(ConnFailure.UNREACHABLE, ConnAway.after(ha, NetNow.LOCAL, ConnFailure.UNREACHABLE))
        assertEquals(ConnFailure.UNREACHABLE, ConnAway.after(ha, null, ConnFailure.UNREACHABLE))
        assertEquals(ConnFailure.UNREACHABLE, ConnAway.after(ntfy.copy(where = ConnWhere.INTERNET), NetNow.AWAY, ConnFailure.UNREACHABLE))
        assertEquals(ConnFailure.KEY_REFUSED, ConnAway.after(ha, NetNow.AWAY, ConnFailure.KEY_REFUSED))
    }

    @Test fun theMinutesAndTheDaysLimits() {
        val minute = LimitState(calls = mapOf(ha.id to List(30) { now - 1000L * it }))
        assertEquals("Robot's limit for your Home Assistant was reached: 30 a minute.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, limits = minute))))
        val five = ha.copy(perMinute = 5)
        assertEquals("Robot's limit for your Home Assistant was reached: 5 a minute.",
            en(ConnStep.prepare(call(five, "light_off", downstairs), x(five, limits = LimitState(calls = mapOf(ha.id to List(5) { now - it }))))))
        val day = LimitState(calls = mapOf(ha.id to List(500) { now - 120_000L - 60_000L * it }))
        assertEquals("Robot's limit for your Home Assistant was reached: 500 a day.", en(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha, limits = day))))
    }

    @Test fun valuesAreTypedByTheOperationAndNeverGuessed() {
        // A constant typed by its argument; a value that doesn't fit, or an argument the operation doesn't have, refuses it.
        assertTrue(ConnStep.prepare(call(ha, "dim", mapOf("level" to StepArg.Fixed(40L))), x(ha)) is Prepared.Ready)
        assertTrue(ConnStep.prepare(call(ha, "dim", mapOf("level" to StepArg.Fixed("40"))), x(ha)) is Prepared.Ready)
        assertEquals(ConnFailure.BAD_VALUE.text("Home Assistant").render(Langs.EN), en(ConnStep.prepare(call(ha, "dim", mapOf("level" to StepArg.Fixed(400L))), x(ha))))
        assertEquals(ConnFailure.BAD_VALUE.text("Home Assistant").render(Langs.EN), en(ConnStep.prepare(call(ha, "dim", mapOf("colour" to StepArg.Fixed("red"))), x(ha))))
        assertEquals(ConnFailure.BAD_VALUE.text("Home Assistant").render(Langs.EN),
            en(ConnStep.prepare(call(ha, "light_off", mapOf("entity" to StepArg.Fixed("lock.front_door"))), x(ha))))
        // An earlier step's output that didn't come back: nothing is sent, and the default isn't used instead.
        assertEquals("Robot didn't send this to your Home Assistant: a value from an earlier step didn't come back.",
            en(ConnStep.prepare(call(ha, "dim", mapOf("level" to StepArg.Missing)), x(ha))))
    }

    @Test fun theFirstCallToAHostIsATapWithItsOwnChoices() {
        val first = ha.copy(answers = emptyMap())
        val r = ready(ConnStep.prepare(call(first, "light_off", downstairs), x(first)))
        assertEquals(setOf(AskWhy.CONNECTION_FIRST), r.why)
        assertEquals(listOf("Allow for this connection", "Just this time"), r.choices.map { it.text!!.render(Langs.EN) })
        assertEquals(listOf(ConsentAnswer.ALLOW, ConsentAnswer.ONCE), r.choices.map { (it.action as ConnStepAction).consent })
        assertNull((r.action as ConnStepAction).consent)
        assertEquals("“Good night” wants to send to home.tail1234.ts.net (your Home Assistant): Turn off a light.", r.noteText!!.render(Langs.EN))
        // Another origin's answer never counts (a changed address asks again).
        val moved = ha.copy(answers = mapOf("https://other.example.org" to ConsentAnswer.ALLOW))
        assertTrue(AskWhy.CONNECTION_FIRST in ready(ConnStep.prepare(call(moved, "light_off", downstairs), x(moved))).why)
        // Just this time spares nothing.
        val once = ha.copy(answers = mapOf("https://home.tail1234.ts.net" to ConsentAnswer.ONCE))
        assertTrue(AskWhy.CONNECTION_FIRST in ready(ConnStep.prepare(call(once, "light_off", downstairs), x(once))).why)
    }

    @Test fun reasonsComeFromWhatItDoesAndWhereEachValueCameFrom() {
        // Fixed or yours, a change at home: no reason of its own (the skill's level and the gate's rules decide).
        assertEquals(emptySet<AskWhy>(), ready(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha))).why)
        assertEquals(emptySet<AskWhy>(), ready(ConnStep.prepare(call(ha, "dim", mapOf("level" to value(40L, ParamType.INTEGER, ValueSource.YOURS))), x(ha))).why)
        // Security, deletes and money: a tap.
        val lock = ConnFixtures.homeAssistant("c-ha0001", ConnFixtures.lockDoor, ConnFixtures.homeOffLock, ConnFixtures.garage)
        for (op in listOf("lock_door", "ha_off", "garage")) assertEquals(op, setOf(AskWhy.CONNECTION_TAP), ready(ConnStep.prepare(call(lock, op), x(lock))).why)
        val money = n8n.copy(ops = listOf(ConnFixtures.n8nPost.copy(marks = setOf(SkillEffect.MONEY))))
        val args = mapOf("kind" to StepArg.Fixed("work"), "minutes" to StepArg.Fixed(20L))
        assertEquals(setOf(AskWhy.CONNECTION_TAP), ready(ConnStep.prepare(call(money, "log_trip", args), x(money))).why)
        assertEquals(emptySet<AskWhy>(), ready(ConnStep.prepare(call(n8n, "log_trip", args), x(n8n))).why)
        // Words the model wrote, and anything read from an answer or on the phone: a tap, always.
        for (v in listOf(value("I'm home", ParamType.TEXT, ValueSource.MODEL), value("Lamp is on", ParamType.TEXT, ValueSource.ONLINE),
            value("Meet at noon", ParamType.TEXT, ValueSource.PHONE)))
            assertEquals(setOf(AskWhy.CONNECTION_TAP), ready(ConnStep.prepare(call(ntfy, "tell_family", mapOf("message" to v)), x(ntfy))).why)
        assertEquals(setOf(AskWhy.CONNECTION_TAP), ready(ConnStep.prepare(call(ha, "dim", mapOf("level" to value(40L, ParamType.INTEGER, ValueSource.ONLINE))),
            x(ha))).why)
        // Words inside your sentence carry the model's: still a tap.
        val inText = StepArg.Value(SkillValue.inText("On my way: {{m}}") { SkillValue.of("soon", ParamType.TEXT, ValueSource.MODEL) }!!)
        assertEquals(setOf(AskWhy.CONNECTION_TAP), ready(ConnStep.prepare(call(ntfy, "tell_family", mapOf("message" to inText)), x(ntfy))).why)
        // A value the model picked from your list: its card (a yes may answer it, D14).
        val picked = ready(ConnStep.prepare(call(ha, "dim", mapOf("level" to value(40L, ParamType.INTEGER, ValueSource.MODEL))), x(ha)))
        assertEquals(setOf(AskWhy.CONNECTION), picked.why)
        assertNull(picked.action.tapOnly)
        // Your own words into ntfy: no reason, but any card of it takes a tap (D14: not a change at home).
        val yours = ready(ConnStep.prepare(call(ntfy, "tell_family", mapOf("message" to value("I'm home", ParamType.TEXT, ValueSource.YOURS))), x(ntfy)))
        assertEquals(emptySet<AskWhy>(), yours.why)
        assertEquals(AskWhy.CONNECTION_TAP, yours.action.tapOnly)
    }

    @Test fun aSpokenYesOnlyForAChangeAtHomeAReadOrAnAppWithTheSwitchOn() {
        assertNull(ready(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha))).action.tapOnly)
        assertNull(ready(ConnStep.prepare(call(ha, "light_state"), x(ha))).action.tapOnly)
        // The switch off: every card it shows takes a tap.
        val off = ha.copy(spokenYes = false)
        assertEquals(AskWhy.CONNECTION_TAP, ready(ConnStep.prepare(call(off, "light_off", downstairs), x(off))).action.tapOnly)
        // Code Robot can't see, data sent online, people told: a tap.
        assertEquals(AskWhy.CONNECTION_TAP, ready(ConnStep.prepare(call(ha, "good_night"), x(ha))).action.tapOnly)
        assertEquals(AskWhy.CONNECTION_TAP, ready(ConnStep.prepare(call(n8n, "log_trip", mapOf("kind" to StepArg.Fixed("work"), "minutes" to StepArg.Fixed(2L))),
            x(n8n))).action.tapOnly)
        // Unprepared, it never takes a yes.
        assertEquals(AskWhy.CONNECTION_TAP, call(ha, "light_off", downstairs).tapOnly)
        assertFalse(call(ha, "light_off", downstairs).worksLocked(app.heyrobot.core.ToolName.entries.toSet()))
    }

    @Test fun theCardShowsWhatWillRunAndNeverTheKey() {
        val r = ready(ConnStep.prepare(call(ha, "dim", mapOf("level" to value(40L, ParamType.INTEGER, ValueSource.MODEL))), x(ha)))
        val a = r.action as ConnStepAction
        assertEquals("Dim the lights · your Home Assistant", a.summary().render(Langs.EN))
        assertEquals(listOf(
            "Goes to home.tail1234.ts.net (your Home Assistant)",
            "entity: light.downstairs · saved in the skill",
            "level: 40 · picked by Robot's model",
            "What it does: changes your home",
            "Request: POST https://home.tail1234.ts.net/api/services/light/turn_on",
            "Header: Content-Type: application/json",
            "Authorization: your Home Assistant token",
            "Body: {\"entity_id\":\"light.downstairs\",\"brightness_pct\":40,\"transition\":2}",
        ), a.bodyText()!!.render(Langs.EN).lines())
        assertEquals(setOf(SkillEffect.CHANGES_HOME), a.prepared!!.effects)
        // A script says Robot can't see it; a webhook's secret address and the ntfy topic show masked, never whole.
        assertTrue(ready(ConnStep.prepare(call(ha, "good_night"), x(ha))).action.bodyText()!!.render(Langs.EN).contains("Robot can't see what this does: check it's yours."))
        val nt = ready(ConnStep.prepare(call(ntfy, "tell_family", mapOf("message" to value("I'm home", ParamType.TEXT, ValueSource.YOURS))),
            x(ntfy, masks = mapOf(SecretPart.TOKEN to "…tk12", SecretPart.TOPIC to "…ly42"))))
        val body = nt.action.bodyText()!!.render(Langs.EN)
        assertTrue(body, body.contains("\"topic\":\"…ly42\"") && body.contains("Authorization: your Jarvis's ntfy token") && body.contains("tells people"))
        // History keeps the summary only (D20): your operation's title and your connection's name, never an address.
        assertFalse(a.describe().contains("tail1234"))
        assertEquals("ConnStepAction(1)", a.toString())
        assertEquals("ConnPrepared(…)", a.prepared.toString())
    }

    @Test fun aLongBodyIsShownCutWithItsCount() {
        val long = "x".repeat(480)
        val r = ready(ConnStep.prepare(call(ntfy, "tell_family", mapOf("message" to value(long + long + long, ParamType.TEXT, ValueSource.FIXED))),
            x(ntfy.copy(ops = listOf(ConnFixtures.publish.copy(params = linkedMapOf("message" to OpParam(ParamType.TEXT, maxLen = 4000),
                "urgency" to OpParam(ParamType.INTEGER, min = 3.0, max = 4.0, default = "3"))))))))
        val lines = r.action.bodyText()!!.render(Langs.EN).lines()
        assertTrue(lines.any { it.startsWith("Body: ") && it.length == "Body: ".length + ConnStep.PREVIEW_BODY })
        assertTrue(lines.last(), lines.last().matches(Regex("\\(and \\d+ more characters\\)")))
    }

    @Test fun whatACallGaveTheSkill() {
        val read = ready(ConnStep.prepare(call(ha, "light_state"), x(ha))).action as ConnStepAction
        val op = ha.op("light_state")!!
        val ok = ConnStep.result(read, ha, op, CallEnd.Answered(ReplyRead.Ok(200, mapOf("state" to "on", "attributes" to mapOf("friendly_name" to "Downstairs")))), null)
        assertEquals(ActionStatus.DONE, ok.status)
        assertEquals("Read from your Home Assistant.", ok.text!!.render(Langs.EN))
        assertEquals(listOf("state: on", "name: Downstairs"), ok.bodyText!!.render(Langs.EN).lines())
        val ran = ok.conn!!
        assertEquals("on", ran.outputs.getValue("state").value)
        assertEquals(setOf(ValueSource.ONLINE), ran.outputs.getValue("name").sources)
        assertTrue(ran.content && ok.content)
        assertEquals(CheckOutcome.NONE, ran.check)
        // A state outside the choices you listed is left unset, never wrong.
        val odd = ConnStep.result(read, ha, op, CallEnd.Answered(ReplyRead.Ok(200, mapOf("state" to "unavailable"))), null)
        assertFalse("state" in odd.conn!!.outputs)
        // A write whose reply keeps nothing isn't content.
        val off = ready(ConnStep.prepare(call(ha, "light_off", downstairs), x(ha))).action as ConnStepAction
        val done = ConnStep.result(off, ha, ha.op("light_off")!!, CallEnd.Answered(ReplyRead.Ok(200, listOf<Any>())), null)
        assertEquals("Your Home Assistant said yes.", done.text!!.render(Langs.EN))
        assertFalse(done.content)
    }

    @Test fun checksAndFailuresSayWhatHappened() {
        val args = mapOf("kind" to StepArg.Fixed("work"), "minutes" to StepArg.Fixed(20L))
        val a = ready(ConnStep.prepare(call(n8n, "log_trip", args), x(n8n))).action as ConnStepAction
        val op = n8n.op("log_trip")!!
        val good = ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Ok(200, mapOf("ok" to true, "count" to 3L))), null)
        assertEquals(CheckOutcome.PASSED, good.conn!!.check)
        assertEquals(3L, good.conn!!.outputs.getValue("count").value)
        val bad = ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Ok(200, mapOf("ok" to false))), null)
        assertEquals(ActionStatus.FAILED, bad.status)
        assertEquals(CheckOutcome.FAILED, bad.conn!!.check)
        assertEquals("Your n8n's answer wasn't what this step checks for.", bad.text!!.render(Langs.EN))
        assertEquals("your n8n's answer wasn't what the step checks for", bad.conn!!.what!!.render(Langs.EN))
        // The step's own check too, on an operation that has none.
        val ha2 = ready(ConnStep.prepare(call(ha, "light_off", downstairs, check = StepCheck.Equals("/0/state", "off")), x(ha))).action as ConnStepAction
        assertEquals(CheckOutcome.FAILED, ConnStep.result(ha2, ha, ha.op("light_off")!!, CallEnd.Answered(ReplyRead.Ok(200, listOf(mapOf("state" to "on")))), null).conn!!.check)
        assertEquals(CheckOutcome.PASSED, ConnStep.result(ha2, ha, ha.op("light_off")!!, CallEnd.Answered(ReplyRead.Ok(200, listOf(mapOf("state" to "off")))), null).conn!!.check)
        // A server's own words are never said: its status, in Robot's words.
        val refused = ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Failed(ConnFailure.KEY_REFUSED, 401)), null)
        assertEquals("Your n8n refused Robot's key. Make a new one and paste it in Settings › Skills › Connections.", refused.text!!.render(Langs.EN))
        assertEquals("Your n8n said no (error 503).", ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Failed(ConnFailure.SAID_NO, 503)), null).text!!.render(Langs.EN))
        // A write that may have gone out says so, and is never sent again by itself; a read doesn't.
        val sent = ConnStep.result(a, n8n, op, CallEnd.Failed(ConnFailure.TIMEOUT, sent = true), null)
        assertEquals("Your n8n didn't answer in time. It may have reached your n8n, so Robot won't send it again by itself.", sent.text!!.render(Langs.EN))
        assertTrue(sent.conn!!.sent)
        val read = ready(ConnStep.prepare(call(ha, "light_state"), x(ha))).action as ConnStepAction
        assertEquals("Your Home Assistant didn't answer in time.", ConnStep.result(read, ha, ha.op("light_state")!!, CallEnd.Failed(ConnFailure.TIMEOUT, true), null)
            .text!!.render(Langs.EN))
        // Away from your network, a home connection's failure to connect says so.
        assertEquals("Robot can't reach your Home Assistant off your home network.",
            ConnStep.result(read, ha, ha.op("light_state")!!, CallEnd.Failed(ConnFailure.UNREACHABLE, false), NetNow.AWAY).text!!.render(Langs.EN))
        // Stopped: a write's card says it may have reached the server.
        assertEquals(ActionStatus.DECLINED, ConnStep.result(a, n8n, op, CallEnd.Stopped(true), null).status)
        assertEquals("Stopped. It may have reached your n8n.", a.stoppedText!!.render(Langs.EN))
        assertNull(read.stoppedText)
    }

    /** C12's M2: a scene (or a group through homeassistant's services) may unlock a door Robot doesn't see: a tap, never a spoken yes. */
    @Test fun aScenesCardTakesATap() {
        val leaving = ConnOp("leaving", "Leaving", OpSpec.HaCall("scene", "turn_on"), mapOf("entity" to ConnFixtures.entity("scene.leaving")))
        val front = ConnOp("front", "Front of the house", OpSpec.HaCall("homeassistant", "turn_on"), mapOf("entity" to ConnFixtures.entity("group.front")))
        val c = ConnFixtures.homeAssistant("c-ha0001", leaving, front, ConnFixtures.lightOff)
        assertTrue(ConnCheck.valid(c))
        for (op in listOf("leaving", "front")) {
            val r = ready(ConnStep.prepare(call(c, op), x(c)))
            val a = r.action as ConnStepAction
            assertFalse(op, a.prepared!!.spokenYes)
            assertEquals(op, AskWhy.CONNECTION_TAP, a.tapOnly)
            assertTrue(op, SkillEffect.RUNS_CODE in a.prepared!!.effects)
            assertTrue(op, a.bodyText()!!.render(Langs.EN).contains(ConnStrings.cantSeeScript().render(Langs.EN)))
        }
        // A light, beside them, still takes a spoken yes.
        assertTrue((ready(ConnStep.prepare(call(c, "light_off", downstairs), x(c))).action as ConnStepAction).prepared!!.spokenYes)
    }

    /** C12's L4: a write's answer that came (2xx) but couldn't be read may still have done its work: said, never sent again. */
    @Test fun anUnreadableAnswerToAWriteSaysItMayHaveHappened() {
        val args = mapOf("kind" to StepArg.Fixed("work"), "minutes" to StepArg.Fixed(20L))
        val a = ready(ConnStep.prepare(call(n8n, "log_trip", args), x(n8n))).action as ConnStepAction
        val op = n8n.op("log_trip")!!
        val e = ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Failed(ConnFailure.UNREADABLE, 200)), null)
        assertEquals(ActionStatus.FAILED, e.status)
        val words = e.text!!.render(Langs.EN)
        assertTrue(words, words.startsWith(ConnFailure.UNREADABLE.text(n8n.name, 200).render(Langs.EN)))
        assertTrue(words, words.endsWith(ConnStrings.mayHaveSent("name" to n8n.name).render(Langs.EN)))
        assertTrue(e.conn!!.sent)
        // A server that said no did nothing to say "may" about; a read changes nothing.
        assertFalse(ConnStep.result(a, n8n, op, CallEnd.Answered(ReplyRead.Failed(ConnFailure.SAID_NO, 500)), null).text!!.render(Langs.EN)
            .contains(ConnStrings.mayHaveSent("name" to n8n.name).render(Langs.EN)))
        val read = ready(ConnStep.prepare(call(ha, "light_state"), x(ha))).action as ConnStepAction
        assertEquals(ConnFailure.UNREADABLE.text(ha.name, 200).render(Langs.EN),
            ConnStep.result(read, ha, ha.op("light_state")!!, CallEnd.Answered(ReplyRead.Failed(ConnFailure.UNREADABLE, 200)), null).text!!.render(Langs.EN))
    }

    @Test fun theVoiceLogsNumbers() {
        assertEquals(1, ConnLog.kind(ConnKind.HOME_ASSISTANT))
        assertEquals(0, ConnLog.result(CallEnd.Answered(ReplyRead.Ok(200, null))))
        assertEquals(411, ConnLog.result(CallEnd.Answered(ReplyRead.Failed(ConnFailure.KEY_REFUSED, 401))))
        assertEquals(513, ConnLog.result(CallEnd.Answered(ReplyRead.Failed(ConnFailure.SAID_NO, 503))))
        assertEquals(4, ConnLog.result(CallEnd.Failed(ConnFailure.TIMEOUT, false)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), listOf(10L, 300L, 900L, 1500L, 4000L, 9000L, 20_000L).map(ConnLog::ms))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), listOf(0, 10, 2000, 20_000, 200_000, 300_000).map(ConnLog::size))
        assertEquals(31, ConnLog.timeAndSize(600L, 12))
        assertTrue(ConnFailure.entries.map { it.code }.let { it.distinct() == it && it.all { c -> c in 1..99 } })
    }
}
