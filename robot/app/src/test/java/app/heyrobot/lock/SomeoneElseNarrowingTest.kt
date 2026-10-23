// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.1, Phone lock §6: during a lock for someone else, Messages' "On the lock screen" and Memory's "Use on the lock screen"
 * are narrowed (core SomeoneElseLock) wherever a turn or the notification bridge decides by them. Every place that reads
 * the Messages lock-screen setting (`.locked` of MessageSettings, through whenLocked or ReplyPlanner) or Memory's
 * settings for a turn narrows them with core SomeoneElseLock and PhoneLockNow.someoneElse.
 */
class SomeoneElseNarrowingTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/$path").readText(), keepStrings = false)

    @Test fun messagesLockScreenSettingNarrowed() {
        val bridge = code("people/MessagesBridge.kt")
        // allowedNow, list and reply: each reads the settings once, narrowed.
        assertEquals(3, Regex("""SomeoneElseLock\.messages\(MessagesStore\.current\([a-zA-Z]+\)\.settings, app\.heyrobot\.lock\.PhoneLockNow\.someoneElse\(""").findAll(bridge).count())
        assertEquals(0, Regex("""val s = MessagesStore\.current\([a-zA-Z]+\)\.settings\s""").findAll(bridge).count())
        val panel = code("assistant/PanelMessages.kt")
        assertTrue(Regex("""val s = app\.heyrobot\.core\.lock\.SomeoneElseLock\.messages\(f\.settings, app\.heyrobot\.lock\.PhoneLockNow\.someoneElse\(ctx\)\)\s+val lang""").containsMatchIn(panel))
        assertTrue(Regex("""ReplyPlanner\.plan\(p\.thread, p\.sendText, p\.sendSource, app\.heyrobot\.core\.lock\.SomeoneElseLock\.messages\(f\.settings, app\.heyrobot\.lock\.PhoneLockNow\.someoneElse\(ctx\)\), locked""").containsMatchIn(panel))
        assertTrue("PanelMessages decides the lock screen only through whenLocked and ReplyPlanner",
            Regex("""settings\.locked|s\.locked\b""").findAll(panel).none())
        assertTrue(code("assistant/handsfree/HandsFreeMessages.kt").contains("val s = SomeoneElseLock.messages(f.settings, PhoneLockNow.someoneElse(app))"))
    }

    @Test fun memoryOnTheLockScreenNarrowed() {
        val memory = code("assistant/MemoryPanel.kt")
        assertTrue(Regex("""settingsFor: \(\) -> MemorySettings = \{\s+app\.heyrobot\.core\.lock\.SomeoneElseLock\.memory\(app\.heyrobot\.sys\.MemorySettingsStore\.current\(VoiceHub\.app\), app\.heyrobot\.lock\.PhoneLockNow\.someoneElse\(VoiceHub\.app\)\)""").containsMatchIn(memory))
        // The vault's sealing key still follows the file (narrowing it would seal memory again with the other key).
        assertTrue(code("sys/MemoryVault.kt").contains("lockScreen = { MemorySettingsStore.current(app).onLockScreen }"))
    }
}
