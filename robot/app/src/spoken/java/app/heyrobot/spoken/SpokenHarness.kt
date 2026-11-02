// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import android.content.Context
import app.heyrobot.assistant.PanelController
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.BrainModel
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.voice.FrameFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * VOICE PROCESS, test build only. Joins the protocol to the running app, once per process, from the first command: from then on the
 * clips take the microphone's place in the listener, so nothing in the room can reach this build's turns.
 */
class SpokenHarness private constructor(private val app: Context) : SpokenPort {
    val results = SpokenResults(File(app.filesDir, SpokenProtocol.RESULTS))
    private val feed = SpokenFrameFeed(
        allowed = { clip -> SpokenCards.allows(clip, cards()) },
        onCut = { clip -> results.line(JSONObject().put("type", "cut").put("clip", clip)) },
        onFed = { wallMs, audioMs -> RLog.i("clip_fed", "wallMs" to wallMs, "audioMs" to audioMs) },
    )
    val commands = SpokenCommands(this, results, feed)
    private val turns = SpokenTurns()
    private val scope = MainScope()
    @Volatile override var recording = false; private set

    private fun begin() {
        FrameFeed.current = feed
        // Before any clip: what reaches another app stops at a card (SpokenSettings), and music's keys have no card to stop at, so music by voice is off.
        saveSettings { it }
        EverydaySettingsStore.update(app) { it.copy(music = false) }
        scope.launch {
            PanelController.state.collect { st ->
                for (line in turns.observe(st, PanelController.lastRouter)) try { results.line(line) } catch (t: Throwable) { RLog.w("spoken_results", t) }
            }
        }
    }

    /** The recorder's turn at the real microphone, then the clips' again. */
    fun microphone(on: Boolean) {
        recording = on
        if (on) feed.clear()
        FrameFeed.current = if (on) null else feed
    }

    override fun cards(): List<String>? = try { SpokenCards.showing(PanelController.state.value) } catch (_: Throwable) { null }

    override fun listening() = PanelController.state.value.let { it.phase == Phase.LISTENING || it.cardHearing == Phase.LISTENING }

    override fun listenModel() = VoiceModelStore.verified(app, VoiceBundle.STT)

    override fun gemmaModel() = BrainModel.entries.any { it.builtIn && ModelStore.verifiedPath(app, it) != null }

    private fun saveSettings(change: (app.heyrobot.core.RobotSettings) -> app.heyrobot.core.RobotSettings?): Boolean {
        val new = change(SettingsStore.load(app)) ?: return false
        SettingsStore.save(app, SpokenSettings.guard(new))
        SettingsStore.afterSaves { Cmd.send(app, Cmd.SETTINGS) }
        return true
    }

    override fun resetSettings() { saveSettings(SpokenSettings::reset) }

    override fun setSetting(name: String?, value: String?) = saveSettings { SpokenSettings.set(it, name, value) }

    /** As the wake word does (VoiceHub.onWake) and as the button does. */
    override fun open(how: String): String? {
        if (PanelController.shown) {
            if (how == "wake") PanelController.onWakeWhileShown() else PanelController.micPressed()
            return null
        }
        val vis = RobotVoiceService.instance ?: return "not the assistant"
        return if (vis.show(if (how == "wake") RobotVoiceService.SRC_WAKE else RobotVoiceService.SRC_APP)) null else "the panel didn't open"
    }

    override fun clip(name: String) = SpokenProtocol.readClip(File(File(app.filesDir, SpokenProtocol.CLIPS), name))

    override fun end() {
        for (t in PanelController.state.value.turns) for (a in t.actions) if (a.status == ActionStatus.PENDING) PanelController.confirm(a.id, yes = false)
        PanelController.stopGenerating()
        PanelController.newConversation()
        turns.clear()
    }

    override fun importModels(): Int {
        val items = SpokenImporter.plan(File(app.filesDir, SpokenAssumptions.IMPORT))
        scope.launch(Dispatchers.IO) {
            for (i in items) {
                val ok = try {
                    when (i) {
                        is SpokenImporter.Item.Voice -> SpokenImporter.installVoice(i.src, i.file, VoiceModelStore.dir(app, i.bundle))
                        is SpokenImporter.Item.Brain -> {
                            ModelStore.dir(app).mkdirs()
                            val dest = File(ModelStore.dir(app), i.model.file)
                            i.src.renameTo(dest) && (ModelStore.verify(app, i.model) || run { dest.delete(); false })
                        }
                    }
                } catch (t: Throwable) {
                    RLog.w("spoken_import", t)
                    false
                }
                results.line(JSONObject().put("type", "import").put("file", i.src.name).put("ok", ok))
            }
            Cmd.send(app, Cmd.VOICE_MODELS)
            results.line(JSONObject().put("type", "import").put("done", true).put("listen", listenModel()).put("gemma", gemmaModel()))
        }
        return items.size
    }

    companion object {
        @Volatile private var one: SpokenHarness? = null

        /** Main thread. */
        fun start(app: Context): SpokenHarness = one ?: SpokenHarness(app).also { one = it; it.begin() }
    }
}
