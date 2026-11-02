// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.Prompt
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Action
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime

/**
 * E2's desk run (everyday-set-plan.md §4.8), Robot's own side of it: skipped unless SUMS_DESK is set (a desk tool, never part
 * of a normal run). SUMS_DESK=dump writes, for each question of robot/tools/sums/questions.tsv, the system prompt and the
 * tools the phone would describe with and without calculate, and SumsRouter's own reply; SUMS_DESK=calls reads the model's
 * replies (SUMS_DESK_IN) and writes what Robot's code says to each calculate call in them. Output under core/build/sums-desk/.
 * robot/tools/sums/desk_sums.py asks the model and scores both.
 */
class SumsDeskTest {
    private val now = ZonedDateTime.parse("2026-10-23T09:30:00-03:00[America/Halifax]")
    private val mode: String? = System.getenv("SUMS_DESK")
    private val out = File("build/sums-desk").apply { mkdirs() }

    private fun questions(): List<List<String>> = File("../tools/sums/questions.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }

    private fun langs(l: String) = if (l == "fr") Langs(Lang.FR, Lang.FR, Lang.FR) else Langs(Lang.EN, Lang.EN, Lang.EN)

    @Test fun dump() {
        assumeTrue(mode == "dump")
        val s = RobotSettings()
        File(out, "prompts.jsonl").printWriter().use { w ->
            for (q in questions()) {
                val (id, lang) = q
                val text = q[5]
                val groups = ToolRouter.route(text)
                fun specs(g: Set<ToolGroup>) = JSONArray(ToolRouter.tools(g, s).map { JSONObject(ToolSpecs.json(it)) })
                val with = ToolRouter.tools(groups, s)
                val system = Prompt.system(now, locked = false, texting = with.any { it.name == "SEND_TEXT" }, weather = with.any { it.name == "WEATHER" },
                    phone = with.any { it.name == "PHONE_STATUS" }, direct = s.directAnswers)
                val clock = if (Prompt.needsClock(text, groups)) Prompt.clock(now) else ""
                val routed = SumsRouter.route(text, now)
                w.println(JSONObject().put("id", id).put("lang", lang).put("system", system).put("user", text + clock)
                    .put("sums_group", ToolGroup.SUMS in groups)
                    .put("tools_with", specs(groups)).put("tools_without", specs(groups - ToolGroup.SUMS))
                    .put("router", routed?.let { Sums.answer(it).text.render(langs(lang)) } ?: JSONObject.NULL))
            }
        }
    }

    @Test fun calls() {
        assumeTrue(mode == "calls")
        val input = File(System.getenv("SUMS_DESK_IN") ?: return)
        File(out, "calls.jsonl").printWriter().use { w ->
            for (line in input.readLines().filter { it.isNotBlank() }) {
                val o = JSONObject(line)
                val raw = o.getString("raw").replace("<|tool_call>", "").replace("<tool_call|>", "").replace("<|tool_response>", "").replace("<|\"|>", "<escape>")
                val calls = ToolCalls.parseText(raw).filter { it.call.name == "calculate" }
                val said = calls.map { c ->
                    when (val p = ToolCalls.parse(c.call)) {
                        is ParsedCall.Ok -> Calculate.run((p.action as Action.Calculate).ask).text.render(langs(o.getString("lang")))
                        is ParsedCall.Invalid -> "INVALID"
                    }
                }
                w.println(JSONObject().put("id", o.getString("id")).put("cond", o.getString("cond")).put("calls", JSONArray(calls.map { JSONObject(it.call.args) }))
                    .put("code", JSONArray(said)))
            }
        }
    }
}
