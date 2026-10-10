package app.heyrobot.assistant

import android.app.Activity
import android.content.Context
import android.os.Bundle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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

// VOICE PROCESS. The Anthropic API key (docs/ask-claude.md "Key and settings"): pasted once into ClaudeKeyActivity, which
// runs in the :voice process like the panel, encrypted with an Android Keystore key (AES-GCM, not exportable), and
// decrypted only here, just before the Claude client is made. It never appears in a log, a crash text, the history, an
// intent or an IPC message: the main app only reads whether one is saved and its masked form (sys/ClaudeFiles.kt).

object ClaudeKeys {
    private const val GCM_TAG_BITS = 128

    /** Shape of an Anthropic API key, checked before saving (a paste of something else is refused, not stored). */
    fun plausible(k: String): Boolean = k.startsWith("sk-ant-") && k.length in 40..400 && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** "sk-ant-…AB12": shown in Settings. */
    fun mask(k: String): String = "sk-ant-…" + k.takeLast(4)

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

    /** Encrypts and saves [key]; false (nothing saved) if it can't. */
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

    /** The key, or null (none saved, or the Keystore key is gone: then the owner pastes it again). */
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
        setContent {
            RobotTheme {
                var key by remember { mutableStateOf("") }
                var problem by remember { mutableStateOf<String?>(null) }
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
                                when {
                                    !ClaudeKeys.plausible(key) -> problem = "That doesn't look like an Anthropic API key (sk-ant-…)."
                                    !ClaudeKeys.save(applicationContext, key) -> problem = "The key couldn't be saved on this phone."
                                    else -> { key = ""; setResult(Activity.RESULT_OK); finish() }
                                }
                            }, enabled = key.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                        }
                    }
                }
                }
            }
        }
    }
}
