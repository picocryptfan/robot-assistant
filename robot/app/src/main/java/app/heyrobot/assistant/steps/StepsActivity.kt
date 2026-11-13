// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heyrobot.R
import app.heyrobot.core.steps.StepWord

// VOICE PROCESS. Step by step's window (docs/plans/step-by-step-plan.md): the step in large text, the screen kept on,
// Back, Again, Next and Stop, the timer button while one is offered, and the microphone mark while StepEar listens.
// Leaving it ends the steps. The Phone lock covers it (LockGate.ROBOT_COVERED).
class StepsActivity : ComponentActivity() {
    /** The session this window shows (StepVoice): leaving it ends that session only. */
    private var serial = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serial = intent.getIntExtra(EXTRA_SERIAL, 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                val ui by StepVoice.ui.collectAsState()
                LaunchedEffect(ui.on) { if (!ui.on) finish() }
                StepsScreen(ui, StepVoice::tap)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        serial = intent.getIntExtra(EXTRA_SERIAL, serial)
        StepVoice.shown(true, serial)
    }

    override fun onStart() { super.onStart(); StepVoice.shown(true, serial) }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) StepVoice.shown(false, serial)
    }

    companion object {
        const val EXTRA_SERIAL = "app.heyrobot.steps.SERIAL" // l10n: not text (an extra's key)
    }
}

@Composable
fun StepsScreen(ui: StepVoice.Ui, onTap: (StepWord) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.read_steps_count, ui.index + 1, ui.total), style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(ui.text, fontSize = 34.sp, lineHeight = 42.sp)
            }
            if (ui.listening) {
                val mic = stringResource(R.string.read_steps_listening)
                Text("🎙 $mic", Modifier.semantics { contentDescription = mic }, style = MaterialTheme.typography.bodyLarge) // l10n: not text (the microphone mark)
            }
            if (ui.offerS != null) Button({ onTap(StepWord.YES) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_steps_timer)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onTap(StepWord.BACK) }, Modifier.weight(1f)) { Text(stringResource(R.string.read_steps_back)) }
                OutlinedButton({ onTap(StepWord.AGAIN) }, Modifier.weight(1f)) { Text(stringResource(R.string.read_steps_again)) }
                Button({ onTap(StepWord.NEXT) }, Modifier.weight(1f)) { Text(stringResource(R.string.read_steps_next)) }
            }
            OutlinedButton({ onTap(StepWord.STOP) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.read_steps_stop)) }
            Spacer(Modifier.padding(4.dp))
        }
    }
}
