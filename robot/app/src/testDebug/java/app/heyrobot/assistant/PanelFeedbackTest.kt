// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import app.heyrobot.core.feedback.FeedbackCommand
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.FeedbackFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A feedback phrase in the panel (PanelFeedback, from PanelController in 1.1): on the lock screen Robot only says to unlock
 * (through Phone lock's own sentence when it is on) and neither keeps your words nor opens anything; unlocked, your words
 * go into Robot's own prefill file before Send feedback opens with the kind, and the panel leaves only when the page
 * really opened. No test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelFeedbackTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private var locked = false
    private var opens = true
    private var attached = true
    private val launched = mutableListOf<Intent>()
    /** What the port was asked, in order: "reply <text> | <spoken>", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val host = object : PanelHost {
        override fun hidePanel() { calls += "hide" }
        override fun launch(intent: Intent): Boolean { launched += intent; return opens }
        override fun startCapture(intent: Intent) = false
    }

    private val feedback = PanelFeedback(object : PanelFeedback.Port {
        override val ctx get() = this@PanelFeedbackTest.ctx
        override val host get() = if (attached) this@PanelFeedbackTest.host else null
        override fun locked() = locked
        override fun unlockFirst(p: Phrase) = Phrase.plain("[lock] " + p.render(Langs.EN))
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)} | ${spoken.render(Langs.EN)}" }
        override suspend fun leaveForApp() { calls += "leave" }
    })

    @Before fun clean() { FeedbackFiles.takePrefill(ctx) }
    @After fun cleanAfter() { FeedbackFiles.takePrefill(ctx) }

    @Test fun onTheLockScreenItOnlySaysToUnlock() = runBlocking {
        locked = true
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, "the timer card is cut off"))
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply [lock] ") && calls[0].contains(" | [lock] "))
        assertTrue(launched.isEmpty())
        assertNull(FeedbackFiles.takePrefill(ctx))
    }

    @Test fun unlockedYourWordsAreKeptAndThePageOpensWithItsKind() = runBlocking {
        feedback.run("r1", FeedbackCommand(FeedbackKind.IDEA, "a darker theme"))
        assertEquals(2, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply ") && !calls[0].contains("[lock]"))
        assertEquals("leave", calls[1])
        assertEquals(FeedbackKind.IDEA.name, launched.single().getStringExtra(AppPages.EXTRA_FEEDBACK_KIND))
        val kept = FeedbackFiles.takePrefill(ctx)!!
        assertEquals(FeedbackKind.IDEA, kept.kind)
        assertEquals("a darker theme", kept.text)
    }

    @Test fun thePanelStaysWhenThePageDidntOpen() = runBlocking {
        opens = false
        feedback.run("r1", FeedbackCommand(FeedbackKind.BUG, null))
        assertEquals(1, launched.size)
        assertEquals(1, calls.size)
        attached = false
        feedback.run("r2", FeedbackCommand(FeedbackKind.BUG, null))
        assertEquals(1, launched.size)
        assertEquals(listOf("reply", "reply"), calls.map { it.substringBefore(' ') })
    }
}
