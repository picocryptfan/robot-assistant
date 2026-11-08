// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPolicyTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResourceAsStream("mcp/$name")!!.readBytes()
    private val modern = McpServer(McpEra.MODERN, McpWire.MODERN, McpServerId("weather-demo", "1.0.0"))
    private fun raw(name: String) = (McpWire.listReply(modern, 200, "application/json", bytes(name), false, 2) as McpOut.Ok).value.tools
    private fun listed(name: String): McpListed = McpTools.read(listOf(raw(name)))!!

    @Test fun aChangedSchemaTurnsTheToolOffAndSaysWhatChanged() {
        val before = listed("list.json")
        val pins = before.tools.map(McpPolicy::pin)
        assertTrue(McpPolicy.changes(pins, before).all { it is McpChange.Same })
        val changes = McpPolicy.changes(pins, listed("list-changed.json"))
        assertEquals(listOf(McpChange.Changed("get_weather", setOf(McpPart.INPUTS), listOf("days")), McpChange.Same("add_note")), changes)
        val settings = listOf(McpToolSetting("get_weather", SkillLevel.JUST_DO_IT, "Weather", onlyReads = true),
            McpToolSetting("add_note", SkillLevel.ASK_FIRST, "Notes"))
        val after = McpPolicy.settle(settings, changes)
        assertEquals(listOf(settings[0].copy(level = SkillLevel.OFF), settings[1]), after)
        assertEquals(listOf("“get_weather” changed on your Weather station. Robot turned it off until you look at it.", "What it takes changed: days."),
            McpPolicy.text(changes[0], "get_weather", "Weather station")!!.map { it.en() })
        assertNull(McpPolicy.text(changes[1], "add_note", "Weather station"))
    }

    @Test fun goneNewAndUnusableTools() {
        val pins = listed("list.json").tools.map(McpPolicy::pin)
        val note = raw("list.json").filter { (it as Map<*, *>)["name"] == "add_note" }
        val time = mapOf("name" to "get_time", "inputSchema" to mapOf("type" to "object"))
        val c = McpPolicy.changes(pins, McpTools.read(listOf(note + time))!!)
        assertEquals(listOf(McpChange.Gone("get_weather"), McpChange.Same("add_note"), McpChange.New("get_time")), c)
        assertEquals(listOf(McpToolSetting("get_weather", SkillLevel.OFF, "Weather", gone = true), McpToolSetting("get_time")),
            McpPolicy.settle(listOf(McpToolSetting("get_weather", SkillLevel.ASK_FIRST, "Weather")), c))
        assertEquals("“get_weather” is gone from your Weather station. Robot turned it off.", McpPolicy.text(c[0], "get_weather", "Weather station")!!.single().en())
        val numberHeader = mapOf("name" to "get_weather", "inputSchema" to mapOf("type" to "object",
            "properties" to mapOf("t" to mapOf("type" to "number", "x-mcp-header" to "T"))))
        assertEquals(McpChange.Unusable("get_weather", McpUnusable.HEADER), McpPolicy.changes(pins, McpTools.read(listOf(note + numberHeader))!!)[0])
    }

    @Test fun annotationsAreClaimsYouConfirm() {
        val l = listed("list.json")
        val weather = l.tool("get_weather")!!
        val note = l.tool("add_note")!!
        assertEquals(McpClaims(true, null, null, true), weather.claims)
        val reads = McpToolSetting("get_weather", SkillLevel.JUST_DO_IT, "Weather", onlyReads = true)
        assertEquals(McpTurnOn.OK, McpPolicy.turnOn(weather, reads))
        assertEquals(McpEffects(setOf(SkillEffect.READS_ONLINE), true), McpPolicy.effects(weather.claims, reads))
        val unticked = reads.copy(onlyReads = false)
        assertEquals(McpTurnOn.JUST_DO, McpPolicy.turnOn(weather, unticked))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.DELETES), McpPolicy.effects(weather.claims, unticked).effects)
        val n = McpToolSetting("add_note", SkillLevel.ASK_FIRST, "Notes")
        assertEquals(McpTurnOn.OK, McpPolicy.turnOn(note, n))
        assertEquals(McpTurnOn.ONLY_READS, McpPolicy.turnOn(note, n.copy(onlyReads = true)))
        assertEquals(McpTurnOn.JUST_DO, McpPolicy.turnOn(note, n.copy(level = SkillLevel.JUST_DO_IT)))
        assertEquals(McpEffects(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.DELETES), true), McpPolicy.effects(note.claims, n))
        assertEquals(McpTurnOn.JUST_DO, McpPolicy.turnOn(weather, reads.copy(marks = setOf(McpMark.MONEY))))
        assertTrue(SkillEffect.MESSAGES_SOMEONE in McpPolicy.effects(note.claims, n.copy(marks = setOf(McpMark.TELLS_PEOPLE))).effects)
        assertEquals(McpTurnOn.LABEL, McpPolicy.turnOn(note, n.copy(label = "")))
        assertEquals(McpTurnOn.LABEL, McpPolicy.turnOn(note, n.copy(label = "x".repeat(41))))
        assertEquals(McpTurnOn.LABEL, McpPolicy.turnOn(note, n.copy(label = " Notes")))
        assertEquals(McpTurnOn.UNUSABLE, McpPolicy.turnOn(null, n))
        assertEquals(McpTurnOn.OK, McpPolicy.turnOn(null, n.copy(level = SkillLevel.OFF)))
    }

    @Test fun anotherServerAtTheSameAddress() {
        val was = McpServerId("weather-demo", "1.0.0")
        assertFalse(McpPolicy.otherServer(was, McpServerId("weather-demo", "1.1.0")))
        assertTrue(McpPolicy.otherServer(was, McpServerId("other", "1.0.0")))
        assertTrue(McpPolicy.otherServer(was, null))
        assertFalse(McpPolicy.otherServer(null, was))
    }

    @Test fun xMcpHeaderRules() {
        fun input(vararg props: Pair<String, Any?>) = mapOf("type" to "object", "properties" to mapOf(*props))
        val region = "region" to mapOf("type" to "string", "x-mcp-header" to "Region")
        val nested = "where" to mapOf("type" to "object", "properties" to mapOf("zone" to mapOf("type" to "integer", "x-mcp-header" to "Zone")))
        val h = McpPolicy.headers(input(region, nested))!!
        assertEquals(listOf(McpHeader(listOf("region"), "Region", "string"), McpHeader(listOf("where", "zone"), "Zone", "integer")), h)
        assertNull(McpPolicy.headers(input("n" to mapOf("type" to "number", "x-mcp-header" to "N"))))
        assertNull(McpPolicy.headers(input(region, "r2" to mapOf("type" to "string", "x-mcp-header" to "REGION"))))
        assertNull(McpPolicy.headers(input("bad" to mapOf("type" to "string", "x-mcp-header" to "a b"))))
        assertNull(McpPolicy.headers(input("list" to mapOf("type" to "array", "items" to mapOf("type" to "string", "x-mcp-header" to "I")))))
        assertNull(McpPolicy.headers(mapOf("type" to "object", "x-mcp-header" to "Root")))
        assertEquals(listOf("Mcp-Param-Region" to "=?base64?TW9udHLDqWFs?=", "Mcp-Param-Zone" to "4"),
            McpPolicy.paramHeaders(h, mapOf("region" to "Montréal", "where" to mapOf("zone" to 4L))))
        assertEquals(listOf("Mcp-Param-Region" to "east"), McpPolicy.paramHeaders(h, mapOf("region" to "east")))
        assertEquals("=?base64??=", McpPolicy.headerValue(""))
        assertEquals("=?base64?IGVhc3Q=?=", McpPolicy.headerValue(" east"))
    }

    @Test fun atMostThreePrimitiveQuestions() {
        assertEquals(listOf(McpQuestion("city", ParamType.TEXT, true), McpQuestion("units", ParamType.CHOICE, false, listOf("metric", "imperial"))),
            McpPolicy.questions(listed("list.json").tool("get_weather")!!.input))
        assertEquals(McpQuestion("days", ParamType.INTEGER, true, min = 1.0, max = 7.0), McpPolicy.questions(listed("list-changed.json").tool("get_weather")!!.input)!![2])
        fun props(n: Int) = mapOf("type" to "object", "properties" to (1..n).associate { "p$it" to mapOf("type" to "string") })
        assertEquals(3, McpPolicy.questions(props(3))!!.size)
        assertNull(McpPolicy.questions(props(4)))
        assertNull(McpPolicy.questions(mapOf("type" to "object", "properties" to mapOf("o" to mapOf("type" to "object")))))
        assertNull(McpPolicy.questions(mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "array", "items" to mapOf("type" to "string"))))))
        assertNull(McpPolicy.questions(mapOf("type" to "object", "additionalProperties" to true)))
        assertEquals(McpQuestion("n", ParamType.INTEGER, false, min = 1.0, max = 9.0), McpPolicy.questions(mapOf("type" to "object",
            "properties" to mapOf("n" to mapOf("type" to "integer", "exclusiveMinimum" to 0L, "exclusiveMaximum" to 10L))))!![0])
    }

    @Test fun pinsDontDependOnKeyOrder() {
        val a = McpPolicy.sha(mapOf("b" to 1L, "a" to listOf(mapOf("y" to true, "x" to "z"))))
        assertEquals(a, McpPolicy.sha(mapOf("a" to listOf(mapOf("x" to "z", "y" to true)), "b" to 1L)))
        assertEquals(64, a.length)
    }
}
