// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The record's codec, what it never holds, its files, retention and the per-day cap (everyday-set-plan.md §5.2, §5.8, D11). */
class NetRecordTest {
    private val got = ArrayList<NetEntry>()

    private fun capture() {
        NetRecord.sink = NetSink { got += it }
        NetRecord.process = "voice"
        NetRecord.clock = { 1_761_220_800_000 }
    }

    @After fun reset() {
        NetRecord.sink = null
        NetRecord.process = "main"
        NetRecord.clock = System::currentTimeMillis
    }

    @Test fun aLongRecordIsReadNewestDayFirstUpToItsCap() {
        val mib = 1024L * 1024
        val files = mapOf("main-20261023.jsonl" to mib, "voice-20261023.jsonl" to mib, "main-20261022.jsonl" to mib, "main-20261021.jsonl" to mib,
            "main-20261023.more.json" to 10L, "stray.txt" to 10L)
        assertEquals(NetRead(listOf("main-20261023.jsonl", "voice-20261023.jsonl", "main-20261022.jsonl", "main-20261021.jsonl"), false), NetFiles.toRead(files, 4 * mib))
        // A day isn't read in part, and nothing older than the day that didn't fit is.
        assertEquals(NetRead(listOf("main-20261023.jsonl", "voice-20261023.jsonl"), true), NetFiles.toRead(files, 2 * mib + 1))
        assertEquals(NetRead(emptyList(), true), NetFiles.toRead(files, mib))
        // No file is read past a day's cap, whatever fits.
        assertEquals(NetRead(listOf("main-20261022.jsonl"), true), NetFiles.toRead(mapOf("main-20261023.jsonl" to NetFiles.MAX_DAY_BYTES + 1, "main-20261022.jsonl" to mib)))
    }

    @Test fun aRowRoundTripsAndOldRowsStayReadable() {
        val e = NetEntry(1_761_220_800_000, "voice", NetPurpose.WEATHER, "api.open-meteo.com", NetCause.ASKED, 0, 2048, NetResult.DONE)
        val line = NetRecord.encode(e)
        // Each key once, short, and nothing else (JSON's key order isn't fixed).
        val o = org.json.JSONObject(line)
        assertEquals(setOf("t", "p", "k", "h", "c", "o", "i", "r"), o.keys().asSequence().toSet())
        assertEquals(listOf<Any>(1_761_220_800_000L, "voice", "weather", "api.open-meteo.com", "asked", 0L, 2048L, "done"),
            listOf<Any>(o.getLong("t"), o.getString("p"), o.getString("k"), o.getString("h"), o.getString("c"), o.getLong("o"), o.getLong("i"), o.getString("r")))
        assertEquals(e, NetRecord.decode(line))
        val d = e.copy(purpose = NetPurpose.VOICE_DOWNLOAD, host = "huggingface.co", cause = NetCause.TAP, inBytes = 0, result = NetResult.STARTED, planned = 326_000_000)
        assertEquals(d, NetRecord.decode(NetRecord.encode(d)))
        assertTrue(NetRecord.encode(d).contains("\"z\":326000000"))
    }

    @Test fun aLineThatCantBeReadIsSkippedNeverGuessed() {
        val good = NetRecord.encode(NetEntry(1, "main", NetPurpose.UPDATE_CHECK, "api.github.com", NetCause.SWITCH, 0, 10, NetResult.DONE))
        for (bad in listOf("", "{", good.dropLast(5), good.replace("\"weather\"", "x").replace("update_check", "a_newer_kind"),
            good.replace("\"switch\"", "\"later\""), good.replace("\"o\":0", "\"o\":-1"), good.replace("\"main\"", "\"Main!\""),
            good.replace("api.github.com", "https://api.github.com/repos/x?q=1"), good.replace("api.github.com", "10.0.0.2")))
            assertNull(bad, NetRecord.decode(bad))
        assertEquals(1, NetRecord.decodeAll("$good\n{\"t\":1,\"p\":\"ma").size)
    }

