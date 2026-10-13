package app.heyrobot.core.feedback

import app.heyrobot.core.Addresses
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.LookSettings
import app.heyrobot.core.PhoneNumbers
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.VoiceFiles
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.helpers.HelperKind
import app.heyrobot.core.helpers.Presets
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.readOrNull
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure (0.7). What Send feedback may add to your words, each only when you tick it: the model problem's report (0.4.7's,
// as it is), the voice log (codes and numbers only), a summary of the settings (switches and levels only) and the
// updater's last error (codes). Each is built here from the few files FeedbackSources reads (the settings, the look, the
// updater's two files, the voice logs, the GPU failure Robot remembers, and 0.7.0's memory.json and messages.json for
// their switches and counts) and nothing else: never the conversations,
// History, transcripts, messages, memory, People or contacts, notes or calendar, nor a wake phrase's words (a phrase is
// "#2"), a helper's name, address or key, the updater's repository or token, a Wi-Fi or Bluetooth name, or a place.

object FeedbackDiagnostics {
    /** The voice log's events a report adds, newest first. */
    const val LOG_EVENTS = 60

    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private fun on(b: Boolean) = if (b) "on" else "off"

    /** "10-11 14:05:03  WAKE 2 -1 0  Wake word #2: opened the panel": the code and its numbers, then the same in fixed words. */
    fun logLine(e: VoiceLogEntry, zone: ZoneId): String =
        clock.format(Instant.ofEpochMilli(e.time).atZone(zone)) + "  " + e.event.name + " " + e.a + " " + e.b + " " + e.c + "  " + VoiceLogText.describe(e)

    /** The last [LOG_EVENTS] events of [newestFirst], wake phrases by number only (VoiceLogText without names). */
    fun voiceLog(newestFirst: List<VoiceLogEntry>, zone: ZoneId = ZoneId.systemDefault()): DiagnosticBlock {
        val events = newestFirst.take(LOG_EVENTS)
        return DiagnosticBlock(Diagnostic.VOICE_LOG, "Voice log, last ${events.size} events, newest first (codes and numbers only):",
            events.map { logLine(it, zone) }.ifEmpty { listOf("(no events)") })
    }

    /** 0.4.7's problem report as it is (it holds codes, this phone and the model only: core ProblemReport). */
    fun problem(report: String, title: String = "Last problem report:"): DiagnosticBlock =
        DiagnosticBlock(Diagnostic.PROBLEM, title, report.replace("\r\n", "\n").trimEnd().lines())

