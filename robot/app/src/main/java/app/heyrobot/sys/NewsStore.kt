// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import app.heyrobot.core.RLog
import app.heyrobot.core.news.FeedAnswer
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.news.NewsSecret
import app.heyrobot.core.news.OwnFeed
import app.heyrobot.core.news.NewsGo
import app.heyrobot.core.news.NewsMoment
import app.heyrobot.core.news.NewsPolicy
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.core.news.NewsRules
import app.heyrobot.core.news.NewsSettings
import app.heyrobot.core.news.NewsVia
import app.heyrobot.core.news.ParsedFeed
import app.heyrobot.core.news.StoredNews
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.writeAtomic
import java.io.Closeable
import java.io.File

// Today H7 (home-design §6.1): your feeds' headlines, fetched when core NewsPolicy says so (your tap, an open of Today with
// "when I open Today", the daily job with "once a day"), kept in noBackupFilesDir: fetched headlines are never in Robot's
// backup or Android's. Every request goes through FeedClient, so What went online records each one. One feed failing
// keeps what it had. Blocking: never on the main thread. today-h7-fix 9: the file's lock is held only to read and write it,
// never across the network; one round at a time (another asking meanwhile is told Waited).
object NewsStore {
    private val FILE_LOCK = Any()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Bumped by [settled]: a round that ran across a settings change keeps only what the settings keep now. */
    @Volatile private var generation = 0L

    fun file(ctx: Context): File = File(ctx.noBackupFilesDir, StoredNews.FILE)

    fun current(ctx: Context): StoredNews = try {
        file(ctx).takeIf { it.isFile }?.let { StoredNews.fromJson(it.readText()) } ?: StoredNews()
    } catch (t: Throwable) {
        RLog.w("today_news_read", t)
        StoredNews()
    }

    /** One request: FeedClient's, replaced in tests. */
    fun interface Ask {
        fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply
    }

    /** today-h7-fix 7: a client that can read a page's first 512 KB (Find's page), past the cap a feed has. */
    fun interface Pages {
        fun page(url: String, cause: NetCause): FeedClient.Reply
    }

    /** How a round reads each feed's bytes: [FeedParseLink], in :feedparse. */
    fun interface Parse {
        fun read(bytes: ByteArray, base: String, now: Long): FeedAnswer?
    }

    /** Each round's reader (one :feedparse for the round, ended after it). The JVM tests set core's pure one. */
    @Volatile var parser: (Context) -> Parse = { FeedParseLink(it) }

    /**
     * The live client for a round: direct, or with Tor chosen through :link, its stream per host (FeedClient.Tor), never
     * direct. Holds one LinkClient for the round; closed after it, so :link sleeps again when nothing else uses it.
     */
    /** A client that goes only through Tor says first whether Tor can carry a request: null when it can, else why not. */
    fun interface ThroughTor {
        fun torWhy(): NewsRefusal?
    }

    class Live(private val ctx: Context, private val viaTor: Boolean) : Ask, Pages, ThroughTor, Closeable {
        private var link: LinkClient? = null
        private fun link(): LinkClient = link ?: LinkClient(ctx).also { link = it }
        // H8: a feed on your own network goes direct even with Tor chosen (Tor can't reach it; its lookup must give your network).
        private fun tor(url: String) = if (viaTor && !OwnFeed.own(url)) FeedClient.Tor { h, p -> link().feedStream(h, p) } else null
        override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply =
            FeedClient.fetch(ctx, url, cause, etag, modified, tor(url))
        override fun page(url: String, cause: NetCause): FeedClient.Reply = FeedClient.fetch(ctx, url, cause, null, null, tor(url), page = true)

        /** Tor chosen: null once it can carry a feed (:link starts it when it isn't, bounded), else why not. */
        override fun torWhy(): NewsRefusal? = when {
            link().status()?.on != true -> NewsRefusal.TOR_OFF
            else -> when (link().feedTor()) {
                app.heyrobot.core.link.LinkIpcProtocol.FEED_TOR_UP -> null
                app.heyrobot.core.link.LinkIpcProtocol.FEED_TOR_STARTING -> NewsRefusal.TOR_STARTING
                else -> NewsRefusal.TOR_DOWN
            }
        }

