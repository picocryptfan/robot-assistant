// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.messages.MessageVoices
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which clock rule each piece the voice speaks gets: the language sherpa reads it in, as PanelController's calls give it. */
class PieceTextTest {
    @Test fun aFrenchTurnsReplyThroughSiwis() {
        // speakRobot in a French turn with Heart chosen: speakOther(Siwis, "fr") (core MessageVoices).
        val (sid, lang) = MessageVoices.french(VoiceChoice.HEART)!!
        assertEquals("Ton rendez-vous est à 12 heures 30.", PieceText.of("Ton rendez-vous est à 12:30.", sid, lang, VoiceChoice.HEART.lang))
        assertEquals("On se voit à 18 heures 15?", PieceText.of("On se voit à 18h15?", sid, lang, VoiceChoice.HEART.lang))
    }

    @Test fun siwisChosenAsRobotsVoice() {
        // MessageVoices.french is null: Robot's own voice, with no speaker, reads in Siwis's "fr".
        assertEquals(null, MessageVoices.french(VoiceChoice.SIWIS))
        assertEquals("Il est 7 heures 2.", PieceText.of("Il est 7 h 02.", null, null, VoiceChoice.SIWIS.lang))
    }

    @Test fun englishPieces() {
        // Heart, Kitten, and a helper's English sentence (its voice, no language): the English rule.
        assertEquals("It's 7 oh 2.", PieceText.of("It's 7:02.", null, null, VoiceChoice.HEART.lang))
        assertEquals("At 7 o'clock.", PieceText.of("At 7:00.", null, null, VoiceChoice.KITTEN.lang))
        assertEquals("Lunch at 12:30 p.m.", PieceText.of("Lunch at 12:30 p.m.", 16, null, VoiceChoice.HEART.lang))
        // A helper's French sentence (core HelperVoices.forSentence: "fr").
        assertEquals("À 9 heures.", PieceText.of("À 9 h.", VoiceChoice.SIWIS.speaker, "fr", VoiceChoice.HEART.lang))
    }

    @Test fun robotsOwnFrenchComesAlreadyDone() {
        val said = "De 9 heures à 10 heures 30, puis minuit 15."
        assertEquals(said, PieceText.of(said, VoiceChoice.SIWIS.speaker, "fr", null))
    }
}
