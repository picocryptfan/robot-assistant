// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-auto-open 5 (auto-open-plan §3): a routine's and a skill's ticked apps show on their own page, each one untickable. */
class OpenTickRowsTest {
    private fun src(f: String) = File("src/main/java/app/heyrobot/ui/settings/$f").readText()

    @Test fun bothPagesShowTheirTicksAndUntickThem() {
        assertTrue(src("RoutinesSettings.kt").contains("OpenTickRows(r.opens) { edit(r.copy(opens = r.opens - it)) }"))
        assertTrue(src("SkillsSettings.kt").contains("OpenTickRows(s.opens) { edit(s.copy(opens = s.opens - it)) }"))
    }
}
