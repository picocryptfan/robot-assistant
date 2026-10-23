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
 * wasn't sent over http, and nothing was asked. 1.1: the page's line is the app's (strings_helpers.xml, HelpersScreensTest).
 */
class KeyOverHttpWordsTest {
    @Test fun theVoiceLogSaysTheHelperWasntAsked() {
        fun line(f: HelperFailure) = VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.CLAUDE_FAILED, HelperFail.of(f).id))
        assertEquals("The helper wasn't asked: key not sent over http", line(HelperFailure.KEY_OVER_HTTP))
        assertEquals("The helper wasn't asked: no key", line(HelperFailure.NO_KEY))
        assertEquals("The helper failed: key refused", line(HelperFailure.KEY_REFUSED))
        // The log's codes are stored as numbers: never renumbered.
        assertEquals(20, HelperFail.KEY_OVER_HTTP.id)
    }
}
