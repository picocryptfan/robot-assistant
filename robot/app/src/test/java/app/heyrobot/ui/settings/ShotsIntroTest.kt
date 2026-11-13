// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// fix-shots-intro: since fix-screenshots-e a screenshot that looks like it holds a password, code or card is skipped
// whole (ShotSecrets.skips), so the intro says only its date is kept, not that secrets are "kept as what they are".
class ShotsIntroTest {
    private fun intro(values: String): String =
        Regex("""<string name="shots_intro">([^<]*)</string>""") // l10n: not text (a pattern)
            .find(File("src/main/res/$values/strings_shots.xml").readText())?.groupValues?.get(1).orEmpty()

    @Test fun introSaysASecretsScreenshotKeepsOnlyItsDate() {
        val en = intro("values")
        val fr = intro("values-fr-rCA")
        assertTrue(en, "only its date is kept" in en)
        assertFalse(en, "what they are" in en || "never their value" in en)
        assertTrue(fr, "seule sa date est gardée" in fr)
        assertFalse(fr, "ce qu’ils sont" in fr || "jamais leur valeur" in fr)
    }
}
