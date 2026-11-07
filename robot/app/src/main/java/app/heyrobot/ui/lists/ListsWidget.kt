// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.WidgetBindings
import app.heyrobot.core.lists.WidgetLine
import app.heyrobot.core.lists.WidgetNow
import app.heyrobot.core.lists.WidgetRules
import app.heyrobot.core.lists.WidgetTick
import app.heyrobot.core.lists.WidgetView
import app.heyrobot.core.lock.SomeoneElseLock
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.LockedEdit
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.ui.MainActivity
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// MAIN AND VOICE PROCESSES. Robot Lists' home-screen widget, "Robot list" (lists-plan.md §5, §7.6, D12; LS6). Framework
// RemoteViews only. Core's WidgetRules decide what a widget draws and whether a tick goes through; this file reads the
// phone's state for them, draws, and ticks through the vault.
//
// - ListsWidgetProvider (main, exported as a widget provider must be, the APPWIDGET_UPDATE filter alone) reads nothing
//   from its intent, not even widget ids: whatever it is sent, it forgets the widgets the system no longer lists and redraws
//   the ones it does. A forged broadcast can only cause a redraw, and a burst of them one prune and one redraw (LS11a, N1).
// - ListsTickReceiver (main, not exported, no filter) gets a tick box's change through the widget's own mutable template
//   PendingIntent (explicit, its action set, so a fill-in can't change where it goes or what it is): the item's id and the
//   checked state, both untrusted. Only a tick or an untick of an item on that widget's own list, through the vault's
//   lockedEdit (how: widget), never during a phone lock, while locked only with "Hear and tick on the lock screen" on.
// - Redraws: after every write of lists.enc in either process (the vault's hook), a Settings › Lists save, a widget placed
//   or picked again; at every screen-off the items go (the vault drops its RAM copy then too: with a lock delay the phone
//   locks later and nothing says so), and they come back at screen-on when the phone isn't locked, and at unlock. Each draw
//   reads the state it draws for, so a late redraw from the other process never puts items on a locked phone's widgets.
//   When neither process runs at screen-off, the items stay drawn until Robot next runs (§17).
// - widgets.json (files/lists/): which list each widget shows, by ids only; written by the main process alone, and made only
//   when a widget's picker opens (LS12): a widget the system lists while it isn't there says Robot's data was cleared.
//
// Nothing here logs a list's name or an item: codes and counts only.

/**
 * The widget's provider. Whatever it is sent, the widgets the system no longer lists are forgotten and the ones it does
 * redrawn, once for a burst (LS11a, N1: any app may send it broadcasts); it reads nothing from its intent.
 */
class ListsWidgetProvider : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        ListsWidgets.sent(ctx.applicationContext, goAsync())
    }
}

/** A widget's tick box changed (not exported: only the widget's own template PendingIntent reaches it). */
class ListsTickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val i = intent ?: return
        // today-fix-c 8: Robot's own redraw (LockEngine), since the provider takes only the system's broadcasts.
        if (i.action == ListsWidgets.REDRAW) { ListsWidgets.sent(ctx.applicationContext, goAsync()); return }
        if (i.action != ListsWidgets.TICK) return
        // Typed reads: a bad extra is nothing.
        val sent = try {
            Triple(i.getIntExtra(ListsWidgets.EXTRA_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID), i.getStringExtra(ListsWidgets.EXTRA_ITEM),
                if (i.hasExtra(RemoteViews.EXTRA_CHECKED)) i.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false) else null)
        } catch (_: Exception) {
            return
        }
        val app = ctx.applicationContext
        val done = goAsync()
        ListsWidgets.work {
            try {
                ListsWidgets.tick(app, sent.first, sent.second, sent.third)
            } finally {
                done.finish()
            }
        }
    }
}

