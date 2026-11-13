// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** review-wikipedia-l4 L5: French "M. " and "oct. " don't end a spoken piece. */
class SpeechFrenchAbbreviationTest {
    @Test fun frenchAbbreviationsDontEndAPiece() {
        val pieces = SentenceSplitter().feed("Il est né. M. Riel est né en oct. 1844 à Saint-Boniface, avec Mme Riel. Il ")
        assertTrue(pieces.toString(), pieces.none { it.endsWith("M.") || it.endsWith("oct.") })
    }
}
