// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The spoken summary in both languages, the page's header, lookups against connections, Android's count and the report's tick. */
class OnlineSummaryTest {
    private val zone = ZoneId.of("America/Halifax")
    private val today = LocalDate.of(2026, 10, 23)

    private fun at(h: Int, m: Int, s: Int = 0, day: LocalDate = today) = ZonedDateTime.of(day.year, day.monthValue, day.dayOfMonth, h, m, s, 0, zone).toInstant().toEpochMilli()

    private fun row(t: Long, p: NetPurpose, host: String, cause: NetCause = NetCause.ASKED, out: Long = 100, inB: Long = 1000, r: NetResult = NetResult.DONE, planned: Long = 0) =
        NetEntry(t, "voice", p, host, cause, out, inB, r, planned)

    /** Today: the weather twice (one of them geocoder and forecast), a web search with two pages, the daily check, a download. */
    private val day = listOf(
        row(at(7, 0), NetPurpose.UPDATE_CHECK, "api.github.com", NetCause.SWITCH),
        row(at(8, 10, 0), NetPurpose.WEATHER_PLACE, "geocoding-api.open-meteo.com"),
        row(at(8, 10, 1), NetPurpose.WEATHER, "api.open-meteo.com"),
        row(at(9, 0), NetPurpose.WEB_SEARCH, "api.search.brave.com"),
        row(at(9, 0, 2), NetPurpose.WEB_PAGE, "example.org"),
        row(at(9, 0, 3), NetPurpose.WEB_PAGE, "example.com"),
        row(at(12, 0), NetPurpose.WEATHER, "api.open-meteo.com"),
        row(at(13, 0), NetPurpose.VOICE_DOWNLOAD, "huggingface.co", NetCause.TAP, 0, 0, NetResult.STARTED, 326_000_000),
        row(at(13, 4), NetPurpose.VOICE_DOWNLOAD, "huggingface.co", NetCause.TAP, 0, 326_000_000, NetResult.DONE),
        // Yesterday: left out of today's answer.
        row(at(20, 0, day = today.minusDays(1)), NetPurpose.HELPER, "api.anthropic.com"),
    )

    private fun say(p: app.heyrobot.core.i18n.Phrase, l: Lang) = p.render(Langs.of(l))

    @Test fun todayInWordsInBothLanguages() {
        val en = OnlineSummary.spoken(day, OnlineDay.TODAY, today, recording = true, locked = false, zone = zone)
        assertEquals("Robot went online 5 times today: the weather twice, web search, a download and the daily update check.", say(en, Lang.EN))
        assertEquals("Robot est allé en ligne 5 fois aujourd’hui : la météo deux fois, la recherche web, un téléchargement et la vérification quotidienne des mises à jour.",
            say(en, Lang.FR))
        val y = OnlineSummary.spoken(day, OnlineDay.YESTERDAY, today, recording = true, locked = false, zone = zone)
        assertEquals("Robot went online once yesterday: your helper.", say(y, Lang.EN))
        assertEquals("Robot est allé en ligne une fois hier : ton IA externe.", say(y, Lang.FR))
        val week = OnlineSummary.spoken(day, OnlineDay.WEEK, today, recording = true, locked = false, zone = zone)
        assertTrue(say(week, Lang.EN), say(week, Lang.EN).startsWith("Robot went online 6 times in the last 7 days: the weather twice"))
    }

    @Test fun noneOffAndLocked() {
        assertEquals("Robot hasn't gone online today.", say(OnlineSummary.spoken(emptyList(), OnlineDay.TODAY, today, true, false, zone), Lang.EN))
        assertEquals("Robot n’est pas allé en ligne aujourd’hui.", say(OnlineSummary.spoken(emptyList(), OnlineDay.TODAY, today, true, false, zone), Lang.FR))
        assertEquals("Robot isn't keeping a record of what went online. You can turn it on in Settings › Safety & privacy › What went online.",
            say(OnlineSummary.spoken(day, OnlineDay.TODAY, today, false, false, zone), Lang.EN))
        // Locked: only the unlock line, whatever the record holds (§5.6, §5.9).
        for (on in listOf(true, false)) {
            assertEquals("Unlock your phone to hear what went online.", say(OnlineSummary.spoken(day, OnlineDay.TODAY, today, on, true, zone), Lang.EN))
            assertEquals("Déverrouille ton téléphone pour savoir ce qui est allé en ligne.", say(OnlineSummary.spoken(day, OnlineDay.TODAY, today, on, true, zone), Lang.FR))
        }
    }

