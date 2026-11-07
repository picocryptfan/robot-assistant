// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.calendar

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.ui.common.AlertDialog
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.EventKind
import app.heyrobot.core.RLog
import app.heyrobot.sys.Assistant
import app.heyrobot.core.TodayPage
import app.heyrobot.core.calendar.AgendaDay
import app.heyrobot.core.calendar.CalendarAgenda
import app.heyrobot.core.calendar.LeaveBy
import app.heyrobot.core.calendar.PrivateEdits
import app.heyrobot.core.calendar.PrivateEvent
import app.heyrobot.core.calendar.PrivateRefusal
import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.PrivateEventsFiles
import app.heyrobot.sys.PrivateLoad
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// MAIN PROCESS. The Calendar tab (home-design H3, §5, §7), where History was: an agenda you scroll by day, the week strip
// on top and Material's date picker to jump. The phone's events (read only; tap opens your calendar app) and private
// events (only Robot knows them: app/sys/PrivateEventsVault.kt). You change private events and the ones Robot added;
// others change only in your calendar app. Leave-by: the next event's place and one tap to your maps app, nothing computed
// and nothing online. Each row says whose event it is in words, so colour is never the only sign.

/** A day or an event's start Today asked the Calendar tab to show (read once by the tab). */
object CalendarJump {
    @Volatile var at: Long? = null
}

/** What the tab shows, read on the phone. */
data class CalendarView(
    val now: ZonedDateTime,
    val from: LocalDate,
    val days: List<AgendaDay>,
    /** The phone's calendar can be read. */
    val readable: Boolean,
    /** Private events: Loaded, Locked or Unreadable (the line says which). */
    val privateState: PrivateState,
    val robotAdded: Set<Long>,
    /** The next event from now, today or later in the window, for leave-by. */
    val next: CalendarEvent?,
)

enum class PrivateState { OK, LOCKED, UNREADABLE }

/** today-fix-b: the tab's window as read ([CalendarSource.read]); [view] at any minute from it, nothing read again. */
class CalendarRead(val from: LocalDate, val all: List<CalendarEvent>, val readable: Boolean, val privateState: PrivateState, val robotAdded: Set<Long>) {
    fun view(now: ZonedDateTime): CalendarView {
        val ms = now.toInstant().toEpochMilli()
        val next = if (from <= now.toLocalDate()) all.filter { !it.allDay && it.begin >= ms }.minByOrNull { it.begin } else null
        return CalendarView(now, from, CalendarAgenda.days(all, from, now.zone), readable, privateState, robotAdded, next)
    }
}

/** The tab's taps. */
class CalendarActions(
    val history: () -> Unit = {},
    val allow: () -> Unit = {},
    val jump: (LocalDate) -> Unit = {},
    val openInApp: (CalendarEvent) -> Unit = {},
    val addInApp: () -> Unit = {},
    val directions: (String) -> Unit = {},
    val save: (EventDraft) -> PrivateRefusal? = { null },
    val delete: (EventDraft) -> Unit = {},
    val startOver: () -> Unit = {},
)

/** The form's event: a private one ([key]), a Robot-added phone one ([phoneId]), or a new private one (neither). */
data class EventDraft(
    val key: String? = null,
    val phoneId: Long = 0,
    val title: String = "",
    val place: String = "",
    val date: LocalDate,
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(10, 0),
    val allDay: Boolean = false,
) {
    val isNew: Boolean get() = key == null && phoneId == 0L

    /** Start and end in [zone]: an all-day event midnight to midnight UTC, as the provider stores it; an end before the start is the next day. */
    fun span(zone: ZoneId): Pair<Long, Long> = if (allDay) {
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } else {
        val b = date.atTime(start).atZone(zone)
        val e = date.atTime(end).atZone(zone).let { if (end < start) it.plusDays(1) else it }
        b.toInstant().toEpochMilli() to e.toInstant().toEpochMilli()
    }

    companion object {
        fun of(e: CalendarEvent, zone: ZoneId): EventDraft {
            val b = if (e.allDay) Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC) else Instant.ofEpochMilli(e.begin).atZone(zone)
            val en = Instant.ofEpochMilli(e.end).atZone(zone)
            return EventDraft(e.key, if (e.kind == EventKind.PHONE) e.id else 0, e.title, e.location.orEmpty(), b.toLocalDate(),
                b.toLocalTime().withSecond(0).withNano(0), en.toLocalTime().withSecond(0).withNano(0), e.allDay)
        }
    }
}

