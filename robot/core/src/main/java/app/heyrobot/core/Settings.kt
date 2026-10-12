package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
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

/**
 * Settings › Actions › "Find the nearest place online" (0.4.0): whether "directions to the nearest pharmacy" may ask a
 * Nominatim search server (OpenStreetMap's unless [RobotSettings.nearestServer] names another). [ASK] (the default): the
 * first time, a card says what would leave the phone, and to which server, and you choose there ("Allow always", "Just
 * this time", "No, search the map"; [NearestAnswer] keeps a lasting answer).
 */
enum class NearestOnline(val title: String, val detail: String) {
    ASK("Ask the first time", "The first “nearest …” shows a card that says what would go online; you choose there."),
    ALWAYS("Always", "Robot asks the search server below (OpenStreetMap's Nominatim unless you choose another) for that kind of " +
        "place around where you are (about 1 km) and opens Organic Maps' route to the closest. It uses the internet."),
    NEVER("Never", "Organic Maps' search, which ranks places around you. Nothing goes online."),
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
    /** Settings › Voice › "Interrupt sensitivity" (0.4.0): "stop" and the wake phrases while Robot talks; Medium as before. */
    val interrupt: InterruptSensitivity = InterruptSensitivity.MEDIUM,
    /** How Robot's voice is played (the echo canceller's reference may depend on it; the barge-in test compares). */
    val playback: Playback = Playback.ASSISTANT,
    /** The volume key that stops Robot talking while the panel shows. */
    val stopKey: StopKey = StopKey.VOLUME_DOWN,
    // Helpers (0.6.0, schema 13; Ask Claude, docs/ask-claude.md, became the first of them). Null where the default depends on
    // whether the helper's API key is saved (HelperSettings).
    /** Claude and any helper you added, in your order. A new install has Claude, as before 0.6.0. */
    val helpers: List<HelperConfig> = listOf(HelperConfig.claude()),
    /** "When Robot needs help"; null: Automatic once the helper Robot asks can answer through its API, Ask me first before. */
    val help: Escalation? = null,
    /** "Ask:" the helper Robot checks with; null (or a helper you removed): the first that can answer through its API. */
    val helpDefault: String? = null,
    /** The daily budget for all helpers in cents (default $1.00); once reached, Automatic asks first for the rest of the day. */
    val helperBudgetCents: Int = 100,
    /** "Show cost" under each helper's reply. */
    val helperShowCost: Boolean = true,
    /**
     * Settings › Weather › Home for weather (0.4.0): looked up once when you save it, then used when the phone can't say
     * where it is (or isn't allowed to). Null: none.
     */
    val weatherHome: WeatherPlace? = null,
    // Conversation mode (0.5.0, docs/conversation-mode.md 9.3).
    /** The languages a conversation translates between, a [LangPair] id. */
    val convPair: String = LangPair.DEFAULT.id,
    /** "Translate" in the transcript (off by default); the Interpreter always translates ([ConversationSettings.translate]). */
    val convTranslate: Boolean = false,
    /** "Speak translations"; null: on in the Interpreter, off in the transcript ([ConversationSettings.speak]). */
    val convSpeak: Boolean? = null,
    /** The words of the open segment while people talk, screen on only (about every 1.5 s; off saves battery). */
    val convPartials: Boolean = true,
    /** The most speakers a transcript tells apart, 2 to 6 (the Interpreter: always 2). */
    val convMaxSpeakers: Int = 6,
    /**
     * Settings › Conversation › "Tell speakers apart" (0.5.0, phase 5.3): label each line S1, S2… by its voice. Null: on
     * once the speaker model is downloaded ([ConversationSettings.speakers]); false: off, and the download isn't offered.
     */
    val convSpeakers: Boolean? = null,
    /** "Hey robot works during a conversation" (off: the wake word sleeps for the whole session; "stop" still works while Robot talks). */
    val convWakeDuring: Boolean = false,
    /** "Start from the lock screen": a new recording shows nothing older. */
    val convStartLocked: Boolean = true,
    /** "Keep audio": the recording of everyone in the conversation, about 7 MB an hour (off). */
    val convKeepAudio: Boolean = false,
    /** The language of Robot's summaries ("fr" or "en"); null: yours ([ConversationSettings.summaryLang]). */
    val convSummaryLang: String? = null,
    /** Offer "Summarize with a stronger AI" first for a long or important conversation, or one the number check flagged. */
    val convAutoOfferHelper: Boolean = true,
    /**
     * A "me" voiceprint is enrolled (Settings › Conversation › Your voice). Only that it exists, kept in step with the
     * file by the main app: the voiceprint itself is in its own encrypted file, written and read by the :voice process
     * only (VoiceprintCodec), never in settings.
     */
    val meVoiceprint: Boolean = false,
    /**
     * Settings › Conversation › "Show the reminder again" (wall clock ms; 0: never): the consent reminder shows before
     * the next session unless you ticked "I'll tell people" after this ([ConsentRecord], kept by the voice process).
     */
    val convReminderAt: Long = 0,
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
    /**
     * "Answer cards by voice" (on): while a card waits, Robot listens for "yes" or "no" when replies are spoken or the
     * question came by voice. A yes never sends personal content to Claude unless [voiceYesPersonal].
     */
    val voiceCards: Boolean = true,
    /**
     * Settings › Actions › "Find the nearest place online" (0.4.0; Ask the first time): "directions to the nearest
     * pharmacy" finds the closest one with OpenStreetMap and opens Organic Maps' route to it; Never: Organic Maps' search.
     * The panel's own answer to the first-use card ([NearestAnswer], kept by the voice process) counts when it is newer
     * than [nearestOnlineAt] (Nearby.mode).
     */
    val nearestOnline: NearestOnline = NearestOnline.ASK,
    /** When [nearestOnline] was last chosen in Settings (wall clock ms); 0: never. */
    val nearestOnlineAt: Long = 0,
    /**
     * Settings › Actions › "Find the nearest place online" › Search server (schema 12): the Nominatim server asked, as its
     * HTTPS base ([NearbyServer.base]); "" is OpenStreetMap's ([NearbyServer.DEFAULT]). Nominatim's usage policy asks that
     * apps can switch the service without an update.
     */
    val nearestServer: String = "",
    /**
     * A model publisher's conditions you confirmed before its download ([ModelTerms], schema 12): the terms' id and when
     * (wall clock ms). Empty until you tick them.
     */
    val confirmedTerms: Map<String, Long> = emptyMap(),
    /**
     * Show [SafetyNotice.TEXT] once in the app (0.4.0): set for a settings file from before Safety & privacy (an update,
     * never a new install), cleared when it is tapped or dismissed.
     */
    val safetyNotice: Boolean = false,
    /**
     * Settings › Model (0.4.1, schema 7): a [BrainModel] id, the model Robot loads; Fast by default. Unknown or absent
     * (older files) means Fast. A choice that isn't on the phone or doesn't load falls back to Fast, and the panel says so
     * (ModelChoice). 0.4.7: either Fast's id means this phone's Fast (ModelCatalogue.resolve: the TPU build on a Tensor G5,
     * the general build elsewhere); the file keeps what was saved.
     */
    val brainModel: String = BrainModel.DEFAULT.id,
    /**
     * Settings › Model › "Let the model think first" (0.4.5, schema 10; off): a model that can reason before it answers
     * (Qwen3, MiniCPM 5, a GGUF file whose template takes enable_thinking) may; off, Robot tells it to answer directly.
     * Robot's built-in Gemma 4 models are the same either way. The thinking is never shown or spoken (core Thinking).
     */
    val modelThinks: Boolean = false,
    /**
     * Settings › Model › "Use the GPU" (0.4.7, on): where the phone's Fast is the general E2B and Robot offers the GPU
     * (ModelCatalogue.gpuOffered), it runs there; off, on the CPU. A GPU that failed is left until you try it again,
     * whatever this says. Absent from older files: on, the default (no new schema needed, as "directAnswers").
     */
    val modelGpu: Boolean = true,
) {
    val brainModelChoice: BrainModel get() = BrainModel.of(brainModel)

    /** The search server "the nearest …" asks: OpenStreetMap's unless you chose another. */
    val nearbyServer: NearbyServer get() = NearbyServer.of(nearestServer)

    /** The Claude helper, or null when you removed it. */
    val claude: HelperConfig? get() = helper(HelperConfig.CLAUDE_ID)

    val claudeModelChoice: ClaudeModel get() = ClaudeModel.of(claude?.model)

    fun helper(id: String?): HelperConfig? = helpers.firstOrNull { it.id == id }

    /** These settings with [h] in place of the helper of the same id (added at the end when there is none). */
    fun withHelper(h: HelperConfig): RobotSettings =
        copy(helpers = if (helpers.any { it.id == h.id }) helpers.map { if (it.id == h.id) h else it } else helpers + h)

    /** These settings with Claude changed by [f] (Claude as every install has it, when you had removed it). */
    fun withClaude(f: (HelperConfig) -> HelperConfig): RobotSettings = withHelper(f(claude ?: HelperConfig.claude()))

    val convPairChoice: LangPair get() = LangPair.of(convPair)

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
        put("interrupt", interrupt.name)
        put("playback", playback.name)
        put("stopKey", stopKey.name)
        put("helpers", org.json.JSONArray().apply { for (h in helpers) put(h.toJson()) })
        help?.let { put("help", it.name) }
        helpDefault?.let { put("helpDefault", it) }
        put("helperBudgetCents", helperBudgetCents)
        put("helperShowCost", helperShowCost)
        // The 0.4/0.5 fields, written from the Claude helper for one release: an older Robot reading this file keeps
        // Claude as it was set (and off when you removed it). The other helpers are new to it and ignored.
        val c = claude
        if (c == null) put("claudeEnabled", false) else {
            c.enabled?.let { put("claudeEnabled", it) }
            c.route?.let { put("claudeRoute", it.name) }
            put("claudeModel", c.model)
            put("claudeEffort", c.effort.name)
            put("claudeSearch", c.search)
            put("claudeLocked", c.locked)
            put("claudeVoice", c.voice)
        }
        put("claudeShowCost", helperShowCost)
        help?.let { put("claudeHelp", it.name) }
        put("claudeBudgetCents", helperBudgetCents)
        weatherHome?.let { put("weatherHome", JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
        put("convPair", convPair)
        put("convTranslate", convTranslate)
        convSpeak?.let { put("convSpeak", it) }
        put("convPartials", convPartials)
        put("convMaxSpeakers", convMaxSpeakers)
        convSpeakers?.let { put("convSpeakers", it) }
        put("convWakeDuring", convWakeDuring)
        put("convStartLocked", convStartLocked)
        put("convKeepAudio", convKeepAudio)
        convSummaryLang?.let { put("convSummaryLang", it) }
        put("convAutoOfferHelper", convAutoOfferHelper)
        put("meVoiceprint", meVoiceprint)
        if (convReminderAt > 0) put("convReminderAt", convReminderAt)
        put("safetyCards", safetyCards.name)
        put("claudeActionCards", claudeActionCards)
        put("whoCards", whoCards)
        put("learnedCards", learnedCards)
        put("deleteCards", deleteCards)
        put("lockedTools", org.json.JSONArray().apply { for (t in LockScreen.CHOICES) if (t in lockedTools) put(t.id) })
        put("personalToHelpers", personalToHelpers)
        put("voiceYesPersonal", voiceYesPersonal)
        put("keepMedia", keepMedia)
        put("voiceCards", voiceCards)
        put("nearestPlace", nearestOnline.name)
        if (nearestOnlineAt > 0) put("nearestPlaceAt", nearestOnlineAt)
        nearbyServer.saved.takeIf { it.isNotEmpty() }?.let { put("nearestServer", it) }
        if (confirmedTerms.isNotEmpty()) put("confirmedTerms", JSONObject().apply { for ((id, at) in confirmedTerms) put(id, at) })
        if (safetyNotice) put("safetyNotice", true)
        put("brainModel", brainModelChoice.id)
        put("modelThinks", modelThinks)
        put("modelGpu", modelGpu)
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
         * 7 (0.4.1): Settings › Model. Older files had one model, the TPU's Gemma 4 E2B: they read as Fast, the same model.
         * 8 and 9 were conversation mode's and the helpers' layouts before either was released, and 11 is kept for another
         * branch; no released Robot wrote any of them. 13 takes over what 8 and 9 brought (below).
         * 10 (0.4.5): "Let the model think first", off unless a file of this schema or later says on (older files never had it).
         * 12 (0.4.5, the compliance work): the nearest place's search server and the model terms you confirmed. Absent from
         * older files, which read as OpenStreetMap's server (the only one before) and nothing confirmed (a model downloaded
         * before keeps working; only a new download or import asks).
         * 13 (0.6.0): conversation mode's settings (0.5.0's schema 8, never released on its own). Absent from every older
         * file, 0.4.5's included, so they read as the defaults, except that live words in a conversation start off when
         * you had turned live words off for questions (to save battery). And helpers (schema 9 before, never released on
         * its own): a file without "helpers" (every one below 13) gets one, Claude (id "claude"), made from its claude*
         * fields, so Ask Claude goes on exactly as it was; the budget, "Show cost" and "When Robot needs help" become every
         * helper's. The claude* fields are still written, from the Claude helper, for one release (a downgrade keeps Claude).
         */
        const val SCHEMA = 13
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
            // 0.9: a read that goes online, like the weather (Settings › Web search decides whether it exists at all).
            ToolName.WEB_SEARCH to Approval.AUTO,
            // Not a level you set: Ask Claude's own settings decide (EscalationPolicy), and the hand-off always asks.
            ToolName.ASK_CLAUDE to Approval.ASK,
        )

        /** The actions whose default became "Just do it" in schema 4. */
        private val OPENS_WITHOUT_ASKING = setOf(ToolName.OPEN_APP, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE)

        val EVENT_MINUTES = listOf(15, 30, 45, 60, 90, 120)

        /** Daily budgets for all helpers offered in Settings, in cents. */
        val CLAUDE_BUDGETS = listOf(25, 50, 100, 200, 500)

        fun clampRate(v: Float): Float = if (v.isNaN()) 1.0f else v.coerceIn(0.5f, 2.0f)
        fun clampKeep(v: Int): Int = if (v == KEEP_ALWAYS) v else v.coerceIn(0, 120)
        fun clampEventMinutes(v: Int): Int = v.coerceIn(5, ToolCalls.MAX_EVENT_MINUTES)
        fun clampSpeakers(v: Int): Int = v.coerceIn(ConversationSettings.SPEAKER_CHOICES.first, ConversationSettings.SPEAKER_CHOICES.last)
        /** Robot summarizes in French or English; anything else means yours. */
        fun summaryLang(v: String?): String? = v?.takeIf { it == "fr" || it == "en" }

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
                interrupt = enumOr(o.optString("interrupt"), d.interrupt),
                playback = enumOr(o.optString("playback"), d.playback),
                stopKey = enumOr(o.optString("stopKey"), d.stopKey),
                // Schema 13 (the helpers' unreleased 9): the list as saved (an empty one stays empty: you removed every helper);
                // a file without it (every released one, 0.4.5's schema 12 included), Claude from its claude* fields.
                helpers = o.optJSONArray("helpers")?.let(HelperConfig::listFromJson) ?: listOf(legacyClaude(o)),
                help = enumValues<Escalation>().firstOrNull { it.name == o.optString(if (o.has("help")) "help" else "claudeHelp") },
                helpDefault = o.optString("helpDefault").takeIf { it.isNotEmpty() },
                helperBudgetCents = o.optInt(if (o.has("helperBudgetCents")) "helperBudgetCents" else "claudeBudgetCents", d.helperBudgetCents).coerceIn(0, 100_000),
                helperShowCost = o.optBoolean(if (o.has("helperShowCost")) "helperShowCost" else "claudeShowCost", d.helperShowCost),
                weatherHome = o.optJSONObject("weatherHome")?.let(::place),
                convPair = LangPair.of(o.optString("convPair", d.convPair)).id,
                convTranslate = o.optBoolean("convTranslate", d.convTranslate),
                convSpeak = if (o.has("convSpeak")) o.optBoolean("convSpeak") else null,
                // Before schema 13 there was no such setting: it follows "Show the words while you speak".
                convPartials = o.optBoolean("convPartials", if (o.optInt("schema", 1) < 13) o.optBoolean("livePartials", d.livePartials) else d.convPartials),
                convMaxSpeakers = clampSpeakers(o.optInt("convMaxSpeakers", d.convMaxSpeakers)),
                // Schema 13 (0.5.0, phase 5.3): absent means "on once the speaker model is downloaded".
                convSpeakers = if (o.has("convSpeakers")) o.optBoolean("convSpeakers") else null,
                convWakeDuring = o.optBoolean("convWakeDuring", d.convWakeDuring),
                convStartLocked = o.optBoolean("convStartLocked", d.convStartLocked),
                convKeepAudio = o.optBoolean("convKeepAudio", d.convKeepAudio),
                convSummaryLang = summaryLang(o.optString("convSummaryLang").takeIf { o.has("convSummaryLang") }),
                convAutoOfferHelper = o.optBoolean("convAutoOfferHelper", d.convAutoOfferHelper),
                meVoiceprint = o.optBoolean("meVoiceprint", d.meVoiceprint),
                // Schema 13 (0.5.0): absent means the reminder was never asked for again.
                convReminderAt = o.optLong("convReminderAt", 0).coerceAtLeast(0),
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
                voiceCards = o.optBoolean("voiceCards", d.voiceCards),
                // Early 0.4.0 builds saved a switch ("nearestOnline", on by default): on or absent asks the first time, off is Never.
                nearestOnline = if (o.has("nearestPlace")) enumOr(o.optString("nearestPlace"), d.nearestOnline)
                    else if (!o.optBoolean("nearestOnline", true)) NearestOnline.NEVER else NearestOnline.ASK,
                nearestOnlineAt = o.optLong("nearestPlaceAt", 0).coerceAtLeast(0),
                // Schema 12: absent (older files) or unusable means OpenStreetMap's; kept in its checked form.
                nearestServer = NearbyServer.of(o.optString("nearestServer")).saved,
                confirmedTerms = o.optJSONObject("confirmedTerms")?.let(::terms) ?: d.confirmedTerms,
                // A file from before schema 6 (0.3.x, early 0.4.0): an update, so the one-time line about Safety & privacy shows.
                safetyNotice = if (o.optInt("schema", 1) < 6) true else o.optBoolean("safetyNotice", false),
                // Schema 7: before it there was only the model Fast is (and no key); an unknown id is Fast too.
                brainModel = if (o.optInt("schema", 1) < 7) BrainModel.FAST.id else BrainModel.of(o.optString("brainModel")).id,
                // Schema 10: before it, models answered directly (no switch): off.
                modelThinks = o.optInt("schema", 1) >= 10 && o.optBoolean("modelThinks", d.modelThinks),
                // 0.4.7: absent (any older file) means on, the default.
                modelGpu = o.optBoolean("modelGpu", d.modelGpu),
            )
        }

        /** Confirmed terms: an id (at most 200 characters) and a time after 1970; anything else is dropped, at most 50. */
        private fun terms(x: JSONObject): Map<String, Long> = x.keys().asSequence()
            .filter { it.isNotBlank() && it.length <= 200 }
            .mapNotNull { k -> x.optLong(k, 0).takeIf { it > 0 }?.let { k to it } }
            .take(50).toMap()

        /**
         * Claude from a file before schema 13 (0.3 to 0.4.5): its claude* fields, each as that version read it, so the route,
         * the escalation and the budget come out exactly as before.
         */
        private fun legacyClaude(o: JSONObject): HelperConfig {
            val c = HelperConfig.claude()
            return c.copy(
                enabled = if (o.has("claudeEnabled")) o.optBoolean("claudeEnabled") else null,
                route = enumValues<ClaudeRoute>().firstOrNull { it.name == o.optString("claudeRoute") },
                model = ClaudeModel.of(o.optString("claudeModel", c.model)).id,
                effort = enumOr(o.optString("claudeEffort"), c.effort),
                search = o.optBoolean("claudeSearch", c.search),
                locked = o.optBoolean("claudeLocked", c.locked),
                voice = ClaudeVoices.of(o.optString("claudeVoice", c.voice)),
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
