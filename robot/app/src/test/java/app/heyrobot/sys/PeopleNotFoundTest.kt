// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** seal-people-fix-b 3: a name only People knows, asked over the lock screen, gets "People opens once you unlock", never "allow
 *  the contacts" or "calls to that number are closed". */
class PeopleNotFoundTest {
    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
    private fun code(path: String) = Source.strip(File(root, "app/src/main/java/app/heyrobot/$path").readText(), keepStrings = true, kotlin = true)

    @Test fun peopleClosedComesBeforeTheContacts() {
        for (granted in listOf(true, false)) assertTrue(PeopleFiles.notFound(true, granted, "Mom")!!.en().contains("opens once you unlock"))
        assertTrue(PeopleFiles.notFound(false, true, "Mom")!!.en().contains("Mom"))
        assertNull(PeopleFiles.notFound(false, false, "Mom"))
    }

    @Test fun everyPathAsksPeopleFirst() {
        val tools = code("assistant/ToolExecutor.kt")
        assertEquals(2, Regex("""needContacts\(\)""").findAll(tools).count()) // its definition and notFound's
        assertTrue(tools.contains("else notFound(action.name, false)"))
        assertTrue(code("assistant/car/CallPlacer.kt").contains("PeopleFiles.notFound(ctx, to, Contacts.granted(ctx))"))
        val hf = code("assistant/handsfree/PanelHandsFree.kt")
        val a = hf.indexOf("PeopleFiles.noContact(app, c.who)")
        assertTrue(a >= 0 && a < hf.indexOf("said(LockStrings.callClosed())"))
    }
}
