// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZonedDateTime

/** Today H4: "put my calendar first" and its kin, the order kept in home_settings.json, the locked phone refused. */
class TodayArrangeTest {
    private val now = ZonedDateTime.parse("2026-11-03T09:00:00-04:00")
    // Which Quick access button the words name is QuickPhrasesTest's; here, Today's part and move.
    private fun route(s: String) = RoutineRouter.route(s, now).let { if (it is RoutineCommand.Arrange) it.copy(quick = null) else it }
    private fun arrange(s: String) = route(s) as? RoutineCommand.Arrange

    @Test fun englishTypedAndSpoken() {
        for ((s, want) in listOf(
            "Put my calendar first" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST),
            "put my calendar first." to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST),
            "hey robot, put my calendar first please" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST),
            "move my note to the top" to RoutineCommand.Arrange(TodayPart.NOTE, TodayMove.FIRST),
            "put my lists last on my day" to RoutineCommand.Arrange(TodayPart.LISTS, TodayMove.LAST),
            "move the calendar down" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.DOWN),
            "Move my reminders up" to RoutineCommand.Arrange(TodayPart.LISTS, TodayMove.UP),
        )) assertEquals(s, want, route(s))
    }

    @Test fun frenchAndItsReply() {
        for ((s, want) in listOf(
            "Mets mon agenda en premier" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST),
            "mets mon calendrier en premier sur ma page" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST),
            "mets ma note épinglée en haut" to RoutineCommand.Arrange(TodayPart.NOTE, TodayMove.FIRST),
            "descends mes listes" to RoutineCommand.Arrange(TodayPart.LISTS, TodayMove.DOWN),
            "mets l'agenda en dernier" to RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.LAST),
        )) {
            assertEquals(s, want, route(s))
            assertEquals(s, Lang.FR, RoutineRouter.lang(s, want))
        }
        assertEquals(null, RoutineRouter.lang("put my calendar first", RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST)))
    }

    /** Whole utterances only: ordinary talk, a title or a reminder that says it in passing moves nothing. */
    @Test fun noMisfires() {
        for (s in listOf("put my calendar first thing tomorrow", "remind me to put my calendar first", "what's first on my calendar",
            "move my calendar meeting up", "put the note first on the agenda for Monday", "add put my calendar first to my notes",
            "move my calendar up to 3 pm", "my calendar first", "monte le son", "mets la musique en premier", "descends les poubelles",
            "mets mon agenda en premier dans le courriel", "put my calendar first and call Bob"))
            assertNull(s, arrange(s))
    }

    @Test fun theLockedPhoneIsRefused() {
        val cmd = RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST)
        assertNotNull(RoutineLockedPhone.refuseText(cmd, locked = true))
        assertNull(RoutineLockedPhone.refuseText(cmd, locked = false))
        assertEquals(RoutineCommandCode.ARRANGE, RoutineCommandCode.of(cmd))
        assertEquals(15, RoutineCommandCode.ARRANGE.id)
    }

    @Test fun movesKeepEveryPartOnce() {
        val d = TodayPart.DEFAULT
        assertEquals(listOf(TodayPart.NOTE, TodayPart.CALENDAR, TodayPart.LISTS), TodayOrder.moved(d, TodayPart.NOTE, TodayMove.FIRST))
        assertEquals(listOf(TodayPart.LISTS, TodayPart.NOTE, TodayPart.CALENDAR), TodayOrder.moved(d, TodayPart.CALENDAR, TodayMove.LAST))
        assertEquals(d, TodayOrder.moved(d, TodayPart.CALENDAR, TodayMove.UP))
        assertEquals(d, TodayOrder.moved(d, TodayPart.NOTE, TodayMove.DOWN))
        assertEquals(listOf(TodayPart.LISTS, TodayPart.CALENDAR, TodayPart.NOTE), TodayOrder.moved(d, TodayPart.LISTS, TodayMove.UP))
        assertEquals(listOf(TodayPart.LISTS, TodayPart.NOTE, TodayPart.CALENDAR), TodayOrder.dragged(d, TodayPart.CALENDAR, 9))
        assertEquals(listOf(TodayPart.NOTE, TodayPart.LISTS, TodayPart.CALENDAR), TodayOrder.of(listOf("note", "lists", "note", "weather")))
    }

    @Test fun theOrderIsKeptInHomeSettings() {
        val s = HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY).with(TodayPart.NOTE, TodayMove.FIRST)
        assertEquals(s, HomeSettings.fromJson(s.toJson()))
        assertEquals(TodayPart.NOTE, HomeSettings.fromJson(s.toJson()).order.first())
        // H2's file, with no order: the default order, and nothing else changes.
        assertEquals(HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY, offer = TodayOffer.TAKEN), HomeSettings.fromJson("""{"v":1,"today":true,"offer":"taken"}"""))
        assertEquals(TodayPart.DEFAULT, HomeSettings.fromJson("""{"v":1,"today":true,"order":"calendar"}""").order)
    }

    @Test fun everyMoveHasItsReplyInBothLanguages() {
        for (p in TodayPart.entries) for (m in TodayMove.entries) {
            val r = TodayArrange.reply(p, m)
            assert(r.en().isNotBlank())
            assert(r.render(app.heyrobot.core.i18n.Langs.of(Lang.FR)) != r.en())
        }
    }
}
