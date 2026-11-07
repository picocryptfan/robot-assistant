// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import app.heyrobot.core.HomeSettings
import app.heyrobot.core.online.NetCause
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today H7: feeds, how news arrives, the fail-closed Tor rule, and the headlines kept and shown. */
class NewsTest {
    private val now = 1_762_000_000_000L
    /** A zone where [now] is 6:00, inside the daily window. */
    private val morning = java.time.ZoneOffset.ofHoursMinutesSeconds(-6, -26, -40)
    private val hour = 3_600_000L
    private val one = NewsFeed("https://a.example.org/feed", "A")
    private val two = NewsFeed("https://b.example.org/rss", "B")
    private val daily = NewsSettings(listOf(one))

    @Test fun noFeedsToStartAndOnceADayByDefault() {
        val s = HomeSettings()
        assertFalse(s.news.on)
        assertEquals(NewsMode.DAILY, s.news.mode)
        assertEquals(NewsVia.DIRECT, s.news.via)
        // With no feeds nothing is ever fetched, whatever asks.
        for (m in NewsMoment.entries) assertEquals(NewsGo.Wait, NewsPolicy.decide(m, s.news, null, now, unmetered = true, saver = false, torUp = true))
    }

    @Test fun theFeedsComeBackFromTheFileAndBadOnesDont() {
        val s = HomeSettings(news = NewsSettings(listOf(one, two), NewsMode.OPEN, NewsVia.TOR))
        assertEquals(s.news, HomeSettings.fromJson(s.toJson()).news)
        val o = JSONObject(s.toJson())
        o.getJSONObject("news").getJSONArray("feeds").put(JSONObject().put("url", "http://8.8.8.8/").put("name", "x"))
            .put(JSONObject().put("url", "javascript:alert(1)").put("name", "y"))
        assertEquals(listOf(one, two), HomeSettings.fromJson(o.toString()).news.feeds)
        // Five at most.
        var n: NewsSettings? = NewsSettings()
        for (i in 1..5) n = n!!.added(NewsFeed("https://f$i.example.org/", "F$i"))
        assertNull(n!!.added(NewsFeed("https://f6.example.org/", "F6")))
        assertNull(n.added(NewsFeed("https://f1.example.org/", "again")))
    }

