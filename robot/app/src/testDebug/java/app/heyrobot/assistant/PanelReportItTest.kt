package app.heyrobot.assistant

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Test

/** RP3: the Report it line under the correction's answer opens the card for the answer it corrects, in English and French. */
class PanelReportItTest {
    @Test fun reportItOpensTheCardForThatTurn() {
        var got: String? = "none"
        val actions = object : PanelActions by RecordingActions() { override fun report(replyId: String?) { got = replyId } }
        val (label, tap) = noticeAction(NoticeAction.REPORT, actions, Langs.EN, "r7")!!
        assertEquals("Report it", label)
        tap()
        assertEquals("r7", got)
        assertEquals("La signaler", noticeAction(NoticeAction.REPORT, actions, Langs.of(Lang.FR), "r7")!!.first)
    }
}
