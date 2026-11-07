// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import app.heyrobot.Source
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * today-h7-fix 10 and 11, pinned in the source: the News dialog doesn't settle under Robolectric (it never goes idle), so
 * its two fixes are pinned here. Tor chosen while it can't work says why beside the choice, where Direct is; each Remove
 * is read with its feed's name.
 */
class TodayNewsDialogPinsTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private val code = Source.strip(File(root, "app/src/main/java/app/heyrobot/ui/today/TodayNews.kt").readText(), keepStrings = true)

    @Test fun torThatCantWorkSaysWhyBesideTheChoice() {
        val dialog = code.substringAfter("fun NewsDialog(")
        val via = dialog.substringAfter("R.string.today_news_via_title").substringBefore("for (v in NewsVia.entries)")
        assertTrue(via, "if (torOff) Text(stringResource(R.string.today_news_tor_off)" in via)
        val setting = code.substringAfter("fun TodayNewsSetting()").substringBefore("fun NewsDialog(")
        assertTrue(setting, "val torOff = torOff(ctx, n)" in setting && "torOff = torOff)" in setting && "R.string.today_news_tor_off" in setting)
    }

    @Test fun eachRemoveSaysWhichFeed() {
        val row = code.substringAfter("for (f in n.feeds)").substringBefore("if (n.on) for (m in NewsMode.entries)")
        assertTrue(row, "stringResource(R.string.today_news_remove_feed, f.name)" in row)
        assertTrue(row, "Modifier.semantics { contentDescription = label }" in row)
    }

    /** today-h8-fix 8: Add saves off the main thread, skips the same real address, and keeps the preview when the save fails. */
    @Test fun addSavesOffTheMainThreadAndSaysWhyItFailed() {
        val add = code.substringAfter("FilledTonalButton(enabled = !busy, onClick = {").substringBefore("R.string.today_news_add")
        assertTrue(add, "withContext(Dispatchers.IO) { NewsKeys.feed(ctx, f.url, f.feed.name) }" in add)
        assertTrue(add, "withContext(Dispatchers.IO) { NewsKeys.has(ctx, n.feeds, f.url) }" in add)
        assertTrue(add, "if (!dup && feed == null) { unsaved = true; return@launch }" in add)
        val row = code.substringAfter("for (f in n.feeds)").substringBefore("if (n.on) for (m in NewsMode.entries)")
        assertTrue(row, "remember(f.key) { if (real) NewsKeys.masked" !in row && "withContext(Dispatchers.IO) { NewsKeys.masked(ctx, f) }" in row)
        // Q35: the plain-http note comes through noteText(OwnFeed.note(...)), which still names today_news_plain_http.
        assertTrue(row, "R.string.today_news_no_key" in row && "OwnFeed.note(f.url, f.key != null)" in row && "noteText(it)" in row)
        assertTrue("OwnFeed.Note.PLAIN_HTTP -> R.string.today_news_plain_http" in code.substringAfter("fun noteText("))
    }
}
