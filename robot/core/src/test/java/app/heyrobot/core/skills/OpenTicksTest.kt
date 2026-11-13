// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.AppEntry
import app.heyrobot.core.OpenTick
import app.heyrobot.core.OpenTicks
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** auto-open-plan §1: the save card's "Open X without asking when this runs", one unticked box per app, saved by package. */
class OpenTicksTest {
    private val maps = AppEntry("Organic Maps", "app.organicmaps/.SplashActivity")
    private val apps = listOf(maps, AppEntry("Signal", "org.thoughtcrime.securesms/.RoutingActivity"))
    private fun draft(fromContent: Boolean = false) = SkillDraft("s1", "go home", Lang.EN,
        listOf(SkillStep.of(StepKind.OPEN_APP, mapOf("name" to "Organic Maps"))),
        listOf(Slot(0, "name", ParamType.APP, "Organic Maps")), SkillMade.VOICE, fromContent, 1L)

    @Test fun anOpenStepGetsOneUntickedBoxSavedByPackage() {
        val d = draft()
        val c = SkillCards.save(d, null, apps)
        assertEquals(listOf(OpenTick("Organic Maps", "app.organicmaps")), c.ticks)
        // Before "Change the name", which stays last.
        assertEquals("Open Organic Maps without asking when this runs · not ticked", c.chips[c.chips.size - 2].render(Langs.EN))
        assertEquals(SkillStrings.rename().render(Langs.EN), c.chips.last().render(Langs.EN))
        assertEquals(emptySet<String>(), d.skill().opens)
        val on = d.copy(opens = OpenTicks.toggle(d.opens, c.ticks[0]))
        assertEquals(setOf("app.organicmaps"), on.skill().opens)
        val onCard = SkillCards.save(on, null, apps)
        assertEquals("Open Organic Maps without asking when this runs · ticked", onCard.chips[onCard.chips.size - 2].render(Langs.EN))
        assertTrue(onCard.chips[onCard.chips.size - 2].render(Langs.of(Lang.FR)).startsWith("Ouvrir Organic Maps sans demander quand elle s"))
        assertEquals(emptySet<String>(), OpenTicks.toggle(on.opens, c.ticks[0]))
        // No apps known (an older caller): no box.
        assertTrue(SkillCards.save(d, null).ticks.isEmpty())
    }

    @Test fun noBoxFromContentForAQuestionOrAGuess() {
        assertTrue(SkillCards.save(draft(fromContent = true), null, apps).ticks.isEmpty())
        assertEquals(emptySet<String>(), draft(fromContent = true).copy(opens = setOf("app.organicmaps")).skill().opens)
        // The app asked each time is a question, not fixed text: no box.
        val asked = draft().let { it.toggle(it.slots[0]) }
        assertTrue(SkillCards.save(asked, null, apps).ticks.isEmpty())
        // Two apps with the same name, or none: a guess, no box.
        assertTrue(OpenTicks.of(listOf("Organic Maps"), apps + AppEntry("Organic Maps", "app.organicmaps.debug/.SplashActivity"), false).isEmpty())
        assertTrue(OpenTicks.of(listOf("Bitwarden"), apps, false).isEmpty())
        assertEquals(listOf("app.organicmaps"), OpenTicks.of(listOf("Organic Maps", "organic maps"), apps, false).map { it.pkg })
    }
}
