// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.SpeakReplies
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Review of point and read, finding 2: a tapped Read aloud was silent on default settings (Speak replies: when I speak).
class ReadSpeaksTest {
    @Test fun aReadingYouAskedForIsSaidOnDefaultSettings() {
        assertTrue(ReadAloud.speaks(SpeakReplies.VOICE, now = false))
        assertTrue(ReadAloud.speaks(SpeakReplies.ALWAYS, now = true))
    }

    @Test fun neverStaysSilent() {
        assertFalse(ReadAloud.speaks(SpeakReplies.NEVER, now = false))
    }
}
