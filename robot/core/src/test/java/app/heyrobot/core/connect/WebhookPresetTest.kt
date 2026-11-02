// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Webhooks (plan §3.3): the header allowlist, methods and their effects, outputs as pointers, caps, and a key-shaped value refused. */
class WebhookPresetTest {
    private val c = ConnFixtures.webhook()
    private val op = ConnFixtures.n8nPost
    private val spec = op.spec as OpSpec.Webhook

    private fun withHeaders(vararg h: Pair<String, String>) = c.copy(ops = listOf(op.copy(spec = spec.copy(headers = h.toList()))))

    @Test fun headerAllowlist() {
        for (ok in listOf("X-Robot-Note" to "{{note}}", "x-trace" to "a", "Accept" to "application/json", "Content-Type" to "application/json; charset=utf-8"))
            assertTrue(ok.first, ConnCheck.valid(withHeaders(ok, "X-N" to "{{note}}")))
        val never = listOf("Authorization", "Cookie", "Host", "Content-Length", "Transfer-Encoding", "Connection", "User-Agent", "Origin", "Referer",
            "X-Api-Key", "X-Auth-Token", "X-Secret", "X-Session-Id", "X-Password", "X-Signature", "X-HTTP-Method-Override", "x-http-method",
            "X-Method-Override", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP", "Proxy-Authorization", "Forwarded", "Via", "Expect", "TE", "Trailer",
            "X-", "X-" + "a".repeat(41), "X Space", "Upgrade", "Keep-Alive")
        for (n in never) assertFalse(n, ConnCheck.valid(withHeaders(n to "x", "X-N" to "{{note}}")))
        // Twice the same name, any case; Content-Type without a body; a templated Accept.
        assertFalse(ConnCheck.valid(withHeaders("X-A" to "1", "x-a" to "2", "X-N" to "{{note}}")))
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(body = null, headers = listOf("Content-Type" to "text/plain", "X-N" to "{{note}}")))))))
        assertFalse(ConnCheck.valid(withHeaders("Accept" to "{{note}}")))
        // The secret header may be Authorization (an API's own scheme), never one the client controls.
        assertTrue(ConnCheck.valid(c.copy(auth = ConnAuth.Header("Authorization"))))
        for (n in listOf("Host", "Content-Length", "Transfer-Encoding", "Connection", "Cookie", "X-Forwarded-For", "X-HTTP-Method-Override", "Content-Type", "bad name"))
            assertFalse(n, ConnCheck.valid(c.copy(auth = ConnAuth.Header(n))))
    }

    @Test fun methodsAndEffects() {
        fun eff(m: HttpMethod, path: String = "/x") = ConnOps.effects(c, ConnOp("o", "O", OpSpec.Webhook(m, path)))
        assertEquals(setOf(SkillEffect.READS_ONLINE), eff(HttpMethod.GET))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE), eff(HttpMethod.POST))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE), eff(HttpMethod.PUT))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE), eff(HttpMethod.PATCH))
        assertEquals(setOf(SkillEffect.DELETES), eff(HttpMethod.DELETE))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.MONEY), eff(HttpMethod.POST, "/api/orders/new"))
        // The address's own path counts too.
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.MONEY),
            ConnOps.effects(c.copy(address = ConnFixtures.url("https://shop.example.org/checkout")), ConnOp("o", "O", OpSpec.Webhook(HttpMethod.POST, "/x"))))
        assertTrue(ConnOps.reads(ConnOp("o", "O", OpSpec.Webhook(HttpMethod.GET, "/x"))))
        assertFalse(ConnOps.reads(op))
        // One operation per webhook.
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op, op.copy(id = "again")))))
    }

    @Test fun outputsAndTheCheck() {
        val r = ConnReply.read(200, "application/json", """{"saved":"yes","count":"12","ok":true}""".toByteArray(), false, true) as ReplyRead.Ok
        assertEquals(mapOf("saved" to true, "count" to 12L), ConnOps.outputs(op, r.json).mapValues { it.value.value })
        assertTrue(ConnOps.checked(op, r.json))
        assertFalse(ConnOps.checked(op, StrictJson.parse("""{"ok":false}""")!!.value))
        // The request wants JSON back only when outputs or a check read it.
        assertTrue(WebhookPreset.op(op).json)
        assertFalse(WebhookPreset.op(ConnOp("o", "O", OpSpec.Webhook(HttpMethod.POST, "/x"))).json)
        // At most 8 outputs, named as a skill's templates can name them.
        val nine = (1..9).associate { "o$it" to OpOutput("/$it", ParamType.TEXT) }
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(outputs = nine)))))
    }

    @Test fun keyShapedValuesAreRefused() {
        val key = "3f2a9c1e7b4d4e2a9c1e7b4d4e2a9c1e"
        assertEquals(listOf(ConnProblem.SECRET_VALUE), ConnCheck.problems(withHeaders("X-Robot-Note" to key, "X-N" to "{{note}}")))
        assertEquals(listOf(ConnProblem.SECRET_VALUE), ConnCheck.problems(c.copy(ops = listOf(op.copy(spec = spec.copy(query = listOf("token" to key)))))))
        assertEquals(listOf(ConnProblem.SECRET_VALUE), ConnCheck.problems(c.copy(ops = listOf(op.copy(spec = spec.copy(path = "/hooks/$key/{{kind}}"))))))
        assertEquals(listOf(ConnProblem.SECRET_VALUE), ConnCheck.problems(c.copy(ops = listOf(op.copy(spec = spec.copy(body = OpBody.Json(mapOf("k" to "{{minutes}} $key", "n" to "{{note}}"))))))))
        // An address with a key in it, not marked as one; marked, it's fine (only its origin is kept).
        assertEquals(listOf(ConnProblem.SECRET_VALUE), ConnCheck.problems(c.copy(address = ConnFixtures.url("https://n8n.example.org/webhook/$key"))))
        assertTrue(ConnCheck.valid(ConnFixtures.discord()))
        assertEquals("This looks like a password or key. Keep it in the connection's key instead.", ConnProblem.SECRET_VALUE.text("n8n").en())
    }

    @Test fun capsAndShapes() {
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(path = "/" + "a".repeat(ConnCheck.MAX_PATH)))))))
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(query = (1..17).map { "q$it" to "1" }))))))
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(body = OpBody.Json(mapOf("k" to "x".repeat(ConnCheck.MAX_BODY), "a" to "{{minutes}} {{note}}"))))))))
        var deep: Any? = "{{note}}"
        repeat(ConnCheck.MAX_BODY_DEPTH + 1) { deep = listOf(deep) }
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(body = OpBody.Json(mapOf("d" to deep, "m" to "{{minutes}}"))))))))
        // An Int in a template isn't what the file reads back (a whole number is a Long): refused, so a save never changes it.
        assertFalse(ConnCheck.valid(c.copy(ops = listOf(op.copy(spec = spec.copy(body = OpBody.Json(mapOf("n" to 1, "a" to "{{minutes}} {{note}}"))))))))
        // A plain value in a body takes any language.
        val v = mapOf("kind" to SkillValue.of("work", ParamType.CHOICE, ValueSource.YOURS), "minutes" to SkillValue.of(5L, ParamType.INTEGER, ValueSource.YOURS),
            "note" to SkillValue.of("ok", ParamType.TEXT, ValueSource.YOURS))
        assertTrue(ConnOps.build(c, "log_trip", v) is Built.Ok)
    }
}
