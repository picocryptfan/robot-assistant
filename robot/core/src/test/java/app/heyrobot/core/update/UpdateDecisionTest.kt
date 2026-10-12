package app.heyrobot.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which release is offered, what the daily check does with it, when a download is kept, and the APK's checks. */
class UpdateDecisionTest {
    private fun v(s: String) = RobotVersion.parse(s)!!
    private fun releases(vararg r: org.json.JSONObject) = GitHubReleases.parseList(Fixtures.list(*r))!!
    private val current = v("0.4.5")

    @Test fun theNewestStableReleaseAboveTheInstalledOne() {
        val list = releases(Fixtures.release("v0.4.7", baseId = 300), Fixtures.release("v0.4.6", baseId = 200), Fixtures.release("v0.4.5", baseId = 100))
        val c = UpdatePick.candidate(list, current, testBuilds = false)!!
        assertEquals(v("0.4.7"), c.version)
        assertEquals("Robot-0.4.7.apk", c.files.apk.name)
        assertEquals(301L, c.files.apk.id)
        assertEquals(302L, c.files.sums.id)
        // GitHub's order doesn't matter: the highest version wins.
        assertEquals(v("0.6.0"), UpdatePick.candidate(releases(Fixtures.release("v0.4.9"), Fixtures.release("v0.6.0"), Fixtures.release("v0.5.0")), current, false)!!.version)
    }

    @Test fun upToDate() {
        assertNull(UpdatePick.candidate(emptyList(), current, false))
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.4.5"), Fixtures.release("v0.4.4"), Fixtures.release("v0.3.0")), current, false))
        // The installed version itself is never offered again, nor an older one.
        assertNull(UpdatePick.candidate(releases(Fixtures.release("0.4.5")), current, true))
    }

    @Test fun testBuildsOnlyWhenAskedFor() {
        val list = releases(Fixtures.release("v0.6.0-rc1", prerelease = true), Fixtures.release("v0.4.6"))
        assertEquals(v("0.4.6"), UpdatePick.candidate(list, current, testBuilds = false)!!.version)
        assertEquals(v("0.6.0-rc1"), UpdatePick.candidate(list, current, testBuilds = true)!!.version)
        // A pre-release version is a test build even if GitHub doesn't mark it so; a marked one even with a plain version.
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.6.0-rc2", prerelease = false)), current, false))
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.6.0", prerelease = true)), current, false))
        assertEquals(v("0.6.0"), UpdatePick.candidate(releases(Fixtures.release("v0.6.0", prerelease = true)), current, true)!!.version)
        // The release after its test builds is newer than they are.
        assertEquals(v("0.6.0"), UpdatePick.candidate(releases(Fixtures.release("v0.6.0-rc3", prerelease = true), Fixtures.release("v0.6.0")), current, true)!!.version)
        // Someone on a test build gets its release when it comes.
        assertEquals(v("0.6.0"), UpdatePick.candidate(releases(Fixtures.release("v0.6.0")), v("0.6.0-rc1"), false)!!.version)
    }

    @Test fun draftsAndReleasesWithoutTheirFilesAreNeverOffered() {
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.4.6", draft = true)), current, true))
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.4.6", apk = false)), current, true))
        assertNull(UpdatePick.candidate(releases(Fixtures.release("v0.4.6", sums = false)), current, true))
        assertNull(UpdatePick.candidate(releases(Fixtures.release("nightly")), current, true))
        // One without its files doesn't hide an older one that has them.
        assertEquals(v("0.4.6"), UpdatePick.candidate(releases(Fixtures.release("v0.4.7", sums = false), Fixtures.release("v0.4.6")), current, false)!!.version)
    }

    @Test fun aReleaseWhoseApkFailedAndroidsChecksDoesntHideAnOlderGoodOne() {
        // "v9.9.9" whose APK matched SHA256SUMS but isn't signed with Robot's key (a stolen GitHub account, or a typo in a tag):
        // without this, the highest version would be offered, refused and offered again, and no update would ever come.
        val list = releases(Fixtures.release("v9.9.9", baseId = 900), Fixtures.release("v0.4.7", baseId = 300), Fixtures.release("v0.4.6", baseId = 200))
        assertEquals(v("9.9.9"), UpdatePick.candidate(list, current, false)!!.version)
        assertEquals(v("0.4.7"), UpdatePick.candidate(list, current, false, barred = setOf(901L))!!.version)
        assertEquals(v("0.4.6"), UpdatePick.candidate(list, current, false, barred = setOf(901L, 301L))!!.version)
        assertNull(UpdatePick.candidate(list, current, false, barred = setOf(901L, 301L, 201L)))
        // The same version with its APK replaced (a new file id) is offered again.
        assertEquals(v("9.9.9"), UpdatePick.candidate(releases(Fixtures.release("v9.9.9", baseId = 950)), current, false, barred = setOf(901L))!!.version)
    }

