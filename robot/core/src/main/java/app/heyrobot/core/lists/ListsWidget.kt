// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.json.JSONObject

// Pure. The home-screen widget's rules (lists-plan.md §5, §7.6, D12; LS6). One list per widget, picked when you place it;
// it shows the list's open items with tick boxes and how many are done, and only ticks. The app (sys/ListsWidget.kt) asks
// these rules what to draw and whether a tick may go through; the vault itself still refuses whatever its own gate refuses.
//
// What it shows:
//   - a lock-screen host (OPTION_APPWIDGET_HOST_CATEGORY keyguard): no items, ever (the manifest asks for the home screen only);
//   - Lists off: a line saying so; no list picked yet: "Tap to pick a list"; no list because Robot's data was cleared since
//     the widget was placed (widgets.json gone, LS12): a line saying so, and that a backup doesn't keep a widget's list;
//   - a phone lock for someone else: "Robot is locked";
//   - your own phone lock: "Robot is locked", unless "Hear and tick on the lock screen" is on: then the items, ticks off;
//   - the screen off or the phone locked: "Unlock to see this list" (no name: the lists can't be opened then), unless
//     "Hear and tick on the lock screen" is on: then the items stay;
//   - otherwise the list's open items (at most [WidgetRules.MAX_DRAWN], then "and 12 more") and "5 done".
//
// widgets.json (files/lists/, written by the main app alone): which list each widget shows, by ids only, never a name.
//
//   {"v":1,"w":{"42":"<the list's 32 hex id>"}}
//
// LS12 (LT12 on the Pixel 8): which list a widget shows isn't in a backup (lists-plan.md §6). A widget's id is numbered by
// Android for the home-screen app on this phone, so another phone's ids mean nothing (Android's own backup renumbers them
// with ACTION_APPWIDGET_RESTORED; a backup file can't), and a binding restored by number could point a widget at a list
// it was never placed for. Only Robot's data cleared on the same phone keeps the widgets, and then the widget says why it
// asks again. The file is made when a widget's picker first opens, and nothing else makes it, so a widget the system
// lists while the file isn't there was placed before Robot's data was cleared.

/** Where and how a widget is drawn now, as the app read it (lock state, switches, the widget's host). */
data class WidgetNow(
    /** Settings › Lists, narrowed by SomeoneElseLock.lists. */
    val settings: ListsSettings,
    /** The list this widget shows, or null: none picked yet. */
    val list: ListsId?,
    /** The screen is on (PowerManager.isInteractive). */
    val interactive: Boolean,
    /** The phone is locked (ListsFiles.locked: the lock screen, a phone lock, or the device locked). */
    val locked: Boolean,
    /** A phone lock (Robot's own) is on, for you or someone else. */
    val phoneLock: Boolean,
    /** That phone lock is for someone else. */
    val someoneElse: Boolean,
    /** The widget's host is the lock screen (a keyguard category). */
    val keyguardHost: Boolean = false,
    /** LS12: Robot's data was cleared since the widget was placed (widgets.json isn't there), so it has no list. */
    val forgot: Boolean = false,
)

/** What a widget says instead of items. */
enum class WidgetLine { UNLOCK, ROBOT_LOCKED, OFF, PICK, PICK_AGAIN, GONE, NOT_NOW, KEY_GONE, UNREADABLE, TOO_NEW }

/** What a widget draws. */
sealed class WidgetView {
    /** [list]'s open items, the first [WidgetRules.MAX_DRAWN] ([more] past them), [done] ticked; [ticks]: tick boxes work. */
    data class Items(val list: ListsList, val items: List<ListItem>, val more: Int, val done: Int, val ticks: Boolean) : WidgetView()
    data class Line(val line: WidgetLine) : WidgetView()
}

/** A tick from the widget: what to do with it. */
sealed class WidgetTick {
    data class Tick(val list: ListsId, val item: ListsId) : WidgetTick()
    data class Untick(val list: ListsId, val item: ListsId) : WidgetTick()
    data class Refused(val why: WidgetRefusal) : WidgetTick()
}

enum class WidgetRefusal { NOT_OURS, BAD, OFF, NO_LIST, ROBOT_LOCKED, LOCKED, HOST }

object WidgetRules {
    /** Items drawn at most (EST, lists-plan.md §7.6): the system caps a widget's views and memory. */
    const val MAX_DRAWN = 50

    /** Whether the hidden lines hold: "Hear and tick on the lock screen" off (as narrowed) means the items stay hidden. */
    private fun keepsItems(n: WidgetNow): Boolean = n.settings.on && n.settings.lockScreen

    /** An item as the widget shows it: its words, then its quantity ("Oat milk (2 L)"), as Share as text writes it. */
    fun label(x: ListItem): String = ItemText.shown(x.text) + (x.qty?.let { " ($it)" } ?: "")  // l10n: not text (an item's own words and quantity)

    /** A stable id for [x] in the widget's collection: the first 64 bits of its random id. */
    fun stableId(x: ListItem): Long = java.lang.Long.parseUnsignedLong(x.id.hex.take(16), 16)

    /** What to draw without opening the lists; null when the lists must be opened to decide. */
    fun before(n: WidgetNow): WidgetView? = when {
        n.keyguardHost -> WidgetView.Line(WidgetLine.UNLOCK)
        !n.settings.on -> WidgetView.Line(WidgetLine.OFF)
        n.list == null -> WidgetView.Line(if (n.forgot) WidgetLine.PICK_AGAIN else WidgetLine.PICK)
        n.someoneElse -> WidgetView.Line(WidgetLine.ROBOT_LOCKED)
        n.phoneLock && !keepsItems(n) -> WidgetView.Line(WidgetLine.ROBOT_LOCKED)
        (!n.interactive || n.locked) && !keepsItems(n) -> WidgetView.Line(WidgetLine.UNLOCK)
        else -> null
    }

