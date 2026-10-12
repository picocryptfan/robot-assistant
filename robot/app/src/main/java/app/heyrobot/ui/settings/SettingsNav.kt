package app.heyrobot.ui.settings

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.AdbFixScreen
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.BargeTestScreen
import app.heyrobot.ui.NotificationAccessScreen
import app.heyrobot.ui.Page
import app.heyrobot.ui.ROBOT_LICENCE
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.WeatherSettingsScreen
import app.heyrobot.ui.model.ModelRoute
import kotlin.reflect.KProperty

// MAIN PROCESS. Settings' sub-pages by route (Page.Settings, which survives a recreation as "settings:<route>"), the
// pages open over the tabs (PageStack: Back goes up one page, as in Android's Settings), and where a link from outside
// the app opens it (AppRoutes; 0.7: Send feedback from the panel). New pages are added here, with what they need already
// passed in, so MainActivity never changes for them.

object SettingsRoutes {
    const val VOICE = "voice"
    const val BARGE_IN = "voice/barge-in"
    const val ACTIONS = "actions"
    const val PEOPLE_NOTES_CALENDAR = "people-notes-calendar"
    const val WEATHER = "weather"
    const val HISTORY = "history"
    const val DISPLAY = "display"
    /** 0.7: Settings › Memory. */
    const val MEMORY = "memory"
    const val ADVANCED = "advanced"
    const val ABOUT = "about"
    /** 0.8: Settings › Routines, the morning brief's page and each routine's (routines.json, not settings.json). */
    const val ROUTINES = "routines"
    /** Settings › About › Updates (the built-in updater); its notifications open it too. */
    const val UPDATES = "updates"
    /** 0.7: Settings › Messages (messages.json). */
    const val MESSAGES = "messages"
    /** 0.8: Settings › Headset and Settings › Car mode (with Add your car), from headset.json, car.json, places.json, devices.json. */
    const val HEADSET = "headset"
    const val CAR = "car"
    /** Settings › Advanced › ADB fixes › one fix, by its id (core AdbFixes). */
    fun adbFix(id: String) = "adb/$id"
    /** Settings › People › Notification access: the three steps. */
    const val NOTIFICATION_ACCESS = "people/notification-access"
    /** 0.7: Settings › About › Send feedback ([feedback] gives where it was opened from, the kind and a problem to report). */
    const val FEEDBACK = "about/feedback"
    fun feedback(entry: FeedbackEntry, kind: FeedbackKind? = null, problem: Long? = null): String =
        FEEDBACK + "?e=" + entry.key + (kind?.let { "&k=" + it.name.lowercase() } ?: "") + (problem?.let { "&p=$it" } ?: "")
}

/** The page for [route]; an unknown one (from a newer version's saved state) goes back to Settings. */
@Composable
fun SettingsNav(
    route: String,
    s: RobotSettings,
    snap: Snapshot,
    calendars: List<CalendarInfo>,
    update: (RobotSettings) -> Unit,
    a: AppActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    busy: String? = null,
) {
    // Settings › Model's pages (the Model tab's rows open them).
    if (route.startsWith("model/") && ModelRoute(route, s, update, snap, busy, a, snackbar, onBack)) return
    // 0.8: Settings › Routines and its pages.
    if (route == SettingsRoutes.ROUTINES || route.startsWith(SettingsRoutes.ROUTINES + "/")) return RoutinesRoute(route, s, snap, a, snackbar, onBack)
    // 0.8: Settings › Headset, Settings › Car mode and Add your car.
    if (route == SettingsRoutes.HEADSET || route == SettingsRoutes.CAR || route.startsWith(SettingsRoutes.CAR + "/")) return HandsFreeRoute(route, a, snackbar, onBack)
    when (route) {
        SettingsRoutes.DISPLAY -> {
            val ctx = LocalContext.current
            DisplaySettingsScreen(LookStore.current(ctx), onChange = { LookStore.save(ctx, it) }, onBack = onBack)
        }
        SettingsRoutes.VOICE -> VoiceSettingsScreen(s, snap, update, a, snackbar, onBack)
        SettingsRoutes.BARGE_IN -> BargeTestScreen(s, snap, a, snackbar, onBack)
        SettingsRoutes.ACTIONS -> ActionsSettingsScreen(s, snap, update, a, snackbar, onBack)
        SettingsRoutes.PEOPLE_NOTES_CALENDAR -> PeopleNotesCalendarScreen(s, snap, calendars, update, a, snackbar, onBack)
        SettingsRoutes.WEATHER -> WeatherSettingsScreen(s, snap, update, a, snackbar, onBack)
        SettingsRoutes.HISTORY -> HistorySettingsScreen(s, update, a, snackbar, onBack)
        SettingsRoutes.MEMORY -> MemorySettingsRoute(a, snackbar, onBack)
        SettingsRoutes.ADVANCED -> AdvancedSettingsScreen(a, onBack)
        SettingsRoutes.ABOUT -> AboutSettingsScreen(a, onBack)
        SettingsRoutes.UPDATES -> UpdatesRoute(snackbar, onBack,
            onReport = { a.openPage(Page.Settings(SettingsRoutes.feedback(FeedbackEntry.UPDATER, FeedbackKind.BUG))) })
        SettingsRoutes.NOTIFICATION_ACCESS -> NotificationAccessScreen(snap.facts, onBack)
        SettingsRoutes.MESSAGES -> {
            val ctx = LocalContext.current
            MessagesSettingsScreen(messagesFile(), snap.facts, onEdit = { app.heyrobot.sys.MessagesStore.update(ctx, it) }, a, onBack)
        }
        else -> when {
            route.startsWith("adb/") -> AdbFixScreen(route.removePrefix("adb/"), snackbar, onBack)
            route.startsWith(SettingsRoutes.FEEDBACK) -> FeedbackRoute(route, snap, snackbar, onBack)
            else -> LaunchedEffect(route) { onBack() }
        }
    }
}

