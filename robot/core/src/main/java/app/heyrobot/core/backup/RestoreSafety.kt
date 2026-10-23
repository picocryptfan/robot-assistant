// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.Approval
import app.heyrobot.core.BriefLock
import app.heyrobot.core.Escalation
import app.heyrobot.core.LookSettings
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarLockUse
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.messages.MessagesLocked
import app.heyrobot.core.messages.ReplyRoute
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.ReadAloud
import app.heyrobot.core.termux.ApiCommands
import app.heyrobot.core.termux.CommandLevel
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.SpokenYes
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebSettings
import java.util.Locale
import kotlin.random.Random

// Pure. The settings a restore keeps at the safer value and the trust it never restores (§7.6). A file
// can be made by someone who tells you its passphrase, and is then perfectly valid crypto; so unless you tick "This
// backup is my own, from my own phone" ([mine]), every setting that lets Robot act with fewer questions, or sends your
// words to a server the file chose, keeps this phone's value where the file's is looser, and the preview lists each one
// ([SaferField], worded by the app: "Kept safer: 'Just do it' for texting, …"). With the tick, they come back as saved.
//
// Either way: the apps Robot trusts (Messages' signer pins, the trusted Termux) are confirmed again on this phone, and
// what belongs to this phone (its calendar, its voiceprint, the model terms you confirmed, the search model downloaded
// here) stays this phone's (RestoreRefs). [RULES] classifies every field of every settings type; RestoreSafetyCoverageTest
// fails when a field is in none, so a setting added in 1.2 can't slip through unclassified.

/** How a restore treats one field. */
enum class FieldRule {
    /** Restored as the file has it. */
    AS_IS,
    /** This phone's value when the file's is looser and the backup isn't marked yours ([SaferField]). */
    GUARDED,
    /** Always this phone's value: it belongs to this phone (RestoreRefs.phoneSpecific). */
    PHONE,
    /** Trust Robot gives an app or a choice on this phone: never restored (§7.6). */
    TRUST,
    /** A value of another settings type, classified on its own. */
    NESTED,
}

/** One guarded setting the restore kept at this phone's safer value (the preview words each one). */
enum class SaferField(val file: String, val field: String) {
    APPROVALS("settings.json", "approvals"),
    LOCKED_TOOLS("settings.json", "lockedTools"),
    SAFETY_CARDS("settings.json", "safetyCards"),
    CLAUDE_ACTION_CARDS("settings.json", "helperActionCards"),
    WHO_CARDS("settings.json", "whoCards"),
    LEARNED_CARDS("settings.json", "learnedCards"),
    DELETE_CARDS("settings.json", "deleteCards"),
    PERSONAL_TO_HELPERS("settings.json", "personalToHelpers"),
    VOICE_YES_PERSONAL("settings.json", "voiceYesPersonal"),
    VOICE_CARDS("settings.json", "voiceCards"),
    LEARN_FROM_CHEOGRAM("settings.json", "learnFromCheogram"),
    HELP("settings.json", "help"),
    HELPER_BUDGET("settings.json", "helperBudgetCents"),
    HELPERS("settings.json", "helpers"),
    NEAREST_ONLINE("settings.json", "nearestOnline"),
    NEAREST_SERVER("settings.json", "nearestServer"),
    SMS_GATEWAY("settings.json", "smsGateway"),
    MEMORY_LOCK_SCREEN("memory.json", "onLockScreen"),
    MEMORY_SPEAK_SECRETS("memory.json", "speakSecrets"),
    MEMORY_HELPERS("memory.json", "helpers"),
    MEMORY_FROM_CONTENT("memory.json", "fromContentNoCard"),
    MESSAGES_REPLY_ROUTE("messages.json", "replyRoute"),
    MESSAGES_REPLY_CARD("messages.json", "replyCard"),
    MESSAGES_VOICE_YES("messages.json", "voiceYesSends"),
    MESSAGES_LOCKED("messages.json", "locked"),
    MESSAGES_HELPERS("messages.json", "helpersNoCard"),
    MESSAGES_NO_CARD("messages.json", "noCard"),
    MESSAGES_ANNOUNCE("messages.json", "announce"),
    TERMUX_SCRIPTS("termux.json", "scripts"),
    TERMUX_COMMANDS("termux.json", "commands"),
    TERMUX_READ_ONLY("termux.json", "readOnlyWithoutCard"),
    TERMUX_SPOKEN_YES("termux.json", "spokenYes"),
    TERMUX_HIDE_SECRETS("termux.json", "hideSecrets"),
    TERMUX_PROFILE("termux.json", "loadProfile"),
    TERMUX_API("termux.json", "api"),
    TERMUX_HELPERS("termux.json", "helpers"),
    TERMUX_LOCK_SCREEN("termux.json", "lockScreenScripts"),
    WEB_ASK("web.json", "ask"),
    WEB_HELPERS("web.json", "helpersMayUse"),
    WEB_LOCK_SCREEN("web.json", "lockScreen"),
    WEB_SEARXNG("web.json", "searxng"),
    WEB_PUBLIC_SEARXNG("web.json", "publicSearxng"),
    WEB_DAILY("web.json", "dailySearches"),
    UPDATES_REPO("updates.json", "repo"),
    UPDATES_TEST_BUILDS("updates.json", "testBuilds"),
    UPDATES_INSTALL("updates.json", "installWithoutAsking"),
    FEEDBACK_SEND_TO("feedback.json", "sendTo"),
    HEADSET_ANNOUNCE_LOCKED("headset.json", "announceLocked"),
    HANDSFREE_PRIVATE("headset.json", "personalOnlyPrivate"),
    HANDSFREE_REPLY("headset.json", "replyAfterSpokenYes"),
    HANDSFREE_CALL("headset.json", "callAfterSpokenYes"),
    CAR_LOCK_USE("car.json", "lockUse"),
    CAR_CALLS("car.json", "calls"),
    CAR_READ_ALOUD("car.json", "readAloud"),
    CAR_TRUSTED_HOURS("car.json", "trustedHours"),
    ROUTINES_LOCKED("routine_settings.json", "runLocked"),
    ROUTINES_ONLINE("routine_settings.json", "online"),
    BRIEF_ON("routine_settings.json", "briefEnabled"),
    BRIEF_LOCK("routine_settings.json", "briefLock"),
    /** "Start from the lock screen" for a conversation: on is the looser side. */
    CONV_START_LOCKED("settings.json", "convStartLocked"),
    /** The version "Skip this version" skips: one this phone doesn't skip is looser (you'd never hear of it). */
    UPDATES_SKIPPED("updates.json", "skipped"),
    /** 1.1, Skills phase B: "Let Robot's model pick skills"; on is the looser side. */
    SKILLS_MODEL_PICKS("skill_settings.json", "modelPicks"),
    /**
     * FX3: Robot Files on (on is the looser side: files you share are kept, read and filed again), reading text in images
     * (on reads more of each picture into the index), and its lines in the morning brief (on says bills and dates aloud,
     * as BRIEF_ON is guarded for the brief itself). Off on this phone stays off unless the backup is yours.
     */
    FILES_ON("files_settings.json", "on"),
    FILES_READ_IMAGE_TEXT("files_settings.json", "readImageText"),
    FILES_BRIEF("files_settings.json", "brief"),
    /** 1.4, Settings › Files: each kind's filing level (File it on its own is the loosest, then Ask me first, then Off). */
    FILES_RECEIPTS("files_settings.json", "receipts"),
    FILES_BILLS("files_settings.json", "bills"),
    FILES_DOCUMENTS("files_settings.json", "documents"),
    FILES_PHOTOS("files_settings.json", "photos"),
    FILES_OTHER("files_settings.json", "other"),
    /** Reminders from files: Add to my brief on its own is the loosest, then Ask me first, then Off. */
    FILES_REMINDERS("files_settings.json", "reminders"),
    /** "Strip location from photos I send": off is the looser side. */
    FILES_STRIP_LOCATION("files_settings.json", "stripLocation"),
    /** F12: travel mode, on is the safer side; kept on even from a backup marked yours (the tick isn't the phone's PIN). */
    FILES_TRAVEL("files_settings.json", "travel"),
    /** F12: hidden drawers: a restore only ever adds to them, even from a backup marked yours. */
    FILES_HIDDEN("files_settings.json", "hidden"),
}