/** The tab: reads the window's events off the main thread, again when it comes back and once a minute. */
@Composable
fun CalendarTab(a: AppActions, modifier: Modifier) {
    val ctx = LocalContext.current
    val lang = LocalLangs.current.ui
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf(CalendarJump.at?.let { day(it) } ?: LocalDate.now()) }
    var view by remember { mutableStateOf<CalendarView?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var open by remember { mutableIntStateOf(0) }
    var changes by remember { mutableIntStateOf(0) }
    val refresh = remember { app.heyrobot.core.PageRefresh<List<Any?>, CalendarRead>() }
    val owner = LocalLifecycleOwner.current
    // today-fix-b: read when the tab comes back and when what it reads changed; once a minute only the leave-by line moves.
    DisposableEffect(ctx) { val stop = app.heyrobot.sys.CalendarChanges.observe(ctx) { changes++ }; onDispose { stop() } }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            CalendarJump.at?.let { from = day(it); CalendarJump.at = null }
            open++
            while (true) { tick++; delay(60_000) }
        }
    }
    LaunchedEffect(open, tick, changes, from) {
        val now = ZonedDateTime.now()
        val at = open
        val v = withContext(Dispatchers.IO) { refresh.get(at, CalendarSource.stamp(ctx, now) + listOf(from, changes)) { _, _ -> CalendarSource.read(ctx, from, now) }.view(now) }
        view = v
    }
    val v = view ?: return Box(modifier.fillMaxSize())
    val zone = v.now.zone
    CalendarScreen(v, lang, CalendarActions(
        history = { a.openPage(app.heyrobot.ui.Page.History) },
        allow = a.askCalendar,
        jump = { from = it },
        openInApp = { e -> CalendarSource.openInApp(ctx, e) },
        addInApp = { CalendarSource.start(ctx, Calendars.editorIntent()) },
        directions = { p -> CalendarSource.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(LeaveBy.geo(p)))) },
        save = { d -> CalendarSource.save(ctx, d, zone).also { if (it == null) changes++ } },
        delete = { d -> scope.launch { withContext(Dispatchers.IO) { CalendarSource.delete(ctx, d) }; changes++ } },
        startOver = {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { CalendarSource.startOver(ctx) }
                a.say(ctx.getString(if (ok) R.string.today_calendar_started_over else R.string.today_calendar_not_started_over))
                changes++
            }
        },
    ), modifier)
}

private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** Reading and writing for the tab: the provider, the private events vault, your calendar and maps apps. */
object CalendarSource {
    fun view(ctx: Context, from: LocalDate, now: ZonedDateTime = ZonedDateTime.now()): CalendarView = read(ctx, from, now).view(now)

    /** today-fix-b: the window's events as read, for the tab to show again each minute without reading (core PageRefresh). */
    fun read(ctx: Context, from: LocalDate, now: ZonedDateTime = ZonedDateTime.now()): CalendarRead {
        val range = CalendarAgenda.range(from, now.zone)
        val readable = Calendars.readGranted(ctx)
        val phone = if (readable) try { Calendars.events(ctx, range) } catch (t: Throwable) { RLog.w("calendar_tab", t); null } else null
        val load = try { PrivateEventsFiles.vault(ctx).load() } catch (t: Throwable) { RLog.w("calendar_private", t); PrivateLoad.Unreadable }
        val private = (load as? PrivateLoad.Loaded)?.file?.events.orEmpty()
        val state = when (load) { is PrivateLoad.Loaded -> PrivateState.OK; PrivateLoad.Locked -> PrivateState.LOCKED; else -> PrivateState.UNREADABLE }
        return CalendarRead(from, CalendarAgenda.join(phone, private), readable, state, PrivateEventsFiles.added(ctx))
    }

