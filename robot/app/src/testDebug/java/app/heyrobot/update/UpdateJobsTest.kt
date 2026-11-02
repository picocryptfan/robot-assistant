// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.update.CheckOutcome
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.UpdateSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What the update jobs do when a setting changes, against Android's scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateJobsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val js: JobScheduler = ctx.getSystemService(JobScheduler::class.java)

    private fun release(version: String, test: Boolean) =
        FoundRelease("o/r", "v$version", version, test, null, "", FileRef(1, "Robot-$version.apk", 10), FileRef(2, "SHA256SUMS", 10))

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    private fun foundWithAWaitingDownload(found: FoundRelease) {
        UpdateStore.update(ctx) { it.copy(found = found, outcome = CheckOutcome.AVAILABLE, firstSeen = 5) }
        UpdateStore.saveSettings(ctx, UpdateSettings(testBuilds = true))
        UpdateJobs.downloadOnWifi(ctx, 10)
        assertNotNull(js.getPendingJob(UpdateJobs.DOWNLOAD))
    }

    /** F71: the switch going off takes a test build already found with it: no download, no offer. */
    @Test fun turningOffTestBuildsDropsAFoundTestBuildAndItsDownload() {
        foundWithAWaitingDownload(release("9.0.0-beta.1", test = true))
        Updater.setSettings(ctx, UpdateSettings(testBuilds = false))
        waitFor { UpdateStore.state(ctx).found == null }
        assertNull(UpdateStore.state(ctx).found)
        assertEquals(CheckOutcome.UP_TO_DATE, UpdateStore.state(ctx).outcome)
        assertNull(js.getPendingJob(UpdateJobs.DOWNLOAD))
    }

    @Test fun turningOffTestBuildsLeavesAFoundReleaseAlone() {
        foundWithAWaitingDownload(release("9.0.0", test = false))
        Updater.setSettings(ctx, UpdateSettings(testBuilds = false))
        // Nothing to wait for: give a wrong drop the time to show.
        Thread.sleep(200)
        assertEquals("9.0.0", UpdateStore.state(ctx).found?.version)
        assertNotNull(js.getPendingJob(UpdateJobs.DOWNLOAD))
    }

    /** F10: a daily check Android won't schedule is reported, not only logged. */
    @Test fun aRefusedScheduleIsReported() {
        js.cancelAll()
        shadowOf(js).failOnJob(UpdateJobs.CHECK)
        assertFalse(UpdateJobs.ensure(ctx, UpdateSettings(autoCheck = true)))
        assertTrue(UpdateJobs.ensure(ctx, UpdateSettings(autoCheck = false)))
        shadowOf(js).failOnJob(UpdateJobs.DOWNLOAD)
        UpdateJobs.downloadOnWifi(ctx, 10)
        assertTrue(UpdateJobs.downloadRefused)
    }

    @Test fun aScheduledCheckIsFine() {
        js.cancelAll()
        assertTrue(UpdateJobs.ensure(ctx, UpdateSettings(autoCheck = true)))
        assertNotNull(js.getPendingJob(UpdateJobs.CHECK))
        assertTrue(UpdateJobs.ensure(ctx, UpdateSettings(autoCheck = true)))
    }
}
