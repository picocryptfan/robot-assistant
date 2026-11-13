// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.everyday.EverydaySettings
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.online.NetEntry
import app.heyrobot.core.online.NetFiles
import app.heyrobot.core.online.NetGroup
import app.heyrobot.core.online.NetMore
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.online.OnlineCompare
import app.heyrobot.core.online.OnlineSummary
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.sys.FeedbackFiles
import app.heyrobot.sys.NetLog
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.pref.preferenceItems
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

// MAIN PROCESS. Settings › Safety & privacy › What went online (docs/plans/everyday-set-plan.md §5.4, §5.5, Q3): every
// connection Robot made, newest first, by day: the time, what for, the server, its bytes and what allowed it; a row's
// details with the Settings page that controls it; a filter by kind; Android's own count for Robot beside the record; the
// switch, the days kept and Delete. Not a SecureWindow: you may want a screenshot of it for a problem report, and it holds
// hosts and counts, never content. The record itself is sys/NetLog's (files/online, on this phone only).

/** What the page shows, read off the main thread. */
private class OnlineView(val rows: List<NetEntry>, val more: Map<LocalDate, NetMore>, val compare: OnlineCompare?, val androidGiven: Boolean, val cut: Boolean)

/** The page each kind's details open: the Settings page that controls it. */
fun onlinePage(p: NetPurpose): Page = when (p.group) {
    NetGroup.WEATHER -> Page.Settings(SettingsRoutes.WEATHER)
    NetGroup.PLACES -> Page.Settings(SettingsRoutes.ACTIONS)
    NetGroup.WEB -> Page.Settings(SettingsRoutes.WEB_SEARCH)
    NetGroup.HELPERS -> Page.Helpers
    NetGroup.DOWNLOADS -> when (p) {
        NetPurpose.VOICE_DOWNLOAD -> Page.Settings(SettingsRoutes.VOICE)
        NetPurpose.TRANSLATION_DOWNLOAD -> Page.ConversationSettings
        NetPurpose.WIKI_DOWNLOAD -> Page.Settings(SettingsRoutes.WIKIPEDIA)
        else -> Page.Settings(SettingsRoutes.MODEL)
    }
    NetGroup.UPDATES -> if (p == NetPurpose.TERMUX_CHECK) Page.Settings(SettingsRoutes.TERMUX) else Page.Settings(SettingsRoutes.UPDATES)
    NetGroup.SKILLS -> Page.Settings(SettingsRoutes.SKILLS)
    NetGroup.LINK -> Page.Settings(SettingsRoutes.LINK)
    NetGroup.NEWS -> Page.Settings(SettingsRoutes.DISPLAY)
    NetGroup.REPORTS -> Page.Settings(SettingsRoutes.REPORTS_SENT)
}

@Composable
private fun groupName(g: NetGroup): String = stringResource(when (g) {
    NetGroup.WEATHER -> R.string.everyday_online_g_weather
    NetGroup.PLACES -> R.string.everyday_online_g_places
    NetGroup.WEB -> R.string.everyday_online_g_web
    NetGroup.HELPERS -> R.string.everyday_online_g_helpers
    NetGroup.DOWNLOADS -> R.string.everyday_online_g_downloads
    NetGroup.UPDATES -> R.string.everyday_online_g_updates
    NetGroup.SKILLS -> R.string.everyday_online_g_skills
    NetGroup.LINK -> R.string.everyday_online_g_link
    NetGroup.NEWS -> R.string.everyday_online_g_news
    NetGroup.REPORTS -> R.string.everyday_online_g_reports
})

