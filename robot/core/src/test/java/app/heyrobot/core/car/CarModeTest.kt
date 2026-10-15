// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.Approval
import app.heyrobot.core.GateDecision
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.headset.ListenWith
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpokenLang
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L
private const val HOUR = 3_600_000L

/** Car mode's states (design §4.8, §4.11 CarModeState) and the trusted session (§4.6). */
class CarModeTest {
    private val carSco = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
    private val memory = DeviceMemory().remember(carSco, DeviceRole.CAR, 0)!!.first
        .remember(SeenDevice(RouteType.BT_A2DP, "Van", "XX:XX:XX:XX:56:78"), DeviceRole.CAR, 0)!!.first
    private val car = memory.byNumber(1)!!
    private val van = memory.byNumber(2)!!
    private val s = CarSettings()

    private fun run(vararg inputs: CarInput, settings: CarSettings = s, start: CarState = CarState()): Pair<CarState, List<List<CarEffect>>> {
        var st = start
        val out = mutableListOf<List<CarEffect>>()
        for (i in inputs) {
            val (n, fx) = CarModeMachine.on(st, i, settings)
            st = n
            out += fx
        }
        return st to out
    }

    private fun active(at: Long = 0, settings: CarSettings = s): CarState = run(CarInput.CarSeen(car, at), CarInput.Started(at + 500), settings = settings).first

    @Test fun theCarConnectsAndCarModeStarts() {
        val (st, fx) = run(CarInput.CarSeen(car, 0), CarInput.Started(1_000))
        assertEquals(listOf(CarEffect.StartService), fx[0])
        assertEquals(listOf(CarEffect.Logged(true, CarWhy.CAR_CONNECTED), CarEffect.CarModeFlag(true), CarEffect.Say(CarModeMachine.CAR_ON), CarEffect.ShowCarScreen), fx[1])
        assertEquals(CarPhase.ACTIVE, st.phase)
        assertEquals(1, st.car)
        assertNull(st.trusted)
        // With Do Not Disturb while driving and Android's car mode on.
        val (_, fx2) = run(CarInput.CarSeen(car, 0), CarInput.Started(1_000), settings = s.copy(dndWhileDriving = true, androidCarMode = true))
        assertTrue(CarEffect.SetDnd(true) in fx2[1] && CarEffect.AndroidCarMode(true) in fx2[1])
    }

    @Test fun aRefusedBackgroundStartWaitsForATap() {
        val (st, fx) = run(CarInput.CarSeen(car, 0), CarInput.StartRefused(100), CarInput.Start(5_000), CarInput.Started(5_200))
        assertEquals(listOf(CarEffect.Notify(CarModeMachine.START_NOTICE)), fx[1])
        assertEquals(listOf(CarEffect.StartService), fx[2])
        assertEquals(CarEffect.Logged(true, CarWhy.TAPPED), fx[3][0])
        assertEquals(CarPhase.ACTIVE, st.phase)
        // The car leaving before it started: nothing more.
        assertEquals(CarPhase.OFF, run(CarInput.CarSeen(car, 0), CarInput.StartRefused(100), CarInput.CarGone(car, 200)).first.phase)
    }

    @Test fun notWithoutTheSettingOrADisabledCar() {
        assertEquals(CarPhase.OFF, run(CarInput.CarSeen(car, 0), settings = s.copy(startOnConnect = false)).first.phase)
        assertEquals(CarPhase.OFF, run(CarInput.CarSeen(car.copy(enabled = false), 0)).first.phase)
        // A headset is never a car.
        assertEquals(CarPhase.OFF, run(CarInput.CarSeen(car.copy(role = DeviceRole.HEADSET), 0)).first.phase)
        // "Car mode" by voice with the setting off still starts it, for the connected car.
        val (st, fx) = run(CarInput.CarSeen(car, 0), CarInput.Start(10), CarInput.Started(20), settings = s.copy(startOnConnect = false))
        assertEquals(CarPhase.ACTIVE, st.phase)
        assertEquals(1, st.car)
        assertEquals(CarEffect.Logged(true, CarWhy.ASKED), fx[2][0])
    }

