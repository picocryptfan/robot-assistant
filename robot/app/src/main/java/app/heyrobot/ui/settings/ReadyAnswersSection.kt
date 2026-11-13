// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.sys.ReadyAnswerStore
import app.heyrobot.ui.pref.SwitchPreference

/** Settings › Messages › Ready answers (idea 18): one switch, off by default, that says what it reads. Needs "Read my messages". */
@Composable
fun ReadyAnswersSection(messagesOn: Boolean) {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(ReadyAnswerStore.settings(ctx)) }
    SwitchPreference(stringResource(R.string.messages_ready), stringResource(R.string.messages_ready_detail), r.on, enabled = messagesOn) { on ->
        val n = r.copy(on = on)
        if (ReadyAnswerStore.save(ctx, n)) r = n
    }
}
