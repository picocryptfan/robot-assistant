// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The updater's words: plain, one message for each failure and install result, "you" and never "the owner". */
class UpdateTextTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = ZonedDateTime.of(2026, 10, 11, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun everyFailureAndOutcomeHasItsOwnPlainSentence() {
        val texts = UpdateFail.entries.map { UpdateText.fail(it, null, zone) } + InstallOutcome.entries.map { UpdateText.install(it, "0.4.6") }
        assertEquals(texts.size, texts.toSet().size)
        for (t in texts) {
            assertTrue(t, t.endsWith(".") && t.length < 200)
            assertFalse(t, t.contains("owner", ignoreCase = true) || t.contains("null") || t.contains("Exception") || t.contains("HTTP"))
        }
    }

    @Test fun thePrivateRepositoryAndRateLimitMessagesSayWhatToDo() {
        assertTrue(UpdateText.fail(UpdateFail.NOT_FOUND_NO_TOKEN).contains("private"))
        assertTrue(UpdateText.fail(UpdateFail.NOT_FOUND_NO_TOKEN).contains("access token"))
        val at = ZonedDateTime.of(2026, 10, 11, 15, 42, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("GitHub's limit on checks from your network was reached (60 an hour without a token). Try again after 15:42.",
            UpdateText.fail(UpdateFail.RATE_LIMITED, at, zone))
        assertTrue(UpdateText.fail(UpdateFail.RATE_LIMITED, null, zone).endsWith("Try again in an hour."))
        assertTrue(UpdateText.fail(UpdateFail.CHECKSUM_MISMATCH).contains("SHA256SUMS"))
        assertTrue(UpdateText.fail(UpdateFail.OTHER_SIGNER).contains("Robot's key"))
        // A token that couldn't be read just now is kept: nothing to add again.
        assertTrue(UpdateText.fail(UpdateFail.TOKEN_UNAVAILABLE).contains("just now") && UpdateText.fail(UpdateFail.TOKEN_UNAVAILABLE).contains("still saved"))
        assertFalse(UpdateText.fail(UpdateFail.TOKEN_UNAVAILABLE).contains("add", ignoreCase = true))
    }

    @Test fun theLastCheckLine() {
        assertEquals("Not checked yet", UpdateText.lastCheck(UpdateState(), now, zone))
        val t = ZonedDateTime.of(2026, 10, 11, 14, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Last checked today at 14:05. You're up to date.", UpdateText.lastCheck(UpdateState(lastCheck = t, outcome = CheckOutcome.UP_TO_DATE), now, zone))
        val y = ZonedDateTime.of(2026, 10, 10, 9, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Last checked yesterday at 09:10. GitHub's limit on checks from your network was reached (60 an hour without a token). Try again in an hour.",
            UpdateText.lastCheck(UpdateState(lastCheck = y, outcome = CheckOutcome.FAILED, fail = UpdateFail.RATE_LIMITED), now, zone))
        val old = ZonedDateTime.of(2026, 9, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val found = FoundRelease("o/r", "v0.4.6", "0.4.6", false, null, "", FileRef(1, "Robot-0.4.6.apk", 1), FileRef(2, "SHA256SUMS", 1))
        assertEquals("Last checked 2 Sep at 08:00. Robot 0.4.6 is available.", UpdateText.lastCheck(UpdateState(lastCheck = old, outcome = CheckOutcome.AVAILABLE, found = found), now, zone))
        assertEquals("Last checked 2 Sep at 08:00. Robot 0.4.6 is available (you skipped it).", UpdateText.lastCheck(UpdateState(lastCheck = old, outcome = CheckOutcome.SKIPPED, found = found), now, zone))
    }

    @Test fun notificationsDatesAndSizes() {
        assertEquals("Robot 0.4.6 is available", UpdateText.availableTitle("0.4.6"))
        assertEquals("Robot 0.4.6 is ready to install", UpdateText.readyTitle("0.4.6"))
        assertEquals("Robot updated to 0.4.6", UpdateText.updatedTitle("0.4.6"))
        assertEquals("11 October 2026", UpdateText.published("2026-10-11T09:30:00Z", zone))
        assertEquals(null, UpdateText.published("yesterday", zone))
        assertEquals(null, UpdateText.published(null, zone))
        assertEquals("97 MB", UpdateText.size(97_000_000))
        assertEquals("250 bytes", UpdateText.size(250))
        assertEquals("2 KB", UpdateText.size(1_600))
        assertEquals("Robot 0.4.6 is installed.", UpdateText.install(InstallOutcome.SUCCESS, "0.4.6"))
        assertEquals("The update is installed.", UpdateText.install(InstallOutcome.SUCCESS, null))
    }
}
