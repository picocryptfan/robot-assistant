// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * robot-to-robot-plan.md §6.1 and D12 (job L2): link_settings.json. The plan's defaults (off), a round trip, keys a later
 * Robot adds kept, and a file this version can't read whole failing closed: Robot to Robot off, a newer one never written
 * over, a damaged one replaced by your next change.
 */
class LinkSettingsTest {
    @Test fun theDefaultsAreThePlans() {
        val d = LinkSettings()
        assertFalse(d.on)
        assertEquals("", d.name)
        assertEquals(LinkReach.PUSH, d.reach)
        assertTrue(d.whileLocked)
        assertEquals(LocalTime.of(9, 0), d.hours.start)
        assertEquals(LocalTime.of(21, 0), d.hours.end)
        assertTrue(d.brief)
        assertEquals(LinkSettingsFile.Ok(d), LinkSettingsCodec.decode(null))
        assertEquals("link_settings.json", LinkSettingsCodec.FILE)
    }

    @Test fun roundTripsAndKeepsKeysItDoesntKnow() {
        val s = LinkSettings(on = true, name = "Matt", reach = LinkReach.WHILE_USED, whileLocked = false,
            hours = EventAlone.Hours(LocalTime.of(8, 30), LocalTime.of(22, 15)), brief = false)
        val text = LinkSettingsCodec.encode(s)
        val o = JSONObject(text)
        assertEquals(1, o.getInt("v"))
        assertEquals("08:30", o.getString("from"))
        assertEquals("22:15", o.getString("to"))
        assertEquals(LinkSettingsFile.Ok(s), LinkSettingsCodec.decode(text))
        val later = JSONObject(text).put("rate", "OFF").toString()
        val read = LinkSettingsCodec.decode(later) as LinkSettingsFile.Ok
        assertEquals(s, read.read)
        assertEquals(mapOf("rate" to "OFF"), read.extra)
        val again = JSONObject(LinkSettingsCodec.update(later) { it.copy(brief = true) }!!)
        assertEquals("OFF", again.getString("rate"))
        assertTrue(again.getBoolean("brief"))
        // 1.5 L7: the calendars free/busy reads (busyFrom); absent, every visible one.
        assertNull(s.busyCalendars)
        assertFalse(JSONObject(text).has("busyFrom"))
        val some = s.copy(busyCalendars = setOf(7L, 3L))
        val st = LinkSettingsCodec.encode(some)
        assertEquals("[3,7]", JSONObject(st).getJSONArray("busyFrom").toString())
        assertEquals(LinkSettingsFile.Ok(some), LinkSettingsCodec.decode(st))
        assertEquals(LinkSettingsFile.Ok(s.copy(busyCalendars = emptySet())), LinkSettingsCodec.decode(LinkSettingsCodec.encode(s.copy(busyCalendars = emptySet()))))
        // A file with only some keys: the rest are the defaults.
        assertEquals(LinkSettings(on = true), LinkSettingsCodec.decode("""{"v":1,"on":true}""").settings)
    }

    /** A file this version can't read whole: Robot to Robot off. */
    @Test fun failsClosed() {
        val on = """{"v":1,"on":true,"name":"Matt"}"""
        assertTrue(LinkSettingsCodec.decode(on).settings.on)
        for (bad in listOf("not json", "[]", """{"on":true}""", """{"v":"1","on":true}""", """{"v":0,"on":true}""",
            """{"v":1,"on":"yes"}""", """{"v":1,"on":true,"reach":"BLUETOOTH"}""", """{"v":1,"on":true,"whileLocked":1}""",
            """{"v":1,"on":true,"from":"9:00"}""", """{"v":1,"on":true,"from":"24:00"}""", """{"v":1,"on":true,"from":"21:00","to":"09:00"}""",
            """{"v":1,"on":true,"name":" Matt"}""", """{"v":1,"on":true,"name":"Matt\nSam"}""", """{"v":1,"on":true,"name":"${"x".repeat(41)}"}""",
            """{"v":1,"on":true,"name":"Ma‮tt"}""", """{"v":1,"on":true,"brief":null}""", """{"v":1,"on":true,"busyFrom":"3"}""",
            """{"v":1,"on":true,"busyFrom":[-1]}""", """{"v":1,"on":true,"busyFrom":["3"]}""", """{"v":1,"on":true,"busyFrom":[1.5]}""")) {
            val f = LinkSettingsCodec.decode(bad)
            assertEquals(bad, LinkSettingsFile.Damaged, f)
            assertFalse(bad, f.settings.on)
        }
        // A newer Robot's file: off here, and never written over.
        val newer = """{"v":2,"on":true,"name":"Matt"}"""
        assertEquals(LinkSettingsFile.TooNew(2), LinkSettingsCodec.decode(newer))
        assertFalse(LinkSettingsCodec.decode(newer).settings.on)
        assertNull(LinkSettingsCodec.save(newer, LinkSettings(on = true)))
        assertNull(LinkSettingsCodec.update(newer) { it.copy(on = true) })
        // A damaged one is replaced by your next change, starting from off.
        val fixed = LinkSettingsCodec.update("not json") { it.copy(brief = false) }!!
        assertEquals(LinkSettings(brief = false), LinkSettingsCodec.decode(fixed).settings)
    }

    @Test fun theNameNeverReachesALog() {
        assertFalse(LinkSettings(on = true, name = "zq9marker").toString().contains("zq9marker"))
        assertTrue(runCatching { LinkSettings(name = " padded") }.isFailure)
        assertTrue(runCatching { LinkSettings(hours = EventAlone.Hours(LocalTime.of(10, 0), LocalTime.of(9, 0))) }.isFailure)
    }
}
