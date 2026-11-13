// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** HS3: Quick access's phrases routed, acting on the home you picked, with replies in English and French. */
class QuickPhrasesTest {
    private val now = ZonedDateTime.parse("2026-11-09T09:00:00-04:00")
    private fun route(s: String) = RoutineRouter.route(s, now)

    @Test fun aButtonOnlyQuickAccessHasMovesThere() {
        for ((s, want) in listOf(
            "put car mode first" to RoutineCommand.ArrangeQuick(QuickItem.CAR, TodayMove.FIRST),
            "Mets le mode voiture en premier" to RoutineCommand.ArrangeQuick(QuickItem.CAR, TodayMove.FIRST),
            "move my skills down" to RoutineCommand.ArrangeQuick(QuickItem.SKILLS, TodayMove.DOWN),
            "mets l'interprète en dernier" to RoutineCommand.ArrangeQuick(QuickItem.INTERPRETER, TodayMove.LAST),
        )) assertEquals(s, want, route(s))
        assertTrue(RoutineRouter.french("Mets le mode voiture en premier", route("Mets le mode voiture en premier")!!))
    }

    @Test fun aPartBothHomesHaveFollowsTheHomeYouPicked() {
        val cmd = route("put my calendar first") as RoutineCommand.Arrange
        assertEquals(RoutineCommand.Arrange(TodayPart.CALENDAR, TodayMove.FIRST, QuickItem.CALENDAR), cmd)
        assertEquals(QuickItem.CALENDAR to TodayMove.FIRST, QuickArrange.onQuick(cmd, HomeChoice.QUICK))
        assertNull(QuickArrange.onQuick(cmd, HomeChoice.TODAY))
        assertNull(QuickArrange.onQuick(cmd, HomeChoice.START))
        // The pinned note has no button: Today's order changes, whichever home you picked.
        assertNull(QuickArrange.onQuick(RoutineCommand.Arrange(TodayPart.NOTE, TodayMove.FIRST), HomeChoice.QUICK))
    }

    /** M1: "notes" is the Notes button on Quick access and the pinned note on Today, in both languages, both ways round. */
    @Test fun notesGoesToWhatYouNamedOnEachHome() {
        for (s in listOf("put my notes first", "Mets mes notes en premier", "put the notes first", "mets les notes en premier")) {
            val cmd = route(s) as? RoutineCommand.Arrange
            assertEquals(s, TodayPart.NOTE, cmd?.part)
            assertEquals(s, QuickItem.NOTES to TodayMove.FIRST, QuickArrange.onQuick(cmd!!, HomeChoice.QUICK))
            assertNull(s, QuickArrange.onQuick(cmd, HomeChoice.TODAY))
        }
        // "my pinned note" has no button: Today's order, whichever home.
        assertNull(QuickArrange.onQuick(route("put my pinned note first") as RoutineCommand.Arrange, HomeChoice.QUICK))
        // Today's other names for a part still reach its button on Quick access.
        assertEquals(QuickItem.LISTS to TodayMove.UP, QuickArrange.onQuick(route("move my reminders up") as RoutineCommand.Arrange, HomeChoice.QUICK))
    }

    @Test fun showQuickAccessOpensIt() {
        for (s in listOf("show quick access", "Open Quick access", "montre-moi l'accès rapide", "Montre l'accès rapide"))
            assertEquals(s, RoutineCommand.Show(ShowPage.QUICK), route(s))
        assertTrue(RoutineRouter.french("montre l'accès rapide", RoutineCommand.Show(ShowPage.QUICK)))
    }

    @Test fun lockedItWaits() {
        assertEquals(app.heyrobot.core.i18n.RoutineStrings.unlockQuick(),
            RoutineLockedPhone.refuseText(RoutineCommand.ArrangeQuick(QuickItem.CAR, TodayMove.FIRST), locked = true))
    }

    @Test fun everyButtonAndMoveHasItsReplyInBothLanguages() {
        val fr = Langs.of(Lang.FR)
        assertEquals("The Car mode button is first on Quick access now.", QuickArrange.reply(QuickItem.CAR, TodayMove.FIRST).en())
        assertEquals("Le bouton Mode auto est en premier dans l’Accès rapide maintenant.", QuickArrange.reply(QuickItem.CAR, TodayMove.FIRST).render(fr))
        for (i in QuickItem.entries) {
            assertTrue(i.name, QuickArrange.name(i).en().isNotBlank())
            for (m in TodayMove.entries) {
                val r = QuickArrange.reply(i, m)
                assertTrue(r.en().contains(QuickArrange.name(i).en()))
                assertTrue(r.render(fr).contains(QuickArrange.name(i).render(fr)))
            }
        }
    }
}
