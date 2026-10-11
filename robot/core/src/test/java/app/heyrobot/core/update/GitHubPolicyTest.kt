package app.heyrobot.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The repository, the hosts and redirects Robot follows, where the token may go, and GitHub's errors. */
class GitHubPolicyTest {
    @Test fun repositoriesAsTypedOrPasted() {
        val r = GitHubRepo("picocryptfan", "robot-gos-assistant")
        for (t in listOf("picocryptfan/robot-gos-assistant", " picocryptfan/robot-gos-assistant ", "https://github.com/picocryptfan/robot-gos-assistant",
            "https://github.com/picocryptfan/robot-gos-assistant/", "github.com/picocryptfan/robot-gos-assistant.git", "HTTPS://GitHub.com/picocryptfan/robot-gos-assistant",
            "http://www.github.com/picocryptfan/robot-gos-assistant")) {
            assertEquals(t, r, GitHubRepo.parse(t))
        }
        assertEquals("a-b/c.d_e-1", GitHubRepo.parse("a-b/c.d_e-1")!!.slug)
    }

    @Test fun notRepositories() {
        for (t in listOf(null, "", "robot", "/robot", "owner/", "owner/name/extra", "owner//name", "-owner/name", "owner-/name", "ow--ner/name",
            "owner/..", "owner/.", "own er/name", "owner/na me", "owner/name?x=1", "owner/name#a", "o".repeat(40) + "/name", "owner/" + "n".repeat(101),
            "https://gitlab.com/owner/name", "https://github.com.evil.com/owner/name", "../../etc/passwd", "owner/na\nme",
            // Look-alikes: Cyrillic "о" in the owner, a full-width slash.
            "оwner/name", "owner／name", "owner/namе")) {
            assertNull(t.toString(), GitHubRepo.parse(t))
        }
    }

    @Test fun theDefaultComesFromTheLicencesSourceLinkOnly() {
        assertEquals(GitHubRepo("picocryptfan", "robot-gos-assistant"), GitHubRepo.fromSourceLink("https://github.com/picocryptfan/robot-gos-assistant"))
        assertNull(GitHubRepo.fromSourceLink("https://gitlab.com/picocryptfan/robot-gos-assistant"))
        assertNull(GitHubRepo.fromSourceLink("http://github.com/picocryptfan/robot-gos-assistant"))
        assertNull(GitHubRepo.fromSourceLink("picocryptfan/robot-gos-assistant"))
        assertNull(GitHubRepo.fromSourceLink(null))
    }

    @Test fun theUrlsAreBuiltHereFromTheRepositoryAndIds() {
        val r = GitHubRepo("o", "r")
        assertEquals("https://api.github.com/repos/o/r/releases?per_page=20", r.releasesUrl())
        assertEquals("https://api.github.com/repos/o/r/releases/assets/123", r.assetUrl(123))
        assertEquals("https://github.com/o/r/releases/tag/v0.4.6", r.releasePage("v0.4.6"))
        assertEquals("https://github.com/o/r/releases/tag/a%2F..%2Fb%3Fx%3D1%20c", r.releasePage("a/../b?x=1 c"))
        try { r.assetUrl(0); throw AssertionError("an id of 0") } catch (_: IllegalArgumentException) {}
        try { GitHubRepo("o/x", "r"); throw AssertionError("a slash in the owner") } catch (_: IllegalArgumentException) {}
    }