        override fun close() { link?.close(); link = null }
    }

    /** The live client; the JVM tests set their own ([asker]). */
    val LIVE: (Context, NewsSettings) -> Ask = { c, s -> Live(c, s.via == NewsVia.TOR) }
    @Volatile var asker: (Context, NewsSettings) -> Ask = LIVE

    fun client(ctx: Context, s: NewsSettings): Ask = asker(ctx, s)

    /** Tor chosen, before anything goes: [ask]'s answer when it's live; any other client is never through Tor, so refused. */
    private fun torWhy(ask: Ask): NewsRefusal? = if (ask is ThroughTor) ask.torWhy() else NewsRefusal.TOR_DOWN

    sealed class Fetched {
        /** Fetched: what's stored now, and how many feeds failed (shown as a small note in Settings, never on the page). */
        data class Done(val stored: StoredNews, val failed: Int) : Fetched()
        /** Not due: nothing went online. */
        object Waited : Fetched()
        /** Due, and refused (Tor chosen and down): nothing went online, and the line says why. */
        data class Refused(val why: NewsRefusal) : Fetched()
    }

    fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Throwable) {
        false
    }

    fun saver(ctx: Context): Boolean = try { ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true } catch (_: Throwable) { true }

    /** News for [moment], when [s] and the policy say it's due; Waited while another round runs. */
    fun fetch(ctx: Context, moment: NewsMoment, s: NewsSettings, ask: Ask = client(ctx, s), now: Long = System.currentTimeMillis(),
        unmetered: Boolean = moment != NewsMoment.TAP && unmetered(ctx), saver: Boolean = moment != NewsMoment.TAP && saver(ctx)): Fetched {
        if (!busy.compareAndSet(false, true)) { (ask as? Closeable)?.close(); return Fetched.Waited }
        return try { round(ctx, moment, s, ask, now, unmetered, saver) } finally { busy.set(false); (ask as? Closeable)?.close() }
    }

    private class Got(val url: String, val feed: ParsedFeed?, val etag: String?, val modified: String?, val from: String?)

    private fun write(ctx: Context, st: StoredNews) = try {
        file(ctx).parentFile?.mkdirs()
        writeAtomic(file(ctx), st.toJson())
    } catch (t: Throwable) {
        RLog.w("today_news_write", t)
    }

    private fun round(ctx: Context, moment: NewsMoment, s: NewsSettings, ask: Ask, now: Long, unmetered: Boolean, saver: Boolean): Fetched {
        val old = current(ctx)
        val gen = generation
        // Tor is asked about only once a fetch is due (asking may start it); core's rule then refuses while it can't carry one.
        // today-h7-fix 6: the daily job paces itself by its own last run, so a tap or an open never delays it.
        // fix-today-review 1: an open in "Once a day" is the daily run's catch-up, so it goes by (and sets) the daily clock.
        val last = if (NewsPolicy.daily(moment, s.mode)) old.daily else old.tried
        val cause = when (val d = NewsPolicy.decide(moment, s, last, now, unmetered, saver, torUp = true)) {
            NewsGo.Wait -> return Fetched.Waited
            is NewsGo.Refused -> return Fetched.Refused(d.why)
            is NewsGo.Go -> d.cause
        }
        val tor = s.via == NewsVia.TOR
        // H8: only when some feed needs Tor (your own network's go direct): with Tor down, the round is refused as before.
        if (tor && s.feeds.any { !OwnFeed.own(it.url) }) torWhy(ask)?.let { return Fetched.Refused(it) }
        val got = ArrayList<Got>()
        var failed = 0
        val p = parser(ctx)
        try {
            for (f in s.feeds) {
                try {
                    // today-h7-fix 4: validators only to the host that gave them, and never through Tor (an ETag can be a cookie).
                    val viaTor = tor && !OwnFeed.own(f.url)
                    val (e, m) = if (viaTor) null to null else old.feeds[f.url]?.validatorsFor(f.url) ?: (null to null)
                    // H8: an address that carries a token is read from its key file only here, just before its request.
                    val addr = NewsKeys.address(ctx, f) ?: throw FeedClient.Failure(NewsFail.NO_KEY)
                    val r = ask.get(addr, cause, e, m)
                    val from = app.heyrobot.core.news.FeedLinks.host(r.url)
                    got += if (r.code == 304) Got(f.url, null, r.etag.takeIf { !viaTor }, r.modified.takeIf { !viaTor }, from)
                    else Got(f.url, (p.read(r.body ?: ByteArray(0), NewsSecret.base(r.url), now) as? FeedAnswer.Feed)?.feed?.let { NewsSecret.cleaned(it, addr) } ?: throw FeedClient.Failure(NewsFail.NOT_A_FEED),
                        r.etag.takeIf { !viaTor }, r.modified.takeIf { !viaTor }, from)
                } catch (e: FeedClient.Failure) {
                    failed++
                } catch (t: Throwable) {
                    RLog.w("today_news_fetch", t)
                    failed++
                }
            }
        } finally {
            (p as? Closeable)?.close()
        }
        // Read again and merged under the lock: what changed meanwhile (an Add, a feed removed, Today turned off) holds.
        val st = synchronized(FILE_LOCK) {
            val keep = if (gen == generation) s else dailyNews(HomeSettingsStore.current(ctx))
            var st = current(ctx).kept(keep).copy(tried = now, daily = if (NewsPolicy.daily(moment, s.mode)) now else current(ctx).daily)
            for (g in got) if (keep.feeds.any { it.url == g.url }) st = st.merged(g.url, g.feed, g.etag, g.modified, now, g.from)
            if (keep.on) write(ctx, st) else file(ctx).delete()
            st
        }
        return Fetched.Done(st, failed)
    }

    /**
     * today-h7-fix 1 and 3a, on every change of home_settings.json (HomeSettingsStore.update, a restore included): a removed
     * feed's headlines are deleted at once, and with no feeds or Today off the file goes; the daily job follows (cancelled
     * while Today is off, not "once a day", or without feeds).
     */
    fun settled(ctx: Context, home: app.heyrobot.core.HomeSettings) {
        val s = dailyNews(home)
        generation++
        try {
            synchronized(FILE_LOCK) {
                if (!s.on) file(ctx).delete()
                else current(ctx).let { st -> st.kept(s).takeIf { it != st }?.let { write(ctx, it) } }
            }
            // H8: a removed feed's key goes with it (Today off keeps them: your feeds are still set).
            NewsKeys.prune(ctx, home.news.feeds)
        } catch (t: Throwable) {
            RLog.w("today_news_settled", t)
        }
        app.heyrobot.update.UpdateJobs.ensureNews(ctx, s)
    }

    /** 11: Add keeps what its preview read, so the page shows it at once (no validators: Find kept none). */
    fun added(ctx: Context, url: String, feed: ParsedFeed, now: Long = System.currentTimeMillis()) = try {
        synchronized(FILE_LOCK) {
            val st = current(ctx)
            if (url !in st.feeds && HomeSettingsStore.current(ctx).let { h -> h.today && h.news.feeds.any { it.url == url } })
                write(ctx, st.merged(url, feed, null, null, now))
        }
    } catch (t: Throwable) {
        RLog.w("today_news_added", t)
    }

    /** What Find gave: a feed to add (its address and what it says now), or why not. */
    sealed class Found {
        data class Feed(val url: String, val feed: ParsedFeed) : Found()
        data class Nothing(val why: NewsFail) : Found()
    }

    /**
     * Add feed's Find, on your tap: [text] is a feed's address or a site's. One request for it; when it's a page and not a
     * feed, one more for the feed its head names. Nothing is saved here: you see the name and three titles, then Add.
     */
    fun find(ctx: Context, text: String, via: NewsVia = HomeSettingsStore.current(ctx).news.via, ask: Ask = Live(ctx, via == NewsVia.TOR),
        now: Long = System.currentTimeMillis()): Found {
        val p = parser(ctx)
        return try {
            val address = NewsRules.address(text) ?: throw FeedClient.Failure(NewsFail.BAD_ADDRESS)
            // Q35: a token over plain http to anything but this phone is refused before any request.
            if (OwnFeed.tokenRefused(address)) throw FeedClient.Failure(NewsFail.TOKEN_OVER_HTTP)
            // Tor chosen: nothing goes while it can't carry a request; still starting is said as such (fix-today-tor-b).
            if (via == NewsVia.TOR && !OwnFeed.own(address)) torWhy(ask)?.let { throw FeedClient.Failure(when (it) { NewsRefusal.TOR_STARTING -> NewsFail.TOR_STARTING; NewsRefusal.TOR_OFF -> NewsFail.TOR_OFF; NewsRefusal.TOR_DOWN -> NewsFail.NO_TOR }) }
            // today-h7-fix 7: a page is read to its first 512 KB (its head, with the feed links, is at the top); a feed that
            // big is still too big, and said so.
            val r = if (ask is Pages) ask.page(address, NetCause.TAP) else ask.get(address, NetCause.TAP, null, null)
            val body = r.body ?: throw FeedClient.Failure(NewsFail.SERVER)
            when (val a = p.read(body, NewsSecret.base(r.url), now)) {
                is FeedAnswer.Feed -> if (r.cut) throw FeedClient.Failure(NewsFail.TOO_BIG) else Found.Feed(kept(r.url) ?: address, NewsSecret.cleaned(a.feed, address))
                is FeedAnswer.Links -> {
                    // 11: only a link Robot keeps as a feed's address (no IP address, say), checked before it's asked.
                    val link = a.links.firstOrNull()?.let { kept(it)?.takeIf { l -> OwnFeed.findLink(address, l) } ?: throw FeedClient.Failure(NewsFail.BAD_ADDRESS) } ?: throw FeedClient.Failure(NewsFail.NOT_A_FEED)
                    val f = ask.get(link, NetCause.TAP, null, null)
                    val feed = (p.read(f.body ?: throw FeedClient.Failure(NewsFail.SERVER), NewsSecret.base(f.url), now) as? FeedAnswer.Feed)?.feed ?: throw FeedClient.Failure(NewsFail.NOT_A_FEED)
                    Found.Feed(kept(f.url) ?: link, NewsSecret.cleaned(feed, link))
                }
                null -> throw FeedClient.Failure(if (r.cut) NewsFail.TOO_BIG else NewsFail.NOT_A_FEED)
            }
        } catch (e: FeedClient.Failure) {
            Found.Nothing(e.why)
        } catch (t: Throwable) {
            RLog.w("today_news_find", t)
            Found.Nothing(NewsFail.SERVER)
        } finally {
            (p as? Closeable)?.close()
            (ask as? Closeable)?.close()
        }
    }

    /** An address NewsSettings keeps as it is (NewsSettings.of), or null. */
    private fun kept(url: String): String? = url.takeIf { NewsRules.address(it) == it }

    /** H7b: with Today off nothing shows the news, so the daily job fetches none (and ensureNews lets the job go). */
    fun dailyNews(home: app.heyrobot.core.HomeSettings): NewsSettings = if (home.today) home.news else NewsSettings()

    /** The daily job (UpdateJobs.NEWS): once a day, on Wi-Fi, out of Battery Saver; it shows nothing and reads nothing of yours but the feed addresses. */
    fun daily(ctx: Context) {
        val s = dailyNews(HomeSettingsStore.current(ctx))
        fetch(ctx, NewsMoment.DAILY, s)
        app.heyrobot.update.UpdateJobs.ensureNews(ctx, s, again = true)
    }
}
