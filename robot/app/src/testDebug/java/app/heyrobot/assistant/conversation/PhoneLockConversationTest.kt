// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.assistant.PanelConversationMode
import app.heyrobot.assistant.PanelHost
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvStop
import app.heyrobot.core.ConversationCommand
import app.heyrobot.core.LangPair
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.TimeWindow
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptIds
import app.heyrobot.core.TranscriptWriter
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.LockSettings
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.lock.LockEngine
import app.heyrobot.lock.LockStore
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Paths
import app.heyrobot.sys.Transcripts
import app.heyrobot.voice.AudioFrames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import app.heyrobot.core.TranscriptKeyAliases
import app.heyrobot.sys.SoftListsKeys
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * 1.1.2, FA8g (phone-lock-design §5.2): nothing records behind Phone lock's cover. During a phone lock the Transcribe
 * tile opens no screen and no microphone, the transcript screen opened to start (a shortcut, Home, Transcripts, Resume
 * after a crash) starts no service, the session itself refuses before anything else, and the panel's "start
 * transcribing" starts nothing, even with "Start from the lock screen" on; each refusal leaves one line in a
 * notification. A session that is on when a lock starts stops at its next tick or at :lock's word, and what it heard is
 * saved. Outside a lock, each of these goes on as before. No real session runs here (no voice engines): the stop is
 * driven through the session's own tick with a real transcript writer set in place, as if one ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLockConversationTest {

    /** seal-transcripts: Robolectric has no Android Keystore; the transcripts' store takes soft keys. */
    @org.junit.Before fun transcriptKeys() { Transcripts.testKeys(SoftListsKeys(setOf(TranscriptKeyAliases.UNLOCKED))) }
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = ctx as Application
    private val notices get() = ctx.getSystemService(NotificationManager::class.java)

    private fun field(name: String) = ConversationSession::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val session get() = field("_state").get(ConversationSession) as MutableStateFlow<ConversationSession.Ui>

    @Before fun setUp() {
        // The session is one object for the whole run: it belongs to this test's application.
        field("app").set(ConversationSession, app)
        session.value = ConversationSession.Ui()
    }

    @After fun tearDown() {
        field("writer").set(ConversationSession, null)
        field("source").set(ConversationSession, null)
        session.value = ConversationSession.Ui()
        File(Paths.dir(ctx), ConsentRecord.FILE).delete()
        lockOff()
    }

    /** Phone lock on, for you, with a Time of day lock open now. */
    private fun lockOn() {
        val now = LocalTime.now().truncatedTo(ChronoUnit.MINUTES)
        val l = PhoneLock("lk_conv0", "Homework", LockRule.Time(TimeWindow(now.minusHours(1), now.plusHours(1)), DayOfWeek.entries.toSet()))
        assertNotNull(LockStore.editLocks(ctx) { LockFile(LockSettings(on = true), listOf(l)) })
        LockEngine.refresh(ctx)
        assertTrue(PhoneLockNow.on(ctx))
    }

    private fun lockOff() {
        File(LockStore.dir(ctx), LockStore.STATE).delete()
        File(LockStore.dir(ctx), LockStore.LOCKS).delete()
        LockEngine.refresh(ctx)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The text of the notification Robot left about a phone lock, or null. */
    private fun lockNotice(): String? = shadowOf(notices).allNotifications.firstNotNullOfOrNull { n ->
        n.extras.getCharSequence("android.text")?.toString()?.takeIf {
            it == ctx.getString(R.string.conv_phone_lock_closed) || it == ctx.getString(R.string.conv_phone_lock_stopped)
        }
    }

    /** The tile's PendingIntent for the transcript screen, if it made one (it is how the tile opens it). */
    private fun tilePending(): PendingIntent? = PendingIntent.getActivity(ctx, 5_030,
        Intent(Transcripts.ACTION_START).setClassName(ctx.packageName, Transcripts.ACTIVITY),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)

    // ---------------------------------------------------------------- the Transcribe tile

    @Test fun theTileDuringALockOpensNoScreenAndNoMicrophone() {
        lockOn()
        val tile = Robolectric.buildService(ConversationTileService::class.java).create().get()
        tile.onClick()
        idle()
        assertNull("the tile made no way into the transcript screen", tilePending())
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(ConversationSession.running)
        assertEquals(ctx.getString(R.string.conv_phone_lock_closed), lockNotice())
    }

    @Test fun theTileOutsideALockOpensTheTranscriptScreenAsBefore() {
        val tile = Robolectric.buildService(ConversationTileService::class.java).create().get()
        tile.onClick()
        idle()
        assertNotNull(tilePending())
        assertNull(lockNotice())
    }

    // ---------------------------------------------------------------- the transcript screen, opened to start

    @Test fun theTranscriptScreenOpenedToStartDuringALockStartsNoService() {
        Transcripts.saveConsent(ctx, at = 5_000L)
        lockOn()
        for (entry in listOf(ConvEntry.TILE, ConvEntry.SHORTCUT, ConvEntry.APP)) {
            val a = Robolectric.buildActivity(ConversationActivity::class.java, Transcripts.startIntent(ctx, entry.name)).create().get()
            idle()
            assertTrue("$entry: the screen opened only to start closes again", a.isFinishing)
            assertNull("$entry: no microphone service", shadowOf(app).nextStartedService)
        }
        // Resume after a crash is a start too.
        val resume = Intent(Transcripts.ACTION_RESUME).setClassName(ctx.packageName, Transcripts.ACTIVITY).putExtra(Transcripts.EXTRA_ID, "t_20261028_1200_abcd")
        Robolectric.buildActivity(ConversationActivity::class.java, resume).create()
        idle()
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(ctx.getString(R.string.conv_phone_lock_closed), lockNotice())
    }

    @Test fun theTranscriptScreenOutsideALockStartsTheServiceAsBefore() {
        Transcripts.saveConsent(ctx, at = 5_000L)
        val a = Robolectric.buildActivity(ConversationActivity::class.java, Transcripts.startIntent(ctx, ConvEntry.TILE.name)).create().get()
        idle()
        assertFalse(a.isFinishing)
        val service = shadowOf(app).nextStartedService
        assertEquals(ConversationService.ACTION_START, service?.action)
        assertNull(lockNotice())
    }

    // ---------------------------------------------------------------- the session: every way in ends there

    /** ConversationSession.start as the service calls it; waits for its control thread; the words it failed with. */
    private fun startSession(): String? {
        var failed: String? = null
        val done = java.util.concurrent.CountDownLatch(1)
        ConversationSession.start(ctx, ConvMode.TRANSCRIBE, ConvEntry.TILE, null) { failed = it; done.countDown() }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return failed
    }

    @Test fun aSessionStartDuringALockIsRefusedFirst() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        lockOn()
        assertEquals(ctx.getString(R.string.conv_phone_lock_closed), startSession())
        assertEquals(ConversationSession.Status.IDLE, ConversationSession.state.value.status)
        assertEquals(ctx.getString(R.string.conv_phone_lock_closed), ConversationSession.state.value.notice)
        assertEquals(ctx.getString(R.string.conv_phone_lock_closed), lockNotice())
    }

    @Test fun aSessionStartOutsideALockGoesOnAsBefore() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // No speech model in a test: it gets as far as before, past the lock.
        assertEquals(ctx.getString(R.string.conv_needs_asr), startSession())
        assertNull(lockNotice())
    }

    // ---------------------------------------------------------------- a lock starting mid-recording

    /** A microphone that is open and not silenced. */
    private val mic = object : AudioFrames {
        override fun addSink(sink: (FloatArray) -> Unit) {}
        override fun removeSink(sink: (FloatArray) -> Unit) {}
    }

    /** A session as if it ran: a real transcript with one line heard, its writer and microphone in place. */
    private fun running(): TranscriptWriter {
        val now = System.currentTimeMillis()
        val w = TranscriptWriter.start(Transcripts.store(ctx), TranscriptIds.new(LocalDateTime.now()), now, ConvMode.TRANSCRIBE, LangPair.DEFAULT)!!
        assertNotNull(w.line(now) { i -> TranscriptEvent.Line(i, 0.0, 1.5, "S1", "en", "See you Friday.") })
        field("writer").set(ConversationSession, w)
        field("source").set(ConversationSession, mic)
        session.value = ConversationSession.Ui(ConversationSession.Status.RUNNING, w.id, ConvMode.TRANSCRIBE, w.meta.createdAt)
        return w
    }

    /** The session's one-second tick, run once here. */
    private fun tick() = (field("tick").get(ConversationSession) as Runnable).run()

    @Test fun aLockStartingMidRecordingStopsItAndKeepsWhatItHeard() {
        val w = running()
        lockOn()
        tick()
        val ui = ConversationSession.state.value
        assertFalse("stopped", ui.active)
        assertNull(field("writer").get(ConversationSession))
        assertEquals(w.id, ui.savedId)
        assertEquals(ctx.getString(R.string.conv_phone_lock_stopped), ui.notice)
        val store = Transcripts.store(ctx)
        assertEquals(SessionStatus.ENDED, store.meta(w.id)?.status)
        assertEquals(listOf("See you Friday."), store.load(w.id)?.lines?.map { it.text })
        assertEquals(ctx.getString(R.string.conv_phone_lock_stopped), lockNotice())
    }

    @Test fun aPausedSessionStopsTooAndNothingResumesDuringTheLock() {
        val w = running()
        session.value = session.value.copy(status = ConversationSession.Status.PAUSED)
        lockOn()
        ConversationSession.phoneLockCheck()
        // The control thread: wait until it has run the check.
        val deadline = System.currentTimeMillis() + 10_000
        while (ConversationSession.state.value.active && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(ConversationSession.state.value.active)
        assertEquals(SessionStatus.ENDED, Transcripts.store(ctx).meta(w.id)?.status)
    }

    @Test fun outsideALockTheTickLeavesTheSessionRunning() {
        val w = running()
        tick()
        assertTrue(ConversationSession.state.value.active)
        assertEquals(w, field("writer").get(ConversationSession))
        assertNull(lockNotice())
        ConversationSession.stop(ConvStop.USER)
        val deadline = System.currentTimeMillis() + 10_000
        while (field("writer").get(ConversationSession) != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun lockTellsTheVoiceProcessWhenALockStarts() {
        lockOn()
        val said = shadowOf(app).broadcastIntents.filter { it.action == "app.heyrobot.CMD" }.map { it.getStringExtra("op") }
        assertTrue(said.toString(), "phone_lock" in said)
    }

    // ---------------------------------------------------------------- by voice, in the panel

    private val replies = mutableListOf<String>()
    private val opened = mutableListOf<Intent>()

    private val panel = PanelConversationMode(object : PanelConversationMode.Port {
        override val ctx get() = this@PhoneLockConversationTest.ctx
        override val settings get() = RobotSettings(convStartLocked = true)
        override val host get() = object : PanelHost {
            override fun hidePanel() {}
            override fun launch(intent: Intent): Boolean { opened += intent; return true }
            override fun startCapture(intent: Intent) = false
        }
        override fun locked() = Assistant.locked(ctx)
        override fun unlockFirst(p: Phrase) = Phrase.plain("[lock] " + p.render(Langs.EN))
        override fun localReply(replyId: String, text: Phrase, spoken: Phrase) { replies += text.render(Langs.EN) }
        override suspend fun leaveForApp() {}
    })

    @Test fun byVoiceDuringALockNothingStartsOrResumes() = runBlocking {
        Transcripts.saveConsent(ctx, at = 5_000L)
        lockOn()
        panel.run("r1", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        panel.run("r2", ConversationCommand.Start(ConvMode.INTERPRET))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(opened.isEmpty())
        assertEquals(2, replies.size)
        assertTrue(replies.toString(), replies.all { it.startsWith("[lock] ") })
        replies.clear()
        panel.run("r3", ConversationCommand.Resume)
        assertTrue(replies.single().startsWith("[lock] "))
    }

    @Test fun byVoiceOutsideALockItStartsAsBefore() = runBlocking {
        Transcripts.saveConsent(ctx, at = 5_000L)
        panel.run("r1", ConversationCommand.Start(ConvMode.TRANSCRIBE))
        assertEquals(ConversationService.ACTION_START, shadowOf(app).nextStartedService?.action)
    }
}