    @Test fun onlyAHostAndCodesAreKeptNeverAPathAQueryOrAnAddress() {
        capture()
        NetRecord.record(NetPurpose.WEB_SEARCH, NetHost.ofUrl("https://search.example.org/search?q=zq9marker&key=s3cret"), NetCause.ASKED, 120, 3000, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "192.168.1.20", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "[fd00::5]", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "8.8.8.8", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "myserver.local", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "router", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "API.Anthropic.COM.", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, "bad host/zq9marker", NetCause.ASKED, 1, 2, NetResult.DONE)
        NetRecord.record(NetPurpose.HELPER, null, NetCause.ASKED, -5, 2, NetResult.FAILED)
        assertEquals(listOf("search.example.org", NetHost.YOUR_NETWORK, NetHost.YOUR_NETWORK, NetHost.AN_ADDRESS, NetHost.YOUR_NETWORK, NetHost.YOUR_NETWORK,
            "api.anthropic.com", NetHost.UNKNOWN, NetHost.UNKNOWN), got.map { it.host })
        assertEquals(0, got.last().out)
        val file = got.joinToString("\n") { NetRecord.encode(it) }
        for (never in listOf("zq9marker", "s3cret", "search?", "/search", "192.168", "8.8.8.8", "fd00", "myserver")) assertFalse(never, file.contains(never))
        assertTrue(got.all { it.process == "voice" && it.time == 1_761_220_800_000 })
    }

    @Test fun withoutASinkNothingIsWrittenAndNothingThrows() {
        NetRecord.record(NetPurpose.WEATHER, "api.open-meteo.com", NetCause.ASKED, 0, 1, NetResult.DONE)
        NetRecord.sink = NetSink { throw IllegalStateException("disk full") }
        NetRecord.record(NetPurpose.WEATHER, "api.open-meteo.com", NetCause.ASKED, 0, 1, NetResult.DONE)
    }

    @Test fun downloadsAreRecordedAtEnqueueAndAtTheirEnd() {
        capture()
        NetRecord.downloadStarted(NetPurpose.MODEL_DOWNLOAD, "https://huggingface.co/litert-community/x/resolve/abc/model.litertlm", 2_600_000_000)
        NetRecord.downloadEnded(NetPurpose.MODEL_DOWNLOAD, "https://huggingface.co/litert-community/x/resolve/abc/model.litertlm", 2_600_000_000, NetResult.DONE)
        assertEquals(listOf(NetResult.STARTED, NetResult.DONE), got.map { it.result })
        assertEquals(listOf(2_600_000_000L, 0L), got.map { it.planned })
        assertEquals(listOf(false, true), got.map { it.downloadEnd })
        assertTrue(got.all { it.host == "huggingface.co" && it.cause == NetCause.TAP })
    }

    @Test fun aSearchToWikipediaOrOpenLibraryIsAnEncyclopediaLookup() {
        assertEquals(NetPurpose.ENCYCLOPEDIA, NetPurpose.search("fr.wikipedia.org"))
        assertEquals(NetPurpose.ENCYCLOPEDIA, NetPurpose.search("openlibrary.org"))
        assertEquals(NetPurpose.WEB_SEARCH, NetPurpose.search("api.search.brave.com"))
        assertEquals(NetPurpose.WEB_SEARCH, NetPurpose.search("notwikipedia.org"))
    }

    @Test fun codesNeverChange() {
        // The record outlives this version: a renamed code would make 30 days of rows unreadable.
        assertEquals(listOf("weather", "weather_place", "nearest", "search", "encyclopedia", "page", "helper", "helper_test", "model", "voice", "translation",
            "update_check", "update_download", "termux_check", "connection", "link_direct", "link_push", "link_tor"), NetPurpose.entries.map { it.code })
        assertEquals(listOf("asked", "routine", "tap", "switch", "skill"), NetCause.entries.map { it.code })
        assertEquals(listOf("done", "failed", "stopped", "started"), NetResult.entries.map { it.code })
    }

