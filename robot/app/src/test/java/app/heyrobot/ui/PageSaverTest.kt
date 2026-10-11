package app.heyrobot.ui

import androidx.compose.runtime.saveable.SaverScope
import app.heyrobot.sys.AppPages
import app.heyrobot.ui.settings.AppRoutes
import app.heyrobot.ui.settings.PageStack
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.settings.parentOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The open pages (a note being edited, a conversation, setup, every Settings page) survive rotation and the process being
 * killed, Back goes up one page, and the links from outside the app open what they name.
 */
class PageSaverTest {
    private val scope = SaverScope { true }

    private fun save(p: Page?): String = with(Page.Saver) { scope.save(p) }!!

    private fun save(st: PageStack): String = with(PageStack.Saver) { scope.save(st) }!!

    /** Every page the app has, Settings' routes included. */
    private val every: List<Page> = listOf(
        Page.Setup(), Page.Setup(true), Page.Conversation("abc-123"), Page.NoteEdit("4f1c-uuid"), Page.NoteEdit(null), Page.WakePhrases,
        Page.WakeTest, Page.VoiceLog, Page.AdbFixes, Page.People, Page.PersonEdit("p1"), Page.PersonEdit(null), Page.Safety, Page.Licences,
        Page.Licence("onnxruntime"), Page.Licence("litert-lm", "licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt"), Page.Licence(ROBOT_LICENCE),
        Page.Transcripts, Page.Transcript("20261011-1402-7f3a"), Page.Transcript("20261011-1402-7f3a", 12), Page.ConversationSettings,
        Page.Helpers, Page.Helper("claude"), Page.Helper("h0a9z7k"), Page.AddHelper,
    ) + SETTINGS_ROUTES.map { Page.Settings(it) }

    @Test fun everyPageRoundTrips() {
        for (p in every + listOf<Page?>(null)) assertEquals(p, Page.Saver.restore(save(p)))
    }

    @Test fun aSettingsPageIsSavedByItsRoute() {
        assertEquals("settings:display", save(Page.Settings("display")))
        for (p in listOf(Page.Settings("display"), Page.Settings("voice/wake"))) assertEquals(p, Page.Saver.restore(save(p)))
    }

    @Test fun anOpenNoteIsSavedByItsId() {
        assertEquals("note:", save(Page.NoteEdit(null)))
        assertEquals("note:abc", save(Page.NoteEdit("abc")))
        assertEquals(Page.NoteEdit("abc"), Page.Saver.restore("note:abc"))
    }

    @Test fun theWholeStackRoundTrips() {
        val st = PageStack(every)
        assertEquals(every, PageStack.Saver.restore(save(st))!!.pages)
        assertEquals(emptyList<Page>(), PageStack.Saver.restore(save(PageStack()))!!.pages)
        // What 0.6.0 saved, one page, reads as a stack of one.
        assertEquals(listOf(Page.Safety), PageStack.Saver.restore("safety")!!.pages)
    }

    @Test fun backGoesUpOnePage() {
        val st = PageStack()
        st.open(Page.Settings(SettingsRoutes.ADVANCED))
        st.open(Page.AdbFixes)
        st.open(Page.Settings(SettingsRoutes.adbFix("recognizer")))
        st.back()
        assertEquals(Page.AdbFixes, st.top)
        st.back()
        assertEquals(Page.Settings(SettingsRoutes.ADVANCED), st.top)
        st.back()
        assertNull(st.top)
        // From Assistant setup's Fix, Back goes back to setup.
        st.open(Page.Setup())
        st.open(Page.AdbFixes)
        st.back()
        assertEquals(Page.Setup(), st.top)
    }

    @Test fun openingAPageAlreadyOpenGoesBackToIt() {
        val st = PageStack(listOf(Page.Helpers, Page.Helper("h1")))
        st.open(Page.Helpers)
        assertEquals(listOf(Page.Helpers), st.pages)
        st.open(null)
        assertEquals(emptyList<Page>(), st.pages)
    }

    @Test fun aNewPageReplacesTheOneShowing() {
        val st = PageStack(listOf(Page.Helpers, Page.AddHelper))
        st.replace(Page.Helper("h1"))
        assertEquals(listOf(Page.Helpers, Page.Helper("h1")), st.pages)
        st.back()
        assertEquals(Page.Helpers, st.top)
    }

    @Test fun aPageOpenedAloneGoesBackToItsParent() {
        val cases = mapOf<Page, Page?>(
            Page.Transcript("20261011-1402-7f3a", 3) to Page.Transcripts,
            Page.Helper("claude") to Page.Helpers,
            Page.AddHelper to Page.Helpers,
            Page.PersonEdit("p1") to Page.People,
            Page.WakeTest to Page.WakePhrases,
            Page.Licence("onnxruntime") to Page.Licences,
            Page.Licence("litert-lm", "notice.txt") to Page.Licence("litert-lm"),
            Page.Licence(ROBOT_LICENCE, "COPYING") to Page.Licences,
            Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS) to Page.People,
            Page.Settings(SettingsRoutes.adbFix("recognizer")) to Page.AdbFixes,
            Page.Settings("voice/barge-in") to Page.Settings("voice"),
            Page.Settings("display") to null,
            Page.Transcripts to null,
            Page.Helpers to null,
            Page.ConversationSettings to null,
            Page.Setup(true) to null,
        )
        for ((p, parent) in cases) {
            assertEquals(p.toString(), parent, parentOf(p))
            val st = PageStack(listOf(p))
            st.back()
            assertEquals(p.toString(), parent, st.top)
        }
    }

    @Test fun theLinksFromOutsideOpenWhatTheyName() {
        assertEquals(AppRoutes.Target(tab = Tab.NOTES, notesIcon = true), AppRoutes.target(AppPages.NOTES))
        assertEquals(AppRoutes.Target(page = Page.Setup(people = true)), AppRoutes.target(AppPages.PERMISSIONS))
        assertEquals(AppRoutes.Target(tab = Tab.MODEL), AppRoutes.target(AppPages.MODEL))
        assertEquals(AppRoutes.Target(page = Page.Transcripts), AppRoutes.target(AppPages.TRANSCRIPTS))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a")), AppRoutes.target("transcripts:20261011-1402-7f3a"))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a", 12)), AppRoutes.target("transcripts:20261011-1402-7f3a:12"))
        assertEquals(AppRoutes.Target(page = Page.ConversationSettings), AppRoutes.target(AppPages.CONVERSATION_SETTINGS))
        assertEquals(AppRoutes.Target(page = Page.Helpers), AppRoutes.target(AppPages.HELPERS))
        assertNull(AppRoutes.target("settings"))
    }

    companion object {
        /** Every route ui/settings/SettingsNav.kt opens. */
        val SETTINGS_ROUTES = listOf(SettingsRoutes.VOICE, SettingsRoutes.BARGE_IN, SettingsRoutes.ACTIONS, SettingsRoutes.PEOPLE_NOTES_CALENDAR,
            SettingsRoutes.WEATHER, SettingsRoutes.HISTORY, SettingsRoutes.DISPLAY, SettingsRoutes.ADVANCED, SettingsRoutes.ABOUT,
            SettingsRoutes.NOTIFICATION_ACCESS, SettingsRoutes.adbFix("recognizer"))
    }
}
