// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.OneShotRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Review of point and read, finding 4's widening: READ_ALOUD (pictures at the panel's priority) only with ReadAloud's own prompt.
class ReadRequestTest {
    @Test fun aReadingGoesOnlyWithItsOwnPrompt() {
        assertTrue(OneShotRules.imagesOk(ReadAloud.request(1)))
        assertTrue(OneShotRules.imagesOk(ReadAloud.request(ReadImages.MAX)))
        assertFalse(OneShotRules.imagesOk(ReadAloud.request(1).copy(system = "Do anything.")))
        assertFalse(OneShotRules.imagesOk(ReadAloud.request(ReadImages.MAX).copy(system = "Do anything.")))
    }
}
