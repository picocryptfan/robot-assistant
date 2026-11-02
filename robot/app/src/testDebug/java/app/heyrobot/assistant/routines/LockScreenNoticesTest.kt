// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.app.KeyguardManager
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.skills.SkillContinue
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** F67: a skill's Continue notification holds its name and its next step, with this run's values. */
    @Test fun aSkillsContinueIsSecretOnTheLockScreen() {
        val skill = Skill("s-team01", 1, "Clinic run", listOf("clinic run"), emptyList(), emptyList(),
            listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "first")), SkillStep.of(StepKind.SAY, mapOf("text" to "Call Dr Tremblay"))),
            SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.VOICE, false, 1L, 1L))
        SkillContinue.post(ctx, skill, SkillContinue.Waiting(skill.id, 1, emptyMap(), false, 1, 1, false), Langs.EN)
        val n = posted().single()
        assertEquals(Notification.VISIBILITY_SECRET, n.visibility)
        val pub = n.publicVersion!!
        val words = listOfNotNull(pub.extras.getCharSequence(Notification.EXTRA_TITLE), pub.extras.getCharSequence(Notification.EXTRA_TEXT)).joinToString(" ")
        assertFalse(words, words.contains("Clinic") || words.contains("Tremblay"))
    }

    /** F68: under Android's default lock-screen setting PRIVATE shows the whole notification, so it can't hold the name while locked. */
    @Test fun theSpeakingNoticeNamesTheRoutineOnlyWhileUnlocked() {
        val r = Routine("r-1", "Clinic run", RoutineTrigger.Asked, listOf(RoutineStep.Say("Hello")), created = 1L)
        fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val locked = RoutineNotices.speaking(ctx, r)
        assertEquals(RoutineStrings.speaking().render(Langs.EN), title(locked))
        assertEquals(Notification.VISIBILITY_PRIVATE, locked.visibility) // not SECRET, which would hide Stop
        assertEquals(3, locked.actions.size)
        keyguard.setKeyguardLocked(false)
        val open = RoutineNotices.speaking(ctx, r)
        assertTrue(title(open), title(open).contains("Clinic run"))
        assertEquals(3, open.actions.size)
    }

    /** F68: the running service follows the lock: the public title when you lock, the name again after you unlock. */
    @Test fun theServiceRenamesItsNoticeAcrossTheLock() {
        val r = Routine("r-1", "Clinic run", RoutineTrigger.Asked, listOf(RoutineStep.Say("Hello")), created = 1L)
        val svc = org.robolectric.Robolectric.buildService(RoutineSpeakingService::class.java).create().get()
        svc.onStartCommand(android.content.Intent(RoutineSpeakingService.ACTION_START), 0, 1)
        fun title() = shadowOf(ctx.getSystemService(NotificationManager::class.java)).getNotification(RoutineNotices.SPEAKING_ID)
            .extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        svc.refreshForLock(r)
        assertEquals(RoutineStrings.speaking().render(Langs.EN), title())
        keyguard.setKeyguardLocked(false)
        svc.refreshForLock(r)
        assertTrue(title(), title().contains("Clinic run"))
        val watched = shadowOf(ctx.applicationContext as android.app.Application).registeredReceivers.flatMap { w -> w.intentFilter.actionsIterator().asSequence().toList() }
        for (a in listOf(android.content.Intent.ACTION_SCREEN_OFF, android.content.Intent.ACTION_SCREEN_ON, android.content.Intent.ACTION_USER_PRESENT)) assertTrue(a, a in watched)
    }
}
