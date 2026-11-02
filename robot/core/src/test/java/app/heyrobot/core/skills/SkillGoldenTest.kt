// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Older skill files keep reading exactly as before (skills connections plan D6, C1): skills-v1-golden.json was written by
 * 1.1's codec (e0965fb7, before C1 changed it) from Phase B's 20 skills, one with a later Robot's languages and one made
 * from content. Today's codec reads it whole, as 1.1's frozen copy does, and writes it back byte for byte, still v1.
 */
class SkillGoldenTest {
    private val golden: String = javaClass.classLoader!!.getResource("skills/skills-v1-golden.json")!!.readText(Charsets.UTF_8).trimEnd('\n')

    @Test fun theV1GoldenReadsWholeAndWritesBackTheSame() {
        val now = SkillCodec.decode(golden) as SkillFile.Ok
        val then = SkillCodec11.decode(golden) as SkillFile.Ok
        assertEquals(0, now.dropped)
        assertEquals(22, now.skills.size)
        assertEquals(then, now)
        assertEquals(golden, SkillCodec.encode(now.skills))
        assertTrue(golden.startsWith("{") && "\"v\":1" in golden)
    }
}
