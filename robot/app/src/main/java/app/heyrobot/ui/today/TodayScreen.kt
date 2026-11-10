// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import app.heyrobot.ui.common.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.sys.TodaySource
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.Note
import app.heyrobot.core.RLog
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.Today
import app.heyrobot.core.TodayPage
import app.heyrobot.core.TodayView
import app.heyrobot.core.WeekDay
import app.heyrobot.core.guide.GuideState
import app.heyrobot.core.guide.HomeGuide
import app.heyrobot.core.guide.SetupGuide
import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.NotesFiles
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.HomeScreen
import app.heyrobot.core.ModelStatus
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// Today (home-design H2): the Home tab's page for your day, short, with each part a tap away from the screen that holds its
// depth. Nothing here speaks or goes online: the calendar provider, the lists' vault, Files' and Robot to Robot's brief
// counts and your notes, read on the phone. New people keep the getting-started home, with one offer once Robot is ready.

/** What the page's taps do; the app's own screens, or your calendar app for a day or an event. */
class TodayActions(
    val talk: () -> Unit = {},
    val openTime: (Long) -> Unit = {},
    val allowCalendar: () -> Unit = {},
    val openList: (String) -> Unit = {},
    val openReminders: () -> Unit = {},
    val openFiles: () -> Unit = {},
    val openRobots: () -> Unit = {},
    val openNote: (String?) -> Unit = {},
    val add: (String) -> Unit = {},
    val pick: (String?) -> Unit = {},
    val gettingStarted: () -> Unit = {},
    /** H3: History, behind the clock icon. */
    val history: () -> Unit = {},
    /** H3: leave-by, the next event's place in your maps app. */
    val directions: (String) -> Unit = {},
    /** H4: the parts in a new order, by drag or TalkBack's Move up and Move down. */
    val reorder: (List<app.heyrobot.core.TodayPart>) -> Unit = {},
    /** H6: fetch today's forecast (the one way the page goes online), and Settings › Weather to save a town. */
    val fetchWeather: () -> Unit = {},
    val weatherTown: () -> Unit = {},
    /** HS1: car mode by hand, beside Talk (ui/car/CarButton, as Getting started's Car mode button). */
    val carMode: () -> Unit = {},
)

/** The Home tab: Today when you turned it on, else the getting-started home (with the one offer, once). */
@Composable
fun HomeTab(snap: Snapshot, recent: List<StoredConversation>, a: AppActions, modifier: Modifier, guide: GuideState = GuideState()) {
    val ctx = LocalContext.current
    // H4: "put my calendar first" changes the file from :voice under the panel; the page follows it (TodayWatch).
    val owner = LocalLifecycleOwner.current
    val resumed = owner.lifecycle.currentStateFlow.collectAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val changes = remember { app.heyrobot.ui.stampChanges({ HomeSettingsStore.stamp(ctx) }) }
    var home by rememberHomeSettings({ HomeSettingsStore.current(ctx) }, resumed, changes) { withContext(Dispatchers.IO) { HomeSettingsStore.current(ctx) } }
    var started by rememberSaveable { mutableStateOf(false) }
    val f = snap.facts
    val ready = f?.active == true && snap.model == ModelStatus.VERIFIED && snap.voiceReady && f.micGranted
    val saves = rememberCoroutineScope()
    fun change(f: (HomeSettings) -> HomeSettings) { home = f(home); TodaySave.save(saves, ctx, f) { r -> r?.let { home = it } } }
    if (home.today && !started) {
        TodayHome(home, ready, a, modifier, onPick = { n -> change { it.copy(note = n) } }, onGettingStarted = { started = true },
            onOrder = { o -> change { it.copy(order = o) } })
        return
    }
    Column(modifier) {
        if (home.today) {
            TextButton(onClick = { started = false }, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.today_back)) }
        } else if (home.offerShows(ready, guide.at != null && SetupGuide.home(guide) == HomeGuide.CONTINUE)) {
            TodayOffer(onOn = { change { it.turnedOn() } }, onNotNow = { change { it.declined() } })
        }
        HomeScreen(snap, recent, a, Modifier.weight(1f), guide)
    }
}

/** "See your whole day here instead": Turn on, or Not now for good (Settings › Display stays the way in). */
@Composable
fun TodayOffer(onOn: () -> Unit, onNotNow: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.today_offer), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOn, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_offer_on)) }
            TextButton(onClick = onNotNow, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_offer_not_now)) }
        }
    }
}

