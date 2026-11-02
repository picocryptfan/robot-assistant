// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.web.PublicAddress
import app.heyrobot.core.web.WebUrl
import org.json.JSONObject
import java.net.IDN
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. What went online (docs/plans/everyday-set-plan.md §5, D10 to D12): the record of every connection Robot made,
// kept on the phone only, never backed up and never sent. One row per request: when, which process, what for, the
// server's host (or "your network" for an address on it, never an IP address), what allowed it, the bytes of the request's
// and the reply's bodies, and how it ended. Never your words, a path, a query, a key or a full URL: [NetRecord.record]
// takes a host and codes only, so nothing else can reach the file.
//
// Each client writes at its one exit point (OpenMeteoClient's, NominatimClient's and GitHubClient's private request
// functions, the helpers' OkHttp clients through an interceptor, the Anthropic SDK's through its own, DownloadManager at
// enqueue and when it ends); ProcessIsolationTest.everyNetworkPathIsRecorded fails the build when a network path doesn't.
// One writer per process (app sys/NetLog, set as [NetRecord.sink] in main, :voice and :link), each in its own file per day,
// files/online/<process>-<yyyymmdd>.jsonl, so no two processes share a file:
//
//   {"t":1761220800000,"p":"voice","k":"weather","h":"api.open-meteo.com","c":"asked","o":0,"i":2048,"r":"done"}
//
// "z" is a download's planned size (on the row written at enqueue). Kept [NetFiles.DEFAULT_DAYS] days (Q3: 30, Settings ›
// Safety & privacy › What went online, 1 to 90), at most [NetFiles.MAX_DAY_BYTES] a process a day: past it, only counts
// are kept for that day (<process>-<yyyymmdd>.more.json).

/** The page's filter (§5.5): one kind of connection. [code] never changes (the page's saved filter). */
enum class NetGroup(val code: String) {
    WEATHER("weather"),
    PLACES("places"),
    WEB("web"),
    HELPERS("helpers"),
    DOWNLOADS("downloads"),
    UPDATES("updates"),
    /** E4: Skills connections' ConnHttp. */
    SKILLS("skills"),
    /** E4: Robot to Robot's carriers. */
    LINK("link"),
}

/** What a connection was for (§5.2). [code] is what the record keeps: never renamed. */
enum class NetPurpose(val code: String, val group: NetGroup) {
    /** A forecast from Open-Meteo (a place you named, your Home, where the phone is). */
    WEATHER("weather", NetGroup.WEATHER),
    /** Open-Meteo's geocoder, finding the place you named (the place's name goes there: audit F32). */
    WEATHER_PLACE("weather_place", NetGroup.WEATHER),
    /** The nearest place, from your Nominatim server (OpenStreetMap's unless you chose another). */
    NEAREST_PLACE("nearest", NetGroup.PLACES),
    /** Your web search service. */
    WEB_SEARCH("search", NetGroup.WEB),
    /** Wikipedia's or Open Library's lookups beside a web search (audit F37: on by default). */
    ENCYCLOPEDIA("encyclopedia", NetGroup.WEB),
    /** One of the result pages Robot reads from the phone. */
    WEB_PAGE("page", NetGroup.WEB),
    /** A question to your helper (a company's API or your own server). */
    HELPER("helper", NetGroup.HELPERS),
    /** Test connection, in a helper's settings. */
    HELPER_TEST("helper_test", NetGroup.HELPERS),
    MODEL_DOWNLOAD("model", NetGroup.DOWNLOADS),
    VOICE_DOWNLOAD("voice", NetGroup.DOWNLOADS),
    TRANSLATION_DOWNLOAD("translation", NetGroup.DOWNLOADS),
    /** The updater asking GitHub for releases. */
    UPDATE_CHECK("update_check", NetGroup.UPDATES),
    /** A release's SHA256SUMS and APK. */
    UPDATE_DOWNLOAD("update_download", NetGroup.UPDATES),
    /** Termux's update check (GitHub's releases and F-Droid's entry). */
    TERMUX_CHECK("termux_check", NetGroup.UPDATES),
    /** A skill's call to one of your connections, or Test on its page (ConnHttp; the cause says which). */
    CONNECTION("connection", NetGroup.SKILLS),
    /** Robot to Robot's direct carrier: the host is only ever "your network", never the other phone's address. */
    LINK_DIRECT("link_direct", NetGroup.LINK),
    /** A push message posted straight to a push server (through Tor, it is part of [LINK_TOR]'s row). */
    LINK_PUSH("link_push", NetGroup.LINK),
    /** Tor's traffic for an hour, by Tor's own counters (core TorHours): never a relay's address. */
    LINK_TOR("link_tor", NetGroup.LINK),
    ;

