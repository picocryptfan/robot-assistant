// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.skills.PlanApprove
import app.heyrobot.core.skills.PlanWith
import app.heyrobot.core.skills.SkillSettings
import app.heyrobot.core.skills.SkillSettingsCodec
import app.heyrobot.core.skills.SkillStore
import app.heyrobot.core.connect.McpToolsCodec
import app.heyrobot.core.connect.McpToolsFile
import app.heyrobot.core.skills.SkillShare
import app.heyrobot.core.skills.TrustedKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// What Skills 1.3 puts in Robot's backup, and what it never puts there (docs/ops/reports/skills-backup.md).
class SkillsBackupTest {
    private val phone = SkillSettings()
    private val file = SkillSettings(modelPicks = true, planWith = "helper_a", planApprove = PlanApprove.STEP_BY_STEP, planCap = 3)

    @Test fun planRowsComeBackFromYourOwnBackup() {
        val g = RestoreSafety.skillSettings(phone, file, mine = true)
        assertEquals(file, g.value)
        assertEquals(emptyList<SaferField>(), g.kept.toList())
    }

    @Test fun someoneElsesBackupNeverChangesThePlanRows() {
        val g = RestoreSafety.skillSettings(phone, file, mine = false).value
        assertEquals(PlanWith.SAME, g.planWith)
        assertEquals(PlanApprove.ALL_AT_ONCE, g.planApprove)
        assertEquals(phone.planCap, g.planCap)
    }

    @Test fun skillsCategoryTakesSkillsAndPlanSettingsButNotTheLastRuns() {
        val c = BackupCategory.SKILLS
        assertNotNull(c.schema(SkillStore.SKILLS))
        assertEquals(SkillSettingsCodec.VERSION, c.schema(SkillStore.SETTINGS))
        assertNull(c.schema(SkillStore.STATE))
        // Plan rows changed: the entry records v2; at their defaults, v1, so a 1.2 Robot still restores it.
        assertEquals(2, c.schemaOf(SkillStore.SETTINGS, SkillSettingsCodec.encode(file).toByteArray()))
        assertEquals(1, c.schemaOf(SkillStore.SETTINGS, SkillSettingsCodec.encode(phone).toByteArray()))
    }

    @Test fun tokensAndKeysStayOutUnlessYouTickKeys() {
        assertFalse(BackupCategory.KEYS.defaultOn)
        assertFalse(BackupCategory.CONN_KEYS.defaultOn)
        assertEquals(BackupCategory.KEYS, BackupCategory.coveredBy(BackupCategory.CONN_KEYS))
        val secrets = listOf(BackupCategory.CONN_KEY + "abc", BackupCategory.HELPER_KEY + "claude", BackupCategory.SEARCH_KEY + "brave")
        for (c in BackupCategory.entries.filter { it.defaultOn }) for (n in secrets) assertNull("$c takes $n", c.schema(n))
    }

    // skills-backup-b: trusted keys and each MCP connection's tool choices, in their own category under the Skills row.
    private val fp = "ab".repeat(32)
    private val theirs = TrustedKey(fp, "Sam", 5L)
    private val keysJson = SkillShare.writeTrusted(listOf(theirs))
    private val tools = McpToolsCodec.encode(McpToolsFile(pins = emptyList()))
    private val emptySkills = app.heyrobot.core.skills.SkillCodec.encode(emptyList())

    private fun trustBackup(skillsToo: Boolean = true): ByteArray {
        val r = RawRecords().manifest()
        if (skillsToo) r.entry("skills", SkillStore.SKILLS, BackupKit.text(emptySkills))
        r.entry("skill_trust", BackupCategory.SKILL_KEYS_FILE, BackupKit.text(keysJson), v = 1)
            .entry("skill_trust", BackupCategory.mcpName("c-mcp001"), tools, v = 1)
            .entry("skill_trust", BackupCategory.mcpName("c-mcp002"), tools, v = 1)
        return BackupKit.encrypt(r.end().bytes())
    }

    @Test fun trustedKeysAndToolChoicesComeBackFromYourOwnBackup() {
        val v = BackupKit.verify(trustBackup())
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(listOf(theirs), v.skillKeys)
        assertEquals(setOf("c-mcp001", "c-mcp002"), v.mcpTools.keys)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertTrue(plan.of(BackupCategory.SKILL_TRUST).restores)
        assertEquals(BackupCategory.SKILLS, BackupCategory.coveredBy(BackupCategory.SKILL_TRUST))
        val mine = TrustedKey("cd".repeat(32), "Me", 1L)
        val m = RestoreMerge.skillTrust(listOf(mine), v.skillKeys, v.mcpTools, restoredConns = setOf("c-mcp001", "c-mcp002"), mine = true)
        assertEquals(listOf(mine, theirs), m.keys)
        assertArrayEquals(tools, m.mcp.getValue("c-mcp001").toByteArray(Charsets.UTF_8))
        assertEquals(setOf("c-mcp001", "c-mcp002"), m.mcp.keys)
    }

