// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWordsTest {
    @Test fun eachOpenWordInEnglishLeavesTheSentenceOpen() {
        for (w in listOf("and", "but", "or", "so", "because", "the", "a", "to", "um", "uh")) {
            assertTrue(w, OpenWords.endsOpen("call my brother $w"))
        }
    }

    @Test fun eachOpenWordInFrenchLeavesTheSentenceOpen() {
        for (w in listOf("et", "mais", "ou", "donc", "parce que", "le", "la", "de", "euh")) {
            assertTrue(w, OpenWords.endsOpen("appelle mon frère $w"))
        }
    }

    @Test fun theRecognizersCapitalsAndPunctuationDontHideIt() {
        assertTrue(OpenWords.endsOpen("Turn on the lights and."))
        assertTrue(OpenWords.endsOpen("Um,"))
        assertTrue(OpenWords.endsOpen("Mets la musique, EUH…"))
        assertTrue(OpenWords.endsOpen("Je reste parce  que"))
    }

    @Test fun aFinishedSentenceIsNotOpen() {
        for (t in listOf("", "   ", "...", "What's the weather tomorrow?", "turn on the lights", "mets-la", "Quelle heure est-il ?",
            "Je veux ce que", "android", "sand")) {
            assertFalse(t, OpenWords.endsOpen(t))
        }
    }

    @Test fun anOpenSentenceGetsVeryLong() = assertEquals(2_500, OpenWords.PAUSE_MS)
}
