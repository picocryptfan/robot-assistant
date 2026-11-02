// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.StepCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The webhook form's core side (plan §3.3, §6.3; C6): the form becomes the operation connections.json keeps, each field's
 * problem is ConnCheck's own (so the form and the file never disagree), and a pasted address that holds a secret keeps
 * only its origin.
 */
class WebhookFormTest {
    @Test fun aNewSecretAddressHooksMoneyHintStartsTheForm() {
        val c = Connection("c-wh0009", ConnKind.WEBHOOK, "Pay", ConnUrl.parse("https://hooks.example.org")!!, secretAddress = true,
            addressMarks = setOf(app.heyrobot.core.skills.SkillEffect.MONEY))
        assertEquals(setOf(app.heyrobot.core.skills.SkillEffect.MONEY), WebhookForm.startMarks(c))
        // An operation's own marks are your answer from then on.
        val op = ConnOp(WebhookForm.OP_ID, "Pay", OpSpec.Webhook(HttpMethod.POST))
        assertEquals(emptySet<Any>(), WebhookForm.startMarks(c.copy(ops = listOf(op))))
        val back = ConnectionCodec.decode(ConnectionCodec.encode(listOf(c))) as ConnFile.Ok
        assertEquals(c.addressMarks, back.connections[0].addressMarks)
        assertFalse(ConnectionCodec.encode(listOf(c.copy(addressMarks = emptySet()))).contains("address_marks"))
    }

    private val c = ConnFixtures.webhook().copy(ops = emptyList())
    private val text = OpParam(ParamType.TEXT, maxLen = 80)
    private val kind = OpParam(ParamType.CHOICE, listOf("work", "home"))

    private val good = WebhookForm.Fields("Log a trip", "post", "/webhook/trips/{{kind}}", query = listOf("source" to "robot"),
        headers = listOf("X-Robot-Note" to "{{note}}", "Content-Type" to "application/json"), body = """{"note": "Trip: {{note}}", "n": 2}""",
        params = linkedMapOf("kind" to kind, "note" to text), outputs = mapOf("saved" to OpOutput("/saved", ParamType.YES_NO)),
        check = StepCheck.Equals("/ok", true), marks = setOf(SkillEffect.MESSAGES_SOMEONE))

    private fun problems(f: WebhookForm.Fields) = (WebhookForm.make(c, f) as? WebhookForm.Made.Problems)?.fields ?: emptyMap()

    private fun typed(type: String, body: String, json: Boolean) = WebhookForm.Fields("Send", "POST", "/send", headers = listOf("Content-Type" to type),
        body = body, json = json, params = mapOf("msg" to text))

    /** C12's M1: a plain-text body goes as plain text or as a form (each value encoded), never under another label. */
    @Test fun aTextBodyTakesOnlyTextOrForm() {
        for (type in listOf("application/json", "application/xml", "text/html", "application/vnd.api+json", "text/markdown"))
            assertEquals(type, mapOf(WebhookForm.Field.HEADERS to ConnProblem.CONTENT_TYPE), problems(typed(type, "chat_id=1&text={{msg}}", json = false)))
        for (type in listOf("text/plain", "text/plain; charset=utf-8", "application/x-www-form-urlencoded", "application/x-www-form-urlencoded; charset=UTF-8"))
            assertEquals(type, emptyMap<WebhookForm.Field, ConnProblem>(), problems(typed(type, "chat_id=1&text={{msg}}", json = false)))
        // No Content-Type: plain text.
        assertTrue(WebhookForm.make(c, typed("x", "{{msg}}", json = false).copy(headers = emptyList())) is WebhookForm.Made.Ok)
    }

    /** C12's M1: a JSON body, when you set its Content-Type, goes as JSON. */
    @Test fun aJsonBodyTakesOnlyAJsonType() {
        for (type in listOf("text/plain", "application/x-www-form-urlencoded", "application/xml"))
            assertEquals(type, mapOf(WebhookForm.Field.HEADERS to ConnProblem.CONTENT_TYPE), problems(typed(type, """{"text": "{{msg}}"}""", json = true)))
        // The words the form shows under Headers.
        assertEquals("A text body goes as text/plain or application/x-www-form-urlencoded, and a JSON body as application/json.",
            ConnProblem.CONTENT_TYPE.text("n8n").render(app.heyrobot.core.i18n.Langs.EN))
        for (type in listOf("application/json", "application/json; charset=utf-8", "application/vnd.api+json", "Application/JSON"))
            assertEquals(type, emptyMap<WebhookForm.Field, ConnProblem>(), problems(typed(type, """{"text": "{{msg}}"}""", json = true)))
    }

