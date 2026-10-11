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
                Page.Licence("litert-lm", "licenses/notices/litertlm-android-THIRD_PARTY_NOTICE.txt"), Page.Licence(ROBOT_LICENCE), null)) {
            assertEquals(p, Page.Saver.restore(save(p)))
        }
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
}
