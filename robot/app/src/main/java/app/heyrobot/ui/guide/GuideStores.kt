package app.heyrobot.ui.guide

import android.content.Context
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.SetupGuideStore
import app.heyrobot.sys.TermuxStore
import app.heyrobot.update.UpdateStore
import app.heyrobot.sys.WebStore
import app.heyrobot.ui.settings.PhoneMemoryStore
import app.heyrobot.ui.settings.announceMessages
import app.heyrobot.ui.settings.turnMessagesOn
import app.heyrobot.update.Updater

// MAIN PROCESS (1.0). What the setup guide reads and writes besides settings.json (which comes in as the app's settings,
// as on every Settings page): the files Settings › Messages, Memory, Routines, Headset, Car mode, Updates, Web search and
// Termux keep, through the same stores, so a choice here is the same as the switch on that page. Nothing new is stored
// but the guide's own state (setup_guide.json).

/** The other settings files as they are now, read together for a page and the summary. */
data class GuideFiles(
    val messages: MessageSettings = MessageSettings(),
    val memory: MemorySettings = MemorySettings(),
    val routines: RoutineSettings = RoutineSettings(),
    val headset: HeadsetSettings = HeadsetSettings(),
    val carOn: Boolean = false,
    val updates: UpdateSettings = UpdateSettings(),
    val webOn: Boolean = false,
    val termuxOn: Boolean = false,
    /** 1.0: Settings › Web search's default engine for a search in your browser. */
    val browserEngine: BrowserEngine = BrowserEngine.DEFAULT,
)

/** The guide's stores; the phone's are [PhoneGuideStores], tests keep theirs in memory. */
interface GuideStores {
    fun guide(): GuideState
    fun saveGuide(s: GuideState)
    fun files(): GuideFiles
    /** Settings › Messages' "Read my messages": on as its own set-up turns it on (your texting app ticked, pinned now), or off. */
    fun messages(on: Boolean)
    /** Settings › Messages › "Announce new messages". */
    fun announce(on: Boolean)
    fun memory(s: MemorySettings)
    /** Memory sealed again with the lock screen's key (on) or the unlock-only one, as Settings › Memory does first; false if that failed. Blocking. */
    fun rekeyMemory(lockScreen: Boolean): Boolean
    fun routines(s: RoutineSettings)
    fun headset(s: HeadsetSettings)
    fun updates(s: UpdateSettings)
    /** 1.0: Settings › Web search's default engine for a search in your browser (web.json). */
    fun browserEngine(e: BrowserEngine)
}

class PhoneGuideStores(ctx: Context) : GuideStores {
    private val app = ctx.applicationContext

    override fun guide(): GuideState = SetupGuideStore.current(app)
    override fun saveGuide(s: GuideState) = SetupGuideStore.save(app, s)

    override fun files(): GuideFiles {
        val hf = HandsFreeFiles.store(app)
        return GuideFiles(
            messages = MessagesStore.current(app).settings,
            memory = MemorySettingsStore.current(app),
            routines = RoutineFiles.store(app).settings(),
            headset = hf.headset().settings,
            carOn = hf.car().on,
            updates = UpdateStore.settings(app),
            webOn = WebStore.current(app).on,
            termuxOn = TermuxStore.load(app).enabled,
            browserEngine = WebStore.current(app).browserEngine,
        )
    }

    override fun messages(on: Boolean) = turnMessagesOn(app, on)

    override fun announce(on: Boolean) = announceMessages(app, on)

    override fun memory(s: MemorySettings) = MemorySettingsStore.save(app, s)

    override fun rekeyMemory(lockScreen: Boolean): Boolean {
        val r = PhoneMemoryStore(app).rekey(lockScreen)
        return r is MemoryLoad.Loaded || r == MemoryLoad.Empty
    }

    override fun routines(s: RoutineSettings) {
        RoutineFiles.store(app).editSettings { s }
        RoutineFiles.changed(app)
    }

    override fun headset(s: HeadsetSettings) {
        HandsFreeFiles.store(app).editHeadset { it.copy(settings = s) }
        HandsFreeFiles.changed(app)
    }

    override fun updates(s: UpdateSettings) = Updater.setSettings(app, s)

    override fun browserEngine(e: BrowserEngine) = WebStore.save(app, WebStore.current(app).copy(browserEngine = e))
}
