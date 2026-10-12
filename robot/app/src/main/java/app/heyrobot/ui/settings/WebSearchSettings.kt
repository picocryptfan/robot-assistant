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
// for browser_search; SearchEngines.kt). The settings live in web.json (sys WebStore), not in settings.json.

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
fun webSummary(web: WebSettings, answer: WebConsentAnswer?): String {
    val service = web.service ?: return "Not set up"
    return (if (WebConsent.on(web, answer)) "On · " else "Off · ") + service.label
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

    PreferenceScreen("Web search", onBack, snackbar = snackbar) {
        item(key = "main") {
            MainSwitchBar("Use web search", on, enabled = web.copy(on = true).ready || on) { want ->
                if (want && !web.on) consent = service?.name else onChange(web.copy(on = want, askAt = System.currentTimeMillis()))
            }
        }
        preferenceGroup("Search with", "service") {
            PreferenceItem(if (service == null) "Set up" else "Search service", service?.title ?: "Pick the service that searches for you", RIcons.Search,
                onClick = { picking = true })
            if (service?.needsAddress == true) {
                val typed = (if (service == SearchService.SEARXNG_HOME) web.searxng else web.publicSearxng).orEmpty()
                PreferenceItem("Address", typed.ifEmpty { "Not set" }, RIcons.Home, onClick = { address = service.name })
            }
            if (service?.needsKey == true) {
                PreferenceItem("API key", facts.masked ?: "Not saved", RIcons.Lock, onClick = { window(WebWindow.MODE_KEY) })
                if (facts.masked != null) PreferenceItem("Delete key", "Remove it from this phone", RIcons.Delete, onClick = { confirmDelete = true })
            }
            if (service != null) {
                val t = facts.test?.takeIf { it.service == service }
                PreferenceItem("Test", t?.line ?: "Sends the word “test” once and says what came back", RIcons.Refresh, enabled = web.ready || !on,
                    onClick = { window(WebWindow.MODE_TEST) })
            }
        }
        if (service != null) {
            preferenceGroup("Asking", "asking") {
                ListPreference("Ask before searching", WebConsent.ask(web, facts.answer), WebAsk.entries, { it.title }, ::askDetail, RIcons.Shield, enabled = on) {
                    onChange(web.copy(ask = it, askAt = System.currentTimeMillis()))
                }
                PreferenceItem("Works on the lock screen", if (lockScreen) "On (Safety & privacy)" else "Off · Safety & privacy decides", RIcons.Lock,
                    onClick = openSafety)
                SliderPreference("Daily searches", web.dailySearches.toFloat().coerceAtMost(DAILY_STEPS.last().toFloat()), DAILY_STEPS.first().toFloat()..DAILY_STEPS.last().toFloat(),
                    steps = 0, valueText = { "Today ${facts.today} of ${daily(it)}" }, icon = RIcons.Timer, enabled = on) { onChange(web.copy(dailySearches = daily(it))) }
            }
            preferenceGroup("Answers", "answers") {
                ListPreference("Read result pages", web.readPages, ReadPages.entries, { it.title }, ::readDetail, RIcons.Note, enabled = on) { onChange(web.copy(readPages = it)) }
                val through = service == SearchService.BRAVE || service == SearchService.KAGI
                ListPreference("Read pages through", if (through) web.readThrough else ReadThrough.PHONE, ReadThrough.entries, { it.title }, ::throughDetail, RIcons.Phone,
                    enabled = on && through) { onChange(web.copy(readThrough = it)) }
                SliderPreference("Pages per answer", web.pages.toFloat(), 1f..WebSettings.MAX_PAGES.toFloat(), steps = WebSettings.MAX_PAGES - 2,
                    valueText = { "${it.roundToInt()}" }, icon = RIcons.Apps, enabled = on && web.readPages != ReadPages.NEVER) { onChange(web.copy(pages = it.roundToInt())) }
                ListPreference("Safe search", web.safeSearch, SafeSearch.entries, { it.title }, icon = RIcons.Shield, enabled = on) { onChange(web.copy(safeSearch = it)) }
                val wiki = WikipediaWire.allowed(WebUserAgent.CONTACT)
                SwitchPreference("Encyclopedia lookups", if (wiki) "Who, what and where questions also ask Wikipedia" else "Wikipedia, not available yet: its rules need a contact address",
                    web.wikipedia(), enabled = on && wiki, icon = RIcons.Info) { onChange(web.copy(encyclopedia = it)) }
                SwitchPreference("Book lookups", "Questions about books also ask Open Library", web.books, enabled = on, icon = RIcons.Note) { onChange(web.copy(books = it)) }
                SwitchPreference("Add my town to local searches", facts.home?.let { "“near me” becomes your Home for weather, ${it.substringBefore(',')}" }
                    ?: "Set a Home for weather first", web.addTown, enabled = on && facts.home != null, icon = RIcons.Place) { onChange(web.copy(addTown = it)) }
                if (service == SearchService.BRAVE) {
                    SwitchPreference("“Powered by Brave” line", "On Brave's answers (optional in Brave's terms)", web.poweredByBrave, enabled = on, icon = RIcons.Info) {
                        onChange(web.copy(poweredByBrave = it))
                    }
                }
            }
            preferenceGroup("Helpers and history", "history") {
                ListPreference("Current information", web.currentInfo, CurrentInfo.entries, { it.title }, ::currentDetail, RIcons.Cloud, enabled = on) {
                    onChange(web.copy(currentInfo = it))
                }
                ListPreference("Keep sources in history", web.keepSources, KeepSources.entries, { it.title }, ::keepDetail, RIcons.History, enabled = on) {
                    onChange(web.copy(keepSources = it))
                }
            }
        }
        // 1.0: every service and every engine with its privacy level.
        readsResultsList(readsTitle, readsIntro, WebSetup.order(facts.homeServer).filter { it.available() || it == service }, service.takeIf { on }, ::choose)
        browserList(browserTitle, browserIntro, web.browserEngine) { onChange(web.copy(browserEngine = it)) }
        preferenceFooter(
            (service?.let { WebSetup.consent(it) + " " } ?: "Off until you set it up. $offBrowser ") +
                (service?.let { "Each search: ${it.cost}" + (if (showCost) " (the card shows it; it counts toward the helpers' daily budget)." else ".") + " " } ?: "") +
                "Answers come from Robot's own model, which reads the results and pages as information, never as instructions. History keeps " +
                "the answer and the sources' domains; titles, links and page text are never saved.\n\n" + WebSetup.NOT_OFFERED + "\n\n" + browserHow +
                "\n\n" + levels,
        )
    }

    if (picking) {
        RadioDialog("Search with", service ?: WebSetup.suggested(facts.homeServer), WebSetup.order(facts.homeServer).filter { it.available() || it == service },
            { it.title + if (it == WebSetup.suggested(facts.homeServer)) " (suggested)" else "" }, { lines[it] }, onDismiss = { picking = false }, onPick = ::picked)
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
        EditDialog(
            if (public) "A public SearXNG" else "Your SearXNG", (if (public) web.publicSearxng else web.searxng).orEmpty(), "Address",
            if (public) "https only. Its admin can see and keep your searches; many public servers refuse apps (JSON off)."
            else "Such as 192.168.1.20:8888, or https://search.tail1234.ts.net. http:// only on your home network.",
            validate = { t -> if (t.isBlank() || SearxngWire.base(t, public) != null) null else if (public) "An https address of a public server" else "An address on your network, or https" },
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
            title = { Text("Turn on web search?") },
            text = { Text(WebSetup.consent(s)) },
            confirmButton = { TextButton(onClick = { consent = null; turnOn(web.copy(service = s)) }) { Text("Turn on web search") } },
            dismissButton = { TextButton(onClick = { consent = null }) { Text("Not now") } },
        )
    }
    if (confirmDelete && service != null) {
        app.heyrobot.ui.pref.ConfirmDialog("Delete the ${service.title} key?", "Robot can't search with ${service.label} until you add a key again.", "Delete",
            onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            deleteKey(service)
        }
    }
}

