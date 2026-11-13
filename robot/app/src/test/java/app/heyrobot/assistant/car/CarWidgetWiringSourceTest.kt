// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car widget's helpers are called where they must be, read from the source (no Android here). */
class CarWidgetWiringSourceTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun src(path: String) = File(root, "app/src/main/java/app/heyrobot/$path").readText()
    private val car = src("assistant/car/CarModeActivity.kt")
    private val settings = src("ui/settings/CarLayoutSettings.kt")
    private val host = src("assistant/car/CarWidgetHost.kt")

    private fun has(text: String, s: String, why: String) = assertTrue(why, s.contains(text))

    @Test fun bothScreensSweepLeftovers() {
        has("CarWidgets.sweep(this@CarModeActivity, setOfNotNull(layout().widget?.id))", car, "the car screen sweeps, keeping the saved widget")
        has("CarWidgets.sweep(app, setOfNotNull(layout.widget?.id, pending?.second))", settings, "Settings keeps the saved widget and the waiting pick")
    }

    @Test fun aWaitingPickOutlivesARecreation() {
        has("var pending by rememberSaveable(stateSaver = PendingPick)", settings, "the pick waiting on Android's answer is saved")
    }

    @Test fun aPickSweptMeanwhileIsNotSaved() {
        has("CarWidgets.bound(app, CarWidget(pin, c.cls, id)) } == null) return@launch drop(id, R.string.car_widget_failed)", settings, "done() checks the id is still bound")
    }

    @Test fun theWidgetClosesUnderThePhoneLock() {
        has("private fun closes(w: CarWidget): Boolean = PhoneLockNow.on(this).let { on -> CarWidgetNow.closes(on,", car, "closes() asks the phone lock")
    }

    @Test fun tapsAndSizeGoThroughTheHost() {
        has("host.createView(CarWidgetTaps(activity, open), w.id, info)", host, "every tap goes through CarWidgetTaps")
        has("view?.addOnLayoutChangeListener", host, "the slot's size follows its layout")
        has("updateAppWidgetSize(", host, "the widget hears its size")
    }
}