@Composable
private fun TodayHome(home: HomeSettings, ready: Boolean, a: AppActions, modifier: Modifier, onPick: (String?) -> Unit, onGettingStarted: () -> Unit,
    onOrder: (List<app.heyrobot.core.TodayPart>) -> Unit) {
    val ctx = LocalContext.current
    val lang = LocalLangs.current.ui
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<TodayView?>(null) }
    var notes by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableIntStateOf(0) }
    var changes by remember { mutableIntStateOf(0) }
    val refresh = remember { app.heyrobot.core.PageRefresh<List<Any?>, app.heyrobot.core.TodayRead>() }
    var weatherBusy by remember { mutableStateOf(false) }
    var weatherProblem by remember { mutableStateOf<app.heyrobot.core.i18n.Phrase?>(null) }
    val owner = LocalLifecycleOwner.current
    // today-fix-b: read each time the page comes back and when what it reads changed (the calendar, a file, a setting, the
    // lock, the date); once a minute only the clock moves the right-now and leave-by lines (core PageRefresh).
    DisposableEffect(ctx) { val stop = app.heyrobot.sys.CalendarChanges.observe(ctx) { changes++ }; onDispose { stop() } }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { open++; while (true) { tick++; delay(60_000) } } }
    LaunchedEffect(open, tick, changes, home.note, lang) {
        val now = ZonedDateTime.now()
        val at = open
        val (v, n) = withContext(Dispatchers.IO) {
            val r = refresh.get(at, TodaySource.stamp(ctx, now) + listOf(home.note, lang, changes)) { last, fresh ->
                TodaySource.read(ctx, lang, home, now, robots = if (fresh) null else last?.fromRobots, choices = NOTE_CHOICES)
            }
            r.view(now) to r.choices
        }
        view = v; notes = n
    }
    val v = view ?: return Box(modifier.fillMaxSize())
    TodayScreen(v, ready, lang, notes, order = home.order, weatherBusy = weatherBusy, weatherProblem = weatherProblem?.str(), t = TodayActions(
        talk = { a.talk(null) },
        carMode = a.carMode,
        openTime = { ms -> app.heyrobot.ui.calendar.CalendarJump.at = ms; a.openTab(Tab.CALENDAR) },
        allowCalendar = a.askCalendar,
        openList = { a.openPage(Page.ListPage(it)) },
        openReminders = { a.openPage(Page.Settings(SettingsRoutes.ROUTINES)) },
        openFiles = { a.openTab(Tab.FILES) },
        openRobots = { a.openPage(Page.Settings(SettingsRoutes.LINK)) },
        openNote = a.openNote,
        add = { text -> scope.launch { withContext(Dispatchers.IO) { quickAdd(ctx, home, text) }; changes++ } },
        pick = onPick,
        gettingStarted = onGettingStarted,
        history = { a.openPage(Page.History) },
        directions = { p -> app.heyrobot.ui.calendar.CalendarSource.start(ctx, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(app.heyrobot.core.calendar.LeaveBy.geo(p)))) },
        reorder = onOrder,
        fetchWeather = {
            if (!weatherBusy) {
                weatherBusy = true
                weatherProblem = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        val s = app.heyrobot.sys.SettingsStore.load(ctx)
                        val town = s.weatherHome
                        if (town == null) null
                        else app.heyrobot.sys.TodayWeatherStore.fetch(ctx, town, app.heyrobot.core.WeatherUnits.of(s.regionFor(app.heyrobot.sys.Phones.deviceRegion(ctx))))
                    }
                    weatherBusy = false
                    weatherProblem = (r as? app.heyrobot.sys.TodayWeatherStore.Fetched.Failed)?.why
                    changes++
                }
            }
        },
        weatherTown = { a.openPage(Page.Settings(SettingsRoutes.WEATHER)) },
    ), modifier = modifier, news = { TodayNews(home, lang) })
}

private const val NOTE_CHOICES = 20

/** A line added to the pinned note (or your newest), or a new note when you have none. */
private fun quickAdd(ctx: Context, home: HomeSettings, text: String) {
    try {
        val store = NotesFiles.store(ctx)
        val now = System.currentTimeMillis()
        val picked: Note? = TodayPage.pick(store.list(), home.note)?.first
        if (picked == null) { if (text.isNotBlank()) store.add(text.trim().take(TodayPage.QUICK_ADD), null, now) }
        else TodayPage.added(picked, text, now)?.let { store.save(it) }
    } catch (t: Throwable) {
        RLog.w("today_quick_add", t)
    }
}

