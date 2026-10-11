package app.heyrobot.assistant

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.heyrobot.core.ClaudeKeyFormat
import app.heyrobot.sys.ClaudeKeyFiles
import app.heyrobot.sys.KeystoreSecret
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// VOICE PROCESS. The Anthropic API key (docs/ask-claude.md "Key and settings"): pasted once into ClaudeKeyActivity, which
// runs in the :voice process like the panel, encrypted with an Android Keystore key (AES-GCM, not exportable), and
// decrypted only here, just before the Claude client is made. It never appears in a log, a crash text, the history, an
// intent or an IPC message: the main app only reads whether one is saved and its masked form (sys/ClaudeFiles.kt).
// The key window is FLAG_SECURE (no screenshots, recordings or task thumbnail), kept out of autofill, and clears the
// clipboard if it still holds the key just saved.

object ClaudeKeys {
    /** Shape of an Anthropic API key, checked before saving (a paste of something else is refused, not stored). */
    fun plausible(k: String): Boolean = ClaudeKeyFormat.plausible(k)

    /** "sk-ant-…AB12": shown in Settings. */
    fun mask(k: String): String = ClaudeKeyFormat.mask(k)

    /** Encrypts and saves [key]; false (nothing saved) if it can't. Background thread (Keystore and files). */
    fun save(ctx: Context, key: String): Boolean = ClaudeKeyFiles.store.save(ctx, key, mask(key))

    /** The key, or null (none saved, or the Keystore key is gone: then [forget] removes it and you paste it again). */
    fun read(ctx: Context): String? = ClaudeKeyFiles.store.read(ctx)

    /**
     * A key that can't be decrypted any more reads as no key: its files and the Keystore entry go, and the note left
     * behind lets Settings say why.
     */
    fun forget(ctx: Context) = ClaudeKeyFiles.store.forget(ctx)

    /** Changes when the key file does: a cached client is rebuilt (or dropped) on the next turn. */
    fun stamp(ctx: Context): Long = ClaudeKeyFiles.store.stamp(ctx)
}

/**
 * "Add key" in Settings › Ask Claude opens this small window in the :voice process: the key is pasted here, encrypted
 * here and never passes through the main app. Not exported; the main app starts it by class name.
 */
class ClaudeKeyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or task thumbnail of this window, and nothing in it for an autofill service
        // to offer to save.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContent {
            RobotTheme {
                var key by remember { mutableStateOf("") }
                var problem by remember { mutableStateOf<String?>(null) }
                var saving by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f)).imePadding(), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Claude API key", style = MaterialTheme.typography.headlineSmall)
                        Text("Create a key at console.anthropic.com (Settings › API keys) and paste it here. API use is paid with API credits, " +
                            "which a claude.ai Pro or Max plan doesn't include. Robot encrypts the key on this phone and only the assistant reads it.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = key, onValueChange = { key = it.trim(); problem = null }, singleLine = true, label = { Text("API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            isError = problem != null, supportingText = { problem?.let { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                            TextButton(onClick = { setResult(Activity.RESULT_CANCELED); finish() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                            Button(onClick = {
                                if (!ClaudeKeys.plausible(key)) {
                                    problem = "That doesn't look like an Anthropic API key (sk-ant- followed by letters, digits, - and _)."
                                    return@Button
                                }
                                val k = key
                                saving = true
                                scope.launch {
                                    // The Keystore and the files off the main thread; a Claude-mode conversation under way keeps its client.
                                    val ok = withContext(Dispatchers.IO) { ClaudeKeys.save(applicationContext, k).also { if (it) ClaudeLink.keyChanged(applicationContext) } }
                                    saving = false
                                    if (!ok) {
                                        problem = "The key couldn't be saved on this phone."
                                        return@launch
                                    }
                                    KeystoreSecret.clearClipboard(this@ClaudeKeyActivity, k)
                                    key = ""
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                }
                            }, enabled = key.isNotEmpty() && !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                        }
                    }
                }
                }
            }
        }
        // Compose's own autofill bridge too (the field is a password field).
        findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
}
