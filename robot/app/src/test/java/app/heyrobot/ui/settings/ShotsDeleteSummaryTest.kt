// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// review-screenshots-b M3: with Selected photos, Delete the index reads every pick again; its summary says so.
class ShotsDeleteSummaryTest {
    private fun summary(values: String): String =
        Regex("""<string name="shots_delete_summary_selected">([^<]*)</string>""") // l10n: not text (a pattern)
            .find(File("src/main/res/$values/strings_shots.xml").readText())?.groupValues?.get(1).orEmpty()

    @Test fun withSelectedPhotosDeleteSaysThePicksAreReadAgain() {
        assertTrue(summary("values"), "picked" in summary("values"))
        assertTrue(summary("values-fr-rCA"), "choisies" in summary("values-fr-rCA"))
    }
}
