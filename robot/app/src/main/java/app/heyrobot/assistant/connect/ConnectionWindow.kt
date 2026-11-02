// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.connect

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.Abort
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnCheck
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.connect.ConnConsent
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnUrl
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.core.connect.WebhookPreset
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.Assistant
import app.heyrobot.core.backup.RestoreMerge
import app.heyrobot.sys.ConnFiles
import app.heyrobot.sys.ConnKeyFiles
import app.heyrobot.sys.ConnWindow
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A connection's window (docs/plans/skills-connections-plan.md §4.3, §6.3; C3): its secrets (a token, ntfy's topic, an
 * address that is itself the key, a header's value) are pasted here, encrypted here (ConnKeys) and never pass through the
 * main app, and Test runs here (ConnTest), because a key is decrypted only in this process and the main process never
 * loads the HTTP client. Like the helpers' window it runs in :voice, isn't exported (the main app starts it by class
 * name with the connection's id, which is not secret, ConnWindow), is FLAG_SECURE (no screenshots, recordings or task
 * thumbnail), kept out of autofill, clears the clipboard if it still holds what was just saved, and closes while the phone
 * is locked (§5.6: keys are never pasted locked; it is never shown over the lock screen). An address that is itself the
 * key is known only here: its origin (what cards and first-use answers name) goes to connections.json, a changed one
 * asking again (ConnConsent.withAddress), with the money mark a word of its path hints (WebhookPreset.addressMarks).
 */
class ConnectionWindow : ComponentActivity() {
    private val abort = Abort()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val id = intent.getStringExtra(ConnWindow.EXTRA_CONN)
        val mode = intent.getStringExtra(ConnWindow.EXTRA_MODE)
        // C9: a new webhook whose address is itself the key: kept in memory until its address is pasted and saved here.
        val saved = id?.let { ConnFiles.store(applicationContext).get(it) }
        val c = saved ?: newHook(id, mode, intent.getStringExtra(ConnWindow.EXTRA_NAME))
        isNewHook = saved == null && c != null
        val keyMode = mode == ConnWindow.MODE_KEY || mode == ConnWindow.MODE_NEW_HOOK
        pick = mode == ConnWindow.MODE_PICK && c?.kind == ConnKind.HOME_ASSISTANT
        // Nothing to do: no such connection, no secret to paste, or (Test) none for its kind yet.
        if (c == null || Assistant.keyguard(this) || (keyMode && c.secrets.isEmpty()) || (!keyMode && !ConnTest.offered(c))) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        setContent { RobotTheme { Window(c, keyMode) } }
        findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    override fun onResume() {
        super.onResume()
        // Locked while open: what was typed goes with the window.
        if (Assistant.keyguard(this)) finish()
    }

    override fun onDestroy() {
        // Stop: a Test under way is cancelled at once.
        abort.cancel()
        // Home Assistant's lists live only while the window is open (C5).
        if (isFinishing) HaPickerHold.forget(intent.getStringExtra(ConnWindow.EXTRA_CONN))
        super.onDestroy()
    }

    /** C9: after Test, pick a Home Assistant operation from its lists (HaOpPicker). */
    private var pick = false

    /** C9: opened for a new webhook, not in connections.json yet: the only window whose save may add a connection. */
    private var isNewHook = false

    /** MODE_NEW_HOOK's connection, not saved yet: a webhook [name]d by you, its address the key. Null when it can't be one. */
    private fun newHook(id: String?, mode: String?, name: String?): Connection? {
        if (mode != ConnWindow.MODE_NEW_HOOK || id == null || !SkillStep.validConnId(id) || name == null || !ConnCheck.validLine(name, ConnCheck.MAX_NAME)) return null
        return Connection(id, ConnKind.WEBHOOK, name, secretAddress = true)
    }

    private enum class Step { KEY, ASK, TESTING, RESULT, PICK }