/**
 * Where Back goes from [p] when nothing was open under it (a link from another screen or app opened it, or Android
 * restored it alone): the page it sits under in Settings, or null for the tabs.
 */
fun parentOf(p: Page): Page? = when (p) {
    is Page.Settings -> when {
        p.route == SettingsRoutes.NOTIFICATION_ACCESS -> Page.People
        // Settings › About › Updates, which the updater's notifications open alone.
        p.route == SettingsRoutes.UPDATES -> Page.Settings(SettingsRoutes.ABOUT)
        // Send feedback: About when it was opened there, else the tabs (the Model tab, the panel).
        p.route.startsWith(SettingsRoutes.FEEDBACK) -> if (FeedbackArgs.of(p.route).entry == FeedbackEntry.ABOUT) Page.Settings(SettingsRoutes.ABOUT) else null
        p.route.startsWith("adb/") -> Page.AdbFixes
        // A model's page sits under the Model tab.
        p.route.startsWith("model/") -> null
        '/' in p.route -> Page.Settings(p.route.substringBeforeLast('/'))
        else -> null
    }
    Page.WakeTest -> Page.WakePhrases
    is Page.PersonEdit -> Page.People
    is Page.Helper, Page.AddHelper -> Page.Helpers
    is Page.Transcript -> Page.Transcripts
    is Page.Licence -> if (p.file != null && p.id != ROBOT_LICENCE) Page.Licence(p.id) else Page.Licences
    else -> null
}

/**
 * The pages open over the tabs, the last one shown. A page opens over the one showing; Back closes it and shows the one
 * under it, or [parentOf] for a page that was opened alone. The whole stack survives a recreation (rotation, the process
 * killed), each page as Page.Saver writes it, one per line.
 */
@Stable
class PageStack(initial: List<Page> = emptyList()) {
    var pages by mutableStateOf(initial)
        private set

    val top: Page? get() = pages.lastOrNull()

    /** Opens [p] over the page showing; a page already open below goes back to it; null closes them all (a tab shows). */
    fun open(p: Page?) {
        pages = when {
            p == null -> emptyList()
            p in pages -> pages.subList(0, pages.indexOf(p) + 1).toList()
            else -> pages + p
        }
    }

    /** [p] in place of the page showing (a new note's first save gives it its id; a helper just added opens its page). */
    fun replace(p: Page) {
        pages = pages.dropLast(1) + p
    }

    /** Back: the page under the one showing, or its parent when it was alone. */
    fun back() {
        pages = if (pages.size > 1) pages.dropLast(1) else listOfNotNull(top?.let(::parentOf))
    }

    /** `var page by stack`: the page showing; setting it opens a page ([open]). */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): Page? = top

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Page?) = open(value)

    companion object {
        val Saver: Saver<PageStack, String> = Saver(
            save = { st -> val scope = this; st.pages.mapNotNull { p -> with(Page.Saver) { scope.save(p) } }.joinToString("\n") },
            // A single page is what 0.6.0 saved, and reads as a stack of one.
            restore = { v -> PageStack(v.split('\n').mapNotNull { Page.Saver.restore(it) }) },
        )
    }
}

