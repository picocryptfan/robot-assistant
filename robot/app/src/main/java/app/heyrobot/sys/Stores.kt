package app.heyrobot.sys

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.NearestAnswer
import app.heyrobot.core.NearestOnline
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.VoiceFiles
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Main and voice processes. App-private storage (credential-encrypted, available after the first unlock).
// Each file has one writer: settings.json the main app, conversations the panel (the main app only
// deletes them), voice_status.json, nearest_answer.json and the boot markers the voice process.

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
    /** The panel's lasting answer to the nearest place's first-use card (core NearestAnswer): the voice process writes it. */
    fun nearestAnswer(ctx: Context) = File(dir(ctx), "nearest_answer.json")

    fun bootCount(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) { -1 }
}

object SettingsStore {
    private fun file(ctx: Context) = File(Paths.dir(ctx), "settings.json")

    /**
     * One thread writes every save, in the order they were asked for. Each change used to start its own background
     * write, so two quick taps (a wake phrase off, then another on) could finish out of order and leave the older
     * settings on disk, which the voice process then kept.
     */
    private val writer: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "robot-settings").apply { isDaemon = true } } }

    fun load(ctx: Context): RobotSettings = RobotSettings.fromJson(readOrNull(file(ctx)))

    /** [MAIN] Saves on the settings thread, after any earlier save, and tells the voice process to re-read them. */
    fun save(ctx: Context, s: RobotSettings) {
        val app = ctx.applicationContext
        writer.execute { write(app, s) }
    }

    private fun write(ctx: Context, s: RobotSettings) {
        try { writeAtomic(file(ctx), s.toJson().toString(1)) } catch (t: Throwable) { RLog.w("settings_save", t) }
        Cmd.send(ctx, Cmd.SETTINGS)
    }
}

/**
 * "Allow always", or "No" with "Don't ask again", on the nearest place's first-use card (0.4.0). [VOICE] writes it (the
 * main app writes settings.json); both read it: it counts while newer than Settings' own choice (core Nearby.mode).
 */
object NearestAnswers {
    fun load(ctx: Context): NearestAnswer? = NearestAnswer.fromJson(readOrNull(Paths.nearestAnswer(ctx)))

    fun save(ctx: Context, choice: NearestOnline) {
        try { writeAtomic(Paths.nearestAnswer(ctx), NearestAnswer(choice, System.currentTimeMillis()).toJson()) } catch (t: Throwable) { RLog.w("nearest_answer_save", t) }
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

    /**
     * [VOICE] The panel saves after each reply: pictures and screen text only as "Keep screenshots and photos in history"
     * kept them (ChatTurn.picture and screenText).
     */
    fun save(ctx: Context, c: StoredConversation) {
        val f = file(ctx, c.id) ?: return
        try { writeAtomic(f, ConversationCodec.toJson(c)) } catch (t: Throwable) { RLog.w("history_save", t) }
    }

    fun delete(ctx: Context, id: String) { file(ctx, id)?.delete() }

    /**
     * Settings › Safety & privacy › "Remove saved screenshots and photos": every saved conversation without the pictures
     * and screen text "Keep screenshots and photos in history" kept. Returns how many conversations changed.
     */
    fun removeMedia(ctx: Context): Int = list(ctx).mapNotNull(ConversationCodec::withoutMedia).onEach { save(ctx, it) }.size

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
    /** The Claude API key was deleted: the voice process drops its client and ends Claude mode. */
    const val CLAUDE_KEY = "claude_key"
    /**
     * "Remove saved screenshots and photos" ran: the voice process drops them from the conversation it still holds, which
     * would otherwise write them back with its next save.
     */
    const val MEDIA_REMOVED = "media_removed"
    /**
     * Conversations were deleted from History (extra [EXTRA_ID]: the one deleted; none: all of them): if the panel still
     * holds one of them, it starts a new conversation, or its next save would write the deleted one back.
     */
    const val CONVERSATIONS_DELETED = "conversations_deleted"
    const val EXTRA_ID = "id"
    /**
     * Transcripts were deleted in the app (0.5.0; extra [EXTRA_ID]: the one deleted; none: all of them): a session
     * recording one of them stops without writing another line, and a Resume offer for one is taken down, so nothing
     * writes a deleted transcript back.
     */
    const val TRANSCRIPTS_DELETED = "transcripts_deleted"
    /** "Barge-in test" (extra [EXTRA_RUN], a core BargeRun's name): results come back in Paths.bargeTest. */
    const val BARGE_TEST = "barge_test"
    const val EXTRA_RUN = "run"
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