    /** today-fix-b: what [read] stands on, at [now], with nothing opened (core PageStamp); the tab adds its own keys. */
    fun stamp(ctx: Context, now: ZonedDateTime): List<Any?> = listOf(now.toLocalDate(), now.zone, Calendars.readGranted(ctx), Assistant.keyguard(ctx),
        Assistant.locked(ctx), app.heyrobot.core.PageStamp.files(PrivateEventsFiles.dir(ctx), PrivateEventsFiles.addedFile(ctx)))

    /** A private event saved, or a Robot-added phone event changed; the refusal, or null when it went in. */
    fun save(ctx: Context, d: EventDraft, zone: ZoneId): PrivateRefusal? {
        val (b, e) = d.span(zone)
        if (d.phoneId > 0) {
            if (d.title.isBlank()) return PrivateRefusal.NO_TITLE
            if (e < b || (d.allDay && e <= b)) return PrivateRefusal.ENDS_BEFORE
            return if (Calendars.update(ctx, d.phoneId, d.title.trim(), d.place.trim(), b, e, d.allDay, zone)) null else PrivateRefusal.NOT_FOUND
        }
        val ev = PrivateEvent(d.key ?: PrivateEdits.newId(), d.title, b, e, d.allDay, d.place)
        val (load, edit) = PrivateEventsFiles.vault(ctx).update { f, now -> PrivateEdits.save(f, ev, now) }
        return edit?.refused ?: when (load) { is PrivateLoad.Loaded -> null; PrivateLoad.Locked -> PrivateRefusal.LOCKED; else -> PrivateRefusal.UNREADABLE }
    }

    /** Private events Robot can't open, kept aside so new ones can be saved (PrivateEventsVault.startOver). */
    fun startOver(ctx: Context): Boolean = PrivateEventsFiles.vault(ctx).startOver()

    fun delete(ctx: Context, d: EventDraft) {
        if (d.phoneId > 0) { if (Calendars.delete(ctx, d.phoneId)) PrivateEventsFiles.forgetAdded(ctx, d.phoneId); return }
        val key = d.key ?: return
        PrivateEventsFiles.vault(ctx).update { f, _ -> PrivateEdits.delete(f, key) }
    }

    /** Your calendar app at the event (or at its time when it has no id). */
    fun openInApp(ctx: Context, e: CalendarEvent) {
        val i = if (e.id > 0) Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(android.provider.CalendarContract.Events.CONTENT_URI, e.id))
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, e.end)
        else Intent(Intent.ACTION_VIEW, android.content.ContentUris.appendId(android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time"), e.begin).build()) // l10n: not text (the provider's path)
        start(ctx, i)
    }

    fun start(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t: Throwable) { RLog.w("calendar_start", t) }
    }
}

/** One column, at most this wide on a large inner screen. */
private val WIDEST = 640.dp
private val STRIP_EDGES = 12.dp
const val CALENDAR_STRIP = "calendar_strip"
const val CALENDAR_LIST = "calendar_list"