    /** Downloaded by Android's download service: left out of the comparison with Android's own count (§5.4). */
    val download: Boolean get() = group == NetGroup.DOWNLOADS

    companion object {
        fun of(code: String?): NetPurpose? = entries.firstOrNull { it.code == code }

        /** A web search's request: an encyclopedia lookup when it goes to Wikipedia or Open Library, else the search. */
        fun search(host: String): NetPurpose {
            val h = host.lowercase(Locale.ROOT)
            return if (h == "wikipedia.org" || h.endsWith(".wikipedia.org") || h == "openlibrary.org" || h.endsWith(".openlibrary.org")) ENCYCLOPEDIA
            else WEB_SEARCH
        }
    }
}

/** What allowed it (§5.2). */
enum class NetCause(val code: String) {
    /** You asked (a question, the panel). */
    ASKED("asked"),
    /** A routine (the morning brief's weather). */
    ROUTINE("routine"),
    /** A tap: Download, Check now, Test connection, a Settings lookup. */
    TAP("tap"),
    /** A switch that runs by itself: the daily update check, the download on Wi-Fi, Termux's weekly check. */
    SWITCH("switch"),
    /** E4: a skill's step. */
    SKILL("skill"),
    ;

    companion object {
        fun of(code: String?): NetCause? = entries.firstOrNull { it.code == code }
    }
}

/** How it ended. */
enum class NetResult(val code: String) {
    DONE("done"),
    FAILED("failed"),
    /** Stop, or its deadline. */
    STOPPED("stopped"),
    /** A download handed to Android's download service (its end is a row of its own). */
    STARTED("started"),
    ;

    companion object {
        fun of(code: String?): NetResult? = entries.firstOrNull { it.code == code }
    }
}

/** One connection. [time] in ms (wall clock); [out] and [inBytes] the bodies' bytes; [planned] a download's planned size. */
data class NetEntry(
    val time: Long,
    val process: String,
    val purpose: NetPurpose,
    val host: String,
    val cause: NetCause,
    val out: Long,
    val inBytes: Long,
    val result: NetResult,
    val planned: Long = 0,
) {
    /** A download's end (done, failed, stopped): the bytes of a download counted once, not a connection of its own. */
    val downloadEnd: Boolean get() = purpose.download && result != NetResult.STARTED
}

/** Where a host is shown as itself, and where it is only "your network" or "an address" (§5.2: never an IP address). */
object NetHost {
    /** An address on your network (a private or local address, a local name). */
    const val YOUR_NETWORK = "~lan"
    /** A public address typed as a number. */
    const val AN_ADDRESS = "~ip"
    /** Nothing readable. */
    const val UNKNOWN = "~?"
    /** The Tor network, as a whole: its relays are never named. */
    const val TOR_NETWORK = "~tor"

    private val MARKERS = setOf(YOUR_NETWORK, AN_ADDRESS, UNKNOWN, TOR_NETWORK)
    private val NAME = Regex("[a-z0-9]([a-z0-9-]{0,62})?(\\.[a-z0-9_]([a-z0-9_-]{0,62})?)*")

    /** [raw] (a URL's host) as the record keeps it: lower case, ASCII, no port or user; a marker for an address. */
    fun of(raw: String?): String {
        val h = raw?.trim()?.removePrefix("[")?.removeSuffix("]")?.removeSuffix(".") ?: return UNKNOWN
        if (h.isEmpty() || h.length > 253) return UNKNOWN
        // An address written as numbers, in any spelling a URL takes ("10.1", "0x7f.1", "::ffff:192.168.1.2"): never kept.
        if (':' in h) return PublicAddress.ipv6(h)?.let { if (PublicAddress.isPublic(it)) AN_ADDRESS else YOUR_NETWORK } ?: UNKNOWN
        PublicAddress.ipv4Loose(h)?.let { return if (PublicAddress.isPublic(it)) AN_ADDRESS else YOUR_NETWORK }
        val name = try { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT) } catch (_: Exception) { return UNKNOWN }
        if (!valid(name) || marker(name)) return UNKNOWN
        return if (PublicAddress.localName(name)) YOUR_NETWORK else name
    }

    /** The host of [url] as [of] keeps it (nothing else of the URL). */
    fun ofUrl(url: String?): String = url?.let { WebUrl.parse(it)?.let { u -> of(u.host) } } ?: UNKNOWN

    /** A marker, or a name the record may hold (never an address written as numbers). */
    fun valid(h: String): Boolean = h in MARKERS || (h.length <= 253 && NAME.matches(h) && !h.all { it.isDigit() || it == '.' })

    fun marker(h: String): Boolean = h in MARKERS
}

