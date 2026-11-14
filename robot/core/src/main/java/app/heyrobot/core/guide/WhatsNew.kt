// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.guide

// Pure (1.5). Every feature added since 1.1.0, the version (versionName) that brought it, the guide page that has its
// row, and the Settings page its row opens (a SettingsRoutes value; null: the feature is used by voice, so the row
// opens its guide page). The guide shows each as a row on [GuideFeature.step]; What's new lists the ones added since
// the version Robot last launched as. A release that adds a feature adds it here, with [GuideFeature.changelog]: words
// its CHANGELOG section has (WhatsNewTest checks them). The words are the app's (strings_guide.xml).

enum class GuideFeature(val id: String, val since: String, val step: GuideStep, val route: String?, val changelog: String?) {
    // 1.1.0
    FILES("files", "1.1.0", GuideStep.FILES_LISTS, "files", "Robot Files"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    TRAVEL_MODE("travel-mode", "1.1.0", GuideStep.FILES_LISTS, "files", "Travel mode"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    LANGUAGE("language", "1.1.0", GuideStep.VOICE, "language", "Québec French"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    WAIT("wait", "1.1.0", GuideStep.VOICE, "voice", "Wait before answering"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    PHONE_LOCK("phone-lock", "1.1.0", GuideStep.PHONE_LOCK, "phone-lock", "Phone lock"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SKILLS("skills", "1.1.0", GuideStep.SKILLS, "skills", "Skills"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    BACKUP("backup", "1.1.0", GuideStep.UPDATES, "backup", "Back up and restore"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SUPPORT("support", "1.1.0", GuideStep.UPDATES, "about/support", "Support Robot"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.2.0
    LISTS("lists", "1.2.0", GuideStep.FILES_LISTS, "lists", "Lists"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    ATTACH("attach", "1.2.0", GuideStep.FILES_LISTS, null, "Attach a PDF"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    CONNECTIONS("connections", "1.2.0", GuideStep.SKILLS, "skills", "Skills connections"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    ROBOT_TO_ROBOT("robot-to-robot", "1.2.0", GuideStep.LINK, "link", "Robot to Robot"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    LINK_MODES("link-modes", "1.2.0", GuideStep.LINK, "link", "Robot to Robot"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    PUSH_APP("push-app", "1.2.0", GuideStep.LINK, "link", "Push app"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    CAR_SCREEN("car-screen", "1.2.0", GuideStep.HEADSET_CAR, "car", "The car screen, rebuilt"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.2.1
    TRANSCRIBE_BUTTON("transcribe-button", "1.2.1", GuideStep.CONVERSATION, null, "transcribes a conversation"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.2.4
    CAR_SHORTCUT("car-shortcut", "1.2.4", GuideStep.HEADSET_CAR, "car", "A Car mode button on the home screen"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.2.6
    TODAY("today", "1.2.6", GuideStep.YOUR_DAY, "display", "Today"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    CALENDAR_TAB("calendar-tab", "1.2.6", GuideStep.YOUR_DAY, "people-notes-calendar", "A Calendar tab"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    PRIVATE_EVENTS("private-events", "1.2.6", GuideStep.YOUR_DAY, null, "Private events"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    NEWS("news", "1.2.6", GuideStep.YOUR_DAY, "display", "News on Today"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    WIDGETS("widgets", "1.2.6", GuideStep.YOUR_DAY, null, "A Robot today widget"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    WEATHER_LINE("weather-line", "1.2.6", GuideStep.YOUR_DAY, "display", "A weather line on Today"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    LOCK_SUMMARY("lock-summary", "1.2.6", GuideStep.YOUR_DAY, "display", "a quiet summary on the lock screen"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.3.0
    MCP("mcp", "1.3.0", GuideStep.SKILLS, "skills", "MCP servers"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    PLANS("plans", "1.3.0", GuideStep.SKILLS, null, "Plans"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    DRAFTS("drafts", "1.3.0", GuideStep.SKILLS, "skills", "Drafts"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SHARING("sharing", "1.3.0", GuideStep.SKILLS, "skills", "Sharing"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.4.0
    SEALED("sealed", "1.4.0", GuideStep.PEOPLE, null, "sealed on the phone"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    READY_ANSWERS("ready-answers", "1.4.0", GuideStep.MESSAGES, "messages", "Ready answers"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SCAM_TEXTS("scam-texts", "1.4.0", GuideStep.MESSAGES, null, "Scam texts"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    OPEN_APPS("open-apps", "1.4.0", GuideStep.ASKS_FIRST, "actions", "Open apps"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    UNDO("undo", "1.4.0", GuideStep.ASKS_FIRST, null, "Undo and change"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    BETTER_SEARCH("better-search", "1.4.0", GuideStep.MEMORY, "memory", "Better search"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SCREENSHOTS("screenshots", "1.4.0", GuideStep.FILES_LISTS, "screenshots", "Ask about your screenshots"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    LAST_TIME("last-time", "1.4.0", GuideStep.FILES_LISTS, null, "When did I last"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    HOME_SCREEN("home-screen", "1.4.0", GuideStep.YOUR_DAY, "display", "Home screen"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    READ_TO_ME("read-to-me", "1.4.0", GuideStep.YOUR_DAY, null, "Read it to me"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    STEP_BY_STEP("step-by-step", "1.4.0", GuideStep.YOUR_DAY, null, "Step by step"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    MOMENT_REMINDERS("moment-reminders", "1.4.0", GuideStep.ROUTINES, null, "Reminders for a moment"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    STEP_BY_VOICE("step-by-voice", "1.4.0", GuideStep.SKILLS, "skills", "adds a step from your connections by voice"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    CAR_WIDGET("car-widget", "1.4.0", GuideStep.HEADSET_CAR, "car", "an app's widget"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    HEADS_UP("heads-up", "1.4.0", GuideStep.HEADSET_CAR, "headset", "Heads-up before events"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    EARBUDS("earbuds", "1.4.0", GuideStep.HEADSET_CAR, "headset", "Earbud buttons while Robot talks"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    HELPER_SEARCH("helper-search", "1.4.0", GuideStep.HELPERS, "web-search", "Let helpers use Robot's"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    KEPT_QUESTIONS("kept-questions", "1.4.0", GuideStep.WEB_TERMUX, null, "Questions kept for when there's signal"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    SUMMARIZE("summarize", "1.4.0", GuideStep.WEB_TERMUX, "web-search", "Summarize a web page"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    WIKIPEDIA("wikipedia", "1.4.0", GuideStep.WEB_TERMUX, "wikipedia", "Wikipedia on this phone"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    TOR_BRIDGES("tor-bridges", "1.4.0", GuideStep.LINK, "link", "Tor bridges"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    REPORT("report", "1.4.0", GuideStep.UPDATES, null, "Report a problem"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    // 1.4.2
    REPEATING_EVENTS("repeating-events", "1.4.2", GuideStep.PEOPLE, null, null), // l10n: not text (an id, a version, a route, CHANGELOG words)
    NOTES_SAVED("notes-saved", "1.4.2", GuideStep.PEOPLE, null, "is saved on the phone in your words"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    NOTE_OF("note-of", "1.4.2", GuideStep.PEOPLE, null, "saves only what follows"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    NOTE_QUESTION("note-question", "1.4.2", GuideStep.PEOPLE, null, "the note keeps the question with its answer"), // l10n: not text (an id, a version, a route, CHANGELOG words)
    FLASHLIGHT_WORDS("flashlight-words", "1.4.2", GuideStep.ASKS_FIRST, null, null), // l10n: not text (an id, a version, a route, CHANGELOG words)
    CAR_SOUND("car-sound", "1.4.2", GuideStep.HEADSET_CAR, "car", null), // l10n: not text (an id, a version, a route, CHANGELOG words)
    NO_EMPTY_REPLY("no-empty-reply", "1.4.2", GuideStep.HELPERS, null, null), // l10n: not text (an id, a version, a route, CHANGELOG words)
    KEPT_QUESTIONS_SURE("kept-questions-sure", "1.4.2", GuideStep.WEB_TERMUX, null, null); // l10n: not text (an id, a version, a route, CHANGELOG words)

    companion object {
        fun of(id: String?): GuideFeature? = entries.firstOrNull { it.id == id }

        /** The rows a guide page shows, in the table's order. */
        fun on(step: GuideStep): List<GuideFeature> = entries.filter { it.step == step }
    }
}

/** What a launch opens by itself: nothing, the whole guide, or What's new. */
enum class GuideLaunch { NOTHING, GUIDE, WHATS_NEW }

object WhatsNew {
    /** The first version What's new lists from (Settings › About shows everything since). */
    const val FIRST = "1.1.0" // l10n: not text (a version)

    /** The last version that kept no number in setup_guide.json (1.0 to 1.4 kept none): an update from any of them counts as from it. */
    const val UNKEPT = "1.4.0" // l10n: not text (a version)

    /**
     * The version Robot last launched as: the kept one; when none was kept but the guide was seen or finished (an update
     * from 1.4 or older), [UNKEPT], so What's new lists what came after it instead of the whole guide opening again; null
     * on a new install.
     */
    /**
     * What's new from [from] lists only what came after 1.4.0 because the version before the update wasn't kept: it ends
     * with a row that opens the guide at its start, where the features from 1.1 to 1.4 are too. A kept version is 1.5 or
     * later (1.4.0 itself kept none), so [UNKEPT] stands only for "not kept".
     */
    fun unkept(from: String): Boolean = from == UNKEPT

    fun from(s: GuideState): String? = s.app ?: UNKEPT.takeIf { s.seen >= 1 || s.finished >= 1 }

    /** "1.4.1", "1.5.0-rc1" or "1.4": its numbers, the missing ones 0; anything else after them is left out. */
    private fun parts(v: String): List<Int> =
        v.trim().takeWhile { it.isDigit() || it == '.' }.split('.').map { it.toIntOrNull() ?: 0 }.let { it + List(maxOf(0, 3 - it.size)) { 0 } }

    fun compare(a: String, b: String): Int {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** The features added after [from] up to and including [to], newest version first, the table's order within one. */
    fun since(from: String, to: String): List<GuideFeature> =
        GuideFeature.entries.filter { compare(it.since, from) > 0 && compare(it.since, to) <= 0 }
            .sortedWith(compareByDescending<GuideFeature, String>(Comparator { a, b -> compare(a, b) }) { it.since })

    /**
     * At a launch no link opened a page for. After an update from a version Robot kept ([GuideState.app] older than
     * [app]) that added features: What's new, once. Otherwise the guide when this guide version is unseen (a new install,
     * or the first update that keeps the version), else nothing (a patch with nothing new, or the same version again).
     */
    fun atLaunch(s: GuideState, app: String): GuideLaunch {
        val last = from(s)
        if (last != null && compare(app, last) > 0 && since(last, app).isNotEmpty()) return GuideLaunch.WHATS_NEW
        return if (SetupGuide.opensAtLaunch(s)) GuideLaunch.GUIDE else GuideLaunch.NOTHING
    }

    /** After [atLaunch]: this version is the one last seen; What's new stands for the new guide version too. */
    fun launched(s: GuideState, app: String, shown: GuideLaunch): GuideState =
        s.copy(app = app, seen = if (shown == GuideLaunch.WHATS_NEW) maxOf(s.seen, SetupGuide.VERSION) else s.seen)

    /** After an update: one notice that What's new waits, when a launch would show it and none was posted for [app] yet. */
    fun notifies(s: GuideState, app: String): Boolean = s.told != app && atLaunch(s, app) == GuideLaunch.WHATS_NEW

    /** The notice for [app] was posted. */
    fun notified(s: GuideState, app: String): GuideState = s.copy(told = app)
}
