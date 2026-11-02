// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.skills.AttentionWhy
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillAttention
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.SlotType
import app.heyrobot.core.skills.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills connections C8 (plan §3.6, D18): a script's `# param:` and `# effect:` lines, every type and its limits; a line
 * Robot can't read, a fourth or a repeated name leaves the script untyped (skipping one would move later values); effects
 * only add. The listing run's new lines, termux_status.json keeping them, and ScriptLinks as a skill's checks see them.
 */
class ScriptParamsTest {
    private fun p(line: String) = ScriptParams.param(ScriptParams.body(line, "param"))

    @Test fun everyTypeReadsWithItsLimitsAndLabel() {
        assertEquals(ScriptParam("folder", ParamType.CHOICE, options = listOf("photos", "documents", "music"), label = "Which folder"),
            p("param: folder choice photos|documents|music \"Which folder\""))
        assertEquals(ScriptParam("days", ParamType.INTEGER, min = 1, max = 30, label = "Keep how many days"), p("# param: days int 1..30 \"Keep how many days\""))
        assertEquals(ScriptParam("offset", ParamType.INTEGER, min = -5, max = 5), p("offset integer -5..5"))
        assertEquals(ScriptParam("note", ParamType.TEXT, maxLen = 80), p("param: note text"))
        assertEquals(ScriptParam("note", ParamType.TEXT, maxLen = 120, dashOk = true, label = "What to write"), p("param: note text max=120 dash=ok \"What to write\""))
        assertEquals(ScriptParam("dry", ParamType.YES_NO), p("param: dry yes_no"))
        assertEquals(ScriptParam("dry", ParamType.YES_NO, label = "Only pretend?"), p("Param:   dry   yes_no   \"Only pretend?\"  "))
        // Its slot and its argument's checks follow.
        assertEquals(SlotType(ParamType.CHOICE, listOf("photos", "documents", "music")), p("folder choice photos|documents|music")!!.slot)
        val op = p("days int 1..30")!!.op
        assertEquals(1.0, op.min!!, 0.0)
        assertEquals(30.0, op.max!!, 0.0)
        assertEquals(80, p("note text")!!.op.maxLen)
    }

    @Test fun aLineRobotCantReadWholeIsNoParameter() {
        for (bad in listOf("", "days", "days int", "days int 30..1", "days int 1..", "days int 1..30 2..3", "days int 1..99999999999",
            "days int -2000000000..1", "days float 1..2", "Days int 1..2", "1days int 1..2", "script int 1..2", "a-b int 1..2",
            "folder choice photos", "folder choice photos|photos", "folder choice -rf|photos", "folder choice a b|c", "folder choice a|b|c|d|e|f|g|h|i|j|k|l|m",
            "folder choice a|\"b\"", "note text max=0", "note text max=201", "note text dash=yes", "note text --", "dry yes_no now",
            "days int 1..30 \"\"", "days int 1..30 \"${"x".repeat(61)}\"", "days int 1..30 \"a\" more", "days int 1..30\t\"tab\"",
            "days int 1..30 \"unclosed", "x".repeat(201))) {
            assertNull(bad, p("param: $bad"))
        }
    }

    @Test fun aBrokenFourthOrRepeatedLineLeavesTheScriptUntyped() {
        val two = ScriptParams.parse(listOf("param: folder choice photos|documents", "param: days int 1..30"), emptyList())
        assertTrue(two.ok)
        assertEquals(listOf("folder", "days"), two.params.map { it.name })
        assertFalse(ScriptParams.parse(listOf("param: folder choice photos|documents", "param: days int 30..1"), emptyList()).ok)
        assertFalse(ScriptParams.parse(listOf("param: days int 1..3", "param: days int 1..30"), emptyList()).ok)
        assertFalse(ScriptParams.parse(listOf("param: a yes_no", "param: b yes_no", "param: c yes_no", "param: d yes_no"), emptyList()).ok)
        assertTrue(ScriptParams.parse(listOf("param: a yes_no", "param: b yes_no", "param: c yes_no"), emptyList()).ok)
        // No `# param:` line: a script that takes no values (a step gives it none).
        assertEquals(ScriptDecl(emptyList(), emptySet()), ScriptParams.parse(emptyList(), emptyList()))
    }

    @Test fun effectsOnlyAdd() {
        assertEquals(setOf("online", "deletes", "sends", "money"), ScriptParams.effects(listOf("effect: online, delete", "# effect: sends; money", "effect: nothing")))
        assertEquals(emptySet<String>(), ScriptParams.effects(listOf("effect: harmless, safe, none")))
        val d = ScriptParams.parse(emptyList(), listOf("effect: online deletes"))
        val linked = ScriptLinks(listOf(TermuxScriptInfo("tidy", effectLines = listOf("effect: online deletes")))).script("tidy")!!
        assertEquals(setOf("online", "deletes"), d.effects)
        assertEquals(setOf(SkillEffect.RUNS_CODE, SkillEffect.WRITES_ONLINE, SkillEffect.DELETES), linked.effects)
        // Running code is always there, whatever the lines say.
        assertEquals(setOf(SkillEffect.RUNS_CODE), ScriptLinks(listOf(TermuxScriptInfo("plain"))).script("plain")!!.effects)
    }

