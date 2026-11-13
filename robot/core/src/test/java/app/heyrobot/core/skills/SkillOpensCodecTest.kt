// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** auto-open-plan §4: a skill's ticked apps (`opens`) round-trip in skills.json, and only in a file that says v4. */
class SkillOpensCodecTest {
    private val ticked = SkillFixtures.headingHome().copy(opens = setOf("app.organicmaps"))

    @Test fun aTickedSkillRoundTripsInAV4File() {
        val file = SkillCodec.encode(listOf(ticked))
        assertEquals(4, JSONObject(file).getInt("v"))
        val back = (SkillCodec.decode(file) as SkillFile.Ok).skills.single()
        assertEquals(ticked, back)
        assertEquals(setOf("app.organicmaps"), back.opens)
    }

    @Test fun anUntickedSkillWritesNoKeyAndKeepsItsVersion() {
        val plain = SkillFixtures.headingHome()
        assertFalse(SkillCodec.toJson(plain).has("opens"))
        assertTrue(SkillCodec.needs(plain) < 4)
    }

    @Test fun anOlderFileOrABadPackageCarriesNoTick() {
        val o = SkillCodec.toJson(ticked)
        assertNull(SkillCodec.fromJson(o, 3))
        assertNull(SkillCodec.fromJson(JSONObject(o.toString()).put("opens", org.json.JSONArray().put("app.organicmaps/.Main")), 4))
        assertNull(SkillCodec.fromJson(JSONObject(o.toString()).put("opens", org.json.JSONArray().put("")), 4))
    }
}
