// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The car fixes that live in Android code no unit test can run: the wiring stays (the 1.1.2 audit's car findings). */
class CarWiringTest {
    private fun code(path: String) = File("src/main/java/app/heyrobot/$path").readText().replace(Regex("\\s+"), " ")
    private val screen = code("assistant/car/CarModeActivity.kt")
    private val controller = code("assistant/car/CarModeController.kt")

    private fun has(text: String, part: String) = assertTrue(part, text.contains(part))

    @Test fun leftoversClearOnceBeforeTheDevicesAreRead() {
        val reload = controller.substringAfter("fun reload()").substringBefore("fun stop()")
        assertTrue(reload.indexOf("CarModeMachine.leftovers(state)") in 0 until reload.indexOf("devicesChanged()"))
        has(controller, "CarEffect.ClearLockCar -> LockSignals.car(app, false, false)")
    }

    @Test fun carModeFromBeforeARebootOrAKillIsCleared() {
        // review-develop-14-b L1: :lock clears car mode from before the boot at BOOT_COMPLETED...
        val receiver = code("lock/LockAlarmReceiver.kt")
        has(receiver, "Intent.ACTION_BOOT_COMPLETED -> LockEngine.rebooted(app)")
        has(code("lock/LockEngine.kt").substringAfter("fun rebooted("), "it.afterReboot(System.currentTimeMillis() - SystemClock.elapsedRealtime())")
        // ...and :voice's start sends the leftovers' car-off whatever the car mode setting (no car session lives past a restart).
        val hub = code("assistant/handsfree/HandsFreeHub.kt")
        has(hub.substringAfter("fun start()").substringBefore("fun stop()"), "running = true reload()")
        has(hub.substringAfter("fun reload()").substringBefore("fun editDevices("), "if (running) CarModeController.reload()")
        assertTrue(app.heyrobot.core.car.CarEffect.ClearLockCar in app.heyrobot.core.car.CarModeMachine.leftovers(app.heyrobot.core.car.CarState()))
    }

    @Test fun devicesAndTheScreenWakeTheTimers() {
        has(controller.substringAfter("fun devicesChanged()"), "tickIfDue() CarModeMachine.forgotten(state, HandsFreeHub.memory, now())")
        has(controller, "ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON)")
    }

    @Test fun carVoiceLeavesASessionsPlaybackAlone() {
        has(controller, "if (HandsFreeHub.session == null) VoiceHub.tts.setPlayback(VoiceHub.settings.playback)")
    }

    @Test fun trustFollowsASomeoneElseLock() {
        has(controller, "CarGate.trustedNow(state.trustedAt(System.currentTimeMillis()), PhoneLockNow.someoneElse(app))")
    }

    @Test fun theCarScreenAsksAboutTheLockScreenNotPhoneLock() {
        assertFalse(screen.contains("CarOpen.maps(HandsFreeHub.phone().locked"))
        assertFalse(screen.contains("CarOpen.app(app.pkg, HandsFreeHub.phone().locked"))
        assertFalse(screen.contains("CarOpen.app(pkg, HandsFreeHub.phone().locked"))
        has(screen, "PhoneLockNow.mapsInCar(this)")
        has(controller, "PhoneLockNow.mapsInCar(ctx)")
    }

    @Test fun aLockedPhoneDoesntStartCarModeByVoice() {
        val on = code("assistant/handsfree/PanelHandsFree.kt").substringAfter("HandsFreeCommand.CarModeOn ->").substringBefore("HandsFreeCommand.CarModeOff")
        has(on, "Assistant.keyguard(app) -> reply(replyId, host, CarGate.UNLOCK_ONCE.get(lang))")
        assertTrue(on.indexOf("Assistant.keyguard(app)") < on.indexOf("CarModeController.start()"))
    }

    @Test fun theSheetAndALateListDontOutliveTheScreen() {
        has(screen.substringAfter("override fun onStop()").substringBefore("super.onStop()"), "sheet = null")
        val pick = screen.substringAfter("is CarSheet.ChooseMusic -> foundMusic")
        assertTrue(pick.indexOf("closed(CarOpen.settings(") in 0 until pick.indexOf("MusicApps.pin("))
        val list = screen.substringAfter("private suspend fun showMessages()")
        assertTrue(list.indexOf("isAtLeast(Lifecycle.State.STARTED)") in list.indexOf("l.list()") until list.indexOf("CarMessagesNow.of("))
    }

    @Test fun theScreenStartsTheHubFirst() {
        val create = screen.substringAfter("override fun onCreate(")
        assertTrue(create.indexOf("VoiceHub.init(this)") in 0 until create.indexOf("enableEdgeToEdge()"))
    }

    @Test fun theSessionTellsTheCarScreen() {
        val session = code("assistant/RobotSession.kt")
        has(session.substringAfter("override fun secure(on: Boolean)"), "CarModeController.secure(on)")
        // fix-car-long-card: no card opens the panel's window over the car screen any more.
        assertFalse(session.contains("windowOpener") || screen.contains("openWindow"))
        has(code("assistant/PanelController.kt"), "override val carQuiet get() = CarModeController.quietTurn")
        has(code("assistant/PanelController.kt"), "override val carTrusted get() = CarModeController.trusted")
        has(code("assistant/PanelMessages.kt"), "!app.heyrobot.assistant.car.CarCardVoice.replyYes(app.heyrobot.assistant.car.CarModeController.trusted, p.readOut, p.sendText)")
        has(code("assistant/PanelMessages.kt"), "p.readOut = if (speak(")
        has(code("assistant/PanelMessages.kt"), "card?.readOut = null")
        // fix-car-long-card-d: askToShare's own words only where a card may be read out.
        has(code("assistant/PanelHelperTurn.kt"), "if (state.value.speak && cardVoice.ownWordsAloud) {")
        has(code("assistant/PanelCardVoice.kt"), "val ownWordsAloud: Boolean get() = !port.carQuiet || port.carTrusted")
        has(code("assistant/VoiceHub.kt"), "if (tts.isSpeaking) { PanelController.voiceStopped(); PanelMessages.voiceStopped() }")
    }
}
