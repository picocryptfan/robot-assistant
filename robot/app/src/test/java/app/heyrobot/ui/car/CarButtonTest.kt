// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.car

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** 1.2.4: car mode by hand, from the home screen's button and the launcher shortcut, through the same start in :voice. */
class CarButtonTest {
    private fun code(path: String) = File("src/main/java/app/heyrobot/$path").readText().replace(Regex("\\s+"), " ")
    private fun has(text: String, vararg parts: String) { for (p in parts) assertTrue(p, text.contains(p)) }
    private val ns = "http://schemas.android.com/apk/res/android"

    @Test fun theShortcutIsDeclaredAndOpensCarMode() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/res/xml/shortcuts.xml"))
        val all = doc.getElementsByTagName("shortcut")
        val car = (0 until all.length).map { all.item(it) as Element }.single { it.getAttributeNS(ns, "shortcutId") == "car_mode" }
        assertEquals("@string/car_shortcut", car.getAttributeNS(ns, "shortcutShortLabel"))
        val intent = car.getElementsByTagName("intent").item(0) as Element
        assertEquals(CarButton.ACTION, intent.getAttributeNS(ns, "action"))
        assertEquals("app.heyrobot.ui.MainActivity", intent.getAttributeNS(ns, "targetClass"))
        // MainActivity answers it when it starts and when it's already open.
        val main = code("ui/MainActivity.kt")
        has(main.substringAfter("override fun onCreate(").substringBefore("override fun "), "CarButton.take(intent)) CarButton.open(this)")
        has(main.substringAfter("override fun onNewIntent(").substringBefore("override fun "), "CarButton.take(intent)) CarButton.open(this)")
    }

    // review-1.2.4 finding 4: a relaunch from Recents doesn't start car mode again, and a handled ask is cleared.
    @Test fun recentsDoesntStartCarModeAgain() {
        assertTrue(CarButton.asked(CarButton.ACTION, android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(!CarButton.asked(CarButton.ACTION, android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(!CarButton.asked(null, 0))
        has(code("ui/car/CarButton.kt").substringAfter("fun take("), "asked(i).also { if (i?.action == ACTION) i.action = null }")
    }

    @Test fun theButtonCallsTheSameStart() {
        has(code("ui/Screens.kt").substringAfter("fun HomeScreen(").substringBefore("@Composable"), "onClick = a.carMode", "R.string.home_car_mode")
        has(code("ui/MainActivity.kt"), "carMode = { CarButton.open(ctx)")
        val open = code("ui/car/CarButton.kt").substringAfter("fun open(")
        has(open, "car().on", "AppPages.CAR_SETTINGS", "startForegroundService(", "CarNotice.ACTION_OPEN")
        // In :voice, the speaking service starts car mode by hand, then goes to the foreground with car mode's notification.
        val service = code("assistant/routines/RoutineSpeakingService.kt").substringAfter("ACTION_CAR_OPEN -> {").substringBefore("ACTION_CAR_END ->")
        has(service, "VoiceHub.init(this)", "CarModeController.startByHand()", "goForeground()", "CarModeController.serviceStarted()", "CarModeController.openScreen()")
        assertTrue(service.indexOf("startByHand()") < service.indexOf("goForeground()"))
        has(code("assistant/routines/RoutineSpeakingService.kt"), "const val ACTION_CAR_OPEN = app.heyrobot.core.car.CarNotice.ACTION_OPEN")
        has(code("assistant/car/CarModeController.kt"), "fun startByHand() = input(CarInput.Start(now(), byHand = true))")
    }

    @Test fun bothSayCarModeInBothLanguages() {
        for (dir in listOf("values" to "Car mode", "values-fr-rCA" to "Mode auto")) {
            has(File("src/main/res/${dir.first}/strings_home.xml").readText(), "<string name=\"home_car_mode\">${dir.second}</string>")
            has(File("src/main/res/${dir.first}/strings_handsfree.xml").readText(), "<string name=\"car_shortcut\">${dir.second}</string>")
        }
    }
}