/** Below this height (split screen, a guess) Talk scrolls with the page instead of staying pinned. */
private val SHORT = 480.dp
/** One column, at most this wide on a large inner screen. */
private val WIDEST = 640.dp
private val STRIP_EDGES = 12.dp

/** The page itself, drawn from [v]: tested on its own. [notes]: the notes you can pin, id and title. */
@Composable
fun TodayScreen(v: TodayView, ready: Boolean, lang: Lang, notes: List<Pair<String, String>>, t: TodayActions, modifier: Modifier = Modifier,
    order: List<app.heyrobot.core.TodayPart> = app.heyrobot.core.TodayPart.DEFAULT, weatherBusy: Boolean = false, weatherProblem: String? = null,
    /** H7: the news, below the weather (TodayNews; nothing with no feeds). */
    news: @Composable () -> Unit = {}) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = LocalConfiguration.current.fontScale
        // At twice the text size, or under about 320 sp across, seven columns can't hold: the rows alone.
        // Each day takes 48 dp at least: the strip reaches 6 dp from the screen's edges, past the page's margins.
        val column = minOf(maxWidth, WIDEST)
        val strip = fontScale < 1.99f && maxWidth.value / fontScale >= 320f && (column - STRIP_EDGES) / 7 >= 48.dp
        val pinned = maxHeight >= SHORT
        val stacked = TalkRow.stacked(maxWidth.value, fontScale)
        Column(Modifier.widthIn(max = WIDEST).fillMaxWidth().fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { Header(v, lang) }
                    // H3: History, behind the clock icon on both homes.
                    androidx.compose.material3.IconButton(onClick = t.history) { Icon(app.heyrobot.ui.common.RIcons.History, app.heyrobot.core.i18n.UiStrings.history().str()) }
                }
                if (!ready) Line(stringResource(R.string.today_not_ready), stringResource(R.string.today_getting_started), t.gettingStarted)
                TodayParts(order, waiting(v), t.reorder) { p ->
                    when (p) {
                        app.heyrobot.core.TodayPart.CALENDAR -> Calendar(v, lang, if (strip) column - STRIP_EDGES else null, t)
                        app.heyrobot.core.TodayPart.LISTS -> Waiting(v, t)
                        app.heyrobot.core.TodayPart.NOTE -> NotePart(v, notes, t)
                    }
                }
                v.weather?.let { WeatherPart(it, v.day.now.zone, lang, weatherBusy, weatherProblem, t) }
                news()
                Column {
                    Text(stringResource(R.string.today_done), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = t.gettingStarted, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_getting_started)) }
                }
                if (!pinned) Talk(t.talk, t.carMode, stacked)
                Spacer(Modifier.height(8.dp))
            }
            if (pinned) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Talk(t.talk, t.carMode, stacked) }
        }
    }
}

/**
 * H6: the weather line, from the forecast stored when you last tapped, with its age; "Updated 6:32" fetches again. Old
 * weather isn't shown: no forecast for today offers a fetch, and no saved town opens Settings › Weather.
 */
