// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import app.heyrobot.Source
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * today-h7-fix: the public list of news feeds (GitHub) is only ever opened in your browser on a tap; Robot never fetches it.
 * verify_apk.sh allows that one address, by exact value, in Today's classes only: this pins both sides.
 */
class FeedListLinkTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val main = File(root, "app/src/main/java")
    private val address = "https://github.com/plenaryapp/awesome-rss-feeds"

    @Test fun theFeedListOpensOnlyInTheBrowserOnATap() {
        val news = File(main, "app/heyrobot/ui/today/TodayNews.kt").readText()
        assertTrue(news.contains("const val FEED_LIST = \"$address\""))
        // Its one use: openLink, which hands it to your browser (ACTION_VIEW, CATEGORY_BROWSABLE) from a button's tap.
        val code = Source.strip(news, keepStrings = false)
        val uses = code.lines().filter { "FEED_LIST" in it }.map { it.trim() }.filterNot { it.startsWith("const val FEED_LIST") }
        assertEquals(1, uses.size)
        assertTrue(uses.single(), uses.single().startsWith("TextButton(onClick = { openLink(ctx, FEED_LIST) })"))
        val open = code.substringAfter("private fun openLink(").substringBefore("\n}")
        assertTrue(open, "Intent.ACTION_VIEW" in open && "CATEGORY_BROWSABLE" in open && "NewsStore" !in open && "FeedClient" !in open)
        // Nowhere else in Robot's code: no client is ever given it.
        val others = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TodayNews.kt" }
            .filter { "FEED_LIST" in it.readText() || "awesome-rss-feeds" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), others)
    }

    @Test fun verifyApkAllowsOnlyThatAddressInTodaysClasses() {
        val script = File(root, "tools/verify_apk.sh").readText()
        val check = script.substringAfter("gh_refs=$(").substringBefore("[ \"\$gh_refs\" = \"0\" ]")
        assertTrue(check, "index(\$0, \"\\\"$address\\\"\")" in check)
        assertTrue(check, "td = (\$0 ~ /Class descriptor  : .Lapp\\/heyrobot\\/ui\\/today\\//)" in check)
        assertEquals(1, Regex("plenaryapp").findAll(check).count())
    }
}