    @Test fun whichFilesStayBarred() {
        // Only Android's checks bar a file: they ran on the bytes SHA256SUMS vouched for, so they would fail again. A wrong
        // sum, a cut download or no space can be different next time.
        for (f in UpdateFail.entries) {
            val archive = f in setOf(UpdateFail.NOT_AN_APK, UpdateFail.WRONG_PACKAGE, UpdateFail.WRONG_VERSION, UpdateFail.NOT_NEWER, UpdateFail.UNSIGNED, UpdateFail.OTHER_SIGNER)
            assertEquals(f.name, archive, UpdateDecision.bars(f))
        }
        assertEquals(listOf(1L, 2L, 3L), UpdateDecision.bar(listOf(1L, 2L), 3L))
        assertEquals(listOf(1L, 2L), UpdateDecision.bar(listOf(2L, 1L), 2L))
        assertEquals(UpdateDecision.MAX_BARRED, (1L..100L).fold(emptyList<Long>()) { b, id -> UpdateDecision.bar(b, id) }.size)
        // Once GitHub no longer lists a barred file it is forgotten, so the list never grows.
        val list = releases(Fixtures.release("v9.9.9", baseId = 900), Fixtures.release("v0.4.6", baseId = 200))
        assertEquals(listOf(901L), UpdateDecision.stillBarred(listOf(555L, 901L), list))
        assertEquals(emptyList<Long>(), UpdateDecision.stillBarred(listOf(555L), list))
    }

    @Test fun whichDownloadFailuresRunTheWifiDownloadAgainSoon() {
        // Stopped, cut off, or the token unreadable just now (it is kept): the next try may work. A refused file, a full
        // phone or a missing repository wait for the next daily check or for you.
        for (f in UpdateFail.entries) {
            assertEquals(f.name, f in setOf(UpdateFail.STOPPED, UpdateFail.NO_INTERNET, UpdateFail.TOKEN_UNAVAILABLE), UpdateDecision.downloadAgain(f))
        }
        assertFalse(UpdateDecision.downloadAgain(null))
        // A token that couldn't be read just now never bars the release's file.
        assertFalse(UpdateDecision.bars(UpdateFail.TOKEN_UNAVAILABLE))
    }

    private val on = UpdateSettings()
    private val noWifiDownload = UpdateSettings(wifiDownload = false)
    private val now = 1_800_000_000_000L

    @Test fun theDailyCheckDecisionTable() {
        val v6 = v("0.4.6")
        // Nothing newer: nothing to say or do.
        assertEquals(AutoPlan(null, false), UpdateDecision.afterAutoCheck(on, null, false, null, 0, now))
        // Download on Wi-Fi (the default): no notice until the file is downloaded and checked...
        assertEquals(AutoPlan(null, true), UpdateDecision.afterAutoCheck(on, v6, false, null, now, now))
        // ... then "ready to install", once.
        assertEquals(Notice.READY, UpdateDecision.afterAutoDownload(on, v6, null))
        assertNull(UpdateDecision.afterAutoDownload(on, v6, "0.4.6 ready"))
        assertEquals(AutoPlan(Notice.READY, false), UpdateDecision.afterAutoCheck(on, v6, true, null, now, now))
        assertEquals(AutoPlan(null, false), UpdateDecision.afterAutoCheck(on, v6, true, "0.4.6 ready", now, now))
        // Two days without Wi-Fi: announced anyway (still downloading when Wi-Fi comes), once.
        assertEquals(AutoPlan(Notice.AVAILABLE, true), UpdateDecision.afterAutoCheck(on, v6, false, null, now - UpdateDecision.WIFI_WAIT_MS, now))
        assertEquals(AutoPlan(null, true), UpdateDecision.afterAutoCheck(on, v6, false, "0.4.6 available", now - UpdateDecision.WIFI_WAIT_MS, now))
        assertEquals(AutoPlan(null, true), UpdateDecision.afterAutoCheck(on, v6, false, null, now - UpdateDecision.WIFI_WAIT_MS + 1, now))
        // Without "Download updates on Wi-Fi": "available" at once, once, and no download.
        assertEquals(AutoPlan(Notice.AVAILABLE, false), UpdateDecision.afterAutoCheck(noWifiDownload, v6, false, null, now, now))
        assertEquals(AutoPlan(null, false), UpdateDecision.afterAutoCheck(noWifiDownload, v6, false, "0.4.6 available", now, now))
        // A newer version is new news.
        assertEquals(AutoPlan(Notice.AVAILABLE, false), UpdateDecision.afterAutoCheck(noWifiDownload, v("0.4.7"), false, "0.4.6 available", now, now))
        // A skipped version: never announced or downloaded; the next one is.
        val skip6 = on.copy(skipped = "0.4.6")
        for (downloaded in listOf(true, false)) assertEquals(AutoPlan(null, false), UpdateDecision.afterAutoCheck(skip6, v6, downloaded, null, 0, now))
        assertNull(UpdateDecision.afterAutoDownload(skip6, v6, null))
        assertEquals(AutoPlan(null, true), UpdateDecision.afterAutoCheck(skip6, v("0.4.7"), false, null, now, now))
        assertEquals(AutoPlan(Notice.AVAILABLE, false), UpdateDecision.afterAutoCheck(skip6.copy(wifiDownload = false), v("0.4.7"), false, null, now, now))
        // A file that failed its checks (a wrong sum, another signer) isn't downloaded again by itself: announced instead.
        assertEquals(AutoPlan(Notice.AVAILABLE, false), UpdateDecision.afterAutoCheck(on, v6, false, null, now, now, refused = true))
        assertEquals(AutoPlan(null, false), UpdateDecision.afterAutoCheck(on, v6, false, "0.4.6 available", now, now, refused = true))
        // "v0.4.6" skipped is the same as "0.4.6".
        assertTrue(UpdateDecision.skipped(UpdateSettings(skipped = "0.4.6"), v("v0.4.6")))
        assertFalse(UpdateDecision.skipped(UpdateSettings(), v6))
    }

