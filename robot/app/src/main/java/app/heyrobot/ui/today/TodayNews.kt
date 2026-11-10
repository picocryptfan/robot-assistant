// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.TodayPage
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.news.FeedLinks
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.news.NewsFeed
import app.heyrobot.core.news.NewsHeadline
import app.heyrobot.core.news.NewsMode
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.core.news.NewsVia
import app.heyrobot.core.news.NewsMoment
import app.heyrobot.core.news.NewsSecret
import app.heyrobot.core.news.NewsSettings
import app.heyrobot.core.news.OwnFeed
import app.heyrobot.core.news.StoredNews
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.NewsKeys
import app.heyrobot.sys.NewsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Today H7 (home-design §6.1): the news on the page, and Settings › Display's feeds. With no feeds there's nothing on the
// page and nothing that asks for one. Headlines open in your browser; Robot has no reader.

/** A known list of feeds to choose from (checked 3 Nov 2026: curated, by country and topic, kept up). */
const val FEED_LIST = "https://github.com/plenaryapp/awesome-rss-feeds" // l10n: not text (an address)

/** What the page shows of the news: the three headlines, when they came, and the last fetch's trouble. */
data class NewsView(val headlines: List<NewsHeadline>, val updated: Long?, val problem: Int? = null)

/** The page's news, read and fetched here: an open of Today fetches only with "when I open Today", at most once an hour. */
@Composable
fun TodayNews(home: HomeSettings, lang: Lang) {
    val news = home.news
    // H7b: with Today off the page isn't there, and nothing is fetched for it.
    if (!news.on || !home.today) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<NewsView?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun shown(st: StoredNews, problem: Int? = null) = NewsView(st.pick(news, System.currentTimeMillis()), st.updated(news, System.currentTimeMillis()), problem)
    fun fetch(moment: NewsMoment) {
        if (busy) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { NewsStore.fetch(ctx, moment, news) }
            busy = false
            view = when (r) {
                is NewsStore.Fetched.Done -> shown(r.stored, if (r.failed == news.feeds.size && moment == NewsMoment.TAP) R.string.today_news_failed else null)
                is NewsStore.Fetched.Refused -> shown(withContext(Dispatchers.IO) { NewsStore.current(ctx) }, refusedText(r.why))
                NewsStore.Fetched.Waited -> view
            }
        }
    }
    LaunchedEffect(news) {
        // today-h7-fix 10: Tor chosen (a restore, say) while Robot to Robot is off: the page says why, whatever the mode.
        view = withContext(Dispatchers.IO) { shown(NewsStore.current(ctx), if (torOff(ctx, news)) refusedText(NewsRefusal.TOR_OFF) else null) }
        fetch(NewsMoment.OPENED)
    }
    val v = view ?: return
    NewsPart(v, busy, lang, open = { link -> openLink(ctx, link) }, fetch = { fetch(NewsMoment.TAP) })
}

/** Tor chosen, and Robot to Robot (which runs it) off: nothing can be fetched, and Settings says why and offers Direct. */
internal fun torOff(ctx: Context, n: NewsSettings): Boolean =
    n.on && n.via == NewsVia.TOR && try { !app.heyrobot.sys.LinkSettingsStore.current(ctx).on } catch (_: Throwable) { true }

private fun openLink(ctx: Context, link: String) {
    // Only an http(s) link Robot cleaned; it opens in your browser, never inside Robot.
    val clean = FeedLinks.clean(link, null) ?: return
    app.heyrobot.ui.calendar.CalendarSource.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addCategory(Intent.CATEGORY_BROWSABLE))
}

/** "6:32" today, "Tuesday 6:32" another day. */
@Composable
private fun whenSaid(at: Long, lang: Lang): String {
    val zone = ZoneId.systemDefault()
    val time = TodayPage.time(at, zone, lang)
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    if (day == java.time.LocalDate.now(zone)) return time
    val name = DateTimeFormatter.ofPattern("EEEE", LocalConfiguration.current.locales[0]).format(day)
    return "$name $time"
}