/** A file's value after the restore's rules, with the guarded settings kept at this phone's value. */
data class Guarded<T>(val value: T, val kept: List<SaferField>)

object RestoreSafety {
    private fun rules(vararg pairs: Pair<FieldRule, List<String>>): Map<String, FieldRule> =
        pairs.flatMap { (r, names) -> names.map { it to r } }.toMap()

    private val A = FieldRule.AS_IS
    private val G = FieldRule.GUARDED
    private val P = FieldRule.PHONE
    private val T = FieldRule.TRUST
    private val N = FieldRule.NESTED

    /** Every field of every settings type a backup carries, and its rule. */
    val RULES: Map<Class<*>, Map<String, FieldRule>> = mapOf(
        RobotSettings::class.java to rules(
            A to listOf("wakeWord", "wakeOnlyWhileCharging", "speakReplies", "speechRate", "voice", "livePartials", "listenPause", "directAnswers",
                "keepModelMinutes", "saveHistory", "textApp", "phoneRegion", "emailApp", "eventMinutes", "rememberPicks", "wakePhrases",
                "bargeIn", "interrupt", "playback", "stopKey", "helpDefault", "helperShowCost", "weatherHome", "convPair", "convTranslate",
                "convSpeak", "convPartials", "convMaxSpeakers", "convSpeakers", "convWakeDuring", "convKeepAudio",
                "convSummaryLang", "convAutoOfferHelper", "keepMedia", "brainModel", "modelThinks", "modelGpu", "replyLang"),
            G to listOf("approvals", "lockedTools", "safetyCards", "helperActionCards", "whoCards", "learnedCards", "deleteCards",
                "personalToHelpers", "voiceYesPersonal", "voiceCards", "learnFromCheogram", "help", "helperBudgetCents", "nearestOnline",
                "nearestServer", "smsGateway", "convStartLocked"),
            N to listOf("helpers"),
            // nearestOnlineAt: when Settings last chose "Find the nearest place online" here, which the card's answers kept on
            // this phone (never in a backup) are measured against ([RestoreSafety.settings]).
            P to listOf("eventCalendar", "meVoiceprint", "convReminderAt", "safetyNotice", "confirmedTerms", "menusNoticeSeen", "nearestOnlineAt"),
        ),
        // A helper this phone has keeps where it sends your questions and its key (its company, address and the model's
        // maker) unless the backup is yours: a change to any of them keeps the whole helper as it is here ([helper]).
        // One this phone doesn't have arrives under a new id (RestoreTaint.newHelper), so the id is the file's only then.
        HelperConfig::class.java to rules(
            A to listOf("id", "name", "route", "effort", "search", "actions", "voice", "prices", "pricesFor", "aliases"),
            G to listOf("enabled", "trust", "locked", "baseUrl", "budgetCents", "preset", "model", "zdr"),
        ),
        LookSettings::class.java to rules(A to listOf("wallpaperColors")),
        // 1.1, Phone lock (§12): its own switch is this phone's; the rows come back (the locks arrive off: [phoneLock]).
        app.heyrobot.core.lock.LockSettings::class.java to rules(P to listOf("on"), A to listOf("forWho", "endEarly", "waitMin", "warnMin", "showLeft")),
        MemorySettings::class.java to rules(
            A to listOf("on", "useInAnswers", "suggestions", "transcripts"),
            G to listOf("onLockScreen", "speakSecrets", "helpers", "fromContentNoCard"),
            P to listOf("searchModel"),
        ),
        MessagesFile::class.java to rules(
            N to listOf("settings"),
            G to listOf("noCard"),
            A to listOf("replyLang", "announcePeople"),
            T to listOf("pins", "firstUse"),
        ),
        MessageSettings::class.java to rules(
            T to listOf("on"),
            A to listOf("allowlist", "keep", "afterReading", "translate", "offerTheirLanguage"),
            G to listOf("replyRoute", "replyCard", "voiceYesSends", "locked", "helpersNoCard", "announce"),
        ),
        WebSettings::class.java to rules(
            A to listOf("on", "service", "encyclopedia", "books", "readPages", "readThrough", "pages", "safeSearch", "addTown",
                "currentInfo", "keepSources", "poweredByBrave", "browserEngine"),
            G to listOf("ask", "helpersMayUse", "lockScreen", "searxng", "publicSearxng", "dailySearches"),
            // When Settings last chose "Ask before searching" here: the card's answers on this phone are measured against it.
            P to listOf("askAt"),
        ),
        TermuxSettings::class.java to rules(
            A to listOf("limitSeconds", "outputLines", "outputChars", "keepInHistory", "updateCheck"),
            G to listOf("scripts", "commands", "readOnlyWithoutCard", "spokenYes", "hideSecrets", "loadProfile", "api", "helpers", "lockScreenScripts"),
            T to listOf("enabled", "trustedSigner", "trustedVersionCode", "trustedUpdatedAt", "trustedVersion", "testKeyRisk"),
        ),
        UpdateSettings::class.java to rules(
            A to listOf("autoCheck", "wifiDownload"),
            G to listOf("testBuilds", "installWithoutAsking", "repo", "skipped"),
        ),
        FeedbackPrefs::class.java to rules(G to listOf("sendTo")),
        HeadsetFile::class.java to rules(N to listOf("settings", "safety"), A to listOf("noticeSeen")),
        HeadsetSettings::class.java to rules(
            A to listOf("buttonOpensRobot", "listenWith", "fallbackToPhoneMic", "speakInHeadset", "followUpSeconds", "stopOnDisconnect",
                "wakeThroughHeadset", "announceMessages", "announceFrom", "announceRead", "answerVoiceButton", "buttonsWhileTalking"),
            G to listOf("announceLocked"),
        ),
        HandsFreeSafety::class.java to rules(G to listOf("personalOnlyPrivate", "replyAfterSpokenYes", "callAfterSpokenYes")),
        CarFile::class.java to rules(A to listOf("on"), N to listOf("settings")),
        CarSettings::class.java to rules(
            A to listOf("startOnConnect", "endGraceSeconds", "speakAsCall", "wakeListenWith", "announce", "announceFrom",
                "voiceReplies", "dndWhileDriving", "androidCarMode", "keepScreenOn", "home", "work", "driveBrief"),
            G to listOf("lockUse", "calls", "readAloud", "trustedHours"),
        ),
        RoutineSettings::class.java to rules(
            A to listOf("briefItems", "finishOnUnlock", "briefLang", "briefPrepare", "clockSilenceMinutes", "alarmWindow", "briefTrigger",
                "briefOutput", "briefOnline"),
            G to listOf("runLocked", "online", "briefEnabled", "briefLock"),
        ),
        app.heyrobot.core.skills.SkillSettings::class.java to rules(G to listOf("modelPicks")),
        // 1.4, Settings › Files (files_settings.json, the FILES category): clean-up suggestions (a list for your tap, never a
        // delete) as the file has them; the filing levels, reminders on their own and location stripping kept safer. FX3
        // (files-1.4-review.md, FX2's note): Robot Files on, reading text in images and the brief's lines are kept safer too,
        // so someone else's backup can't turn back on what you turned off here. F12: travel mode and its hidden drawers are
        // guarded even from a backup marked yours (fewer hidden drawers, or travel mode off, is looser).
        app.heyrobot.core.files.FilesSettings::class.java to rules(
            A to listOf("cleanup"),
            G to listOf("on", "readImageText", "brief", "receipts", "bills", "documents", "photos", "other", "reminders", "stripLocation", "travel", "hidden"),
            // FX1: never in a file (a backup's is always false): this phone's damaged file's "every drawer hidden" stays.
            P to listOf("hiddenUnknown"),
        ),
    )