    @Test fun aFlapWithinTheGraceKeepsCarModeAndItsTrust() {
        var st = active()
        st = run(CarInput.Unlocked(1_000), start = st).first
        assertTrue(st.trustedAt(2_000))
        val (ending, fx) = run(CarInput.CarGone(car, 10_000), start = st)
        assertEquals(CarPhase.ENDING, ending.phase)
        assertEquals(listOf(CarEffect.WakeAt(40_000)), fx[0])
        // While the car is gone the phone has left it: car functions need the unlock again (the session waits).
        assertFalse(ending.trustedAt(10_000))
        assertFalse(ending.trustedAt(39_999))
        // Back after 20 s: still on, still trusted.
        val (back, fx2) = run(CarInput.CarSeen(car, 30_000), start = ending)
        assertEquals(CarPhase.ACTIVE, back.phase)
        assertEquals(emptyList<CarEffect>(), fx2[0])
        assertTrue(back.trustedAt(31_000))
        // A tick before the end does nothing.
        assertEquals(ending, run(CarInput.Tick(39_999), start = ending).first)
    }

    @Test fun theCarLeavingEndsItAfterTheGrace() {
        val st = run(CarInput.Unlocked(1_000), start = active()).first
        val (off, fx) = run(CarInput.CarGone(car, 10_000), CarInput.Tick(40_000), start = st)
        assertEquals(CarPhase.OFF, off.phase)
        assertNull(off.trusted)
        assertEquals(listOf(CarEffect.Trusted(false, TrustEnd.CAR_GONE), CarEffect.CarModeFlag(false), CarEffect.StopService,
            CarEffect.Logged(false, CarWhy.CAR_GONE)), fx[1])
        // Connecting again starts a new car mode, without the trust.
        val (again, _) = run(CarInput.CarSeen(car, 50_000), CarInput.Started(50_100), start = off)
        assertEquals(CarPhase.ACTIVE, again.phase)
        assertNull(again.trusted)
        // A grace of 0 ends at the next tick.
        val (z, _) = run(CarInput.CarGone(car, 10_000), CarInput.Tick(10_000), start = active(settings = s.copy(endGraceSeconds = 0)), settings = s.copy(endGraceSeconds = 0))
        assertEquals(CarPhase.OFF, z.phase)
    }

    @Test fun theTrustedSession() {
        // After one unlock in the car: until the car leaves or 4 hours.
        val st = active()
        assertFalse(st.trustedAt(0))
        val (t, fx) = run(CarInput.Unlocked(MIN), start = st)
        assertEquals(listOf(CarEffect.Trusted(true, TrustEnd.UNLOCKED_IN_CAR), CarEffect.WakeAt(MIN + 4 * HOUR)), fx[0])
        assertTrue(t.trustedAt(MIN + 4 * HOUR - 1))
        assertFalse(t.trustedAt(MIN + 4 * HOUR))
        // A second unlock doesn't extend it.
        assertEquals(t, run(CarInput.Unlocked(2 * HOUR), start = t).first)
        val (expired, fx2) = run(CarInput.Tick(MIN + 4 * HOUR), start = t)
        assertNull(expired.trusted)
        assertEquals(listOf(CarEffect.Trusted(false, TrustEnd.EXPIRED)), fx2[0])
        assertEquals(CarPhase.ACTIVE, expired.phase)
        // Hours as set.
        val two = run(CarInput.Unlocked(0), start = active(settings = s.copy(trustedHours = 2)), settings = s.copy(trustedHours = 2)).first
        assertEquals(2 * HOUR, two.trusted!!.until)
        // Never: no trust. Always: from the start, no unlock needed.
        assertNull(run(CarInput.Unlocked(0), start = active(settings = s.copy(lockUse = CarLockUse.NEVER)), settings = s.copy(lockUse = CarLockUse.NEVER)).first.trusted)
        val always = s.copy(lockUse = CarLockUse.ALWAYS)
        val (a, afx) = run(CarInput.CarSeen(car, 0), CarInput.Started(100), settings = always)
        assertTrue(a.trustedAt(200))
        assertTrue(CarEffect.Trusted(true, TrustEnd.ALWAYS) in afx[1])
        // Not before car mode is on, and not without a car.
        assertNull(run(CarInput.Unlocked(0)).first.trusted)
        val manual = run(CarInput.Start(0), CarInput.Started(10), CarInput.Unlocked(20)).first
        assertEquals(CarPhase.ACTIVE, manual.phase)
        assertNull(manual.trusted)
    }

