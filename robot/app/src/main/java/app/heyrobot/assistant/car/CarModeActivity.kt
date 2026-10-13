package app.heyrobot.assistant.car

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.CarPhase
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.ui.car.CarModeScreen
import app.heyrobot.ui.car.CarScreenActions
import app.heyrobot.ui.car.CarScreenState
import app.heyrobot.ui.theme.RobotTheme

// VOICE PROCESS (0.8). The car screen (design §4.3), not exported, in :voice where car mode is: big buttons, one status
// line. Over the lock screen only during the trusted session (§4.6), and then only these buttons; it keeps the screen on
// only with "Keep the screen on". It closes when car mode ends.

class CarModeActivity : ComponentActivity() {
    private val ui = mutableStateOf(CarScreenState())
    private var said = ""
    private val watcher: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CarModeController.watch(watcher)
        refresh()
        setContent {
            RobotTheme(dark = true) {
                CarModeScreen(ui.value, CarScreenActions(
                    talk = { CarModeController.talk(call = false) },
                    call = { CarModeController.talk(call = true) },
                    navigateHome = { said = CarModeController.navigateHome(this); refresh() },
                    end = { CarModeController.end(); finish() },
                ))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        CarModeController.unwatch(watcher)
        super.onDestroy()
    }

    private fun refresh() {
        val c = CarModeController
        if (c.state.phase == CarPhase.OFF) return finish()
        val lang = HandsFreeHub.lang
        val trusted = c.trusted
        setShowWhenLocked(trusted)
        if (HandsFreeHub.car.settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val home = HandsFreeFiles.store(this).places().home != null
        val status = said.ifEmpty { if (lang == SpokenLang.FR) "Dis « hey robot »" else "Say “hey robot”" }
        ui.value = CarScreenState(CarScreen.title(trusted, lang), trusted, status, CarScreen.buttons(messages = false, home = home))
    }
}
