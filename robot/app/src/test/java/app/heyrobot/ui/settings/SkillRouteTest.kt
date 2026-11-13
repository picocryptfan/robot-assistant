package app.heyrobot.ui.settings

import app.heyrobot.sys.AppPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Low 9: a skill's page opens only for Robot's own launches (or when Android can't say, as a list's page). */
class SkillRouteTest {
    private val route = AppPages.skillRoute("s-abc123")

    @Test fun anotherAppCantOpenASkillsPage() {
        assertNull(AppRoutes.skillFrom(route, own = false))
    }

    @Test fun robotItselfCan() {
        assertEquals(route, AppRoutes.skillFrom(route, own = true))
        assertEquals(route, AppRoutes.skillFrom(route, own = null))
        assertNull(AppRoutes.skillFrom("skill:not an id", own = true))
    }
}
