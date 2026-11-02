// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsSettingsFile
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Robot Lists in the app's processes (lists-plan.md §4.3, D10; LS2): files/lists and files/robot/lists_settings.json, the
 * main process's vault (nothing made on disk by Settings' counting), and the settings file (written with later keys kept,
 * a newer Robot's never written over, a damaged one's lock switches off, :voice told).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListsFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theMainProcesssVaultMakesNothingWhenSettingsCounts() {
        assertEquals("main", ListsFiles.process())
        assertEquals(File(ctx.filesDir, "lists"), ListsFiles.dir(ctx))
        val v = ListsFiles.vault(ctx)
        assertTrue(v === ListsFiles.vault(ctx))
        assertEquals(ListsLoad.Empty, v.load(firstUse = false))
        ListsFiles.changed(ctx)
        assertFalse(File(ListsFiles.dir(ctx), "lists.enc").exists())
        assertFalse(ListsFiles.locked(ctx))
        // The settings live beside memory.json, never in settings.json.
        assertEquals(File(Paths.dir(ctx), "lists_settings.json"), ListsSettingsStore.file(ctx))
    }

    @Test fun theSettingsFileIsWrittenWithLaterKeysKeptAndTellsVoice() {
        assertEquals(ListsSettings(), ListsSettingsStore.current(ctx))
        writeAtomic(ListsSettingsStore.file(ctx), """{"v":1,"widgetHide":true}""")
        ListsSettingsStore.forget()
        assertTrue(ListsSettingsStore.update(ctx) { it.copy(lockScreen = true) })
        assertEquals(ListsSettings(lockScreen = true), ListsSettingsStore.current(ctx))
        val o = JSONObject(ListsSettingsStore.file(ctx).readText())
        assertEquals(true, o.getBoolean("widgetHide"))
        assertEquals(true, o.getBoolean("lockScreen"))
        val sent = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).broadcastIntents.last()
        assertEquals(Cmd.ACTION, sent.action)
        assertEquals(Cmd.LISTS, sent.getStringExtra("op"))
    }

    @Test fun aNewerRobotsFileIsNeverWrittenOverAndADamagedOneFailsClosed() {
        val newer = """{"v":2,"lockScreen":true}"""
        writeAtomic(ListsSettingsStore.file(ctx), newer)
        ListsSettingsStore.forget()
        assertTrue(ListsSettingsStore.read(ctx) is ListsSettingsFile.TooNew)
        assertEquals(ListsSettings(on = true, addLocked = false, lockScreen = false), ListsSettingsStore.current(ctx))
        assertFalse(ListsSettingsStore.update(ctx) { it.copy(on = false) })
        assertEquals(newer, ListsSettingsStore.file(ctx).readText())
        writeAtomic(ListsSettingsStore.file(ctx), "{")
        ListsSettingsStore.forget()
        assertEquals(ListsSettings(on = true, addLocked = false, lockScreen = false), ListsSettingsStore.current(ctx))
        assertTrue(ListsSettingsStore.update(ctx) { it.copy(addLocked = true) })
        assertEquals(ListsSettings(), ListsSettingsStore.current(ctx))
    }
}
