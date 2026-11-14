// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-calendar-repeat-f: a calendar chosen in Settings that no longer exists: Robot adds to the phone's default and says so.
class CalendarPickGoneTest {
    private val personal = CalendarInfo(3, "Personal", "me", "me", primary = true)
    private val work = CalendarInfo(7, "Work", "me", null, primary = false)

    @Test fun aChosenCalendarThatIsGoneFallsBackAndIsNamed() {
        assertEquals(personal, CalendarPick.choose(listOf(work, personal), 42))
        assertTrue(CalendarPick.gone(listOf(work, personal), 42))
        assertTrue(app.heyrobot.core.i18n.ToolStrings.chosenCalendarGone("calendar" to "Personal").en().contains("Personal"))
    }

    // fix-calendar-lows (review 8): Robot's Settings are « Réglages » in French, « Paramètres » is Android's (l10n/glossary.tsv).
    @Test fun theFrenchLineNamesRobotsSettingsAsTheGlossarySays() {
        val fr = app.heyrobot.core.i18n.ToolStrings.chosenCalendarGone("calendar" to "Personnel").render(app.heyrobot.core.i18n.Langs.of(app.heyrobot.core.i18n.Lang.FR))
        assertTrue(fr, "Réglages" in fr)
        assertFalse(fr, "paramètres" in fr.lowercase())
    }

    @Test fun aChosenCalendarStillThereOrNoneChosenIsNotGone() {
        assertFalse(CalendarPick.gone(listOf(work, personal), 7))
        assertFalse(CalendarPick.gone(listOf(work, personal), CalendarPick.AUTO))
    }
}