/** The widgets in this process: drawing, ticking, which list each shows, and the screen-off and unlock redraws. */
object ListsWidgets {
    /** The tick template's action (explicit to ListsTickReceiver; set, so a fill-in can't supply one). */
    const val TICK = "app.heyrobot.lists.WIDGET_TICK" // l10n: not text (an intent action)
    /** Robot's own redraw of every lists widget (LockSignals.LIST_REDRAW_ACTION). */
    const val REDRAW = "app.heyrobot.lists.WIDGET_REDRAW" // l10n: not text (an intent action)
    const val EXTRA_WIDGET = "w" // l10n: not text (an extra's key)
    const val EXTRA_ITEM = "i" // l10n: not text (an extra's key)

    private val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-lists-widget").apply { isDaemon = true } } }
    private val pending = AtomicBoolean(false)
    /** The provider was sent something since the last redraw began: forget the widgets the system no longer lists first. */
    private val prunePending = AtomicBoolean(false)
    /** The provider's broadcasts held open (goAsync) until the redraw that covers them ends. */
    private val held = ConcurrentLinkedQueue<BroadcastReceiver.PendingResult>()
    @Volatile private var started = false

    fun work(block: () -> Unit) = worker.execute {
        try { block() } catch (t: Throwable) { RLog.w("lists_widget", t) }
    }

    private fun provider(ctx: Context) = ComponentName(ctx, ListsWidgetProvider::class.java)

    /** The widgets the system lists for Robot's provider: the only ids ever drawn, ticked or picked for. */
    fun ids(ctx: Context): Set<Int> = try {
        AppWidgetManager.getInstance(ctx)?.getAppWidgetIds(provider(ctx))?.toSet().orEmpty()
    } catch (t: Exception) {
        RLog.w("lists_widget_ids", t)
        emptySet()
    }

    /** [widget] is one of Robot's list widgets now. */
    fun ours(ctx: Context, widget: Int): Boolean = widget != AppWidgetManager.INVALID_APPWIDGET_ID && widget in ids(ctx)

    /**
     * At the process's start (RobotApp, main and :voice only): redraw after every change of the lists in this process, and
     * at screen-off (the items go), screen-on and unlock (they come back when the phone isn't locked).
     */
    fun start(app: Context) {
        if (ListsFiles.process() == null || started) return
        started = true
        ListsFiles.onChange = { changed(it) }
        try {
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = changed(app)
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) { RLog.w("lists_widget_hooks", t) }
    }

    /** The lists, their settings or the screen changed: every widget drawn again, soon (one redraw for a burst of changes). */
    fun changed(ctx: Context) {
        val app = ctx.applicationContext
        if (!pending.compareAndSet(false, true)) return
        work {
            pending.set(false)
            // The broadcasts that arrived before this redraw began; later ones queued another.
            val waiting = generateSequence { held.poll() }.toList()
            try {
                if (prunePending.getAndSet(false)) prune(app)
                redrawNow(app)
            } finally {
                waiting.forEach { it.finish() }
            }
        }
    }

    /**
     * [MAIN] LS11a (N1): the provider was sent a broadcast (by the system, or by any app). The widgets the system no longer
     * lists are forgotten and the rest redrawn through [changed], so a burst costs one prune and one redraw; [done] (the
     * broadcast's goAsync, null when it was called directly) is finished once that redraw ends.
     */
    fun sent(ctx: Context, done: BroadcastReceiver.PendingResult?) {
        prunePending.set(true)
        if (done != null) held.add(done)
        changed(ctx)
    }

    // widgets.json: which list each widget shows.

    private fun file(ctx: Context): File = File(ListsFiles.dir(ctx), WidgetBindings.FILE)
    private fun lock(ctx: Context): File = File(ListsFiles.dir(ctx), "widgets.lock") // l10n: not text (a file name)

    fun bindings(ctx: Context): Map<Int, ListsId> = WidgetBindings.decode(readOrNull(file(ctx))).bound

