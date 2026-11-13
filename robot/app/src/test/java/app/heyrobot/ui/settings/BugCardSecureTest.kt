package app.heyrobot.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-batch-17-18 M3: the bug card shows the turn and another app's screenshot, so Recents and screenshots never keep it. */
class BugCardSecureTest {
    @Test fun theBugCardIsASecureWindow() {
        val f = listOf("src/main/java/app/heyrobot/ui/settings/FeedbackSettings.kt", "app/src/main/java/app/heyrobot/ui/settings/FeedbackSettings.kt")
            .map(::File).first { it.exists() }
        val s = f.readText()
        val start = s.indexOf("internal fun FeedbackRoute(")
        val body = s.substring(start, s.indexOf("\n}\n", start))
        assertTrue(body.contains("SecureWindow()"))
    }
}