    @Test fun manyKindsNameThreeAndCountTheRest() {
        val many = listOf(NetPurpose.WEATHER, NetPurpose.NEAREST_PLACE, NetPurpose.WEB_SEARCH, NetPurpose.HELPER, NetPurpose.TERMUX_CHECK)
            .mapIndexed { i, p -> row(at(10 + i, 0), p, "x.example") }
        assertEquals("Robot went online 5 times today: the weather, the nearest place, web search and 2 other things.",
            say(OnlineSummary.spoken(many, OnlineDay.TODAY, today, true, false, zone), Lang.EN))
    }

    @Test fun everyConnectionIsInExactlyOneLookup() {
        val c = OnlineSummary.connections(day)
        val l = OnlineSummary.lookups(day)
        assertEquals(c.size, l.sumOf { it.rows.size })
        assertEquals(c.toSet(), l.flatMap { it.rows }.toSet())
        // A download's end is no connection of its own; the gap splits two weather questions hours apart.
        assertEquals(9, c.size)
        assertEquals(listOf(OnlineKind.DAILY_CHECK, OnlineKind.WEATHER, OnlineKind.WEB, OnlineKind.WEATHER, OnlineKind.DOWNLOAD, OnlineKind.HELPER).sorted(),
            l.map { it.kind }.sorted())
    }

    @Test fun theHeaderCountsConnectionsAndServers() {
        val todays = OnlineSummary.within(day, today..today, zone)
        val h = OnlineSummary.header(todays, app.heyrobot.core.i18n.EverydayStrings.onlineTodayTitle())
        assertEquals("Today: 8 connections to 7 servers.", say(h, Lang.EN))
        assertEquals("Aujourd’hui : 8 connexions vers 7 serveurs.", say(h, Lang.FR))
        assertEquals("Today: no connections.", say(OnlineSummary.header(emptyList(), app.heyrobot.core.i18n.EverydayStrings.onlineTodayTitle()), Lang.EN))
    }

    @Test fun aReportHandedOnIsNoConnectionAndNoServer() {
        val sent = row(at(15, 0), NetPurpose.REPORT_BUG, NetHost.ANOTHER_APP, NetCause.TAP, 4096, 0)
        val title = app.heyrobot.core.i18n.EverydayStrings.onlineTodayTitle()
        assertEquals("Today: no connections.", say(OnlineSummary.header(listOf(sent), title), Lang.EN))
        assertEquals("1 report handed to another app.", say(OnlineSummary.handedLine(listOf(sent))!!, Lang.EN))
        assertEquals("2 rapports remis à une autre appli.", say(OnlineSummary.handedLine(listOf(sent, sent))!!, Lang.FR))
        assertEquals(null, OnlineSummary.handedLine(day))
        val todays = OnlineSummary.within(day + sent, today..today, zone)
        assertEquals("Today: 8 connections to 7 servers.", say(OnlineSummary.header(todays, title), Lang.EN))
        assertEquals("Robot hasn't gone online today.", say(OnlineSummary.spoken(listOf(sent), OnlineDay.TODAY, today, true, false, zone), Lang.EN))
        assertTrue(say(OnlineSummary.spoken(day + sent, OnlineDay.TODAY, today, true, false, zone), Lang.EN).startsWith("Robot went online 5 times today:"))
    }

