// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import app.heyrobot.core.online.NetCause
import org.json.JSONArray
import org.json.JSONObject

// Pure. Today H7 (home-design §6.1 and the user's answers of 3 Nov 2026): your news feeds, none to start; how they arrive
// (once a day by default, when you open Today, or only when you ask); through what (direct, or through Tor, failing
// closed); and the headlines kept, newest 20 of each feed for 7 days, three shown, at most two from one feed.

/** How news arrives. Once a day says less about you than a fetch at each open (which tells each server when you look). */
enum class NewsMode(val key: String) {
    DAILY("daily"), OPEN("open"), ASKED("asked");
    companion object { fun of(k: String?): NewsMode? = entries.firstOrNull { it.key == k } }
}

/** Through what. TOR: only through Tor, and nothing at all while it isn't connected. */
enum class NewsVia(val key: String) {
    DIRECT("direct"), TOR("tor");
    companion object { fun of(k: String?): NewsVia? = entries.firstOrNull { it.key == k } }
}

/**
 * A feed you added: its address and the name it gave itself. H8: one whose address carries a token ([NewsSecret]) has a
 * [key], and [url] is then only its stand-in (its server and the key's id, NewsSecret.standIn); the address itself is in
 * its key file (app NewsKeys), never here.
 */
data class NewsFeed(val url: String, val name: String, val key: String? = null) {
    override fun toString(): String = "NewsFeed(" + (FeedLinks.host(url) ?: "?") + (if (key != null) ", secret" else "") + ")" // l10n: not text (toString, for the logs)
}

/** In home_settings.json under "news" (additive: the file stays at v1). Off, with no feeds, until you add one. */
/** fix-today-review 2: [mobileData], "Use mobile data for news", off by default: off, every fetch Robot starts by itself waits for Wi-Fi. */
data class NewsSettings(val feeds: List<NewsFeed> = emptyList(), val mode: NewsMode = NewsMode.DAILY, val via: NewsVia = NewsVia.DIRECT,
    val mobileData: Boolean = false) {
    val on: Boolean get() = feeds.isNotEmpty()

    /** [f] added, or null: already there, or [MAX_FEEDS] already. */
    fun added(f: NewsFeed): NewsSettings? = if (feeds.size >= MAX_FEEDS || feeds.any { it.url == f.url }) null else copy(feeds = feeds + f)
    fun removed(url: String): NewsSettings = copy(feeds = feeds.filterNot { it.url == url })

    fun toJson(): JSONObject = JSONObject().put("mode", mode.key).put("via", via.key).put("mobileData", mobileData)
        .put("feeds", JSONArray(feeds.map { f -> JSONObject().put("url", f.url).put("name", f.name).also { o -> f.key?.let { o.put("key", it) } } }))

    companion object {
        const val MAX_FEEDS = 5

        /**
         * A feed whose address isn't one Robot would accept now, or past the fifth, is dropped. H8: so is an address that
         * carries a token kept as it is (only its stand-in and key are ever kept), and a stand-in whose key isn't one.
         */
        fun of(o: JSONObject?): NewsSettings {
            if (o == null) return NewsSettings()
            val a = o.optJSONArray("feeds")
            val feeds = (0 until minOf(a?.length() ?: 0, 16)).mapNotNull { i ->
                val f = a?.optJSONObject(i) ?: return@mapNotNull null
                val key = f.optString("key").takeIf { it.isNotEmpty() }
                val url = f.optString("url").takeIf { if (key != null) NewsSecret.isStandIn(it, key) else NewsRules.address(it) == it && !NewsSecret.carries(it) }
                    ?: return@mapNotNull null
                NewsFeed(url, FeedText.clean(f.optString("name"), FeedRead.NAME_MAX).ifEmpty { FeedLinks.host(url) ?: return@mapNotNull null }, key)
            }.distinctBy { it.url }.take(MAX_FEEDS)
            return NewsSettings(feeds, NewsMode.of(o.optString("mode")) ?: NewsMode.DAILY, NewsVia.of(o.optString("via")) ?: NewsVia.DIRECT,
                o.optBoolean("mobileData", false))
        }
    }
}

/** What asks for news: you opening Today, the daily job, or your tap (Add, "Updated 6:32"). */
enum class NewsMoment { OPENED, DAILY, TAP }

/**
 * H7b: "read me the news" is Robot's own only with Today on and a feed ([newsOn], read only then); otherwise it stays a
 * question for the rest of the panel (a web search, say), as before Today had news.
 */
