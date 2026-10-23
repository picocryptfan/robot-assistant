// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.runtime.saveable.SaverScope
import app.heyrobot.core.BrainModel
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.feedback.FeedbackEntry
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.sys.AppPages
import app.heyrobot.ui.model.ModelRoutes
import app.heyrobot.ui.settings.AppRoutes
import app.heyrobot.ui.settings.FeedbackArgs
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
        Page.Helpers, Page.Helper("claude"), Page.Helper("h0a9z7k"), Page.AddHelper, Page.Guide,
    ) + SETTINGS_ROUTES.map { Page.Settings(it) }

    @Test fun everyPageRoundTrips() {
        for (p in every + listOf<Page?>(null)) assertEquals(p, Page.Saver.restore(save(p)))
    }

    @Test fun aSettingsPageIsSavedByItsRoute() {
        assertEquals("settings:display", save(Page.Settings("display")))
        // Settings › About › Updates, which the updater's notifications open, too.
        assertEquals("settings:updates", save(Page.Settings(SettingsRoutes.UPDATES)))
        for (p in listOf(Page.Settings("display"), Page.Settings("voice/wake"), Page.Settings("updates"))) assertEquals(p, Page.Saver.restore(save(p)))
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
            // The updater's notifications open Updates alone: Back goes to About, where its row is.
            Page.Settings(SettingsRoutes.UPDATES) to Page.Settings(SettingsRoutes.ABOUT),
            Page.Settings("voice/barge-in") to Page.Settings("voice"),
            // 1.1: Support Robot sits under About.
            Page.Settings(SettingsRoutes.SUPPORT) to Page.Settings(SettingsRoutes.ABOUT),
            // 0.7: Send feedback goes back to About when it was opened there, else to the tabs (Settings › Model, the panel).
            Page.Settings(SettingsRoutes.feedback(FeedbackEntry.ABOUT)) to Page.Settings(SettingsRoutes.ABOUT),
            Page.Settings(SettingsRoutes.feedback(FeedbackEntry.MODEL_PROBLEM, FeedbackKind.BUG, 1_760_000_000_000L)) to null,
            Page.Settings(SettingsRoutes.feedback(FeedbackEntry.VOICE, FeedbackKind.IDEA)) to null,
            Page.Settings("display") to null,
            // A model's page sits under Settings › Model (1.4: a page of Settings, the tab Files took), which sits under the tabs.
            Page.Settings(ModelRoutes.model(BrainModel.SMARTER)) to Page.Settings(SettingsRoutes.MODEL),
            Page.Settings(ModelRoutes.voice(VoiceBundle.STT)) to Page.Settings(SettingsRoutes.MODEL),
            Page.Settings(SettingsRoutes.MODEL) to null,
            // 1.4: scanning opens from the Files tab's Scan button, and Back goes there.
            Page.Settings(SettingsRoutes.FILES_SCAN) to null,
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
        assertEquals(AppRoutes.Target(tab = Tab.SETTINGS, page = Page.Settings(SettingsRoutes.MODEL)), AppRoutes.target(AppPages.MODEL))
        // 1.4: a filing notification opens the Files tab; the "Robot Files" icon too, Back then leaving the app.
        assertEquals(AppRoutes.Target(tab = Tab.FILES), AppRoutes.target(AppPages.FILES))
        assertEquals(AppRoutes.Target(tab = Tab.FILES, filesIcon = true), AppRoutes.target(AppPages.FILES_ICON))
        assertEquals(AppRoutes.Target(page = Page.Transcripts), AppRoutes.target(AppPages.TRANSCRIPTS))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a")), AppRoutes.target("transcripts:20261011-1402-7f3a"))
        assertEquals(AppRoutes.Target(page = Page.Transcript("20261011-1402-7f3a", 12)), AppRoutes.target("transcripts:20261011-1402-7f3a:12"))
        assertEquals(AppRoutes.Target(page = Page.ConversationSettings), AppRoutes.target(AppPages.CONVERSATION_SETTINGS))
        assertEquals(AppRoutes.Target(page = Page.Helpers), AppRoutes.target(AppPages.HELPERS))
        assertEquals(AppRoutes.Target(page = Page.Settings(SettingsRoutes.UPDATES)), AppRoutes.target(AppPages.UPDATES))
        // 0.7.0: the panel's Settings button opens the Settings tab; anything else unknown opens nothing.
        assertEquals(AppRoutes.Target(tab = Tab.SETTINGS), AppRoutes.target(AppPages.SETTINGS))
        assertNull(AppRoutes.target("settings:voice"))
        assertNull(AppRoutes.target("nonsense"))
        // 0.7: a feedback phrase in the panel opens Send feedback with its kind (or none: a bug, as from About).
        assertEquals(AppRoutes.Target(page = Page.Settings("about/feedback?e=voice&k=idea")), AppRoutes.target("feedback:idea"))
        assertEquals(AppRoutes.Target(page = Page.Settings("about/feedback?e=voice")), AppRoutes.target(AppPages.FEEDBACK))
        assertEquals(AppRoutes.Target(page = Page.Settings("about/feedback?e=voice")), AppRoutes.target("feedback:nonsense"))
    }

    /** 0.7: Send feedback's route says where it was opened from, its kind and a problem's time, and reads back the same. */
    @Test fun sendFeedbacksRouteHoldsItsArguments() {
        assertEquals("about/feedback?e=about", SettingsRoutes.feedback(FeedbackEntry.ABOUT))
        assertEquals("about/feedback?e=problem&k=bug&p=1760000000000", SettingsRoutes.feedback(FeedbackEntry.MODEL_PROBLEM, FeedbackKind.BUG, 1_760_000_000_000L))
        for (e in FeedbackEntry.entries) for (k in FeedbackKind.entries + listOf(null)) for (p in listOf(null, 1_760_000_000_000L)) {
            assertEquals(FeedbackArgs(e, k, p), FeedbackArgs.of(SettingsRoutes.feedback(e, k, p)))
        }
        // Damaged or unknown parts read as About, a bug, no problem.
        assertEquals(FeedbackArgs(FeedbackEntry.ABOUT, null, null), FeedbackArgs.of("about/feedback?e=x&k=y&p=z"))
        assertEquals(FeedbackKind.BUG, FeedbackArgs.of("about/feedback").startKind)
    }

    companion object {
        /** Every route ui/settings/SettingsNav.kt opens. */
        val SETTINGS_ROUTES = listOf(SettingsRoutes.VOICE, SettingsRoutes.BARGE_IN, SettingsRoutes.ACTIONS, SettingsRoutes.PEOPLE_NOTES_CALENDAR,
            SettingsRoutes.WEATHER, SettingsRoutes.HISTORY, SettingsRoutes.DISPLAY, SettingsRoutes.ADVANCED, SettingsRoutes.ABOUT,
            SettingsRoutes.UPDATES, SettingsRoutes.NOTIFICATION_ACCESS, SettingsRoutes.adbFix("recognizer"), SettingsRoutes.SUPPORT,
            // 0.7: Send feedback, from About, a model problem and the panel.
            SettingsRoutes.feedback(FeedbackEntry.ABOUT), SettingsRoutes.feedback(FeedbackEntry.MODEL_PROBLEM, FeedbackKind.BUG, 1_760_000_000_000L),
            SettingsRoutes.feedback(FeedbackEntry.VOICE, FeedbackKind.IDEA)) +
            BrainModel.entries.map(ModelRoutes::model) + VoiceBundle.entries.map(ModelRoutes::voice)
    }
}
