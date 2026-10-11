package app.heyrobot.update

import android.content.pm.PackageInstaller
import app.heyrobot.BuildConfig
import app.heyrobot.core.LicenceBook
import app.heyrobot.core.update.FileRef
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.InstallOutcome
import app.heyrobot.core.update.RobotVersion
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateState
import app.heyrobot.ui.settings.updatesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The updater against the app it ships in: this version parses, the default repository is the one Settings › About ›
 * Licences names (written once, in components.json), core's install statuses are Android's, and its files sit beside
 * settings.json without being in it.
 */
class UpdaterTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    @Test fun thisVersionParses() {
        val v = RobotVersion.parse(BuildConfig.VERSION_NAME)
        assertNotNull(BuildConfig.VERSION_NAME, v)
        assertEquals(BuildConfig.VERSION_NAME, v!!.text)
    }

    @Test fun theDefaultRepositoryIsTheOneTheLicencesListNames() {
        val book = LicenceBook.parse(File(root, "app/src/main/assets/${LicenceBook.FILE}").readText())
        val repo = GitHubRepo.fromSourceLink(book.robot.source)
        assertEquals(GitHubRepo("picocryptfan", "robot-gos-assistant"), repo)
        // The tag and bundle links follow from the same place, so a rename is one change in one file.
        assertTrue(book.robot.sourceTag.startsWith(book.robot.source + "/"))
        assertTrue(book.robot.sourceBundle.startsWith(book.robot.source + "/releases/download/"))
        // And no Kotlin source writes it a second time.
        val sources = listOf("core", "app", "voice", "brain", "claude").flatMap { m -> File(root, "$m/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertEquals(emptyList<String>(), sources.filter { it.readText().contains("robot-gos-assistant") }.map { it.name })
    }

    @Test fun installStatusesAreAndroids() {
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, InstallOutcome.STATUS_PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, InstallOutcome.STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE, InstallOutcome.STATUS_FAILURE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, InstallOutcome.STATUS_FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, InstallOutcome.STATUS_FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, InstallOutcome.STATUS_FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, InstallOutcome.STATUS_FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, InstallOutcome.STATUS_FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, InstallOutcome.STATUS_FAILURE_INCOMPATIBLE)
        assertEquals(PackageInstaller.STATUS_FAILURE_TIMEOUT, InstallOutcome.STATUS_FAILURE_TIMEOUT)
        assertEquals(InstallOutcome.CANCELLED, InstallOutcome.of(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallOutcome.CONFLICT, InstallOutcome.of(PackageInstaller.STATUS_FAILURE_CONFLICT))
    }

    @Test fun theFilesSitBesideSettingsJsonButOutsideIt() {
        val src = File(root, "app/src/main/java/app/heyrobot/update/UpdateStore.kt").readText()
        assertTrue(src.contains("File(Paths.dir(ctx), UpdateSettings.FILE)") && src.contains("File(Paths.dir(ctx), UpdateState.FILE)"))
        assertEquals("updates.json", UpdateSettings.FILE)
        assertEquals("update_state.json", UpdateState.FILE)
        // settings.json (core Settings.kt) knows nothing of the updater.
        val settings = File(root, "core/src/main/java/app/heyrobot/core/Settings.kt").readText()
        assertTrue(listOf("autoCheck", "wifiDownload", "testBuilds", "installWithoutAsking", "update").none { settings.contains("\"$it") })
    }

    @Test fun theAboutRowSaysWhatMatters() {
        val now = System.currentTimeMillis()
        val found = FoundRelease("o/r", "v9.0.0", "9.0.0", false, null, "", FileRef(1, "Robot-9.0.0.apk", 1), FileRef(2, "SHA256SUMS", 1))
        assertEquals("Robot 9.0.0 is available", updatesSummary(UpdateSettings(), found, now, now))
        assertEquals("Checks once a day", updatesSummary(UpdateSettings(), null, 0, now))
        assertTrue(updatesSummary(UpdateSettings(), null, now, now).startsWith("Checked today at "))
        assertEquals("Automatic checks are off", updatesSummary(UpdateSettings(autoCheck = false), null, 0, now))
        assertTrue(updatesSummary(UpdateSettings(autoCheck = false), null, now, now).endsWith(" · automatic checks off"))
        // A skipped version isn't announced there either.
        assertEquals("Checks once a day", updatesSummary(UpdateSettings(skipped = "9.0.0"), found, 0, now))
    }
}

/**
 * The jobs' service runs the daily check and the download on Wi-Fi, sometimes at once: Android stopping one stops that one's
 * work only, and a job's work that ends forgets only its own stop signal.
 */
class JobAbortsTest {
    @Test fun stoppingOneJobLeavesTheOtherRunning() {
        val jobs = JobAborts()
        val check = jobs.start(UpdateJobs.CHECK)
        val download = jobs.start(UpdateJobs.DOWNLOAD)
        jobs.stop(UpdateJobs.CHECK)
        assertTrue(check.cancelled)
        assertFalse(download.cancelled)
        // The check's thread ends; the download is still stopped by its own id, not by a stale one.
        jobs.done(UpdateJobs.CHECK, check)
        jobs.stop(UpdateJobs.DOWNLOAD)
        assertTrue(download.cancelled)
    }

    @Test fun aJobStartedAgainReplacesItsOldSignal() {
        val jobs = JobAborts()
        val first = jobs.start(UpdateJobs.DOWNLOAD)
        val second = jobs.start(UpdateJobs.DOWNLOAD)
        // The old run is told to stop; the old thread ending doesn't forget the new run's signal.
        assertTrue(first.cancelled)
        jobs.done(UpdateJobs.DOWNLOAD, first)
        assertFalse(second.cancelled)
        jobs.stop(UpdateJobs.DOWNLOAD)
        assertTrue(second.cancelled)
        // Stopping a job that isn't running does nothing.
        jobs.stop(12345)
    }
}
