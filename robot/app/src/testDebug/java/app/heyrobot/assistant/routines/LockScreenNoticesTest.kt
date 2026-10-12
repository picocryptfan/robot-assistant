package app.heyrobot.assistant.routines

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 1.0 audit (secrets-lockscreen-notification-content): the brief's words and Ready cards' steps never show on the secure
 * lock screen, whatever "Show sensitive content when locked" says: VISIBILITY_SECRET, with a public version that names
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockScreenNoticesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun posted(): List<Notification> = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun theBriefsWordsAndReadyCardsAreSecretOnTheLockScreen() {
        val brief = BriefRoutine.of(RoutineSettings())
        RoutineNotices.silent(ctx, brief, "10:00 Oncology follow-up with Dr Tremblay. Reminder: call the bank.")
        RoutineNotices.ready(ctx, listOf(ReadyCard("r-1", "Leaving work", RoutineStep.Say("Text Mom I'm on my way"), 1_000L, LocalDate.of(2026, 10, 12))))
        val all = posted()
        assertEquals(2, all.size)
        for (n in all) {
            assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
            val pub = n.publicVersion!!
            val words = listOfNotNull(pub.extras.getCharSequence(Notification.EXTRA_TITLE), pub.extras.getCharSequence(Notification.EXTRA_TEXT)).joinToString(" ")
            assertFalse(words, words.contains("Tremblay") || words.contains("Mom") || words.contains("Leaving work"))
        }
    }
}