    /** [MAIN] [widget] now shows [list] (the setup window, after your tap). False when it wasn't saved. */
    fun bind(ctx: Context, widget: Int, list: ListsId): Boolean {
        if (ListsFiles.process() != "main" || !ours(ctx, widget)) return false
        return save(ctx) { WidgetBindings.bind(it, widget, list) }
    }

    /** [MAIN] The widgets the system no longer lists are forgotten (a missing widgets.json stays missing: LS12). */
    fun prune(ctx: Context) {
        if (ListsFiles.process() != "main") return
        val alive = ids(ctx)
        save(ctx, make = false) { WidgetBindings.retain(it, alive) }
        RLog.i("lists_widget_pruned", "n" to alive.size)
    }

    /**
     * [MAIN] LS12: the picker opened for one of Robot's widgets. widgets.json is made if it isn't there, so a new widget you
     * back out of says "Tap to pick a list.", not that Robot's data was cleared.
     */
    fun placed(ctx: Context) {
        if (ListsFiles.process() != "main") return
        save(ctx) { it }
    }

    private fun save(ctx: Context, make: Boolean = true, change: (Map<Int, ListsId>) -> Map<Int, ListsId>): Boolean = try {
        withFileLock(lock(ctx)) {
            val read = WidgetBindings.decode(readOrNull(file(ctx)))
            if (!read.writable) return@withFileLock false
            if (!read.there && !make) return@withFileLock true
            val next = change(read.bound)
            if (next != read.bound || !file(ctx).exists()) writeAtomic(file(ctx), WidgetBindings.encode(next))
            true
        }
    } catch (t: Exception) {
        RLog.w("lists_widget_save", t)
        false
    }

    // Drawing.

    /** What [widget] is drawn for now; [list] the list it shows; [forgot]: Robot's data was cleared since it was placed. */
    private fun now(ctx: Context, m: AppWidgetManager, widget: Int, list: ListsId?, forgot: Boolean = false): WidgetNow {
        val someoneElse = PhoneLockNow.someoneElse(ctx)
        val host = try {
            val cat = m.getAppWidgetOptions(widget).getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            cat and AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD != 0
        } catch (_: Exception) {
            true
        }
        val interactive = try { ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: false } catch (_: Exception) { false }
        return WidgetNow(SomeoneElseLock.lists(ListsSettingsStore.current(ctx), someoneElse), list, interactive = interactive,
            locked = ListsFiles.locked(ctx), phoneLock = PhoneLockNow.on(ctx), someoneElse = someoneElse, keyguardHost = host, forgot = forgot)
    }

    /** Every widget drawn as things are now (on this process's worker); [vault]: this process's (tests give theirs). */
    fun redrawNow(ctx: Context, vault: () -> ListsVault = { ListsFiles.vault(ctx) }) {
        val m = AppWidgetManager.getInstance(ctx) ?: return
        val ids = ids(ctx)
        if (ids.isEmpty()) return
        val read = WidgetBindings.decode(readOrNull(file(ctx)))
        val bound = read.bound
        // The lists opened at most once for all the widgets, and only when one of them needs them.
        var opened: ListsLoad<ListsFile>? = null
        val load = { opened ?: vault().load(firstUse = false).also { opened = it } }
        for (id in ids) {
            try {
                val view = WidgetRules.draw(now(ctx, m, id, bound[id], forgot = !read.there), load)
                m.updateAppWidget(id, views(ctx, id, view))
            } catch (t: Exception) {
                RLog.w("lists_widget_draw", t)
            }
        }
        RLog.i("lists_widget_drawn", "n" to ids.size)
    }

