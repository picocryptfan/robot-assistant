package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.VoiceFiles
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Main and voice processes. App-private storage (credential-encrypted, available after the first unlock).
// Each file has one writer: settings.json the main app, conversations the panel (the main app only
// deletes them), voice_status.json and the boot markers the voice process.

object Paths {
    fun dir(ctx: Context): File = File(ctx.filesDir, "robot").apply { mkdirs() }
    fun marker(ctx: Context, name: String) = File(dir(ctx), "$name.marker")
    fun conversations(ctx: Context): File = File(ctx.filesDir, "conversations").apply { mkdirs() }
    fun voiceStatus(ctx: Context) = File(dir(ctx), "voice_status.json")
    /** Written by the voice process: its speech engines' states and the voice speed test (core VoiceStatus.kt). */
    fun voiceEngines(ctx: Context) = File(dir(ctx), VoiceFiles.ENGINES)
    fun voiceBench(ctx: Context) = File(dir(ctx), VoiceFiles.BENCH)
    /** The voice log (core VoiceLog.kt): written by the voice process, read by Settings › Voice › Voice log. */
    fun voiceLog(ctx: Context) = File(dir(ctx), VoiceFiles.LOG)
    /** The main process's own few voice-log events (the recognizer repair): one writer per file. */
    fun voiceLogMain(ctx: Context) = File(dir(ctx), VoiceFiles.LOG_MAIN)
    fun bargeTest(ctx: Context) = File(dir(ctx), VoiceFiles.BARGE)

    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) { -1 }
}

object SettingsStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "settings.json")

    fun load(ctx: Context): RobotSettings = RobotSettings.fromJson(readOrNull(file(ctx)))

    /** [MAIN] Saves and tells the voice process to re-read them. */
    fun save(ctx: Context, s: RobotSettings) {
        try { writeAtomic(file(ctx), s.toJson().toString(1)) } catch (t: Throwable) { RLog.w("settings_save", t) }
        Cmd.send(ctx, Cmd.SETTINGS)
    }
}

object ConversationStore {
    private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun validId(id: String?): Boolean = id != null && ID.matches(id)

    private fun file(ctx: Context, id: String): File? = if (validId(id)) File(Paths.conversations(ctx), "$id.json") else null

    /** Newest first. */
    fun list(ctx: Context): List<StoredConversation> =
        Paths.conversations(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> readOrNull(f)?.let(ConversationCodec::fromJson) }
            .sortedByDescending { it.updated }

    fun load(ctx: Context, id: String): StoredConversation? = file(ctx, id)?.let(::readOrNull)?.let(ConversationCodec::fromJson)

    /** [VOICE] The panel saves after each reply (never pictures or screen text: ChatTurn has none). */
    fun save(ctx: Context, c: StoredConversation) {
        val f = file(ctx, c.id) ?: return
        try { writeAtomic(f, ConversationCodec.toJson(c)) } catch (t: Throwable) { RLog.w("history_save", t) }
    }

    fun delete(ctx: Context, id: String) { file(ctx, id)?.delete() }

    fun deleteAll(ctx: Context) {
        Paths.conversations(ctx).listFiles().orEmpty().forEach { it.delete() }
    }
}

/**
 * Commands from the main app to the voice process: an explicit broadcast to this package, received by
 * a receiver the voice process registers with RECEIVER_NOT_EXPORTED and Robot's signature permission.
 */
object Cmd {
    const val ACTION = "app.heyrobot.CMD"
    const val PERMISSION = "app.heyrobot.permission.INTERNAL"
    const val SETTINGS = "settings"
    const val SPEAK_TEST = "speak_test"
    /** Voice models were installed or deleted: the voice process re-checks them. */
    const val VOICE_MODELS = "voice_models"
    /** "Try again" after a voice engine was turned off. */
    const val VOICE_RESET = "voice_reset"
    /** "Test voice speed": results come back in Paths.voiceBench. */
    const val VOICE_BENCH = "voice_bench"
    /** "Barge-in test" (extra [EXTRA_SILENT]): results come back in Paths.bargeTest. */
    const val BARGE_TEST = "barge_test"
    const val EXTRA_SILENT = "silent"
    /**
     * "Test wake words" is open (extra [EXTRA_ON]): wake words are logged, not acted on. Sent again every few seconds
     * while the screen shows; the voice process ends the test by itself when that stops.
     */
    const val WAKE_TEST = "wake_test"
    const val EXTRA_ON = "on"

    fun send(ctx: Context, op: String, extras: Bundle = Bundle()) {
        try {
            ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("op", op).putExtras(extras), PERMISSION)
        } catch (t: Throwable) {
            RLog.w("cmd_send", t)
        }
    }
}
