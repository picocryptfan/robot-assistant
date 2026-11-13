package app.heyrobot.ui.settings

import app.heyrobot.core.feedback.Diagnostic
import app.heyrobot.core.feedback.ReportPart
import org.junit.Assert.assertEquals
import org.junit.Test

/** L5: Reports you sent names a part by its diagnostic's title, and by its report header when no diagnostic holds it. */
class SentReportsPartTitleTest {
    @Test fun aPartNoDiagnosticHoldsShowsItsHeader() {
        val titles = mapOf(Diagnostic.SETTINGS to "Settings")
        assertEquals("Settings", partTitle(ReportPart.SETTINGS, titles))
        assertEquals(ReportPart.ONLINE.header, partTitle(ReportPart.ONLINE, titles))
    }
}
