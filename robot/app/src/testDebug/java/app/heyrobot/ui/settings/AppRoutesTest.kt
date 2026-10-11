package app.heyrobot.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.HandOffApps
import app.heyrobot.ui.Page
import app.heyrobot.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The intents that open the app on a page, as the panel, the transcript and summary screens, the tile, the Robot Notes
 * icon and the updater's notifications send them, each reaching the page it names (the redesign moved them out of MainActivity, unchanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppRoutesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun main() = Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity")

    private fun open(i: Intent): AppRoutes.Target? = AppRoutes.of(i)?.let(AppRoutes::target)

    @Test fun thePanelsLinks() {
        // A chat-only model's "Open Model", and a card's "Allow" (PanelController, through HandOffApps.robotPage).
        assertEquals(AppRoutes.Target(tab = Tab.MODEL), open(HandOffApps.robotPage(ctx, AppPages.MODEL)))
        assertEquals(AppRoutes.Target(page = Page.Setup(people = true)), open(HandOffApps.robotPage(ctx, AppPages.PERMISSIONS)))
        assertEquals(AppRoutes.Target(tab = Tab.NOTES, notesIcon = true), open(HandOffApps.robotPage(ctx, AppPages.NOTES)))
    }

    @Test fun conversationModesLinks() {
        // The transcript screen's settings and Open, the summary screen's Helpers and its [14:03] link, the tile's long press.
        assertEquals(AppRoutes.Target(page = Page.ConversationSettings), open(main().putExtra(AppPages.EXTRA, AppPages.CONVERSATION_SETTINGS)))
        assertEquals(AppRoutes.Target(page = Page.Transcripts), open(main().putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS)))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a")),
            open(main().putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS).putExtra(AppPages.EXTRA_TRANSCRIPT, "20261011-1402-7f3a")))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a", 7)),
            open(main().putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS).putExtra(AppPages.EXTRA_TRANSCRIPT, "20261011-1402-7f3a").putExtra(AppPages.EXTRA_LINE, 7)))
        assertEquals(AppRoutes.Target(page = Page.Helpers), open(main().putExtra(AppPages.EXTRA, AppPages.HELPERS)))
        assertEquals(AppRoutes.Target(page = Page.ConversationSettings), open(Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")))
    }

    @Test fun theUpdatersNotifications() {
        // "Robot … is available", the download's and the install's notices (update/UpdateNotices): Settings › About › Updates.
        assertEquals(AppRoutes.Target(page = Page.Settings(SettingsRoutes.UPDATES)),
            open(main().putExtra(AppPages.EXTRA, AppPages.UPDATES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
    }

    @Test fun theNotesIconAndNothingElse() {
        assertEquals(AppRoutes.Target(tab = Tab.NOTES, notesIcon = true), open(Intent().setComponent(ComponentName(ctx.packageName, AppPages.NOTES_ALIAS))))
        // A transcript id that isn't one opens the library; an unknown page, nothing.
        assertEquals(AppRoutes.Target(page = Page.Transcripts),
            open(main().putExtra(AppPages.EXTRA, AppPages.TRANSCRIPTS).putExtra(AppPages.EXTRA_TRANSCRIPT, "../../etc")))
        assertNull(AppRoutes.of(main().putExtra(AppPages.EXTRA, "settings:display")))
        assertNull(AppRoutes.of(main()))
        assertNull(AppRoutes.of(null))
    }
}
