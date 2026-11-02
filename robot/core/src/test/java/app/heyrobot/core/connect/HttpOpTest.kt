// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Building a request (plan §3.3, §5.1): each value encoded for its place (quotes, newlines, "{{", "%", "../", "%2F", NUL,
 * a leading "-"), a typed slot refusing the wrong type, allowed headers only, caps, and the secrets: never in a built
 * request or its preview, put in only by ConnWire, only over https.
 */
class HttpOpTest {
    private var locale: Locale = Locale.getDefault()

    // A French phone writes 45,5: nothing Robot sends may.
    @Before fun french() { Locale.setDefault(Locale.CANADA_FRENCH) }
    @After fun back() { Locale.setDefault(locale) }

    private fun text(s: String, src: ValueSource = ValueSource.YOURS) = SkillValue.of(s, ParamType.TEXT, src)
    private fun int(n: Long, src: ValueSource = ValueSource.MODEL) = SkillValue.of(n, ParamType.INTEGER, src)
    private fun choice(s: String) = SkillValue.of(s, ParamType.CHOICE, ValueSource.YOURS)

    private fun ok(b: Built): HttpRequest = (b as? Built.Ok)?.request ?: throw AssertionError("refused: $b")
    private fun body(r: HttpRequest): String = r.body!!.parts.joinToString("") { (it as BodyPart.Text).text }
    private fun wire(r: HttpRequest, s: ConnSecrets = ConnSecrets()): Wire = (ConnWire.assemble(r, s) as? ConnWire.Result.Ok)?.wire ?: throw AssertionError("refused")

    private val n8n = ConnFixtures.webhook()

    @Test fun aWebhookRequest() {
        val r = ok(ConnOps.build(n8n, "log_trip", mapOf("kind" to choice("work"), "minutes" to int(25), "note" to text("He said \"hi\" then {{left}} 100%"))))
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/webhook/trips/work?source=robot", r.tail)
        assertEquals(listOf("X-Robot-Note" to "He said \"hi\" then {{left}} 100%"), r.headers)
        val json = StrictJson.parse(body(r))!!.value as Map<*, *>
        // A whole template is the value, typed; inside words, text (a value's "{{" is never read as a template); the rest as written.
        assertEquals(25L, json["minutes"])
        assertEquals("Trip: He said \"hi\" then {{left}} 100%", json["note"])
        // In a body, a line break, a quote and NUL are escaped, never cut.
        val c = n8n.copy(ops = listOf(ConnFixtures.n8nPost.copy(spec = (ConnFixtures.n8nPost.spec as OpSpec.Webhook).copy(headers = emptyList()))))
        val b = body(ok(ConnOps.build(c, "log_trip", mapOf("kind" to choice("work"), "minutes" to int(1), "note" to text("a\"b\nc\u0000d\u2028e")))))
        assertTrue(b, b.contains("\"note\":\"Trip: a\\\"b\\nc\\u0000d\\u2028e\""))
        assertEquals("Trip: a\"b\nc\u0000d\u2028e", (StrictJson.parse(b)!!.value as Map<*, *>)["note"])
        assertEquals(listOf(1L, 2.5, true, null), json["fixed"])
        assertEquals("application/json", r.body!!.contentType)
        assertTrue(r.json)
        assertFalse(r.read)
        assertEquals(emptySet<SecretPart>(), r.secrets)
    }

