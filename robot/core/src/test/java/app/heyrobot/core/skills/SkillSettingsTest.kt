// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Phase B: skill_settings.json v1 (plan §3), and where it lives (SkillStore, under skills.lock). */
class SkillSettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theFile() {
        assertEquals("""{"v":1,"modelPicks":true}""", SkillSettingsCodec.encode(SkillSettings()))
        assertEquals(SkillSettingsFile.Ok(SkillSettings(false)), SkillSettingsCodec.decode("""{"v":1,"modelPicks":false}"""))
        // None, or no key: on (the default).
        assertTrue(SkillSettingsCodec.decode(null).settings.modelPicks)
        assertTrue(SkillSettingsCodec.decode("""{"v":1}""").settings.modelPicks)
        assertEquals(SkillSettingsFile.TooNew(2), SkillSettingsCodec.decode("""{"v":2,"modelPicks":false,"more":1}"""))
        for (bad in listOf("{", """{"v":1,"modelPicks":"no"}""", """{"v":1,"other":true}""", """{"modelPicks":false}""", """{"v":0}"""))
            assertEquals(bad, SkillSettingsFile.Damaged, SkillSettingsCodec.decode(bad))
        // What can't be read keeps the defaults.
        assertTrue(SkillSettingsFile.Damaged.settings.modelPicks)
    }

    @Test fun theStore() {
        val st = SkillStore(tmp.root)
        assertTrue(st.settings().modelPicks)
        assertTrue(st.saveSettings(SkillSettings(false)))
        assertFalse(st.settings().modelPicks)
        // Never over a file from a newer Robot.
        java.io.File(tmp.root, SkillStore.SETTINGS).writeText("""{"v":2,"modelPicks":false}""")
        assertFalse(st.saveSettings(SkillSettings(true)))
        assertEquals("""{"v":2,"modelPicks":false}""", java.io.File(tmp.root, SkillStore.SETTINGS).readText())
        assertTrue(st.settings().modelPicks)
    }
}
