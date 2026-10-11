package app.heyrobot.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

/** The open page (a note being edited, a conversation, setup) survives rotation and the process being killed. */
class PageSaverTest {
    private val scope = SaverScope { true }

    private fun save(p: Page?): String = with(Page.Saver) { scope.save(p) }!!

    @Test fun everyPageRoundTrips() {
        for (p in listOf(Page.Setup(), Page.Setup(true), Page.Conversation("abc-123"), Page.NoteEdit("4f1c-uuid"), Page.NoteEdit(null), Page.Safety,
                Page.People, Page.PersonEdit("p1"), Page.WakePhrases, Page.Licences, Page.Licence("onnxruntime"),
                Page.Licence("litert-lm", "licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt"), Page.Licence(ROBOT_LICENCE),
                Page.Transcripts, Page.Transcript("20261011-1402-7f3a"), Page.Transcript("20261011-1402-7f3a", 12), Page.ConversationSettings,
                Page.Helpers, Page.Helper("claude"), Page.Helper("h0a9z7k"), Page.AddHelper, null)) {
            assertEquals(p, Page.Saver.restore(save(p)))
        }
    }

    @Test fun aSettingsPageIsSavedByItsRoute() {
        assertEquals("settings:display", save(Page.Settings("display")))
        // Settings › About › Updates, which the updater's notifications open, too.
        assertEquals("settings:updates", save(Page.Settings(app.heyrobot.ui.settings.SettingsRoutes.UPDATES)))
        for (p in listOf(Page.Settings("display"), Page.Settings("voice/wake"), Page.Settings("updates"))) assertEquals(p, Page.Saver.restore(save(p)))
    }

    @Test fun anOpenNoteIsSavedByItsId() {
        assertEquals("note:", save(Page.NoteEdit(null)))
        assertEquals("note:abc", save(Page.NoteEdit("abc")))
        assertEquals(Page.NoteEdit("abc"), Page.Saver.restore("note:abc"))
    }
}