    @Test fun filesAreOnePerProcessPerDayAndAgeOut() {
        val day = LocalDate.of(2026, 10, 23)
        assertEquals("voice-20261023.jsonl", NetFiles.rows("voice", day))
        assertEquals("main-20261023.more.json", NetFiles.more("main", day))
        assertEquals(Triple("voice", day, true), NetFiles.parse("voice-20261023.jsonl"))
        assertEquals(Triple("main", day, false), NetFiles.parse("main-20261023.more.json"))
        assertNull(NetFiles.parse("voice-2026102.jsonl"))
        assertNull(NetFiles.parse("notes.txt"))
        val names = listOf("main-20260923.jsonl", "main-20260924.jsonl", "voice-20260924.more.json", "voice-20261023.jsonl", "stray.txt")
        // 30 days: 24 Sep to 23 Oct are kept.
        assertEquals(listOf("main-20260923.jsonl"), NetFiles.expired(names, day, 30))
        assertEquals(listOf("main-20260923.jsonl", "main-20260924.jsonl", "voice-20260924.more.json"), NetFiles.expired(names, day, 7))
        assertEquals(LocalDate.of(2026, 10, 23), NetFiles.firstKept(day, 1))
        // 04:00 UTC on 1 Oct 2025 is 01:00 in Halifax, the same day; 02:00 UTC is still 30 Sep there.
        assertEquals(LocalDate.of(2025, 10, 1), NetFiles.dayOf(1_759_291_200_000, ZoneId.of("America/Halifax")))
        assertEquals(LocalDate.of(2025, 9, 30), NetFiles.dayOf(1_759_284_000_000, ZoneId.of("America/Halifax")))
    }

    @Test fun pastTwoMebibytesADayOnlyCountsAreKept() {
        assertTrue(NetFiles.fits(0, 200))
        assertTrue(NetFiles.fits(NetFiles.MAX_DAY_BYTES - 201, 200))
        assertFalse(NetFiles.fits(NetFiles.MAX_DAY_BYTES - 200, 200))
        val e = NetEntry(1, "main", NetPurpose.WEATHER, "api.open-meteo.com", NetCause.ASKED, 3, 7, NetResult.DONE)
        val more = NetMore() + e + e
        assertEquals(NetMore(2, 6, 14), more)
        assertEquals(more, NetMore.decode(more.encode()))
        assertEquals(NetMore(), NetMore.decode("{"))
    }

    /** E4: Robot to Robot's rows: markers only, and a host that claims to be a marker stays nothing. */
    @Test fun robotToRobotsRowsHoldMarkersAndNoAddress() {
        capture()
        NetRecord.linkDirect(5, 3, NetResult.DONE)
        NetRecord.torHour(70, 500)
        NetRecord.record(NetPurpose.LINK_PUSH, "push.example.org", NetCause.SWITCH, 700, 0, NetResult.DONE)
        NetRecord.record(NetPurpose.LINK_PUSH, "8.8.8.8", NetCause.SWITCH, 700, 0, NetResult.DONE)
        NetRecord.record(NetPurpose.LINK_PUSH, NetHost.TOR_NETWORK, NetCause.SWITCH, 700, 0, NetResult.DONE)
        assertEquals(listOf(NetHost.YOUR_NETWORK, NetHost.TOR_NETWORK, "push.example.org", NetHost.AN_ADDRESS, NetHost.UNKNOWN), got.map { it.host })
        assertEquals(listOf(NetPurpose.LINK_DIRECT, NetPurpose.LINK_TOR), got.take(2).map { it.purpose })
        assertEquals(setOf(NetGroup.LINK), got.map { it.purpose.group }.toSet())
        assertEquals(setOf(NetCause.SWITCH), got.map { it.cause }.toSet())
        // They read back: a marker added since is in the codec's list.
        for (e in got) assertEquals(e, NetRecord.decode(NetRecord.encode(e)))
    }
}
