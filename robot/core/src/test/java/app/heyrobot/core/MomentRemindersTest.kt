// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.output.DeviceFamily
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DevicePresence
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RememberedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

/** moment-reminders: reminders for a moment, read from your words, gone off once on routines' triggers. */
class MomentRemindersTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val ms = now.toInstant().toEpochMilli()
    private fun route(text: String) = RoutineRouter.route(text, now, emptyList(), id = "draft-1")
    private fun cmd(text: String) = route(text) as? RoutineCommand.RemindWhen ?: error("not a moment reminder: $text -> ${route(text)}")
    private fun r(id: String, m: Moment, text: String = "take the groceries in", created: Long = ms - 1000, between: TimeWindow? = null) =
        MomentReminder(id, text, m, created, between)

    @Test fun englishPhrasesParseForEachTrigger() {
        val cases = mapOf(
            "Remind me to take the groceries in when I get out of the car" to Pair("take the groceries in", Moment.CAR_OFF),
            "remind me to call mom when I get in the car" to Pair("call mom", Moment.CAR_ON),
            "remind me to stretch when I take off my headphones" to Pair("stretch", Moment.HEADSET_OFF),
            "remind me to check the stove, when I unplug my phone" to Pair("check the stove", Moment.UNPLUGGED),
            "remind me to take my pills when I unlock my phone" to Pair("take my pills", Moment.UNLOCK),
            "When I plug in tonight, remind me to put out the recycling" to Pair("put out the recycling", Moment.CHARGING),
        )
        for ((text, want) in cases) {
            val c = cmd(text)
            assertEquals(text, want.first, c.text)
            assertEquals(text, want.second, c.moment)
            assertEquals(text, Lang.EN, c.lang)
        }
        assertEquals(TimeWindow.NIGHT, cmd("When I plug in tonight, remind me to put out the recycling").between)
        assertNull(cmd("remind me to water the plants when I plug in my phone").between)
    }

    @Test fun frenchPhrasesParse() {
        val a = cmd("Rappelle-moi de rentrer l'épicerie quand je sors de l'auto")
        assertEquals(Moment.CAR_OFF, a.moment); assertEquals("rentrer l'épicerie", a.text); assertEquals(Lang.FR, a.lang)
        val b = cmd("Quand je branche mon téléphone, rappelle-moi de sortir le recyclage")
        assertEquals(Moment.CHARGING, b.moment); assertEquals("sortir le recyclage", b.text); assertEquals(Lang.FR, b.lang)
        assertEquals(Moment.HEADSET_OFF, cmd("rappelle-moi d'appeler Marie quand j'enlève mes écouteurs").moment)
        assertEquals(Moment.UNLOCK, cmd("rappelle-moi de payer le loyer quand je déverrouille mon téléphone").moment)
        assertEquals(Lang.FR, RoutineRouter.lang("Rappelle-moi de rentrer l'épicerie quand je sors de l'auto", a))
    }

    @Test fun otherWordsStayAsTheyWere() {
        assertTrue(route("when I get in the car, play my music") !is RoutineCommand.RemindWhen)
        assertTrue(route("remind me in the morning to bring the passport") is RoutineCommand.Remind)
        assertNull(route("remind me to call the bank when I can"))
        assertTrue(route("remind me when I get out of the car") !is RoutineCommand.RemindWhen)
    }

    @Test fun eachTriggerGoesOffItsOwnReminders() {
        val list = listOf(r("a", Moment.CHARGING), r("b", Moment.UNPLUGGED), r("c", Moment.UNLOCK), r("d", Moment.HEADSET_OFF), r("e", Moment.CAR_ON))
        assertEquals(listOf("a"), MomentReminders.onEvent(list, TriggerEvent.Power(true), now).now.map { it.id })
        assertEquals(listOf("b"), MomentReminders.onEvent(list, TriggerEvent.Power(false), now).now.map { it.id })
        assertEquals(listOf("c"), MomentReminders.onEvent(list, TriggerEvent.Unlocked, now).now.map { it.id })
        assertEquals(listOf("d"), MomentReminders.onEvent(list, TriggerEvent.Device(DeviceKind.HEADSET, "dev-1", false), now).now.map { it.id })
        assertEquals(listOf("e"), MomentReminders.onEvent(list, TriggerEvent.Device(DeviceKind.CAR, "dev-2", true), now).now.map { it.id })
        assertTrue(MomentReminders.onEvent(list, TriggerEvent.Alarm(AlarmEnd.DISMISSED, ms), now).now.isEmpty())
        // Set after the event: not this one's.
        assertTrue(MomentReminders.onEvent(listOf(r("f", Moment.UNLOCK, created = ms + 5)), TriggerEvent.Unlocked, now).now.isEmpty())
    }

    @Test fun tonightOnlyAtNight() {
        val list = listOf(r("a", Moment.CHARGING, between = TimeWindow.NIGHT))
        assertTrue(MomentReminders.onEvent(list, TriggerEvent.Power(true), now.withHour(13)).now.isEmpty())
        assertEquals(1, MomentReminders.onEvent(list, TriggerEvent.Power(true), now.withHour(22)).now.size)
    }

    @Test fun theCarWaitsTwoMinutes() {
        assertEquals(120_000L, DevicePresence.CAR_GRACE_MS + MomentReminders.holdMs(Moment.CAR_OFF))
        val list = listOf(r("a", Moment.CAR_OFF))
        // The link dropped 30 s ago (DevicePresence's grace): it waits the rest of the 2 minutes.
        val off = MomentReminders.onEvent(list, TriggerEvent.Device(DeviceKind.CAR, "dev-1", false), now)
        assertTrue(off.now.isEmpty())
        assertEquals(ms + 90_000L, off.held["a"])
        assertTrue(MomentReminders.ripe(list, off.held, ms + 89_000L).now.isEmpty())
        val ripe = MomentReminders.ripe(list, off.held, ms + 90_000L)
        assertEquals(listOf("a"), ripe.now.map { it.id })
        assertTrue(ripe.held.isEmpty())
        // A red light: the car is back within the wait, and nothing goes off.
        val back = MomentReminders.onEvent(list, TriggerEvent.Device(DeviceKind.CAR, "dev-1", true), now.plusSeconds(40), off.held)
        assertTrue(back.held.isEmpty())
        assertTrue(MomentReminders.ripe(list, back.held, ms + 200_000L).now.isEmpty())
    }

    @Test fun onceOnly() {
        var list = listOf(r("a", Moment.UNLOCK), r("b", Moment.CHARGING))
        val first = MomentReminders.onEvent(list, TriggerEvent.Unlocked, now)
        list = first.now.fold(list) { l, x -> MomentReminders.remove(l, x.id) }
        assertEquals(listOf("b"), list.map { it.id })
        assertTrue(MomentReminders.onEvent(list, TriggerEvent.Unlocked, now.plusMinutes(1)).now.isEmpty())
        // The same words for the same moment are kept once.
        assertEquals(1, MomentReminders.add(MomentReminders.add(emptyList(), r("x", Moment.UNLOCK)), r("y", Moment.UNLOCK)).size)
    }

    @Test fun aNewerFileIsNeverWrittenOver() {
        val dir = tmp.newFolder("routines")
        val newer = "{\"v\":2,\"moments\":[{\"id\":\"q\",\"text\":\"x\",\"moment\":\"teleport\"}]}"
        val f = java.io.File(dir, RoutineStore.MOMENTS).apply { writeText(newer) }
        val store = RoutineStore(dir)
        assertTrue(store.momentsNewer())
        assertNull(store.editMoments { MomentReminders.add(it, r("a", Moment.UNLOCK)) })
        assertNull(store.editMoments { MomentReminders.remove(it, "q") })
        assertEquals(newer, f.readText())
        assertTrue(store.moments().isEmpty())
        // This Robot's own file is still changed as before.
        f.writeText(MomentReminders.encode(emptyList()))
        assertTrue(!store.momentsNewer())
        assertEquals(listOf("a"), store.editMoments { MomentReminders.add(it, r("a", Moment.UNLOCK)) }?.map { it.id })
    }

    @Test fun keptAcrossARestartAndAReboot() {
        val dir = tmp.newFolder("routines")
        RoutineStore(dir).editMoments { MomentReminders.add(it, r("a", Moment.CAR_OFF, between = TimeWindow.NIGHT)) }
        // A new process (a reboot): read again from the file; the hub listens for its trigger again.
        val again = RoutineStore(dir).moments()
        assertEquals(listOf(r("a", Moment.CAR_OFF, between = TimeWindow.NIGHT)), again)
        assertTrue(TriggerKind.DEVICE in TriggerNeeds.of(emptyList(), false, again))
        assertEquals(setOf(TriggerKind.UNLOCK, TriggerKind.CHARGING), TriggerNeeds.of(emptyList(), false, listOf(r("b", Moment.UNLOCK), r("c", Moment.UNPLUGGED))))
        assertTrue(MomentReminders.decode("{\"v\":2,\"moments\":[]}").isEmpty())
        assertTrue(MomentReminders.decode("not json").isEmpty())
        assertTrue(MomentReminders.decode("{\"v\":1,\"moments\":[{\"id\":\"z\",\"text\":\"x\",\"moment\":\"teleport\"}]}").isEmpty())
    }

    @Test fun aCarItCantSeeSaysSo() {
        val car = DeviceMemory(listOf(RememberedDevice(1, DeviceRole.CAR, DeviceFamily.BLUETOOTH, "A car")))
        assertEquals(RoutineStrings.momentNoCar(), MomentReminders.cantFire(Moment.CAR_OFF, null))
        assertEquals(RoutineStrings.momentNoHeadset(), MomentReminders.cantFire(Moment.HEADSET_OFF, car))
        assertNull(MomentReminders.cantFire(Moment.CAR_OFF, car))
        assertNull(MomentReminders.cantFire(Moment.CHARGING, null))
    }

    @Test fun cardInEnglishAndFrench() {
        val x = r("a", Moment.CAR_OFF)
        val p = RoutineStrings.momentSet("moment" to MomentReminders.phrase(x), "text" to x.text)
        assertEquals("I'll remind you when you get out of the car: take the groceries in.", p.render(Langs.of(Lang.EN)))
        assertEquals("Je vais te le rappeler quand tu sors de l\u2019auto\u00a0: take the groceries in.", p.render(Langs.of(Lang.FR)))
        assertEquals(RoutineCommandCode.REMIND_WHEN, RoutineCommandCode.of(cmd("remind me to stretch when I take off my headphones")))
        assertEquals(RoutineStrings.unlockMoment(), RoutineLockedPhone.refuseText(cmd("remind me to stretch when I take off my headphones"), locked = true))
    }

    /** fix-moment-reminders (review-after13-a 7): two for the same moment both go off, each with its own notice. */
    @Test fun twoForTheSameMomentEachKeepTheirNotice() {
        val list = MomentReminders.add(MomentReminders.add(emptyList(), r("a", Moment.UNLOCK, "take the bins out")), r("b", Moment.UNLOCK, "call the garage"))
        val step = MomentReminders.onEvent(list, TriggerEvent.Unlocked, now)
        assertEquals(listOf("a", "b"), step.now.map { it.id })
        assertEquals(2, step.now.map(MomentReminders::noticeTag).toSet().size)
        assertTrue(MomentReminders.apply(list, step).isEmpty())
    }

    /** fix-moment-reminders (review-after13-a 8): the car's wait is in the file, so the phone's sleep or a restart keeps it and it ends on time. */
    @Test fun theCarsWaitIsKeptInTheFile() {
        val dir = tmp.newFolder("routines")
        RoutineStore(dir).editMoments { MomentReminders.add(it, r("a", Moment.CAR_OFF)) }
        RoutineStore(dir).editMoments { l -> MomentReminders.apply(l, MomentReminders.onEvent(l, TriggerEvent.Device(DeviceKind.CAR, "dev-1", false), now, MomentReminders.held(l))) }
        val again = RoutineStore(dir).moments()
        assertEquals(mapOf("a" to ms + 90_000L), MomentReminders.held(again))
        assertEquals(ms + 90_000L, MomentReminders.nextRipe(MomentReminders.held(again)))
        assertTrue(MomentReminders.ripe(again, MomentReminders.held(again), ms + 89_000L).now.isEmpty())
        assertEquals(listOf("a"), MomentReminders.ripe(again, MomentReminders.held(again), ms + 90_000L).now.map { it.id })
    }

    /** fix-moment-reminders: a wait that ended while Robot was stopped goes off once when it's back; the car back first keeps it for next time. */
    @Test fun aWaitThatEndedWhileStoppedGoesOffWhenRobotIsBack() {
        val list = listOf(r("a", Moment.CAR_OFF).copy(heldUntil = ms + 90_000L))
        val late = MomentReminders.ripe(list, MomentReminders.held(list), ms + 3_600_000L)
        assertEquals(listOf("a"), late.now.map { it.id })
        assertTrue(MomentReminders.apply(list, late).isEmpty())
        val back = MomentReminders.onEvent(list, TriggerEvent.Device(DeviceKind.CAR, "dev-1", true), now.plusSeconds(40), MomentReminders.held(list))
        assertTrue(back.now.isEmpty())
        assertEquals(listOf(r("a", Moment.CAR_OFF)), MomentReminders.apply(list, back))
    }

    /** fix-moment-reminders: headphones already on when Robot starts listening aren't "put on"; the next time they are. */
    @Test fun headphonesAlreadyOnAreNotPutOn() {
        val list = listOf(r("a", Moment.HEADSET_ON))
        val on = TriggerEvent.Device(DeviceKind.HEADSET, "dev-2", true)
        assertTrue(MomentReminders.onEvent(list, on, now, already = true).now.isEmpty())
        assertEquals(listOf("a"), MomentReminders.onEvent(list, on, now).now.map { it.id })
    }

    /** named-timers keeps 17: a reminder for a moment is 18 in the voice log. */
    @Test fun commandCodeIs18() = assertEquals(18, RoutineCommandCode.REMIND_WHEN.id)
}
