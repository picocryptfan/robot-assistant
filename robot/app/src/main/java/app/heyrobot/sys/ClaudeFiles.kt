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
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.LocalDate

// Main and voice processes. Ask Claude's files and the Claude app's intents, without the API key: the key is encrypted
// and decrypted in the :voice process only (assistant/ClaudeKeys.kt). The main app reads whether a key is saved and its
// masked form, and can delete it; it never reads the key itself.

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

    /** "Delete key": the files and the Keystore key go; a key in a running client is dropped at its next turn. */
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

/** Today's spend on Claude: written by the :voice process after each reply, shown in Settings. */
object ClaudeSpendStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "claude_spend.json")

    fun load(ctx: Context): ClaudeSpend = ClaudeSpend.fromJson(readOrNull(file(ctx)))

    fun today(ctx: Context): Long = load(ctx).on(LocalDate.now()).micros

    /** [VOICE] */
    fun add(ctx: Context, dollars: Double): ClaudeSpend {
        val s = load(ctx).add(LocalDate.now(), dollars)
        try { writeAtomic(file(ctx), s.toJson()) } catch (t: Throwable) { RLog.w("claude_spend_save", t) }
        return s
    }
}

/** What the Assistant setup screen shows about the Claude app: installed, and which hand-off resolves (a, b, c). */
data class ClaudeAppFacts(val installed: Boolean, val appLink: Boolean, val webLink: Boolean, val send: Boolean)

/**
 * The Claude app (closed source; its intent filters are unverified): the hand-off intents and which of them resolve to
 * its package on this phone, checked at run time (resolveActivity with setPackage). Robot only prefills; the owner
 * presses Send. Never accessibility, never reading the app in the background.
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

    /** Way d: the question on the clipboard (the owner's own words, so not marked sensitive) before the app opens. */
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
