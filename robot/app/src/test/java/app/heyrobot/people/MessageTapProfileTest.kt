// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.people

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-what-did-i-miss-c: a work profile's notification is that profile's. MessageTap.parse is the one gate for the
 * shade at connect, each posted notification and each read again, so it turns away any user but Robot's own before
 * anything is parsed. StatusBarNotification and Process need a phone, so this reads the gate.
 */
class MessageTapProfileTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    @Test fun aWorkProfilesNotificationIsNeverParsed() {
        val code = File(root, "app/src/main/java/app/heyrobot/people/MessageTap.kt").readText()
        val start = code.indexOf("private fun parse(ctx: Context, sbn: StatusBarNotification)")
        val end = code.indexOf("NotificationParsing.parse(", start)
        assertTrue("parse and its gate", start in 0 until end)
        assertTrue(code.substring(start, end).contains("if (sbn.user != android.os.Process.myUserHandle()) null"))
        // Every way in goes through parse: no other NotificationParsing.parse call.
        assertTrue(code.indexOf("NotificationParsing.parse(", end + 1) < 0)
    }
}