    // Looser to stricter, as each setting's own docs describe them: a higher index is looser.
    private val APPROVAL = listOf(Approval.OFF, Approval.ASK, Approval.AUTO)
    private val CARDS = listOf(SafetyCards.AFTER_READING, SafetyCards.UNASKED, SafetyCards.UNSURE_ONLY)
    private val NEAREST = listOf(NearestOnline.NEVER, NearestOnline.ASK, NearestOnline.ALWAYS)
    private val MSG_LOCKED = listOf(MessagesLocked.OFF, MessagesLocked.WHO_MESSAGED, MessagesLocked.READ_AND_REPLY)
    private val REPLY = listOf(ReplyRoute.OPEN_CHAT, ReplyRoute.SEND_AFTER_CARD)
    private val SCRIPT = listOf(ScriptLevel.OFF, ScriptLevel.ASK, ScriptLevel.AUTO)
    private val COMMAND = listOf(CommandLevel.OFF, CommandLevel.ASK)
    private val SPOKEN = listOf(SpokenYes.NEVER, SpokenYes.WITHOUT_WARNINGS)
    private val API = listOf(ApiCommands.NOT_ALLOWED, ApiCommands.WITH_CARD)
    private val WEB_ASK = listOf(WebAsk.ALWAYS, WebAsk.FIRST_TIME, WebAsk.NEVER)
    private val MEM_HELPERS = listOf(HelperMemory.ONLY_WHEN_INCLUDED, HelperMemory.TRUSTED_HELPERS)
    private val BRIEF_LOCK = listOf(BriefLock.NOTHING, BriefLock.TIME_WEATHER, BriefLock.EVERYTHING)
    private val CAR_LOCK = listOf(CarLockUse.NEVER, CarLockUse.AFTER_ONE_UNLOCK, CarLockUse.ALWAYS)
    private val CALLS = listOf(CallSetting.OPEN_DIALER, CallSetting.CALL_AFTER_YES)
    private val READ_ALOUD = listOf(ReadAloud.NEVER, ReadAloud.ASK, ReadAloud.ALWAYS)
    private val FILE_LEVEL = listOf(app.heyrobot.core.files.FileLevel.OFF, app.heyrobot.core.files.FileLevel.ASK, app.heyrobot.core.files.FileLevel.AUTO)
    private val FILE_REMINDERS = listOf(app.heyrobot.core.files.FileReminders.OFF, app.heyrobot.core.files.FileReminders.ASK, app.heyrobot.core.files.FileReminders.BRIEF)
    private val DEFAULT_SETTINGS = RobotSettings()

