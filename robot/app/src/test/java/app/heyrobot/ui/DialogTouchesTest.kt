// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.Flat
import app.heyrobot.Source
import app.heyrobot.assertInOrder
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * fb13: a dialog has a window of its own, so MainActivity's filterObscuredTouches doesn't reach it. Every dialog in Robot goes
 * through ui/common's AlertDialog, which refuses a tap while another app's window covers it (DialogRefusesCoveredTapsTest runs
 * it). This guard reads the source, as nothing else can show that no other dialog window exists.
 */
class DialogTouchesTest {
    private val root = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot")
    private val secure = "ui/common/Secure.kt"

    @Test fun noDialogWindowBesidesTheOneThatRefusesCoveredTaps() {
        val bare = Regex("""material3\.(AlertDialog|BasicAlertDialog|ModalBottomSheet)\b|androidx\.compose\.ui\.window\.Dialog\b|\bandroid\.app\.(AlertDialog|Dialog)\b|\b(BasicAlertDialog|ModalBottomSheet|AlertDialog\.Builder|ComponentDialog)\b""")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.relativeTo(root).path != secure }
            .filter { bare.containsMatchIn(Source.strip(it.readText(), keepStrings = false)) }.map { it.relativeTo(root).path }.toList()
        assertEquals(emptyList<String>(), found)
    }

    /** F115: a menu is a window of its own too, and some of its rows delete at once (a list's item). */
    @Test fun noMenuBesidesTheOneThatRefusesCoveredTaps() {
        val bare = Regex("""material3\.(DropdownMenu|ExposedDropdownMenu\w*)\b(?!Item)|\bExposedDropdownMenu\w*\b|androidx\.compose\.ui\.window\.Popup\b""")
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.relativeTo(root).path != secure }
            .filter { bare.containsMatchIn(Source.strip(it.readText(), keepStrings = false)) }.map { it.relativeTo(root).path }.toList()
        assertEquals(emptyList<String>(), found)
    }

    @Test fun theMenuFiltersItsOwnWindow() {
        val code = Flat(Source.strip(File(root, secure).readText(), keepStrings = true))
        assertInOrder(code, "fun DropdownMenu(", "RefuseCoveredPopupTaps(); content()", "private fun RefuseCoveredPopupTaps()", "top.filterTouchesWhenObscured = true")
    }
}
