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
                Page.People, Page.PersonEdit("p1"), Page.WakePhrases, Page.Transcripts, Page.Transcript("20261011-1402-7f3a"),
                Page.Transcript("20261011-1402-7f3a", 12), Page.ConversationSettings, null)) {
            assertEquals(p, Page.Saver.restore(save(p)))
        }
    }

    @Test fun anOpenNoteIsSavedByItsId() {
        assertEquals("note:", save(Page.NoteEdit(null)))
        assertEquals("note:abc", save(Page.NoteEdit("abc")))
        assertEquals(Page.NoteEdit("abc"), Page.Saver.restore("note:abc"))
    }
}