    @Test fun onlyTheAllowedHostsOverHttps() {
        assertEquals(setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), GitHubHosts.ALLOWED)
        for (u in listOf("https://api.github.com/repos/o/r/releases", "https://API.GitHub.com/x", "https://github.com/o/r", "https://api.github.com:443/x",
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/1?X-Amz-Signature=abc",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=x%2By")) {
            assertTrue(u, GitHubHosts.allowed(u))
        }
        for (u in listOf("http://api.github.com/x", "ftp://api.github.com/x", "https://api.github.com:8443/x", "https://user@api.github.com/x",
            "https://user:pw@api.github.com/x", "https://api.github.com.evil.com/x", "https://evil.com/api.github.com", "https://evil.com#@api.github.com",
            "https://api.github.com./x", "https://raw.githubusercontent.com/o/r/main/x", "https://codeload.github.com/o/r", "https://gist.github.com/x",
            "https://xn--pi-github-xyz.com/x", "https://api.githuЬ.com/x", "https://аpi.github.com/x", "//api.github.com/x", "api.github.com/x",
            "https://api.github.com\\@evil.com/", "javascript:alert(1)", "", "https://")) {
            assertFalse(u, GitHubHosts.allowed(u))
        }
    }

    @Test fun theTokenGoesOnlyToTheApi() {
        assertTrue(GitHubHosts.mayAuthorize("https://api.github.com/repos/o/r/releases"))
        assertTrue(GitHubHosts.mayAuthorize("https://api.github.com/repos/o/r/releases/assets/1"))
        for (u in listOf("https://github.com/o/r", "https://objects.githubusercontent.com/x", "https://release-assets.githubusercontent.com/x",
            "http://api.github.com/x", "https://api.github.com.evil.com/x", "https://user@api.github.com/x", "https://api.github.com:444/x")) {
            assertFalse(u, GitHubHosts.mayAuthorize(u))
        }
    }

    @Test fun redirectsToTheDownloadServersAreFollowedWithoutTheToken() {
        val api = "https://api.github.com/repos/o/r/releases/assets/7"
        assertEquals(Hop.Follow("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=a", false),
            GitHubHosts.redirect(api, "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=a", 1))
        assertEquals(Hop.Follow("https://objects.githubusercontent.com/x?X-Amz-Signature=s", false),
            GitHubHosts.redirect(api, "https://objects.githubusercontent.com/x?X-Amz-Signature=s", 1))
        // A renamed repository: GitHub's API redirects to itself, and the token goes along.
        assertEquals(Hop.Follow("https://api.github.com/repositories/42/releases/assets/7", true), GitHubHosts.redirect(api, "/repositories/42/releases/assets/7", 1))
        assertEquals(Hop.Follow("https://github.com/o/r2", false), GitHubHosts.redirect(api, "https://github.com/o/r2", 2))
    }

    @Test fun redirectsElsewhereOrTooManyAreRefused() {
        val api = "https://api.github.com/repos/o/r/releases/assets/7"
        assertEquals(Hop.Refuse(RedirectRefusal.NO_LOCATION), GitHubHosts.redirect(api, null, 1))
        assertEquals(Hop.Refuse(RedirectRefusal.NO_LOCATION), GitHubHosts.redirect(api, "  ", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.NOT_HTTPS), GitHubHosts.redirect(api, "http://objects.githubusercontent.com/x", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.HOST_NOT_ALLOWED), GitHubHosts.redirect(api, "https://evil.example/x", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.HOST_NOT_ALLOWED), GitHubHosts.redirect(api, "https://objects.githubusercontent.com.evil.example/x", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.HOST_NOT_ALLOWED), GitHubHosts.redirect(api, "https://user@objects.githubusercontent.com/x", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.HOST_NOT_ALLOWED), GitHubHosts.redirect(api, "//evil.example/x", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.MALFORMED), GitHubHosts.redirect(api, "https://objects.githubusercontent.com/a b", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.MALFORMED), GitHubHosts.redirect(api, "https://objects.githubusercontent.com\\@evil/", 1))
        assertEquals(Hop.Refuse(RedirectRefusal.NOT_HTTPS), GitHubHosts.redirect(api, "file:///data/data/app.heyrobot/files/x", 1))
        assertTrue(GitHubHosts.redirect(api, "https://objects.githubusercontent.com/x", GitHubHosts.MAX_REDIRECTS) is Hop.Follow)
        assertEquals(Hop.Refuse(RedirectRefusal.TOO_MANY), GitHubHosts.redirect(api, "https://objects.githubusercontent.com/x", GitHubHosts.MAX_REDIRECTS + 1))
        assertEquals(5, GitHubHosts.MAX_REDIRECTS)
    }

    @Test fun theUserAgentNamesRobotAndItsVersionOnly() {
        assertEquals("Robot/0.4.5", GitHubHosts.userAgent("0.4.5"))
        assertEquals("Robot/0.6.0-rc1", GitHubHosts.userAgent("0.6.0-rc1"))
        assertEquals("Robot/0.4.5Xy", GitHubHosts.userAgent("0.4.5\r\nX: y"))
    }

    @Test fun githubsErrors() {
        val now = 1_000_000_000_000L
        fun h(vararg kv: Pair<String, String>): (String) -> String? = { k -> kv.firstOrNull { it.first.equals(k, true) }?.second }
        assertEquals(UpdateFail.RATE_LIMITED to 1_700_000_000_000L, GitHubErrors.classify(403, false, h("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1700000000"), now))
        assertEquals(UpdateFail.RATE_LIMITED to now + 60_000, GitHubErrors.classify(429, false, h("Retry-After" to "60"), now))
        assertEquals(UpdateFail.RATE_LIMITED to now + 30_000, GitHubErrors.classify(403, true, h("retry-after" to "30"), now))
        assertEquals(UpdateFail.RATE_LIMITED to null, GitHubErrors.classify(429, false, h(), now))
        // A reset time that makes no sense is left out rather than shown.
        assertEquals(UpdateFail.RATE_LIMITED to null, GitHubErrors.classify(403, false, h("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "-5"), now))
        assertEquals(UpdateFail.FORBIDDEN to null, GitHubErrors.classify(403, true, h("x-ratelimit-remaining" to "4999"), now))
        assertEquals(UpdateFail.NOT_FOUND_NO_TOKEN to null, GitHubErrors.classify(404, false, h(), now))
        assertEquals(UpdateFail.NOT_FOUND to null, GitHubErrors.classify(404, true, h(), now))
        assertEquals(UpdateFail.TOKEN_REFUSED to null, GitHubErrors.classify(401, true, h(), now))
        assertEquals(UpdateFail.SERVICE to null, GitHubErrors.classify(502, false, h(), now))
        assertEquals(UpdateFail.SERVICE to null, GitHubErrors.classify(500, true, h(), now))
    }

    @Test fun tokens() {
        assertTrue(GitHubToken.plausible("github_pat_" + "A1b2_".repeat(16) + "xy"))
        assertTrue(GitHubToken.plausible("ghp_" + "a1B2".repeat(9)))
        assertTrue(GitHubToken.plausible("0123456789abcdef0123456789abcdef01234567"))
        for (t in listOf("", "ghp_short", "github_pat_" + "a".repeat(300), "ghp_" + "a".repeat(35) + "\n", "ghp_" + "a".repeat(35) + " ",
            "Bearer ghp_" + "a".repeat(36), "ghp_" + "а".repeat(36), "ghp_" + "a".repeat(30) + "\r\nX: y", "sk-ant-" + "a".repeat(40),
            "xyz_" + "a".repeat(40), "0123456789ABCDEF0123456789ABCDEF01234567")) {
            assertFalse(t, GitHubToken.plausible(t))
        }
        assertEquals("github_pat_…WXYZ", GitHubToken.mask("github_pat_" + "a".repeat(40) + "WXYZ"))
        assertEquals("ghp_…1234", GitHubToken.mask("ghp_" + "a".repeat(32) + "1234"))
        assertEquals("…4567", GitHubToken.mask("0123456789abcdef0123456789abcdef01234567"))
    }
}

/** A download that continues: only the exact rest of the file counts. */
class ByteRangeTest {
    @Test fun theRestOfTheFileExactly() {
        assertEquals("bytes=100-", ByteRange.request(100))
        assertTrue(ByteRange.continues("bytes 100-999/1000", 100, 1000))
        assertTrue(ByteRange.continues("bytes 100-999/*", 100, 1000))
        for (h in listOf(null, "", "bytes 0-999/1000", "bytes 100-998/1000", "bytes 100-999/1001", "bytes 99-999/1000", "bytes */1000",
            "items 100-999/1000", "bytes 100-999/1000, 0-1", "bytes -100-999/1000", "bytes 100-999/１０００")) {
            assertFalse(h.toString(), ByteRange.continues(h, 100, 1000))
        }
    }
}