/** The part itself, drawn from [v]: tested on its own. */
@Composable
fun NewsPart(v: NewsView, busy: Boolean, lang: Lang, open: (String) -> Unit, fetch: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.today_news_title), style = MaterialTheme.typography.titleMedium)
        if (v.headlines.isEmpty()) {
            Text(stringResource(R.string.today_news_not_yet), style = MaterialTheme.typography.bodyLarge)
        }
        for (h in v.headlines) {
            TextButton(onClick = { open(h.link) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(h.title, style = MaterialTheme.typography.bodyLarge)
                    Text(h.feed, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        v.problem?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        if (v.updated == null) {
            FilledTonalButton(onClick = fetch, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (busy) R.string.today_news_fetching else R.string.today_news_fetch))
            }
        } else {
            val updated = stringResource(R.string.today_news_updated, whenSaid(v.updated, lang))
            val again = stringResource(R.string.today_news_again, updated)
            TextButton(onClick = fetch, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = again }) {
                Text(if (busy) stringResource(R.string.today_news_fetching) else updated)
            }
        }
    }
}

@Composable
private fun modeName(m: NewsMode, mobileData: Boolean): String = stringResource(when (m) {
    // fix-126-review 4: with "Use mobile data for news" on, the daily fetch doesn't wait for Wi-Fi.
    NewsMode.DAILY -> if (mobileData) R.string.today_news_mode_daily_mobile else R.string.today_news_mode_daily
    NewsMode.OPEN -> R.string.today_news_mode_open
    NewsMode.ASKED -> R.string.today_news_mode_asked
})

/** Why nothing went, in plain words: Tor chosen, and it couldn't connect or Robot to Robot (which runs it) is off. */
internal fun refusedText(why: NewsRefusal): Int = when (why) {
    NewsRefusal.TOR_DOWN -> R.string.today_news_tor_down
    NewsRefusal.TOR_OFF -> R.string.today_news_tor_off
    NewsRefusal.TOR_STARTING -> R.string.today_news_tor_starting
}

/** Q35: the line under a feed, where it's added and where it's listed (OwnFeed.note). */
internal fun noteText(n: OwnFeed.Note): Int = when (n) {
    OwnFeed.Note.PLAIN_HTTP -> R.string.today_news_plain_http
    OwnFeed.Note.NOT_ENCRYPTED_ON_PHONE -> R.string.today_news_token_clear
}

internal fun failText(f: NewsFail): Int = when (f) {
    NewsFail.NO_TOR -> R.string.today_news_fail_tor
    NewsFail.TOR_STARTING -> R.string.today_news_tor_starting
    NewsFail.TOR_OFF -> R.string.today_news_tor_off
    NewsFail.OFF_NETWORK -> R.string.today_news_fail_off_network
    NewsFail.BAD_ADDRESS -> R.string.today_news_fail_address
    NewsFail.NOT_A_FEED -> R.string.today_news_fail_not_feed
    NewsFail.SERVER -> R.string.today_news_fail_server
    NewsFail.NO_INTERNET -> R.string.today_news_fail_internet
    NewsFail.NO_PERMISSION -> R.string.today_news_fail_permission
    NewsFail.TOO_BIG -> R.string.today_news_fail_big
    NewsFail.FULL -> R.string.today_news_fail_full
    NewsFail.NOT_HOME -> R.string.today_news_fail_not_home
    NewsFail.TOKEN_OVER_HTTP -> R.string.today_news_fail_token_http
    NewsFail.NO_KEY -> R.string.today_news_no_key
    NewsFail.UNSAVED -> R.string.today_news_unsaved
}