    private fun views(ctx: Context, widget: Int, v: WidgetView): RemoteViews {
        val r = RemoteViews(ctx.packageName, R.layout.lists_widget)
        val res = ctx.resources
        // The collection is always set again: a widget that hides its items keeps none in the home-screen app either.
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
        when (v) {
            is WidgetView.Items -> {
                val name = listLabel(v.list, AppLanguage.langs(ctx))
                r.setTextViewText(R.id.lists_widget_title, name)
                r.setContentDescription(R.id.lists_widget_title, ctx.getString(R.string.lists_card_open, name))
                val open = openList(ctx, widget, v.list.id)
                r.setOnClickPendingIntent(R.id.lists_widget_title, open)
                for (x in v.items) {
                    val row = RemoteViews(ctx.packageName, R.layout.lists_widget_item)
                    row.setTextViewText(R.id.lists_widget_item, WidgetRules.label(x))
                    row.setCompoundButtonChecked(R.id.lists_widget_item, false)
                    if (v.ticks) row.setOnCheckedChangeResponse(R.id.lists_widget_item,
                        RemoteViews.RemoteResponse.fromFillInIntent(Intent().putExtra(EXTRA_ITEM, x.id.hex)))
                    else row.setBoolean(R.id.lists_widget_item, "setEnabled", false) // l10n: not text (a view method)
                    items.addItem(WidgetRules.stableId(x), row)
                }
                r.setPendingIntentTemplate(R.id.lists_widget_items, tickTemplate(ctx, widget))
                val empty = when {
                    v.items.isNotEmpty() -> null
                    v.done > 0 -> ctx.getString(R.string.lists_all_ticked)
                    else -> ctx.getString(R.string.lists_widget_empty)
                }
                line(r, empty)
                r.setOnClickPendingIntent(R.id.lists_widget_line, open)
                // LS11a (N2): someone else's list from a backup says so, as the Lists screens do, so their "Shopping" never
                // passes for yours.
                val foot = listOfNotNull(
                    if (v.list.from == ListFrom.BACKUP) ctx.getString(R.string.lists_from_backup) else null,
                    if (v.list.from == ListFrom.PEER) ctx.getString(R.string.lists_from_peer) else null,
                    if (v.more > 0) res.getQuantityString(R.plurals.lists_widget_more, v.more, v.more) else null,
                    if (v.done > 0) res.getQuantityString(R.plurals.lists_widget_done, v.done, v.done) else null,
                ).joinToString(" · ") // l10n: not text (a separator between two counts)
                r.setTextViewText(R.id.lists_widget_done, foot)
                r.setViewVisibility(R.id.lists_widget_done, if (foot.isEmpty()) View.GONE else View.VISIBLE)
                r.setOnClickPendingIntent(R.id.lists_widget_done, open)
            }
            is WidgetView.Line -> {
                r.setTextViewText(R.id.lists_widget_title, ctx.getString(R.string.lists_widget_label))
                r.setContentDescription(R.id.lists_widget_title, null)
                // Drawn over what was there (the home-screen app reapplies the same layout): the taps that opened the list go.
                r.setOnClickPendingIntent(R.id.lists_widget_title, null)
                r.setOnClickPendingIntent(R.id.lists_widget_done, null)
                line(r, ctx.getString(when (v.line) {
                    WidgetLine.UNLOCK -> R.string.lists_widget_unlock
                    WidgetLine.ROBOT_LOCKED -> R.string.lists_widget_robot_locked
                    WidgetLine.OFF -> R.string.lists_widget_off
                    WidgetLine.PICK -> R.string.lists_widget_pick
                    WidgetLine.PICK_AGAIN -> R.string.lists_widget_pick_again
                    WidgetLine.GONE -> R.string.lists_gone
                    WidgetLine.NOT_NOW -> R.string.lists_not_now
                    WidgetLine.KEY_GONE -> R.string.lists_key_gone
                    WidgetLine.UNREADABLE -> R.string.lists_unreadable
                    WidgetLine.TOO_NEW -> R.string.lists_too_new
                }))
                r.setViewVisibility(R.id.lists_widget_done, View.GONE)
                // Picking a list (none yet, or its list is gone) is a tap away; nothing else opens from a line.
                r.setOnClickPendingIntent(R.id.lists_widget_line, if (v.line in PICKS) pick(ctx, widget) else null)
            }
        }
        r.setRemoteAdapter(R.id.lists_widget_items, items.build())
        return r
    }

