// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-stage-reading: when "read this" is point and read's, and when it is Read it to me's. */
class ReadAskRuleTest {
    /** M3: a dictated answer, a message's words or Conversation mode's are never a reading; Car mode never opens the camera. */
    @Test fun aWaitingAnswerOrTheCarNeverOpensTheCamera() {
        for (q in listOf("what does it say", "read it", "qu'est-ce que ça dit")) {
            assertEquals(q, ReadAloud.Ask.CAMERA, ReadAloud.panelAsk(q, Attachment.NONE, false, taken = false, car = false))
            assertNull(q, ReadAloud.panelAsk(q, Attachment.NONE, false, taken = true, car = false))
            assertNull(q, ReadAloud.panelAsk(q, Attachment.NONE, false, taken = false, car = true))
        }
        // A photo already held is still read in the car: no camera opens for it.
        assertEquals(ReadAloud.Ask.PHOTO, ReadAloud.panelAsk("read this", Attachment.PHOTO, true, taken = false, car = true))
    }

    /** L3: a bare "read this" opens the camera; a request naming text (article, page, note) reads it or says there's none. */
    @Test fun aBareReadThisOpensTheCameraAndNamedTextIsReadInBothLanguages() {
        for (q in listOf("read this", "Read this, please", "lis ça", "Lis-moi ça")) assertEquals(q, ReadAloud.Ask.CAMERA, ReadAloud.ask(q, Attachment.NONE, false))
        for (q in listOf("read me this article", "read this page to me", "lis-moi cet article", "lis-moi cette page")) {
            assertNull(q, ReadAloud.ask(q, Attachment.NONE, false))
            assertEquals(q, ReadCommand.Shared, ReadRouter.route(q))
        }
        for (q in listOf("read me my last note", "lis-moi ma note sur le voyage")) {
            assertNull(q, ReadAloud.ask(q, Attachment.NONE, false))
            assertTrue(q, ReadRouter.route(q) is ReadCommand.Note)
        }
    }
}
