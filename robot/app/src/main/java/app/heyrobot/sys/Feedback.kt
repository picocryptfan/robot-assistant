// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.feedback.FeedbackKind
import app.heyrobot.core.feedback.FeedbackPrefill
import app.heyrobot.core.feedback.FeedbackPrefs
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Main and voice processes (0.7). Send feedback's files and its ways out. feedback.json holds the number "Text it" opens
// your SMS app with (the main app writes it; not settings.json). The panel hands what you said after a feedback phrase to
// the page through feedback_prefill.json, a file of Robot's own, and opens the app with the kind only. Out, only what you
// tap: Android's share sheet (you pick the app and the person), the clipboard (marked sensitive, cleared 60 s on if it
// still holds the message), your SMS app with the number filled in, or a GitHub issue page in your browser. Robot sends
// nothing itself, has no network code here, and never logs the message: the voice log gets its kind, length and which
// diagnostics it holds.

object FeedbackFiles {
    private fun prefsFile(ctx: Context) = File(Paths.dir(ctx), FeedbackPrefs.FILE)
    private fun prefillFile(ctx: Context) = File(Paths.dir(ctx), FeedbackPrefill.FILE)

    /** [MAIN] The page's own settings (the number to text). */
    fun prefs(ctx: Context): FeedbackPrefs = FeedbackPrefs.fromJson(readOrNull(prefsFile(ctx)))

    /** [MAIN] Background thread. */
    fun savePrefs(ctx: Context, p: FeedbackPrefs) {
        try { writeAtomic(prefsFile(ctx), p.toJson()) } catch (t: Throwable) { RLog.w("feedback_prefs_save", t) }
    }

    /** [VOICE] What you said after the phrase, for the page to start your text with; nothing when you said nothing more. */
    fun prefill(ctx: Context, kind: FeedbackKind, text: String?) {
        val f = prefillFile(ctx)
        try {
            if (text.isNullOrBlank()) f.delete() else writeAtomic(f, FeedbackPrefill(kind, text.take(FeedbackPrefill.MAX_TEXT), System.currentTimeMillis()).toJson())
        } catch (t: Throwable) { RLog.w("feedback_prefill_save", t) }
    }

    /** [MAIN] The panel's words, once and only while fresh: the file is deleted as it is read. */
    fun takePrefill(ctx: Context, now: Long = System.currentTimeMillis()): FeedbackPrefill? {
        val f = prefillFile(ctx)
        val p = FeedbackPrefill.fromJson(readOrNull(f))
        f.delete()
        return p?.takeIf { it.fresh(now) }
    }

    /** [VOICE] Robot's app on Send feedback with [kind] (the words, if any, are in the prefill file). */
    fun openIntent(ctx: Context, kind: FeedbackKind): Intent = HandOffApps.robotPage(ctx, AppPages.FEEDBACK).putExtra(AppPages.EXTRA_FEEDBACK_KIND, kind.name)

    /** [MAIN] A feedback event in the main process's voice log (codes and numbers only). */
    fun log(ctx: Context, e: VoiceEvent, a: Int, b: Int = 0, c: Int = 0) {
        try {
            if (!android.app.Application.getProcessName().contains(':')) VoiceLog.init(Paths.voiceLogMain(ctx))
            VoiceLog.add(e, a, b, c)
            VoiceLog.flush()
        } catch (t: Throwable) { RLog.w("feedback_log", t) }
    }
}

/** [MAIN] The ways a message leaves Robot, each one you tap: none sends anything by itself. */
object FeedbackSend {
    /** Android's share sheet with the message as plain text (no subject: an SMS app would turn it into an MMS). */
    fun shareIntent(text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Send feedback with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** "Text it": your SMS app with [number] and the message filled in; you press Send there. */
    fun textIntent(number: String, text: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A GitHub issue page in your browser (core builds the link). */
    fun browserIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Starts [i]; false when no app takes it. */
    fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i)
        true
    } catch (t: Exception) {
        RLog.w("feedback_start", t)
        false
    }
}

/**
 * [MAIN] Copy: the message on the clipboard, marked sensitive (Android hides its preview), and cleared [CLEAR_MS] later if
 * the clipboard still holds it. Android lets an app read the clipboard only while it has the focus: when the time comes
 * with Robot in the background, the check waits for Robot's window to have the focus again (MainActivity).
 */
object FeedbackClipboard {
    const val CLEAR_MS = 60_000L

    /** The text copied and when, until it is cleared or replaced. */
    @Volatile private var pending: Pair<String, Long>? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun copy(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val clip = ClipData.newPlainText("Robot feedback", text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        pending = text to System.currentTimeMillis()
        main.postDelayed({ check(app) }, CLEAR_MS)
    }

    /** Clears the clipboard once [CLEAR_MS] have passed if it still holds the message; forgets it if something else replaced it. */
    fun check(ctx: Context, now: Long = System.currentTimeMillis()) {
        val (text, at) = pending ?: return
        if (now - at < CLEAR_MS) return
        try {
            val cm = ctx.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
            // Without the focus Android gives no clip (null): wait for the focus.
            if (!cm.hasPrimaryClip()) return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString() == text) cm.clearPrimaryClip()
            pending = null
        } catch (t: Throwable) {
            RLog.w("feedback_clip", t)
        }
    }
}