    @Test fun endingByVoiceKeepsItOffUntilTheCarReconnects() {
        val st = run(CarInput.Unlocked(0), start = active()).first
        val (off, fx) = run(CarInput.End(MIN), start = st)
        assertEquals(CarPhase.OFF, off.phase)
        assertTrue(CarEffect.Say(CarModeMachine.CAR_OFF) in fx[0])
        assertTrue(CarEffect.Trusted(false, TrustEnd.CAR_MODE_ENDED) in fx[0])
        assertEquals(1, off.declined)
        // The car still connected (a flap of another profile) doesn't restart it.
        assertEquals(CarPhase.OFF, run(CarInput.CarSeen(car, 2 * MIN), start = off).first.phase)
        // After it really left and came back: it starts.
        assertEquals(CarPhase.CONNECTED, run(CarInput.CarGone(car, 3 * MIN), CarInput.CarSeen(car, 4 * MIN), start = off).first.phase)
        // "Car mode" by voice starts it at once.
        assertEquals(CarPhase.CONNECTED, run(CarInput.Start(2 * MIN), start = off).first.phase)
    }

    @Test fun twoRememberedCars() {
        // Car mode follows the first; the second connecting changes nothing.
        val st = run(CarInput.CarSeen(van, 1_000), start = active()).first
        assertEquals(1, st.car)
        assertEquals(setOf(1, 2), st.present)
        // The first leaves for good while the second is still there: car mode starts again for the second.
        val (next, fx) = run(CarInput.CarGone(car, 10_000), CarInput.Tick(40_000), start = st)
        assertEquals(CarPhase.CONNECTED, next.phase)
        assertEquals(2, next.car)
        assertEquals(CarEffect.StartService, fx[1].last())
        assertTrue(CarEffect.Logged(false, CarWhy.CAR_GONE) in fx[1])
        // The second leaving while the first drives: nothing.
        assertEquals(CarPhase.ACTIVE, run(CarInput.CarGone(van, 2_000), start = st).first.phase)
    }

    @Test fun aVoiceRestartLosesTheTrust() {
        // :voice restarted: the state is gone; the car is reported again and car mode starts without the trust.
        val (st, _) = run(CarInput.CarSeen(car, HOUR), CarInput.Started(HOUR + 100))
        assertNull(st.trusted)
        assertFalse(st.trustedAt(HOUR + 200))
    }

    @Test fun manualCarModeEndsAfterFourHours() {
        val (st, fx) = run(CarInput.Start(0), CarInput.Started(10))
        assertNull(st.car)
        assertTrue(CarEffect.WakeAt(10 + CarModeMachine.MANUAL_MAX_MS) in fx[1])
        assertEquals(CarPhase.ACTIVE, run(CarInput.Tick(CarModeMachine.MANUAL_MAX_MS), start = st).first.phase)
        val (off, ofx) = run(CarInput.Tick(10 + CarModeMachine.MANUAL_MAX_MS), start = st)
        assertEquals(CarPhase.OFF, off.phase)
        assertTrue(CarEffect.Logged(false, CarWhy.TIMEOUT) in ofx[0])
        // The car connecting while it runs without one: it becomes that car's.
        assertEquals(1, run(CarInput.CarSeen(car, 100), start = st).first.car)
    }

