// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ntfy (plan §3.2): the JSON publish to the server's root, the topic only in its place, 4,096 bytes, Test publishes nothing. */
class NtfyPresetTest {
    private val n = ConnFixtures.ntfy()
    private fun text(s: String, src: ValueSource = ValueSource.MODEL) = SkillValue.of(s, ParamType.TEXT, src)
    private fun ok(b: Built) = (b as Built.Ok).request

    @Test fun publish() {
        val r = ok(ConnOps.build(n, "tell_family", mapOf("message" to text("Arrivé à la maison \"enfin\""))))
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/", r.tail)
        assertEquals(setOf(SecretPart.TOKEN, SecretPart.TOPIC), r.secrets)
        // The topic is nowhere but its place in the body.
        assertTrue(r.body!!.parts.first() is BodyPart.Text)
        assertEquals(BodyPart.Secret(SecretPart.TOPIC), r.body!!.parts[1])
        val w = (ConnWire.assemble(r, ConnSecrets(token = "tk_AgQdq7mVBoFD37zQVN29RhuMzNIz2", topic = "family_x7q2")) as ConnWire.Result.Ok).wire
        assertEquals("https://ntfy.example.org/", w.url)
        assertEquals("Authorization" to "Bearer tk_AgQdq7mVBoFD37zQVN29RhuMzNIz2", w.headers.last())
        // A title in any language: in the JSON, never a header (an HTTP client takes only ASCII there).
        assertEquals(mapOf("topic" to "family_x7q2", "message" to "Arrivé à la maison \"enfin\"", "title" to "From Robot", "priority" to 3L, "tags" to listOf("house")),
            StrictJson.parse(w.body!!)!!.value)
        assertTrue(w.headers.none { it.first.equals("Title", true) || it.first.equals("Priority", true) })
        assertFalse(w.url.contains("family"))
    }

    @Test fun aServerBehindAPathAndNoToken() {
        val c = n.copy(address = ConnFixtures.url("https://example.org/ntfy"), auth = ConnAuth.None)
        val r = ok(ConnOps.build(c, "tell_family", mapOf("message" to text("x"))))
        assertEquals(setOf(SecretPart.TOPIC), r.secrets)
        assertEquals("https://example.org/ntfy/", (ConnWire.assemble(r, ConnSecrets(topic = "t1")) as ConnWire.Result.Ok).wire.url)
        // The topic is a secret: without it nothing goes; one that isn't a topic never goes.
        assertEquals(ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(r, ConnSecrets()))
        for (bad in listOf("", "a b", "a/b", "x".repeat(65), "é", "a\"b"))
            assertEquals(bad, ConnWire.Result.Refused(ConnFailure.NO_KEY), ConnWire.assemble(r, ConnSecrets(topic = bad)))
    }

    @Test fun fourThousandNinetySixBytes() {
        // Bytes, not characters: "é" is two.
        assertTrue(ConnOps.build(n.copy(ops = listOf(big())), "tell_family", mapOf("message" to text("é".repeat(2048)))) is Built.Ok)
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n.copy(ops = listOf(big())), "tell_family", mapOf("message" to text("é".repeat(2048) + "x"))))
        // Fixed words count too.
        val worded = big().copy(spec = OpSpec.NtfyPublish("Note: {{message}}"))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(n.copy(ops = listOf(worded)), "tell_family", mapOf("message" to text("x".repeat(4091)))))
        assertTrue(ConnOps.build(n.copy(ops = listOf(worded)), "tell_family", mapOf("message" to text("x".repeat(4090)))) is Built.Ok)
    }

    private fun big() = ConnOp("tell_family", "Tell", OpSpec.NtfyPublish("{{message}}"), mapOf("message" to OpParam(ParamType.TEXT, maxLen = ConnCheck.MAX_TEXT)))

    @Test fun priorityIsANumber() {
        val r = ok(ConnOps.build(n, "tell_family", mapOf("message" to text("x"), "urgency" to SkillValue.of(4L, ParamType.INTEGER, ValueSource.YOURS))))
        val w = (ConnWire.assemble(r, ConnSecrets(token = "tk_a", topic = "t")) as ConnWire.Result.Ok).wire
        assertEquals(4L, (StrictJson.parse(w.body!!)!!.value as Map<*, *>)["priority"])
        // Only 1 to 5, as a whole number argument or a constant.
        val publish = ConnFixtures.publish
        fun with(p: Any?, param: OpParam? = null) = n.copy(ops = listOf(publish.copy(spec = (publish.spec as OpSpec.NtfyPublish).copy(priority = p),
            params = if (param == null) mapOf("message" to publish.params.getValue("message")) else publish.params + ("urgency" to param))))
        assertTrue(ConnCheck.valid(with(5L)))
        assertFalse(ConnCheck.valid(with(6L)))
        assertFalse(ConnCheck.valid(with(0L)))
        assertFalse(ConnCheck.valid(with("{{urgency}}", OpParam(ParamType.CHOICE, listOf("4", "5")))))
        assertFalse(ConnCheck.valid(with("{{urgency}}", OpParam(ParamType.INTEGER, min = 0.0, max = 5.0))))
        assertFalse(ConnCheck.valid(with("high")))
    }

    @Test fun tagsAndTitle() {
        val p = ConnFixtures.publish
        fun with(s: OpSpec.NtfyPublish) = ConnCheck.valid(n.copy(ops = listOf(p.copy(spec = s))))
        val s = p.spec as OpSpec.NtfyPublish
        assertFalse(with(s.copy(tags = listOf("a", "b", "c", "d", "e", "f"))))
        assertFalse(with(s.copy(tags = listOf("Has Space"))))
        assertFalse(with(s.copy(title = "x".repeat(201))))
        assertFalse(with(s.copy(title = "two\nlines")))
        assertFalse(with(s.copy(message = "")))
        // A topic is always secret: ntfy is https only, and the topic never in connections.json.
        assertTrue(SecretPart.TOPIC in n.secrets)
        assertFalse(ConnCheck.valid(n.copy(address = ConnFixtures.url("http://192.168.1.9"), auth = ConnAuth.None)))
    }

    @Test fun effects() {
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), ConnOps.effects(n, n.ops[0]))
        val mine = ConnFixtures.ntfy(onlyMine = true)
        assertEquals(setOf(SkillEffect.WRITES_ONLINE), ConnOps.effects(mine, mine.ops[0]))
    }

    @Test fun testPublishesNothing() {
        val t = ConnOps.test(n).single()
        assertEquals(HttpMethod.GET, t.method)
        assertEquals("/v1/health", t.tail)
        assertNull(t.body)
        assertTrue(NtfyPreset.healthy(StrictJson.parse("""{"healthy":true,"role":"active"}""")!!.value))
        assertFalse(NtfyPreset.healthy(StrictJson.parse("""{"healthy":false}""")!!.value))
        assertFalse(NtfyPreset.healthy(StrictJson.parse("""{"healthy":"true"}""")!!.value))
    }
}