    /** The lines a tap on opens the picker: no list yet, its list gone, or Robot's data cleared (LS12). */
    private val PICKS = setOf(WidgetLine.PICK, WidgetLine.PICK_AGAIN, WidgetLine.GONE)

    /** [text] instead of the items (null: the items show). */
    private fun line(r: RemoteViews, text: String?) {
        r.setTextViewText(R.id.lists_widget_line, text.orEmpty())
        r.setViewVisibility(R.id.lists_widget_line, if (text == null) View.GONE else View.VISIBLE)
        r.setViewVisibility(R.id.lists_widget_items, if (text == null) View.VISIBLE else View.GONE)
    }

    /** The list [id]'s page in the app (AppPages.LISTS): an id only. Immutable. */
    private fun openList(ctx: Context, widget: Int, id: ListsId): PendingIntent = PendingIntent.getActivity(ctx, widget,
        Intent(ctx, MainActivity::class.java).putExtra(AppPages.EXTRA, AppPages.listRoute(id.hex)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setIdentifier(identity(widget)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The window that picks [widget]'s list. Immutable. */
    private fun pick(ctx: Context, widget: Int): PendingIntent = PendingIntent.getActivity(ctx, widget,
        Intent(ctx, ListsWidgetSetup::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setIdentifier(identity(widget)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * LS10 (L3): the widget's own PendingIntents' identity. A PendingIntent is its request code (here the widget's id, which
     * Android numbers across every widget ever placed) and its intent's filterEquals, never the extras; with an identifier of
     * their own, widget 47's title can't share one with a notice that opens MainActivity under code 47 (sys/Problems.kt),
     * where FLAG_UPDATE_CURRENT would set the page for both.
     */
    private fun identity(widget: Int): String = "lists-widget:$widget" // l10n: not text (a PendingIntent's identity)

    /**
     * The tick boxes' template: explicit to ListsTickReceiver, its action set, the widget's id in it. Mutable only because
     * the home-screen app fills in each item's id and the system the checked state (FLAG_MUTABLE, as a collection's template
     * must be); both are checked again in [tick].
     */
    private fun tickTemplate(ctx: Context, widget: Int): PendingIntent = PendingIntent.getBroadcast(ctx, widget,
        Intent(ctx, ListsTickReceiver::class.java).setAction(TICK).putExtra(EXTRA_WIDGET, widget),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A tick box changed on [widget] (all three untrusted): ticked or put back through the vault, then every widget redrawn. */
    fun tick(ctx: Context, widget: Int, item: String?, checked: Boolean?, vault: () -> ListsVault = { ListsFiles.vault(ctx) }) {
        val m = AppWidgetManager.getInstance(ctx) ?: return
        val ours = ours(ctx, widget)
        val t = WidgetRules.tick(now(ctx, m, widget, if (ours) bindings(ctx)[widget] else null), ours, item, checked)
        val w = when (t) {
            is WidgetTick.Refused -> {
                RLog.i("lists_widget_tick", "refused" to RLog.Code(t.why.toString().lowercase(java.util.Locale.ROOT)))
                null
            }
            is WidgetTick.Tick -> vault().lockedEdit(LockedEdit.Tick(t.list, t.item), ItemHow.WIDGET)
            is WidgetTick.Untick -> vault().lockedEdit(LockedEdit.Untick(t.list, t.item), ItemHow.WIDGET)
        }
        // :voice drops what it holds; a refused or failed tick is drawn back as it was.
        if (w?.done?.changed == true) Cmd.send(ctx, Cmd.LISTS)
        redrawNow(ctx, vault)
    }
}
