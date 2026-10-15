// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.heyrobot.core.RLog
import app.heyrobot.core.SecretRead
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperFacts
import app.heyrobot.core.helpers.HelperKeyFormat
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.helpers.Protocol
import app.heyrobot.helpers.HelperProbe
import app.heyrobot.helpers.ProbeResult
import app.heyrobot.sys.HelperWindow
import app.heyrobot.sys.HelperFactsStore
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.sys.SettingsStore
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A helper's connection window (0.6.0; "Add key" for Claude before): its API key is pasted here, encrypted here and never
 * passes through the main app, and Test connection runs here, because the key is decrypted only in this process and the
 * main process never loads the HTTP client. It runs in the :voice process like the panel, isn't exported (the main app
 * starts it by class name with the helper's id, which is not secret), is FLAG_SECURE (no screenshots, recordings or task
 * thumbnail) and kept out of autofill, and clears the clipboard if it still holds the key just saved. What Test
 * connection found (the server's port, its models, whether a tiny question worked) goes to the helper's facts file for
 * Settings; never the key or any words.
 */
class HelperConnectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val id = intent.getStringExtra(EXTRA_HELPER)
        val helper = id?.let { SettingsStore.load(applicationContext).helper(it) }
        if (helper == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val mode = intent.getStringExtra(EXTRA_MODE)
        val keyMode = mode != MODE_TEST && mode != MODE_ACTIONS
        setContent { RobotTheme { Window(helper, keyMode, actionsFirst = mode == MODE_ACTIONS) } }
        findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    @androidx.compose.runtime.Composable
    private fun Window(h: HelperConfig, keyMode: Boolean, actionsFirst: Boolean = false) {
        val p = h.presetOf
        var key by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf(if (keyMode) Step.KEY else Step.TESTING) }
        var result by remember { mutableStateOf<ProbeResult?>(null) }
        val scope = rememberCoroutineScope()
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        fun test(actions: Boolean) {
            step = Step.TESTING
            scope.launch {
                result = withContext(Dispatchers.IO) { probe(h, actions) }
                step = Step.RESULT
            }
        }
        LaunchedEffect(Unit) { if (!keyMode) test(actions = actionsFirst) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)).imePadding(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        Step.KEY -> {
                            Text(if (p.keyRequired) "${h.name}: API key" else "${h.name}: key (if your server asks for one)", style = MaterialTheme.typography.headlineSmall)
                            val where = p.keyWhere?.let { "Create a key at $it and paste it here. " }.orEmpty()
                            Text(where + (p.billing?.let { "$it " }.orEmpty()) + "Robot encrypts the key on this phone and only the assistant reads it.",
                                style = MaterialTheme.typography.bodyMedium, color = muted)
                            OutlinedTextField(
                                value = key, onValueChange = { key = it.trim(); problem = null }, singleLine = true, label = { Text("API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                isError = problem != null, supportingText = { (problem ?: HelperKeyFormat.warning(p, key).takeIf { key.isNotEmpty() })?.let { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                TextButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                                Button(onClick = {
                                    if (!HelperKeyFormat.plausible(p, key)) {
                                        problem = HelperKeyFormat.refusal(p)
                                        return@Button
                                    }
                                    val k = key
                                    busy = true
                                    scope.launch {
                                        // The Keystore and the files off the main thread; a conversation under way keeps its client.
                                        val ok = withContext(Dispatchers.IO) {
                                            HelperKeys.save(applicationContext, h.id, k, HelperKeyFormat.mask(p, k)).also { if (it) HelperLink.keyChanged(applicationContext, h.id) }
                                        }
                                        busy = false
                                        if (!ok) {
                                            problem = "The key couldn't be saved on this phone."
                                            return@launch
                                        }
                                        HelperKeys.clearClipboard(this@HelperConnectionActivity, h.id, k)
                                        key = ""
                                        setResult(Activity.RESULT_OK)
                                        // Claude's key is checked by the first question, as before; any other helper's now.
                                        if (p.protocol == Protocol.ANTHROPIC) finish() else test(actions = false)
                                    }
                                }, enabled = key.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                            }
                        }
                        Step.TESTING -> {
                            Text("Testing ${h.name}…", style = MaterialTheme.typography.headlineSmall)
                            Text(if (h.home) "Robot looks for your server at the address you gave (on the usual ports when you gave none), lists its models and asks it to reply “OK”."
                                else "Robot lists ${p.company}'s models for your key and asks the model to reply “OK” (a fraction of a cent).",
                                style = MaterialTheme.typography.bodyMedium, color = muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                        }
                        Step.RESULT -> {
                            val r = result
                            Text(when {
                                r?.ok == true -> "${h.name} is ready"
                                keyNote != null -> "${h.name}: API key"
                                // 1.0.2: refused before connecting, so nothing was asked (not "didn't answer").
                                r?.failure == app.heyrobot.core.HelperFailure.KEY_OVER_HTTP -> "${h.name}: key not sent"
                                else -> "${h.name} didn't answer"
                            }, style = MaterialTheme.typography.headlineSmall)
                            Text(r?.let { words(h, it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                if (r?.ok == true && h.actions) OutlinedButton(onClick = { test(actions = true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check actions") }
                                // 1.0.1: never for an http server (a key never goes over http): the words say what to do instead.
                                if (r?.needsKey == true && !cleartext(h, r)) OutlinedButton(onClick = { step = Step.KEY }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add key") }
                                Button(onClick = { setResult(Activity.RESULT_OK); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                            }
                        }
                    }
                }
            }
        }
    }

    private enum class Step { KEY, TESTING, RESULT }

    /**
     * What the saved key's read said when it gave no key (Test connection then sends nothing): it couldn't be read just now
     * (kept), or it could never be read again (removed). Null when it was read, or none is saved.
     */
    @Volatile private var keyNote: String? = null

    /** Test connection, its facts kept for Settings (background thread). */
    private suspend fun probe(h: HelperConfig, actions: Boolean): ProbeResult {
        val ctx = applicationContext
        keyNote = null
        val candidates = h.presetOf.baseUrl?.let { listOf(it) } ?: h.baseUrl?.let { LanRule.normalize(it)?.candidates }.orEmpty()
        if (candidates.isEmpty()) return ProbeResult(failure = app.heyrobot.core.HelperFailure.NOT_LOCAL)
        // GrapheneOS's Network toggle is off for Robot: say so rather than "isn't answering" (nothing could be sent).
        if (!HelperLink.networkAllowed(ctx)) return ProbeResult(failure = app.heyrobot.core.HelperFailure.NO_NETWORK_PERMISSION)
        val key = when (val r = HelperKeys.read(ctx, h.id)) {
            is SecretRead.Present -> r.value
            SecretRead.Absent -> null
            // Kept: nothing is tested without it (a server that answers without a key would look fine), and nothing is
            // written to the facts; Test connection again in a moment reads it again.
            is SecretRead.Unavailable -> {
                keyNote = HelperWords(HelperWho.of(h)).keyUnavailable
                return ProbeResult(failure = app.heyrobot.core.HelperFailure.NO_KEY)
            }
            // Removed as it was read: Settings says so, the panel's client goes, and Add key is offered.
            is SecretRead.Invalidated -> {
                keyNote = HelperWords(HelperWho.of(h)).keyUnreadable
                HelperLink.keyChanged(ctx, h.id)
                return ProbeResult(failure = app.heyrobot.core.HelperFailure.NO_KEY, needsKey = true)
            }
        }
        val r = try {
            HelperProbe.run(h, key, candidates, question = true, actions = actions) { dollars -> HelperSpendStore.add(ctx, h.id, dollars) }
        } catch (t: Throwable) {
            RLog.w("helper_probe", t)
            ProbeResult(failure = app.heyrobot.core.HelperFailure.UNKNOWN)
        }
        HelperFactsStore.update(ctx, h.id) { old ->
            val model = h.model.ifEmpty { r.models.firstOrNull().orEmpty() }
            var f = HelperFacts(forAddress = h.baseUrl, base = r.base ?: old.base.takeIf { old.forAddress == h.baseUrl }, server = r.server ?: old.server,
                models = r.models.ifEmpty { old.models }, answered = r.answered, model = model, actions = r.actions ?: old.actions.takeIf { old.model == model },
                failure = r.failure, needsKey = r.needsKey, checkedAt = System.currentTimeMillis(), ms = r.ms, dropped = old.dropped)
            for (e in r.dropped) f = f.withDropped(model, e)
            f
        }
        // The panel's client is made again for the address found.
        HelperLink.keyChanged(ctx, h.id)
        return r
    }

    /** What Test connection found, in words (no address, no key). */
    private fun words(h: HelperConfig, r: ProbeResult): String = buildString {
        keyNote?.let {
            append(it)
            return@buildString
        }
        val failure = r.failure
        if (failure != null) {
            append(failure.say(HelperWho.of(h)))
            if (r.needsKey) append(if (cleartext(h, r)) " This server asks for a key, which Robot sends only over https." else " This server asks for a key.")
            if (r.base != null && r.models.isNotEmpty()) append(" It lists ${r.models.size} model${if (r.models.size == 1) "" else "s"}.")
            return@buildString
        }
        append(if (r.models.isEmpty()) "Connected." else "Connected: ${r.models.size} model${if (r.models.size == 1) "" else "s"} to choose from in Settings.")
        if (r.answered) append(" A test question was answered in ${String.format(Locale.US, "%.1f", r.ms / 1000.0)} s.")
        if (!r.usage && r.answered && h.home) append(" The server doesn't report its token counts.")
        when (r.actions) {
            true -> append(" The model can use Robot's actions (always behind a card).")
            false -> append(" The model didn't use Robot's action when asked: actions are off for it.")
            null -> {}
        }
        if (r.dropped.isNotEmpty()) append(" Robot will leave out what the server didn't take: ${r.dropped.joinToString { it.name.lowercase().replace('_', ' ') }}.")
        if (cleartext(h, r)) append(" This connection isn't encrypted: anyone on your network can read it.")
    }

    /** The address you gave, or the one found, is http:// (no key ever goes there: core LanRule.keyOverHttp). */
    private fun cleartext(h: HelperConfig, r: ProbeResult): Boolean = h.baseUrl?.startsWith("http://") == true || r.base?.startsWith("http://") == true

    companion object {
        const val EXTRA_HELPER = HelperWindow.EXTRA_HELPER
        const val EXTRA_MODE = HelperWindow.EXTRA_MODE
        const val MODE_KEY = HelperWindow.MODE_KEY
        const val MODE_TEST = HelperWindow.MODE_TEST
        const val MODE_ACTIONS = HelperWindow.MODE_ACTIONS
    }
}
