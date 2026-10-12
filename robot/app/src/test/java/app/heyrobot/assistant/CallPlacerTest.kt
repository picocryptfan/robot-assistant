package app.heyrobot.assistant

import android.content.Intent
import app.heyrobot.assistant.car.CallPlacer
import app.heyrobot.core.car.CallRules
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CallWay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.8's calls (design §4.5 flow 2, §4.9 rule 6): the dialer by default; ACTION_CALL only after car mode's spoken yes with
 * the Phone permission and both switches on; an emergency number only ever opens the dialer. The emergency class is a
 * seam: these tests mark an ordinary number as one, so nothing here names or dials a real emergency number.
 */
class CallPlacerTest {
    @Test fun anEmergencyNumberIsNeverCalledDirectly() {
        assertEquals(Intent.ACTION_DIAL, CallPlacer.actionFor(CallWay.CALL, emergency = true))
        assertEquals(Intent.ACTION_DIAL, CallPlacer.actionFor(CallWay.DIAL, emergency = true))
        assertEquals(Intent.ACTION_DIAL, CallPlacer.actionFor(CallWay.DIAL, emergency = false))
        assertEquals(Intent.ACTION_CALL, CallPlacer.actionFor(CallWay.CALL, emergency = false))
    }

    @Test fun aDirectCallNeedsTheSettingThePermissionAndTheSafetyRow() {
        assertEquals(CallWay.DIAL, CallRules().way)
        assertEquals(CallWay.DIAL, CallRules(CallSetting.CALL_AFTER_YES, hasCallPermission = false).way)
        assertEquals(CallWay.DIAL, CallRules(CallSetting.CALL_AFTER_YES, hasCallPermission = true, callAfterSpokenYes = false).way)
        assertEquals(CallWay.CALL, CallRules(CallSetting.CALL_AFTER_YES, hasCallPermission = true).way)
    }

    @Test fun everyCallGoesThroughThePlacerAndChecksEmergencyFirst() {
        val root = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot")
        val placer = File(root, "assistant/car/CallPlacer.kt").readText()
        // run(): a direct call only when asked, not an emergency number, and with the permission at that moment.
        val run = placer.substringAfter("fun run(").substringBefore("\n\n")
        assertTrue(run, run.contains("a.direct && !a.emergency && Assistant.granted(ctx, Manifest.permission.CALL_PHONE)"))
        assertTrue(run, run.contains("intentFor(number, way, a.emergency)"))
        // Nothing else starts ACTION_CALL or builds a tel: link.
        val others = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "CallPlacer.kt" }
            .filter { f -> f.readText().let { it.contains("ACTION_CALL") || it.contains("\"tel\"") || it.contains("tel:") } }.map { it.name }.toList()
        assertEquals(emptyList<String>(), others)
        // The car's spoken flow marks the number for the placer, which checks Android's list (or the region's) again.
        val panel = File(root, "assistant/handsfree/PanelHandsFree.kt").readText()
        assertTrue(panel.contains("emergency = CallPlacer.isEmergency(app, e.number, region)"))
        // A direct call is car mode's alone: a headset session's spoken call opens the dialer, and only the car's trusted
        // session calls from the lock screen.
        val call = panel.substringAfter("private suspend fun call(").substringBefore("private suspend fun callAnswer(")
        assertTrue(call, call.contains("CallPlacer.rules(app).forSession(car = inCar)"))
        // The spoken read-back names who and their number: in a headset session only where a personal answer may play,
        // else the call tool's card on the screen.
        assertTrue(call, call.contains("if (s0 == null || (!inCar && !personalPlays(s0))) {\n            host.tool(replyId, RoutineStep.Tool(Action.Call(asked)))"))
        assertTrue(call.indexOf("personalPlays(s0)") < call.indexOf("CallTarget.plan("))
        assertTrue(call, call.contains("CallTarget.plan(c.who, candidates, rules, phone.locked, inCar && CarModeController.trusted"))
        assertEquals(1, Regex("CallPlacer\\.rules\\(").findAll(panel).count())
    }
}
