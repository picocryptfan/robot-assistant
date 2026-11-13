// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.headset.HeadsetAnnounce
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.Snapshots.thread
import app.heyrobot.core.output.MessageNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scam texts (idea 17): every reading gets the same line from [ScamCheck.line], English and French. Made-up texts. */
class ScamReadingsTest {
    private val scam = "Canada Post: parcel held, pay \$1.99 at canadapost-redelivery.top"
    private val words = mapOf(Lang.EN to "isn't one of", Lang.FR to "pas une des adresses") // l10n: not text (test expectations)
    private fun notice(text: String) = MessageNotice("k", "Sam", null, text, "app", replyable = true)

    @Test fun carModeAndTheHeadsetSayTheLineBeforeTheWords() {
        for ((lang, w) in words) {
            val car = CarMessages.says(notice(scam), lang)
            assertTrue(car, car.contains(w) && car.indexOf(w) < car.indexOf("canadapost-redelivery"))
            assertEquals(car, HeadsetAnnounce.read(notice(scam), lang))
            assertFalse(CarMessages.says(notice("See you at 6"), lang).contains(w))
        }
    }

    @Test fun aSummaryGetsOneLinePerScamConversation() {
        val lines = MessageReader.scamLines(listOf(thread(Snapshots.cheogram1to1(scam)), thread(Snapshots.cheogram1to1("See you at 6"))))
        assertEquals(1, lines.size)
        for ((lang, w) in words) assertTrue(lines[0].render(Langs.of(lang)).contains(w))
    }

    @Test fun thePanelReadsTheSameLine() {
        val shown = MessageReader.thread(thread(Snapshots.cheogram1to1(scam)), Lang.EN, more = false).shown
        assertTrue(shown, shown.contains(ScamCheck.line(scam)!!.render(Langs.of(Lang.EN))))
    }
}