    /** What the listing run prints (its script, run on a PC's bash with two scripts in ~/robot, gave exactly these lines). */
    private val listed = listOf(
        "robot-param\tbackup\tparam: folder choice photos|documents|music \"Which folder\"",
        "robot-param\tbackup\tparam: days int 1..30",
        "robot-effect\tbackup\teffect: online",
        "robot-script\tbackup\tBacks up\t",
        "robot-script\tplain\tplain\t",
        "robot-param\t../evil\tparam: x yes_no",
        "robot-end",
    ).joinToString("\n")

    @Test fun theListingRunReturnsEachScriptsLines() {
        // The run itself: a param or effect line is printed on its own, never taken as the script's comment.
        assertTrue(ScriptRegistry.LIST_SCRIPT.contains("[Pp]aram:*|[Ee]ffect*:*)"))
        assertTrue(ScriptRegistry.LIST_SCRIPT.contains("printf 'robot-%s\\t%s\\t%s\\n'"))
        assertTrue(ScriptRegistry.LIST_SCRIPT.contains("\${x:0:200}"))
        val l = ScriptRegistry.parse(listed)
        assertTrue(l.complete)
        val backup = l.scripts.single { it.name == "backup" }
        assertEquals("Backs up", backup.comment)
        assertEquals(listOf("param: folder choice photos|documents|music \"Which folder\"", "param: days int 1..30"), backup.paramLines)
        assertEquals(listOf("effect: online"), backup.effectLines)
        assertTrue(backup.decl.ok)
        assertEquals(emptyList<String>(), l.scripts.single { it.name == "plain" }.paramLines)
        // A fifth line is kept only to be refused (four at most kept); a name Robot can't use is dropped.
        val many = ScriptRegistry.parse((1..9).joinToString("\n") { "robot-param\tx\tparam: p$it yes_no" } + "\nrobot-script\tx\t\t\nrobot-end")
        assertEquals(ScriptParams.MAX_LINES, many.scripts.single().paramLines.size)
        assertFalse(many.scripts.single().decl.ok)
        assertEquals(listOf("backup", "plain"), l.scripts.map { it.name })
    }

    @Test fun termuxStatusKeepsTheLines() {
        val st = TermuxSetupStatus(listAt = 5, scripts = ScriptRegistry.parse(listed).scripts)
        val back = TermuxSetupStatus.parse(st.toJson().toString())
        assertEquals(st.scripts, back.scripts)
        assertEquals(listOf("folder", "days"), back.scripts.first().decl.params.map { it.name })
        // An older file without them: scripts without declarations, as before.
        val old = TermuxSetupStatus.parse("""{"scripts":[{"name":"backup","comment":"Backs up"}]}""")
        assertEquals(TermuxScriptInfo("backup", "Backs up"), old.scripts.single())
        // Read back with the same caps as the listing.
        val big = TermuxSetupStatus.parse("""{"scripts":[{"name":"x","params":[${(1..9).joinToString(",") { "\"param: p$it yes_no\"" }}],"effects":["${"e".repeat(500)}"]}]}""")
        assertEquals(ScriptParams.MAX_LINES, big.scripts.single().paramLines.size)
        assertEquals(ScriptParams.MAX_LINE, big.scripts.single().effectLines.single().length)
    }

    @Test fun scriptLinksAreWhatASkillsChecksSee() {
        val scripts = ScriptRegistry.parse(listed).scripts
        val links = ScriptLinks(scripts)
        // The same as the fixtures' backup script, which every skills test uses.
        assertEquals(SkillFixtures.links.script("backup"), links.script("backup"))
        assertNull(links.script("gone"))
        assertNull(ScriptLinks(listOf(TermuxScriptInfo("bad", paramLines = listOf("param: x int 3..1")))).script("bad"))
        val back = SkillFixtures.backUp()
        val s = back.copy(params = listOf(SkillFixtures.folder, SkillFixtures.days), steps = listOf(back.steps[1]))
        assertTrue(SkillCheck.valid(s))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        // Gone, broken, or no longer taking what the step gives: the skill needs attention and doesn't run.
        assertEquals(listOf(SkillAttention(0, AttentionWhy.SCRIPT_GONE)), SkillCheck.attention(s, ScriptLinks(emptyList())))
        val renamed = ScriptLinks(listOf(TermuxScriptInfo("backup", paramLines = listOf("param: where choice photos|documents|music", "param: days int 1..30"))))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(s, renamed))
        val narrower = ScriptLinks(listOf(TermuxScriptInfo("backup", paramLines = listOf("param: folder choice photos|music", "param: days int 1..30"))))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(s, narrower))
        val step = SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf("script" to "plain"))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s.copy(steps = listOf(step)), links))
    }
}
