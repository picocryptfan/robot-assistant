package app.heyrobot.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.heyrobot.core.RLog
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.theme.RobotTheme

// VOICE PROCESS. The panel the system shows for the assistant: the corner swipe, the lock-screen
// affordance, "hey robot", and Robot's own app. A Compose UI inside onCreateContentView: a session is
// not an Activity, so it is its own LifecycleOwner and SavedStateRegistryOwner.

class RobotSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        VoiceHub.init(this)
        return RobotSession(this)
    }
}

class RobotSession(ctx: Context) : VoiceInteractionSession(ctx), LifecycleOwner, SavedStateRegistryOwner, PanelHost {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        PanelController.attach(this)
    }

    /** Called once the session's window exists (it does not yet in onCreate). */
    override fun onCreateContentView(): View {
        // Volume keys stop Robot talking (Settings › Voice). The dialog's key listener sees them before its views; the
        // KeyEvent.Callback overrides below are the session's own path for whatever reaches it after.
        window?.setOnKeyListener { _, keyCode, event -> onVolumeKey(keyCode, event) }
        window?.window?.let { w ->
            // A wake word with the screen off lights it up.
            @Suppress("DEPRECATION")
            w.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            // Edge to edge: the panel pads itself for the navigation bar and the keyboard (WindowInsets in Compose).
            @Suppress("DEPRECATION")
            w.setDecorFitsSystemWindows(false)
            // "Resize" with the decor not fitting reports the keyboard's height, which the panel pads for (KeyboardPad).
            // On the Pixel the assistant's window still slid up ("pan") as well, which showed the keyboard's height
            // twice (0.4.0): the panel subtracts whatever slide it finds, so either behaviour ends with the text box
            // on the keyboard.
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // Compose finds its owners from the window's root view, so they go on the decor view as well.
            w.decorView.setViewTreeLifecycleOwner(this)
            w.decorView.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@RobotSession)
            setViewTreeSavedStateRegistryOwner(this@RobotSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(registry))
            setContent {
                RobotTheme {
                    AssistantPanel(onClose = { hide() })
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Settings › Display, as the main app last saved it (a small file): each panel opens in the current look.
        LookStore.reload(context)
        registry.currentState = Lifecycle.State.RESUMED
        PanelController.attach(this)
        PanelController.onShow(args, Assistant.locked(context), showFlags)
    }

    override fun onHide() {
        PanelController.onHide()
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        PanelController.detach(this)
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    /** Screen text from the corner swipe (never stored, never logged). */
    override fun onHandleAssist(state: AssistState) {
        // None: "Use text from screen" is off or the app keeps its screen private. The panel says so when you tap the chip.
        val s = state.assistStructure ?: return PanelController.onAssistNone()
        val pieces = ArrayList<String>()
        fun walk(n: AssistStructure.ViewNode, depth: Int) {
            if (depth > 60 || pieces.size > 2_000) return
            n.text?.toString()?.let { if (it.isNotBlank()) pieces += it }
            n.contentDescription?.toString()?.let { if (it.isNotBlank() && n.text.isNullOrBlank()) pieces += it }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), depth + 1)
        }
        try {
            for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode, 0)
        } catch (t: Throwable) {
            RLog.w("assist_walk", t)
        }
        // Which app was on screen: over the Claude app the panel offers "Read Claude's answer" (your swipe only). With
        // several windows (split screen) this runs once per activity: the panel puts them together.
        PanelController.onAssistText(pieces, try { s.activityComponent?.packageName } catch (_: Throwable) { null }, state.index, state.count)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        PanelController.onScreenshot(screenshot)
    }

    // ------------------------------------------------------------------ volume keys

    /** The volume key whose press stopped Robot: its repeats and its release are used up too, so the volume stays. */
    private var swallowed = 0

    private fun onVolumeKey(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        return when (e.action) {
            KeyEvent.ACTION_DOWN -> when {
                e.repeatCount > 0 -> swallowed == keyCode
                PanelController.volumeKey(keyCode) -> { swallowed = keyCode; true }
                else -> { swallowed = 0; false }
            }
            KeyEvent.ACTION_UP -> (swallowed == keyCode).also { if (it) swallowed = 0 }
            else -> false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = onVolumeKey(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = onVolumeKey(keyCode, event) || super.onKeyUp(keyCode, event)

    // ------------------------------------------------------------------ PanelHost

    override fun hidePanel() = hide()

    override fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        RLog.w("panel_launch", t)
        false
    }

    override fun startCapture(intent: Intent): Boolean = try {
        startAssistantActivity(intent)
        true
    } catch (t: Throwable) {
        RLog.w("panel_capture", t)
        false
    }
}
