// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.json.JSONObject

// Pure. Settings › Files (files-1.4-plan.md §6.6, D12): files_settings.json v1 in files/robot/, beside memory.json. The
// main app writes it (sys/FilesVault.kt's FilesSettingsStore); :voice reads it. Never in settings.json (its schema stays
// 13) and never one of the backup's 8 Settings files: a ninth would make a 1.1 reader refuse the whole Settings category,
// so it goes in the backup's own FILES category (§2.8, F11), as skill_settings.json goes with Skills.
//
//   {"v":1,"on":true,"receipts":"AUTO","bills":"AUTO","documents":"AUTO","photos":"AUTO","other":"AUTO",
//    "readImageText":true,"stripLocation":true,"reminders":"ASK","cleanup":true,"brief":true}
//
// A key this version doesn't know is kept and written back as it was (a later 1.4 job's row). A known key with a wrong
// type or a value this version doesn't know makes the file Damaged (the defaults apply, travel mode as below; a save writes a new file): a new
// value is a new version. A newer version is TooNew and never written over.
//
// FX1 (files-1.4-review.md finding 5): a Damaged or TooNew file still gives travel mode's two keys if they can be read
// (`travel` and `hidden`, each on its own), and fails closed if they can't: travel mode counts as on with the hidden
// drawers unknown ([FilesSettings.hiddenUnknown]), so every drawer is out of sight until your PIN turns travel mode off.

/** A kind's filing level (§4.1): File it on its own (the default), Ask me first, Off (it stays in Inbox, not read). */
enum class FileLevel { AUTO, ASK, OFF }

/** Reminders from files (§7.2, D11): Ask me first (the default), Add to my brief on its own, Off. The calendar always takes a card. */
enum class FileReminders { ASK, BRIEF, OFF }

/**
 * Settings › Files' values, at the plan's defaults (§6.6). F12, travel mode: [hidden] drawers (a starter drawer's id or
 * your drawer's name) are out of sight everywhere while [travel] is on, until you confirm with the phone's PIN (TravelMode).
 * A privacy screen, not encryption: the files stay sealed as before, under the same key.
 */
data class FilesSettings(
    /** Robot Files on: it holds nothing until you add a file (F5). */
    val on: Boolean = true,
    val receipts: FileLevel = FileLevel.AUTO,
    val bills: FileLevel = FileLevel.AUTO,
    val documents: FileLevel = FileLevel.AUTO,
    val photos: FileLevel = FileLevel.AUTO,
    val other: FileLevel = FileLevel.AUTO,
    /** Read text in images (with Robot's own model, §4.2). */
    val readImageText: Boolean = true,
    /** Strip location from photos I send (§5.2). */
    val stripLocation: Boolean = true,
    val reminders: FileReminders = FileReminders.ASK,
    /** Clean-up suggestions: a list for your tap, never a delete (§7.4). */
    val cleanup: Boolean = true,
    /** Show in morning brief (only while unlocked, §7.2). */
    val brief: Boolean = true,
    /** F12: travel mode on: [hidden] drawers out of sight until the phone's PIN. */
    val travel: Boolean = false,
    /** F12: the drawers travel mode hides (none by default). */
    val hidden: Set<String> = emptySet(),
    /**
     * FX1: files_settings.json couldn't say which drawers are hidden (damaged, or a newer Robot's): with [travel] on, every
     * drawer is out of sight (TravelMode). Never written: a file is never saved with travel mode on and this set
     * (FilesSettingsWrite refuses it), so only turning travel mode off, with your PIN, clears it.
     */
    val hiddenUnknown: Boolean = false,
) {
    init {
        require(hidden.size <= MAX_HIDDEN && hidden.all(FileIndexCodec::label))
    }

    companion object {
        /** Hidden drawers, at most: every starter drawer and every drawer of yours. */
        const val MAX_HIDDEN = FileViews.MAX_DRAWERS + 8

        /** FX1: what a file that can't say anything about travel mode gives: on, every drawer hidden. */
        val CLOSED = FilesSettings(travel = true, hiddenUnknown = true)
    }
}

/** What files_settings.json gave. */
sealed class FilesSettingsFile {
    /** [extra]: the keys this version doesn't know, kept to write back. */
    data class Ok(val read: FilesSettings, val extra: Map<String, Any?> = emptyMap()) : FilesSettingsFile()
    /** FX1: [safe], the defaults with travel mode's keys read leniently from it (FilesSettingsCodec.safe). */
    data class TooNew(val v: Int, val safe: FilesSettings = FilesSettings()) : FilesSettingsFile()
    /** FX1: [safe] as for [TooNew]; [FilesSettings.CLOSED] when nothing of it can be read. */
    data class Damaged(val safe: FilesSettings = FilesSettings.CLOSED) : FilesSettingsFile()

