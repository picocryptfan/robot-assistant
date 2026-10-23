// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.heyrobot.R
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.WebStrings
import app.heyrobot.core.web.CurrentInfo
import app.heyrobot.core.web.KeepSources
import app.heyrobot.core.web.ReadPages
import app.heyrobot.core.web.ReadThrough
import app.heyrobot.core.web.SafeSearch
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.SearxngWire
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebConsent
import app.heyrobot.core.web.WebConsentAnswer
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.web.WebSetup
import app.heyrobot.core.web.WebUserAgent
import app.heyrobot.core.web.WikipediaWire
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebCounts
import app.heyrobot.sys.WebKeyFiles
import app.heyrobot.sys.WebStore
import app.heyrobot.sys.WebTest
import app.heyrobot.sys.WebWindow
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioDialog
import app.heyrobot.ui.pref.SliderPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// MAIN PROCESS. Settings › Web search (0.9, design A4.1 and A5): Off until set up. Set up picks one search service (your
// own SearXNG first when you have a home server; Google, Bing, Startpage and DuckDuckGo have no full API Robot can use),
// its address or its key (pasted in the set-up window in the assistant's process, which also runs Test: the main process
// never loads the HTTP client or reads a key), then the consent screen ("sites see the phone's IP"). Then every behaviour
// is a row with its default. 1.0: then every service and every engine with its privacy level, grouped "Robot reads the
// results" (a tap sets one up; a public SearXNG from a short list) and "Opens in your browser" (a tap makes one the default
// for browser_search; SearchEngines.kt). The settings live in web.json (sys WebStore), not in settings.json. 1.1: the page's
// words are in strings_web.xml, with their French; the choices' names and the row's summary are core's (WebStrings).

/** What the page shows besides the settings: the service's saved key (masked), today's searches, the last Test, the first search card's answer. */
data class WebFacts(
    val masked: String? = null,
    val today: Int = 0,
    val test: WebTest? = null,
    val answer: WebConsentAnswer? = null,
    /** A helper is a server on your network: your own SearXNG is suggested first. */
    val homeServer: Boolean = false,
    /** Your Home for weather's name, for "Add my town". */
    val home: String? = null,
)

/** "On · your SearXNG", "Off", "Not set up": Settings' row. */
fun webSummaryText(web: WebSettings, answer: WebConsentAnswer?): Phrase {
    val service = web.service ?: return WebStrings.summaryNotSetUp()
    return (if (WebConsent.on(web, answer)) WebStrings.summaryOn else WebStrings.summaryOff)("service" to service.labelText)
}


/** The route: the settings and facts from the files, the set-up window in the assistant's process. */
@Composable
fun WebSearchRoute(s: RobotSettings, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val web = WebStore.current(ctx)
    var refresh by remember { mutableIntStateOf(0) }
    var facts by remember { mutableStateOf(WebFacts()) }
    LaunchedEffect(web.service, refresh) {
        facts = withContext(Dispatchers.IO) {
            WebFacts(web.service?.let { WebKeyFiles.masked(ctx, it) }, WebCounts.today(ctx), WebTest.load(ctx), WebAnswers.load(ctx),
                homeServer = s.helpers.any { it.home }, home = s.weatherHome?.name)
        }
    }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    WebSearchSettingsScreen(
        web, facts, onChange = { WebStore.save(ctx, it) },
        window = { mode ->
            // By name: the main process never refers to the assistant's classes; once the settings it reads are on disk.
            SettingsStore.afterSaves {
                scope.launch(Dispatchers.Main) {
                    try {
                        launcher.launch(Intent().setClassName(ctx.packageName, WebWindow.CLASS).putExtra(WebWindow.EXTRA_MODE, mode))
                    } catch (_: Exception) {}
                }
            }
        },
        deleteKey = { service ->
            Thread { WebKeyFiles.delete(ctx, service); refresh++ }.start()
        },
        lockScreen = ToolName.WEB_SEARCH in s.lockedTools,
        openSafety = { a.openPage(Page.Safety) },
        showCost = s.helperShowCost,
        snackbar = snackbar, onBack = onBack,
    )
}

