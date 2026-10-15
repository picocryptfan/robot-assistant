// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Intent
import app.heyrobot.core.CheogramLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Choose in Cheogram" opened Cheogram's last screen instead of its share screen (0.4.0): a plain SEND to the package
 * matched the first intent of Cheogram's task (an earlier share; Android ignores the text), so the task only came to
 * the front. Every hand-off now names its activity where it can and starts in a task of its own.
 */
class CheogramIntentsTest {
    @Test fun eachHandOffStartsInATaskOfItsOwn() {
        assertEquals(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK, CheogramIntents.FRESH)
        assertEquals("eu.siacs.conversations.ui.ShareWithActivity", CheogramLinks.SHARE_ACTIVITY)
        // Intents can't be built off the phone: the source says how each route is made.
        val src = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot/sys/People.kt").readText()
        val share = src.substringAfter("fun share(").substringBefore("\n\n")
        assertTrue(share, share.contains("setClassName(pkg, CheogramLinks.SHARE_ACTIVITY)") && share.contains("addFlags(FRESH)") && !share.contains("setPackage"))
        for (f in listOf("fun chat(", "fun link(")) assertTrue(f, src.substringAfter(f).substringBefore("\n\n").contains("addFlags(FRESH)"))
        // A Cheogram build without that share screen: the share to the package, as before 0.4.0 (fresh too), tried second.
        val any = src.substringAfter("fun shareAny(").substringBefore("\n\n")
        assertTrue(any, any.contains("setPackage(pkg)") && any.contains("addFlags(FRESH)") && !any.contains("setClassName"))
        val tool = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot/assistant/ToolExecutor.kt").readText()
        val pick = tool.substringAfter("TextVia.CHEOGRAM_PICK -> listOf(").substringBefore("\n")
        assertTrue(pick, pick.indexOf("CheogramIntents.share(") in 0 until pick.indexOf("CheogramIntents.shareAny("))
        // Each route is resolved before it starts, so the second runs only when the first can't.
        assertTrue(tool.contains("if (i.resolveActivity(ctx.packageManager) == null) return false"))
        // The contact list is started for a result from Robot's own activity: it stays in that task, unchanged.
        assertTrue(!src.substringAfter("fun pick(").substringBefore("\n").contains("FRESH"))
    }
}
