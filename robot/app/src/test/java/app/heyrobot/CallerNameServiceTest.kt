// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Caller name (review-caller-name): what the service and its switch must keep, read from their sources.
class CallerNameServiceTest {
    private val service = File("src/main/java/app/heyrobot/calls/CallerNameService.kt").readText()
    private val switch = File("src/main/java/app/heyrobot/ui/settings/CallerNameSettings.kt").readText()
    private fun res(dir: String) = File("src/main/res/$dir/strings_people.xml").readText()
    private fun string(dir: String, name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(res(dir))!!.groupValues[1]

    /** Medium 3: Telecom gets its answer before any search, and the search runs on one bounded thread that skips while busy. */
    @Test fun respondsBeforeSearching() {
        val screen = service.substringAfter("override fun onScreenCall").substringBefore("\n    }\n")
        assertTrue(screen.indexOf("respondToCall") in 0 until screen.indexOf("CallerLookup."))
        assertFalse(service.contains("newCachedThreadPool"))
        assertFalse(Regex("""\.get\(""").containsMatchIn(service.substringAfter("object CallerLookup").substringBefore("object CallerNotice")))
    }

    /** review-caller-name-b, Low 3: the 1.5 s clock starts when the call comes in, not after the contacts check. */
    @Test fun clockStartsWithTheCall() {
        val screen = service.substringAfter("override fun onScreenCall").substringBefore("\n    }\n")
        assertTrue(screen.indexOf("elapsedRealtime()") in 0 until screen.indexOf("respondToCall"))
        assertTrue(Regex("""CallerName\.guess\([^\n]*start = """).containsMatchIn(service))
    }

    /** review-caller-name-b, Low 4: the role is read again each time the screen comes back (from Default apps). */
    @Test fun roleReadAgainOnResume() {
        assertTrue(switch.contains("Lifecycle.Event.ON_RESUME"))
        assertFalse(Regex("""val still = [^\n]*held\(\)""").containsMatchIn(switch))
    }

    /** Medium 4: the notice stays on this phone (no paired watch). */
    @Test fun noticeIsLocalOnly() {
        assertTrue(service.substringAfter("object CallerNotice").contains(".setLocalOnly(true)"))
    }

    /** High 2: turning it off says the role is still Robot's and opens Default apps; the line says so before it's on. */
    @Test fun offGivesTheRoleBack() {
        assertTrue(switch.contains("ACTION_MANAGE_DEFAULT_APPS_SETTINGS"))
        assertTrue(string("values", "people_caller_name_detail").contains("Default apps"))
        assertTrue(string("values-fr-rCA", "people_caller_name_detail").contains("Applications par défaut"))
        assertTrue(string("values", "people_caller_name_still").contains("Default apps"))
        assertTrue(string("values-fr-rCA", "people_caller_name_still").contains("Applications par défaut"))
    }

    /** review-caller-name-c, Low 4: the file turns off with the switch when another app took the role, read from the file. */
    @Test fun fileOffWhenRoleGone() {
        assertTrue(Regex("""if \(CallerNameStore\.current\(ctx\)\.on && !holds\)""").containsMatchIn(switch))
        assertFalse(switch.contains("if (on && !holds)"))
    }
}
