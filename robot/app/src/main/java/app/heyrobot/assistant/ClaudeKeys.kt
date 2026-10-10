package app.heyrobot.assistant

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import app.heyrobot.core.RLog
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.ClaudeKeyFiles
import app.heyrobot.ui.theme.RobotTheme
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
    private const val GCM_TAG_BITS = 128

    /** Shape of an Anthropic API key, checked before saving (a paste of something else is refused, not stored). */
    fun plausible(k: String): Boolean = ClaudeKeyFormat.plausible(k)

    /** "sk-ant-…AB12": shown in Settings. */
    fun mask(k: String): String = ClaudeKeyFormat.mask(k)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ClaudeKeyFiles.ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ClaudeKeyFiles.ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    /** Encrypts and saves [key]; false (nothing saved) if it can't. Background thread (Keystore and files). */
    fun save(ctx: Context, key: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(key.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        val out = ByteBuffer.allocate(1 + iv.size + ct.size).put(iv.size.toByte()).put(iv).put(ct).array()
        val f = ClaudeKeyFiles.secret(ctx)
        val tmp = java.io.File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        writeAtomic(ClaudeKeyFiles.meta(ctx), JSONObject().put("masked", mask(key)).put("saved", System.currentTimeMillis()).toString())
        RLog.i("claude_key", "op" to RLog.Code("saved"))
        true
    } catch (t: Throwable) {
        RLog.w("claude_key_save", t)
        false
    }

    /** The key, or null (none saved, or the Keystore key is gone: then [forget] removes it and you paste it again). */
    fun read(ctx: Context): String? {
        val f = ClaudeKeyFiles.secret(ctx)
        if (!f.isFile) return null
        return try {
            val b = ByteBuffer.wrap(f.readBytes())
            val iv = ByteArray(b.get().toInt()).also { b.get(it) }
            val ct = ByteArray(b.remaining()).also { b.get(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            RLog.w("claude_key_read", t)
            null
        }
    }

    /**
     * A key that can't be decrypted any more reads as no key: its files and the Keystore entry go, and the note left
     * behind lets Settings say why.
     */
    fun forget(ctx: Context) {
        ClaudeKeyFiles.delete(ctx)
        try { writeAtomic(ClaudeKeyFiles.meta(ctx), JSONObject().put("unreadable", true).toString()) } catch (t: Throwable) { RLog.w("claude_key_forget", t) }
    }

    /** Changes when the key file does: a cached client is rebuilt (or dropped) on the next turn. */
    fun stamp(ctx: Context): Long = ClaudeKeyFiles.secret(ctx).let { if (it.isFile) it.lastModified() xor it.length() else 0L }
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
                                    clearClipboard(k)
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

    /** The key was pasted: if the clipboard still holds exactly it, it is cleared (this window has focus, so it may read it). */
    private fun clearClipboard(key: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(this)?.toString()?.trim() == key) cm.clearPrimaryClip()
        } catch (t: Throwable) {
            RLog.w("claude_key_clip", t)
        }
    }
}