    @Test fun automaticChecksOnlyWhileTheSwitchIsOn() {
        assertTrue(UpdateDecision.autoCheckRuns(UpdateSettings()))
        assertFalse(UpdateDecision.autoCheckRuns(UpdateSettings(autoCheck = false)))
    }

    private fun found(version: String = "0.4.6", apkId: Long = 101, size: Long = 97_000_000, repo: String = "o/r") =
        FoundRelease(repo, "v$version", version, false, null, "", FileRef(apkId, "Robot-$version.apk", size), FileRef(apkId + 1, "SHA256SUMS", 250))

    @Test fun aDownloadIsKeptOnlyWhileItIsStillTheOneOffered() {
        val d = VerifiedDownload("0.4.6", "Robot-0.4.6.apk", "a".repeat(64), 97_000_000, 101)
        assertTrue(UpdateDecision.keepDownload(d, found(), current))
        assertFalse(UpdateDecision.keepDownload(null, found(), current))
        assertFalse(UpdateDecision.keepDownload(d, null, current))
        // A newer release appeared, the file was replaced on GitHub, or this version is installed now.
        assertFalse(UpdateDecision.keepDownload(d, found("0.4.7", 201), current))
        assertFalse(UpdateDecision.keepDownload(d, found(apkId = 555), current))
        assertFalse(UpdateDecision.keepDownload(d, found(size = 1), current))
        assertFalse(UpdateDecision.keepDownload(d, found(), v("0.4.6")))
    }

    @Test fun whatThePageOffers() {
        val repo = GitHubRepo("o", "r")
        val s = UpdateState(found = found())
        assertEquals(found(), UpdateDecision.offered(s, repo, current))
        assertNull(UpdateDecision.offered(s, GitHubRepo("fork", "r"), current))
        assertNull(UpdateDecision.offered(s, null, current))
        assertNull(UpdateDecision.offered(s, repo, v("0.4.6")))
        assertNull(UpdateDecision.offered(UpdateState(), repo, current))
    }

    @Test fun spaceForTheRestOfTheFileAndAndroidsCopy() {
        assertEquals(100L + 100 + UpdateDecision.SPACE_MARGIN, UpdateDecision.spaceNeeded(100, 0))
        assertEquals(40L + 100 + UpdateDecision.SPACE_MARGIN, UpdateDecision.spaceNeeded(100, 60))
        assertEquals(100L + UpdateDecision.SPACE_MARGIN, UpdateDecision.spaceNeeded(100, 150))
    }

    private val robotKey = "11".repeat(32)
    private val oldKey = "22".repeat(32)
    private val otherKey = "33".repeat(32)
    private val installed = ApkFacts("ai.robot_app", 45, "0.4.5", listOf(robotKey), listOf(robotKey), false)
    private fun apk(pkg: String? = "ai.robot_app", code: Long = 46, name: String? = "0.4.6", signers: List<String> = listOf(robotKey),
                    lineage: List<String> = listOf(robotKey), multiple: Boolean = false) = ApkFacts(pkg, code, name, signers, lineage, multiple)

