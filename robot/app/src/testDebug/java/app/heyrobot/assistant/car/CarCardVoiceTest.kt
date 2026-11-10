// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-car-long-card-b: a reply card's voice yes in the car sends only words Robot read out in full (PanelMessages). */
class CarCardVoiceTest {
    private val words = "See you at six"

    @Test fun aYesSendsOnlyTheWordsReadOutInFullOnATrustedCarScreen() {
        assertTrue(CarCardVoice.replyYes(trusted = true, readOut = words, sends = words))
    }

    @Test fun speakRepliesOffOrCutOffOrNeverReadTakesATap() {
        assertFalse("Speak replies off, held back or cut off", CarCardVoice.replyYes(true, null, words))
    }

    @Test fun wordsChangedSinceTheReadOutTakeATap() {
        // A translation ticked or the draft changed after Robot read it: what would go wasn't heard.
        assertFalse(CarCardVoice.replyYes(true, words, "Vois-toi à six heures"))
        assertFalse(CarCardVoice.replyYes(true, words, "$words, love"))
    }

    @Test fun anUntrustedCarScreenTakesATap() {
        assertFalse(CarCardVoice.replyYes(false, words, words))
    }

    @Test fun wordsOverAboutAMinuteAloudTakeATapEvenReadOut() {
        val long = "word ".repeat(200).trim()
        assertTrue(long.length > CarCardVoice.SAY_MAX)
        assertFalse(CarCardVoice.replyYes(true, long, long))
        val short = "word ".repeat(150).trim()
        assertTrue(CarCardVoice.replyYes(true, short, short))
    }

    @Test fun frenchWordsReadOutCountTheSame() {
        val fr = "On se voit à six heures, d\u2019accord\u202f?"
        assertTrue(CarCardVoice.replyYes(true, fr, fr))
        assertFalse(CarCardVoice.replyYes(true, null, fr))
    }
}