@Composable
private fun WeatherPart(w: app.heyrobot.core.WeatherGlance, zone: ZoneId, lang: Lang, busy: Boolean, problem: String?, t: TodayActions) {
    Column {
        when (w) {
            app.heyrobot.core.WeatherGlance.NoTown -> Line(stringResource(R.string.today_weather_no_town), stringResource(R.string.today_weather_town), t.weatherTown)
            app.heyrobot.core.WeatherGlance.NotYet -> Column {
                Text(stringResource(R.string.today_weather_not_yet), style = MaterialTheme.typography.bodyLarge)
                FilledTonalButton(onClick = t.fetchWeather, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (busy) R.string.today_weather_fetching else R.string.today_weather_fetch))
                }
            }
            is app.heyrobot.core.WeatherGlance.Shown -> {
                Text(w.line.str(), style = MaterialTheme.typography.bodyLarge)
                val updated = stringResource(R.string.today_weather_updated, TodayPage.time(w.at, zone, lang))
                // today-fix-c 9: TalkBack hears that it is fetching, not "Get the forecast again", while it does.
                val again = if (busy) stringResource(R.string.today_weather_fetching) else stringResource(R.string.today_weather_again, updated)
                TextButton(onClick = t.fetchWeather, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = again }) {
                    Text(if (busy) stringResource(R.string.today_weather_fetching) else updated)
                }
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

/** The date and the right-now line: one heading, the line read first. */
@Composable
private fun Header(v: TodayView, lang: Lang) {
    val p = v.day
    val now = Today.rightNow(p, lang).str()
    val tomorrow = p.tomorrow?.takeIf { p.doneForToday }?.let { e ->
        stringResource(R.string.today_tomorrow, title(e), if (e.allDay) stringResource(R.string.today_all_day) else TodayPage.time(e.begin, p.now.zone, lang))
    }
    val date = p.now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale(lang))) // l10n: not text (a date pattern; the words are the locale's)
    val said = listOfNotNull(now, tomorrow, date).joinToString(" ") // l10n: not text (TalkBack reads the line, then the date)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = said; heading() }) {
        Text(date.replaceFirstChar { it.titlecase(locale(lang)) }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(now, style = MaterialTheme.typography.headlineSmall)
        if (tomorrow != null) Text(tomorrow, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun locale(lang: Lang): Locale = if (lang == Lang.FR) Locale.CANADA_FRENCH else Locale.CANADA

@Composable
private fun title(e: CalendarEvent): String = TodayPage.title(e).ifEmpty { BriefStrings.untitled().str() }

@Composable
private fun Calendar(v: TodayView, lang: Lang, strip: androidx.compose.ui.unit.Dp?, t: TodayActions) {
    if (!v.readable) {
        Line(stringResource(R.string.today_calendar_allow_text), stringResource(R.string.today_calendar_allow), t.allowCalendar)
        return
    }
    val zone = v.day.now.zone
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (strip != null) Row(Modifier.requiredWidth(strip).align(Alignment.CenterHorizontally).testTag(STRIP)) { v.week.forEach { d -> Day(d, zone, lang, t) } }
        val open = stringResource(R.string.today_open_calendar)
        for (e in v.rows) {
            val bold = e == v.next
            val time = when {
                e.allDay -> stringResource(R.string.today_all_day)
                e.begin <= v.day.now.toInstant().toEpochMilli() -> stringResource(R.string.today_now)
                else -> TodayPage.time(e.begin, zone, lang)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = open) { t.openTime(e.begin) }
                .semantics(mergeDescendants = true) {}.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(time, Modifier.widthIn(min = 72.dp).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (bold) FontWeight.SemiBold else null)
                Column(Modifier.weight(1f)) {
                    Text(title(e), style = MaterialTheme.typography.bodyLarge, fontWeight = if (bold) FontWeight.SemiBold else null)
                    e.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (v.more > 0) Text(pluralStringResource(R.plurals.today_and_more, v.more, v.more), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // H3, leave-by: the next event's place, one tap to your maps app; no time computed, nothing online.
        app.heyrobot.core.calendar.LeaveBy.place(v.next)?.let { place ->
            val label = stringResource(R.string.today_calendar_directions_label, place)
            FilledTonalButton(onClick = { t.directions(place) }, modifier = Modifier.heightIn(min = 48.dp).testTag(DIRECTIONS)
                .semantics { contentDescription = label }) { Text(stringResource(R.string.today_calendar_directions)) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Day(d: WeekDay, zone: ZoneId, lang: Lang, t: TodayActions) {
    val loc = locale(lang)
    val name = d.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", loc)) // l10n: not text (a date pattern; the words are the locale's)
    val said = if (d.count == 0) stringResource(R.string.today_day_none, name) else pluralStringResource(R.plurals.today_day_events, d.count, name, d.count)
    val open = stringResource(R.string.today_open_calendar)
    Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable(onClickLabel = open) { t.openTime(d.date.atStartOfDay(zone).toInstant().toEpochMilli()) }
        .clearAndSetSemantics { contentDescription = said }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, loc).take(2), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.size(32.dp).then(if (d.today) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.bodyLarge, // l10n: not text (a day's number)
                color = if (d.today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(d.dots) { Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)) }
        }
    }
}

/** Lists, reminders, Files' loose ends and word from other Robots: a line each, and no line when there is nothing. */
@Composable
private fun Waiting(v: TodayView, t: TodayActions) {
    val rows = buildList<Pair<String, () -> Unit>> {
        for (l in v.lists) add(stringResource(R.string.today_list_left, l.name, l.left) to { t.openList(l.id) })
        for (r in v.reminders) add(stringResource(R.string.today_reminder, r) to t.openReminders)
        if (v.moreReminders > 0) add(pluralStringResource(R.plurals.today_and_more, v.moreReminders, v.moreReminders) to t.openReminders)
        if (v.looseEnds > 0) add(pluralStringResource(R.plurals.today_loose_ends, v.looseEnds, v.looseEnds) to t.openFiles)
        if (v.fromRobots > 0) add(pluralStringResource(R.plurals.today_from_robots, v.fromRobots, v.fromRobots) to t.openRobots)
    }
    if (rows.isEmpty()) return
    Column { rows.forEach { (text, go) -> Tap(text, go) } }
}

@Composable
private fun NotePart(v: TodayView, notes: List<Pair<String, String>>, t: TodayActions) {
    val n = v.note
    var text by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (n != null) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { t.openNote(n.id) }.semantics(mergeDescendants = true) {}.padding(vertical = 4.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleMedium)
                n.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text(stringResource(if (n != null) R.string.today_note_add_hint else R.string.today_note_new_hint)) })
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (text.isNotBlank()) { t.add(text); text = "" } }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_note_add)) }
        }
        if (notes.size > 1) Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_note_pick)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.today_note_newest)) }, { menu = false; t.pick(null) })
                notes.forEach { (id, title) -> DropdownMenuItem({ Text(title) }, { menu = false; t.pick(id) }) }
            }
        }
    }
}

