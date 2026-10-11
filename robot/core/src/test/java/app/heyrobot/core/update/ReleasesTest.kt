package app.heyrobot.core.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub's release answers (their real shape, then hostile ones), the files a release needs, SHA256SUMS and the notes. */
class ReleasesTest {
    @Test fun githubsRealShape() {
        val list = GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.4.6", body = "## What's new\n- Updates")))!!
        val r = list.single()
        assertEquals("v0.4.6", r.tag)
        assertEquals("Robot 0.4.6", r.name)
        assertEquals("## What's new\n- Updates", r.body)
        assertFalse(r.draft)
        assertFalse(r.prerelease)
        assertFalse(r.testBuild)
        assertEquals("2026-10-11T09:30:00Z", r.publishedAt)
        assertEquals(RobotVersion.parse("0.4.6"), r.version)
        val apk = r.assets.first { it.name == "Robot-0.4.6.apk" }
        assertEquals(101L, apk.id)
        assertEquals(97_000_000L, apk.size)
        assertEquals("https://github.com/o/r/releases/download/v0.4.6/Robot-0.4.6.apk", apk.browserDownloadUrl)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/101", apk.url)
        assertEquals(setOf("Robot-0.4.6.apk", "SHA256SUMS", "robot-0.4.6-third-party-sources.tar.gz", "robot-0.4.6-third-party-sources.tar.gz.sha256"), r.assets.map { it.name }.toSet())
        // /releases/latest answers one object.
        assertEquals(r, GitHubReleases.parseOne(Fixtures.release("v0.4.6", body = "## What's new\n- Updates").toString()))
        // An empty repository: no releases, which is not an error.
        assertEquals(emptyList<GitHubRelease>(), GitHubReleases.parseList("[]"))
    }

    @Test fun aWrongTypeOrAMissingFieldNeverReadsAsSomethingElse() {
        val base = Fixtures.release("v0.4.6")
        // No tag or a tag that isn't a string: the release is left out.
        for (bad in listOf(JSONObject(base.toString()).apply { remove("tag_name") }, JSONObject(base.toString()).put("tag_name", 46),
            JSONObject(base.toString()).put("tag_name", JSONObject.NULL), JSONObject(base.toString()).put("tag_name", ""))) {
            assertEquals(emptyList<GitHubRelease>(), GitHubReleases.parseList(JSONArray().put(bad).toString()))
        }
        // draft and prerelease missing or not booleans: the careful reading.
        for (v in listOf<Any>("false", 0, JSONObject.NULL)) {
            val r = GitHubReleases.parseList(JSONArray().put(JSONObject(base.toString()).put("draft", v).put("prerelease", v)).toString())!!.single()
            assertTrue(r.draft)
            assertTrue(r.prerelease)
        }
        val r = GitHubReleases.parseList(JSONArray().put(JSONObject(base.toString()).apply { remove("draft"); remove("prerelease"); remove("name"); remove("body"); remove("published_at") }).toString())!!.single()
        assertTrue(r.draft && r.prerelease)
        assertEquals("", r.name)
        assertEquals("", r.body)
        assertNull(r.publishedAt)
        // A file with an id, a name or a size of the wrong type is left out; the others stay.
        val assets = JSONArray()
            .put(Fixtures.asset(1, "Robot-0.4.6.apk", 10))
            .put(JSONObject(Fixtures.asset(2, "a", 10).toString()).put("id", "2"))
            .put(JSONObject(Fixtures.asset(3, "b", 10).toString()).put("id", 3.5))
            .put(JSONObject(Fixtures.asset(4, "c", 10).toString()).put("id", -4))
            .put(JSONObject(Fixtures.asset(5, "d", 10).toString()).put("size", "10"))
            .put(JSONObject(Fixtures.asset(6, "e", 10).toString()).put("size", -1))
            .put(JSONObject(Fixtures.asset(7, "f", 10).toString()).put("name", 7))
            .put(JSONObject(Fixtures.asset(8, "", 10).toString()))
            .put(JSONObject(Fixtures.asset(9, "g", 10).toString()).put("id", JSONObject.NULL))
            .put("not an object").put(12).put(JSONObject.NULL)
        val one = GitHubReleases.parseList(JSONArray().put(JSONObject(base.toString()).put("assets", assets)).toString())!!.single()
        assertEquals(listOf(1L), one.assets.map { it.id })
        // Assets that aren't a list: none.
        assertEquals(emptyList<ReleaseAsset>(), GitHubReleases.parseList(JSONArray().put(JSONObject(base.toString()).put("assets", "x")).toString())!!.single().assets)
        // Elements of the list that aren't objects are skipped.
        assertEquals(1, GitHubReleases.parseList(JSONArray().put(1).put("x").put(JSONObject.NULL).put(base).toString())!!.size)
    }

