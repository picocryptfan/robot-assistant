// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.R
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.AppLanguage
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import androidx.compose.ui.res.stringResource
import android.Manifest
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
import app.heyrobot.BuildConfig
import app.heyrobot.core.Abort
import app.heyrobot.core.RLog
import app.heyrobot.core.SecretRead
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebKeyFormat
import app.heyrobot.core.web.WebLimits
import app.heyrobot.core.web.WebSettings
import app.heyrobot.helpers.web.SearchClient
import app.heyrobot.helpers.web.WebTransport
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.WebStore
import app.heyrobot.sys.WebTest
import app.heyrobot.sys.WebWindow
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Web search's set-up window (0.9, design A4.1): the chosen service's API key (Brave, Kagi, Mojeek) is pasted
 * here, encrypted here and never passes through the main app, and Test sends the fixed query "test" once and says what
 * came back. It runs in the :voice process like the panel (the main process never loads the HTTP client or reads a key),
 * isn't exported (the main app starts it by class name), is FLAG_SECURE and kept out of autofill, and clears the
 * clipboard if it still holds the key just saved. What Test said goes to web_test.json for Settings: the service and its
 * line, never an address or a key.
 */
class WebSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val s = WebStore.load(applicationContext)
        val service = s.service
        if (service == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val keyMode = intent.getStringExtra(WebWindow.EXTRA_MODE) == WebWindow.MODE_KEY && service.needsKey
        setContent { RobotTheme { Window(s, service, keyMode) } }
    }

    private enum class Step { KEY, TESTING, RESULT }

    @Composable
    private fun Window(s: WebSettings, service: SearchService, keyMode: Boolean) {
        var key by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf(if (keyMode) Step.KEY else Step.TESTING) }
        var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        val scope = rememberCoroutineScope()
        val langs = LocalLangs.current
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        fun test() {
            step = Step.TESTING
            scope.launch {
                result = withContext(Dispatchers.IO) { test(s, service) }
                step = Step.RESULT
            }
        }
        LaunchedEffect(Unit) { if (!keyMode) test() }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)).imePadding(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        Step.KEY -> {
                            Text(stringResource(R.string.panel_web_key_title, service.title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.panel_web_key_where, WebKeyFormat.whereText(service)?.str().orEmpty()),
                                style = MaterialTheme.typography.bodyMedium, color = muted)
                            OutlinedTextField(
                                value = key, onValueChange = { key = it.trim(); problem = null }, singleLine = true, label = { Text(stringResource(R.string.panel_web_api_key)) },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                isError = problem != null, supportingText = { problem?.let { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                TextButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_cancel)) }
                                Button(onClick = {
                                    WebKeyFormat.problemText(key)?.let {
                                        problem = it.render(langs)
                                        return@Button
                                    }
                                    val k = key
                                    busy = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { WebKeys.save(applicationContext, service, k, WebKeyFormat.mask(k)) }
                                        busy = false
                                        if (!ok) {
                                            problem = getString(R.string.panel_web_key_not_saved)
                                            return@launch
                                        }
                                        WebKeys.clearClipboard(this@WebSearchActivity, service, k)
                                        key = ""
                                        setResult(Activity.RESULT_OK)
                                        test()
                                    }
                                }, enabled = key.isNotEmpty() && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_web_save)) }
                            }
                        }
                        Step.TESTING -> {
                            Text(stringResource(R.string.panel_web_testing, service.label), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.panel_web_test_sends), style = MaterialTheme.typography.bodyMedium, color = muted)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                        }
                        Step.RESULT -> {
                            val r = result
                            Text(stringResource(if (r?.first == true) R.string.panel_web_works else R.string.panel_web_didnt_work, service.title), style = MaterialTheme.typography.headlineSmall)
                            Text(r?.second.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                                if (service.needsKey && r?.first != true) OutlinedButton(onClick = { step = Step.KEY }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_web_change_key)) }
                                OutlinedButton(onClick = { test() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_web_test_again)) }
                                Button(onClick = { setResult(Activity.RESULT_OK); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.panel_done)) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Test: the fixed query once, with the key decrypted here; its line kept for Settings (background thread). 1.1: the
     * window and Settings show it in the menus' language (WebTest keeps it without one).
     */
    private fun test(s: WebSettings, service: SearchService): Pair<Boolean, String> {
        val ctx = applicationContext
        val r: Pair<Boolean, Phrase> = when {
            !Assistant.granted(ctx, Manifest.permission.INTERNET) -> false to WebFail.NO_NETWORK_PERMISSION.textPhrase(service)
            else -> {
                val key = if (!service.needsKey) null else when (val k = WebKeys.read(ctx, service)) {
                    is SecretRead.Present -> k.value
                    is SecretRead.Unavailable -> return false to ctx.getString(R.string.panel_web_key_unreadable)
                    else -> null
                }
                if (service.needsKey && key == null) false to WebFail.KEY_MISSING.textPhrase(service)
                else try {
                    SearchClient(s, key, BuildConfig.VERSION_NAME, WebTransport(), Abort(WebLimits.TOTAL_MS)).testText()
                } catch (t: Throwable) {
                    RLog.w("web_test", t)
                    false to WebFail.SERVICE.textPhrase(service)
                }
            }
        }
        RLog.i("web_test", "svc" to service.code, "ok" to r.first)
        WebTest.save(ctx, WebTest(service, r.first, r.second, System.currentTimeMillis()))
        return r.first to r.second.render(AppLanguage.langs(ctx))
    }
}