/** Settings › Display's row, while Today is on: your feeds and how they arrive. */
@Composable
fun TodayNewsSetting() {
    val ctx = LocalContext.current
    var home by remember { mutableStateOf(HomeSettingsStore.current(ctx)) }
    var editing by remember { mutableStateOf(false) }
    val saves = rememberCoroutineScope()
    val n = home.news
    val torOff = torOff(ctx, n)
    PreferenceItem(stringResource(R.string.today_news_setting),
        if (!n.on) stringResource(R.string.today_news_setting_off) else if (torOff) stringResource(R.string.today_news_tor_off)
        else stringResource(R.string.today_news_setting_on, n.feeds.size, modeName(n.mode, n.mobileData)),
        onClick = { editing = true })
    // HomeSettingsStore.update settles the news (NewsStore.settled): headlines and the daily job follow the change.
    if (editing) NewsDialog(n, onChange = { s ->
        home = home.copy(news = s)
        TodaySave.save(saves, ctx, { it.copy(news = s) }) { r -> r?.let { home = it } }
    }, onDone = { editing = false }, torOff = torOff)
}

/**
 * Your feeds: each with Remove; how they arrive (the card's three choices, with what each says about you); and Add feed:
 * how to get one, a known list, then paste a site's address and tap Find. Nothing is added until you see its name and
 * three titles and tap Add.
 */
