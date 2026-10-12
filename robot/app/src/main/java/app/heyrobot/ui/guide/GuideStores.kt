package app.heyrobot.ui.guide

import android.content.Context
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageAllowlist
import app.heyrobot.core.messages.MessageApp
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.people.MessageAppSigners
import app.heyrobot.sys.HandOffApps
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.MemorySettingsStore
import app.heyrobot.sys.MessagesStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.SetupGuideStore
import app.heyrobot.sys.TermuxStore
import app.heyrobot.update.UpdateStore
import app.heyrobot.sys.WebStore
import app.heyrobot.ui.settings.PhoneMemoryStore
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
        )
    }

    override fun messages(on: Boolean) {
        val now = MessagesStore.current(app).settings
        if (on && now.allowlist.apps.isEmpty()) {
            // As Settings › Messages' "Read your messages?" with its pre-ticked app: your texting app (Cheogram), pinned now.
            val texting = MessageApp.CHEOGRAM.takeIf { c -> c.packages.any { HandOffApps.installed(app, it) } }
            val apps = MessageAllowlist.atSetup(texting).apps
            val pins = apps.flatMap { MessageAppSigners.pinsNow(app, it).entries }.associate { it.toPair() }
            MessagesStore.update(app) { it.turnedOn(apps, pins) }
        } else {
            MessagesStore.update(app) { it.copy(settings = it.settings.copy(on = on)) }
        }
        if (on) NotificationAccess.rebind(app)
    }

    override fun announce(on: Boolean) = MessagesStore.update(app) { it.copy(settings = it.settings.copy(announce = on)) }

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
}