    /**
     * Every switch and level, by its code, grouped as Settings has them. Names, addresses, wake phrases' words, server
     * addresses, keys and places are never read: a phrase is its number and sensitivity, a helper its kind and switches, a
     * place or a server "set" or "default".
     */
    fun settings(s: RobotSettings, look: LookSettings, updates: UpdateSettings, memory: MemorySettings = MemorySettings(),
        messages: MessagesFile = MessagesFile()): DiagnosticBlock {
        val lines = mutableListOf<String>()
        fun add(area: String, vararg kv: Pair<String, Any>) {
            lines += "$area: " + kv.joinToString(" · ") { (k, v) -> "$k=" + (if (v is Boolean) on(v) else v.toString()) }
        }
        val phrases = s.wakePhrases.joinToString(", ", "[", "]") { "#${it.id} ${it.sensitivity.name} ${on(it.enabled)}" }
        add("Voice", "wakeWord" to s.wakeWord, "onlyWhileCharging" to s.wakeOnlyWhileCharging, "phrases" to phrases,
            "speakReplies" to s.speakReplies.name, "speechRate" to String.format(Locale.US, "%.2f", s.speechRate), "voice" to s.voiceChoice.name,
            "liveWords" to s.livePartials)
        add("Hearing you while Robot talks", "bargeIn" to s.bargeIn.name, "interrupt" to s.interrupt.name, "playback" to s.playback.name,
            "stopKey" to s.stopKey.name)
        add("Model", "model" to s.brainModelChoice.id, "useGpu" to s.modelGpu, "thinkFirst" to s.modelThinks,
            "keepLoadedMin" to s.keepModelMinutes, "directAnswers" to s.directAnswers, "termsConfirmed" to s.confirmedTerms.size)
        add("History", "save" to s.saveHistory, "keepScreenshotsAndPhotos" to s.keepMedia)
        add("Actions", "levels" to ToolName.entries.joinToString(",") { "${it.id}:${s.approval(it).name}" })
        add("Texts and email", "textApp" to (s.textApp?.name ?: "default"), "emailApp" to (s.emailApp?.name ?: "default"),
            "smsGateway" to (if (s.smsGateway == PhoneNumbers.DEFAULT_GATEWAY) "default" else "another"),
            "numberRegion" to (if (s.phoneRegion == null) "the phone's" else "set"))
        add("Calendar", "calendar" to (if (s.eventCalendar == CalendarPick.AUTO) "Robot picks" else "chosen"), "eventMinutes" to s.eventMinutes)
        add("People", "rememberPicks" to s.rememberPicks, "learnFromCheogram" to s.learnFromCheogram)
        // 0.7: memory's and messages' switches, and how many apps' keys and people's choices are kept (never an item, a
        // message, a name, a People id or a key).
        add("Memory", "on" to memory.on, "inAnswers" to memory.useInAnswers, "lockScreen" to memory.onLockScreen, "sayPrivateAloud" to memory.speakSecrets,
            "helpers" to memory.helpers.name, "suggestions" to memory.suggestions.name)
        val m = messages.settings
        add("Messages", "on" to m.on, "apps" to m.allowlist.apps.map { it.name }.sorted().joinToString(",").ifEmpty { "none" }, "groups" to m.allowlist.groups,
            "replies" to m.replyRoute.name, "card" to m.replyCard, "voiceYes" to m.voiceYesSends, "afterReading" to m.afterReading.name, "keep" to m.keep.name,
            "translate" to m.translate, "theirLanguage" to m.offerTheirLanguage, "lockScreen" to m.locked.name, "keysKept" to messages.pins.size,
            "firstUse" to messages.firstUse.size, "withoutCard" to messages.noCard.size, "replyLanguages" to messages.replyLang.size)
        add("Safety", "safetyCards" to s.safetyCards.name, "helperActionCards" to s.claudeActionCards, "whoCards" to s.whoCards,
            "learnedNameCards" to s.learnedCards, "deleteCards" to s.deleteCards,
            "onLockScreen" to s.lockedTools.map { it.id }.sorted().joinToString(",").ifEmpty { "none" },
            "personalToHelpers" to s.personalToHelpers, "voiceYesPersonal" to s.voiceYesPersonal, "answerCardsByVoice" to s.voiceCards)
        add("Nearest place", "online" to s.nearestOnline.name, "server" to (if (s.nearestServer.isEmpty()) "OpenStreetMap" else "another"))
        add("Weather", "home" to (if (s.weatherHome == null) "none" else "set"))
        val helpers = s.helpers.joinToString(", ", "[", "]") { h ->
            listOfNotNull(h.kind.name, on(h.on), h.route?.name, if (h.search) "search" else null, if (h.trust) "trusted" else null).joinToString(" ")
        }
        add("Helpers", "count" to s.helpers.size, "helpers" to helpers, "help" to (s.help?.name ?: "default"),
            "budgetCents" to s.helperBudgetCents, "showCost" to s.helperShowCost, "homeServers" to s.helpers.count { it.kind == HelperKind.HOME })
        add("Conversation", "pair" to s.convPairChoice.id, "translate" to s.convTranslate, "speak" to (s.convSpeak?.let(::on) ?: "default"),
            "liveWords" to s.convPartials, "maxSpeakers" to s.convMaxSpeakers, "speakers" to (s.convSpeakers?.let(::on) ?: "default"),
            "wakeDuring" to s.convWakeDuring, "startLocked" to s.convStartLocked, "keepAudio" to s.convKeepAudio,
            "summaryLang" to (s.convSummaryLang?.takeIf { it == "fr" || it == "en" } ?: "default"), "offerStrongerAi" to s.convAutoOfferHelper,
            "yourVoice" to s.meVoiceprint)
        add("Look", "classic" to look.classic, "wallpaperColours" to look.wallpaperColors)
        add("Updates", "automatic" to updates.autoCheck, "wifiDownload" to updates.wifiDownload, "testBuilds" to updates.testBuilds,
            "installWithoutAsking" to updates.installWithoutAsking, "repository" to (if (updates.repo == null) "Robot's own" else "another"),
            "skipped" to (if (updates.skipped == null) "none" else "a version"))
        return DiagnosticBlock(Diagnostic.SETTINGS, "Settings summary (switches and levels only):", lines)
    }

