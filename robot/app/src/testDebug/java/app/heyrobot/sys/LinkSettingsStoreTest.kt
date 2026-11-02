// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.link.LinkReach
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.LinkSettingsFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * robot-to-robot-plan.md §6.1 and D12 (job L2): link_settings.json in files/robot/, beside files_settings.json, never in
 * settings.json. Off until set up; a change written over the file as it is, keys a later Robot added kept; a newer Robot's
 * file never written over; a reader that sees the file change reads it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkSettingsStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() {
        LinkSettingsStore.file(ctx).delete()
        LinkSettingsStore.forget()
    }

    @Test fun offUntilSetUpThenWrittenAndReadBack() {
        assertEquals(File(File(ctx.filesDir, "robot"), "link_settings.json"), LinkSettingsStore.file(ctx))
        assertFalse(LinkSettingsStore.current(ctx).on)
        assertFalse(LinkSettingsStore.file(ctx).exists())
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true, name = "Matt", reach = LinkReach.WHILE_USED) })
        assertEquals(LinkSettings(on = true, name = "Matt", reach = LinkReach.WHILE_USED), LinkSettingsStore.current(ctx))
        // Another process's change: read again once the file changed.
        val f = LinkSettingsStore.file(ctx)
        f.writeText(JSONObject(f.readText()).put("brief", false).put("later", 7).toString())
        f.setLastModified(f.lastModified() + 2_000)
        assertFalse(LinkSettingsStore.current(ctx).brief)
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(whileLocked = false) })
        val o = JSONObject(f.readText())
        assertEquals(7, o.getInt("later"))
        assertFalse(o.getBoolean("brief"))
        assertFalse(o.getBoolean("whileLocked"))
    }

    @Test fun aNewerFileIsNeverWrittenOverAndKeepsItOff() {
        val f = LinkSettingsStore.file(ctx)
        f.parentFile!!.mkdirs()
        val newer = """{"v":2,"on":true}"""
        f.writeText(newer)
        assertEquals(LinkSettingsFile.TooNew(2), LinkSettingsStore.read(ctx))
        assertFalse(LinkSettingsStore.current(ctx).on)
        assertFalse(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        assertEquals(newer, f.readText())
        // A damaged file: off, and your next change replaces it.
        f.writeText("not json")
        LinkSettingsStore.forget()
        assertFalse(LinkSettingsStore.current(ctx).on)
        assertTrue(LinkSettingsStore.update(ctx) { it.copy(on = true) })
        assertTrue(LinkSettingsStore.current(ctx).on)
    }
}
