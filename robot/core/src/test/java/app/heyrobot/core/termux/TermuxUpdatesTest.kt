// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.update.GitHubHosts
import app.heyrobot.core.update.UpdateFail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0, "Check for Termux updates": the two fixed addresses, the two parsers on recorded answers, and what Settings › Termux
 * says for each source. resources/termux/fdroid_com.termux.json is F-Droid's answer as recorded on 12 Oct 2026;
 * github_releases.json is GitHub's releases answer in its format for Termux's newest five releases (tags and dates as
 * termux/termux-app's tags have them; bodies and assets shortened).
 */
class TermuxUpdatesTest {
    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("termux/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val day = 24L * 3600 * 1000

    @Test fun onlyTwoFixedAddresses() {
        assertEquals("https://api.github.com/repos/termux/termux-app/releases?per_page=5", TermuxUpdates.GITHUB_URL)
        assertEquals("https://f-droid.org/api/v1/packages/com.termux", TermuxUpdates.FDROID_URL)
        assertEquals(setOf(TermuxUpdates.GITHUB_URL, TermuxUpdates.FDROID_URL), TermuxUpdates.URLS)
        assertTrue(TermuxUpdates.URLS.all { it.startsWith("https://") })
        for (bad in listOf("https://f-droid.org/api/v1/packages/com.termux?x=1", "http://f-droid.org/api/v1/packages/com.termux",
            "https://api.github.com/repos/termux/termux-app/releases", "https://f-droid.org.evil.example/api/v1/packages/com.termux",
            "https://api.github.com/repos/termux/termux-app/releases/assets/1")) assertFalse(bad, TermuxUpdates.allowed(bad))
        // The GitHub request is a public one: the updater's token never goes with it (GitHubClient passes none), and its
        // User-Agent is Robot's ("Robot/1.0.0"), as DownloadPolicy's.
        assertEquals("Robot/0.9.0", GitHubHosts.userAgent("0.9.0"))
        assertEquals(7L * day, TermuxUpdates.WEEK_MS)
    }

    @Test fun fdroidsRecordedAnswer() {
        val f = TermuxUpdates.parseFdroid(sample("fdroid_com.termux.json"))!!
        assertEquals(1002L, f.suggestedCode)
        assertEquals("0.118.3", f.suggested)
        assertEquals(1022L, f.newestCode)
        assertEquals("0.119.0-beta.3", f.newest)
        // Not F-Droid's answer for Termux, or not an answer: nothing.
        assertNull(TermuxUpdates.parseFdroid("""{"packageName":"com.evil","suggestedVersionCode":5,"packages":[]}"""))
        assertNull(TermuxUpdates.parseFdroid("""{"packageName":"com.termux"}"""))
        assertNull(TermuxUpdates.parseFdroid("<html>"))
        assertNull(TermuxUpdates.parseFdroid(null))
        assertNull(TermuxUpdates.parseFdroid("{\"packageName\":\"com.termux\",\"x\":\"" + "a".repeat(70_000) + "\"}"))
        // A version name that isn't one is left out; the suggested code still counts.
        val odd = TermuxUpdates.parseFdroid("""{"packageName":"com.termux","suggestedVersionCode":1002,"packages":[{"versionName":"<b>","versionCode":1002}]}""")!!
        assertEquals(1002L, odd.suggestedCode)
        assertNull(odd.suggested)
    }

    @Test fun githubsReleasesAnswer() {
        val g = TermuxUpdates.parseGitHub(sample("github_releases.json"))!!
        // The newest release, not a test version; the newer test version beside it.
        assertEquals("0.118.3", g.version)
        assertEquals(TermuxUpdates.time("2025-05-22T21:53:15Z"), g.publishedAt)
        assertEquals("0.119.0-beta.3", g.testVersion)
        assertEquals(setOf("0.119.0-beta.3", "0.118.3", "0.119.0-beta.2", "0.118.2", "0.119.0-beta.1"), g.dates.keys)
        assertEquals(TermuxUpdates.time("2025-03-29T04:27:24Z"), g.dates["0.118.2"])
        assertNull(TermuxUpdates.parseGitHub("""{"message":"API rate limit exceeded"}"""))
        assertNull(TermuxUpdates.parseGitHub("[]"))
        assertNull(TermuxUpdates.parseGitHub("""[{"tag_name":"v9.9","draft":true,"prerelease":false}]"""))
        assertEquals(0L, TermuxUpdates.time("yesterday"))
    }

    private val state = TermuxUpdateState(
        checkedAt = 1, github = TermuxUpdates.parseGitHub(sample("github_releases.json")), fdroid = TermuxUpdates.parseFdroid(sample("fdroid_com.termux.json")))

    private fun pkg(version: String, code: Long, at: Long = TermuxUpdates.time("2025-06-01T00:00:00Z")) =
        TermuxPackage(true, version, listOf(TermuxIdentity.F_DROID), true, "com.termux", code, at)

    @Test fun whatEachBuildIsTold() {
        // F-Droid's 0.118.3: up to date (the betas are test versions).
        assertEquals(TermuxNews(TermuxUpdateVerdict.UP_TO_DATE, null), TermuxUpdates.news(pkg("0.118.3", 1002), TermuxSource.F_DROID, state))
        // F-Droid's 0.118.2: F-Droid has 0.118.3 for it.
        assertEquals(TermuxNews(TermuxUpdateVerdict.AVAILABLE, "0.118.3"), TermuxUpdates.news(pkg("0.118.2", 1001), TermuxSource.F_DROID, state))
        // The developers released 0.118.4, F-Droid hasn't built it yet.
        val ahead = state.copy(github = state.github!!.copy(version = "0.118.4", dates = state.github!!.dates + ("0.118.4" to 1L)))
        assertEquals(TermuxNews(TermuxUpdateVerdict.NOT_ON_FDROID_YET, "0.118.4"), TermuxUpdates.news(pkg("0.118.3", 1002), TermuxSource.F_DROID, ahead))
        // A GitHub build compares with the developers' release.
        assertEquals(TermuxNews(TermuxUpdateVerdict.AVAILABLE, "0.118.3"), TermuxUpdates.news(pkg("0.118.2", 1001), TermuxSource.GITHUB, state))
        assertEquals(TermuxUpdateVerdict.UP_TO_DATE, TermuxUpdates.news(pkg("0.119.0-beta.3", 1022), TermuxSource.GITHUB, state).verdict)
        // Never checked, failed, not installed.
        assertEquals(TermuxUpdateVerdict.NOT_CHECKED, TermuxUpdates.news(pkg("0.118.3", 1002), TermuxSource.F_DROID, TermuxUpdateState()).verdict)
        assertEquals(TermuxUpdateVerdict.FAILED, TermuxUpdates.news(pkg("0.118.3", 1002), TermuxSource.F_DROID,
            TermuxUpdateState(checkedAt = 5, githubFailure = UpdateFail.NO_INTERNET, fdroidFailure = UpdateFail.NO_INTERNET)).verdict)
        assertEquals(TermuxUpdateVerdict.UNKNOWN, TermuxUpdates.news(TermuxPackage(false), null, state).verdict)
    }

    @Test fun aYearBehindIsSaid() {
        // 0.118.1 (June 2024) against 0.118.3 (May 2025): not listed among the five, so its install time dates it.
        val old = pkg("0.118.1", 1000, at = TermuxUpdates.time("2024-06-20T00:00:00Z"))
        assertEquals(TermuxNews(TermuxUpdateVerdict.AVAILABLE, "0.118.3"), TermuxUpdates.news(old, TermuxSource.F_DROID, state))
        val older = pkg("0.118.0", 118, at = TermuxUpdates.time("2024-01-10T00:00:00Z"))
        assertEquals(TermuxNews(TermuxUpdateVerdict.FAR_BEHIND, "0.118.3"), TermuxUpdates.news(older, TermuxSource.F_DROID, state))
        // A listed version is dated by its release, whenever it was installed.
        val listed = TermuxUpdateState(1, GitHubLatest("0.119.0", TermuxUpdates.time("2026-05-01T00:00:00Z"),
            dates = mapOf("0.119.0" to TermuxUpdates.time("2026-05-01T00:00:00Z"), "0.118.2" to TermuxUpdates.time("2025-03-29T04:27:24Z"))))
        assertEquals(TermuxUpdateVerdict.FAR_BEHIND, TermuxUpdates.news(pkg("0.118.2", 1001, at = TermuxUpdates.time("2026-04-30T00:00:00Z")),
            TermuxSource.GITHUB, listed).verdict)
    }

    @Test fun theStateKeepsInItsFile() {
        val s = state.copy(checkedAt = 1_760_000_000_000, fdroidFailure = UpdateFail.RATE_LIMITED)
        assertEquals(s, TermuxUpdateState.parse(s.toJson().toString()))
        assertEquals(TermuxUpdateState(), TermuxUpdateState.parse("not json"))
        assertEquals(TermuxUpdateState(), TermuxUpdateState.parse(null))
        assertEquals("termux_updates.json", TermuxUpdateState.FILE)
        assertNotNull(TermuxUpdateState.parse(s.toJson().toString()).github?.version)
    }
}
