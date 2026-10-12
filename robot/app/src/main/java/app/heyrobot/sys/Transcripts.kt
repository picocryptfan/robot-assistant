package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.RLog
import app.heyrobot.core.TranscriptStore
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Main and voice processes (0.5.0, docs/conversation-mode.md 6). Where conversation transcripts live: filesDir/
// transcripts/<id>/, credential-encrypted like the rest of Robot's files and excluded from backup (allowBackup=false,
// data_extraction_rules). While a session records one, the :voice process is its only writer; the main app renames and
// deletes the others, and after deleting tells the :voice process (Cmd.TRANSCRIPTS_DELETED), which then never writes
// that transcript again, live or recovering.

object Transcripts {
    @Volatile private var store: TranscriptStore? = null

    /** One store per process (it remembers what this process deleted). */
    fun store(ctx: Context): TranscriptStore = store ?: synchronized(this) {
        store ?: TranscriptStore(File(ctx.applicationContext.filesDir, app.heyrobot.core.TranscriptFiles.DIR)).also { store = it }
    }

    /** [MAIN] Deletes one transcript ([id]) or all of them (null), any exported copy, and tells the voice process. */
    fun delete(ctx: Context, id: String?): Int {
        val s = store(ctx)
        val n = if (id == null) s.deleteAll() else if (s.delete(id)) 1 else 0
        Exports.clear(ctx)
        log(ctx, app.heyrobot.core.VoiceEvent.CONV_DELETE, n, 0)
        Cmd.send(ctx, Cmd.TRANSCRIPTS_DELETED, Bundle().apply { id?.let { putString(Cmd.EXTRA_ID, it) } })
        return n
    }

    /** The transcript a session records right now (its heartbeat is fresh), or null. */
    fun live(ctx: Context, now: Long = System.currentTimeMillis()): String? =
        store(ctx).heartbeat()?.takeIf { now - it.beatAt < 3 * app.heyrobot.core.ConversationRules.BEAT_MS }?.id

    /** [MAIN] An export or a delete for the voice log (numbers only), in the main process's own log file. */
    fun log(ctx: Context, e: app.heyrobot.core.VoiceEvent, a: Int, b: Int = 0) {
        try {
            if (!android.app.Application.getProcessName().contains(':')) app.heyrobot.core.VoiceLog.init(Paths.voiceLogMain(ctx))
            app.heyrobot.core.VoiceLog.add(e, a, b)
            app.heyrobot.core.VoiceLog.flush()
        } catch (t: Throwable) { RLog.w("transcript_log", t) }
    }

    /** The voice process's record of "I'll tell people" (one writer: the voice process). */
    private fun consentFile(ctx: Context) = File(Paths.dir(ctx), ConsentRecord.FILE)

    fun consent(ctx: Context): ConsentRecord? = ConsentRecord.fromJson(readOrNull(consentFile(ctx)))

    /** [VOICE] You ticked "I'll tell people". */
    fun saveConsent(ctx: Context, at: Long = System.currentTimeMillis()) {
        try { writeAtomic(consentFile(ctx), ConsentRecord(at).toJson()) } catch (t: Throwable) { RLog.w("conv_consent_save", t) }
    }

    // By name: the main process never refers to the assistant's classes.
    const val ACTIVITY = "app.heyrobot.assistant.conversation.ConversationActivity"
    const val CHECK_ACTIVITY = "app.heyrobot.assistant.conversation.TranscriptionCheckActivity"
    const val ACTION_START = "app.heyrobot.action.TRANSCRIBE"
    const val ACTION_RESUME = "app.heyrobot.action.RESUME_TRANSCRIPT"
    const val ACTION_OPEN = "app.heyrobot.action.OPEN_TRANSCRIBE"
    /** Extras: how it was started (a core ConvEntry's name), a transcript id, and the face it starts with (a ConvMode's name). */
    const val EXTRA_ENTRY = "entry"
    const val EXTRA_ID = "id"
    const val EXTRA_MODE = "conv_mode"
    /** The voice process's summary screen (0.5.0, phase 5.6): Robot's own summary, or the stronger AI's card. */
    const val SUMMARY_ACTIVITY = "app.heyrobot.assistant.conversation.SummaryActivity"

    /**
     * [MAIN] "Transcribe" (or "Translate": [mode] "INTERPRET") in the app: the transcript screen, in the voice process,
     * starts the session.
     */
    fun startIntent(ctx: Context, entry: String, mode: String? = null): Intent = Intent(ACTION_START).setClassName(ctx.packageName, ACTIVITY)
        .putExtra(EXTRA_ENTRY, entry).apply { mode?.let { putExtra(EXTRA_MODE, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** [MAIN] The summary screen for transcript [id] (unlocked only: it checks). */
    fun summaryIntent(ctx: Context, id: String): Intent = Intent().setClassName(ctx.packageName, SUMMARY_ACTIVITY).putExtra(EXTRA_ID, id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openIntent(ctx: Context): Intent = Intent(ACTION_OPEN).setClassName(ctx.packageName, ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun checkIntent(ctx: Context): Intent = Intent().setClassName(ctx.packageName, CHECK_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * [MAIN] Exported transcripts (Markdown or text) for sharing: written to cacheDir/exports/ only for the share sheet,
 * served by [ExportProvider], and removed at the next export or when transcripts are deleted.
 */
object Exports {
    /** The manifest's `${applicationId}.exports` (unique on the phone, so 1.0 installs beside 0.9). */
    const val AUTHORITY = app.heyrobot.BuildConfig.APPLICATION_ID + ".exports"

    fun dir(ctx: Context): File = File(ctx.cacheDir, "exports")

    fun write(ctx: Context, name: String, text: String): File {
        clear(ctx)
        val d = dir(ctx).apply { mkdirs() }
        val f = File(d, name.replace(Regex("[/\\\\:*?\"<>|]"), "_"))
        writeAtomic(f, text)
        return f
    }

    fun clear(ctx: Context) {
        dir(ctx).listFiles().orEmpty().forEach { it.delete() }
    }
}

/** The main app's share provider for exported transcripts (cacheDir/exports only, read-only grants). */
class ExportProvider : androidx.core.content.FileProvider()