/** Daily searches: 10 to 1,000 on the slider, by tens. */
private val DAILY_STEPS = listOf(10, 1_000)

private fun daily(v: Float): Int = ((v / 10).roundToInt() * 10).coerceIn(DAILY_STEPS.first(), DAILY_STEPS.last())

private fun askDetail(a: WebAsk): String = when (a) {
    WebAsk.FIRST_TIME -> "The default. The first search shows a card you answer with a tap"
    WebAsk.ALWAYS -> "A card before every search"
    WebAsk.NEVER -> "No card (Actions and When Robot asks first still apply)"
}

private fun readDetail(r: ReadPages): String = when (r) {
    ReadPages.WHEN_NEEDED -> "The default. For how-to, why, steps, comparing or details; snippets otherwise"
    ReadPages.ALWAYS -> "Every answer reads pages (slower)"
    ReadPages.NEVER -> "Only the results' snippets: no site sees the phone"
}

private fun throughDetail(r: ReadThrough): String = when (r) {
    ReadThrough.PHONE -> "The default. The sites see the phone's IP address"
    ReadThrough.SERVICE -> "Brave or Kagi read them for you: costs more, the sites don't see you"
}

private fun currentDetail(c: CurrentInfo): String = when (c) {
    CurrentInfo.ROBOT_FIRST -> "The default. News, scores and prices stay with Robot"
    CurrentInfo.HELPER_FIRST -> "Only when your helper can search the web itself"
}

private fun keepDetail(k: KeepSources): String = when (k) {
    KeepSources.DOMAINS -> "The default. “nhl.com, tsn.ca” under the answer"
    KeepSources.LINKS -> "The full addresses"
    KeepSources.NONE -> "Only that Robot searched"
}
