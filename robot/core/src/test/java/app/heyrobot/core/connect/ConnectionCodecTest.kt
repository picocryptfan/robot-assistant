// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** connections.json v1 (plan §5.2): round trips, strict keys and types, versions, caps, a connection never read in part. */
class ConnectionCodecTest {
    private val all = listOf(ConnFixtures.homeAssistant(), ConnFixtures.ntfy(), ConnFixtures.webhook(), ConnFixtures.discord(), ConnFixtures.routing(),
        ConnFixtures.handOff(), ConnFixtures.homeAssistant("c-ha0002", ConnFixtures.lockDoor, ConnFixtures.allLightsOff, ConnFixtures.blinds,
            ConnFixtures.garage, ConnFixtures.forecast, ConnFixtures.readTemp, ConnFixtures.homeOffLock))

    @Test fun everyFixtureIsValid() {
        for (c in all) assertEquals(c.toString(), emptyList<ConnProblem>(), ConnCheck.problems(c))
    }

    @Test fun roundTrip() {
        val text = ConnectionCodec.encode(all)
        val back = ConnectionCodec.decode(text)
        assertEquals(ConnFile.Ok(all), back)
        assertEquals(text, ConnectionCodec.encode((back as ConnFile.Ok).connections))
        // Strict JSON, and the version first.
        assertTrue(StrictJson.parse(text) != null)
        assertTrue(text.startsWith("{\"v\":1,\"connections\":["))
    }

    @Test fun anEmptyFileRoundTrips() {
        assertEquals("{\"v\":1,\"connections\":[]}", ConnectionCodec.encode(emptyList()))
        assertEquals(ConnFile.Ok(emptyList()), ConnectionCodec.decode("{\"v\":1,\"connections\":[]}"))
    }

    @Test fun typesSurviveInTemplates() {
        val text = ConnectionCodec.encode(listOf(ConnFixtures.webhook()))
        val body = (((ConnectionCodec.decode(text) as ConnFile.Ok).connections[0].ops[0].spec as OpSpec.Webhook).body as OpBody.Json).tree as Map<*, *>
        assertEquals(listOf(1L, 2.5, true, null), body["fixed"])
        // A limit whole in the file reads back as the same Double.
        val p = (ConnectionCodec.decode(text) as ConnFile.Ok).connections[0].ops[0].params.getValue("minutes")
        assertEquals(600.0, p.max!!, 0.0)
    }

    @Test fun noSecretValueIsEverWritten() {
        val text = ConnectionCodec.encode(all)
        // The secret parts are named, never held: a secret address keeps only its origin.
        assertTrue(text.contains("\"secret\":[\"address\"]"))
        assertTrue(text.contains("\"address\":\"https://discord.example.com\""))
        assertTrue(text.contains("\"secret\":[\"token\",\"topic\"]"))
        // The ntfy notification's title has its own key: an operation's "title" is yours.
        assertTrue(text.contains("\"title\":\"Tell the family\"") && text.contains("\"heading\":\"From Robot\""))
    }

    @Test fun versions() {
        assertEquals(ConnFile.TooNew(2), ConnectionCodec.decode("{\"v\":2,\"connections\":[],\"later\":1}"))
        assertEquals(ConnFile.TooNew(99), ConnectionCodec.decode("{\"v\":99}"))
        for (bad in listOf("{\"v\":0,\"connections\":[]}", "{\"v\":-1,\"connections\":[]}", "{\"connections\":[]}", "{\"v\":1.0,\"connections\":[]}",
            "{\"v\":\"1\",\"connections\":[]}", "{\"v\":1}", "{\"v\":1,\"connections\":{}}", "{\"v\":1,\"connections\":[],\"more\":true}", "[]", "null", "",
            "{\"v\":1,\"connections\":[]} x", "{\"v\":1,\"v\":1,\"connections\":[]}"))
            assertEquals(bad, ConnFile.Damaged, ConnectionCodec.decode(bad))
    }

    @Test fun everyTruncationIsDamaged() {
        val text = ConnectionCodec.encode(all)
        for (n in 0 until text.length) assertEquals("cut at $n", ConnFile.Damaged, ConnectionCodec.decode(text.substring(0, n)))
    }