@Composable
fun WebSearchSettingsScreen(
    web: WebSettings,
    facts: WebFacts,
    onChange: (WebSettings) -> Unit,
    window: (String) -> Unit,
    deleteKey: (SearchService) -> Unit = {},
    lockScreen: Boolean = false,
    openSafety: () -> Unit = {},
    showCost: Boolean = true,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit,
) {
    val service = web.service
    val on = WebConsent.on(web, facts.answer)
    var picking by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf<String?>(null) }
    var consent by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // 1.0: a public SearXNG from set-up's short list, or "Other address".
    var publicPick by rememberSaveable { mutableStateOf(false) }
    val lines = SearchService.entries.associateWith { stringResource(serviceLine(it)) }
    val readsTitle = stringResource(R.string.web_reads_group)
    val readsIntro = stringResource(R.string.web_reads_intro)
    val browserTitle = stringResource(R.string.web_browser_group)
    val browserIntro = stringResource(R.string.web_browser_intro)
    val browserHow = stringResource(R.string.web_browser_how)
    val levels = stringResource(R.string.web_levels)
    val offBrowser = stringResource(R.string.web_off_browser)
    val ctx = LocalContext.current
    val searchWith = stringResource(R.string.web_search_with)
    val asking = stringResource(R.string.web_asking)
    val answers = stringResource(R.string.web_answers)
    val helpersHistory = stringResource(R.string.web_helpers_history)
    val suggested = WebSetup.suggested(facts.homeServer)
    val titles = SearchService.entries.associateWith { it.titleText.str() }
    val pickLabels = SearchService.entries.associateWith { if (it == suggested) stringResource(R.string.web_suggested, titles.getValue(it)) else titles.getValue(it) }
    val askLabels = WebAsk.entries.associateWith { it.label().str() }
    val askDetails = WebAsk.entries.associateWith { stringResource(askDetail(it)) }
    val readLabels = ReadPages.entries.associateWith { it.label().str() }
    val readDetails = ReadPages.entries.associateWith { stringResource(readDetail(it)) }
    val throughLabels = ReadThrough.entries.associateWith { it.label().str() }
    val throughDetails = ReadThrough.entries.associateWith { stringResource(throughDetail(it)) }
    val safeLabels = SafeSearch.entries.associateWith { it.label().str() }
    val currentLabels = CurrentInfo.entries.associateWith { it.label().str() }
    val currentDetails = CurrentInfo.entries.associateWith { stringResource(currentDetail(it)) }
    val keepLabels = KeepSources.entries.associateWith { it.label().str() }
    val keepDetails = KeepSources.entries.associateWith { stringResource(keepDetail(it)) }
    // The footer: what leaves the phone (or that it's off), what a search costs, and where answers come from; then the engines.
    val first = listOfNotNull(
        service?.let { WebSetup.consentText(it, web).str() } ?: stringResource(R.string.web_off_until),
        offBrowser.takeIf { service == null },
        service?.let { stringResource(if (showCost) R.string.web_each_search_card else R.string.web_each_search, it.costText.str()) },
        stringResource(R.string.web_answers_footer),
    ).joinToString(" ")
    val footer = listOf(first, WebSetup.notOffered.str(), browserHow, levels).joinToString("\n\n")

    /** After the consent: on, with a key or Test next. */
    fun turnOn(w: WebSettings) {
        val now = System.currentTimeMillis()
        onChange(w.copy(on = true, askAt = maxOf(w.askAt, now)))
        val s = w.service ?: return
        window(if (s.needsKey && facts.masked == null) WebWindow.MODE_KEY else WebWindow.MODE_TEST)
    }

    fun picked(s: SearchService) {
        picking = false
        val w = web.copy(service = s, on = false)
        onChange(w)
        when {
            s == SearchService.SEARXNG_HOME -> address = s.name
            s == SearchService.SEARXNG_PUBLIC -> publicPick = true
            else -> consent = s.name
        }
    }

    /** A tap in the "Robot reads the results" list: another service is set up; the one in use keeps its set-up (its address). */
    fun choose(s: SearchService) {
        if (s != service) return picked(s)
        when {
            s == SearchService.SEARXNG_PUBLIC -> publicPick = true
            s.needsAddress -> address = s.name
            !on -> consent = s.name
        }
    }

    PreferenceScreen(UiStrings.webSearch().str(), onBack, snackbar = snackbar) {
        item(key = "main") {
            MainSwitchBar(stringResource(R.string.web_use), on, enabled = web.copy(on = true).ready || on) { want ->
                if (want && !web.on) consent = service?.name else onChange(web.copy(on = want, askAt = System.currentTimeMillis()))
            }
        }
        preferenceGroup(searchWith, "service") {
            PreferenceItem(stringResource(if (service == null) R.string.web_set_up else R.string.web_service), service?.let { titles.getValue(it) }
                ?: stringResource(R.string.web_pick_service), RIcons.Search, onClick = { picking = true })
            if (service?.needsAddress == true) {
                val typed = (if (service == SearchService.SEARXNG_HOME) web.searxng else web.publicSearxng).orEmpty()
                PreferenceItem(stringResource(R.string.web_address), typed.ifEmpty { stringResource(R.string.web_not_set) }, RIcons.Home, onClick = { address = service.name })
            }
            if (service?.needsKey == true) {
                PreferenceItem(stringResource(R.string.web_api_key), facts.masked ?: stringResource(R.string.web_not_saved), RIcons.Lock, onClick = { window(WebWindow.MODE_KEY) })
                if (facts.masked != null) PreferenceItem(stringResource(R.string.web_delete_key), stringResource(R.string.web_delete_key_summary), RIcons.Delete,
                    onClick = { confirmDelete = true })
            }
            if (service != null) {
                val t = facts.test?.takeIf { it.service == service }
                PreferenceItem(stringResource(R.string.web_test), t?.line?.str() ?: stringResource(R.string.web_test_summary), RIcons.Refresh, enabled = web.ready || !on,
                    onClick = { window(WebWindow.MODE_TEST) })
            }
        }
        if (service != null) {
            preferenceGroup(asking, "asking") {
                ListPreference(stringResource(R.string.web_ask), WebConsent.ask(web, facts.answer), WebAsk.entries, { askLabels.getValue(it) }, { askDetails.getValue(it) },
                    RIcons.Shield, enabled = on) {
                    onChange(web.copy(ask = it, askAt = System.currentTimeMillis()))
                }
                PreferenceItem(stringResource(R.string.web_lock), stringResource(if (lockScreen) R.string.web_lock_on else R.string.web_lock_off), RIcons.Lock,
                    onClick = openSafety)
                SliderPreference(stringResource(R.string.web_daily), web.dailySearches.toFloat().coerceAtMost(DAILY_STEPS.last().toFloat()), DAILY_STEPS.first().toFloat()..DAILY_STEPS.last().toFloat(),
                    steps = 0, valueText = { ctx.getString(R.string.web_daily_value, facts.today, daily(it)) }, icon = RIcons.Timer, enabled = on) { onChange(web.copy(dailySearches = daily(it))) }
            }
            preferenceGroup(answers, "answers") {
                ListPreference(stringResource(R.string.web_read_pages), web.readPages, ReadPages.entries, { readLabels.getValue(it) }, { readDetails.getValue(it) }, RIcons.Note,
                    enabled = on) { onChange(web.copy(readPages = it)) }
                val through = service == SearchService.BRAVE || service == SearchService.KAGI
                ListPreference(stringResource(R.string.web_read_through), if (through) web.readThrough else ReadThrough.PHONE, ReadThrough.entries, { throughLabels.getValue(it) },
                    { throughDetails.getValue(it) }, RIcons.Phone, enabled = on && through) { onChange(web.copy(readThrough = it)) }
                SliderPreference(stringResource(R.string.web_pages), web.pages.toFloat(), 1f..WebSettings.MAX_PAGES.toFloat(), steps = WebSettings.MAX_PAGES - 2,
                    valueText = { "${it.roundToInt()}" }, icon = RIcons.Apps, enabled = on && web.readPages != ReadPages.NEVER) { onChange(web.copy(pages = it.roundToInt())) }
                ListPreference(stringResource(R.string.web_safe_search), web.safeSearch, SafeSearch.entries, { safeLabels.getValue(it) }, icon = RIcons.Shield, enabled = on) {
                    onChange(web.copy(safeSearch = it))
                }
                val wiki = WikipediaWire.allowed(WebUserAgent.CONTACT)
                SwitchPreference(stringResource(R.string.web_encyclopedia), stringResource(if (wiki) R.string.web_encyclopedia_on else R.string.web_encyclopedia_unavailable),
                    web.wikipedia(), enabled = on && wiki, icon = RIcons.Info) { onChange(web.copy(encyclopedia = it)) }
                SwitchPreference(stringResource(R.string.web_books), stringResource(R.string.web_books_summary), web.books, enabled = on, icon = RIcons.Note) {
                    onChange(web.copy(books = it))
                }
                SwitchPreference(stringResource(R.string.web_town), facts.home?.let { stringResource(R.string.web_town_summary, it.substringBefore(',')) }
                    ?: stringResource(R.string.web_town_no_home), web.addTown, enabled = on && facts.home != null, icon = RIcons.Place) { onChange(web.copy(addTown = it)) }
                if (service == SearchService.BRAVE) {
                    SwitchPreference(stringResource(R.string.web_powered_by_brave), stringResource(R.string.web_powered_by_brave_summary), web.poweredByBrave, enabled = on,
                        icon = RIcons.Info) {
                        onChange(web.copy(poweredByBrave = it))
                    }
                }
            }
            preferenceGroup(helpersHistory, "history") {
                ListPreference(stringResource(R.string.web_current_info), web.currentInfo, CurrentInfo.entries, { currentLabels.getValue(it) }, { currentDetails.getValue(it) },
                    RIcons.Cloud, enabled = on) {
                    onChange(web.copy(currentInfo = it))
                }
                ListPreference(stringResource(R.string.web_keep_sources), web.keepSources, KeepSources.entries, { keepLabels.getValue(it) }, { keepDetails.getValue(it) },
                    RIcons.History, enabled = on) {
                    onChange(web.copy(keepSources = it))
                }
            }
        }
        // 1.0: every service and every engine with its privacy level.
        readsResultsList(readsTitle, readsIntro, WebSetup.order(facts.homeServer).filter { it.available() || it == service }, service.takeIf { on }, ::choose)
        browserList(browserTitle, browserIntro, web.browserEngine) { onChange(web.copy(browserEngine = it)) }
        preferenceFooter(footer)
    }

    if (picking) {
        RadioDialog(searchWith, service ?: suggested, WebSetup.order(facts.homeServer).filter { it.available() || it == service },
            { pickLabels.getValue(it) }, { lines[it] }, onDismiss = { picking = false }, onPick = ::picked)
    }
    if (publicPick) {
        PublicSearxngDialog(web.publicSearxng, onDismiss = { publicPick = false }, onOther = { publicPick = false; address = SearchService.SEARXNG_PUBLIC.name }) { url ->
            publicPick = false
            val w = web.copy(service = SearchService.SEARXNG_PUBLIC, publicSearxng = url, on = false)
            onChange(w)
            consent = SearchService.SEARXNG_PUBLIC.name
        }
    }
    address?.let { name ->
        val s = SearchService.valueOf(name)
        val public = s == SearchService.SEARXNG_PUBLIC
        val invalid = stringResource(if (public) R.string.web_public_invalid else R.string.web_home_invalid)
        EditDialog(
            (if (public) WebStrings.publicTitle else WebStrings.homeCap)().str(), (if (public) web.publicSearxng else web.searxng).orEmpty(), stringResource(R.string.web_address),
            if (public) stringResource(R.string.web_public_help) else stringResource(R.string.web_home_help, "192.168.1.20:8888", "https://search.tail1234.ts.net"),
            validate = { t -> if (t.isBlank() || SearxngWire.base(t, public) != null) null else invalid },
            confirm = UiStrings.save().str(),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            canSave = { it.isNotBlank() },
            onDismiss = { address = null },
        ) { typed ->
            address = null
            val w = if (public) web.copy(service = s, publicSearxng = typed) else web.copy(service = s, searxng = typed)
            onChange(w)
            if (!w.on) consent = s.name
        }
    }
    consent?.let { name ->
        val s = SearchService.valueOf(name)
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text(stringResource(R.string.web_turn_on_title)) },
            text = { Text(WebSetup.consentText(s, web).str()) },
            confirmButton = { TextButton(onClick = { consent = null; turnOn(web.copy(service = s)) }) { Text(stringResource(R.string.web_turn_on)) } },
            dismissButton = { TextButton(onClick = { consent = null }) { Text(stringResource(R.string.web_not_now)) } },
        )
    }
    if (confirmDelete && service != null) {
        app.heyrobot.ui.pref.ConfirmDialog(stringResource(R.string.web_delete_key_title, titles.getValue(service)),
            stringResource(R.string.web_delete_key_text, service.labelText.str()), UiStrings.delete().str(),
            onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            deleteKey(service)
        }
    }
}

