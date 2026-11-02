// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** lists_settings.json (lists-plan.md §7.5, D10): the defaults, unknown keys kept, a newer file never written over, the lock switches failing closed. */
class ListsSettingsTest {
    @Test fun noFileIsTheDefaults() {
        val f = ListsSettingsCodec.decode(null)
        assertEquals(ListsSettingsFile.Ok(ListsSettings()), f)
        // §7.5: Lists on, Add to lists while locked on (Q3), Hear and tick on the lock screen off.
        assertEquals(ListsSettings(on = true, addLocked = true, lockScreen = false), f.settings)
        assertTrue(f.settings.lockKey)
        assertEquals("lists_settings.json", ListsSettingsCodec.FILE)
    }

    @Test fun roundTripAndMissingKeysAtTheirDefaults() {
        for (on in listOf(true, false)) for (add in listOf(true, false)) for (ls in listOf(true, false)) {
            val s = ListsSettings(on, add, ls)
            assertEquals(ListsSettingsFile.Ok(s), ListsSettingsCodec.decode(ListsSettingsCodec.encode(s)))
            assertEquals(add || ls, s.lockKey)
        }
        assertEquals(ListsSettings(), ListsSettingsCodec.decode("{\"v\":1}").settings)
        assertEquals(ListsSettings(on = false), ListsSettingsCodec.decode("{\"v\":1,\"on\":false}").settings)
    }

    @Test fun unknownKeysAreKeptOnEveryWrite() {
        val raw = "{\"v\":1,\"on\":true,\"addLocked\":true,\"lockScreen\":false,\"widgetHide\":true,\"later\":{\"a\":[1,2]}}"
        val f = ListsSettingsCodec.decode(raw) as ListsSettingsFile.Ok
        assertEquals(setOf("widgetHide", "later"), f.extra.keys)
        val out = JSONObject(ListsSettingsCodec.update(raw) { it.copy(lockScreen = true) }!!)
        assertEquals(true, out.getBoolean("widgetHide"))
        assertEquals(2, out.getJSONObject("later").getJSONArray("a").length())
        assertEquals(true, out.getBoolean("lockScreen"))
        assertEquals(1, out.getInt("v"))
    }

    @Test fun aNewerFileIsNeverWrittenOverAndItsLockSwitchesReadOff() {
        val raw = "{\"v\":2,\"on\":false,\"addLocked\":true,\"lockScreen\":true}"
        val f = ListsSettingsCodec.decode(raw)
        assertEquals(ListsSettingsFile.TooNew(2, ListsSettings(on = false, addLocked = false, lockScreen = false)), f)
        assertNull(ListsSettingsCodec.update(raw) { it.copy(on = true) })
    }

    @Test fun aDamagedFileFailsClosedAndASaveWritesANewOne() {
        for (raw in listOf("not json", "{\"on\":true}", "{\"v\":\"1\"}", "{\"v\":0}", "{\"v\":1,\"lockScreen\":\"yes\"}", "{\"v\":1,\"addLocked\":1}",
            "{\"v\":1,\"on\":null}", "[]")) {
            val f = ListsSettingsCodec.decode(raw)
            assertTrue(raw, f is ListsSettingsFile.Damaged)
            assertEquals(raw, false, f.settings.addLocked)
            assertEquals(raw, false, f.settings.lockScreen)
            assertEquals(raw, false, f.settings.lockKey)
        }
        // "Lists" itself as the file says, when it says it plainly.
        assertEquals(false, ListsSettingsCodec.decode("{\"v\":1,\"on\":false,\"lockScreen\":\"yes\"}").settings.on)
        assertEquals(true, ListsSettingsCodec.decode("not json").settings.on)
        // A save over a damaged file writes the page's values as a new file.
        val out = ListsSettingsCodec.update("{\"v\":1,\"lockScreen\":\"yes\"}") { it.copy(addLocked = true) }!!
        assertEquals(ListsSettingsFile.Ok(ListsSettings(on = true, addLocked = true, lockScreen = false)), ListsSettingsCodec.decode(out))
    }
}