    /** A version from a release (GitHub's text): its letters, digits, dots and dashes only. */
    private fun version(v: String?): String = v?.filter { it.isLetterOrDigit() || it == '.' || it == '-' }?.take(32)?.ifEmpty { null } ?: "?"

    /** The updater went wrong: a check that failed, a file it refused, an install that didn't finish. */
    fun updaterFailed(st: UpdateState): Boolean =
        st.outcome == CheckOutcome.FAILED || st.fail != null || st.refusedWhy != null || (st.install != null && st.install != InstallOutcome.SUCCESS)

    /** The updater's last check, error, refused file and install, as codes (never the repository, the token or a link). */
    fun updater(st: UpdateState, zone: ZoneId = ZoneId.systemDefault()): DiagnosticBlock {
        val lines = mutableListOf<String>()
        lines += "lastCheck=" + (if (st.lastCheck > 0) stamp.format(Instant.ofEpochMilli(st.lastCheck).atZone(zone)) else "never") +
            " · outcome=" + (st.outcome?.name ?: "none") + " · error=" + (st.fail?.name ?: "none") + (if (st.failHttp != 0) " · http=${st.failHttp}" else "") +
            (st.retryAt?.let { " · retryAfter=" + stamp.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "")
        st.found?.let { lines += "found=${version(it.version)} · testBuild=${on(it.testBuild)} · downloaded=${on(st.downloaded != null)}" }
        st.refusedWhy?.let { lines += "refusedFile=${it.name}" }
        st.install?.let { lines += "lastInstall=${it.name} (${version(st.installVersion)})" }
        return DiagnosticBlock(Diagnostic.UPDATER, "Updater (codes only):", lines)
    }
}

/**
 * The last pass over every diagnostic line before it is shown or sent: any personal text Robot knows of (a wake phrase's
 * words, a helper's name, aliases, address or model on your own server, your weather home, an SMS gateway or search
 * server you set, the updater's repository, the number feedback goes to) becomes [LEFT_OUT]. The diagnostics are built
 * without them; this keeps one from slipping in through a line Robot didn't write (Android's text in a problem report).
 * Your own words are never changed.
 */
object FeedbackPrivacy {
    const val LEFT_OUT = "[left out]"
    /** Shorter text would hide ordinary words. */
    const val MIN_LENGTH = 4

    fun secrets(s: RobotSettings, updates: UpdateSettings?, defaultRepo: String?, extra: Collection<String> = emptyList()): List<String> {
        val out = mutableListOf<String>()
        out += s.wakePhrases.map { it.text }
        for (h in s.helpers) {
            if (h.name != h.presetOf.defaultName && !(h.kind == HelperKind.OTHER && h.name == Presets.OTHER_FORMER_NAME)) out += h.name
            out += h.aliases
            h.baseUrl?.let { u -> out += u; hostOf(u)?.let { out += it } }
            if (h.kind == HelperKind.HOME || h.kind == HelperKind.OTHER) out += h.model
        }
        s.weatherHome?.let { out += it.name }
        if (s.smsGateway != PhoneNumbers.DEFAULT_GATEWAY) out += s.smsGateway
        if (s.nearestServer.isNotEmpty()) { out += s.nearestServer; hostOf(s.nearestServer)?.let { out += it } }
        updates?.repo?.let { out += it }
        defaultRepo?.let { out += it }
        out += extra
        return out.map { it.trim() }.filter { it.length >= MIN_LENGTH }.distinct().sortedByDescending { it.length }
    }

    private fun hostOf(url: String): String? = try { java.net.URI(url.trim()).host?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    /** [line] with every one of [secrets] (any case) as [LEFT_OUT]. */
    fun redact(line: String, secrets: Collection<String>): String {
        var out = line
        for (s in secrets) if (s.length >= MIN_LENGTH && out.contains(s, ignoreCase = true)) out = out.replace(s, LEFT_OUT, ignoreCase = true)
        return out
    }
}

/** What the diagnostics are made from, read from Robot's own folder (files/robot) and nothing else. */
data class FeedbackInputs(
    val settings: RobotSettings,
    val look: LookSettings,
    val updates: UpdateSettings,
    val updateState: UpdateState,
    /** Both voice logs (the voice process's and the main app's), newest first. */
    val voiceLog: List<VoiceLogEntry>,
    val gpuFailure: GpuFailure?,
    /** 0.7: Settings › Memory's and Settings › Messages' switches (their files hold no item and no message). */
    val memory: MemorySettings = MemorySettings(),
    val messages: MessagesFile = MessagesFile(),
)

object FeedbackSources {
    /** settings.json (written by the main app, sys/SettingsStore). */
    const val SETTINGS = "settings.json"

