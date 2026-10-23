// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.output.HandsFreeSafety
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.web.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Design §7.5: every field of every settings type a backup carries is either restored as is, guarded, this phone's, or
 * trust that is never restored. A field added later (1.2's) fails here until someone decides which, so it can't slip
 * through a restore unexamined.
 */
class RestoreSafetyCoverageTest {
    private fun fields(c: Class<*>): Set<String> =
        c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()

    @Test fun everyFieldIsClassified() {
        for ((c, rules) in RestoreSafety.RULES) {
            val f = fields(c)
            assertEquals("${c.simpleName}: fields without a rule", emptySet<String>(), f - rules.keys)
            assertEquals("${c.simpleName}: rules for fields it doesn't have", emptySet<String>(), rules.keys - f)
        }
    }

    @Test fun theDesignsTypesAreAllThere() {
        val must = listOf(RobotSettings::class.java, MemorySettings::class.java, MessageSettings::class.java, MessagesFile::class.java,
            TermuxSettings::class.java, WebSettings::class.java, UpdateSettings::class.java, HeadsetSettings::class.java, HandsFreeSafety::class.java,
            CarSettings::class.java, RoutineSettings::class.java,
            // 1.4, Robot Files: files_settings.json (F11); F12's travel mode and hidden drawers, guarded even from your own backup.
            app.heyrobot.core.files.FilesSettings::class.java)
        for (c in must) assertTrue(c.simpleName, c in RestoreSafety.RULES)
        // Every nested field names a type that is classified too.
        for ((c, rules) in RestoreSafety.RULES) for ((name, r) in rules) if (r == FieldRule.NESTED) {
            val t = c.getDeclaredField(name).type
            val inner = if (List::class.java.isAssignableFrom(t)) app.heyrobot.core.helpers.HelperConfig::class.java else t
            assertTrue("${c.simpleName}.$name", inner in RestoreSafety.RULES)
        }
    }

    /** Each guarded field has a [SaferField] the preview can name, and each [SaferField] names a guarded field. */
    @Test fun guardedFieldsHaveWords() {
        val guarded = RestoreSafety.RULES.flatMap { (_, r) -> r.filterValues { it == FieldRule.GUARDED }.keys }.toSet()
        for (s in SaferField.entries) assertTrue(s.name, s.field in guarded)
        // The helpers' own switches are named once, as HELPERS, and so is where a helper sends your questions (its
        // company, its model and providers that keep nothing), which a backup that isn't yours never changes.
        val named = SaferField.entries.map { it.field }.toSet() + setOf("enabled", "trust", "locked", "baseUrl", "budgetCents", "preset", "model", "zdr")
        assertEquals(emptySet<String>(), guarded - named)
    }
}
