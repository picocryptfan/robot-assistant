package app.heyrobot.core.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** updates.json and update_state.json: their defaults, every field round trip, and forward-compatible reading. */
class UpdateFilesTest {
    @Test fun theDefaultsAreTheSafeOnes() {
        val d = UpdateSettings()
        assertTrue(d.autoCheck)
        assertTrue(d.wifiDownload)
        assertFalse(d.testBuilds)
        assertFalse(d.installWithoutAsking)
        assertNull(d.repo)
        assertNull(d.skipped)
        assertEquals("updates.json", UpdateSettings.FILE)
        assertEquals("update_state.json", UpdateState.FILE)
    }

    @Test fun settingsRoundTrip() {
        for (a in listOf(true, false)) for (w in listOf(true, false)) for (t in listOf(true, false)) for (i in listOf(true, false)) {
            val s = UpdateSettings(a, w, t, i, if (a) "fork/robot" else null, if (w) "0.4.7" else null)
            assertEquals(s, UpdateSettings.fromJson(s.toJson()))
        }
        val o = JSONObject(UpdateSettings(repo = "o/r").toJson())
        assertEquals(1, o.getInt("v"))
        assertEquals("o/r", o.getString("repo"))
        assertFalse(o.has("skipped"))
    }

    @Test fun settingsReadForwardCompatibly() {
        for (t in listOf(null, "", "garbage", "[]", "{", "null")) assertEquals(t.toString(), UpdateSettings(), UpdateSettings.fromJson(t))
        // A newer file: what this version knows is read, the rest ignored.
        assertEquals(UpdateSettings(autoCheck = false, testBuilds = true),
            UpdateSettings.fromJson("""{"v": 9, "autoCheck": false, "testBuilds": true, "channel": "nightly", "mirrors": ["x"]}"""))
        // Wrong types keep the default; an invalid repository or version reads as none.
        assertEquals(UpdateSettings(), UpdateSettings.fromJson("""{"autoCheck": "false", "wifiDownload": 0, "testBuilds": null, "installWithoutAsking": "yes", "repo": 5, "skipped": 1.0}"""))
        assertEquals(UpdateSettings(), UpdateSettings.fromJson("""{"repo": "../../etc", "skipped": "latest"}"""))
        assertEquals(UpdateSettings(repo = "o/r", skipped = "0.4.7"), UpdateSettings.fromJson("""{"repo": "https://github.com/o/r", "skipped": "v0.4.7"}"""))
    }

    @Test fun theRepositoryIsYoursOrTheDefault() {
        val def = GitHubRepo("picocryptfan", "robot-gos-assistant")
        assertEquals(def, UpdateSettings().repoOr(def))
        assertEquals(GitHubRepo("fork", "robot"), UpdateSettings(repo = "fork/robot").repoOr(def))
        assertNull(UpdateSettings().repoOr(null))
    }

    private val full = UpdateState(
        lastCheck = 1_800_000_000_000, outcome = CheckOutcome.FAILED, fail = UpdateFail.RATE_LIMITED, failHttp = 403, retryAt = 1_800_000_360_000,
        found = FoundRelease("o/r", "v0.4.6", "0.4.6", true, "2026-10-11T09:30:00Z", "Notes\n• one", FileRef(101, "Robot-0.4.6.apk", 97_000_000), FileRef(102, "SHA256SUMS", 250)),
        firstSeen = 1_799_000_000_000, downloaded = VerifiedDownload("0.4.6", "Robot-0.4.6.apk", "ab".repeat(32), 97_000_000, 101),
        notified = "0.4.6 ready", updatedTo = "0.4.6", install = InstallOutcome.CANCELLED, installVersion = "0.4.6",
        refusedApk = 99, refusedWhy = UpdateFail.CHECKSUM_MISMATCH,
    )

    @Test fun stateRoundTrips() {
        assertEquals(full, UpdateState.fromJson(full.toJson()))
        assertEquals(UpdateState(), UpdateState.fromJson(UpdateState().toJson()))
        for (o in CheckOutcome.entries) assertEquals(o, UpdateState.fromJson(UpdateState(outcome = o).toJson()).outcome)
        for (f in UpdateFail.entries) assertEquals(f, UpdateState.fromJson(UpdateState(fail = f).toJson()).fail)
        for (i in InstallOutcome.entries) assertEquals(i, UpdateState.fromJson(UpdateState(install = i).toJson()).install)
    }

    @Test fun stateReadsForwardCompatiblyAndDefensively() {
        for (t in listOf(null, "", "{", "[1]", "0")) assertEquals(UpdateState(), UpdateState.fromJson(t))
        val o = JSONObject(full.toJson()).put("v", 7).put("newField", JSONObject().put("x", 1)).put("outcome", "mystery").put("fail", "NEW_KIND").put("install", 3)
        val s = UpdateState.fromJson(o.toString())
        assertNull(s.outcome)
        assertNull(s.fail)
        assertNull(s.install)
        assertEquals(full.found, s.found)
        // A damaged part reads as missing, the rest stays.
        val damaged = JSONObject(full.toJson())
        damaged.getJSONObject("found").put("apk", "x")
        damaged.getJSONObject("downloaded").put("sha256", "not-hex")
        val d = UpdateState.fromJson(damaged.toString())
        assertNull(d.found)
        assertNull(d.downloaded)
        assertEquals(full.lastCheck, d.lastCheck)
        assertEquals(full.notified, d.notified)
    }

    @Test fun aDownloadsFileNameIsOnlyEverItsVersionsApk() {
        // A state file naming another file (a path, another version's) doesn't name a download at all.
        for (f in listOf("../../shared_prefs/x.xml", "/data/data/app.heyrobot/files/robot/settings.json", "Robot-0.4.7.apk", "Robot-0.4.6.apk.part", "")) {
            val o = JSONObject(full.toJson())
            o.getJSONObject("downloaded").put("file", f)
            assertNull(f, UpdateState.fromJson(o.toString()).downloaded)
        }
        val o = JSONObject(full.toJson())
        o.getJSONObject("downloaded").put("version", "../x")
        assertNull(UpdateState.fromJson(o.toString()).downloaded)
        assertEquals("Robot-0.4.6.apk", UpdateFilesNames.apk("0.4.6"))
        assertEquals("Robot-0.6.0-rc1.apk.part", UpdateFilesNames.part("v0.6.0-rc1"))
        try { UpdateFilesNames.apk("../0.4.6"); throw AssertionError("a path") } catch (_: IllegalArgumentException) {}
    }

    @Test fun theNotesAreKeptPlain() {
        val o = JSONObject(full.toJson())
        o.getJSONObject("found").put("notes", "<b>Bold</b> [link](https://evil.example)‮")
        assertEquals("Bold link", UpdateState.fromJson(o.toString()).found!!.notes)
    }

    @Test fun foundFromARelease() {
        val r = GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.6.0-rc1", prerelease = true, body = "**New**")))!!.single()
        val f = FoundRelease.of(GitHubRepo("o", "r"), r, ReleaseAssets.select(r)!!)!!
        assertEquals("o/r", f.repo)
        assertEquals("0.6.0-rc1", f.version)
        assertEquals("v0.6.0-rc1", f.tag)
        assertTrue(f.testBuild)
        assertEquals("New", f.notes)
        assertEquals(FileRef(101, "Robot-0.6.0-rc1.apk", 97_000_000), f.apk)
        assertEquals(FileRef(102, "SHA256SUMS", 250), f.sums)
    }
}
