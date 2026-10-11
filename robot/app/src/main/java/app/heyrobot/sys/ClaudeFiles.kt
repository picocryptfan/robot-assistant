package app.heyrobot.sys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import app.heyrobot.core.ClaudeAppLink
import app.heyrobot.core.ClaudeSpend
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate

// Main and voice processes. Ask Claude's files and the Claude app's intents, without the API key: the key is encrypted
// and decrypted in the :voice process only (assistant/ClaudeKeys.kt). The main app reads whether a key is saved and its
// masked form, and can delete it (then tells the voice process); it never reads the key itself.

object ClaudeKeyFiles {
    /** The Android Keystore key that encrypts the API key (AES-GCM, not exportable). */
    const val ALIAS = "robot_claude_api_key"

    /** IV and ciphertext, written and read only by the :voice process. */
    fun secret(ctx: Context) = File(Paths.dir(ctx), "claude_key.bin")
    /** Not secret: the masked form ("sk-ant-…AB12") and when it was saved, for Settings. */
    fun meta(ctx: Context) = File(Paths.dir(ctx), "claude_key.json")

    fun saved(ctx: Context): Boolean = secret(ctx).isFile

    fun masked(ctx: Context): String? = if (!saved(ctx)) null else
        readOrNull(meta(ctx))?.let { try { JSONObject(it).optString("masked").takeIf { m -> m.isNotEmpty() } } catch (_: Exception) { null } } ?: "Saved"

    /** A saved key couldn't be decrypted and was removed (Settings says so until a new key is saved). */
    fun unreadable(ctx: Context): Boolean = !saved(ctx) &&
        (readOrNull(meta(ctx))?.let { try { JSONObject(it).optBoolean("unreadable") } catch (_: Exception) { false } } ?: false)

    /**
     * "Delete key" (background thread): the files and the Keystore key go. The caller then tells the voice process
     * ([Cmd.CLAUDE_KEY]), which drops its client.
     */
    fun delete(ctx: Context) {
        secret(ctx).delete()
        meta(ctx).delete()
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (t: Throwable) {
            RLog.w("claude_key_delete", t)
        }
    }
}

/**
 * Today's spend on helpers (0.6.0; Claude's alone before): written by the :voice process as each request's usage arrives
 * (so a crash mid-answer loses none of it), shown in Settings. The :voice process keeps its copy in memory once read.
 * helpers_spend.json holds the total and each helper's part; without it, 0.4/0.5's claude_spend.json is Claude's spend.
 * Claude's part is still written to claude_spend.json for one release, so a downgrade keeps today's Claude spend.
 */
object HelperSpendStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "helpers_spend.json")
    private fun legacy(ctx: Context) = File(Paths.dir(ctx), "claude_spend.json")

    /** [VOICE] The copy in memory, once read. */
    @Volatile private var memo: HelperSpend? = null

    /** From the files (Settings, in the main process). Background thread. */
    fun load(ctx: Context): HelperSpend = HelperSpend.fromJson(readOrNull(file(ctx)), readOrNull(legacy(ctx)))

    /** Today's spend on all helpers, and each one's (Settings). */
    fun today(ctx: Context): HelperSpend = load(ctx).on(LocalDate.now())

    /** [VOICE] Today's spend, from memory, or from the files the first time. Background thread. */
    fun current(ctx: Context): HelperSpend = memo ?: load(ctx).also { memo = it }

    /** [VOICE] Null until [current] has read the files. */
    fun cached(): HelperSpend? = memo

    /** [VOICE] Background thread. */
    @Synchronized
    fun add(ctx: Context, helper: String, dollars: Double): HelperSpend {
        val today = LocalDate.now()
        val s = current(ctx).add(today, helper, dollars)
        memo = s
        try {
            writeAtomic(file(ctx), s.toJson())
            if (helper == HelperConfig.CLAUDE_ID) writeAtomic(legacy(ctx), ClaudeSpend(s.day, s.of(today, helper)).toJson())
        } catch (t: Throwable) { RLog.w("helper_spend_save", t) }
        return s
    }
}

/** What the Assistant setup screen shows about the Claude app: installed, and which hand-off resolves (a, b, c). */
data class ClaudeAppFacts(val installed: Boolean, val appLink: Boolean, val webLink: Boolean, val send: Boolean)

/**
 * The Claude app (closed source; its intent filters are unverified): the hand-off intents and which of them resolve to
 * its package on this phone, checked at run time (resolveActivity with setPackage). Robot only prefills; you press
 * Send. Never accessibility, never reading the app in the background.
 */
object ClaudeAppIntents {
    fun installed(ctx: Context): Boolean = HandOffApps.installed(ctx, ClaudeAppLink.PACKAGE)

    fun intent(ctx: Context, way: ClaudeAppLink.Way, text: String): Intent? = when (way) {
        ClaudeAppLink.Way.APP_LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(ClaudeAppLink.appLink(text))).setPackage(ClaudeAppLink.PACKAGE)
        ClaudeAppLink.Way.WEB_LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(ClaudeAppLink.webLink(text))).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(ClaudeAppLink.PACKAGE)
        ClaudeAppLink.Way.SEND -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ClaudeAppLink.cap(text)).setPackage(ClaudeAppLink.PACKAGE)
        ClaudeAppLink.Way.LAUNCH -> ctx.packageManager.getLaunchIntentForPackage(ClaudeAppLink.PACKAGE)
    }

    fun resolves(ctx: Context, way: ClaudeAppLink.Way): Boolean = try {
        val i = intent(ctx, way, "test") ?: return false
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo?.packageName == ClaudeAppLink.PACKAGE }
    } catch (_: Exception) { false }

    fun facts(ctx: Context): ClaudeAppFacts {
        val installed = installed(ctx)
        return ClaudeAppFacts(installed, installed && resolves(ctx, ClaudeAppLink.Way.APP_LINK), installed && resolves(ctx, ClaudeAppLink.Way.WEB_LINK),
            installed && resolves(ctx, ClaudeAppLink.Way.SEND))
    }

    /** The way to hand off now (a, b, c, then d), or null when the app isn't installed. */
    fun choose(ctx: Context): ClaudeAppLink.Way? = ClaudeAppLink.choose(installed(ctx)) { resolves(ctx, it) }

    /** Way d: the question on the clipboard (your own words, so not marked sensitive) before the app opens. */
    fun copy(ctx: Context, text: String) {
        try {
            val clip = ClipData.newPlainText("Question for Claude", text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (t: Throwable) {
            RLog.w("claude_clip", t)
        }
    }
}
