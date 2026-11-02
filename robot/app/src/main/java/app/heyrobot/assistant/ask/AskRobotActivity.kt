// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.assistant.PanelLaunchActivity
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.conversation.ConversationSession
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.AskHeard
import app.heyrobot.core.ask.AskRefusal
import app.heyrobot.core.ask.CappedRead
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.files.ShareRules
import app.heyrobot.core.files.SharedRef
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.ui.common.filterObscuredTouches
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.voice.VoiceEngines
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// VOICE PROCESS, exported. "Ask Robot" (« Demander à Robot ») in another app's text-selection menu (PROCESS_TEXT) and share
// sheet (SEND: text, one image, one audio file): docs/plans/everyday-set-plan.md §7, D19, D20; job E7. What it does with a
// start is AskModel's; this is its window and the phone's side of it.
//
//   - Exactly PROCESS_TEXT and SEND, checked again here (an exported activity can be started with any action).
//   - Never over the lock screen (no showWhenLocked), out of Recents, no history; touches refused while another window
//     covers it. It shows only Robot's own words (a line, a progress bar, Cancel), never what was shared.
//   - Reads once, while it is open (a share's grant lasts as long as the activity): another app's content:// only, at most
//     the cap, within AskCaps.READ_MS; no persistable permission; nothing written.
//   - Never answers the sending app: no result is ever set, so the text-selection menu's text stays as it was (Q5).
//   - "Ask Robot in other apps" off disables this component (sys AskRobotDoor); it is read here again too.

