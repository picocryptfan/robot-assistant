// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.TimeWindow
import app.heyrobot.lock.LockEngine
import app.heyrobot.lock.LockStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** fb11, F6: "run <routine>" during someone else's lock: a routine's own words aren't said, on a phone that isn't locked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AskedRoutineSomeoneElseTest {
    /** The one VoiceHub reads from: it keeps the first test's for the whole run. */
    private val ctx: Context by lazy { VoiceHub.init(ApplicationProvider.getApplicationContext()); VoiceHub.app }

    private class Fake : PanelRoutines.Host {
        val said = ArrayList<String>()
        override val speak = true
        override val locked = false
        override fun text(replyId: String, text: String) = Unit
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) { said += sentence }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?) = false
        override fun show(replyId: String, card: Card) = Unit
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() = Unit
    }

    @Before fun hub() { ctx }

    @After fun lockOff() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun run(): List<String> {
        val r = Routine("r", "Test", RoutineTrigger.AlarmDismissed(), listOf(RoutineStep.Say("Dentist at 9.")), created = 0)
        val host = Fake()
        runBlocking { PanelRoutines.runAsked("reply", r, host, RunOrigin.ASKED) }
        return host.said
    }

    @Test fun itsOwnWordsWaitDuringSomeoneElsesLock() {
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val l = PhoneLock("lk_else0", "Guest", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true, forWho = LockFor.SOMEONE_ELSE), listOf(l)) })
        assertTrue(LockEngine.refresh(ctx).active)
        assertEquals(emptyList<String>(), run())
    }

    @Test fun withNoLockTheyAreSaid() {
        assertEquals(listOf("Dentist at 9."), run())
    }
}
