package app.heyrobot.core

import org.json.JSONObject

// Pure. settings.json is written by the main app only; the voice process re-reads it when the main app
// says it changed (Cmd.SETTINGS).

/** How freely Robot may run an action ("approval levels"). */
enum class Approval(val title: String) {
    AUTO("Just do it"),
    ASK("Ask first"),
    OFF("Off"),
}

enum class SpeakReplies(val title: String) {
    VOICE("When I ask by voice"),
    ALWAYS("Always"),
    NEVER("Never"),
}

data class RobotSettings(
    /** Listen for "hey robot" (needs the microphone permission and Robot as the digital assistant). */
    val wakeWord: Boolean = true,
    val wakeOnlyWhileCharging: Boolean = false,
    val speakReplies: SpeakReplies = SpeakReplies.VOICE,
    /** Text-to-speech rate, 0.5 to 2.0 (1.0 = the voice's normal rate). */
    val speechRate: Float = 1.0f,
    /** Voice id for the text-to-speech engine; empty = the engine's default voice. */
    val voice: String = "",
    /** Minutes Gemma stays loaded after the panel closes; [KEEP_ALWAYS] = keep it loaded. */
    val keepModelMinutes: Int = 5,
    /** Save conversations to the history on this phone (screen conversations only when kept). */
    val saveHistory: Boolean = true,
    val approvals: Map<ToolName, Approval> = DEFAULT_APPROVALS,
) {
    fun approval(t: ToolName): Approval = approvals[t] ?: DEFAULT_APPROVALS.getValue(t)

    fun toJson(): JSONObject = JSONObject().apply {
        put("wakeWord", wakeWord)
        put("wakeOnlyWhileCharging", wakeOnlyWhileCharging)
        put("speakReplies", speakReplies.name)
        put("speechRate", speechRate.toDouble())
        put("voice", voice)
        put("keepModelMinutes", keepModelMinutes)
        put("saveHistory", saveHistory)
        put("approvals", JSONObject().apply { for (t in ToolName.entries) put(t.id, approval(t).name) })
    }

    companion object {
        const val KEEP_ALWAYS = -1
        val KEEP_CHOICES = listOf(1, 5, 15, KEEP_ALWAYS)

        /** Timers and alarms: just do it (spec item 7). Flashlight is harmless and undone in one word. */
        val DEFAULT_APPROVALS: Map<ToolName, Approval> = mapOf(
            ToolName.SET_TIMER to Approval.AUTO,
            ToolName.SET_ALARM to Approval.AUTO,
            ToolName.FLASHLIGHT to Approval.AUTO,
            ToolName.OPEN_APP to Approval.ASK,
            ToolName.MAPS_SEARCH to Approval.ASK,
            ToolName.MAPS_NAVIGATE to Approval.ASK,
        )

        fun clampRate(v: Float): Float = if (v.isNaN()) 1.0f else v.coerceIn(0.5f, 2.0f)
        fun clampKeep(v: Int): Int = if (v == KEEP_ALWAYS) v else v.coerceIn(0, 120)

        fun fromJson(text: String?): RobotSettings {
            val o = try { JSONObject(text ?: return RobotSettings()) } catch (_: Exception) { return RobotSettings() }
            val d = RobotSettings()
            val ap = o.optJSONObject("approvals")
            return RobotSettings(
                wakeWord = o.optBoolean("wakeWord", d.wakeWord),
                wakeOnlyWhileCharging = o.optBoolean("wakeOnlyWhileCharging", d.wakeOnlyWhileCharging),
                speakReplies = enumOr(o.optString("speakReplies"), d.speakReplies),
                speechRate = clampRate(o.optDouble("speechRate", d.speechRate.toDouble()).toFloat()),
                voice = o.optString("voice", d.voice),
                keepModelMinutes = clampKeep(o.optInt("keepModelMinutes", d.keepModelMinutes)),
                saveHistory = o.optBoolean("saveHistory", d.saveHistory),
                approvals = ToolName.entries.associateWith { t -> enumOr(ap?.optString(t.id), DEFAULT_APPROVALS.getValue(t)) },
            )
        }
    }
}
