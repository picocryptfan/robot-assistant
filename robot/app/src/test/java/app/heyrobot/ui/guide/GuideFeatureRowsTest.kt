// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.guide

import app.heyrobot.core.guide.GuideFeature
import app.heyrobot.core.guide.GuideStep
import app.heyrobot.ui.settings.SettingsRoutes
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5: each feature's row opens a Settings page that exists, and every row and page has its own words. */
class GuideFeatureRowsTest {
    private val routes = SettingsRoutes::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }.toSet()

    @Test fun everyRouteIsASettingsPage() {
        assertTrue(routes.contains(SettingsRoutes.CAR))
        for (f in GuideFeature.entries) f.route?.let { assertTrue("${f.id}: $it", it in routes) }
    }

    @Test fun everyFeatureHasItsOwnWords() {
        val ids = GuideFeature.entries.flatMap { featureText(it).toList() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it != 0 })
    }

    @Test fun theNewPagesHaveWordsAndRows() {
        assertEquals(setOf(GuideStep.FILES_LISTS, GuideStep.YOUR_DAY, GuideStep.SKILLS, GuideStep.LINK), FEATURE_PAGES.keys)
        for (s in FEATURE_PAGES.keys) assertTrue(s.id, GuideFeature.on(s).size >= 3)
    }
}