    @Test fun answersThatArentAListOfReleases() {
        for (j in listOf(null, "", "{", "{}", "null", "\"x\"", "42", "{\"message\":\"Not Found\"}", "<html>Not Found</html>", "[1, 2", "[[[[")) {
            assertNull(j.toString(), GitHubReleases.parseList(j))
        }
        assertNull(GitHubReleases.parseOne("[]"))
        assertNull(GitHubReleases.parseOne("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}"))
    }

    @Test fun hostileAnswers() {
        // Nested deeper than any answer: refused before the parser could overflow its stack.
        assertNull(GitHubReleases.parseList("[".repeat(100_000) + "]".repeat(100_000)))
        assertNull(GitHubReleases.parseList("[" + "{\"a\":".repeat(50) + "1" + "}".repeat(50) + "]"))
        // Brackets inside strings don't count.
        assertNotNull(GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.4.6", body = "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[ \\\" {{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{"))))
        // Over a megabyte: not read at all.
        assertNull(GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.4.6", body = "x".repeat(GitHubReleases.MAX_JSON)))))
        // A huge body just under the cap is cut.
        val big = GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.4.6", body = "y".repeat(500_000))))!!.single()
        assertEquals(GitHubReleases.MAX_BODY, big.body.length)
        // Hundreds of releases and assets: only the first are read.
        val many = JSONArray()
        repeat(150) { many.put(Fixtures.release("v0.4.${it + 6}")) }
        assertEquals(GitHubReleases.MAX_RELEASES, GitHubReleases.parseList(many.toString())!!.size)
        val manyAssets = JSONArray()
        repeat(150) { manyAssets.put(Fixtures.asset(it + 1L, "f$it", 1)) }
        assertEquals(GitHubReleases.MAX_ASSETS, GitHubReleases.parseList(JSONArray().put(Fixtures.release("v0.4.6").put("assets", manyAssets)).toString())!!.single().assets.size)
        // A very long tag or asset name is not a release or file.
        assertEquals(emptyList<GitHubRelease>(), GitHubReleases.parseList(Fixtures.list(Fixtures.release("v0.4.6").put("tag_name", "v" + "1".repeat(300)))))
        // An id too large for a number GitHub uses.
        val huge = JSONArray().put(Fixtures.release("v0.4.6").put("assets", JSONArray().put(JSONObject("{\"id\": 123456789012345678901234567890, \"name\": \"Robot-0.4.6.apk\", \"size\": 1}"))))
        assertEquals(emptyList<ReleaseAsset>(), GitHubReleases.parseList(huge.toString())!!.single().assets)
    }

    @Test fun aReleaseNeedsExactlyItsApkAndSha256sums() {
        fun files(vararg assets: ReleaseAsset, tag: String = "v0.4.6") = ReleaseAssets.select(GitHubRelease(tag, "", "", false, false, null, assets.toList()))
        val apk = ReleaseAsset(1, "Robot-0.4.6.apk", 90_000_000, null, null)
        val sums = ReleaseAsset(2, "SHA256SUMS", 200, null, null)
        assertEquals(ReleaseFiles(apk, sums), files(apk, sums, ReleaseAsset(3, "robot-0.4.6-third-party-sources.tar.gz", 300_000_000, null, null)))
        assertEquals("Robot-0.6.0-rc1.apk", ReleaseAssets.select(GitHubRelease("v0.6.0-rc1", "", "", false, true, null,
            listOf(ReleaseAsset(1, "Robot-0.6.0-rc1.apk", 1, null, null), sums)))!!.apk.name)
        assertNull(files(apk))
        assertNull(files(sums))
        assertNull(files(apk, sums, tag = "release-0.4.6"))
        // Another version's APK, another case, a path, a look-alike, a different extension or a debug build: not the APK.
        for (n in listOf("Robot-0.4.7.apk", "robot-0.4.6.apk", "Robot-0.4.6.APK", "../Robot-0.4.6.apk", "x/Robot-0.4.6.apk", "Robot-0.4.6.apk ",
            " Robot-0.4.6.apk", "Rоbot-0.4.6.apk", "Robot-0.4.6.apk​", "Robot-0.4.6.apk.zip", "Robot-0.4.6-debug.apk", "Robot-v0.4.6.apk", "Robot-0.4.6.aab")) {
            assertNull(n, files(ReleaseAsset(1, n, 10, null, null), sums))
        }
        // For tag v0.4.6, "Robot-0.4.6.apk" exactly; for a tag "0.4.6" too.
        assertNotNull(files(apk, sums, tag = "0.4.6"))
        // Two of either: ambiguous, so neither is trusted.
        assertNull(files(apk, apk.copy(id = 9), sums))
        assertNull(files(apk, sums, sums.copy(id = 9)))
        assertNull(files(ReleaseAsset(1, "Robot-0.4.6.apk", 0, null, null), sums))
        assertNull(files(ReleaseAsset(1, "Robot-0.4.6.apk", ReleaseAssets.MAX_APK + 1, null, null), sums))
        assertNotNull(files(ReleaseAsset(1, "Robot-0.4.6.apk", ReleaseAssets.MAX_APK, null, null), sums))
        assertNull(files(apk, ReleaseAsset(2, "SHA256SUMS", ReleaseAssets.MAX_SUMS + 1L, null, null)))
        assertNull(files(apk, ReleaseAsset(2, "sha256sums", 10, null, null)))
        assertNull(files(apk, ReleaseAsset(2, "SHA256SUMS.txt", 10, null, null)))
        assertEquals(300L * 1024 * 1024, ReleaseAssets.MAX_APK)
        assertEquals(64 * 1024, ReleaseAssets.MAX_SUMS)
    }

    @Test fun sha256sumsInBothForms() {
        val a = "a".repeat(64)
        val b = "0123456789ABCDEF".repeat(4)
        val s = Sha256Sums.parse("$a  Robot-0.4.6.apk\n$b *robot-0.4.6-third-party-sources.tar.gz\n\n")!!
        assertEquals(a, s.of("Robot-0.4.6.apk"))
        assertEquals(b.lowercase(), s.of("robot-0.4.6-third-party-sources.tar.gz"))
        assertNull(s.of("Robot-0.4.7.apk"))
        assertNull(s.of("robot-0.4.6.apk"))
        // Windows line ends, a byte order mark, the same line twice, a name with a space.
        val w = Sha256Sums.parse("﻿$a  Robot-0.4.6.apk\r\n$a  Robot-0.4.6.apk\r\n$b  my file.txt\r\n")!!
        assertEquals(a, w.of("Robot-0.4.6.apk"))
        assertEquals(b.lowercase(), w.of("my file.txt"))
        assertEquals(setOf("Robot-0.4.6.apk", "my file.txt"), w.files)
    }

    @Test fun aMalformedSha256sumsIsRefusedWhole() {
        val a = "a".repeat(64)
        for (t in listOf(null, "", "\n\n", "$a Robot-0.4.6.apk", "$a\tRobot-0.4.6.apk", "$a   ", "${"a".repeat(63)}  Robot-0.4.6.apk",
            "${"a".repeat(65)}  Robot-0.4.6.apk", "${"g".repeat(64)}  Robot-0.4.6.apk", "$a  Robot-0.4.6.apk\nnonsense",
            "$a  ../Robot-0.4.6.apk", "$a  ./Robot-0.4.6.apk", "$a  dir\\Robot-0.4.6.apk", "$a  .", "$a  ..", "\\$a  Robot\\n.apk",
            "$a  Robot-0.4.6.apk\u0000", "$a  Robot-0.4.6.apk‮", "$a  Robot​-0.4.6.apk",
            "$a  Robot-0.4.6.apk\n${"b".repeat(64)}  Robot-0.4.6.apk", "SHA256 (Robot-0.4.6.apk) = $a", "# comment\n$a  Robot-0.4.6.apk",
            "${"０".repeat(64)}  Robot-0.4.6.apk")) {
            assertNull(t.toString(), Sha256Sums.parse(t))
        }
        assertNull(Sha256Sums.parse("$a  x\n".repeat(2000)))
        assertNull(Sha256Sums.parse("$a  " + "x".repeat(ReleaseAssets.MAX_SUMS)))
    }

    @Test fun releaseNotesArePlainText() {
        val n = ReleaseNotes.plain("## What's new\r\n\r\n- **Updates** from [GitHub](https://example.com/x)\n* `code`\n![shot](https://e.com/a.png)\n" +
            "<details><summary>More</summary>hidden <b>bold</b></details>\n<!-- a comment\nover lines -->\n\n\n\nEnd‮​.")
        assertEquals("What's new\n\n• Updates from GitHub\n• code\n\nMore hidden bold\n\nEnd.", n)
        assertEquals("", ReleaseNotes.plain(null))
        assertEquals("", ReleaseNotes.plain(" \n\n "))
        // Capped: lines, then characters, marked with an ellipsis; never half an emoji.
        val long = ReleaseNotes.plain((1..100).joinToString("\n") { "line $it" })
        assertEquals(ReleaseNotes.MAX_LINES, long.lines().size)
        assertTrue(long.endsWith("…"))
        val wide = ReleaseNotes.plain("x".repeat(ReleaseNotes.MAX_CHARS - 1) + "😀😀")
        assertTrue(wide.endsWith("…"))
        assertFalse(wide.dropLast(1).last().isHighSurrogate())
        assertTrue(wide.length <= ReleaseNotes.MAX_CHARS + 1)
        // Control characters go; ordinary text and emoji stay.
        assertEquals("a b 😀", ReleaseNotes.plain("a\u0007 b\u001b \u0085😀"))
        // A tag that never closes stays as text rather than eating the rest.
        assertEquals("1 < 2 and 3 > 2", ReleaseNotes.plain("1 < 2 and 3 > 2"))
    }
}

