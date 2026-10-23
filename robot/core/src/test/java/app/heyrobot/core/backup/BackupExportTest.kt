// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.messages.MessagesFile
import app.heyrobot.core.termux.TermuxSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design §4.1, §6.2: what Back up now writes for messages.json, termux.json and Memory. */
class BackupExportTest {
    @Test fun messagesWithoutPins() {
        val f = BackupKit.messages()
        assertTrue(f.pins.isNotEmpty())
        val out = MessagesFile.fromJson(BackupExport.messages(f.toJson()))
        assertTrue(out.pins.isEmpty() && out.firstUse.isEmpty())
        assertEquals(f.settings, out.settings)
        assertEquals(f.noCard, out.noCard)
        assertFalse(BackupExport.messages(f.toJson()).contains("AAAA"))
    }

    @Test fun termuxWithoutTrust() {
        val t = BackupKit.termux()
        val out = TermuxSettings.fromJson(JSONObject(BackupExport.termux(t.toJson().toString())))
        assertNull(out.trustedSigner)
        assertEquals(0, out.trustedVersionCode)
        assertFalse(out.enabled)
        assertEquals(t.scripts, out.scripts)
        // A missing file gives the defaults, untrusted.
        assertNull(TermuxSettings.fromJson(JSONObject(BackupExport.termux(null))).trustedSigner)
    }

    @Test fun memoryCodesApart() {
        val plain = MemoryItem("m_1", "Marie likes jasmine tea", createdAt = 1L)
        val private = MemoryItem("m_2", "My locker is the blue one", secret = true, createdAt = 1L)
        val code = MemoryItem("m_3", "My bank PIN is 4321", secret = true, createdAt = 1L)
        val split = BackupExport.memory(MemoryFile(items = listOf(plain, private, code)))
        assertEquals(listOf("m_1", "m_2"), split.memory.items.map { it.id })
        assertEquals(listOf("m_3"), split.codes.items.map { it.id })
        assertFalse(split.toString().contains("4321"))
    }

    @Test fun theVoiceLogLine() {
        val bits = BackupExport.bits(setOf(BackupCategory.SETTINGS, BackupCategory.NOTES))
        assertEquals(240, VoiceEvent.BACKUP.id)
        val line = VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.BACKUP, BackupCode.DONE_CHECKED.id, bits, 12))
        assertEquals("Backup: saved and checked · settings,notes · 12 MB", line)
    }
}
