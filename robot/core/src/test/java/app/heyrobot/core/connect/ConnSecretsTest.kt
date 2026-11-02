// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key file's plain form: strict, capped, versioned; each part checked; never shown by toString. */
class ConnSecretsTest {
    private val s = ConnSecrets(token = "tk_AgQdq7mVBoFD37zQVN29RhuMzNIz2", topic = "family_x7q2")

    @Test fun roundTrip() {
        val bytes = ConnSecretsCodec.encode(s)
        assertEquals(s, ConnSecretsCodec.decode(bytes))
        assertEquals(ConnSecrets(), ConnSecretsCodec.decode(ConnSecretsCodec.encode(ConnSecrets())))
        val all = ConnSecrets("a:b", "t", "https://x.example.org/hooks/abc?k=v", "h-1")
        assertEquals(all, ConnSecretsCodec.decode(ConnSecretsCodec.encode(all)))
    }

    @Test fun strict() {
        val text = String(ConnSecretsCodec.encode(s))
        for (n in 0 until text.length) assertNull("cut at $n", ConnSecretsCodec.decode(text.substring(0, n).toByteArray()))
        for (bad in listOf("""{"v":2,"token":"x"}""", """{"v":0}""", """{"token":"x"}""", """{"v":1.0}""", """{"v":1,"token":"x","pin":"1"}""",
            """{"v":1,"token":5}""", """{"v":1,"token":null}""", """{"v":1,"token":""}""", """{"v":1,"topic":"a b"}""", """{"v":1,"token":"a\nb"}""",
            """{"v":1,"address":"http://x.example.org/h"}""", """{"v":1,"token":" padded"}""", """{"v":1,"v":1}"""))
            assertNull(bad, ConnSecretsCodec.decode(bad.toByteArray()))
        assertNull(ConnSecretsCodec.decode(ByteArray(ConnSecretsCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
        assertEquals(ConnSecrets(), ConnSecretsCodec.decode("""{"v":1}""".toByteArray()))
        // A newer Robot's key file is told apart from a damaged one: C3 keeps the first and removes only the second.
        assertEquals(SecretsFile.TooNew(2), ConnSecretsCodec.read("""{"v":2,"token":"x","later":{"a":1}}""".toByteArray()))
        assertEquals(SecretsFile.Damaged, ConnSecretsCodec.read("""{"v":1,"later":1}""".toByteArray()))
        assertEquals(SecretsFile.Damaged, ConnSecretsCodec.read("""{"v":1.0}""".toByteArray()))
        assertEquals(SecretsFile.Damaged, ConnSecretsCodec.read("nope".toByteArray()))
        assertEquals(s, (ConnSecretsCodec.read(ConnSecretsCodec.encode(s)) as SecretsFile.Ok).secrets)
    }

    @Test fun neverShown() {
        assertEquals("ConnSecrets(token,topic)", s.toString())
        assertFalse(s.masks().values.any { it.contains("AgQdq7") })
        assertEquals(mapOf(SecretPart.TOKEN to "…NIz2", SecretPart.TOPIC to "…q2"), s.masks())
    }

    @Test fun eachPart() {
        assertTrue(ConnSecrets.valid(SecretPart.TOPIC, "a-B_9"))
        assertFalse(ConnSecrets.valid(SecretPart.TOPIC, "x".repeat(65)))
        assertTrue(ConnSecrets.valid(SecretPart.TOKEN, "x".repeat(4096)))
        assertFalse(ConnSecrets.valid(SecretPart.TOKEN, "x".repeat(4097)))
        assertFalse(ConnSecrets.valid(SecretPart.HEADER, "é"))
        assertFalse(ConnSecrets.valid(SecretPart.ADDRESS, "http://x.example.org/h"))
        assertFalse(ConnSecrets.valid(SecretPart.ADDRESS, "https://user@x.example.org/h"))
        assertTrue(ConnSecrets.basic("u:p"))
        assertFalse(ConnSecrets.basic(":p"))
        assertFalse(ConnSecrets.basic("up"))
    }
}