    /** What applies: the file's; the defaults with travel mode's keys as read (or closed) when it can't be read whole. */
    val settings: FilesSettings get() = when (this) {
        is Ok -> read
        is TooNew -> safe
        is Damaged -> safe
    }
}

object FilesSettingsCodec {
    const val FILE = "files_settings.json"
    const val VERSION = 1
    private val LEVELS = listOf("receipts", "bills", "documents", "photos", "other")
    private val FLAGS = listOf("on", "readImageText", "stripLocation", "cleanup", "brief")
    private val KNOWN = setOf("v", "reminders", "travel", "hidden") + LEVELS + FLAGS

    fun encode(s: FilesSettings, extra: Map<String, Any?> = emptyMap()): String {
        val o = JSONObject()
        for ((k, v) in extra) if (k !in KNOWN) o.put(k, v ?: JSONObject.NULL)
        return o.put("v", VERSION)
            .put("on", s.on)
            .put("receipts", s.receipts.name).put("bills", s.bills.name).put("documents", s.documents.name)
            .put("photos", s.photos.name).put("other", s.other.name)
            .put("readImageText", s.readImageText)
            .put("stripLocation", s.stripLocation)
            .put("reminders", s.reminders.name)
            .put("cleanup", s.cleanup)
            .put("brief", s.brief)
            // F12: written only when set, so a file without travel mode reads as before F12 (and older 1.4 Robots keep them).
            .apply { if (s.travel) put("travel", true) }
            .apply { if (s.hidden.isNotEmpty()) put("hidden", org.json.JSONArray(s.hidden.sorted())) }
            .toString()
    }

    /** None is the defaults. */
    fun decode(json: String?): FilesSettingsFile {
        json ?: return FilesSettingsFile.Ok(FilesSettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return FilesSettingsFile.Damaged() }
        val damaged = FilesSettingsFile.Damaged(safe(o))
        val v = o.opt("v")
        if (v !is Int && v !is Long) return damaged
        val version = (v as Number).toLong()
        if (version > VERSION) return FilesSettingsFile.TooNew(version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), safe(o))
        if (version < 1) return damaged
        val d = FilesSettings()
        fun flag(k: String, default: Boolean): Boolean? = if (!o.has(k)) default else o.opt(k) as? Boolean
        fun level(k: String, default: FileLevel): FileLevel? = if (!o.has(k)) default else (o.opt(k) as? String)?.let { n -> FileLevel.entries.firstOrNull { it.name == n } }
        val reminders = if (!o.has("reminders")) d.reminders else (o.opt("reminders") as? String)?.let { n -> FileReminders.entries.firstOrNull { it.name == n } }
        val s = FilesSettings(
            on = flag("on", d.on) ?: return damaged,
            receipts = level("receipts", d.receipts) ?: return damaged,
            bills = level("bills", d.bills) ?: return damaged,
            documents = level("documents", d.documents) ?: return damaged,
            photos = level("photos", d.photos) ?: return damaged,
            other = level("other", d.other) ?: return damaged,
            readImageText = flag("readImageText", d.readImageText) ?: return damaged,
            stripLocation = flag("stripLocation", d.stripLocation) ?: return damaged,
            reminders = reminders ?: return damaged,
            cleanup = flag("cleanup", d.cleanup) ?: return damaged,
            brief = flag("brief", d.brief) ?: return damaged,
            travel = flag("travel", d.travel) ?: return damaged,
            hidden = hidden(o) ?: return damaged,
        )
        val extra = o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) }
        return FilesSettingsFile.Ok(s, extra)
    }

    /**
     * FX1: the defaults with travel mode's keys read on their own from a file that can't be read whole. `travel` known off:
     * off (the hidden drawers kept when they can be read). On, or not known: on, with the hidden drawers if they can be
     * read, else every drawer hidden ([FilesSettings.CLOSED]).
     */
    fun safe(o: JSONObject): FilesSettings {
        val travel = if (!o.has("travel")) false else o.opt("travel") as? Boolean
        val hidden = hidden(o)
        return when {
            travel == false -> FilesSettings(hidden = hidden.orEmpty())
            hidden != null -> FilesSettings(travel = true, hidden = hidden)
            else -> FilesSettings.CLOSED
        }
    }

    /**
     * FX1: what a newer Robot's file [raw] becomes when travel mode's keys change to [s]'s (turning it off with your PIN,
     * or on): only `travel` and `hidden` are touched, the rest is left as it was. Null when [s] changes anything else, when
     * it can't be written (travel mode on with the hidden drawers unknown), or [raw] isn't JSON.
     */
    fun patchTravel(raw: String, now: FilesSettings, s: FilesSettings): String? {
        if (s.copy(travel = now.travel, hidden = now.hidden, hiddenUnknown = now.hiddenUnknown) != now) return null
        if (s.travel && s.hiddenUnknown) return null
        val o = try { JSONObject(raw) } catch (_: Exception) { return null }
        if (s.travel) o.put("travel", true) else o.remove("travel")
        // Hidden drawers that couldn't be read (only with travel mode now off) stay as the newer Robot wrote them.
        if (!s.hiddenUnknown) { if (s.hidden.isEmpty()) o.remove("hidden") else o.put("hidden", org.json.JSONArray(s.hidden.sorted())) }
        return o.toString()
    }
}