    /** "When Robot needs help": Off, Ask me first, Automatic; unset is Automatic once a key is saved, so as loose. */
    private fun helpRank(e: Escalation?): Int = when (e) {
        Escalation.OFF -> 0
        Escalation.ASK -> 1
        Escalation.AUTO, null -> 2
    }

    /** Keeps this phone's value of each guarded field the file loosens (unless [mine]), and remembers which. */
    private class Keep(val mine: Boolean) {
        val kept = LinkedHashSet<SaferField>()

        fun <T> pick(phone: T, file: T, looser: Boolean, f: SaferField): T = if (!mine && looser) { kept += f; phone } else file

        /** A switch whose on is the looser side ([onLooser]) or its off. */
        fun flag(phone: Boolean, file: Boolean, f: SaferField, onLooser: Boolean = true): Boolean =
            pick(phone, file, if (onLooser) file && !phone else !file && phone, f)

        fun <E> rank(phone: E, file: E, order: List<E>, f: SaferField): E = pick(phone, file, order.indexOf(file) > order.indexOf(phone), f)

        /** Where Robot sends something: a place the file chose (not this phone's, not Robot's own [default]) is looser. */
        fun <T> place(phone: T, file: T, default: T, f: SaferField): T = pick(phone, file, file != phone && file != default, f)

        /** A set of allowances: anything beyond this phone's is looser. */
        fun <T> set(phone: Set<T>, file: Set<T>, f: SaferField): Set<T> = pick(phone, file, !phone.containsAll(file), f)

        /** A number whose higher value is looser (a budget, a cap, a duration). */
        fun most(phone: Int, file: Int, f: SaferField): Int = pick(phone, file, file > phone, f)

        fun <T> done(v: T) = Guarded(v, kept.toList())
    }

