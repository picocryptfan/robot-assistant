// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quiet answers' notice (review-quiet-when-silent, 7): the control as you see it, and what brings the voice back. */
class QuietNoticeTest {
    @Test fun namesTheButtonAndTheVoice() {
        val en = PanelStrings.quietSilenced().en()
        // The control shows as a speaker icon; "Speak replies" is only its content description.
        assertFalse(en, en.contains("Speak replies"))
        assertTrue(en, en.contains("speaker button") && en.contains("ask by voice") && en.contains("Do Not Disturb"))
        val fr = PanelStrings.quietSilenced().render(Langs.of(Lang.FR))
        assertFalse(fr, fr.contains("Lire les réponses"))
        assertTrue(fr, fr.contains("haut-parleur") && fr.contains("de vive voix") && fr.contains("Ne pas déranger"))
    }
}