/** The app's open pages, kept across a recreation. */
@Composable
fun rememberPageStack(): PageStack = rememberSaveable(saver = PageStack.Saver) { PageStack() }

/**
 * Where the app opens when another screen or app asks for a page (AppPages): the Robot Notes icon, a card's "Allow"
 * (Assistant setup's permissions), a chat-only model's "Open Model", the transcript screen's links, the tile's long press,
 * the summary screen's "Helpers", the updater's notifications.
 */
object AppRoutes {
    /** What [i] asks for, as a route [target] understands, or null. */
    fun of(i: Intent?): String? = when {
        i == null -> null
        i.component?.className == AppPages.NOTES_ALIAS -> AppPages.NOTES
        // 0.5.0: a long press on the "Transcribe" tile.
        i.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES" -> AppPages.CONVERSATION_SETTINGS
        // 0.5.0: the Transcripts library, or one transcript (the transcript screen's "Open", the "Transcripts" shortcut).
        i.getStringExtra(AppPages.EXTRA) == AppPages.TRANSCRIPTS ->
            i.getStringExtra(AppPages.EXTRA_TRANSCRIPT)?.takeIf { TranscriptIds.valid(it) }?.let { id ->
                // A summary's [14:03] link: that line in view (phase 5.6).
                val line = i.getIntExtra(AppPages.EXTRA_LINE, -1)
                "${AppPages.TRANSCRIPTS}:$id" + if (line >= 0) ":$line" else ""
            } ?: AppPages.TRANSCRIPTS
        // Settings › Conversation also from the transcript screen's speaker-model offer (it only opens the page); Settings ›
        // Model from a chat-only model's answer to an action (0.4.3); Settings › About › Updates from the updater's notifications;
        // Settings › Memory from a memory card's "Open Memory" (0.7).
        // 0.7: Send feedback from a feedback phrase in the panel, with its kind only (the words come in a file of Robot's own).
        i.getStringExtra(AppPages.EXTRA) == AppPages.FEEDBACK ->
            AppPages.FEEDBACK + (FeedbackKind.of(i.getStringExtra(AppPages.EXTRA_FEEDBACK_KIND))?.let { ":" + it.name.lowercase() } ?: "")
        else -> i.getStringExtra(AppPages.EXTRA)?.takeIf { it == AppPages.NOTES || it == AppPages.PERMISSIONS || it == AppPages.CONVERSATION_SETTINGS ||
            it == AppPages.MODEL || it == AppPages.HELPERS || it == AppPages.ROUTINES || it == AppPages.UPDATES ||
            it == AppPages.MEMORY || it == AppPages.SETTINGS }
    }

    /** What a route opens: a [tab] (every page closed), or a [page] over the one showing; [notesIcon]: from Robot Notes' icon. */
    data class Target(val tab: Tab? = null, val page: Page? = null, val notesIcon: Boolean = false)

    fun target(r: String): Target? = when {
        r == AppPages.NOTES -> Target(tab = Tab.NOTES, notesIcon = true)
        r == AppPages.PERMISSIONS -> Target(page = Page.Setup(people = true))
        r == AppPages.TRANSCRIPTS -> Target(page = Page.Transcripts)
        r.startsWith(AppPages.TRANSCRIPTS + ":") -> r.split(':').let { p -> Target(page = Page.Transcript(p[1], p.getOrNull(2)?.toIntOrNull())) }
        r == AppPages.CONVERSATION_SETTINGS -> Target(page = Page.ConversationSettings)
        r == AppPages.HELPERS -> Target(page = Page.Helpers)
        r == AppPages.MODEL -> Target(tab = Tab.MODEL)
        r == AppPages.ROUTINES -> Target(page = Page.Settings(SettingsRoutes.ROUTINES))
        r == AppPages.UPDATES -> Target(page = Page.Settings(SettingsRoutes.UPDATES))
        r == AppPages.SETTINGS -> Target(tab = Tab.SETTINGS)
        r == AppPages.MEMORY -> Target(page = Page.Settings(SettingsRoutes.MEMORY))
        r == AppPages.FEEDBACK || r.startsWith(AppPages.FEEDBACK + ":") ->
            Target(page = Page.Settings(SettingsRoutes.feedback(FeedbackEntry.VOICE, FeedbackKind.of(r.substringAfter(':', "")))))
        else -> null
    }
}