/** GitHub's answers as the API gives them (trimmed to the fields that matter, plus a few that don't). */
object Fixtures {
    fun asset(id: Long, name: String, size: Long): JSONObject = JSONObject()
        .put("url", "https://api.github.com/repos/o/r/releases/assets/$id")
        .put("browser_download_url", "https://github.com/o/r/releases/download/v0.4.6/$name")
        .put("id", id)
        .put("node_id", "RA_kwDO$id")
        .put("name", name)
        .put("label", "")
        .put("state", "uploaded")
        .put("content_type", "application/octet-stream")
        .put("size", size)
        .put("download_count", 3)
        .put("created_at", "2026-10-11T09:00:00Z")
        .put("updated_at", "2026-10-11T09:00:00Z")
        .put("uploader", JSONObject().put("login", "picocryptfan").put("id", 1).put("type", "User"))

    fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, body: String = "Notes", apk: Boolean = true, sums: Boolean = true,
                baseId: Long = 100): JSONObject {
        val v = tag.removePrefix("v")
        val assets = JSONArray()
        if (apk) assets.put(asset(baseId + 1, "Robot-$v.apk", 97_000_000))
        if (sums) assets.put(asset(baseId + 2, "SHA256SUMS", 250))
        assets.put(asset(baseId + 3, "robot-$v-third-party-sources.tar.gz", 310_000_000))
        assets.put(asset(baseId + 4, "robot-$v-third-party-sources.tar.gz.sha256", 100))
        return JSONObject()
            .put("url", "https://api.github.com/repos/o/r/releases/$baseId")
            .put("html_url", "https://github.com/o/r/releases/tag/$tag")
            .put("id", baseId)
            .put("author", JSONObject().put("login", "picocryptfan").put("id", 1))
            .put("tag_name", tag)
            .put("target_commitish", "main")
            .put("name", "Robot $v")
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("created_at", "2026-10-11T09:00:00Z")
            .put("published_at", "2026-10-11T09:30:00Z")
            .put("assets", assets)
            .put("tarball_url", "https://api.github.com/repos/o/r/tarball/$tag")
            .put("zipball_url", "https://api.github.com/repos/o/r/zipball/$tag")
            .put("body", body)
    }

    fun list(vararg r: JSONObject): String = JSONArray().apply { r.forEach { put(it) } }.toString()
}
