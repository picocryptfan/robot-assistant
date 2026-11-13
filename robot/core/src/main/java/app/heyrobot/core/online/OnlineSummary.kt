// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import java.time.LocalDate
import java.time.ZoneId

// Pure. What the record says, in words (docs/plans/everyday-set-plan.md §5.4 to §5.6): the page's header and rows, the
// spoken answer to "what went online today?", Android's own count beside the record, and Send feedback's tick (§5.8).
//
// Two counts, kept apart on purpose: the page counts connections (each request, a download once), and the voice counts
// times Robot went online ([lookups]: a weather question is the geocoder and the forecast, a web search its service, its
// encyclopedia and its pages, each one time). Every connection belongs to exactly one lookup (OnlineSummaryTest).

/** What the spoken summary names. */
enum class OnlineKind(val msg: Msg) {
    WEATHER(EverydayStrings.onlineKWeather),
    NEAREST(EverydayStrings.onlineKNearest),
    WEB(EverydayStrings.onlineKWeb),
    HELPER(EverydayStrings.onlineKHelper),
    HELPER_TEST(EverydayStrings.onlineKHelperTest),
    DOWNLOAD(EverydayStrings.onlineKDownload),
    DAILY_CHECK(EverydayStrings.onlineKDailyCheck),
    UPDATE_CHECK(EverydayStrings.onlineKUpdateCheck),
    UPDATE_DOWNLOAD(EverydayStrings.onlineKUpdateDownload),
    TERMUX(EverydayStrings.onlineKTermux),
    CONNECTION(EverydayStrings.onlineKConnection),
    LINK(EverydayStrings.onlineKLink),
    NEWS(EverydayStrings.onlineKNews),
    REPORT(EverydayStrings.onlineKReport),
}

/** One time Robot went online: the connections it took, in order. */
data class OnlineLookup(val kind: OnlineKind, val rows: List<NetEntry>)

/** Android's count beside the record (§5.4): what to show, and whether Android counted far more than the record. */
data class OnlineCompare(val android: Long, val robot: Long, val tooMuch: Boolean)

object OnlineSummary {
    /** Connections of one kind this close together are one time Robot went online. */
    const val GAP_MS = 60_000L

    /**
     * Whether Android counts what its download service fetched for Robot as Robot's: INFERRED from AOSP's DownloadThread
     * as remembered (it tags its sockets with the requesting app's uid, TrafficStats.setThreadStatsUid), UNVERIFIED on
     * GrapheneOS: WO3 measures it on the phone. True: the page takes the recorded downloads off Android's number.
     */
    const val ANDROID_COUNTS_DOWNLOADS = true

    /** Android's count more than this many times the record, and more than [TOO_MUCH_BYTES] above it, means a missed path (EST). */
    const val TOO_MUCH_TIMES = 2
    const val TOO_MUCH_BYTES = 1L * 1024 * 1024

    fun kind(e: NetEntry): OnlineKind = when (e.purpose) {
        NetPurpose.WEATHER, NetPurpose.WEATHER_PLACE -> OnlineKind.WEATHER
        NetPurpose.NEAREST_PLACE -> OnlineKind.NEAREST
        NetPurpose.WEB_SEARCH, NetPurpose.ENCYCLOPEDIA, NetPurpose.WEB_PAGE -> OnlineKind.WEB
        NetPurpose.HELPER -> OnlineKind.HELPER
        NetPurpose.HELPER_TEST -> OnlineKind.HELPER_TEST
        NetPurpose.MODEL_DOWNLOAD, NetPurpose.VOICE_DOWNLOAD, NetPurpose.TRANSLATION_DOWNLOAD, NetPurpose.WIKI_DOWNLOAD -> OnlineKind.DOWNLOAD
        NetPurpose.UPDATE_CHECK -> if (e.cause == NetCause.SWITCH) OnlineKind.DAILY_CHECK else OnlineKind.UPDATE_CHECK
        NetPurpose.UPDATE_DOWNLOAD -> OnlineKind.UPDATE_DOWNLOAD
        NetPurpose.TERMUX_CHECK -> OnlineKind.TERMUX
        NetPurpose.CONNECTION -> OnlineKind.CONNECTION
        NetPurpose.LINK_DIRECT, NetPurpose.LINK_PUSH, NetPurpose.LINK_TOR -> OnlineKind.LINK
        NetPurpose.FEED -> OnlineKind.NEWS
        NetPurpose.REPORT_BUG, NetPurpose.REPORT_IDEA, NetPurpose.REPORT_OTHER -> OnlineKind.REPORT
    }

