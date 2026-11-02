// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import java.io.File

/**
 * Reads the phrases to you, one at a time, and records you through the listener, the path Robot really hears. Started over adb only:
 * the manifest asks DUMP of whoever starts it. It isn't a Phone lock window (LockGate holds Robot's released windows; this build
 * never has a lock on; SpokenBuildTest lists it).
 */
class SpokenRecorderActivity : ComponentActivity() {
    private var at by mutableIntStateOf(0)
    private var recording by mutableStateOf(false)
    private lateinit var phrases: List<SpokenRecorder.Phrase>
    private lateinit var recorder: SpokenRecorder
    private lateinit var dir: File
    private var own: Listener? = null
    private val sink: (FloatArray) -> Unit = { recorder.frame(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceHub.init(this)
        dir = File(filesDir, SpokenProtocol.RECORDINGS)
        recorder = SpokenRecorder(dir)
        phrases = SpokenRecorder.phrases(assets.open("spoken/phrases.txt").bufferedReader().readLines())
        at = phrases.indexOfFirst { !File(dir, it.id + ".s16").isFile }.let { if (it < 0) phrases.size else it }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        setContent { RobotTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            val p = phrases.getOrNull(at)
            Text(stringResource(R.string.spoken_recorder_count, at, phrases.size))
            Text(p?.words ?: stringResource(R.string.spoken_recorder_done), style = MaterialTheme.typography.headlineMedium)
            if (p == null) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recording) {
                    Button(onClick = { stop(); recorder.keep(p.id); at++ }) { Text(stringResource(R.string.spoken_recorder_keep)) }
                    OutlinedButton(onClick = { stop(); recorder.discard() }) { Text(stringResource(R.string.spoken_recorder_again)) }
                } else {
                    Button(onClick = ::start) { Text(stringResource(R.string.spoken_recorder_record)) }
                    OutlinedButton(onClick = { at++ }) { Text(stringResource(R.string.spoken_recorder_skip)) }
                }
            }
        }
    }

    private fun start() {
        SpokenHarness.start(applicationContext).microphone(true)
        recorder.discard()
        val l = VoiceHub.listener?.takeIf { it.running } ?: Listener(this, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> }).also {
            if (!it.start()) { it.stop(); SpokenHarness.start(applicationContext).microphone(false); return }
            own = it
        }
        VoiceHub.conversationWake(true)
        l.addSink(sink)
        recording = true
    }

    private fun stop() {
        if (!recording) return
        recording = false
        (own ?: VoiceHub.listener)?.removeSink(sink)
        own?.stop()
        own = null
        VoiceHub.conversationWake(false)
        SpokenHarness.start(applicationContext).microphone(false)
    }

    override fun onStop() {
        super.onStop()
        if (recording) { stop(); recorder.discard() }
    }
}