    @Test fun theCapIsInBytes() {
        val c = ConnFixtures.homeAssistant()
        val base = ConnectionCodec.encode(listOf(c))
        // Pad the name field's neighbour with spaces (white space is allowed in JSON) up to exactly the cap, then one over.
        val pad = ConnectionCodec.MAX_BYTES - base.toByteArray().size
        val atCap = base.dropLast(1) + " ".repeat(pad) + "}"
        assertEquals(ConnectionCodec.MAX_BYTES, atCap.toByteArray().size)
        assertTrue(ConnectionCodec.decode(atCap) is ConnFile.Ok)
        assertEquals(ConnFile.Damaged, ConnectionCodec.decode(base.dropLast(1) + " ".repeat(pad + 1) + "}"))
        // Measured in bytes: two-byte characters count twice.
        val renamed = ConnectionCodec.encode(listOf(c.copy(name = "Maison éé")))
        val pad2 = ConnectionCodec.MAX_BYTES - renamed.toByteArray().size
        assertEquals(ConnFile.Damaged, ConnectionCodec.decode(renamed.dropLast(1) + " ".repeat(pad2 + 1) + "}"))
        assertEquals(ConnFile.Damaged, ConnectionCodec.decode(ByteArray(ConnectionCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
    }

    @Test fun notUtf8IsDamaged() {
        val bytes = ConnectionCodec.encode(emptyList()).toByteArray().toMutableList()
        bytes.add(bytes.size - 1, 0xC3.toByte())
        assertEquals(ConnFile.Damaged, ConnectionCodec.decode(bytes.toByteArray()))
    }

    /** One bad connection is dropped whole and counted; the others read. */
    @Test fun aBadConnectionIsDroppedWhole() {
        val good = ConnectionCodec.toJson(ConnFixtures.ntfy())
        fun file(vararg cs: Map<String, Any?>) = ConnJson.write(linkedMapOf("v" to 1L, "connections" to cs.toList()))
        val cases = listOf<(MutableMap<String, Any?>) -> Unit>(
            { it["later"] = 1L },                                    // an unknown key
            { it["name"] = 5L },                                     // a wrong type
            { it["name"] = "" },                                     // out of its limits
            { it["name"] = "x".repeat(41) },
            { it["kind"] = "mcp" },                                  // a kind this Robot doesn't know
            { it.remove("answers") },                                // a key missing
            { it["secret"] = listOf("token") },                      // the topic is always secret
            { it["secret"] = listOf("token", "topic", "address") },  // and nothing else is
            { it["address"] = "http://ntfy.example.org" },           // a secret over http (D2)
            { it["address"] = "https://user@ntfy.example.org" },
            { it["per_minute"] = 61L },
            { it["per_minute"] = 0L },
            { it["spoken_yes"] = "true" },
            { it["auth"] = "digest" },
            { it["answers"] = mapOf("https://ntfy.example.org/path" to "allow") },
            { it["answers"] = mapOf("https://ntfy.example.org" to "always") },
            { it["tested"] = mapOf("at" to 1L, "by" to "me") },
            { it["routing"] = "osrm" },
            { it["package"] = "org.example" },
            { it["id"] = "c-UPPER1" },
        )
        for ((i, change) in cases.withIndex()) {
            val bad = LinkedHashMap(good).also(change)
            val r = ConnectionCodec.decode(file(ConnectionCodec.toJson(ConnFixtures.homeAssistant()), bad))
            assertEquals("case $i", ConnFile.Ok(listOf(ConnFixtures.homeAssistant()), 1), r)
        }
    }

    /** C10: a hand-off restored from a backup that isn't yours keeps its app and what it asks, without a signer. */
    @Test fun aHandOffNotPinnedHereRoundTrips() {
        val c = ConnFixtures.signal().let { it.copy(handOff = it.handOff!!.copy(signer = null)) }
        val text = ConnectionCodec.encode(listOf(c))
        org.junit.Assert.assertFalse(text.contains("\"signer\""))
        assertEquals(ConnFile.Ok(listOf(c)), ConnectionCodec.decode(text))
        // A signer without its app is never read.
        val bad = LinkedHashMap(ConnectionCodec.toJson(ConnFixtures.ntfy())).apply { put("signer", "a".repeat(64)) }
        assertEquals(ConnFile.Ok(emptyList(), 1), ConnectionCodec.decode(ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(bad)))))
    }

    @Test fun aBadOperationDropsItsConnection() {
        fun opChanged(change: (MutableMap<String, Any?>) -> Unit): ConnFile {
            val c = LinkedHashMap(ConnectionCodec.toJson(ConnFixtures.webhook()))
            @Suppress("UNCHECKED_CAST")
            val op = LinkedHashMap((c["ops"] as List<Map<String, Any?>>)[0]).also(change)
            c["ops"] = listOf(op)
            return ConnectionCodec.decode(ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(c))))
        }
        assertEquals(ConnFile.Ok(listOf(ConnFixtures.webhook())), opChanged { })
        val cases = listOf<(MutableMap<String, Any?>) -> Unit>(
            { it["do"] = "mcp_call" },
            { it["method"] = "TRACE" },
            { it["extra"] = 1L },
            { it["headers"] = listOf(listOf("Authorization", "Bearer x")) },
            { it["headers"] = listOf(listOf("X-Api-Key", "abc")) },
            { it["headers"] = listOf(listOf("X-HTTP-Method-Override", "DELETE")) },
            { it["headers"] = listOf(listOf("X-Note", "a\r\nX-Other: b")) },
            { it["headers"] = listOf(listOf("X-Note")) },
            { it["path"] = "/webhook/../admin" },
            { it["path"] = "/webhook/%2e%2e/admin" },
            { it["path"] = "/a b" },
            { it["path"] = "/{{nothing}}" },               // a template naming no argument
            { it["path"] = "/webhook/trips" },             // an argument no template uses
            { it["marks"] = listOf("made_up") },           // an unknown mark is still refused
            { it["outputs"] = mapOf("much_too_long_name_x" to mapOf("pointer" to "/a", "type" to "text")) },
            { it["outputs"] = mapOf("a" to mapOf("pointer" to "a", "type" to "text")) },
            { it["outputs"] = mapOf("a" to mapOf("pointer" to "/a", "type" to "person")) },
            { it["check"] = mapOf("pointer" to "/ok", "equals" to listOf(1L)) },
            { it["body"] = mapOf("json" to mapOf("k" to "{{minutes}}"), "text" to "x") },
            { it["query"] = listOf(listOf("bad name", "x")) },
            { it["title"] = "Line\nbreak" },
            { it["id"] = "Log" },
        )
        for ((i, change) in cases.withIndex()) assertEquals("case $i", ConnFile.Ok(emptyList(), 1), opChanged(change))
        // A mark the app knows is read: deletes is one of the four.
        val marked = opChanged { it["marks"] = listOf("deletes") }
        assertTrue(marked.toString(), marked is ConnFile.Ok && marked.dropped == 0 && marked.connections.size == 1)
    }

    @Test fun duplicatesAndTheCountAreDropped() {
        val c = ConnFixtures.ntfy()
        val twice = ConnJson.write(linkedMapOf("v" to 1L, "connections" to listOf(ConnectionCodec.toJson(c), ConnectionCodec.toJson(c.copy(name = "Again")))))
        assertEquals(ConnFile.Ok(listOf(c), 1), ConnectionCodec.decode(twice))
        val many = (0 until ConnCheck.MAX_CONNECTIONS + 2).map { ConnectionCodec.toJson(ConnFixtures.ntfy("c-n" + it.toString().padStart(5, '0'))) }
        val r = ConnectionCodec.decode(ConnJson.write(linkedMapOf("v" to 1L, "connections" to many))) as ConnFile.Ok
        assertEquals(ConnCheck.MAX_CONNECTIONS, r.connections.size)
        assertEquals(2, r.dropped)
    }

    @Test fun paramsKeepTheirShape() {
        val p = ConnFixtures.n8nPost.params
        assertEquals(listOf("kind", "minutes", "note"), p.keys.toList())
        assertEquals(ParamType.CHOICE, p.getValue("kind").type)
    }
}
