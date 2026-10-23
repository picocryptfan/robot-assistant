// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import app.heyrobot.assistant.conversation.ConversationService
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConversationCommand
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PanelStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.Paths
import app.heyrobot.sys.Transcripts
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Conversation mode by voice in the panel (PanelConversationMode, from PanelController in 1.1), the parts that start
 * nothing here: on the lock screen with starting there turned off Robot only says to unlock and opens nothing; the first
 * time (no consent yet) it starts nothing itself and opens the transcript screen as a start in the mode you asked for,
 * leaving only when the screen opened; "stop" stops and says where it was saved; a summary with no session or on the lock
 * screen only says to unlock. Then (1.1) a running session switched between its two faces (ConversationSession's state set
 * by reflection, as if one ran), a start after you said you'd tell people (the service asked to start, its screen opened,
 * as a start on the lock screen), and pause and resume's lines. No session really runs in these tests, so whether the
 * switch, the pause and the resume reach the session isn't seen here; no test covered the panel's side before the move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelConversationModeTest {
    private var locked = false
    private var settings = RobotSettings()
    private var opens = true
    private val launched = mutableListOf<Intent>()
    /** What the port was asked, in order: "reply <text> | <spoken>", "leave". */
    private val calls = java.util.concurrent.CopyOnWriteArrayList<String>() // written from coroutines while the test waits on it

    private val host = object : PanelHost {
        override fun hidePanel() { calls += "hide" }
        override fun launch(intent: Intent): Boolean { launched += intent; return opens }
        override fun startCapture(intent: Intent) = false
    }

    private val mode = PanelConversationMode(object : PanelConversationMode.Port {
        override val ctx get() = RuntimeEnvironment.getApplication()
        override val settings get() = this@PanelConversationModeTest.settings
        override val host get() = this@PanelConversationModeTest.host
        override fun locked() = locked
        override fun unlockFirst(p: Phrase) = Phrase.plain("[lock] " + p.render(Langs.EN))
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { calls += "reply ${text.render(Langs.EN)} | ${spoken.render(Langs.EN)}" }
        override suspend fun leaveForApp() { calls += "leave" }
    })

    @Test fun onTheLockScreenWithStartingThereOffItOnlySaysToUnlock() = runBlocking {
        locked = true
        settings = RobotSettings(convStartLocked = false)
        mode.run("r1", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply [lock] ") && calls[0].contains(" | [lock] "))
        assertTrue(launched.isEmpty())
    }

    @Test fun theFirstTimeTheTranscriptScreenOpensAsAStartInYourMode() = runBlocking {
        mode.run("r1", ConversationCommand.Start(ConvMode.INTERPRET))
        val tell = PanelStrings.tellPeopleFirst().render(Langs.EN)
        assertEquals(listOf("reply $tell | $tell", "leave"), calls)
        val i = launched.single()
        assertEquals(Transcripts.ACTION_START, i.action)
        assertEquals(ConvEntry.VOICE.name, i.getStringExtra(Transcripts.EXTRA_ENTRY))
        assertEquals(ConvMode.INTERPRET.name, i.getStringExtra(Transcripts.EXTRA_MODE))
        // Locked, with starting there allowed (the default): the same start, never the unlock line.
        calls.clear()
        launched.clear()
        locked = true
        opens = false
        mode.run("r2", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertEquals(listOf("reply $tell | $tell"), calls)
        assertEquals(ConvMode.TRANSCRIBE.name, launched.single().getStringExtra(Transcripts.EXTRA_MODE))
    }

    @Test fun stopSaysWhereItWasSavedAndOpensNothing() = runBlocking {
        mode.run("r1", ConversationCommand.Stop)
        val shown = PanelStrings.stoppedSaved("page" to app.heyrobot.core.i18n.UiLabel(app.heyrobot.core.i18n.UiStrings.transcripts)).render(Langs.EN)
        assertEquals(listOf("reply $shown | ${PanelStrings.stoppedSavedSpoken().render(Langs.EN)}"), calls)
        assertTrue(launched.isEmpty())
    }

    @Test fun aSummaryWithNoSessionOnlySaysToUnlock() = runBlocking {
        mode.run("r1", ConversationCommand.Summarize)
        assertEquals(1, calls.size)
        assertTrue(calls[0], calls[0].startsWith("reply [lock] ") && calls[0].contains(" | [lock] "))
        assertTrue(launched.isEmpty())
    }

    // ---------------------------------------------------------------- 1.1 tests: what the split's own tests left uncovered

    private val ctx = RuntimeEnvironment.getApplication()

    /** ConversationSession's state, set as if a session ran (nothing is started: no engine, no transcript). */
    @Suppress("UNCHECKED_CAST")
    private val session = ConversationSession::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(ConversationSession) as MutableStateFlow<ConversationSession.Ui>

    @After fun end() {
        session.value = ConversationSession.Ui()
        File(Paths.dir(ctx), ConsentRecord.FILE).delete()
    }

    @Test fun aRunningSessionSwitchesAndItsScreenOpens() = runBlocking {
        val lines = mutableListOf<String>()
        for ((now, asked) in listOf(ConvMode.TRANSCRIBE to ConvMode.INTERPRET, ConvMode.TRANSCRIBE to ConvMode.TRANSCRIBE,
            ConvMode.INTERPRET to ConvMode.INTERPRET, ConvMode.INTERPRET to ConvMode.TRANSCRIBE)) {
            session.value = ConversationSession.Ui(status = ConversationSession.Status.RUNNING, mode = now)
            calls.clear()
            launched.clear()
            mode.run("r1", ConversationCommand.Start(asked))
            assertEquals(2, calls.size)
            lines += calls[0]
            assertEquals("leave", calls[1])
            assertEquals(Transcripts.openIntent(ctx).action, launched.single().action)
        }
        assertEquals(listOf(PanelStrings.translatingNow(), PanelStrings.alreadyTranscribing(), PanelStrings.alreadyTranslating(), PanelStrings.backToTranscript())
            .map { it.render(Langs.EN) }.map { "reply $it | $it" }, lines)
        // Paused counts as running: no second session.
        session.value = ConversationSession.Ui(status = ConversationSession.Status.PAUSED)
        launched.clear()
        mode.run("r1", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertEquals(Transcripts.openIntent(ctx).action, launched.single().action)
        assertNull(shadowOf(ctx).nextStartedService)
    }

    @Test fun afterYouToldPeopleItStartsHereAndItsScreenOpens() = runBlocking {
        Transcripts.saveConsent(ctx, at = 5_000L)
        mode.run("r1", ConversationCommand.Start(ConvMode.INTERPRET))
        val service = shadowOf(ctx).nextStartedService
        assertEquals(ConvMode.INTERPRET.name, service.getStringExtra(ConversationService.EXTRA_MODE))
        assertEquals(ConvEntry.VOICE.name, service.getStringExtra(Transcripts.EXTRA_ENTRY))
        val started = PanelStrings.startedTranslating("consent" to ConsentRecord.line).render(Langs.EN)
        assertEquals(listOf("reply $started | $started", "leave"), calls)
        assertEquals(Transcripts.openIntent(ctx).action, launched.single().action)
        // On the lock screen its screen opens as a start, so it may show there.
        calls.clear()
        launched.clear()
        locked = true
        mode.run("r2", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertEquals(ConvMode.TRANSCRIBE.name, shadowOf(ctx).nextStartedService.getStringExtra(ConversationService.EXTRA_MODE))
        val i = launched.single()
        assertEquals(Transcripts.ACTION_START, i.action)
        assertEquals(ConvMode.TRANSCRIBE.name, i.getStringExtra(Transcripts.EXTRA_MODE))
        // A reminder newer than your yes: tell people first again, and nothing starts here.
        calls.clear()
        launched.clear()
        locked = false
        settings = RobotSettings(convReminderAt = 6_000L)
        mode.run("r3", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertNull(shadowOf(ctx).nextStartedService)
        val tell = PanelStrings.tellPeopleFirst().render(Langs.EN)
        assertEquals(listOf("reply $tell | $tell", "leave"), calls)
        assertEquals(Transcripts.ACTION_START, launched.single().action)
    }

    @Test fun pauseAndResumeSayWhatHappenedAndOpenNothing() = runBlocking {
        mode.run("r1", ConversationCommand.Pause)
        mode.run("r2", ConversationCommand.Resume)
        val paused = PanelStrings.paused("button" to UiLabel(UiStrings.resume)).render(Langs.EN)
        val goingOn = PanelStrings.goingOn().render(Langs.EN)
        assertEquals(listOf("reply $paused | $paused", "reply $goingOn | $goingOn"), calls)
        assertTrue(launched.isEmpty())
    }
}