    @Test fun aHeaderTakesPrintableAsciiOnly() {
        val v = mapOf("kind" to choice("work"), "minutes" to int(25))
        // A line break would add a header; anything outside ASCII makes an HTTP client throw: refused, not repaired.
        for (bad in listOf("a\r\nX-Evil: 1", "a\nb", "café", "tab\there", "nul\u0000"))
            assertEquals(bad, Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("note" to text(bad))))
        assertEquals(listOf("X-Robot-Note" to "fine, 100% {{x}}"), ok(ConnOps.build(n8n, "log_trip", v + ("note" to text("fine, 100% {{x}}")))).headers)
    }

    @Test fun pathAndQueryEncoding() {
        val c = n8n.copy(ops = listOf(ConnOp("get", "Look up", OpSpec.Webhook(HttpMethod.GET, "/items/{{id}}/x", query = listOf("q" to "{{q}}", "fixed" to "a b&c")),
            linkedMapOf("id" to OpParam(ParamType.TEXT, maxLen = 100), "q" to OpParam(ParamType.TEXT, maxLen = 100)))))
        fun req(id: String, q: String) = ConnOps.build(c, "get", mapOf("id" to text(id), "q" to text(q)))
        assertEquals("/items/caf%C3%A9%20%22x%22/x?q=a%26b%3Dc%25%7B%7Bz%7D%7D&fixed=a%20b%26c", ok(req("café \"x\"", "a&b=c%{{z}}")).tail)
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), req("ok", "line\nbreak"))
        // A path value never climbs out of its segment.
        for (bad in listOf("../admin", "a/b", "..", "a\\b", "%2F", "x\u0000"))
            if (bad == "%2F") assertEquals("/items/%252F/x?q=ok&fixed=a%20b%26c", ok(req(bad, "ok")).tail)
            else assertEquals(bad, Built.Refused(ConnFailure.BAD_VALUE), req(bad, "ok"))
        // Filled, a segment is never a dot segment or empty: "." alone, or two templates side by side making "..".
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), req(".", "ok"))
        val two = c.copy(ops = listOf(ConnOp("get", "Look up", OpSpec.Webhook(HttpMethod.GET, "/items/{{id}}{{q}}/x"),
            linkedMapOf("id" to OpParam(ParamType.TEXT, maxLen = 100), "q" to OpParam(ParamType.TEXT, maxLen = 100)))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(two, "get", mapOf("id" to text("."), "q" to text("."))))
        assertEquals("/items/.a/x", ok(ConnOps.build(two, "get", mapOf("id" to text("."), "q" to text("a")))).tail)
        // A choice of yours may hold "/", never "." or an empty piece.
        val pick = c.copy(ops = listOf(ConnOp("get", "Look up", OpSpec.Webhook(HttpMethod.GET, "/{{where}}"),
            mapOf("where" to OpParam(ParamType.CHOICE, listOf("a/b", "a/./b", "a//b"))))))
        fun where(v: String) = ConnOps.build(pick, "get", mapOf("where" to choice(v)))
        assertEquals("/a/b", ok(where("a/b")).tail)
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), where("a/./b"))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), where("a//b"))
        // A leading "-" is only text here (it matters for a script's arguments, never for a URL).
        assertEquals("/items/-rf/x?q=-x&fixed=a%20b%26c", ok(req("-rf", "-x")).tail)
        assertTrue(ok(req("a", "b")).read)
    }

    @Test fun aTypedSlotRefusesTheWrongType() {
        val v = mapOf("kind" to choice("work"), "note" to text("x"))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("minutes" to text("25"))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("minutes" to int(601))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("minutes" to SkillValue.of(2.5, ParamType.NUMBER, ValueSource.YOURS))))
        // A choice only one of its values; a missing argument without a default; one the operation doesn't have.
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", mapOf("kind" to choice("mars"), "minutes" to int(1))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", mapOf("minutes" to int(1))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", mapOf("kind" to choice("work"), "minutes" to int(1), "extra" to int(1))))
        // Text longer than its argument takes, or empty.
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("minutes" to int(1)) + ("note" to text("x".repeat(81)))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n8n, "log_trip", v + ("minutes" to int(1)) + ("note" to text(""))))
        // The default fills what isn't given.
        assertEquals("none", ok(ConnOps.build(n8n, "log_trip", mapOf("kind" to choice("home"), "minutes" to int(1)))).values.getValue("note").value)
        assertEquals(Built.Refused(ConnFailure.GONE), ConnOps.build(n8n, "nothing", emptyMap()))
    }

    @Test fun constantsAreTypedByTheirArgument() {
        val p = OpParam(ParamType.INTEGER, min = 0.0, max = 100.0)
        assertEquals(30L, ConnValues.fixed("30", p)!!.value)
        assertEquals(30L, ConnValues.fixed(30, p)!!.value)
        assertNull(ConnValues.fixed("101", p))
        assertNull(ConnValues.fixed("thirty", p))
        assertEquals(setOf(ValueSource.FIXED), ConnValues.fixed(30L, p)!!.sources)
        assertEquals(true, ConnValues.fixed("true", OpParam(ParamType.YES_NO))!!.value)
        assertNull(ConnValues.fixed("light.attic", ConnFixtures.entity("light.kitchen")))
        assertEquals(2.5, ConnValues.fixed(2.5, OpParam(ParamType.NUMBER, min = 0.0, max = 5.0))!!.value)
        assertEquals(ParamType.INTEGER, ConnValues.fixed(2L, OpParam(ParamType.NUMBER, min = 0.0, max = 5.0))!!.type)
    }

    @Test fun aTextBody() {
        val c = n8n.copy(ops = listOf(ConnOp("t", "Text", OpSpec.Webhook(HttpMethod.PUT, "/t", body = OpBody.Text("Note: {{n}}\n")),
            mapOf("n" to OpParam(ParamType.TEXT)))))
        val r = ok(ConnOps.build(c, "t", mapOf("n" to text("a \"b\" {{c}}"))))
        assertEquals("Note: a \"b\" {{c}}\n", body(r))
        assertEquals("text/plain; charset=utf-8", r.body!!.contentType)
        // Your own Content-Type, when you set one (C12's M1: plain text or a form, for a text body).
        val typed = c.copy(ops = listOf(c.ops[0].copy(spec = (c.ops[0].spec as OpSpec.Webhook).copy(headers = listOf("Content-Type" to "text/plain; charset=us-ascii")))))
        val r2 = ok(ConnOps.build(typed, "t", mapOf("n" to text("x"))))
        assertEquals("text/plain; charset=us-ascii", r2.body!!.contentType)
        assertTrue(r2.headers.isEmpty())
    }

    /**
     * C12's M1: a form post (a bot's, a push service's, an SMS gateway's) takes each value percent-encoded, your words as
     * you typed them, so "&", "=", "+" and "%" in a value arrive as written and no value can add a field.
     */
    @Test fun aFormBodyEncodesEachValue() {
        fun form(type: String) = n8n.copy(ops = listOf(ConnOp("send", "Send", OpSpec.Webhook(HttpMethod.POST, "/send", headers = listOf("Content-Type" to type),
            body = OpBody.Text("chat_id=123&text={{msg}}")), mapOf("msg" to OpParam(ParamType.TEXT, maxLen = 200)))))
        for (type in listOf("application/x-www-form-urlencoded", "Application/X-WWW-Form-Urlencoded; charset=UTF-8")) {
            val c = form(type)
            assertTrue(type, ConnCheck.valid(c))
            val r = ok(ConnOps.build(c, "send", mapOf("msg" to text("Tom & Jerry=1+1 100%"))))
            assertEquals("chat_id=123&text=Tom%20%26%20Jerry%3D1%2B1%20100%25", body(r))
            assertEquals(type, r.body!!.contentType)
            // Words the model wrote can't add a second chat_id.
            assertEquals("chat_id=123&text=on%20my%20way%26chat_id%3D999", body(ok(ConnOps.build(c, "send", mapOf("msg" to text("on my way&chat_id=999", ValueSource.MODEL))))))
        }
        // A text body under a JSON label (one that slipped past its checks) is never built: a quote in a value would end its string.
        val labelled = n8n.copy(ops = listOf(ConnOp("t", "Text", OpSpec.Webhook(HttpMethod.POST, "/t", headers = listOf("Content-Type" to "application/json"),
            body = OpBody.Text("{\"text\":\"{{msg}}\"}")), mapOf("msg" to OpParam(ParamType.TEXT)))))
        assertFalse(ConnCheck.valid(labelled))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(labelled, "t", mapOf("msg" to text("hi\",\"to\":\"them"))))
    }

    /** C12's L5: a query's value is words, not a path: dots and slashes are fine there, encoded; a path's still refuses them. */
    @Test fun aQueryValueMayHoldDotsAndSlashes() {
        val c = n8n.copy(ops = listOf(ConnOp("note", "Notify", OpSpec.Webhook(HttpMethod.GET, "/notify", query = listOf("text" to "{{msg}}")),
            mapOf("msg" to OpParam(ParamType.TEXT, maxLen = 100)))))
        fun tail(s: String) = ok(ConnOps.build(c, "note", mapOf("msg" to text(s)))).tail
        assertEquals("/notify?text=On%20my%20way...", tail("On my way..."))
        assertEquals("/notify?text=back%20at%203%2F4", tail("back at 3/4"))
        assertEquals("/notify?text=..%2F..%2Fadmin%5Cx", tail("../../admin\\x"))
        // Still never a control character.
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(c, "note", mapOf("msg" to text("two\nlines"))))
        // A path segment keeps its rule.
        val path = n8n.copy(ops = listOf(ConnOp("get", "Get", OpSpec.Webhook(HttpMethod.GET, "/items/{{id}}"), mapOf("id" to OpParam(ParamType.TEXT, maxLen = 100)))))
        for (bad in listOf("On my way...", "back at 3/4", "..")) assertEquals(bad, Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(path, "get", mapOf("id" to text(bad))))
    }

    @Test fun secretsComeOnlyFromConnWire() {
        val c = n8n.copy(auth = ConnAuth.Bearer)
        val r = ok(ConnOps.build(c, "log_trip", mapOf("kind" to choice("work"), "minutes" to int(5))))
        assertEquals(setOf(SecretPart.TOKEN), r.secrets)
        assertTrue(r.headers.none { it.first.equals("Authorization", true) })
        // Without the key: nothing goes.
        assertEquals(ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(r, ConnSecrets()))
        val w = wire(r, ConnSecrets(token = "abc.DEF-123"))
        assertEquals("https://n8n.example.org/webhook/trips/work?source=robot", w.url)
        assertEquals("Authorization" to "Bearer abc.DEF-123", w.headers.last())
        assertEquals("Wire(POST)", w.toString())
        // A token that would break a header is never sent.
        assertEquals(ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(r, ConnSecrets(token = "abc\r\nX-Evil: 1")))
        // Basic: user:password, base64; a header of your naming.
        val basic = ok(ConnOps.build(n8n.copy(auth = ConnAuth.Basic), "log_trip", mapOf("kind" to choice("work"), "minutes" to int(5))))
        assertEquals("Authorization" to "Basic dXNlcjpwYXNzOndvcmQ=", wire(basic, ConnSecrets(token = "user:pass:word")).headers.last())
        assertEquals(ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(basic, ConnSecrets(token = "nouser")))
        val header = ok(ConnOps.build(n8n.copy(auth = ConnAuth.Header("X-Api-Key")), "log_trip", mapOf("kind" to choice("work"), "minutes" to int(5))))
        assertEquals("X-Api-Key" to "k-123", wire(header, ConnSecrets(header = "k-123")).headers.last())
    }

    /** D2 a last time, where the secrets go in: an http request never carries one, whatever was saved. */
    @Test fun aSecretNeverGoesOverHttp() {
        val http = ConnFixtures.url("http://192.168.1.5:5678")
        // A connection that slipped past its checks: ConnWire still refuses.
        val r = HttpRequest(HttpMethod.POST, http, false, "/x", emptyList(), emptyList(), null, ConnAuth.Bearer, read = false, json = false)
        assertEquals(ConnWire.Result.Refused(ConnFailure.KEY_OVER_HTTP), ConnWire.assemble(r, ConnSecrets(token = "abc")))
        assertEquals(Built.Refused(ConnFailure.KEY_OVER_HTTP), ConnOps.build(n8n.copy(address = http, auth = ConnAuth.Bearer), "log_trip",
            mapOf("kind" to choice("work"), "minutes" to int(5))))
        // Without a secret, http at home goes.
        assertEquals("http://192.168.1.5:5678/webhook/trips/work?source=robot",
            wire(ok(ConnOps.build(n8n.copy(address = http), "log_trip", mapOf("kind" to choice("work"), "minutes" to int(5))))).url)
    }

    /** An address that is itself the key: its scheme lives in the secret, so ConnWire checks it, and its origin. */
    @Test fun aSecretAddress() {
        val d = ConnFixtures.discord()
        val r = ok(ConnOps.build(d, "post", mapOf("text" to text("Hi \"all\""))))
        assertEquals(setOf(SecretPart.ADDRESS), r.secrets)
        val w = wire(r, ConnSecrets(address = "https://discord.example.com/api/webhooks/123/AbCdEf?wait=true"))
        assertEquals("https://discord.example.com/api/webhooks/123/AbCdEf?wait=true", w.url)
        assertEquals("{\"content\":\"Hi \\\"all\\\"\"}", String(w.body!!))
        // Pasted as http: refused, though the connection says https.
        assertEquals(ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(r, ConnSecrets(address = "http://discord.example.com/api/webhooks/1/x")))
        // Another host than the one the card shows and the first-use answer named: never.
        assertEquals(ConnWire.Result.Refused(ConnFailure.NOWHERE), ConnWire.assemble(r, ConnSecrets(address = "https://evil.example.com/api/webhooks/1/x")))
        assertEquals(ConnWire.Result.Refused(ConnFailure.NOWHERE), ConnWire.assemble(r, ConnSecrets(address = "https://discord.example.com:8443/api/webhooks/1/x")))
        // A path and a query after the key's.
        val c2 = d.copy(ops = listOf(ConnFixtures.discordPost.copy(spec = OpSpec.Webhook(HttpMethod.POST, "/messages", query = listOf("thread_id" to "9")))))
        val r2 = ok(ConnOps.build(c2, "post", mapOf("text" to text("x"))))
        assertEquals("https://discord.example.com/api/webhooks/1/x/messages?wait=true&thread_id=9",
            wire(r2, ConnSecrets(address = "https://discord.example.com/api/webhooks/1/x?wait=true")).url)
    }

    @Test fun aPreviewNeverHoldsAKey() {
        val ha = ConnFixtures.homeAssistant()
        val r = ok(ConnOps.build(ha, "light_off", mapOf("entity" to choice("light.kitchen"))))
        val token = "eyJhbGciOiJIUzI1NiJ9.zq9markerToken.Sig"
        val p = ConnPreview.of(r, ha, ConnSecrets(token = token).masks())
        assertEquals("POST", p.method)
        assertEquals("https://home.tail1234.ts.net/api/services/light/turn_off", p.url)
        assertEquals("{\"entity_id\":\"light.kitchen\"}", p.body)
        assertEquals("Authorization: your Home Assistant token", p.auth!!.en())
        assertFalse((p.url + p.body + p.headers + p.auth!!.en() + p.toString()).contains("zq9marker"))
        // A secret address shows only its masked end; ntfy's topic only its mask in the body.
        val d = ConnFixtures.discord()
        val dp = ConnPreview.of(ok(ConnOps.build(d, "post", mapOf("text" to text("x")))), d, ConnSecrets(address = "https://discord.example.com/api/webhooks/1/zq9markerAB12").masks())
        assertEquals("https://discord.example.com/…AB12", dp.url)
        val n = ConnFixtures.ntfy()
        val np = ConnPreview.of(ok(ConnOps.build(n, "tell_family", mapOf("message" to text("I'm home.")))), n, ConnSecrets(topic = "zq9markerFamily", token = "tk_x").masks())
        assertFalse(np.body!!.contains("zq9marker"))
        assertTrue(np.body!!.startsWith("{\"topic\":\"…ily\""))
        // A mask that isn't one ("…" and up to 4 characters) is never shown.
        assertEquals("https://discord.example.com/…", ConnPreview.of(ok(ConnOps.build(d, "post", mapOf("text" to text("x")))), d,
            mapOf(SecretPart.ADDRESS to "zq9marker")).url)
    }

    @Test fun masks() {
        assertEquals("…", ConnSecrets.mask("abc"))
        assertEquals("…", ConnSecrets.mask("abcdefg"))
        assertEquals("…gh", ConnSecrets.mask("abcdefgh"))
        assertEquals("…lmno", ConnSecrets.mask("abcdefghijklmno".padStart(16, 'x')))
        assertEquals("…AB12", ConnSecrets.mask("tk_" + "x".repeat(25) + "AB12"))
    }

    @Test fun bodyCaps() {
        val big = "x".repeat(ConnCheck.MAX_TEXT)
        val c = n8n.copy(ops = listOf(ConnOp("t", "Text", OpSpec.Webhook(HttpMethod.POST, "/t", body = OpBody.Json(mapOf("a" to "{{a}}"))),
            mapOf("a" to OpParam(ParamType.TEXT, maxLen = ConnCheck.MAX_TEXT)))))
        assertTrue(ConnOps.build(c, "t", mapOf("a" to text(big))) is Built.Ok)
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(c, "t", mapOf("a" to text(big + "x"))))
        // A body on GET can't be made (an HTTP client throws on one).
        assertFalse(ConnCheck.valid(n8n.copy(ops = listOf(c.ops[0].copy(spec = (c.ops[0].spec as OpSpec.Webhook).copy(method = HttpMethod.GET))))))
    }
}
