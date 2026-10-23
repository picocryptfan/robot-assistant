// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ReplyLang
import app.heyrobot.core.i18n.ReplyLangChoice
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 1.1 (the l10n design's WP2a): what the panel says by itself, in Québec French (tu) with French menus or English ones,
 * and 1.0's English unchanged. Robot's screens named in a reply follow the menus ([Langs.ui]); the voice gets no
 * typography characters.
 */
class PanelInFrenchTest {
    private val fr = Langs.of(Lang.FR)
    /** A French speaker whose menus are English. */
    private val frOnEnglishMenus = Langs(Lang.FR, Lang.EN, Lang.EN)
    private val nb = ' '
    private val fine = ' '

    @Test fun theEnglishIsOnePointZeros() {
        assertEquals("Sorry, I didn't catch that.", PanelStrings.sorry().en())
        assertEquals("Send your screen's text and a screenshot of Signal to Claude?",
            PanelStrings.sendTo("what" to Phrase.ListOf(listOf(PanelStrings.screenText(), PanelStrings.aScreenshotOf("appName" to "Signal"))), "name" to "Claude").en())
        assertEquals("Unlock your phone to start (Settings › Conversation › Start from the lock screen is off).",
            PanelStrings.unlockToStart("page" to UiStrings.inSettings(UiStrings.conversation)).en())
        assertEquals("Stopped. The transcript is saved in Robot › Transcripts.", PanelStrings.stoppedSaved("page" to UiLabel(UiStrings.transcripts)).en())
        assertEquals("1 thing, in Settings › Memory.", PanelStrings.thingsInSettings(1, "page" to UiStrings.inSettings(UiStrings.memory)).en())
        assertEquals("4 things, in Settings › Memory.", PanelStrings.thingsInSettings(4, "page" to UiStrings.inSettings(UiStrings.memory)).en())
        assertEquals("Forgotten: 3 things.", PanelStrings.forgottenThings(3).en())
        assertEquals("Unlock your phone, then open the Robot app › Settings › Model.", PanelStrings.unlockOpenPage("page" to UiStrings.inSettings(UiStrings.model)).en())
        assertEquals("Waiting for Claude…", PanelStrings.waitingFor("name" to "Claude").en())
        assertEquals("Locked: timers", PanelStrings.lockedAllowed("allowed" to "timers").en())
    }

    @Test fun whatRobotSaysInFrench() {
        assertEquals("Désolé, je n’ai pas compris.", PanelStrings.sorry().render(fr))
        assertEquals("Envoyer le texte de ton écran et une capture d’écran de Signal à Claude$fine?",
            PanelStrings.sendTo("what" to Phrase.ListOf(listOf(PanelStrings.screenText(), PanelStrings.aScreenshotOf("appName" to "Signal"))), "name" to "Claude").render(fr))
        assertEquals("Texto à Maman.", PanelStrings.texting("name" to "Maman").render(fr))
        assertEquals("Ton script «${nb}sauvegarde${nb}».", PanelStrings.yourScript("name" to "sauvegarde").render(fr))
        assertEquals("C’est oublié$nb: 3 choses.", PanelStrings.forgottenThings(3).render(fr))
        assertEquals("1 chose, dans Réglages › Mémoire.", PanelStrings.thingsInSettings(1, "page" to UiStrings.inSettings(UiStrings.memory)).render(fr))
        assertEquals("Robot n’avait pas de réponse. Essaie de demander autrement.", PanelStrings.noAnswer().render(fr))
    }

    /** A French reply on English menus names the screen you'll see: « … dans Robot › Transcripts. ». */
    @Test fun aRobotScreenFollowsTheMenus() {
        assertEquals("Arrêté. La transcription est enregistrée dans Robot › Transcripts.",
            PanelStrings.stoppedSaved("page" to UiLabel(UiStrings.transcripts)).render(frOnEnglishMenus))
        assertEquals("Arrêté. La transcription est enregistrée dans Robot › Transcriptions.",
            PanelStrings.stoppedSaved("page" to UiLabel(UiStrings.transcripts)).render(fr))
    }

    @Test fun theVoiceGetsNoTypography() {
        val said = PanelStrings.cardHint().render(fr.voice)
        assertEquals("Une carte attend: dis oui ou non, ou touche un de ses boutons.", said)
        assertFalse(PanelStrings.wantOpenApp("appName" to "ChatGPT").render(fr.voice).contains(fine))
        assertFalse(PanelStrings.yourScript("name" to "x").render(fr.voice).contains('«'))
    }

    @Test fun theStatusLineInFrench() {
        assertEquals("À l’écoute…", PanelStrings.listening().render(fr))
        assertEquals("Avec Jarvis$nb: les questions vont à ton serveur", PanelStrings.withHelperServer("name" to "Jarvis").render(fr))
        assertEquals("Verrouillé$nb: minuteries", PanelStrings.lockedAllowed("allowed" to "minuteries").render(fr))
    }

    /** The panel's reply language: « libère de la mémoire » matched in French answers in French; a fixed setting wins. */
    @Test fun theReplyLanguageOfAQuestion() {
        val router = FreeUp.asked("libère de la mémoire")?.let { if (it) Lang.FR else Lang.EN }
        assertEquals(Lang.FR, ReplyLang.decide(null, router, null, 0, Lang.EN, ReplyLangChoice.SPOKEN))
        assertEquals(Lang.EN, ReplyLang.decide(null, router, null, 0, Lang.FR, ReplyLangChoice.EN))
        // A routine's Run now: nothing heard, the menus' language.
        assertEquals(Lang.FR, ReplyLang.unheard(Lang.FR, ReplyLangChoice.SPOKEN))
    }
}