    /** The connections: every row but a download's end (its bytes belong to the download it ends). */
    fun connections(entries: List<NetEntry>): List<NetEntry> = entries.filterNot { it.downloadEnd }

    /** The times Robot went online: [connections] of one kind and cause, each within [GAP_MS] of the one before. */
    fun lookups(entries: List<NetEntry>): List<OnlineLookup> {
        val out = ArrayList<OnlineLookup>()
        var rows = ArrayList<NetEntry>()
        var key: Pair<OnlineKind, NetCause>? = null
        for (e in connections(entries).sortedBy { it.time }) {
            val k = kind(e) to e.cause
            if (key != k || e.time - rows.last().time > GAP_MS) {
                key?.let { out += OnlineLookup(it.first, rows) }
                rows = ArrayList()
                key = k
            }
            rows += e
        }
        key?.let { out += OnlineLookup(it.first, rows) }
        return out
    }

    /** The first and last day (inclusive) [day] means, [today] being today. */
    fun days(day: OnlineDay, today: LocalDate): ClosedRange<LocalDate> = when (day) {
        OnlineDay.TODAY -> today..today
        OnlineDay.YESTERDAY -> today.minusDays(1)..today.minusDays(1)
        OnlineDay.WEEK -> today.minusDays(6)..today
    }

    /** Of [entries], those on the days [range] holds. */
    fun within(entries: List<NetEntry>, range: ClosedRange<LocalDate>, zone: ZoneId = ZoneId.systemDefault()): List<NetEntry> =
        entries.filter { NetFiles.dayOf(it.time, zone) in range }

    private fun dayWords(day: OnlineDay): Phrase = when (day) {
        OnlineDay.TODAY -> EverydayStrings.onlineToday()
        OnlineDay.YESTERDAY -> EverydayStrings.onlineYesterday()
        OnlineDay.WEEK -> EverydayStrings.onlineWeek()
    }

    /** Settings › Safety & privacy › What went online. */
    val PAGE: UiLabel = UiLabel(UiStrings.settings, UiStrings.safety, EverydayStrings.online)

    /** How many kinds a spoken answer names before "and 2 other things". */
    const val SAID_KINDS = 4