/** Daily searches: 10 to 1,000 on the slider, by tens. */
private val DAILY_STEPS = listOf(10, 1_000)

private fun daily(v: Float): Int = ((v / 10).roundToInt() * 10).coerceIn(DAILY_STEPS.first(), DAILY_STEPS.last())

private fun askDetail(a: WebAsk): Int = when (a) {
    WebAsk.FIRST_TIME -> R.string.web_ask_first_time
    WebAsk.ALWAYS -> R.string.web_ask_always
    WebAsk.NEVER -> R.string.web_ask_never
}

private fun readDetail(r: ReadPages): Int = when (r) {
    ReadPages.WHEN_NEEDED -> R.string.web_read_when_needed
    ReadPages.ALWAYS -> R.string.web_read_always
    ReadPages.NEVER -> R.string.web_read_never
}

private fun throughDetail(r: ReadThrough): Int = when (r) {
    ReadThrough.PHONE -> R.string.web_through_phone
    ReadThrough.SERVICE -> R.string.web_through_service
}

private fun currentDetail(c: CurrentInfo): Int = when (c) {
    CurrentInfo.ROBOT_FIRST -> R.string.web_robot_first
    CurrentInfo.HELPER_FIRST -> R.string.web_helper_first
}

private fun keepDetail(k: KeepSources): Int = when (k) {
    KeepSources.DOMAINS -> R.string.web_keep_domains
    KeepSources.LINKS -> R.string.web_keep_links
    KeepSources.NONE -> R.string.web_keep_none
}
