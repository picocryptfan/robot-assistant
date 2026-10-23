// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Enrolment
import app.heyrobot.R
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.VoiceprintFiles
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.voice.AudioFrames
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.VoiceEngines
import java.util.concurrent.Executors

// VOICE PROCESS. Settings › Conversation › Your voice (docs/conversation-mode.md 3.3, phase 5.3): you read three short
// sentences; each recording's speech is turned into a voice embedding by the speaker model, the three are checked to be
// one voice and averaged into your voiceprint, which is encrypted with an Android Keystore key and saved (Voiceprints).
// Sessions then seed "Me" with it, so your lines say "You". The recordings stay in memory and are dropped as soon as
// their embedding is made; the embeddings are dropped once saved or when you leave. Not exported; the main app opens it
// by class name, and deletes the voiceprint itself (file and key) on your word.

class VoiceprintActivity : ComponentActivity() {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-voiceprint") }
    private val main = Handler(Looper.getMainLooper())

    // The recording: frames from the shared listener, or a capture of its own (as the Transcription check).
    private val recLock = Any()
    private var recorded = ArrayList<FloatArray>()
    private var recSamples = 0
    private var source: AudioFrames? = null
    private var own: Listener? = null
    private var recStart = 0L
    private val sink: (FloatArray) -> Unit = { x ->
        synchronized(recLock) {
            if (recSamples < MAX_SAMPLES) {
                recorded += x.copyOf()
                recSamples += x.size
            }
        }
    }
    private val autoStop = Runnable { if (recording.value) finishClip() }

    private val recording = mutableStateOf(false)
    private val busy = mutableStateOf<String?>(null)
    private val problem = mutableStateOf<String?>(null)
    /** The embeddings of the sentences read so far (memory only). */
    private val voices = ArrayList<FloatArray>()
    private val done = mutableIntStateOf(0)
    private val saved = mutableStateOf(false)
    private val justSaved = mutableStateOf(false)
    /** The phone is locked: read again when the screen comes back, and at each action (a lock can begin meanwhile). */
    private val lockedNow = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        lockedNow.value = Assistant.locked(this)
        saved.value = VoiceprintFiles.saved(this)
        setContent { RobotTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        lockedNow.value = Assistant.locked(this)
    }

    override fun onResume() {
        super.onResume()
        lockedNow.value = Assistant.locked(this)
    }

    /** Read now, at an action: false (and the screen shows "Unlock your phone…") while the phone is locked. */
    private fun unlocked(): Boolean {
        val l = Assistant.locked(this)
        lockedNow.value = l
        return !l
    }

    override fun onPause() {
        super.onPause()
        // Never recording in the background: a recording you didn't finish is dropped.
        if (recording.value) {
            stopRecording()
            problem.value = getString(R.string.conv_vp_left)
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoStop)
        work.execute { forget() }
        work.shutdown()
        super.onDestroy()
    }

    /** Work thread: the embeddings go. */
    private fun forget() {
        synchronized(voices) {
            voices.forEach { it.fill(0f) }
            voices.clear()
        }
    }

    private fun modelReady(): Boolean = VoiceEngines.speakerEmbedder()?.available == true