    @Test fun detectionAndSetup() {
        assertEquals(CarSeen.Car(car), CarDetect.of(carSco, memory))
        assertEquals(CarSeen.Car(car), CarDetect.of(carSco.copy(type = RouteType.BT_A2DP), memory))
        val stranger = SeenDevice(RouteType.BT_SCO, "Rental", "XX:XX:XX:XX:AA:BB", BtClass.CAR_AUDIO)
        assertEquals(CarSeen.Suggest(stranger), CarDetect.of(stranger, memory))
        assertEquals(CarSeen.Suggest(stranger.copy(btClass = BtClass.HANDSFREE)), CarDetect.of(stranger.copy(btClass = BtClass.HANDSFREE), memory))
        assertEquals(CarSeen.NotCar, CarDetect.of(stranger.copy(btClass = BtClass.HEADPHONES), memory))
        assertEquals(CarSeen.NotCar, CarDetect.of(stranger.copy(btClass = BtClass.UNKNOWN), memory))
        assertTrue(CarDetect.surelyCar(stranger))
        assertFalse(CarDetect.surelyCar(stranger.copy(btClass = BtClass.HANDSFREE)))
        assertEquals("Robot sees “MyCar” (…12:34). Is this your car?", CarDetect.question(carSco, SpokenLang.EN))
        assertEquals("Robot voit « MyCar » (…12:34). Est-ce ton auto?", CarDetect.question(carSco, SpokenLang.FR))
        assertFalse(CarSettings.hasCar(DeviceMemory()))
        assertTrue(CarSettings.hasCar(memory))
        assertFalse(CarSettings.hasCar(memory.setEnabled(1, false).setEnabled(2, false)))
    }

    @Test fun settingsDefaultsAndJson() {
        val d = CarSettings()
        assertTrue(d.startOnConnect && d.speakAsCall && d.voiceReplies)
        assertFalse(d.dndWhileDriving || d.androidCarMode || d.keepScreenOn || d.driveBrief)
        assertEquals(30_000L, d.endGraceMs)
        assertEquals(4 * HOUR, d.trustedMs)
        assertEquals(ListenWith.PHONE_MIC, d.wakeListenWith)
        assertEquals(CarLockUse.AFTER_ONE_UNLOCK, d.lockUse)
        assertEquals(CallSetting.OPEN_DIALER, d.calls)
        assertEquals(CarAnnounce.SENDER_THEN_ASK, d.announce)
        assertEquals(CarAnnounceFrom.DND_ALLOWS, d.announceFrom)
        assertEquals(ReadAloud.ASK, d.readAloud)
        assertNull(d.home)
        assertEquals(d, CarSettings.fromJson(null))
        assertEquals(d, CarSettings.fromJson(JSONObject()))
        val home = SavedPlace.of("  12 rue\nPrincipale ", 45.5, -73.6)!!
        assertEquals("12 rue Principale", home.label)
        val changed = CarSettings(false, 60, false, ListenWith.HEADSET_MIC, CarLockUse.NEVER, 8, CallSetting.CALL_AFTER_YES,
            CarAnnounce.READ_WHOLE, CarAnnounceFrom.EVERYONE, false, ReadAloud.ALWAYS, true, true, true, home, SavedPlace.of("Work", 45.4, -73.5), true)
        assertEquals(changed, CarSettings.fromJson(JSONObject(changed.toJson().toString())))
        val odd = CarSettings.fromJson(JSONObject().put("endGraceSeconds", 9_999).put("trustedHours", 0).put("lockUse", "SOMETIMES")
            .put("home", JSONObject().put("label", "x").put("lat", 200.0).put("lon", 0.0)))
        assertEquals(300, odd.endGraceSeconds)
        assertEquals(1, odd.trustedHours)
        assertEquals(CarLockUse.AFTER_ONE_UNLOCK, odd.lockUse)
        assertNull(odd.home)
        assertNull(SavedPlace.of(" ", 1.0, 1.0))
        assertNull(SavedPlace.of("x", Double.NaN, 1.0))
        assertFalse(home.toString().contains("Principale"))
    }