/**
 * FX1: what a save makes of files_settings.json, decided over the file as it is then (the store reads it under its
 * cross-process lock). Pure, so the rules are tested here.
 */
object FilesSettingsWrite {
    /**
     * The text to write for a save of [s] over the file's [raw] text, or null to write nothing. [pin]: your PIN just
     * confirmed it (travel mode off, a drawer unhidden while it is on); without it, travel mode stays as it is on in the file
     * (TravelMode.kept). Never travel mode on with its drawers unknown (nothing safe to write); a newer Robot's file only
     * has travel mode's keys changed, and only when nothing else is (FilesSettingsCodec.patchTravel).
     */
    fun save(raw: String?, s: FilesSettings, pin: Boolean): String? {
        val now = FilesSettingsCodec.decode(raw)
        val next = if (pin) s else TravelMode.kept(now.settings, s)
        if (next.travel && next.hiddenUnknown) return null
        return written(raw, now, next)
    }

    /**
     * FX3: a change made in Settings › Files, applied to the file as it is now ([raw], read under the store's lock), so a
     * field another writer just wrote (a restore's finish, the other process) stays unless [change] itself changes it; a
     * page's older copy of the other fields is never written back. Then as [save].
     */
    fun update(raw: String?, pin: Boolean, change: (FilesSettings) -> FilesSettings): String? =
        save(raw, change(FilesSettingsCodec.decode(raw).settings), pin)

    /**
     * FX3: your drawer [old] was renamed [new]: hidden under its new name in the old one's place (TravelMode.renamed), over
     * the file as it is now. No PIN: nothing comes into sight (the drawer called [old] is gone), and since the old name
     * leaves as the new one comes, the new name can't be left out for want of room (MAX_HIDDEN). Null: nothing to write.
     */
    fun rename(raw: String?, old: String, new: String): String? {
        val now = FilesSettingsCodec.decode(raw)
        val next = TravelMode.renamed(now.settings, old, new)
        if (next == now.settings || (next.travel && next.hiddenUnknown)) return null
        return written(raw, now, next)
    }

    private fun written(raw: String?, now: FilesSettingsFile, next: FilesSettings): String? = when (now) {
        is FilesSettingsFile.TooNew -> raw?.let { FilesSettingsCodec.patchTravel(it, now.settings, next) }
        is FilesSettingsFile.Ok -> FilesSettingsCodec.encode(next, now.extra)
        is FilesSettingsFile.Damaged -> FilesSettingsCodec.encode(next)
    }
}

/** F12: "hidden", a list of drawers' names, or null when it isn't one (the file is then Damaged). */
private fun hidden(o: JSONObject): Set<String>? {
    if (!o.has("hidden")) return emptySet()
    val a = o.opt("hidden") as? org.json.JSONArray ?: return null
    if (a.length() > FilesSettings.MAX_HIDDEN) return null
    val out = LinkedHashSet<String>()
    for (i in 0 until a.length()) out += (a.opt(i) as? String)?.takeIf(FileIndexCodec::label) ?: return null
    return out
}

/** What Robot Files holds: [files] and their [bytes]. */
data class FileUsage(val files: Int, val bytes: Long) {
    companion object {
        /** From the index: each file's own size, as it was added. */
        fun of(index: FileIndex): FileUsage = FileUsage(index.entries.size, index.entries.sumOf { it.from.size.coerceAtLeast(0) })
    }
}
