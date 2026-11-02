// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.testing

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Screens read their files with `withContext(Dispatchers.IO)` in a `LaunchedEffect` and write the result to their state.
 * On a phone the effect comes back to the main thread. Compose's older test rule (`junit4.createComposeRule`) runs effects
 * on an unconfined dispatcher, so the rest of the effect ran on the IO thread, while the main thread measured the page:
 * now and then a lazy list lost that change for good, and a page opened with a group missing. So every test uses the
 * `junit4.v2` rule, whose effects come back to the main thread as on a phone. flakes-2.md has the evidence.
 */
private fun threadAfterIo(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule): Pair<String, String> {
    var after: Thread? = null
    rule.setContent {
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { Thread.sleep(5) }
            after = Thread.currentThread()
        }
        Text("page")
    }
    rule.waitUntil(5_000) { after != null }
    return after!!.id.toString() to Thread.currentThread().id.toString()
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComposeRuleThreadTest {
    @get:Rule val rule = createComposeRule()

    @Test fun anEffectComesBackToTheMainThreadAsOnAPhone() {
        val (effect, test) = threadAfterIo(rule)
        assertEquals(test, effect)
    }

    /** Reads a file in two steps, as Settings' pages do. */
    @androidx.compose.runtime.Composable private fun Page() {
        var text by androidx.compose.runtime.remember { mutableStateOf("reading") }
        LaunchedEffect(Unit) {
            val first = withContext(Dispatchers.IO) { Thread.sleep(300); "half" }
            text = withContext(Dispatchers.IO) { Thread.sleep(300); "$first read" }
        }
        Text(text)
    }

    @Test fun waitForIdleWaitsForAPageToReadItsFile() {
        rule.show { Page() }
        rule.waitForIdle()
        rule.onNodeWithText("half read").assertExists()
    }

    /** The control: without [IoIdling] the same wait comes back too early, which is why [show] registers it. */
    @Test fun withoutItTheWaitComesBackEarly() {
        rule.setContent { Page() }
        rule.waitForIdle()
        rule.onNodeWithText("half read").assertDoesNotExist()
    }

    private fun usesOldRule(text: String) =
        Regex("""androidx\.compose\.ui\.test\.junit4\.(\*|createComposeRule|createAndroidComposeRule|createEmptyComposeRule)""").containsMatchIn(text)

    @Test fun everyTestUsesTheRuleThatDoes() {
        // L16: a star import counts too, and both test folders are walked.
        assertTrue(usesOldRule("import androidx.compose.ui.test.junit4.*"))
        assertTrue(usesOldRule("import androidx.compose.ui.test.junit4.createComposeRule"))
        assertFalse(usesOldRule("import androidx.compose.ui.test.junit4.v2.createComposeRule"))
        val users = listOf("src/testDebug/java", "src/test/java").flatMap { File(it).walkTopDown().toList() }.filter { f -> f.extension == "kt" && usesOldRule(f.readText()) }.map { it.name }.toList()
        assertEquals(emptyList<String>(), users.filter { it != "ComposeRuleThreadTest.kt" })
        assertTrue(File("src/testDebug/java").walkTopDown().count { it.extension == "kt" } > 50)
    }
}

/** The control: the older rule's effect does stay on the IO thread, which is why [ComposeRuleThreadTest] is there. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class OldComposeRuleThreadTest {
    @Suppress("DEPRECATION")
    @get:Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()

    @Test fun anEffectStaysOnTheIoThread() {
        val (effect, test) = threadAfterIo(rule)
        assertNotEquals(test, effect)
    }
}