    /**
     * The answer to "what went online today?" (§5.6), from [entries] (the record, any days: this picks [day]'s). Locked:
     * only the unlock line (anyone at a locked phone shouldn't learn which helper you used). [recording] off: the record's
     * switch is off.
     */
    fun spoken(entries: List<NetEntry>, day: OnlineDay, today: LocalDate, recording: Boolean, locked: Boolean, zone: ZoneId = ZoneId.systemDefault()): Phrase {
        if (locked) return EverydayStrings.onlineLocked()
        if (!recording) return EverydayStrings.onlineOff("path" to PAGE)
        val times = lookups(within(entries, days(day, today), zone).filterNot { it.purpose.handed })
        if (times.isEmpty()) return EverydayStrings.onlineNone("day" to dayWords(day))
        val byKind = times.groupingBy { it.kind }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<OnlineKind, Int>> { it.value }.thenBy { it.key.ordinal })
        val named = if (byKind.size <= SAID_KINDS) byKind else byKind.take(SAID_KINDS - 1)
        val items: MutableList<Phrase> = named.map { (k, n) ->
            when (n) {
                1 -> k.msg()
                2 -> EverydayStrings.onlineTwice("what" to k.msg())
                else -> EverydayStrings.onlineTimes(n, "what" to k.msg())
            }
        }.toMutableList()
        if (named.size < byKind.size) items += EverydayStrings.onlineOthers(byKind.size - named.size)
        val what = Phrase.ListOf(items, ListKind.AND)
        return if (times.size == 1) EverydayStrings.onlineWentOnce("day" to dayWords(day), "what" to what)
        else EverydayStrings.onlineWent(times.size, "day" to dayWords(day), "what" to what)
    }

    // ---- The page's words for a row.

    fun purpose(p: NetPurpose): Phrase = when (p) {
        NetPurpose.WEATHER -> EverydayStrings.onlinePWeather()
        NetPurpose.WEATHER_PLACE -> EverydayStrings.onlinePWeatherPlace()
        NetPurpose.NEAREST_PLACE -> EverydayStrings.onlinePNearest()
        NetPurpose.WEB_SEARCH -> EverydayStrings.onlinePSearch()
        NetPurpose.ENCYCLOPEDIA -> EverydayStrings.onlinePEncyclopedia()
        NetPurpose.WEB_PAGE -> EverydayStrings.onlinePPage()
        NetPurpose.HELPER -> EverydayStrings.onlinePHelper()
        NetPurpose.HELPER_TEST -> EverydayStrings.onlinePHelperTest()
        NetPurpose.MODEL_DOWNLOAD -> EverydayStrings.onlinePModel()
        NetPurpose.VOICE_DOWNLOAD -> EverydayStrings.onlinePVoice()
        NetPurpose.TRANSLATION_DOWNLOAD -> EverydayStrings.onlinePTranslation()
        NetPurpose.WIKI_DOWNLOAD -> EverydayStrings.onlinePWikipedia()
        NetPurpose.UPDATE_CHECK -> EverydayStrings.onlinePUpdateCheck()
        NetPurpose.UPDATE_DOWNLOAD -> EverydayStrings.onlinePUpdateDownload()
        NetPurpose.TERMUX_CHECK -> EverydayStrings.onlinePTermux()
        NetPurpose.CONNECTION -> EverydayStrings.onlinePConnection()
        NetPurpose.LINK_DIRECT -> EverydayStrings.onlinePLinkDirect()
        NetPurpose.LINK_PUSH -> EverydayStrings.onlinePLinkPush()
        NetPurpose.LINK_TOR -> EverydayStrings.onlinePLinkTor()
        NetPurpose.FEED -> EverydayStrings.onlinePFeed()
        NetPurpose.REPORT_BUG -> EverydayStrings.onlinePReportBug()
        NetPurpose.REPORT_IDEA -> EverydayStrings.onlinePReportIdea()
        NetPurpose.REPORT_OTHER -> EverydayStrings.onlinePReportOther()
    }

    /** The Settings page that controls [p] (a row's details, with a button to open it). */
    fun settings(p: NetPurpose): UiLabel = when (p.group) {
        NetGroup.WEATHER -> UiLabel(UiStrings.settings, UiStrings.weather)
        NetGroup.PLACES -> UiLabel(UiStrings.settings, UiStrings.actions)
        NetGroup.WEB -> UiLabel(UiStrings.settings, UiStrings.webSearch)
        NetGroup.HELPERS -> UiLabel(UiStrings.settings, UiStrings.helpers)
        NetGroup.DOWNLOADS -> when (p) {
            NetPurpose.VOICE_DOWNLOAD -> UiLabel(UiStrings.settings, UiStrings.voice)
            NetPurpose.TRANSLATION_DOWNLOAD -> UiLabel(UiStrings.settings, UiStrings.conversation)
            NetPurpose.WIKI_DOWNLOAD -> UiLabel(UiStrings.settings, EverydayStrings.wikiOnPhone)
            else -> UiLabel(UiStrings.settings, UiStrings.model)
        }
        NetGroup.UPDATES -> if (p == NetPurpose.TERMUX_CHECK) UiLabel(UiStrings.settings, UiStrings.termux) else UiLabel(UiStrings.settings, UiStrings.about, UiStrings.updates)
        NetGroup.SKILLS -> UiLabel(UiStrings.settings, UiStrings.skills)
        NetGroup.LINK -> UiLabel(UiStrings.settings, EverydayStrings.onlineKLink)
        NetGroup.NEWS -> UiLabel(UiStrings.settings, UiStrings.display)
        NetGroup.REPORTS -> UiLabel(UiStrings.settings, UiStrings.about, EverydayStrings.onlineSReportsSent)
    }

    /** What allowed it: "You asked", "Runs by itself · Settings › About › Updates". */
    fun cause(e: NetEntry): Phrase = when (e.cause) {
        NetCause.ASKED -> EverydayStrings.onlineCAsked()
        NetCause.ROUTINE -> EverydayStrings.onlineCRoutine()
        NetCause.TAP -> EverydayStrings.onlineCTap()
        NetCause.SWITCH -> EverydayStrings.onlineCSwitch("path" to settings(e.purpose))
        NetCause.SKILL -> EverydayStrings.onlineCSkill()
    }

    fun result(r: NetResult): Phrase = when (r) {
        NetResult.DONE -> EverydayStrings.onlineRDone()
        NetResult.FAILED -> EverydayStrings.onlineRFailed()
        NetResult.STOPPED -> EverydayStrings.onlineRStopped()
        NetResult.STARTED -> EverydayStrings.onlineRStarted()
    }

    /** The server as the page shows it: its name, or what the record keeps instead of an address. */
    fun host(h: String): Phrase = when (h) {
        NetHost.YOUR_NETWORK -> EverydayStrings.onlineHLan()
        NetHost.AN_ADDRESS -> EverydayStrings.onlineHIp()
        NetHost.UNKNOWN -> EverydayStrings.onlineHUnknown()
        NetHost.TOR_NETWORK -> EverydayStrings.onlineHTor()
        NetHost.ANOTHER_APP -> EverydayStrings.onlineHApp()
        else -> Phrase.plain(h)
    }

    /** RP6: "1 report handed to another app." for [entries]' handed rows (no connection of Robot's own), or null with none. */
    fun handedLine(entries: List<NetEntry>): Phrase? = entries.count { it.purpose.handed }.takeIf { it > 0 }?.let { EverydayStrings.onlineHanded(it) }

    /** "Today: 4 connections to 3 servers." [title]: the day's name ("Today", a date). Handed reports are left out ([handedLine]). */
    fun header(entries: List<NetEntry>, title: Phrase): Phrase {
        val c = connections(entries).filterNot { it.purpose.handed }
        if (c.isEmpty()) return EverydayStrings.onlineHeaderNone("day" to title)
        val servers = c.map { it.host }.toSet().size
        return EverydayStrings.onlineHeader(c.size, "day" to title, "servers" to EverydayStrings.onlineServers(servers))
    }

    /**
     * Android's own count for Robot since the phone started ([android], sent and received; null when Android gives none)
     * beside the record's rows written since then ([sinceBoot]). Downloads are left out of both (§5.4).
     */
    fun compare(android: Long?, sinceBoot: List<NetEntry>): OnlineCompare? {
        android ?: return null
        val robot = sinceBoot.filterNot { it.purpose.download || it.purpose.handed }.sumOf { it.out + it.inBytes }
        val downloads = sinceBoot.filter { it.downloadEnd }.sumOf { it.inBytes }
        val shown = if (ANDROID_COUNTS_DOWNLOADS) (android - downloads).coerceAtLeast(0) else android
        return OnlineCompare(shown, robot, shown > TOO_MUCH_TIMES * robot && shown - robot > TOO_MUCH_BYTES)
    }

    // ---- Send feedback's tick, "What went online (7 days)" (§5.8): hosts, kinds and counts only.

    /** Days the tick covers. */
    const val FEEDBACK_DAYS = 7

    /**
     * A host the report may name: only a server Robot itself fixes (Open-Meteo, GitHub, F-Droid, the models' servers,
     * Wikipedia and Open Library). Your own servers (a helper, a search service, a Nominatim server) and the result pages'
     * hosts (they would tell what you searched) are named by their kind only, as Settings summary names no server address.
     */
    fun reportHost(e: NetEntry): String = when (e.purpose) {
        NetPurpose.WEATHER, NetPurpose.WEATHER_PLACE, NetPurpose.ENCYCLOPEDIA, NetPurpose.MODEL_DOWNLOAD, NetPurpose.VOICE_DOWNLOAD,
        NetPurpose.TRANSLATION_DOWNLOAD, NetPurpose.WIKI_DOWNLOAD, NetPurpose.UPDATE_CHECK, NetPurpose.UPDATE_DOWNLOAD, NetPurpose.TERMUX_CHECK -> if (NetHost.marker(e.host)) "(server)" else e.host // l10n: not text (the report, a diagnostic: English)
        NetPurpose.REPORT_BUG, NetPurpose.REPORT_IDEA, NetPurpose.REPORT_OTHER -> "(another app)" // l10n: not text (the report, a diagnostic: English)
        else -> "(" + e.purpose.group.code + " server)" // l10n: not text (the report, a diagnostic: English)
    }

    /** The report's lines: "api.open-meteo.com · weather · asked · 3 · 1.2 KB out · 40 KB in", most used first. */
    fun reportLines(entries: List<NetEntry>): List<String> = entries.groupBy { Triple(reportHost(it), it.purpose, it.cause) }
        .map { (k, rows) -> Triple(k, rows.size, rows.sumOf { it.out } to rows.sumOf { it.inBytes }) }
        .sortedWith(compareByDescending<Triple<Triple<String, NetPurpose, NetCause>, Int, Pair<Long, Long>>> { it.second }.thenBy { it.first.first })
        .map { (k, n, bytes) -> "${k.first} · ${k.second.code} · ${k.third.code} · $n · ${bytes.first} B out · ${bytes.second} B in" } // l10n: not text (the report, a diagnostic: English)
}