    /**
     * settings.json. [file]'s helpers: one this phone also has keeps this phone's switches (on, trusted, on the lock screen,
     * budget, providers that keep nothing) where the file's are looser, and stays exactly as it is here when the file
     * would send it to another company, address or model maker; one this phone doesn't have arrives off, untrusted and
     * not on the lock screen, under a new id, unless [mine] (RestoreTaint). Phone-specific fields are this phone's
     * (RestoreRefs). [random]: picks the new ids.
     */
    fun settings(phone: RobotSettings, file: RobotSettings, mine: Boolean, random: (Int) -> Int = { Random.nextInt(it) }): Guarded<RobotSettings> {
        val k = Keep(mine)
        // Keys are kept by id, and one can outlive its helper here (a restore that left it out): a helper new to this
        // phone gets an id no helper here or in the file has, nor Claude's, whose key files are its own.
        val taken = HashSet<String>().apply { phone.helpers.mapTo(this) { it.id }; file.helpers.mapTo(this) { it.id }; add(HelperConfig.CLAUDE_ID) }
        val moved = HashMap<String, String>()
        val approvals = ToolName.entries.associateWith { t -> k.rank(phone.approval(t), file.approval(t), APPROVAL, SaferField.APPROVALS) }
        val s = file.copy(
            approvals = approvals,
            lockedTools = k.set(phone.lockedTools, file.lockedTools, SaferField.LOCKED_TOOLS),
            safetyCards = k.rank(phone.safetyCards, file.safetyCards, CARDS, SaferField.SAFETY_CARDS),
            helperActionCards = k.flag(phone.helperActionCards, file.helperActionCards, SaferField.CLAUDE_ACTION_CARDS, onLooser = false),
            whoCards = k.flag(phone.whoCards, file.whoCards, SaferField.WHO_CARDS, onLooser = false),
            learnedCards = k.flag(phone.learnedCards, file.learnedCards, SaferField.LEARNED_CARDS, onLooser = false),
            deleteCards = k.flag(phone.deleteCards, file.deleteCards, SaferField.DELETE_CARDS, onLooser = false),
            personalToHelpers = k.flag(phone.personalToHelpers, file.personalToHelpers, SaferField.PERSONAL_TO_HELPERS),
            voiceYesPersonal = k.flag(phone.voiceYesPersonal, file.voiceYesPersonal, SaferField.VOICE_YES_PERSONAL),
            voiceCards = k.flag(phone.voiceCards, file.voiceCards, SaferField.VOICE_CARDS),
            learnFromCheogram = k.flag(phone.learnFromCheogram, file.learnFromCheogram, SaferField.LEARN_FROM_CHEOGRAM),
            help = k.pick(phone.help, file.help, helpRank(file.help) > helpRank(phone.help), SaferField.HELP),
            helperBudgetCents = k.most(phone.helperBudgetCents, file.helperBudgetCents, SaferField.HELPER_BUDGET),
            nearestOnline = k.rank(phone.nearestOnline, file.nearestOnline, NEAREST, SaferField.NEAREST_ONLINE),
            nearestServer = k.place(phone.nearestServer, file.nearestServer, DEFAULT_SETTINGS.nearestServer, SaferField.NEAREST_SERVER),
            smsGateway = k.place(phone.smsGateway, file.smsGateway, DEFAULT_SETTINGS.smsGateway, SaferField.SMS_GATEWAY),
            convStartLocked = k.flag(phone.convStartLocked, file.convStartLocked, SaferField.CONV_START_LOCKED),
            // The card's "Allow always" or Never stays on this phone and counts while newer than Settings' last choice here:
            // the backup's time, older or later, would bring back an answer you changed since, or hide a newer one.
            nearestOnlineAt = phone.nearestOnlineAt,
            helpers = file.helpers.map { h ->
                val here = phone.helper(h.id)
                if (here != null) return@map helper(here, h, k)
                val id = if (mine) h.id else HelperConfig.newId(taken, random).also { taken += it; moved[h.id] = it }
                RestoreTaint.newHelper(h, mine, id).also { if (it.copy(id = h.id) != h) k.kept += SaferField.HELPERS }
            },
            helpDefault = file.helpDefault?.let { moved[it] ?: it },
        )
        return k.done(RestoreRefs.phoneSpecific(phone, s))
    }

    /**
     * Where a helper sends your questions with its key: its company, the address you typed (your server, another company)
     * and the model's maker, which a model's name gives before its '/' ("anthropic/…", "openrouter/auto" through
     * OpenRouter). A change to any of them, from a backup that isn't yours, keeps this phone's helper whole.
     */
    private fun sameService(a: HelperConfig, b: HelperConfig): Boolean =
        a.preset == b.preset && a.baseUrl == b.baseUrl && maker(a.model) == maker(b.model)

    private fun maker(model: String): String = model.substringBefore('/', "").trim().lowercase(Locale.ROOT)

    private fun helper(phone: HelperConfig, file: HelperConfig, k: Keep): HelperConfig {
        if (!k.mine && !sameService(phone, file)) {
            k.kept += SaferField.HELPERS
            return phone
        }
        val h = Keep(k.mine)
        val out = file.copy(
            enabled = h.pick(phone.enabled, file.enabled, file.on && !phone.on, SaferField.HELPERS),
            trust = h.flag(phone.trust, file.trust, SaferField.HELPERS),
            locked = h.flag(phone.locked, file.locked, SaferField.HELPERS),
            baseUrl = h.pick(phone.baseUrl, file.baseUrl, file.baseUrl != phone.baseUrl, SaferField.HELPERS),
            budgetCents = h.pick(phone.budgetCents, file.budgetCents, (file.budgetCents ?: Int.MAX_VALUE) > (phone.budgetCents ?: Int.MAX_VALUE), SaferField.HELPERS),
            // OpenRouter: only providers that keep nothing. Off sends your questions to any of them.
            zdr = h.flag(phone.zdr, file.zdr, SaferField.HELPERS, onLooser = false),
        )
        k.kept += h.kept
        return out
    }