    /** What to draw: [before]'s answer, else from the lists as they opened ([load]). */
    fun draw(n: WidgetNow, load: () -> ListsLoad<ListsFile>): WidgetView {
        before(n)?.let { return it }
        val id = checkNotNull(n.list)
        return when (val l = load()) {
            is ListsLoad.Loaded -> {
                val list = l.value.list(id) ?: return WidgetView.Line(WidgetLine.GONE)
                // 1.5: another person's words wait for the unlock, whatever Lists' lock-screen switch says.
                if (n.locked && ListsShared.peerText(list)) return WidgetView.Line(WidgetLine.UNLOCK)
                val open = list.open
                WidgetView.Items(list, open.take(MAX_DRAWN), (open.size - MAX_DRAWN).coerceAtLeast(0), list.done.size, ticks = !n.phoneLock)
            }
            ListsLoad.Empty -> WidgetView.Line(WidgetLine.GONE)
            ListsLoad.Locked -> WidgetView.Line(if (n.phoneLock) WidgetLine.ROBOT_LOCKED else if (n.interactive && !n.locked) WidgetLine.NOT_NOW else WidgetLine.UNLOCK)
            ListsLoad.KeyGone -> WidgetView.Line(WidgetLine.KEY_GONE)
            ListsLoad.Unreadable -> WidgetView.Line(WidgetLine.UNREADABLE)
            is ListsLoad.TooNew -> WidgetView.Line(WidgetLine.TOO_NEW)
        }
    }

    /**
     * A tick or untick the home-screen app sent for the widget [ours] says (null: not one of this provider's widgets). Every
     * part of it is untrusted: [item], the fill-in's item id, and [checked], the box's new state. The item must also be on
     * that widget's list: the vault's tick refuses an item that isn't (ListsEdits' NO_ITEM). Never an add, an edit or a
     * removal; never during a phone lock; while locked only with "Hear and tick on the lock screen" on.
     */
    fun tick(n: WidgetNow, ours: Boolean, item: String?, checked: Boolean?): WidgetTick {
        if (!ours) return WidgetTick.Refused(WidgetRefusal.NOT_OURS)
        val id = ListsId.of(item) ?: return WidgetTick.Refused(WidgetRefusal.BAD)
        if (checked == null) return WidgetTick.Refused(WidgetRefusal.BAD)
        val list = n.list
        return when {
            n.keyguardHost -> WidgetTick.Refused(WidgetRefusal.HOST)
            !n.settings.on -> WidgetTick.Refused(WidgetRefusal.OFF)
            list == null -> WidgetTick.Refused(WidgetRefusal.NO_LIST)
            n.someoneElse || n.phoneLock -> WidgetTick.Refused(WidgetRefusal.ROBOT_LOCKED)
            n.locked && !keepsItems(n) -> WidgetTick.Refused(WidgetRefusal.LOCKED)
            checked -> WidgetTick.Tick(list, id)
            else -> WidgetTick.Untick(list, id)
        }
    }
}

/** widgets.json: which list each widget shows, by ids alone. */
object WidgetBindings {
    const val FILE = "widgets.json"
    const val VERSION = 1
    /** More widgets than anyone places; past it the oldest-numbered binding goes first. */
    const val MAX = 64

    /** What widgets.json says; [writable] false for a newer Robot's file (never written over); [there] false: no file (LS12). */
    data class Read(val bound: Map<Int, ListsId>, val writable: Boolean = true, val there: Boolean = true)

    /** Strict: anything that isn't a widget id (a positive int) mapped to a list id is dropped. No file, or a broken one: none. */
    fun decode(json: String?): Read {
        json ?: return Read(emptyMap(), there = false)
        val o = try { JSONObject(json) } catch (_: Exception) { return Read(emptyMap()) }
        val v = (o.opt("v") as? Number)?.toLong() ?: return Read(emptyMap())
        if (v > VERSION) return Read(emptyMap(), writable = false)
        val w = o.optJSONObject("w") ?: return Read(emptyMap())
        val out = sortedMapOf<Int, ListsId>()
        for (k in w.keys()) {
            val id = k.toIntOrNull()?.takeIf { it > 0 && it.toString() == k } ?: continue
            out[id] = ListsId.of(w.opt(k) as? String) ?: continue
        }
        return Read(out.entries.toList().takeLast(MAX).associate { it.key to it.value })
    }

    fun encode(bound: Map<Int, ListsId>): String {
        val w = JSONObject()
        for ((k, v) in bound.toSortedMap().entries.toList().takeLast(MAX)) w.put(k.toString(), v.hex)
        return JSONObject().put("v", VERSION).put("w", w).toString()
    }

    /** [bound] with [widget] showing [list]. */
    fun bind(bound: Map<Int, ListsId>, widget: Int, list: ListsId): Map<Int, ListsId> {
        require(widget > 0)
        return bound + (widget to list)
    }

    /** Only the widgets the system still lists for Robot's provider ([alive]): one it deleted is forgotten. */
    fun retain(bound: Map<Int, ListsId>, alive: Set<Int>): Map<Int, ListsId> = bound.filterKeys { it in alive }
}
