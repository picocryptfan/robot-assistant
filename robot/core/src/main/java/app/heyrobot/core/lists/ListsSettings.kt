// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.json.JSONObject

// Pure. Settings › Lists (lists-plan.md §7.5, D10): lists_settings.json v1 in files/robot/, beside memory.json and
// files_settings.json. The main app writes it (sys/ListsVault.kt's ListsSettingsStore); both app processes read it. Never in
// settings.json (its schema stays 13) and never one of the backup's 8 Settings files: a ninth would make a 1.1 reader refuse
// the whole Settings category, so it goes inside Lists' own category (§6, LS7).
//
//   {"v":1,"on":true,"addLocked":true,"lockScreen":false}
//
// A key this version doesn't know is kept and written back as it was. A known key with a wrong type makes the file Damaged
// (a save writes a new one); a newer version is TooNew and never written over. Either way the two lock switches fail
// closed: what applies reads them off ("Lists" itself is read on its own when it can be), so nothing widens what works on
// a locked phone while the file can't be read whole. Nothing here changes a key or the journal: only a switch you turn
// does, through the vault (ListsVault.follow), before the file is saved.

/** Settings › Lists' values, at the plan's defaults (§7.5). */
data class ListsSettings(
    /** Lists on: Robot hears list phrases (LS3); off, they reach the model as before. */
    val on: Boolean = true,
    /** "Add to lists while locked" (Q3): an add said to the locked phone waits, sealed, in the journal until you unlock. */
    val addLocked: Boolean = true,
    /** "Hear and tick on the lock screen": anyone who can talk to your locked phone could hear your lists and tick items. */
    val lockScreen: Boolean = false,
) {
    /** robot_lists_lock_v1 is needed: for the journal (adds while locked) or the lists themselves (the lock-screen switch). */
    val lockKey: Boolean get() = addLocked || lockScreen
}

/** What lists_settings.json gave. */
sealed class ListsSettingsFile {
    /** [extra]: the keys this version doesn't know, kept to write back. */
    data class Ok(val read: ListsSettings, val extra: Map<String, Any?> = emptyMap()) : ListsSettingsFile()
    /** A newer Robot's: never written over. [safe]: what applies, both lock switches off. */
    data class TooNew(val v: Int, val safe: ListsSettings) : ListsSettingsFile()
    /** Broken: [safe] applies, both lock switches off; a save writes a new file. */
    data class Damaged(val safe: ListsSettings) : ListsSettingsFile()

    /** What applies now. */
    val settings: ListsSettings get() = when (this) {
        is Ok -> read
        is TooNew -> safe
        is Damaged -> safe
    }
}

object ListsSettingsCodec {
    const val FILE = "lists_settings.json"
    const val VERSION = 1
    private val FLAGS = listOf("on", "addLocked", "lockScreen")  // l10n: not text (JSON keys)
    private val KNOWN = setOf("v") + FLAGS  // l10n: not text (JSON keys)

    /** [s] as the file's JSON, [extra] (keys this version doesn't know) kept. */
    fun encode(s: ListsSettings, extra: Map<String, Any?> = emptyMap()): String {
        val o = JSONObject()
        for ((k, v) in extra) if (k !in KNOWN) o.put(k, v ?: JSONObject.NULL)
        return o.put("v", VERSION).put("on", s.on).put("addLocked", s.addLocked).put("lockScreen", s.lockScreen).toString()
    }

    /** None is the defaults. */
    fun decode(json: String?): ListsSettingsFile {
        json ?: return ListsSettingsFile.Ok(ListsSettings())
        val o = try { JSONObject(json) } catch (_: Exception) { return ListsSettingsFile.Damaged(safe(null)) }
        val damaged = ListsSettingsFile.Damaged(safe(o))
        val v = o.opt("v")
        if (v !is Int && v !is Long) return damaged
        val version = (v as Number).toLong()
        if (version > VERSION) return ListsSettingsFile.TooNew(version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), safe(o))
        if (version < 1) return damaged
        val d = ListsSettings()
        fun flag(k: String, default: Boolean): Boolean? = if (!o.has(k)) default else o.opt(k) as? Boolean
        val s = ListsSettings(
            on = flag("on", d.on) ?: return damaged,
            addLocked = flag("addLocked", d.addLocked) ?: return damaged,
            lockScreen = flag("lockScreen", d.lockScreen) ?: return damaged,
        )
        return ListsSettingsFile.Ok(s, o.keys().asSequence().filter { it !in KNOWN }.associateWith { o.opt(it) })
    }

    /** A file that can't be read whole: "Lists" as it says when it can, both lock switches off. */
    private fun safe(o: JSONObject?): ListsSettings = ListsSettings(on = o?.opt("on") as? Boolean ?: true, addLocked = false, lockScreen = false)

    /**
     * The text to write: [change] applied to the file as it is ([raw]), its unknown keys kept. Null when nothing may be
     * written: a newer Robot's file is never written over.
     */
    fun update(raw: String?, change: (ListsSettings) -> ListsSettings): String? = when (val f = decode(raw)) {
        is ListsSettingsFile.Ok -> encode(change(f.read), f.extra)
        is ListsSettingsFile.Damaged -> encode(change(f.safe))
        is ListsSettingsFile.TooNew -> null
    }
}
