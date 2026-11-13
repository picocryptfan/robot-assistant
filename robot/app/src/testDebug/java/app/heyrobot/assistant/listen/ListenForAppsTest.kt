// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.listen

import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import app.heyrobot.assistant.RobotRecognitionService
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.everyday.ListenForAppCode
import app.heyrobot.core.everyday.ListenRefusal
import app.heyrobot.core.everyday.ListenSheetCode
import app.heyrobot.voice.AppListen
import app.heyrobot.voice.SpeechToText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Listening for other apps (everyday-set-plan.md §6.2, E5), the coordinator the recognizer and the sheet share: a request
 * listens only from the next main-thread message (after Android's own check of the caller, which may cancel it first), one
 * at a time, with the wake word paused; Robot busy when it would start refuses it; Robot's own listening takes it over and
 * keeps the wake word paused; the app's stop and cancel reach only its own request; partial words only when it asked; the
 * voice log gets codes and tenths of a second. What core ListenForApps decides is core's ListenForAppsTest; the recognizer's
 * and the sheet's checks are pinned in ProcessIsolationTest.listeningForAppsChecksTheCaller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenForAppsTest {
    /** Speech to text's app path: each request's listener and options; stops and cancels by handle. */
    private class FakeStt : SpeechToText {
        override val available = true
        /** Robot listens itself: startForApp starts nothing. */
        var robotListens = false
        val apps = mutableListOf<Pair<SpeechToText.Listener, AppListen>>()
        val stopped = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val last: SpeechToText.Listener get() = apps.last().first
        override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) {}
        override fun stop() {}
        override fun cancel() {}
        override fun release() {}
        override fun startForApp(listener: SpeechToText.Listener, app: AppListen): Int? = if (robotListens) null else { apps += listener to app; apps.size }
        override fun stopForApp(handle: Int) { stopped += handle }
        override fun cancelForApp(handle: Int) { cancelled += handle }
    }

    /** What a request's owner heard, in order. */
    private class Heard : AppListening.Sink {
        val got = mutableListOf<String>()
        override fun ready() { got += "ready" }
        override fun speechStart() { got += "speech" }
        override fun level(dbfs: Float) { got += "level $dbfs" }
        override fun partial(text: String) { got += "partial $text" }
        override fun endOfSpeech() { got += "end" }
        override fun words(text: String) { got += "words $text" }
        override fun ended(end: AppListening.End, message: String?) { got += "ended $end" + (message?.let { " $it" } ?: "") }
    }

    private val looper = shadowOf(Looper.getMainLooper())
    private val stt = FakeStt()
    private var robotBusy = false
    /** Each pause (true) and let go (false) of the wake word. */
    private val wake = mutableListOf<Boolean>()
    private var now = 10_000L
    private val logs = mutableListOf<Triple<VoiceEvent, Int, Int>>()
    private val listening = AppListening(object : AppListening.Port {
        override val main = Handler(Looper.getMainLooper())
        override val stt: SpeechToText get() = this@ListenForAppsTest.stt
        override fun robotBusy() = this@ListenForAppsTest.robotBusy
        override fun pauseWake(on: Boolean) { wake += on }
        override fun now() = now
        override fun log(event: VoiceEvent, a: Int, b: Int) { logs += Triple(event, a, b) }
    })

    private val plain = AppListen(pauseMs = 1_000, partials = false)

    private fun open(from: AppListening.From = AppListening.From.RECOGNIZER, app: AppListen = plain, heard: Heard = Heard()): Pair<AppListening.Request, Heard> =
        listening.open(from, app, heard)!! to heard

    private fun idle() = looper.idle()

    @Test fun aRequestListensFromTheNextMessageWithTheWakeWordPausedAndGetsOnlyItsWords() {
        val (_, heard) = open()
        // Nothing listens inside the message that opened it (Android checks the caller after onStartListening returns).
        assertTrue(stt.apps.isEmpty())
        assertTrue(wake.isEmpty())
        idle()
        assertEquals(listOf(plain), stt.apps.map { it.second })
        assertEquals(listOf(true), wake)
        stt.last.onListening()
        stt.last.onLevel(-30f)
        stt.last.onSpeechStart()
        // The app didn't ask for words as they come: none go to it.
        stt.last.onPartial("hel")
        stt.last.onEndOfSpeech()
        now += 2_345
        stt.last.onFinal("hello")
        idle()
        assertEquals(listOf("ready", "level -30.0", "speech", "end", "words hello"), heard.got)
        assertEquals(listOf(true, false), wake)
        assertEquals(Triple(VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.DONE.id, 23), logs.single())
        // Over: the next request may open.
        assertNotNull(listening.open(AppListening.From.RECOGNIZER, plain, Heard()))
    }

    /** 1.3 (tidy-reword, review-after13-b): an app gets your words with fillers out and every correction as said. */
    @Test fun anAppGetsWordsWithoutFillersOnly() {
        for ((said, got) in listOf(
            "I'll be uh ten no fifteen minutes late" to "I'll be ten no fifteen minutes late",
            "I can't make it Friday, actually Saturday works" to "I can't make it Friday, actually Saturday works",
            "Thanks Sam, sorry Jim couldn't come" to "Thanks Sam, sorry Jim couldn't come",
            "I'm at the ER" to "I'm at the ER",
            "je serai là à euh cinq non six heures" to "je serai là à cinq non six heures",
        )) {
            val (_, heard) = open()
            idle()
            stt.last.onFinal(said)
            idle()
            assertEquals("words $got", heard.got.last())
        }
    }

    @Test fun wordsAsTheyComeOnlyWhenTheAppAskedForThem() {
        val (_, heard) = open(app = AppListen(pauseMs = 1_000, partials = true))
        idle()
        stt.last.onPartial("hel")
        stt.last.onFinal("hello")
        idle()
        assertEquals(listOf("partial hel", "words hello"), heard.got)
    }

    @Test fun androidRefusingTheCallerEndsTheRequestBeforeAnyAudio() {
        val (r, heard) = open()
        // Android's own check failed in the same message: it cancels before the request begins.
        listening.cancel(r)
        idle()
        assertTrue(stt.apps.isEmpty())
        assertTrue(wake.isEmpty())
        assertTrue(heard.got.isEmpty())
        assertEquals(Triple(VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.ANDROID.id, 0), logs.single())
        assertFalse(listening.busy())
    }

    @Test fun oneRequestAtATime() {
        open()
        assertTrue(listening.busy())
        assertNull(listening.open(AppListening.From.SHEET, plain, Heard()))
        idle()
        assertNull(listening.open(AppListening.From.RECOGNIZER, plain, Heard()))
        stt.last.onFinal("done")
        idle()
        assertFalse(listening.busy())
        // Robot's own state counts too.
        robotBusy = true
        assertTrue(listening.busy())
    }

    @Test fun robotBusyWhenTheRequestWouldStartRefusesItWithoutTouchingTheWakeWord() {
        val (_, heard) = open()
        // The panel opened, or Robot started talking, between the checks and the start.
        robotBusy = true
        idle()
        assertTrue(stt.apps.isEmpty())
        assertTrue(wake.isEmpty())
        assertEquals(listOf("ended BUSY"), heard.got)
        assertEquals(Triple(VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.BUSY.id, 0), logs.single())
    }

    @Test fun speechToTextNeverEndsRobotsOwnQuestionForAnApp() {
        stt.robotListens = true
        val (_, heard) = open()
        idle()
        assertEquals(listOf("ended BUSY"), heard.got)
        // Paused for the request, then let go again.
        assertEquals(listOf(true, false), wake)
    }

    @Test fun robotsOwnListeningTakesOverAndKeepsTheWakeWordPaused() {
        val (_, heard) = open()
        idle()
        stt.last.onListening()
        // The panel opened and listens: speech to text tells the request it's over.
        stt.last.onTakenOver()
        // Late words for it are dropped.
        stt.last.onFinal("late")
        idle()
        assertEquals(listOf("ready", "ended TAKEN"), heard.got)
        // Never let go: the panel paused the wake word for its own question.
        assertEquals(listOf(true), wake)
        assertEquals(ListenForAppCode.TAKEN.id, logs.single().second)
    }

    @Test fun theAppsStopAndCancelReachOnlyItsOwnRequest() {
        val (r, heard) = open()
        idle()
        listening.stop(r)
        assertEquals(listOf(1), stt.stopped)
        stt.last.onEndOfSpeech()
        stt.last.onFinal("so far")
        idle()
        assertEquals(listOf("end", "words so far"), heard.got)

        val (r2, heard2) = open()
        idle()
        listening.cancel(r2)
        assertEquals(listOf(2), stt.cancelled)
        assertEquals(listOf(true, false, true, false), wake)
        // Nothing reaches the app after it cancelled.
        stt.last.onFinal("late")
        idle()
        assertTrue(heard2.got.isEmpty())
        assertEquals(ListenForAppCode.CANCELLED.id, logs.last().second)
        // Ending twice changes nothing.
        listening.cancel(r2)
        listening.stop(r2)
        assertEquals(listOf(2), stt.cancelled)
        assertEquals(listOf(1), stt.stopped)
    }

    @Test fun aStopBeforeItBeganStopsItAsSoonAsItStarts() {
        val (r, _) = open()
        listening.stop(r)
        assertTrue(stt.stopped.isEmpty())
        idle()
        assertEquals(listOf(1), stt.stopped)
    }

    @Test fun eachWayItEndsReachesTheApp() {
        val ends = mutableListOf<String>()
        for ((call, end) in listOf<Pair<(SpeechToText.Listener) -> Unit, String>>(
            { l: SpeechToText.Listener -> l.onError(SpeechToText.Reason.NO_SPEECH, "Nothing heard.") } to "ended NO_SPEECH Nothing heard.",
            { l: SpeechToText.Listener -> l.onFinal("  ") } to "ended NO_MATCH",
            { l: SpeechToText.Listener -> l.onError(SpeechToText.Reason.MIC, "Mic busy.") } to "ended MIC Mic busy.",
            { l: SpeechToText.Listener -> l.onError(SpeechToText.Reason.UNAVAILABLE, "Download.") } to "ended NO_MODEL Download.",
            { l: SpeechToText.Listener -> l.onError(SpeechToText.Reason.ERROR, "Failed.") } to "ended ERROR Failed.",
        )) {
            val (_, heard) = open()
            idle()
            call(stt.last)
            idle()
            assertEquals(listOf(end), heard.got)
            ends += end
        }
        assertEquals(5, ends.size)
        assertEquals(listOf(ListenForAppCode.NOTHING, ListenForAppCode.NOTHING, ListenForAppCode.MIC, ListenForAppCode.NO_MODEL, ListenForAppCode.ERROR).map { it.id },
            logs.map { it.second })
        // Each paused the wake word and let it go.
        assertEquals(List(5) { listOf(true, false) }.flatten(), wake)
    }

    private fun openWatched(heard: Heard = Heard(), partials: Boolean = false): Heard {
        assertNotNull(listening.open(AppListening.From.RECOGNIZER, AppListen(1_000, partials), heard, callerMay = { callerMay }))
        idle()
        return heard
    }

    private var callerMay = true

    @Test fun aCallerThatLeavesTheFrontLosesTheRequestWithinASecondWithoutAudio() {
        val heard = openWatched()
        stt.last.onLevel(-30f)
        idle()
        // Still in front: nothing happens, however long.
        looper.idleFor(5, TimeUnit.SECONDS)
        assertEquals(listOf("level -30.0"), heard.got)
        assertTrue(stt.cancelled.isEmpty())
        // Leaves: with no audio coming at all, the check still runs.
        callerMay = false
        looper.idleFor(AppListening.WATCH_MS, TimeUnit.MILLISECONDS)
        assertEquals(listOf("level -30.0", "ended CALLER"), heard.got)
        assertEquals(listOf(1), stt.cancelled)
        assertEquals(listOf(true, false), wake)
        assertEquals(Triple(VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.CALLER.id, 0), logs.single())
        // Nothing after that, and the next request may open.
        stt.last.onFinal("what she said")
        idle()
        looper.idleFor(5, TimeUnit.SECONDS)
        assertEquals(2, heard.got.size)
        assertEquals(1, logs.size)
        assertNotNull(listening.open(AppListening.From.RECOGNIZER, plain, Heard()))
    }

    @Test fun noWordsGoToACallerThatHasLeftEvenBetweenChecks() {
        val heard = openWatched(partials = true)
        stt.last.onPartial("so far")
        idle()
        assertEquals(listOf("partial so far"), heard.got)
        callerMay = false
        stt.last.onPartial("and more")
        idle()
        assertEquals(listOf("partial so far", "ended CALLER"), heard.got)
        val final = openWatchedAfterCallerBack()
        callerMay = false
        stt.last.onFinal("the secret")
        idle()
        assertEquals(listOf("ended CALLER"), final.got)
    }

    private fun openWatchedAfterCallerBack(): Heard {
        callerMay = true
        return openWatched()
    }

    @Test fun robotStartingToTalkEndsARequestWithinASecondWithoutAudio() {
        for (from in listOf(AppListening.From.RECOGNIZER, AppListening.From.SHEET)) {
            robotBusy = false
            val before = stt.cancelled.size
            val (_, heard) = open(from = from)
            idle()
            looper.idleFor(5, TimeUnit.SECONDS)
            assertTrue(heard.got.isEmpty())
            robotBusy = true
            looper.idleFor(AppListening.WATCH_MS, TimeUnit.MILLISECONDS)
            assertEquals(listOf("ended BUSY"), heard.got)
            assertEquals(before + 1, stt.cancelled.size)
        }
    }

    @Test fun whatsHeardWhileRobotTalksGoesNowhereEvenBetweenChecks() {
        val (_, heard) = open(from = AppListening.From.RECOGNIZER, app = AppListen(1_000, true))
        idle()
        robotBusy = true
        stt.last.onPartial("your timer is")
        idle()
        stt.last.onFinal("your timer is done")
        idle()
        assertEquals(listOf("ended BUSY"), heard.got)
    }

    @Test fun aRequestWithNoCallerCheckRunsOnWhileRobotIsQuiet() {
        val (_, heard) = open(from = AppListening.From.SHEET)
        idle()
        looper.idleFor(5, TimeUnit.SECONDS)
        stt.last.onFinal("hello")
        idle()
        assertEquals(listOf("words hello"), heard.got)
    }

    @Test fun theSheetLogsItsOwnCodesAndNoDuration() {
        val (r, _) = open(from = AppListening.From.SHEET)
        idle()
        now += 5_000
        listening.cancel(r)
        val (_, heard) = open(from = AppListening.From.SHEET)
        idle()
        stt.last.onFinal("a city")
        idle()
        assertEquals(listOf("words a city"), heard.got)
        assertEquals(listOf(Triple(VoiceEvent.LISTEN_SHEET, ListenSheetCode.CANCELLED.id, 0), Triple(VoiceEvent.LISTEN_SHEET, ListenSheetCode.DONE.id, 0)), logs)
        listening.refused(AppListening.From.SHEET, ListenSheetCode.LOCKED)
        listening.refused(AppListening.From.RECOGNIZER, ListenForAppCode.CALLER)
        assertEquals(listOf(Triple(VoiceEvent.LISTEN_SHEET, ListenSheetCode.LOCKED.id, 0), Triple(VoiceEvent.LISTEN_FOR_APP, ListenForAppCode.CALLER.id, 0)), logs.takeLast(2))
    }

    @Test fun theRecognizerAnswersInAndroidsCodes() {
        // Off and not the assistant: unavailable, as the recognizer answered every request before the everyday set.
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, RobotRecognitionService.codeFor(ListenRefusal.OFF))
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, RobotRecognitionService.codeFor(ListenRefusal.NOT_ASSISTANT))
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, RobotRecognitionService.codeFor(ListenRefusal.NO_MODEL))
        assertEquals(SpeechRecognizer.ERROR_CLIENT, RobotRecognitionService.codeFor(ListenRefusal.OWN_AUDIO))
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, RobotRecognitionService.codeFor(ListenRefusal.LANGUAGE))
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RobotRecognitionService.codeFor(ListenRefusal.ROBOT_MIC))
        // A caller not in front or without the permission hears what Android's own check would say.
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RobotRecognitionService.codeFor(ListenRefusal.CALLER))
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RobotRecognitionService.codeFor(ListenRefusal.BUSY))
        assertEquals(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, RobotRecognitionService.codeFor(AppListening.End.NO_SPEECH))
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, RobotRecognitionService.codeFor(AppListening.End.NO_MATCH))
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RobotRecognitionService.codeFor(AppListening.End.TAKEN))
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RobotRecognitionService.codeFor(AppListening.End.BUSY))
        assertEquals(SpeechRecognizer.ERROR_AUDIO, RobotRecognitionService.codeFor(AppListening.End.MIC))
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RobotRecognitionService.codeFor(AppListening.End.CALLER))
        assertEquals(SpeechRecognizer.ERROR_SERVER, RobotRecognitionService.codeFor(AppListening.End.ERROR))
    }
}
