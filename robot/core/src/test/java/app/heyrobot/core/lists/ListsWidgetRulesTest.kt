// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The widget's rules (lists-plan.md §5's widget row, §7.6, D12; LS6): what it draws while unlocked, locked, with the screen
 * off, during a phone lock for you or someone else, on a lock-screen host; that it only ticks, and only an item it was sent
 * as an id; and widgets.json, ids only.
 */
class ListsWidgetRulesTest {
    private val t0 = 1_761_150_000_000
    private val ids = CountingIds()
    private val you = ListsWho(ItemBy.YOU, ItemHow.TYPED, t0)
    private val start = ListsEdits.firstUse(t0, ids)
    private val shopping = start.lists[0].id
    private val todo = start.lists[1].id

    private fun file(open: Int, done: Int = 0): ListsFile {
        var f = start
        repeat(open + done) { i -> f = (ListsEdits.add(f, shopping, listOf(NewItem("item $i")), you, ids) as ListsEdit.Done).file }
        val items = f.list(shopping)!!.open
        repeat(done) { i -> f = (ListsEdits.tick(f, shopping, items[i].id, you) as ListsEdit.Done).file }
        return f
    }

    private val unlocked = WidgetNow(ListsSettings(), shopping, interactive = true, locked = false, phoneLock = false, someoneElse = false)
    private val never: () -> ListsLoad<ListsFile> = { fail("the lists were opened"); ListsLoad.Empty }

    @Test fun unlockedItShowsTheOpenItemsAndHowManyAreDone() {
        val v = WidgetRules.draw(unlocked) { ListsLoad.Loaded(file(open = 3, done = 2)) } as WidgetView.Items
        assertEquals(shopping, v.list.id)
        assertEquals(3, v.items.size)
        assertTrue(v.items.none { it.done })
        assertEquals(0, v.more)
        assertEquals(2, v.done)
        assertTrue(v.ticks)
    }

