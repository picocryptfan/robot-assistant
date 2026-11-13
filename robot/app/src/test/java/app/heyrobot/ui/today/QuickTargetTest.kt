// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import app.heyrobot.core.QuickButton
import app.heyrobot.core.QuickItem
import app.heyrobot.core.QuickState
import app.heyrobot.ui.Page
import app.heyrobot.ui.Tab
import app.heyrobot.ui.settings.PageStack
import app.heyrobot.ui.settings.SettingsRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review M2: where every Quick access button goes, and Back from each page it opens. */
class QuickTargetTest {
    private fun go(i: QuickItem, s: QuickState = QuickState.READY) = target(QuickButton(i, s))

    @Test fun everyButtonGoesToItsFeature() {
        val want = mapOf(
            QuickItem.TALK to QuickGo.Feature.TALK, QuickItem.CAR to QuickGo.Feature.CAR,
            QuickItem.LINK to QuickGo.Over(Page.Settings(SettingsRoutes.LINK)), QuickItem.SKILLS to QuickGo.Over(Page.Settings(SettingsRoutes.SKILLS)),
            QuickItem.ROUTINES to QuickGo.Over(Page.Settings(SettingsRoutes.ROUTINES)), QuickItem.LISTS to QuickGo.Notes(lists = true),
            QuickItem.NOTES to QuickGo.Notes(lists = false), QuickItem.FILES to QuickGo.ToTab(Tab.FILES), QuickItem.CALENDAR to QuickGo.ToTab(Tab.CALENDAR),
            QuickItem.HISTORY to QuickGo.Over(Page.History), QuickItem.TRANSCRIBE to QuickGo.Feature.TRANSCRIBE,
            QuickItem.INTERPRETER to QuickGo.Feature.INTERPRETER,
        )
        assertEquals(QuickItem.entries.toSet(), want.keys)
        for ((i, g) in want) assertEquals(i.name, g, go(i))
        assertEquals(QuickGo.Feature.TRANSCRIPTION_CHECK, go(QuickItem.TRANSCRIBE, QuickState.SET_UP))
        assertEquals(QuickGo.Over(Page.ConversationSettings), go(QuickItem.INTERPRETER, QuickState.SET_UP))
    }

    @Test fun backFromEachPageComesBackToQuickAccess() {
        for (i in QuickItem.entries) {
            val p = (go(i) as? QuickGo.Over)?.page ?: continue
            // Quick access as your home: the page alone over the tabs; Back shows the Home tab, never a Settings page.
            assertNull(i.name, PageStack().apply { open(p); back() }.top)
            // Over "show quick access" on another home: Back is Quick access.
            assertEquals(i.name, Page.Quick, PageStack().apply { open(Page.Quick); open(p); back() }.top)
        }
    }
}