    /** The eight files a report's diagnostics come from, each missing or damaged one read as its defaults. */
    fun read(robotDir: File): FeedbackInputs = FeedbackInputs(
        settings = RobotSettings.fromJson(readOrNull(File(robotDir, SETTINGS))),
        look = LookSettings.fromJson(readOrNull(File(robotDir, LookSettings.FILE))),
        updates = UpdateSettings.fromJson(readOrNull(File(robotDir, UpdateSettings.FILE))),
        updateState = UpdateState.fromJson(readOrNull(File(robotDir, UpdateState.FILE))),
        voiceLog = (VoiceLogCodec.decode(readOrNull(File(robotDir, VoiceFiles.LOG))) + VoiceLogCodec.decode(readOrNull(File(robotDir, VoiceFiles.LOG_MAIN))))
            .sortedByDescending { it.time },
        gpuFailure = GpuFailure.decode(readOrNull(File(robotDir, GpuFailure.FILE))),
        memory = MemorySettings.fromJson(readOrNull(File(robotDir, MemorySettings.FILE))),
        messages = MessagesFile.fromJson(readOrNull(File(robotDir, MessagesFile.FILE))),
    )
}

/**
 * Send feedback's own small file, files/robot/[FILE] (not settings.json, which stays schema 13): the number "Text it"
 * opens your SMS app with ([sendTo], from the page's menu), or none. Written by the main app only.
 */
data class FeedbackPrefs(val sendTo: String? = null) {
    fun toJson(): String = JSONObject().put("v", VERSION).apply { sendTo?.let { put("sendTo", it) } }.toString()

    companion object {
        const val FILE = "feedback.json"
        const val VERSION = 1

        fun fromJson(text: String?): FeedbackPrefs {
            val o = try { JSONObject(text ?: return FeedbackPrefs()) } catch (_: Exception) { return FeedbackPrefs() }
            return FeedbackPrefs(sendTo = (o.opt("sendTo") as? String)?.let(SendTo::normalize))
        }
    }
}

/** The number feedback is texted to: digits with an optional "+", 3 to 15 of them, as typed with spaces, dashes or brackets. */
object SendTo {
    fun valid(s: String): Boolean = Addresses.isPhone(s)

    /** "+1 (514) 555-0123" → "+15145550123"; null when it isn't a number. */
    fun normalize(s: String): String? {
        if (!valid(s)) return null
        val t = PhoneNumbers.normalize(s).trim()
        return (if (t.startsWith("+")) "+" else "") + PhoneNumbers.digits(t)
    }
}

/**
 * What you said after a feedback phrase in the panel ("I have an idea: read my calendar in the brief"), handed from the
 * voice process to Send feedback in a file of Robot's own (files/robot/[FILE]), never in the intent another app could
 * send too; the page takes it once, within [FRESH_MS], and deletes it.
 */
data class FeedbackPrefill(val kind: FeedbackKind, val text: String, val at: Long) {
    fun toJson(): String = JSONObject().put("v", 1).put("kind", kind.name).put("text", text).put("at", at).toString()

    fun fresh(now: Long): Boolean = now - at in 0..FRESH_MS

    companion object {
        const val FILE = "feedback_prefill.json"
        const val FRESH_MS = 120_000L
        const val MAX_TEXT = 2_000

        fun fromJson(text: String?): FeedbackPrefill? {
            val o = try { JSONObject(text ?: return null) } catch (_: Exception) { return null }
            val kind = FeedbackKind.of(o.optString("kind")) ?: return null
            val t = (o.opt("text") as? String)?.take(MAX_TEXT) ?: return null
            val at = o.optLong("at", 0).takeIf { it > 0 } ?: return null
            return FeedbackPrefill(kind, t, at)
        }
    }
}
