// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Looper
import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnSlot
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.helpers.HelperOutcome
import app.heyrobot.helpers.HelperSession
import app.heyrobot.helpers.HelperTurnListener
import app.heyrobot.helpers.HelperTurnReport
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A helper's conversation (PanelHelperMode, from PanelController in 1.1): the chip's helper, name and "your server" flag
 * in the panel's state; its session and join point, which helperTurn reads to decide what goes to the helper, kept while
 * the same helper is asked again and dropped when another one is; and its end after 10 minutes without a turn, unless a
 * turn is still running. The idle timer runs in the panel's scope on the main looper, so the looper's clock drives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelHelperModeTest {
    private val scope = MainScope()
    private val looper = shadowOf(Looper.getMainLooper())
    private val state = MutableStateFlow(PanelState("c"))
    private val slot = TurnSlot(scope)
    private val mode = PanelHelperMode(scope, state) { slot.running }

    private val jarvis = HelperConfig("h1", "anthropic", "Jarvis", "m")
    private val server = HelperConfig("h2", "home", "My server", "m")

    /** A session nothing is asked of: only whether it is kept or dropped is looked at. */
    private object Session : HelperSession {
        override val fresh = true
        override val lastReport: HelperTurnReport get() = error("not asked")
        override val offered = emptySet<ToolName>()
        override suspend fun ask(blocks: List<HelperBlock>, plan: HelperPlan, listener: HelperTurnListener): HelperOutcome = error("not asked")
        override suspend fun answer(results: List<Map<String, Any?>>, listener: HelperTurnListener): HelperOutcome = error("not asked")
        override fun abandon(results: List<Map<String, Any?>>, stopped: Boolean) = Unit
    }

    @After fun end() = scope.cancel()

    private fun minutes(m: Long) = looper.idleFor(Duration.ofMinutes(m))

    @Test fun startingSetsTheChipAndTheJoinPoint() {
        mode.start(server, joinedAt = 4, misheard = true)
        assertEquals("h2", state.value.helperMode)
        assertEquals("My server", state.value.helperName)
        assertTrue(state.value.helperHome)
        assertEquals(4, mode.joinedAt)
        assertTrue(mode.misheard)
        assertNull(mode.session)
    }

    @Test fun theSameHelperAgainKeepsItsSessionAndJoinPointAnotherDropsThem() {
        mode.start(jarvis, joinedAt = 3, misheard = true)
        mode.session = Session
        // "Go get Jarvis" again: the conversation goes on, joined where it was, with the same session.
        mode.start(jarvis, joinedAt = 9)
        assertSame(Session, mode.session)
        assertEquals(3, mode.joinedAt)
        assertTrue(mode.misheard)
        // Another helper: one at a time, so Jarvis's session goes and the new one joins from here.
        mode.start(server, joinedAt = 9)
        assertNull(mode.session)
        assertEquals(9, mode.joinedAt)
        assertFalse(mode.misheard)
        assertEquals("h2", state.value.helperMode)
        assertTrue(state.value.helperHome)
    }

    @Test fun endingClearsTheChipAndTheSessionAndAgainChangesNothing() {
        mode.start(jarvis, joinedAt = 0)
        mode.session = Session
        mode.end()
        assertNull(state.value.helperMode)
        assertNull(state.value.helperName)
        assertFalse(state.value.helperHome)
        assertNull(mode.session)
        // Nothing to end: the state is left as it is (not even rewritten).
        val before = state.value
        mode.end()
        assertSame(before, state.value)
        // A session kept with no helper on the chip still goes.
        mode.session = Session
        mode.end()
        assertNull(mode.session)
    }

    /** 1.1, from PanelController.settingsChanged: the main app's settings changed. */
    @Test fun aHelperRenamedRenamesTheChipAndOneTurnedOffOrRemovedEndsIt() {
        mode.start(jarvis, joinedAt = 0)
        mode.session = Session
        // Another helper's change, or none: the chip is left as it is.
        val before = state.value
        mode.settingsChanged(RobotSettings(helpers = listOf(jarvis, server.copy(name = "Home"))))
        assertSame(before, state.value)
        mode.settingsChanged(RobotSettings(helpers = listOf(jarvis.copy(name = "Friday"))))
        assertEquals("Friday", state.value.helperName)
        assertSame(Session, mode.session)
        mode.settingsChanged(RobotSettings(helpers = listOf(jarvis.copy(enabled = false))))
        assertNull(state.value.helperMode)
        assertNull(mode.session)
        mode.start(jarvis, joinedAt = 0)
        mode.settingsChanged(RobotSettings(helpers = listOf(server)))
        assertNull(state.value.helperMode)
        // Without a helper's conversation, nothing changes.
        val idle = state.value
        mode.settingsChanged(RobotSettings(helpers = listOf(server)))
        assertSame(idle, state.value)
    }

    @Test fun tenMinutesWithoutATurnEndItAndATurnStartsThemAgain() {
        mode.start(jarvis, joinedAt = 0)
        minutes(9)
        mode.touch()
        minutes(9)
        assertEquals("h1", state.value.helperMode)
        minutes(2)
        assertNull(state.value.helperMode)
    }

    @Test fun aTurnStillRunningKeepsIt() {
        mode.start(jarvis, joinedAt = 0)
        assertTrue(slot.start { awaitCancellation() })
        minutes(11)
        // The timer found a turn running: the conversation stays, until the next question's expire() (below).
        assertEquals("h1", state.value.helperMode)
        slot.stop()
        looper.idle()
        assertFalse(slot.running)
        assertEquals("h1", state.value.helperMode)
    }

    /**
     * The next question (PanelController.send) ends a conversation whose last turn was more than 10 minutes ago, by the
     * wall clock; the looper's clock doesn't drive System.currentTimeMillis here, so the last turn is set back instead.
     */
    private fun lastTurn(minutesAgo: Long) = PanelHelperMode::class.java.getDeclaredField("last").apply { isAccessible = true }
        .setLong(mode, System.currentTimeMillis() - minutesAgo * 60_000L)

    @Test fun theNextQuestionEndsAConversationOlderThanTenMinutes() {
        mode.start(jarvis, joinedAt = 0)
        mode.session = Session
        lastTurn(9)
        mode.expire()
        assertEquals("h1", state.value.helperMode)
        assertSame(Session, mode.session)
        lastTurn(11)
        mode.expire()
        assertNull(state.value.helperMode)
        assertNull(mode.session)
        // Without a helper's conversation, nothing to expire.
        val before = state.value
        mode.expire()
        assertSame(before, state.value)
    }
}