    /**
     * 1.0 (the user, 13 Oct 2026: no driving restrictions): "Hold screen actions until I park" (holdWhileDriving) and "New
     * texts while driving" (newTexts) are gone. A car.json that still has them loads with every other row as it was, and
     * the next save leaves them out.
     */
    @Test fun anOldCarFileWithTheDrivingRowsStillLoads() {
        val old = """{"v":1,"on":true,"car":{"startOnConnect":false,"newTexts":"KEEP_FOR_PARKING","holdWhileDriving":true,""" +
            """"dndWhileDriving":true,"voiceReplies":false,"calls":"CALL_AFTER_YES","endGraceSeconds":60}}"""
        val f = app.heyrobot.core.output.CarFile.decode(old)
        assertTrue(f.on)
        assertEquals(CarSettings(startOnConnect = false, dndWhileDriving = true, voiceReplies = false, calls = CallSetting.CALL_AFTER_YES, endGraceSeconds = 60), f.settings)
        for (v in listOf("OPEN_CHEOGRAM", "SOMETHING_ELSE")) {
            val o = JSONObject().put("newTexts", v).put("holdWhileDriving", false).put("keepScreenOn", true)
            assertEquals(CarSettings(keepScreenOn = true), CarSettings.fromJson(o))
        }
        // Written again, without the old keys.
        val json = JSONObject(f.encode()).getJSONObject("car")
        assertFalse(json.has("holdWhileDriving") || json.has("newTexts"))
        // Through the store, as the app reads and changes it.
        val dir = java.nio.file.Files.createTempDirectory("car-old").toFile()
        try {
            java.io.File(dir, app.heyrobot.core.output.HandsFreeStore.CAR).writeText(old)
            java.io.File(dir, app.heyrobot.core.output.HandsFreeStore.LEGACY_PARK).writeText("""{"v":1,"items":[{"code":1,"summary":"Text Bob","at":1}]}""")
            val store = app.heyrobot.core.output.HandsFreeStore(dir)
            assertEquals(f, store.car())
            store.editCar { it.copy(settings = it.settings.copy(keepScreenOn = true)) }
            val saved = java.io.File(dir, app.heyrobot.core.output.HandsFreeStore.CAR).readText()
            assertFalse(saved, saved.contains("holdWhileDriving") || saved.contains("newTexts"))
            assertTrue(store.car().settings.keepScreenOn && store.car().settings.dndWhileDriving)
            // What 1.0's test builds kept for parking is deleted, not read.
            store.dropLegacy()
            assertFalse(java.io.File(dir, app.heyrobot.core.output.HandsFreeStore.LEGACY_PARK).exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}

/** What car mode loosens on the lock screen, and what it doesn't (§4.6). */
class CarGateTest {
    private val settings = RobotSettings()

    @Test fun carFunctionsInATrustedSession() {
        // What car mode loosens is decided in PanelHandsFree (a car function locked, outside the trusted session: "Unlock
        // once"); actions themselves go through ApprovalGate as anywhere else.
        assertTrue(CarGate.allowed(CarFunction.CALL, locked = true, trusted = true))
        assertFalse(CarGate.allowed(CarFunction.CALL, locked = true, trusted = false))
        assertTrue(CarGate.allowed(CarFunction.CALL, locked = false, trusted = false))
        assertEquals("Car mode · unlocked features", CarScreen.title(true, SpokenLang.EN))
        assertEquals("Mode auto", CarScreen.title(false, SpokenLang.FR))
    }
}