    @Test fun theApksChecks() {
        val v6 = v("0.4.6")
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(installed, apk(), v6))
        assertEquals(ArchiveVerdict.NOT_AN_APK, ArchiveCheck.check(installed, null, v6))
        assertEquals(ArchiveVerdict.NOT_AN_APK, ArchiveCheck.check(installed, apk(pkg = null), v6))
        assertEquals(ArchiveVerdict.WRONG_PACKAGE, ArchiveCheck.check(installed, apk(pkg = "ai.robot_app.debug"), v6))
        // 1.0's app ID; 0.9's (app.heyrobot) is another app, both ways: 0.9 refuses 1.0's APK just so.
        assertEquals("ai.robot_app", ArchiveCheck.PACKAGE)
        assertEquals(ArchiveVerdict.WRONG_PACKAGE, ArchiveCheck.check(installed, apk(pkg = "app.heyrobot"), v6))
        assertEquals(ArchiveVerdict.WRONG_PACKAGE, ArchiveCheck.check(installed.copy(packageName = "app.heyrobot"), apk(), v6))
        assertEquals(ArchiveVerdict.WRONG_PACKAGE, ArchiveCheck.check(installed, apk(pkg = "com.evil"), v6))
        // The APK must be the release's version...
        assertEquals(ArchiveVerdict.WRONG_VERSION, ArchiveCheck.check(installed, apk(name = "0.4.7"), v6))
        assertEquals(ArchiveVerdict.WRONG_VERSION, ArchiveCheck.check(installed, apk(name = null), v6))
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(installed, apk(name = "0.6.0-rc1", code = 59), v("v0.6.0-rc1")))
        // ... with a higher versionCode: never the same again, never a downgrade, whatever the name says.
        assertEquals(ArchiveVerdict.NOT_NEWER, ArchiveCheck.check(installed, apk(code = 45), v6))
        assertEquals(ArchiveVerdict.NOT_NEWER, ArchiveCheck.check(installed, apk(code = 44), v6))
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(installed, apk(code = 1000), v6))
        // Signed by Robot's key, or by a newer key whose lineage holds it (a v3 rotation).
        assertEquals(ArchiveVerdict.UNSIGNED, ArchiveCheck.check(installed, apk(signers = emptyList(), lineage = emptyList()), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(otherKey)), v6))
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(robotKey, otherKey)), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(oldKey, otherKey)), v6))
        // A lineage is a chain ending at the key that signed the APK (each step signed by the key before it, which Android
        // verifies): Robot's key in a list that doesn't end at the APK's own signer proves nothing about that signer.
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(robotKey)), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(otherKey, robotKey)), v6))
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(installed, apk(signers = listOf(otherKey), lineage = listOf(robotKey, oldKey, otherKey)), v6))
        // An installed Robot that rotated already: its current key is what counts, not one it left.
        val rotated = installed.copy(signers = listOf(otherKey), lineage = listOf(robotKey, otherKey))
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(rotated, apk(signers = listOf(otherKey), lineage = listOf(robotKey, otherKey)), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(rotated, apk(signers = listOf(robotKey), lineage = listOf(robotKey)), v6))
        // Several signers: exactly the same set.
        val two = installed.copy(signers = listOf(robotKey, oldKey), lineage = emptyList(), multipleSigners = true)
        assertEquals(ArchiveVerdict.OK, ArchiveCheck.check(two, apk(signers = listOf(oldKey, robotKey), lineage = emptyList(), multiple = true), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(two, apk(signers = listOf(robotKey), lineage = listOf(robotKey)), v6))
        assertEquals(ArchiveVerdict.OTHER_SIGNER, ArchiveCheck.check(installed, apk(signers = listOf(robotKey, otherKey), lineage = emptyList(), multiple = true), v6))
        // Each refusal has its message.
        for (verdict in ArchiveVerdict.entries) assertEquals(verdict == ArchiveVerdict.OK, UpdateFail.of(verdict) == null)
    }

    @Test fun installStatuses() {
        assertEquals(InstallOutcome.SUCCESS, InstallOutcome.of(0))
        assertEquals(InstallOutcome.CANCELLED, InstallOutcome.of(3))
        assertEquals(InstallOutcome.CONFLICT, InstallOutcome.of(5))
        assertEquals(InstallOutcome.STORAGE, InstallOutcome.of(6))
        assertEquals(InstallOutcome.INCOMPATIBLE, InstallOutcome.of(7))
        assertEquals(InstallOutcome.INVALID, InstallOutcome.of(4))
        assertEquals(InstallOutcome.BLOCKED, InstallOutcome.of(2))
        assertEquals(InstallOutcome.TIMEOUT, InstallOutcome.of(8))
        assertEquals(InstallOutcome.FAILED, InstallOutcome.of(1))
        assertEquals(InstallOutcome.FAILED, InstallOutcome.of(99))
        assertEquals(InstallOutcome.FAILED, InstallOutcome.of(-1))
    }
}