/** Where a process's record goes: one sink per process (app sys/NetLog), set once it starts. */
fun interface NetSink {
    fun add(e: NetEntry)
}

object NetRecord {
    /** This process's writer; none in a process that never goes online, and in the JVM tests unless a test sets one. */
    @Volatile var sink: NetSink? = null

    /** This process's name in the record ("main", "voice"), set with [sink]. */
    @Volatile var process: String = "main"

    /** For tests: the clock (wall time, ms). */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /**
     * One connection: [host] as the URL had it ([NetHost.of] keeps only what may be kept), its bodies' bytes, how it
     * ended. Never throws: the record never stands in the way of the connection.
     */
    fun record(purpose: NetPurpose, host: String?, cause: NetCause, out: Long, inBytes: Long, result: NetResult, planned: Long = 0) {
        add(purpose, cause, out, inBytes, result, planned) { NetHost.of(host) }
    }

    private inline fun add(purpose: NetPurpose, cause: NetCause, out: Long, inBytes: Long, result: NetResult, planned: Long = 0, host: () -> String) {
        val s = sink ?: return
        try {
            s.add(NetEntry(clock(), process, purpose, host(), cause, out.coerceAtLeast(0), inBytes.coerceAtLeast(0), result, planned.coerceAtLeast(0)))
        } catch (_: Throwable) {}
    }

    /** Robot to Robot's direct carrier: "another phone on your network", so no address is ever passed in. */
    fun linkDirect(out: Long, inBytes: Long, result: NetResult) =
        add(NetPurpose.LINK_DIRECT, NetCause.SWITCH, out, inBytes, result) { NetHost.YOUR_NETWORK }

    /** One hour of Tor's traffic, by its own counters. */
    fun torHour(out: Long, inBytes: Long) =
        add(NetPurpose.LINK_TOR, NetCause.SWITCH, out, inBytes, NetResult.DONE) { NetHost.TOR_NETWORK }

    /** A download handed to Android's download service: [url]'s host and the planned size (§5.3). */
    fun downloadStarted(purpose: NetPurpose, url: String, planned: Long, cause: NetCause = NetCause.TAP) =
        record(purpose, hostOf(url), cause, 0, 0, NetResult.STARTED, planned)

    /** A download's end: what it fetched ([bytes]) and how it ended. */
    fun downloadEnded(purpose: NetPurpose, url: String?, bytes: Long, result: NetResult, cause: NetCause = NetCause.TAP) =
        record(purpose, hostOf(url), cause, 0, bytes, result)

    /** [url]'s host, as a URL parser reads it (nothing else of the URL is kept). */
    fun hostOf(url: String?): String? = url?.let { WebUrl.parse(it)?.host }

    // ---- The file's lines.

    fun encode(e: NetEntry): String = JSONObject()
        .put("t", e.time)
        .put("p", e.process)
        .put("k", e.purpose.code)
        .put("h", e.host)
        .put("c", e.cause.code)
        .put("o", e.out)
        .put("i", e.inBytes)
        .put("r", e.result.code)
        .apply { if (e.planned > 0) put("z", e.planned) }
        .toString()

    private val PROCESS = Regex("[a-z]{1,16}")

    /** One line, or null: torn (a writer appending as it is read), damaged, or a newer Robot's code. */
    fun decode(line: String): NetEntry? {
        val o = try { JSONObject(line) } catch (_: Exception) { return null }
        fun long(k: String, required: Boolean = true): Long? = when (val v = o.opt(k)) {
            is Int -> v.toLong()
            is Long -> v
            null -> if (required) null else 0L
            else -> null
        }?.takeIf { it >= 0 }
        val process = (o.opt("p") as? String)?.takeIf { PROCESS.matches(it) } ?: return null
        val host = (o.opt("h") as? String)?.takeIf { NetHost.valid(it) } ?: return null
        return NetEntry(
            time = long("t") ?: return null,
            process = process,
            purpose = NetPurpose.of(o.opt("k") as? String) ?: return null,
            host = host,
            cause = NetCause.of(o.opt("c") as? String) ?: return null,
            out = long("o") ?: return null,
            inBytes = long("i") ?: return null,
            result = NetResult.of(o.opt("r") as? String) ?: return null,
            planned = long("z", required = false) ?: return null,
        )
    }