    @Test fun aFilledFormIsTheOperation() {
        val op = (WebhookForm.make(c, good) as WebhookForm.Made.Ok).op
        assertEquals(WebhookForm.OP_ID, op.id)
        val s = op.spec as OpSpec.Webhook
        assertEquals(HttpMethod.POST, s.method)
        assertEquals(OpBody.Json(mapOf("note" to "Trip: {{note}}", "n" to 2L)), s.body)
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), op.marks)
        // What the form makes, the file saves.
        assertTrue(ConnCheck.valid(c.copy(ops = listOf(op))))
        // A plain-text body, and a GET with none.
        assertTrue(WebhookForm.make(c, good.copy(body = "Trip: {{note}}", json = false, headers = listOf("X-Robot-Note" to "{{note}}"))) is WebhookForm.Made.Ok)
        assertTrue(WebhookForm.make(c, WebhookForm.Fields("Count", "GET", "/count", outputs = mapOf("n" to OpOutput("/n", ParamType.INTEGER)))) is WebhookForm.Made.Ok)
    }

    @Test fun eachFieldSaysItsOwnProblem() {
        assertEquals(setOf(WebhookForm.Field.TITLE), problems(good.copy(title = " ")).keys)
        assertEquals(setOf(WebhookForm.Field.METHOD), problems(good.copy(method = "TRACE")).keys)
        assertEquals(setOf(WebhookForm.Field.PATH), problems(good.copy(path = "webhook/{{kind}}")).keys)
        assertEquals(setOf(WebhookForm.Field.QUERY), problems(good.copy(query = listOf("bad name" to "x"))).keys)
        // Never a key's header, a method override or Host; Content-Type only with a body.
        for (h in listOf("Authorization", "X-Api-Key", "X-HTTP-Method-Override", "Host", "Cookie"))
            assertEquals(h, setOf(WebhookForm.Field.HEADERS), problems(good.copy(headers = listOf(h to "x") + good.headers)).keys)
        assertEquals(setOf(WebhookForm.Field.HEADERS), problems(WebhookForm.Fields("x", "GET", headers = listOf("Content-Type" to "application/json"))).keys)
        // A body that isn't JSON, or a GET with a body.
        assertEquals(setOf(WebhookForm.Field.BODY), problems(good.copy(body = "{not json")).keys)
        assertTrue(WebhookForm.Field.BODY in problems(good.copy(method = "GET")))
        assertEquals(setOf(WebhookForm.Field.OUTPUTS), problems(good.copy(outputs = mapOf("Bad Name" to OpOutput("/x", ParamType.TEXT)))).keys)
        assertEquals(setOf(WebhookForm.Field.CHECK), problems(good.copy(check = StepCheck.Equals("no-slash", true))).keys)
        // An argument nothing uses, or a slot naming none.
        assertEquals(setOf(WebhookForm.Field.ARGUMENTS), problems(good.copy(params = good.params + ("extra" to text))).keys)
        assertTrue(problems(good.copy(params = mapOf("kind" to kind))).isNotEmpty())
        // A key typed into a field is refused there.
        val key = "sk_live_" + "a1B2c3D4".repeat(4)
        assertEquals(mapOf(WebhookForm.Field.QUERY to ConnProblem.SECRET_VALUE), problems(good.copy(query = listOf("token" to key))))
    }

    @Test fun anAddressThatHoldsASecretKeepsOnlyItsOrigin() {
        val typed = "https://discord.example.com/api/webhooks/123456/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"
        val (a, p) = WebhookForm.address(typed, holdsSecret = true)
        assertNull(p)
        assertEquals("https://discord.example.com", a!!.kept.text)
        assertEquals(typed, a.secret)
        assertTrue(a.marks.isEmpty())
        // The connection it makes is one ConnCheck saves, and its card shows only the origin.
        val conn = Connection("c-disc02", ConnKind.WEBHOOK, "Discord", a.kept, secretAddress = true)
        assertTrue(ConnCheck.valid(conn))
        // A money word in its path becomes a mark; http with a secret is refused; a plain address is kept whole.
        assertEquals(setOf(SkillEffect.MONEY), WebhookForm.address("https://n8n.example.org/webhook/pay/AbCdEfGhIjKlMnOp", true).first!!.marks)
        assertEquals(null to ConnProblem.KEY_OVER_HTTP, WebhookForm.address("http://n8n.example.org/webhook/AbCdEfGh", true).let { it.first to it.second })
        val plain = WebhookForm.address("http://192.168.1.9/lamp", false).first!!
        assertEquals("http://192.168.1.9/lamp", plain.kept.text)
        assertNull(plain.secret)
        assertEquals(ConnProblem.ADDRESS, WebhookForm.address("ftp://x", false).second)
    }
}
