package app.heyrobot.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.CarSetup
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.headset.ButtonHandler
import app.heyrobot.core.headset.HeadsetEntry
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.Places
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.sys.HandsFreeFacts
import app.heyrobot.ui.car.CarModeScreen
import app.heyrobot.ui.car.CarScreenActions
import app.heyrobot.ui.car.CarScreenState
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Headset and Settings › Car mode (0.8): the design's defaults (the headset button on, car mode off), each
 * switch writing its own file, the optional permissions asked only from their rows, Add your car, and the car screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class HandsFreeSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val calls = ArrayList<String>()
    private var headset: HeadsetFile? = null
    private var car: CarFile? = null
    private var places: Places? = null
    private var memory = DeviceMemory()

    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val myCar = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34", BtClass.CAR_AUDIO)
    private val robotButton = HeadsetEntry.ACTIONS.associateWith { ButtonHandler.ROBOT }
    private fun facts(bluetooth: Boolean = false, callPhone: Boolean = false, dnd: Boolean = false, handlers: Map<String, ButtonHandler> = robotButton) =
        HandsFreeFacts(bluetooth, callPhone, dnd, organicMaps = true, buttonHandlers = handlers)

    private fun actions() = HandsFreeActions(
        headset = { headset = it; calls += "headset" }, car = { car = it; calls += "car" }, places = { places = it; calls += "places" },
        devices = { change -> memory = change(memory); calls += "devices" }, callAfterYes = { calls += "callAfterYes $it" },
        ask = { calls += "ask $it" }, openDndAccess = { calls += "dndAccess" }, open = { calls += "open $it" },
    )

    @Test fun theHeadsetPageHasTheDesignsDefaults() {
        memory = DeviceMemory().remember(buds, DeviceRole.HEADSET, 10)!!.first.answerPrivate(1, true)
        rule.show { HeadsetSettingsScreen(HeadsetPageData(HeadsetFile(), memory, facts()), actions(), SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Headset button opens Robot").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        rule.onNodeWithText("→ Robot ✓").assertExists()
        rule.shot("settings_headset")
        // Off: other apps get the button; the switch writes headset.json.
        rule.onRow("Headset button opens Robot").performClick()
        assertEquals(HeadsetSettings(buttonOpensRobot = false), headset!!.settings)
        rule.onRow("Listen with").performClick()
        rule.inDialog("Phone microphone").performClick()
        assertEquals(app.heyrobot.core.headset.ListenWith.PHONE_MIC, headset!!.settings.listenWith)
        rule.scrollToText("Personal answers only in private headphones")
        rule.onRow("Personal answers only in private headphones").performClick()
        assertFalse(headset!!.safety.personalOnlyPrivate)
        // Your headphones: private or not, or forget it.
        rule.scrollToText("Pixel Buds Pro")
        rule.onRow("Pixel Buds Pro").performClick()
        rule.inDialog("Not private").performClick()
        assertEquals(false, memory.byNumber(1)!!.private)
        // Nearby devices is asked only from its row.
        assertTrue(calls.none { it.startsWith("ask") })
        rule.scrollToText("Answer the headset's voice button properly")
        rule.onRow("Answer the headset's voice button properly").performClick()
        assertEquals("ask ${Manifest.permission.BLUETOOTH_CONNECT}", calls[calls.size - 2])
        assertTrue(headset!!.settings.answerVoiceButton)
    }

    @Test fun theButtonRowSaysWhenAndroidWouldAsk() {
        rule.show { HeadsetSettingsScreen(HeadsetPageData(HeadsetFile(), DeviceMemory(), facts(handlers = robotButton + (HeadsetEntry.WEB_SEARCH to ButtonHandler.ASK))),
            actions(), SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("ask each time", substring = true).assertExists()
        rule.scrollToText("None yet")
        rule.onNodeWithText("None yet").assertExists()
    }

    @Test fun carModeIsOffUntilYouTurnItOnAndAddYourCar() {
        rule.show { CarSettingsScreen(CarPageData(facts = facts()), actions(), SnackbarHostState(), onBack = {}) }
        // Off by default: every row but the switch waits for it.
        rule.onNodeWithText("Use car mode").assertExists()
        rule.onRow("Add your car").assertIsNotEnabled()
        rule.onRow("Use car mode").performClick()
        assertEquals(CarFile(on = true), car)
    }

    @Test fun theCarPage() {
        memory = DeviceMemory().remember(myCar, DeviceRole.CAR, 10)!!.first
        val d = CarPageData(CarFile(on = true), Places(home = SavedPlace("Home", 45.5017, -73.5673)), memory, facts = facts())
        rule.show { CarSettingsScreen(d, actions(), SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("settings_car")
        rule.onNodeWithContentDescription("MyCar starts car mode").performClick()
        assertFalse(memory.byNumber(1)!!.enabled)
        rule.onRow("Add your car").performClick()
        assertEquals("open ${HandsFreeRoutes.ADD_CAR}", calls.last())
        // The Phone permission is asked only when you choose to call after a spoken yes.
        rule.scrollToText("Calls")
        assertTrue(calls.none { it.startsWith("ask") })
        rule.onRow("Calls").performClick()
        rule.inDialog(CallSetting.CALL_AFTER_YES.title).performClick()
        assertTrue(calls.contains("ask ${Manifest.permission.CALL_PHONE}"))
        assertEquals(CallSetting.CALL_AFTER_YES, car!!.settings.calls)
        // Do Not Disturb: Android's own access page first.
        rule.scrollToText("Do Not Disturb while driving")
        rule.onRow("Do Not Disturb while driving").performClick()
        assertEquals("dndAccess", calls[calls.size - 2])
        assertTrue(car!!.settings.dndWhileDriving)
        // Places: Home saved as coordinates, Work not set yet.
        rule.scrollToText("Work")
        rule.onNodeWithText("Home · 45.5017, -73.5673").assertExists()
        rule.onNodeWithText("Not set").assertExists()
    }

    @Test fun nothingWaitsForWhenYouPark() {
        // 1.0 (the user, 13 Oct 2026: no driving restrictions): no row holds anything while you drive; Do Not Disturb stays.
        var file by mutableStateOf(CarFile(on = true))
        val act = actions()
        val write = HandsFreeActions(car = { file = it; act.car(it) })
        rule.show { CarSettingsScreen(CarPageData(file, facts = facts(dnd = true), announce = true), write, SnackbarHostState(), onBack = {}) }
        rule.scrollToText("Nearby devices")
        rule.onRow("Do Not Disturb while driving").assertExists()
        rule.onNodeWithText("Hold screen actions until I park").assertDoesNotExist()
        rule.onNodeWithText("New texts while driving").assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("settings_car_drive")
        // "Replies by voice" says the screen's reply card takes what isn't sent by voice, on or off.
        rule.scrollToText("Replies by voice")
        rule.onNodeWithText("otherwise the reply card shows", substring = true).assertExists()
        rule.onRow("Replies by voice").performClick()
        assertFalse(car!!.settings.voiceReplies)
        rule.onNodeWithText("Off: the reply card shows, as outside the car").assertExists()
        // The footer promises no parking and no "when you're stopped".
        rule.scrollToText("Robot doesn't hold or refuse anything because you're driving", substring = true)
        for (w in listOf("park", "stopped", "From your drive")) rule.onNodeWithText(w, substring = true).assertDoesNotExist()
        assertEquals(CarSettings(voiceReplies = false), car!!.settings)
    }

    @Test fun newMessagesWaitForSettingsMessages() {
        // 0.8: off until Settings › Messages announces new messages; its row opens that page.
        rule.show { HeadsetSettingsScreen(HeadsetPageData(HeadsetFile(), DeviceMemory(), facts()), actions(), SnackbarHostState(), onBack = {}) }
        rule.scrollToText("Announce new messages in the headset")
        rule.onRow("Announce new messages in the headset").assertIsNotEnabled()
        rule.onRow("Announce from").assertIsNotEnabled()
        rule.scrollToText("Announce new messages is off")
        rule.onRow("Announce new messages is off").performClick()
        assertEquals("open ${SettingsRoutes.MESSAGES}", calls.last())
    }

    @Test fun newMessagesInTheHeadsetAndTheCar() {
        var page by mutableStateOf(0)
        rule.show {
            if (page == 0) HeadsetSettingsScreen(HeadsetPageData(HeadsetFile(), DeviceMemory(), facts(), announce = true), actions(), SnackbarHostState(), onBack = {})
            else CarSettingsScreen(CarPageData(CarFile(on = true), facts = facts(), announce = true), actions(), SnackbarHostState(), onBack = {})
        }
        rule.scrollToText("Announce from")
        rule.onRow("Announce from").performClick()
        rule.inDialog(app.heyrobot.core.headset.AnnounceFrom.EVERYONE.title).performClick()
        assertEquals(app.heyrobot.core.headset.AnnounceFrom.EVERYONE, headset!!.settings.announceFrom)
        rule.scrollToText("Announce while locked")
        rule.onRow("Announce while locked").performClick()
        assertTrue(headset!!.settings.announceLocked)
        page = 1
        rule.scrollToText("Replies by voice")
        rule.onRow("Replies by voice").performClick()
        assertFalse(car!!.settings.voiceReplies)
        rule.scrollToText("Announce new messages")
        rule.onRow("Announce new messages").performClick()
        rule.inDialog(app.heyrobot.core.car.CarAnnounce.READ_WHOLE.title).performClick()
        assertEquals(app.heyrobot.core.car.CarAnnounce.READ_WHOLE, car!!.settings.announce)
    }

    @Test fun addYourCarAsksIsThisYourCar() {
        val d = CarPageData(CarFile(on = true), candidates = CarSetup.candidates(listOf(myCar, myCar.copy(type = RouteType.BT_A2DP), buds), DeviceMemory()))
        rule.show { AddCarScreen(d, actions(), SnackbarHostState(), onBack = { calls += "back" }) }
        rule.onRow("MyCar").performClick()
        rule.inDialog("Robot sees “MyCar” (…12:34). Is this your car?").assertExists()
        rule.inDialog("Yes, it's my car").performClick()
        assertEquals(listOf(DeviceRole.CAR), memory.devices.map { it.role })
        assertEquals("back", calls.last())
    }

    @Test fun theCarScreenHasBigButtonsAndOneLine() {
        val taps = ArrayList<String>()
        rule.show(dark = true) {
            CarModeScreen(CarScreenState("Car mode · unlocked features", trusted = true, status = "Calling Martine Martin, mobile, on a line far too long to fit",
                buttons = CarScreen.buttons(messages = false, home = true)), CarScreenActions(talk = { taps += "talk" }, call = { taps += "call" },
                navigateHome = { taps += "home" }, end = { taps += "end" }))
        }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("car_screen")
        rule.onNodeWithContentDescription("Talk").performClick()
        rule.onNodeWithContentDescription("Call").performClick()
        rule.onNodeWithContentDescription("Navigate home").performClick()
        rule.onNodeWithText("End car mode").performClick()
        assertEquals(listOf("talk", "call", "home", "end"), taps)
        rule.onNodeWithContentDescription("Unlocked features on").assertExists()
        // One status line of at most 40 characters.
        rule.onNodeWithText(CarScreen.status("Calling Martine Martin, mobile, on a line far too long to fit")).assertExists()
        assertTrue(CarScreen.status("Calling Martine Martin, mobile, on a line far too long to fit").length <= CarScreen.MAX_STATUS)
        assertEquals(CarSettings(), CarSettings.fromJson(null))
    }
}
