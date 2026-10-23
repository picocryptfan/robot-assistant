// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.files.FileLevel
import app.heyrobot.core.files.FileReminders
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.FilesSettingsFile
import app.heyrobot.core.files.IndexLoad
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
 * Robot Files in the app's processes (files-1.4-plan.md §2.2, D12): files/files and files/robot/files_settings.json, the
 * main process's vault (nothing made on disk until a file comes, even at the start's recovery), and the settings file
 * (written with later keys kept, a newer Robot's never written over, :voice told).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotFilesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun theMainProcesssVaultMakesNothingUntilAFileComes() {
        assertEquals("main", RobotFiles.process())
        assertEquals(File(ctx.filesDir, "files"), RobotFiles.dir(ctx))
        // RobotApp started it (its recovery ran on its worker): still nothing on disk.
        val v = RobotFiles.vault(ctx)
        assertTrue(v === RobotFiles.vault(ctx))
        assertEquals(IndexLoad.Empty, v.load())
        RobotFiles.changed(ctx)
        assertFalse(RobotFiles.dir(ctx).exists())
        assertFalse(RobotFiles.locked(ctx))
        // The settings live beside memory.json, never in settings.json.
        assertEquals(File(Paths.dir(ctx), "files_settings.json"), FilesSettingsStore.file(ctx))
    }

    @Test fun theSettingsFileIsWrittenWithLaterKeysKeptAndTellsVoice() {
        assertEquals(FilesSettings(), FilesSettingsStore.current(ctx))
        // F12, deliberately: "hidden" is travel mode's own key now; a later row stands in for the key this version doesn't know.
        writeAtomic(FilesSettingsStore.file(ctx), """{"v":1,"shelves":["Medical"]}""")
        val s = FilesSettings(bills = FileLevel.ASK, reminders = FileReminders.OFF)
        assertTrue(FilesSettingsStore.save(ctx, s))
        assertEquals(s, FilesSettingsStore.current(ctx))
        val o = JSONObject(FilesSettingsStore.file(ctx).readText())
        assertEquals("Medical", o.getJSONArray("shelves").getString(0))
        assertEquals("ASK", o.getString("bills"))
        val sent = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).broadcastIntents.last()
        assertEquals(Cmd.ACTION, sent.action)
        assertEquals(Cmd.FILES, sent.getStringExtra("op"))
    }

    @Test fun aNewerRobotsFileIsNeverWrittenOver() {
        val newer = """{"v":2,"on":false}"""
        writeAtomic(FilesSettingsStore.file(ctx), newer)
        FilesSettingsStore.forget()
        assertEquals(FilesSettingsFile.TooNew(2), FilesSettingsStore.read(ctx))
        assertFalse(FilesSettingsStore.save(ctx, FilesSettings(on = false)))
        assertEquals(newer, FilesSettingsStore.file(ctx).readText())
        // FX1, deliberately (files-1.4-review.md finding 5): a damaged one no longer gives the defaults. Nothing in it can be
        // read, so travel mode counts as on with every drawer hidden; a save without your PIN can't write it, and turning
        // travel mode off with your PIN writes a new file.
        writeAtomic(FilesSettingsStore.file(ctx), "{")
        FilesSettingsStore.forget()
        assertEquals(FilesSettings.CLOSED, FilesSettingsStore.current(ctx))
        assertFalse(FilesSettingsStore.save(ctx, FilesSettings(brief = false)))
        assertEquals("{", FilesSettingsStore.file(ctx).readText())
        assertTrue(FilesSettingsStore.save(ctx, FilesSettingsStore.current(ctx).copy(travel = false), pin = true))
        assertEquals(FilesSettings(), FilesSettingsStore.current(ctx))
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(brief = false)))
        assertEquals(FilesSettings(brief = false), FilesSettingsStore.current(ctx))
    }

    /**
     * FX1 (finding 2): every write takes the file's own lock (files_settings.lock beside it, shared by the main app and
     * :voice), and a save without your PIN never turns travel mode off or unhides a drawer, whatever copy the page held.
     */
    @Test fun aSaveWithoutThePinKeepsTravelModeAsTheFileHasIt() {
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(travel = true, hidden = setOf("Lawyer"))))
        assertEquals(File(Paths.dir(ctx), "files_settings.lock"), FilesSettingsStore.lock(ctx))
        assertTrue(FilesSettingsStore.lock(ctx).isFile)
        // The page's older copy (travel mode off) changing another row.
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(bills = FileLevel.ASK)))
        assertEquals(FilesSettings(bills = FileLevel.ASK, travel = true, hidden = setOf("Lawyer")), FilesSettingsStore.current(ctx))
        // With your PIN.
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(bills = FileLevel.ASK), pin = true))
        assertEquals(FilesSettings(bills = FileLevel.ASK), FilesSettingsStore.current(ctx))
        // A newer Robot's file: travel mode off with your PIN changes only its key.
        writeAtomic(FilesSettingsStore.file(ctx), """{"v":2,"travel":true,"hidden":["Lawyer"],"later":1}""")
        FilesSettingsStore.forget()
        val now = FilesSettingsStore.current(ctx)
        assertEquals(FilesSettings(travel = true, hidden = setOf("Lawyer")), now)
        assertTrue(FilesSettingsStore.save(ctx, now.copy(travel = false), pin = true))
        val o = JSONObject(FilesSettingsStore.file(ctx).readText())
        assertEquals(2, o.getInt("v"))
        assertFalse(o.has("travel"))
        assertEquals(1, o.getInt("later"))
    }

    /**
     * FX3: Settings › Files' changes go through update, applied to the file as it is under its lock: a restore's finish
     * that wrote other fields after the page read them stays. A renamed hidden drawer takes the old name's place.
     */
    @Test fun aChangeKeepsWhatARestoreWroteMeanwhile() {
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings()))
        // A restore's finish (from :voice) after the page read the file.
        assertTrue(FilesSettingsStore.restore(ctx, app.heyrobot.core.files.FilesSettingsCodec.encode(FilesSettings(receipts = FileLevel.ASK, cleanup = false,
            travel = true, hidden = setOf("Lawyer"))), mine = true))
        assertTrue(FilesSettingsStore.update(ctx) { it.copy(brief = false) })
        assertEquals(FilesSettings(receipts = FileLevel.ASK, cleanup = false, brief = false, travel = true, hidden = setOf("Lawyer")), FilesSettingsStore.current(ctx))
        assertTrue(FilesSettingsStore.rename(ctx, "Lawyer", "Notary"))
        assertEquals(setOf("Notary"), FilesSettingsStore.current(ctx).hidden)
        assertTrue(FilesSettingsStore.update(ctx, pin = true) { it.copy(travel = false) })
        assertEquals(FilesSettings(receipts = FileLevel.ASK, cleanup = false, brief = false, hidden = setOf("Notary")), FilesSettingsStore.current(ctx))
    }

    /**
     * FX3: while travel mode hides drawers, History holding Robot Files' answers stays out of Back up now, whole (its line
     * off with why; nothing of it gathered, so no Replace restore can delete a conversation), and the panel won't go on
     * with such a conversation: a question the router takes counts as well as a marked answer.
     */
    @Test fun historyStaysOutOfABackupWhileTravelModeHidesFilesAnswers() {
        val c = app.heyrobot.core.StoredConversation("c-1", 1, 2, listOf(
            app.heyrobot.core.ChatTurn("u", app.heyrobot.core.TurnRole.USER, "where's my lease", 1),
            app.heyrobot.core.ChatTurn("r", app.heyrobot.core.TurnRole.ASSISTANT, "In Lawyer.", 2)))
        ConversationStore.save(ctx, c)
        val settings = app.heyrobot.core.RobotSettings(saveHistory = true)
        fun historyIn() = app.heyrobot.assistant.backup.BackupSources.gather(ctx, settings, setOf(app.heyrobot.core.backup.BackupCategory.HISTORY),
            recordings = false, pictures = false).sources.count { it.category == app.heyrobot.core.backup.BackupCategory.HISTORY }
        assertEquals(1, app.heyrobot.assistant.backup.BackupSources.survey(ctx, settings)[app.heyrobot.core.backup.BackupCategory.HISTORY]!!.count)
        assertEquals(1, historyIn())
        assertFalse(app.heyrobot.assistant.PanelFiles.travelHides(ctx, c.turns))
        // The panel puts such a conversation away only when travel mode starts hiding more (PanelController.filesChanged).
        assertEquals(emptySet<String>(), app.heyrobot.assistant.PanelFiles.hidingNow(ctx))
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(travel = true, hidden = setOf("Lawyer"))))
        assertEquals(setOf("Lawyer"), app.heyrobot.assistant.PanelFiles.hidingNow(ctx))
        assertTrue(app.heyrobot.assistant.backup.BackupSources.historyHidden(ctx))
        assertEquals(-1, app.heyrobot.assistant.backup.BackupSources.survey(ctx, settings)[app.heyrobot.core.backup.BackupCategory.HISTORY]!!.count)
        assertEquals(0, historyIn())
        assertTrue(app.heyrobot.assistant.PanelFiles.travelHides(ctx, c.turns))
        // Nothing of Files' in History: it goes as before.
        ConversationStore.save(ctx, c.copy(turns = listOf(app.heyrobot.core.ChatTurn("u", app.heyrobot.core.TurnRole.USER, "Hi", 1))))
        assertFalse(app.heyrobot.assistant.backup.BackupSources.historyHidden(ctx))
        assertEquals(1, historyIn())
    }

    /**
     * FX1 (finding 3): History in the app while travel mode hides drawers: Robot Files' answers as the placeholder, read
     * again when travel mode changes (its state is in the stamp), and as stored once it is off.
     */
    @Test fun historyShowsPlaceholdersWhileTravelModeHides() {
        val c = app.heyrobot.core.StoredConversation("c1", 1, 2, listOf(
            app.heyrobot.core.ChatTurn("u", app.heyrobot.core.TurnRole.USER, "How much at the pharmacy?", 1),
            app.heyrobot.core.ChatTurn("r", app.heyrobot.core.TurnRole.ASSISTANT, "You spent 84.12 at Jean Coutu.", 2, files = true)))
        assertEquals(listOf(c), app.heyrobot.ui.TravelHistory.shown(ctx, listOf(c)))
        val off = app.heyrobot.ui.TravelHistory.stamp(ctx, 41)
        assertTrue(FilesSettingsStore.save(ctx, FilesSettings(travel = true, hidden = setOf("Lawyer"))))
        assertTrue(app.heyrobot.ui.TravelHistory.stamp(ctx, 41) != off)
        val shown = app.heyrobot.ui.TravelHistory.shown(ctx, listOf(c)).single()
        assertEquals("[An answer from Robot Files, out of sight while travel mode is on.]", shown.turns[1].text)
        assertEquals("[An answer from Robot Files, out of sight while travel mode is on.]", shown.preview)
        // FX3: the whole conversation, its title (your first question) too.
        assertEquals("[Out of sight while travel mode is on.]", shown.turns[0].text)
        assertEquals("[Out of sight while travel mode is on.]", shown.title)
        // Shown with your PIN: as stored.
        TravelReveal.start()
        try { assertEquals(listOf(c), app.heyrobot.ui.TravelHistory.shown(ctx, listOf(c))) } finally { TravelReveal.end() }
    }
}
