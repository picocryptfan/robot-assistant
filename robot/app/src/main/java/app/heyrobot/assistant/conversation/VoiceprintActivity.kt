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
import androidx.compose.ui.unit.dp
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Enrolment
import app.heyrobot.core.LangLabel
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        saved.value = VoiceprintFiles.saved(this)
        setContent { RobotTheme { Screen() } }
    }

    override fun onPause() {
        super.onPause()
        // Never recording in the background: a recording you didn't finish is dropped.
        if (recording.value) {
            stopRecording()
            problem.value = "The recording stopped when you left. Read that sentence again."
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
        if (ConversationSession.running) {
            problem.value = "A transcription is running. Stop it first."
            return
        }
        if (!modelReady()) {
            problem.value = "This needs the speaker model: download it in Robot › Settings › Conversation."
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
                problem.value = "Robot can't use the microphone (allow it in the Robot app, or close the app that is recording)."
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
        if (audio.size < Pcm.RATE * Enrolment.MIN_CLIP_MS / 1000) {
            problem.value = "That was too short. Read the whole sentence, then tap Stop."
            return
        }
        busy.value = "Listening to your voice…"
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
                    speech == null || e == null && enough -> problem.value = "Robot couldn't use the speaker model. Try again in a moment."
                    e == null -> problem.value = "Robot heard too little speech. Read the sentence again, a little closer to the phone."
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
        busy.value = "Saving…"
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
                    r is Enrolment.Result.NotAlike -> problem.value = "The three recordings don't sound like one voice (someone else spoke, or it was " +
                        "noisy). Read them again, alone, somewhere quiet."
                    r is Enrolment.Result.Ok -> problem.value = "Your voiceprint couldn't be saved on this phone."
                    else -> problem.value = "Robot needs all three sentences. Read them again."
                }
            }
        }
    }

    private fun deleteVoiceprint() {
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
        val sentences = remember { Enrolment.sentences(LangLabel.yours()) }
        var confirmDelete by remember { mutableStateOf(false) }
        Scaffold(topBar = {
            TopAppBar(title = { Text("Your voice") }, navigationIcon = { IconButton(onClick = { finish() }) { Icon(RIcons.Back, "Back") } })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Read three short sentences so Robot knows your voice: in transcripts, your lines then say “You”. Optional.",
                    style = MaterialTheme.typography.bodyLarge)
                Text("Robot keeps only a voiceprint (192 numbers made from your recordings, not the recordings), encrypted on this phone. " +
                    "A voiceprint is biometric data: it is used only to label your lines, never sent anywhere, and one tap deletes it. " +
                    "The recordings stay in memory and are dropped right away.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (saved.value) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (justSaved.value) "Saved. Your lines will say “You” from the next transcript." else "Your voiceprint is saved.",
                            style = MaterialTheme.typography.bodyLarge)
                        Text("Reading the sentences again replaces it.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(RIcons.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete my voiceprint")
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
                    Text("Sentence ${k + 1} of ${sentences.size}", style = MaterialTheme.typography.titleSmall)
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Text(sentences[k], Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text("Hold the phone as you would at a table, and read in your normal voice. Recording stops by itself after " +
                        "${Enrolment.MAX_CLIP_MS / 1000} seconds.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!recording.value) Button(onClick = { startRecording() }, enabled = busy.value == null, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(RIcons.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (saved.value && k == 0) "Record again" else "Record")
                        } else Button(onClick = { finishClip() }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(RIcons.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                        }
                        if (k > 0 && !recording.value) TextButton(onClick = { work.execute { forget() }; done.intValue = 0 }, enabled = busy.value == null) { Text("Start over") }
                    }
                }
                Spacer(Modifier.heightIn(min = 24.dp))
            }
        }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your voiceprint?") },
            text = { Text("Robot stops recognising your voice at once; lines already labelled “You” stay as they are. You can read the sentences again any time.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; deleteVoiceprint() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    companion object {
        /** A recording is kept to this many samples at most (the auto-stop is a little earlier). */
        private const val MAX_SAMPLES = (Enrolment.MAX_CLIP_MS / 1000 + 1) * Pcm.RATE
    }
}
