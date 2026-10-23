// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.car.CallTarget
import app.heyrobot.core.car.CarAnnounce
import app.heyrobot.core.car.CarDetect
import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.car.CarNavigate
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.CarSetup
import app.heyrobot.core.car.PhoneCandidate
import app.heyrobot.core.car.PhoneLabel
import app.heyrobot.core.car.ReplyDraft
import app.heyrobot.core.headset.HeadsetEntry
import app.heyrobot.core.headset.HeadsetSetup
import app.heyrobot.core.i18n.FrTypo
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 1.1 (WP4b): headset and car mode in Québec French. A spoken line gets the voice's form (no typography characters, core
 * VoiceForm); a screen gets its French typography; a Robot page named in a sentence follows the menus.
 */
class HandsFreeFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private fun f(s: String) = FrTypo.fix(s)
    private val marie = MessageNotice("CHEOGRAM:me#marie", "Marie", "k1", "On se voit à 5?", "Cheogram", replyable = true)

    @Test fun aLineIsSaidWithoutTypographyAndShownWithIt() {
        val line = OutputLines.READ_ALOUD
        assertEquals("Others may hear it. Read it aloud?", line.get(Lang.EN))
        assertEquals("D’autres pourraient l’entendre. Je le lis à voix haute?", line.get(Lang.FR))
        assertEquals(f("D'autres pourraient l'entendre. Je le lis à voix haute ?"), line.shown(fr))
        assertEquals(line.fr, line.said(fr))
        for (l in listOf(OutputLines.KEEP_FOR_LOOK, CallTarget.NO_CALL, CarMessages.ASK_REPLY, CarNavigate.ROUTE_READY)) {
            assertFalse(l.fr, Regex("[  «»“”›]").containsMatchIn(l.fr))
        }
    }

    @Test fun messagesInTheCar() {
        assertEquals("Message de Marie. Je te le lis?", CarMessages.announce(marie, CarSettings(announce = CarAnnounce.SENDER_THEN_ASK), locked = false,
            trusted = false, senderAllowed = true, lang = Lang.FR).let { (it as app.heyrobot.core.car.CarAnnouncement.Announce).say })
        assertEquals("Réponse à Marie: J’arrive. J’envoie?", CarMessages.readBack(ReplyDraft("k", "Marie", "Cheogram", "J’arrive"), Lang.FR))
        assertEquals("Remis à Cheogram.", CarMessages.result(ReplyResult.HANDED_OVER, "Cheogram", Lang.FR))
    }

    @Test fun callsAndPlaces() {
        val home = PhoneCandidate("Martine Martin", "+1 514 555 0101", PhoneLabel.HOME)
        assertEquals("Appeler Martine Martin, domicile, qui finit par 0101", CallTarget.readBack(home, Lang.FR))
        assertEquals("Call Martine Martin, home, ending 0101", CallTarget.readBack(home, Lang.EN))
        // A page named aloud: Réglages, Mode auto (the voice's pause for "›").
        assertEquals("Enregistre d’abord l’adresse de ton travail dans Réglages, Mode auto.", CarNavigate.noPlace(false, Lang.FR))
        // On English menus the page keeps the menus' words.
        assertEquals(f("Enregistre d'abord l'adresse de ton domicile dans ") + "Settings › Car mode.",
            CarNavigate.noPlaceText(true).render(Langs(Lang.FR, Lang.EN, Lang.EN)))
    }

    @Test fun theScreensWords() {
        assertEquals("Mode auto", CarScreen.titleText(false).render(fr))
        assertEquals("Mode auto · déverrouillé", CarScreen.titleText(true).render(fr))
        assertEquals("Activé · aucune auto ajoutée", CarSetup.summaryText(true, DeviceMemory()).render(fr))
        assertEquals("Désactivé", CarSetup.summaryText(false, DeviceMemory()).render(fr))
        assertEquals(f("Robot voit « MyCar » (…12:34). Est-ce ton auto ?"),
            CarDetect.questionText(SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")).render(fr))
        val off = HeadsetSetup.row(buttonOn = false, handlers = emptyMap())
        assertEquals(f("Désactivé : les autres applis reçoivent le bouton des écouteurs (Réglages › Écouteurs)"), off.textPhrase.render(fr))
        assertEquals(HeadsetSetup.row(false, emptyMap()), off)
        assertEquals("Robot n'est pas l'assistant numérique".replace('\'', '’'), HeadsetEntry.notAssistant.render(fr))
        assertEquals("Les personnes que tu choisis", app.heyrobot.core.headset.AnnounceFrom.CHOSEN_PEOPLE.text.render(fr))
    }
}
