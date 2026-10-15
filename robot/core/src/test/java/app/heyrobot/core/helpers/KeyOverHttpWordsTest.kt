// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.HelperFail
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.0.2, from the phone: after 1.0.1 refused to send a key over http, the helper's page said "Last checked: the server
 * answered." (no server had been reached) and the voice log said the model failed. They now say what happened: the key
 * wasn't sent over http, and nothing was asked.
 */
class KeyOverHttpWordsTest {
    @Test fun theLastCheckedLineSaysWhatTheCheckFound() {
        assertEquals("Robot hasn't reached My server yet.", HelperFacts().checkedLine("My server"))
        assertEquals("Last checked: the key wasn't sent over http, so nothing was asked.",
            HelperFacts(failure = HelperFailure.KEY_OVER_HTTP, checkedAt = 1).checkedLine("My server"))
        // Any other failure says so too, with the voice log's words for it (old models kept from an earlier check aren't counted).
        assertEquals("Last checked: it didn't work (server not answering).",
            HelperFacts(failure = HelperFailure.UNREACHABLE, models = listOf("a", "b"), checkedAt = 1).checkedLine("My server"))
        // As before when it worked.
        assertEquals("Last checked: a test question was answered, 2 models listed.",
            HelperFacts(answered = true, models = listOf("a", "b"), checkedAt = 1).checkedLine("My server"))
        assertEquals("Last checked: the server answered.", HelperFacts(checkedAt = 1).checkedLine("My server"))
    }

    @Test fun theVoiceLogSaysTheHelperWasntAsked() {
        fun line(f: HelperFailure) = VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_FAILED, HelperFail.of(f).id))
        assertEquals("The helper wasn't asked: key not sent over http", line(HelperFailure.KEY_OVER_HTTP))
        assertEquals("The helper wasn't asked: no key", line(HelperFailure.NO_KEY))
        assertEquals("The helper failed: key refused", line(HelperFailure.KEY_REFUSED))
        // The log's codes are stored as numbers: never renumbered.
        assertEquals(20, HelperFail.KEY_OVER_HTTP.id)
    }
}