@Composable
fun OnlineScreen(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val langs = LocalLangs.current
    val lang = langs.say
    var tick by remember { mutableIntStateOf(0) }
    var s by remember { mutableStateOf(EverydaySettings()) }
    var view by remember { mutableStateOf<OnlineView?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<NetEntry?>(null) }
    LaunchedEffect(Unit) {
        // The main app's voice log: that the page opened, and whether its record is on.
        withContext(Dispatchers.IO) { FeedbackFiles.log(app, VoiceEvent.ONLINE_PAGE, if (EverydaySettingsStore.current(app).online) 1 else 0) }
    }
    LaunchedEffect(tick) {
        val (now, v) = withContext(Dispatchers.IO) {
            val settings = EverydaySettingsStore.current(app)
            NetLog.settleDownloads(app)
            NetLog.trim(app, settings.onlineDays)
            val (rows, cut) = NetLog.readKept(app)
            val android = NetLog.androidBytes()
            val boot = NetLog.bootTime()
            settings to OnlineView(rows, NetLog.more(app), OnlineSummary.compare(android, rows.filter { it.time >= boot }), android != null, cut)
        }
        s = now
        view = v
    }
    fun change(f: (EverydaySettings) -> EverydaySettings) {
        // Shown at once; the file is written, then read back with the record.
        s = f(s)
        scope.launch {
            withContext(Dispatchers.IO) { EverydaySettingsStore.update(app, f) }
            tick++
        }
    }
    val title = EverydayStrings.online().str()
    val footer = stringResource(R.string.everyday_online_footer)
    PreferenceScreen(title, onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.everyday_online_intro)) } // l10n: not text (a list key)
        val v = view
        val today = LocalDate.now()
        val todays = v?.rows?.filter { NetFiles.dayOf(it.time) == today }.orEmpty()
        preferenceGroup(null, "online-top") { // l10n: not text (a list key)
            val header = OnlineSummary.header(todays, EverydayStrings.onlineTodayTitle()).str()
            val handed = OnlineSummary.handedLine(todays)?.str()
            val android = when {
                v == null -> null
                !v.androidGiven || v.compare == null -> EverydayStrings.onlineAndroidNone().str()
                else -> EverydayStrings.onlineAndroid("android" to Fmt.bytes(v.compare.android, lang), "robot" to Fmt.bytes(v.compare.robot, lang)).str() +
                    if (v.compare.tooMuch) "\n" + EverydayStrings.onlineAndroidMore().str() else ""
            }
            PreferenceItem(header, android)
            if (handed != null) PreferenceItem(handed) // M1: handed reports, a line of their own under the header
            if (!s.online) PreferenceItem(stringResource(R.string.everyday_online_is_off), null, RIcons.Info)
            if (v?.cut == true) PreferenceItem(stringResource(R.string.everyday_online_cut), null, RIcons.Info)
        }
        preferenceGroup(null, "online-settings") { // l10n: not text (a list key)
            SwitchPreference(stringResource(R.string.everyday_online), pluralStringResource(R.plurals.everyday_online_summary, s.onlineDays, s.onlineDays), s.online) { on ->
                change { it.copy(online = on) }
            }
            val choices = (listOf(7, 30, 90) + s.onlineDays).distinct().sorted()
            ListPreference(stringResource(R.string.everyday_online_days), s.onlineDays, choices, { n -> app.resources.getQuantityString(R.plurals.everyday_online_days_n, n, n) },
                enabled = s.online) { n -> change { it.copy(onlineDays = n) } }
            PreferenceItem(stringResource(R.string.everyday_online_delete), stringResource(R.string.everyday_online_delete_detail), RIcons.Delete,
                onClick = { confirmDelete = true })
        }
        val rows = v?.rows.orEmpty()
        val groups = rows.map { it.purpose.group }.distinct().sortedBy { it.ordinal }
        val shown = NetGroup.entries.firstOrNull { it.code == filter }?.takeIf { it in groups }
        if (groups.size > 1 || shown != null) preferenceGroup(null, "online-filter") { // l10n: not text (a list key)
            val all = stringResource(R.string.everyday_online_all)
            val names = groups.associateWith { groupName(it) }
            ListPreference(stringResource(R.string.everyday_online_filter), shown, listOf<NetGroup?>(null) + groups, { g -> g?.let { names.getValue(it) } ?: all }) { g ->
                filter = g?.code
            }
        }
        val kept = rows.filter { shown == null || it.purpose.group == shown }
        if (v != null && kept.isEmpty()) {
            item(key = "online-empty") { IntroPreference(app.resources.getQuantityString(R.plurals.everyday_online_empty, s.onlineDays, s.onlineDays)) } // l10n: not text (a list key)
        }
        val byDay = kept.groupBy { NetFiles.dayOf(it.time) }.toSortedMap(compareByDescending { it })
        for ((day, list) in byDay) {
            val dayTitle = when (day) {
                today -> EverydayStrings.onlineTodayTitle().render(langs)
                today.minusDays(1) -> app.getString(R.string.everyday_online_yesterday)
                else -> Fmt.date(day, lang)
            }
            val newest = list.asReversed().withIndex().toList()
            preferenceItems(dayTitle, "online-day-$day", newest, { "online-$day-${it.index}" }) { (_, e) -> OnlineRow(e, lang) { open = e } } // l10n: not text (a list key)
            val more = v?.more?.get(day)
            if (more != null && more.count > 0 && shown == null) item(key = "online-more-$day") { // l10n: not text (a list key)
                IntroPreference(EverydayStrings.onlineMoreDay(more.count).str())
            }
        }
        preferenceFooter(footer)
    }
    if (confirmDelete) {
        ConfirmDialog(stringResource(R.string.everyday_online_delete_title), stringResource(R.string.everyday_online_delete_text),
            stringResource(R.string.everyday_online_delete_confirm), onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            scope.launch {
                withContext(Dispatchers.IO) { NetLog.delete(app) }
                tick++
                snackbar.showSnackbar(app.getString(R.string.everyday_online_deleted))
            }
        }
    }
    open?.let { e -> OnlineDetails(e, lang, onDismiss = { open = null }) { open = null; a.openPage(onlinePage(e.purpose)) } }
}

