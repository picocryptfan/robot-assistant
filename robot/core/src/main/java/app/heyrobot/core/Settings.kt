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
    /**
     * Settings › "Direct answers" (0.4.0, on): the local model helps with whatever you ask without refusing or adding
     * warnings, and still says when something isn't possible ([Prompt.DIRECT]). Off: the model's own judgment.
     */
    val directAnswers: Boolean = true,
    /** Minutes Gemma stays loaded after the panel closes; [KEEP_ALWAYS] = keep it loaded. */
    val keepModelMinutes: Int = 5,
    /** Save conversations to the history on this phone (screen conversations only when kept). */
    val saveHistory: Boolean = true,
    val approvals: Map<ToolName, Approval> = DEFAULT_APPROVALS,
    /** Null until the person picks one: Cheogram when it is installed, else the SMS app ([textAppFor]). */
    val textApp: TextApp? = null,
    /** Where Cheogram texts phone numbers: "+<E.164 digits>@<gateway>" (JMP's is cheogram.com). */
    val smsGateway: String = PhoneNumbers.DEFAULT_GATEWAY,
    /** For numbers written without a country code (PhoneNumberUtils' default country); null: the phone's own ([regionFor]). */
    val phoneRegion: String? = null,
    /** Null until the person picks one: Proton Mail when it is installed, else the chooser ([emailAppFor]). */
    val emailApp: EmailApp? = null,
    /** A CalendarContract calendar id for new events; [CalendarPick.AUTO]: Robot picks (CalendarPick.choose). */
    val eventCalendar: Long = CalendarPick.AUTO,
    /** Length of a new event when no end or duration was said. */
    val eventMinutes: Int = 60,
    /** Settings › People › "Remember who I pick": Robot offers to remember who you pick in Cheogram or on a card. */
    val rememberPicks: Boolean = true,
    /**
     * Settings › People › "Learn names from Cheogram notifications" (off): with notification access, Robot keeps who
     * messages you in Cheogram (name, address, account), never the messages.
     */
    val learnFromCheogram: Boolean = false,
    /** The wake phrases (Settings › Voice › Wake words); "hey robot" at Medium until the user changes it. */
    val wakePhrases: List<WakePhrase> = WakePhrases.DEFAULT,
    /** Settings › Voice › "Hear me while Robot talks". */
    val bargeIn: BargeIn = BargeIn.ECHO_CANCEL,
    /** How Robot's voice is played (the echo canceller's reference may depend on it; the barge-in test compares). */
    val playback: Playback = Playback.ASSISTANT,
    /** The volume key that stops Robot talking while the panel shows. */
    val stopKey: StopKey = StopKey.VOLUME_DOWN,
    // Ask Claude (docs/ask-claude.md). Null where the default depends on whether an API key is saved (ClaudeSettings).
    /** Ask Claude on or off; null: on (the Claude app without a key, the API with one). */
    val claudeEnabled: Boolean? = null,
    /** The Claude app or the API; null: the app without a key, the API with one. */
    val claudeRoute: ClaudeRoute? = null,
    /** A [ClaudeModel] id. */
    val claudeModel: String = ClaudeModel.DEFAULT.id,
    val claudeEffort: ClaudeEffortSetting = ClaudeEffortSetting.AUTO,
    /** "Let Claude search the web". */
    val claudeSearch: Boolean = true,
    /** "Allow on lock screen": off, because a locked phone can't show who is asking. */
    val claudeLocked: Boolean = false,
    /** Claude's Kokoro voice ([ClaudeVoices]). */
    val claudeVoice: String = ClaudeVoices.DEFAULT,
    /** "Show cost" under each Claude reply. */
    val claudeShowCost: Boolean = true,
    /** "When Robot needs help"; null: Automatic once a key is saved, Ask me first before. */
    val claudeHelp: Escalation? = null,
    /** Daily budget for Claude in cents (default $1.00); once reached, Automatic asks first for the rest of the day. */
    val claudeBudgetCents: Int = 100,
    /**
     * Settings › Weather › Home for weather (0.4.0): looked up once when you save it, then used when the phone can't say
     * where it is (or isn't allowed to). Null: none.
     */
    val weatherHome: WeatherPlace? = null,
    // Settings › Safety & privacy (0.4.0, schema 6): each rule that makes an action ask first is a switch.
    /** "Safety cards": when content Robot read makes an action ask first (older files: the new default too). */
    val safetyCards: SafetyCards = SafetyCards.UNASKED,
    /** "Card when Claude asks Robot to do something". */
    val claudeActionCards: Boolean = true,
    /** "Card when who-to isn't certain": several people, a near match, a number or address the model wrote, a loose app name. */
    val whoCards: Boolean = true,
    /** "Card for names learned from Cheogram". */
    val learnedCards: Boolean = true,
    /** "Card before deleting files": free_storage's photos and videos (Android asks as well) and Robot's cache. */
    val deleteCards: Boolean = true,
    /** "Works on the lock screen": the actions allowed while locked (questions always). */
    val lockedTools: Set<ToolName> = LockScreen.DEFAULT,
    /**
     * "Send personal content to helpers automatically" (off): Robot checking with Claude by itself may send the
     * conversation's personal content and this question's screen or photo, and what a read tool finds for Claude goes
     * without "Share with Claude". Claude joining ("go get Claude") still asks first.
     */
    val personalToHelpers: Boolean = false,
    /** "Voice yes can send personal content" (off): a spoken or typed yes may send what otherwise needs a tap. */
    val voiceYesPersonal: Boolean = false,
    /**
     * "Keep screenshots and photos in history" (off): with history on, a question's picture (a small thumbnail) and the
     * screen's text are saved with the conversation, for you to see in History. Off: only that one was attached.
     */
    val keepMedia: Boolean = false,
) {
    val claudeModelChoice: ClaudeModel get() = ClaudeModel.of(claudeModel)

    fun approval(t: ToolName): Approval = approvals[t] ?: DEFAULT_APPROVALS.getValue(t)

    fun textAppFor(cheogramInstalled: Boolean): TextApp = textApp ?: if (cheogramInstalled) TextApp.CHEOGRAM else TextApp.SMS

    /** The region for numbers without a country code: the one set, else the phone's own ([device]), else none (""). */
    fun regionFor(device: String?): String = phoneRegion ?: device ?: ""

    fun emailAppFor(protonInstalled: Boolean): EmailApp = emailApp ?: if (protonInstalled) EmailApp.PROTON else EmailApp.ASK

    val voiceChoice: VoiceChoice get() = VoiceChoice.of(voice)

    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("wakeWord", wakeWord)
        put("wakeOnlyWhileCharging", wakeOnlyWhileCharging)
        put("speakReplies", speakReplies.name)
        put("speechRate", speechRate.toDouble())
        put("voice", voice)
        put("livePartials", livePartials)
        put("directAnswers", directAnswers)
        put("keepModelMinutes", keepModelMinutes)
        put("saveHistory", saveHistory)
        put("approvals", JSONObject().apply { for (t in ToolName.entries) put(t.id, approval(t).name) })
        textApp?.let { put("textApp", it.name) }
        put("smsGateway", smsGateway)
        phoneRegion?.let { put("phoneRegion", it) }
        emailApp?.let { put("emailApp", it.name) }
        put("eventCalendar", eventCalendar)
        put("eventMinutes", eventMinutes)
        put("rememberPicks", rememberPicks)
        put("learnFromCheogram", learnFromCheogram)
        put("wakePhrases", org.json.JSONArray().apply {
            for (p in wakePhrases) put(JSONObject().put("id", p.id).put("text", p.text).put("sensitivity", p.sensitivity.name).put("enabled", p.enabled))
        })
        put("bargeIn", bargeIn.name)
        put("playback", playback.name)
        put("stopKey", stopKey.name)
        claudeEnabled?.let { put("claudeEnabled", it) }
        claudeRoute?.let { put("claudeRoute", it.name) }
        put("claudeModel", claudeModel)
        put("claudeEffort", claudeEffort.name)
        put("claudeSearch", claudeSearch)
        put("claudeLocked", claudeLocked)
        put("claudeVoice", claudeVoice)
        put("claudeShowCost", claudeShowCost)
        claudeHelp?.let { put("claudeHelp", it.name) }
        put("claudeBudgetCents", claudeBudgetCents)
        weatherHome?.let { put("weatherHome", JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        put("safetyCards", safetyCards.name)
        put("claudeActionCards", claudeActionCards)
        put("whoCards", whoCards)
        put("learnedCards", learnedCards)
        put("deleteCards", deleteCards)
        put("lockedTools", org.json.JSONArray().apply { for (t in LockScreen.CHOICES) if (t in lockedTools) put(t.id) })
        put("personalToHelpers", personalToHelpers)
        put("voiceYesPersonal", voiceYesPersonal)
        put("keepMedia", keepMedia)
    }

    companion object {
        /**
         * The settings file's layout. 2 (0.2.3): the phone region is saved only when chosen. Files without it come from
         * earlier versions, which saved "CA" whether chosen or not: there it reads as unset, so the phone's own applies.
         * 3 (0.3.0): Ask Claude's settings (absent from older files, so they read as the defaults).
         * 4 (0.3.1): opening apps and Organic Maps just happen by default. Older files saved "Ask first" for them
         * whether chosen or not, so there it reads as the new default; "Off" stays off.
         * 5 (0.4.0): the Home for weather (absent from older files: none) and the new actions' levels (their defaults).
         * 6 (0.4.0): Settings › Safety & privacy. Older files keep every rule as it was (the switches' defaults are today's
         * behaviour, the lock screen still timers and alarms) except "Safety cards", which takes the new default, "When
         * you didn't ask for it", as the person asked.
         */
        const val SCHEMA = 6
        const val KEEP_ALWAYS = -1
        val KEEP_CHOICES = listOf(1, 5, 15, KEEP_ALWAYS)

        /**
         * Timers and alarms: just do it (spec item 7). Flashlight is harmless and undone in one word. Opening an app or
         * a search in Organic Maps only shows something, so it just happens (0.3.1). Phase 2: texts, emails and new
         * events ask first (they go to someone, or into the calendar); looking things up and saving a note (Undo on its
         * card) just happen.
         */
        val DEFAULT_APPROVALS: Map<ToolName, Approval> = mapOf(
            ToolName.SET_TIMER to Approval.AUTO,
            ToolName.SET_ALARM to Approval.AUTO,
            ToolName.FLASHLIGHT to Approval.AUTO,
            ToolName.OPEN_APP to Approval.AUTO,
            ToolName.MAPS_SEARCH to Approval.AUTO,
            ToolName.MAPS_NAVIGATE to Approval.AUTO,
            ToolName.SEND_TEXT to Approval.ASK,
            ToolName.DRAFT_EMAIL to Approval.ASK,
            ToolName.LOOKUP_CONTACT to Approval.AUTO,
            ToolName.ADD_NOTE to Approval.AUTO,
            ToolName.FIND_NOTES to Approval.AUTO,
            ToolName.ADD_EVENT to Approval.ASK,
            ToolName.READ_CALENDAR to Approval.AUTO,
            // 0.4.0: reading only (the weather goes online to Open-Meteo, Settings › Actions says so).
            ToolName.WEATHER to Approval.AUTO,
            ToolName.PHONE_STATUS to Approval.AUTO,
            // They stop or delete something (or unload Robot's model): a card first.
            ToolName.FREE_MEMORY to Approval.ASK,
            ToolName.FREE_STORAGE to Approval.ASK,
            // Not a level you set: Ask Claude's own settings decide (EscalationPolicy), and the hand-off always asks.
            ToolName.ASK_CLAUDE to Approval.ASK,
        )

        /** The actions whose default became "Just do it" in schema 4. */
        private val OPENS_WITHOUT_ASKING = setOf(ToolName.OPEN_APP, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE)

        val EVENT_MINUTES = listOf(15, 30, 45, 60, 90, 120)

        /** Daily Claude budgets offered in Settings, in cents. */
        val CLAUDE_BUDGETS = listOf(25, 50, 100, 200, 500)

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
                // Absent from older files: on, the default (no new schema needed).
                directAnswers = o.optBoolean("directAnswers", d.directAnswers),
                keepModelMinutes = clampKeep(o.optInt("keepModelMinutes", d.keepModelMinutes)),
                saveHistory = o.optBoolean("saveHistory", d.saveHistory),
                approvals = ToolName.entries.associateWith { t ->
                    if (t.internal) return@associateWith DEFAULT_APPROVALS.getValue(t)
                    val saved = enumOr(ap?.optString(t.id), DEFAULT_APPROVALS.getValue(t))
                    if (o.optInt("schema", 1) < 4 && t in OPENS_WITHOUT_ASKING && saved == Approval.ASK) Approval.AUTO else saved
                },
                textApp = enumValues<TextApp>().firstOrNull { it.name == o.optString("textApp") },
                smsGateway = PhoneNumbers.gateway(o.optString("smsGateway", d.smsGateway)),
                // Kept as saved; absent or unreadable means the phone's own region, and so does the "CA" every earlier
                // version wrote by default.
                phoneRegion = PhoneNumbers.region(o.optString("phoneRegion", "")).takeUnless { o.optInt("schema", 1) < 2 && it == "CA" },
                emailApp = enumValues<EmailApp>().firstOrNull { it.name == o.optString("emailApp") },
                eventCalendar = o.optLong("eventCalendar", d.eventCalendar),
                eventMinutes = clampEventMinutes(o.optInt("eventMinutes", d.eventMinutes)),
                rememberPicks = o.optBoolean("rememberPicks", d.rememberPicks),
                learnFromCheogram = o.optBoolean("learnFromCheogram", d.learnFromCheogram),
                wakePhrases = o.optJSONArray("wakePhrases")?.let(::phrases) ?: d.wakePhrases,
                bargeIn = enumOr(o.optString("bargeIn"), d.bargeIn),
                playback = enumOr(o.optString("playback"), d.playback),
                stopKey = enumOr(o.optString("stopKey"), d.stopKey),
                claudeEnabled = if (o.has("claudeEnabled")) o.optBoolean("claudeEnabled") else null,
                claudeRoute = enumValues<ClaudeRoute>().firstOrNull { it.name == o.optString("claudeRoute") },
                claudeModel = ClaudeModel.of(o.optString("claudeModel", d.claudeModel)).id,
                claudeEffort = enumOr(o.optString("claudeEffort"), d.claudeEffort),
                claudeSearch = o.optBoolean("claudeSearch", d.claudeSearch),
                claudeLocked = o.optBoolean("claudeLocked", d.claudeLocked),
                claudeVoice = ClaudeVoices.of(o.optString("claudeVoice", d.claudeVoice)),
                claudeShowCost = o.optBoolean("claudeShowCost", d.claudeShowCost),
                claudeHelp = enumValues<Escalation>().firstOrNull { it.name == o.optString("claudeHelp") },
                claudeBudgetCents = o.optInt("claudeBudgetCents", d.claudeBudgetCents).coerceIn(0, 100_000),
                weatherHome = o.optJSONObject("weatherHome")?.let(::place),
                // Schema 6: absent from older files, which read as the defaults (the new "When you didn't ask for it" included).
                safetyCards = enumOr(o.optString("safetyCards"), d.safetyCards),
                claudeActionCards = o.optBoolean("claudeActionCards", d.claudeActionCards),
                whoCards = o.optBoolean("whoCards", d.whoCards),
                learnedCards = o.optBoolean("learnedCards", d.learnedCards),
                deleteCards = o.optBoolean("deleteCards", d.deleteCards),
                lockedTools = o.optJSONArray("lockedTools")?.let { a ->
                    (0 until a.length()).mapNotNull { ToolName.of(a.optString(it)) }.filter { !it.internal }.toSet()
                } ?: d.lockedTools,
                personalToHelpers = o.optBoolean("personalToHelpers", d.personalToHelpers),
                voiceYesPersonal = o.optBoolean("voiceYesPersonal", d.voiceYesPersonal),
                keepMedia = o.optBoolean("keepMedia", d.keepMedia),
            )
        }

        /** A saved Home for weather; one without a name or with coordinates off the globe is dropped. */
        private fun place(x: JSONObject): WeatherPlace? {
            val name = x.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
            val lat = x.optDouble("lat").takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
            val lon = x.optDouble("lon").takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
            return WeatherPlace(name.take(ToolCalls.MAX_PLACE), lat, lon)
        }

        /** Saved wake phrases; a damaged entry is dropped, never fatal (an empty list stays empty: the user removed them). */
        private fun phrases(a: org.json.JSONArray): List<WakePhrase> = WakePhrases.sanitize((0 until a.length()).mapNotNull { i ->
            val x = a.optJSONObject(i) ?: return@mapNotNull null
            WakePhrase(x.optInt("id", 0), x.optString("text", ""), enumOr(x.optString("sensitivity"), WakeSensitivity.MEDIUM), x.optBoolean("enabled", true))
        })
    }
}
