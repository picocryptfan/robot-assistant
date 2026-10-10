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

/**
 * The voice that reads replies (docs/voice-plan.md 1): Kokoro v1.0 "af_heart" (speaker 3) by default; Kokoro
 * "ff_siwis" (speaker 30, espeak language "fr") for French replies later; Kitten nano 0.8 as the small, fast
 * fallback; or the phone's own text to speech. A voice whose model is not downloaded falls back to the phone's.
 */
enum class VoiceChoice(val id: String, val title: String, val detail: String, val bundle: VoiceBundle?, val speaker: Int, val lang: String?) {
    HEART("kokoro_af_heart", "Heart", "English, natural (Kokoro)", VoiceBundle.KOKORO, 3, null),
    SIWIS("kokoro_ff_siwis", "Siwis", "French, for French replies in a later version (Kokoro)", VoiceBundle.KOKORO, 30, "fr"),
    KITTEN("kitten_expr_voice_5_f", "Kitten", "English, faster and smaller, less natural", VoiceBundle.KITTEN, 7, null),
    SYSTEM("system", "Phone's voice", "GrapheneOS Speech Services", null, 0, null);

    companion object {
        val DEFAULT = HEART

        /** Unknown or empty ids (older settings) mean the default. */
        fun of(id: String?): VoiceChoice = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Which app opens with a text: Cheogram (texts and XMPP, through the SMS gateway), the phone's SMS app, or none. */
enum class TextApp(val title: String) {
    CHEOGRAM("Cheogram"),
    SMS("SMS app"),
    OFF("Off"),
}

/** Which app opens with an email draft: Proton Mail, or the system chooser every time. */
enum class EmailApp(val title: String) {
    PROTON("Proton Mail"),
    ASK("Ask each time"),
}

data class RobotSettings(
    /** Listen for "hey robot" (needs the microphone permission and Robot as the digital assistant). */
    val wakeWord: Boolean = true,
    val wakeOnlyWhileCharging: Boolean = false,
    val speakReplies: SpeakReplies = SpeakReplies.VOICE,
    /** Text-to-speech rate, 0.5 to 2.0 (1.0 = the voice's normal rate); the speed passed to the voice model. */
    val speechRate: Float = 1.0f,
    /** A [VoiceChoice] id; empty or unknown = [VoiceChoice.DEFAULT]. */
    val voice: String = "",
    /** Show the words while you speak (decodes the speech so far about every 0.7 s; off saves battery). */
    val livePartials: Boolean = true,
    /** Minutes Gemma stays loaded after the panel closes; [KEEP_ALWAYS] = keep it loaded. */
    val keepModelMinutes: Int = 5,
    /** Save conversations to the history on this phone (screen conversations only when kept). */
    val saveHistory: Boolean = true,
    val approvals: Map<ToolName, Approval> = DEFAULT_APPROVALS,
    /** Null until the person picks one: Cheogram when it is installed, else the SMS app ([textAppFor]). */
    val textApp: TextApp? = null,
    /** Where Cheogram texts phone numbers: "+<E.164 digits>@<gateway>" (JMP's is cheogram.com). */
    val smsGateway: String = PhoneNumbers.DEFAULT_GATEWAY,
    /** For numbers written without a country code (PhoneNumberUtils' default country). */
    val phoneRegion: String = PhoneNumbers.DEFAULT_REGION,
    /** Null until the person picks one: Proton Mail when it is installed, else the chooser ([emailAppFor]). */
    val emailApp: EmailApp? = null,
    /** A CalendarContract calendar id for new events; [CalendarPick.AUTO]: Robot picks (CalendarPick.choose). */
    val eventCalendar: Long = CalendarPick.AUTO,
    /** Length of a new event when no end or duration was said. */
    val eventMinutes: Int = 60,
) {
    fun approval(t: ToolName): Approval = approvals[t] ?: DEFAULT_APPROVALS.getValue(t)

    fun textAppFor(cheogramInstalled: Boolean): TextApp = textApp ?: if (cheogramInstalled) TextApp.CHEOGRAM else TextApp.SMS

    fun emailAppFor(protonInstalled: Boolean): EmailApp = emailApp ?: if (protonInstalled) EmailApp.PROTON else EmailApp.ASK

    val voiceChoice: VoiceChoice get() = VoiceChoice.of(voice)

    fun toJson(): JSONObject = JSONObject().apply {
        put("wakeWord", wakeWord)
        put("wakeOnlyWhileCharging", wakeOnlyWhileCharging)
        put("speakReplies", speakReplies.name)
        put("speechRate", speechRate.toDouble())
        put("voice", voice)
        put("livePartials", livePartials)
        put("keepModelMinutes", keepModelMinutes)
        put("saveHistory", saveHistory)
        put("approvals", JSONObject().apply { for (t in ToolName.entries) put(t.id, approval(t).name) })
        textApp?.let { put("textApp", it.name) }
        put("smsGateway", smsGateway)
        put("phoneRegion", phoneRegion)
        emailApp?.let { put("emailApp", it.name) }
        put("eventCalendar", eventCalendar)
        put("eventMinutes", eventMinutes)
    }

    companion object {
        const val KEEP_ALWAYS = -1
        val KEEP_CHOICES = listOf(1, 5, 15, KEEP_ALWAYS)

        /**
         * Timers and alarms: just do it (spec item 7). Flashlight is harmless and undone in one word. Phase 2: texts,
         * emails and new events ask first (they go to someone, or into the calendar); looking things up and saving a
         * note (Undo on its card) just happen.
         */
        val DEFAULT_APPROVALS: Map<ToolName, Approval> = mapOf(
            ToolName.SET_TIMER to Approval.AUTO,
            ToolName.SET_ALARM to Approval.AUTO,
            ToolName.FLASHLIGHT to Approval.AUTO,
            ToolName.OPEN_APP to Approval.ASK,
            ToolName.MAPS_SEARCH to Approval.ASK,
            ToolName.MAPS_NAVIGATE to Approval.ASK,
            ToolName.SEND_TEXT to Approval.ASK,
            ToolName.DRAFT_EMAIL to Approval.ASK,
            ToolName.LOOKUP_CONTACT to Approval.AUTO,
            ToolName.ADD_NOTE to Approval.AUTO,
            ToolName.FIND_NOTES to Approval.AUTO,
            ToolName.ADD_EVENT to Approval.ASK,
            ToolName.READ_CALENDAR to Approval.AUTO,
        )

        val EVENT_MINUTES = listOf(15, 30, 45, 60, 90, 120)

        fun clampRate(v: Float): Float = if (v.isNaN()) 1.0f else v.coerceIn(0.5f, 2.0f)
        fun clampKeep(v: Int): Int = if (v == KEEP_ALWAYS) v else v.coerceIn(0, 120)
        fun clampEventMinutes(v: Int): Int = v.coerceIn(5, ToolCalls.MAX_EVENT_MINUTES)

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
                livePartials = o.optBoolean("livePartials", d.livePartials),
                keepModelMinutes = clampKeep(o.optInt("keepModelMinutes", d.keepModelMinutes)),
                saveHistory = o.optBoolean("saveHistory", d.saveHistory),
                approvals = ToolName.entries.associateWith { t -> enumOr(ap?.optString(t.id), DEFAULT_APPROVALS.getValue(t)) },
                textApp = enumValues<TextApp>().firstOrNull { it.name == o.optString("textApp") },
                smsGateway = PhoneNumbers.gateway(o.optString("smsGateway", d.smsGateway)),
                phoneRegion = PhoneNumbers.region(o.optString("phoneRegion", d.phoneRegion)),
                emailApp = enumValues<EmailApp>().firstOrNull { it.name == o.optString("emailApp") },
                eventCalendar = o.optLong("eventCalendar", d.eventCalendar),
                eventMinutes = clampEventMinutes(o.optInt("eventMinutes", d.eventMinutes)),
            )
        }
    }
}