    @Test fun eachModeFetchesOnlyWhenTheDesignSays() {
        fun go(m: NewsMoment, s: NewsSettings, last: Long?, wifi: Boolean = true, saver: Boolean = false) = NewsPolicy.decide(m, s, last, now, wifi, saver, torUp = false, zone = morning)
        // Once a day (the default): the job on Wi-Fi out of Battery Saver, once a morning; an open only to catch up a missed one.
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, daily, now - hour / 4))
        assertEquals(NewsGo.Go(NetCause.SWITCH), go(NewsMoment.OPENED, daily, now - 21 * hour))
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, daily, now - 21 * hour, wifi = false))
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, daily, now - 21 * hour, saver = true))
        assertEquals(NewsGo.Go(NetCause.SWITCH), go(NewsMoment.DAILY, daily, now - 21 * hour))
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, daily, now - hour / 4))
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, daily, null, wifi = false))
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, daily, null, saver = true))
        // When I open Today: at most once an hour, and the daily job doesn't run.
        val open = daily.copy(mode = NewsMode.OPEN)
        assertEquals(NewsGo.Go(NetCause.SWITCH), go(NewsMoment.OPENED, open, now - hour))
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, open, now - hour / 2))
        // fix-today-review 2: "Use mobile data for news" is off by default, and then nothing automatic goes on mobile data
        // (a tap still does); on, the open and the daily job may.
        assertEquals(false, NewsSettings().mobileData)
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, open, now - hour, wifi = false))
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, daily, now - 21 * hour, wifi = false))
        assertEquals(NewsGo.Go(NetCause.TAP), go(NewsMoment.TAP, open, now, wifi = false))
        assertEquals(NewsGo.Go(NetCause.SWITCH), go(NewsMoment.OPENED, open.copy(mobileData = true), now - hour, wifi = false))
        assertEquals(NewsGo.Go(NetCause.SWITCH), go(NewsMoment.DAILY, daily.copy(mobileData = true), now - 21 * hour, wifi = false))
        assertEquals(true, NewsSettings.of(daily.copy(mobileData = true).toJson()).mobileData)
        assertEquals(false, NewsSettings.of(daily.toJson().apply { remove("mobileData") }).mobileData)
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, open, null))
        // Only when I ask: a tap, nothing by itself.
        val asked = daily.copy(mode = NewsMode.ASKED)
        assertEquals(NewsGo.Wait, go(NewsMoment.OPENED, asked, null))
        assertEquals(NewsGo.Wait, go(NewsMoment.DAILY, asked, null))
        for (s in listOf(daily, open, asked)) assertEquals(NewsGo.Go(NetCause.TAP), go(NewsMoment.TAP, s, now))
    }

    /** today-h7-fix 3b, fix-today-review 1: the daily fetch goes from 5:30 local; a job Android ran late catches the morning up. */
    @Test fun theDailyFetchGoesFrom530AndCatchesUpAMissedMorning() {
        val hour = 3_600_000L
        fun at(h: Int, m: Int) = java.time.ZoneOffset.ofTotalSeconds(-((12 - h) * 3600 + (26 - m) * 60 + 40))
        assertEquals(NewsGo.Go(NetCause.SWITCH), NewsPolicy.decide(NewsMoment.DAILY, daily, now - 21 * hour, now, true, false, true, zone = at(5, 30)))
        assertEquals(NewsGo.Go(NetCause.SWITCH), NewsPolicy.decide(NewsMoment.DAILY, daily, null, now, true, false, true, zone = at(6, 59)))
        for (z in listOf(at(5, 29), at(0, 0)))
            assertEquals("$z", NewsGo.Wait, NewsPolicy.decide(NewsMoment.DAILY, daily, null, now, true, false, true, zone = z))
        // Late (Wi-Fi back at 9:00, or the evening): yesterday's run is caught up once; a run this morning holds it to tomorrow.
        for (z in listOf(at(7, 0), at(9, 0), at(19, 0)))
            assertEquals("$z", NewsGo.Go(NetCause.SWITCH), NewsPolicy.decide(NewsMoment.DAILY, daily, now - 23 * hour, now, true, false, true, zone = z))
        assertEquals(NewsGo.Wait, NewsPolicy.decide(NewsMoment.DAILY, daily, now - 2 * hour, now, true, false, true, zone = at(9, 0)))
        // Before 5:30, a catch-up from yesterday waits for this morning's run, so two never go an hour apart.
        assertEquals(NewsGo.Wait, NewsPolicy.decide(NewsMoment.DAILY, daily, now - 47 * hour, now, true, false, true, zone = at(4, 0)))
        // A tap is never held to the window.
        assertEquals(NewsGo.Go(NetCause.TAP), NewsPolicy.decide(NewsMoment.TAP, daily, null, now, false, true, true, zone = at(19, 0)))
    }

    /** today-h7-fix 8 and 4: a stored link keeps its first date; validators keep the host that gave them, and go back only to it. */
    @Test fun aStoredLinkKeepsItsDateAndValidatorsTheirHost() {
        val url = "https://news.example.org/feed.xml"
        val first = StoredNews().merged(url, ParsedFeed("Example", listOf(FeedItem("Undated", "https://news.example.org/a", now))), "\"e\"", null, now, "cdn.example.net")
        val later = first.merged(url, ParsedFeed("Example", listOf(FeedItem("Undated", "https://news.example.org/a", now + 86_400_000))), null, null, now + 86_400_000)
        assertEquals(now, later.feeds[url]!!.items.single().at)
        val st = first.feeds[url]!!
        assertEquals("cdn.example.net", st.from)
        assertEquals(null to null, st.validatorsFor(url))
        assertEquals("\"e\"" to null, st.validatorsFor("https://cdn.example.net/feed.xml"))
        val back = StoredNews.fromJson(first.copy(daily = now).toJson())
        assertEquals("cdn.example.net", back.feeds[url]!!.from)
        assertEquals(now, back.daily)
        assertNull(StoredNews.fromJson(first.toJson().replace("cdn.example.net", "a b")).feeds[url]!!.from)
    }

    @Test fun withTorChosenAndDownNothingIsFetched() {
        val tor = daily.copy(via = NewsVia.TOR)
        for (m in NewsMoment.entries) {
            val s = if (m == NewsMoment.OPENED) tor.copy(mode = NewsMode.OPEN) else tor
            assertEquals(m.name, NewsGo.Refused(NewsRefusal.TOR_DOWN), NewsPolicy.decide(m, s, null, now, unmetered = true, saver = false, torUp = false, zone = morning))
            assertEquals(m.name, true, NewsPolicy.decide(m, s, null, now, unmetered = true, saver = false, torUp = true, zone = morning) is NewsGo.Go)
        }
        // Not due is still not due: the refusal is only ever instead of a fetch.
        assertEquals(NewsGo.Wait, NewsPolicy.decide(NewsMoment.OPENED, tor, now - hour / 4, now, true, false, false, zone = morning))
    }

    @Test fun theDailyJobStartsAtTheNext530() {
        val z = java.time.ZoneId.of("America/Halifax")
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
        assertEquals(at("2026-11-03T05:30"), NewsPolicy.nextDaily(at("2026-11-03T01:00"), z))
        assertEquals(at("2026-11-04T05:30"), NewsPolicy.nextDaily(at("2026-11-03T05:30"), z))
        assertEquals(at("2026-11-04T05:30"), NewsPolicy.nextDaily(at("2026-11-03T22:00"), z))
    }

    private fun items(prefix: String, n: Int, newest: Long) = (0 until n).map { FeedItem("$prefix $it", "https://$prefix.example.org/$it", newest - it * hour) }

    @Test fun theNewest20OfEachForAWeekAndThreeShownAtMostTwoFromOne() {
        val s = NewsSettings(listOf(one, two))
        var st = StoredNews().merged(one.url, ParsedFeed("A", items("a", 30, now)), "\"v1\"", "Sat, 01 Nov 2025 10:00:00 GMT", now)
        assertEquals(StoredNews.KEEP_ITEMS, st.feeds.getValue(one.url).items.size)
        st = st.merged(two.url, ParsedFeed("B", items("b", 3, now - 10 * hour)), null, null, now)
        assertEquals(listOf("a 0", "a 1", "b 0"), st.pick(s, now).map { it.title })
        // A 304 keeps the items; a week later they're gone, and a feed you removed isn't shown.
        st = st.merged(one.url, null, "\"v1\"", null, now + hour)
        assertEquals(20, st.feeds.getValue(one.url).items.size)
        assertEquals(emptyList<NewsHeadline>(), st.pick(s, now + StoredNews.KEEP_MS + 31 * hour))
        assertNull(st.updated(s, now + StoredNews.KEEP_MS + 2 * hour))
        // One feed left: still at most two from it.
        assertEquals(listOf("b 0", "b 1"), st.pick(s.removed(one.url), now).map { it.title })
        assertEquals(setOf(two.url), st.kept(s.removed(one.url)).feeds.keys)
        // The file round trip, and its validators never carry a line break into a header.
        val back = StoredNews.fromJson(st.toJson())
        assertEquals(st.feeds, back.feeds)
        assertNull(StoredNews.validator("v\r\nX-Evil: 1"))
        assertEquals(StoredNews(), StoredNews.fromJson("not json"))
    }
}