    @Composable
    private fun Window(first: Connection, keyMode: Boolean) {
        var c by remember { mutableStateOf(first) }
        val parts = remember(c.id) { SecretPart.entries.filter { it in first.secrets } }
        val values = remember { mutableStateMapOf<SecretPart, String>() }
        var problem by remember { mutableStateOf<Pair<SecretPart?, String>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf(if (keyMode) Step.KEY else if (ConnTest.asksFirst(first)) Step.ASK else Step.TESTING) }
        var result by remember { mutableStateOf<ConnTestResult?>(null) }
        val scope = rememberCoroutineScope()
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        fun test() {
            step = Step.TESTING
            scope.launch {
                val now = withContext(Dispatchers.IO) { ConnFiles.store(applicationContext).get(c.id) } ?: c
                c = now
                result = withContext(Dispatchers.IO) { ConnTest.run(applicationContext, now, abort) }
                if (result == null) finish() else step = Step.RESULT
            }
        }
        LaunchedEffect(Unit) { if (step == Step.TESTING) test() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)).imePadding(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        Step.KEY -> {
                            Text(stringResource(R.string.conn_window_key_title, c.name), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.conn_window_key_safe), style = MaterialTheme.typography.bodyMedium, color = muted)
                            for (p in parts) {
                                val bad = problem?.takeIf { it.first == p }?.second
                                OutlinedTextField(
                                    value = values[p].orEmpty(), onValueChange = { values[p] = it.trim(); problem = null }, singleLine = true,
                                    label = { Text(label(c, p)) }, visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                    isError = bad != null, supportingText = { bad?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            problem?.takeIf { it.first == null }?.let { Text(it.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                TextButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) }
                                Button(onClick = {
                                    for (p in parts) problemOf(c, p, values[p].orEmpty())?.let { problem = p to it; return@Button }
                                    val typed = parts.associateWith { values[it].orEmpty() }
                                    busy = true
                                    scope.launch {
                                        // The Keystore and the files off the main thread.
                                        val saved = withContext(Dispatchers.IO) { save(c, typed) }
                                        busy = false
                                        if (saved == null) {
                                            problem = null to getString(R.string.conn_window_not_saved)
                                            return@launch
                                        }
                                        c = saved
                                        for (v in typed.values) ConnKeys.clearClipboard(this@ConnectionWindow, c.id, v)
                                        values.clear()
                                        setResult(Activity.RESULT_OK)
                                        if (!ConnTest.offered(c)) finish() else if (ConnTest.asksFirst(c)) step = Step.ASK else test()
                                    }
                                }, enabled = parts.all { !values[it].isNullOrEmpty() } && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.save().str()) }
                            }
                        }
                        Step.ASK -> {
                            Text(stringResource(R.string.conn_window_routing_title, c.name), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.conn_window_routing_sends, c.address?.origin.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                TextButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.cancel().str()) }
                                Button(onClick = { test() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_window_routing_go)) }
                            }
                        }
                        Step.TESTING -> {
                            Text(stringResource(R.string.conn_window_testing, c.name), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.conn_window_test_sends), style = MaterialTheme.typography.bodyMedium, color = muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                        }
                        Step.RESULT -> {
                            val r = result
                            Text(stringResource(if (r?.ok == true) R.string.conn_window_works else R.string.conn_window_didnt_work, c.name),
                                style = MaterialTheme.typography.headlineSmall)
                            Text(r?.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            val pickers = if (pick && r?.ok == true) HaPickerHold.get(c.id) else null
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                if (pickers != null) Button(onClick = { step = Step.PICK }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.conn_pick_next))
                                }
                                if (r?.needsKey == true && c.secrets.isNotEmpty()) OutlinedButton(onClick = { step = Step.KEY }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.conn_window_change_key))
                                }
                                if (r?.ok != true) OutlinedButton(onClick = { test() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.conn_window_test_again)) }
                                if (pickers == null) Button(onClick = { setResult(Activity.RESULT_OK); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(UiStrings.done().str()) }
                            }
                        }
                        Step.PICK -> {
                            val p = HaPickerHold.get(c.id)
                            if (p == null) { finish(); return@Column }
                            HaOpPicker(c, p, onCancel = { setResult(Activity.RESULT_CANCELED); finish() }) { op ->
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        ConnFiles.store(applicationContext).edit { all -> all.map { if (it.id == c.id && it.ops.size < ConnCheck.MAX_OPS) it.copy(ops = it.ops + op) else it } }
                                            ?.firstOrNull { it.id == c.id }?.op(op.id) != null
                                    }
                                    if (ok) { setResult(Activity.RESULT_OK); finish() } else problem = null to getString(R.string.conn_window_not_saved)
                                }
                            }
                            problem?.takeIf { it.first == null }?.let { Text(it.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    /** A field's label: what that part of [c] is. */
    @Composable
    private fun label(c: Connection, p: SecretPart): String = when (p) {
        SecretPart.TOKEN -> stringResource(if (c.auth == ConnAuth.Basic) R.string.conn_window_password else R.string.conn_window_token)
        SecretPart.TOPIC -> stringResource(R.string.conn_window_topic)
        SecretPart.ADDRESS -> stringResource(R.string.conn_window_address)
        SecretPart.HEADER -> stringResource(R.string.conn_window_header_value, (c.auth as? ConnAuth.Header)?.name.orEmpty())
    }

    /** What's wrong with [v] as [c]'s part [p], in words; null when it can be saved. */
    private fun problemOf(c: Connection, p: SecretPart, v: String): String? = when {
        p == SecretPart.ADDRESS -> {
            val u = ConnUrl.parse(v, whole = true)
            // https, a whole address Robot reads, and an origin D1 allows when it's typed as a number.
            if (u == null || !ConnSecrets.valid(p, v) || ConnRules.literal(u.originOnly()) is ConnRules.Lookup.Refused)
                ConnStrings.addressBad().render(AppLanguage.langs(this)) else null
        }
        p == SecretPart.TOPIC && !ConnSecrets.valid(p, v) -> getString(R.string.conn_window_bad_topic)
        p == SecretPart.TOKEN && c.auth == ConnAuth.Basic && !(ConnSecrets.valid(p, v) && ConnSecrets.basic(v)) -> getString(R.string.conn_window_bad_password)
        !ConnSecrets.valid(p, v) -> getString(R.string.conn_window_bad_value)
        else -> null
    }

    /**
     * Saves what was typed for [c] (background thread): an address that is itself the key first gives the connection its
     * origin (a changed one asks again), then every part goes into its key file. The connection as saved, or null.
     *
     * C12's L1: only for the connection this window showed, read again: still there (or this window's new webhook) and
     * reaching the same place (RestoreMerge.sameOrigin). Once the key is saved it is checked once more under the store's
     * lock, which Settings' Remove and its change of address take around their own key deletion: a connection removed or
     * re-addressed meanwhile never keeps the key (it is deleted again, and nothing is saved).
     */
    private fun save(c: Connection, typed: Map<SecretPart, String>): Connection? = saveKey(applicationContext, c, typed, isNewHook)

    companion object {
        /** [save]'s work, for its test: [newHook], the window was opened for a new webhook not in connections.json yet. */
        internal fun saveKey(ctx: android.content.Context, c: Connection, typed: Map<SecretPart, String>, newHook: Boolean): Connection? {
            val store = ConnFiles.store(ctx)
            val here = store.get(c.id)
            if (if (here == null) !newHook else !RestoreMerge.sameOrigin(here, c)) return null
            var now = here ?: c
            typed[SecretPart.ADDRESS]?.let { a ->
                val origin = ConnUrl.parse(a, whole = true)?.originOnly() ?: return null
                val marks = WebhookPreset.addressMarks(a)
                if (origin != now.address || marks != now.addressMarks || marks.any { m -> now.ops.any { m !in it.marks } }) {
                    var changed = false
                    val list = store.edit { all ->
                        val was = all.firstOrNull { it.id == c.id }
                        when {
                            // C9: a new webhook joins the file only now, with its address's origin.
                            was == null && newHook -> all + ConnConsent.withAddress(c, origin).copy(addressMarks = marks)
                            was == null || !RestoreMerge.sameOrigin(was, now) -> { changed = true; all }
                            else -> all.map { if (it.id != c.id) it else ConnConsent.withAddress(it, origin).let { x -> x.copy(addressMarks = marks, ops = x.ops.map { op -> op.copy(marks = op.marks + marks) }) } }
                        }
                    } ?: return null
                    if (changed) return null
                    now = list.firstOrNull { it.id == c.id } ?: return null
                }
            }
            val s = ConnSecrets(typed[SecretPart.TOKEN], typed[SecretPart.TOPIC], typed[SecretPart.ADDRESS], typed[SecretPart.HEADER])
            if (!ConnKeys.save(ctx, now.id, s)) return null
            val saved = now
            return store.locked {
                val after = store.get(saved.id)
                when {
                    after == null -> { ConnKeyFiles.removeAll(ctx, saved.id); null }
                    !RestoreMerge.sameOrigin(after, saved) -> { ConnKeyFiles.delete(ctx, saved.id); null }
                    else -> after
                }
            }
        }
    }
}