    private fun startRecording() {
        if (!unlocked()) return
        if (ConversationSession.running) {
            problem.value = getString(R.string.conv_running_stop_first)
            return
        }
        if (!modelReady()) {
            problem.value = getString(R.string.conv_vp_needs_model)
            return
        }
        synchronized(recLock) { recorded = ArrayList(); recSamples = 0 }
        val hub = VoiceHub.listener?.takeIf { it.running }
        if (hub != null) {
            hub.addSink(sink)
            source = hub
        } else {
            val l = Listener(this, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
            if (!l.start()) {
                l.stop()
                problem.value = getString(R.string.conv_no_mic)
                return
            }
            l.addSink(sink)
            own = l
            source = l
        }
        problem.value = null
        recording.value = true
        recStart = SystemClock.elapsedRealtime()
        // Nobody's "hey robot" opens the panel mid-sentence.
        VoiceHub.conversationWake(true)
        main.postDelayed(autoStop, Enrolment.MAX_CLIP_MS.toLong())
    }

    private fun stopRecording(): FloatArray {
        main.removeCallbacks(autoStop)
        source?.removeSink(sink)
        source = null
        own?.let { l -> Thread({ l.stop() }, "robot-voiceprint-mic").start() }
        own = null
        recording.value = false
        if (!ConversationSession.running) VoiceHub.conversationWake(false)
        return synchronized(recLock) {
            val all = FloatArray(recSamples)
            var k = 0
            for (x in recorded) { val n = minOf(x.size, all.size - k); System.arraycopy(x, 0, all, k, n); k += n }
            recorded = ArrayList()
            recSamples = 0
            all
        }
    }

    /** Stop: the speech of this recording becomes one embedding (work thread); the audio goes. */
    private fun finishClip() {
        val audio = stopRecording()
        // Locked meanwhile: the recording is dropped, as when you leave the screen.
        if (!unlocked()) {
            audio.fill(0f)
            return
        }
        if (audio.size < Pcm.RATE * Enrolment.MIN_CLIP_MS / 1000) {
            problem.value = getString(R.string.conv_vp_too_short)
            return
        }
        busy.value = getString(R.string.conv_vp_listening)
        work.execute {
            val speech = try { ConversationEngine.speechOnly(this, audio) } catch (t: Throwable) { RLog.w("voiceprint_vad", t); null }
            audio.fill(0f)
            val enough = speech != null && speech.size >= Pcm.RATE * 2
            val e = if (enough) VoiceEngines.speakerEmbedder()?.embed(speech!!) else null
            speech?.fill(0f)
            // Kept here, on this thread, so leaving the screen (which forgets them on this thread too) never misses one.
            val n = if (e != null) synchronized(voices) { voices += e; voices.size } else -1
            runOnUiThread {
                busy.value = null
                when {
                    speech == null || e == null && enough -> problem.value = getString(R.string.conv_vp_model_failed)
                    e == null -> problem.value = getString(R.string.conv_vp_too_little)
                    else -> {
                        done.intValue = n
                        if (n >= Enrolment.CLIPS) makeVoiceprint()
                    }
                }
            }
        }
    }

    /** The recordings, checked to be one voice, averaged, encrypted and saved. */
    private fun makeVoiceprint() {
        if (!unlocked()) {
            work.execute { forget() }
            done.intValue = 0
            return
        }
        busy.value = getString(R.string.conv_vp_saving)
        val app = applicationContext
        work.execute {
            val r = synchronized(voices) { Enrolment.make(VoiceBundle.SPEAKERS.id, voices.toList()) }
            val ok = r is Enrolment.Result.Ok && Voiceprints.save(app, r.voiceprint)
            if (r is Enrolment.Result.Ok) r.voiceprint.vector.fill(0f)
            forget()
            runOnUiThread {
                busy.value = null
                done.intValue = 0
                when {
                    ok -> {
                        saved.value = true
                        justSaved.value = true
                    }
                    r is Enrolment.Result.NotAlike -> problem.value = getString(R.string.conv_vp_not_alike)
                    r is Enrolment.Result.Ok -> problem.value = getString(R.string.conv_vp_not_saved)
                    else -> problem.value = getString(R.string.conv_vp_needs_three)
                }
            }
        }
    }

    private fun deleteVoiceprint() {
        if (!unlocked()) return
        val app = applicationContext
        work.execute {
            VoiceprintFiles.delete(app)
            // This process's running session (if any) stops using it, and the main app's flag follows.
            ConversationSession.voiceprintDeleted()
            Cmd.send(app, Cmd.VOICEPRINT_DELETED)
            runOnUiThread { saved.value = false; justSaved.value = false }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val sentences = remember { Enrolment.sentences(ConversationSession.yourLanguage(this)) }
        var confirmDelete by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.conv_vp_title)) },
                navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, stringResource(R.string.conv_back)) } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (lockedNow.value) {
                    Text(stringResource(R.string.conv_unlock_screen), style = MaterialTheme.typography.bodyLarge)
                    return@Column
                }
                Text(stringResource(R.string.conv_vp_intro), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.conv_vp_privacy),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (saved.value) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (justSaved.value) R.string.conv_vp_saved_now else R.string.conv_vp_saved), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.conv_vp_again_replaces), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.conv_vp_delete_mine))
                        }
                    }
                }
                problem.value?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                busy.value?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val k = done.intValue
                if (k < sentences.size) {
                    Text(stringResource(R.string.conv_vp_sentence, k + 1, sentences.size), style = MaterialTheme.typography.titleSmall)
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Text(sentences[k], Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(stringResource(R.string.conv_vp_hold, Enrolment.MAX_CLIP_MS / 1000), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!recording.value) Button(onClick = { startRecording() }, enabled = busy.value == null, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(RIcons.Mic, null); Spacer(Modifier.width(8.dp)); Text(stringResource(if (saved.value && k == 0) R.string.conv_vp_record_again else R.string.conv_record))
                        } else Button(onClick = { finishClip() }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(RIcons.Stop, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.conv_stop))
                        }
                        if (k > 0 && !recording.value) TextButton(onClick = { work.execute { forget() }; done.intValue = 0 }, enabled = busy.value == null) { Text(stringResource(R.string.conv_vp_start_over)) }
                    }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
        if (confirmDelete && !lockedNow.value) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.conv_vp_delete_title)) },
            text = { Text(stringResource(R.string.conv_vp_delete_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; deleteVoiceprint() }) { Text(stringResource(R.string.conv_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.conv_cancel)) } },
        )
    }

    companion object {
        /** A recording is kept to this many samples at most (the auto-stop is a little earlier). */
        private const val MAX_SAMPLES = (Enrolment.MAX_CLIP_MS / 1000 + 1) * Pcm.RATE
    }
}
