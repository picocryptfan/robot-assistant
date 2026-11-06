package app.heyrobot.core

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-everyday-c: the Pixel 8 row's "cancel my alarm" showed "Open your clock app" and cancelled nothing (phone-1.2.3-b). */
class AlarmCancelTest {
    private val now = ZonedDateTime.of(2026, 11, 5, 11, 17, 0, 0, ZoneId.of("America/Halifax"))
    private fun cmd(t: String) = RoutineRouter.route(t, now) as RoutineCommand.CantCancel
    private val next = LocalTime.of(11, 26)

    @Test fun cancelMyAlarmAsksAboutTheNextOne() {
        assertEquals(AlarmCancel.Plan.Ask(next), AlarmCancel.plan(cmd("cancel my alarm"), next))
        assertEquals(AlarmCancel.Plan.Open(none = true), AlarmCancel.plan(cmd("cancel my alarm"), null))
        assertEquals(AlarmCancel.Plan.Ask(next), AlarmCancel.plan(cmd("annule mon alarme"), next))
    }

    @Test fun aTimeYouSaidGoesAsIsAndIsCheckedWhenItIsTheNext() {
        assertEquals(AlarmCancel.Plan.Dismiss(next, true), AlarmCancel.plan(cmd("cancel my 11:26 alarm"), next))
        val evening = LocalTime.of(18, 30)
        // review-1.2.4 finding 2: no a.m. or p.m., and the next is the other half of the day: the card asks.
        assertEquals(AlarmCancel.Plan.Ask(evening), AlarmCancel.plan(cmd("cancel my 6:30 alarm"), evening))
        assertEquals(AlarmCancel.Plan.Ask(LocalTime.of(19, 0)), AlarmCancel.plan(cmd("cancel my 7 alarm"), LocalTime.of(19, 0)))
        assertEquals(AlarmCancel.Plan.Dismiss(LocalTime.of(7, 0), true), AlarmCancel.plan(cmd("cancel my 7 alarm"), LocalTime.of(7, 0)))
        assertEquals(AlarmCancel.Plan.Dismiss(evening, true), AlarmCancel.plan(cmd("cancel my 6:30 pm alarm"), evening))
        assertEquals(AlarmCancel.Plan.Dismiss(LocalTime.of(6, 30), false), AlarmCancel.plan(cmd("cancel my 6:30 am alarm"), evening))
        assertEquals(AlarmCancel.Plan.Dismiss(LocalTime.of(7, 0), false), AlarmCancel.plan(cmd("annule mon alarme de 7 h"), next))
        assertEquals(AlarmCancel.Plan.Open(none = false), AlarmCancel.plan(cmd("cancel all my alarms"), next))
    }

    @Test fun theNextAlarmMovingOnMeansItWent() {
        assertFalse(AlarmCancel.gone(1000L, 1000L))
        assertTrue(AlarmCancel.gone(1000L, 87_400_000L))
        assertTrue(AlarmCancel.gone(1000L, null))
    }

    /**
     * fix-cancel-alarm (phone-1.2.4 step 5): "what alarms do I have" was the model's, an "Open Clock" card that stayed
     * waiting, so the "cancel my alarm" after it got "A card is waiting" and the yes opened the clock instead.
     */
    @Test fun whatAlarmsIsRobotsOwnAnswer() {
        for (t in listOf("what alarms do I have", "What alarms do I have?", "do I have any alarms set", "when is my next alarm",
            "what time is my alarm set for", "is my alarm set", "what's my next alarm"))
            assertEquals(t, RoutineCommand.NextAlarm(french = false), RoutineRouter.route(t, now))
        for (t in listOf("quelles alarmes j'ai", "est-ce que j'ai une alarme", "c'est quand ma prochaine alarme", "à quelle heure sonne mon réveil"))
            assertEquals(t, RoutineCommand.NextAlarm(french = true), RoutineRouter.route(t, now))
        for (t in listOf("set an alarm for 7", "what alarm app do you use", "what is an alarm clock", "cancel my alarm"))
            assertFalse(t, RoutineRouter.route(t, now) is RoutineCommand.NextAlarm)
        assertEquals(RoutineCommand.CantCancel(timer = false, french = false), RoutineRouter.route("cancel my alarm", now))
    }

    @Test fun nextLineSaysTheOneRobotSees() {
        val tomorrow = now.plusDays(1).withHour(9).withMinute(7)
        assertTrue(AlarmCancel.nextLine(tomorrow, now).en(), AlarmCancel.nextLine(tomorrow, now).en().let { it.startsWith("Your next alarm is at ") && it.contains("tomorrow. Android shows Robot only that one") })
        assertTrue(AlarmCancel.nextLine(now.withHour(19).withMinute(0), now).en().contains("today"))
        assertTrue(AlarmCancel.nextLine(now.plusDays(3), now).en().contains(" on "))
        assertEquals("Robot doesn't see an alarm set.", AlarmCancel.nextLine(null, now).en())
        assertTrue(AlarmCancel.nextLine(tomorrow, now).render(app.heyrobot.core.i18n.Langs(app.heyrobot.core.i18n.Lang.FR, app.heyrobot.core.i18n.Lang.FR, app.heyrobot.core.i18n.Lang.FR)).startsWith("Ta prochaine alarme sonne demain"))
    }
}
