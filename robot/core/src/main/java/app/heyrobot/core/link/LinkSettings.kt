// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.json.JSONObject
import java.time.LocalTime

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §6.1, D12, Q2, Q3; job L2): Settings › Robot to Robot's values in
// files/robot/link_settings.json, beside files_settings.json. The main app writes it (sys/LinkSettingsStore.kt); :link and
// :voice read it. Never in settings.json (its schema stays 13). Pairings, permissions and the block list are not here:
// they live in :link's sealed state and change only through it (D12). Payments and the exchange rate (D20) wait with
// payments (the user, 21 Oct): no keys for them yet.
//
//   {"v":1,"on":false,"name":"","reach":"PUSH","whileLocked":true,"from":"09:00","to":"21:00","brief":true}
//
// L7 adds "busyFrom" (§4.1, "only the calendars you choose"): the calendars free/busy answers read, as their ids; absent,
// every visible calendar (what read_calendar reads). An older Robot keeps it as an unknown key.
//
// L-bridges adds "bridges" (Settings › Robot to Robot › Tor bridges: OFF, AUTO, SNOWFLAKE, OBFS4 or OWN; absent, OFF) and
// "ownBridges" (the bridge lines you entered, each as core BridgeLine writes it; absent, none). OWN needs at least one.
//
// A key this version doesn't know is kept and written back as it was (a later job's row). A known key with a wrong type or
// a value this version doesn't know makes the file Damaged; a newer version is TooNew and never written over. Either way
// Robot to Robot fails closed: off, until a file this version reads says otherwise (a Damaged file is written over by
// your next change in Settings; a TooNew one waits for the newer Robot).

/** Q2: how other Robots reach yours while you aren't using it. */
enum class LinkReach {
    /** "Through your push app": push wake-ups through a push distributor app; only while you use Robot if there is none. */
    PUSH,
    /** "Keep Tor running in the background". */
    TOR,
    /** "Only while I use Robot". */
    WHILE_USED,
}

/** Settings › Robot to Robot, at the plan's defaults (§6.1). */
data class LinkSettings(
    /** Robot to Robot on: off until you set it up (turning it on makes the keys). */
    val on: Boolean = false,
    /** "Name other Robots see": asked when you turn it on; never sent until you pair. Empty until then, and again after Start over. */
    val name: String = "",
    val reach: LinkReach = LinkReach.PUSH,
    /** Q3, "Answer while my phone is locked": what you set to Answer on its own still answers while locked. */
    val whileLocked: Boolean = true,
    /** "Working hours for events on their own" (D18). */
    val hours: EventAlone.Hours = EventAlone.Hours(),
    /** "Show in morning brief". */
    val brief: Boolean = true,
    /** L7, "Calendars other Robots see as busy": their ids; null for every visible calendar. */
    val busyCalendars: Set<Long>? = null,
    /** L-bridges, "Tor bridges": Off by default. */
    val bridges: BridgeChoice = BridgeChoice.OFF,
    /** L-bridges, "Your own bridge": at most TorBridges.MAX_OWN lines, kept even while another choice is on. */
    val ownBridges: List<BridgeLine> = emptyList(),
) {
    init {
        require(name.isEmpty() || LinkText.ok(name, PairHello.MAX_NAME))
        require(hours.start < hours.end)
        require(busyCalendars == null || (busyCalendars.size <= MAX_CALENDARS && busyCalendars.all { it >= 0 }))
        require(ownBridges.size <= TorBridges.MAX_OWN && ownBridges.distinct().size == ownBridges.size)
        require(bridges != BridgeChoice.OWN || ownBridges.isNotEmpty())
    }

    /** The routes :link's Tor tries, in order (TorBridges.routes). */
    val torRoutes: List<TorRoute> get() = TorBridges.routes(bridges, ownBridges)

    override fun toString(): String = "LinkSettings(on=$on, reach=$reach, whileLocked=$whileLocked, brief=$brief, bridges=$bridges)" // l10n: not text (toString for logs and tests; never the name or a bridge)

    companion object {
        /** What applies when the file can't be read whole: off. */
        val CLOSED = LinkSettings()
        /** Calendars named in "busyFrom" at most. */
        const val MAX_CALENDARS = 64
    }
}

/** What link_settings.json gave. */
sealed class LinkSettingsFile {
    /** [extra]: the keys this version doesn't know, kept to write back. */
    data class Ok(val read: LinkSettings, val extra: Map<String, Any?> = emptyMap()) : LinkSettingsFile()
    data class TooNew(val v: Int) : LinkSettingsFile()
    object Damaged : LinkSettingsFile()

    /** What applies: the file's, else off (fail closed). */
    val settings: LinkSettings get() = (this as? Ok)?.read ?: LinkSettings.CLOSED
}

