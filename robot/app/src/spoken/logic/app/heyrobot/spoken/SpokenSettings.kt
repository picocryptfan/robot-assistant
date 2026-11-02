// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.core.Approval
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.ToolName

/** The short list of settings a script may set (spoken.json's `settings`). Nothing else can be changed from the computer. */
object SpokenSettings {
    val NAMES = setOf("speakReplies", "voiceCards", "wakeWord", "livePartials", "directAnswers", "saveHistory", "speechRate", "voice")

    /**
     * What reaches another app or the web, which your Robot does at once: here each stops at its card, so an unattended run can't
     * open anything if the model calls it on its own. On every save from the test build, so no script or reset can undo it; these
     * cards aren't harmless, so nothing but a no plays over one. A setting of this build's own: a release's defaults don't change.
     */
    val REACHES_OUT: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM, ToolName.OPEN_APP, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE, ToolName.WEB_SEARCH, ToolName.BROWSER_SEARCH,
        ToolName.CONNECTION, ToolName.HAND_OFF)

    fun guard(s: RobotSettings): RobotSettings = s.copy(approvals = s.approvals + REACHES_OUT.associateWith { Approval.ASK })

    /** [s] with [name] set to [value], or null when the name isn't on the list or the value doesn't fit it. */
    fun set(s: RobotSettings, name: String?, value: String?): RobotSettings? {
        if (value == null) return null
        val on = value.toBooleanStrictOrNull()
        return when (name) {
            "speakReplies" -> SpeakReplies.entries.firstOrNull { it.name == value }?.let { s.copy(speakReplies = it) }
            "voiceCards" -> on?.let { s.copy(voiceCards = it) }
            "wakeWord" -> on?.let { s.copy(wakeWord = it) }
            "livePartials" -> on?.let { s.copy(livePartials = it) }
            "directAnswers" -> on?.let { s.copy(directAnswers = it) }
            "saveHistory" -> on?.let { s.copy(saveHistory = it) }
            "speechRate" -> value.toFloatOrNull()?.takeIf { it in 0.5f..2f }?.let { s.copy(speechRate = it) }
            "voice" -> value.takeIf { it.length <= 64 }?.let { s.copy(voice = it) }
            else -> null
        }
    }

    /** [s] with the short list back at Robot's defaults, everything else as it is. */
    fun reset(s: RobotSettings): RobotSettings {
        val d = RobotSettings()
        return s.copy(speakReplies = d.speakReplies, voiceCards = d.voiceCards, wakeWord = d.wakeWord, livePartials = d.livePartials,
            directAnswers = d.directAnswers, saveHistory = d.saveHistory, speechRate = d.speechRate, voice = d.voice)
    }
}