    @Test fun aRowInWords() {
        val check = day.first()
        assertEquals("Runs by itself · Settings › About › Updates", say(OnlineSummary.cause(check), Lang.EN))
        assertEquals("Update check", say(OnlineSummary.purpose(check.purpose), Lang.EN))
        assertEquals("your network", say(OnlineSummary.host(NetHost.YOUR_NETWORK), Lang.EN))
        assertEquals("api.github.com", say(OnlineSummary.host("api.github.com"), Lang.FR))
        assertEquals("Settings › Weather", OnlineSummary.settings(NetPurpose.WEATHER_PLACE).render(Lang.EN))
        // Every purpose, cause and result has its words in both languages.
        for (p in NetPurpose.entries) for (l in Lang.entries) assertTrue(say(OnlineSummary.purpose(p), l).isNotBlank())
        for (r in NetResult.entries) assertTrue(say(OnlineSummary.result(r), Lang.FR).isNotBlank())
    }

    @Test fun androidsCountBesideTheRecord() {
        // The record since boot: 7 connections of 1,100 bytes and a download of 326 MB (left out of both sides).
        val since = day.filter { it.time >= at(0, 0) }
        val c = OnlineSummary.compare(326_000_000L + 9_500, since)!!
        assertEquals(7 * 1100L, c.robot)
        assertEquals(if (OnlineSummary.ANDROID_COUNTS_DOWNLOADS) 9_500L else 326_009_500L, c.android)
        assertFalse(c.tooMuch)
        // Far more than the record, and more than 1 MB above it: a path the record missed.
        assertTrue(OnlineSummary.compare(326_000_000L + 3_000_000, since)!!.tooMuch)
        // More than twice, but less than 1 MB above: encryption and headers on a small record.
        assertFalse(OnlineSummary.compare(326_000_000L + 30_000, since)!!.tooMuch)
        assertEquals(null, OnlineSummary.compare(null, since))
    }

    @Test fun theReportNamesOnlyRobotsOwnServers() {
        val lines = OnlineSummary.reportLines(day + row(at(14, 0), NetPurpose.HELPER, NetHost.YOUR_NETWORK) + row(at(15, 0), NetPurpose.NEAREST_PLACE, "nominatim.my-own.example"))
        val text = lines.joinToString("\n")
        for (shown in listOf("api.github.com", "api.open-meteo.com", "geocoding-api.open-meteo.com", "huggingface.co")) assertTrue(shown, text.contains(shown))
        // A result page's host would tell what you searched; your own servers are yours.
        for (never in listOf("example.org", "example.com", "api.search.brave.com", "api.anthropic.com", "nominatim.my-own.example", "~lan")) assertFalse(never, text.contains(never))
        assertTrue(text.contains("(web server) · page · asked · 2 · 200 B out · 2000 B in"))
    }

    /** E4: the page's words for Robot to Robot's rows, in both languages; the report never names a push server. */
    @Test fun robotToRobotsRowsHaveTheirWords() {
        fun both(p: app.heyrobot.core.i18n.Phrase) = say(p, Lang.EN) to say(p, Lang.FR)
        assertEquals("Another phone on your network (Robot to Robot)" to "Un autre téléphone sur ton réseau (Robot à Robot)", both(OnlineSummary.purpose(NetPurpose.LINK_DIRECT)))
        assertEquals("A push server (Robot to Robot)" to "Un serveur push (Robot à Robot)", both(OnlineSummary.purpose(NetPurpose.LINK_PUSH)))
        assertEquals("Tor network (Robot to Robot)" to "Réseau Tor (Robot à Robot)", both(OnlineSummary.purpose(NetPurpose.LINK_TOR)))
        assertEquals("the Tor network" to "le réseau Tor", both(OnlineSummary.host(NetHost.TOR_NETWORK)))
        val tor = row(at(9, 0), NetPurpose.LINK_TOR, NetHost.TOR_NETWORK, NetCause.SWITCH)
        assertEquals(OnlineKind.LINK, OnlineSummary.kind(tor))
        assertTrue(say(OnlineSummary.cause(tor), Lang.EN), say(OnlineSummary.cause(tor), Lang.EN).endsWith("Robot to Robot"))
        val push = row(at(9, 0), NetPurpose.LINK_PUSH, "push.friend.example", NetCause.SWITCH)
        assertEquals("(link server)", OnlineSummary.reportHost(push))
        assertEquals("(link server)", OnlineSummary.reportHost(tor))
    }
}
