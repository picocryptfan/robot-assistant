// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.lists

import android.app.Application
import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.View
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListItem
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.NewItem
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.ListsFiles
import app.heyrobot.sys.ListsSettingsStore
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.SoftListsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lists' home-screen widget in the main process (lists-plan.md §5, §7.6, §14's ListsWidgetTest; LS6): a widget bound to a
 * list draws its open items and how many are done; a tick from it ticks that item, as "on the widget", and tells :voice; a
 * tick for a widget that isn't Robot's, or for an item on another list, changes nothing; with the screen off or the phone
 * locked the items go and come back; a widget with no list asks for one; a widget the system deleted is forgotten. One test:
 * the vault, with software keys, over this run's files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListsWidgetTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val m = AppWidgetManager.getInstance(ctx)
    private val random = SecureRandom()

    // Software keys (Robolectric has no Keystore); the lock and the settings as the phone's vault reads them.
    private val v = ListsVault(java.io.File(ListsFiles.dir(ctx).path), SoftListsKeys(), settings = { ListsSettingsStore.current(ctx) }, someoneElse = { false },
        locked = { ListsFiles.locked(ctx) })
    private val vault = { v }
    private fun file(): ListsFile = (v.load() as ListsLoad.Loaded).value
    private fun view(id: Int): View = shadowOf(m).getViewFor(id)
    private fun title(id: Int) = view(id).findViewById<TextView>(R.id.lists_widget_title).text.toString()
    private fun line(id: Int) = view(id).findViewById<TextView>(R.id.lists_widget_line).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }
    private fun foot(id: Int) = view(id).findViewById<TextView>(R.id.lists_widget_done).let { if (it.visibility == View.VISIBLE) it.text.toString() else null }
    private fun rows(id: Int): Int = view(id).findViewById<ListView>(R.id.lists_widget_items).let { if (it.visibility == View.VISIBLE) it.adapter?.count ?: 0 else 0 }
    private fun lastCmd(): String? = shadowOf(ApplicationProvider.getApplicationContext<Application>()).broadcastIntents.lastOrNull { it.action == Cmd.ACTION }?.getStringExtra("op")

    @Test fun theWidgetDrawsAndOnlyTicks() {
        // RobotApp started the widgets' hooks, which redraw on their own worker with the Keystore's vault: this test draws itself.
        ListsFiles.onChange = null
        val provider = ComponentName(ctx, ListsWidgetProvider::class.java)
        // Each update inflated afresh: under Robolectric a reapplied collection kept its first items (Android's own reapply sets them again).
        shadowOf(m).setAlwaysRecreateViewsDuringUpdate(true)
        shadowOf(m).bindAppWidgetId(7, provider)
        shadowOf(m).bindAppWidgetId(9, provider)
        // Another app's widget id, bound to someone else's provider.
        shadowOf(m).bindAppWidgetId(11, ComponentName("com.example.other", "com.example.other.Widget"))
        assertEquals(setOf(7, 9), ListsWidgets.ids(ctx))

        // Shopping with three items, one ticked; To-do with one.
        val start = file()
        val shopping = start.lists[0].id
        val todo = start.lists[1].id
        val ids = { ListsId.new(random) }
        v.update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, shopping, listOf(NewItem("oat milk", "2 L"), NewItem("eggs"), NewItem("bread")), who, ids) }
        v.update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, todo, listOf(NewItem("call the dentist")), who, ids) }
        val eggs = file().list(shopping)!!.items.single { it.text == "eggs" }.id
        val bread = file().list(shopping)!!.items.single { it.text == "bread" }.id
        val dentist = file().list(todo)!!.items.single().id
        v.update(ItemHow.TYPED) { f, who -> ListsEdits.tick(f, shopping, bread, who) }

        // Not Robot's widget: nothing is bound. Robot's: bound, ids only.
        assertFalse(ListsWidgets.bind(ctx, 11, todo))
        assertTrue(ListsWidgets.bind(ctx, 7, shopping))
        assertEquals(mapOf(7 to shopping), ListsWidgets.bindings(ctx))
        assertFalse(java.io.File(ListsFiles.dir(ctx), "widgets.json").readText().contains("Shopping"))

        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Shopping", title(7))
        assertEquals(2, rows(7))
        assertEquals(null, line(7))
        assertEquals("1 done", foot(7))
        // Widget 9 has no list yet.
        assertEquals("Robot list", title(9))
        assertEquals("Tap to pick a list.", line(9))
        assertEquals(0, rows(9))

        // A tick: that item, on the widget, and :voice told.
        ListsWidgets.tick(ctx, 7, eggs.hex, true, vault)
        val ticked = file().list(shopping)!!.item(eggs)!!
        assertTrue(ticked.done)
        assertEquals(ItemHow.WIDGET, ticked.hist.last().how)
        assertEquals(Cmd.LISTS, lastCmd())
        assertEquals(1, rows(7))
        assertEquals("2 done", foot(7))
        ListsWidgets.tick(ctx, 7, eggs.hex, false, vault)
        assertFalse(file().list(shopping)!!.item(eggs)!!.done)

        // Refused, nothing changed: not Robot's widget, a widget with no list, an item on another list, a bad id, no state.
        val before = file()
        ListsWidgets.tick(ctx, 11, eggs.hex, true, vault)
        ListsWidgets.tick(ctx, 9, eggs.hex, true, vault)
        ListsWidgets.tick(ctx, 7, dentist.hex, true, vault)
        ListsWidgets.tick(ctx, 7, "eggs", true, vault)
        ListsWidgets.tick(ctx, 7, eggs.hex, null, vault)
        assertEquals(before, file())

        // The screen goes off: the items go, without a name; back on and unlocked, they come back.
        val power = shadowOf(ctx.getSystemService(PowerManager::class.java))
        power.setIsInteractive(false)
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Robot list", title(7))
        assertEquals("Unlock to see this list.", line(7))
        assertEquals(0, rows(7))
        assertEquals(null, foot(7))
        power.setIsInteractive(true)
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals(2, rows(7))

        // Locked: hidden, and a tick refused (the lists sealed under the unlock-only key aren't opened at all).
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        ListsWidgets.tick(ctx, 7, eggs.hex, true, vault)
        assertEquals("Unlock to see this list.", line(7))
        keyguard.setKeyguardLocked(false)
        assertFalse(file().list(shopping)!!.item(eggs)!!.done)

        // Lists turned off.
        ListsSettingsStore.update(ctx) { it.copy(on = false) }
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Lists are off in Robot.", line(7))
        ListsSettingsStore.update(ctx) { it.copy(on = true) }

        // The list deleted: the widget says so and offers to pick another.
        v.update(ItemHow.TYPED) { f, _ -> ListsEdits.deleteList(f, shopping) }
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals(ctx.getString(R.string.lists_gone), line(7))

        // A widget the system deleted is forgotten; the provider redraws only what the system lists.
        shadowOf(m).bindAppWidgetId(9, ComponentName("com.example.other", "com.example.other.Widget"))
        assertTrue(ListsWidgets.bind(ctx, 7, todo))
        ListsWidgets.prune(ctx)
        assertEquals(mapOf(7 to todo), ListsWidgets.bindings(ctx))
    }

    /**
     * LS10 (L3): Android numbers widgets across every widget ever placed, so Robot's list widget can be number 47, the model
     * problem notice's request code (sys/Problems.kt). Drawing it never takes over that notice's PendingIntent: the notice
     * still opens Settings › Model, and the widget's title its list.
     */
    @Test fun theWidgetsTapNeverTakesANoticesPendingIntent() {
        ListsFiles.onChange = null
        shadowOf(m).setAlwaysRecreateViewsDuringUpdate(true)
        shadowOf(m).bindAppWidgetId(47, ComponentName(ctx, ListsWidgetProvider::class.java))
        val shopping = file().lists[0].id
        assertTrue(ListsWidgets.bind(ctx, 47, shopping))
        // As Problems.notify makes it.
        val notice = PendingIntent.getActivity(ctx, 47, Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity")
            .putExtra(AppPages.EXTRA, AppPages.MODEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Shopping", title(47))
        assertEquals(AppPages.MODEL, shadowOf(notice).savedIntent.getStringExtra(AppPages.EXTRA))
        // The widget's own tap still opens its list.
        val tap = PendingIntent.getActivity(ctx, 47, Intent(ctx, app.heyrobot.ui.MainActivity::class.java).setIdentifier("lists-widget:47"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        assertEquals(AppPages.listRoute(shopping.hex), shadowOf(tap!!).savedIntent.getStringExtra(AppPages.EXTRA))
    }

    /**
     * LS11a (N2): someone else's "Shopping", restored from their backup beside yours, says so on its widget, as the Lists
     * screens say it; yours doesn't.
     */
    @Test fun someoneElsesListFromABackupIsMarkedOnItsWidget() {
        ListsFiles.onChange = null
        shadowOf(m).setAlwaysRecreateViewsDuringUpdate(true)
        val provider = ComponentName(ctx, ListsWidgetProvider::class.java)
        shadowOf(m).bindAppWidgetId(21, provider)
        shadowOf(m).bindAppWidgetId(22, provider)
        val mine = file().lists[0].id
        val theirs = ListsId.new(random)
        v.update(ItemHow.RESTORE) { f, _ ->
            ListsEdit.Done(f.copy(lists = f.lists + ListsList(theirs, name = "Shopping", made = 1, from = ListFrom.BACKUP,
                items = listOf(ListItem(ListsId.new(random), "nails", pos = 0, at = 1, upd = 1, by = ItemBy.BACKUP, how = ItemHow.RESTORE)))), theirs)
        }
        assertTrue(ListsWidgets.bind(ctx, 21, mine))
        assertTrue(ListsWidgets.bind(ctx, 22, theirs))
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Shopping", title(21))
        assertEquals("Shopping", title(22))
        assertEquals(null, foot(21))
        assertEquals("From a backup that isn't yours", foot(22))
        assertEquals(1, rows(22))
    }

    /**
     * LS11a (N1): any app may send the exported provider a broadcast. A burst of them is one prune and one redraw, through
     * the coalesced ListsWidgets.changed, not one of each per broadcast; a widget the system deleted is still forgotten.
     */
    @Test fun aBurstOfBroadcastsIsOnePruneAndOneRedraw() {
        ListsFiles.onChange = null
        val provider = ComponentName(ctx, ListsWidgetProvider::class.java)
        shadowOf(m).bindAppWidgetId(31, provider)
        shadowOf(m).bindAppWidgetId(32, provider)
        assertTrue(ListsWidgets.bind(ctx, 32, file().lists[0].id))
        // Widget 32 deleted: the system lists only 31 now.
        shadowOf(m).bindAppWidgetId(32, ComponentName("com.example.other", "com.example.other.Widget"))
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        RLog.sink = { lines += it }
        try {
            // The widgets' worker held, so the whole burst lands before it runs.
            val hold = CountDownLatch(1)
            ListsWidgets.work { hold.await(5, TimeUnit.SECONDS) }
            repeat(20) { ListsWidgetProvider().onReceive(ctx, Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)) }
            hold.countDown()
            val idle = CountDownLatch(1)
            ListsWidgets.work { idle.countDown() }
            assertTrue(idle.await(10, TimeUnit.SECONDS))
        } finally {
            RLog.sink = null
        }
        assertEquals(1, lines.count { it.startsWith("lists_widget_pruned ") })
        assertEquals(1, lines.count { it.startsWith("lists_widget_drawn ") })
        assertEquals(emptyMap<Int, ListsId>(), ListsWidgets.bindings(ctx))
    }

    /**
     * LS12 (LT12 step 3 on the Pixel 8): Robot's data cleared with a widget on the home screen, widgets.json gone with it
     * (a backup doesn't keep it, lists-plan.md §6). The widget says why it asks for its list again, a tap on the line opens
     * the picker for it, and a prune never makes the file. Once a picker has opened, a widget with no list says only "Tap to
     * pick a list.".
     */
    @Test fun aWidgetThatLostItsListWithRobotsDataSaysWhy() {
        ListsFiles.onChange = null
        shadowOf(m).setAlwaysRecreateViewsDuringUpdate(true)
        shadowOf(m).bindAppWidgetId(21, ComponentName(ctx, ListsWidgetProvider::class.java))
        val json = java.io.File(ListsFiles.dir(ctx), "widgets.json")
        json.delete()
        ListsWidgets.prune(ctx)
        assertFalse(json.exists())
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Robot list", title(21))
        assertEquals("Robot's data was cleared, and a backup doesn't keep a widget's list. Tap to pick it again.", line(21))
        view(21).findViewById<TextView>(R.id.lists_widget_line).performClick()
        val opened = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertEquals(ComponentName(ctx, ListsWidgetSetup::class.java), opened.component)
        assertEquals(21, opened.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        ListsWidgets.placed(ctx)
        assertTrue(json.exists())
        ListsWidgets.redrawNow(ctx, vault)
        assertEquals("Tap to pick a list.", line(21))
    }
}