/** A line of text with its one button (the calendar's Allow, Robot not ready). */
@Composable
private fun Line(text: String, button: String, go: () -> Unit) {
    Column {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = go, modifier = Modifier.heightIn(min = 48.dp)) { Text(button) }
    }
}

@Composable
private fun Tap(text: String, go: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = go).padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
}

/** HS1: Talk and Car mode side by side (2 to 1), or stacked when the screen is narrow or the text large. */
internal object TalkRow {
    /** Below this width (dp) the two don't fit side by side. */
    const val NARROW = 360f
    /** From this font scale the labels need the whole width. */
    const val LARGE_FONT = 1.5f
    fun stacked(widthDp: Float, fontScale: Float): Boolean = widthDp < NARROW || fontScale >= LARGE_FONT
}

/** Talk first in both forms, so TalkBack reads it first. */
@Composable
private fun Talk(talk: () -> Unit, car: () -> Unit, stacked: Boolean) {
    if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TalkButton(talk, Modifier.fillMaxWidth())
        CarModeButton(car, Modifier.fillMaxWidth())
    } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TalkButton(talk, Modifier.weight(2f).fillMaxHeight())
        CarModeButton(car, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun TalkButton(talk: () -> Unit, modifier: Modifier) {
    Button(onClick = talk, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(20.dp)) {
        Icon(RIcons.Mic, null)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.home_talk_to_robot), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun CarModeButton(car: () -> Unit, modifier: Modifier) {
    FilledTonalButton(onClick = car, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(RIcons.Car, null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.home_car_mode), style = MaterialTheme.typography.titleMedium, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Settings › Display's row: Today as your home page, on or off. Turning it on answers the offer. */
@Composable
fun TodayHomeSetting() {
    val ctx = LocalContext.current
    var home by remember { mutableStateOf(HomeSettingsStore.current(ctx)) }
    val saves = rememberCoroutineScope()
    SwitchPreference(stringResource(R.string.today_setting), stringResource(if (home.today) R.string.today_setting_on else R.string.today_setting_off), home.today) { on ->
        val f: (HomeSettings) -> HomeSettings = { if (on) it.turnedOn() else it.copy(today = false) }
        home = f(home)
        TodaySave.save(saves, ctx, f) { r -> r?.let { home = it }; TodayGlances.changed(ctx) }
    }
    // H6: the weather line, off until you turn it on; it fetches only when you tap it.
    if (home.today) {
        SwitchPreference(stringResource(R.string.today_weather_setting), stringResource(if (home.weather) R.string.today_weather_setting_on else R.string.today_weather_setting_off), home.weather) { on ->
            home = home.copy(weather = on)
            TodaySave.save(saves, ctx, { it.copy(weather = on) }) { r -> r?.let { home = it } }
        }
        // today-fix-c 4: H9's lock screen summary and alarm switches, as the weather line, only with Today on.
        TodayOutsideSettings()
    }
    // H7: your news feeds, none to start.
    if (home.today) TodayNewsSetting()
}

private fun waiting(v: TodayView): Boolean =
    v.lists.isNotEmpty() || v.reminders.isNotEmpty() || v.moreReminders > 0 || v.looseEnds > 0 || v.fromRobots > 0

/**
 * H4: the parts in your [order] (lists and reminders only when [hasWaiting]). Press and hold one, then drag it up or down;
 * TalkBack has Move up and Move down on each part, named for it. The right-now line above and the foot below don't move.
 */
@Composable
private fun TodayParts(order: List<app.heyrobot.core.TodayPart>, hasWaiting: Boolean, reorder: (List<app.heyrobot.core.TodayPart>) -> Unit,
    part: @Composable (app.heyrobot.core.TodayPart) -> Unit) {
    val shown = app.heyrobot.core.TodayOrder.of(order.map { it.key }).filter { it != app.heyrobot.core.TodayPart.LISTS || hasWaiting }
    var dragging by remember { mutableStateOf<app.heyrobot.core.TodayPart?>(null) }
    var offset by remember { mutableStateOf(0f) }
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<app.heyrobot.core.TodayPart, Pair<Float, Float>>() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val up = stringResource(R.string.today_move_up)
    val down = stringResource(R.string.today_move_down)
    fun place(p: app.heyrobot.core.TodayPart, to: Int) {
        val next = app.heyrobot.core.TodayOrder.placed(order, shown, p, to)
        if (next != app.heyrobot.core.TodayOrder.of(order.map { it.key })) reorder(next)
    }
    shown.forEachIndexed { i, p ->
        val name = stringResource(when (p) {
            app.heyrobot.core.TodayPart.CALENDAR -> R.string.today_part_calendar
            app.heyrobot.core.TodayPart.LISTS -> R.string.today_part_lists
            app.heyrobot.core.TodayPart.NOTE -> R.string.today_part_note
        })
        androidx.compose.runtime.key(p) {
            Box(Modifier
                .onPlaced { bounds[p] = it.positionInParent().y to it.size.height.toFloat() }
                .zIndex(if (dragging == p) 1f else 0f)
                .graphicsLayer { translationY = if (dragging == p) offset else 0f }
                .holdToDrag(shown, onStart = { dragging = p; offset = 0f; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                    onDrag = { offset += it }, onCancel = { dragging = null; offset = 0f }, onEnd = {
                        val (top, height) = bounds[p] ?: (0f to 0f)
                        val middle = top + height / 2 + offset
                        val to = shown.filter { it != p }.count { q -> bounds[q]?.let { (t, h) -> t + h / 2 < middle } ?: false }
                        dragging = null; offset = 0f
                        place(p, to)
                    })
                .semantics {
                    contentDescription = name
                    customActions = listOfNotNull(
                        if (i > 0) androidx.compose.ui.semantics.CustomAccessibilityAction(up) { place(p, i - 1); true } else null,
                        if (i < shown.lastIndex) androidx.compose.ui.semantics.CustomAccessibilityAction(down) { place(p, i + 1); true } else null)
                }
                .testTag(PART + p.key)) { part(p) }
        }
    }
}

/**
 * Press and hold (the phone's long-press time, without moving past its touch slop), then drag: [onDrag] gets each step
 * down the screen. Once held, the gesture is the drag's alone (consumed before the part's own taps and the page's scroll
 * see it), so letting go never opens what was under the finger. A quick tap or a scroll is left alone.
 */
private fun Modifier.holdToDrag(key: Any, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val letGo = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id } ?: break
                    if (!c.pressed || (c.position - first.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (letGo != null) return@awaitEachGesture
            onStart()
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == first.id }
                if (c == null) { onCancel(); return@awaitEachGesture }
                c.consume()
                if (!c.pressed) { onEnd(); return@awaitEachGesture }
                onDrag(c.position.y - c.previousPosition.y)
            }
        }
    }

/** A Today part's test tag, its key after this. */
const val PART = "today_part_" // l10n: not text (a test tag)

/** The week strip's test tag. */
const val STRIP = "today_strip" // l10n: not text (a test tag)
const val DIRECTIONS = "today_directions" // l10n: not text (a test tag)
