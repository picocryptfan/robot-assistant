// SPDX-License-Identifier: GPL-3.0-only
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
    /** The last Test translation speed (core MtSpeedReport): the main app writes it. */
    fun mtSpeed(ctx: Context) = File(dir(ctx), "mt_speed.json")
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

    /** Runs [block] on the settings thread once every save asked for so far is on disk (the voice process may read them). */
    fun afterSaves(block: () -> Unit) = writer.execute(block)

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
     * 0.7.0: a number that changes when a conversation is saved, added or deleted, by either process (each save renames a
     * new file into the folder, which changes the folder's time): the folder's time and how many files it holds. Cheap
     * enough to read once a second while the app shows (ui HistoryWatch).
     */
    fun stamp(ctx: Context): Long {
        val d = Paths.conversations(ctx)
        return d.lastModified() * 31 + (d.list()?.size ?: 0)
    }

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
    /** Explicit to this package and not exported (above), so 0.9 beside 1.0 never gets it: the name can stay. */
    const val ACTION = "app.heyrobot.CMD"
    /** The manifest's signature permission, named after the application ID since 1.0 (ai.robot_app.permission.INTERNAL). */
    const val PERMISSION = app.heyrobot.BuildConfig.APPLICATION_ID + ".permission.INTERNAL"
    const val SETTINGS = "settings"
    const val SPEAK_TEST = "speak_test"
    /** Voice models were installed or deleted: the voice process re-checks them. */
    const val VOICE_MODELS = "voice_models"
    /** "Try again" after a voice engine was turned off. */
    const val VOICE_RESET = "voice_reset"
    /** "Test voice speed": results come back in Paths.voiceBench. */
    const val VOICE_BENCH = "voice_bench"
    /**
     * A helper's key was deleted, or the helper was removed or changed (extra [EXTRA_ID]: the helper's id, which is not
     * secret): the voice process drops its client and ends that helper's conversation. ("claude_key" before 0.6.0.)
     */
    const val HELPER_KEY = "helper_key"
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
    /** Your "me" voiceprint was deleted (0.5.0, phase 5.3): a running session stops comparing lines with it. */
    const val VOICEPRINT_DELETED = "voiceprint_deleted"
    /**
     * 0.7: Settings › Memory changed what Robot remembers or how (an item, Delete all, a switch): the voice process drops
     * what it holds of memory and starts the live conversation again, so a deleted item can't come back in an answer.
     */
    const val MEMORY_CHANGED = "memory_changed"
    /**
     * 1.4: Robot Files changed in the main app (Delete them all, Start over, files_settings.json): :voice drops the index
     * it holds in RAM and reads the settings again (sys/FilesVault.kt).
     */
    const val FILES = "files"
    /** 1.4 F6: a filing notification's Undo, and its "File them" (extra [EXTRA_KEY]: the notification's token, never a file's id). */
    const val FILES_UNDO = "files_undo"
    const val FILES_FILE = "files_file"
    /** "Barge-in test" (extra [EXTRA_RUN], a core BargeRun's name): results come back in Paths.bargeTest. */
    const val BARGE_TEST = "barge_test"
    const val EXTRA_RUN = "run"
    /**
     * "Test wake words" is open (extra [EXTRA_ON]): wake words are logged, not acted on. Sent again every few seconds
     * while the screen shows; the voice process ends the test by itself when that stops.
     */
    const val WAKE_TEST = "wake_test"
    const val EXTRA_ON = "on"
    /** 0.8: Settings › Routines changed routines.json or routine_settings.json: the voice process reloads its triggers. */
    const val ROUTINES = "routines"
    /** 1.1: Settings › Skills changed skills.json, or a backup restored it: :voice reads it again. */
    const val SKILLS = "skills"
    /** 0.8: Settings › Headset or Car mode changed headset.json, car.json, places.json or devices.json: :voice reads them again. */
    const val HANDSFREE = "handsfree"
    /**
     * 0.8: a new message came with "Announce new messages" on (the main process's MessageTap): extra [EXTRA_KEY], the
     * conversation's key in RAM, and [EXTRA_DND], whether Do Not Disturb lets it through. Never its sender or words: :voice
     * asks the bridge for those, as for any read.
     */
    const val MESSAGE_NEW = "message_new"
    const val EXTRA_KEY = "key"
    const val EXTRA_DND = "dnd"
    /**
     * 0.9, Settings › Termux (extra [EXTRA_RUN]: "test", "folder" or "list"): the voice process runs that fixed set-up
     * command in Termux; what it found comes back in files/robot/termux_status.json. 1.0: "shizuku" with [EXTRA_ID], an
     * ADB fix's id (Settings › Advanced › ADB fixes): that fix's fixed command through Shizuku's rish.
     */
    const val TERMUX = "termux"

    fun send(ctx: Context, op: String, extras: Bundle = Bundle()) {
        try {
            ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("op", op).putExtras(extras), PERMISSION)
        } catch (t: Throwable) {
            RLog.w("cmd_send", t)
        }
    }
}

/**
 * A helper's connection window (assistant HelperConnectionActivity, in the :voice process, where keys are decrypted):
 * opened by class name from the main app, which never refers to the assistant's classes. [EXTRA_HELPER]: the helper's
 * id; [EXTRA_MODE]: [MODE_KEY] (add or replace its key, then Test connection) or [MODE_TEST] (Test connection only).
 */
object HelperWindow {
    const val CLASS = "app.heyrobot.assistant.HelperConnectionActivity"
    const val EXTRA_HELPER = "helper"
    const val EXTRA_MODE = "mode"
    const val MODE_KEY = "key"
    const val MODE_TEST = "test"
    const val MODE_ACTIONS = "actions"
}