class AskRobotActivity : ComponentActivity() {
    private var model: AskModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Refuses a tap while another window covers this one, and still reaches Phone lock, which covers this window (LS13).
        window.filterObscuredTouches()
        val i = intent
        // Made again after the share was handled (a change it doesn't handle): the share isn't read twice.
        if (savedInstanceState != null || (i?.action != Intent.ACTION_PROCESS_TEXT && i?.action != Intent.ACTION_SEND)) {
            finish()
            return
        }
        VoiceHub.init(this)
        val m = AskModel(PhonePort(this), AskRequest.of(i)).also { model = it }
        m.start()
        // Text goes straight to the panel: no window at all.
        if (m.ui is AskUi.Done) {
            finish()
            return
        }
        setContent {
            RobotTheme {
                val ui = m.ui
                LaunchedEffect(ui) { if (ui is AskUi.Done) finish() }
                BackHandler { m.close(); finish() }
                AskWindow(ui, onClose = { m.close(); finish() }, onSetup = { m.close(); setup(); finish() })
            }
        }
    }

    override fun onDestroy() {
        // Leaving (Cancel, Back, Home, the task removed): nothing more is read, and nothing reaches the panel.
        model?.close()
        super.onDestroy()
    }

    /** Robot's Assistant setup (in the main app, by class name), where it says how to make Robot your assistant. */
    private fun setup() {
        try {
            startActivity(Intent().setClassName(packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.SETUP)  // l10n: not text (a class name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            RLog.w("ask_setup", t)
        }
    }

    /** The phone's side: this process's lock state, settings, the sender's provider, :files, the speech model, the panel. */
    private class PhonePort(private val activity: ComponentActivity) : AskModel.Port {
        private val app = activity.applicationContext

        override fun gate(): AskRefusal? = when {
            PhoneLockNow.on(app) -> AskRefusal.PHONE_LOCK
            Assistant.keyguard(app) -> AskRefusal.LOCKED
            !EverydaySettingsStore.current(app).askRobot -> AskRefusal.OFF
            else -> null
        }

        override fun assistant(): Boolean? = when {
            RobotVoiceService.instance != null -> true
            Assistant.roleHeld(app) -> null
            else -> false
        }

        override fun allowed(uri: Uri): Boolean = ShareRules.check(sharedRef(uri), ::own) == null

        /** One of Robot's own providers: by its name, and by the package Android says owns it (Robot Files' share target asks the same). */
        private fun own(authority: String): Boolean {
            val pkg = app.packageName
            if (authority == pkg || authority.startsWith("$pkg.")) return true
            return try {
                app.packageManager.resolveContentProvider(authority, 0)?.packageName == pkg
            } catch (_: Exception) {
                false
            }
        }

        override fun read(uri: Uri, cap: Int): AskModel.Read {
            val signal = CancellationSignal()
            var opened: AssetFileDescriptor? = null
            fun once(): AskModel.Read {
                val a = app.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: return AskModel.Read.Unreadable
                opened = a
                return a.use {
                    // A provider that says how long it is: past the cap, refused unread.
                    if (a.declaredLength != AssetFileDescriptor.UNKNOWN_LENGTH && a.declaredLength > cap) AskModel.Read.TooLarge
                    else a.createInputStream().use { s ->
                        when (val g = CappedRead.read(s, cap)) {
                            is CappedRead.Got.Bytes -> AskModel.Read.Bytes(g.b)
                            CappedRead.Got.TooLarge -> AskModel.Read.TooLarge
                            CappedRead.Got.Empty -> AskModel.Read.Unreadable
                        }
                    }
                }
            }
            val job = FutureTask<AskModel.Read> { once() }
            Thread(job, "robot-ask-read").apply { isDaemon = true }.start()  // l10n: not text (a thread's name)
            return try {
                job.get(AskCaps.READ_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A sender that doesn't answer: Robot gives up and closes its end.
                signal.cancel()
                try { opened?.close() } catch (_: Exception) {}
                job.cancel(true)
                AskModel.Read.TimedOut
            } catch (e: ExecutionException) {
                RLog.w("ask_read", e.cause ?: e)
                AskModel.Read.Unreadable
            }
        }

        override fun photo(bytes: ByteArray): SharedPhoto.Read = SharedPhoto.read(app, bytes)

        override fun speech(): AskRefusal? {
            val d = VoiceEngines.segmentDecoder()
            return when {
                d == null || !d.available -> AskRefusal.NO_SPEECH_MODEL
                ConversationSession.running -> AskRefusal.BUSY
                else -> null
            }
        }

        override fun transcribe(bytes: ByteArray, cancelled: () -> Boolean): AskModel.Heard {
            val audio = SharedAudio.decode(bytes, cancelled = cancelled)
                ?: return AskModel.Heard.Not(AskRefusal.UNREADABLE, if (cancelled()) AskHeard.CANCELLED else AskHeard.FAILED)
            if (cancelled()) return AskModel.Heard.Not(AskRefusal.UNREADABLE, AskHeard.CANCELLED, audio.seconds)
            if (audio.samples.size < Pcm.RATE / 4) return AskModel.Heard.Not(AskRefusal.NO_WORDS, AskHeard.NO_WORDS, audio.seconds)
            speech()?.let { return AskModel.Heard.Not(it, AskHeard.FAILED, audio.seconds) }
            val decoder = VoiceEngines.segmentDecoder() ?: return AskModel.Heard.Not(AskRefusal.NO_SPEECH_MODEL, AskHeard.FAILED, audio.seconds)
            // Parakeet in pieces at the pauses, as conversation mode does (the Transcription check's own path), in memory only.
            val heard = try {
                ConversationEngine.transcribe(app, decoder, audio.samples, ConversationSession.yourLanguage(app))
            } catch (t: Throwable) {
                RLog.w("ask_transcribe", t)
                null
            } ?: return AskModel.Heard.Not(AskRefusal.UNREADABLE, AskHeard.FAILED, audio.seconds)
            val text = heard.first.trim()
            if (text.isEmpty()) return AskModel.Heard.Not(AskRefusal.NO_WORDS, AskHeard.NO_WORDS, audio.seconds)
            return AskModel.Heard.Words(text, audio.seconds, audio.cut)
        }

        override fun open(item: SharedItem): Boolean {
            val id = AskInbox.put(item, SystemClock.elapsedRealtime())
            activity.startActivity(Intent(activity, PanelLaunchActivity::class.java).putExtra(RobotVoiceService.ARG_SHARED, id))
            return true
        }

        override fun log(event: VoiceEvent, a: Int, b: Int) = VoiceLog.add(event, a, b)
    }
}

/** The window: a card over the sending app, with Robot's own words only. */
@Composable
fun AskWindow(ui: AskUi, onClose: () -> Unit, onSetup: () -> Unit) {
    if (ui is AskUi.Quiet || ui is AskUi.Done) return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.everyday_ask_label), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                when (ui) {
                    is AskUi.Working -> {
                        Text(stringResource(if (ui.kind == SharedKind.VOICE) R.string.everyday_ask_writing_down else R.string.everyday_ask_reading_photo),
                            style = MaterialTheme.typography.bodyLarge)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Buttons { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) } }
                    }
                    is AskUi.Refused -> {
                        Text(ui.why.str(), style = MaterialTheme.typography.bodyLarge)
                        Buttons { Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.everyday_ask_close)) } }
                    }
                    AskUi.NotAssistant -> {
                        Text(app.heyrobot.core.i18n.EverydayStrings.askNotAssistant().str(), style = MaterialTheme.typography.bodyLarge)
                        Buttons {
                            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.everyday_ask_close)) }
                            Button(onClick = onSetup, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.assistantSetup().str()) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** One URI as Android parsed it, both forms of its provider's name: ContentResolver opens the decoded one (F50, as Robot Files). */
internal fun sharedRef(uri: Uri) = SharedRef(uri.scheme, uri.authority, uri.encodedAuthority)