    fun memory(phone: MemorySettings, file: MemorySettings, mine: Boolean): Guarded<MemorySettings> {
        val k = Keep(mine)
        return k.done(file.copy(
            onLockScreen = k.flag(phone.onLockScreen, file.onLockScreen, SaferField.MEMORY_LOCK_SCREEN),
            speakSecrets = k.flag(phone.speakSecrets, file.speakSecrets, SaferField.MEMORY_SPEAK_SECRETS),
            helpers = k.rank(phone.helpers, file.helpers, MEM_HELPERS, SaferField.MEMORY_HELPERS),
            fromContentNoCard = k.flag(phone.fromContentNoCard, file.fromContentNoCard, SaferField.MEMORY_FROM_CONTENT),
            // The search model's offer is this phone's: a model downloaded on the old phone isn't here.
            searchModel = phone.searchModel,
        ))
    }

    /**
     * messages.json, its People ids already remapped (RestoreRefs). Trust is never restored (§7.6): the signer pins and
     * the first-use list are dropped and "Read my messages" arrives off with your apps still ticked, so turning it on
     * pins the apps installed on this phone (MessagesFile.withApp).
     */
    fun messages(phone: MessagesFile, file: MessagesFile, mine: Boolean): Guarded<MessagesFile> {
        val k = Keep(mine)
        val p = phone.settings
        val f = file.settings
        val settings = f.copy(
            on = false,
            replyRoute = k.rank(p.replyRoute, f.replyRoute, REPLY, SaferField.MESSAGES_REPLY_ROUTE),
            replyCard = k.flag(p.replyCard, f.replyCard, SaferField.MESSAGES_REPLY_CARD, onLooser = false),
            voiceYesSends = k.flag(p.voiceYesSends, f.voiceYesSends, SaferField.MESSAGES_VOICE_YES),
            locked = k.rank(p.locked, f.locked, MSG_LOCKED, SaferField.MESSAGES_LOCKED),
            helpersNoCard = k.flag(p.helpersNoCard, f.helpersNoCard, SaferField.MESSAGES_HELPERS),
            announce = k.flag(p.announce, f.announce, SaferField.MESSAGES_ANNOUNCE),
        )
        return k.done(file.copy(
            settings = settings,
            noCard = k.set(phone.noCard, file.noCard, SaferField.MESSAGES_NO_CARD),
            pins = emptyMap(),
            firstUse = emptySet(),
        ))
    }

    /** termux.json: never trusted (TermuxSettings.untrust: off until Settings › Termux's checklist is green and you tap Trust). */
    fun termux(phone: TermuxSettings, file: TermuxSettings, mine: Boolean): Guarded<TermuxSettings> {
        val k = Keep(mine)
        // A script at Just do it where this phone's isn't: this phone's level (Ask first when it has none).
        val scripts = file.scripts.mapValues { (name, level) ->
            k.pick(phone.script(name), level, SCRIPT.indexOf(level) > SCRIPT.indexOf(phone.script(name)), SaferField.TERMUX_SCRIPTS)
        }
        return k.done(file.copy(
            scripts = scripts,
            commands = k.rank(phone.commands, file.commands, COMMAND, SaferField.TERMUX_COMMANDS),
            readOnlyWithoutCard = k.flag(phone.readOnlyWithoutCard, file.readOnlyWithoutCard, SaferField.TERMUX_READ_ONLY),
            spokenYes = k.rank(phone.spokenYes, file.spokenYes, SPOKEN, SaferField.TERMUX_SPOKEN_YES),
            hideSecrets = k.flag(phone.hideSecrets, file.hideSecrets, SaferField.TERMUX_HIDE_SECRETS, onLooser = false),
            loadProfile = k.flag(phone.loadProfile, file.loadProfile, SaferField.TERMUX_PROFILE),
            api = k.rank(phone.api, file.api, API, SaferField.TERMUX_API),
            helpers = k.flag(phone.helpers, file.helpers, SaferField.TERMUX_HELPERS),
            lockScreenScripts = k.set(phone.lockScreenScripts, file.lockScreenScripts, SaferField.TERMUX_LOCK_SCREEN),
        ).untrust())
    }

    fun web(phone: WebSettings, file: WebSettings, mine: Boolean): Guarded<WebSettings> {
        val k = Keep(mine)
        return k.done(file.copy(
            ask = k.rank(phone.ask, file.ask, WEB_ASK, SaferField.WEB_ASK),
            // As nearestOnlineAt: the card's answers on this phone are measured against this phone's time.
            askAt = phone.askAt,
            helpersMayUse = k.flag(phone.helpersMayUse, file.helpersMayUse, SaferField.WEB_HELPERS),
            lockScreen = k.flag(phone.lockScreen, file.lockScreen, SaferField.WEB_LOCK_SCREEN),
            searxng = k.place(phone.searxng, file.searxng, null, SaferField.WEB_SEARXNG),
            publicSearxng = k.place(phone.publicSearxng, file.publicSearxng, null, SaferField.WEB_PUBLIC_SEARXNG),
            dailySearches = k.most(phone.dailySearches, file.dailySearches, SaferField.WEB_DAILY),
        ))
    }