    /** A day file's rows, oldest first; lines that can't be read are skipped. */
    fun decodeAll(text: String?): List<NetEntry> = text?.lineSequence()?.filter { it.isNotBlank() }?.mapNotNull(::decode)?.toList().orEmpty()
}

/** A day's counts past the cap (§5.8): how many more connections, and their bytes. */
data class NetMore(val count: Long = 0, val out: Long = 0, val inBytes: Long = 0) {
    operator fun plus(e: NetEntry): NetMore = NetMore(count + 1, out + e.out, inBytes + e.inBytes)
    operator fun plus(o: NetMore): NetMore = NetMore(count + o.count, out + o.out, inBytes + o.inBytes)

    fun encode(): String = JSONObject().put("n", count).put("o", out).put("i", inBytes).toString()

    companion object {
        fun decode(text: String?): NetMore {
            val o = try { JSONObject(text ?: return NetMore()) } catch (_: Exception) { return NetMore() }
            fun n(k: String): Long = (o.opt(k) as? Number)?.toLong()?.coerceAtLeast(0) ?: 0
            return NetMore(n("n"), n("o"), n("i"))
        }
    }
}

/** The record's files in files/[DIR]: their names, which day they hold, and which have aged out. */
/** What [NetFiles.toRead] picked; [cut]: some of the record was left out. */
data class NetRead(val names: List<String>, val cut: Boolean)

object NetFiles {
    const val DIR = "online"
    const val DEFAULT_DAYS = 30
    /** A process's rows a day, at most (§5.8); past it only counts ([NetMore]). */
    const val MAX_DAY_BYTES = 2L * 1024 * 1024
    private const val ROWS = ".jsonl"
    private const val MORE = ".more.json"
    private val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val NAME = Regex("([a-z]{1,16})-(\\d{8})(\\.jsonl|\\.more\\.json)")

    fun rows(process: String, day: LocalDate): String = "$process-${DAY.format(day)}$ROWS"
    fun more(process: String, day: LocalDate): String = "$process-${DAY.format(day)}$MORE"

    /** The local day [time] falls on. */
    fun dayOf(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

    /** A record file's process, day and whether it holds rows (else counts); null for anything else. */
    fun parse(name: String): Triple<String, LocalDate, Boolean>? {
        val m = NAME.matchEntire(name) ?: return null
        val day = try { LocalDate.parse(m.groupValues[2], DAY) } catch (_: Exception) { return null }
        return Triple(m.groupValues[1], day, m.groupValues[3] == ROWS)
    }

    /** The first day kept with [days] days: today and the [days] - 1 before it. */
    fun firstKept(today: LocalDate, days: Int): LocalDate = today.minusDays((days.coerceIn(1, 90) - 1).toLong())

    /** Of [names], the record's files older than [days] days (the rest, and anything that isn't the record's, stay). */
    fun expired(names: Collection<String>, today: LocalDate, days: Int): List<String> {
        val first = firstKept(today, days)
        return names.filter { n -> parse(n)?.let { it.second < first } == true }.sorted()
    }

    /** What a whole read takes at most (the worst case is 2 MiB × 3 processes × 90 days, 540 MiB). */
    const val READ_CAP = 16L * 1024 * 1024

    /** E10: which rows files to read of [files] (name to size): every day's, newest first, up to [cap]; [NetRead.cut]: some were left out. A file past a day's cap isn't one Robot wrote, so it's left out too. */
    fun toRead(files: Map<String, Long>, cap: Long = READ_CAP): NetRead {
        val rows = files.entries.mapNotNull { (n, size) -> parse(n)?.takeIf { it.third }?.let { Triple(n, it.second, size) } }
        val names = ArrayList<String>()
        var total = 0L
        var cut = false
        for (day in rows.groupBy { it.second }.toSortedMap(compareByDescending { it }).values) {
            val ok = day.filter { it.third <= MAX_DAY_BYTES }
            if (ok.size < day.size) cut = true
            total += ok.sumOf { it.third }
            if (total > cap) return NetRead(names, true) // nothing older than a day that didn't fit
            ok.mapTo(names) { it.first }
        }
        return NetRead(names, cut)
    }

    /** Whether a row of [lineBytes] still fits a day file of [size] bytes. */
    fun fits(size: Long, lineBytes: Int): Boolean = size + lineBytes + 1 <= MAX_DAY_BYTES
}
