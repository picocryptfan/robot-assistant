// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word error rate for the Transcription check, with French normalisation (docs/conversation-mode.md 2.5). */
class WerTest {
    @Test fun frenchNormalisation() {
        assertEquals(listOf("j'ai", "pogné", "un", "char", "de", "trafic", "sur", "le", "pont", "champlain"),
            Wer.words("J’ai pogné un char de trafic sur le pont-Champlain."))
        assertEquals(listOf("on", "se", "reprend", "tu", "demain"), Wer.words("On se reprend-tu demain?"))
        assertEquals(listOf("le", "coeur", "a", "ses", "raisons"), Wer.words("« Le cœur a ses raisons »"))
        assertEquals(0, Wer.score("J’ai pogné un char de trafic sur le pont Champlain.", "j'ai pogné un char de trafic sur le pont-champlain").errors)
        assertEquals(0, Wer.score("Le cœur, l'œil.", "le coeur l'oeil").errors)
    }

    @Test fun accentsCountButAreCountedApart() {
        val r = Wer.score("Il a été déçu", "il a ete déçu")
        assertEquals(WerResult(4, substitutions = 1, accents = 1), r)
        assertEquals(25, r.percent)
        assertEquals(0.0, r.rateWithoutAccents, 0.0)
        // A different word is not an accent error.
        assertEquals(0, Wer.score("il a été déçu", "il a eu déçu").accents)
    }

    @Test fun substitutionsDeletionsInsertions() {
        assertEquals(WerResult(5, substitutions = 1, deletions = 1), Wer.score("on se voit demain matin", "on te voit demain"))
        assertEquals(WerResult(3, insertions = 2), Wer.score("à tantôt là", "à tantôt là là là"))
        assertEquals(WerResult(0), Wer.score("", ""))
        assertEquals(1.0, Wer.score("", "bonjour").rate, 0.0)
        assertEquals(1.0, Wer.score("bonjour", "").rate, 0.0)
    }

    @Test fun totalsAndTheGate() {
        val lines = listOf(Wer.score("un deux trois quatre cinq", "un deux trois quatre cinq"), Wer.score("six sept huit neuf dix", "six sept huit neuf"))
        val all = Wer.total(lines)
        assertEquals(10, all.words)
        assertEquals(10, all.percent)
        assertTrue(Wer.passes(all, "fr"))
        assertFalse(Wer.passes(all, "en"))
        assertTrue(Wer.passes(WerResult(100, substitutions = 8), "en"))
        assertFalse(Wer.passes(WerResult(100, substitutions = 16), "fr"))
    }
}