/** "10:42 · Weather forecast" over "api.open-meteo.com · 0 B out · 14 KB in · You asked". */
@Composable
private fun OnlineRow(e: NetEntry, lang: Lang, onClick: () -> Unit) {
    val time = Fmt.clock(Instant.ofEpochMilli(e.time).atZone(NetLog.zone).toLocalTime(), lang)
    val parts = listOfNotNull(
        OnlineSummary.host(e.host).str(),
        if (e.result == NetResult.STARTED) null else EverydayStrings.onlineBytes("out" to Fmt.bytes(e.out, lang), "in" to Fmt.bytes(e.inBytes, lang)).str(),
        if (e.result == NetResult.DONE) null else OnlineSummary.result(e.result).str(),
        OnlineSummary.cause(e).str(),
    )
    PreferenceItem("$time · ${OnlineSummary.purpose(e.purpose).str()}", parts.joinToString(" · "), onClick = onClick)
}

/** Every field of a row (§5.2), and the Settings page that controls it, with a button to open it. */
@Composable
private fun OnlineDetails(e: NetEntry, lang: Lang, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val at = Instant.ofEpochMilli(e.time).atZone(NetLog.zone)
    val lines = listOfNotNull(
        stringResource(R.string.everyday_online_d_when) to Fmt.date(at.toLocalDate(), lang) + " · " + Fmt.clock(at.toLocalTime(), lang),
        stringResource(R.string.everyday_online_d_for) to OnlineSummary.purpose(e.purpose).str(),
        stringResource(R.string.everyday_online_d_server) to OnlineSummary.host(e.host).str(),
        stringResource(R.string.everyday_online_d_cause) to OnlineSummary.cause(e).str(),
        stringResource(R.string.everyday_online_d_sent) to Fmt.bytes(e.out, lang),
        stringResource(R.string.everyday_online_d_received) to Fmt.bytes(e.inBytes, lang),
        if (e.planned > 0) stringResource(R.string.everyday_online_d_planned) to Fmt.bytes(e.planned, lang) else null,
        stringResource(R.string.everyday_online_d_ended) to OnlineSummary.result(e.result).str(),
        stringResource(R.string.everyday_online_d_part) to stringResource(when (e.process) { "voice" -> R.string.everyday_online_p_voice; "link" -> R.string.everyday_online_p_link; else -> R.string.everyday_online_p_main }),
    )
    val settings = OnlineSummary.settings(e.purpose).render(lang)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OnlineSummary.purpose(e.purpose).str()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, value) in lines) Column {
                    Text(k, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.everyday_online_d_settings, settings), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.everyday_online_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.everyday_online_close)) } },
    )
}