object NewsAsk {
    fun routed(cmd: app.heyrobot.core.RoutineCommand, newsOn: () -> Boolean): app.heyrobot.core.RoutineCommand? =
        if (cmd == app.heyrobot.core.RoutineCommand.News && !newsOn()) null else cmd

    /** fix-today-tor: what a spoken ask says instead of the headlines when its fetch was refused for [why]; null: the headlines. */
    fun instead(why: NewsRefusal?): app.heyrobot.core.i18n.Phrase? =
        if (why == NewsRefusal.TOR_STARTING) app.heyrobot.core.i18n.RoutineStrings.newsTorStarting() else null

    /**
     * today-news-off: no headlines to say, and why, as the page would say it: no feeds; Tor chosen with Robot to Robot off
     * ([torOff], the page's rule, or this ask's fetch refused for it); this ask's fetch refused ([refused]: Tor starting or
     * down); every feed failed in this ask's fetch ([failedAll]); else none yet.
     */
    fun nothing(s: NewsSettings, torOff: Boolean, refused: NewsRefusal? = null, failedAll: Boolean = false): app.heyrobot.core.i18n.Phrase {
        val t = app.heyrobot.core.i18n.RoutineStrings
        return when {
            !s.on -> t.newsNoFeeds()
            torOff || refused == NewsRefusal.TOR_OFF -> t.newsTorOff()
            refused == NewsRefusal.TOR_STARTING -> t.newsTorStarting()
            refused == NewsRefusal.TOR_DOWN -> t.newsTorDown()
            failedAll -> t.newsFeedsFailed()
            else -> t.newsNothing()
        }
    }
}

/** Why a feed or a page you pasted gave nothing: the page shows it in words (strings_today.xml). */
/**
 * Why a feed failed. today-h8-fix 7: [NOT_HOME], plain http to an address that isn't on your network; [TOKEN_OVER_HTTP], an
 * address with a token over plain http to anything but this phone (OwnFeed.tokenRefused); [NO_KEY], a secret feed whose
 * key file isn't on this phone (a restored backup); [UNSAVED], Add couldn't keep a secret address in its key file.
 * merge-today-124: [TOR_OFF], Find with Tor chosen and Robot to Robot (which runs it) off: Tor won't start, so it says to turn it on.
 */
enum class NewsFail { NO_PERMISSION, NO_INTERNET, SERVER, TOO_BIG, NOT_A_FEED, BAD_ADDRESS, FULL, NO_TOR, NOT_HOME, TOKEN_OVER_HTTP, NO_KEY, UNSAVED, TOR_STARTING, TOR_OFF }

/** Why news wasn't fetched though it was due: Tor chosen and couldn't connect, or Tor chosen with Robot to Robot (which runs it) off. */
enum class NewsRefusal { TOR_DOWN, TOR_OFF, TOR_STARTING }

sealed class NewsGo {
    data class Go(val cause: NetCause) : NewsGo()
    object Wait : NewsGo()
    data class Refused(val why: NewsRefusal) : NewsGo()
}

object NewsPolicy {
    /** "When I open Today": at most once an hour (a guess). */
    const val OPEN_EVERY_MS = 3_600_000L
    /** The daily job's window is 90 minutes; a run less than this after the last daily run waits for tomorrow's. */
    const val DAILY_EVERY_MS = 20L * 3_600_000

    /**
     * home-design §6.1's table. [unmetered] and [saver]: the daily fetch is on Wi-Fi only and not in Battery Saver. [torUp]:
     * with Tor chosen and not connected, nothing goes and the line says why, never a quiet direct fetch. [last]: for DAILY,
     * the daily job's own last run (StoredNews.daily), so a tap or an open never delays the next morning's; otherwise the
     * last fetch of any kind (StoredNews.tried). today-h7-fix 3b: the daily fetch goes only in its morning window
     * ([DAILY_AT] to [DAILY_UNTIL], local time); a job Android ran later waits for tomorrow's.
     * fix-today-review 1: a morning missed (Wi-Fi off, Doze, Battery Saver) is caught up at the next chance: the job run
     * late once Wi-Fi is back, or Today opened on Wi-Fi in "Once a day" ([missed]); [last] is then the daily run's.
     * fix-today-review 2: nothing Robot starts by itself goes on a metered network unless [NewsSettings.mobileData]; a tap does.
     */
    fun decide(moment: NewsMoment, s: NewsSettings, last: Long?, now: Long, unmetered: Boolean, saver: Boolean, torUp: Boolean,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): NewsGo {
        if (!s.on) return NewsGo.Wait
        val cause = when (moment) {
            NewsMoment.TAP -> NetCause.TAP
            NewsMoment.OPENED -> NetCause.SWITCH.takeIf {
                when (s.mode) {
                    NewsMode.OPEN -> due(last, now, OPEN_EVERY_MS) && (unmetered || s.mobileData)
                    NewsMode.DAILY -> (unmetered || s.mobileData) && !saver && missed(last, now, zone)
                    else -> false
                }
            }
            NewsMoment.DAILY -> NetCause.SWITCH.takeIf { s.mode == NewsMode.DAILY && (unmetered || s.mobileData) && !saver && missed(last, now, zone) }
        } ?: return NewsGo.Wait
        if (s.via == NewsVia.TOR && !torUp) return NewsGo.Refused(NewsRefusal.TOR_DOWN)
        return NewsGo.Go(cause)
    }