/** The tab itself, drawn from [v]: tested on its own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(v: CalendarView, lang: Lang, t: CalendarActions, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf<EventDraft?>(null) }
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val zone = v.now.zone
    val today = v.now.toLocalDate()
    var confirmStartOver by remember { mutableStateOf(false) }
    if (confirmStartOver) app.heyrobot.ui.pref.ConfirmDialog(stringResource(R.string.today_calendar_start_over_title), stringResource(R.string.today_calendar_start_over_text),
        stringResource(R.string.today_calendar_start_over), onDismiss = { confirmStartOver = false }) { confirmStartOver = false; t.startOver() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val fontScale = LocalConfiguration.current.fontScale
        val column = minOf(maxWidth, WIDEST)
        val strip = fontScale < 1.99f && maxWidth.value / fontScale >= 320f && (column - STRIP_EDGES) / 7 >= 48.dp
        LazyColumn(Modifier.widthIn(max = WIDEST).fillMaxWidth().testTag(CALENDAR_LIST), rememberLazyListState(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "top") {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(UiStrings.calendar().str(), Modifier.weight(1f).padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                        IconButton(onClick = t.history, modifier = Modifier.heightIn(min = 48.dp)) { Icon(RIcons.History, UiStrings.history().str()) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (v.from != today) OutlinedButton(onClick = { t.jump(today) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_today)) }
                        OutlinedButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_pick_date)) }
                        FilledTonalButton(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(RIcons.Add, null); Spacer(Modifier.padding(start = 4.dp)); Text(stringResource(R.string.today_calendar_add))
                        }
                    }
                    if (!v.readable) Line(stringResource(R.string.today_calendar_allow_text), stringResource(R.string.today_calendar_allow), t.allow)
                    when (v.privateState) {
                        PrivateState.LOCKED -> Text(stringResource(R.string.today_calendar_private_locked), style = MaterialTheme.typography.bodyMedium)
                        PrivateState.UNREADABLE -> Line(stringResource(R.string.today_calendar_private_unreadable), stringResource(R.string.today_calendar_start_over)) { confirmStartOver = true }
                        PrivateState.OK -> {}
                    }
                    LeaveByLine(v, lang, t)
                    if (strip) Row(Modifier.requiredWidth(column - STRIP_EDGES).align(Alignment.CenterHorizontally).testTag(CALENDAR_STRIP)) {
                        v.days.take(7).forEach { d -> StripDay(d, d.date == today, lang, t) }
                    }
                }
            }
            items(v.days, key = { it.date.toString() }) { d -> DayPart(d, today, zone, lang, v.robotAdded, t) { editing = it } }
            item(key = "more") {
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { t.jump(v.from.minusDays(CalendarAgenda.DAYS.toLong())) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_earlier)) }
                    OutlinedButton(onClick = { t.jump(v.from.plusDays(CalendarAgenda.DAYS.toLong())) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_later)) }
                }
            }
        }
    }
    if (picking) PickDate(v.from, onPick = { picking = false; t.jump(it) }, onCancel = { picking = false })
    if (adding) AddChoice(onPrivate = { adding = false; editing = EventDraft(date = v.from.takeIf { it > today } ?: today) },
        onApp = { adding = false; t.addInApp() }, onCancel = { adding = false })
    editing?.let { d -> EventForm(d, t.save, { t.delete(d); editing = null }, { editing = null }) }
}

private fun locale(lang: Lang): Locale = if (lang == Lang.FR) Locale.CANADA_FRENCH else Locale.CANADA

@Composable
private fun title(e: CalendarEvent): String = TodayPage.title(e).ifEmpty { BriefStrings.untitled().str() }

/** Leave-by: "Next: dentist at 2:30", its place, and Directions to your maps app. */
@Composable
private fun LeaveByLine(v: CalendarView, lang: Lang, t: CalendarActions) {
    val e = v.next ?: return
    val place = LeaveBy.place(e) ?: return
    val label = stringResource(R.string.today_calendar_directions_label, place)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(stringResource(R.string.today_calendar_next, title(e), TodayPage.time(e.begin, v.now.zone, lang)), style = MaterialTheme.typography.titleMedium)
        Text(place, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = { t.directions(place) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
            Icon(RIcons.Navigate, null); Spacer(Modifier.padding(start = 4.dp)); Text(stringResource(R.string.today_calendar_directions))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StripDay(d: AgendaDay, today: Boolean, lang: Lang, t: CalendarActions) {
    val name = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale(lang))
    val said = d.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale(lang))) // l10n: not text (a date pattern)
    Column(Modifier.weight(1f).heightIn(min = 56.dp).clickable { t.jump(d.date) }.clearAndSetSemantics { contentDescription = said },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(d.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = if (today) FontWeight.Bold else null,
            color = if (today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text("·".repeat(d.events.size.coerceAtMost(TodayPage.MARKS)), style = MaterialTheme.typography.labelMedium) // l10n: not text (dots)
    }
}

@Composable
private fun DayPart(d: AgendaDay, today: LocalDate, zone: ZoneId, lang: Lang, robotAdded: Set<Long>, t: CalendarActions, edit: (EventDraft) -> Unit) {
    val date = d.date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale(lang))).replaceFirstChar { it.titlecase(locale(lang)) } // l10n: not text (a date pattern)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(if (d.date == today) stringResource(R.string.today_calendar_today_date, date) else date, Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall, color = if (d.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.events.isEmpty()) Text(stringResource(R.string.today_calendar_nothing), Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val change = stringResource(R.string.today_calendar_change)
        val open = stringResource(R.string.today_open_calendar)
        for (e in d.events) {
            val mine = CalendarAgenda.editable(e, robotAdded)
            val time = if (e.allDay) stringResource(R.string.today_all_day) else TodayPage.time(e.begin, zone, lang)
            val whose = when {
                e.kind == EventKind.PRIVATE -> stringResource(R.string.today_calendar_private)
                mine -> stringResource(R.string.today_calendar_added_by_robot)
                else -> e.calendar.orEmpty()
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = if (mine) change else open) {
                if (mine) edit(EventDraft.of(e, zone)) else t.openInApp(e)
            }.semantics(mergeDescendants = true) {}.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Text(time, Modifier.widthIn(min = 72.dp).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                Column(Modifier.weight(1f)) {
                    Text(title(e), style = MaterialTheme.typography.bodyLarge)
                    e.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (whose.isNotEmpty()) Text(whose, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Line(text: String, button: String, go: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = go, modifier = Modifier.heightIn(min = 48.dp)) { Text(button) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDate(at: LocalDate, onPick: (LocalDate) -> Unit, onCancel: () -> Unit) {
    val s = rememberDatePickerState(initialSelectedDateMillis = at.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    // Robot's own dialog (it refuses covered taps, DialogTouchesTest); full width and scrolling, so it fits at 360 dp and the largest font.
    AlertDialog(onDismissRequest = onCancel, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false), text = {
        Box(Modifier.verticalScroll(rememberScrollState())) { DatePicker(s, title = null, headline = null) }
    }, confirmButton = {
        TextButton(onClick = { s.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onCancel() }) { Text(stringResource(R.string.today_calendar_ok)) }
    }, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.today_calendar_cancel)) } })
}

@Composable
private fun AddChoice(onPrivate: () -> Unit, onApp: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.today_calendar_add)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onPrivate).semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.today_calendar_add_private), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.today_calendar_add_private_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.today_calendar_add_app), Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onApp).padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium)
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.today_calendar_cancel)) } })
}

