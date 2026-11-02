// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Markers (plan §7.1's log row, CN14 on the desk): a connection named, hosted, keyed and filled with "zq9marker" never
 * shows it in a toString (what a log or a crash would print), a failure's words (only your connection's name, which you
 * typed), connections.json's plain text (no secret), or a preview (no secret). And core/connect names no network class.
 */
class ConnMarkersTest {
    private val m = "zq9marker"

    private val conn = Connection("c-zq9mk1", ConnKind.WEBHOOK, "Hook", ConnFixtures.url("https://$m.lan/$m"), auth = ConnAuth.Header("X-Zq9marker"),
        ops = listOf(ConnOp("go", "Send $m", OpSpec.Webhook(HttpMethod.POST, "/{{x}}", query = listOf("q" to m), headers = listOf("X-Note" to m),
            body = OpBody.Json(mapOf(m to "{{x}}"))), mapOf("x" to OpParam(ParamType.TEXT, default = m)),
            outputs = mapOf("o" to OpOutput("/$m", ParamType.TEXT)))),
        answers = mapOf("https://$m.lan" to ConsentAnswer.ALLOW), tested = ConnTested(1L, m))
    private val secrets = ConnSecrets(header = "${m}KEY", topic = "${m}Topic", token = "${m}Token", address = "https://$m.lan/$m/KEY")

    @Test fun nothingPrintsIt() {
        assertTrue(ConnCheck.valid(conn))
        val built = (ConnOps.build(conn, "go", mapOf("x" to SkillValue.of("$m value", ParamType.TEXT, ValueSource.MODEL))) as Built.Ok).request
        val wire = (ConnWire.assemble(built, secrets) as ConnWire.Result.Ok).wire
        val reply = ConnReply.read(200, null, "{\"$m\":\"$m\"}".toByteArray(), false, true)
        val things: List<Any> = listOf(conn, conn.address!!, conn.ops[0], conn.ops[0].spec, conn.ops[0].params.getValue("x"), conn.ops[0].outputs.getValue("o"),
            (conn.ops[0].spec as OpSpec.Webhook).body!!, built, built.body!!, built.body!!.parts[0], wire, secrets, reply, ConnPreview.of(built, conn, secrets.masks()),
            ConnOps.build(conn, "go", emptyMap()), ConnWire.assemble(built, secrets), ConnFixtures.handOff().handOff!!, ConnRules.lookup(true, listOf(byteArrayOf(10, 0, 0, 1))),
            OpSpec.NtfyPublish(m), ConnFixtures.homeAssistant().ops[0].spec)
        for (t in things) assertFalse("$t", t.toString().contains(m, ignoreCase = true))
    }

    @Test fun failuresSayOnlyYourName() {
        for (f in ConnFailure.entries) for (lang in Lang.entries) {
            val s = f.text("Home Assistant", status = 404, limit = 30).render(Langs.of(lang))
            assertFalse(s.contains(m))
            assertFalse("$f", s.contains("{"))
        }
        assertEquals("Your Home Assistant didn't answer in time.", ConnFailure.TIMEOUT.text("Home Assistant").en())
        assertEquals(ConnFailure.entries.size, ConnFailure.entries.map { it.code }.toSet().size)
        assertTrue(ConnFailure.entries.all { ConnFailure.of(it.code) == it })
    }

    @Test fun connectionsJsonHoldsNoSecret() {
        val text = ConnectionCodec.encode(listOf(conn, ConnFixtures.ntfy(), ConnFixtures.discord()))
        for (v in listOf(secrets.header!!, secrets.topic!!, secrets.token!!, secrets.address!!)) assertFalse(text.contains(v))
        // A preview shows masks only.
        val p = ConnPreview.of((ConnOps.build(conn, "go", emptyMap()) as Built.Ok).request, conn, secrets.masks())
        assertFalse((p.url + p.headers + p.body + p.auth!!.en()).contains("KEY"))
        assertEquals("X-Zq9marker: your Hook key", p.auth!!.en())
    }

    /** core/connect is pure: no network class, no logging (C3 pins the client's side). */
    @Test fun noNetworkNoLogs() {
        val dir = File(System.getProperty("user.dir"), "src/main/java/app/heyrobot/core/connect")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue(files.size >= 15)
        val net = Regex("""\bjava\.net\b|HttpURLConnection|\bokhttp3\b|\bapp\.heyrobot\.helpers\b|\bInetAddress\b|\bSocket\b|\bRLog\b|\bLog\.[a-z]\(|\bprintln\(""")
        for (f in files) assertFalse(f.name, net.containsMatchIn(f.readText()))
    }
}