    /** The daily job's start: 5:30, local time (home-design §6.1: about 5:30 to 7:00, as Android allows). */
    val DAILY_AT: java.time.LocalTime = java.time.LocalTime.of(5, 30)
    /** today-h7-fix: the window's end (PRIVACY.md item 12 says "between 5:30 and 7:00"). */
    val DAILY_UNTIL: java.time.LocalTime = java.time.LocalTime.of(7, 0)

    fun inWindow(now: Long, zone: java.time.ZoneId): Boolean =
        java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalTime().let { !it.isBefore(DAILY_AT) && it.isBefore(DAILY_UNTIL) }

    /**
     * fix-today-review 1: today's daily fetch is still owed: it's past [DAILY_AT] and the last daily run was before it (or
     * never, or the clock went back). Before [DAILY_AT] nothing is owed, so a late catch-up never doubles the morning's.
     */
    fun missed(last: Long?, now: Long, zone: java.time.ZoneId): Boolean {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
        if (t.toLocalTime().isBefore(DAILY_AT)) return false
        val since = t.toLocalDate().atTime(DAILY_AT).atZone(zone).toInstant().toEpochMilli()
        return last == null || now < last || last < since
    }

    /** The rounds paced by the daily job's own clock (StoredNews.daily): the job, and an open in "Once a day" (its catch-up). */
    fun daily(moment: NewsMoment, mode: NewsMode): Boolean = moment == NewsMoment.DAILY || (moment == NewsMoment.OPENED && mode == NewsMode.DAILY)

    /** The next [DAILY_AT] after [now] in [zone] (tomorrow's when it's past). */
    fun nextDaily(now: Long, zone: java.time.ZoneId): Long {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val today = t.toLocalDate().atTime(DAILY_AT).atZone(zone)
        return (if (today.toInstant().toEpochMilli() > now) today else today.plusDays(1).toLocalDate().atTime(DAILY_AT).atZone(zone)).toInstant().toEpochMilli()
    }

    /** A clock set back counts as due. */
    private fun due(last: Long?, now: Long, every: Long): Boolean = last == null || now < last || now - last >= every
}

/** One headline shown or said, with its feed's name. */
data class NewsHeadline(val title: String, val link: String, val feed: String, val at: Long)

/**
 * A feed's last answer: its items, when it was fetched, and its validators for If-None-Match and If-Modified-Since.
 * today-h7-fix 4: [from], the host that gave the validators; they go back to that host only (an ETag can be a cookie).
 */
data class FeedState(val name: String, val items: List<FeedItem>, val at: Long, val etag: String? = null, val modified: String? = null,
    val from: String? = null) {
    /** The validators to send for a request to [url]: only when its host gave them. */
    fun validatorsFor(url: String): Pair<String?, String?> =
        if (from != null && from == FeedLinks.host(url) && (etag != null || modified != null)) etag to modified else null to null
}

/**
 * The headlines fetched, in noBackupFilesDir/[FILE]: never in either backup. [tried]: the last fetch, done or not, so the
 * policy's pace holds when every feed fails. [daily]: the daily job's own last run (today-h7-fix 6).
 */
data class StoredNews(val feeds: Map<String, FeedState> = emptyMap(), val tried: Long? = null, val daily: Long? = null) {