    @Test fun neitherComesBackFromSomeoneElsesBackup() {
        val v = BackupKit.verify(trustBackup())
        assertTrue(BackupCategory.SKILL_TRUST in BackupCategory.ONLY_YOURS)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = false)
        assertEquals(PlanSkip.NOT_YOURS, plan.of(BackupCategory.SKILL_TRUST).skip)
        assertTrue(plan.of(BackupCategory.SKILLS).restores)
        val m = RestoreMerge.skillTrust(emptyList(), v.skillKeys, v.mcpTools, restoredConns = setOf("c-mcp001"), mine = false)
        assertNull(m.keys)
        assertEquals(emptyMap<String, String>(), m.mcp)
    }

    @Test fun toolChoicesForAConnectionNotRestoredHereAreSkipped() {
        val v = BackupKit.verify(trustBackup())
        val m = RestoreMerge.skillTrust(emptyList(), v.skillKeys, v.mcpTools, restoredConns = setOf("c-mcp002"), mine = true)
        assertEquals(setOf("c-mcp002"), m.mcp.keys)
        assertEquals(emptyMap<String, String>(), RestoreMerge.skillTrust(emptyList(), v.skillKeys, v.mcpTools, emptySet(), mine = true).mcp)
        // A connection restored here is one the backup's list brought (added, or the list replaced), never one kept as it was.
        assertEquals(emptySet<String>(), RestoreMerge.connections(emptyList(), emptyList(), RestoreMode.ADD, true, emptySet()).restored)
        // Names: only skill_keys.json and mcp/<a connection id>.json.
        assertNull(BackupCategory.SKILL_TRUST.schema("mcp/../skills.json"))
        assertNull(BackupCategory.SKILL_TRUST.schema("mcp/c-mcp001/mcp_tools.json"))
        assertNull(BackupCategory.SKILL_TRUST.schema(SkillStore.SKILLS))
        assertEquals("c-mcp001", BackupCategory.mcpOf(BackupCategory.mcpName("c-mcp001")))
    }

    @Test fun a13RobotReadsA12BackupAsBefore() {
        val f = BackupKit.encrypt(RawRecords().manifest().entry("skills", SkillStore.SKILLS, BackupKit.text(emptySkills)).end().bytes())
        val v = BackupKit.verify(f)
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(setOf(BackupCategory.SKILLS), v.found.keys)
        assertNull(v.skillKeys)
        val plan = RestorePlanner.plan(v, PhoneHas(emptyMap()), RestorePlanner.defaults(v), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(PlanSkip.NOT_IN_BACKUP, plan.of(BackupCategory.SKILL_TRUST).skip)
        val here = TrustedKey("cd".repeat(32), "Me", 1L)
        assertNull(RestoreMerge.skillTrust(listOf(here), v.skillKeys, v.mcpTools, emptySet(), mine = true).keys)
    }

    /** A Robot without the category (1.2's reader: an unknown category is skipped by name) restores Skills and skips only it. */
    @Test fun aRobotWithoutTheCategoryRestoresTheRestAndSkipsIt() {
        val f = trustBackup()
        val old = RestoreReader.verify(BackupKit.opener(f), BackupHeader.parse(f), BackupKit.key(), {}) { id -> BackupCategory.of(id)?.takeIf { it != BackupCategory.SKILL_TRUST } }
        assertEquals(listOf(CategorySkip("skill_trust", null, SkipWhy.UNKNOWN)), old.skipped)
        assertEquals(setOf(BackupCategory.SKILLS), old.found.keys)
        val plan = RestorePlanner.plan(old, PhoneHas(emptyMap()), RestorePlanner.defaults(old), mine = true)
        assertEquals(listOf(BackupCategory.SKILLS), plan.restoring)
        assertEquals(listOf("skill_trust"), plan.skippedByName.map { it.name })
        // Within what any older reader holds an unknown entry to: under 1 GiB, a name within 96 characters.
        for (n in listOf(BackupCategory.SKILL_KEYS_FILE, BackupCategory.mcpName("c-mcp001"))) {
            assertTrue(n.length <= 96)
            assertTrue(BackupCategory.SKILL_TRUST.maxBytes(n)!! < BackupCaps.UNKNOWN_ENTRY)
        }
    }

    @Test fun connectionsEntryIsTheListAlone() {
        assertNotNull(BackupCategory.CONNECTIONS.schema(BackupCategory.CONNECTIONS_FILE))
        assertNull(BackupCategory.CONNECTIONS.schema(BackupCategory.CONN_KEY + "abc"))
        assertEquals(1, BackupCategory.CONNECTIONS.maxEntries)
    }
}