    fun updates(phone: UpdateSettings, file: UpdateSettings, mine: Boolean): Guarded<UpdateSettings> {
        val k = Keep(mine)
        return k.done(file.copy(
            repo = k.place(phone.repo, file.repo, null, SaferField.UPDATES_REPO),
            testBuilds = k.flag(phone.testBuilds, file.testBuilds, SaferField.UPDATES_TEST_BUILDS),
            installWithoutAsking = k.flag(phone.installWithoutAsking, file.installWithoutAsking, SaferField.UPDATES_INSTALL),
            // Automatic checks stay as the file has them (off is the stricter side). A version the file skips and this phone
            // doesn't: this phone's, so you still hear of it.
            skipped = k.place(phone.skipped, file.skipped, null, SaferField.UPDATES_SKIPPED),
        ))
    }

    fun feedback(phone: FeedbackPrefs, file: FeedbackPrefs, mine: Boolean): Guarded<FeedbackPrefs> {
        val k = Keep(mine)
        return k.done(file.copy(sendTo = k.place(phone.sendTo, file.sendTo, null, SaferField.FEEDBACK_SEND_TO)))
    }

    fun look(@Suppress("UNUSED_PARAMETER") phone: LookSettings, file: LookSettings): Guarded<LookSettings> = Guarded(file, emptyList())

    fun headset(phone: HeadsetFile, file: HeadsetFile, mine: Boolean): Guarded<HeadsetFile> {
        val k = Keep(mine)
        return k.done(file.copy(
            settings = file.settings.copy(announceLocked = k.flag(phone.settings.announceLocked, file.settings.announceLocked, SaferField.HEADSET_ANNOUNCE_LOCKED)),
            safety = HandsFreeSafety(
                personalOnlyPrivate = k.flag(phone.safety.personalOnlyPrivate, file.safety.personalOnlyPrivate, SaferField.HANDSFREE_PRIVATE, onLooser = false),
                replyAfterSpokenYes = k.flag(phone.safety.replyAfterSpokenYes, file.safety.replyAfterSpokenYes, SaferField.HANDSFREE_REPLY),
                callAfterSpokenYes = k.flag(phone.safety.callAfterSpokenYes, file.safety.callAfterSpokenYes, SaferField.HANDSFREE_CALL),
            ),
        ))
    }

    fun car(phone: CarFile, file: CarFile, mine: Boolean): Guarded<CarFile> {
        val k = Keep(mine)
        val p = phone.settings
        val f = file.settings
        return k.done(file.copy(settings = f.copy(
            lockUse = k.rank(p.lockUse, f.lockUse, CAR_LOCK, SaferField.CAR_LOCK_USE),
            calls = k.rank(p.calls, f.calls, CALLS, SaferField.CAR_CALLS),
            readAloud = k.rank(p.readAloud, f.readAloud, READ_ALOUD, SaferField.CAR_READ_ALOUD),
            trustedHours = k.most(p.trustedHours, f.trustedHours, SaferField.CAR_TRUSTED_HOURS),
        )))
    }

    /**
     * 1.1, Phone lock (docs/plans/phone-lock-design.md §12): what a restore writes to locks.json. Phone lock itself keeps
     * this phone's value. Restored locks arrive off; with "This backup is my own" ([mine]), locks for yourself come back as
     * you left them. Locks for someone else always arrive off and wait for a new code: without a code on this phone
     * ([phoneCode]) the rows go back to yourself and End early without the code, as removing the code does in Settings.
     * Add to what's here ([add], this phone has locks): this phone's rows stay, and the file's locks join its own (by id,
     * at most ten). The lock code, the state and the record are never in a backup.
     */
    fun phoneLock(phone: LockFile?, file: LockFile, mine: Boolean, phoneCode: Boolean, add: Boolean): LockFile {
        val f = file.settings
        val keepOn = mine && f.forWho == LockFor.ME
        val incoming = file.locks.map { if (keepOn) it else it.copy(on = false) }
        val here = phone ?: LockFile()
        if (add && here.locks.isNotEmpty()) {
            val ids = here.locks.map { it.id }.toSet()
            return here.copy(locks = (here.locks + incoming.filter { it.id !in ids }).take(app.heyrobot.core.lock.LockLimits.MAX_LOCKS))
        }
        val rows = if (phoneCode) f else f.copy(forWho = LockFor.ME, endEarly = if (f.endEarly == EndEarly.CODE) EndEarly.OFF else f.endEarly)
        return LockFile(rows.copy(on = here.settings.on), incoming)
    }

    /** 1.1, Skills phase B: skill_settings.json. The model picking skills is the looser side (it may then show a card for one). */
    fun skillSettings(phone: app.heyrobot.core.skills.SkillSettings, file: app.heyrobot.core.skills.SkillSettings, mine: Boolean): Guarded<app.heyrobot.core.skills.SkillSettings> {
        val k = Keep(mine)
        return k.done(file.copy(modelPicks = k.flag(phone.modelPicks, file.modelPicks, SaferField.SKILLS_MODEL_PICKS)))
    }