    /**
     * [url]'s answer at [now]: [read] (null: 304, not modified, so the items stand), with its validators and [from], the host
     * that gave them. today-h7-fix 8: a link already stored keeps the date it was first given (an undated item is dated when
     * first seen, not at every fetch, so it doesn't stay on top).
     */
    fun merged(url: String, read: ParsedFeed?, etag: String?, modified: String?, now: Long, from: String? = null): StoredNews {
        val old = feeds[url]
        val had = old?.items?.associate { it.link to it.at }.orEmpty()
        val fresh = (read?.items ?: emptyList()).map { i -> had[i.link]?.let { i.copy(at = it) } ?: i }
        val items = (fresh + (old?.items ?: emptyList())).distinctBy { it.link }
            .filter { now - it.at <= KEEP_MS }.sortedByDescending { it.at }.take(KEEP_ITEMS)
        val name = read?.name ?: old?.name ?: FeedLinks.host(url) ?: url
        val e = validator(etag); val m = validator(modified)
        return copy(feeds = feeds + (url to FeedState(name, items, now, e, m, from.takeIf { e != null || m != null })))
    }

    /** Only the feeds still in [s]. */
    fun kept(s: NewsSettings): StoredNews = copy(feeds = feeds.filterKeys { k -> s.feeds.any { it.url == k } })

    /** The three to show: newest first, from your feeds, under 7 days old, at most [PER_FEED] from one feed. */
    fun pick(s: NewsSettings, now: Long, count: Int = SHOW): List<NewsHeadline> {
        val all = s.feeds.flatMap { f ->
            val st = feeds[f.url] ?: return@flatMap emptyList()
            st.items.filter { now - it.at in -FeedRead.AHEAD_MS..KEEP_MS }.map { NewsHeadline(it.title, it.link, f.name, it.at) }
        }.sortedByDescending { it.at }
        val per = HashMap<String, Int>()
        return all.filter { h -> (per.merge(h.feed, 1, Int::plus) ?: 0) <= PER_FEED }.take(count)
    }

    /** When your feeds last answered ("Updated Tuesday 6:32"), or null: none in the last 7 days. */
    fun updated(s: NewsSettings, now: Long): Long? = s.feeds.mapNotNull { feeds[it.url]?.at }.filter { now - it <= KEEP_MS }.maxOrNull()

    fun toJson(): String = JSONObject().put("v", 1).apply { if (tried != null) put("tried", tried); if (daily != null) put("daily", daily) }.put("feeds", JSONObject().apply {
        for ((url, f) in feeds) put(url, JSONObject().put("name", f.name).put("at", f.at).apply {
            f.etag?.let { put("etag", it) }; f.modified?.let { put("modified", it) }; f.from?.let { put("from", it) }
        }.put("items", JSONArray(f.items.map { JSONObject().put("t", it.title).put("l", it.link).put("at", it.at) })))
    }).toString()

    companion object {
        const val FILE = "today_news.json"
        const val KEEP_MS = 7L * 24 * 3_600_000
        const val KEEP_ITEMS = 20
        const val SHOW = 3
        const val PER_FEED = 2

        /** A validator goes back in a request header: printable ASCII only, and short. */
        fun validator(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.all { c -> c in ' '..'~' } }

        /** The file, read as strictly as a feed: whatever doesn't hold is dropped. */
        fun fromJson(text: String?): StoredNews = try {
            val o = JSONObject(text ?: "")
            val fs = o.optJSONObject("feeds")
            val feeds = LinkedHashMap<String, FeedState>()
            fs?.keys()?.asSequence()?.take(NewsSettings.MAX_FEEDS * 3)?.forEach { url ->
                val f = fs.optJSONObject(url) ?: return@forEach
                val a = f.optJSONArray("items")
                val items = (0 until minOf(a?.length() ?: 0, KEEP_ITEMS)).mapNotNull { i ->
                    val it = a?.optJSONObject(i) ?: return@mapNotNull null
                    val t = FeedText.clean(it.optString("t"), FeedRead.TITLE_MAX).takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val l = FeedLinks.clean(it.optString("l"), null) ?: return@mapNotNull null
                    FeedItem(t, l, it.optLong("at"))
                }
                feeds[url] = FeedState(FeedText.clean(f.optString("name"), FeedRead.NAME_MAX), items, f.optLong("at"),
                    validator(f.optString("etag").takeIf { f.has("etag") }), validator(f.optString("modified").takeIf { f.has("modified") }),
                    f.optString("from").takeIf { f.has("from") && it.length in 1..253 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' } })
            }
            StoredNews(feeds, if (o.has("tried")) o.optLong("tried") else null, if (o.has("daily")) o.optLong("daily") else null)
        } catch (_: Exception) {
            StoredNews()
        }
    }
}