/** The small form: a private event, or one Robot added. Nothing is saved until Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventForm(start: EventDraft, save: (EventDraft) -> PrivateRefusal?, onDelete: () -> Unit, onDone: () -> Unit) {
    var d by remember { mutableStateOf(start) }
    val title = androidx.compose.foundation.text.input.rememberTextFieldState(start.title)
    val place = androidx.compose.foundation.text.input.rememberTextFieldState(start.place)
    var why by remember { mutableStateOf<PrivateRefusal?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    // A private event's words are as private as a list's: the keyboard doesn't learn them.
    app.heyrobot.ui.common.NoImeLearning {
    AlertDialog(onDismissRequest = onDone, title = { Text(stringResource(if (d.isNew) R.string.today_calendar_form_new else R.string.today_calendar_form_edit)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField(title, stringResource(R.string.today_calendar_title), app.heyrobot.core.calendar.PrivateEventsCodec.MAX_TITLE)
            FormField(place, stringResource(R.string.today_calendar_place), app.heyrobot.core.calendar.PrivateEventsCodec.MAX_PLACE)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val allDay = stringResource(R.string.today_all_day)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { d = d.copy(allDay = !d.allDay) }
                .clearAndSetSemantics { contentDescription = allDay; toggleableState = ToggleableState(d.allDay) }, verticalAlignment = Alignment.CenterVertically) {
                Text(allDay, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(d.allDay, null)
            }
            OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.today_calendar_date_is, d.date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))))
            }
            if (!d.allDay) {
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_starts_at, clock(d.start))) }
                OutlinedButton(onClick = { pickTime = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_ends_at, clock(d.end))) }
            }
            why?.let { Text(stringResource(refusalText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (!d.isNew) TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_delete)) }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { val e = d.copy(title = title.text.toString(), place = place.text.toString()); scope.launch { val r = withContext(Dispatchers.IO) { save(e) }; if (r == null) onDone() else why = r } }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.today_calendar_save))
        }
    }, dismissButton = { TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.today_calendar_cancel)) } })
    }
    if (pickDate) PickDate(d.date, onPick = { d = d.copy(date = it); pickDate = false }, onCancel = { pickDate = false })
    pickTime?.let { first ->
        val at = if (first) d.start else d.end
        val s = rememberTimePickerState(at.hour, at.minute)
        AlertDialog(onDismissRequest = { pickTime = null }, text = { Box(Modifier.verticalScroll(rememberScrollState())) { TimeInput(s) } }, confirmButton = {
            TextButton(onClick = {
                val t = LocalTime.of(s.hour, s.minute)
                d = if (first) d.copy(start = t, end = if (d.end > d.start) t.plusMinutes(java.time.Duration.between(d.start, d.end).toMinutes()) else d.end) else d.copy(end = t)
                pickTime = null
            }) { Text(stringResource(R.string.today_calendar_ok)) }
        }, dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(R.string.today_calendar_cancel)) } })
    }
}

/**
 * A one-line outlined field on a [androidx.compose.foundation.text.input.TextFieldState], cut at [max] characters
 * (cut, not refused: a long paste keeps its start). Not
 * material3's value-based OutlinedTextField: in a dialog window that one wrote its layout state on every pass, so the
 * form never settled (today-h4). material3 is held at 1.3.2, which has no state-based field, so this is its decoration
 * box around foundation's state-based field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormField(state: androidx.compose.foundation.text.input.TextFieldState, label: String, max: Int) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors()
    androidx.compose.foundation.text.BasicTextField(state, Modifier.fillMaxWidth(),
        inputTransformation = { if (length > max) replace(max, length, "") },
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        decorator = { inner ->
            androidx.compose.material3.OutlinedTextFieldDefaults.DecorationBox(value = state.text.toString(), innerTextField = inner,
                enabled = true, singleLine = true, visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                interactionSource = interaction, label = { Text(label) }, colors = colors,
                container = { androidx.compose.material3.OutlinedTextFieldDefaults.ContainerBox(true, false, interaction, colors) })
        })
}

private fun clock(t: LocalTime): String = t.format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

private fun refusalText(r: PrivateRefusal): Int = when (r) {
    PrivateRefusal.NO_TITLE -> R.string.today_calendar_no_title
    PrivateRefusal.ENDS_BEFORE -> R.string.today_calendar_ends_before
    PrivateRefusal.FULL -> R.string.today_calendar_full
    PrivateRefusal.NOT_FOUND -> R.string.today_calendar_not_saved_gone
    PrivateRefusal.LOCKED -> R.string.today_calendar_not_saved
    PrivateRefusal.UNREADABLE -> R.string.today_calendar_not_saved_unreadable
}
