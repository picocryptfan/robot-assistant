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
import app.heyrobot.core.SaveWatch
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
        val ok = try { writeAtomic(file(ctx), s.toJson().toString(1)); true } catch (t: Throwable) { RLog.w("settings_save", t); false }
        if (saves.tell(ok)) saveFailed.trySend(Unit)
        Cmd.send(ctx, Cmd.SETTINGS)
    }

    private val saves = SaveWatch()

    /** A save failed (storage full, most likely): MainActivity says so, once; the old settings stay on disk. Held until it is read. */
    val saveFailed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
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

    /** Newest first (seal-history: through History's vault, sealed with Robot's own keys). */
    fun list(ctx: Context): List<StoredConversation> = HistoryFiles.vault(ctx).read().conversations

    /** History as it opens now, with how many conversations are kept aside (their key is gone). */
    fun read(ctx: Context): HistoryRead = HistoryFiles.vault(ctx).read()

    /** Every conversation for a backup or a restore; throws HistoryNotWhole while one can't be opened (HistoryVault.whole). */
    fun whole(ctx: Context): List<StoredConversation> = HistoryFiles.vault(ctx).whole()

    fun load(ctx: Context, id: String): StoredConversation? = HistoryFiles.vault(ctx).load(id)

    /**
     * [VOICE] The panel saves after each reply: pictures and screen text only as "Keep screenshots and photos in history"
     * kept them (ChatTurn.picture and screenText). False: not saved (storage full, most likely), and the panel says so.
     */
    fun save(ctx: Context, c: StoredConversation): Boolean = HistoryFiles.vault(ctx).save(c)

    fun delete(ctx: Context, id: String) = HistoryFiles.vault(ctx).delete(id)

    /** How many conversations are kept aside because Android removed their key (History says so; Delete all removes them). */
    fun keptAside(ctx: Context): Int = HistoryFiles.vault(ctx).aside()

    /** History's card, Delete these: only the conversations kept aside and a newer Robot's. */
    fun deleteAside(ctx: Context) = HistoryFiles.vault(ctx).deleteAside()

    /** Nothing in History's folder: no conversation, sealed or not, and none kept aside. */
    fun none(ctx: Context): Boolean = Paths.conversations(ctx).list().isNullOrEmpty()

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

    /** History's Delete all: the record of the timers you asked for goes with it (fix-timers-store). */
    fun deleteAll(ctx: Context) {
        HistoryFiles.vault(ctx).deleteAll()
        RobotTimers.clear(ctx)
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
    /**
     * 1.4 Lists: Settings › Lists changed the lists or lists_settings.json (Delete all lists, Start over, a switch): :voice
     * drops the lists it holds in RAM and reads the settings again (sys/ListsVault.kt).
     */
    const val LISTS = "lists"
    /** 1.4 F6: a filing notification's Undo, and its "File them" (extra [EXTRA_KEY]: the notification's token, never a file's id). */
    const val FILES_UNDO = "files_undo"
    const val FILES_FILE = "files_file"
    /**
     * The everyday set (docs/plans/everyday-set-plan.md §2.4): everyday_settings.json changed in the main app (Settings, a
     * restore's finish): :voice reads it again (sys EverydaySettingsStore).
     */
    const val EVERYDAY = "everyday"
    /**
     * 1.5 L6: :link's desk changed (a card waits, Robot handled something, a pairing changed): :voice asks LinkService for
     * the notices (DESK_NOTICES). Sent by :link with no extra: nothing of a request or a pairing travels in it.
     */
    const val LINK_DESK = "link_desk"
    /** 1.5 L6: a Robot to Robot card notification's Yes or Decline (extra [EXTRA_KEY]: the notification's token, never a card's id). */
    const val LINK_ANSWER = "link_answer"
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
    /**
     * 1.1.2, FA8g: from :lock, a phone lock started. A conversation session that runs stops at once, keeping what it has
     * (phone-lock-design §5.2); its own tick checks each second too, should this be missed.
     */
    const val PHONE_LOCK = "phone_lock"

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