object LinkSettingsCodec {
    const val FILE = "link_settings.json" // l10n: not text (a file name)
    const val VERSION = 1
    private val KNOWN = setOf("v", "on", "name", "reach", "whileLocked", "from", "to", "brief", "busyFrom", "bridges", "ownBridges") // l10n: not text (JSON keys)
    private val TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]") // l10n: not text (a pattern)

    fun encode(s: LinkSettings, extra: Map<String, Any?> = emptyMap()): String {
        val o = JSONObject()
        for ((k, v) in extra) if (k !in KNOWN) o.put(k, v ?: JSONObject.NULL)
        return o.put("v", VERSION)
            .put("on", s.on)
            .put("name", s.name)
            .put("reach", s.reach.name)
            .put("whileLocked", s.whileLocked)
            .put("from", time(s.hours.start))
            .put("to", time(s.hours.end))
            .put("brief", s.brief)
            .also { o -> s.busyCalendars?.let { ids -> o.put("busyFrom", org.json.JSONArray(ids.sorted())) } } // l10n: not text (a JSON key)
            .also { o -> if (s.bridges != BridgeChoice.OFF) o.put("bridges", s.bridges.name) } // l10n: not text (a JSON key)
            .also { o -> if (s.ownBridges.isNotEmpty()) o.put("ownBridges", org.json.JSONArray(s.ownBridges.map { it.text })) } // l10n: not text (a JSON key)
            .toString()
    }

    /** None is the defaults (off). */
    fun decode(json: String?): LinkSettingsFile {
        json ?: return LinkSettingsFile.Ok(LinkSettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return LinkSettingsFile.Damaged }
        val v = o.opt("v")
        if (v !is Int && v !is Long) return LinkSettingsFile.Damaged
        val version = (v as Number).toLong()
        if (version > VERSION) return LinkSettingsFile.TooNew(version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (version < 1) return LinkSettingsFile.Damaged
        val d = LinkSettings()
        fun flag(k: String, default: Boolean): Boolean? = if (!o.has(k)) default else o.opt(k) as? Boolean
        fun clock(k: String, default: LocalTime): LocalTime? =
            if (!o.has(k)) default else (o.opt(k) as? String)?.takeIf { TIME.matches(it) }?.let { LocalTime.parse(it) }
        val name = if (!o.has("name")) d.name else (o.opt("name") as? String)?.takeIf { it.isEmpty() || LinkText.ok(it, PairHello.MAX_NAME) }
        val reach = if (!o.has("reach")) d.reach else (o.opt("reach") as? String)?.let { n -> LinkReach.entries.firstOrNull { it.name == n } }
        val from = clock("from", d.hours.start) ?: return LinkSettingsFile.Damaged // l10n: not text (a JSON key)
        val to = clock("to", d.hours.end) ?: return LinkSettingsFile.Damaged // l10n: not text (a JSON key)
        if (from >= to) return LinkSettingsFile.Damaged
        val busyFrom: Set<Long>? = if (!o.has("busyFrom")) null else { // l10n: not text (a JSON key)
            val a = o.opt("busyFrom") as? org.json.JSONArray ?: return LinkSettingsFile.Damaged // l10n: not text (a JSON key)
            if (a.length() > LinkSettings.MAX_CALENDARS) return LinkSettingsFile.Damaged
            (0 until a.length()).map { i -> (a.opt(i) as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it >= 0 } ?: return LinkSettingsFile.Damaged }.toSet()
        }
        val bridges = if (!o.has("bridges")) d.bridges else (o.opt("bridges") as? String)?.let { n -> BridgeChoice.entries.firstOrNull { it.name == n } } // l10n: not text (a JSON key)
            ?: return LinkSettingsFile.Damaged
        val own: List<BridgeLine> = if (!o.has("ownBridges")) emptyList() else { // l10n: not text (a JSON key)
            val a = o.opt("ownBridges") as? org.json.JSONArray ?: return LinkSettingsFile.Damaged // l10n: not text (a JSON key)
            if (a.length() > TorBridges.MAX_OWN) return LinkSettingsFile.Damaged
            // Each line exactly as BridgeLine writes it: a line changed by hand reads as damaged, never as some other bridge.
            (0 until a.length()).map { i -> (a.opt(i) as? String)?.let { t -> BridgeLine.parse(t)?.takeIf { it.text == t } } ?: return LinkSettingsFile.Damaged }
        }
        if (own.distinct().size != own.size || (bridges == BridgeChoice.OWN && own.isEmpty())) return LinkSettingsFile.Damaged
        val s = LinkSettings(
            on = flag("on", d.on) ?: return LinkSettingsFile.Damaged, // l10n: not text (a JSON key)
            name = name ?: return LinkSettingsFile.Damaged,
            reach = reach ?: return LinkSettingsFile.Damaged,
            whileLocked = flag("whileLocked", d.whileLocked) ?: return LinkSettingsFile.Damaged, // l10n: not text (a JSON key)
            hours = EventAlone.Hours(from, to),
            brief = flag("brief", d.brief) ?: return LinkSettingsFile.Damaged, // l10n: not text (a JSON key)
            busyCalendars = busyFrom,
            bridges = bridges,
            ownBridges = own,
        )
        val extra = o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) }
        return LinkSettingsFile.Ok(s, extra)
    }

    /**
     * The text a save of [s] writes over the file's [raw] text, or null to write nothing: a newer Robot's file is never
     * written over; a damaged one is replaced; keys this version doesn't know are kept.
     */
    fun save(raw: String?, s: LinkSettings): String? = when (val now = decode(raw)) {
        is LinkSettingsFile.TooNew -> null
        is LinkSettingsFile.Ok -> encode(s, now.extra)
        LinkSettingsFile.Damaged -> encode(s)
    }

    /** A change made in Settings, applied to the file as it is now ([raw]), so a field another writer just wrote stays. */
    fun update(raw: String?, change: (LinkSettings) -> LinkSettings): String? = when (val now = decode(raw)) {
        is LinkSettingsFile.TooNew -> null
        else -> save(raw, change(now.settings))
    }

    private fun time(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute) // l10n: not text (a time in the file)
}