@Composable
fun NewsDialog(n: NewsSettings, onChange: (NewsSettings) -> Unit, onDone: () -> Unit,
    find: suspend (String) -> NewsStore.Found = { _ -> NewsStore.Found.Nothing(NewsFail.SERVER) }, real: Boolean = true, torOff: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<NewsStore.Found?>(null) }
    var unsaved by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDone, title = { Text(stringResource(R.string.today_news_dialog_title)) },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.today_news_done)) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in n.feeds) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyLarge)
                        // H8: an address that carries a token is shown masked; with Tor chosen, one on your network says it goes direct.
                        // today-h8-fix 8: the masked note is read off the main thread; 7: with no key file (a restore), it says to add the feed again.
                        val masked by produceState<Pair<Boolean, String?>>(false to null, f.key) { if (real && f.key != null) value = true to withContext(Dispatchers.IO) { NewsKeys.masked(ctx, f) } }
                        if (f.key != null) Text(stringResource(R.string.today_news_secret, masked.second ?: FeedLinks.host(f.url).orEmpty()), style = MaterialTheme.typography.bodySmall)
                        if (f.key != null && masked.first && masked.second == null) Text(stringResource(R.string.today_news_no_key), style = MaterialTheme.typography.bodySmall)
                        // today-h8-fix 2, Q35: plain http is read from whatever device has that address; with a token, on this phone only, not encrypted.
                        OwnFeed.note(f.url, f.key != null)?.let { Text(stringResource(noteText(it)), style = MaterialTheme.typography.bodySmall) }
                        if (OwnFeed.route(f.url, n.via) == OwnFeed.Route.HOME) Text(stringResource(R.string.today_news_own_direct), style = MaterialTheme.typography.bodySmall)
                    }
                    // 11: TalkBack hears which feed ("Remove Example"), not "Remove" alone.
                    val label = stringResource(R.string.today_news_remove_feed, f.name)
                    TextButton(onClick = { onChange(n.removed(f.url)) }, Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.today_news_remove)) }
                }
                if (n.on) for (m in NewsMode.entries) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(m == n.mode, role = Role.RadioButton) { onChange(n.copy(mode = m)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(m == n.mode, onClick = null)
                    Column {
                        Text(modeName(m, n.mobileData))
                        if (m == NewsMode.OPEN) Text(stringResource(if (n.mobileData) R.string.today_news_mode_open_note_mobile else R.string.today_news_mode_open_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // fix-today-review 2: off by default; its note says which network the news Robot fetches by itself waits for.
                if (n.on) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(n.mobileData, role = Role.Switch) { onChange(n.copy(mobileData = it)) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.today_news_mobile_data))
                        Text(stringResource(if (n.mobileData) R.string.today_news_mobile_data_on else R.string.today_news_mobile_data_off), style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.material3.Switch(n.mobileData, onCheckedChange = null)
                }
                // H7b: how it's fetched, direct or only through Tor (fail closed), once there's a feed to fetch.
                if (n.on) {
                    Text(stringResource(R.string.today_news_via_title), style = MaterialTheme.typography.titleSmall)
                    if (torOff) Text(stringResource(R.string.today_news_tor_off), style = MaterialTheme.typography.bodyMedium)
                    for (v in NewsVia.entries) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(v == n.via, role = Role.RadioButton) { onChange(n.copy(via = v)) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(v == n.via, onClick = null)
                        Column {
                            Text(stringResource(if (v == NewsVia.TOR) R.string.today_news_via_tor else R.string.today_news_via_direct))
                            Text(stringResource(if (v == NewsVia.TOR) R.string.today_news_via_tor_note else R.string.today_news_via_direct_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(stringResource(R.string.today_news_how), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { openLink(ctx, FEED_LIST) }) { Text(stringResource(R.string.today_news_list)) }
                OutlinedTextField(text, { text = it; found = null; unsaved = false }, label = { Text(stringResource(R.string.today_news_address)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                FilledTonalButton(enabled = !busy && text.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        found = if (n.feeds.size >= NewsSettings.MAX_FEEDS) NewsStore.Found.Nothing(NewsFail.FULL)
                        else if (real) withContext(Dispatchers.IO) { NewsStore.find(ctx, text) } else find(text)
                        busy = false
                    }
                }) { Text(stringResource(if (busy) R.string.today_news_finding else R.string.today_news_find)) }
                when (val f = found) {
                    is NewsStore.Found.Nothing -> Text(stringResource(failText(f.why)), style = MaterialTheme.typography.bodyMedium)
                    is NewsStore.Found.Feed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(f.feed.name, style = MaterialTheme.typography.titleSmall)
                        for (i in f.feed.items.take(3)) Text(i.title, style = MaterialTheme.typography.bodyMedium)
                        OwnFeed.note(f.url)?.let { Text(stringResource(noteText(it)), style = MaterialTheme.typography.bodySmall) }
                        val hosts = (n.feeds.map { it.url } + f.url).mapNotNull(FeedLinks::host).distinct().joinToString(", ")
                        Text(stringResource(when (n.mode) {
                            NewsMode.DAILY -> if (n.mobileData) R.string.today_news_card_daily_mobile else R.string.today_news_card_daily
                            NewsMode.OPEN -> R.string.today_news_card_open
                            NewsMode.ASKED -> R.string.today_news_card_asked
                        }, hosts), style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                // H8: an address that carries a token goes into its key file; the settings keep its stand-in.
                                // today-h8-fix 8: off the main thread; the same real address twice is one feed; a failed save
                                // keeps the preview and says so.
                                val dup = if (real) withContext(Dispatchers.IO) { NewsKeys.has(ctx, n.feeds, f.url) } else n.feeds.any { it.url == f.url }
                                val feed = when {
                                    dup -> null
                                    real -> withContext(Dispatchers.IO) { NewsKeys.feed(ctx, f.url, f.feed.name) }
                                    else -> NewsFeed(f.url, f.feed.name).takeIf { !NewsSecret.carries(f.url) }
                                }
                                busy = false
                                if (!dup && feed == null) { unsaved = true; return@launch }
                                feed?.let { nf -> n.added(nf) }?.let(onChange)
                                // 11: what the preview read is kept, so the page has it at once.
                                if (real && feed != null) launch(Dispatchers.IO) { NewsStore.added(ctx, feed.url, f.feed) }
                                text = ""
                                found = null
                            }
                        }) { Text(stringResource(R.string.today_news_add)) }
                        if (unsaved) Text(stringResource(R.string.today_news_unsaved), style = MaterialTheme.typography.bodyMedium)
                    }
                    null -> {}
                }
            }
        })
}
