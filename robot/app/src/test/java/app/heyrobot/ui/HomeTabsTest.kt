// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.runtime.saveable.SaverScope
import app.heyrobot.sys.AppPages
import app.heyrobot.ui.settings.AppRoutes
import org.junit.Assert.assertEquals
import org.junit.Test

// H3: Calendar takes History's place on the bar; History is a page (the clock icon, "show my history", Settings › History).
class HomeTabsTest {
    private val scope = SaverScope { true }

    @Test fun calendarTakesHistorysPlace() =
        assertEquals(listOf(Tab.HOME, Tab.CALENDAR, Tab.NOTES, Tab.FILES, Tab.SETTINGS), Tab.entries.toList())

    @Test fun aSavedHistoryTabComesBackAsHome() {
        assertEquals(Tab.HOME, Tab.Saver.restore("HISTORY"))
        assertEquals(Tab.CALENDAR, Tab.Saver.restore(with(Tab.Saver) { scope.save(Tab.CALENDAR) }!!))
    }

    @Test fun theHistoryPageSurvivesARecreation() =
        assertEquals(Page.History, Page.Saver.restore(with(Page.Saver) { scope.save(Page.History) }!!))

    @Test fun voiceRoutesOpenTheirPages() {
        assertEquals(AppRoutes.Target(page = Page.History), AppRoutes.target(AppPages.HISTORY))
        assertEquals(AppRoutes.Target(tab = Tab.CALENDAR), AppRoutes.target(AppPages.CALENDAR))
    }

    /**
     * today-fix-d (phone-today-a H3 5): "show my history" said "Here's your history." and opened nothing, as the intent's page
     * was dropped by AppRoutes.of before [AppRoutes.target] saw it. Every plain page target() knows comes through an intent,
     * but those of() reads its own way.
     */
    @Test fun everyPageTargetKnowsComesThroughAnIntent() {
        // Read by of() before its plain list: the launcher icons' aliases, a transcript with its id, feedback with its kind.
        val ownWay = setOf(AppPages.FILES_ICON, AppPages.TRANSCRIPTS, AppPages.FEEDBACK)
        val pages = AppPages::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }.filter { AppRoutes.target(it) != null && it !in ownWay }
        assertEquals(true, AppPages.HISTORY in pages && AppPages.CALENDAR in pages)
        assertEquals(emptyList<String>(), pages.filter { AppRoutes.byExtra(it) != it })
    }
}