    /**
     * 1.4, Settings › Files (files-1.4-plan.md §2.8). Unless [mine]: a filing level looser than this phone's, reminders
     * added to the brief on their own, and location stripping off keep this phone's value; FX3: so do Robot Files, reading
     * text in images and the brief's lines turned on where this phone has them off. The file's keys this Robot
     * doesn't know (a newer Robot's rows) are never taken from someone else's backup: they would skip these rules
     * ([filesSettingsExtra]).
     */
    fun filesSettings(phone: app.heyrobot.core.files.FilesSettings, file: app.heyrobot.core.files.FilesSettings, mine: Boolean): Guarded<app.heyrobot.core.files.FilesSettings> {
        val k = Keep(mine)
        return k.done(file.copy(
            on = k.flag(phone.on, file.on, SaferField.FILES_ON),
            readImageText = k.flag(phone.readImageText, file.readImageText, SaferField.FILES_READ_IMAGE_TEXT),
            brief = k.flag(phone.brief, file.brief, SaferField.FILES_BRIEF),
            receipts = k.rank(phone.receipts, file.receipts, FILE_LEVEL, SaferField.FILES_RECEIPTS),
            bills = k.rank(phone.bills, file.bills, FILE_LEVEL, SaferField.FILES_BILLS),
            documents = k.rank(phone.documents, file.documents, FILE_LEVEL, SaferField.FILES_DOCUMENTS),
            photos = k.rank(phone.photos, file.photos, FILE_LEVEL, SaferField.FILES_PHOTOS),
            other = k.rank(phone.other, file.other, FILE_LEVEL, SaferField.FILES_OTHER),
            reminders = k.rank(phone.reminders, file.reminders, FILE_REMINDERS, SaferField.FILES_REMINDERS),
            stripLocation = k.flag(phone.stripLocation, file.stripLocation, SaferField.FILES_STRIP_LOCATION, onLooser = false),
        )).let { g -> travel(phone, g) }
    }

    /**
     * F12, travel mode (files-1.4-plan.md §2.8, "fewer hidden drawers"): a restore never shows what this phone hides. Neither
     * someone else's backup nor an older one of yours can turn travel mode off or take a drawer out of the hidden ones; both
     * need the phone's PIN on this phone, and "This backup is my own" is a tick anyone holding the phone can tick. So travel
     * mode stays on when it is on here, and the hidden drawers are this phone's and the backup's together, whatever [mine].
     */
    private fun travel(phone: app.heyrobot.core.files.FilesSettings, g: Guarded<app.heyrobot.core.files.FilesSettings>): Guarded<app.heyrobot.core.files.FilesSettings> {
        val file = g.value
        val kept = ArrayList(g.kept)
        // Every drawer this phone hides, then the backup's as room allows.
        val hidden = LinkedHashSet(phone.hidden).apply { for (h in file.hidden) if (size < app.heyrobot.core.files.FilesSettings.MAX_HIDDEN) add(h) }
        if (!file.hidden.containsAll(phone.hidden)) kept += SaferField.FILES_HIDDEN
        val travel = file.travel || phone.travel
        if (travel != file.travel) kept += SaferField.FILES_TRAVEL
        return Guarded(file.copy(travel = travel, hidden = hidden, hiddenUnknown = phone.hiddenUnknown || file.hiddenUnknown), kept)
    }

    /**
     * FX1 (files-1.4-review.md finding 2): files_settings.json at a Files restore's finish, which may come at the next unlock,
     * long after staging. The backup's own file ([staged], as staged) merged by [filesSettings] over this phone's file as it
     * is then ([raw], read under the store's lock), so travel mode turned on or a drawer hidden meanwhile stays. Null:
     * nothing written (a newer Robot's file here, a staged file that can't be read, or travel mode on here with its drawers
     * unknown, which no file can say).
     */
    fun filesSettingsFile(raw: String?, staged: String?, mine: Boolean): String? {
        val file = app.heyrobot.core.files.FilesSettingsCodec.decode(staged ?: return null) as? app.heyrobot.core.files.FilesSettingsFile.Ok ?: return null
        val here = app.heyrobot.core.files.FilesSettingsCodec.decode(raw)
        if (here is app.heyrobot.core.files.FilesSettingsFile.TooNew) return null
        val g = filesSettings(here.settings, file.read, mine).value
        if (g.travel && g.hiddenUnknown) return null
        val extraHere = (here as? app.heyrobot.core.files.FilesSettingsFile.Ok)?.extra.orEmpty()
        return app.heyrobot.core.files.FilesSettingsCodec.encode(g, filesSettingsExtra(extraHere, file.extra, mine))
    }

    /** The unknown keys files_settings.json keeps after a restore: the file's from your own backup, this phone's otherwise. */
    fun filesSettingsExtra(phone: Map<String, Any?>, file: Map<String, Any?>, mine: Boolean): Map<String, Any?> = if (mine) file else phone

    fun routineSettings(phone: RoutineSettings, file: RoutineSettings, mine: Boolean): Guarded<RoutineSettings> {
        val k = Keep(mine)
        return k.done(file.copy(
            runLocked = k.flag(phone.runLocked, file.runLocked, SaferField.ROUTINES_LOCKED),
            online = k.flag(phone.online, file.online, SaferField.ROUTINES_ONLINE),
            briefEnabled = k.flag(phone.briefEnabled, file.briefEnabled, SaferField.BRIEF_ON),
            briefLock = k.rank(phone.briefLock, file.briefLock, BRIEF_LOCK, SaferField.BRIEF_LOCK),
        ))
    }
}
