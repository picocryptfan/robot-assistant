// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// An MCP tool's card shows everything that will be sent (S3.11's review, finding 2): arguments longer than the card's
// preview (ConnStep.PREVIEW_BODY) are refused before the card, never cut on it and sent whole.
class McpCardTest {
    private val now = 1_761_100_000_000L
    private val input = mapOf("type" to "object", "properties" to mapOf("text" to mapOf("type" to "string")), "required" to listOf("text"))
    private val note = ConnOp("save_note", "Save a note", OpSpec.McpCall("save_note", input, emptySet(), reads = false),
        mapOf("text" to OpParam(ParamType.TEXT, maxLen = 4000)))
    private val origin = "https://192.168.1.5:8123"
    private val c = Connection("c-mcp001", ConnKind.MCP, "Jarvis", ConnUrl.parse("$origin/api/mcp")!!, auth = ConnAuth.Bearer, ops = listOf(note),
        tested = ConnTested(now - 1000), answers = mapOf(origin to ConsentAnswer.ALLOW))

    private fun prepare(text: String): Prepared = ConnStep.prepare(
        ConnStepAction(ConnStepCall(StepKind.CONN, c.id, "save_note", mapOf("text" to StepArg.Fixed(text)), "Notes", 1)),
        StepContext(c, true, null, true, mapOf(SecretPart.TOKEN to "…AB12"), LimitState(), now))

    @Test fun argumentsLongerThanTheCardAreRefusedNotCut() {
        val long = prepare("x".repeat(ConnStep.PREVIEW_BODY))
        assertTrue(long.toString(), long is Prepared.Refused)
        assertEquals(ConnFailure.MCP_TOO_LONG.text("Jarvis").render(Langs.EN), (long as Prepared.Refused).text!!.render(Langs.EN))
    }

    @Test fun argumentsThatFitAreShownWhole() {
        val text = "y".repeat(ConnStep.PREVIEW_BODY - 20)
        val ready = prepare(text) as? Prepared.Ready ?: throw AssertionError("refused")
        val body = (ready.action as ConnStepAction).prepared!!.body.render(Langs.EN)
        assertTrue(body, McpPolicy.canonical(mapOf("text" to text)) in body && "…" !in body)
    }
}