    @Test fun anotherPersonsWordsWaitForTheUnlockWhateverTheSwitchSays() {
        val shared = file(open = 2).let { f ->
            val l = f.list(shopping)!!
            f.with(l.copy(from = ListFrom.PEER))
        }
        val on = unlocked.copy(locked = true, settings = ListsSettings(lockScreen = true))
        assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(on) { ListsLoad.Loaded(shared) })
        assertTrue(WidgetRules.draw(on) { ListsLoad.Loaded(file(open = 2)) } is WidgetView.Items)
        assertTrue(WidgetRules.draw(unlocked) { ListsLoad.Loaded(shared) } is WidgetView.Items)
    }

    @Test fun atMostFiftyDrawnThenACount() {
        val v = WidgetRules.draw(unlocked) { ListsLoad.Loaded(file(open = 62)) } as WidgetView.Items
        assertEquals(WidgetRules.MAX_DRAWN, v.items.size)
        assertEquals(12, v.more)
        assertEquals((0 until 50).map { "item $it" }, v.items.map { it.text })
    }

    @Test fun lockedOrScreenOffTheItemsAreHiddenWithoutOpeningTheLists() {
        for (n in listOf(unlocked.copy(locked = true), unlocked.copy(interactive = false), unlocked.copy(locked = true, interactive = false)))
            assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(n, never))
        // "Add to lists while locked" doesn't show anything.
        assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(unlocked.copy(locked = true, settings = ListsSettings(addLocked = true)), never))
    }

    @Test fun withHearAndTickOnTheLockScreenTheItemsStay() {
        val on = ListsSettings(lockScreen = true)
        for (n in listOf(unlocked.copy(settings = on, locked = true), unlocked.copy(settings = on, interactive = false)))
            assertTrue(WidgetRules.draw(n) { ListsLoad.Loaded(file(open = 2)) } is WidgetView.Items)
        // The vault still says locked (its key busy): the unlock line, never items.
        assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(unlocked.copy(settings = on, locked = true)) { ListsLoad.Locked })
    }

    @Test fun duringAPhoneLock() {
        // For someone else: "Robot is locked", even with the switch on (SomeoneElseLock.lists has narrowed it off already;
        // the rule holds without that too).
        val other = unlocked.copy(phoneLock = true, someoneElse = true, locked = true)
        assertEquals(WidgetView.Line(WidgetLine.ROBOT_LOCKED), WidgetRules.draw(other, never))
        assertEquals(WidgetView.Line(WidgetLine.ROBOT_LOCKED), WidgetRules.draw(other.copy(settings = ListsSettings(lockScreen = true)), never))
        // Your own: "Robot is locked"; with the lock-screen switch on, the items with their tick boxes off.
        val own = unlocked.copy(phoneLock = true, locked = true)
        assertEquals(WidgetView.Line(WidgetLine.ROBOT_LOCKED), WidgetRules.draw(own, never))
        val v = WidgetRules.draw(own.copy(settings = ListsSettings(lockScreen = true))) { ListsLoad.Loaded(file(open = 2)) } as WidgetView.Items
        assertFalse(v.ticks)
        assertEquals(WidgetView.Line(WidgetLine.ROBOT_LOCKED), WidgetRules.draw(own.copy(settings = ListsSettings(lockScreen = true))) { ListsLoad.Locked })
    }

    @Test fun aLockScreenHostNeverGetsItems() {
        val host = unlocked.copy(keyguardHost = true, settings = ListsSettings(lockScreen = true))
        assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(host, never))
    }

    @Test fun offNotPickedGoneAndTheVaultsStates() {
        assertEquals(WidgetView.Line(WidgetLine.OFF), WidgetRules.draw(unlocked.copy(settings = ListsSettings(on = false)), never))
        assertEquals(WidgetView.Line(WidgetLine.PICK), WidgetRules.draw(unlocked.copy(list = null), never))
        // LS12: no list because Robot's data was cleared since the widget was placed: the line says so.
        assertEquals(WidgetView.Line(WidgetLine.PICK_AGAIN), WidgetRules.draw(unlocked.copy(list = null, forgot = true), never))
        assertEquals(WidgetView.Line(WidgetLine.UNLOCK), WidgetRules.draw(unlocked.copy(list = ids(), forgot = true, locked = true), never))
        assertEquals(WidgetView.Line(WidgetLine.GONE), WidgetRules.draw(unlocked.copy(list = ids())) { ListsLoad.Loaded(start) })
        assertEquals(WidgetView.Line(WidgetLine.GONE), WidgetRules.draw(unlocked) { ListsLoad.Empty })
        assertEquals(WidgetView.Line(WidgetLine.NOT_NOW), WidgetRules.draw(unlocked) { ListsLoad.Locked })
        assertEquals(WidgetView.Line(WidgetLine.KEY_GONE), WidgetRules.draw(unlocked) { ListsLoad.KeyGone })
        assertEquals(WidgetView.Line(WidgetLine.UNREADABLE), WidgetRules.draw(unlocked) { ListsLoad.Unreadable })
        assertEquals(WidgetView.Line(WidgetLine.TOO_NEW), WidgetRules.draw(unlocked) { ListsLoad.TooNew(2) })
    }

    @Test fun theWidgetOnlyTicks() {
        val item = ids().hex
        assertEquals(WidgetTick.Tick(shopping, ListsId.of(item)!!), WidgetRules.tick(unlocked, true, item, true))
        assertEquals(WidgetTick.Untick(shopping, ListsId.of(item)!!), WidgetRules.tick(unlocked, true, item, false))
        // Always the widget's own list, whatever else the fill-in carried.
        assertEquals(WidgetTick.Tick(todo, ListsId.of(item)!!), WidgetRules.tick(unlocked.copy(list = todo), true, item, true))
        fun refused(why: WidgetRefusal, n: WidgetNow, ours: Boolean = true, it: String? = item, checked: Boolean? = true) =
            assertEquals(WidgetTick.Refused(why), WidgetRules.tick(n, ours, it, checked))
        refused(WidgetRefusal.NOT_OURS, unlocked, ours = false)
        for (bad in listOf(null, "", "milk", item.replaceFirst('0', 'A'), item + "0", item.dropLast(1), "../$item")) refused(WidgetRefusal.BAD, unlocked, it = bad)
        refused(WidgetRefusal.BAD, unlocked, checked = null)
        refused(WidgetRefusal.OFF, unlocked.copy(settings = ListsSettings(on = false)))
        refused(WidgetRefusal.NO_LIST, unlocked.copy(list = null))
        refused(WidgetRefusal.HOST, unlocked.copy(keyguardHost = true))
        // Never during a phone lock, for you or someone else, whatever the switch.
        for (s in listOf(ListsSettings(), ListsSettings(lockScreen = true))) {
            refused(WidgetRefusal.ROBOT_LOCKED, unlocked.copy(settings = s, phoneLock = true, locked = true))
            refused(WidgetRefusal.ROBOT_LOCKED, unlocked.copy(settings = s, phoneLock = true, someoneElse = true, locked = true))
        }
        // Locked: only with "Hear and tick on the lock screen" on.
        refused(WidgetRefusal.LOCKED, unlocked.copy(locked = true))
        assertEquals(WidgetTick.Tick(shopping, ListsId.of(item)!!), WidgetRules.tick(unlocked.copy(locked = true, settings = ListsSettings(lockScreen = true)), true, item, true))
    }

    @Test fun bindingsAreIdsOnly() {
        val a = ids()
        val b = ids()
        val bound = WidgetBindings.bind(WidgetBindings.bind(emptyMap(), 7, a), 12, b)
        val json = WidgetBindings.encode(bound)
        val o = org.json.JSONObject(json)
        assertEquals(setOf("v", "w"), o.keys().asSequence().toSet())
        assertEquals(1, o.getInt("v"))
        assertEquals(mapOf("7" to a.hex, "12" to b.hex), o.getJSONObject("w").let { w -> w.keys().asSequence().associateWith { w.getString(it) } })
        assertEquals(WidgetBindings.Read(bound), WidgetBindings.decode(json))
        // Rebinding replaces; a widget the system no longer lists is forgotten.
        assertEquals(mapOf(7 to b, 12 to b), WidgetBindings.bind(bound, 7, b))
        assertEquals(mapOf(12 to b), WidgetBindings.retain(bound, setOf(12, 99)))
        // Strict: anything else is dropped; broken reads as none; a newer Robot's is never written over.
        assertEquals(mapOf(7 to a), WidgetBindings.decode("""{"v":1,"w":{"7":"${a.hex}","-3":"${b.hex}","x":"${b.hex}","08":"${b.hex}","9":"Shopping","10":5,"11":null}}""").bound)
        for (broken in listOf("", "{", "[]", """{"w":{}}""", """{"v":"1","w":{"7":"${a.hex}"}}""", """{"v":1,"w":[]}"""))
            assertEquals(WidgetBindings.Read(emptyMap()), WidgetBindings.decode(broken))
        // LS12: no file at all is told apart (Robot's data cleared since a widget was placed).
        assertEquals(WidgetBindings.Read(emptyMap(), there = false), WidgetBindings.decode(null))
        assertEquals(WidgetBindings.Read(emptyMap(), writable = false), WidgetBindings.decode("""{"v":2,"w":{"7":"${a.hex}"}}"""))
        // Capped: the highest ids kept.
        val many = (1..100).associateWith { ids() }
        assertEquals((37..100).toList(), WidgetBindings.decode(WidgetBindings.encode(many)).bound.keys.sorted())
    }
}
